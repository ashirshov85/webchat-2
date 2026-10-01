import http from 'k6/http'
import sse from 'k6/x/sse'
import { check, fail, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'
import exec from 'k6/execution'

/**
 * 006-group-chats T067 — group chats load smoke (research.md §9, quickstart §4;
 * SC-001/SC-003/SC-004/SC-005/SC-006/SC-007).
 *
 * Five independent scenarios over dedicated seeded users (one run covers all):
 *
 *   A lifecycle (SC-005) — K6_VUS VUs in parallel, each driving the full
 *     "create a group + add up to 10 members from contacts" cycle:
 *     №27 create (empty roster) -> №31 batch add -> №28 verify. The cycle
 *     clock covers exactly the SC-005 wording (group ops only — contacts are
 *     a per-run fixture). Threshold: p90 of group_create_cycle_ms <= 60 s at
 *     the default K6_VUS=10 / K6_GROUP_SIZE=10.
 *   B messaging (SC-001) — one group, rotating flood-safe senders and one
 *     audit member with an open SSE stream; every №16 send is matched to its
 *     message.created frame at the member. Threshold: p95 of
 *     group_delivered_ms (send -> frame) <= 2 s; 0 losses/duplicates after
 *     the final history pull.
 *   C changes (SC-004) — owner drives metadata/role/membership changes
 *     (№29 PATCH, №34 role x2, №31 add, №32 remove) while the online member
 *     watches SSE; each change is matched to its group.* frame. Threshold:
 *     p95 of group_change_visible_ms (op -> frame) <= 2 s.
 *   D authz (SC-003) — an authenticated non-member and a kicked (removed)
 *     member run the full denial matrix: №28-№35 -> the single 404
 *     group_not_found, №15/№16/№17 -> 403 not_participant (004 semantics),
 *     №26 sync / №12 chat list carry no trace of the group. Threshold:
 *     group_authz_violations_total == 0.
 *   E large smoke (SC-006/SC-007) — a group of K6_GROUP_SIZE members
 *     (200 for the documented smoke): №27 fill of the whole roster, a wave
 *     of group messages audited for exactly-once and seq order (SSE frames
 *     + final №15 history pull), №32 kick and №33 leave with
 *     group.you_removed timed at the removed member, then a quiet window
 *     proving no group frames keep arriving. Thresholds:
 *     group_you_removed_ms p(100) <= 5 s, 0 losses/duplicates/order
 *     violations, 0 quiet-window leaks.
 *
 * Full-scale peak budgets (1M CCU / 100k msg/s) stay a platform task
 * (feature 016, ROADMAP) — this file is the groups smoke of quickstart §4.
 *
 * The SSE client needs the k6/x/sse extension (stock k6 buffers HTTP bodies,
 * grafana/k6#746): build the runner image first —
 *   docker build -t webchat-k6 load/k6
 *   docker run --rm -i --network host webchat-k6 run - < load/k6/group-chats.js
 * Env: K6_BASE_URL (default http://localhost:8080), K6_MAILPIT_URL (default
 * http://localhost:8025), K6_VUS (default 10 — scenario A parallelism),
 * K6_GROUP_SIZE (default 10 — scenario A caps it at 10 per the SC-005 shape;
 * set 200 for the scenario E smoke), K6_MESSAGE_RPS (default 4 — scenario B
 * pacing target; senders are sized to stay under the 30 msg/min flood cap).
 *
 * Seeding follows the 002/004 smoke pattern: register -> Mailpit letter ->
 * confirm -> set password; every user gets a distinct source IP to stay off
 * the per-IP register limits. Sends are strictly sequential per chat (one VU
 * drives a group's wave), so seq order == publish order for the audits.
 */

const BASE_URL = __ENV.K6_BASE_URL || 'http://localhost:8080'
const MAILPIT_URL = __ENV.K6_MAILPIT_URL || 'http://localhost:8025'

const API_BASE = `${BASE_URL}/api/v1`
const REGISTER_ENDPOINT = `${API_BASE}/auth/register`
const CONFIRM_ENDPOINT = `${API_BASE}/auth/register/confirm`
const SET_PASSWORD_ENDPOINT = `${API_BASE}/auth/register/password`
const LOGIN_ENDPOINT = `${API_BASE}/auth/login`
const REFRESH_ENDPOINT = `${API_BASE}/auth/refresh`
const EVENTS_ENDPOINT = `${API_BASE}/users/me/events`
const SYNC_ENDPOINT = `${API_BASE}/users/me/sync`
const GROUPS_ENDPOINT = `${API_BASE}/groups`
const CHATS_ENDPOINT = `${API_BASE}/chats`
const CONTACTS_ENDPOINT = `${API_BASE}/contacts`

// A: two waves of parallel create cycles -> 2 x K6_VUS p90 samples.
const LIFECYCLE_WAVES = 2
// B: one stream round batches this many sends; a small batch keeps the SSE
// frame-dispatch lag (frames flush when the open handler returns) well under
// the 2 s budget while amortizing the stream handshake.
const MESSAGE_PER_STREAM = 4
// C: rounds x 5 change kinds (metadata, grant, revoke, add, remove).
const CHANGE_ROUNDS = 5
// E: the quiet window after you_removed must carry no group frames (SC-006).
const QUIET_WINDOW_S = 6
const SSE_TIMEOUT_CHANGE = '10s'
const SSE_TIMEOUT_MESSAGE = '20s'
const SSE_TIMEOUT_WAVE = '30s'
const VERIFICATION_LINK_PATTERN = /confirm-registration\?token=([A-Za-z0-9_-]{43})/
const ACCESS_TOKEN_REFRESH_MARGIN_MS = 60_000

function parseIntEnv(name, fallback, min) {
  const raw = __ENV[name]
  if (raw === undefined || String(raw).trim() === '') return fallback
  const value = parseInt(String(raw), 10)
  if (!Number.isFinite(value) || value < min) fail(`invalid ${name} "${raw}" (expected an integer >= ${min})`)
  return value
}

function parseFloatEnv(name, fallback, min) {
  const raw = __ENV[name]
  if (raw === undefined || String(raw).trim() === '') return fallback
  const value = parseFloat(String(raw))
  if (!Number.isFinite(value) || value < min) fail(`invalid ${name} "${raw}" (expected a number >= ${min})`)
  return value
}

const SCENARIO_VUS = parseIntEnv('K6_VUS', 10, 1)
const GROUP_SIZE = parseIntEnv('K6_GROUP_SIZE', 10, 5)
const MESSAGE_RPS = parseFloatEnv('K6_MESSAGE_RPS', 4, 0.1)

// SC-005 is defined for "up to 10 members" per group; K6_GROUP_SIZE=200
// reconfigures scenario E (the SC-007 smoke), not the SC-005 shape.
const LIFECYCLE_MEMBERS = Math.min(GROUP_SIZE, 10)
// Flood cap is 30 msg/min per sender (0.5/s); 0.4 target keeps headroom.
const MESSAGE_SENDERS = Math.max(2, Math.ceil(MESSAGE_RPS / 0.4))
const MESSAGE_TOTAL = Math.max(40, Math.ceil(MESSAGE_RPS * 15))
const LARGE_TOTAL = Math.min(GROUP_SIZE, 200)
const LARGE_MEMBERS = LARGE_TOTAL - 1
const LARGE_SENDERS = Math.min(4, Math.max(1, LARGE_MEMBERS - 2))
const LARGE_WAVE = Math.min(80, Math.max(20, LARGE_SENDERS * 10))

if (LARGE_MEMBERS < 3) fail(`K6_GROUP_SIZE must be >= 5 (scenario E needs an owner plus >= 3 members)`)
if (MESSAGE_SENDERS + 2 > 200) fail(`K6_MESSAGE_RPS=${MESSAGE_RPS} needs > 200 users for scenario B`)

const LIFECYCLE_USERS = SCENARIO_VUS * (1 + LIFECYCLE_MEMBERS)
const MESSAGE_USERS = MESSAGE_SENDERS + 1
const CHANGE_USERS = 4
const AUTHZ_USERS = 4
const LARGE_USERS = LARGE_TOTAL
const TOTAL_USERS = LIFECYCLE_USERS + MESSAGE_USERS + CHANGE_USERS + AUTHZ_USERS + LARGE_USERS

// The backend outbox drips verification letters (5 s poll, sequential SMTP
// sends), so the Mailpit wait scales with the roster: ~2.5 s per seeded user,
// floor 5 minutes.
const MAILPIT_WAIT_MS = Math.max(300_000, TOTAL_USERS * 2500)

// k6 re-evaluates module scope per VU: Date.now()-derived values differ
// between setup() and scenario executors, so run identity travels via setup data.
const runId = Date.now().toString(36)

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 204, 403, 404, 409))

