import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { listContacts } from '../../../api/chats'
import type { ChatListItem as ChatListItemData, ContactView } from '../../../api/chats'
import type { PublicUser } from '../../../api/auth'
import { ToastProvider } from '../../../ui/Toast'
import { ChatHeader } from '../ChatHeader'
import { ChatListItem } from '../ChatListItem'
import { ContactsModal } from '../ContactsModal'

/**
 * Presence-отображение US3 (feature 008, T040; US3-AS2, FR-024, FR-016,
 * data-model 1.2/2.1/2.3/2.5, семантика 007): точка online/offline/
 * unknown на АВАТАРАХ поверхностей-007 (строки личных чатов списка,
 * строки «Контактов») и статус ЗАГОЛОВКА личного чата — «В сети» либо
 * нейтральное «неизвестно», без ложного «офлайна».
 *
 * Тесты написаны ДО реализации (конституция VI): красные до T042
 * (presence-точки в ChatListItem/ContactsModal) и T043 (лампа+текст
 * статуса ChatHeader). Контракт фиксирован здесь:
 *
 * - источник статуса — ТОЛЬКО presenceStore 007 через
 *   usePresenceStatus(userId): строка/контакт/заголовок не ведут своих
 *   запросов; per-peer ключ — peer.id / contact.user.id / chat.peerId;
 * - СООТВЕТСТВИЕ СОСТОЯНИЮ: online → зелёная мерцающая (базовый
 *   `.av-dot`, лампа без `.off`), offline → тусклая (`.av-dot.off`,
 *   `.lamp.off`), unknown → нейтральная (`.av-dot.unknown`, лампа БЕЗ
 *   `.off`) — design-tokens §4;
 * - «неизвестно» ≠ «офлайн» (edge case 007): отсутствие/задержка
 *   снимка №36 — нейтральная точка и текст «неизвестно», ложный
 *   «офлайн» (класс .off / текст) недопустим до получения данных;
 *   настоящий offline из store — «офлайн» отображается (не «ложный»);
 * - текст статуса заголовка (прототип §7 statusRow): «В сети» /
 *   «офлайн» / «неизвестно» в `.status-txt` рядом с лампой — видимый
 *   лейбл 007 (a11y-clarify: точка + подпись);
 * - группы присутствия не несут (006/007): presence-точки на аватаре
 *   групповой строки нет.
 *
 * usePresence замокан хранилищем- Map<userId, status>: «unknown» по
 * умолчанию = свежая поверхность до первого №36 (семантика 007).
 */

const presence = vi.hoisted(() => ({
  statuses: new Map<string, 'online' | 'offline' | 'unknown'>(),
}))

vi.mock('../../../presence/usePresence', () => ({
  usePresenceStatus: (userId: string | null | undefined) =>
    (userId === null || userId === undefined ? undefined : presence.statuses.get(userId)) ??
    'unknown',
  usePresenceSurfaces: () => {},
}))

vi.mock('../../../api/chats', () => ({
  listContacts: vi.fn(),
  ensureChat: vi.fn(),
  removeContact: vi.fn(),
  searchUsers: vi.fn(),
  addContact: vi.fn(),
  blockUser: vi.fn(),
  unblockUser: vi.fn(),
  deleteChat: vi.fn(),
}))

const mockedListContacts = vi.mocked(listContacts)

const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const CAROL = '44444444-4444-4444-4444-444444444444'
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
    chatId: 'chat-alice',
    peer: peer(ALICE, 'alice'),
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

