import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * US1 creation entry wiring (feature 006, T029; quickstart §3.1): the
 * messenger panel carries the «Создать группу» button that opens the
 * CreateGroupDialog (T026), a created №27 `GroupView` opens the GROUP
 * window and refetches №12, and a group row of the unified «Чаты» list
 * (T028) opens the group window — the header shows the group title and
 * NO direct-only actions («Действия» = №14 delete + №23/№24 block are
 * 004 pair-dialog features; blocks never apply to groups). A direct
 * row keeps the 004 pair dialog with its action menu.
 */

type GroupView = components['schemas']['GroupView']
type ChatView = components['schemas']['ChatView']

const { mockGetCurrentUser, mockChats, mockCreateGroup, mockSse } = vi.hoisted(() => ({
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
}))

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => ({
  createGroup: mockCreateGroup,
}))

vi.mock('../../../api/sse', () => mockSse)

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
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

function groupRow(): ChatListItem {
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
  }
}

function directRow(): ChatListItem {
  return {
    chatId: 'chat-direct-1',
    peer: peer(ALICE, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
  }
}

function groupChatView(): ChatView {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    description: null,
    myRole: 'owner',
    othersReadUpToSeq: 0,
    memberCount: 3,
    peer: null,
    blockedByMe: null,
    peerReadUpToSeq: null,
    myReadUpToSeq: 0,
  }
}

function createdGroupView(): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: null,
    myRole: 'owner',
    members: [
      { user: peer(ME, 'me'), role: 'owner', joinedAt: '2026-09-20T12:00:00.000Z' },
      { user: peer(ALICE, 'alice'), role: 'member', joinedAt: '2026-09-20T12:00:00.000Z' },
    ],
  }
}

function contacts(): ContactView[] {
  return [{ user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z' }]
}

/** The №18 stub: a silent stream — no frames, no (re)connects. */
function installSilentStream(): void {
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe: () => () => {},
    close: () => {},
  }))
}

async function renderPage(chats: ChatListItem[]): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue(chats)
  mockChats.getChat.mockImplementation((chatId: string) =>
    chatId === GROUP_ID
      ? Promise.resolve(groupChatView())
      : Promise.reject(new Error('unexpected getChat call')),
  )
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listContacts.mockResolvedValue(contacts())
  installSilentStream()
  render(<MessengerPage />)
  await screen.findByRole('button', { name: 'Создать группу' })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('MessengerPage «Создать группу» entry (US1, quickstart §3.1)', () => {
  it('opens the CreateGroupDialog from the panel button and closes it on «Отмена»', async () => {
    await renderPage([directRow()])

    fireEvent.click(screen.getByRole('button', { name: 'Создать группу' }))
    expect(screen.getByRole('dialog', { name: 'Создание группы' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('dialog', { name: 'Создание группы' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Создать группу' })).toBeInTheDocument()
  })

  it('creates the group via №27, closes the dialog, refetches №12 and opens the group window', async () => {
    await renderPage([directRow()])

    fireEvent.click(screen.getByRole('button', { name: 'Создать группу' }))
    fireEvent.change(await screen.findByLabelText('Название группы'), {
      target: { value: 'Проект Альфа' },
    })
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Выбрать alice' }))
    mockCreateGroup.mockResolvedValueOnce(createdGroupView())
    const callsBefore = mockChats.listChats.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: 'Создать группу' }))

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledWith({
        title: 'Проект Альфа',
        memberUserIds: [ALICE],
      })
    })
    await waitFor(() => {
      expect(mockChats.listChats.mock.calls.length).toBeGreaterThan(callsBefore)
    })
    expect(screen.queryByRole('dialog', { name: 'Создание группы' })).toBeNull()
    // The group window opened on the №27 result: its title is the header.
    expect(screen.getByRole('heading', { level: 2, name: 'Проект Альфа' })).toBeInTheDocument()
  })

  it('keeps the dialog open on a №27 problem so the draft stays fixable', async () => {
    await renderPage([directRow()])

    fireEvent.click(screen.getByRole('button', { name: 'Создать группу' }))
    fireEvent.change(await screen.findByLabelText('Название группы'), {
      target: { value: 'Проект Альфа' },
    })
    mockCreateGroup.mockRejectedValueOnce({
      title: 'Unprocessable Entity',
      status: 422,
      errors: { memberUserIds: ['not_in_contacts'] },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Создать группу' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('not_in_contacts')
    expect(screen.getByRole('dialog', { name: 'Создание группы' })).toBeInTheDocument()
  })
})

describe('MessengerPage group window from the unified list (US1)', () => {
  it('opens the group window on a group row: title header, composer, no direct-only action menu', async () => {
    await renderPage([groupRow(), directRow()])

    fireEvent.click(screen.getByRole('button', { name: /Проект Альфа/ }))

    await waitFor(() => {
      expect(mockChats.getChat).toHaveBeenCalledWith(GROUP_ID)
    })
    expect(screen.getByRole('heading', { level: 2, name: 'Проект Альфа' })).toBeInTheDocument()
    expect(screen.getByLabelText('Текст сообщения')).toBeInTheDocument()
    // №14/№23/№24 are pair-dialog actions — a group window carries none.
    expect(screen.queryByRole('button', { name: 'Действия' })).toBeNull()
  })

  it('keeps the direct dialog intact: peer header with the action menu', async () => {
    await renderPage([groupRow(), directRow()])

    fireEvent.click(screen.getByRole('button', { name: /^alice/ }))

    expect(screen.getByRole('heading', { level: 2, name: 'alice' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Действия' })).toBeInTheDocument()
  })
})
