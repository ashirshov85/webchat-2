import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useEffect, useRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getChat, listMessages, markChatRead } from '../../../api/chats'
import type { ChatView, Message, MessagePage } from '../../../api/chats'
import { formatDate, formatTime } from '../../../ui/time'
import { useChatMessages } from '../../hooks/useChatMessages'
import type { SyncPageUpdate } from '../../hooks/useChatMessages'
import type { OutboxRecord } from '../../outbox'
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

/**
 * Per-message delivery mark (feature 008, US1, T022/T023): a
 * server-confirmed outgoing message carries the engraved ✓/✓✓ tick
 * stamp — its SC-002 text rides the prototype `title`
 * («Доставлено»/«Прочитано», design-tokens §6); local outbox entries
 * keep the 005 text statuses inside `.message-status`; incoming
 * messages carry no mark at all (`null`).
 */
function statusTexts(container: HTMLElement): Array<string | null> {
  return Array.from(container.querySelectorAll('.message')).map((item) => {
    const tick = item.querySelector('.tick')
    if (tick !== null) {
      return tick.getAttribute('title')
    }
    return item.querySelector('.message-status')?.textContent ?? null
  })
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

/** Wires MessageList to the real hook exactly like MessengerPage (T044, T078/T085). */
function DialogWindow({ chatId, currentUserId }: { chatId: string; currentUserId: string }) {
  const { messages, peerReadUpToSeq, unreadFromSeq, hasOlder, loadingOlder, loadOlder } =
    useChatMessages(chatId)
  return (
    <MessageList
      messages={messages}
      currentUserId={currentUserId}
      peerReadUpToSeq={peerReadUpToSeq}
      unreadFromSeq={unreadFromSeq}
      hasOlder={hasOlder}
      loadingOlder={loadingOlder}
      onLoadOlder={loadOlder}
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
    // T022/T023: the ✓ stamp is the engraved tick — «Доставлено» rides
    // its title (SC-002), `.dlv` is the single-check variant.
    const tick = container.querySelector('.tick') as HTMLElement
    expect(tick).toHaveClass('dlv')
    expect(tick).toHaveAttribute('title', 'Доставлено')
    expect(container.querySelector('.tick.read')).toBeNull()
    // The row carries the prototype bubble hooks alongside the preserved
    // 004 test hooks (research §C, FR-034).
    expect(container.querySelector('.message.outgoing')).toHaveClass('msg', 'me')
    expect(container.querySelector('.msg.me .bubble .b-text')?.textContent).toBe('Привет')
    expect(container.querySelectorAll('.message.outgoing')).toHaveLength(1)
  })

  it('renders incoming messages without any status marks', () => {
    const { container } = render(
      <MessageList messages={[message({ senderId: PEER, text: 'Ответ' })]} currentUserId={ME} />,
    )

    expect(screen.getByText('Ответ')).toBeVisible()
    expect(screen.queryByText(/доставлено/)).toBeNull()
    expect(screen.queryByText(/отправляется/)).toBeNull()
    expect(container.querySelector('.tick')).toBeNull()
    expect(container.querySelector('.message-status')).toBeNull()
    expect(container.querySelector('.message.incoming')).toHaveClass('msg', 'them')
    expect(container.querySelector('.msg.them .bubble .b-text')?.textContent).toBe('Ответ')
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
    expect(container.querySelector('.tick.dlv')).toHaveAttribute('title', 'Доставлено')
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
    expect(statusTexts(container)).toEqual([null, 'Прочитано', 'Доставлено'])
    expect(container.querySelectorAll('.tick.read')).toHaveLength(1)
    expect(container.querySelectorAll('.tick.dlv')).toHaveLength(1)
  })

  it('renders everything as delivered when no watermark is given (default 0)', () => {
    const { container } = render(
      <MessageList
        messages={[dialogMessage('out-1', 2, ME), dialogMessage('out-2', 3, ME)]}
        currentUserId={ME}
      />,
    )

    expect(statusTexts(container)).toEqual(['Доставлено', 'Доставлено'])
  })

  it('flips ✓ to ✓✓ when the watermark advances and covers more outgoing messages', () => {
    const messages = [dialogMessage('out-1', 2, ME), dialogMessage('out-2', 3, ME)]
    const { container, rerender } = render(
      <MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={2} />,
    )
    expect(statusTexts(container)).toEqual(['Прочитано', 'Доставлено'])

    rerender(<MessageList messages={messages} currentUserId={ME} peerReadUpToSeq={3} />)

    expect(statusTexts(container)).toEqual(['Прочитано', 'Прочитано'])
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
      expect(statusTexts(container)).toEqual([null, 'Доставлено'])
    })

    emitChatRead(stream, 'chat-1', 2)

    await waitFor(() => {
      expect(statusTexts(container)).toEqual([null, 'Прочитано'])
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
      expect(statusTexts(container)).toEqual(['Прочитано', 'Прочитано'])
    })

    emitChatRead(stream, 'chat-1', 2)

    expect(statusTexts(container)).toEqual(['Прочитано', 'Прочитано'])
  })

  it('ignores chat.read frames of other chats', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(dialogPage([dialogMessage('out-1', 2, ME)]))

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['Доставлено'])
    })

    emitChatRead(stream, 'chat-2', 2)

    expect(statusTexts(container)).toEqual(['Доставлено'])
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
      expect(statusTexts(container)).toEqual([null, 'Доставлено', 'Доставлено'])
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

    expect(statusTexts(container)).toEqual([null, 'Прочитано', 'Доставлено'])
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
      expect(statusTexts(container)).toEqual(['Доставлено', 'Доставлено'])
    })

    rerender(
      <SyncDialogWindow
        chatId="chat-1"
        currentUserId={ME}
        update={{ chatId: 'chat-1', messages: [], peerReadUpToSeq: 3 }}
      />,
    )
    expect(statusTexts(container)).toEqual(['Прочитано', 'Прочитано'])

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
    expect(statusTexts(container)).toEqual(['Прочитано', 'Прочитано'])
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
    expect(statusTexts(container)).toEqual([null, 'Прочитано'])
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

