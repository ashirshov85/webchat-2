import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatListItem as ChatListItemData, Message } from '../../../api/chats'
import { ChatListItem } from '../ChatListItem'

/**
 * One «Чаты» row over the №12 group elements (US1, T019 → T028;
 * FR-014): the unified list discriminates rows by `type` — a group
 * row renders `title` + `memberCount` from the group fields while
 * `peer`/`blockedByMe` are null (api-contract.md §3), and a direct
 * row keeps rendering the peer login exactly as in 004 (the `type`
 * field may be absent — backward-friendly). The unread badge rules
 * of 004 (exact up to 99, «99+» above) apply to group rows alike.
 */

const ME = '11111111-1111-1111-1111-111111111111'
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

function lastMessage(chatId: string, id: string, senderId: string, text: string): Message {
  return { id, chatId, senderId, text, seq: 10, createdAt: '2026-09-20T12:00:00.000Z' }
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
    myRole: 'owner',
    peer: null,
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: null,
    ...overrides,
  }
}

afterEach(cleanup)

describe('ChatListItem type discrimination (№12 group elements, FR-014)', () => {
  it('renders a group row from the group fields: title, member counter, no peer login', () => {
    render(
      <ul>
        <ChatListItem item={groupItem()} />
      </ul>,
    )

    expect(screen.getByText('Проект Альфа')).toBeVisible()
    expect(screen.queryByText('alice')).toBeNull()
    expect(screen.queryByText('заблокирован')).toBeNull()
  })

  it('renders the member counter with Russian plurals', () => {
    const { container } = render(
      <ul>
        <ChatListItem item={groupItem({ memberCount: 1 })} />
        <ChatListItem item={groupItem({ chatId: 'group-2', memberCount: 3 })} />
        <ChatListItem item={groupItem({ chatId: 'group-3', memberCount: 5 })} />
      </ul>,
    )
    const counters = Array.from(container.querySelectorAll('.chat-item-members'))

    expect(counters.map((counter) => counter.textContent)).toEqual([
      '1 участник',
      '3 участника',
      '5 участников',
    ])
  })

  it('keeps direct rows peer-driven and without a member counter (type absent or direct)', () => {
    const { container } = render(
      <ul>
        <ChatListItem item={directItem()} />
        <ChatListItem
          item={directItem({ chatId: 'chat-2', type: 'direct', peer: peer(ME, 'bob') })}
        />
      </ul>,
    )

    expect(screen.getByText('alice')).toBeVisible()
    expect(screen.getByText('bob')).toBeVisible()
    expect(container.querySelector('.chat-item-members')).toBeNull()
  })
})

describe('ChatListItem group unread badge (FR-014)', () => {
  it('caps the group badge at «99+» and hides it at zero', () => {
    const { container } = render(
      <ul>
        <ChatListItem item={groupItem({ unreadCount: 120 })} />
        <ChatListItem item={groupItem({ chatId: 'group-2', unreadCount: 5 })} />
        <ChatListItem item={groupItem({ chatId: 'group-3', unreadCount: 0 })} />
      </ul>,
    )
    const rows = Array.from(container.querySelectorAll('.chat-item'))
    const badgeTexts = rows.map((row) => row.querySelector('.chat-item-badge')?.textContent ?? null)

    expect(badgeTexts).toEqual(['99+', '5', null])
  })
})

describe('ChatListItem group row actions', () => {
  it('prefixes the own outgoing group message and opens the group on click', () => {
    const onSelect = vi.fn()
    render(
      <ul>
        <ChatListItem
          item={groupItem({ lastMessage: lastMessage(GROUP_ID, 'm-1', ME, 'привет всем') })}
          currentUserId={ME}
          onSelect={onSelect}
        />
      </ul>,
    )

    expect(screen.getByText('Вы: привет всем')).toBeVisible()

    fireEvent.click(screen.getByRole('button'))
    expect(onSelect).toHaveBeenCalledWith(GROUP_ID)
  })
})