// ---------------------------------------------------------------- metrics ---

const groupCreateCycleMs = new Trend('group_create_cycle_ms')
const groupDeliveredMs = new Trend('group_delivered_ms')
const groupChangeVisibleMs = new Trend('group_change_visible_ms')
const groupYouRemovedMs = new Trend('group_you_removed_ms')
const largeFillMs = new Trend('large_group_fill_ms')

const authzDenied = new Counter('group_authz_denied_total')
const authzViolations = new Counter('group_authz_violations_total')
const changeFramesMissed = new Counter('group_change_frames_missed_total')
const deliveryFramesMissed = new Counter('group_delivery_frames_missed_total')
const groupLosses = new Counter('group_losses_total')
const groupDuplicates = new Counter('group_duplicates_total')
const groupOrderViolations = new Counter('group_order_violations_total')
const groupWireRedelivery = new Counter('group_wire_redelivery_total')
const quietViolations = new Counter('group_quiet_violations_total')

export const options = {
  scenarios: {
    lifecycle: {
      executor: 'shared-iterations',
      vus: SCENARIO_VUS,
      iterations: SCENARIO_VUS * LIFECYCLE_WAVES,
      exec: 'lifecycleCycle',
      gracefulStop: '30s',
    },
    messaging: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'messagingLoop',
      gracefulStop: '30s',
    },
    changes: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'changesLoop',
      gracefulStop: '30s',
    },
    authz: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'authzMatrix',
      gracefulStop: '30s',
    },
    large: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'largeGroupSmoke',
      gracefulStop: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    'http_reqs{status:/^5/}': ['count==0'],
    checks: ['rate==1'],
    group_create_cycle_ms: ['p(90)<60000'],
    group_delivered_ms: ['p(95)<2000'],
    group_change_visible_ms: ['p(95)<2000'],
    group_you_removed_ms: ['p(100)<5000'],
    group_authz_violations_total: ['count==0'],
    group_change_frames_missed_total: ['count==0'],
    group_delivery_frames_missed_total: ['count==0'],
    group_losses_total: ['count==0'],
    group_duplicates_total: ['count==0'],
    group_order_violations_total: ['count==0'],
    group_quiet_violations_total: ['count==0'],
  },
  setupTimeout: '25m',
}

// --------------------------------------------------------------- helpers ---

function jsonHeaders(sourceIp) {
  const headers = { 'Content-Type': 'application/json' }
  if (sourceIp) headers['X-Forwarded-For'] = sourceIp
  return headers
}

function postJson(url, payload, sourceIp) {
  return http.post(url, JSON.stringify(payload), { headers: jsonHeaders(sourceIp) })
}

function safeJson(value) {
  try {
    return typeof value === 'string' ? JSON.parse(value) : value
  } catch (error) {
    return null
  }
}

