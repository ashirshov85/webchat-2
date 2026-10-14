import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView, Message } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'
import { getAckBatcher } from '../../../sync/ack'

/**
 * T054 — реалтайм-сортировка «Чатов» по последнему сообщению для
 * ОТПРАВИТЕЛЯ в группе (spec 008b Phase 19): №16 fanout группы =
 * активный ростер МИНУС отправитель (realtime-group-events.md §3.7 —
 * «the sender's own stream stays silent»), поэтому подтверждение
 * outbox (№16 201) — единственный живой источник позиции строки
 * отправителя: по confirm страница применяет собственное сообщение к
 * списку («applyOwnSentMessage»), и строка группы поднимается наверх
 * БЕЗ какого-либо realtime-кадра — в той же живой сессии, без
 * reload/reconnect.
 *
 * Direct-чаты кадр own-echo получают (fanout обоим) — confirm там
 * страховка от потерянного кадра; редупликация confirm ⇄ кадр
 * закрывается dedup по `message.id` готового редьюсера: единичное
 * применение, без мерцания превью и без задвоения.
 */

type ChatView = components['schemas']['ChatView']
type GroupView = components['schemas']['GroupView']

const { mockGetCurrentUser, mockChats, mockGroups, mockSse, mockPresence, mockSound } = vi.hoisted(
  () => ({
    mockGetCurrentUser: vi.fn(),
    mockChats: {
      listChats: vi.fn(),
      getChat: vi.fn(),
      listMessages: vi.fn(),
      markChatRead: vi.fn(),
      listMessagesAfter: vi.fn(),
      sync: vi.fn(),
      sendMessage: vi.fn(),
      sendTyping: vi.fn(() => Promise.resolve()),
      setChatSound: vi.fn(),
      deliveryAck: vi.fn(),
      ensureChat: vi.fn(),
      listContacts: vi.fn(),
      removeContact: vi.fn(),
      addContact: vi.fn(),
      searchUsers: vi.fn(),
      deleteChat: vi.fn(),
      blockUser: vi.fn(),
      unblockUser: vi.fn(),
    },
    mockGroups: {
      createGroup: vi.fn(),
      getGroup: vi.fn(),
      updateGroup: vi.fn(),
      deleteGroup: vi.fn(),
      leaveGroup: vi.fn(),
      addMembers: vi.fn(),
      kickMember: vi.fn(),
      setMemberRole: vi.fn(),
      transferOwnership: vi.fn(),
    },
    mockSse: { streamUserEvents: vi.fn() },
    mockPresence: {
      fetchPresenceSettings: vi.fn(),
      updatePresenceSettings: vi.fn(),
      createPresenceHeartbeat: vi.fn(() => ({ updateConnectionId: vi.fn(), stop: vi.fn() })),
    },
    mockSound: {
      chimeOnRealtimeIncoming: vi.fn(),
      chimeOnSyncBatch: vi.fn(),
      playBellTone: vi.fn(),
      playMuteTone: vi.fn(),
    },
  }),
)

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => mockGroups)

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

vi.mock('../../../ui/sound', () => mockSound)

// useOutbox НЕ мокается: сценарий держится на живомconfirm-пути
// (enqueue → №16 → onConfirmed → handleConfirmed → список).

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const ALICE_CHAT_ID = 'chat-direct-alice'
const BOB_CHAT_ID = 'chat-direct-bob'
const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'

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
  text: string,
): Message {
  return { id, chatId, senderId, text, seq, createdAt }
}

function directRow(
  chatId: string,
  userId: string,
  username: string,
  lastMessage: Message | null,
): ChatListItem {
  return {
    chatId,
    peer: peer(userId, username),
    lastMessage,
    unreadCount: 0,
    blockedByMe: false,
  }
}

function groupRow(lastMessage: Message | null): ChatListItem {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    memberCount: 3,
    myRole: 'owner',
    peer: null,
    lastMessage,
    unreadCount: 0,
    blockedByMe: null,
  }
}

