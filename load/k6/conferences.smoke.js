import http from 'k6/http'
import { check, fail, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

/**
 * 009-video-conferences T058 — control-plane smoke (quickstart §4, research §13;
 * SC-003/SC-004 legs that are exercisable without WebRTC media).
 *
 * Two independent profiles in one script (K6_PROFILE=all|control-plane|sc-004):
 *
 *   control-plane — one VU (carol + dave, seeded per run) driving the member and
 *     guest lifecycle over HTTP only (no media plane is ever connected):
 *       №44 create (mode alternates video/audio) → №45 get → №46 me →
 *       №47 join x2 (member tickets, identity u:…) → №52 guest invite →
 *       №54 invite-info {valid:true} → every 3rd cycle №55 join-by-invite
 *       (guest ticket, identity g:…) → №48 end.
 *     The FR-015 conference_full quota is NOT exercisable here: the pre-check
 *     counts LIVE LiveKit participants (JoinTokenService), and an HTTP client
 *     never connects — that leg stays with IT T021/T048 (real LiveKit), the
 *     media-plane load with `lk load-test` (T068).
 *
 *   sc-004 — SC-004 flood profile (quickstart §3.4 step 7):
 *     background lane (1 VU, whole run): №12 GET /chats/{id} x4 + №27
 *     POST /groups x1 per ~500 ms batch → ~10 rps total, half №12, a fifth №27;
 *     flood lanes (constant-arrival-rate, own spoofed XFF so their 429s never
 *     touch the other lanes' buckets): №54 at 60 req/min/IP and №55 at
 *     20 req/min/IP — exactly 2x the default T047 buckets (30/1m and 10/1m,
 *     intervally refilled: the N+1-th request inside a window is
 *     deterministically the uniform 429).
 *
 *     The run is split into a baseline window (no flood, default 60 s) and the
 *     flood window (default 5 m); the background VU samples every №12/№27
 *     duration into per-phase series and, at the end of the run, enforces the
 *     SC-004 criterion IN-RUN: Δp99(flood vs baseline) <= 10 % per op and on
 *     the aggregate bg_op_ms series (a final check failing the
 *     checks{scenario:background} threshold). For cross-run parity with
 *     presence.smoke.js/social-signals.smoke.js an OPTIONAL env gate exists:
 *     run once with K6_SC004_FLOOD=false, take bg_op_ms p(99), then start the
 *     full run with K6_BG_P99_BASELINE_MS=<that value> to also gate against a
 *     foreign baseline run.
 *
 * Thresholds (quickstart §3.4 step 7 / §4):
 *   SC-004  flood legs answer 200/404/429 only (never 5xx) and both buckets do
 *           engage: http_reqs{scenario:flood54,status:429} > 0, same for flood55;
 *           429 carries Retry-After >= 1s
 *   SC-004  Δp99 of the background №12/№27 under flood <= +10 % (in-run check;
 *           optional K6_BG_P99_BASELINE_MS cross-run threshold)
 *   health  №12/№27 p95 <= 3 s (the 004 SC-004 dialog-open budget); no 5xx
 *           anywhere; http_req_failed == 0
 *
 * The script needs NO k6/x/sse extension (no SSE lane — SC-006 delivery is
 * covered by IT T057), so the stock runner of quickstart §4 works:
 *   docker run --rm -i grafana/k6 run - < load/k6/conferences.smoke.js
 * On Linux against a localhost stand add --network host (and -e K6_BASE_URL
 * -e K6_MAILPIT_URL when they differ). Seeding uses the Mailpit of the local
 * compose stand exactly like messaging.smoke.js.
 * Env: K6_BASE_URL (default http://localhost:8080), K6_MAILPIT_URL
 * (default http://localhost:8025), K6_PROFILE (all|control-plane|sc-004,
 * default all), K6_SMOKE_DURATION (flood window, k6-style single-unit value,
 * default 5m), K6_SC004_BASELINE_MS (default 60000), K6_SC004_FLOOD
 * (default true — set false for a baseline-only run), K6_BG_P99_BASELINE_MS
 * (optional cross-run p99 gate, milliseconds).
 */

const BASE_URL = __ENV.K6_BASE_URL || 'http://localhost:8080'
const MAILPIT_URL = __ENV.K6_MAILPIT_URL || 'http://localhost:8025'

const API_BASE = `${BASE_URL}/api/v1`
const REGISTER_ENDPOINT = `${API_BASE}/auth/register`
const CONFIRM_ENDPOINT = `${API_BASE}/auth/register/confirm`
const SET_PASSWORD_ENDPOINT = `${API_BASE}/auth/register/password`
const LOGIN_ENDPOINT = `${API_BASE}/auth/login`
const REFRESH_ENDPOINT = `${API_BASE}/auth/refresh`
const CHATS_ENSURE_ENDPOINT = `${API_BASE}/chats/ensure`
const CHAT_ENDPOINT = `${API_BASE}/chats`
const GROUPS_ENDPOINT = `${API_BASE}/groups`
const CONFERENCES_ENDPOINT = `${API_BASE}/conferences`

// T047 defaults (api-contract 009 §3, per-env `conferences.rate-limit.*`):
// №54 30/1m, №55 10/1m, intervally refilled. The flood drives exactly 2x —
// the quickstart §3.4 step 7 canon (60 and 20 req/min/IP).
const FLOOD54_RATE_PER_MIN = 60
const FLOOD55_RATE_PER_MIN = 20

// Background shaping: one batch = 4x №12 + 1x №27 every 500 ms → ~10 rps total.
const BG_BATCH_CHAT_OPENS = 4
const BG_BATCH_PERIOD_MS = 500
const BG_MIN_SAMPLES_PER_PHASE = 30

const CP_CYCLE_SLEEP_S = 4
const CP_GUEST_LEG_EVERY_N_CYCLES = 3

const SEED_ROLES = ['bgalice', 'bgbob', 'cpcarol', 'cpdave']
const VERIFICATION_LINK_PATTERN = /confirm-registration\?token=([A-Za-z0-9_-]{43})/
const MAILPIT_WAIT_MS = 90_000
const ACCESS_TOKEN_REFRESH_MARGIN_MS = 60_000

// k6 re-evaluates module scope per VU: Date.now()-derived values differ
// between setup() and scenario executors, so run identity and the flood IPs
// travel via setup data (the flood lanes MUST share one IP per route or the
// 2x bucket would split across VUs and never trip).
const runId = Date.now().toString(36)

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 204, 404, 429))