function bearerHeaders(session) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${session.accessToken}` }
}

function openSession(seed, password) {
  // The login limit is 10/min per source IP (002): the seeded virtual IPs
  // (X-Forwarded-For, register pattern) give every user its own bucket.
  const response = postJson(LOGIN_ENDPOINT, { identifier: seed.username, password }, seed.sourceIp)
  if (response.status !== 200) fail(`login ${seed.username} -> ${response.status} ${response.body}`)
  const body = response.json()
  return {
    username: seed.username,
    userId: body.user.id,
    sourceIp: seed.sourceIp,
    accessToken: body.accessToken,
    refreshToken: body.refreshToken,
    expiresAt: Date.now() + body.expiresInSec * 1000,
  }
}

function refreshIfNeeded(session) {
  if (Date.now() < session.expiresAt - ACCESS_TOKEN_REFRESH_MARGIN_MS) return
  const response = postJson(REFRESH_ENDPOINT, { refreshToken: session.refreshToken }, session.sourceIp)
  if (response.status !== 200) fail(`refresh ${session.username} -> ${response.status} ${response.body}`)
  const body = response.json()
  session.accessToken = body.accessToken
  session.refreshToken = body.refreshToken
  session.expiresAt = Date.now() + body.expiresInSec * 1000
}

function describeSseError(error) {
  try {
    if (error && typeof error.error === 'function') return String(error.error())
  } catch (ignored) {
    // fall through to coercion below
  }
  return String(error)
}

// RFC 9457 Problem codes live in errors: {field: [code, ...]} (002 convention).
function problemCodes(response) {
  const body = safeJson(response.body)
  if (!body || typeof body.errors !== 'object' || !body.errors) return []
  const codes = []
  for (const field of Object.keys(body.errors)) {
    for (const code of body.errors[field] || []) codes.push(code)
  }
  return codes
}

function addContact(session, userId) {
  const response = http.post(CONTACTS_ENDPOINT, JSON.stringify({ userId }), {
    headers: bearerHeaders(session),
    tags: { op: 'contact_add' },
  })
  if (response.status !== 200 && response.status !== 201) {
    fail(`add contact ${session.username} -> ${userId}: ${response.status} ${response.body}`)
  }
}

function createGroup(session, title, memberUserIds, opTag) {
  const payload = { title }
  if (memberUserIds && memberUserIds.length > 0) payload.memberUserIds = memberUserIds
  const response = http.post(GROUPS_ENDPOINT, JSON.stringify(payload), {
    headers: bearerHeaders(session),
    tags: { op: opTag || 'group_create' },
  })
  if (response.status !== 201) fail(`create group by ${session.username} -> ${response.status} ${response.body}`)
  const body = safeJson(response.body) || {}
  if (!body.chatId) fail(`create group by ${session.username} returned no chatId: ${response.body}`)
  return body
}

function addGroupMembers(session, chatId, userIds) {
  return http.post(`${GROUPS_ENDPOINT}/${chatId}/members`, JSON.stringify({ userIds }), {
    headers: bearerHeaders(session),
    tags: { op: 'group_add_members' },
  })
}

function getGroupView(session, chatId) {
  return http.get(`${GROUPS_ENDPOINT}/${chatId}`, { headers: bearerHeaders(session), tags: { op: 'group_get' } })
}

function patchGroup(session, chatId, patch) {
  return http.patch(`${GROUPS_ENDPOINT}/${chatId}`, JSON.stringify(patch), {
    headers: bearerHeaders(session),
    tags: { op: 'group_patch' },
  })
}

function setMemberRole(session, chatId, userId, role) {
  return http.put(`${GROUPS_ENDPOINT}/${chatId}/members/${userId}/role`, JSON.stringify({ role }), {
    headers: bearerHeaders(session),
    tags: { op: 'group_set_role' },
  })
}

function removeGroupMember(session, chatId, userId) {
  // http.del(url, body, params): the body slot must stay null, otherwise k6
  // form-encodes the params object and the auth header never reaches the wire.
  return http.del(`${GROUPS_ENDPOINT}/${chatId}/members/${userId}`, null, {
    headers: bearerHeaders(session),
    tags: { op: 'group_kick' },
  })
}

function leaveGroup(session, chatId) {
  return http.del(`${GROUPS_ENDPOINT}/${chatId}/membership`, null, {
    headers: bearerHeaders(session),
    tags: { op: 'group_leave' },
  })
}

function sendGroupMessage(session, chatId, clientMessageId, text, opTag) {
  return http.post(`${CHATS_ENDPOINT}/${chatId}/messages`, JSON.stringify({ clientMessageId, text }), {
    headers: bearerHeaders(session),
    tags: { op: opTag || 'group_send' },
  })
}

function sendAccepted(response) {
  return response.status === 201 || response.status === 200
}

// --------------------------------------------------------------- seeding ---

function mailpitMessageSummaries(limit) {
  const response = http.get(`${MAILPIT_URL}/api/v1/messages?limit=${limit}`)
  if (response.status !== 200) fail(`Mailpit API ${MAILPIT_URL} answered ${response.status}`)
  try {
    return response.json().messages || []
  } catch (error) {
    fail(`Mailpit API ${MAILPIT_URL} returned a non-JSON body: ${error}`)
  }
}

function seedAndConfirmUsers(prefix, count, seedPassword) {
  const users = []
  for (let i = 0; i < count; i += 1) {
    const user = {
      username: `${prefix}${runId}u${i}`,
      email: `${prefix}.${runId}.${i}@example.com`,
      // Distinct source IPs keep the sequential registrations off the per-IP
      // register limits (002 pattern).
      sourceIp: `198.18.${Math.floor(i / 254) % 254}.${(i % 254) + 1}`,
    }
    const registered = postJson(REGISTER_ENDPOINT, { username: user.username, email: user.email }, user.sourceIp)
    if (registered.status !== 202) {
      fail(`seed register ${user.username} -> ${registered.status} ${registered.body}`)
    }
    users.push(user)
  }

  const deadline = Date.now() + MAILPIT_WAIT_MS
  for (;;) {
    for (const summary of mailpitMessageSummaries(Math.min(1000, count + 250))) {
      for (const recipient of summary.To || []) {
        const user = users.find(u => u.email === recipient.Address && !u.verificationLetterId)
        if (user) user.verificationLetterId = summary.ID
      }
    }
    if (users.every(user => user.verificationLetterId)) break
    if (Date.now() > deadline) {
      const missing = users.filter(user => !user.verificationLetterId).map(user => user.email).join(', ')
      fail(`verification letters missing in Mailpit for ${missing}`)
    }
    sleep(2)
  }

  for (const user of users) {
    const letter = http.get(`${MAILPIT_URL}/api/v1/message/${user.verificationLetterId}`)
    const match = ((letter.json() || {}).Text || '').match(VERIFICATION_LINK_PATTERN)
    if (!match) fail(`verification link not found in the letter for ${user.email}`)
    const confirmed = postJson(CONFIRM_ENDPOINT, { token: match[1] }, user.sourceIp)
    if (confirmed.status !== 200) fail(`seed confirm ${user.email} -> ${confirmed.status} ${confirmed.body}`)
    const setupToken = (confirmed.json() || {}).setupToken
    const passwordSet = postJson(
      SET_PASSWORD_ENDPOINT,
      { setupToken, password: seedPassword, confirmPassword: seedPassword },
      user.sourceIp,
    )
    if (passwordSet.status !== 204) fail(`seed password ${user.email} -> ${passwordSet.status} ${passwordSet.body}`)
  }
  return users
}

export function setup() {
  const seedPassword = `K6grp-${runId}-Load1`
  const users = seedAndConfirmUsers('k6grp', TOTAL_USERS, seedPassword)
  let cursor = 0
  const next = () => users[cursor++]
  const take = count => users.slice(cursor, (cursor += count))

  const lifecyclePlans = []
  for (let i = 0; i < SCENARIO_VUS; i += 1) {
    lifecyclePlans.push({ owner: next(), members: take(LIFECYCLE_MEMBERS) })
  }
  return {
    seedPassword,
    lifecycle: { plans: lifecyclePlans, waves: LIFECYCLE_WAVES },
    messaging: { senders: take(MESSAGE_SENDERS), receiver: next() },
    changes: { owner: next(), admin: next(), observer: next(), spare: next() },
    authz: { owner: next(), member: next(), kicked: next(), outsider: next() },
    large: {
      owner: next(),
      members: take(LARGE_MEMBERS),
      total: LARGE_TOTAL,
      senders: LARGE_SENDERS,
      wave: LARGE_WAVE,
    },
  }
}

// ------------------------------------------------------ exactly-once audit ---
// Mirrors the 005 audit semantics: `expected` is what the senders got accepted
// (201/200), `ids` is what the receiver actually rendered (applied exactly
// once — wire re-delivery is deduped by message.id and counted separately).

function newAudit() {
  return { chats: {} }
}

function chatAudit(audit, chatId) {
  if (!audit.chats[chatId]) {
    audit.chats[chatId] = { ids: new Set(), expected: new Map(), lastSeq: 0, violations: 0 }
  }
  return audit.chats[chatId]
}

function recordSent(audit, chatId, messageId, seq) {
  chatAudit(audit, chatId).expected.set(messageId, seq)
}

function applyMessages(audit, chatId, messages) {
  const chat = chatAudit(audit, chatId)
  for (const message of messages) {
    if (!message || !message.id) continue
    if (chat.ids.has(message.id)) {
      groupWireRedelivery.add(1)
      continue
    }
    if (typeof message.seq === 'number' && message.seq <= chat.lastSeq) chat.violations += 1
    chat.ids.add(message.id)
    if (typeof message.seq === 'number' && message.seq > chat.lastSeq) chat.lastSeq = message.seq
  }
}

function finalAudit(audit) {
  for (const chatId of Object.keys(audit.chats)) {
    const chat = audit.chats[chatId]
    for (const id of chat.expected.keys()) {
      if (!chat.ids.has(id)) groupLosses.add(1)
    }
    for (const id of chat.ids) {
      if (!chat.expected.has(id)) groupDuplicates.add(1)
    }
    if (chat.violations > 0) groupOrderViolations.add(chat.violations)
  }
}

function catchUpHistory(session, chatId, audit) {
  refreshIfNeeded(session)
  let after = 0
  for (;;) {
    const response = http.get(`${CHATS_ENDPOINT}/${chatId}/messages?after=${after}&limit=50`, {
      headers: bearerHeaders(session),
      tags: { op: 'history_page' },
    })
    if (response.status !== 200) fail(`history page -> ${response.status} ${response.body}`)
    const page = safeJson(response.body) || {}
    applyMessages(audit, chatId, page.messages || [])
    if (typeof page.nextAfter !== 'number' || page.nextAfter < 1) break
    after = page.nextAfter
  }
}

// --------------------------------------------- scenario A: lifecycle (SC-005) ---

export function lifecycleCycle(data) {
  // iterationInTest is 0-based and unique per scenario iteration (k6 v2);
  // the modulo spread gives every plan exactly LIFECYCLE_WAVES runs.
  const iteration = exec.scenario.iterationInTest
  const plans = data.lifecycle.plans
  const plan = plans[((iteration % plans.length) + plans.length) % plans.length]
  const wave = iteration + 1

  const owner = openSession(plan.owner, data.seedPassword)
  refreshIfNeeded(owner)
  const memberIds = []
  for (const seed of plan.members) {
    const member = openSession(seed, data.seedPassword)
    memberIds.push(member.userId)
    addContact(owner, member.userId)
  }

  // The SC-005 clock covers group operations only (contacts are a fixture).
  const cycleStartedAt = Date.now()
  const created = createGroup(owner, `k6 ${runId} A${wave} ${plan.owner.username}`, [])
  const chatId = created.chatId
  const added = addGroupMembers(owner, chatId, memberIds)
  const view = getGroupView(owner, chatId)
  groupCreateCycleMs.add(Date.now() - cycleStartedAt)

  const addedBody = safeJson(added.body) || {}
  check(added, {
    'A: №31 batch add returns 200 with the full roster': r =>
      r.status === 200 && (addedBody.members || []).length === memberIds.length + 1,
  })
  const viewBody = safeJson(view.body) || {}
  check(viewBody, {
    'A: №28 roster complete after the cycle (SC-005)': b =>
      b.myRole === 'owner' && (b.members || []).length === memberIds.length + 1,
    'A: added members carry the member role': b =>
      (b.members || []).filter(m => m.role === 'member').length === memberIds.length,
  })
}

// --------------------------------------------- scenario B: messaging (SC-001) ---

export function messagingLoop(data) {
  const senders = data.messaging.senders.map(seed => openSession(seed, data.seedPassword))
  const receiver = openSession(data.messaging.receiver, data.seedPassword)
  const creator = senders[0]
  const roster = []
  for (const sender of senders.slice(1)) {
    addContact(creator, sender.userId)
    roster.push(sender.userId)
  }
  addContact(creator, receiver.userId)
  roster.push(receiver.userId)
  const group = createGroup(creator, `k6 ${runId} B messaging`, roster, 'group_create_b')
  const chatId = group.chatId

  const audit = newAudit()
  const streamRounds = Math.ceil(MESSAGE_TOTAL / MESSAGE_PER_STREAM)
  const roundBudgetMs = (1000 / MESSAGE_RPS) * MESSAGE_PER_STREAM
  let sendIndex = 0
  for (let round = 0; round < streamRounds; round += 1) {
    const count = Math.min(MESSAGE_PER_STREAM, MESSAGE_TOTAL - round * MESSAGE_PER_STREAM)
    const startedAt = Date.now()
    messagingRound({ senders, receiver, chatId, count, firstIndex: sendIndex, audit })
    sendIndex += count
    const elapsed = Date.now() - startedAt
    if (round < streamRounds - 1 && elapsed < roundBudgetMs) sleep((roundBudgetMs - elapsed) / 1000)
  }

  // The realtime path is at-most-once: anything the stream missed must come
  // back through the pull path, still exactly once and in seq order.
  catchUpHistory(receiver, chatId, audit)
  finalAudit(audit)
}

function messagingRound({ senders, receiver, chatId, count, firstIndex, audit }) {
  const timings = []
  const byId = new Map()
  const streamErrors = []
  let sendsCompleted = false

  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${receiver.accessToken}` }, timeout: SSE_TIMEOUT_MESSAGE },
    client => {
      client.on('event', event => {
        if (event.name !== 'message.created') return
        const payload = safeJson(event.data)
        if (!payload || payload.chatId !== chatId || !payload.message) return
        applyMessages(audit, chatId, [payload.message])
        const timing = byId.get(payload.message.id)
        if (!timing) return
        if (!timing.frameAt) timing.frameAt = Date.now()
        if (sendsCompleted && timings.every(t => t.frameAt)) client.close()
      })
      client.on('error', error => {
        streamErrors.push(describeSseError(error))
        client.close()
      })
      client.on('open', () => {
        for (let i = 0; i < count; i += 1) {
          const sender = senders[(firstIndex + i) % senders.length]
          refreshIfNeeded(sender)
          const clientMessageId = crypto.randomUUID()
          const timing = { sentAt: Date.now(), ackStatus: 0, frameAt: 0 }
          timings.push(timing)
          byId.set(clientMessageId, timing)
          const response = sendGroupMessage(sender, chatId, clientMessageId, `k6 ${runId} B ${clientMessageId}`)
          timing.ackStatus = response.status
          if (sendAccepted(response)) {
            recordSent(audit, chatId, clientMessageId, (safeJson(response.body) || {}).seq || 0)
          }
        }
        sendsCompleted = true
      })
    },
  )

  check(timings.length, { 'B: stream opened and the round sends driven': n => n === count })
  for (const timing of timings) {
    check(timing, { 'B: fresh group send accepted (201)': t => t.ackStatus === 201 })
    if (timing.frameAt) {
      groupDeliveredMs.add(timing.frameAt - timing.sentAt)
    } else {
      deliveryFramesMissed.add(1)
      groupDeliveredMs.add(Date.now() - timing.sentAt)
    }
  }
  check(streamErrors.join('; '), { 'B: realtime stream ended without transport errors': e => e === '' })
}

