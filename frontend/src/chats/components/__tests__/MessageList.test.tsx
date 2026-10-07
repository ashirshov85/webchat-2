import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useEffect, useRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getChat, listMessages, markChatRead } from '../../../api/chats'
import type { ChatView, Message, MessagePage } from '../../../api/chats'
import { formatDate, formatTime } from '../../../ui/time'
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

/** Wires MessageList to the real hook exactly like MessengerPage (T044, T078). */
function DialogWindow({ chatId, currentUserId }: { chatId: string; currentUserId: string }) {
  const { messages, peerReadUpToSeq, unreadFromSeq } = useChatMessages(chatId)
  return (
    <MessageList
      messages={messages}
      currentUserId={currentUserId}
      peerReadUpToSeq={peerReadUpToSeq}
      unreadFromSeq={unreadFromSeq}
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

describe('MessageList scroll to the first unread incoming on open (bug 2, T078)', () => {
  /**
   * T078: opening a chat with unread messages seats the feed at the
   * FIRST unread INCOMING message (`seq > myReadUpToSeq` of the №13
   * answer, latched at open — `unreadFromSeq`), NOT at the very
   * bottom: everything above the anchor stays reachable by scrolling
   * up. A fully read window and an empty chat keep the current
   * behaviour (no scroll), the anchor fires ONCE per open, and the
   * pagination anchor of `loadOlder` prepends (anchorHeightRef) is
   * untouched.
   *
   * jsdom ships no layout and no `scrollIntoView` — the mock records
   * the calls so the tests pin WHICH row the browser would scroll to.
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
   * seq 1–3 are read (watermark 3), seq 4 is an unread OUTGOING row
   * (not an anchor — only incoming counts), seq 5–6 are the unread
   * incoming ones: the anchor is seq 5, one above the very bottom.
   */
  const UNREAD_WINDOW: Message[] = [
    dialogMessage('r-in-1', 1, PEER, 'прочитанное 1'),
    dialogMessage('r-out-2', 2, ME, 'прочитанное 2'),
    dialogMessage('r-in-3', 3, PEER, 'прочитанное 3'),
    dialogMessage('u-out-4', 4, ME, 'непрочитанный исходящий'),
    dialogMessage('u-in-5', 5, PEER, 'первое непрочитанное'),
    dialogMessage('u-in-6', 6, PEER, 'второе непрочитанное'),
  ]

  it('seats the open chat at the first unread incoming row, not at the very bottom', () => {
    const { container } = render(
      <MessageList messages={UNREAD_WINDOW} currentUserId={ME} unreadFromSeq={3} />,
    )

    // The anchor row is marked for the scroll and stays in the DOM —
    // the rows above it remain reachable by scrolling up (bug 2).
    const anchor = container.querySelector('[data-first-unread]')
    expect(anchor).not.toBeNull()
    expect(anchor?.textContent).toContain('первое непрочитанное')
    expect(container.querySelectorAll('[data-first-unread]')).toHaveLength(1)

    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element).toBe(anchor)
    expect(scrolled[0]?.block).toBe('start')
  })

  it('keeps the current behaviour for a fully read window: no anchor, no scroll', () => {
    const { container } = render(
      <MessageList messages={UNREAD_WINDOW} currentUserId={ME} unreadFromSeq={6} />,
    )

    expect(container.querySelector('[data-first-unread]')).toBeNull()
    expect(scrolled).toHaveLength(0)
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

    // The №13 answer lands with the open-time watermark — the anchor fires.
    rerender(props(3))
    expect(scrolled).toHaveLength(1)
    expect(scrolled[0]?.element.textContent).toContain('первое непрочитанное')
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

  it('re-arms on the chat switch: the next open scrolls to ITS first unread', () => {
    const props = (messages: readonly Message[], unreadFromSeq: number) => (
      <MessageList messages={messages} currentUserId={ME} unreadFromSeq={unreadFromSeq} />
    )
    const { rerender } = render(props(UNREAD_WINDOW, 3))
    expect(scrolled).toHaveLength(1)

    // Chat switch: the hook resets the window to [] — the cycle re-arms.
    rerender(props([], 0))
    expect(scrolled).toHaveLength(1)

    // The next chat opens fully unread — its first incoming is the anchor.
    rerender(
      props(
        [
          dialogMessage('n-in-1', 1, PEER, 'новый чат первое'),
          dialogMessage('n-in-2', 2, ME, 'новый чат второе'),
        ],
        0,
      ),
    )
    expect(scrolled).toHaveLength(2)
    expect(scrolled[1]?.element.textContent).toContain('новый чат первое')
  })

  it('keeps the loadOlder viewport anchor intact after the unread scroll', () => {
    const onLoadOlder = vi.fn()
    // seq 41–60 (even indexes of the alternating fixture are ME) with
    // watermark 40: everything incoming is unread, the anchor is the
    // first incoming row (seq 42).
    const window1 = Array.from({ length: 20 }, (_, index) =>
      dialogMessage(
        `w-${index + 41}`,
        index + 41,
        index % 2 === 0 ? ME : PEER,
        `окно ${index + 41}`,
      ),
    )
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
    const { container, rerender } = render(props(window1))
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

    // The unread seat lands near the top — the standard scroll handler
    // asks for the older page (the rows above the anchor load in).
    fireEvent.scroll(list)
    expect(onLoadOlder).toHaveBeenCalledTimes(1)

    // The older page (seq 21–40) prepends and grows the content above
    // by 400px — the viewport stays pinned to the same rows (T053
    // anchor), and the unread one-shot does not fire again.
    metrics.scrollHeight = 1000
    const olderPage = Array.from({ length: 20 }, (_, index) =>
      dialogMessage(
        `o-${index + 21}`,
        index + 21,
        index % 2 === 0 ? ME : PEER,
        `старая ${index + 21}`,
      ),
    )
    rerender(props([...olderPage, ...window1]))

    expect(metrics.scrollTop).toBe(400)
    expect(scrolled).toHaveLength(1)
  })

  it('drives the anchor from the №13 watermark through the hook (MessengerPage wiring)', async () => {
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
    expect(scrolled[0]?.element.textContent).toContain('первое непрочитанное')
  })
})
