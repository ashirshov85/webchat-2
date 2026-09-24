import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deliveryAck, listMessagesAfter, sync } from '../../../api/chats'
import type { Message, MessagePage, SyncChatDelta, SyncResponse } from '../../../api/chats'
import type { PublicUser } from '../../../api/auth'
import { getAckBatcher } from '../../ack'
import { advanceCursor, getCursor } from '../../cursors'
import { useSync } from '../useSync'
import type { SyncChatUpdate, UseSyncResult } from '../useSync'

const api = vi.hoisted(() => ({
  sync: vi.fn(),
  listMessagesAfter: vi.fn(),
  deliveryAck: vi.fn(),
}))

vi.mock('../../../api/chats', () => api)

const realtime = vi.hoisted(() => {
  const listeners = new Set<() => void>()
  return {
    onOpen(listener: () => void) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    fireOpen() {
      for (const listener of listeners) {
        listener()
      }
    },
  }
})

vi.mock('../../../chats/hooks/useRealtime', () => ({ useRealtime: () => realtime }))

const USER = 'user-sync'
const CHAT_A = '7dc5d4a2-3b1e-4f6a-9c2d-000000000001'
const CHAT_B = '7dc5d4a2-3b1e-4f6a-9c2d-000000000002'
const PEER: PublicUser = {
  id: '9a2c9a2c-0000-4000-8000-000000000009',
  username: 'peer',
  email: 'peer@example.com',
  status: 'active',
  createdAt: '2026-01-01T00:00:00.000Z',
}

function message(chatId: string, seq: number, id: string = `m-${seq}`): Message {
  return {
    id,
    chatId,
    senderId: PEER.id,
    text: `text-${seq}`,
    seq,
    createdAt: `2026-09-24T10:00:${String(seq % 60).padStart(2, '0')}.000Z`,
  }
}

function messages(chatId: string, from: number, to: number): Message[] {
  const list: Message[] = []
  for (let seq = from; seq <= to; seq += 1) {
    list.push(message(chatId, seq))
  }
  return list
}

function delta(fields: Partial<SyncChatDelta> & Pick<SyncChatDelta, 'chatId'>): SyncChatDelta {
  return {
    peer: PEER,
    blockedByMe: false,
    startAfterSeq: 0,
    messages: [],
    hasMore: false,
    peerReadUpToSeq: 0,
    unreadCount: 0,
    lastSeq: 0,
    ...fields,
  }
}

function syncResponse(chats: SyncChatDelta[], moreChats = false): SyncResponse {
  return { chats, moreChats }
}

function page(items: Message[], nextAfter?: number): MessagePage {
  return nextAfter === undefined ? { messages: items } : { messages: items, nextAfter }
}

/**
 * The consumer-side view the hook's contract prescribes (useSync header,
 * quickstart §3.1): merge idempotently — dedup by `message.id`, order by
 * `seq`, drop local messages at/below `truncatedUpToSeq`. The assertions
 * below read the rendered view, exactly like MessengerPage would (T023).
 */
class DialogViews {
  private readonly chats = new Map<string, Map<string, Message>>()

  apply(update: SyncChatUpdate): void {
    const byId = this.chats.get(update.chatId) ?? new Map<string, Message>()
    this.chats.set(update.chatId, byId)
    if (update.truncatedUpToSeq !== undefined) {
      for (const [id, stored] of byId) {
        if (stored.seq <= update.truncatedUpToSeq) {
          byId.delete(id)
        }
      }
    }
    for (const item of update.messages) {
      byId.set(item.id, item)
    }
  }

  seqs(chatId: string): number[] {
    return [...(this.chats.get(chatId)?.values() ?? [])]
      .sort((left, right) => left.seq - right.seq)
      .map((item) => item.seq)
  }
}

interface UpdateSink {
  apply(update: SyncChatUpdate): void
}

const mounted: Array<{ unmount(): void }> = []

function mountSync(userId: string | null = USER): { current: UseSyncResult } {
  const rendered = renderHook((props: { userId: string | null }) => useSync(props.userId), {
    initialProps: { userId },
  })
  mounted.push(rendered)
  return rendered.result
}

