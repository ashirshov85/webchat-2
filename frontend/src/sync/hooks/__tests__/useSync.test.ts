import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deliveryAck, listMessagesAfter, markChatRead, sync } from '../../../api/chats'
import type { Message, MessagePage, SyncChatDelta, SyncResponse } from '../../../api/chats'
import type { PublicUser } from '../../../api/auth'
import { getAckBatcher, ACK_DEBOUNCE_MS, ACK_RETRY_MAX_MS } from '../../ack'
import { advanceCursor, getCursor } from '../../cursors'
import { getPendingRead, readPendingReads, recordPendingRead } from '../../pendingReads'
import { useSync } from '../useSync'
import type { SyncChatUpdate, UseSyncResult } from '../useSync'

const api = vi.hoisted(() => ({
  sync: vi.fn(),
  listMessagesAfter: vi.fn(),
  deliveryAck: vi.fn(),
  markChatRead: vi.fn(),
}))

vi.mock('../../../api/chats', () => api)

const realtime = vi.hoisted(() => {
  const openListeners = new Set<() => void>()
  const connectedListeners = new Set<(connectionId: string) => void>()
  let lastConnectionId: string | null = null
  return {
    onOpen(listener: () => void) {
      openListeners.add(listener)
      return () => {
        openListeners.delete(listener)
      }
    },
    fireOpen() {
      for (const listener of openListeners) {
        listener()
      }
    },
    // Mirrors RealtimeConnection.onConnected (useRealtime.ts): replays
    // the CURRENT connectionId to a late subscriber (008 T076) — the
    // replay semantics the T093 trigger relies on.
    onConnected(listener: (connectionId: string) => void) {
      if (lastConnectionId !== null) {
        listener(lastConnectionId)
      }
      connectedListeners.add(listener)
      return () => {
        connectedListeners.delete(listener)
      }
    },
    fireConnected(connectionId: string) {
      lastConnectionId = connectionId
      for (const listener of connectedListeners) {
        listener(connectionId)
      }
    },
    // release() of the real singleton clears lastConnectionId
    reset() {
      lastConnectionId = null
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
const mockedMarkChatRead = vi.mocked(markChatRead)

beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  window.localStorage.clear()
  mockedDeliveryAck.mockResolvedValue(undefined)
  mockedMarkChatRead.mockResolvedValue(undefined)
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  getAckBatcher(USER).dispose()
  realtime.reset()
  vi.useRealTimers()
})

describe('useSync — catch-up trigger (SSE connected frame, T093)', () => {
  it('runs the cycle on every connected frame with the persisted cursors and contract limits', async () => {
    advanceCursor(USER, CHAT_A, 7)
    mockedSync.mockResolvedValue(syncResponse([]))
    mountSync()

    expect(mockedSync).not.toHaveBeenCalled()

    act(() => {
      realtime.fireConnected('conn-1')
    })
    await settle()

    expect(mockedSync).toHaveBeenCalledTimes(1)
    expect(mockedSync).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 7 }], 20, 50)

    act(() => {
      realtime.fireConnected('conn-2')
    })
    await settle()

    expect(mockedSync).toHaveBeenCalledTimes(2)
  })

  /**
   * T093 race A: the shared №18 stream opens in PresenceKeeper
   * (ProtectedRoute) BEFORE MessengerPage mounts and getCurrentUser()
   * resolves — the `connected` frame is long gone by the time useSync
   * subscribes. The raw onOpen is NOT replayed to late subscribers, so
   * the old wiring never started the cycle; the connected frame IS
   * replayed (lastConnectionId), so the late mount still catches up.
   */
  it('race A (T093): a connected frame delivered BEFORE the subscriber mount still starts the cycle (lastConnectionId replay)', async () => {
    mockedSync.mockResolvedValue(syncResponse([]))
    realtime.fireConnected('conn-early')
    const result = mountSync()

    await settle()

    expect(mockedSync).toHaveBeenCalledTimes(1)
    expect(result.current.syncing).toBe(false)
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
    // T052: the cycle START re-feeds the persisted cursor tails through
    // the shared batcher (the secondary-window top-up below), so the
    // №25 batch of this cycle carries CHAT_A's tail alongside the
    // applied page's ack.
    expect(mockedDeliveryAck).toHaveBeenCalledWith([
      { chatId: CHAT_A, upToSeq: 30 },
      { chatId: CHAT_B, upToSeq: 4 },
    ])
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

describe('useSync — №26 group deltas (feature 006, US2, T039)', () => {
  it('carries the group othersReadUpToSeq watermark to the open dialog (FR-012 offline ✓✓)', async () => {
    mockedSync
      .mockResolvedValueOnce(
        syncResponse([
          delta({
            chatId: CHAT_A,
            type: 'group',
            title: 'Проект Альфа',
            memberCount: 3,
            othersReadUpToSeq: 12,
            peer: null,
            blockedByMe: null,
            peerReadUpToSeq: null,
            messages: messages(CHAT_A, 9, 10),
            lastSeq: 10,
            unreadCount: 2,
          }),
        ]),
      )
      .mockResolvedValue(syncResponse([]))
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

    // The reconnect catch-up is the US2-3 convergence leg: the MAX
    // watermark (read by at least one) of the other active members read while the user was
    // offline must reach the open group window (MessengerPage feeds
    // the update straight into applySyncPage), and the 004 peer
    // projections normalize to "absent" for a group delta.
    expect(received).toHaveLength(1)
    expect(received[0]?.othersReadUpToSeq).toBe(12)
    expect(received[0]?.peer).toBeUndefined()
    expect(received[0]?.peerReadUpToSeq).toBeUndefined()
    expect(received[0]?.blockedByMe).toBeUndefined()
    expect(received[0]?.unreadCount).toBe(2)
  })

  it('keeps the direct delta watermark on peerReadUpToSeq and othersReadUpToSeq absent', async () => {
    mockedSync
      .mockResolvedValueOnce(
        syncResponse([delta({ chatId: CHAT_A, peerReadUpToSeq: 7, messages: [], lastSeq: 7 })]),
      )
      .mockResolvedValue(syncResponse([]))
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
    expect(received[0]?.peerReadUpToSeq).toBe(7)
    expect(received[0]?.othersReadUpToSeq).toBeUndefined()
  })
})

describe('useSync — flushPendingReads pre-step (§3.1/§6, T034)', () => {
  it('flushes every pending offline read via №17 BEFORE the №26 catch-up and confirms on 204', async () => {
    recordPendingRead(USER, CHAT_A, 12)
    recordPendingRead(USER, CHAT_B, 30)
    mockedSync.mockResolvedValue(syncResponse([]))
    const result = mountSync()

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedMarkChatRead).toHaveBeenCalledTimes(2)
    expect(mockedMarkChatRead).toHaveBeenCalledWith(CHAT_A, 12)
    expect(mockedMarkChatRead).toHaveBeenCalledWith(CHAT_B, 30)
    // the flush precedes the catch-up: every №17 lands before the first №26
    const firstRead = mockedMarkChatRead.mock.invocationCallOrder[0]
    const firstSync = mockedSync.mock.invocationCallOrder[0]
    expect(firstRead).toBeDefined()
    expect(firstSync).toBeDefined()
    expect(firstRead!).toBeLessThan(firstSync!)
    expect(readPendingReads(USER)).toEqual({})
  })

  it('a failed №17 leaves the entry pending and never fails the catch-up cycle', async () => {
    recordPendingRead(USER, CHAT_A, 12)
    mockedMarkChatRead.mockRejectedValueOnce(new Error('timeout'))
    mockedSync.mockResolvedValueOnce(
      syncResponse([delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 2), lastSeq: 2 })]),
    )
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(result.current.error).toBeNull()
    expect(views.seqs(CHAT_A)).toEqual([1, 2])
    expect(getPendingRead(USER, CHAT_A)).toBe(12)

    // the next (re)open replays the same watermark — №17's server-side
    // GREATEST makes the repeat safe and the 204 clears it
    await act(async () => {
      await result.current.syncNow()
    })
    expect(mockedMarkChatRead).toHaveBeenCalledWith(CHAT_A, 12)
    expect(getPendingRead(USER, CHAT_A)).toBe(0)
  })

  it('a newer offline read recorded while the flush №17 is in flight stays pending', async () => {
    recordPendingRead(USER, CHAT_A, 5)
    let resolveRead!: () => void
    mockedMarkChatRead.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        resolveRead = resolve
      }),
    )
    mockedSync.mockResolvedValue(syncResponse([]))
    const result = mountSync()

    let run!: Promise<void>
    act(() => {
      run = result.current.syncNow()
    })
    // the user keeps reading while the №17 for 5 is in flight
    recordPendingRead(USER, CHAT_A, 9)
    await act(async () => {
      resolveRead()
      await run
    })

    expect(mockedMarkChatRead).toHaveBeenCalledWith(CHAT_A, 5)
    expect(getPendingRead(USER, CHAT_A)).toBe(9)
  })

  it('nothing pending — the cycle starts with №26 directly', async () => {
    mockedSync.mockResolvedValue(syncResponse([]))
    const result = mountSync()

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedMarkChatRead).not.toHaveBeenCalled()
    expect(mockedSync).toHaveBeenCalledTimes(1)
  })
})

