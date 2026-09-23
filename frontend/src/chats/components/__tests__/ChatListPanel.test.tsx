import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatListItem, Message } from '../../../api/chats'
import { ChatListPanel } from '../ChatListPanel'

/**
 * Left panel of the messenger (US5, T058/T061; FR-013/014/020/021):
 * the «Чаты»/«Контакты» mode switch keeps both sections mounted (list
 * state survives the round trip), rows render the unread badge with
 * the «99+» cap and the blocker-side «заблокирован» mark, the open
 * dialog is highlighted, and after a per-user chat deletion (№14 +
 * useChatList reload) the dropped chat simply disappears from the
 * `chats` prop — down to the empty state when the last dialog is gone.
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

/** Stateful probe for the «Контакты» slot: survives mode switches (FR-013). */
function Probe() {
  const [count, setCount] = useState(0)
  return (
    <button type="button" onClick={() => setCount((value) => value + 1)}>
      probe-{count}
    </button>
  )
}

function panelElement(
  chats: readonly ChatListItem[],
  overrides: Partial<Parameters<typeof ChatListPanel>[0]> = {},
) {
  return <ChatListPanel chats={chats} {...overrides} />
}

afterEach(cleanup)

describe('ChatListPanel mode switching (FR-013)', () => {
  it('defaults to «Чаты» and renders the chat rows', () => {
    render(panelElement([chatItem()]))

    expect(screen.getByRole('tab', { name: 'Чаты' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Контакты' })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByText('alice')).toBeVisible()
  })

  it('keeps both sections mounted: the contacts state survives the round trip', () => {
    const { container } = render(panelElement([chatItem()], { contacts: <Probe /> }))
    const chatsSection = container.querySelector('#chat-panel-section-chats') as HTMLElement
    const contactsSection = container.querySelector('#chat-panel-section-contacts') as HTMLElement
    expect(chatsSection.hasAttribute('hidden')).toBe(false)
    expect(contactsSection.hasAttribute('hidden')).toBe(true)

    fireEvent.click(screen.getByRole('tab', { name: 'Контакты' }))
    expect(screen.getByRole('tab', { name: 'Контакты' })).toHaveAttribute('aria-selected', 'true')
    expect(chatsSection.hasAttribute('hidden')).toBe(true)
    expect(contactsSection.hasAttribute('hidden')).toBe(false)

    fireEvent.click(screen.getByText('probe-0'))
    expect(screen.getByText('probe-1')).toBeVisible()

    fireEvent.click(screen.getByRole('tab', { name: 'Чаты' }))
    expect(chatsSection.hasAttribute('hidden')).toBe(false)
    expect(contactsSection.hasAttribute('hidden')).toBe(true)

    fireEvent.click(screen.getByRole('tab', { name: 'Контакты' }))
    expect(screen.getByText('probe-1')).toBeVisible()
  })
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
