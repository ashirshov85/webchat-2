import http from 'k6/http'
import sse from 'k6/x/sse'
import { check, fail, sleep } from 'k6'
import { Trend } from 'k6/metrics'

/**
 * 004-direct-messaging-core T066 — messaging smoke (research.md §13, quickstart §4.10).
 *
 * Profile (fixed): 1 VU-pair (alice -> bob, both seeded per run) driving the
 * full messaging cycle for the whole run, plus a single-VU flood slice on its
 * own pair (carol -> dave) so 429s never consume the latency pair's flood
 * budget. Default run length is 5 minutes (K6_SMOKE_DURATION).
 *
 * Thresholds (fixed here, quickstart §4.10):
 *   SC-001  send -> ack (201/200)                       p95 <= 2s  http_req_duration{op:send}
 *   SC-004  dialog open (GET /chats/{id})               p95 <= 3s  http_req_duration{op:dialog_open}
 *   SC-004  history page (GET .../messages?limit=50)    p95 <= 3s  http_req_duration{op:history_page}
 *   SC-005  ack -> SSE message.created at the receiver  p95 <= 2s  realtime_push_ms
 *   SC-007  displayed -> SSE chat.read at the sender    p95 <= 2s  read_receipt_ms
 *   flood   31+/min -> only 200/201/429, no 5xx anywhere, 429 carries Retry-After >= 1s
 *
 * SC-006 is NOT exercised here on purpose: the smoke checks target latencies
 * at the minimal VU-pair profile, not the peak constitutional budgets
 * (1M CCU / 100k msg/s) — those are confirmed by calculation (plan.md
 * Constraints, research.md §12); full-scale load testing is a platform
 * cross-cutting task (ROADMAP).
 *
 * Cycle anatomy (one VU plays both roles, streams nested so both directions
 * are measured while connected):
 *   1. alice opens the dialog (GET /chats/{id}) and the first history page;
 *   2. alice's SSE stream opens, inside it bob's SSE stream opens;
 *   3. alice POSTs the message (SC-001 clock stops at the ack);
 *   4. bob receives message.created (SC-005), his stream closes;
 *   5. bob POSTs /read, alice receives chat.read (SC-007), her stream closes.
 *
 * The SSE client needs the k6/x/sse extension (stock k6 buffers HTTP bodies,
 * grafana/k6#746): build the runner image first —
 *   docker build -t webchat-k6 load/k6
 *   docker run --rm -i --network host \
 *     -e K6_BASE_URL -e K6_MAILPIT_URL -e K6_SMOKE_DURATION \
 *     webchat-k6 run - < load/k6/messaging.smoke.js
 * Env: K6_BASE_URL (default http://localhost:8080), K6_MAILPIT_URL
 * (default http://localhost:8025), K6_SMOKE_DURATION (k6-style single-unit value,
 * e.g. 5m / 90s / 2m; default 5m).
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

// Per-user flood limit is 30 msg/min with a burst of 30 (capacity 30, greedy
// 30/60s drip — research.md §7). The pair keeps alice at ~10-11 sends/min
// (cycle work ~1.5s + 4s pacing), far below the budget; the flood slice runs
// carol at 120/min so a full bucket drains within ~20s and the run spends
// most of the flood window in the 429 path.
const CYCLE_SLEEP_S = 4
const FLOOD_RATE_PER_MIN = 120
const SEED_ROLES = ['alice', 'bob', 'carol', 'dave']
const VERIFICATION_LINK_PATTERN = /confirm-registration\?token=([A-Za-z0-9_-]{43})/
const MAILPIT_WAIT_MS = 90_000
const ACCESS_TOKEN_REFRESH_MARGIN_MS = 60_000
const SSE_TIMEOUT = '30s'

// k6 re-evaluates module scope per VU: Date.now()-derived values differ
// between setup() and scenario executors, so run identity travels via setup data.
const runId = Date.now().toString(36)

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 204, 429))

const realtimePushMs = new Trend('realtime_push_ms')
const readReceiptMs = new Trend('read_receipt_ms')

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

const RUN_MS = parseDurationMs(__ENV.K6_SMOKE_DURATION, 300_000)
const FLOOD_START_MS = Math.min(30_000, Math.floor(RUN_MS / 6))
const FLOOD_DURATION_MS = Math.max(10_000, RUN_MS - 2 * FLOOD_START_MS)

export const options = {
  scenarios: {
    pair: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'pairLoop',
      gracefulStop: '60s',
    },
    flood: {
      executor: 'constant-arrival-rate',
      rate: FLOOD_RATE_PER_MIN,
      timeUnit: '1m',
      startTime: `${FLOOD_START_MS}ms`,
      duration: `${FLOOD_DURATION_MS}ms`,
      preAllocatedVUs: 2,
      maxVUs: 2,
      exec: 'floodSend',
      gracefulStop: '10s',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    'http_reqs{status:/^5/}': ['count==0'],
    'http_req_duration{op:send}': ['p(95)<2000'],
    'http_req_duration{op:dialog_open}': ['p(95)<3000'],
    'http_req_duration{op:history_page}': ['p(95)<3000'],
    realtime_push_ms: ['p(95)<2000'],
    read_receipt_ms: ['p(95)<2000'],
    'http_reqs{scenario:flood,status:429}': ['count>0'],
    'checks{scenario:pair}': ['rate==1'],
    'checks{scenario:flood}': ['rate==1'],
  },
}

function jsonHeaders() {
  return { 'Content-Type': 'application/json' }
}

function postJson(url, payload, sourceIp) {
  const headers = jsonHeaders()
  if (sourceIp) headers['X-Forwarded-For'] = sourceIp
  return http.post(url, JSON.stringify(payload), { headers })
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

function bearerHeaders(session) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${session.accessToken}` }
}

function openSession(seed, password) {
  const response = postJson(LOGIN_ENDPOINT, { identifier: seed.username, password })
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

function describeSseError(error) {
  try {
    if (error && typeof error.error === 'function') return String(error.error())
  } catch (ignored) {
    // fall through to coercion below
  }
  return String(error)
}

export function setup() {
  const seedPassword = `K6msg-${runId}-Smoke1`
  const seedBase = Date.now()
  const thirdOctet = ((seedBase >>> 8) & 0xff) || 1
  const fourthOctet = seedBase & 0xff
  const users = []
  SEED_ROLES.forEach((role, i) => {
    const user = {
      role,
      username: `k6msg${runId}${role}`,
      email: `k6msg.${runId}.${role}@example.com`,
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
  return { users, seedPassword }
}

function runPairCycle(state) {
  refreshIfNeeded(state.alice)
  refreshIfNeeded(state.bob)

  const dialog = http.get(`${API_BASE}/chats/${state.chatId}`, {
    headers: bearerHeaders(state.alice),
    tags: { op: 'dialog_open' },
  })
  check(dialog, { 'dialog open is 200 (SC-004)': r => r.status === 200 })

  const history = http.get(`${API_BASE}/chats/${state.chatId}/messages?limit=50`, {
    headers: bearerHeaders(state.alice),
    tags: { op: 'history_page' },
  })
  check(history, { 'history page is 200 (SC-004)': r => r.status === 200 })

  const clientMessageId = crypto.randomUUID()
  state.counter += 1
  const text = `k6 messaging smoke ${runId} #${state.counter}`
  const timing = {
    ackAt: 0,
    seq: 0,
    sendStatus: 0,
    readStatus: 0,
    readPosted: false,
    bobDisplayedAt: 0,
    pushMeasured: false,
    receiptMeasured: false,
    senderError: null,
    receiverError: null,
  }

  const senderStream = sse.open(EVENTS_ENDPOINT, {
    headers: { Authorization: `Bearer ${state.alice.accessToken}` },
    timeout: SSE_TIMEOUT,
  }, aliceClient => {
    aliceClient.on('event', event => {
      if (event.name !== 'chat.read') return
      const payload = safeJson(event.data)
      if (!payload || payload.chatId !== state.chatId) return
      if (payload.byUserId !== state.bob.userId || payload.readUpToSeq < timing.seq) return
      if (!timing.readPosted || !timing.bobDisplayedAt) return
      readReceiptMs.add(Date.now() - timing.bobDisplayedAt)
      timing.receiptMeasured = true
      aliceClient.close()
    })
    aliceClient.on('error', error => {
      timing.senderError = describeSseError(error)
      aliceClient.close()
    })
    aliceClient.on('open', () => {
      sse.open(EVENTS_ENDPOINT, {
        headers: { Authorization: `Bearer ${state.bob.accessToken}` },
        timeout: SSE_TIMEOUT,
      }, bobClient => {
        bobClient.on('event', event => {
          if (event.name !== 'message.created') return
          const payload = safeJson(event.data)
          if (!payload || !payload.message || payload.message.id !== clientMessageId) return
          timing.bobDisplayedAt = Date.now()
          if (timing.ackAt) realtimePushMs.add(timing.bobDisplayedAt - timing.ackAt)
          timing.pushMeasured = true
          bobClient.close()
        })
        bobClient.on('error', error => {
          timing.receiverError = describeSseError(error)
          bobClient.close()
        })
        bobClient.on('open', () => {
          const sent = http.post(
            `${API_BASE}/chats/${state.chatId}/messages`,
            JSON.stringify({ clientMessageId, text }),
            { headers: bearerHeaders(state.alice), tags: { op: 'send' } },
          )
          timing.sendStatus = sent.status
          timing.ackAt = Date.now()
          const body = safeJson(sent.body)
          timing.seq = body ? body.seq : 0
        })
      })

      if (timing.pushMeasured && timing.seq > 0) {
        const read = http.post(
          `${API_BASE}/chats/${state.chatId}/read`,
          JSON.stringify({ upToSeq: timing.seq }),
          { headers: bearerHeaders(state.bob), tags: { op: 'read_post' } },
        )
        timing.readStatus = read.status
        timing.readPosted = true
      }
    })
  })

  if (timing.ackAt && !timing.pushMeasured) realtimePushMs.add(Date.now() - timing.ackAt)
  if (timing.bobDisplayedAt && timing.readPosted && !timing.receiptMeasured) {
    readReceiptMs.add(Date.now() - timing.bobDisplayedAt)
  }

  check(senderStream, { 'sender realtime stream connected (200)': r => r && r.status === 200 })
  check(timing, {
    'send acknowledged 201/200 (SC-001)': t => t.sendStatus === 201 || t.sendStatus === 200,
    'message.created received over SSE (SC-005)': t => t.pushMeasured,
    'read accepted 204': t => !t.readPosted || t.readStatus === 204,
    'chat.read received over SSE (SC-007)': t => !t.readPosted || t.receiptMeasured,
  })
  const streamErrors = [timing.senderError, timing.receiverError].filter(Boolean).join('; ')
  check(streamErrors, { 'realtime streams ended without transport errors': e => e === '' })
}

export function pairLoop(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const alice = openSession(seeds.alice, data.seedPassword)
  const bob = openSession(seeds.bob, data.seedPassword)

  const ensured = http.post(`${API_BASE}/chats/ensure`, JSON.stringify({ peerUserId: bob.userId }), {
    headers: bearerHeaders(alice),
  })
  if (ensured.status !== 200 && ensured.status !== 201) fail(`ensure chat -> ${ensured.status} ${ensured.body}`)

  const state = {
    alice,
    bob,
    chatId: ensured.json('chatId'),
    counter: 0,
  }
  const deadline = Date.now() + RUN_MS
  while (Date.now() < deadline) {
    runPairCycle(state)
    sleep(CYCLE_SLEEP_S)
  }
}

const floodState = { carol: null, chatId: null }

function initFlood(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const carol = openSession(seeds.carol, data.seedPassword)
  const dave = openSession(seeds.dave, data.seedPassword)
  const ensured = http.post(`${API_BASE}/chats/ensure`, JSON.stringify({ peerUserId: dave.userId }), {
    headers: bearerHeaders(carol),
  })
  if (ensured.status !== 200 && ensured.status !== 201) fail(`flood ensure chat -> ${ensured.status} ${ensured.body}`)
  floodState.carol = carol
  floodState.chatId = ensured.json('chatId')
}

export function floodSend(data) {
  if (!floodState.carol) initFlood(data)
  refreshIfNeeded(floodState.carol)
  const clientMessageId = crypto.randomUUID()
  const response = http.post(
    `${API_BASE}/chats/${floodState.chatId}/messages`,
    JSON.stringify({ clientMessageId, text: `k6 flood ${clientMessageId}` }),
    { headers: bearerHeaders(floodState.carol), tags: { op: 'flood_send' } },
  )
  check(response, {
    'flood degrades to 200/201/429 only': r => r.status === 200 || r.status === 201 || r.status === 429,
    'flood 429 carries Retry-After >= 1s': r => r.status !== 429 || retryAfterSeconds(r) >= 1,
  })
}
