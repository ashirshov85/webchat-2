import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView, Message } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * T070 — приёмочный прогон «без мыши» (quickstart E4, SC-008, FR-035):
 * Tab/Enter/Space/Esc/стрелки по всем интерактивам ui/ + chats/, видимый
 * focus-ring, возврат фокуса на инициатора после меню/модалей/drawer.
 *
 * Аудит покрытия на момент задачи: все интерактивы машины — нативные
 * <button>/<input>/<textarea> (Enter/Space — платформа, S6819; паттерн
 * T031/T043/T054), focus-ring :focus-visible/:focus-within — в CSS каждой
 * поверхности (chat-list-panel/contacts-modal/message-input/message-list/
 * chat-header/ctx-menu/confirm-dialog/machine burger/settings/banners),
 * клавиатура примитивов — T012 (ModalShell: ловушка/возврат/Esc) и T013
 * (ContextMenu: стрелки/Enter/Space/Esc/возврат). Этот файл — приёмочный
 * уровень СТРАНИЦЫ: безмышиный проход A–D (меню → модаль → композер) и
 * закрытие пробела drawer (FR-035 «ловушка фокуса внутри модальных окон
 * И drawer» + «возврат фокуса на инициатор»; сам прототип openSidebar/
 * closeSidebar фокуса не ведает — норматив spec.md, не chats.html):
 * Tab не покидает открытый drawer, Esc/выбор чата возвращают фокус на
 * burger-инициатор, ЗАКРЫТЫЙ off-canvas сайдбар исключён из табуляции
 * (inert — transform-скрытие оставляет кнопки достижимыми, иначе
 * «логичный порядок табуляции» FR-035 нарушен невидимыми элементами).
 *
 * Тесты drawer-блока TDD-красные до реализации T070 (ловушки/возврата/
 * inert в MessengerPage нет); проход A–D частично паритетен юнит-тестам
 * T012/T013/T024 — здесь собран сквозным сценарием страницы (E4).
 */

type ChatView = components['schemas']['ChatView']

const { mockGetCurrentUser, mockChats, mockCreateGroup, mockSse, mockPresence } = vi.hoisted(
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
      // 008a T038: машина №41-сигналов композера (useTyping) — best-effort;
      // контракт api-функции — Promise.
      sendTyping: vi.fn(() => Promise.resolve()),
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
    mockCreateGroup: vi.fn(),
    mockSse: { streamUserEvents: vi.fn() },
    mockPresence: {
      fetchPresenceSettings: vi.fn(),
      updatePresenceSettings: vi.fn(),
      createPresenceHeartbeat: vi.fn(() => ({ updateConnectionId: vi.fn(), stop: vi.fn() })),
    },
  }),
)

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => ({
  createGroup: mockCreateGroup,
}))

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const DIRECT_ID = 'chat-direct-1'
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

function aliceRow(): ChatListItem {
  return {
    chatId: DIRECT_ID,
    peer: peer(ALICE, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
  }
}

function bobRow(): ChatListItem {
  return {
    chatId: BOB_CHAT_ID,
    peer: peer(BOB, 'bob'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
  }
}

function directChatView(): ChatView {
  return {
    chatId: DIRECT_ID,
    type: 'direct',
    peer: peer(ALICE, 'alice'),
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  }
}

function contacts(): ContactView[] {
  return [
    { user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z', blockedByMe: false },
    { user: peer(BOB, 'bob'), createdAt: '2026-09-03T00:00:00.000Z', blockedByMe: false },
  ]
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

/** jsdom не реализует matchMedia: стаб экрана (паттерн T062). */
function stubViewport(narrow: boolean): void {
  window.matchMedia = (query: string): MediaQueryList => {
    const compact = query.replace(/\s+/g, '')
    const matches = compact.includes('max-width:900px')
      ? narrow
      : compact.includes('min-width:901px')
        ? !narrow
        : false
    return {
      matches,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }
  }
}

/** Сайдбар (aside.sidebar) — корень ловушки drawer. */
function sidebarEl(): HTMLElement {
  const el = document.querySelector('.sidebar')
  expect(el).not.toBeNull()
  return el as HTMLElement
}

/** Открыть чат alice «с клавиатуры»: фокус на строку + нативная активация. */
async function openAliceByKeyboard(): Promise<void> {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  const row = within(list).getByText('alice').closest('button') as HTMLElement
  row.focus()
  fireEvent.click(row)
  await screen.findByRole('heading', { level: 2, name: 'alice' })
}

async function renderPage(chats: ChatListItem[] = [aliceRow(), bobRow()]): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue(chats)
  mockChats.getChat.mockResolvedValue(directChatView())
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listContacts.mockResolvedValue(contacts())
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe: () => () => {},
    close: () => {},
  }))
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  // Стаб — own-property создания теста; jsdom-дефолта (нет matchMedia)
  // достаточно восстановить удалением (паттерн T062).
  Reflect.deleteProperty(window, 'matchMedia')
})

