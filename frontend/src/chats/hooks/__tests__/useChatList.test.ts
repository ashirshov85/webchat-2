import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listChats } from '../../../api/chats'
import type { ChatListItem, Message } from '../../../api/chats'
import { useChatList } from '../useChatList'

/**
 * Chat list state of the «Чаты» panel (US5, T057/T061): the №12
 * aggregate stays live without manual refreshes — `message.created`
 * frames update preview/position/badge of known chats, a frame with an
 * UNKNOWN chatId is the first incoming from a stranger (FR-019/US5-4)
 * and refetches the whole list, `chat.read` frames of the current user
 * zero the badge, and every SSE (re)connect converges via a refetch
 * (FR-009). FR-014 ordering: last visible message first, messageless
 * chats below — owned by this hook's sort, asserted here.
 */

const sse = vi.hoisted(() => ({ streamUserEvents: vi.fn() }))

vi.mock('../../../api/sse', () => sse)

vi.mock('../../../api/chats', () => ({
  listChats: vi.fn(),
}))

const mockedListChats = vi.mocked(listChats)

const ME = '11111111-1111-1111-1111-111111111111'
const PEER_A = '22222222-2222-2222-2222-222222222222'
const PEER_B = '33333333-3333-3333-3333-333333333333'
const STRANGER = '44444444-4444-4444-4444-444444444444'

function peer(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function makeMessage(
  chatId: string,
  id: string,
  seq: number,
  senderId: string,
  createdAt: string,
  text = `text-${id}`,
): Message {
  return { id, chatId, senderId, text, seq, createdAt }
}

function chatItem(overrides: Partial<ChatListItem> = {}): ChatListItem {
  return {
    chatId: 'chat-1',
    peer: peer(PEER_A, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
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

function emitMessageCreated(stream: MockStream, message: Message): void {
  act(() => {
    stream.emit('message.created', JSON.stringify({ chatId: message.chatId, message }))
  })
}

function emitChatRead(
  stream: MockStream,
  event: { chatId: string; readUpToSeq: number; byUserId: string },
): void {
  act(() => {
    stream.emit('chat.read', JSON.stringify(event))
  })
}

const mounted: Array<{ unmount(): void }> = []

function mountChatList(currentUserId: string | null = ME) {
  const rendered = renderHook((userId: string | null) => useChatList(userId), {
    initialProps: currentUserId,
  })
  mounted.push(rendered)
  return rendered
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.clearAllMocks()
})

describe('useChatList initial load', () => {
  it('fetches №12 once and exposes the ready state', async () => {
    installStream()
    mockedListChats.mockResolvedValue([chatItem()])

    const { result } = mountChatList()

    expect(result.current.status).toBe('loading')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.error).toBeNull()
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('sorts by lastMessage.createdAt DESC with messageless chats below (FR-014)', async () => {
    installStream()
    const quiet = chatItem({ chatId: 'chat-quiet', peer: peer(PEER_B, 'bob') })
    const older = chatItem({
      chatId: 'chat-older',
      lastMessage: makeMessage('chat-older', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
    })
    const newer = chatItem({
      chatId: 'chat-newer',
      lastMessage: makeMessage('chat-newer', 'n-1', 10, PEER_B, '2026-09-20T12:00:00.000Z'),
    })
    mockedListChats.mockResolvedValue([quiet, older, newer])

    const { result } = mountChatList()

    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.chats.map((item) => item.chatId)).toEqual([
      'chat-newer',
      'chat-older',
      'chat-quiet',
    ])
  })

  it('exposes the error and keeps chats empty when №12 fails', async () => {
    installStream()
    const problem = { status: 500, title: 'Internal Server Error' }
    mockedListChats.mockRejectedValue(problem)

    const { result } = mountChatList()

    await waitFor(() => {
      expect(result.current.status).toBe('error')
    })
    expect(result.current.error).toBe(problem)
    expect(result.current.chats).toEqual([])
  })
})

describe('useChatList message.created updates (FR-014)', () => {
  it('updates the preview, lifts the position and increments the badge for an incoming frame', async () => {
    const stream = installStream()
    const quiet = chatItem({ chatId: 'chat-quiet', peer: peer(PEER_B, 'bob') })
    const active = chatItem({
      chatId: 'chat-1',
      lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      unreadCount: 2,
    })
    mockedListChats.mockResolvedValue([quiet, active])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(
      stream,
      makeMessage('chat-1', 'a-2', 20, PEER_A, '2026-09-20T12:00:00.000Z', 'новое сообщение'),
    )

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1', 'chat-quiet'])
    expect(result.current.chats[0]?.unreadCount).toBe(3)
    expect(result.current.chats[0]?.lastMessage?.id).toBe('a-2')
    expect(result.current.chats[0]?.lastMessage?.text).toBe('новое сообщение')
  })

  it('lifts the position for an outgoing frame without touching the badge', async () => {
    const stream = installStream()
    const quiet = chatItem({
      chatId: 'chat-quiet',
      peer: peer(PEER_B, 'bob'),
      lastMessage: makeMessage('chat-quiet', 'b-1', 10, PEER_B, '2026-09-20T10:00:00.000Z'),
    })
    const active = chatItem({
      chatId: 'chat-1',
      lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T12:00:00.000Z'),
    })
    mockedListChats.mockResolvedValue([active, quiet])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-quiet', 'b-2', 20, ME, '2026-09-20T14:00:00.000Z'))

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-quiet', 'chat-1'])
    expect(result.current.chats[0]?.unreadCount).toBe(0)
  })

  it('is idempotent for a duplicate frame: same message.id keeps state and reference', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    const frame = makeMessage('chat-1', 'a-2', 20, PEER_A, '2026-09-20T12:00:00.000Z')
    emitMessageCreated(stream, frame)
    const afterFirst = result.current.chats
    expect(afterFirst[0]?.lastMessage?.id).toBe('a-2')

    emitMessageCreated(stream, frame)
    expect(result.current.chats).toBe(afterFirst)
    expect(result.current.chats[0]?.unreadCount).toBe(1)
  })
})

describe('useChatList stranger chat (FR-019/US5-4)', () => {
  it('refetches №12 when a frame arrives with an unknown chatId and the new chat appears by itself', async () => {
    const stream = installStream()
    const mine = chatItem()
    const strangerChat = chatItem({
      chatId: 'chat-stranger',
      peer: peer(STRANGER, 'carol'),
      lastMessage: makeMessage(
        'chat-stranger',
        's-1',
        1,
        STRANGER,
        '2026-09-20T13:00:00.000Z',
        'привет, это carol',
      ),
      unreadCount: 1,
    })
    mockedListChats.mockResolvedValueOnce([mine]).mockResolvedValueOnce([strangerChat, mine])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])

    emitMessageCreated(
      stream,
      makeMessage('chat-stranger', 's-1', 1, STRANGER, '2026-09-20T13:00:00.000Z'),
    )

    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-stranger', 'chat-1'])
    })
    expect(result.current.chats[0]?.peer?.username).toBe('carol')
    expect(result.current.chats[0]?.unreadCount).toBe(1)
  })

  it('applies a known-chat frame locally without a refetch', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(mockedListChats).toHaveBeenCalledTimes(1)

    emitMessageCreated(stream, makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T12:00:00.000Z'))

    expect(result.current.chats[0]?.lastMessage?.id).toBe('a-1')
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })
})

