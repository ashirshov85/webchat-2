/**
 * Outbox send-failure classification tests (feature 005, T025; FR-006/
 * FR-009, US2, data-model entity 4; sync-protocol.md §7): how the
 * `useOutbox` retry pipeline reacts to every class of send failures.
 *
 * - `503 server_busy` (№16 backpressure — mocked here, the E2E effect
 *   arrives with T041) is handled EXACTLY like `429 flood_limit`
 *   (FR-009): the record stays `sending` with
 *   `retryAt = now + Retry-After`, the retry fires by the SAME
 *   `clientMessageId` at the deadline, no terminal state ever appears
 *   and the user sees no error (transparent queue).
 * - network/timeout/5xx (500/502/504) → `sending` + exponential backoff
 *   1s…30s (capped) retried indefinitely by the same id — a prolonged
 *   temporary outage never escalates to `failed` (clarify: бессрочные
 *   повторы, никаких эскалаций по времени).
 * - permanent 4xx (400/403/404/409) → terminal `failed` with the
 *   contract error code, auto-retries stop, the rest of the queue keeps
 *   flowing (FR-006).
 * - flush on (re)start/login is FIFO per chat: a chat's records are
 *   attempted in enqueue order and one chat's Retry-After lock never
 *   delays another chat's records (sync-protocol.md §7 — «чат не ждёт
 *   чужой блокировки»).
 *
 * Written test-first (constitution VI): the 503 cases must FAIL until
 * T027 extends the classifier in `useOutbox` with `server_busy`.
 */
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { sendMessage } from '../../../api/chats'
import type { Message } from '../../../api/chats'
import { useOutbox } from '../useOutbox'
import type { EnqueueResult } from '../useOutbox'
import { addOutboxRecord, readOutbox } from '../../outbox'

vi.mock('../../../api/chats', () => ({ sendMessage: vi.fn() }))

const mockedSend = vi.mocked(sendMessage)

const USER = 'user-retry'

function makeMessage(chatId: string, id: string, seq: number, text: string = 'confirmed'): Message {
  return {
    id,
    chatId,
    senderId: '9a2c-9a2c-9a2c',
    text,
    seq,
    createdAt: '2026-09-23T12:00:00.000Z',
  }
}

const problem = (status: number, code: string): Record<string, unknown> => ({
  title: 'Problem',
  status,
  errors: { clientMessageId: [code] },
})

/** №16 backpressure refusal: `errors: {chat: [server_busy]}` + Retry-After (sync-protocol.md §8). */
const serverBusy = (retryAfterSec?: number): Record<string, unknown> => ({
  title: 'Service Unavailable',
  status: 503,
  errors: { chat: ['server_busy'] },
  ...(retryAfterSec === undefined ? {} : { retryAfterSec }),
})

const flood = (retryAfterSec: number): Record<string, unknown> => ({
  title: 'Too Many Requests',
  status: 429,
  errors: { user: ['flood_limit'] },
  retryAfterSec,
})

type ConfirmedCallback = NonNullable<Parameters<typeof useOutbox>[1]>['onConfirmed']

const mounted: Array<{ unmount(): void }> = []

function mountOutbox(userId: string | null, onConfirmed?: ConfirmedCallback) {
  const rendered = renderHook(() =>
    useOutbox(userId, onConfirmed === undefined ? undefined : { onConfirmed }),
  )
  mounted.push(rendered)
  return rendered
}

function enqueueValid(
  result: { current: ReturnType<typeof useOutbox> },
  chatId: string,
  text: string,
): string {
  let outcome: EnqueueResult | undefined
  act(() => {
    outcome = result.current.enqueue(chatId, text)
  })
  if (outcome === undefined || !outcome.ok) {
    throw new Error(`expected enqueue to succeed: ${JSON.stringify(outcome)}`)
  }
  return outcome.clientMessageId
}

/** Seeds a persisted `sending` record to exercise the mount-flush path (US2-5). */
function seedSending(clientMessageId: string, chatId: string, text: string): void {
  addOutboxRecord(USER, { clientMessageId, chatId, text, state: 'sending' })
}

async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

function sentIds(): string[] {
  return mockedSend.mock.calls.map((call) => call[1].clientMessageId)
}

beforeEach(() => {
  // resetAllMocks (not clearAllMocks): unconsumed mock*ValueOnce entries
  // must not leak into the next test when an assertion aborts mid-test.
  vi.resetAllMocks()
  vi.useFakeTimers()
  window.localStorage.clear()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.useRealTimers()
  vi.resetAllMocks()
})

