import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deliveryAck } from '../../api/chats'
import type { ApiProblem } from '../../api/auth'
import { advanceCursor, readCursors } from '../cursors'
import { ACK_BATCH_LIMIT, ACK_DEBOUNCE_MS, createAckBatcher, getAckBatcher } from '../ack'
import type { AckBatcher } from '../ack'

vi.mock('../../api/chats', () => ({ deliveryAck: vi.fn() }))

const mockedDeliveryAck = vi.mocked(deliveryAck)

function ackItems(entries: Record<string, number>) {
  return Object.entries(entries).map(([chatId, upToSeq]) => ({ chatId, upToSeq }))
}

function chatId(index: number): string {
  return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
}

let batcher: AckBatcher

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  batcher = createAckBatcher()
})

afterEach(() => {
  batcher.dispose()
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('ack debounce flushes within ≤1 s (sync-protocol.md §2)', () => {
  it('sends nothing before the debounce window elapses', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS - 1)

    expect(mockedDeliveryAck).not.toHaveBeenCalled()
  })

  it('flushes at the debounce deadline without an explicit flush call', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(1)]: 5 }))
  })

  it('items arriving inside the window merge into one request (amortization N:1)', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    await vi.advanceTimersByTimeAsync(400)
    batcher.ack(chatId(2), 9)
    await vi.advanceTimersByTimeAsync(400)

    expect(mockedDeliveryAck).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(200)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(1)]: 5, [chatId(2)]: 9 }))
    expect(batcher.pendingSize()).toBe(0)
  })
})

describe('pending positions merge per chat by max', () => {
  it('a smaller seq for a known chat is ignored', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    batcher.ack(chatId(1), 3)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(1)]: 5 }))
  })

  it('a larger seq replaces the pending position of the same chat', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    batcher.ack(chatId(1), 9)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(1)]: 9 }))
  })

  it('invalid input is never queued nor sent', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack('', 5)
    batcher.ack(chatId(1), 0)
    batcher.ack(chatId(1), 1.5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(batcher.pendingSize()).toBe(0)
    expect(mockedDeliveryAck).not.toHaveBeenCalled()
  })
})

describe('batch limit of №25 — at most 100 items per request', () => {
  it('drains an oversized pending set in sequential ≤100-item requests', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    const total = ACK_BATCH_LIMIT + 50
    for (let index = 1; index <= total; index += 1) {
      batcher.ack(chatId(index), index)
    }

    await batcher.flush()

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)
    const [first, second] = mockedDeliveryAck.mock.calls
    expect(first?.[0]).toHaveLength(ACK_BATCH_LIMIT)
    expect(second?.[0]).toHaveLength(50)
    expect(first?.[0]?.every((item) => item.upToSeq >= 1)).toBe(true)
    expect(batcher.pendingSize()).toBe(0)
  })
})

describe('a failed ack is retried by the next flush', () => {
  it('keeps entries pending, re-arms the timer and retries within ≤1 s', async () => {
    mockedDeliveryAck.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    // №25 is atomic — the rejected batch left no partial effects, entries stay pending
    expect(batcher.pendingSize()).toBe(1)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)
    expect(mockedDeliveryAck).toHaveBeenLastCalledWith(ackItems({ [chatId(1)]: 5 }))
    expect(batcher.pendingSize()).toBe(0)
  })

  it('a rejection from flush() reports but never strands the tail', async () => {
    mockedDeliveryAck.mockRejectedValue(new Error('offline'))
    batcher.ack(chatId(1), 5)

    await expect(batcher.flush()).rejects.toThrow('offline')
    expect(batcher.pendingSize()).toBe(1)
  })

  it('a seq merged while a flush is in flight is confirmed by a follow-up request', async () => {
    let resolveFirst: (() => void) | undefined
    mockedDeliveryAck.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          resolveFirst = resolve
        }),
    )
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    const flushing = batcher.flush()
    batcher.ack(chatId(1), 9)

    resolveFirst?.()
    await flushing

    // only the confirmed value (5) was consumed; the in-flight merge (9)
    // is picked up by the drain loop's next iteration, never lost
    expect(mockedDeliveryAck).toHaveBeenNthCalledWith(1, ackItems({ [chatId(1)]: 5 }))
    expect(mockedDeliveryAck).toHaveBeenNthCalledWith(2, ackItems({ [chatId(1)]: 9 }))
    expect(batcher.pendingSize()).toBe(0)
  })
})