function publicUser(id: string, username: string): PublicUser {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active',
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function contactView(id: string, username: string): ContactView {
  return { user: publicUser(id, username), createdAt: '2026-09-10T00:00:00.000Z' }
}

const CONTACTS = [
  contactView(ALICE, 'alice'),
  contactView(BOB, 'Борис'),
  contactView(CAROL, 'анна'),
]

function renderRows(items: readonly ChatListItemData[]) {
  return render(
    <ul>
      {items.map((item) => (
        <ChatListItem key={item.chatId} item={item} />
      ))}
    </ul>,
  )
}

/** Строка списка чатов по имени (прототипный хук .c-name). */
function chatRowOf(container: HTMLElement, name: string): HTMLElement {
  const row = Array.from(container.querySelectorAll<HTMLElement>('.chat-item')).find(
    (node) => node.querySelector('.c-name')?.textContent === name,
  )
  if (row === undefined) {
    throw new Error(`строка чата не найдена: ${name}`)
  }
  return row
}

/** Presence-точка на аватаре строки (контракт T042 — точка обязана быть). */
function dotOf(row: HTMLElement): HTMLElement {
  const dot = row.querySelector<HTMLElement>('.avatar .av-dot')
  if (dot === null) {
    throw new Error('presence-точка не найдена на аватаре строки')
  }
  return dot
}

afterEach(() => {
  cleanup()
  presence.statuses.clear()
  mockedListContacts.mockReset()
})

describe('ChatListItem presence dot (T040 → T042, US3-AS2, FR-024, data-model 2.1)', () => {
  it('direct row online: зелёная мерцающая точка — базовый .av-dot с aria «онлайн»', () => {
    presence.statuses.set(ALICE, 'online')
    const { container } = renderRows([directItem()])

    const row = chatRowOf(container, 'alice')
    const dot = dotOf(row)
    expect(dot.className).toBe('av-dot')
    expect(within(row).getByRole('img', { name: 'онлайн' })).toBe(dot)
  })

  it('direct row offline: тусклая точка .av-dot.off с aria «офлайн»', () => {
    presence.statuses.set(ALICE, 'offline')
    const { container } = renderRows([directItem()])

    const row = chatRowOf(container, 'alice')
    const dot = dotOf(row)
    expect(dot.className).toBe('av-dot off')
    expect(within(row).getByRole('img', { name: 'офлайн' })).toBe(dot)
  })

  it('direct row unknown: нейтральная .av-dot.unknown — БЕЗ ложного «офлайн» (.off)', () => {
    presence.statuses.set(ALICE, 'unknown')
    const { container } = renderRows([directItem()])

    const row = chatRowOf(container, 'alice')
    const dot = dotOf(row)
    expect(dot.className).toBe('av-dot unknown')
    expect(row.querySelector('.av-dot.off')).toBeNull()
    expect(within(row).getByRole('img', { name: 'неизвестно' })).toBe(dot)
  })

  it('строка без записи в store (до первого №36): нейтральная точка, не .off — семантика 007', () => {
    const { container } = renderRows([directItem()])

    const row = chatRowOf(container, 'alice')
    expect(dotOf(row).className).toBe('av-dot unknown')
    expect(row.querySelector('.av-dot.off')).toBeNull()
  })

  it('точки per-peer: статусы разных собеседников не смешиваются (ключ — peer.id)', () => {
    presence.statuses.set(ALICE, 'online')
    presence.statuses.set(BOB, 'offline')
    const { container } = renderRows([
      directItem(),
      directItem({ chatId: 'chat-bob', peer: peer(BOB, 'bob') }),
    ])

    expect(dotOf(chatRowOf(container, 'alice')).className).toBe('av-dot')
    expect(dotOf(chatRowOf(container, 'bob')).className).toBe('av-dot off')
  })

  it('групповая строка: presence-точки нет (006/007 — группы присутствия не несут)', () => {
    presence.statuses.set(ALICE, 'online')
    const { container } = renderRows([groupItem()])

    expect(chatRowOf(container, 'Проект Альфа').querySelector('.avatar .av-dot')).toBeNull()
  })
})

describe('ContactsModal presence dots (T040 → T042, US3-AS2, FR-024, data-model 2.5)', () => {
  beforeEach(() => {
    mockedListContacts.mockResolvedValue(CONTACTS)
  })

  /** Строка «Контактов» по имени (прототипный хук .ctc-row > .c-name). */
  function contactRowOf(container: HTMLElement, username: string): HTMLElement {
    const row = Array.from(container.querySelectorAll<HTMLElement>('.ctc-row')).find(
      (node) => node.querySelector('.c-name')?.textContent === username,
    )
    if (row === undefined) {
      throw new Error(`строка контакта не найдена: ${username}`)
    }
    return row
  }

  async function renderContacts() {
    const view = render(
      <ToastProvider>
        <ContactsModal chats={[]} onOpenChat={vi.fn()} />
      </ToastProvider>,
    )
    await waitFor(() => {
      expect(view.container.querySelectorAll('.ctc-row')).toHaveLength(CONTACTS.length)
    })
    return view.container
  }

  it('точки per-contact: online/offline/unknown соответствуют статусам contact.user.id', async () => {
    presence.statuses.set(ALICE, 'online')
    presence.statuses.set(BOB, 'offline')
    presence.statuses.set(CAROL, 'unknown')
    const container = await renderContacts()

    const aliceRow = contactRowOf(container, 'alice')
    const bobRow = contactRowOf(container, 'Борис')
    const carolRow = contactRowOf(container, 'анна')

    expect(dotOf(aliceRow).className).toBe('av-dot')
    expect(within(aliceRow).getByRole('img', { name: 'онлайн' })).toBeVisible()
    expect(dotOf(bobRow).className).toBe('av-dot off')
    expect(within(bobRow).getByRole('img', { name: 'офлайн' })).toBeVisible()
    expect(dotOf(carolRow).className).toBe('av-dot unknown')
    expect(within(carolRow).getByRole('img', { name: 'неизвестно' })).toBeVisible()
  })

  it('список до первого №36: ВСЕ точки нейтральные — ни одной ложной .off (семантика 007)', async () => {
    const container = await renderContacts()

    for (const row of Array.from(container.querySelectorAll<HTMLElement>('.ctc-row'))) {
      expect(dotOf(row).className).toBe('av-dot unknown')
      expect(row.querySelector('.av-dot.off')).toBeNull()
    }
  })
})

describe('ChatHeader direct status (T040 → T043, US3-AS2, FR-016, data-model 2.3)', () => {
  function renderDirectHeader(peerId: string = ALICE) {
    return render(
      <ChatHeader
        chat={{ kind: 'direct', peerId, username: 'alice', blockedByMe: false }}
        menuOpen={false}
        onToggleMenu={vi.fn()}
        onDeleteChat={vi.fn()}
        onToggleBlock={vi.fn()}
        groupInfoOpen={false}
        onToggleGroupInfo={vi.fn()}
      />,
    )
  }

  function lampOf(container: HTMLElement): HTMLElement {
    const lamp = container.querySelector<HTMLElement>('.chat-head .status-row .lamp')
    if (lamp === null) {
      throw new Error('presence-лампа не найдена в status-row заголовка')
    }
    return lamp
  }

  it('online: прототипная лампа без .off + статус «В сети» (.status-txt)', () => {
    presence.statuses.set(ALICE, 'online')
    const { container } = renderDirectHeader()

    expect(lampOf(container).classList.contains('off')).toBe(false)
    expect(container.querySelector('.chat-head .status-row .status-txt')?.textContent).toBe(
      'В сети',
    )
  })

  it('offline: тусклая лампа .off + статус «офлайн» (настоящий offline из store 007)', () => {
    presence.statuses.set(ALICE, 'offline')
    const { container } = renderDirectHeader()

    expect(lampOf(container).classList.contains('off')).toBe(true)
    expect(container.querySelector('.chat-head .status-row .status-txt')?.textContent).toBe(
      'офлайн',
    )
  })

  it('unknown: нейтральная лампа БЕЗ .off + «неизвестно» — ложного «офлайна» нет', () => {
    // Нет записи в store: вход/переподключение до первого №36 (edge case 007).
    const { container } = renderDirectHeader()

    expect(lampOf(container).classList.contains('off')).toBe(false)
    expect(container.querySelector('.chat-head .status-row .status-txt')?.textContent).toBe(
      'неизвестно',
    )
    expect(screen.queryByText('офлайн')).toBeNull()
  })
})
