import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView, Message } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * 009, US5, T069 — живая проекция завершения конференции на карточке-
 * приглашении в ленте открытого чата (баг-фикс проводки T024×T059;
 * FR-013, конституция III): кадр №18 `conference.ended` уже доставлен
 * мессенджеру (useConferenceRealtime, один монтаж в MessengerPage,
 * tombstone `endedByChat` chatId→conferenceId) — карточка должна
 * узнавать о завершении из ЭТОГО среза БЕЗ клика «Подключиться»
 * (раньше единственным источником была клик-проверка №45, api-contract
 * §2). Сопоставление: chatId ленты + conferenceId карточки против
 * `endedByChat`.
 *
 * Функция статуса (§4 порядка доставки): дубли/внепорядковые кадры
 * безопасны — повторный `conference.started` завершённой конференции
 * карточку НЕ оживляет; инвариант «одна активная на чат» — после
 * ended A и старта B карточка A остаётся завершённой (липкое
 * терминальное состояние: `endedByChat` хранит только ПОСЛЕДНЮЮ
 * завершённую конференцию чата, ended B вытесняет A из среза), B —
 * живая. Клик-проверка №45 не заменяется: getConference не вызывается
 * (событие — не запрос), истории/контракт не меняются (Q20=A).
 *
 * Мок-паттерн MessengerPage-тестов (vi.hoisted + installStream): кадры
 * №18 подаются вручную в поток api/sse.
 */

type ChatListItemSchema = components['schemas']['ChatListItem']

const {
  mockGetCurrentUser,
  mockChats,
  mockGroups,
  mockSse,
  mockPresence,
  mockSound,
  mockConferences,
} = vi.hoisted(() => ({
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
  // T069: единственная точка REST-конференций, которая может
  // понадобиться живой сессии — REST-fallback №45; карточка обязана
  // сходить кадром №18 БЕЗ него (assert not called).
  mockConferences: { getConference: vi.fn() },
}))

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => mockGroups)

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

vi.mock('../../../ui/sound', () => mockSound)

vi.mock('../../../conferences/api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../conferences/api/conferences')>()),
  getConference: mockConferences.getConference,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const ALICE_CHAT_ID = 'chat-direct-1'
const FOREIGN_CHAT_ID = 'chat-direct-2'

function peer(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function chatRow(chatId: string, userId: string, username: string): ChatListItem {
  return {
    chatId,
    peer: peer(userId, username),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
  }
}

/** Системное сообщение-виджет 009 (api-contract §4) — карточка в ленте. */
function inviteMessage(seq: number, conferenceId: string, mode: 'video' | 'audio'): Message {
  return {
    id: `m-sys-${seq}`,
    chatId: ALICE_CHAT_ID,
    senderId: null,
    text: '',
    seq,
    createdAt: `2026-10-17T12:00:${String(seq % 60).padStart(2, '0')}.000Z`,
    kind: 'system',
    payload: { type: 'conference_invite', conferenceId, mode } as unknown as Message['payload'],
  }
}

interface MockStream {
  onOpen: (() => void) | undefined
  emit(eventType: string, data: string): void
}

/** Поток с ручной подачей кадров (паттерн MessengerPage.sound.test). */
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

function contacts(): ContactView[] {
  return []
}

async function renderPage(chats: ChatListItemSchema[] = [chatRow(ALICE_CHAT_ID, ALICE, 'alice')]) {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue(chats)
  mockChats.getChat.mockResolvedValue({
    chatId: ALICE_CHAT_ID,
    type: 'direct',
    peer: peer(ALICE, 'alice'),
    blockedByMe: false,
    peerReadUpToSeq: 100,
    myReadUpToSeq: 100,
  })
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listMessagesAfter.mockResolvedValue({ messages: [] })
  mockChats.sync.mockResolvedValue({ chats: [], moreChats: false })
  mockChats.markChatRead.mockResolvedValue(undefined)
  mockChats.deliveryAck.mockResolvedValue(undefined)
  mockChats.listContacts.mockResolvedValue(contacts())
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  const stream = installStream()
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
  // Ждём коммит пользовательского стейта (паттерн sound-теста): к
  // моменту listContacts все эффекты среза useConferenceRealtime
  // смонтированы — ручные кадры №18 не потеряются.
  await waitFor(() => {
    expect(mockChats.listContacts).toHaveBeenCalledTimes(1)
  })
  return stream
}

/** Открыть чат alice с карточками в истории (заголовок готов, лента отрисована). */
async function openAlice(history: Message[]): Promise<void> {
  mockChats.listMessages.mockResolvedValue({ messages: history })
  const list = screen.getByRole('list', { name: 'Список чатов' })
  fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
  await screen.findByRole('heading', { level: 2, name: 'alice' })
}

function emitStarted(
  stream: MockStream,
  conferenceId: string,
  chatId: string = ALICE_CHAT_ID,
  mode: 'video' | 'audio' = 'video',
): void {
  act(() => {
    stream.emit(
      'conference.started',
      JSON.stringify({ chatId, conferenceId, startedAt: '2026-10-17T12:10:00.000Z', mode }),
    )
  })
}

function emitEnded(
  stream: MockStream,
  conferenceId: string,
  chatId: string = ALICE_CHAT_ID,
  reason = 'moderator',
): void {
  act(() => {
    stream.emit(
      'conference.ended',
      JSON.stringify({ chatId, conferenceId, endedAt: '2026-10-17T12:30:00.000Z', reason }),
    )
  })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  localStorage.clear()
})

