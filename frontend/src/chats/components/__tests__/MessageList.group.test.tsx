import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { useEffect, useRef } from 'react'
import type { ComponentProps, ComponentType } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getChat, listMessages, markChatRead } from '../../../api/chats'
import type { ChatView, Message, MessagePage } from '../../../api/chats'
import type { GroupMember } from '../../../api/groups'
import { useChatMessages } from '../../hooks/useChatMessages'
import type { SyncPageUpdate } from '../../hooks/useChatMessages'
import { MessageList } from '../MessageList'

/**
 * Local projections of the US2 wiring T038/T039 evolve onto MessageList
 * and useChatMessages (the group props/fields land with those tasks):
 * the adapters keep the red phase lint/type-clean while the ASSERTIONS
 * stay red until the group variant exists. Drop them when the real API
 * matches (T038/T039).
 */
type GroupMessageListProps = ComponentProps<typeof MessageList> & {
  readonly members?: readonly GroupMember[]
  readonly othersReadUpToSeq?: number
}

const GroupMessageList = MessageList as unknown as ComponentType<GroupMessageListProps>

type GroupChatMessagesResult = ReturnType<typeof useChatMessages> & {
  readonly othersReadUpToSeq: number
}

const useGroupChatMessages = useChatMessages as unknown as (
  chatId: string | null,
  userId: string | null,
  groupMembers?: readonly GroupMember[],
) => GroupChatMessagesResult

/** №26 group delta (T039): carries `othersReadUpToSeq` instead of `peerReadUpToSeq`. */
type GroupSyncPageUpdate = SyncPageUpdate & {
  readonly othersReadUpToSeq?: number
}

/**
 * Group variant of the open dialog window (feature 006, US2, T033 →
 * T038/T039; FR-012, realtime-group-events.md §3.7): the same
 * MessageList renders a GROUP once the №28 roster (`members`) flows
 * in — every INCOMING message is attributed to its sender's username
 * (several peers are distinguishable), own messages keep only the
 * status marks. ✓✓ follows the group watermark `othersReadUpToSeq` =
 * MIN(last_read_seq) of the OTHER active members (№13/№26): own
 * messages with `seq ≤ othersReadUpToSeq` render «прочитано ✓✓».
 *
 * The wired tests drive the US2 chat.read semantics through the real
 * hook exactly like MessengerPage: №13 seeds the watermark, every
 * `chat.read` frame advances THAT member's mark, and the ✓✓ candidate
 * is the MIN over the other roster members — the mark flips only when
 * EVERY other active member has read (spec US2-5). The client holds
 * the maximum (FR-012): a stale frame or a reconnect refetch with a
 * lower projection (the re-add exception) never rolls a rendered ✓✓
 * back. №17 read marks flow for group windows as for direct ones
 * (US2-3 convergence), and №26 deltas carry `othersReadUpToSeq` for
 * the offline catch-up.
 */

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
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'

function member(id: string, username: string, role: GroupMember['role'] = 'member'): GroupMember {
  return {
    user: {
      id,
      username,
      email: `${username}@example.com`,
      status: 'active',
      createdAt: '2026-09-01T00:00:00.000Z',
    },
    role,
    joinedAt: '2026-09-01T00:00:00.000Z',
  }
}

const GROUP_ROSTER: readonly GroupMember[] = [
  member(ME, 'me', 'owner'),
  member(ALICE, 'alice'),
  member(BOB, 'bob'),
]

function groupMessage(id: string, seq: number, senderId: string, text = `text-${id}`): Message {
  return { id, chatId: GROUP_ID, senderId, text, seq, createdAt: '2026-09-25T12:00:00.000Z' }
}

function groupPage(messages: Message[], nextBefore?: number): MessagePage {
  return nextBefore === undefined ? { messages } : { messages, nextBefore }
}

