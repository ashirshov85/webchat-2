import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatListItem as ChatListItemData, Message } from '../../../api/chats'
import { ChatListItem } from '../ChatListItem'

/**
 * One «Чаты» row over the №12 group elements (US1, T019 → T028;
 * FR-014): the unified list discriminates rows by `type` — a group
 * row renders `title` from the group fields while `peer`/
 * `blockedByMe` are null (api-contract.md §3), and a direct row keeps
 * rendering the peer login exactly as in 004 (the `type` field may be
 * absent — backward-friendly). The unread badge rules of 004 (exact up
 * to 99, «99+» above) apply to group rows alike.
 *
 * Feature 008 (US1, T017 → T020): the row is rebuilt per the
 * prototype — avatar + `.c-name` + preview + time + badge. The №12
 * `memberCount` LEAVES the row (the prototype carries no counter —
 * FR-001) and returns as the «N участников» header status of US3
 * (T043); the 007 presence dot leaves the row markup until the Avatar
 * presence wiring of US3 (T040/T042). Neither is a row expectation
 * anymore (data-model 2.1).
 *
 * T020 row shape (FR-008, data-model 2.1): the avatar derives from
 * `title` (octagon) or `peer.username` (circle) via ui/Avatar; the
 * `.c-time` node carries the ЧЧ:ММ of `lastMessage.createdAt` and
 * renders EMPTY for a messageless chat; the truncated `.c-name`/
 * `.c-prev` nodes carry the full texts in `title` tooltips (spec
 * edge-cases). An INCOMING group message renders WITHOUT the «Имя: »
 * prefix of FR-008: №12's `lastMessage` carries `senderId` only, the
 * roster lives in №28 of the OPEN chat alone, SC-003 forbids contract
 * changes and the panel issues no per-chat requests (004) — the
 * name-prefixed preview stays the feed's privilege (T022, members
 * prop); outgoing keeps the 004 «Вы: » prefix everywhere.
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

function lastMessage(
  chatId: string,
  id: string,
  senderId: string,
  text: string,
  createdAt: string = '2026-09-20T12:00:00.000Z',
): Message {
  return { id, chatId, senderId, text, seq: 10, createdAt }
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
  it('renders a group row from the group fields: title, no peer login', () => {
    render(
      <ul>
        <ChatListItem item={groupItem()} />
      </ul>,
    )

    // T020: the title rides the prototype `.c-name` hook.
    expect(screen.getByText('Проект Альфа')).toHaveClass('c-name')
    expect(screen.queryByText('alice')).toBeNull()
    expect(screen.queryByText('заблокирован')).toBeNull()
  })

  it('keeps direct rows peer-driven (type absent or direct)', () => {
    render(
      <ul>
        <ChatListItem item={directItem()} />
        <ChatListItem
          item={directItem({ chatId: 'chat-2', type: 'direct', peer: peer(ME, 'bob') })}
        />
      </ul>,
    )

    expect(screen.getByText('alice')).toBeVisible()
    expect(screen.getByText('bob')).toBeVisible()
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

describe('ChatListItem prototype row rebuild (T020, FR-008, data-model 2.1)', () => {
  it('renders the group row: octagon avatar from title, .c-main/.c-top structure, ЧЧ:ММ time', () => {
    // Local-wall-clock instant → the ЧЧ:ММ expectation is timezone-safe.
    const createdAt = new Date(2026, 8, 19, 12, 30).toISOString()
    const { container } = render(
      <ul>
        <ChatListItem
          item={groupItem({
            lastMessage: lastMessage(GROUP_ID, 'm-1', PEER_A, 'привет всем', createdAt),
          })}
        />
      </ul>,
    )
    const row = container.querySelector('.chat-item') as HTMLElement

    // Avatar: octagon of the group title, initials «ПА» (FR-024).
    expect(row.querySelector('.avatar.oct')).not.toBeNull()
    expect(row.querySelector('.avatar .av-in span')?.textContent).toBe('ПА')

    // Prototype structure: .c-main > .c-top (.c-name + .c-time) + .c-prev.
    expect(row.querySelector('.c-main .c-top .c-name')?.textContent).toBe('Проект Альфа')
    expect(row.querySelector('.c-time')?.textContent).toBe('12:30')

    // An incoming group message renders bare — №12 carries senderId only
    // (no «Имя: » prefix source; the header comment spells out the
    // SC-003/004 constraints).
    expect(row.querySelector('.c-prev')?.textContent).toBe('привет всем')
  })

  it('renders the direct row: circle avatar from peer username, truncated name with a title tooltip', () => {
    const { container } = render(
      <ul>
        <ChatListItem item={directItem({ peer: peer(PEER_A, 'alice') })} />
      </ul>,
    )
    const row = container.querySelector('.chat-item') as HTMLElement

    expect(row.querySelector('.avatar:not(.oct)')).not.toBeNull()
    expect(row.querySelector('.avatar .av-in span')?.textContent).toBe('A')
    expect(row.querySelector('.c-name')?.textContent).toBe('alice')
    // Truncation is CSS-side; the full name rides the title tooltip (spec
    // edge-case «полный текст — во всплывающей подсказке»).
    expect(row.querySelector('.c-name')?.getAttribute('title')).toBe('alice')

    // Messageless chat: EMPTY time node (prototype `${last?last.time:''}`)
    // and the muted «Нет сообщений» preview.
    expect(row.querySelector('.c-time')?.textContent).toBe('')
    expect(row.querySelector('.c-prev')?.textContent).toBe('Нет сообщений')
  })

  it('carries the full preview text in the .c-prev tooltip', () => {
    const longText = 'а'.repeat(80)
    const { container } = render(
      <ul>
        <ChatListItem
          item={directItem({
            lastMessage: lastMessage('chat-1', 'm-1', ME, longText),
          })}
          currentUserId={ME}
        />
      </ul>,
    )
    const preview = container.querySelector('.c-prev') as HTMLElement

    // The DOM text stays clamped (№12 client render decision, 004).
    expect(preview.textContent).toBe(`Вы: ${'а'.repeat(64)}…`)
    // The tooltip carries the FULL text (spec edge-case).
    expect(preview.getAttribute('title')).toBe(`Вы: ${longText}`)
  })
})