describe('MessageList ЧЧ:ММ footers and date dividers (FR-019, T026 baseline parity)', () => {
  // T026: the T016(а) feed baselines carry the prototype's `.b-time`
  // («18:41» before the outgoing tick) and the `.date-divider` between
  // calendar days — SC-001 at the US1 checkpoint needs both rendered,
  // so the feed-scoped half of T044 rides with this task (its full
  // watermark/UX polish stays US3).

  /** Local-day instants — robust under any runner timezone. */
  function localIso(year: number, month: number, day: number, hh: number, mm: number): string {
    return new Date(year, month - 1, day, hh, mm).toISOString()
  }

  it('renders the ЧЧ:ММ of every bubble: bare time on incoming, time before the tick on outgoing', () => {
    const incomingAt = localIso(2026, 9, 20, 18, 41)
    const outgoingAt = localIso(2026, 9, 20, 18, 43)
    const { container } = render(
      <MessageList
        messages={[
          message({ id: 'in-1', senderId: PEER, text: 'Вопрос', seq: 1, createdAt: incomingAt }),
          message({ id: 'out-1', senderId: ME, text: 'Ответ', seq: 2, createdAt: outgoingAt }),
        ]}
        currentUserId={ME}
      />,
    )

    const footers = Array.from(container.querySelectorAll('.message .b-time'))
    expect(footers).toHaveLength(2)
    expect(footers[0]?.textContent).toBe(formatTime(incomingAt))
    expect(footers[0]?.querySelector('.tick')).toBeNull()
    expect(footers[1]?.textContent).toContain(formatTime(outgoingAt))
    expect(footers[1]?.querySelector('.tick.dlv')).not.toBeNull()
  })

  it('puts exactly one divider per calendar-day run and labels it with the long date', () => {
    const { container } = render(
      <MessageList
        messages={[
          message({ id: 'a', seq: 1, createdAt: localIso(2026, 9, 19, 10, 0) }),
          message({ id: 'b', seq: 2, createdAt: localIso(2026, 9, 19, 11, 30) }),
          message({ id: 'c', seq: 3, createdAt: localIso(2026, 9, 20, 9, 0) }),
          message({ id: 'd', seq: 4, createdAt: localIso(2026, 9, 20, 9, 5) }),
        ]}
        currentUserId={ME}
      />,
    )

    const dividers = Array.from(container.querySelectorAll('.date-divider'))
    expect(dividers.map((divider) => divider.textContent)).toEqual([
      formatDate(localIso(2026, 9, 19, 10, 0)),
      formatDate(localIso(2026, 9, 20, 9, 0)),
    ])
    // The divider sits between the day runs, not inside them.
    expect(container.querySelectorAll('.message')).toHaveLength(4)
  })

  it('starts a new divider at midnight (23:59 → 00:01)', () => {
    const { container } = render(
      <MessageList
        messages={[
          message({ id: 'late', seq: 1, createdAt: localIso(2026, 9, 19, 23, 59) }),
          message({ id: 'early', seq: 2, createdAt: localIso(2026, 9, 20, 0, 1) }),
        ]}
        currentUserId={ME}
      />,
    )

    expect(container.querySelectorAll('.date-divider')).toHaveLength(2)
  })

  it('keeps a single divider at a same-day pagination junction', () => {
    // The hook merges an older same-day page above the loaded window —
    // the merged list must not grow a second divider of that day.
    const { container } = render(
      <MessageList
        messages={[
          message({ id: 'old-1', seq: 1, createdAt: localIso(2026, 9, 19, 9, 0) }),
          message({ id: 'old-2', seq: 2, createdAt: localIso(2026, 9, 19, 9, 2) }),
          message({ id: 'new-1', seq: 3, createdAt: localIso(2026, 9, 19, 10, 0) }),
        ]}
        currentUserId={ME}
      />,
    )

    expect(container.querySelectorAll('.date-divider')).toHaveLength(1)
  })

  it('hides the top divider while older history may exist above the rendered window', () => {
    const sameList = [
      message({ id: 'a', seq: 1, createdAt: localIso(2026, 9, 19, 10, 0) }),
      message({ id: 'b', seq: 2, createdAt: localIso(2026, 9, 20, 11, 0) }),
    ]

    const partial = render(<MessageList messages={sameList} currentUserId={ME} hasOlder />)
    // The oldest rendered row may not be the start of its day — the
    // divider appears only once the history is known complete.
    expect(partial.container.querySelectorAll('.date-divider')).toHaveLength(1)
    expect(partial.container.querySelector('.date-divider')?.textContent).toBe(
      formatDate(localIso(2026, 9, 20, 11, 0)),
    )

    cleanup()
    const complete = render(<MessageList messages={sameList} currentUserId={ME} hasOlder={false} />)
    expect(complete.container.querySelectorAll('.date-divider')).toHaveLength(2)
  })
})

describe('MessageList date dividers at pagination junctions (US3-AS3, T041, data-model 1.3)', () => {
  /**
   * T041: the T026 block above fixed the baseline-parity RENDER of the
   * `.date-divider`; this block pins the remaining data-model 1.3 rules
   * of the US3 acceptance — the junction between two PAGES is decided
   * by comparing the adjacent messages across it (the last row of the
   * prepended page vs the first message of the previously loaded
   * window): a day change inserts EXACTLY ONE divider there (never one
   * per page), a same-day prepend inserts none, and an empty feed
   * carries no dividers at all.
   */

  /** Local-day instants — robust under any runner timezone. */
  function localIso(year: number, month: number, day: number, hh: number, mm: number): string {
    return new Date(year, month - 1, day, hh, mm).toISOString()
  }

  function dividerLabels(container: HTMLElement): string[] {
    return Array.from(container.querySelectorAll('.date-divider')).map(
      (divider) => divider.textContent ?? '',
    )
  }

  /** Ordered feed sketch — pins the divider to the junction position. */
  function rowSketch(container: HTMLElement): string[] {
    return Array.from(container.querySelectorAll('.message-list > li')).map((row) =>
      row.classList.contains('date-divider') ? 'divider' : 'message',
    )
  }

  it('inserts exactly one divider at a cross-day junction, labeled by the newer page first message', () => {
    // The hook's `loadOlder` prepends the older page above the loaded
    // window and re-renders — exactly the MessengerPage wiring.
    const olderPage = [
      message({ id: 'a', seq: 1, createdAt: localIso(2026, 9, 19, 10, 0) }),
      message({ id: 'b', seq: 2, createdAt: localIso(2026, 9, 19, 10, 30) }),
    ]
    const loadedWindow = [
      message({ id: 'c', seq: 3, createdAt: localIso(2026, 9, 20, 9, 0) }),
      message({ id: 'd', seq: 4, createdAt: localIso(2026, 9, 20, 9, 5) }),
    ]
    const props = (messages: readonly Message[], hasOlder: boolean) => (
      <MessageList messages={messages} currentUserId={ME} hasOlder={hasOlder} />
    )

    // Latest page on screen, older history may exist — no dividers yet.
    const { container, rerender } = render(props(loadedWindow, true))
    expect(dividerLabels(container)).toEqual([])

    // The older page of 19 September lands above: the junction carries
    // EXACTLY ONE divider, labeled with the day of the first message of
    // the newer page (the data-model 1.3 comparison target) — a naive
    // per-page divider would render it twice.
    rerender(props([...olderPage, ...loadedWindow], true))
    expect(dividerLabels(container)).toEqual([formatDate(localIso(2026, 9, 20, 9, 0))])
    expect(rowSketch(container)).toEqual(['message', 'message', 'divider', 'message', 'message'])

    // The boundary answer exhausts the history: the prepended day gains
    // its own top divider — the junction one stays single.
    rerender(props([...olderPage, ...loadedWindow], false))
    expect(dividerLabels(container)).toEqual([
      formatDate(localIso(2026, 9, 19, 10, 0)),
      formatDate(localIso(2026, 9, 20, 9, 0)),
    ])
  })

  it('grows no divider when the prepended page is the same day', () => {
    const props = (messages: readonly Message[], hasOlder: boolean) => (
      <MessageList messages={messages} currentUserId={ME} hasOlder={hasOlder} />
    )
    const loadedWindow = [message({ id: 'new-1', seq: 3, createdAt: localIso(2026, 9, 19, 10, 0) })]
    const olderPage = [
      message({ id: 'old-1', seq: 1, createdAt: localIso(2026, 9, 19, 9, 0) }),
      message({ id: 'old-2', seq: 2, createdAt: localIso(2026, 9, 19, 9, 2) }),
    ]

    const { container, rerender } = render(props(loadedWindow, true))
    rerender(props([...olderPage, ...loadedWindow], true))
    // The junction sits inside one calendar day — the run never grows
    // a divider at the page boundary itself.
    expect(dividerLabels(container)).toEqual([])

    rerender(props([...olderPage, ...loadedWindow], false))
    expect(dividerLabels(container)).toEqual([formatDate(localIso(2026, 9, 19, 9, 0))])
  })

  it('renders no dividers on an empty feed, with or without local optimistic rows', () => {
    const empty = render(<MessageList messages={[]} currentUserId={ME} />)
    expect(empty.container.querySelector('.message-list')).toBeNull()
    expect(empty.container.querySelector('.date-divider')).toBeNull()
    cleanup()

    // Optimistic entries carry no server timestamp — a fresh chat's
    // local rows never sprout a divider (data-model 1.3: пустая лента).
    const localOnly = render(
      <MessageList
        messages={[]}
        currentUserId={ME}
        pending={[{ clientMessageId: 'cm-1', text: 'Первое сообщение' }]}
      />,
    )
    expect(localOnly.container.querySelectorAll('.message')).toHaveLength(1)
    expect(localOnly.container.querySelector('.date-divider')).toBeNull()
  })
})