function subscribe(sink: UpdateSink, result: { current: UseSyncResult }): void {
  act(() => {
    result.current.onChatUpdate((update) => sink.apply(update))
  })
}

/**
 * Flushes the promise chain of a fire-and-forget trigger (`void syncNow()`
 * from onOpen) without touching timers — microtask hops only, so it is safe
 * under fake timers (the ack debounce never fires mid-test).
 */
async function settle(): Promise<void> {
  for (let hop = 0; hop < 20; hop += 1) {
    await act(async () => {
      await Promise.resolve()
    })
  }
}

const mockedSync = vi.mocked(sync)
const mockedListAfter = vi.mocked(listMessagesAfter)
const mockedDeliveryAck = vi.mocked(deliveryAck)

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  window.localStorage.clear()
  mockedDeliveryAck.mockResolvedValue(undefined)
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  getAckBatcher(USER).dispose()
  vi.useRealTimers()
})

describe('useSync — catch-up trigger (SSE onOpen wiring)', () => {
  it('runs the cycle on every (re)open with the persisted cursors and contract limits', async () => {
    advanceCursor(USER, CHAT_A, 7)
    mockedSync.mockResolvedValue(syncResponse([]))
    mountSync()

    expect(mockedSync).not.toHaveBeenCalled()

    act(() => {
      realtime.fireOpen()
    })
    await settle()

    expect(mockedSync).toHaveBeenCalledTimes(1)
    expect(mockedSync).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 7 }], 20, 50)

    act(() => {
      realtime.fireOpen()
    })
    await settle()

    expect(mockedSync).toHaveBeenCalledTimes(2)
  })

  it('a null userId is a no-op', async () => {
    const result = mountSync(null)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedSync).not.toHaveBeenCalled()
    expect(result.current.syncing).toBe(false)
  })
})

describe('useSync — single-flight (research.md §7)', () => {
  it('a trigger arriving mid-cycle joins the in-flight run instead of competing', async () => {
    let resolveFirst!: (response: SyncResponse) => void
    mockedSync.mockImplementationOnce(
      () =>
        new Promise<SyncResponse>((resolve) => {
          resolveFirst = resolve
        }),
    )
    const result = mountSync()

    let first!: Promise<void>
    let second!: Promise<void>
    act(() => {
      first = result.current.syncNow()
    })
    expect(result.current.syncing).toBe(true)

    act(() => {
      second = result.current.syncNow()
    })
    expect(second).toBe(first)
    expect(mockedSync).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveFirst(syncResponse([]))
      await first
      await second
    })

    expect(mockedSync).toHaveBeenCalledTimes(1)
    expect(result.current.syncing).toBe(false)
    expect(result.current.error).toBeNull()
  })
})