describe('burger-drawer — ловушка фокуса и возврат на инициатора (T070, FR-035, SC-008)', () => {
  beforeEach(() => {
    stubViewport(true)
  })

  it('Tab из burger входит в drawer и не покидает сайдбар при многократном Tab', async () => {
    await renderPage()

    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    burger.focus()
    fireEvent.click(burger)
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'true')
    })

    // Первый Tab из burger (вне сайдбара) заводит фокус в drawer.
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Tab' })
    expect(sidebarEl().contains(document.activeElement)).toBe(true)
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Меню' }))

    // Дальнейшие Tab крутятся по кругу ВНУТРИ сайдбара (ловушка FR-035).
    for (let i = 0; i < 8; i += 1) {
      fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Tab' })
      expect(sidebarEl().contains(document.activeElement)).toBe(true)
    }
  })

  it('Tab на последнем элементе сайдбара заворачивает на первый; Shift+Tab на первом — на последний', async () => {
    await renderPage()

    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    burger.focus()
    fireEvent.click(burger)
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'true')
    })

    const list = screen.getByRole('list', { name: 'Список чатов' })
    const last = within(list).getByText('bob').closest('button') as HTMLElement
    last.focus()
    fireEvent.keyDown(last, { key: 'Tab' })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Меню' }))

    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)
  })

  it('закрытый off-canvas сайдбар исключён из табуляции (inert); открытый drawer и широкий экран — без inert', async () => {
    await renderPage()

    // Закрытый drawer на ≤900px: сайдбар за transform-скрытием — невидимые
    // интерактивы не должны участвовать в табуляции (FR-035).
    expect(sidebarEl()).toHaveAttribute('inert')

    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    burger.focus()
    fireEvent.click(burger)
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'true')
    })
    expect(sidebarEl()).not.toHaveAttribute('inert')
  })

  it('модаль над drawer: Tab принадлежит модальной ловушке, фокус не проваливается в drawer', async () => {
    await renderPage()

    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    burger.focus()
    fireEvent.click(burger)
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'true')
    })

    // Клавиатурный вход в модаль из drawer: «Меню» → Enter → стрелка →
    // Enter (вход фокуса — на первый пункт T013, путь пользователя).
    const menuBtn = screen.getByRole('button', { name: 'Меню' })
    menuBtn.focus()
    fireEvent.click(menuBtn)
    const first = await screen.findByRole('menuitem', { name: 'Мой профиль' })
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Enter' })
    const dialog = await screen.findByRole('dialog', { name: 'Контакты' })
    expect(dialog).toContainElement(document.activeElement as HTMLElement)

    // Tab крутится ВНУТРИ модали (ловушка ModalShell T012); drawer ниже
    // не перехватывает и не остаётся без модали (data-model 3.1).
    for (let i = 0; i < 6; i += 1) {
      fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Tab' })
      expect(dialog.contains(document.activeElement)).toBe(true)
    }
    expect(sidebarEl()).toHaveClass('open')
  })

  it('Esc закрывает drawer и возвращает фокус на burger-инициатор', async () => {
    await renderPage()

    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    burger.focus()
    fireEvent.click(burger)
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'true')
    })

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'false')
    })
    expect(document.activeElement).toBe(burger)
  })

  it('выбор чата с клавиатуры закрывает drawer и возвращает фокус на burger', async () => {
    await renderPage()

    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    burger.focus()
    fireEvent.click(burger)
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'true')
    })

    // Строка каталога активирована с клавиатуры (нативная кнопка).
    const list = screen.getByRole('list', { name: 'Список чатов' })
    const row = within(list).getByText('alice').closest('button') as HTMLElement
    row.focus()
    fireEvent.click(row)
    await screen.findByRole('heading', { level: 2, name: 'alice' })
    await waitFor(() => {
      expect(burger).toHaveAttribute('aria-expanded', 'false')
    })
    expect(document.activeElement).toBe(burger)
  })
})

