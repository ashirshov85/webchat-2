import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatListItem, Message } from '../../../api/chats'
import { ChatListPanel } from '../ChatListPanel'

/**
 * Left panel of the messenger (US5, T058/T061; FR-013/014/020/021):
 * rows render the unread badge with the «99+» cap and the blocker-side
 * «заблокирован» mark, the open dialog is highlighted, and after a
 * per-user chat deletion (№14 + useChatList reload) the dropped chat
 * simply disappears from the `chats` prop — down to the empty state
 * when the last dialog is gone.
 *
 * Feature 008 (US1, T017): the 004 class hooks of the rows
 * (`.chat-item`, `.chat-item-badge`, `.chat-item-blocked`) stay as
 * wrappers of the prototype row classes (research §C, FR-034); the
 * badge/preview nodes additionally carry the prototype hooks `.c-badge`
 * and `.c-prev` (T019/T020).
 *
 * Feature 008 (US2, T027; FR-006/007, research §D, ui-behavior §2):
 * the «Чаты»/«Контакты» tabs are GONE — the chat list renders right
 * away and contacts live in a modal (T028/T031). The search row hosts
 * the MainMenuButton (T030): the «Меню» button opens the ContextMenu
 * with «Мой профиль» / «Контакты» / «Создать групповой чат»; a repeated
 * click, an outside click and Escape close it. Selecting an item opens
 * the matching ModalShell form hosted by MessengerPage (T034) through
 * the `onOpenProfile` / `onOpenContacts` / `onCreateGroup` callbacks —
 * the 004 reachability of every operation is preserved, only the entry
 * point changes (FR-034, SC-004).
 */

const ME = '11111111-1111-1111-1111-111111111111'
const PEER_A = '22222222-2222-2222-2222-222222222222'
const PEER_B = '33333333-3333-3333-3333-333333333333'