describe('useOutbox 503 server_busy — transparent queue exactly like 429 (FR-009, US2)', () => {
  it('stays sending with retryAt = now + Retry-After and retries by the same id at the deadline', async () => {
    const startedAt = Date.now()
    mockedSend
      .mockRejectedValueOnce(serverBusy(3))
      .mockResolvedValueOnce(makeMessage('chat-1', 'srv-busy', 7))
    const { result } = mountOutbox(USER)

    const clientMessageId = enqueueValid(result, 'chat-1', 'under load')

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records).toHaveLength(1)
    expect(result.current.records[0]).toMatchObject({
      clientMessageId,
      state: 'sending',
    })
    expect(result.current.records[0]?.errorCode).toBeUndefined()
    expect(result.current.records[0]?.retryAt).toBe(startedAt + 3000)

    await advance(2999)
    expect(mockedSend).toHaveBeenCalledTimes(1)

    await advance(1)
    expect(mockedSend).toHaveBeenCalledTimes(2)
    expect(sentIds()).toEqual([clientMessageId, clientMessageId])
    expect(result.current.records).toEqual([])
  })

  it('retries consecutive 503s by each Retry-After with the same id — never terminal (prolonged overload)', async () => {
    mockedSend
      .mockRejectedValueOnce(serverBusy(2))
      .mockRejectedValueOnce(serverBusy(2))
      .mockRejectedValueOnce(serverBusy(2))
      .mockResolvedValueOnce(makeMessage('chat-1', 'srv-busy', 9))
    const { result } = mountOutbox(USER)

    const clientMessageId = enqueueValid(result, 'chat-1', 'still busy')

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records[0]?.state).toBe('sending')

    await advance(2000)
    expect(mockedSend).toHaveBeenCalledTimes(2)
    expect(result.current.records[0]?.state).toBe('sending')

    await advance(2000)
    expect(mockedSend).toHaveBeenCalledTimes(3)
    expect(result.current.records[0]?.state).toBe('sending')

    await advance(2000)
    expect(mockedSend).toHaveBeenCalledTimes(4)
    expect(result.current.records).toEqual([])

    expect(sentIds()).toEqual([clientMessageId, clientMessageId, clientMessageId, clientMessageId])
  })

  it('a 503 without Retry-After stays sending (transient fallback, uniform with 429)', async () => {
    mockedSend
      .mockRejectedValueOnce(serverBusy())
      .mockResolvedValueOnce(makeMessage('chat-1', 'srv-busy', 11))
    const { result } = mountOutbox(USER)

    const clientMessageId = enqueueValid(result, 'chat-1', 'no header')

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records[0]?.state).toBe('sending')
    expect(result.current.records[0]?.errorCode).toBeUndefined()

    await advance(30000)
    expect(mockedSend).toHaveBeenCalledTimes(2)
    expect(sentIds()).toEqual([clientMessageId, clientMessageId])
    expect(result.current.records).toEqual([])
  })
})

describe('useOutbox temporary failures retry indefinitely by the same id (FR-009, US2)', () => {
  it('retries network failures with 1s…30s capped backoff and never escalates to failed', async () => {
    mockedSend.mockRejectedValue(new TypeError('offline'))
    const { result } = mountOutbox(USER)

    const clientMessageId = enqueueValid(result, 'chat-1', 'offline')

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records[0]?.state).toBe('sending')
    expect(result.current.records[0]?.retryAt).toBeUndefined()

    await advance(999)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    await advance(1)
    expect(mockedSend).toHaveBeenCalledTimes(2)

    await advance(2000)
    expect(mockedSend).toHaveBeenCalledTimes(3)
    await advance(4000)
    expect(mockedSend).toHaveBeenCalledTimes(4)
    await advance(8000)
    expect(mockedSend).toHaveBeenCalledTimes(5)
    await advance(16000)
    expect(mockedSend).toHaveBeenCalledTimes(6)
    // backoff caps at 30s (32s would exceed the ceiling)
    await advance(30000)
    expect(mockedSend).toHaveBeenCalledTimes(7)
    // «бессрочно»: ~5 more minutes of sustained outage keep retrying the same id
    for (let attempt = 0; attempt < 10; attempt += 1) {
      await advance(30000)
    }
    expect(mockedSend).toHaveBeenCalledTimes(17)

    expect(new Set(sentIds())).toEqual(new Set([clientMessageId]))
    expect(result.current.records).toEqual([
      { clientMessageId, chatId: 'chat-1', text: 'offline', state: 'sending' },
    ])
    expect(readOutbox(USER)).toHaveLength(1)
  })

  it.each([
    [
      'request timeout',
      () => Object.assign(new Error('Request timeout'), { name: 'TimeoutError' }),
    ],
    ['HTTP 500', () => problem(500, 'internal_error')],
    ['HTTP 502', () => problem(502, 'bad_gateway')],
    ['HTTP 504', () => problem(504, 'gateway_timeout')],
  ])(
    'keeps the record sending on %s — same id, plain backoff, no countdown',
    async (_name, cause) => {
      mockedSend.mockRejectedValue(cause())
      const { result } = mountOutbox(USER)

      const clientMessageId = enqueueValid(result, 'chat-1', 'temporary')

      await advance(0)
      expect(mockedSend).toHaveBeenCalledTimes(1)
      await advance(1000)
      expect(mockedSend).toHaveBeenCalledTimes(2)
      await advance(2000)
      expect(mockedSend).toHaveBeenCalledTimes(3)

      expect(new Set(sentIds())).toEqual(new Set([clientMessageId]))
      expect(result.current.records[0]).toMatchObject({
        clientMessageId,
        state: 'sending',
      })
      expect(result.current.records[0]?.retryAt).toBeUndefined()
      expect(result.current.records[0]?.errorCode).toBeUndefined()
    },
  )
})