/**
 * T052 secondary window (cursor ahead without a delta): the persisted
 * cursor (cursors.ts) may legitimately run AHEAD of the server
 * delivery position — the previous session's debounce ack was lost
 * (the batcher is in-memory, the cursor is the persisted echo), so №26
 * takes the effective cursor max(client, server), returns NO delta at
 * all and nothing in the old cycle moved `delivered_up_to_seq`: the
 * server-unread counter of №12 stayed 0 and the entry badge had no
 * re-anchor until a live frame arrived. The cycle START now re-feeds
 * every persisted cursor tail through the shared batcher — №25 is
 * idempotent and monotone (server GREATEST), so the end-of-cycle
 * flush closes the gap and the consumer's after-ack №12 re-anchor
 * counts the tail.
 */
describe('useSync — cursor-tail top-up (T052, вторичное окно)', () => {
  it('acks the persisted cursor tail through the batcher even when №26 returns no deltas', async () => {
    advanceCursor(USER, CHAT_A, 7)
    mockedSync.mockResolvedValue(syncResponse([]))
    const result = mountSync()

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedSync).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 7 }], 20, 50)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_A, upToSeq: 7 }])
    expect(getCursor(USER, CHAT_A)).toBe(7)
  })

  it('merges the top-up with the applied pages of the same cycle into one №25 batch (per-chat max)', async () => {
    advanceCursor(USER, CHAT_A, 7)
    mockedSync.mockResolvedValueOnce(
      syncResponse([delta({ chatId: CHAT_B, messages: messages(CHAT_B, 1, 2), lastSeq: 2 })]),
    )
    const result = mountSync()

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([
      { chatId: CHAT_A, upToSeq: 7 },
      { chatId: CHAT_B, upToSeq: 2 },
    ])
  })

  it('a dropped (§5.5) chat tail is suppressed by the batcher itself — the top-up never poisons №25', async () => {
    advanceCursor(USER, CHAT_A, 7)
    getAckBatcher(USER).drop(CHAT_A)
    mockedSync.mockResolvedValue(syncResponse([]))
    const result = mountSync()

    await act(async () => {
      await result.current.syncNow()
    })

    expect(mockedDeliveryAck).not.toHaveBeenCalled()
  })

  /**
   * T088: drop() of the per-account batcher also FORGETS the persisted
   * cursor, so a later cycle's T052 re-feed no longer resurrects the
   * dead chatId — neither into the №26 body nor into №25. Without the
   * forgetting, a hard-deleted group (№30) re-entered the re-feed from
   * localStorage every new session and its 404 chat_not_found poisoned
   * the whole №25 batch (all-or-refusal) forever.
   */
  it('T088: after drop() the next cycle neither syncs nor re-feeds the dead chatId', async () => {
    advanceCursor(USER, CHAT_A, 7)
    advanceCursor(USER, CHAT_B, 9)
    getAckBatcher(USER).drop(CHAT_A)
    mockedSync.mockResolvedValue(syncResponse([]))
    const result = mountSync()

    await act(async () => {
      await result.current.syncNow()
    })

    // the cursor map no longer carries CHAT_A: №26 asks from B only…
    expect(mockedSync).toHaveBeenCalledWith([{ chatId: CHAT_B, upToSeq: 9 }], 20, 50)
    // …and the T052 re-feed batch carries B only — A never reaches №25
    expect(mockedDeliveryAck).toHaveBeenCalledTimes(1)
    expect(mockedDeliveryAck).toHaveBeenCalledWith([{ chatId: CHAT_B, upToSeq: 9 }])
    expect(getCursor(USER, CHAT_A)).toBe(0)
  })
})

