import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView, Message } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'
import { getAckBatcher } from '../../../sync/ack'
import { cursorsStorageKey } from '../../../sync/cursors'

/**
 * T052 — счётчик непрочитанных при входе (spec 008b Phase 17): A пишет
 * B, B заходит в приложение — бейдж обязан показать ВСЕ непрочитанные
 * входящие (direct и группы) сразу после завершения catch-up-цикла,
 * без ожидания новых сообщений и без reload.
 *
 * Гонка, которую тест фиксирует: SSE onOpen запускает ОДНОВРЕМЕННО
 * refetch №12 (useChatList) и catch-up-цикл (useSync); №12-снимок
 * delivery-ограничен (`seq ≤ delivered_up_to_seq` — считается только
 * №25-подтверждённый хвост), поэтому приходит с `unreadCount: 0`
 * РАНЬШЕ подтверждения ack'ов и затирал оптимистичный бейдж дельты.
 * Сходимость после фикса:
 *
 *  - дельта №26 считает хвост оптимистично (якорь + incoming страницы);
 *  - гоночный пред-ack №12 не обнуляет уже посчитанное (merge держит
 *    неподтверждённый хвост; поздний ответ к тому же отменяется более
 *    свежим реанкор-рефетчем);
 *  - по завершении цикла (после `batcher.flush()` — №25 подтверждён)
 *    страница реанкорит №12: после-ack снимок серверно-авторитарен;
 *  - вторичное окно (курсор впереди, №26 без дельт) закрывается
 *    стартовой докладкой хвостов курсора через общий батчер — №25
 *    идемпотентен/монотонен, реанкор видит хвост в счётчике.
 */

type ChatListItemSchema = components['schemas']['ChatListItem']
type SyncChatDelta = components['schemas']['SyncChatDelta']
type SyncResponse = components['schemas']['SyncResponse']

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

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const ALICE_CHAT_ID = 'chat-direct-1'
const GROUP_CHAT_ID = 'chat-group-1'

