import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getChat, listMessages, markChatRead } from '../../../api/chats'
import type { ChatView, Message, MessagePage } from '../../../api/chats'
import { advanceCursor } from '../../../sync/cursors'
import { getPendingRead } from '../../../sync/pendingReads'
import { useChatMessages } from '../useChatMessages'

const sse = vi.hoisted(() => ({ streamUserEvents: vi.fn() }))

vi.mock('../../../api/sse', () => sse)

vi.mock('../../../api/chats', () => ({
  listMessages: vi.fn(),
  getChat: vi.fn(),
  markChatRead: vi.fn(),
}))

const mockedListMessages = vi.mocked(listMessages)
const mockedGetChat = vi.mocked(getChat)
const mockedMarkChatRead = vi.mocked(markChatRead)

const PEER_ID = '9a2c-9a2c-9a2c'

function chatView(overrides: Partial<ChatView> = {}): ChatView {
  return {
    chatId: 'chat-1',
    peer: {
      id: PEER_ID,
      username: 'peer',
      email: 'peer@example.com',
      status: 'active',
      createdAt: '2026-09-01T00:00:00.000Z',
    },
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
    ...overrides,
  }
}

interface MockStream {
  onOpen: (() => void) | undefined
  emit(eventType: string, data: string): void
}

function installStream(): MockStream {
  const listeners = new Map<string, (data: string) => void>()
  const mock: MockStream = {
    onOpen: undefined,
    emit(eventType, data) {
      listeners.get(eventType)?.(data)
    },
  }
  sse.streamUserEvents.mockImplementation((options?: { onOpen?: () => void }) => {
    mock.onOpen = options?.onOpen
    return {
      subscribe(eventType: string, listener: (data: string) => void) {
        listeners.set(eventType, listener)
        return () => {
          listeners.delete(eventType)
        }
      },
      close(): void {
        // not asserted here (covered by useRealtime tests)
      },
    }
  })
  return mock
}

function makeMessage(chatId: string, id: string, seq: number, text = `text-${id}`): Message {
  return {
    id,
    chatId,
    senderId: PEER_ID,
    text,
    seq,
    createdAt: `2026-09-20T12:00:${String(seq % 60).padStart(2, '0')}.000Z`,
  }
}

function page(messages: Message[], nextBefore?: number): MessagePage {
  return nextBefore === undefined ? { messages } : { messages, nextBefore }
}

function emitMessageCreated(stream: MockStream, message: Message): void {
  act(() => {
    stream.emit('message.created', JSON.stringify({ chatId: message.chatId, message }))
  })
}

const mounted: Array<{ unmount(): void }> = []

function mountChatMessages(initialChatId: string | null, userId: string | null = null) {
  const rendered = renderHook((chatId: string | null) => useChatMessages(chatId, userId), {
    initialProps: initialChatId,
  })
  mounted.push(rendered)
  return rendered
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedGetChat.mockResolvedValue(chatView())
  mockedMarkChatRead.mockResolvedValue(undefined)
  window.localStorage.clear()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.clearAllMocks()
})

describe('useChatMessages initial load', () => {
  it('loads the latest page and renders it in ascending seq order', async () => {
    installStream()
    const newest = makeMessage('chat-1', 'm-2', 20)
    const oldest = makeMessage('chat-1', 'm-1', 10)
    mockedListMessages.mockResolvedValueOnce(page([newest, oldest], 10))

    const { result } = mountChatMessages('chat-1')

    expect(result.current.status).toBe('loading')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.error).toBeNull()
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])
    expect(mockedListMessages).toHaveBeenCalledWith('chat-1')
  })

  it('exposes the error and keeps messages empty when the history request fails', async () => {
    installStream()
    const problem = { code: 'chat_not_found' }
    mockedListMessages.mockRejectedValueOnce(problem)

    const { result } = mountChatMessages('chat-1')

    await waitFor(() => {
      expect(result.current.status).toBe('error')
    })
    expect(result.current.error).toBe(problem)
    expect(result.current.messages).toEqual([])
  })

  it('does not fetch anything while no chat is open', async () => {
    installStream()

    const { result } = mountChatMessages(null)

    await waitFor(() => {
      expect(mockedListMessages).not.toHaveBeenCalled()
    })
    expect(result.current.status).toBe('ready')
    expect(result.current.messages).toEqual([])
  })
})