/**
 * T093 (009, unread badge of the offline recipient): a TRANSIENT №25
 * failure of the end-of-cycle flush used to be swallowed — the cycle
 * counted as a success, the page's falling-edge №12 re-anchor
 * disarmed the unconfirmedTails merge protection and the pre-ack
 * snapshot (0) erased the badge, while nobody re-asked №12 after the
 * batcher's retry finally succeeded. The flush failure now FAILS the
 * cycle: `error` surfaces, the page's re-anchor is skipped by its
 * syncError guard and the batcher's onRecovered re-runs the cycle
 * after the retry drain succeeds (the falling-edge re-anchor of THAT
 * cycle closes the badge gap without a reconnect).
 */
describe('useSync — transient №25 flush failure fails the cycle (T093)', () => {
  it('surfaces the error, keeps the applied deltas and the confirmed cursors in place', async () => {
    mockedSync.mockResolvedValueOnce(
      syncResponse([delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 3), lastSeq: 3 })]),
    )
    mockedDeliveryAck.mockRejectedValueOnce(new Error('offline'))
    const views = new DialogViews()
    const result = mountSync()
    subscribe(views, result)

    await act(async () => {
      await result.current.syncNow()
    })

    expect(result.current.error).toBeTruthy()
    expect(result.current.syncing).toBe(false)
    // pages applied before the flush keep their render + cursor (US1-4):
    // the entries stay pending in the batcher, nothing is rolled back
    expect(views.seqs(CHAT_A)).toEqual([1, 2, 3])
    expect(getCursor(USER, CHAT_A)).toBe(3)
  })
})