function peer(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function lastMessage(chatId: string, id: string, senderId: string, text: string): Message {
  return { id, chatId, senderId, text, seq: 10, createdAt: '2026-09-20T12:00:00.000Z' }
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

/** Menu items → single ModalShell forms of MessengerPage (T034, ui-behavior §3). */
const MENU_ITEM_TO_PROP = [
  { label: 'Мой профиль', prop: 'onOpenProfile' },
  { label: 'Контакты', prop: 'onOpenContacts' },
  { label: 'Создать групповой чат', prop: 'onCreateGroup' },
] as const

function panelElement(
  chats: readonly ChatListItem[],
  overrides: Partial<Parameters<typeof ChatListPanel>[0]> = {},
) {
  return <ChatListPanel chats={chats} {...overrides} />
}

afterEach(cleanup)

describe('ChatListPanel without tabs (FR-006)', () => {
  it('renders the chat list right away — no «Чаты»/«Контакты» mode tabs at all', () => {
    render(panelElement([chatItem()]))

    expect(screen.queryByRole('tablist')).toBeNull()
    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.getByText('alice')).toBeVisible()
    // Контакты больше не секция сайдбара — их дом теперь модаль (T028/T031).
    expect(screen.queryByText('Контакты появятся здесь')).toBeNull()
  })
})

describe('ChatListPanel main menu (FR-007, ui-behavior §2)', () => {
  it('opens the «Меню» button menu with the three prototype items', () => {
    const { container } = render(panelElement([chatItem()]))
    const menuButton = screen.getByRole('button', { name: 'Меню' })
    // T030: кнопка едет в прототипном классе `.menu-btn` строки поиска.
    expect(menuButton).toHaveClass('menu-btn')
    expect(menuButton.closest('.search-row')).toBe(container.querySelector('.search-row'))

    expect(screen.queryByRole('menu')).toBeNull()
    fireEvent.click(menuButton)

    const items = screen.getAllByRole('menuitem')
    expect(items.map((item) => item.textContent)).toEqual([
      'Мой профиль',
      'Контакты',
      'Создать групповой чат',
    ])
  })

  it('closes the menu by a repeated button click, an outside click and Escape', () => {
    render(panelElement([chatItem()]))
    const menuButton = screen.getByRole('button', { name: 'Меню' })

    fireEvent.click(menuButton)
    expect(screen.getByRole('menu')).toBeVisible()

    fireEvent.click(menuButton)
    expect(screen.queryByRole('menu')).toBeNull()

    fireEvent.click(menuButton)
    expect(screen.getByRole('menu')).toBeVisible()
    fireEvent.click(document.body)
    expect(screen.queryByRole('menu')).toBeNull()

    fireEvent.click(menuButton)
    expect(screen.getByRole('menu')).toBeVisible()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
  })

  for (const { label, prop } of MENU_ITEM_TO_PROP) {
    it(`«${label}» opens the matching modal form and closes the menu`, () => {
      const handlers = {
        onOpenProfile: vi.fn(),
        onOpenContacts: vi.fn(),
        onCreateGroup: vi.fn(),
      }
      render(panelElement([chatItem()], handlers))

      fireEvent.click(screen.getByRole('button', { name: 'Меню' }))
      fireEvent.click(screen.getByRole('menuitem', { name: label }))

      expect(screen.queryByRole('menu')).toBeNull()
      expect(handlers[prop]).toHaveBeenCalledTimes(1)
      // Ровно одна форма на действие пункта — остальные не трогаются.
      for (const other of MENU_ITEM_TO_PROP) {
        if (other.prop !== prop) {
          expect(handlers[other.prop]).not.toHaveBeenCalled()
        }
      }
    })
  }
})

describe('ChatListPanel unread badge (FR-014)', () => {
  it('renders the exact server count up to 99', () => {
    const { container } = render(
      panelElement([
        chatItem({ peer: peer(PEER_A, 'alice'), unreadCount: 5 }),
        chatItem({ chatId: 'chat-2', peer: peer(PEER_B, 'bob'), unreadCount: 99 }),
      ]),
    )
    const badges = Array.from(container.querySelectorAll('.chat-item-badge'))

    expect(badges.map((badge) => badge.textContent)).toEqual(['5', '99'])
    // T020: the badge node rides the prototype `.c-badge` hook.
    expect(badges[0]).toHaveClass('c-badge')
  })

  it('collapses into «99+» above ninety-nine and hides the badge at zero', () => {
    const { container } = render(
      panelElement([
        chatItem({ peer: peer(PEER_A, 'alice'), unreadCount: 100 }),
        chatItem({ chatId: 'chat-2', peer: peer(PEER_B, 'bob'), unreadCount: 1000 }),
        chatItem({ chatId: 'chat-3', peer: peer(PEER_B, 'carol'), unreadCount: 0 }),
      ]),
    )
    const rows = Array.from(container.querySelectorAll('.chat-item'))
    const badgeTexts = rows.map((row) => row.querySelector('.chat-item-badge')?.textContent ?? null)

    expect(badgeTexts).toEqual(['99+', '99+', null])
  })
})

describe('ChatListPanel blocked mark (FR-020)', () => {
  it('renders «заблокирован» only on the blocker side rows', () => {
    const { container } = render(
      panelElement([
        chatItem({ peer: peer(PEER_A, 'alice'), blockedByMe: true }),
        chatItem({ chatId: 'chat-2', peer: peer(PEER_B, 'bob'), blockedByMe: false }),
      ]),
    )
    const rows = Array.from(container.querySelectorAll('.chat-item'))

    expect(rows[0]?.querySelector('.chat-item-blocked')?.textContent).toBe('заблокирован')
    expect(rows[1]?.querySelector('.chat-item-blocked')).toBeNull()
  })
})

describe('ChatListPanel rows and the open dialog', () => {
  it('highlights the open chat row and opens a dialog on click', () => {
    const onSelectChat = vi.fn()
    const { container } = render(
      panelElement(
        [
          chatItem({ peer: peer(PEER_A, 'alice') }),
          chatItem({ chatId: 'chat-2', peer: peer(PEER_B, 'bob') }),
        ],
        { activeChatId: 'chat-2', onSelectChat, currentUserId: ME },
      ),
    )
    const rows = Array.from(container.querySelectorAll('.chat-item'))

    expect(rows[1]?.getAttribute('aria-current')).toBe('true')
    expect(rows[0]?.getAttribute('aria-current')).toBeNull()

    fireEvent.click(rows[0] as HTMLElement)
    expect(onSelectChat).toHaveBeenCalledWith('chat-1')
  })

  it('prefixes the outgoing last message preview with «Вы:»', () => {
    render(
      panelElement(
        [
          chatItem({
            lastMessage: lastMessage('chat-1', 'm-1', ME, 'привет'),
          }),
        ],
        { currentUserId: ME },
      ),
    )

    // T020: the preview node rides the prototype `.c-prev` hook.
    expect(screen.getByText('Вы: привет')).toHaveClass('c-prev')
    expect(screen.getByText('Вы: привет')).toBeVisible()
  })
})

describe('ChatListPanel after chat deletion (FR-021)', () => {
  it('drops only the deleted chat row and shows the empty state when the last dialog is gone', () => {
    const { container, rerender } = render(
      panelElement([
        chatItem({ peer: peer(PEER_A, 'alice') }),
        chatItem({ chatId: 'chat-2', peer: peer(PEER_B, 'bob') }),
      ]),
    )

    rerender(panelElement([chatItem({ chatId: 'chat-2', peer: peer(PEER_B, 'bob') })]))
    expect(container.querySelectorAll('.chat-item')).toHaveLength(1)
    expect(screen.queryByText('alice')).toBeNull()
    expect(screen.getByText('bob')).toBeVisible()

    rerender(panelElement([]))
    expect(container.querySelectorAll('.chat-item')).toHaveLength(0)
    expect(screen.getByText('Диалогов пока нет')).toBeVisible()
  })

  it('renders the list failure with a retry wired to onReload', () => {
    const onReload = vi.fn()
    render(
      panelElement([], {
        status: 'error',
        error: { status: 500, title: 'Internal Server Error' },
        onReload,
      }),
    )

    expect(screen.getByText('Internal Server Error')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(onReload).toHaveBeenCalledTimes(1)
  })

  it('renders the loading state while №12 is in flight', () => {
    render(panelElement([], { status: 'loading' }))

    expect(screen.getByText('Загрузка чатов…')).toBeVisible()
  })
})