describe('useChatMessages realtime appends', () => {
  it('appends a message.created frame of the open chat in seq order', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      page([makeMessage('chat-1', 'm-2', 20), makeMessage('chat-1', 'm-1', 10)], 10),
    )
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'm-3', 30))

    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2', 'm-3'])
  })

  it('is idempotent for a duplicate frame: same id keeps state and reference', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'm-2', 20))
    const afterFirst = result.current.messages
    expect(afterFirst.map((m) => m.id)).toEqual(['m-1', 'm-2'])

    emitMessageCreated(stream, makeMessage('chat-1', 'm-2', 20))
    expect(result.current.messages).toBe(afterFirst)
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])
  })

  it('inserts an out-of-order frame at its seq position', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      page([makeMessage('chat-1', 'm-3', 30), makeMessage('chat-1', 'm-1', 10)], 10),
    )
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'm-2', 20))

    expect(result.current.messages.map((m) => m.seq)).toEqual([10, 20, 30])
  })

  it('ignores frames of other chats', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-2', 'other-1', 99))

    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1'])
  })
})

describe('useChatMessages outbox confirmation (T036, FR-012)', () => {
  it('merges the 201/200 server copy and converges with a racing SSE frame into one instance', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    // The outbox send is acknowledged (201/200): message.id = clientMessageId.
    act(() => {
      result.current.confirmMessage({ ...makeMessage('chat-1', 'cm-1', 20), senderId: 'me-1' })
    })
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'cm-1'])

    // The own-stream SSE frame for the same message races the acknowledgement —
    // exactly one instance survives (FR-004/FR-009).
    emitMessageCreated(stream, { ...makeMessage('chat-1', 'cm-1', 20), senderId: 'me-1' })
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'cm-1'])
  })

  it('appends an own outgoing message sent from another device via the own stream (US2-6)', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, { ...makeMessage('chat-1', 'other-device-1', 20), senderId: 'me-1' })

    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'other-device-1'])
  })
})

describe('useChatMessages convergence on SSE (re)connect (FR-009)', () => {
  it('refetches the latest page on onOpen and converges without duplicates or reordering', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      page([makeMessage('chat-1', 'm-2', 20), makeMessage('chat-1', 'm-1', 10)], 10),
    )
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])

    // The stream dropped and reconnected: the missed m-3 arrives via the refetch,
    // server copies of m-1/m-2 are fresh objects with the same content.
    mockedListMessages.mockResolvedValueOnce(
      page([
        makeMessage('chat-1', 'm-3', 30),
        makeMessage('chat-1', 'm-2', 20),
        makeMessage('chat-1', 'm-1', 10),
      ]),
    )
    act(() => {
      stream.onOpen?.()
    })

    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2', 'm-3'])
    })
    expect(result.current.status).toBe('ready')
  })

  it('keeps a single instance when the reconnection frame and the refetch deliver the same message', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    let resolveRefetch: (value: MessagePage) => void = () => {}
    mockedListMessages.mockReturnValueOnce(
      new Promise<MessagePage>((resolve) => {
        resolveRefetch = resolve
      }),
    )
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(2)
    })

    // The at-most-once channel may redeliver m-3 right after reconnect…
    emitMessageCreated(stream, makeMessage('chat-1', 'm-3', 30))
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-3'])

    // …and the convergence refetch returns the same message again —
    // exactly one instance, server `seq` order preserved (US2-5).
    act(() => {
      resolveRefetch(page([makeMessage('chat-1', 'm-3', 30), makeMessage('chat-1', 'm-1', 10)], 10))
    })

    await waitFor(() => {
      expect(result.current.messages.map((m) => m.seq)).toEqual([10, 30])
    })
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-3'])
    expect(result.current.status).toBe('ready')
  })

  it('converges silently: status stays ready and rendered messages keep order and identity while the refetch is in flight', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      page([makeMessage('chat-1', 'm-2', 20), makeMessage('chat-1', 'm-1', 10)], 10),
    )
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    const renderedBefore = [...result.current.messages]

    mockedListMessages.mockReturnValueOnce(new Promise<MessagePage>(() => {}))
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(2)
    })

    // No «loading» flash, no losses, no reordering, same object references —
    // statuses and order of already rendered messages survive the reconnect.
    expect(result.current.status).toBe('ready')
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])
    expect(result.current.messages[0]).toBe(renderedBefore[0])
    expect(result.current.messages[1]).toBe(renderedBefore[1])
  })

  it('refetches again on every subsequent reconnection', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValue(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(2)
    })
    act(() => {
      stream.onOpen?.()
    })

    await waitFor(() => {
      expect(mockedListMessages).toHaveBeenCalledTimes(3)
    })
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1'])
  })
})

