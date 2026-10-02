import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../api/client'
import type { ApiProblem } from '../../api/auth'
import {
  HEARTBEAT_INTERVAL_MS,
  createPresenceHeartbeat,
  fetchPresenceSnapshot,
  sendPresenceHeartbeat,
} from '../presenceApi'

/**
 * Presence API client (feature 007, T019; contracts/presence-api.md §1/§2,
 * research.md §E2):
 *
 * - №36 batch snapshot: deduped comma-joined `userIds`, one request per
 *   displayed surface batch (≤200 — chunking belongs to usePresence);
 * - №37 heartbeat: 204 → renewed; 404 `presence_connection_not_found` →
 *   the registration is dead, the SSE channel must reconnect IMMEDIATELY;
 *   429 `flood_limit` → KEEP the SSE connection and retry in the next
 *   interval — the 90 s TTL gives a 3× margin over the 30 s interval;
 * - the scheduler is fed the connectionId of every `connected` №18 frame
 *   (a reconnect issues a NEW id; the old one self-expires by TTL).
 */

vi.mock('../../api/client', () => ({ apiFetch: vi.fn() }))

const mockedApiFetch = vi.mocked(apiFetch)

const ALICE = '11111111-1111-4111-8111-111111111111'
const BOB = '22222222-2222-4222-8222-222222222222'
const CONNECTION_ID = '7c9e6679-7425-40de-944b-e07fc1f90ae7'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function noContent(): Response {
  return new Response(null, { status: 204 })
}

