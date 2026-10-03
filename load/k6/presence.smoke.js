import http from 'k6/http'
import sse from 'k6/x/sse'
import { check, fail, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

/**
 * 007-user-presence T035 — presence smoke (specs/007-user-presence/quickstart.md
 * "нагрузочный smoke", tasks.md Phase 7; SC-001/SC-006/SC-007).
 *
 * Profile (fixed): two 1-VU lanes running concurrently for the whole run —
 *
 *   presence lane  — alice (observer) + bob (subject), both seeded per run,
 *                    direct chat between them (visibility audience). Each cycle:
 *                      1. alice (re)connects №18, takes the `connected` frame
 *                         (new connectionId every cycle — the reconnect path);
 *                      2. bob connects №18 → `connected` frame → registration
 *                         publishes `online` → alice must receive
 *                         presence.updated <= 2 s after bob's frame (SC-001,
 *                         measured client-side end to end);
 *                      3. alice POSTs №37 heartbeat for her own connectionId
 *                         (204 renews the registration) and reads №36 batch
 *                         snapshot — `online` + rev (SC-006);
 *                      4. bob disconnects; after the server hysteresis window
 *                         alice receives presence.updated offline and №36 must
 *                         report `offline` with a strictly greater rev (FR-003
 *                         monotonicity, FR-004 snapshot/event consistency).
 *                    A lost realtime frame is the legal at-most-once path of №18:
 *                    the cycle then converges through the №36 snapshot (loss
 *                    recovery) instead of failing — only the loss of BOTH the
 *                    frame and the converged snapshot fails the run.
 *
 *   messaging lane — carol -> dave (chat seeded per run) driving the messaging
 *                    send->ack->SSE message.created cycle of messaging.smoke.js
 *                    WHILE presence load runs: its realtime_push_ms samples are
 *                    the SC-007 comparison series (same metric name, same
 *                    cadence as the base run of load/k6/messaging.smoke.js).
 *
 * Thresholds (quickstart §"Ожидаемые исходы"):
 *   SC-001  bob's connected frame -> presence.updated at the observer
 *                                                   p95 <= 2s  presence_delivery_ms
 *   SC-006  №36 batch snapshot                     p95 <= 2s  http_req_duration{op:snapshot}
 *   SC-007  messaging delivery under presence load — no degradation vs the base
 *          run: run messaging.smoke.js first, take its realtime_push_ms p99,
 *          then start this script with K6_MSG_P99_BASELINE_MS=<that value>:
 *          the gate realtime_push_ms p(99) <= baseline * 1.10 (+10%) is added.
 *          Without the env the lane only records samples (health gate p95 <= 2s)
 *          and the delta is compared by the operator. The server-side series
 *          webchat_realtime_push_seconds{event=...} is audited by the 007
 *          observability IT (T036) — k6's client-side push timing is the same
 *          proxy messaging.smoke.js uses for its SC-005/SC-007 budgets.
 *   also   >= 1 online delivery, >= 1 offline transition and >= 1 successful
 *          №37 renewal must happen (counters) — run >= ~3 minutes (default 5m:
 *          ~5 presence cycles at production windows).
 *
 * Presence windows default to the production profile (hysteresis 45s, poller
 * 1s — presence-events.md §3): a full online/offline cycle takes ~50 s. Against
 * a local backend started with tightened windows (e.g. PRESENCE_HYSTERESIS=5s
 * PRESENCE_POLLER_INTERVAL=200ms, the test-profile values) export
 * K6_PRESENCE_HYSTERESIS_MS/K6_PRESENCE_POLLER_MS to match and cycles shrink
 * accordingly. The observer sends one №37 beat per cycle from the post-online
 * driver context (the 30 s contract cadence spans less than one cycle window).
 *
 * The SSE client needs the k6/x/sse extension (stock k6 buffers HTTP bodies,
 * grafana/k6#746): build the runner image first —
 *   docker build -t webchat-k6 load/k6
 *   docker run --rm -i --network host \
 *     -e K6_BASE_URL -e K6_MAILPIT_URL -e K6_SMOKE_DURATION \
 *     [-e K6_PRESENCE_HYSTERESIS_MS -e K6_PRESENCE_POLLER_MS] \
 *     [-e K6_MSG_P99_BASELINE_MS] \
 *     webchat-k6 run - < load/k6/presence.smoke.js
 * Env: K6_BASE_URL (default http://localhost:8080), K6_MAILPIT_URL
 * (default http://localhost:8025), K6_SMOKE_DURATION (k6-style single-unit
 * value, e.g. 5m / 90s / 2m; default 5m), K6_PRESENCE_HYSTERESIS_MS
 * (default 45000), K6_PRESENCE_POLLER_MS (default 1000),
 * K6_MSG_P99_BASELINE_MS (optional SC-007 gate, milliseconds).
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
const HEARTBEAT_ENDPOINT = `${API_BASE}/users/me/presence/heartbeat`
const SNAPSHOT_ENDPOINT = `${API_BASE}/users/me/presence`

// Cycle shaping. The offline wait budget follows the server's silence pipeline:
// offq fires at hysteresis, the poller adds up to 2 poller ticks (SC-003 budget
// TTL + hysteresis + 2×poller; here the TTL part is absent — the disconnect is
// explicit), plus slack for the scheduler jitter and the snapshot round-trip.
const HYSTERESIS_MS = parseMsEnv('K6_PRESENCE_HYSTERESIS_MS', 45_000)
const POLLER_MS = parseMsEnv('K6_PRESENCE_POLLER_MS', 1_000)
const OFFLINE_BUDGET_MS = HYSTERESIS_MS + 4 * POLLER_MS + 10_000
// After a cycle whose online frame was lost (at-most-once №18) the subject
// stays pending-offline: wait the window out before the next cycle so it
// starts from a published offline state (otherwise no online transition — and
// no delivery sample — would ever fire again).
const PENDING_OFFLINE_FLUSH_MS = HYSTERESIS_MS + 4 * POLLER_MS + 5_000
// The observer stream must outlive the offline wait (a stream cut short would
// fail the cycle); the surplus is the at-most-once frame-loss recovery window —
// if the offline frame is lost, the stream times out and the cycle converges
// through the №36 snapshot instead.
const OBSERVER_TIMEOUT_MS = OFFLINE_BUDGET_MS + 30_000
// The subject stream only needs to survive its own connect + the online fanout.
const SUBJECT_TIMEOUT_MS = 30_000
const MESSAGING_TIMEOUT_MS = 30_000

const SEED_ROLES = ['alice', 'bob', 'carol', 'dave']
const VERIFICATION_LINK_PATTERN = /confirm-registration\?token=([A-Za-z0-9_-]{43})/
const MAILPIT_WAIT_MS = 90_000
const ACCESS_TOKEN_REFRESH_MARGIN_MS = 60_000
const CYCLE_PACING_S = 2
const MESSAGING_PACING_S = 4
const ONLINE_DELIVERY_LIMIT_MS = 2_000
// Do not start a presence cycle that cannot finish inside the run window.
const MIN_REMAINING_CYCLE_MS = OFFLINE_BUDGET_MS + 20_000

// k6 re-evaluates module scope per VU: Date.now()-derived values differ
// between setup() and scenario executors, so run identity travels via setup data.
const runId = Date.now().toString(36)

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 204))

// ---------------------------------------------------------------- metrics ---

const presenceDeliveryMs = new Trend('presence_delivery_ms')
const realtimePushMs = new Trend('realtime_push_ms')
const presenceCyclesTotal = new Counter('presence_cycles_total')
const onlineDeliveriesTotal = new Counter('presence_online_deliveries_total')
const offlineTransitionsTotal = new Counter('presence_offline_transitions_total')
const heartbeatRenewalsTotal = new Counter('presence_heartbeat_renewals_total')

// SC-007: p99 gate against a base messaging.smoke.js run (client-side push
// series, same name and cadence — apples to apples). No env -> record only.
const MSG_P99_BASELINE_MS = parseMsEnv('K6_MSG_P99_BASELINE_MS', 0)

const thresholds = {
  http_req_failed: ['rate==0'],
  'http_reqs{status:/^5/}': ['count==0'],
  presence_delivery_ms: ['p(95)<2000'],
  'http_req_duration{op:snapshot}': ['p(95)<2000'],
  realtime_push_ms: ['p(95)<2000'],
  presence_online_deliveries_total: ['count>0'],
  presence_offline_transitions_total: ['count>0'],
  presence_heartbeat_renewals_total: ['count>0'],
  'checks{scenario:presence}': ['rate==1'],
  'checks{scenario:messaging}': ['rate==1'],
}
if (MSG_P99_BASELINE_MS > 0) {
  thresholds.realtime_push_ms.push(`p(99)<=${Math.ceil(MSG_P99_BASELINE_MS * 1.1)}`)
}

export const options = {
  scenarios: {
    presence: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'presenceLoop',
      gracefulStop: `${Math.ceil((OBSERVER_TIMEOUT_MS + 60_000) / 1000)}s`,
    },
    messaging: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      exec: 'messagingLoop',
      gracefulStop: '60s',
    },
  },
  thresholds,
}

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
// transport failure — the cycle converges through №36 (checked separately).
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

// №36 batch snapshot for one target — the displayed-surface read of SC-006.
// Returns the target's {userId, status, rev} item or null.
function snapshotItem(session, targetUserId) {
  const response = http.get(`${SNAPSHOT_ENDPOINT}?userIds=${targetUserId}`, {
    headers: bearerHeaders(session),
    tags: { op: 'snapshot' },
  })
  if (response.status !== 200) return null
  const body = safeJson(response.body) || {}
  const items = body.items || []
  return items.find(item => item.userId === targetUserId) || null
}

// №37 presence heartbeat: renews the registration of an open connection.
function sendHeartbeat(session, connectionId) {
  const response = http.post(HEARTBEAT_ENDPOINT, JSON.stringify({ connectionId }), {
    headers: bearerHeaders(session),
    tags: { op: 'heartbeat' },
  })
  return check(response, {
    'heartbeat №37 renewed the registration (204)': r => r.status === 204,
  })
}

// ------------------------------------------------------------------ setup ---

export function setup() {
  const seedPassword = `K6prc-${runId}-Smoke1`
  const seedBase = Date.now()
  const thirdOctet = ((seedBase >>> 8) & 0xff) || 1
  const fourthOctet = seedBase & 0xff
  const users = []
  SEED_ROLES.forEach((role, i) => {
    const user = {
      role,
      username: `k6prc${runId}${role}`,
      email: `k6prc.${runId}.${role}@example.com`,
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
  // Direct chats make the pairs each other's visibility audience (a shared
  // active chat is sufficient — data-model §1.4) and the messaging corridor.
  const alice = openSession(seeds.alice, seedPassword)
  const bob = openSession(seeds.bob, seedPassword)
  ensureChat(alice, bob.userId)
  const carol = openSession(seeds.carol, seedPassword)
  const dave = openSession(seeds.dave, seedPassword)
  const messagingChatId = ensureChat(carol, dave.userId)

  return { users, seedPassword, messagingChatId }
}

// --------------------------------------------------------- presence lane ---

export function presenceLoop(data) {
  const seeds = {}
  for (const user of data.users) seeds[user.role] = user
  const state = {
    alice: openSession(seeds.alice, data.seedPassword),
    bob: openSession(seeds.bob, data.seedPassword),
  }
  const deadline = Date.now() + RUN_MS
  while (Date.now() < deadline - MIN_REMAINING_CYCLE_MS) {
    const armed = runPresenceCycle(state)
    sleep(CYCLE_PACING_S)
    if (!armed) {
      // The online frame was lost (at-most-once №18): the subject is
      // pending-offline now — let the hysteresis window flush so the next
      // cycle starts from a published offline state and arms again.
      sleep(PENDING_OFFLINE_FLUSH_MS / 1000)
    }
  }
}

// One full online/offline cycle of the subject as observed by the observer.
// Structure follows the messaging.smoke.js idiom: the observer stream is the
// outer blocking sse.open, the subject stream is nested inside its 'open'
// callback, and every state transition is driven by event handlers (cross-
// client close()), so the VU never spin-waits inside a callback.
// Returns true when the cycle was "armed" (the online transition observed).
function runPresenceCycle(state) {
  refreshIfNeeded(state.alice)
  refreshIfNeeded(state.bob)

  const obs = {
    aliceConnectionId: '',
    heartbeatOk: false,
    onlineEventAt: 0,
    onlineDelivered: false,
    onlineLatencyMs: 0,
    onlineSnapshotOk: false,
    onlineRev: 0,
    offlineEventAt: 0,
    transportError: null,
    subjectTransportError: null,
  }
  const subject = { client: null, frameAt: 0 }

  const observerStream = sse.open(
    EVENTS_ENDPOINT,
    {
      headers: { Authorization: `Bearer ${state.alice.accessToken}` },
      timeout: `${Math.ceil(OBSERVER_TIMEOUT_MS / 1000)}s`,
    },
    aliceClient => {
      aliceClient.on('event', event => {
        if (event.name === 'connected') {
          const payload = safeJson(event.data)
          if (payload && payload.connectionId) obs.aliceConnectionId = payload.connectionId
          return
        }
        if (event.name !== 'presence.updated') return
        const payload = safeJson(event.data)
        if (!payload || payload.userId !== state.bob.userId) return
        if (payload.status === 'online' && !obs.onlineEventAt) {
          obs.onlineEventAt = Date.now()
          if (subject.frameAt) recordOnlineDelivery(obs, subject)
          // Release the subject stream — its job (coming online) is done.
          if (subject.client) subject.client.close()
        } else if (payload.status === 'offline' && !obs.offlineEventAt) {
          obs.offlineEventAt = Date.now()
          // The cycle is complete — release the observer stream.
          aliceClient.close()
        }
      })
      aliceClient.on('error', error => {
        obs.transportError = describeSseError(error)
        aliceClient.close()
        if (subject.client) subject.client.close()
      })
      aliceClient.on('open', () => {
        // Subject connects (nested stream — the messaging.smoke.js pattern).
        sse.open(
          EVENTS_ENDPOINT,
          {
            headers: { Authorization: `Bearer ${state.bob.accessToken}` },
            timeout: `${Math.ceil(SUBJECT_TIMEOUT_MS / 1000)}s`,
          },
          subjectClient => {
            subject.client = subjectClient
            subjectClient.on('event', event => {
              if (event.name !== 'connected') return
              const payload = safeJson(event.data)
              if (!payload || !payload.connectionId) return
              subject.frameAt = Date.now()
              // The online fanout can edge out this frame's own processing —
              // settle the delivery sample either way (FR-003 idempotence
              // makes the race harmless, the latency anchors on the frame).
              if (obs.onlineEventAt) recordOnlineDelivery(obs, subject)
            })
            subjectClient.on('error', error => {
              obs.subjectTransportError = describeSseError(error)
              subjectClient.close()
            })
            // No 'open' work: the close comes from the observer's online
            // handler (or the timeout / error paths above).
          },
        )

        // Driver context after the subject stream closed — safe for HTTP, the
        // registration behind aliceConnectionId is seconds old by now.
        if (obs.onlineEventAt && subject.frameAt) {
          if (obs.aliceConnectionId) {
            obs.heartbeatOk = sendHeartbeat(state.alice, obs.aliceConnectionId)
            if (obs.heartbeatOk) heartbeatRenewalsTotal.add(1)
          }
          const item = snapshotItem(state.alice, state.bob.userId)
          obs.onlineSnapshotOk = !!item && item.status === 'online' && item.rev >= 1
          if (obs.onlineSnapshotOk) obs.onlineRev = item.rev
          // The observer stream keeps blocking until the offline frame arrives
          // (its handler closes it) or the loss-recovery timeout cuts it.
        } else {
          // The online frame never settled (lost, or the subject stream
          // broke): converge via №36 instead of waiting for offline events.
          aliceClient.close()
        }
      })
    },
  )
  check(observerStream, { 'observer №18 stream connected (200)': r => r && r.status === 200 })

  presenceCyclesTotal.add(1)

  // A cycle is "armed" when the online transition was actually observed (both
  // the subject's connected frame and the online event) — only then are the
  // №37/№36 online assertions made and the offline half of the cycle expected.
  // A disarmed cycle (online frame lost, №18 at-most-once) still must converge
  // through №36 — that is the loss path of конституция III, not a failure.
  const armed = obs.onlineEventAt > 0 && subject.frameAt > 0

  let onlineConverged = obs.onlineDelivered
  if (!onlineConverged) {
    const item = snapshotItem(state.alice, state.bob.userId)
    onlineConverged = !!item && item.status === 'online'
  }

  // Offline convergence: the frame path, or (at-most-once №18) the snapshot
  // path — a lost offline frame must not fail an armed cycle (конституция III).
  let offlineSnapshot = null
  if (armed) {
    if (obs.offlineEventAt > 0) {
      offlineTransitionsTotal.add(1)
    }
    offlineSnapshot = snapshotItem(state.alice, state.bob.userId)
    if (!obs.offlineEventAt && offlineSnapshot && offlineSnapshot.status === 'offline') {
      // The transition happened but its frame was lost — still a real offline
      // transition exercised and observed through the snapshot.
      offlineTransitionsTotal.add(1)
    }
  }

  check(obs, {
    'observer №18 opening frame connected received': o => !!o.aliceConnectionId,
    'subject №18 opening frame connected received (SC-001 precondition)': () => !!subject.frameAt,
    'presence.updated online delivered to the observer or converged via №36 (SC-001)':
      o => o.onlineDelivered || onlineConverged,
    'online delivery within 2 s (SC-001)': o => !o.onlineDelivered || o.onlineLatencyMs <= ONLINE_DELIVERY_LIMIT_MS,
    'heartbeat №37 renewal succeeded': o => o.heartbeatOk || !armed || !o.aliceConnectionId,
    '№36 snapshot reports the published online state with rev (SC-006, FR-004)': o =>
      o.onlineSnapshotOk || !armed,
  })
  if (armed) {
    check(obs, {
      'offline transition observed (event or №36 convergence)': () =>
        obs.offlineEventAt > 0 || (offlineSnapshot && offlineSnapshot.status === 'offline'),
      '№36 snapshot after offline reports offline with strictly greater rev (SC-006, FR-003)': () =>
        !!offlineSnapshot &&
        offlineSnapshot.status === 'offline' &&
        (!obs.onlineRev || offlineSnapshot.rev > obs.onlineRev),
    })
  }
  const streamErrors = [
    obs.transportError && !isStreamTimeout(obs.transportError) ? obs.transportError : '',
    obs.subjectTransportError && !isStreamTimeout(obs.subjectTransportError) ? obs.subjectTransportError : '',
  ]
    .filter(Boolean)
    .join('; ')
  check(streamErrors, { 'realtime streams ended without transport errors': e => e === '' })

  return armed
}

function recordOnlineDelivery(obs, subject) {
  if (obs.onlineDelivered) return
  obs.onlineLatencyMs = Math.max(0, obs.onlineEventAt - subject.frameAt)
  presenceDeliveryMs.add(obs.onlineLatencyMs)
  onlineDeliveriesTotal.add(1)
  obs.onlineDelivered = true
}

// ------------------------------------------------------- messaging lane ---
// The SC-007 comparison series: the messaging send->ack->SSE cycle of
// messaging.smoke.js (trimmed to the push receipt — the read-receipt half is
// out of scope here) driven concurrently with the presence load.

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
            text: `k6 presence smoke ${runId} #${state.counter}`,
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