describe('useChatMessages parallel queue and realtime sends after reconnect (quickstart §3.3.6, T029)', () => {
  it('interleaves outbox-confirmed and realtime messages by server seq — no losses, no duplicates', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)], 10))
    const { result } = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    // Bob is back online: his queued sends were accepted at seq 20/21
    // while Alice's realtime frames (seq 19/22) race into the same
    // dialog, and the own SSE stream redelivers Bob's confirmed
    // message. The dialog keeps the actual server acceptance order
    // (seq), every message exactly once (edge «одновременная
    // отправка», spec 005).
    act(() => {
      result.current.confirmMessage({ ...makeMessage('chat-1', 'bob-1', 20), senderId: 'me-1' })
    })
    emitMessageCreated(stream, makeMessage('chat-1', 'alice-1', 19))
    act(() => {
      result.current.confirmMessage({ ...makeMessage('chat-1', 'bob-2', 21), senderId: 'me-1' })
    })
    emitMessageCreated(stream, makeMessage('chat-1', 'alice-2', 22))
    emitMessageCreated(stream, { ...makeMessage('chat-1', 'bob-1', 20), senderId: 'me-1' })

    expect(result.current.messages.map((message) => message.seq)).toEqual([10, 19, 20, 21, 22])
    expect(result.current.messages.map((message) => message.id)).toEqual([
      'm-1',
      'alice-1',
      'bob-1',
      'bob-2',
      'alice-2',
    ])
  })
})

describe('useChatMessages chat switching', () => {
  it('resets and loads the newly opened chat', async () => {
    installStream()
    mockedListMessages
      .mockResolvedValueOnce(page([makeMessage('chat-1', 'a-1', 1)]))
      .mockResolvedValueOnce(page([makeMessage('chat-2', 'b-5', 50)]))
    const rendered = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['a-1'])
    })

    rendered.rerender('chat-2')

    await waitFor(() => {
      expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['b-5'])
    })
  })

  it('ignores a stale response of the previous chat that resolves after the switch', async () => {
    installStream()
    let resolveStale: (value: MessagePage) => void = () => {}
    const stalePromise = new Promise<MessagePage>((resolve) => {
      resolveStale = resolve
    })
    mockedListMessages
      .mockReturnValueOnce(stalePromise)
      .mockResolvedValueOnce(page([makeMessage('chat-2', 'b-5', 50)]))
    const rendered = mountChatMessages('chat-1')

    rendered.rerender('chat-2')
    await waitFor(() => {
      expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['b-5'])
    })

    act(() => {
      resolveStale(page([makeMessage('chat-1', 'a-1', 1)]))
    })

    expect(rendered.result.current.messages.map((m) => m.id)).toEqual(['b-5'])
  })
})