describe('useSync — loop A: №26 delta application (US1-1…US1-3)', () => {
  it('delivers every missed message once, ascending by seq, acks the tail and advances the cursor', async () => {
    mockedSync
      .mockResolvedValueOnce(
        syncResponse([
          delta({
            chatId: CHAT_A,
            messages: messages(CHAT_A, 1, 10),
            lastSeq: 10,
            unreadCount: 10,
          }),
        ]),
      )
      .mockResolvedValue(syncResponse([]))
    const received: SyncChatUpdate[] = []
    const views = new DialogViews()
    const result = mountSync()
    subscribe(
      {
        apply(update) {
          received.push(update)
          views.apply(update)
        },
      },
      result,
    )

    await act(async () => {
      await result.current.syncNow()
    })

    expect(received).toHaveLength(1)
    expect(received[0]?.messages.map((item) => item.seq)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    expect(views.seqs(CHAT_A)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 10 }])
    expect(getCursor(USER, CHAT_A)).toBe(10)
  })

  it('a repeated cycle asks from the advanced cursor and renders nothing new (quickstart §3.1)', async () => {
    mockedSync
      .mockResolvedValueOnce(
        syncResponse([delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 10), lastSeq: 10 })]),
      )
      .mockResolvedValueOnce(syncResponse([]))
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })
    const rendered = views.seqs(CHAT_A)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedSync).toHaveBeenLastCalledWith([{ chatId: CHAT_A, upToSeq: 10 }], 20, 50)
    expect(views.seqs(CHAT_A)).toEqual(rendered)
  })

  it('a duplicate delivery of the same message id renders once (US1-3)', async () => {
    mockedSync.mockResolvedValue(
      syncResponse([delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 3), lastSeq: 3 })]),
    )
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    // the at-most-once realtime channel may deliver a frame the catch-up
    // page also carries — the merged view must keep one copy per id
    views.apply({ chatId: CHAT_A, messages: [message(CHAT_A, 2)] })

    await act(async () => {
      await result.current.syncNow()
    })
    await act(async () => {
      await result.current.syncNow()
    })

    expect(views.seqs(CHAT_A)).toEqual([1, 2, 3])
  })

  it('a chat unknown to the client appears with its whole history and metadata (US1-6)', async () => {
    advanceCursor(USER, CHAT_A, 30)
    mockedSync.mockResolvedValueOnce(
      syncResponse([
        delta({ chatId: CHAT_B, startAfterSeq: 0, messages: messages(CHAT_B, 1, 4), lastSeq: 4 }),
      ]),
    )
    const received: SyncChatUpdate[] = []
    const views = new DialogViews()
    const result = mountSync()
    subscribe(
      {
        apply(update) {
          received.push(update)
          views.apply(update)
        },
      },
      result,
    )

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedSync).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 30 }], 20, 50)
    expect(received[0]?.chatId).toBe(CHAT_B)
    expect(received[0]?.peer).toEqual(PEER)
    expect(views.seqs(CHAT_B)).toEqual([1, 2, 3, 4])
    expect(getCursor(USER, CHAT_B)).toBe(4)
    expect(getCursor(USER, CHAT_A)).toBe(30)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_B, upToSeq: 4 }])
  })
})

describe('useSync — loop B: №15 after continuation (§4)', () => {
  it('continues a hasMore chat page by page until nextAfter is absent', async () => {
    mockedSync.mockResolvedValueOnce(
      syncResponse([
        delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 2), hasMore: true, lastSeq: 5 }),
      ]),
    )
    mockedListAfter
      .mockResolvedValueOnce(page(messages(CHAT_A, 3, 4), 4))
      .mockResolvedValueOnce(page(messages(CHAT_A, 5, 5), undefined))
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedListAfter).toHaveBeenNthCalledWith(1, CHAT_A, 2, 50)
    expect(mockedListAfter).toHaveBeenNthCalledWith(2, CHAT_A, 4, 50)
    expect(views.seqs(CHAT_A)).toEqual([1, 2, 3, 4, 5])
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 5 }])
    expect(getCursor(USER, CHAT_A)).toBe(5)
  })

  it('an empty boundary page is a valid «caught up» answer', async () => {
    mockedSync.mockResolvedValueOnce(
      syncResponse([
        delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 2), hasMore: true, lastSeq: 2 }),
      ]),
    )
    mockedListAfter.mockResolvedValueOnce(page([], undefined))
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(views.seqs(CHAT_A)).toEqual([1, 2])
    expect(mockedListAfter).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 2 }])
  })
})

describe('useSync — moreChats: loop A repeats with refreshed cursors', () => {
  it('processes every chat page and re-asks with the already advanced cursors', async () => {
    mockedSync
      .mockResolvedValueOnce(
        syncResponse(
          [delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 1), lastSeq: 1 })],
          true,
        ),
      )
      .mockResolvedValueOnce(
        syncResponse(
          [delta({ chatId: CHAT_B, messages: messages(CHAT_B, 1, 1), lastSeq: 1 })],
          false,
        ),
      )
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedSync).toHaveBeenCalledTimes(2)
    expect(mockedSync).toHaveBeenNthCalledWith(1, [], 20, 50)
    expect(mockedSync).toHaveBeenNthCalledWith(2, [{ chatId: CHAT_A, upToSeq: 1 }], 20, 50)
    expect(views.seqs(CHAT_A)).toEqual([1])
    expect(views.seqs(CHAT_B)).toEqual([1])
    expect(mockedDeliveryAck).toHaveBeenCalledWith([
      { chatId: CHAT_A, upToSeq: 1 },
      { chatId: CHAT_B, upToSeq: 1 },
    ])
  })
})