function directChatView(chatId: string, userId: string, username: string): ChatView {
  return {
    chatId,
    type: 'direct',
    peer: peer(userId, username),
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  }
}

function groupChatView(): ChatView {
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
  }
}

function groupView(): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: null,
    myRole: 'owner',
    members: [
      { user: peer(ME, 'me'), role: 'owner', joinedAt: '2026-09-20T12:00:00.000Z' },
      { user: peer(ALICE, 'alice'), role: 'member', joinedAt: '2026-09-20T12:00:00.000Z' },
    ],
  }
}

function contacts(): ContactView[] {
  return [
    { user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z', blockedByMe: false },
    { user: peer(BOB, 'bob'), createdAt: '2026-09-02T00:00:00.000Z', blockedByMe: false },
  ]
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
  mockSse.streamUserEvents.mockImplementation((options?: { onOpen?: () => void }) => {
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

async function renderMessengerPage(): Promise<MockStream> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.getChat.mockImplementation((chatId: string) =>
    chatId === GROUP_ID
      ? Promise.resolve(groupChatView())
      : chatId === ALICE_CHAT_ID
        ? Promise.resolve(directChatView(ALICE_CHAT_ID, ALICE, 'alice'))
        : Promise.resolve(directChatView(BOB_CHAT_ID, BOB, 'bob')),
  )
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listMessagesAfter.mockResolvedValue({ messages: [] })
  mockChats.markChatRead.mockResolvedValue(undefined)
  mockChats.deliveryAck.mockResolvedValue(undefined)
  mockChats.sync.mockResolvedValue({ chats: [], moreChats: false })
  mockChats.listContacts.mockResolvedValue(contacts())
  mockGroups.getGroup.mockResolvedValue(groupView())
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  const stream = installStream()
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
  await waitFor(() => {
    expect(mockChats.listContacts).toHaveBeenCalledTimes(1)
  })
  return stream
}

/** Порядок строк «Чатов» — по `.c-name` кнопок строк (DOM-порядок = сортировка). */
function rowTitles(): string[] {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  return Array.from(list.querySelectorAll('.chat-item')).map(
    (row) => row.querySelector('.c-name')?.textContent ?? '',
  )
}

function rowOf(title: string): HTMLElement {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  return within(list).getByText(title).closest('button') as HTMLElement
}

async function openChat(title: string): Promise<void> {
  fireEvent.click(rowOf(title))
  await screen.findByRole('heading', { level: 2, name: title })
}

/** Отправка через композер открытого окна (enqueue → №16 → confirm). */
function sendFromComposer(text: string): void {
  const field = screen.getByLabelText('Текст сообщения')
  fireEvent.change(field, { target: { value: text } })
  fireEvent.keyDown(field, { key: 'Enter' })
}

function emitMessageCreated(stream: MockStream, message: Message): void {
  act(() => {
    stream.emit('message.created', JSON.stringify({ chatId: message.chatId, message }))
  })
}

afterEach(() => {
  cleanup()
  getAckBatcher(ME).dispose()
  vi.resetAllMocks()
  window.localStorage.clear()
})

describe('реалтайм-подъём строки отправителя (T054, интеграционно)', () => {
  it('группа: отправка без какого-либо realtime-кадра — по confirm строка группы поднимается наверх живой сессией', async () => {
    mockChats.listChats.mockResolvedValue([
      directRow(
        BOB_CHAT_ID,
        BOB,
        'bob',
        makeMessage(BOB_CHAT_ID, 'b-1', 10, BOB, '2026-09-24T10:00:00.000Z', 'превью боба'),
      ),
      groupRow(null),
    ])
    const own = makeMessage(GROUP_ID, 'g-own-1', 1, ME, '2026-09-24T14:00:00.000Z', 'привет группа')
    mockChats.sendMessage.mockResolvedValue(own)
    await renderMessengerPage()
    expect(rowTitles()).toEqual(['bob', 'Проект Альфа'])
    const listChatsCalls = mockChats.listChats.mock.calls.length

    await openChat('Проект Альфа')
    sendFromComposer('привет группа')

    await waitFor(() => {
      expect(mockChats.sendMessage).toHaveBeenCalledWith(
        GROUP_ID,
        expect.objectContaining({ text: 'привет группа' }),
      )
    })
    // Confirm применён к списку: группа наверху, превью — своё
    // сообщение с префиксом «Вы: », бейджа нет (исходящее).
    await waitFor(() => {
      expect(rowTitles()).toEqual(['Проект Альфа', 'bob'])
    })
    const groupRowButton = rowOf('Проект Альфа')
    expect(groupRowButton.querySelector('.c-prev')?.textContent).toBe('Вы: привет группа')
    expect(groupRowButton.querySelector('.chat-item-badge')).toBeNull()
    // Живая сессия — без №12-рефетча и без reconnect (кадров не было).
    expect(mockChats.listChats.mock.calls.length).toBe(listChatsCalls)
  })

  it('direct: confirm + затем own-echo кадр — единичное применение, затем входящий кадр растит бейдж (регресс)', async () => {
    mockChats.listChats.mockResolvedValue([
      directRow(
        BOB_CHAT_ID,
        BOB,
        'bob',
        makeMessage(BOB_CHAT_ID, 'b-1', 10, BOB, '2026-09-24T12:00:00.000Z', 'превью боба'),
      ),
      directRow(
        ALICE_CHAT_ID,
        ALICE,
        'alice',
        makeMessage(ALICE_CHAT_ID, 'a-1', 5, ALICE, '2026-09-24T10:00:00.000Z', 'старое сообщение'),
      ),
    ])
    const own = makeMessage(
      ALICE_CHAT_ID,
      'a-own-1',
      11,
      ME,
      '2026-09-24T14:00:00.000Z',
      'привет алиса',
    )
    mockChats.sendMessage.mockResolvedValue(own)
    const stream = await renderMessengerPage()
    expect(rowTitles()).toEqual(['bob', 'alice'])

    await openChat('alice')
    sendFromComposer('привет алиса')

    await waitFor(() => {
      expect(mockChats.sendMessage).toHaveBeenCalled()
    })
    // Confirm поднял строку отправителя наверх (страховка от
    // потерянного own-echo — для direct кадр мог бы и не прийти).
    await waitFor(() => {
      expect(rowTitles()).toEqual(['alice', 'bob'])
    })
    expect(rowOf('alice').querySelector('.c-prev')?.textContent).toBe('Вы: привет алиса')

    // Own-echo кадр с тем же id поверх confirm'а — единичное
    // применение: порядок не «мигает», превью не задваивается.
    emitMessageCreated(stream, own)
    expect(rowTitles()).toEqual(['alice', 'bob'])
    const list = screen.getByRole('list', { name: 'Список чатов' })
    expect(within(list).getAllByText('Вы: привет алиса')).toHaveLength(1)
    expect(rowOf('alice').querySelector('.chat-item-badge')).toBeNull()

    // Регресс (в): входящий кадр по-прежнему поднимает строку и
    // растит бейдж — чужие отправления сходятся кадром (фоновый чат;
    // открытый диалог авточитается, поэтому бейдж проверяется на bob).
    emitMessageCreated(
      stream,
      makeMessage(BOB_CHAT_ID, 'b-2', 11, BOB, '2026-09-24T15:00:00.000Z', 'ответ боба'),
    )
    expect(rowTitles()).toEqual(['bob', 'alice'])
    expect(rowOf('bob').querySelector('.c-prev')?.textContent).toBe('ответ боба')
    expect(rowOf('bob').querySelector('.chat-item-badge')?.textContent).toBe('1')
  })
})