describe('useChatMessages ✓✓ watermark from sync deltas (feature 005, T036, US3-8)', () => {
  function own(id: string, seq: number): Message {
    return { ...makeMessage('chat-1', id, seq), senderId: 'me-1' }
  }

  async function mountReady() {
    mockedListMessages.mockResolvedValueOnce(page([own('m-1', 10), own('m-2', 20)]))
    const rendered = mountChatMessages('chat-1')
    await waitFor(() => {
      expect(rendered.result.current.status).toBe('ready')
    })
    return rendered
  }

  it('actualizes own message statuses after reconnect: the delta peerReadUpToSeq advances the watermark without any reload', async () => {
    installStream()
    const { result } = await mountReady()
    expect(result.current.peerReadUpToSeq).toBe(0)

    // The peer read up to seq 10 while the user was offline — the
    // §3.1 catch-up delta carries the fresh watermark (US3-8,
    // FR-003: statuses of own messages arrive with the catch-up).
    act(() => {
      result.current.applySyncPage({ chatId: 'chat-1', messages: [], peerReadUpToSeq: 10 })
    })

    expect(result.current.peerReadUpToSeq).toBe(10)
  })

  it('applies each status event once: a repeated identical delta is a no-op (quickstart §3.2)', async () => {
    installStream()
    const { result } = await mountReady()

    act(() => {
      result.current.applySyncPage({ chatId: 'chat-1', messages: [], peerReadUpToSeq: 10 })
    })
    const messagesAfterFirst = result.current.messages
    expect(result.current.peerReadUpToSeq).toBe(10)

    // A repeated №26 with the same cursors redelivers the same
    // watermark — nothing doubles, nothing re-renders.
    act(() => {
      result.current.applySyncPage({ chatId: 'chat-1', messages: [], peerReadUpToSeq: 10 })
    })

    expect(result.current.peerReadUpToSeq).toBe(10)
    expect(result.current.messages).toBe(messagesAfterFirst)
  })

  it('never regresses the watermark on a stale smaller delta (monotonic)', async () => {
    installStream()
    const { result } = await mountReady()

    act(() => {
      result.current.applySyncPage({ chatId: 'chat-1', messages: [], peerReadUpToSeq: 20 })
    })
    act(() => {
      result.current.applySyncPage({ chatId: 'chat-1', messages: [], peerReadUpToSeq: 10 })
    })

    expect(result.current.peerReadUpToSeq).toBe(20)
  })

  it('leaves the watermark untouched when the page carries none (№15 continuation pages)', async () => {
    installStream()
    const { result } = await mountReady()

    act(() => {
      result.current.applySyncPage({ chatId: 'chat-1', messages: [own('m-3', 30)] })
    })

    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2', 'm-3'])
    expect(result.current.peerReadUpToSeq).toBe(0)
  })

  it("ignores the watermark of other chats' pages", async () => {
    installStream()
    const { result } = await mountReady()

    act(() => {
      result.current.applySyncPage({ chatId: 'chat-2', messages: [], peerReadUpToSeq: 99 })
    })

    expect(result.current.peerReadUpToSeq).toBe(0)
    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2'])
  })

  it('merges own outgoing delta messages together with the watermark — offline-read ones land straight in ✓✓ scope', async () => {
    installStream()
    const { result } = await mountReady()

    // Sent from another device while offline AND already read by the
    // peer: the delta message and its «прочитано» status arrive in the
    // same page (актуальные «доставлено»/«прочитано», US3-8).
    act(() => {
      result.current.applySyncPage({
        chatId: 'chat-1',
        messages: [own('m-3', 30)],
        peerReadUpToSeq: 30,
      })
    })

    expect(result.current.messages.map((m) => m.id)).toEqual(['m-1', 'm-2', 'm-3'])
    expect(result.current.peerReadUpToSeq).toBe(30)
  })
})

describe('useChatMessages offline read watermark (feature 005, T034, sync-protocol.md §6)', () => {
  it('records the pending watermark BEFORE the №17 attempt and removes it only after the 204', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)]))
    let resolveRead!: () => void
    mockedMarkChatRead.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        resolveRead = resolve
      }),
    )
    const { result } = mountChatMessages('chat-1', 'user-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    // №17 is in flight — the watermark already sits in pendingReads
    // (§6: запись ДО попытки), so a lost request/response replays on reconnect
    await waitFor(() => {
      expect(mockedMarkChatRead).toHaveBeenCalledWith('chat-1', 10)
    })
    expect(getPendingRead('user-1', 'chat-1')).toBe(10)

    await act(async () => {
      resolveRead()
      await Promise.resolve()
    })
    await waitFor(() => {
      expect(getPendingRead('user-1', 'chat-1')).toBe(0)
    })
  })

  it('a failed №17 keeps the watermark pending for the reconnect flush', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)]))
    mockedMarkChatRead.mockRejectedValue(new Error('offline'))
    const { result } = mountChatMessages('chat-1', 'user-1')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    await waitFor(() => {
      expect(mockedMarkChatRead).toHaveBeenCalledWith('chat-1', 10)
    })
    expect(getPendingRead('user-1', 'chat-1')).toBe(10)
  })

  it('clamps the offline watermark to the local delivery cursor (US3-7)', async () => {
    installStream()
    advanceCursor('user-1', 'chat-1', 6)
    mockedListMessages.mockResolvedValueOnce(page([makeMessage('chat-1', 'm-1', 10)]))
    let resolveRead!: () => void
    mockedMarkChatRead.mockReturnValueOnce(
      new Promise<void>((resolve) => {
        resolveRead = resolve
      }),
    )
    mountChatMessages('chat-1', 'user-1')
    await waitFor(() => {
      expect(mockedMarkChatRead).toHaveBeenCalledWith('chat-1', 10)
    })

    // №17 carries the actually displayed seq (10), but the offline
    // watermark is bounded by the client's delivery position (6) —
    // offline reading applies only to previously synchronized messages
    expect(getPendingRead('user-1', 'chat-1')).toBe(6)

    await act(async () => {
      resolveRead()
      await Promise.resolve()
    })
    await waitFor(() => {
      expect(getPendingRead('user-1', 'chat-1')).toBe(0)
    })
  })
})