describe('MessageList open seat: the row before the first unread incoming (bug 2/7/13, T078/T085/T091)', () => {
  /**
   * T091 (bug 13, уточнение T085/T078): the first render of an open
   * chat seats the feed so the BOTTOM edge of the FOLD row lands at
   * the BOTTOM edge of the viewport. The fold row is the row
   * immediately BEFORE the first unread INCOMING message (`senderId
   * ≠ me && seq > myReadUpToSeq` of the №13 answer latched at open —
   * `unreadFromSeq`): own outgoing rows are read by the author the
   * moment they leave, so a chat whose tail is own sends carries NO
   * unread incoming — the seat degenerates to the feed's very last
   * row (the open seats in the end and the own tail is in view —
   * the bug 13 fix). When the window STARTS with the unread run,
   * the fold row sits above it — the seat drives №14 `loadOlder`
   * pages until it enters the window; a failed page never retries
   * on its own. A wholly unread window (watermark 0) has no fold
   * row — the feed keeps its natural top position. The seat fires
   * ONCE per open (re-armed by the empty window of a chat switch),
   * waits for the №13 watermark, and the pagination anchor of
   * `loadOlder` prepends (anchorHeightRef, T053) stays intact.
   *
   * jsdom ships no layout and no `scrollIntoView` — the mock records
   * the calls so the tests pin WHICH row the browser would scroll to
   * and with which block.
   */

  let scrolled: Array<{ element: Element; block?: string }>

  beforeEach(() => {
    scrolled = []
    Element.prototype.scrollIntoView = function (
      this: Element,
      options?: boolean | ScrollIntoViewOptions,
    ): void {
      scrolled.push({
        element: this,
        block: typeof options === 'object' && options !== null ? options.block : undefined,
      })
    }
  })

  afterEach(() => {
    // jsdom declares no own scrollIntoView — the mock is all there ever was.
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView')
  })

  /**
   * seq 1–3 are read (watermark 3), seq 4 is an unread OUTGOING row,
   * seq 5–6 are the unread incoming ones: the fold row is seq 4 —
   * the row immediately BEFORE the first unread incoming (u-in-5),
   * an OWN row (T091: own sends are read by the author; the anchor
   * must not stop at the last READ row r-in-3, which used to leave
   * u-out-4 below the fold) and not the very bottom row either.
   */
  const UNREAD_WINDOW: Message[] = [
    dialogMessage('r-in-1', 1, PEER, 'прочитанное 1'),
    dialogMessage('r-out-2', 2, ME, 'прочитанное 2'),
    dialogMessage('r-in-3', 3, PEER, 'прочитанное 3'),
    dialogMessage('u-out-4', 4, ME, 'непрочитанный исходящий'),
    dialogMessage('u-in-5', 5, PEER, 'первое непрочитанное'),
    dialogMessage('u-in-6', 6, PEER, 'второе непрочитанное'),
  ]

  it('seats the open chat at the own row above the first unread incoming, not at the last read row', () => {
    const { container } = render(
      <MessageList messages={UNREAD_WINDOW} currentUserId={ME} unreadFromSeq={3} />,
    )

    // The fold row is marked for the scroll and stays in the DOM —
    // the unread run below it starts right under the fold, the read
    // history above stays reachable by scrolling up (bug 7). T091:
    // u-out-4 counts as read by the author, so IT — not r-in-3 — is
    // the row the unread incoming run u-in-5… starts below.
    const anchor = container.querySelector('[data-seat-anchor]')
    expect(anchor).not.toBeNull()
    expect(anchor?.textContent).toContain('непрочитанный исходящий')
    expect(container.querySelectorAll('[data-seat-anchor]')).toHaveLength(1)

    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element).toBe(anchor)
    expect(scrolled[0]?.block).toBe('end')
  })

  it('seats a window with no unread incoming at the end of the feed', () => {
    const { container } = render(
      <MessageList messages={UNREAD_WINDOW} currentUserId={ME} unreadFromSeq={6} />,
    )

    // No unread incoming exists (own sends are read by the author,
    // the incoming ones sit at/below the watermark) — the fold
    // degenerates to the feed's very last row: the open seats in the
    // end (T091).
    const anchor = container.querySelector('[data-seat-anchor]')
    expect(anchor?.textContent).toContain('второе непрочитанное')
    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element).toBe(anchor)
    expect(scrolled[0]?.block).toBe('end')
  })

  it('seats a chat whose tail is own sends at the very end of the feed (bug 13)', () => {
    // Watermark 1: the peer's seq 1 row is read, seq 2–3 are OWN
    // sends — read by the author the moment they left (T091). No
    // unread incoming exists, so the fold degenerates to the feed's
    // very last row: the open seats IN THE END and the own tail is
    // in view — the T085 anchor (the last READ row, seq 1) used to
    // leave the whole own tail below the fold (the bug 13 symptom).
    const OWN_TAIL: Message[] = [
      dialogMessage('t-in-1', 1, PEER, 'входящее прочитанное'),
      dialogMessage('t-out-2', 2, ME, 'своё первое'),
      dialogMessage('t-out-3', 3, ME, 'своё второе'),
    ]
    const { container } = render(
      <MessageList messages={OWN_TAIL} currentUserId={ME} unreadFromSeq={1} />,
    )

    const anchor = container.querySelector('[data-seat-anchor]')
    expect(anchor?.textContent).toContain('своё второе')
    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element).toBe(anchor)
    expect(scrolled[0]?.block).toBe('end')
  })

  it('seats an own send standing right above the first unread incoming (interleave)', () => {
    // Watermark 2: seq 1–2 are read (the seq 2 row is an OWN send),
    // seq 3 is the first unread incoming — the fold row is the OWN
    // seq 2 row directly above it, whatever the watermark says about
    // it (T091 interleave: the anchor is the row before the unread
    // incoming run, not the last read incoming one).
    const INTERLEAVE: Message[] = [
      dialogMessage('i-in-1', 1, PEER, 'прочитанное входящее'),
      dialogMessage('i-out-2', 2, ME, 'своё над непрочитанным'),
      dialogMessage('i-in-3', 3, PEER, 'непрочитанное входящее'),
    ]
    const { container } = render(
      <MessageList messages={INTERLEAVE} currentUserId={ME} unreadFromSeq={2} />,
    )

    const anchor = container.querySelector('[data-seat-anchor]')
    expect(anchor?.textContent).toContain('своё над непрочитанным')
    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element).toBe(anchor)
    expect(scrolled[0]?.block).toBe('end')
  })

  it('does not scroll an empty chat', () => {
    render(<MessageList messages={[]} currentUserId={ME} unreadFromSeq={0} />)

    expect(screen.getByText('Сообщений пока нет')).toBeVisible()
    expect(scrolled).toHaveLength(0)
  })

  it('waits for the open-time watermark: no scroll while №13 is still in flight', () => {
    const props = (unreadFromSeq?: number) => (
      <MessageList messages={UNREAD_WINDOW} currentUserId={ME} unreadFromSeq={unreadFromSeq} />
    )
    const { rerender, container } = render(props())

    // The №16 page is on screen but the watermark is unknown — no scroll.
    expect(container.querySelectorAll('.message')).toHaveLength(6)
    expect(scrolled).toHaveLength(0)

    // The №13 answer lands with the open-time watermark — the seat fires.
    rerender(props(3))
    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element.textContent).toContain('непрочитанный исходящий')
  })

  it('scrolls once per open: realtime appends after the open never re-scroll', () => {
    const props = (messages: readonly Message[]) => (
      <MessageList messages={messages} currentUserId={ME} unreadFromSeq={3} />
    )
    const { rerender } = render(props(UNREAD_WINDOW))
    expect(scrolled).toHaveLength(1)

    rerender(props([...UNREAD_WINDOW, dialogMessage('new-in-7', 7, PEER, 'новое входящее')]))
    expect(scrolled).toHaveLength(1)
  })

  it('re-arms on the chat switch: the next open seats at ITS fold', () => {
    const props = (messages: readonly Message[], unreadFromSeq: number) => (
      <MessageList messages={messages} currentUserId={ME} unreadFromSeq={unreadFromSeq} />
    )
    const { rerender } = render(props(UNREAD_WINDOW, 3))
    expect(scrolled).toHaveLength(1)

    // Chat switch: the hook resets the window to [] — the cycle re-arms.
    rerender(props([], 0))
    expect(scrolled).toHaveLength(1)

    // The next chat opens fully read — its seat is the very last row.
    rerender(
      props(
        [
          dialogMessage('n-in-1', 1, PEER, 'новый чат первое'),
          dialogMessage('n-in-2', 2, ME, 'новый чат второе'),
        ],
        2,
      ),
    )
    expect(scrolled).toHaveLength(2)
    expect(scrolled[1]?.element.textContent).toContain('новый чат второе')
    expect(scrolled[1]?.block).toBe('end')
  })

  /**
   * The №14 catch-up of the seat (T085): the fold row sits above the
   * loaded window — pages load until it enters, THEN the seat fires.
   * T091 parity: every catch-up window STARTS with an unread
   * INCOMING row (index 0 → PEER) — with an own-row start the new
   * anchor (the row before the first unread incoming) would sit
   * INSIDE the window and the seat would fire without any №14 page.
   */
  const HIGH_WINDOW: Message[] = Array.from({ length: 20 }, (_, index) =>
    dialogMessage(`w-${index + 41}`, index + 41, index % 2 === 0 ? PEER : ME, `окно ${index + 41}`),
  )
  const OLDER_PAGE: Message[] = Array.from({ length: 20 }, (_, index) =>
    dialogMessage(
      `o-${index + 21}`,
      index + 21,
      index % 2 === 0 ? PEER : ME,
      `старая ${index + 21}`,
    ),
  )

  it('loads №14 pages until the read row enters the window, then seats at it', () => {
    const onLoadOlder = vi.fn()
    const props = (messages: readonly Message[], loadingOlder: boolean) => (
      <MessageList
        messages={messages}
        currentUserId={ME}
        unreadFromSeq={40}
        hasOlder
        loadingOlder={loadingOlder}
        onLoadOlder={onLoadOlder}
      />
    )

    // The window (41–60) STARTS with the unread incoming run (seq
    // 41, PEER) — the fold row right above it is outside the window,
    // the seat asks for the older page instead of firing.
    const { container, rerender } = render(props(HIGH_WINDOW, false))
    expect(onLoadOlder).toHaveBeenCalledTimes(1)
    expect(scrolled).toHaveLength(0)
    expect(container.querySelector('[data-seat-anchor]')).toBeNull()

    // A page in flight — no second request while it loads.
    rerender(props(HIGH_WINDOW, true))
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    // The page (21–40) prepends — the fold row (seq 40, the row
    // right above the unread run start seq 41) is in the window now,
    // the seat fires at its bottom edge.
    rerender(props([...OLDER_PAGE, ...HIGH_WINDOW], false))
    expect(onLoadOlder).toHaveBeenCalledTimes(1)
    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element.textContent).toContain('старая 40')
    expect(scrolled[0]?.block).toBe('end')

    // The seat is spent — later appends never re-scroll.
    rerender(
      props([...OLDER_PAGE, ...HIGH_WINDOW, dialogMessage('new-61', 61, PEER, 'новое')], false),
    )
    expect(scrolled).toHaveLength(1)
  })

  it('does not retry a failed seat page on its own (no request storm)', () => {
    const onLoadOlder = vi.fn()
    const props = (loadingOlder: boolean) => (
      <MessageList
        messages={HIGH_WINDOW}
        currentUserId={ME}
        unreadFromSeq={40}
        hasOlder
        loadingOlder={loadingOlder}
        onLoadOlder={onLoadOlder}
      />
    )
    const { rerender } = render(props(false))
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    // The request fails: the window and the cursor stay as they were
    // — the seat does not hammer №14 in a loop; the user's own scroll
    // still retries through the standard handleScroll path.
    rerender(props(true))
    rerender(props(false))
    expect(onLoadOlder).toHaveBeenCalledTimes(1)
    expect(scrolled).toHaveLength(0)
  })

  it('keeps a wholly unread window at its natural top: no fold row, no №14 storm', () => {
    const onLoadOlder = vi.fn()
    const { container } = render(
      <MessageList
        messages={UNREAD_WINDOW}
        currentUserId={ME}
        unreadFromSeq={0}
        hasOlder
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />,
    )

    // Watermark 0 — nothing read exists, pagination can never find a
    // fold row: the feed keeps its natural top position and №14 is
    // never asked (the whole feed IS the unread run).
    expect(container.querySelector('[data-seat-anchor]')).toBeNull()
    expect(onLoadOlder).not.toHaveBeenCalled()
    expect(scrolled).toHaveLength(0)
  })

  it('keeps the loadOlder viewport anchor intact after the seat', () => {
    const onLoadOlder = vi.fn()
    const props = (messages: readonly Message[]) => (
      <MessageList
        messages={messages}
        currentUserId={ME}
        unreadFromSeq={40}
        hasOlder
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />
    )
    // Open → the №14 catch-up loads 21–40 → the seat fires at seq 40.
    const { container, rerender } = render(props(HIGH_WINDOW))
    expect(onLoadOlder).toHaveBeenCalledTimes(1)
    rerender(props([...OLDER_PAGE, ...HIGH_WINDOW]))
    expect(scrolled).toHaveLength(1)

    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics = { scrollTop: 0, scrollHeight: 600 }
    Object.defineProperty(list, 'scrollTop', {
      configurable: true,
      get: () => metrics.scrollTop,
      set: (value: number) => {
        metrics.scrollTop = value
      },
    })
    Object.defineProperty(list, 'scrollHeight', {
      configurable: true,
      get: () => metrics.scrollHeight,
    })

    // The user scrolls to the top — the standard scroll handler asks
    // for the next older page with the anchor captured (T053).
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(2)

    // The older page (seq 1–20) prepends and grows the content above
    // by 400px — the viewport stays pinned to the same rows (T053
    // anchor), and the spent seat does not fire again.
    metrics.scrollHeight = 1000
    const oldestPage = Array.from({ length: 20 }, (_, index) =>
      dialogMessage(
        `p-${index + 1}`,
        index + 1,
        index % 2 === 0 ? ME : PEER,
        `древняя ${index + 1}`,
      ),
    )
    rerender(props([...oldestPage, ...OLDER_PAGE, ...HIGH_WINDOW]))

    expect(metrics.scrollTop).toBe(400)
    expect(scrolled).toHaveLength(1)
  })

  it('drives the seat from the №13 watermark through the hook (MessengerPage wiring)', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(dialogPage(UNREAD_WINDOW))
    mockedGetChat.mockResolvedValueOnce(chatView({ myReadUpToSeq: 3 }))

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)

    await waitFor(() => {
      expect(container.querySelectorAll('.message')).toHaveLength(6)
    })
    await waitFor(() => {
      expect(scrolled).toHaveLength(1)
    })
    expect(scrolled[0]?.element.textContent).toContain('непрочитанный исходящий')
    expect(scrolled[0]?.block).toBe('end')
  })

  it('drives the №14 catch-up through the live wiring until the read row arrives', async () => {
    installStream()
    // T091 parity: highestPage/middlePage START with an unread
    // INCOMING row (index 0 → PEER) — the fold row above the unread
    // run start stays outside the window until the read page lands.
    const highestPage = Array.from({ length: 10 }, (_, index) =>
      dialogMessage(
        `h-${index + 51}`,
        index + 51,
        index % 2 === 0 ? PEER : ME,
        `верх ${index + 51}`,
      ),
    )
    const middlePage = Array.from({ length: 10 }, (_, index) =>
      dialogMessage(
        `m-${index + 41}`,
        index + 41,
        index % 2 === 0 ? PEER : ME,
        `средняя ${index + 41}`,
      ),
    )
    const readPage = Array.from({ length: 10 }, (_, index) =>
      dialogMessage(
        `r-${index + 31}`,
        index + 31,
        index % 2 === 0 ? PEER : ME,
        `нижняя ${index + 31}`,
      ),
    )
    mockedListMessages
      .mockResolvedValueOnce(dialogPage(highestPage, 50))
      .mockResolvedValueOnce(dialogPage(middlePage, 40))
      .mockResolvedValueOnce(dialogPage(readPage))
    mockedGetChat.mockResolvedValueOnce(chatView({ myReadUpToSeq: 40 }))

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)

    // The №16 window is 51–60 and starts with the unread incoming
    // run (seq 51) — the fold row (right above the run, which itself
    // reaches down to seq 41) is two №14 pages above; the seat
    // paginates down to it and fires at its bottom.
    await waitFor(() => {
      expect(scrolled).toHaveLength(1)
    })
    expect(scrolled[0]?.element.textContent).toContain('нижняя 40')
    expect(scrolled[0]?.block).toBe('end')
    expect(container.querySelectorAll('.message')).toHaveLength(30)
    expect(mockedListMessages).toHaveBeenNthCalledWith(2, 'chat-1', { before: 50 })
    expect(mockedListMessages).toHaveBeenNthCalledWith(3, 'chat-1', { before: 40 })
  })
})