describe('живая проекция завершения на карточке (009 T069, FR-013)', () => {
  it('кадр №18 conference.ended своей конференции — «Конференция завершена» без клика', async () => {
    const stream = await renderPage()
    await openAlice([inviteMessage(1, 'c-live-1', 'video')])
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()

    emitEnded(stream, 'c-live-1')

    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Подключиться' })).toBeNull()
    // событие — не запрос: клик-проверка №45 не выполнялась
    expect(mockConferences.getConference).not.toHaveBeenCalled()
  })

  it('кадры чужого chatId и чужого conferenceId карточку не трогают', async () => {
    const stream = await renderPage()
    await openAlice([inviteMessage(1, 'c-live-1', 'video')])

    emitEnded(stream, 'c-live-1', FOREIGN_CHAT_ID)
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()

    emitEnded(stream, 'c-other-1', ALICE_CHAT_ID)
    expect(screen.getByRole('button', { name: 'Подключиться' })).toBeTruthy()
    expect(screen.queryByText('Конференция завершена')).toBeNull()
  })

  it('внепорядковый conference.started завершённой конференции не оживляет карточку (§4)', async () => {
    const stream = await renderPage()
    await openAlice([inviteMessage(1, 'c-live-1', 'video')])
    emitEnded(stream, 'c-live-1')
    expect(screen.getByText('Конференция завершена')).toBeTruthy()

    emitStarted(stream, 'c-live-1')

    expect(screen.getByText('Конференция завершена')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Подключиться' })).toBeNull()
  })

  it('одна активная на чат: ended A + started B — A завершена, B живая; ended B — обе завершённые', async () => {
    const stream = await renderPage()
    await openAlice([inviteMessage(1, 'c-a', 'video'), inviteMessage(2, 'c-b', 'audio')])
    expect(screen.getAllByRole('button', { name: 'Подключиться' })).toHaveLength(2)

    emitEnded(stream, 'c-a')
    // карточка A терминальная, карточка B — живая (одна кнопка осталась)
    expect(screen.getAllByText('Конференция завершена')).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: 'Подключиться' })).toHaveLength(1)

    emitStarted(stream, 'c-b', ALICE_CHAT_ID, 'audio')
    // B продолжает жить (кадр старта не завершает), A не оживает
    expect(screen.getAllByText('Конференция завершена')).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: 'Подключиться' })).toHaveLength(1)

    emitEnded(stream, 'c-b')
    // ended B вытесняет A из tombstone endedByChat — карточка A
    // держится ЛИПКИМ терминальным состоянием, B сводится кадром
    expect(screen.getAllByText('Конференция завершена')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Подключиться' })).toBeNull()
  })
})