describe('dispose cancels the timer and drops the tail', () => {
  it('sends nothing after teardown', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    batcher.dispose()

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).not.toHaveBeenCalled()
    expect(batcher.pendingSize()).toBe(0)
  })
})

/**
 * §5.5 drop (feature 006, US5, T055 → T058; realtime-group-events.md
 * §5.5 + api-contract.md №25): after `group.you_removed` the server
 * treats the caller as a non-participant for the group's chatId — an
 * ack batch carrying it is refused WHOLE (403 not_participant,
 * all-or-refusal), so the removed group's chatId must leave the
 * pending №25 batches: `drop(chatId)` tombstones the chat — its
 * pending position is discarded (never sent) and acks arriving AFTER
 * the removal are suppressed too (the §1 order race may still feed
 * frames committed before the removal through useChatMessages/useSync).
 * The `group.deleted` case joins with the same call in US6 (T062).
 */
describe('§5.5: a chat dropped after group.you_removed leaves the pending №25 batches', () => {
  it('a pending position of the removed group is never sent — otherwise the whole batch gets 403 not_participant', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    batcher.ack(chatId(2), 9)

    batcher.drop(chatId(1))

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(2)]: 9 }))
    expect(batcher.pendingSize()).toBe(0)
  })

  it('a late ack of the removed chat is suppressed — the §1 race may still deliver frames committed before the removal', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.drop(chatId(1))

    batcher.ack(chatId(1), 5)

    expect(batcher.pendingSize()).toBe(0)
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).not.toHaveBeenCalled()
  })

  it('drop is idempotent and tombstones even a chat with nothing pending (FR-015)', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    batcher.drop(chatId(1))
    batcher.drop(chatId(1))

    batcher.drop(chatId(2))
    batcher.ack(chatId(2), 7)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).not.toHaveBeenCalled()
    expect(batcher.pendingSize()).toBe(0)
  })
})

describe('the shared per-account batcher carries the §5.5 drop (T058 realtime wiring)', () => {
  it("getAckBatcher(user).drop excludes the chat from that account's pending №25 batches", async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    const shared = getAckBatcher('00000000-0000-4000-8000-00000000000a')
    try {
      shared.ack(chatId(1), 5)
      shared.ack(chatId(2), 9)

      shared.drop(chatId(1))

      await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

      expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
      expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(2)]: 9 }))
      expect(shared.pendingSize()).toBe(0)
    } finally {
      shared.dispose()
    }
  })
})

/**
 * §5.5 group.deleted half (US6, T062 → T066; the case T055 deferred
 * to T062): №30 hard-delete makes the caller a non-participant of the
 * group's chatId exactly like `group.you_removed` — №25 stays
 * all-or-refusal, so the deleted group's chatId leaves the pending
 * batches through the SAME `drop(chatId)` (the MessengerPage wiring
 * drops it on the `group.deleted` frame, T066): the pending position
 * is discarded and the §1 race's late acks — frames committed before
 * the delete may still be published after it — are suppressed.
 */
describe('§5.5: a chat dropped after group.deleted leaves the pending №25 batches too', () => {
  it('the hard-deleted group (№30) never reaches №25 — pending discarded, late acks suppressed', async () => {
    mockedDeliveryAck.mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    batcher.ack(chatId(2), 9)

    batcher.drop(chatId(1))
    // §1 race: a frame committed before the delete acks after it.
    batcher.ack(chatId(1), 7)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(2)]: 9 }))
    expect(batcher.pendingSize()).toBe(0)
  })
})

/**
 * T088 (009, bug-fix of the eternal №25 retry loop): a HARD-deleted
 * group (№30) leaves dead chatIds in the PERSISTED cursors
 * (`webchat.sync.cursors.<userId>` — cursors.ts had no deletion API),
 * so every new session's T052 re-feed re-queues the dead chatId and
 * №25 (all-or-refusal) rejects the whole batch with 404 chat_not_found
 * forever — 1/s in the foreground, ~4/s throttled in a background tab,
 * poisoning the acks of LIVE chats merged into the same batch. The
 * drain now classifies №25 failures by ApiProblem: the terminal codes
 * (404 chat_not_found, 403 not_participant, 400 invalid_up_to_seq —
 * the position is unreachable forever: no chat/membership, last_seq
 * never rolls back) isolate and DROP the culprit; everything else
 * (network, 408, 429, 5xx) is transient — entries stay pending and
 * the retry backs off exponentially instead of hammering 1/s.
 */
