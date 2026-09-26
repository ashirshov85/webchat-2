import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { ChatListItem as ChatListItemData } from '../../../api/chats'
import { ChatListPanel } from '../ChatListPanel'

/**
 * Left panel over the UNIFIED №12 list (US1, T019 → T028; FR-014):
 * direct dialogs and groups render in one «Чаты» list — a group row
 * shows its `title`, a direct row the peer login — and the search
 * field filters the rows live by the group title (case-insensitive)
 * or the peer login. The server keeps owning the ordering; the panel
 * only filters what it already renders (no extra requests).
 */

const PEER_A = '22222222-2222-2222-2222-222222222222'
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

function directItem(overrides: Partial<ChatListItemData> = {}): ChatListItemData {
  return {
    chatId: 'chat-1',
    peer: peer(PEER_A, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
    ...overrides,
  }
}

function groupItem(overrides: Partial<ChatListItemData> = {}): ChatListItemData {
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

function search(query: string) {
  fireEvent.change(screen.getByRole('searchbox', { name: 'Поиск чатов' }), {
    target: { value: query },
  })
}

afterEach(cleanup)

describe('ChatListPanel unified list (FR-014)', () => {
  it('renders direct and group rows in one «Чаты» list', () => {
    const { container } = render(<ChatListPanel chats={[directItem(), groupItem()]} />)

    expect(screen.getByText('alice')).toBeVisible()
    expect(screen.getByText('Проект Альфа')).toBeVisible()

    const list = container.querySelector('.chat-list') as HTMLElement
    expect(list.querySelectorAll('.chat-item')).toHaveLength(2)
  })
})

describe('ChatListPanel search by title (FR-014)', () => {
  it('finds a group by its title case-insensitively and restores the list when cleared', () => {
    render(<ChatListPanel chats={[directItem(), groupItem()]} />)

    search('проект')
    expect(screen.getByText('Проект Альфа')).toBeVisible()
    expect(screen.queryByText('alice')).toBeNull()

    search('')
    expect(screen.getByText('Проект Альфа')).toBeVisible()
    expect(screen.getByText('alice')).toBeVisible()
  })

  it('finds a direct dialog by the peer login', () => {
    render(<ChatListPanel chats={[directItem(), groupItem()]} />)

    search('alice')
    expect(screen.getByText('alice')).toBeVisible()
    expect(screen.queryByText('Проект Альфа')).toBeNull()
  })

  it('renders zero rows when nothing matches', () => {
    const { container } = render(<ChatListPanel chats={[directItem(), groupItem()]} />)

    search('нет такого чата')
    expect(container.querySelectorAll('.chat-item')).toHaveLength(0)
  })
})