describe('useSync — mid-cycle abort resumes from the last confirmed page (US1-4)', () => {
  it('a №15 failure keeps the confirmed prefix; the next cycle resumes from it without duplicates', async () => {
    mockedSync
      .mockResolvedValueOnce(
        syncResponse([
          delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 3), hasMore: true, lastSeq: 6 }),
        ]),
      )
      .mockResolvedValueOnce(
        syncResponse([
          delta({ chatId: CHAT_A, startAfterSeq: 3, messages: messages(CHAT_A, 4, 6), lastSeq: 6 }),
        ]),
      )
    mockedListAfter.mockRejectedValueOnce(new Error('connection lost'))
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(result.current.error).toBeTruthy()
    expect(result.current.syncing).toBe(false)
    expect(views.seqs(CHAT_A)).toEqual([1, 2, 3])
    expect(getCursor(USER, CHAT_A)).toBe(3)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(result.current.error).toBeNull()
    expect(mockedSync).toHaveBeenLastCalledWith([{ chatId: CHAT_A, upToSeq: 3 }], 20, 50)
    expect(views.seqs(CHAT_A)).toEqual([1, 2, 3, 4, 5, 6])
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 6 }])
  })

  it('a №26 failure aborts without touching cursors; the next cycle starts over from them', async () => {
    mockedSync
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(
        syncResponse([delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 2), lastSeq: 2 })]),
      )
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(result.current.error).toBeTruthy()
    expect(views.seqs(CHAT_A)).toEqual([])
    expect(getCursor(USER, CHAT_A)).toBe(0)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(result.current.error).toBeNull()
    expect(views.seqs(CHAT_A)).toEqual([1, 2])
  })
})

describe('useSync — truncation never restores deleted history (US1-5)', () => {
  it('drops local messages below truncatedUpToSeq and pins the cursor on the point', async () => {
    advanceCursor(USER, CHAT_A, 2)
    mockedSync.mockResolvedValueOnce(
      syncResponse([
        delta({
          chatId: CHAT_A,
          startAfterSeq: 42,
          truncatedUpToSeq: 42,
          messages: messages(CHAT_A, 43, 45),
          lastSeq: 45,
        }),
      ]),
    )
    const views = new DialogViews()
    // stale local history the server deleted for this user (004 per-chat removal)
    views.apply({ chatId: CHAT_A, messages: [message(CHAT_A, 1), message(CHAT_A, 2)] })
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(views.seqs(CHAT_A)).toEqual([43, 44, 45])
    expect(getCursor(USER, CHAT_A)).toBe(45)
    expect(mockedListAfter).not.toHaveBeenCalled()
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 45 }])
  })

  it('a truncation-only delta acks the truncation point itself (§2 ack sources)', async () => {
    advanceCursor(USER, CHAT_A, 5)
    mockedSync.mockResolvedValueOnce(
      syncResponse([
        delta({
          chatId: CHAT_A,
          startAfterSeq: 42,
          truncatedUpToSeq: 42,
          messages: [],
          lastSeq: 42,
        }),
      ]),
    )
    const received: SyncChatUpdate[] = []
    const result = mountSync()
    subscribe(
      {
        apply(update) {
          received.push(update)
        },
      },
      result,
    )

    await act(async () => {
      await result.current.syncNow()
    })

    expect(received).toHaveLength(1)
    expect(received[0]?.truncatedUpToSeq).toBe(42)
    expect(received[0]?.messages).toEqual([])
    expect(getCursor(USER, CHAT_A)).toBe(42)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 42 }])
  })
})