// ---------------------------------------------------------------- metrics ---

// The SC-004 comparison series: every №12/№27 duration, tagged op+phase. The
// per-phase sub-metrics (`bg_op_ms{phase:baseline}` / `{phase:flood}`) land in
// the end-of-run summary for the operator; the in-run gate rides a final
// check of the background scenario (see backgroundLoop).
const bgOpMs = new Trend('bg_op_ms')
const cpCyclesTotal = new Counter('conferences_cp_cycles_total')
const guestTicketsTotal = new Counter('conferences_cp_guest_tickets_total')
const floodAdmissions54Total = new Counter('conferences_flood54_admissions_total')
const floodAdmissions55Total = new Counter('conferences_flood55_admissions_total')

// ----------------------------------------------------------------- timing ---

function parseMsEnv(name, fallbackMs) {
  const raw = __ENV[name]
  if (!raw) return fallbackMs
  const value = parseFloat(String(raw).trim())
  if (!Number.isFinite(value) || value <= 0) fail(`invalid ${name} "${raw}" (expected positive milliseconds)`)
  return value
}

function parseDurationMs(value, fallbackMs) {
  if (!value) return fallbackMs
  const match = String(value).trim().match(/^(\d+(?:\.\d+)?)(ms|s|m|h)?$/)
  if (!match) fail(`invalid K6_SMOKE_DURATION "${value}" (expected e.g. 5m, 90s, 45000ms)`)
  const amount = parseFloat(match[1])
  if (match[2] === 'ms') return amount
  if (match[2] === 'm') return amount * 60_000
  if (match[2] === 'h') return amount * 3_600_000
  return amount * 1000
}

const FLOOD_MS = parseDurationMs(__ENV.K6_SMOKE_DURATION, 300_000)
const BASELINE_MS = Math.min(parseMsEnv('K6_SC004_BASELINE_MS', 60_000), Math.floor(FLOOD_MS / 2))
const RUN_MS = BASELINE_MS + FLOOD_MS
const FLOOD_ENABLED = String(__ENV.K6_SC004_FLOOD || 'true').trim().toLowerCase() !== 'false'
const PROFILE = String(__ENV.K6_PROFILE || 'all').trim().toLowerCase()
if (PROFILE !== 'all' && PROFILE !== 'control-plane' && PROFILE !== 'sc-004') {
  fail(`invalid K6_PROFILE "${__ENV.K6_PROFILE}" (expected all|control-plane|sc-004)`)
}