function problem(status: number, code: string): ApiProblem {
  return { title: `HTTP ${status}`, status, errors: { chat: [code] } }
}

describe('T088: a terminal 404 chat_not_found drops the culprit instead of retrying forever', () => {
  it('(а) a single-chat chunk 404s ONCE: the culprit is dropped, the drain resolves, a repeated ack is suppressed', async () => {
    mockedDeliveryAck
      .mockRejectedValueOnce(problem(404, 'chat_not_found'))
      .mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    // exactly one POST — the 404 was terminal, no retry was scheduled
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith(ackItems({ [chatId(1)]: 5 }))
    expect(batcher.pendingSize()).toBe(0)

    // the tombstone suppresses a late ack of the dead chat
    batcher.ack(chatId(1), 7)
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(batcher.pendingSize()).toBe(0)
  })

  it('(б) a poisoned batch [A, B, C] isolates the culprit: A dropped, B and C confirmed by single requests', async () => {
    mockedDeliveryAck
      .mockRejectedValueOnce(problem(404, 'chat_not_found')) // the batch [A, B, C]
      .mockRejectedValueOnce(problem(404, 'chat_not_found')) // isolation: [A] alone
      .mockResolvedValue(undefined) // isolation: [B], [C]
    batcher.ack(chatId(1), 5)
    batcher.ack(chatId(2), 9)
    batcher.ack(chatId(3), 11)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(4)
    expect(mockedDeliveryAck).toHaveBeenNthCalledWith(
      1,
      ackItems({ [chatId(1)]: 5, [chatId(2)]: 9, [chatId(3)]: 11 }),
    )
    expect(mockedDeliveryAck).toHaveBeenNthCalledWith(2, ackItems({ [chatId(1)]: 5 }))
    expect(mockedDeliveryAck).toHaveBeenNthCalledWith(3, ackItems({ [chatId(2)]: 9 }))
    expect(mockedDeliveryAck).toHaveBeenNthCalledWith(4, ackItems({ [chatId(3)]: 11 }))
    expect(batcher.pendingSize()).toBe(0)

    // the dropped A stays suppressed; a LIVE chat still acks normally
    batcher.ack(chatId(1), 7)
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(4)

    batcher.ack(chatId(2), 12)
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenNthCalledWith(5, ackItems({ [chatId(2)]: 12 }))
    expect(batcher.pendingSize()).toBe(0)
  })

  it('(в) 403 not_participant and 400 invalid_up_to_seq are terminal too — drop, no retry', async () => {
    mockedDeliveryAck.mockRejectedValue(problem(403, 'not_participant'))
    batcher.ack(chatId(1), 5)
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(batcher.pendingSize()).toBe(0)

    // invalid_up_to_seq is terminal as well: the future cursor is
    // healed by №26's rewind (cursors.ts §5), never by ack retries
    mockedDeliveryAck.mockRejectedValue(problem(400, 'invalid_up_to_seq'))
    batcher.ack(chatId(2), 9)
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)
    expect(batcher.pendingSize()).toBe(0)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)
  })

  it('a terminal 400 whose code is NOT in the terminal class stays a transient retry (classification is code-exact)', async () => {
    mockedDeliveryAck
      .mockRejectedValueOnce(problem(400, 'invalid_uuid'))
      .mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(batcher.pendingSize()).toBe(1)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)
    expect(batcher.pendingSize()).toBe(0)
  })

  it('a transient failure mid-isolation keeps the remaining entries pending and re-arms the retry (isolation never gets stuck)', async () => {
    mockedDeliveryAck
      .mockRejectedValueOnce(problem(404, 'chat_not_found')) // batch [A, B]
      .mockRejectedValueOnce(problem(404, 'chat_not_found')) // isolation [A] → drop
      .mockRejectedValueOnce(new Error('offline')) // isolation [B] → transient
      .mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)
    batcher.ack(chatId(2), 9)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(3)
    // A dropped; B stays pending and is confirmed by the NEXT flush
    expect(batcher.pendingSize()).toBe(1)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

    expect(mockedDeliveryAck).toHaveBeenLastCalledWith(ackItems({ [chatId(2)]: 9 }))
    expect(batcher.pendingSize()).toBe(0)
  })
})