describe('useChatList chat.read updates (FR-014)', () => {
  it('zeroes the chat badge when the current user is the reader', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
        unreadCount: 7,
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitChatRead(stream, { chatId: 'chat-1', readUpToSeq: 10, byUserId: ME })

    expect(result.current.chats[0]?.unreadCount).toBe(0)
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
  })

  it('leaves the list untouched for peer reads (the ✓✓ path of the open dialog)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([chatItem({ unreadCount: 3 })])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    const before = result.current.chats

    emitChatRead(stream, { chatId: 'chat-1', readUpToSeq: 10, byUserId: PEER_A })

    expect(result.current.chats).toBe(before)
    expect(result.current.chats[0]?.unreadCount).toBe(3)
  })
})

describe('useChatList convergence and actions', () => {
  it('refetches №12 on every SSE (re)connect (FR-009)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(3)
    })
    expect(result.current.status).toBe('ready')
  })

  it('reload() re-runs the №12 fetch (error retry, panel actions)', async () => {
    installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.reload()
    })

    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
  })

  it('markChatReadLocally zeroes the badge of the open dialog keeping the position', async () => {
    installStream()
    const unreadTop = chatItem({
      chatId: 'chat-1',
      lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T12:00:00.000Z'),
      unreadCount: 4,
    })
    const other = chatItem({
      chatId: 'chat-2',
      peer: peer(PEER_B, 'bob'),
      lastMessage: makeMessage('chat-2', 'b-1', 10, PEER_B, '2026-09-20T10:00:00.000Z'),
      unreadCount: 1,
    })
    mockedListChats.mockResolvedValue([unreadTop, other])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.markChatReadLocally('chat-1')
    })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1', 'chat-2'])
    expect(result.current.chats[0]?.unreadCount).toBe(0)
    expect(result.current.chats[1]?.unreadCount).toBe(1)
  })
})