// ---------------------------------------------- scenario C: changes (SC-004) ---

export function changesLoop(data) {
  const { owner, admin, observer, spare } = data.changes
  const ownerSession = openSession(owner, data.seedPassword)
  const adminSession = openSession(admin, data.seedPassword)
  const observerSession = openSession(observer, data.seedPassword)
  const spareSession = openSession(spare, data.seedPassword)

  refreshIfNeeded(ownerSession)
  addContact(ownerSession, adminSession.userId)
  addContact(ownerSession, observerSession.userId)
  addContact(ownerSession, spareSession.userId)
  const group = createGroup(
    ownerSession,
    `k6 ${runId} C changes`,
    [adminSession.userId, observerSession.userId],
    'group_create_c',
  )
  const chatId = group.chatId

  for (let round = 1; round <= CHANGE_ROUNDS; round += 1) {
    refreshIfNeeded(ownerSession)
    const title = `k6 ${runId} C r${round}`
    measureChange({
      kind: 'metadata',
      observer: observerSession,
      chatId,
      matchEvent: frameEvent('group.updated', chatId, p => p.title === title),
      applyChange: () => patchGroup(ownerSession, chatId, { title }).status,
    })
    measureChange({
      kind: 'role_grant',
      observer: observerSession,
      chatId,
      matchEvent: frameEvent('group.role.changed', chatId, p => p.userId === observerSession.userId && p.role === 'admin'),
      applyChange: () => setMemberRole(ownerSession, chatId, observerSession.userId, 'admin').status,
    })
    measureChange({
      kind: 'role_revoke',
      observer: observerSession,
      chatId,
      matchEvent: frameEvent('group.role.changed', chatId, p => p.userId === observerSession.userId && p.role === 'member'),
      applyChange: () => setMemberRole(ownerSession, chatId, observerSession.userId, 'member').status,
    })
    measureChange({
      kind: 'member_add',
      observer: observerSession,
      chatId,
      matchEvent: frameEvent('group.member.added', chatId, p => p.user && p.user.id === spareSession.userId),
      applyChange: () => addGroupMembers(ownerSession, chatId, [spareSession.userId]).status,
    })
    measureChange({
      kind: 'member_remove',
      observer: observerSession,
      chatId,
      matchEvent: frameEvent('group.member.removed', chatId, p => p.userId === spareSession.userId),
      applyChange: () => removeGroupMember(ownerSession, chatId, spareSession.userId).status,
    })
    sleep(0.3)
  }
}

