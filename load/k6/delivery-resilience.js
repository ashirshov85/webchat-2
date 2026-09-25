import http from 'k6/http'
import sse from 'k6/x/sse'
import { check, fail, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

/**
 * 005-delivery-resilience T042 — delivery resilience profiles (research.md §10,
 * quickstart §4; SC-001/SC-004/SC-005/SC-006/SC-007/SC-008).
 *
 * One script, two profiles selected by env:
 *
 * 1. Disconnect profile (default; quickstart §4 "Разрывы/SC-001/SC-008"):
 *      docker run --rm -i --network host \
 *        -e K6_BASE_URL -e K6_MAILPIT_URL -e K6_DISCONNECT_PROFILE \
 *        webchat-k6 run - < load/k6/delivery-resilience.js
 *    A single VU drives one receiver (bob) with 7 peer chats and forces SSE /
 *    sync-cycle breaks at distinct points of the delivery path:
 *      after-ack       — the receiver stream is closed right after the send
 *                        acks return, before the message.created frames settle
 *                        (frames that still arrive are applied; the rest must
 *                        come back through the pull cycle);
 *      mid-sync        — the catch-up cycle is aborted after the first №26
 *                        delta page (applied + acked), before the №15
 *                        continuation, then resumed from the last acked cursor;
 *      between-pages   — the cycle is aborted after the first №15 ascending
 *                        page, between history pages, then resumed.
 *    After every break the normative cycle (sync-protocol.md §3.1: №26 →
 *    render with id-dedup → №25 ack → №15 after while hasMore → repeat while
 *    moreChats) must converge with, per chat:
 *      0 losses   — every accepted clientMessageId rendered exactly once;
 *      0 doubles  — no rendered message the senders never accepted (wire-level
 *                   re-delivery is allowed and counted separately — the client
 *                   dedups by message.id, US1-3);
 *      monotonic order — first application per chat is strictly ascending by
 *                   server seq.
 *    The bulk rounds measure the SC-008 steady budget: 200 messages (7 chats,
 *    flood-safe 29 per sender burst) missed while offline must catch up with
 *    p95 <= 10 s (catchup_200_ms). A re-№26 with pre-ack cursors must return
 *    the same messages without double renders (quickstart §3.1.3). A final №26
 *    with the acked cursors must be empty. The retry-by-same-id dedup fastpath
 *    (200, same seq, never a second record) is probed on the way (№16 order:
 *    dedup first — api-contract.md §3).
 *
 *    Env: K6_BASE_URL (default http://localhost:8080), K6_MAILPIT_URL
 *    (default http://localhost:8025), K6_DISCONNECT_PROFILE ('all' or a
 *    comma list of after-ack,mid-sync,between-pages), K6_DISCONNECT_ROUNDS
 *    (default 5; 0 disables the bulk rounds), K6_DISCONNECT_ROUND_MESSAGES
 *    (default 200).
 *
 * 2. Saturation profile (K6_TARGET_RPS set; quickstart §4 "Насыщение"):
 *      docker run --rm -i --network host \
 *        -e K6_BASE_URL -e K6_MAILPIT_URL \
 *        -e K6_TARGET_RPS=5000 -e K6_DURATION=5m [-e K6_RAMP=2m] \
 *        [-e K6_SATURATION_PAIRS=32] [-e K6_RECOVERY_WINDOW=5m] \
 *        [-e K6_STRICT_VALIDATION=1] \
 *        webchat-k6 run - < load/k6/delivery-resilience.js
 *    - bulk: ramping-arrival-rate 0 -> K6_TARGET_RPS over K6_RAMP, then hold
 *      K6_DURATION. The ramp keeps rising until the first shed signal — the
 *      overloaded regime is defined from the first 503/429 (backpressure or
 *      rate limiting) until the queues drain. Thresholds:
 *        0 "silent" failures — no transport errors/timeouts and no statuses
 *          outside {200,201,429,503} on the send path (http_req_failed==0 and
 *          silent_failures_total==0; the criterion is enforced strictly: a
 *          timeout is never an acceptable outcome, degradation must be an
 *          explicit 503/429);
 *        every 503/429 carries Retry-After >= 1s (missing_retry_after_total==0);
 *        accepted (201/200) messages are delivered without losses/doubles —
 *          every bulk VU owns exactly one sender→receiver pair (VU count ==
 *          K6_SATURATION_PAIRS) and, near the end of the hold phase, replays
 *          the full pull cycle for its receiver comparing the id sets;
 *        delivery latency (send -> SSE frame at the receiver, a conservative
 *          proxy for the SC-004 "server accept -> delivery" budget: it
 *          includes the client upstream, negligible on a LAN stand) measured
 *          by a dedicated audit pair: p99 <= 500 ms while steady
 *          (delivery_ms{mode:steady}) and p99 <= 2 s while the overloaded
 *          regime is active (delivery_ms{mode:overloaded}); rejected attempts
 *          retry transparently by Retry-After and never start the latency
 *          clock;
 *        catch-up of 200 messages in the overloaded regime: p95 <= 30 s
 *          (catchup_overloaded_ms, SC-008) — a probe receiver with 7 chats is
 *          refilled every ~75 s during the hold phase;
 *        recovery: in the last 60 s of the K6_RECOVERY_WINDOW (default 5 m)
 *          after ramp-down the audit pair delivery must be back at p99
 *          <= 500 ms (recovery_tail_ms, SC-006).
 *    Run validity for the 005 full run (K6_STRICT_VALIDATION=1):
 *      achieved accepted flow >= 5 000 msg/s (msg_accepted_total run-average
 *      rate > 4999 — set K6_TARGET_RPS with headroom for the ramp/recovery
 *      windows, e.g. >= 8000 for a minimal validation) and the overloaded
 *      regime lasts >= 60 s (overloaded_duration_s + a tagged check).
 *
 *      Flow ceiling note: the per-user flood limit (30/min, FR-010) bounds the
 *      ACCEPTED flow at pairs/2 msg/s — a stand configured with 32 pairs tops
 *      out at ~16 accepted msg/s no matter the attempted rate (the rest is
 *      explicit 429, which still counts as the rate-limited overloaded
 *      regime). To physically reach an accepted peak of 5 000 msg/s set
 *      K6_SATURATION_PAIRS >= 12000; for the budget profile —
 *      K6_TARGET_RPS=100000 K6_DURATION=10m K6_SATURATION_PAIRS>=200000 —
 *      executed by distributed k6 on scaled infrastructure (platform feature
 *      014, plan.md Complexity Tracking); user seeding then scales with the
 *      stand and is not Mailpit-bound.
 *
 * The SSE client needs the k6/x/sse extension (stock k6 buffers HTTP bodies,
 * grafana/k6#746): build the runner image first —
 *   docker build -t webchat-k6 load/k6
 * Seeding follows the 002/004 smoke pattern: register → Mailpit letter →
 * confirm → set password; every user gets a distinct source IP to stay off
 * the per-IP register limits.
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
const DELIVERY_ACK_ENDPOINT = `${API_BASE}/users/me/delivery-ack`
// permitAll (SecurityConfig): the server-side observability surface of FR-014
const PROMETHEUS_ENDPOINT = `${BASE_URL}/actuator/prometheus`
// T045: the 004 rate-limiting meter the saturation profile audits server-side
const FLOOD_METER = 'webchat_send_rejected_total'

// Flood limit is 30 msg/min per user with a burst capacity of 30 (004 §7);
// 29 back-to-back fresh sends fit the burst, retries by the same id are free
// (the dedup fastpath runs before the flood gate — api-contract.md 005 §3).
const FLOOD_SAFE_BURST = 29
const DISCONNECT_SENDERS = 7
const PROBE_SENDERS = 7
const ROUND_SLEEP_S = 66
const PROBE_ROUND_SLEEP_S = 75
const PROBE_ROUND_BUDGET_MS = 90_000
const MIN_SEND_PACE_S = 2.1
const AUDIT_CYCLE_SLEEP_S = 2.2
const ACCESS_TOKEN_REFRESH_MARGIN_MS = 60_000
const SSE_TIMEOUT_AUDIT = '60s'
const SSE_TIMEOUT_SHORT = '10s'
const VERIFICATION_LINK_PATTERN = /confirm-registration\?token=([A-Za-z0-9_-]{43})/
const MAILPIT_WAIT_MS = 120_000

const PROFILE = (() => {
  const raw = parseInt(String(__ENV.K6_TARGET_RPS || ''), 10)
  return Number.isFinite(raw) && raw > 0 ? 'saturation' : 'disconnect'
})()

function parseDurationMs(value, fallbackMs) {
  if (!value) return fallbackMs
  const match = String(value).trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)?$/)
  if (!match) fail(`invalid duration "${value}" (expected e.g. 5m, 90s, 45000ms)`)
  const amount = parseFloat(match[1])
  if (match[2] === 'ms') return amount
  if (match[2] === 'm') return amount * 60_000
  if (match[2] === 'h') return amount * 3_600_000
  return amount * 1000
}

function formatDuration(ms) {
  if (ms % 1000 !== 0) return `${ms}ms`
  const seconds = ms / 1000
  if (seconds % 60 !== 0) return `${seconds}s`
  return `${seconds / 60}m`
}

// k6 re-evaluates module scope per VU: Date.now()-derived values differ
// between setup() and scenario executors, so run identity travels via setup data.
const runId = Date.now().toString(36)

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 204, 400, 401, 404, 429, 503))

// ---------------------------------------------------------------- metrics ---

const auditLosses = new Counter('audit_losses_total')
const auditDuplicates = new Counter('audit_duplicates_total')
const auditOrderViolations = new Counter('audit_order_violations_total')
const auditSeqMismatch = new Counter('audit_seq_mismatch_total')
const wireRedeliveryTotal = new Counter('wire_redelivery_total')
const dedupRetryHits = new Counter('dedup_retry_hits_total')

const msgAcceptedTotal = new Counter('msg_accepted_total')
const msgRejectedTotal = new Counter('msg_rejected_total')
const silentFailuresTotal = new Counter('silent_failures_total')
const missingRetryAfterTotal = new Counter('missing_retry_after_total')

const catchup200Ms = new Trend('catchup_200_ms')
const catchupOverloadedMs = new Trend('catchup_overloaded_ms')
const deliveryMs = new Trend('delivery_ms')
const recoveryTailMs = new Trend('recovery_tail_ms')
const overloadedDurationS = new Trend('overloaded_duration_s')
const auditTimeToFirstShedS = new Trend('audit_time_to_first_shed_s')
const probeStarvedRounds = new Counter('probe_starved_rounds_total')

// T045 (FR-014): the server-side observability audit of the saturation
// profile — the 004 flood counter `webchat_send_rejected_total{reason=flood}`
// must be PRESENT on /actuator/prometheus and GROW over the run (teardown
// compares its scrapes against the setup baseline and reports through these).
const serverFloodRejectionsDelta = new Counter('server_flood_rejections_delta_total')
const serverFloodMeterPresent = new Counter('server_flood_meter_present')

// --------------------------------------------- server observability scrape ---

// One /actuator/prometheus exposition: { meters: {name: true}, samples: [...] }.
// Parsed exactly like an operator's scraper: HELP headers name the meters,
// sample lines carry the labelled series values.
function scrapePrometheus() {
  const response = http.get(PROMETHEUS_ENDPOINT, { tags: { op: 'prometheus_scrape' } })
  if (response.status !== 200) return null
  const exposition = response.body || ''
  const meters = {}
  const samples = []
  for (const line of exposition.split('\n')) {
    if (line.startsWith('# HELP ')) {
      meters[line.slice('# HELP '.length).split(' ')[0]] = true
    } else if (!line.startsWith('#')) {
      const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([0-9.eE+-]+)$/)
      if (match) samples.push({ name: match[1], labels: match[2] || '', value: parseFloat(match[3]) })
    }
  }
  return { meters, samples }
}

// The value of one labelled sample (`labelsFragment` e.g. 'reason="flood"'),
// or null when the series is absent (a lazily-registered counter before its
// first increment).
function sampleValue(scrape, name, labelsFragment) {
  if (!scrape) return null
  const sample = scrape.samples.find(
    s => s.name === name && (!labelsFragment || s.labels.includes(labelsFragment)),
  )
  return sample ? sample.value : null
}

// -------------------------------------------------------------- constants ---

const DISCONNECT_ROUNDS = parseInt(String(__ENV.K6_DISCONNECT_ROUNDS ?? '5'), 10)
const DISCONNECT_ROUND_MESSAGES = parseInt(String(__ENV.K6_DISCONNECT_ROUND_MESSAGES ?? '200'), 10)
const DISCONNECT_PHASES = (() => {
  const raw = String(__ENV.K6_DISCONNECT_PROFILE || 'all').trim()
  if (!raw || raw === 'all') return new Set(['after-ack', 'mid-sync', 'between-pages'])
  return new Set(raw.split(',').map(s => s.trim()).filter(Boolean))
})()

const TARGET_RPS = PROFILE === 'saturation' ? parseInt(String(__ENV.K6_TARGET_RPS), 10) : 0
const HOLD_MS = PROFILE === 'saturation' ? parseDurationMs(__ENV.K6_DURATION, 300_000) : 0
const RAMP_MS =
  PROFILE === 'saturation' ? parseDurationMs(__ENV.K6_RAMP, Math.min(120_000, Math.floor(HOLD_MS / 2))) : 0
const RECOVERY_MS = PROFILE === 'saturation' ? parseDurationMs(__ENV.K6_RECOVERY_WINDOW, 300_000) : 0
const SATURATION_PAIRS = PROFILE === 'saturation' ? parseInt(String(__ENV.K6_SATURATION_PAIRS ?? '32'), 10) : 0
const STRICT_VALIDATION = String(__ENV.K6_STRICT_VALIDATION || '') === '1'

if (PROFILE === 'saturation') {
  if (!Number.isFinite(TARGET_RPS) || TARGET_RPS <= 0) fail(`invalid K6_TARGET_RPS "${__ENV.K6_TARGET_RPS}"`)
  if (!Number.isFinite(SATURATION_PAIRS) || SATURATION_PAIRS < 1) fail('K6_SATURATION_PAIRS must be >= 1')
}

const thresholds = {
  http_req_failed: ['rate==0'],
  checks: ['rate==1'],
  audit_losses_total: ['count==0'],
  audit_duplicates_total: ['count==0'],
  audit_order_violations_total: ['count==0'],
  audit_seq_mismatch_total: ['count==0'],
}

if (PROFILE === 'disconnect') {
  thresholds.catchup_200_ms = ['p(95)<10000']
} else {
  thresholds.silent_failures_total = ['count==0']
  thresholds.missing_retry_after_total = ['count==0']
  thresholds['delivery_ms{mode:steady}'] = ['p(99)<500']
  thresholds['delivery_ms{mode:overloaded}'] = ['p(99)<2000']
  thresholds.recovery_tail_ms = ['p(99)<500']
  thresholds.catchup_overloaded_ms = ['p(95)<30000']
  if (STRICT_VALIDATION) {
    // Run-average accepted flow; for the minimal 005 validation set
    // K6_TARGET_RPS with headroom over 5000 so the ramp/recovery windows do
    // not dilute the average below the validity bar.
    thresholds.msg_accepted_total = ['rate>4999']
    thresholds['checks{validity:strict}'] = ['rate==1']
  }
}

export const options =
  PROFILE === 'disconnect'
    ? {
        scenarios: {
          resilience: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 1,
            exec: 'disconnectProfile',
            gracefulStop: '120s',
          },
        },
        thresholds,
      }
    : {
        scenarios: {
          bulk: {
            executor: 'ramping-arrival-rate',
            startRate: 1,
            timeUnit: '1s',
            stages: [
              { duration: formatDuration(RAMP_MS), target: TARGET_RPS },
              { duration: formatDuration(HOLD_MS), target: TARGET_RPS },
            ],
            preAllocatedVUs: SATURATION_PAIRS,
            maxVUs: SATURATION_PAIRS,
            exec: 'saturateSend',
            gracefulStop: '30s',
          },
          audit: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 1,
            startTime: '0s',
            exec: 'auditLoop',
            gracefulStop: '30s',
          },
          probe: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 1,
            startTime: formatDuration(RAMP_MS),
            exec: 'catchupProbe',
            gracefulStop: '90s',
          },
        },
        thresholds,
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

function headerValue(response, name) {
  const target = name.toLowerCase()
  for (const key of Object.keys(response.headers)) {
    if (key.toLowerCase() === target) return response.headers[key]
  }
  return null
}

function retryAfterSeconds(response) {
  const raw = headerValue(response, 'Retry-After')
  return raw === null || raw === '' ? 0 : parseInt(raw, 10)
}

function describeSseError(error) {
  try {
    if (error && typeof error.error === 'function') return String(error.error())
  } catch (ignored) {
    // fall through to coercion below
  }
  return String(error)
}

function bearerHeaders(session) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${session.accessToken}` }
}

function openSession(seed, password) {
  const response = postJson(LOGIN_ENDPOINT, { identifier: seed.username, password })
  if (response.status !== 200) fail(`login ${seed.username} -> ${response.status} ${response.body}`)
  const body = response.json()
  return {
    username: seed.username,
    userId: body.user.id,
    accessToken: body.accessToken,
    refreshToken: body.refreshToken,
    expiresAt: Date.now() + body.expiresInSec * 1000,
  }
}

function refreshIfNeeded(session) {
  if (Date.now() < session.expiresAt - ACCESS_TOKEN_REFRESH_MARGIN_MS) return
  const response = postJson(REFRESH_ENDPOINT, { refreshToken: session.refreshToken })
  if (response.status !== 200) fail(`refresh ${session.username} -> ${response.status} ${response.body}`)
  const body = response.json()
  session.accessToken = body.accessToken
  session.refreshToken = body.refreshToken
  session.expiresAt = Date.now() + body.expiresInSec * 1000
}

function ensureChat(session, peerUserId) {
  const response = http.post(`${API_BASE}/chats/ensure`, JSON.stringify({ peerUserId }), {
    headers: bearerHeaders(session),
    tags: { op: 'ensure' },
  })
  if (response.status !== 200 && response.status !== 201) {
    fail(`ensure chat for ${session.username} -> ${response.status} ${response.body}`)
  }
  const body = safeJson(response.body) || {}
  if (!body.chatId) fail(`ensure chat for ${session.username} returned no chatId: ${response.body}`)
  return body.chatId
}

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

function distributeMessages(total, buckets, cap) {
  const plan = []
  let left = total
  for (let i = 0; i < buckets && left > 0; i += 1) {
    const take = Math.min(cap, left)
    plan.push(take)
    left -= take
  }
  return plan
}

// -------------------------------------------------------- audit machinery ---
// One audit object per RECEIVER. `expected` is what the senders on this VU got
// accepted (201/200); `ids` is what the receiver actually rendered (applied
// exactly once — re-delivery over the wire is deduped by message.id, US1-3).

function newAudit() {
  return { chats: {} }
}

function chatAudit(audit, chatId) {
  if (!audit.chats[chatId]) {
    audit.chats[chatId] = {
      ids: new Set(),
      expected: new Map(),
      lastSeq: 0,
      rendered: 0,
      redeliveries: 0,
      violations: 0,
    }
  }
  return audit.chats[chatId]
}

function recordSent(audit, chatId, messageId, seq) {
  const chat = chatAudit(audit, chatId)
  chat.expected.set(messageId, seq)
}

function applyMessages(audit, chatId, messages) {
  const chat = chatAudit(audit, chatId)
  for (const message of messages) {
    if (chat.ids.has(message.id)) {
      chat.redeliveries += 1
      wireRedeliveryTotal.add(1)
      continue
    }
    if (message.seq <= chat.lastSeq) chat.violations += 1
    if (chat.expected.has(message.id) && chat.expected.get(message.id) !== message.seq) {
      auditSeqMismatch.add(1)
    }
    chat.ids.add(message.id)
    chat.rendered += 1
    if (message.seq > chat.lastSeq) chat.lastSeq = message.seq
  }
}

function finalAudit(audit) {
  for (const chatId of Object.keys(audit.chats)) {
    const chat = audit.chats[chatId]
    for (const id of chat.expected.keys()) {
      if (!chat.ids.has(id)) auditLosses.add(1)
    }
    for (const id of chat.ids) {
      if (!chat.expected.has(id)) auditDuplicates.add(1)
    }
    if (chat.violations > 0) auditOrderViolations.add(chat.violations)
  }
}

function phaseCheck(name, audit, chatId, expectedIds) {
  const chat = chatAudit(audit, chatId)
  const missing = expectedIds.filter(id => !chat.ids.has(id)).length
  check(
    { missing, violations: chat.violations },
    {
      [`${name}: every message rendered exactly once (0 losses)`]: s => s.missing === 0,
      [`${name}: application order monotonic by seq`]: s => s.violations === 0,
    },
  )
}

// ------------------------------------------------------ protocol client  ---
// The normative catch-up cycle (sync-protocol.md §3.1): №26 → self-heal the
// cursor to startAfterSeq → render (id-dedup) → №25 ack(max(last page seq |
// truncatedUpToSeq)) → №15 after-pages for hasMore chats → repeat while
// moreChats. `abortAt` injects the forced disconnect points of the disconnect
// profile ('delta' — after the first №26 round; 'after-history-page' — after
// the first №15 page); the caller resumes from the acked cursors.

function cursorList(cursors) {
  return Object.keys(cursors).map(chatId => ({ chatId, upToSeq: Math.max(0, cursors[chatId]) }))
}

function flushAcks(session, acks) {
  if (!acks.length) return
  if (acks.length > 100) fail(`ack batch of ${acks.length} exceeds the contract limit of 100`)
  const response = http.post(DELIVERY_ACK_ENDPOINT, JSON.stringify({ acks }), {
    headers: bearerHeaders(session),
    tags: { op: 'delivery_ack' },
  })
  if (response.status !== 204) fail(`delivery-ack -> ${response.status} ${response.body}`)
}

function syncAll(session, cursors, audit, opts = {}) {
  const messageLimit = opts.messageLimit || 50
  const chatLimit = opts.chatLimit || 50
  const historyLimit = opts.historyLimit || 50
  const abortAt = opts.abortAt || null
  const startedAt = Date.now()
  let aborted = null

  for (;;) {
    const response = http.post(
      SYNC_ENDPOINT,
      JSON.stringify({ cursors: cursorList(cursors), chatLimit, messageLimit }),
      { headers: bearerHeaders(session), tags: { op: 'sync' } },
    )
    if (response.status !== 200) fail(`sync -> ${response.status} ${response.body}`)
    const body = safeJson(response.body) || {}
    const deltas = body.chats || []

    const acks = []
    const continueChats = []
    for (const delta of deltas) {
      const chatId = delta.chatId
      cursors[chatId] = delta.startAfterSeq
      if (delta.desynced && typeof delta.serverUpToSeq === 'number') {
        cursors[chatId] = delta.serverUpToSeq
      }
      const messages = delta.messages || []
      applyMessages(audit, chatId, messages)
      let ackPosition = cursors[chatId]
      if (messages.length > 0) ackPosition = Math.max(ackPosition, messages[messages.length - 1].seq)
      if (typeof delta.truncatedUpToSeq === 'number') ackPosition = Math.max(ackPosition, delta.truncatedUpToSeq)
      if (ackPosition >= 1) {
        acks.push({ chatId, upToSeq: ackPosition })
        cursors[chatId] = ackPosition
      }
      if (delta.hasMore) continueChats.push(chatId)
    }
    flushAcks(session, acks)

    if (abortAt === 'delta' && deltas.length > 0) {
      aborted = 'delta'
      break
    }

    let pageAborted = false
    for (const chatId of continueChats) {
      for (;;) {
        const after = cursors[chatId]
        const pageResponse = http.get(`${API_BASE}/chats/${chatId}/messages?after=${after}&limit=${historyLimit}`, {
          headers: bearerHeaders(session),
          tags: { op: 'history_after' },
        })
        if (pageResponse.status !== 200) fail(`history after -> ${pageResponse.status} ${pageResponse.body}`)
        const page = safeJson(pageResponse.body) || {}
        applyMessages(audit, chatId, page.messages || [])
        const nextAfter = page.nextAfter
        if (typeof nextAfter === 'number' && nextAfter >= 1) {
          flushAcks(session, [{ chatId, upToSeq: nextAfter }])
          cursors[chatId] = nextAfter
        }
        if (abortAt === 'after-history-page') {
          aborted = 'after-history-page'
          pageAborted = true
          break
        }
        if (typeof nextAfter !== 'number') break
      }
      if (pageAborted) break
    }
    if (pageAborted) break
    if (!body.moreChats) break
  }

  return { durationMs: Date.now() - startedAt, aborted }
}

function sendFresh(session, chatId, clientMessageId, text, opTag) {
  return http.post(
    `${API_BASE}/chats/${chatId}/messages`,
    JSON.stringify({ clientMessageId, text: text || `k6 ${runId} ${clientMessageId}` }),
    { headers: bearerHeaders(session), tags: { op: opTag || 'send' } },
  )
}

function classifySend(response) {
  if (!response || response.status === 0 || response.error) return 'silent'
  if (response.status === 201) return 'accepted'
  if (response.status === 200) return 'dedup'
  if (response.status === 503) return 'server_busy'
  if (response.status === 429) return 'flood_limit'
  return 'silent'
}

// ------------------------------------------------------------------ setup ---

export function setup() {
  const seedPassword = `K6res-${runId}-Load1`

  if (PROFILE === 'disconnect') {
    const users = seedAndConfirmUsers('k6res', 1 + DISCONNECT_SENDERS, seedPassword)
    const enabledPhases = ['after-ack', 'mid-sync', 'between-pages'].filter(phase => DISCONNECT_PHASES.has(phase))
    return {
      profile: PROFILE,
      seedPassword,
      users: { bob: users[0], senders: users.slice(1) },
      rounds: DISCONNECT_ROUNDS,
      roundMessages: DISCONNECT_ROUND_MESSAGES,
      enabledPhases,
    }
  }

  const total = SATURATION_PAIRS * 2 + 2 + 1 + PROBE_SENDERS
  const users = seedAndConfirmUsers('k6sat', total, seedPassword)
  let cursor = 0
  const pairs = []
  for (let i = 0; i < SATURATION_PAIRS; i += 1) {
    pairs.push({ sender: users[cursor], receiver: users[cursor + 1] })
    cursor += 2
  }
  const auditPair = { sender: users[cursor], receiver: users[cursor + 1] }
  cursor += 2
  const probe = { receiver: users[cursor], senders: users.slice(cursor + 1, cursor + 1 + PROBE_SENDERS) }

  // startedAt anchors every wall-clock schedule: captured at the very end of
  // setup so scenario startTime offsets (which k6 counts from the test start,
  // i.e. after setup) line up with the VU clocks.
  const startedAt = Date.now()
  const bulkEndAt = startedAt + RAMP_MS + HOLD_MS

  // T045 (FR-014): the observability baseline of the saturation run — the
  // server flood counter before the load; teardown compares against it.
  const prometheusBefore = scrapePrometheus()

  return {
    profile: PROFILE,
    seedPassword,
    startedAt,
    prometheusBefore,
    pairs,
    pairCount: SATURATION_PAIRS,
    auditPair,
    probe,
    probeMessages: 200,
    schedule: {
      rampDownAt: startedAt + RAMP_MS,
      bulkEndAt,
      probeEndAt: bulkEndAt - 60_000,
      verifyAt: bulkEndAt - 30_000,
      auditEndAt: bulkEndAt + RECOVERY_MS - 10_000,
      tailStartAt: bulkEndAt + RECOVERY_MS - 70_000,
      strict: STRICT_VALIDATION,
    },
  }
}

// T045 (FR-014): the saturation run must leave its mark on the SERVER side
// too — the 004 rate-limiting meter `webchat_send_rejected_total{reason=flood}`
// present on /actuator/prometheus and grown over the run (a valid saturation
// overload necessarily drives the 30/minute per-user buckets into 429).
export function teardown(data) {
  if (!data || data.profile !== 'saturation') return
  const before = data.prometheusBefore
  const after = scrapePrometheus()
  if (!after) {
    check(null, {
      'FR-014 server observability: /actuator/prometheus is scrapable after the run': () => false,
    })
    return
  }
  if (before) {
    const present = !!after.meters[FLOOD_METER]
    if (present) serverFloodMeterPresent.add(1)
    const floodBefore = sampleValue(before, FLOOD_METER, 'reason="flood"') || 0
    const floodAfter = sampleValue(after, FLOOD_METER, 'reason="flood"') || 0
    if (floodAfter > floodBefore) serverFloodRejectionsDelta.add(floodAfter - floodBefore)
    check(
      { present, floodBefore, floodAfter },
      {
        [`FR-014 server ${FLOOD_METER} present on /actuator/prometheus`]: s => s.present,
        [`FR-014 server ${FLOOD_METER}{reason=flood} grew over the run (${floodBefore} -> ${floodAfter})`]: s =>
          s.floodAfter > s.floodBefore,
      },
    )
  }
}

// ------------------------------------------------------ disconnect profile ---

export function disconnectProfile(data) {
  const bob = openSession(data.users.bob, data.seedPassword)
  const senders = data.users.senders.map(seed => openSession(seed, data.seedPassword))
  const chats = senders.map(sender => ensureChat(sender, bob.userId))

  const audit = newAudit()
  const cursors = {}
  for (const chatId of chats) cursors[chatId] = 0

  if (data.enabledPhases.includes('after-ack')) {
    disconnectAfterAck({ bob, sender: senders[0], chatId: chats[0], audit, cursors })
  }
  if (data.enabledPhases.includes('mid-sync')) {
    disconnectMidSync({ bob, sender: senders[1], chatId: chats[1], audit, cursors })
  }
  if (data.enabledPhases.includes('between-pages')) {
    disconnectBetweenPages({ bob, sender: senders[2], chatId: chats[2], audit, cursors })
  }
  idempotencyProbe({ bob, sender: senders[3], chatId: chats[3], audit, cursors })

  for (let round = 1; round <= data.rounds; round += 1) {
    bulkCatchUpRound({ round, bob, senders, chats, audit, cursors, messages: data.roundMessages })
  }

  // Final convergence: with the acked cursors nothing may be pending and the
  // whole run audit must be exactly-once / ordered (SC-001).
  refreshIfNeeded(bob)
  const finalCheck = http.post(
    SYNC_ENDPOINT,
    JSON.stringify({ cursors: cursorList(cursors), chatLimit: 50, messageLimit: 50 }),
    { headers: bearerHeaders(bob), tags: { op: 'sync' } },
  )
  const finalBody = safeJson(finalCheck.body) || {}
  check(finalBody, {
    'final №26 with acked cursors is empty (everything delivered)': b => b.chats !== undefined && b.chats.length === 0,
  })
  finalAudit(audit)
}

function disconnectAfterAck({ bob, sender, chatId, audit, cursors }) {
  const ids = [crypto.randomUUID(), crypto.randomUUID(), crypto.randomUUID()]
  let streamError = null

  // The receiver stream is open BEFORE the sends (the realtime path), then
  // force-closed right after the send acks return — before the frames settle.
  // Frames racing in before the close are applied; the rest must come back
  // through the pull cycle (SC-001: no loss, no double, order by seq).
  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${bob.accessToken}` }, timeout: SSE_TIMEOUT_SHORT },
    client => {
      client.on('event', event => {
        if (event.name !== 'message.created') return
        const payload = safeJson(event.data)
        if (!payload || payload.chatId !== chatId || !payload.message) return
        applyMessages(audit, chatId, [payload.message])
      })
      client.on('error', error => {
        streamError = describeSseError(error)
        client.close()
      })
      client.on('open', () => {
        for (const id of ids) {
          const response = sendFresh(sender, chatId, id, `k6 ${runId} after-ack ${id}`)
          const body = safeJson(response.body) || {}
          check(response, { 'after-ack: fresh send accepted (201)': r => r.status === 201 })
          if (response.status === 201 || response.status === 200) recordSent(audit, chatId, id, body.seq || 0)
        }
        // Dedup fastpath (№16 order: dedup before admission/flood): the retry
        // by the same id must return 200 with the same row, never a double.
        const dup = sendFresh(sender, chatId, ids[0], `k6 ${runId} after-ack ${ids[0]}`)
        const dupBody = safeJson(dup.body) || {}
        const expectedSeq = audit.chats[chatId].expected.get(ids[0])
        check(dup, {
          'after-ack: retry by the same id is 200 with the same seq': r =>
            r.status === 200 && dupBody.seq === expectedSeq,
        })
        client.close()
      })
    },
  )

  sleep(1)
  syncAll(bob, cursors, audit, {})
  phaseCheck('after-ack', audit, chatId, ids)
  check(streamError, { 'after-ack: no transport error on the broken stream': e => e === null })
}

function disconnectMidSync({ bob, sender, chatId, audit, cursors }) {
  const ids = []
  for (let i = 0; i < FLOOD_SAFE_BURST; i += 1) ids.push(crypto.randomUUID())
  postBurst({ sender, chatId, ids, audit, name: 'mid-sync' })
  sleep(0.3)

  // 29 pending with messageLimit 20: the first №26 delta carries 20 messages
  // and hasMore — the disconnect lands right after that page is applied and
  // acked, before the №15 continuation.
  const first = syncAll(bob, cursors, audit, { messageLimit: 20, abortAt: 'delta' })
  check(first, { 'mid-sync: cycle aborted after the delta page': r => r.aborted === 'delta' })
  sleep(1)

  syncAll(bob, cursors, audit, { messageLimit: 20 })
  phaseCheck('mid-sync', audit, chatId, ids)
}

function disconnectBetweenPages({ bob, sender, chatId, audit, cursors }) {
  const ids = []
  for (let i = 0; i < FLOOD_SAFE_BURST; i += 1) ids.push(crypto.randomUUID())
  postBurst({ sender, chatId, ids, audit, name: 'between-pages' })
  sleep(0.3)

  // messageLimit 10: №26 delta of 10 (hasMore) then №15 pages of 10 — the
  // disconnect lands between the first and the second history page.
  const first = syncAll(bob, cursors, audit, {
    messageLimit: 10,
    historyLimit: 10,
    abortAt: 'after-history-page',
  })
  check(first, { 'between-pages: cycle aborted between history pages': r => r.aborted === 'after-history-page' })
  sleep(1)

  syncAll(bob, cursors, audit, { messageLimit: 10, historyLimit: 10 })
  phaseCheck('between-pages', audit, chatId, ids)
}

function idempotencyProbe({ bob, sender, chatId, audit, cursors }) {
  // quickstart §3.1.3: a repeated №26 with pre-ack cursors returns the same
  // messages; the id-dedup keeps the render exactly-once.
  const ids = []
  for (let i = 0; i < 5; i += 1) ids.push(crypto.randomUUID())
  postBurst({ sender, chatId, ids, audit, name: 're-sync' })
  sleep(0.3)

  const cursorBefore = cursors[chatId]
  const payload = { cursors: [{ chatId, upToSeq: cursorBefore }], chatLimit: 50, messageLimit: 50 }
  const first = http.post(SYNC_ENDPOINT, JSON.stringify(payload), {
    headers: bearerHeaders(bob),
    tags: { op: 'sync' },
  })
  if (first.status !== 200) fail(`re-sync probe -> ${first.status} ${first.body}`)
  const firstDeltas = (safeJson(first.body) || {}).chats || []
  const delta = firstDeltas.find(d => d.chatId === chatId)
  check(delta, { 're-sync: the delta carries the pending messages': d => d && d.messages && d.messages.length === 5 })

  const second = http.post(SYNC_ENDPOINT, JSON.stringify(payload), {
    headers: bearerHeaders(bob),
    tags: { op: 'sync' },
  })
  if (second.status !== 200) fail(`re-sync probe (repeat) -> ${second.status} ${second.body}`)
  const repeatDelta = ((safeJson(second.body) || {}).chats || []).find(d => d.chatId === chatId)
  check(repeatDelta, { 're-sync: the repeat №26 returns the same page': d => d && d.messages && d.messages.length === 5 })

  applyMessages(audit, chatId, ((delta || {}).messages || []).concat((repeatDelta || {}).messages || []))
  const chat = chatAudit(audit, chatId)
  check(
    { rendered: ids.filter(id => chat.ids.has(id)).length, redeliveries: chat.redeliveries },
    {
      're-sync: each message rendered exactly once across the repeat': s => s.rendered === ids.length,
      're-sync: the repeat was a wire-level re-delivery (dedup by id)': s => s.redeliveries >= 5,
    },
  )
  // Ack now — the position advances and a further №26 must be empty for the chat.
  const lastSeq = Math.max(...((delta || {}).messages || []).map(m => m.seq), 0)
  if (lastSeq >= 1) flushAcks(bob, [{ chatId, upToSeq: lastSeq }])
  cursors[chatId] = Math.max(cursorBefore, lastSeq)
  const after = http.post(SYNC_ENDPOINT, JSON.stringify(payload), {
    headers: bearerHeaders(bob),
    tags: { op: 'sync' },
  })
  const afterDelta = (((safeJson(after.body) || {}).chats || []).find(d => d.chatId === chatId))
  check(afterDelta, { 're-sync: after the ack the chat is fully delivered': d => d === undefined })
}

function postBurst({ sender, chatId, ids, audit, name }) {
  for (const id of ids) {
    const response = sendFresh(sender, chatId, id, `k6 ${runId} ${name} ${id}`)
    const body = safeJson(response.body) || {}
    check(response, { [`${name}: fresh send accepted (201)`]: r => r.status === 201 })
    if (response.status === 201 || response.status === 200) recordSent(audit, chatId, id, body.seq || 0)
  }
}

function bulkCatchUpRound({ round, bob, senders, chats, audit, cursors, messages }) {
  // Refill the flood buckets before the first round (the phases above spent
  // tokens; 29 fresh tokens per sender need ~58 s of drip).
  if (round === 1) sleep(ROUND_SLEEP_S)

  // Access tokens live 5 min while the rounds span ~10 min — refresh every
  // session (receiver + senders) before it sends/syncs with an expired one.
  refreshIfNeeded(bob)
  for (const sender of senders) refreshIfNeeded(sender)

  const plan = distributeMessages(messages, senders.length, FLOOD_SAFE_BURST)
  const roundIds = []
  plan.forEach((count, index) => {
    const sender = senders[index]
    const chatId = chats[index]
    for (let i = 0; i < count; i += 1) {
      const id = crypto.randomUUID()
      roundIds.push(id)
      const response = sendFresh(sender, chatId, id, `k6 ${runId} bulk${round} ${id}`)
      const body = safeJson(response.body) || {}
      check(response, { [`bulk round ${round}: fresh send accepted (201)`]: r => r.status === 201 })
      if (response.status === 201 || response.status === 200) recordSent(audit, chatId, id, body.seq || 0)
    }
  })
  sleep(0.3)

  const result = syncAll(bob, cursors, audit, {})
  catchup200Ms.add(result.durationMs)
  check(result, {
    [`bulk round ${round}: catch-up of ${roundIds.length} messages within SC-008 (<= 10 s)`]: r =>
      r.durationMs <= 10_000,
  })

  const perChatMissing = {}
  for (const chatId of Object.keys(audit.chats)) {
    perChatMissing[chatId] = chatAudit(audit, chatId)
  }
  const missing = roundIds.filter(id => {
    for (const chatId of Object.keys(perChatMissing)) {
      if (perChatMissing[chatId].ids.has(id)) return false
    }
    return true
  }).length
  check(missing, { [`bulk round ${round}: 0 losses across >= 3 chats`]: m => m === 0 })

  sleep(ROUND_SLEEP_S)
}

// ------------------------------------------------------ saturation profile ---

// One VU == one sender→receiver pair (the bulk scenario pins maxVUs to the
// pair count, so the mapping below is a bijection for the VUs running this
// exec; other scenarios never touch this state). Every accepted id is recorded
// on the same VU that later replays the receiver's pull cycle — the exactly-
// once audit needs no cross-VU sharing.
let bulkPair = null

export function saturateSend(data) {
  if (!bulkPair) {
    const pairIndex = (__VU - 1) % data.pairCount
    const plan = data.pairs[pairIndex]
    const sender = openSession(plan.sender, data.seedPassword)
    const chatId = ensureChat(sender, openSession(plan.receiver, data.seedPassword).userId)
    bulkPair = {
      sender,
      receiverSeed: plan.receiver,
      chatId,
      audit: newAudit(),
      cursors: { [chatId]: 0 },
      verified: false,
    }
  }

  if (Date.now() >= data.schedule.verifyAt) {
    if (!bulkPair.verified) {
      const receiver = openSession(bulkPair.receiverSeed, data.seedPassword)
      syncAll(receiver, bulkPair.cursors, bulkPair.audit, {})
      finalAudit(bulkPair.audit)
      bulkPair.verified = true
    }
    return
  }

  refreshIfNeeded(bulkPair.sender)
  const clientMessageId = crypto.randomUUID()
  const response = sendFresh(bulkPair.sender, bulkPair.chatId, clientMessageId, `k6 ${runId} bulk ${clientMessageId}`, 'bulk_send')
  const kind = classifySend(response)
  if (kind === 'accepted' || kind === 'dedup') {
    const body = safeJson(response.body) || {}
    recordSent(bulkPair.audit, bulkPair.chatId, clientMessageId, body.seq || 0)
    msgAcceptedTotal.add(1)
    if (kind === 'dedup') dedupRetryHits.add(1)
    return
  }
  if (kind === 'server_busy' || kind === 'flood_limit') {
    msgRejectedTotal.add(1, { kind })
    const retryAfter = retryAfterSeconds(response)
    if (retryAfter < 1) missingRetryAfterTotal.add(1)
    return
  }
  silentFailuresTotal.add(1)
}

export function auditLoop(data) {
  const { sender: senderSeed, receiver: receiverSeed } = data.auditPair
  const sender = openSession(senderSeed, data.seedPassword)
  const receiver = openSession(receiverSeed, data.seedPassword)
  const chatId = ensureChat(sender, receiver.userId)

  const audit = newAudit()
  const cursors = { [chatId]: 0 }
  const state = {
    mode: 'steady', // steady -> overloaded (first 503/429) -> recovery (sustained < 500 ms after ramp-down)
    firstRejectionAt: 0,
    recoveredAt: 0,
    consecutiveGood: 0,
  }
  const schedule = data.schedule
  const deadline = schedule.auditEndAt

  while (Date.now() < deadline - 15_000) {
    refreshIfNeeded(sender)
    refreshIfNeeded(receiver)
    runAuditCycle({ sender, receiver, chatId, audit, cursors, state, schedule, deadline })
    sleep(AUDIT_CYCLE_SLEEP_S)
  }

  if (state.firstRejectionAt) {
    const regimeEnd = state.recoveredAt || Date.now()
    overloadedDurationS.add((regimeEnd - state.firstRejectionAt) / 1000)
    auditTimeToFirstShedS.add((state.firstRejectionAt - data.startedAt) / 1000)
  }
  if (schedule.strict) {
    check(
      state,
      {
        '005 run validity: overloaded regime >= 60 s from the first shed signal':
          s => s.firstRejectionAt !== 0 && (s.recoveredAt || Date.now()) - s.firstRejectionAt >= 60_000,
      },
      { validity: 'strict' },
    )
  }
  finalAudit(audit)
}

function runAuditCycle({ sender, receiver, chatId, audit, cursors, state, schedule, deadline }) {
  const clientMessageId = crypto.randomUUID()
  const timing = {
    accepted: false,
    acceptedAt: 0,
    attemptStartedAt: 0,
    frameSeen: false,
    recoveredBySync: false,
    abandoned: false,
  }

  sse.open(
    EVENTS_ENDPOINT,
    { headers: { Authorization: `Bearer ${receiver.accessToken}` }, timeout: SSE_TIMEOUT_AUDIT },
    client => {
      client.on('event', event => {
        if (event.name !== 'message.created') return
        const payload = safeJson(event.data)
        const message = payload && payload.message
        if (!message || message.id !== clientMessageId) return
        timing.frameSeen = true
        const now = Date.now()
        // The frame can only exist after the accepting POST reached the
        // server; fall back to the attempt start if the frame races the ack.
        const acceptedAt = timing.acceptedAt || timing.attemptStartedAt
        const latencyMs = acceptedAt ? now - acceptedAt : 0
        if (state.mode === 'overloaded' && now > schedule.rampDownAt) {
          if (latencyMs < 500) {
            state.consecutiveGood += 1
            if (state.consecutiveGood >= 3) {
              state.mode = 'recovery'
              state.recoveredAt = now
            }
          } else {
            state.consecutiveGood = 0
          }
        }
        if (acceptedAt) {
          deliveryMs.add(latencyMs, { mode: state.mode })
          if (now >= schedule.tailStartAt) recoveryTailMs.add(latencyMs)
        }
        applyMessages(audit, chatId, [message])
        client.close()
      })
      client.on('error', () => {
        client.close()
      })
      client.on('open', () => {
        // Transparent queue semantics (FR-009/FR-010): rejections are retried
        // by the same id after Retry-After; every attempt is paced so the
        // audit sender never touches its own flood limit. The latency clock
        // starts at the attempt that the server actually accepts.
        for (;;) {
          if (Date.now() > deadline) {
            timing.abandoned = true
            client.close()
            return
          }
          const attemptStartedAt = Date.now()
          timing.attemptStartedAt = attemptStartedAt
          const response = sendFresh(
            sender,
            chatId,
            clientMessageId,
            `k6 ${runId} audit ${clientMessageId}`,
            'audit_send',
          )
          const kind = classifySend(response)
          if (kind === 'accepted' || kind === 'dedup') {
            timing.accepted = true
            timing.acceptedAt = attemptStartedAt
            const body = safeJson(response.body) || {}
            recordSent(audit, chatId, clientMessageId, body.seq || 0)
            msgAcceptedTotal.add(1)
            if (kind === 'dedup') dedupRetryHits.add(1)
            return
          }
          if (kind === 'server_busy' || kind === 'flood_limit') {
            msgRejectedTotal.add(1, { kind })
            if (!state.firstRejectionAt) state.firstRejectionAt = Date.now()
            // A rejection after a (partial) recovery means the regime is still
            // or again active — flip back so the p99<=2 s window keeps tagging.
            if (state.mode === 'steady') {
              state.mode = 'overloaded'
            } else if (state.mode === 'recovery') {
              state.mode = 'overloaded'
              state.recoveredAt = 0
            }
            state.consecutiveGood = 0
            const retryAfter = retryAfterSeconds(response)
            if (retryAfter < 1) missingRetryAfterTotal.add(1)
            sleep(Math.max(retryAfter, MIN_SEND_PACE_S))
            continue
          }
          silentFailuresTotal.add(1)
          sleep(MIN_SEND_PACE_S)
        }
      })
    },
  )

  if (timing.accepted && !timing.frameSeen) {
    // The frame was lost or the stream broke — the pull cycle must recover the
    // message (US1); the loss threshold is enforced by finalAudit either way.
    syncAll(receiver, cursors, audit, {})
    timing.recoveredBySync = chatAudit(audit, chatId).ids.has(clientMessageId)
  }

  if (!timing.abandoned) {
    check(timing, {
      'audit pair: send accepted (201/200) within the cycle': t => t.accepted,
      'audit pair: frame received over SSE or recovered by the pull cycle':
        t => t.frameSeen || t.recoveredBySync || !t.accepted,
    })
  }
}

export function catchupProbe(data) {
  const group = data.probe
  const receiver = openSession(group.receiver, data.seedPassword)
  const senders = group.senders.map(seed => openSession(seed, data.seedPassword))
  const chats = senders.map(sender => ensureChat(sender, receiver.userId))

  const audit = newAudit()
  const cursors = {}
  for (const chatId of chats) cursors[chatId] = 0

  const deadline = data.schedule.probeEndAt
  let round = 0
  while (Date.now() < deadline - PROBE_ROUND_BUDGET_MS) {
    round += 1
    const complete = probeRound({ round, receiver, senders, chats, audit, cursors, deadline, messages: data.probeMessages })
    if (!complete) probeStarvedRounds.add(1)
    sleep(PROBE_ROUND_SLEEP_S)
  }
  finalAudit(audit)
}

function probeRound({ round, receiver, senders, chats, audit, cursors, deadline, messages }) {
  const plan = distributeMessages(messages, senders.length, FLOOD_SAFE_BURST)
  const pending = plan.map((count, index) => {
    const ids = []
    for (let i = 0; i < count; i += 1) ids.push(crypto.randomUUID())
    return { senderIndex: index, chatId: chats[index], ids }
  })

  // Batched bursts per sender (transparent retries by the same id on 503/429 —
  // the outbox semantics of FR-009, exercised here against the real shed path).
  const roundDeadline = Date.now() + PROBE_ROUND_BUDGET_MS
  while (pending.some(p => p.ids.length > 0) && Date.now() < roundDeadline && Date.now() < deadline) {
    const requests = []
    const owners = []
    for (const part of pending) {
      if (!part.ids.length) continue
      const sender = senders[part.senderIndex]
      refreshIfNeeded(sender)
      for (const id of part.ids) {
        requests.push([
          'POST',
          `${API_BASE}/chats/${part.chatId}/messages`,
          JSON.stringify({ clientMessageId: id, text: `k6 ${runId} probe${round} ${id}` }),
          { headers: bearerHeaders(sender), tags: { op: 'probe_send' } },
        ])
        owners.push(part)
      }
    }
    if (!requests.length) break
    const responses = http.batch(requests)
    let maxRetryAfter = 1
    let index = 0
    for (const part of pending) {
      if (!part.ids.length) continue
      const kept = []
      for (const id of part.ids) {
        const response = responses[index]
        index += 1
        const kind = classifySend(response)
        if (kind === 'accepted' || kind === 'dedup') {
          const body = safeJson(response.body) || {}
          recordSent(audit, part.chatId, id, body.seq || 0)
          msgAcceptedTotal.add(1)
          if (kind === 'dedup') dedupRetryHits.add(1)
        } else if (kind === 'server_busy' || kind === 'flood_limit') {
          msgRejectedTotal.add(1, { kind })
          const retryAfter = retryAfterSeconds(response)
          if (retryAfter < 1) missingRetryAfterTotal.add(1)
          if (retryAfter > maxRetryAfter) maxRetryAfter = retryAfter
          kept.push(id)
        } else {
          silentFailuresTotal.add(1)
          kept.push(id)
        }
      }
      part.ids = kept
    }
    if (pending.some(p => p.ids.length > 0)) sleep(Math.max(maxRetryAfter, 1))
  }

  const starved = pending.some(p => p.ids.length > 0)
  if (starved) return false

  sleep(0.3)
  const result = syncAll(receiver, cursors, audit, {})
  catchupOverloadedMs.add(result.durationMs)
  check(result, {
    [`probe round ${round}: catch-up of ${messages} messages within SC-008 degraded (<= 30 s)`]: r =>
      r.durationMs <= 30_000,
  })
  return true
}

// --------------------------------------------------------------- summary ---

export function handleSummary(data) {
  const metrics = data.metrics
  // k6 v2 lists declared-but-never-fired counters without `.count` — read
  // defensively so a perfect run prints explicit zeros, not `undefined`.
  const countOf = name => {
    const metric = metrics[name]
    if (!metric) return 0
    return metric.count ?? (metric.values ? metric.values.count : undefined) ?? 0
  }
  const accepted = countOf('msg_accepted_total')
  const rejectedBusy = countOf('msg_rejected_total{kind:server_busy}')
  const rejectedFlood = countOf('msg_rejected_total{kind:flood_limit}')
  const lines = [`=== 005 delivery-resilience (${PROFILE} profile) ===`]

  if (PROFILE === 'disconnect') {
    const catchup = metrics.catchup_200_ms ? metrics.catchup_200_ms.values : null
    lines.push(`accepted sends: ${accepted}`)
    if (catchup) lines.push(`catch-up of 200 (p95): ${catchup['p(95)'] !== undefined ? catchup['p(95)'] : catchup.max} ms (budget <= 10000)`)
    lines.push(`wire re-deliveries deduped by id: ${countOf('wire_redelivery_total')}`)
    lines.push(`audit: losses=${countOf('audit_losses_total')}, doubles=${countOf('audit_duplicates_total')}, order violations=${countOf('audit_order_violations_total')}`)
  } else {
    const activeWindowS = (RAMP_MS + HOLD_MS) / 1000
    const sustained = Math.round(accepted / activeWindowS)
    const overload = metrics.overloaded_duration_s ? metrics.overloaded_duration_s.values : null
    const firstShed = metrics.audit_time_to_first_shed_s ? metrics.audit_time_to_first_shed_s.values.min : null
    const steady = metrics['delivery_ms{mode:steady}'] ? metrics['delivery_ms{mode:steady}'].values : null
    const degraded = metrics['delivery_ms{mode:overloaded}'] ? metrics['delivery_ms{mode:overloaded}'].values : null
    const tail = metrics.recovery_tail_ms ? metrics.recovery_tail_ms.values : null
    const catchup = metrics.catchup_overloaded_ms ? metrics.catchup_overloaded_ms.values : null
    lines.push(`target ${TARGET_RPS} msg/s (ramp ${formatDuration(RAMP_MS)}, hold ${formatDuration(HOLD_MS)}, recovery window ${formatDuration(RECOVERY_MS)}), pairs ${SATURATION_PAIRS}`)
    lines.push(`accepted: ${accepted} (~${sustained} msg/s over ramp+hold; flood ceiling of this stand: ~${Math.floor(SATURATION_PAIRS / 2)} msg/s)`)
    lines.push(`rejections: 503 server_busy=${rejectedBusy}, 429 flood_limit=${rejectedFlood}, silent=${countOf('silent_failures_total')}, missing Retry-After=${countOf('missing_retry_after_total')}`)
    lines.push(`server FR-014 observability (T045): flood meter present=${countOf('server_flood_meter_present')}/1, server-side flood rejections over the run=${countOf('server_flood_rejections_delta_total')}`)
    if (firstShed !== null) lines.push(`first shed signal after ~${firstShed.toFixed(1)} s`)
    if (overload) lines.push(`overloaded regime: ${overload.max !== undefined ? overload.max.toFixed(1) : '?'} s (005 validity bar: >= 60)`)
    if (steady) lines.push(`delivery steady p99: ${steady['p(99)'] !== undefined ? steady['p(99)'] : steady.max} ms (budget 500)`)
    if (degraded) lines.push(`delivery overloaded p99: ${degraded['p(99)'] !== undefined ? degraded['p(99)'] : degraded.max} ms (budget 2000)`)
    if (catchup) lines.push(`catch-up 200 degraded (p95): ${catchup['p(95)'] !== undefined ? catchup['p(95)'] : catchup.max} ms (budget 30000)`)
    if (tail) lines.push(`recovery tail p99 (last 60 s of the window): ${tail['p(99)'] !== undefined ? tail['p(99)'] : tail.max} ms (budget 500)`)
    if (STRICT_VALIDATION) {
      lines.push(`strict validity: peak accepted flow ~${sustained} msg/s (bar >= 5000; raise K6_TARGET_RPS/K6_SATURATION_PAIRS if below)`)
    }
    lines.push(`audit: losses=${countOf('audit_losses_total')}, doubles=${countOf('audit_duplicates_total')}, order violations=${countOf('audit_order_violations_total')}`)
  }

  return { stdout: `\n${lines.join('\n')}\n` }
}