describe('useChatList sync unread convergence (US3, T035, FR-007)', () => {
  it('anchors the badge to the №26 counter plus the page incoming — the server counter is pre-ack (US3-2)', async () => {
    installStream()
    mockedListChats.mockResolvedValue([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
        unreadCount: 2,
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [
          makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:01.000Z'),
          makeMessage('chat-1', 'm-1', 12, ME, '2026-09-20T12:00:02.000Z'),
          makeMessage('chat-1', 'a-3', 13, PEER_A, '2026-09-20T12:00:03.000Z'),
        ],
        unreadCount: 2,
      })
    })

    const chat = result.current.chats.find((item) => item.chatId === 'chat-1')
    expect(chat?.unreadCount).toBe(4)
    expect(chat?.lastMessage?.id).toBe('a-3')
  })

  it('adds №15 continuation pages incoming exactly once — repeated pages change nothing (paginated catch-up, US3-2)', async () => {
    installStream()
    mockedListChats.mockResolvedValue([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
        unreadCount: 1,
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    const continuation = [
      makeMessage('chat-1', 'a-4', 14, PEER_A, '2026-09-20T12:00:04.000Z'),
      makeMessage('chat-1', 'm-2', 15, ME, '2026-09-20T12:00:05.000Z'),
      makeMessage('chat-1', 'a-5', 16, PEER_A, '2026-09-20T12:00:06.000Z'),
    ]
    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [
          makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:01.000Z'),
          makeMessage('chat-1', 'a-3', 12, PEER_A, '2026-09-20T12:00:02.000Z'),
        ],
        unreadCount: 1,
      })
    })
    expect(result.current.chats[0]?.unreadCount).toBe(3)
    act(() => {
      result.current.applySyncUpdate({ chatId: 'chat-1', messages: continuation })
    })
    expect(result.current.chats[0]?.unreadCount).toBe(5)

    act(() => {
      result.current.applySyncUpdate({ chatId: 'chat-1', messages: continuation })
    })
    expect(result.current.chats[0]?.unreadCount).toBe(5)
  })

  it('counts a realtime-raced message once when the continuation page re-carries it (safe race, US1-3)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'r-1', 16, PEER_A, '2026-09-20T12:00:06.000Z'))
    expect(result.current.chats[0]?.unreadCount).toBe(1)

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [
          makeMessage('chat-1', 'r-1', 16, PEER_A, '2026-09-20T12:00:06.000Z'),
          makeMessage('chat-1', 'r-2', 17, PEER_A, '2026-09-20T12:00:07.000Z'),
        ],
      })
    })
    expect(result.current.chats[0]?.unreadCount).toBe(2)
  })

  it('converges DOWN to the server counter — offline reads flushed before №26 show up in it (US3-7)', async () => {
    installStream()
    mockedListChats.mockResolvedValue([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
        unreadCount: 9,
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:01.000Z')],
        unreadCount: 1,
      })
    })

    expect(result.current.chats[0]?.unreadCount).toBe(2)
  })

  it('materializes an unknown chat with the server counter plus the page incoming (US1-6/US3-2)', async () => {
    installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-2',
        peer: peer(PEER_B, 'bob'),
        messages: [
          makeMessage('chat-2', 'b-1', 1, PEER_B, '2026-09-20T12:00:01.000Z'),
          makeMessage('chat-2', 'b-2', 2, PEER_B, '2026-09-20T12:00:02.000Z'),
          makeMessage('chat-2', 'm-1', 3, ME, '2026-09-20T12:00:03.000Z'),
        ],
        unreadCount: 3,
      })
    })

    const materialized = result.current.chats.find((item) => item.chatId === 'chat-2')
    expect(materialized?.unreadCount).toBe(5)
    expect(materialized?.lastMessage?.id).toBe('m-1')
  })

  it('never counts truncated history: a truncation-cycle badge derives from the counter and the page only (US1-5/FR-007)', async () => {
    installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        peer: peer(PEER_A, 'alice'),
        messages: [makeMessage('chat-1', 'a-9', 43, PEER_A, '2026-09-20T12:00:01.000Z')],
        unreadCount: 0,
      })
    })

    expect(result.current.chats[0]?.unreadCount).toBe(1)
  })
})

const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'
const OTHER_GROUP_ID = '9a2c9a2c-9a2c-49a2-89a2-c9a2c9a2c9a2'
const ACTOR = '55555555-5555-5555-5555-555555555555'

function groupRow(overrides: Partial<ChatListItem> = {}): ChatListItem {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    memberCount: 3,
    myRole: 'member',
    peer: null,
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: null,
    ...overrides,
  }
}