function problemResponse(status: number, errors?: Record<string, string[]>): Response {
  return new Response(JSON.stringify({ title: 'HTTP error', status, errors }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

function heartbeatCalls(): Array<[path: string, init?: RequestInit | undefined]> {
  return mockedApiFetch.mock.calls.filter(([path]) => path === '/users/me/presence/heartbeat')
}

function lastHeartbeatBody(): { connectionId?: string } {
  const calls = heartbeatCalls()
  const [, init] = calls[calls.length - 1]!
  return JSON.parse(init?.body as string) as { connectionId?: string }
}

beforeEach(() => {
  mockedApiFetch.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('№36 fetchPresenceSnapshot (T019)', () => {
  it('requests the deduped userIds comma-joined and returns the items', async () => {
    mockedApiFetch.mockResolvedValue(
      jsonResponse(200, {
        items: [
          { userId: ALICE, status: 'online', rev: 5 },
          { userId: BOB, status: 'unknown', rev: 1 },
        ],
      }),
    )

    const items = await fetchPresenceSnapshot([ALICE, BOB, ALICE])

    expect(mockedApiFetch).toHaveBeenCalledTimes(1)
    const [path, init] = mockedApiFetch.mock.calls[0]!
    expect(path).toBe(`/users/me/presence?userIds=${ALICE},${BOB}`)
    expect(new Headers(init?.headers).get('Accept')).toBe(
      'application/json, application/problem+json',
    )
    expect(items).toEqual([
      { userId: ALICE, status: 'online', rev: 5 },
      { userId: BOB, status: 'unknown', rev: 1 },
    ])
  })

  it('skips the network entirely for an empty surface batch', async () => {
    const items = await fetchPresenceSnapshot([])

    expect(items).toEqual([])
    expect(mockedApiFetch).not.toHaveBeenCalled()
  })

  it('throws the RFC 9457 problem on a non-OK answer', async () => {
    mockedApiFetch.mockResolvedValue(problemResponse(400, { userIds: ['presence_ids_too_many'] }))

    const problem = await fetchPresenceSnapshot([ALICE]).catch(
      (error: unknown) => error as ApiProblem,
    )

    expect(problem).toMatchObject({ status: 400, errors: { userIds: ['presence_ids_too_many'] } })
  })
})

describe('№37 sendPresenceHeartbeat (T019)', () => {
  it('posts the connectionId and classifies 204 as renewed', async () => {
    mockedApiFetch.mockResolvedValue(noContent())

    const outcome = await sendPresenceHeartbeat(CONNECTION_ID)

    expect(outcome).toBe('renewed')
    const [path, init] = mockedApiFetch.mock.calls[0]!
    expect(path).toBe('/users/me/presence/heartbeat')
    expect(init?.method).toBe('POST')
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(init?.body as string)).toEqual({ connectionId: CONNECTION_ID })
  })

  it('classifies 404 as connection_lost (presence_connection_not_found)', async () => {
    mockedApiFetch.mockResolvedValue(problemResponse(404))

    await expect(sendPresenceHeartbeat(CONNECTION_ID)).resolves.toBe('connection_lost')
  })

  it('classifies 429 as flood_limited', async () => {
    mockedApiFetch.mockResolvedValue(problemResponse(429))

    await expect(sendPresenceHeartbeat(CONNECTION_ID)).resolves.toBe('flood_limited')
  })

  it('classifies a network failure as a transient error — no unhandled rejection', async () => {
    mockedApiFetch.mockRejectedValue(new TypeError('network down'))

    await expect(sendPresenceHeartbeat(CONNECTION_ID)).resolves.toBe('error')
  })
})

describe('heartbeat scheduler (T019, research.md §E2)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  it('beats №37 every interval with the current connectionId; the first beat waits one interval', async () => {
    mockedApiFetch.mockResolvedValue(noContent())
    const heartbeat = createPresenceHeartbeat()
    heartbeat.updateConnectionId(CONNECTION_ID)

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS - 1)
    expect(mockedApiFetch).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(mockedApiFetch).toHaveBeenCalledTimes(1)
    expect(lastHeartbeatBody().connectionId).toBe(CONNECTION_ID)

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(mockedApiFetch).toHaveBeenCalledTimes(2)
    heartbeat.stop()
  })

  it('404 → reconnects immediately and stops beating the dead connectionId; a new connected frame resumes', async () => {
    mockedApiFetch.mockResolvedValue(problemResponse(404))
    const onReconnectRequired = vi.fn()
    const heartbeat = createPresenceHeartbeat({ onReconnectRequired })
    heartbeat.updateConnectionId(CONNECTION_ID)

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(onReconnectRequired).toHaveBeenCalledTimes(1)
    expect(heartbeatCalls()).toHaveLength(1)

    // The dead registration is not beaten again while the SSE reconnects…
    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS * 3)
    expect(heartbeatCalls()).toHaveLength(1)

    // …the reconnect's `connected` frame restarts the loop with a NEW id.
    mockedApiFetch.mockResolvedValue(noContent())
    heartbeat.updateConnectionId('99999999-9999-4999-8999-999999999999')
    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(heartbeatCalls()).toHaveLength(2)
    expect(lastHeartbeatBody().connectionId).toBe('99999999-9999-4999-8999-999999999999')
    heartbeat.stop()
  })

  it('429 → keeps the SSE connection and retries in the next interval (3× TTL margin)', async () => {
    mockedApiFetch.mockResolvedValue(problemResponse(429))
    const onReconnectRequired = vi.fn()
    const heartbeat = createPresenceHeartbeat({ onReconnectRequired })
    heartbeat.updateConnectionId(CONNECTION_ID)

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(heartbeatCalls()).toHaveLength(1)
    expect(onReconnectRequired).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(heartbeatCalls()).toHaveLength(2)
    expect(onReconnectRequired).not.toHaveBeenCalled()
    heartbeat.stop()
  })

  it('a new connected frame resets the beat phase and switches to the new connectionId', async () => {
    mockedApiFetch.mockResolvedValue(noContent())
    const heartbeat = createPresenceHeartbeat()
    heartbeat.updateConnectionId(CONNECTION_ID)

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS / 2)
    heartbeat.updateConnectionId('99999999-9999-4999-8999-999999999999')

    // Half an interval since the restart — the phase started over.
    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS / 2)
    expect(mockedApiFetch).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS / 2 + 1)
    expect(mockedApiFetch).toHaveBeenCalledTimes(1)
    expect(lastHeartbeatBody().connectionId).toBe('99999999-9999-4999-8999-999999999999')
    heartbeat.stop()
  })

  it('a transient network failure retries in the next interval without a reconnect', async () => {
    mockedApiFetch
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValue(noContent())
    const onReconnectRequired = vi.fn()
    const heartbeat = createPresenceHeartbeat({ onReconnectRequired })
    heartbeat.updateConnectionId(CONNECTION_ID)

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(heartbeatCalls()).toHaveLength(1)
    expect(onReconnectRequired).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(heartbeatCalls()).toHaveLength(2)
    expect(onReconnectRequired).not.toHaveBeenCalled()
    heartbeat.stop()
  })

  it('stop() halts the beats', async () => {
    mockedApiFetch.mockResolvedValue(noContent())
    const heartbeat = createPresenceHeartbeat()
    heartbeat.updateConnectionId(CONNECTION_ID)
    heartbeat.stop()

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS * 3)
    expect(mockedApiFetch).not.toHaveBeenCalled()
  })
})