/** №13 group variant of the open chat (api-contract.md §3): peer fields null. */
function groupChatView(overrides: Partial<ChatView> = {}): ChatView {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    description: null,
    myRole: 'owner',
    othersReadUpToSeq: 0,
    memberCount: 3,
    peer: null,
    blockedByMe: null,
    peerReadUpToSeq: null,
    myReadUpToSeq: 0,
    ...overrides,
  }
}

/** Per-message status mark: `null` — no mark (incoming messages). */
function statusTexts(container: HTMLElement): Array<string | null> {
  return Array.from(container.querySelectorAll('.message')).map(
    (item) => item.querySelector('.message-status')?.textContent ?? null,
  )
}

/** Sender attribution of a rendered group message (US2): `null` — own message. */
function senderTexts(container: HTMLElement): Array<string | null> {
  return Array.from(container.querySelectorAll('.message')).map(
    (item) => item.querySelector('.message-sender')?.textContent ?? null,
  )
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

function emitGroupRead(
  stream: MockStream,
  chatId: string,
  byUserId: string,
  readUpToSeq: number,
): void {
  act(() => {
    stream.emit('chat.read', JSON.stringify({ chatId, readUpToSeq, byUserId }))
  })
}

/** Wires MessageList to the real hook exactly like MessengerPage of US2 (T038/T039). */
function GroupWindow({
  chatId,
  currentUserId,
  members,
}: {
  chatId: string
  currentUserId: string
  members: readonly GroupMember[]
}) {
  const { messages, othersReadUpToSeq } = useGroupChatMessages(chatId, currentUserId, members)
  return (
    <GroupMessageList
      messages={messages}
      currentUserId={currentUserId}
      members={members}
      othersReadUpToSeq={othersReadUpToSeq}
    />
  )
}

/**
 * The same wiring plus replayed applied №26 catch-up pages (US2-3):
 * every new `update` is one group delta of the reconnect loop.
 */
function SyncGroupWindow({
  chatId,
  currentUserId,
  members,
  update,
}: {
  chatId: string
  currentUserId: string
  members: readonly GroupMember[]
  update: GroupSyncPageUpdate | null
}) {
  const { messages, othersReadUpToSeq, applySyncPage } = useGroupChatMessages(
    chatId,
    currentUserId,
    members,
  )
  const appliedRef = useRef<SyncPageUpdate | null>(null)
  useEffect(() => {
    if (update !== null && update !== appliedRef.current) {
      appliedRef.current = update
      applySyncPage(update)
    }
  }, [update, applySyncPage])
  return (
    <GroupMessageList
      messages={messages}
      currentUserId={currentUserId}
      members={members}
      othersReadUpToSeq={othersReadUpToSeq}
    />
  )
}

beforeEach(() => {
  mockedGetChat.mockResolvedValue(groupChatView())
  mockedMarkChatRead.mockResolvedValue(undefined)
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('MessageList group sender attribution (US2, T033 → T038)', () => {
  it('renders the roster username on incoming group messages and none on own ones', () => {
    const { container } = render(
      <GroupMessageList
        messages={[
          groupMessage('in-1', 1, ALICE, 'Вопрос'),
          groupMessage('in-2', 2, BOB, 'Ответ'),
          groupMessage('out-1', 3, ME, 'Понял'),
        ]}
        currentUserId={ME}
        members={GROUP_ROSTER}
      />,
    )

    expect(screen.getByText('alice')).toBeVisible()
    expect(screen.getByText('bob')).toBeVisible()
    expect(senderTexts(container)).toEqual(['alice', 'bob', null])
    expect(screen.getByText('доставлено ✓')).toBeVisible()
  })

  it('keeps the direct variant attribution-free when no roster is given', () => {
    const { container } = render(
      <GroupMessageList messages={[groupMessage('in-1', 1, ALICE, 'Вопрос')]} currentUserId={ME} />,
    )

    expect(senderTexts(container)).toEqual([null])
    expect(container.querySelector('.message-sender')).toBeNull()
  })
})

describe('MessageList group ✓✓ by othersReadUpToSeq (№13/№26, FR-012)', () => {
  it('renders ✓✓ for own messages at or below the watermark, ✓ above it and nothing on incoming', () => {
    const { container } = render(
      <GroupMessageList
        messages={[
          groupMessage('in-1', 1, ALICE, 'Вопрос'),
          groupMessage('out-1', 2, ME),
          groupMessage('out-2', 3, ME),
        ]}
        currentUserId={ME}
        members={GROUP_ROSTER}
        othersReadUpToSeq={2}
      />,
    )

    // The boundary is inclusive: seq ≤ othersReadUpToSeq counts as read
    // by every other active member (MIN of their watermarks).
    expect(statusTexts(container)).toEqual([null, 'прочитано ✓✓', 'доставлено ✓'])
    expect(container.querySelectorAll('.message-status-read')).toHaveLength(1)
  })

  it('keeps a lone sender without ✓✓ — no other members, nothing is ever read (edge, FR-012)', () => {
    const { container } = render(
      <GroupMessageList
        messages={[groupMessage('out-1', 2, ME)]}
        currentUserId={ME}
        members={[member(ME, 'me', 'owner')]}
      />,
    )

    expect(statusTexts(container)).toEqual(['доставлено ✓'])
  })

  it('flips ✓ to ✓✓ when the watermark advances (a larger othersReadUpToSeq rerender)', () => {
    const messages = [groupMessage('out-1', 2, ME), groupMessage('out-2', 3, ME)]
    const { container, rerender } = render(
      <GroupMessageList
        messages={messages}
        currentUserId={ME}
        members={GROUP_ROSTER}
        othersReadUpToSeq={2}
      />,
    )
    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'доставлено ✓'])

    rerender(
      <GroupMessageList
        messages={messages}
        currentUserId={ME}
        members={GROUP_ROSTER}
        othersReadUpToSeq={3}
      />,
    )

    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])
  })
})

