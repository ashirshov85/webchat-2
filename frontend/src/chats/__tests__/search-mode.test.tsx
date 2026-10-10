import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatListItem, ContactView, Message } from '../../api/chats'
import { MessengerPage } from '../pages/MessengerPage'

/**
 * T068 (008a Phase 11) — проводка псевдо-поиска сообщений открытого
 * чата по мок-паттерну MessengerPage.sound.test.tsx (vi.hoisted +
 * installStream): состояние режима и запроса живёт у СТРАНИЦЫ — кнопка
 * #btnSearch заголовка переключает режим (повторный клик выключает и
 * возвращает фокус композеру), Escape выключает со сбросом запроса,
 * смена чата сбрасывает режим, результаты строятся ТОЛЬКО по
 * загруженному окну ленты (№13-страница + подгруженные №14-страницы
 * useChatMessages) — сообщение за пределами окна не находится; клик по
 * строке-результату прыгает к сообщению ленты со вспышкой.
 */

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

vi.mock('../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../api/chats', () => mockChats)

vi.mock('../../api/groups', () => mockGroups)

vi.mock('../../api/sse', () => mockSse)

vi.mock('../../presence/presenceApi', () => mockPresence)

vi.mock('../../ui/sound', () => mockSound)

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const ALICE_CHAT_ID = 'chat-direct-1'
const BOB_CHAT_ID = 'chat-direct-2'

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

function makeMessage(
  chatId: string,
  id: string,
  seq: number,
  senderId: string,
  text: string,
): Message {
  return {
    id,
    chatId,
    senderId,
    text,
    seq,
    createdAt: `2026-09-24T10:00:${String(seq % 60).padStart(2, '0')}.000Z`,
  }
}

/**
 * Загруженное окно ленты alice: два «привет» (своё и peer) в окне;
 * «давно потерянное привет» за пределами страницы №13 — псевдо-поиск
 * его не находит (ограничение скоупа Phase 11).
 */
function aliceWindow(): Message[] {
  return [
    makeMessage(ALICE_CHAT_ID, 'a-1', 1, ME, 'привет от меня'),
    makeMessage(ALICE_CHAT_ID, 'a-2', 2, ALICE, 'ночной привет'),
    makeMessage(ALICE_CHAT_ID, 'a-3', 3, ALICE, 'ок'),
  ]
}