function emitGroupEvent(stream: MockStream, eventType: string, payload: unknown): void {
  act(() => {
    stream.emit(eventType, JSON.stringify(payload))
  })
}

/**
 * US5 privacy slice (T055 → T058; realtime-group-events.md §5.2/§1,
 * FR-010/FR-015): `group.you_removed` is the FINAL frame of the group
 * for this user — the №12-sourced row must leave «Чаты»
 * deterministically, WITHOUT a refetch/polling round (the server has
 * already answered: the membership row is dead), and every LATER
 * frame of the group — the §1 post-commit order race may publish a
 * message/roster frame committed before the removal after it — must
 * be ignored: no №12 refetch (the US1-2 unknown-group path would
 * otherwise fire), no resurrection, no rename. The tombstone is
 * per-group and group-scoped: other groups/direct chats keep flowing.
 * `group.deleted` joins with the same shape in US6 (T062/T066).
 */
describe('useChatList group.you_removed (US5, T055 → T058, §5.2/§1)', () => {
  it('removes the group row deterministically, without a №12 refetch (без поллинга)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(mockedListChats).toHaveBeenCalledTimes(1)

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('ignores a late group.member.added frame of the removed group — no refetch, no resurrection (§1 race)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])

    // The US1-2 unknown-group path would refetch №12 here — for the
    // tombstoned group it must stay silent.
    emitGroupEvent(stream, 'group.member.added', {
      groupId: GROUP_ID,
      user: peer(STRANGER, 'carol'),
      actorId: ACTOR,
    })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('a late group.updated frame of the removed group changes nothing (§1 race)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })
    emitGroupEvent(stream, 'group.updated', {
      groupId: GROUP_ID,
      title: 'Новое название',
      description: null,
      actorId: ACTOR,
    })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('a redelivered final frame is idempotent — both §3.6 reasons converge to the same removal (FR-015)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })
    const afterRemoval = result.current.chats
    expect(afterRemoval.map((item) => item.chatId)).toEqual(['chat-1'])

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'left' })

    expect(result.current.chats).toBe(afterRemoval)
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('never drops a direct chat on a you_removed frame — the removal is group-scoped', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: 'chat-1', reason: 'kicked' })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('the tombstone is scoped: another group still materializes through the №12 refetch (US1-2 intact)', async () => {
    const stream = installStream()
    const otherGroup = groupRow({ chatId: OTHER_GROUP_ID, title: 'Проект Бета' })
    mockedListChats
      .mockResolvedValueOnce([groupRow(), chatItem()])
      .mockResolvedValueOnce([chatItem(), otherGroup])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })
    emitGroupEvent(stream, 'group.member.added', {
      groupId: OTHER_GROUP_ID,
      user: peer(STRANGER, 'carol'),
      actorId: ACTOR,
    })

    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1', OTHER_GROUP_ID])
    })
  })
})

/**
 * US6 delete slice (T062 → T066; realtime-group-events.md §3.5/§5.2,
 * FR-006): `group.deleted` — the №30 hard-delete broadcast to every
 * former active member — removes the №12-sourced group row from
 * «Чаты» deterministically, WITHOUT a refetch/polling round (the
 * server has already erased the chat), and tombstones the `groupId`
 * against the §1 order race exactly like `group.you_removed`: every
 * LATER frame of the group (a message/roster frame committed before
 * the delete) is ignored — no US1-2 refetch, no rename, no
 * resurrection. Unlike you_removed the tombstone is permanent for
 * this group id: №30 erased the chat, so №12 can never return it
 * (no re-add revival path). The removal stays group-scoped and the
 * tombstone per-group.
 */