describe('MessageList group chat.read handling (US2, T039; realtime-group-events.md §3.7)', () => {
  it('flips ✓ to ✓✓ only once EVERY other member has read: the watermark is the MIN over the roster', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(groupPage([groupMessage('out-1', 2, ME)]))

    const { container } = render(
      <GroupWindow chatId={GROUP_ID} currentUserId={ME} members={GROUP_ROSTER} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['доставлено ✓'])
    })

    // Alice alone has read up to 2 — bob has not, so the MIN of the
    // others' watermarks stays below 2 and the mark must not flip
    // (spec US2-5: «все остальные текущие участники просмотрели»).
    emitGroupRead(stream, GROUP_ID, ALICE, 2)
    expect(statusTexts(container)).toEqual(['доставлено ✓'])

    emitGroupRead(stream, GROUP_ID, BOB, 2)

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['прочитано ✓✓'])
    })
  })

  it('seeds ✓✓ from the №13 group watermark and keeps it on a stale smaller chat.read frame', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(
      groupPage([groupMessage('out-1', 2, ME), groupMessage('out-2', 3, ME)]),
    )
    mockedGetChat.mockResolvedValueOnce(groupChatView({ othersReadUpToSeq: 3 }))

    const { container } = render(
      <GroupWindow chatId={GROUP_ID} currentUserId={ME} members={GROUP_ROSTER} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])
    })

    emitGroupRead(stream, GROUP_ID, ALICE, 2)

    expect(statusTexts(container)).toEqual(['прочитано ✓✓', 'прочитано ✓✓'])
  })

  it('never renders a rollback when a reconnect refetch returns a lower watermark (FR-012 live-session max)', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(groupPage([groupMessage('out-1', 2, ME)]))
    mockedGetChat.mockResolvedValueOnce(groupChatView({ othersReadUpToSeq: 2 }))
    // A re-added member with a stale mark drags the server projection
    // DOWN (the FR-012 exception) — the live session keeps its maximum.
    mockedGetChat.mockResolvedValue(groupChatView({ othersReadUpToSeq: 1 }))

    const { container } = render(
      <GroupWindow chatId={GROUP_ID} currentUserId={ME} members={GROUP_ROSTER} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['прочитано ✓✓'])
    })

    act(() => {
      stream.onOpen?.()
    })

    await waitFor(() => {
      expect(mockedGetChat).toHaveBeenCalledTimes(2)
    })
    expect(statusTexts(container)).toEqual(['прочитано ✓✓'])
  })

  it('ignores chat.read frames of other chats', async () => {
    const stream = installStream()
    mockedListMessages.mockResolvedValueOnce(groupPage([groupMessage('out-1', 2, ME)]))

    const { container } = render(
      <GroupWindow chatId={GROUP_ID} currentUserId={ME} members={GROUP_ROSTER} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual(['доставлено ✓'])
    })

    emitGroupRead(stream, 'chat-2', ALICE, 2)

    expect(statusTexts(container)).toEqual(['доставлено ✓'])
  })
})