describe('MessageList scroll to the bottom on send and new incoming (bug 2, T079)', () => {
  /**
   * T079: a send always seats the feed at the bottom — the optimistic
   * outbox row AND the server ack that replaces it scroll even while
   * the user reads history. A new incoming `message.created` scrolls
   * ONLY when the user rides the bottom edge — reading history is
   * never yanked. Older-page prepends keep the T053 viewport anchor
   * untouched (the bottom row identity does not change). A fresh open
   * (mount or the empty window of a chat switch) only ARMS the
   * tracking: the open seat (T085) owns the open scroll, the first
   * paint of history never jumps.
   *
   * jsdom ships no layout: per-test metrics drive the scroll
   * container (`clientHeight` included — the bottom-edge latch needs
   * it), `fireEvent.scroll` plays the user's scroll, and the asserts
   * pin WHICH offset the effect writes. In real browsers the append
   * lands first and `scrollTop = scrollHeight` reaches the true
   * bottom; here the mocked `scrollHeight` stands in for it.
   */

  interface ScrollMetrics {
    scrollTop: number
    scrollHeight: number
    clientHeight: number
  }

  function installScrollMetrics(element: HTMLElement, metrics: ScrollMetrics): void {
    Object.defineProperty(element, 'scrollTop', {
      configurable: true,
      get: () => metrics.scrollTop,
      set: (value: number) => {
        metrics.scrollTop = value
      },
    })
    Object.defineProperty(element, 'scrollHeight', {
      configurable: true,
      get: () => metrics.scrollHeight,
    })
    Object.defineProperty(element, 'clientHeight', {
      configurable: true,
      get: () => metrics.clientHeight,
    })
  }

  const WINDOW: Message[] = [
    dialogMessage('w-in-1', 1, PEER, 'история 1'),
    dialogMessage('w-out-2', 2, ME, 'история 2'),
    dialogMessage('w-in-3', 3, PEER, 'история 3'),
  ]

  const SENDING: OutboxRecord = {
    clientMessageId: 'cm-send-1',
    chatId: 'chat-1',
    text: 'исходящее',
    state: 'sending',
  }

  /** 600-100-400 = 100px from the bottom — the user reads history. */
  const READING_HISTORY: ScrollMetrics = { scrollTop: 100, scrollHeight: 600, clientHeight: 400 }

  it('seats the feed at the bottom when the optimistic row appears, even while reading history', () => {
    const { container, rerender } = render(<MessageList messages={WINDOW} currentUserId={ME} />)
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics = { ...READING_HISTORY }
    installScrollMetrics(list, metrics)
    fireEvent.scroll(list)

    rerender(<MessageList messages={WINDOW} currentUserId={ME} outbox={[SENDING]} />)

    // Own send is unconditional — the fresh row must land in view.
    expect(metrics.scrollTop).toBe(600)
  })

  it('seats the feed at the bottom again when the server ack replaces the local row', () => {
    const props = (messages: readonly Message[], outbox?: readonly OutboxRecord[]) => (
      <MessageList messages={messages} currentUserId={ME} outbox={outbox} />
    )
    const { container, rerender } = render(props(WINDOW))
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics = { ...READING_HISTORY }
    installScrollMetrics(list, metrics)

    // The send scrolls (unconditional), then the user scrolls BACK UP
    // to read history before the acknowledgement lands.
    rerender(props(WINDOW, [SENDING]))
    expect(metrics.scrollTop).toBe(600)
    metrics.scrollTop = 100
    fireEvent.scroll(list)

    // The ack arrives: the local row is replaced by the server copy
    // (id = clientMessageId) — the confirmation stays in view.
    rerender(props([...WINDOW, dialogMessage('cm-send-1', 4, ME, 'исходящее')], []))
    expect(metrics.scrollTop).toBe(600)
  })

  it('follows a new incoming message while the user rides the bottom edge', () => {
    const { container, rerender } = render(<MessageList messages={WINDOW} currentUserId={ME} />)
    const list = container.querySelector('.message-list') as HTMLOListElement
    // 600-560-400 < 0 — at the bottom edge.
    const metrics: ScrollMetrics = { scrollTop: 560, scrollHeight: 600, clientHeight: 400 }
    installScrollMetrics(list, metrics)
    fireEvent.scroll(list)

    rerender(
      <MessageList
        messages={[...WINDOW, dialogMessage('w-in-4', 4, PEER, 'новое входящее')]}
        currentUserId={ME}
      />,
    )

    expect(metrics.scrollTop).toBe(600)
  })

  it('keeps the scroll untouched for a new incoming while the user reads history', () => {
    const { container, rerender } = render(<MessageList messages={WINDOW} currentUserId={ME} />)
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics = { ...READING_HISTORY }
    installScrollMetrics(list, metrics)
    fireEvent.scroll(list)

    rerender(
      <MessageList
        messages={[...WINDOW, dialogMessage('w-in-4', 4, PEER, 'новое входящее')]}
        currentUserId={ME}
      />,
    )

    // The user is 100px-equivalent deep in history — no yank.
    expect(metrics.scrollTop).toBe(100)
  })

  it('never scrolls on open: the first window only arms the tracking', () => {
    const { container } = render(<MessageList messages={WINDOW} currentUserId={ME} />)
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics = { ...READING_HISTORY }
    installScrollMetrics(list, metrics)

    expect(container.querySelectorAll('.message')).toHaveLength(3)
    expect(metrics.scrollTop).toBe(100)
  })

  it('re-arms on the chat switch: the next open does not jump to the bottom', () => {
    const props = (messages: readonly Message[]) => (
      <MessageList messages={messages} currentUserId={ME} />
    )
    const { container, rerender } = render(props(WINDOW))
    const firstList = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 560, scrollHeight: 600, clientHeight: 400 }
    installScrollMetrics(firstList, metrics)
    fireEvent.scroll(firstList)

    // Chat switch: the hook resets the window to [] (empty state, no
    // list element), then the next chat's first page renders a FRESH
    // <ol> — the shared metrics object keeps standing in for it.
    rerender(props([]))
    rerender(
      props([dialogMessage('n-in-1', 1, PEER, 'новый чат'), dialogMessage('n-in-2', 2, ME, 'ок')]),
    )
    const secondList = container.querySelector('.message-list') as HTMLOListElement
    metrics.scrollHeight = 800
    installScrollMetrics(secondList, metrics)

    expect(secondList).not.toBe(firstList)
    expect(metrics.scrollTop).toBe(560)
  })

  it('keeps the loadOlder viewport anchor when an older page prepends: no bottom jump', () => {
    const onLoadOlder = vi.fn()
    const props = (messages: readonly Message[]) => (
      <MessageList
        messages={messages}
        currentUserId={ME}
        hasOlder
        loadingOlder={false}
        onLoadOlder={onLoadOlder}
      />
    )
    const { container, rerender } = render(props(WINDOW))
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 0, scrollHeight: 600, clientHeight: 400 }
    installScrollMetrics(list, metrics)

    // Scroll to the top: the older page is requested with the anchor
    // captured (T053) — the user is far from the bottom here.
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    // The older page prepends and grows the content above by 400px:
    // the viewport stays pinned (anchor shift 0→400), and the sticky
    // effect must NOT dump the user at the bottom (1000).
    metrics.scrollHeight = 1000
    rerender(
      props([
        dialogMessage('o-in--1', -1, PEER, 'старая 1'),
        dialogMessage('o-in-0', 0, ME, 'старая 2'),
        ...WINDOW,
      ]),
    )

    expect(metrics.scrollTop).toBe(400)
  })

  it('follows a new incoming while an optimistic row is still pending (the server branch stays live)', () => {
    const props = (messages: readonly Message[], outbox?: readonly OutboxRecord[]) => (
      <MessageList messages={messages} currentUserId={ME} outbox={outbox} />
    )
    const { container, rerender } = render(props(WINDOW))
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 560, scrollHeight: 600, clientHeight: 400 }
    installScrollMetrics(list, metrics)

    // Own send lands (unconditional scroll), the user stays at the
    // bottom edge, the ack is still in flight.
    rerender(props(WINDOW, [SENDING]))
    metrics.scrollTop = 590
    fireEvent.scroll(list)

    // A peer's message arrives BEFORE the ack — it renders above the
    // optimistic row, the feed follows it down.
    rerender(props([...WINDOW, dialogMessage('w-in-4', 4, PEER, 'ответ собеседника')], [SENDING]))
    expect(metrics.scrollTop).toBe(600)
  })

  it('scrolls a realtime message.created to the bottom through the live wiring (MessengerPage path)', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(dialogPage(WINDOW))

    const { container } = render(<DialogWindow chatId="chat-1" currentUserId={ME} />)
    await waitFor(() => {
      expect(container.querySelectorAll('.message')).toHaveLength(3)
    })
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ScrollMetrics = { scrollTop: 560, scrollHeight: 600, clientHeight: 400 }
    installScrollMetrics(list, metrics)
    fireEvent.scroll(list)

    emitIncoming(stream, 'w-in-4', 4)
    await waitFor(() => {
      expect(container.querySelectorAll('.message')).toHaveLength(4)
    })
    expect(metrics.scrollTop).toBe(600)
  })
})

