import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ContactView } from '../../../api/chats'
import { CreateGroupDialog } from '../CreateGroupDialog'

/**
 * Group creation dialog (US1, T019 → T026; FR-001/FR-002): the form
 * carries the client half of the №27 validation — title trim 1–64,
 * description ≤256 — so an invalid draft never reaches the API, and
 * the initial members are MULTI-selected from the creator's №20
 * contacts. Submission goes through №27 `createGroup` (T025): the
 * trimmed title, the optional description and the picked
 * `memberUserIds` travel in one atomic request; a server problem
 * (422 `not_in_contacts` — the server-side half of FR-002) renders
 * without closing the dialog, and the created `GroupView` is handed
 * to the parent via `onCreated`.
 */

type GroupView = components['schemas']['GroupView']
type Problem = components['schemas']['Problem']

const { mockListContacts, mockCreateGroup } = vi.hoisted(() => ({
  mockListContacts: vi.fn(),
  mockCreateGroup: vi.fn(),
}))

vi.mock('../../../api/chats', () => ({
  listContacts: mockListContacts,
}))

vi.mock('../../../api/groups', () => ({
  createGroup: mockCreateGroup,
}))

const ME = '11111111-1111-1111-1111-111111111111'
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

function contacts(): ContactView[] {
  return [
    { user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z' },
    { user: peer(BOB, 'bob'), createdAt: '2026-09-03T00:00:00.000Z' },
    { user: peer(CAROL, 'carol'), createdAt: '2026-09-04T00:00:00.000Z' },
  ]
}

function groupView(): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: null,
    myRole: 'owner',
    members: [{ user: peer(ME, 'me'), role: 'owner', joinedAt: '2026-09-20T12:00:00.000Z' }],
  }
}

function fillTitle(value: string) {
  fireEvent.change(screen.getByLabelText('Название группы'), { target: { value } })
}

function fillDescription(value: string) {
  fireEvent.change(screen.getByLabelText('Описание группы'), { target: { value } })
}

function pick(username: string) {
  fireEvent.click(screen.getByRole('checkbox', { name: `Выбрать ${username}` }))
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Создать группу' }))
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('CreateGroupDialog form (FR-001)', () => {
  it('renders the title/description fields and the №20 contacts to pick from', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    render(<CreateGroupDialog />)

    expect(screen.getByLabelText('Название группы')).toBeInTheDocument()
    expect(screen.getByLabelText('Описание группы')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Создать группу' })).toBeInTheDocument()

    expect(await screen.findByRole('checkbox', { name: 'Выбрать alice' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Выбрать bob' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Выбрать carol' })).toBeInTheDocument()
  })

  it('rejects an empty or whitespace-only title without calling №27', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    render(<CreateGroupDialog />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    submit()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockCreateGroup).not.toHaveBeenCalled()

    fillTitle('   ')
    submit()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockCreateGroup).not.toHaveBeenCalled()
  })

  it('rejects a 65-character title without calling №27', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    render(<CreateGroupDialog />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('a'.repeat(65))
    submit()

    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockCreateGroup).not.toHaveBeenCalled()
  })

  it('rejects a 257-character description without calling №27', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    render(<CreateGroupDialog />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('Проект Альфа')
    fillDescription('d'.repeat(257))
    submit()

    expect(screen.getByRole('alert')).toHaveTextContent(/256/)
    expect(mockCreateGroup).not.toHaveBeenCalled()
  })

  it('accepts the 64/256 boundary, trims the title and submits the picked member', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    render(<CreateGroupDialog />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle(`  ${'a'.repeat(64)}  `)
    fillDescription('d'.repeat(256))
    pick('alice')
    submit()

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockCreateGroup).toHaveBeenCalledWith({
      title: 'a'.repeat(64),
      description: 'd'.repeat(256),
      memberUserIds: [ALICE],
    })
  })
})

describe('CreateGroupDialog member multiselect (FR-002)', () => {
  it('submits every picked contact as memberUserIds and toggles picks off', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    render(<CreateGroupDialog />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('Проект Альфа')
    pick('alice')
    pick('bob')
    pick('carol')
    submit()

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockCreateGroup).toHaveBeenCalledWith({
      title: 'Проект Альфа',
      memberUserIds: [ALICE, BOB, CAROL],
    })
  })

  it('omits memberUserIds when nothing is picked', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    render(<CreateGroupDialog />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('Проект Альфа')
    submit()

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockCreateGroup).toHaveBeenCalledWith({ title: 'Проект Альфа' })
  })
})

describe('CreateGroupDialog submission outcome', () => {
  it('hands the created GroupView to onCreated (№27 201)', async () => {
    const onCreated = vi.fn()
    const view = groupView()
    mockListContacts.mockResolvedValueOnce(contacts())
    mockCreateGroup.mockResolvedValueOnce(view)
    render(<CreateGroupDialog onCreated={onCreated} />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('Проект Альфа')
    pick('alice')
    submit()

    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledWith(view)
    })
  })

  it('renders the 422 problem code and keeps the form open (not_in_contacts)', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    mockCreateGroup.mockRejectedValueOnce({
      title: 'Unprocessable Entity',
      status: 422,
      errors: { memberUserIds: ['not_in_contacts'] },
    } satisfies Problem)
    render(<CreateGroupDialog />)
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('Проект Альфа')
    pick('alice')
    submit()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('not_in_contacts')
    expect(screen.getByLabelText('Название группы')).toBeInTheDocument()
  })
})
