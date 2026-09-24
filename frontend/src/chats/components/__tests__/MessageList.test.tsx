import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { useEffect, useRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getChat, listMessages, markChatRead } from '../../../api/chats'
import type { ChatView, Message, MessagePage } from '../../../api/chats'
import { useChatMessages } from '../../hooks/useChatMessages'
import type { SyncPageUpdate } from '../../hooks/useChatMessages'
import { MessageList } from '../MessageList'

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

const ME = '11111111-1111-1111-1111-111111111111'
const PEER = '22222222-2222-2222-2222-222222222222'

function message(overrides: Partial<Message> = {}): Message {
  return {
    id: 'm-1',
    chatId: 'chat-1',
    senderId: ME,
    text: 'Привет',
    seq: 1,
    createdAt: '2026-09-20T12:00:00.123Z',
    ...overrides,
  }
}

function renderedTexts(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('.message')).map(
    (item) => item.querySelector('.message-text')?.textContent ?? '',
  )
}

/** Per-message status mark (US4): `null` — no mark (incoming messages). */
function statusTexts(container: HTMLElement): Array<string | null> {
  return Array.from(container.querySelectorAll('.message')).map(
    (item) => item.querySelector('.message-status')?.textContent ?? null,
  )
}

function chatView(overrides: Partial<ChatView> = {}): ChatView {
  return {
    chatId: 'chat-1',
    peer: {
      id: PEER,
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

function dialogMessage(id: string, seq: number, senderId: string, text = `text-${id}`): Message {
  return {
    id,
    chatId: 'chat-1',
    senderId,
    text,
    seq,
    createdAt: '2026-09-20T12:00:00.000Z',
  }
}

function dialogPage(messages: Message[], nextBefore?: number): MessagePage {
  return nextBefore === undefined ? { messages } : { messages, nextBefore }
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

function emitChatRead(stream: MockStream, chatId: string, readUpToSeq: number): void {
  act(() => {
    stream.emit('chat.read', JSON.stringify({ chatId, readUpToSeq, byUserId: PEER }))
  })
}

function emitIncoming(stream: MockStream, id: string, seq: number): void {
  act(() => {
    stream.emit(
      'message.created',
      JSON.stringify({ chatId: 'chat-1', message: dialogMessage(id, seq, PEER) }),
    )
  })
}

/** Wires MessageList to the real hook exactly like MessengerPage (T044). */
function DialogWindow({ chatId, currentUserId }: { chatId: string; currentUserId: string }) {
  const { messages, peerReadUpToSeq } = useChatMessages(chatId)
  return (
    <MessageList
      messages={messages}
      currentUserId={currentUserId}
      peerReadUpToSeq={peerReadUpToSeq}
    />
  )
}

beforeEach(() => {
  mockedGetChat.mockResolvedValue(chatView())
  mockedMarkChatRead.mockResolvedValue(undefined)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('MessageList delivery statuses', () => {
  it('renders outgoing server messages as delivered with a single check mark', () => {
    const { container } = render(
      <MessageList messages={[message({ senderId: ME })]} currentUserId={ME} />,
    )

    expect(screen.getByText('Привет')).toBeVisible()
    expect(screen.getByText('доставлено ✓')).toBeVisible()
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(1)
  })

  it('renders incoming messages without any status marks', () => {
    const { container } = render(
      <MessageList messages={[message({ senderId: PEER, text: 'Ответ' })]} currentUserId={ME} />,
    )

    expect(screen.getByText('Ответ')).toBeVisible()
    expect(screen.queryByText(/доставлено/)).toBeNull()
    expect(screen.queryByText(/отправляется/)).toBeNull()
    expect(container.querySelector('.message-status')).toBeNull()
    expect(container.querySelectorAll('.message.incoming')).toHaveLength(1)
  })

  it('renders pending optimistic entries as sending', () => {
    const { container } = render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-1', text: 'Ещё летит' }]}
      />,
    )

    expect(screen.getByText('Ещё летит')).toBeVisible()
    expect(screen.getByText('отправляется')).toBeVisible()
    expect(screen.queryByText(/доставлено/)).toBeNull()
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(1)
  })
})

describe('MessageList idempotent render', () => {
  it('prefers the server copy when the optimistic entry is already acknowledged', () => {
    const { container } = render(
      <MessageList
        messages={[message({ id: 'cm-1', senderId: ME, text: 'Подтверждено' })]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-1', text: 'Подтверждено' }]}
      />,
    )

    expect(container.querySelectorAll('.message')).toHaveLength(1)
    expect(screen.getByText('Подтверждено')).toBeVisible()
    expect(screen.getByText('доставлено ✓')).toBeVisible()
    expect(screen.queryByText('отправляется')).toBeNull()
  })

  it('renders server messages and pending entries together in order', () => {
    const { container } = render(
      <MessageList
        messages={[
          message({ id: 'm-1', senderId: PEER, text: 'Раз', seq: 1 }),
          message({ id: 'm-2', senderId: ME, text: 'Два', seq: 2 }),
        ]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-3', text: 'Три' }]}
      />,
    )

    expect(renderedTexts(container)).toEqual(['Раз', 'Два', 'Три'])
    expect(container.querySelectorAll('.message.incoming')).toHaveLength(1)
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(2)
  })

  it('shows the empty state when there is nothing to render', () => {
    render(<MessageList messages={[]} currentUserId={ME} />)

    expect(screen.getByText('Сообщений пока нет')).toBeVisible()
  })
})

describe('MessageList read status by watermark (US4, T045)', () => {
  it('renders ✓✓ for outgoing messages at or below the watermark, ✓ above it and nothing on incoming', () => {
    const { container } = render(
      <MessageList
        messages={[
          dialogMessage('in-1', 1, PEER, 'Вопрос'),
          dialogMessage('out-1', 2, ME, 'Ответ один'),
          dialogMessage('out-2', 3, ME, 'Ответ два'),
        ]}
        currentUserId={ME}
        peerReadUpToSeq={2}
      />,
    )

    // The boundary is inclusive: seq ≤ peerReadUpToSeq counts as read.
    expect(statusTexts(container)).toEqual([null, 'прочитано ✓✓', 'доставлено ✓'])
    expect(container.querySelectorAll('.message-status-read')).toHaveLength(1)
  })

  it('renders everything as delivered when no watermark is given (default 0)', () => {
    const { container } = render(
      <MessageList
        messages={[dialogMessage('out-1', 2, ME), dialogMessage('out-2', 3, ME)]}
        currentUserId={ME}
      />,
    )

    expect(statusTexts(container)).toEqual(['доставлено ✓', 'доставлено ✓'])
  })

  it('flips ✓ to ✓✓ when the watermark advances and covers more outgoing messages', () => {
    const messages = [dialogMessage('out-1', 2, ME), dialogMessage('out-2', 3, ME)]
    const { container, rerender } = render(
      <MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={2} />,
    )
    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'доставлено ✓'])

    rerender(<MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={3} />)

    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])
  })
})

describe('MessageList read status via chat.read (US4, T045)', () => {
  it('flips ✓ to ✓✓ in realtime on a chat.read frame of the open chat', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      dialogPage([dialogMessage('in-1', 1, PEER), dialogMessage('out-1', 2, ME)]),
    )

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)

    await waitFor(() => {
      expect(statusTexts(container)).toEqual([null, 'доставлено ✓'])
    })

    emitChatRead(stream, 'chat-1', 2)

    await waitFor(() => {
      expect(statusTexts(container)).toEqual([null, 'прочитано ✓✓'])
    })
  })

  it('seeds ✓✓ from the ChatView watermark and keeps it on a stale smaller frame (US4-5)', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      dialogPage([dialogMessage('out-1', 2, ME), dialogMessage('out-2', 3, ME)]),
    )
    mockedGetChat.mockResolvedValueOnce(chatView({ peerReadUpToSeq: 3 }))

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])
    })

    emitChatRead(stream, 'chat-1', 2)

    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])
  })

  it('ignores chat.read frames of other chats', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(dialogPage([dialogMessage('out-1', 2, ME)]))

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['доставлено ✓'])
    })

    emitChatRead(stream, 'chat-2', 2)

    expect(statusTexts(container)).toEqual(['доставлено ✓'])
  })
})