describe('useOutbox permanent 4xx failures are terminal (FR-006, US2)', () => {
  it.each([
    [400, 'text_too_long'],
    [403, 'you_are_blocked'],
    [404, 'chat_not_found'],
    [409, 'message_id_conflict'],
  ])('marks the record failed (%s %s) and stops auto-retries', async (status, errorCode) => {
    mockedSend.mockRejectedValueOnce(problem(status, errorCode))
    const { result } = mountOutbox(USER)

    const clientMessageId = enqueueValid(result, 'chat-1', 'doomed')

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records).toEqual([
      { clientMessageId, chatId: 'chat-1', text: 'doomed', state: 'failed', errorCode },
    ])

    await advance(60000)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(readOutbox(USER)[0]?.state).toBe('failed')
  })

  it('a permanent refusal does not block the rest of the queue (FR-006)', async () => {
    // Flush order is deterministic (array walk): doomed → tail → other.
    mockedSend
      .mockRejectedValueOnce(problem(403, 'you_are_blocked'))
      .mockResolvedValueOnce(makeMessage('chat-1', 'srv-tail', 12))
      .mockResolvedValueOnce(makeMessage('chat-2', 'srv-other', 13))
    seedSending('doomed', 'chat-1', 'blocked text')
    seedSending('tail', 'chat-1', 'still flows')
    seedSending('other', 'chat-2', 'other chat')
    const { result } = mountOutbox(USER)

    await advance(0)
    expect(sentIds()).toEqual(['doomed', 'tail', 'other'])

    expect(result.current.records).toEqual([
      {
        clientMessageId: 'doomed',
        chatId: 'chat-1',
        text: 'blocked text',
        state: 'failed',
        errorCode: 'you_are_blocked',
      },
    ])
    expect(readOutbox(USER)).toHaveLength(1)

    await advance(60000)
    expect(mockedSend).toHaveBeenCalledTimes(3)
  })
})