describe('приёмочный проход A–D «без мыши» (quickstart E4, SC-008, FR-035)', () => {
  beforeEach(() => {
    stubViewport(false)
  })

  it('главное меню: Enter открывает, стрелки ходят по кругу, Enter выбирает, Esc возвращает фокус на инициатора', async () => {
    await renderPage()

    // Клавиатурная активация нативной кнопки (платформа Enter/Space).
    const menuBtn = screen.getByRole('button', { name: 'Меню' })
    menuBtn.focus()
    fireEvent.click(menuBtn)

    // Вход фокуса на первый пункт (T013), ArrowDown — к «Контакты».
    const first = await screen.findByRole('menuitem', { name: 'Мой профиль' })
    expect(document.activeElement).toBe(first)
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'Контакты' }))

    // Enter — активация пункта: модаль открывается, фокус входит в форму.
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Enter' })
    const dialog = await screen.findByRole('dialog', { name: 'Контакты' })
    expect(dialog).toContainElement(document.activeElement as HTMLElement)

    // Esc — закрытие модали; фокус возвращается на инициатора (SC-008).
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Контакты' })).toBeNull()
    })
    expect(document.activeElement).toBe(menuBtn)
  })

  it('композер: Enter отправляет с сохранением фокуса в поле; Shift+Enter — не отправляет', async () => {
    // Сервер-копия несёт тот же текст — сходимость 005 не прячет строку.
    mockChats.sendMessage.mockResolvedValue({
      ...makeMessage(DIRECT_ID, 1, ME),
      text: 'привет без мыши',
    })
    await renderPage()
    await openAliceByKeyboard()

    const field = screen.getByLabelText('Текст сообщения')
    field.focus()
    fireEvent.change(field, { target: { value: 'привет без мыши' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    await waitFor(() => {
      expect(mockChats.sendMessage).toHaveBeenCalledTimes(1)
    })
    // Фокус остаётся в поле — можно печатать дальше (T024, quickstart A2).
    expect(document.activeElement).toBe(field)
    await screen.findByText('привет без мыши')

    // Shift+Enter — перенос строки, НЕ отправка (004).
    fireEvent.change(field, { target: { value: 'вторая строка' } })
    fireEvent.keyDown(field, { key: 'Enter', shiftKey: true })
    expect(mockChats.sendMessage).toHaveBeenCalledTimes(1)
  })

  it('инвентарь интерактивов — нативные табл-элементы без tabindex=-1 (Enter/Space — платформа)', async () => {
    mockChats.sendMessage.mockResolvedValue(makeMessage(DIRECT_ID, 1, ME))
    await renderPage()
    await openAliceByKeyboard()

    // Burger (вне .machine) и все контролы машины — нативные
    // button/input/textarea: табуляция и Enter/Space без JS-эмуляции.
    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    expect(burger.tagName).toBe('BUTTON')
    const controls = document.querySelectorAll('.machine button, .machine input, .machine textarea')
    expect(controls.length).toBeGreaterThanOrEqual(7)
    for (const el of Array.from(controls)) {
      expect(el).not.toHaveAttribute('tabindex', '-1')
    }

    // Все ключевые интерактивы A–D достижимы и опознаваемы по роли.
    expect(screen.getByRole('button', { name: 'Меню' }).tagName).toBe('BUTTON')
    expect(screen.getByRole('searchbox', { name: 'Поиск чатов' }).tagName).toBe('INPUT')
    expect(screen.getByRole('button', { name: 'Настройки чата' }).tagName).toBe('BUTTON')
    expect(screen.getByRole('textbox', { name: 'Текст сообщения' }).tagName).toBe('TEXTAREA')
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' }).tagName).toBe('BUTTON')
  })
})