describe('useChatList group.deleted (US6, T062 → T066, §3.5/§5.2)', () => {
  it('removes the group row deterministically, without a №12 refetch (без поллинга)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(mockedListChats).toHaveBeenCalledTimes(1)

    emitGroupEvent(stream, 'group.deleted', { groupId: GROUP_ID, actorId: ACTOR })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('ignores a late group.member.added frame of the deleted group — no refetch, no resurrection (§1 race)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.deleted', { groupId: GROUP_ID, actorId: ACTOR })
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])

    // The US1-2 unknown-group path would refetch №12 here — for the
    // tombstoned group it must stay silent.
    emitGroupEvent(stream, 'group.member.added', {
      groupId: GROUP_ID,
      user: peer(STRANGER, 'carol'),
      actorId: ACTOR,
    })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('a late group.updated frame of the deleted group changes nothing (§1 race)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.deleted', { groupId: GROUP_ID, actorId: ACTOR })
    emitGroupEvent(stream, 'group.updated', {
      groupId: GROUP_ID,
      title: 'Новое название',
      description: null,
      actorId: ACTOR,
    })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('a redelivered group.deleted frame is idempotent — the same list reference (FR-015)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.deleted', { groupId: GROUP_ID, actorId: ACTOR })
    const afterDelete = result.current.chats
    expect(afterDelete.map((item) => item.chatId)).toEqual(['chat-1'])

    emitGroupEvent(stream, 'group.deleted', { groupId: GROUP_ID, actorId: ACTOR })

    expect(result.current.chats).toBe(afterDelete)
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('never drops a direct chat on a group.deleted frame — the removal is group-scoped', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.deleted', { groupId: 'chat-1', actorId: ACTOR })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('the tombstone is scoped: another group still materializes through the №12 refetch (US1-2 intact)', async () => {
    const stream = installStream()
    const otherGroup = groupRow({ chatId: OTHER_GROUP_ID, title: 'Проект Бета' })
    mockedListChats
      .mockResolvedValueOnce([groupRow(), chatItem()])
      .mockResolvedValueOnce([chatItem(), otherGroup])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.deleted', { groupId: GROUP_ID, actorId: ACTOR })
    emitGroupEvent(stream, 'group.member.added', {
      groupId: OTHER_GROUP_ID,
      user: peer(STRANGER, 'carol'),
      actorId: ACTOR,
    })

    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1', OTHER_GROUP_ID])
    })
  })
})

/**
 * T051 (re-add convergence, №31 after №32/№33): a group removed by
 * `group.you_removed` (any reason — kicked or a voluntary left) is
 * tombstoned against the §1 order race, but the US6 re-add path must
 * stay open IN A LIVE SESSION: the §3.2 `group.member.added` frame is
 * fanned out to every active participant INCLUDING the added one, so
 * a frame whose `user.id` IS the current user pierces the tombstone
 * and re-runs the US1-2 №12 refetch — the server truth (the restored
 * membership) outranks the local tombstone, the row returns without a
 * reload, and the tombstone clears (the existing №12 cleanup). The
 * §1 negatives stay closed: a stranger-added frame never pierces,
 * `message.created` of the tombstoned group stays ignored until the
 * revival, and a stale redelivered «меня добавили» frame without real
 * membership refetches a №12 that does not return the group — the row
 * stays dead and the tombstone stays armed.
 */
