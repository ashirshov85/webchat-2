import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deliveryAck } from '../../api/chats'
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
