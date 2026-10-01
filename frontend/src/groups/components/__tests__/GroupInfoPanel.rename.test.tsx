import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { GroupMember } from '../../../api/groups'
import { GroupInfoPanel } from '../GroupInfoPanel'

/**
 * Rename UI of the group card (US4, T050 → T053; FR-007/FR-001,
 * api-contract.md №29): metadata editing is offered to owner/admin
 * only — a plain member's №29 is a server `403 forbidden_role`, so the
 * entry is hidden client-side exactly like the roster actions and the
 * №31 add-members section (FR-004 precedent). The open form carries
 * the CLIENT half of the №29 validation (groups/validation.ts — title
 * trim 1–64, description ≤256, shared with the №27 dialog), so an
 * invalid draft never reaches the API. Submission is ONE atomic №29
 * `updateGroup` (T052a): the trimmed title always travels (the form
 * requires it — the `empty_patch` 400 is unreachable through the UI),
 * the description only when non-empty after trim; a server problem
 * (`forbidden_role` after a role raced away, network) renders in an
 * ErrorBanner WITHOUT closing the form, and the №29 GroupView is
 * handed up through `onUpdated` — the parent's №28 state (useGroup)
 * converges at once, then the `group.updated` frames (T053's realtime
 * half, tested in useGroupRealtime.test) keep the OTHER viewers live.
 */

type GroupView = components['schemas']['GroupView']
type Problem = components['schemas']['Problem']

const { mockAddMembers, mockUpdateGroup } = vi.hoisted(() => ({
  mockAddMembers: vi.fn(),
  mockUpdateGroup: vi.fn(),
}))

vi.mock('../../../api/groups', () => ({
  addMembers: mockAddMembers,
  updateGroup: mockUpdateGroup,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'
const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'

function user(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function member(id: string, username: string, role: GroupMember['role']): GroupMember {
  return { user: user(id, username), role, joinedAt: '2026-09-20T12:00:00.000Z' }
}

/** alice=owner, bob=admin, carol=member — the §3.3 quickstart roster. */
function roster(): GroupMember[] {
  return [
    member(ME, 'alice', 'owner'),
    member(BOB, 'bob', 'admin'),
    member(CAROL, 'carol', 'member'),
  ]
}

function panelView(overrides: Partial<GroupView> = {}): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: 'рабочая группа',
    myRole: 'owner',
    members: roster(),
    ...overrides,
  }
}

function renderPanel(overrides: Partial<Parameters<typeof GroupInfoPanel>[0]> = {}) {
  const view = panelView()
  const props = {
    chatId: view.chatId,
    title: view.title,
    description: view.description,
    members: view.members,
    myRole: view.myRole,
    currentUserId: ME,
    onKick: vi.fn(),
    onSetRole: vi.fn(),
    onTransferOwnership: vi.fn(),
    ...overrides,
  }
  render(<GroupInfoPanel {...props} />)
  return props
}

function openRenameForm() {
  fireEvent.click(screen.getByRole('button', { name: 'Переименовать' }))
}

function fillTitle(value: string) {
  fireEvent.change(screen.getByLabelText('Название группы'), { target: { value } })
}

function fillDescription(value: string) {
  fireEvent.change(screen.getByLabelText('Описание группы'), { target: { value } })
}

function submitRename() {
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('GroupInfoPanel rename entry visibility (FR-007: admin+)', () => {
  it('owner sees the rename entry; the form opens prefilled with the №28 metadata', () => {
    renderPanel({ myRole: 'owner' })

    openRenameForm()

    expect(screen.getByLabelText('Название группы')).toHaveValue('Проект Альфа')
    expect(screen.getByLabelText('Описание группы')).toHaveValue('рабочая группа')
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeInTheDocument()
  })

  it('admin sees the rename entry too (№29 is owner/admin)', () => {
    renderPanel({ myRole: 'admin', currentUserId: BOB })

    expect(screen.getByRole('button', { name: 'Переименовать' })).toBeInTheDocument()
  })

  it('member never sees the rename entry (№29 → 403 forbidden_role, hidden client-side)', () => {
    renderPanel({ myRole: 'member', currentUserId: CAROL })

    expect(screen.queryByRole('button', { name: 'Переименовать' })).toBeNull()
  })
})

describe('GroupInfoPanel rename form — №29 client validation (FR-001)', () => {
  it('rejects an empty or whitespace-only title without calling №29', () => {
    renderPanel({ myRole: 'owner' })
    openRenameForm()

    fillTitle('')
    submitRename()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()

    fillTitle('   ')
    submitRename()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()
  })

  it('rejects a 65-character title without calling №29', () => {
    renderPanel({ myRole: 'owner' })
    openRenameForm()

    fillTitle('a'.repeat(65))
    submitRename()

    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()
  })

  it('rejects a 257-character description without calling №29', () => {
    renderPanel({ myRole: 'owner' })
    openRenameForm()

    fillTitle('Новое название')
    fillDescription('d'.repeat(257))
    submitRename()

    expect(screen.getByRole('alert')).toHaveTextContent(/256/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()
  })
})

describe('GroupInfoPanel rename submission (№29 PATCH)', () => {
  it('trims the title and omits the description when empty', async () => {
    renderPanel({ myRole: 'owner' })
    openRenameForm()

    fillTitle(`  Новое название  `)
    fillDescription('')
    submitRename()

    await waitFor(() => {
      expect(mockUpdateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockUpdateGroup).toHaveBeenCalledWith(GROUP_ID, { title: 'Новое название' })
  })

  it('accepts the 64/256 boundary and submits the trimmed description', async () => {
    renderPanel({ myRole: 'admin', currentUserId: BOB })
    openRenameForm()

    fillTitle(`  ${'a'.repeat(64)}  `)
    fillDescription(`  ${'d'.repeat(256)}  `)
    submitRename()

    await waitFor(() => {
      expect(mockUpdateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockUpdateGroup).toHaveBeenCalledWith(GROUP_ID, {
      title: 'a'.repeat(64),
      description: 'd'.repeat(256),
    })
  })

  it('hands the №29 GroupView up via onUpdated and closes the form (200)', async () => {
    const onUpdated = vi.fn()
    const view = panelView({ title: 'Новое название', description: null })
    mockUpdateGroup.mockResolvedValueOnce(view)
    renderPanel({ myRole: 'owner', onUpdated })
    openRenameForm()

    fillTitle('Новое название')
    submitRename()

    await waitFor(() => {
      expect(onUpdated).toHaveBeenCalledWith(view)
    })
    expect(screen.queryByLabelText('Название группы')).toBeNull()
  })

  it('renders the 403 forbidden_role problem and keeps the form open', async () => {
    mockUpdateGroup.mockRejectedValueOnce({
      title: 'Forbidden',
      status: 403,
      errors: { role: ['forbidden_role'] },
    } satisfies Problem)
    renderPanel({ myRole: 'admin', currentUserId: BOB })
    openRenameForm()

    fillTitle('Новое название')
    submitRename()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('forbidden_role')
    expect(screen.getByLabelText('Название группы')).toBeInTheDocument()
  })
})