describe('useChatList re-add of a removed group (T051, №31 after №32/№33)', () => {
  it('pierces the tombstone with a «меня добавили» frame — №12 revives the row and clears the tombstone (kicked)', async () => {
    const stream = installStream()
    mockedListChats
      .mockResolvedValueOnce([groupRow(), chatItem()])
      .mockResolvedValueOnce([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(mockedListChats).toHaveBeenCalledTimes(1)

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)

    emitGroupEvent(stream, 'group.member.added', {
      groupId: GROUP_ID,
      user: peer(ME, 'me'),
      actorId: ACTOR,
    })

    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats.map((item) => item.chatId)).toEqual([GROUP_ID, 'chat-1'])
    })

    // Tombstone cleared — the next §3.1 frame renames the live row
    // (live frame-handling, not a one-off resurrection).
    emitGroupEvent(stream, 'group.updated', {
      groupId: GROUP_ID,
      title: 'Новое название',
      description: null,
      actorId: ACTOR,
    })
    expect(result.current.chats.find((item) => item.chatId === GROUP_ID)?.title).toBe(
      'Новое название',
    )
    expect(mockedListChats).toHaveBeenCalledTimes(2)
  })

  it('the same revival works after a voluntary leave (reason left, №33)', async () => {
    const stream = installStream()
    mockedListChats
      .mockResolvedValueOnce([groupRow(), chatItem()])
      .mockResolvedValueOnce([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'left' })
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])

    emitGroupEvent(stream, 'group.member.added', {
      groupId: GROUP_ID,
      user: peer(ME, 'me'),
      actorId: ACTOR,
    })

    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats.map((item) => item.chatId)).toEqual([GROUP_ID, 'chat-1'])
    })
  })

  it('a stranger-added frame of the tombstoned group never pierces — no refetch, no resurrection (§1 race)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow(), chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })
    emitGroupEvent(stream, 'group.member.added', {
      groupId: GROUP_ID,
      user: peer(STRANGER, 'carol'),
      actorId: ACTOR,
    })

    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('message.created of the group is ignored before the re-add and flows again after the revival', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValueOnce([groupRow(), chatItem()]).mockResolvedValueOnce([
      groupRow({
        lastMessage: makeMessage(GROUP_ID, 'g-1', 5, ACTOR, '2026-09-20T10:00:00.000Z'),
      }),
      chatItem(),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })

    // BEFORE the re-add: a frame of the tombstoned group is the §1
    // race — ignored, no US1-2/FR-019 refetch.
    emitMessageCreated(stream, makeMessage(GROUP_ID, 'g-1', 5, ACTOR, '2026-09-20T10:00:00.000Z'))
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(1)

    emitGroupEvent(stream, 'group.member.added', {
      groupId: GROUP_ID,
      user: peer(ME, 'me'),
      actorId: ACTOR,
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats.map((item) => item.chatId)).toEqual([GROUP_ID, 'chat-1'])
    })

    // AFTER the revival: the tombstone is gone — an incoming frame
    // updates the preview and grows the badge as for any known chat.
    emitMessageCreated(stream, makeMessage(GROUP_ID, 'g-2', 6, ACTOR, '2026-09-20T11:00:00.000Z'))
    const group = result.current.chats.find((item) => item.chatId === GROUP_ID)
    expect(group?.lastMessage?.id).toBe('g-2')
    expect(group?.unreadCount).toBe(1)
  })

  it('a stale redelivered «меня добавили» frame without real membership does not resurrect the row', async () => {
    const stream = installStream()
    mockedListChats
      .mockResolvedValueOnce([groupRow(), chatItem()])
      .mockResolvedValueOnce([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitGroupEvent(stream, 'group.you_removed', { groupId: GROUP_ID, reason: 'kicked' })
    emitGroupEvent(stream, 'group.member.added', {
      groupId: GROUP_ID,
      user: peer(ME, 'me'),
      actorId: ACTOR,
    })

    // The refetch happened (the pierce is honest), but №12 has no
    // membership to return — the row stays dead.
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    })

    // The tombstone stays armed: №12 did not return the group, so a
    // later §3.1 frame of it is still the §1 race — ignored.
    emitGroupEvent(stream, 'group.updated', {
      groupId: GROUP_ID,
      title: 'Новое название',
      description: null,
      actorId: ACTOR,
    })
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1'])
    expect(mockedListChats).toHaveBeenCalledTimes(2)
  })
})

describe('useChatList blocked badge freeze (US3-5, FR-020 of 004)', () => {
  function blockedChatItem(): ChatListItem {
    return chatItem({
      blockedByMe: true,
      unreadCount: 3,
      lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
    })
  }

  it('skips realtime increments while blocked — the preview still follows the frame', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([blockedChatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(
      stream,
      makeMessage('chat-1', 'a-2', 20, PEER_A, '2026-09-20T12:00:00.000Z', 'под блокировкой'),
    )

    const chat = result.current.chats.find((item) => item.chatId === 'chat-1')
    expect(chat?.unreadCount).toBe(3)
    expect(chat?.lastMessage?.id).toBe('a-2')
  })

  it('keeps the badge through markChatReadLocally and own chat.read while blocked', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([blockedChatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.markChatReadLocally('chat-1')
    })
    expect(result.current.chats[0]?.unreadCount).toBe(3)

    emitChatRead(stream, { chatId: 'chat-1', readUpToSeq: 20, byUserId: ME })
    expect(result.current.chats[0]?.unreadCount).toBe(3)
  })

  it('ignores sync delta counters while blocked', async () => {
    installStream()
    mockedListChats.mockResolvedValue([blockedChatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [makeMessage('chat-1', 'a-3', 30, PEER_A, '2026-09-20T12:00:01.000Z')],
        unreadCount: 7,
      })
    })

    const chat = result.current.chats.find((item) => item.chatId === 'chat-1')
    expect(chat?.unreadCount).toBe(3)
    expect(chat?.lastMessage?.id).toBe('a-3')
  })

  it('preserves the frozen value across the T052 entry race too — a №26-counted tail never thaws a blocked badge', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValueOnce([
      chatItem({
        blockedByMe: true,
        unreadCount: 3,
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:00.000Z')],
        unreadCount: 7,
      })
    })
    expect(result.current.chats[0]?.unreadCount).toBe(3)

    mockedListChats.mockResolvedValueOnce([chatItem({ blockedByMe: true, unreadCount: 8 })])
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await settleRefetch()

    expect(result.current.chats[0]?.unreadCount).toBe(3)
  })

  it('preserves the frozen value across №12 refetches while the block persists and converges after unblock', async () => {
    installStream()
    mockedListChats.mockResolvedValueOnce([blockedChatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    mockedListChats.mockResolvedValueOnce([chatItem({ blockedByMe: true, unreadCount: 8 })])
    act(() => {
      result.current.reload()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(result.current.chats[0]?.unreadCount).toBe(3)
    })

    mockedListChats.mockResolvedValueOnce([chatItem({ blockedByMe: false, unreadCount: 8 })])
    act(() => {
      result.current.reload()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(3)
    })
    await waitFor(() => {
      expect(result.current.chats[0]?.unreadCount).toBe(8)
    })
  })
})