function frameEvent(name, chatId, payloadMatches) {
  return event => {
    if (event.name !== name) return false
    const payload = safeJson(event.data)
    return !!payload && payload.groupId === chatId && payloadMatches(payload)
  }
}

function measureChange({ kind, observer, chatId, matchEvent, applyChange }) {
  const timing = { startedAt: 0, opStatus: 0, matched: false, frameAt: 0, streamError: null }

  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${observer.accessToken}` }, timeout: SSE_TIMEOUT_CHANGE },
    client => {
      client.on('event', event => {
        if (!matchEvent(event)) return
        timing.frameAt = Date.now()
        timing.matched = true
        client.close()
      })
      client.on('error', error => {
        timing.streamError = describeSseError(error)
        client.close()
      })
      client.on('open', () => {
        timing.startedAt = Date.now()
        timing.opStatus = applyChange()
      })
    },
  )

  if (timing.matched) {
    groupChangeVisibleMs.add(timing.frameAt - timing.startedAt, { kind })
  } else {
    changeFramesMissed.add(1, { kind })
    groupChangeVisibleMs.add(timing.startedAt ? Date.now() - timing.startedAt : 15_000, { kind })
  }
  check(timing, {
    [`C: ${kind} applied over REST (2xx)`]: t => t.opStatus >= 200 && t.opStatus < 300,
    [`C: ${kind} visible over SSE to the online member (SC-004)`]: t => t.matched,
    [`C: ${kind} stream ended without transport errors`]: t => t.streamError === null,
  })
  return timing
}

// ------------------------------------------------ scenario D: authz (SC-003) ---

export function authzMatrix(data) {
  const { owner, member, kicked, outsider } = data.authz
  const ownerSession = openSession(owner, data.seedPassword)
  const memberSession = openSession(member, data.seedPassword)
  const kickedSession = openSession(kicked, data.seedPassword)
  const outsiderSession = openSession(outsider, data.seedPassword)

  refreshIfNeeded(ownerSession)
  addContact(ownerSession, memberSession.userId)
  addContact(ownerSession, kickedSession.userId)
  const group = createGroup(
    ownerSession,
    `k6 ${runId} D authz`,
    [memberSession.userId, kickedSession.userId],
    'group_create_d',
  )
  const chatId = group.chatId

  runDenialPass({ actor: outsiderSession, label: 'non-member', chatId, targetUserId: memberSession.userId })

  const kick = removeGroupMember(ownerSession, chatId, kickedSession.userId)
  check(kick, { 'D: №32 kick by the owner applied (204)': r => r.status === 204 })
  sleep(0.5)
  const repeat = removeGroupMember(ownerSession, chatId, kickedSession.userId)
  check(repeat, {
    'D: repeated №32 -> 409 target_not_member (idempotency edge)': r =>
      r.status === 409 && problemCodes(r).includes('target_not_member'),
  })

  runDenialPass({ actor: kickedSession, label: 'removed-member', chatId, targetUserId: memberSession.userId })
}

function runDenialPass({ actor, label, chatId, targetUserId }) {
  const headers = bearerHeaders(actor)
  refreshIfNeeded(actor)
  const attempts = [
    ['№28 GET group', 404, 'group_not_found', () =>
      http.get(`${GROUPS_ENDPOINT}/${chatId}`, { headers, tags: { op: 'authz_group_get' } })],
    ['№29 PATCH group', 404, 'group_not_found', () =>
      http.patch(`${GROUPS_ENDPOINT}/${chatId}`, JSON.stringify({ title: `k6 ${runId} D patch` }), {
        headers,
        tags: { op: 'authz_group_patch' },
      })],
    ['№31 POST members', 404, 'group_not_found', () =>
      http.post(`${GROUPS_ENDPOINT}/${chatId}/members`, JSON.stringify({ userIds: [targetUserId] }), {
        headers,
        tags: { op: 'authz_group_members' },
      })],
    ['№32 DELETE member', 404, 'group_not_found', () =>
      http.del(`${GROUPS_ENDPOINT}/${chatId}/members/${targetUserId}`, null, {
        headers,
        tags: { op: 'authz_group_kick' },
      })],
    ['№34 PUT role', 404, 'group_not_found', () =>
      http.put(
        `${GROUPS_ENDPOINT}/${chatId}/members/${targetUserId}/role`,
        JSON.stringify({ role: 'admin' }),
        { headers, tags: { op: 'authz_group_role' } },
      )],
    ['№35 POST owner', 404, 'group_not_found', () =>
      http.post(`${GROUPS_ENDPOINT}/${chatId}/owner`, JSON.stringify({ userId: targetUserId }), {
        headers,
        tags: { op: 'authz_group_owner' },
      })],
    ['№33 DELETE membership', 404, 'group_not_found', () =>
      http.del(`${GROUPS_ENDPOINT}/${chatId}/membership`, null, {
        headers,
        tags: { op: 'authz_group_leave' },
      })],
    ['№30 DELETE group', 404, 'group_not_found', () =>
      http.del(`${GROUPS_ENDPOINT}/${chatId}`, null, { headers, tags: { op: 'authz_group_delete' } })],
    ['№15 history', 403, 'not_participant', () =>
      http.get(`${CHATS_ENDPOINT}/${chatId}/messages?limit=50`, { headers, tags: { op: 'authz_history' } })],
    ['№16 send', 403, 'not_participant', () =>
      http.post(
        `${CHATS_ENDPOINT}/${chatId}/messages`,
        JSON.stringify({ clientMessageId: crypto.randomUUID(), text: `k6 ${runId} D denied` }),
        { headers, tags: { op: 'authz_send' } },
      )],
    ['№17 read', 403, 'not_participant', () =>
      http.post(`${CHATS_ENDPOINT}/${chatId}/read`, JSON.stringify({ upToSeq: 1 }), {
        headers,
        tags: { op: 'authz_read' },
      })],
  ]

  for (const [name, expectedStatus, expectedCode, run] of attempts) {
    const response = run()
    const codes = problemCodes(response)
    const denied = response.status === expectedStatus && codes.includes(expectedCode)
    if (denied) {
      authzDenied.add(1)
    } else {
      authzViolations.add(1)
    }
    check(
      { status: response.status, codes },
      {
        [`D ${label}: ${name} denied with ${expectedStatus} ${expectedCode} (SC-003)`]: s =>
          s.status === expectedStatus && s.codes.includes(expectedCode),
      },
    )
  }

  const sync = http.post(
    SYNC_ENDPOINT,
    JSON.stringify({ cursors: [{ chatId, upToSeq: 0 }], chatLimit: 50, messageLimit: 50 }),
    { headers, tags: { op: 'authz_sync' } },
  )
  const deltas = ((safeJson(sync.body) || {}).chats) || []
  const invisibleInSync = sync.status === 200 && !deltas.some(delta => delta.chatId === chatId)
  if (invisibleInSync) {
    authzDenied.add(1)
  } else {
    authzViolations.add(1)
  }
  check(invisibleInSync, { [`D ${label}: №26 sync carries no group deltas (SC-003)`]: v => v })

  const list = http.get(CHATS_ENDPOINT, { headers, tags: { op: 'authz_chat_list' } })
  const items = ((safeJson(list.body) || {}).chats) || []
  const invisibleInList = list.status === 200 && !items.some(item => item.chatId === chatId)
  if (invisibleInList) {
    authzDenied.add(1)
  } else {
    authzViolations.add(1)
  }
  check(invisibleInList, { [`D ${label}: №12 chat list hides the group (FR-009)`]: v => v })
}

// ------------------------------------- scenario E: large-group smoke (SC-006/007) ---

export function largeGroupSmoke(data) {
  const ownerSession = openSession(data.large.owner, data.seedPassword)
  const memberSessions = data.large.members.map(seed => openSession(seed, data.seedPassword))
  const memberIds = memberSessions.map(member => member.userId)

  // Roster fill (№27 with the full initial composition — the SC-007 smoke
  // shape); timed for the report, not thresholded.
  refreshIfNeeded(ownerSession)
  const fillStartedAt = Date.now()
  for (const userId of memberIds) addContact(ownerSession, userId)
  const group = createGroup(ownerSession, `k6 ${runId} E large`, memberIds, 'group_create_e')
  largeFillMs.add(Date.now() - fillStartedAt)
  const chatId = group.chatId
  check(
    { created: group.members || [], total: data.large.total },
    {
      'E: №27 filled the full roster in one request': s =>
        s.created.length === s.total && group.myRole === 'owner',
    },
  )

  const probe = memberSessions[0]
  const kickedMember = memberSessions[1]
  const senders = memberSessions.slice(2, 2 + data.large.senders)

  const audit = newAudit()
  largeWave({ chatId, senders, probe, kicked: kickedMember, audit, waveSize: data.large.wave })
  youRemovedByKick({ owner: ownerSession, chatId, kicked: kickedMember })
  quietWindow({ owner: ownerSession, chatId, kicked: kickedMember, audit })
  youRemovedByLeave({ chatId, leaver: senders[0] })

  catchUpHistory(probe, chatId, audit)
  finalAudit(audit)

  refreshIfNeeded(ownerSession)
  const view = getGroupView(ownerSession, chatId)
  const viewBody = safeJson(view.body) || {}
  check(viewBody, {
    'E: №28 roster reflects the kick and the leave': b =>
      view.status === 200 && (b.members || []).length === data.large.total - 2,
  })
}

function largeWave({ chatId, senders, probe, kicked, audit, waveSize }) {
  const waveIds = []
  const seenByProbe = new Set()
  const seenByKicked = new Set()
  const streamErrors = []
  let rejected = 0

  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${probe.accessToken}` }, timeout: SSE_TIMEOUT_WAVE },
    probeClient => {
      probeClient.on('event', event => {
        if (event.name !== 'message.created') return
        const payload = safeJson(event.data)
        if (!payload || payload.chatId !== chatId || !payload.message) return
        const message = payload.message
        if (seenByProbe.has(message.id)) {
          groupWireRedelivery.add(1)
        } else {
          seenByProbe.add(message.id)
          applyMessages(audit, chatId, [message])
        }
        if (seenByProbe.size >= waveIds.length && waveIds.length > 0) probeClient.close()
      })
      probeClient.on('error', error => {
        streamErrors.push(`probe: ${describeSseError(error)}`)
        probeClient.close()
      })
      probeClient.on('open', () => {
        sse.open(
          EVENTS_ENDPOINT,
          { headers: { Authorization: `Bearer ${kicked.accessToken}` }, timeout: SSE_TIMEOUT_WAVE },
          kickedClient => {
            kickedClient.on('event', event => {
              if (event.name !== 'message.created') return
              const payload = safeJson(event.data)
              if (!payload || payload.chatId !== chatId || !payload.message) return
              const id = payload.message.id
              if (seenByKicked.has(id)) {
                groupWireRedelivery.add(1)
              } else {
                seenByKicked.add(id)
              }
              if (seenByKicked.size >= waveIds.length && waveIds.length > 0) kickedClient.close()
            })
            kickedClient.on('error', error => {
              streamErrors.push(`kicked: ${describeSseError(error)}`)
              kickedClient.close()
            })
            kickedClient.on('open', () => {
              for (let i = 0; i < waveSize; i += 1) {
                const sender = senders[i % senders.length]
                refreshIfNeeded(sender)
                const clientMessageId = crypto.randomUUID()
                const response = sendGroupMessage(sender, chatId, clientMessageId, `k6 ${runId} E ${i}`)
                if (sendAccepted(response)) {
                  waveIds.push(clientMessageId)
                  recordSent(audit, chatId, clientMessageId, (safeJson(response.body) || {}).seq || 0)
                } else {
                  rejected += 1
                }
              }
            })
          },
        )
      })
    },
  )

  check(rejected, { 'E: every wave send accepted (201)': r => r === 0 })
  check(streamErrors.join('; '), { 'E: wave streams ended without transport errors': e => e === '' })
  check(
    { probe: seenByProbe.size, kicked: seenByKicked.size, accepted: waveIds.length },
    {
      'E: both online members saw the whole wave over SSE (SC-007)': s =>
        s.accepted > 0 && s.probe === s.accepted && s.kicked === s.accepted,
    },
  )
}