describe('MessageList read status via sync deltas (feature 005, T036, US3-8)', () => {
  /**
   * Wires MessageList to the real hook and replays applied catch-up
   * pages exactly like MessengerPage does (T036): every new `update`
   * object is one §3.1 delta / №15 page of the catch-up loop.
   */
  function SyncDialogWindow({
    chatId,
    currentUserId,
    update,
  }: {
    chatId: string
    currentUserId: string
    update: SyncPageUpdate | null
  }) {
    const { messages, peerReadUpToSeq, applySyncPage } = useChatMessages(chatId)
    const appliedRef = useRef<SyncPageUpdate | null>(null)
    useEffect(() => {
      if (update !== null && update !== appliedRef.current) {
        appliedRef.current = update
        applySyncPage(update)
      }
    }, [update, applySyncPage])
    return (
      <MessageList
        messages={messages}
        currentUserId={currentUserId}
        peerReadUpToSeq={peerReadUpToSeq}
      />
    )
  }

  it('actualizes ✓→✓✓ of own messages from a reconnect delta without a page reload', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(
      dialogPage([
        dialogMessage('in-1', 1, PEER),
        dialogMessage('out-1', 2, ME),
        dialogMessage('out-2', 3, ME),
      ]),
    )
    const { container, rerender } = render(
      <SyncDialogWindow chatId="chat-1" currentUserId={ME} update={null} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual([null, 'доставлено ✓', 'доставлено ✓'])
    })

    // The peer read up to seq 2 while the user was offline; the §3.1
    // catch-up delta carries the fresh watermark — the statuses of
    // already rendered messages flip in place (US3-8).
    rerender(
      <SyncDialogWindow
        chatId="chat-1"
        currentUserId={ME}
        update={{ chatId: 'chat-1', messages: [], peerReadUpToSeq: 2 }}
      />,
    )

    expect(statusTexts(container)).toEqual([null, 'прочитано ✓✓', 'доставлено ✓'])
  })

  it('applies each status event once: a repeated identical delta keeps the statuses stable (quickstart §3.2)', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(
      dialogPage([dialogMessage('out-1', 2, ME), dialogMessage('out-2', 3, ME)]),
    )
    const { container, rerender } = render(
      <SyncDialogWindow chatId="chat-1" currentUserId={ME} update={null} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['доставлено ✓', 'доставлено ✓'])
    })

    rerender(
      <SyncDialogWindow
        chatId="chat-1"
        currentUserId={ME}
        update={{ chatId: 'chat-1', messages: [], peerReadUpToSeq: 3 }}
      />,
    )
    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])

    // A repeated №26 with the same cursors redelivers the same
    // watermark — every message renders once, no doubled statuses.
    rerender(
      <SyncDialogWindow
        chatId="chat-1"
        currentUserId={ME}
        update={{ chatId: 'chat-1', messages: [], peerReadUpToSeq: 3 }}
      />,
    )

    expect(container.querySelectorAll('.message')).toHaveLength(2)
    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])
  })

  it('renders own delta messages that were read while offline straight as ✓✓ (FR-003 status catch-up)', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(dialogPage([dialogMessage('in-1', 1, PEER)]))
    const { container, rerender } = render(
      <SyncDialogWindow chatId="chat-1" currentUserId={ME} update={null} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual([null])
    })

    // Sent from another device while offline AND already read by the
    // peer: the message and its «прочитано» status arrive in the same
    // delta (актуальные «доставлено»/«прочитано», US3-8).
    rerender(
      <SyncDialogWindow
        chatId="chat-1"
        currentUserId={ME}
        update={{
          chatId: 'chat-1',
          messages: [dialogMessage('out-1', 2, ME)],
          peerReadUpToSeq: 2,
        }}
      />,
    )

    expect(renderedTexts(container)).toEqual(['text-in-1', 'text-out-1'])
    expect(statusTexts(container)).toEqual([null, 'прочитано ✓✓'])
  })
})

describe('read receipt throttle (US4, T044: ≤1 POST /read per 500 ms)', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('coalesces rapid displayed changes into one maximal read mark', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(dialogPage([dialogMessage('in-1', 10, PEER)]))

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)

    // The initial window is outside any throttle gap and flushes immediately.
    await waitFor(() => {
      expect(mockedMarkChatRead).toHaveBeenCalledTimes(1)
    })
    expect(mockedMarkChatRead).toHaveBeenNthCalledWith(1, 'chat-1', 10)

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    // Two realtime appends land inside the same 500 ms window…
    emitIncoming(stream, 'in-2', 20)
    emitIncoming(stream, 'in-3', 30)
    expect(renderedTexts(container)).toHaveLength(3)

    // …so no second request fires until the window elapses.
    expect(mockedMarkChatRead).toHaveBeenCalledTimes(1)

    act(() => {
      vi.advanceTimersByTime(500)
    })

    expect(mockedMarkChatRead).toHaveBeenCalledTimes(2)
    expect(mockedMarkChatRead).toHaveBeenLastCalledWith('chat-1', 30)
    // The intermediate watermark 20 never hits the wire on its own.
    expect(mockedMarkChatRead.mock.calls.map((call) => call[1])).toEqual([10, 30])
  })
})