describe('useOutbox quickstart §3.3 offline-queue validation (T029, US2 — SC-002)', () => {
  it('§3.3.1: the queue survives a restart while still offline, then delivers every message exactly once in send order', async () => {
    const confirmed: Message[] = []
    const onConfirmed: ConfirmedCallback = (message) => {
      confirmed.push(message)
    }
    mockedSend.mockRejectedValue(new TypeError('offline'))
    const first = mountOutbox(USER, onConfirmed)

    // Bob Offline: three sends are stored `sending`, first attempts fail.
    const id1 = enqueueValid(first.result, 'chat-1', 'one')
    const id2 = enqueueValid(first.result, 'chat-1', 'two')
    const id3 = enqueueValid(first.result, 'chat-1', 'three')
    await advance(0)
    expect(sentIds()).toEqual([id1, id2, id3])
    expect(readOutbox(USER)).toHaveLength(3)

    // Close the tab and reopen the app with the network still down:
    // the queue is intact (localStorage), all records keep `sending`.
    first.unmount()
    const second = mountOutbox(USER, onConfirmed)
    expect(second.result.current.records.map((record) => record.clientMessageId)).toEqual([
      id1,
      id2,
      id3,
    ])
    expect(second.result.current.records.every((record) => record.state === 'sending')).toBe(true)
    await advance(0)
    expect(sentIds()).toEqual([id1, id2, id3, id1, id2, id3])
    expect(readOutbox(USER)).toHaveLength(3)

    // Network restored: the FIFO flush (enqueue order = server
    // acceptance order) confirms every id exactly once — statuses ✓,
    // no duplicates, nothing lost (SC-002).
    mockedSend
      .mockResolvedValueOnce(makeMessage('chat-1', id1, 11, 'one'))
      .mockResolvedValueOnce(makeMessage('chat-1', id2, 12, 'two'))
      .mockResolvedValueOnce(makeMessage('chat-1', id3, 13, 'three'))
    await advance(1000)
    expect(sentIds()).toEqual([id1, id2, id3, id1, id2, id3, id1, id2, id3])
    expect(confirmed.map((message) => message.seq)).toEqual([11, 12, 13])
    expect(second.result.current.records).toEqual([])
    expect(readOutbox(USER)).toEqual([])

    await advance(60000)
    expect(mockedSend).toHaveBeenCalledTimes(9)
  })

  it('§3.3.2: a lost acknowledgement converges on retry by the same id — one confirmed instance (dedup)', async () => {
    const onConfirmed = vi.fn<NonNullable<ConfirmedCallback>>()
    const { result } = mountOutbox(USER, onConfirmed)
    const clientMessageId = enqueueValid(result, 'chat-1', 'did you get it?')
    // The first request reached the server (accepted at seq 42) but
    // the response was lost on a network blip; the retry by the SAME
    // clientMessageId hits the 004 server dedup (200 + the same
    // Message) — the dialog confirms exactly one instance.
    const serverCopy = makeMessage('chat-1', clientMessageId, 42, 'did you get it?')
    mockedSend
      .mockRejectedValueOnce(new TypeError('network blip'))
      .mockResolvedValueOnce(serverCopy)

    await advance(0)
    expect(result.current.records[0]?.state).toBe('sending')
    expect(result.current.records[0]?.retryAt).toBeUndefined()
    expect(onConfirmed).not.toHaveBeenCalled()

    await advance(1000)
    expect(sentIds()).toEqual([clientMessageId, clientMessageId])
    expect(result.current.records).toEqual([])
    expect(readOutbox(USER)).toEqual([])
    expect(onConfirmed).toHaveBeenCalledOnce()
    expect(onConfirmed.mock.calls[0]?.[0]).toBe(serverCopy)
    expect(onConfirmed.mock.calls[0]?.[1].clientMessageId).toBe(clientMessageId)
  })
})

describe('useOutbox flush is FIFO per chat (sync-protocol.md §7, US2)', () => {
  it('attempts each chat in enqueue order; a chat stuck on Retry-After never delays other chats', async () => {
    const startedAt = Date.now()
    // Flush order is deterministic (array walk): a1 → a2 → b1 at t=0,
    // then a1's own retry at its Retry-After deadline.
    mockedSend
      .mockRejectedValueOnce(flood(60))
      .mockResolvedValueOnce(makeMessage('chat-1', 'srv-a2', 13))
      .mockResolvedValueOnce(makeMessage('chat-2', 'srv-b1', 14))
      .mockRejectedValueOnce(flood(60))
    seedSending('a1', 'chat-1', 'first of chat one')
    seedSending('a2', 'chat-1', 'second of chat one')
    seedSending('b1', 'chat-2', 'independent chat')
    const { result } = mountOutbox(USER)

    await advance(0)
    // Array walk: chat-1's records go in enqueue order (a1 before a2),
    // and chat-2's record is attempted immediately — not behind chat-1's lock.
    expect(sentIds()).toEqual(['a1', 'a2', 'b1'])
    expect(mockedSend.mock.calls[0]?.[0]).toBe('chat-1')
    expect(mockedSend.mock.calls[1]?.[0]).toBe('chat-1')
    expect(mockedSend.mock.calls[2]?.[0]).toBe('chat-2')

    // Only the stuck head of chat-1 remains — with its flood countdown.
    expect(result.current.records).toHaveLength(1)
    expect(result.current.records[0]).toMatchObject({
      clientMessageId: 'a1',
      chatId: 'chat-1',
      state: 'sending',
    })
    expect(result.current.records[0]?.retryAt).toBe(startedAt + 60000)

    await advance(59999)
    expect(mockedSend).toHaveBeenCalledTimes(3)

    // The locked head retries by the same id exactly at its deadline.
    await advance(1)
    expect(mockedSend).toHaveBeenCalledTimes(4)
    expect(mockedSend.mock.calls[3]?.[1].clientMessageId).toBe('a1')
    expect(result.current.records[0]?.state).toBe('sending')
  })
})