function youRemovedByKick({ owner, chatId, kicked }) {
  const timing = { startedAt: 0, status: 0, matched: false, frameAt: 0, reason: null }

  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${kicked.accessToken}` }, timeout: SSE_TIMEOUT_WAVE },
    client => {
      client.on('event', event => {
        if (event.name !== 'group.you_removed') return
        const payload = safeJson(event.data)
        if (!payload || payload.groupId !== chatId) return
        timing.frameAt = Date.now()
        timing.matched = true
        timing.reason = payload.reason
        client.close()
      })
      client.on('error', () => client.close())
      client.on('open', () => {
        refreshIfNeeded(owner)
        timing.startedAt = Date.now()
        timing.status = removeGroupMember(owner, chatId, kicked.userId).status
      })
    },
  )

  if (timing.matched) {
    groupYouRemovedMs.add(timing.frameAt - timing.startedAt)
  } else {
    groupYouRemovedMs.add(timing.startedAt ? Date.now() - timing.startedAt : 15_000)
  }
  check(timing, {
    'E: №32 kick applied over REST (204)': t => t.status === 204,
    'E: group.you_removed {reason:kicked} delivered to the removed member (SC-006)': t =>
      t.matched && t.reason === 'kicked',
  })
}

function quietWindow({ owner, chatId, kicked, audit }) {
  // SC-006: after you_removed the group subscription is logically closed —
  // the user stream stays open, so any group frame within the window is a
  // delivery leak. The owner triggers group activity while the removed
  // member watches; the message also stays in the audit's expectation.
  const leaks = []

  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${kicked.accessToken}` }, timeout: `${QUIET_WINDOW_S}s` },
    client => {
      client.on('event', event => {
        const payload = safeJson(event.data) || {}
        const groupId = payload.groupId || payload.chatId
        if (groupId !== chatId) return
        if (event.name.startsWith('group.') || event.name === 'message.created') leaks.push(event.name)
      })
      client.on('error', () => client.close())
      client.on('open', () => {
        refreshIfNeeded(owner)
        const quietId = crypto.randomUUID()
        const sent = sendGroupMessage(owner, chatId, quietId, `k6 ${runId} E quiet`)
        if (sendAccepted(sent)) {
          recordSent(audit, chatId, quietId, (safeJson(sent.body) || {}).seq || 0)
        }
        patchGroup(owner, chatId, { title: `k6 ${runId} E quiet` })
      })
    },
  )

  for (let i = 0; i < leaks.length; i += 1) quietViolations.add(1)
  check(leaks.length, {
    [`E: no group frames reach the removed member within ${QUIET_WINDOW_S}s (SC-006)`]: n => n === 0,
  })
}

