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
    expect(result.current.chats[0]?.peer.username).toBe('carol')
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
