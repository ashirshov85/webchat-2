import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatListItem, ContactView } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * T062 — тесты burger-drawer (US5; FR-029, data-model 3.1, ui-behavior
 * §7, design-tokens §9): на узком экране (≤900px) сайдбар — выдвижной
 * drawer поверх контента с затемнением. Машина состояний прототипа
 * (openSidebar/closeSidebar, design/chats.html §Мобильный сайдбар):
 *
 *   closed → open (burger) → closed (выбор чата / клик по затемнению /
 *   повторный burger / Esc, если выше нет слоёв).
 *
 * Хуки DOM дословно из прототипа: `.burger` (морф-индикатор `.open`),
 * `.sidebar.open`, `.backdrop.show` — тот же словарь, что у визуальной
 * регрессии T016(б)/T068 (`.sidebar.open` / `.backdrop.show`). Esc-слои
 * (data-model 3.1): ctx-menu/members-tip → модаль → drawer — Esc
 * закрывает строго верхний активный слой, drawer закрывается Esc'ом
 * только когда модалей выше нет (модальная оболочка слушает Esc на
 * document без stopPropagation — охрана «нет слоёв выше» на стороне
 * drawer). Контрольная точка ≤900px: jsdom не умеет media-запросы,
 * viewport стабится window.matchMedia (запросы обоих стилей —
 * max-width:900px / min-width:901px — сводятся к одному ответу);
 * скрытие burger на ≥901px — CSS-паттерн прототипа (`.burger{display:
 * none}` вне media-блока), его проверяет визуальная регрессия T068 и
 * quickstart E, не jsdom. Тесты TDD-красные до реализации T065
 * (burger/backdrop в разметке страницы ещё нет).
 */

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

function contacts(): ContactView[] {
  return [
    { user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z' },
    { user: peer(BOB, 'bob'), createdAt: '2026-09-03T00:00:00.000Z' },
  ]
}

/** jsdom не реализует matchMedia: стаб экрана ≤900px (design-tokens §9). */
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

/** Drawer открыт: классы openSidebar прототипа на месте. */
function expectDrawerOpen(): void {
  const sidebar = document.querySelector('.sidebar')
  expect(sidebar).not.toBeNull()
  expect(sidebar).toHaveClass('open')
  const backdrop = document.querySelector('.backdrop')
  expect(backdrop).not.toBeNull()
  expect(backdrop).toHaveClass('show')
}

/** Drawer закрыт: классы сняты (подложка может жить в DOM — паттерн ModalShell). */
function expectDrawerClosed(): void {
  const sidebar = document.querySelector('.sidebar')
  expect(sidebar).not.toBeNull()
  expect(sidebar).not.toHaveClass('open')
  const backdrop = document.querySelector('.backdrop')
  if (backdrop !== null) {
    expect(backdrop).not.toHaveClass('show')
  }
}

/** Burger (прототип #burger, title «Directory» → русская строка) открывает drawer. */
async function openDrawer(): Promise<HTMLElement> {
  const burger = screen.getByRole('button', { name: 'Каталог чатов' })
  fireEvent.click(burger)
  await waitFor(() => {
    expectDrawerOpen()
  })
  return burger
}

async function renderPage(chats: ChatListItem[] = [aliceRow(), bobRow()]): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue(chats)
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

beforeEach(() => {
  stubViewport(true)
})

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  // Стаб — own-property создания теста; jsdom-дефолта (нет matchMedia)
  // достаточно восстановить удалением.
  Reflect.deleteProperty(window, 'matchMedia')
})

describe('MessengerPage burger-drawer ≤900px (T062, FR-029, data-model 3.1)', () => {
  it('burger открывает drawer с затемнением — классы прототипа и aria-expanded', async () => {
    await renderPage()

    const burger = screen.getByRole('button', { name: 'Каталог чатов' })
    expect(burger).toHaveAttribute('aria-expanded', 'false')
    expectDrawerClosed()

    fireEvent.click(burger)

    // openSidebar прототипа: .sidebar.open + .backdrop.show + .burger.open.
    expectDrawerOpen()
    expect(burger).toHaveClass('open')
    expect(burger).toHaveAttribute('aria-expanded', 'true')
  })

  it('повторный burger закрывает drawer; цикл замыкается заново', async () => {
    await renderPage()
    const burger = await openDrawer()

    fireEvent.click(burger)
    await waitFor(() => {
      expectDrawerClosed()
    })
    expect(burger).not.toHaveClass('open')
    expect(burger).toHaveAttribute('aria-expanded', 'false')

    // closed → open снова: состояние не «залипает» (машина 3.1).
    fireEvent.click(burger)
    await waitFor(() => {
      expectDrawerOpen()
    })
  })

  it('клик по затемнению закрывает drawer', async () => {
    await renderPage()
    await openDrawer()

    fireEvent.click(document.querySelector('.backdrop') as HTMLElement)

    await waitFor(() => {
      expectDrawerClosed()
    })
  })

  it('выбор чата закрывает drawer и открывает окно переписки', async () => {
    await renderPage()
    await openDrawer()

    const list = screen.getByRole('list', { name: 'Список чатов' })
    fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
    await screen.findByRole('heading', { level: 2, name: 'alice' })

    // closeSidebar прототипа едет вместе с selectChat (строка кликнута
    // из drawer — окно открывается, затемнение уходит).
    await waitFor(() => {
      expectDrawerClosed()
    })
  })

  it('Esc закрывает drawer, когда выше нет слоёв', async () => {
    await renderPage()
    await openDrawer()

    fireEvent.keyDown(document, { key: 'Escape' })

    await waitFor(() => {
      expectDrawerClosed()
    })
  })

  it('Esc закрывает строго верхний слой: модаль раньше drawer (data-model 3.1)', async () => {
    await renderPage()
    await openDrawer()

    // Модаль НАД drawer: главное меню сайдбара → «Контакты» (T030/T034).
    // openForm прототипа сайдбар не трогает — drawer остаётся под модалью.
    fireEvent.click(screen.getByRole('button', { name: 'Меню' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Контакты' }))
    await screen.findByRole('dialog', { name: 'Контакты' })
    expectDrawerOpen()

    // Первый Esc — ТОЛЬКО модаль; drawer не проваливается (3.1).
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Контакты' })).toBeNull()
    })
    expectDrawerOpen()

    // Второй Esc — drawer (модалей выше больше нет).
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expectDrawerClosed()
    })
  })
})