const BG_P99_BASELINE_MS = parseMsEnv('K6_BG_P99_BASELINE_MS', 0)

// ---------------------------------------------------------------- options ---

const thresholds = {
  http_req_failed: ['rate==0'],
  'http_reqs{status:/^5/}': ['count==0'],
}

if (PROFILE !== 'control-plane') {
  thresholds['http_req_duration{op:chat_open}'] = ['p(95)<3000']
  thresholds['http_req_duration{op:group_create}'] = ['p(95)<3000']
  thresholds['checks{scenario:background}'] = ['rate==1']
  thresholds['checks{scenario:flood54}'] = ['rate==1']
  thresholds['checks{scenario:flood55}'] = ['rate==1']
  thresholds['http_reqs{scenario:flood54,status:429}'] = ['count>0']
  thresholds['http_reqs{scenario:flood55,status:429}'] = ['count>0']
  if (BG_P99_BASELINE_MS > 0) {
    thresholds.bg_op_ms = [`p(99)<=${Math.ceil(BG_P99_BASELINE_MS * 1.1)}`]
  }
}
if (PROFILE !== 'sc-004') {
  thresholds['checks{scenario:control-plane}'] = ['rate==1']
}

const scenarios = {}
if (PROFILE !== 'control-plane') {
  scenarios.background = {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    exec: 'backgroundLoop',
    gracefulStop: '30s',
  }
  if (FLOOD_ENABLED) {
    scenarios.flood54 = {
      executor: 'constant-arrival-rate',
      rate: FLOOD54_RATE_PER_MIN,
      timeUnit: '1m',
      startTime: `${BASELINE_MS}ms`,
      duration: `${FLOOD_MS}ms`,
      preAllocatedVUs: 1,
      maxVUs: 2,
      exec: 'floodInviteInfo',
      gracefulStop: '10s',
    }
    scenarios.flood55 = {
      executor: 'constant-arrival-rate',
      rate: FLOOD55_RATE_PER_MIN,
      timeUnit: '1m',
      startTime: `${BASELINE_MS}ms`,
      duration: `${FLOOD_MS}ms`,
      preAllocatedVUs: 1,
      maxVUs: 2,
      exec: 'floodJoinByInvite',
      gracefulStop: '10s',
    }
  }
}
if (PROFILE !== 'sc-004') {
  scenarios['control-plane'] = {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    exec: 'controlPlaneLoop',
    startTime: `${BASELINE_MS}ms`,
    gracefulStop: '30s',
  }
}

export const options = { scenarios, thresholds }

// ---------------------------------------------------------------- helpers ---

function jsonHeaders() {
  return { 'Content-Type': 'application/json' }
}

function requestHeaders(session, sourceIp) {
  const headers = { 'Content-Type': 'application/json' }
  if (session) headers.Authorization = `Bearer ${session.accessToken}`
  if (sourceIp) headers['X-Forwarded-For'] = sourceIp
  return headers
}

