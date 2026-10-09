import http from 'k6/http'
import sse from 'k6/x/sse'
import { check, fail, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

/**
 * 008a-social-signals T041 — typing smoke (specs/008a-social-signals/
 * research.md §E, quickstart.md §"Нагрузка (SC-008)"; SC-008, FR-017).
 *
 * Profile (fixed): 1-VU lanes running concurrently for the whole run —
 *
 *   messaging lane  — carol -> dave (chat seeded per run) driving the
 *                    send->ack->SSE message.created cycle of
 *                    messaging.smoke.js WHILE typing load runs: its
 *                    realtime_push_ms samples are the SC-008 comparison
 *                    series (same metric name, same cadence as the base run
 *                    of load/k6/messaging.smoke.js — the presence.smoke.js
 *                    007 SC-007 pattern).
 *
 *   typing actor    — alice "typing" into the alice<->bob direct chat per
 *                    the useTyping machine (ui-behavior.md §2.1): №41 start
 *                    on the first keystroke, renewals every 3 s (the №41
 *                    client repeat window), then the send — №16 message POST
 *                    (the server extinguishes the typing state at the INSERT
 *                    and publishes typing.stopped, T033) with the explicit
 *                    client stop №41 riding after it onto the already
 *                    extinguished state (idempotent no-op — «stop на
 *                    отправке»). ~4 signals + 1 send per ~14 s cycle — far
 *                    under the 60/min №41 flood budget; a 429 here fails
 *                    http_req_failed by design.
 *
 *   typing observer — bob's №18 stream in 30 s reconnect windows: his
 *                    connected app is the dispatch destination the push
 *                    timer observes (FR-017 stage=dispatch). Delivered
 *                    typing.started/typing.stopped/message.created frames
 *                    are counted; №18 is at-most-once and the reconnect gap
 *                    may drop individual frames, so only run-wide counters
 *                    are asserted — never per-cycle delivery.
 *
 * SC-008 gate (Δp99 webchat_realtime_push_seconds <= 10%):
 *   1. base run — this script with K6_TYPING_LANE=false (the messaging lane
 *      alone; the base run of load/k6/messaging.smoke.js is the same series
 *      and cadence and works as the base too): note its realtime_push_ms
 *      p(99);
 *   2. loaded run — default K6_TYPING_LANE=true plus
 *      K6_MSG_P99_BASELINE_MS=<base p99>: the gate
 *      realtime_push_ms p(99) <= baseline * 1.10 (+10%) is added and fails
 *      the run once active typing degrades push latency beyond the budget.
 *      Without the env the lane only records samples (health gate p95 <= 2s)
 *      and the delta is compared by the operator;
 *   3. the server-side series webchat_realtime_push_seconds{event=typing.*,
 *      stage=publish|dispatch} (FR-017) is audited by the operator in
 *      /actuator/prometheus (T062) — k6's client-side push timing is the
 *      same proxy presence.smoke.js uses for the 007 SC-007 budget.
 *
 * Also (health): №41 signal POST p95 <= 2s, no 5xx, no failed requests,
 * >= 1 typing start/stop signal and >= 1 delivered typing.started/stopped
 * frame over the run (counters) — run >= ~2 minutes (default 5m: ~20 typing
 * cycles at the №41 contract windows).
 *
 * The SSE client needs the k6/x/sse extension (stock k6 buffers HTTP bodies,
 * grafana/k6#746): build the runner image first —
 *   docker build -t webchat-k6 load/k6
 *   docker run --rm -i --network host \
 *     -e K6_BASE_URL -e K6_MAILPIT_URL -e K6_SMOKE_DURATION \
 *     [-e K6_TYPING_LANE] [-e K6_MSG_P99_BASELINE_MS] \
 *     webchat-k6 run - < load/k6/social-signals.smoke.js
 * Env: K6_BASE_URL (default http://localhost:8080), K6_MAILPIT_URL
 * (default http://localhost:8025), K6_SMOKE_DURATION (k6-style single-unit
 * value, e.g. 5m / 90s / 2m; default 5m), K6_TYPING_LANE ('false' excludes
 * the typing lanes — the base run; default true), K6_MSG_P99_BASELINE_MS
 * (optional SC-008 gate, milliseconds).
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

// Typing cadence follows the №41 contract constants: client repeat window
// 3 s (start renewals), server TTL 8 s (every renewal re-arms it), flood
// budget 60 signals/min per user. A cycle = first keystroke start + 3
// renewals (~9-10 s of composing) + №16 send + №41 stop + a post-send
// silence pause — ~14 s, i.e. ~4 signals and 1 send per user per cycle.
const TYPING_REPEAT_WINDOW_S = 3
const TYPING_RENEWALS_PER_CYCLE = 3
const TYPING_CYCLE_PACING_S = 4
const MIN_REMAINING_TYPING_CYCLE_MS = TYPING_RENEWALS_PER_CYCLE * TYPING_REPEAT_WINDOW_S * 1000 + 15_000
// The observer stream is a long-lived №18 connection cut into 30 s windows
// (the reconnect loop of a connected app); only the ~1 s gap between windows
// may lose frames (at-most-once №18) — run-wide counters tolerate it.
const OBSERVER_WINDOW_MS = 30_000
const OBSERVER_RECONNECT_PACING_S = 1
const MESSAGING_PACING_S = 4
const MESSAGING_TIMEOUT_MS = 30_000

const SEED_ROLES = ['alice', 'bob', 'carol', 'dave']
const VERIFICATION_LINK_PATTERN = /confirm-registration\?token=([A-Za-z0-9_-]{43})/
const MAILPIT_WAIT_MS = 90_000
const ACCESS_TOKEN_REFRESH_MARGIN_MS = 60_000

// k6 re-evaluates module scope per VU: Date.now()-derived values differ
// between setup() and scenario executors, so run identity travels via setup data.
const runId = Date.now().toString(36)

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 204))

// ----------------------------------------------------------------metrics ---

const realtimePushMs = new Trend('realtime_push_ms')
const typingCyclesTotal = new Counter('typing_cycles_total')
const typingStartedSignalsTotal = new Counter('typing_started_signals_total')
const typingStopSignalsTotal = new Counter('typing_stop_signals_total')
const typingFramesStartedTotal = new Counter('typing_frames_started_total')
const typingFramesStoppedTotal = new Counter('typing_frames_stopped_total')
const typingFramesMessageTotal = new Counter('typing_frames_message_total')

// SC-008: p99 gate against the base messaging run (client-side push series,
// same name and cadence — apples to apples). No env -> record only.
const MSG_P99_BASELINE_MS = parseMsEnv('K6_MSG_P99_BASELINE_MS', 0)
// K6_TYPING_LANE=false strips the typing lanes — the script then IS the base
// messaging run of the SC-008 comparison.
const TYPING_LANE_ENABLED = (__ENV.K6_TYPING_LANE || 'true').trim().toLowerCase() !== 'false'

const thresholds = {
  http_req_failed: ['rate==0'],
  'http_reqs{status:/^5/}': ['count==0'],
  realtime_push_ms: ['p(95)<2000'],
  'checks{scenario:messaging}': ['rate==1'],
}
if (TYPING_LANE_ENABLED) {
  Object.assign(thresholds, {
    'http_req_duration{op:typing_signal}': ['p(95)<2000'],
    typing_started_signals_total: ['count>0'],
    typing_stop_signals_total: ['count>0'],
    typing_frames_started_total: ['count>0'],
    typing_frames_stopped_total: ['count>0'],
    'checks{scenario:typing_actor}': ['rate==1'],
    'checks{scenario:typing_observer}': ['rate==1'],
  })
}
if (MSG_P99_BASELINE_MS > 0) {
  thresholds.realtime_push_ms.push(`p(99)<=${Math.ceil(MSG_P99_BASELINE_MS * 1.1)}`)
}

const scenarios = {
  messaging: {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    exec: 'messagingLoop',
    gracefulStop: '60s',
  },
}
if (TYPING_LANE_ENABLED) {
  scenarios.typing_actor = {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    exec: 'typingActorLoop',
    gracefulStop: '30s',
  }
  scenarios.typing_observer = {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    exec: 'typingObserverLoop',
    gracefulStop: '60s',
  }
}

export const options = { scenarios, thresholds }

// --------------------------------------------------------------- helpers ---

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

const RUN_MS = parseDurationMs(__ENV.K6_SMOKE_DURATION, 300_000)

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

function describeSseError(error) {
  try {
    if (error && typeof error.error === 'function') return String(error.error())
  } catch (ignored) {
    // fall through to coercion below
  }
  return String(error)
}

// A stream ended by its timeout is the at-most-once frame-loss path, not a
// transport failure — the observer just opens the next window.
function isStreamTimeout(message) {
  return /deadline|timeout|timed out/i.test(String(message || ''))
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

function ensureChat(session, peerUserId) {
  const response = http.post(`${API_BASE}/chats/ensure`, JSON.stringify({ peerUserId }), {
    headers: bearerHeaders(session),
    tags: { op: 'ensure' },
  })
  if (response.status !== 200 && response.status !== 201) {
    fail(`ensure chat for ${session.role} -> ${response.status} ${response.body}`)
  }
  const body = safeJson(response.body) || {}
  if (!body.chatId) fail(`ensure chat for ${session.role} returned no chatId: ${response.body}`)
  return body.chatId
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

// №41 typing signal — returns the HTTP status (204 expected).
function postTypingSignal(session, chatId, action) {
  const response = http.post(
    `${API_BASE}/chats/${chatId}/typing`,
    JSON.stringify({ action }),
    { headers: bearerHeaders(session), tags: { op: 'typing_signal' } },
  )
  return response.status
}

// ------------------------------------------------------------------ setup ---

export function setup() {
  const seedPassword = `K6soc-${runId}-Smoke1`
  const seedBase = Date.now()
  const thirdOctet = ((seedBase >>> 8) & 0xff) || 1
  const fourthOctet = seedBase & 0xff
  const users = []
  SEED_ROLES.forEach((role, i) => {
    const user = {
      role,
      username: `k6soc${runId}${role}`,
      email: `k6soc.${runId}.${role}@example.com`,
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

  const seeds = {}
  for (const user of users) seeds[user.role] = user
  // The typing pair and the messaging pair live in their own direct chats, so
  // typing-lane messages never contaminate the messaging comparison series.
  const alice = openSession(seeds.alice, seedPassword)
  const bob = openSession(seeds.bob, seedPassword)
  const typingChatId = ensureChat(alice, bob.userId)
  const carol = openSession(seeds.carol, seedPassword)
  const dave = openSession(seeds.dave, seedPassword)
  const messagingChatId = ensureChat(carol, dave.userId)

  return { users, seedPassword, typingChatId, messagingChatId }
}

// --------------------------------------------------------- typing lane ---

export function typingActorLoop(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const state = {
    alice: openSession(seeds.alice, data.seedPassword),
    chatId: data.typingChatId,
    counter: 0,
  }
  const deadline = Date.now() + RUN_MS
  // Do not start a typing cycle that cannot finish inside the run window.
  while (Date.now() < deadline - MIN_REMAINING_TYPING_CYCLE_MS) {
    runTypingCycle(state)
    sleep(TYPING_CYCLE_PACING_S)
  }
}

// One compose->send cycle of the useTyping machine: №41 start on the first
// keystroke, renewals every 3 s, then the send (№16 INSERT extinguishes the
// server state and publishes typing.stopped) with the explicit №41 stop
// riding after it — the idempotent no-op path of «stop на отправке».
function runTypingCycle(state) {
  refreshIfNeeded(state.alice)
  state.counter += 1

  let startsOk = postTypingSignal(state.alice, state.chatId, 'start') === 204
  if (startsOk) typingStartedSignalsTotal.add(1)
  for (let i = 0; i < TYPING_RENEWALS_PER_CYCLE; i += 1) {
    sleep(TYPING_REPEAT_WINDOW_S)
    if (postTypingSignal(state.alice, state.chatId, 'start') === 204) {
      typingStartedSignalsTotal.add(1)
    } else {
      startsOk = false
    }
  }

  const clientMessageId = crypto.randomUUID()
  const sent = http.post(
    `${API_BASE}/chats/${state.chatId}/messages`,
    JSON.stringify({
      clientMessageId,
      text: `k6 social-signals typing ${runId} #${state.counter}`,
    }),
    { headers: bearerHeaders(state.alice), tags: { op: 'typing_msg_send' } },
  )
  const stopStatus = postTypingSignal(state.alice, state.chatId, 'stop')
  if (stopStatus === 204) typingStopSignalsTotal.add(1)
  typingCyclesTotal.add(1)

  const cycle = { startsOk, sendStatus: sent.status, stopStatus }
  check(cycle, {
    'typing №41 start signals accepted (204)': c => c.startsOk,
    'typing №16 send acknowledged 201/200': c => c.sendStatus === 201 || c.sendStatus === 200,
    'typing №41 stop accepted (204)': c => c.stopStatus === 204,
  })
}

export function typingObserverLoop(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const bob = openSession(seeds.bob, data.seedPassword)
  const chatId = data.typingChatId
  const state = { streamErrors: '' }
  const deadline = Date.now() + RUN_MS
  while (Date.now() < deadline) {
    refreshIfNeeded(bob)
    observeTypingWindow(bob, chatId, state)
    sleep(OBSERVER_RECONNECT_PACING_S)
  }
  check(state, { 'typing observer streams ended without transport errors': s => s.streamErrors === '' })
}

// One 30 s №18 window of the observer: counts delivered typing/message
// frames (the dispatch destination of the FR-017 push path). Per-cycle frame
// delivery is NOT asserted — №18 is at-most-once and the reconnect gap may
// legally drop frames; run-wide counters carry the assertion instead.
function observeTypingWindow(bob, chatId, state) {
  const stream = sse.open(
    EVENTS_ENDPOINT,
    {
      headers: { Authorization: `Bearer ${bob.accessToken}` },
      timeout: `${Math.ceil(OBSERVER_WINDOW_MS / 1000)}s`,
    },
    client => {
      client.on('event', event => {
        if (event.name === 'typing.started' || event.name === 'typing.stopped') {
          const payload = safeJson(event.data)
          if (!payload || payload.chatId !== chatId) return
          if (event.name === 'typing.started') typingFramesStartedTotal.add(1)
          else typingFramesStoppedTotal.add(1)
          return
        }
        if (event.name === 'message.created') {
          const payload = safeJson(event.data)
          if (!payload || payload.chatId !== chatId) return
          typingFramesMessageTotal.add(1)
        }
      })
      client.on('error', error => {
        const described = describeSseError(error)
        if (!isStreamTimeout(described)) {
          state.streamErrors = state.streamErrors ? `${state.streamErrors}; ${described}` : described
        }
        client.close()
      })
    },
  )
  check(stream, { 'typing observer №18 stream connected (200)': r => r && r.status === 200 })
}

// ------------------------------------------------------- messaging lane ---
// The SC-008 comparison series: the messaging send->ack->SSE cycle of
// messaging.smoke.js (trimmed to the push receipt — the read-receipt half is
// out of scope here) driven concurrently with the typing load.

export function messagingLoop(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const state = {
    carol: openSession(seeds.carol, data.seedPassword),
    dave: openSession(seeds.dave, data.seedPassword),
    chatId: data.messagingChatId,
    counter: 0,
  }
  const deadline = Date.now() + RUN_MS
  while (Date.now() < deadline) {
    runMessagingCycle(state)
    sleep(MESSAGING_PACING_S)
  }
}

function runMessagingCycle(state) {
  refreshIfNeeded(state.carol)
  refreshIfNeeded(state.dave)

  const clientMessageId = crypto.randomUUID()
  state.counter += 1
  const timing = {
    ackAt: 0,
    sendStatus: 0,
    pushMeasured: false,
    receiverError: null,
  }

  const stream = sse.open(
    EVENTS_ENDPOINT,
    {
      headers: { Authorization: `Bearer ${state.dave.accessToken}` },
      timeout: `${Math.ceil(MESSAGING_TIMEOUT_MS / 1000)}s`,
    },
    daveClient => {
      daveClient.on('event', event => {
        if (event.name !== 'message.created') return
        const payload = safeJson(event.data)
        if (!payload || !payload.message || payload.message.id !== clientMessageId) return
        if (timing.ackAt) realtimePushMs.add(Date.now() - timing.ackAt)
        timing.pushMeasured = true
        daveClient.close()
      })
      daveClient.on('error', error => {
        timing.receiverError = describeSseError(error)
        daveClient.close()
      })
      daveClient.on('open', () => {
        const sent = http.post(
          `${API_BASE}/chats/${state.chatId}/messages`,
          JSON.stringify({
            clientMessageId,
            text: `k6 social-signals smoke ${runId} #${state.counter}`,
          }),
          { headers: bearerHeaders(state.carol), tags: { op: 'msg_send' } },
        )
        timing.sendStatus = sent.status
        timing.ackAt = Date.now()
        // The frame (if delivered) arrives while the outer sse.open keeps
        // blocking — its handler closes the stream. On frame loss the timeout
        // cuts the stream and the post-cycle pull below recovers the message.
      })
    },
  )
  check(stream, { 'messaging lane stream connected (200)': r => r && r.status === 200 })

  let recoveredByPull = false
  if (!timing.pushMeasured && (timing.sendStatus === 200 || timing.sendStatus === 201)) {
    // At-most-once №18: the pull cycle must recover the lost frame (US1 of 005).
    const history = http.get(`${API_BASE}/chats/${state.chatId}/messages?limit=50`, {
      headers: bearerHeaders(state.dave),
      tags: { op: 'history_page' },
    })
    const body = safeJson(history.body) || {}
    recoveredByPull = (body.messages || []).some(message => message.id === clientMessageId)
  }

  check(timing, {
    'messaging lane: send acknowledged 201/200': t => t.sendStatus === 201 || t.sendStatus === 200,
    'messaging lane: message.created delivered over SSE or recovered by pull': t =>
      t.pushMeasured || recoveredByPull || (t.sendStatus !== 200 && t.sendStatus !== 201),
  })
  const receiverError =
    timing.receiverError && !isStreamTimeout(timing.receiverError) ? timing.receiverError : ''
  check(receiverError, { 'messaging lane: stream ended without transport errors': e => e === '' })
}