function peer(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function makeMessage(chatId: string, seq: number, senderId: string): Message {
  return {
    id: `m-${chatId}-${seq}`,
    chatId,
    senderId,
    text: `text-${seq}`,
    seq,
    createdAt: `2026-09-24T10:00:${String(seq % 60).padStart(2, '0')}.000Z`,
  }
}

function directRow(unreadCount: number, lastMessage: Message | null): ChatListItem {
  return {
    chatId: ALICE_CHAT_ID,
    peer: peer(ALICE, 'alice'),
    lastMessage,
    unreadCount,
    blockedByMe: false,
  }
}

function groupRow(unreadCount: number, lastMessage: Message | null): ChatListItem {
  return {
    chatId: GROUP_CHAT_ID,
    type: 'group',
    title: 'Проект Альфа',
    memberCount: 3,
    myRole: 'member',
    peer: null,
    lastMessage,
    unreadCount,
    blockedByMe: null,
  }
}

function delta(fields: Partial<SyncChatDelta> & Pick<SyncChatDelta, 'chatId'>): SyncChatDelta {
  return {
    peer: peer(ALICE, 'alice'),
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

function groupDelta(fields: Partial<SyncChatDelta> & Pick<SyncChatDelta, 'chatId'>): SyncChatDelta {
  return delta({
    type: 'group',
    title: 'Проект Альфа',
    memberCount: 3,
    peer: null,
    blockedByMe: null,
    peerReadUpToSeq: null,
    othersReadUpToSeq: 0,
    ...fields,
  })
}

function syncResponse(chats: SyncChatDelta[], moreChats = false): SyncResponse {
  return { chats, moreChats }
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

/**
 * All defaults EXCEPT listChats — every test sequences the №12 answers
 * itself (initial → racing pre-ack → after-ack re-anchor).
 */
async function renderMessengerPage(): Promise<MockStream> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.getChat.mockResolvedValue({
    chatId: ALICE_CHAT_ID,
    type: 'direct',
    peer: peer(ALICE, 'alice'),
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  })
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listMessagesAfter.mockResolvedValue({ messages: [] })
  mockChats.markChatRead.mockResolvedValue(undefined)
  mockChats.deliveryAck.mockResolvedValue(undefined)
  mockChats.sendMessage.mockResolvedValue(makeMessage(ALICE_CHAT_ID, 1, ME))
  const contacts: ContactView[] = []
  mockChats.listContacts.mockResolvedValue(contacts)
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  const stream = installStream()
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
  // Эффекты коммита пользовательского стейта (включая onOpen-слушатель
  // useSync и звуковые подписки) смонтированы — №20-фетч книги идёт в
  // том же коммите и раньше них (паттерн MessengerPage.sound.test).
  await waitFor(() => {
    expect(mockChats.listContacts).toHaveBeenCalledTimes(1)
  })
  return stream
}

function badgeOf(name: string): string | null {
  const row = screen.getByText(name).closest('button')
  const badge = row?.querySelector('.chat-item-badge')
  return badge?.textContent ?? null
}

/** Resolver of the deferred racing №12 fetch (network order control). */
let releaseRacingFetch!: (rows: ChatListItemSchema[]) => void

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

describe('счётчик непрочитанных при входе (T052, интеграционно)', () => {
  it('direct: один непрочитанный — бейдж 1 после catch-up-цикла, гоночный пред-ack №12 его не затирает', async () => {
    mockChats.listChats
      .mockResolvedValueOnce([directRow(0, makeMessage(ALICE_CHAT_ID, 10, ALICE))])
      .mockImplementationOnce(
        () =>
          new Promise<ChatListItemSchema[]>((resolve) => {
            releaseRacingFetch = resolve
          }),
      )
      .mockResolvedValue([directRow(1, makeMessage(ALICE_CHAT_ID, 11, ALICE))])
    mockChats.sync
      .mockResolvedValueOnce(
        syncResponse([
          delta({
            chatId: ALICE_CHAT_ID,
            messages: [makeMessage(ALICE_CHAT_ID, 11, ALICE)],
            unreadCount: 0,
            lastSeq: 11,
          }),
        ]),
      )
      .mockResolvedValue(syncResponse([]))
    const stream = await renderMessengerPage()
    expect(badgeOf('alice')).toBeNull()

    act(() => {
      stream.onOpen?.()
    })

    // Дельта применилась — оптимистичный бейдж виден сразу.
    await waitFor(() => {
      expect(badgeOf('alice')).toBe('1')
    })
    // Цикл завершён (flush №25) — реанкор-рефетч №12 после-ack.
    await waitFor(() => {
      expect(mockChats.listChats).toHaveBeenCalledTimes(3)
    })

    // Гоночный пред-ack снимок приземляется ПОСЛЕДНИМ (порядок сети):
    // его эффект уже отменён реанкором, а защита merge держала бы
    // бейдж и без отмены — счётчик жив без новых сообщений.
    await act(async () => {
      releaseRacingFetch([directRow(0, makeMessage(ALICE_CHAT_ID, 11, ALICE))])
      await Promise.resolve()
    })
    await new Promise((resolve) => {
      setTimeout(resolve, 20)
    })
    expect(badgeOf('alice')).toBe('1')
  })

  it('группа: тот же вход — бейдж 1 после catch-up-цикла без reload', async () => {
    mockChats.listChats
      .mockResolvedValueOnce([groupRow(0, makeMessage(GROUP_CHAT_ID, 10, BOB))])
      .mockResolvedValueOnce([groupRow(0, makeMessage(GROUP_CHAT_ID, 10, BOB))])
      .mockResolvedValue([groupRow(1, makeMessage(GROUP_CHAT_ID, 11, BOB))])
    mockChats.sync
      .mockResolvedValueOnce(
        syncResponse([
          groupDelta({
            chatId: GROUP_CHAT_ID,
            messages: [makeMessage(GROUP_CHAT_ID, 11, BOB)],
            unreadCount: 0,
            lastSeq: 11,
          }),
        ]),
      )
      .mockResolvedValue(syncResponse([]))
    const stream = await renderMessengerPage()
    expect(badgeOf('Проект Альфа')).toBeNull()

    act(() => {
      stream.onOpen?.()
    })

    await waitFor(() => {
      expect(badgeOf('Проект Альфа')).toBe('1')
    })
    // Реанкор №12 после-ack подтверждает серверный счётчик группы.
    await waitFor(() => {
      expect(mockChats.listChats).toHaveBeenCalledTimes(3)
    })
    expect(badgeOf('Проект Альфа')).toBe('1')
  })

  it('вторичное окно: курсор впереди без №26-дельты — докладка хвоста + реанкор дают бейдж, живой кадр считает от него', async () => {
    // Хвост прошлой сессии доставлен локально, но №25-ack потерян
    // (батчер in-memory) — №26 ответит «доставлено всё», счётчик держит
    // только реанкор после докладки хвоста курсора.
    window.localStorage.setItem(cursorsStorageKey(ME), JSON.stringify({ [ALICE_CHAT_ID]: 11 }))
    mockChats.listChats
      .mockResolvedValueOnce([directRow(0, makeMessage(ALICE_CHAT_ID, 10, ALICE))])
      .mockResolvedValueOnce([directRow(0, makeMessage(ALICE_CHAT_ID, 11, ALICE))])
      .mockResolvedValue([directRow(1, makeMessage(ALICE_CHAT_ID, 11, ALICE))])
    mockChats.sync.mockResolvedValue(syncResponse([]))
    const stream = await renderMessengerPage()
    expect(badgeOf('alice')).toBeNull()

    act(() => {
      stream.onOpen?.()
    })

    // Стартовая докладка хвоста курсора ушла через общий батчер (№25).
    await waitFor(() => {
      expect(mockChats.deliveryAck).toHaveBeenCalledWith([{ chatId: ALICE_CHAT_ID, upToSeq: 11 }])
    })
    // Цикл завершён — реанкор-рефетч несёт после-ack счётчик.
    await waitFor(() => {
      expect(mockChats.listChats).toHaveBeenCalledTimes(3)
    })
    await waitFor(() => {
      expect(badgeOf('alice')).toBe('1')
    })

    // Второе сообщение A: бейдж = 2, а не 1 — хвост над позицией
    // доставки реанкорён и живой кадр считает от него.
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 12, ALICE))
    expect(badgeOf('alice')).toBe('2')
  })
})