describe('useSync — batcher recovery re-runs the cycle (T093 onRecovered)', () => {
  it('a successful retry after a transient №25 failure triggers exactly one more cycle; no loop afterwards', async () => {
    mockedSync
      .mockResolvedValueOnce(
        syncResponse([delta({ chatId: CHAT_A, messages: messages(CHAT_A, 1, 2), lastSeq: 2 })]),
      )
      .mockResolvedValue(syncResponse([]))
    mockedDeliveryAck.mockRejectedValueOnce(new Error('offline'))
    const result = mountSync()

    await act(async () => {
      await result.current.syncNow()
    })
    expect(result.current.error).toBeTruthy()
    expect(mockedSync).toHaveBeenCalledTimes(1)

    // the T088 backoff retry (1 s) succeeds → the batcher notes the
    // recovery → the cycle re-runs and completes AFTER the ack
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ACK_DEBOUNCE_MS)
    })
    await settle()
    expect(mockedSync).toHaveBeenCalledTimes(2)
    expect(result.current.error).toBeNull()
    expect(result.current.syncing).toBe(false)

    // no further recoveries: a quiet drain raises no event and the
    // cycle count stays put (no refetch ⇄ sync loop)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ACK_RETRY_MAX_MS)
    })
    await settle()
    expect(mockedSync).toHaveBeenCalledTimes(2)
  })
})