function bobWindow(): Message[] {
  return [makeMessage(BOB_CHAT_ID, 'b-1', 1, BOB, 'привет из другого чата')]
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

async function renderPage() {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue([
    chatRow(ALICE_CHAT_ID, ALICE, 'alice'),
    chatRow(BOB_CHAT_ID, BOB, 'bob'),
  ])
  mockChats.getChat.mockImplementation((chatId: string) => ({
    chatId,
    type: 'direct' as const,
    peer: peer(chatId === BOB_CHAT_ID ? BOB : ALICE, chatId === BOB_CHAT_ID ? 'bob' : 'alice'),
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 3,
  }))
  mockChats.listMessages.mockImplementation((chatId: string) => ({
    messages: chatId === BOB_CHAT_ID ? bobWindow() : aliceWindow(),
  }))
  mockChats.listMessagesAfter.mockResolvedValue({ messages: [] })
  mockChats.sync.mockResolvedValue({ chats: [], moreChats: false })
  mockChats.markChatRead.mockResolvedValue(undefined)
  mockChats.deliveryAck.mockResolvedValue(undefined)
  mockChats.sendMessage.mockResolvedValue(makeMessage(ALICE_CHAT_ID, 'a-9', 9, ME, 'ok'))
  mockChats.listContacts.mockResolvedValue([
    { user: peer(BOB, 'bob'), createdAt: '2026-09-01T00:00:00.000Z', blockedByMe: false },
  ] as ContactView[])
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  const stream = installStream()
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
  await waitFor(() => {
    expect(mockChats.listContacts).toHaveBeenCalledTimes(1)
  })
  return stream
}

/** Открыть чат alice (заголовок с лупой готов). */
async function openAlice(): Promise<void> {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
  await screen.findByRole('heading', { level: 2, name: 'alice' })
}

/** Лупа #btnSearch заголовка. */
function searchButton(): HTMLElement {
  return screen.getByRole('button', { name: 'Поиск' })
}

/** Поле сайдбара в режиме поиска. */
function chatSearchField(): HTMLElement {
  return screen.getByLabelText('Поиск по чату…')
}

/** Включить режим поиска открытого чата alice. */
async function enterSearchMode(): Promise<void> {
  await openAlice()
  fireEvent.click(searchButton())
  await screen.findByLabelText('Поиск по чату…')
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('MessengerPage псевдо-поиск (T068, 008a Phase 11)', () => {
  it('кнопка включает режим: поле «Поиск по чату…», шапка с именем чата, живой ввод', async () => {
    await renderPage()
    await enterSearchMode()

    expect(searchButton()).toHaveClass('on')
    expect(chatSearchField()).toHaveFocus()
    expect(screen.getByText('Поиск по чату — alice')).toBeVisible()
    expect(screen.getByText('Введите запрос — результаты появятся здесь')).toBeVisible()

    fireEvent.change(chatSearchField(), { target: { value: 'привет' } })
    const rows = document.querySelectorAll('.sr-row')
    // только загруженное окно: a-1 и a-2; a-3 не совпадает, «давно
    // потерянное привет» за пределами окна не находится вовсе.
    expect(rows).toHaveLength(2)
    expect(rows[0]?.querySelector('.c-name')?.textContent).toBe('Вы')
    expect(rows[1]?.querySelector('.c-name')?.textContent).toBe('alice')
    expect(rows[1]?.querySelector('.c-prev')?.textContent).toContain('ночной привет')

    fireEvent.change(chatSearchField(), { target: { value: 'zzz' } })
    expect(screen.getByText('Ничего не найдено')).toBeVisible()
  })

  it('клик по строке-результату прыгает к сообщению ленты со вспышкой', async () => {
    await renderPage()
    await enterSearchMode()

    fireEvent.change(chatSearchField(), { target: { value: 'ночной' } })
    const row = document.querySelector('.sr-row') as HTMLElement
    fireEvent.click(row)

    await waitFor(() => {
      const flashed = document.querySelector('.message-list li.flash')
      expect(flashed).not.toBeNull()
      expect(flashed?.querySelector('.message-text')?.textContent).toBe('ночной привет')
    })
  })

  it('повторный клик по лупе выключает режим и возвращает фокус композеру', async () => {
    await renderPage()
    await enterSearchMode()

    fireEvent.change(chatSearchField(), { target: { value: 'привет' } })
    fireEvent.click(searchButton())

    // сайдбар вернулся к чатам, кнопка без .on, фокус — в композере
    expect(screen.getByLabelText('Поиск чатов')).toBeVisible()
    expect(screen.queryByLabelText('Поиск по чату…')).toBeNull()
    expect(searchButton()).not.toHaveClass('on')
    await waitFor(() => {
      expect(screen.getByLabelText('Текст сообщения')).toHaveFocus()
    })
  })

  it('Escape выключает режим и сбрасывает запрос (повторное включение — поле пусто)', async () => {
    await renderPage()
    await enterSearchMode()

    fireEvent.change(chatSearchField(), { target: { value: 'привет' } })
    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByLabelText('Поиск по чату…')).toBeNull()
    expect(searchButton()).not.toHaveClass('on')
    expect(screen.getByRole('list', { name: 'Список чатов' })).toBeVisible()

    // сброс запроса: повторное включение стартует с пустого поля
    fireEvent.click(searchButton())
    await screen.findByLabelText('Поиск по чату…')
    expect(chatSearchField()).toHaveValue('')
    expect(screen.getByText('Введите запрос — результаты появятся здесь')).toBeVisible()
  })

  it('смена чата сбрасывает режим (модальный путь №11 → onOpenChat)', async () => {
    await renderPage()
    await enterSearchMode()
    expect(screen.getByLabelText('Поиск по чату…')).toBeVisible()

    // Главное меню остаётся в строке поиска: Контакты → строка bob —
    // №11 открывает окно bob, пока режим поиска alice ещё включён.
    mockChats.ensureChat.mockResolvedValue({
      chatId: BOB_CHAT_ID,
      type: 'direct',
      peer: peer(BOB, 'bob'),
      blockedByMe: false,
      peerReadUpToSeq: 0,
      myReadUpToSeq: 1,
    })
    fireEvent.click(screen.getByRole('button', { name: 'Меню' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Контакты' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Контакт bob' }))

    await screen.findByRole('heading', { level: 2, name: 'bob' })
    // Смена чата погасила режим: сайдбар — список чатов, лупа без .on.
    await waitFor(() => {
      expect(screen.queryByLabelText('Поиск по чату…')).toBeNull()
    })
    expect(screen.getByRole('list', { name: 'Список чатов' })).toBeVisible()
    expect(searchButton()).not.toHaveClass('on')
  })

  it('без открытого чата лупы нет (кнопка живёт в заголовке окна)', async () => {
    await renderPage()
    expect(screen.queryByRole('button', { name: 'Поиск' })).toBeNull()
    expect(screen.getByText('Чат не выбран')).toBeVisible()
  })
})