/**
 * Pumps the microtask chain of an in-flight №12 fetch (the effect body
 * continues after exactly one `await listChats()` hop) so a triggered
 * refetch is guaranteed APPLIED before the next assertion — waitFor on
 * the call count alone can pass while the merge has not landed yet.
 */
async function settleRefetch(hops = 4): Promise<void> {
  for (let hop = 0; hop < hops; hop += 1) {
    await act(async () => {
      await Promise.resolve()
    })
  }
}

/**
 * T052 (entry unread race; spec 008b Phase 17, FR-007 user report):
 * №12's `unreadCount` is DELIVERY-BOUNDED — `seq ≤ LEAST(last_seq,
 * delivered_up_to_seq)`, only the №25-acked tail counts — while the
 * refetch every SSE (re)open fires races the catch-up cycle of the
 * SAME (re)open: a №26 delta (or a live frame) counts the missed
 * incoming optimistically, and the №12 response — a snapshot taken
 * before the debounced №25 moved the delivery position — still answers
 * `unreadCount: 0`, so the wholesale merge replace ERASED the badge at
 * entry (the counter appeared only after the NEXT live message).
 *
 * The merge must keep the already-counted optimistic tail while its
 * ack is unconfirmed (a pre-ack snapshot cannot know it — same
 * `lastMessage.id`, lower counter is ambiguous by contract), and the
 * server stays the truth AFTER the ack: `confirmCountedTails()` — the
 * completed catch-up cycle flushed №25 — re-arms the plain replace so
 * a legitimately lower value converges the badge DOWN too (US3-7,
 * read from another device / offline №17 flush before the snapshot).
 * FR-020 is untouched: a blocked chat never counts, its frozen badge
 * rides the existing merge rule (the test above).
 */
describe('useChatList pre-ack №12 merge (T052, entry race)', () => {
  it('a racing pre-ack №12 (same lastMessage.id, unreadCount 0) does not erase the №26-counted badge', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValueOnce([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    // The №26 delta of the same (re)open: pre-ack anchor 0, the page's
    // single incoming counts on top — the optimistic badge of 1 with
    // `countedIncomingSeq` = 11.
    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:00.000Z')],
        unreadCount: 0,
      })
    })
    expect(result.current.chats[0]?.unreadCount).toBe(1)

    // The racing №12 lands AFTER the delta (network order): the message
    // is committed (same lastMessage.id) but №25 has not moved the
    // delivery position yet — `unreadCount: 0`.
    mockedListChats.mockResolvedValueOnce([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:00.000Z'),
        unreadCount: 0,
      }),
    ])
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await settleRefetch()

    expect(result.current.chats[0]?.unreadCount).toBe(1)
  })

  it('the same protection holds for a realtime-counted tail — a live frame raced the refetch', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValueOnce([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    emitMessageCreated(stream, makeMessage('chat-1', 'a-2', 20, PEER_A, '2026-09-20T12:00:00.000Z'))
    expect(result.current.chats[0]?.unreadCount).toBe(1)

    mockedListChats.mockResolvedValueOnce([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-2', 20, PEER_A, '2026-09-20T12:00:00.000Z'),
        unreadCount: 0,
      }),
    ])
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await settleRefetch()

    expect(result.current.chats[0]?.unreadCount).toBe(1)
  })

  it('converges DOWN after confirmCountedTails() — the server is the truth once №25 is flushed (US3-7)', async () => {
    installStream()
    mockedListChats.mockResolvedValueOnce([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applySyncUpdate({
        chatId: 'chat-1',
        messages: [makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:00.000Z')],
        unreadCount: 0,
      })
    })
    expect(result.current.chats[0]?.unreadCount).toBe(1)

    // The catch-up cycle completed — the №25 flush confirmed the tail,
    // so the protection is re-armed off and a legitimately lower value
    // (the offline №17 flush landed before this snapshot — read from
    // another device) converges the badge down.
    act(() => {
      result.current.confirmCountedTails()
    })
    mockedListChats.mockResolvedValueOnce([
      chatItem({
        lastMessage: makeMessage('chat-1', 'a-2', 11, PEER_A, '2026-09-20T12:00:00.000Z'),
        unreadCount: 0,
      }),
    ])
    act(() => {
      result.current.reload()
    })
    await waitFor(() => {
      expect(mockedListChats).toHaveBeenCalledTimes(2)
    })
    await settleRefetch()

    expect(result.current.chats[0]?.unreadCount).toBe(0)
  })
})