function postJson(url, payload, sourceIp) {
  return http.post(url, JSON.stringify(payload), { headers: requestHeaders(null, sourceIp) })
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

function mailpitMessageSummaries() {
  const response = http.get(`${MAILPIT_URL}/api/v1/messages?limit=100`)
  if (response.status !== 200) fail(`Mailpit API ${MAILPIT_URL} answered ${response.status}`)
  try {
    return response.json().messages || []
  } catch (error) {
    fail(`Mailpit API ${MAILPIT_URL} returned a non-JSON body: ${error}`)
  }
}

function openSession(seed, password) {
  const response = postJson(LOGIN_ENDPOINT, { identifier: seed.username, password }, seed.sourceIp)
  if (response.status !== 200) fail(`login ${seed.role} -> ${response.status} ${response.body}`)
  const body = response.json()
  return {
    role: seed.role,
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
  if (response.status !== 200) fail(`refresh ${session.role} -> ${response.status} ${response.body}`)
  const body = response.json()
  session.accessToken = body.accessToken
  session.refreshToken = body.refreshToken
  session.expiresAt = Date.now() + body.expiresInSec * 1000
}

// Nearest-rank p99 of a plain millisecond sample array.
function p99(samples) {
  if (!samples.length) return 0
  const sorted = samples.slice().sort((a, b) => a - b)
  const rank = Math.max(1, Math.ceil(0.99 * sorted.length))
  return sorted[rank - 1]
}

function inviteTokenOf(inviteUrl) {
  const token = String(inviteUrl || '').split('/').pop()
  return token && token.length > 0 ? token : null
}

// ------------------------------------------------------------------ setup ---

export function setup() {
  const seedPassword = `K6conf-${runId}-Smoke1`
  const seedBase = Date.now()
  const thirdOctet = ((seedBase >>> 8) & 0xff) || 1
  const fourthOctet = seedBase & 0xff
  const users = []
  SEED_ROLES.forEach((role, i) => {
    const user = {
      role,
      username: `k6conf${runId}${role}`,
      email: `k6conf.${runId}.${role}@example.com`,
      // one distinct spoofed source per seed user: register/login IP buckets
      // (002) never see the whole seed batch as one source
      sourceIp: `198.18.${thirdOctet}.${((fourthOctet + i) % 254) + 1}`,
    }
    const registered = postJson(REGISTER_ENDPOINT, { username: user.username, email: user.email }, user.sourceIp)
    if (registered.status !== 202) fail(`seed register ${user.username} -> ${registered.status} ${registered.body}`)
    users.push(user)
  })

  const deadline = Date.now() + MAILPIT_WAIT_MS
  for (;;) {
    for (const summary of mailpitMessageSummaries()) {
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

  // Flood sources travel via setup data: every flood VU of a lane must resolve
  // to the SAME spoofed IP or the 2x bucket would split across VUs.
  return {
    users,
    seedPassword,
    flood54Ip: `198.18.${((thirdOctet + 53) % 254) + 1}.54`,
    flood55Ip: `198.18.${((thirdOctet + 53) % 254) + 1}.55`,
  }
}

// --------------------------------------------------------- sc-004: background ---

export function backgroundLoop(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const alice = openSession(seeds.bgalice, data.seedPassword)
  const bob = openSession(seeds.bgbob, data.seedPassword)

  const ensured = http.post(CHATS_ENSURE_ENDPOINT, JSON.stringify({ peerUserId: bob.userId }), {
    headers: requestHeaders(alice),
  })
  if (ensured.status !== 200 && ensured.status !== 201) fail(`background ensure chat -> ${ensured.status} ${ensured.body}`)
  const chatId = ensured.json('chatId')

  const samples = {
    baseline: { chat_open: [], group_create: [], all: [] },
    flood: { chat_open: [], group_create: [], all: [] },
  }
  let batchCounter = 0
  const startedAt = Date.now()
  const deadline = startedAt + RUN_MS

  while (Date.now() < deadline) {
    const phase = Date.now() - startedAt < BASELINE_MS ? 'baseline' : 'flood'
    const batchStartedAt = Date.now()
    for (let i = 0; i < BG_BATCH_CHAT_OPENS; i += 1) {
      refreshIfNeeded(alice)
      const opened = http.get(`${CHAT_ENDPOINT}/${chatId}`, {
        headers: requestHeaders(alice),
        tags: { op: 'chat_open' },
      })
      check(opened, { '№12 chat open is 200': r => r.status === 200 })
      samples[phase].chat_open.push(opened.timings.duration)
      samples[phase].all.push(opened.timings.duration)
      bgOpMs.add(opened.timings.duration, { op: 'chat_open', phase })
    }
    refreshIfNeeded(alice)
    batchCounter += 1
    const group = http.post(
      GROUPS_ENDPOINT,
      JSON.stringify({ title: `k6conf bg ${runId} #${batchCounter}` }),
      { headers: requestHeaders(alice), tags: { op: 'group_create' } },
    )
    check(group, { '№27 group create is 201': r => r.status === 201 })
    samples[phase].group_create.push(group.timings.duration)
    samples[phase].all.push(group.timings.duration)
    bgOpMs.add(group.timings.duration, { op: 'group_create', phase })

    const batchElapsed = Date.now() - batchStartedAt
    if (batchElapsed < BG_BATCH_PERIOD_MS) sleep((BG_BATCH_PERIOD_MS - batchElapsed) / 1000)
  }

  // SC-004 verdict (in-run): Δp99 of the background ops under the flood must
  // stay within +10 % of the flood-free baseline. Nearest-rank p99 over the
  // VU-local samples (the lane is a single VU, so the sets are exact).
  const report = ['SC-004 background p99 (ms):']
  let deltaOk = true
  let enoughSamples = true
  for (const series of ['chat_open', 'group_create', 'all']) {
    const base = samples.baseline[series]
    const flood = samples.flood[series]
    if (base.length < BG_MIN_SAMPLES_PER_PHASE || flood.length < BG_MIN_SAMPLES_PER_PHASE) {
      enoughSamples = false
      continue
    }
    const baseP99 = p99(base)
    const floodP99 = p99(flood)
    const deltaPct = baseP99 > 0 ? ((floodP99 - baseP99) / baseP99) * 100 : 0
    report.push(`  ${series}: baseline=${baseP99} flood=${floodP99} delta=${deltaPct.toFixed(1)}%`)
    if (deltaPct > 10) deltaOk = false
  }
  console.log(report.join('\n'))
  check(enoughSamples, {
    [`SC-004 phases have >= ${BG_MIN_SAMPLES_PER_PHASE} samples each (run longer)`]: v => v,
  })
  check(samples, {
    'SC-004 Δp99 of background №12/№27 under flood <= 10%': () => deltaOk,
  })
}

// ------------------------------------------------------------- sc-004: flood ---

// №54 from one spoofed IP at 2x the 30/1m default bucket: the first ~30 hits
// of a window are admitted (garbage nonce → uniform {valid:false}, no reason —
// the non-disclosure edge), the rest is the deterministic 429.
export function floodInviteInfo(data) {
  const token = `flood54-${runId}-${Math.random().toString(36).slice(2)}`
  const response = http.get(
    `${CONFERENCES_ENDPOINT}/invite-info?inviteToken=${token}`,
    { headers: { 'X-Forwarded-For': data.flood54Ip }, tags: { op: 'flood_invite_info' } },
  )
  if (response.status === 200) floodAdmissions54Total.add(1)
  check(response, {
    '№54 flood answers 200/429 only': r => r.status === 200 || r.status === 429,
    '№54 flood 429 carries Retry-After >= 1s': r => r.status !== 429 || retryAfterSeconds(r) >= 1,
    '№54 flood 200 is uniform {valid:false}': r => {
      if (r.status !== 200) return true
      const body = safeJson(r.body)
      return body !== null && body.valid === false && body.reason === undefined
    },
  })
}

// №55 from one spoofed IP at 2x the 10/1m default bucket; the garbage nonce
// never reaches a ticket mint — admitted hits answer the uniform 404.
export function floodJoinByInvite(data) {
  const response = http.post(
    `${CONFERENCES_ENDPOINT}/join-by-invite`,
    JSON.stringify({ inviteToken: `flood55-${runId}-${Math.random().toString(36).slice(2)}`, name: 'k6 flood' }),
    { headers: { 'X-Forwarded-For': data.flood55Ip }, tags: { op: 'flood_join_by_invite' } },
  )
  if (response.status === 200 || response.status === 404) floodAdmissions55Total.add(1)
  check(response, {
    '№55 flood answers 404/429 only': r => r.status === 404 || r.status === 429,
    '№55 flood 429 carries Retry-After >= 1s': r => r.status !== 429 || retryAfterSeconds(r) >= 1,
  })
}

// -------------------------------------------------------- control-plane lane ---

export function controlPlaneLoop(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const carol = openSession(seeds.cpcarol, data.seedPassword)
  const dave = openSession(seeds.cpdave, data.seedPassword)

  const ensured = http.post(CHATS_ENSURE_ENDPOINT, JSON.stringify({ peerUserId: dave.userId }), {
    headers: requestHeaders(carol),
  })
  if (ensured.status !== 200 && ensured.status !== 201) fail(`control-plane ensure chat -> ${ensured.status} ${ensured.body}`)
  const chatId = ensured.json('chatId')

  const deadline = Date.now() + FLOOD_MS
  let cycle = 0
  while (Date.now() < deadline) {
    cycle += 1
    const mode = cycle % 2 === 0 ? 'audio' : 'video'

    refreshIfNeeded(carol)
    refreshIfNeeded(dave)

    const created = http.post(
      `${CHAT_ENDPOINT}/${chatId}/conferences`,
      JSON.stringify({ mode }),
      { headers: requestHeaders(carol), tags: { op: 'create' } },
    )
    check(created, {
      '№44 create answers 201 (fresh) / 200 (active edge)': r => r.status === 201 || r.status === 200,
    })
    const conferenceId = (safeJson(created.body) || {}).id
    if (!conferenceId) fail(`№44 left no conference id: ${created.status} ${created.body}`)

    const view = http.get(`${CONFERENCES_ENDPOINT}/${conferenceId}`, {
      headers: requestHeaders(carol),
      tags: { op: 'get' },
    })
    check(view, {
      '№45 get is 200, chatId/mode echoed': r => {
        if (r.status !== 200) return false
        const body = safeJson(r.body)
        return body !== null && body.chatId === chatId && body.mode === mode
      },
    })

    const me = http.get(`${CONFERENCES_ENDPOINT}/${conferenceId}/me`, {
      headers: requestHeaders(carol),
      tags: { op: 'me' },
    })
    check(me, {
      '№46 me is 200, creator is moderator': r => {
        if (r.status !== 200) return false
        const body = safeJson(r.body)
        return body !== null && body.role === 'moderator'
      },
    })

    const carolTicket = http.post(`${CONFERENCES_ENDPOINT}/${conferenceId}/join`, null, {
      headers: requestHeaders(carol),
      tags: { op: 'join' },
    })
    check(carolTicket, {
      '№47 member join is 200 with u: identity + wsUrl': r => {
        if (r.status !== 200) return false
        const body = safeJson(r.body)
        return body !== null && String(body.identity || '').startsWith('u:') && Boolean(body.wsUrl) && Boolean(body.token)
      },
    })

    const daveTicket = http.post(`${CONFERENCES_ENDPOINT}/${conferenceId}/join`, null, {
      headers: requestHeaders(dave),
      tags: { op: 'join' },
    })
    check(daveTicket, { '№47 second member join is 200': r => r.status === 200 })

    const guestLeg = cycle % CP_GUEST_LEG_EVERY_N_CYCLES === 0
    if (guestLeg) {
      const invite = http.post(`${CONFERENCES_ENDPOINT}/${conferenceId}/guest-invite`, null, {
        headers: requestHeaders(carol),
        tags: { op: 'invite_create' },
      })
      check(invite, { '№52 guest invite is 201 with inviteUrl': r => r.status === 201 && Boolean(safeJson(r.body)?.inviteUrl) })
      const inviteToken = inviteTokenOf((safeJson(invite.body) || {}).inviteUrl)
      if (!inviteToken) fail(`№52 left no parsable invite token: ${invite.status} ${invite.body}`)

      // Legit guest probes ride the k6 host source IP: ~1 №54 per cycle and
      // one №55 per 3rd cycle stay far under the 30/1m and 10/1m buckets.
      const info = http.get(`${CONFERENCES_ENDPOINT}/invite-info?inviteToken=${inviteToken}`, {
        tags: { op: 'invite_info' },
      })
      check(info, {
        '№54 valid invite is {valid:true}': r => {
          if (r.status !== 200) return false
          const body = safeJson(r.body)
          return body !== null && body.valid === true
        },
      })

      const guest = http.post(
        `${CONFERENCES_ENDPOINT}/join-by-invite`,
        JSON.stringify({ inviteToken, name: `k6 guest ${runId}` }),
        { tags: { op: 'guest_join' } },
      )
      check(guest, {
        '№55 guest join is 200 with g: identity': r => {
          if (r.status !== 200) return false
          const body = safeJson(r.body)
          return body !== null && String(body.identity || '').startsWith('g:') && Boolean(body.wsUrl)
        },
      })
      if (guest.status === 200) guestTicketsTotal.add(1)
    }

    const ended = http.post(`${CONFERENCES_ENDPOINT}/${conferenceId}/end`, null, {
      headers: requestHeaders(carol),
      tags: { op: 'end' },
    })
    check(ended, { '№48 end is 204': r => r.status === 204 })

    cpCyclesTotal.add(1)
    // webhook room_finished → finalize is asynchronous: give it a beat before
    // the next №44 so the fresh-201 path dominates (200-on-active stays an
    // accepted edge either way)
    sleep(1)
    if (Date.now() < deadline) sleep(CP_CYCLE_SLEEP_S - 1)
  }
}