function youRemovedByLeave({ chatId, leaver }) {
  const timing = { startedAt: 0, status: 0, matched: false, frameAt: 0, reason: null }

  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${leaver.accessToken}` }, timeout: SSE_TIMEOUT_WAVE },
    client => {
      client.on('event', event => {
        if (event.name !== 'group.you_removed') return
        const payload = safeJson(event.data)
        if (!payload || payload.groupId !== chatId) return
        timing.frameAt = Date.now()
        timing.matched = true
        timing.reason = payload.reason
        client.close()
      })
      client.on('error', () => client.close())
      client.on('open', () => {
        timing.startedAt = Date.now()
        timing.status = leaveGroup(leaver, chatId).status
      })
    },
  )

  if (timing.matched) {
    groupYouRemovedMs.add(timing.frameAt - timing.startedAt)
  } else {
    groupYouRemovedMs.add(timing.startedAt ? Date.now() - timing.startedAt : 15_000)
  }
  check(timing, {
    'E: №33 leave applied over REST (204)': t => t.status === 204,
    'E: group.you_removed {reason:left} delivered to the leaver (SC-006)': t =>
      t.matched && t.reason === 'left',
  })
}

// ------------------------------------------------------------------ summary ---

export function handleSummary(data) {
  const metrics = data.metrics
  // k6 v2 lists declared-but-never-fired counters without `.count` — read
  // defensively so a perfect run prints explicit zeros, not `undefined`.
  const countOf = name => {
    const metric = metrics[name]
    if (!metric) return 0
    return metric.count ?? (metric.values ? metric.values.count : undefined) ?? 0
  }
  const trendOf = (name, quantile) => {
    const metric = metrics[name]
    if (!metric || !metric.values) return null
    const values = metric.values
    return values[quantile] !== undefined ? values[quantile] : values.max
  }
  const lines = [
    `=== 006 group-chats smoke (VUS=${SCENARIO_VUS}, GROUP_SIZE=${GROUP_SIZE}, MESSAGE_RPS=${MESSAGE_RPS}, users seeded=${TOTAL_USERS}) ===`,
    `A lifecycle: cycle p90=${trendOf('group_create_cycle_ms', 'p(90)')} ms, max=${trendOf('group_create_cycle_ms', 'max')} ms (SC-005 budget 60000)`,
    `B messaging: delivered p95=${trendOf('group_delivered_ms', 'p(95)')} ms, frames missed=${countOf('group_delivery_frames_missed_total')} (SC-001 budget 2000)`,
    `C changes: visible p95=${trendOf('group_change_visible_ms', 'p(95)')} ms, frames missed=${countOf('group_change_frames_missed_total')} (SC-004 budget 2000)`,
    `D authz: denials=${countOf('group_authz_denied_total')}, violations=${countOf('group_authz_violations_total')} (SC-003)`,
    `E large: roster fill=${trendOf('large_group_fill_ms', 'max')} ms, you_removed p100=${trendOf('group_you_removed_ms', 'p(100)')} ms (SC-006 budget 5000), quiet leaks=${countOf('group_quiet_violations_total')}`,
    `audit: losses=${countOf('group_losses_total')}, doubles=${countOf('group_duplicates_total')}, order violations=${countOf('group_order_violations_total')}, wire re-deliveries deduped=${countOf('group_wire_redelivery_total')}`,
  ]
  return { stdout: `\n${lines.join('\n')}\n` }
}