/**
 * T054 (sender lift, 008b Phase 19): the №16 group fanout is the
 * active roster MINUS the sender (realtime-group-events.md §3.7 —
 * «the sender's own stream stays silent», the devices already hold
 * the durable POST answer), so the outbox confirm — not a frame — is
 * the only live source of the sender's own row position. The hook
 * exposes `applyOwnSentMessage(message)` for exactly that caller
 * (MessengerPage `handleConfirmed`): the ready `applyMessageCreated`
 * reducer lifts the row with ANY message (preview + sort, badge only
 * for incoming, dedup by `message.id`), so an own confirm is a pure
 * position/preview update — the badge never grows, a direct-chat
 * own-echo frame racing the confirm dedups to the SAME reference, and
 * the FR-020 frozen badge of a blocked chat is untouched (an outgoing
 * message is not incoming bookkeeping).
 */
describe('useChatList own sent message (T054, №16 confirm)', () => {
  it('lifts a messageless known group row to the top with the own message as preview — badge stays 0', async () => {
    installStream()
    const group = groupRow()
    const activeDirect = chatItem({
      chatId: 'chat-1',
      lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
    })
    mockedListChats.mockResolvedValue([activeDirect, group])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(result.current.chats.map((item) => item.chatId)).toEqual(['chat-1', GROUP_ID])

    act(() => {
      result.current.applyOwnSentMessage(
        makeMessage(GROUP_ID, 'g-1', 1, ME, '2026-09-20T14:00:00.000Z', 'моё сообщение'),
      )
    })

    expect(result.current.chats.map((item) => item.chatId)).toEqual([GROUP_ID, 'chat-1'])
    const lifted = result.current.chats.find((item) => item.chatId === GROUP_ID)
    expect(lifted?.lastMessage?.id).toBe('g-1')
    expect(lifted?.lastMessage?.text).toBe('моё сообщение')
    // Исходящее: бейдж не растёт (FR-014 — только входящие).
    expect(lifted?.unreadCount).toBe(0)
  })

  it('is idempotent: re-applying the same message.id and a later own-echo frame keep the SAME reference (US1-3)', async () => {
    const stream = installStream()
    mockedListChats.mockResolvedValue([groupRow()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    const own = makeMessage(GROUP_ID, 'g-1', 1, ME, '2026-09-20T14:00:00.000Z')
    act(() => {
      result.current.applyOwnSentMessage(own)
    })
    const afterConfirm = result.current.chats
    expect(afterConfirm[0]?.lastMessage?.id).toBe('g-1')

    act(() => {
      result.current.applyOwnSentMessage(own)
    })
    expect(result.current.chats).toBe(afterConfirm)

    // Direct-style own-echo кадр с тем же id поверх confirm'а —
    // единичное применение, без задвоения превью.
    emitMessageCreated(stream, own)
    expect(result.current.chats).toBe(afterConfirm)
    expect(result.current.chats[0]?.unreadCount).toBe(0)
  })

  it('ignores an own message of an unknown chatId — no row materializes, no refetch', async () => {
    installStream()
    mockedListChats.mockResolvedValue([chatItem()])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    const before = result.current.chats

    act(() => {
      result.current.applyOwnSentMessage(
        makeMessage('chat-unknown', 'u-1', 1, ME, '2026-09-20T14:00:00.000Z'),
      )
    })

    expect(result.current.chats).toBe(before)
    expect(mockedListChats).toHaveBeenCalledTimes(1)
  })

  it('keeps the FR-020 frozen badge of a blocked chat — an own confirm is not incoming bookkeeping', async () => {
    installStream()
    mockedListChats.mockResolvedValue([
      chatItem({
        blockedByMe: true,
        unreadCount: 3,
        lastMessage: makeMessage('chat-1', 'a-1', 10, PEER_A, '2026-09-20T10:00:00.000Z'),
      }),
    ])
    const { result } = mountChatList()
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })

    act(() => {
      result.current.applyOwnSentMessage(
        makeMessage('chat-1', 'm-1', 20, ME, '2026-09-20T14:00:00.000Z'),
      )
    })

    const chat = result.current.chats.find((item) => item.chatId === 'chat-1')
    expect(chat?.unreadCount).toBe(3)
    expect(chat?.lastMessage?.id).toBe('m-1')
  })
})