describe('MessageList seat convergence after the cold layout (bug 8а, T086)', () => {
  /**
   * T086(а): the open seat's `scrollIntoView({block:'end'})` runs
   * against a COLD layout — `content-visibility: auto` keeps 64px
   * placeholders until the first paint, so the browser may undershoot
   * (the seat bottom above the fold — positive gap) or no-op entirely
   * (the cold layout thinks everything fits; after the warm-up the
   * seat bottom hangs BELOW the fold — negative gap). The post-paint
   * correction must re-scroll in BOTH directions until the seat sits
   * at the fold (|gap| ≤ 2), within a bounded frame budget.
   *
   * jsdom ships no layout and no `scrollIntoView`: the mock records
   * the calls, the seat/list rects are per-test instance mocks, and
   * the rAF correction frames are flushed by hand through a stubbed
   * `requestAnimationFrame`.
   */
  let scrolled: Array<{ element: Element; block?: string }>
  let rafQueue: Array<() => void>

  /** Runs one hand-driven animation frame (the pending rAF callbacks). */
  const flushRaf = (): void => {
    const frame = rafQueue
    rafQueue = []
    for (const callback of frame) {
      callback()
    }
  }

  beforeEach(() => {
    scrolled = []
    rafQueue = []
    Element.prototype.scrollIntoView = function (
      this: Element,
      options?: boolean | ScrollIntoViewOptions,
    ): void {
      scrolled.push({
        element: this,
        block: typeof options === 'object' && options !== null ? options.block : undefined,
      })
    }
    vi.stubGlobal('requestAnimationFrame', (callback: () => void): number => {
      rafQueue.push(callback)
      return rafQueue.length
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    // jsdom declares no own scrollIntoView — the mock is all there ever was.
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView')
  })

  const READ_WINDOW: Message[] = [
    dialogMessage('c-in-1', 1, PEER, 'прочитанное 1'),
    dialogMessage('c-out-2', 2, ME, 'прочитанное 2'),
    dialogMessage('c-in-3', 3, PEER, 'прочитанное 3'),
  ]

  /** Instance-level rect mock: only the `bottom` edge drives the gap. */
  function mockBottom(element: Element, bottom: () => number): void {
    element.getBoundingClientRect = () => ({ bottom: bottom() }) as DOMRect
  }

  it('re-scrolls a seat that undershot the fold after the paint (positive gap)', () => {
    const { container } = render(
      <MessageList messages={READ_WINDOW} currentUserId={ME} unreadFromSeq={3} />,
    )
    const anchor = container.querySelector('[data-seat-anchor]') as Element
    let seatBottom = 380
    mockBottom(container.querySelector('.message-list') as Element, () => 500)
    mockBottom(anchor, () => seatBottom)

    flushRaf()
    // The warmed layout left the seat 120px ABOVE the fold — the
    // correction fires (the T085 re-check saw only this direction).
    expect(scrolled).toHaveLength(2)
    expect(scrolled[1]?.block).toBe('end')

    // The corrective scroll aligned the seat with the fold — the loop
    // rests, no further scrollIntoView calls.
    seatBottom = 500
    flushRaf()
    flushRaf()
    expect(scrolled).toHaveLength(2)
  })

  it('re-scrolls a fully no-op seat: the anchor below the fold (negative gap)', () => {
    const { container } = render(
      <MessageList messages={READ_WINDOW} currentUserId={ME} unreadFromSeq={3} />,
    )
    const anchor = container.querySelector('[data-seat-anchor]') as Element
    let seatBottom = 720
    mockBottom(container.querySelector('.message-list') as Element, () => 500)
    mockBottom(anchor, () => seatBottom)

    flushRaf()
    // The cold layout fit everything (scrollIntoView no-op'd); after
    // the warm-up the seat hangs 220px BELOW the fold — the old
    // `gap > 2` check never fired here, the new |gap| one must.
    expect(scrolled).toHaveLength(2)
    expect(scrolled[1]?.block).toBe('end')

    seatBottom = 500
    flushRaf()
    expect(scrolled).toHaveLength(2)
  })

  it('stops correcting after the bounded frame budget even without convergence', () => {
    const { container } = render(
      <MessageList messages={READ_WINDOW} currentUserId={ME} unreadFromSeq={3} />,
    )
    const anchor = container.querySelector('[data-seat-anchor]') as Element
    // The layout keeps drifting (never converges) — the loop must die
    // within its frame budget (well under the 120 frames flushed
    // here) instead of spinning.
    mockBottom(container.querySelector('.message-list') as Element, () => 500)
    mockBottom(anchor, () => 380)

    for (let frame = 0; frame < 120; frame += 1) {
      flushRaf()
    }
    const settled = scrolled.length
    expect(settled).toBeGreaterThan(1)
    expect(settled).toBeLessThan(120)
    // And it stays dead — no zombie frames resurrect the seat.
    for (let frame = 0; frame < 5; frame += 1) {
      flushRaf()
    }
    expect(scrolled).toHaveLength(settled)
  })

  it('never corrects on the zero layout of jsdom (the suite stays quiet)', () => {
    // No rect mocks: jsdom rects are all zeros — the gap is 0 and the
    // correction must rest on the very first frame (the T078/T085
    // suites rely on exactly one scrollIntoView per seat).
    render(<MessageList messages={READ_WINDOW} currentUserId={ME} unreadFromSeq={3} />)
    flushRaf()
    flushRaf()
    flushRaf()
    expect(scrolled).toHaveLength(1)
  })
})

describe('MessageList send autoscroll convergence and ack race (bug 8б/8в, T086)', () => {
  /**
   * T086(б): `list.scrollTop = list.scrollHeight` writes against the
   * COLD layout too — content-visibility placeholders keep the
   * scrollHeight low until the first paint, so the write clamps to the
   * cold maximum and stops ABOVE the real bottom once the rows render.
   * A post-paint rAF loop must re-drive the scroll until
   * `scrollHeight − scrollTop − clientHeight` converges.
   *
   * T086(в): when the 201 ack lands before the optimistic row's first
   * render (a batched flush), `localIdsRef` never held the id and the
   * append used to lose the own-branch — the wiring-level `ownAckIds`
   * set (MessengerPage feeds it from the outbox engine's onConfirmed)
   * must scroll the server copy as own regardless.
   *
   * jsdom ships no layout and no clamping: the local metrics mock
   * CLAMPS writes the way a real scroll container does
   * (`min(value, scrollHeight − clientHeight)`), which is what makes
   * the cold-write undershoot reproducible, and counts every write.
   */
  let rafQueue: Array<() => void>

  const flushRaf = (): void => {
    const frame = rafQueue
    rafQueue = []
    for (const callback of frame) {
      callback()
    }
  }

  beforeEach(() => {
    rafQueue = []
    vi.stubGlobal('requestAnimationFrame', (callback: () => void): number => {
      rafQueue.push(callback)
      return rafQueue.length
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  interface ClampedMetrics {
    scrollTop: number
    scrollHeight: number
    clientHeight: number
    writes: number
  }

  function installClampedScroll(element: HTMLElement, metrics: ClampedMetrics): void {
    Object.defineProperty(element, 'scrollTop', {
      configurable: true,
      get: () => metrics.scrollTop,
      set: (value: number) => {
        metrics.writes += 1
        metrics.scrollTop = Math.max(
          0,
          Math.min(value, metrics.scrollHeight - metrics.clientHeight),
        )
      },
    })
    Object.defineProperty(element, 'scrollHeight', {
      configurable: true,
      get: () => metrics.scrollHeight,
    })
    Object.defineProperty(element, 'clientHeight', {
      configurable: true,
      get: () => metrics.clientHeight,
    })
  }

  const WINDOW: Message[] = [
    dialogMessage('w-in-1', 1, PEER, 'история 1'),
    dialogMessage('w-out-2', 2, ME, 'история 2'),
    dialogMessage('w-in-3', 3, PEER, 'история 3'),
  ]

  const SENDING: OutboxRecord = {
    clientMessageId: 'cm-send-1',
    chatId: 'chat-1',
    text: 'исходящее',
    state: 'sending',
  }

  it('re-drives the send scroll to the true bottom when the warmed layout grows', () => {
    const props = (outbox?: readonly OutboxRecord[]) => (
      <MessageList messages={WINDOW} currentUserId={ME} outbox={outbox} />
    )
    const { container, rerender } = render(props())
    const list = container.querySelector('.message-list') as HTMLOListElement
    // The user reads history (100 from the top — NOT sticky); the cold
    // layout claims 600px of content.
    const metrics: ClampedMetrics = {
      scrollTop: 100,
      scrollHeight: 600,
      clientHeight: 400,
      writes: 0,
    }
    installClampedScroll(list, metrics)
    fireEvent.scroll(list)

    rerender(props([SENDING]))
    // The cold write scrollTop=scrollHeight(600) CLAMPS to the cold
    // maximum 200 — the real browser's undershoot.
    expect(metrics.scrollTop).toBe(200)

    // The paint warms the layout: placeholders become real rows and
    // the scrollHeight grows to 1000 — the post-paint loop must catch
    // the 400px gap and re-drive the scroll to the WARM bottom.
    metrics.scrollHeight = 1000
    flushRaf()
    expect(metrics.scrollTop).toBe(600)

    // Converged: further frames never touch the scroll again.
    flushRaf()
    flushRaf()
    expect(metrics.scrollTop).toBe(600)
    expect(metrics.writes).toBe(2)
  })

  it('never rewrites a settled bottom (no spurious corrections)', () => {
    const props = (outbox?: readonly OutboxRecord[]) => (
      <MessageList messages={WINDOW} currentUserId={ME} outbox={outbox} />
    )
    const { container, rerender } = render(props())
    const list = container.querySelector('.message-list') as HTMLOListElement
    // Already at the cold bottom (600−200−400 = 0): the write lands on
    // the same offset and the post-paint loop must rest immediately.
    const metrics: ClampedMetrics = {
      scrollTop: 200,
      scrollHeight: 600,
      clientHeight: 400,
      writes: 0,
    }
    installClampedScroll(list, metrics)
    fireEvent.scroll(list)

    rerender(props([SENDING]))
    expect(metrics.scrollTop).toBe(200)
    flushRaf()
    flushRaf()
    expect(metrics.scrollTop).toBe(200)
    expect(metrics.writes).toBe(1)
  })

  it('scrolls the server ack as own even when the optimistic row never rendered (201 raced the first render)', () => {
    const props = (messages: readonly Message[], ownAckIds?: ReadonlySet<string>) => (
      <MessageList messages={messages} currentUserId={ME} ownAckIds={ownAckIds} />
    )
    const { container, rerender } = render(props(WINDOW))
    const list = container.querySelector('.message-list') as HTMLOListElement
    // The user reads history — the sticky latch is OFF; the outbox row
    // NEVER renders (the 201 batched with the enqueue flush), so
    // `localIdsRef` cannot recognize the ack. Only the wiring-level
    // own-ack set can scroll it as own (T086в).
    const metrics: ClampedMetrics = {
      scrollTop: 100,
      scrollHeight: 600,
      clientHeight: 400,
      writes: 0,
    }
    installClampedScroll(list, metrics)
    fireEvent.scroll(list)

    rerender(
      props(
        [...WINDOW, dialogMessage('cm-race', 4, ME, 'подтверждение гонки')],
        new Set(['cm-race']),
      ),
    )
    // Own send is unconditional — the ack lands in view at the bottom.
    expect(metrics.scrollTop).toBe(200)
  })

  it('keeps an unrelated own-id append sticky-gated (ownAckIds only vouches for its own ids)', () => {
    const props = (messages: readonly Message[], ownAckIds?: ReadonlySet<string>) => (
      <MessageList messages={messages} currentUserId={ME} ownAckIds={ownAckIds} />
    )
    const { container, rerender } = render(props(WINDOW))
    const list = container.querySelector('.message-list') as HTMLOListElement
    const metrics: ClampedMetrics = {
      scrollTop: 100,
      scrollHeight: 600,
      clientHeight: 400,
      writes: 0,
    }
    installClampedScroll(list, metrics)
    fireEvent.scroll(list)

    // An own message from ANOTHER device (not in the ack set) while
    // the user reads history — still sticky-gated, no yank.
    rerender(
      props([...WINDOW, dialogMessage('other-device', 4, ME, 'с другого устройства')], new Set()),
    )
    expect(metrics.scrollTop).toBe(100)
  })
})