describe('T088: transient retries back off exponentially instead of hammering 1/s', () => {
  it('(г) consecutive failures double the delay 1 s → 2 s; a success resets the backoff to 1 s', async () => {
    mockedDeliveryAck
      .mockRejectedValueOnce(new Error('offline')) // t=1 s: failure #1 → retry in 1 s
      .mockRejectedValueOnce(new Error('offline')) // t=2 s: failure #2 → retry in 2 s
      .mockResolvedValue(undefined) // t=4 s: success
    batcher.ack(chatId(1), 5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)

    // the second failure pushed the next attempt to +2 s — nothing at +1 s
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(3)
    expect(batcher.pendingSize()).toBe(0)

    // the success reset the backoff: the NEXT failure retries after 1 s again
    mockedDeliveryAck.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    batcher.ack(chatId(2), 3)
    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(4)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(5)
    expect(mockedDeliveryAck).toHaveBeenLastCalledWith(ackItems({ [chatId(2)]: 3 }))
    expect(batcher.pendingSize()).toBe(0)
  })

  it('the backoff is capped at 30 s', async () => {
    let calls = 0
    mockedDeliveryAck.mockImplementation(() => {
      calls += 1
      return Promise.reject(new Error('offline'))
    })
    batcher.ack(chatId(1), 5)

    // failures at t=1,2,4,8,16 s arm 1→2→4→8→16 s delays
    await vi.advanceTimersByTimeAsync(31_000)
    expect(calls).toBe(5)

    // failure #6 (t=32 s) would arm 32 s — the cap holds it at 30 s:
    // nothing before t=62 s
    await vi.advanceTimersByTimeAsync(1_000)
    expect(calls).toBe(6)
    await vi.advanceTimersByTimeAsync(29_999)
    expect(calls).toBe(6)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(calls).toBe(7)
  })

  it('(д) a 429 with Retry-After defers to the server window', async () => {
    const tooMany: ApiProblem = {
      title: 'Too Many Requests',
      status: 429,
      errors: { flood: ['flood_limit'] },
      retryAfterSec: 5,
    }
    mockedDeliveryAck.mockRejectedValueOnce(tooMany).mockResolvedValue(undefined)
    batcher.ack(chatId(1), 5)

    await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)

    // the backoff (1 s) yields to retryAfterSec=5 s — nothing before +5 s
    await vi.advanceTimersByTimeAsync(4_000)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(2)
    expect(batcher.pendingSize()).toBe(0)
  })
})

describe('T088: drop() also forgets the persisted cursor of the per-account batcher', () => {
  it('(е) getAckBatcher(userId).drop removes the chatId from readCursors — T052 re-feed cannot resurrect it', () => {
    const userId = '00000000-0000-4000-8000-0000000000ee'
    window.localStorage.clear()
    advanceCursor(userId, chatId(1), 9)
    advanceCursor(userId, chatId(2), 4)
    const shared = getAckBatcher(userId)
    try {
      shared.drop(chatId(1))

      expect(readCursors(userId)).not.toHaveProperty(chatId(1))
      expect(readCursors(userId)).toEqual({ [chatId(2)]: 4 })

      // the standalone batcher (custom owners) has no account context —
      // its drop stays session-local and never touches another owner's
      // persisted cursors
      batcher.drop(chatId(2))
      expect(readCursors(userId)).toEqual({ [chatId(2)]: 4 })
    } finally {
      shared.dispose()
      window.localStorage.clear()
    }
  })

  it('a terminal 404 during a drain forgets the dead cursor of the per-account batcher too', async () => {
    const userId = '00000000-0000-4000-8000-0000000000ef'
    window.localStorage.clear()
    advanceCursor(userId, chatId(1), 9)
    const shared = getAckBatcher(userId)
    try {
      mockedDeliveryAck
        .mockRejectedValueOnce(problem(404, 'chat_not_found'))
        .mockResolvedValue(undefined)
      shared.ack(chatId(1), 9)

      await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)

      expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
      expect(shared.pendingSize()).toBe(0)
      // the isolation drop is the SAME drop(): the persisted cursor is
      // gone, so the next session's T052 re-feed never re-queues it
      expect(readCursors(userId)).toEqual({})
    } finally {
      shared.dispose()
      window.localStorage.clear()
    }
  })
})