describe('MessageList group read marks and sync deltas (US2; №17/№26)', () => {
  it('advances the №17 read mark from rendered incoming group messages (US2-3 convergence)', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(groupPage([groupMessage('in-1', 5, ALICE, 'Вопрос')]))

    render(<GroupWindow chatId={GROUP_ID} currentUserId={ME} members={GROUP_ROSTER} />)

    await waitFor(() => {
      expect(screen.getByText('Вопрос')).toBeVisible()
    })
    await waitFor(() => {
      expect(mockedMarkChatRead).toHaveBeenCalledWith(GROUP_ID, 5)
    })
  })

  it('actualizes ✓→✓✓ of own messages from a №26 reconnect delta carrying othersReadUpToSeq', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(
      groupPage([
        groupMessage('in-1', 1, ALICE),
        groupMessage('out-1', 2, ME),
        groupMessage('out-2', 3, ME),
      ]),
    )
    const { container, rerender } = render(
      <SyncGroupWindow chatId={GROUP_ID} currentUserId={ME} members={GROUP_ROSTER} update={null} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual([null, 'доставлено ✓', 'доставлено ✓'])
    })

    // The others read up to seq 2 while the user was offline; the №26
    // group delta carries the fresh MIN watermark and the statuses of
    // already rendered messages flip in place (US2-3).
    rerender(
      <SyncGroupWindow
        chatId={GROUP_ID}
        currentUserId={ME}
        members={GROUP_ROSTER}
        update={{ chatId: GROUP_ID, messages: [], othersReadUpToSeq: 2 }}
      />,
    )

    expect(statusTexts(container)).toEqual([null, 'прочитано ✓✓', 'доставлено ✓'])
  })

  it('renders own delta messages read while offline straight as ✓✓ (quickstart §3.2)', async () => {
    installStream()
    mockedListMessages.mockResolvedValueOnce(groupPage([groupMessage('in-1', 1, ALICE)]))
    const { container, rerender } = render(
      <SyncGroupWindow chatId={GROUP_ID} currentUserId={ME} members={GROUP_ROSTER} update={null} />,
    )

    await waitFor(() => {
      expect(statusTexts(container)).toEqual([null])
    })

    // Sent from another device while offline AND already read by every
    // other member: the message and its «прочитано» status arrive in
    // the same group delta.
    rerender(
      <SyncGroupWindow
        chatId={GROUP_ID}
        currentUserId={ME}
        members={GROUP_ROSTER}
        update={{
          chatId: GROUP_ID,
          messages: [groupMessage('out-1', 2, ME)],
          othersReadUpToSeq: 2,
        }}
      />,
    )

    expect(statusTexts(container)).toEqual([null, 'прочитано ✓✓'])
  })
})
