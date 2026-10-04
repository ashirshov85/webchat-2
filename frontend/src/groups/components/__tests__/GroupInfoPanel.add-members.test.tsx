import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ContactView } from '../../../api/chats'
import type { GroupMember } from '../../../api/groups'
import { ToastProvider } from '../../../ui/Toast'
import { GroupInfoPanel } from '../GroupInfoPanel'

/**
 * №31 add-members section of the group card (US3, T046; FR-002,
 * api-contract.md №31): the adder's №20 contacts load LAZILY — the
 * fetch fires only when the section first opens, and the «Загрузка
 * контактов…» slice must give way to the AddMembersPicker multiselect
 * as soon as `listContacts` resolves.
 *
 * T072 regression (Phase 10 bugfix): the lazy-load effect used to keep
 * its own `contactsStatus` write among its deps — the effect itself
 * flips the status `idle → loading`, so its own re-run ran the cleanup,
 * marked the in-flight request `cancelled` and DISCARDED the №20
 * answer: the section hung on «Загрузка контактов…» forever. The main
 * case below is RED on that code (the resolve never lands); the
 * surrounding cases guard the properties the T073 fix must keep —
 * laziness (no №20 while the section is closed) and the «Повторить»
 * retry of a failed №20.
 */

type Problem = components['schemas']['Problem']

const { mockListContacts, mockAddMembers, mockUpdateGroup } = vi.hoisted(() => ({
  mockListContacts: vi.fn(),
  mockAddMembers: vi.fn(),
  mockUpdateGroup: vi.fn(),
}))

vi.mock('../../../api/chats', () => ({
  listContacts: mockListContacts,
}))

vi.mock('../../../api/groups', () => ({
  addMembers: mockAddMembers,
  updateGroup: mockUpdateGroup,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'
const DAVE = '44444444-4444-4444-4444-444444444444'
const EVE = '55555555-5555-5555-5555-555555555555'
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

/** The adder's №20 contacts — dave/eve are NOT in the roster yet. */
function contacts(): ContactView[] {
  return [
    { user: user(DAVE, 'dave'), createdAt: '2026-09-02T00:00:00.000Z' },
    { user: user(EVE, 'eve'), createdAt: '2026-09-03T00:00:00.000Z' },
  ]
}

function renderPanel(overrides: Partial<Parameters<typeof GroupInfoPanel>[0]> = {}) {
  const props = {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: null,
    members: roster(),
    myRole: 'owner' as GroupMember['role'],
    currentUserId: ME,
    onKick: vi.fn(),
    onSetRole: vi.fn(),
    onTransferOwnership: vi.fn(),
    ...overrides,
  }
  // T045: панель №29 завершается тостом — рендер внутри ToastProvider.
  render(
    <ToastProvider>
      <GroupInfoPanel {...props} />
    </ToastProvider>,
  )
  return props
}

function openAddSection() {
  fireEvent.click(screen.getByRole('button', { name: 'Добавить участников' }))
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('GroupInfoPanel add-members section — lazy №20 load (T072 regression)', () => {
  it('does not fetch №20 until the section first opens (laziness, FR-002)', () => {
    renderPanel()

    expect(mockListContacts).not.toHaveBeenCalled()

    openAddSection()

    expect(mockListContacts).toHaveBeenCalledTimes(1)
  })

  it('renders AddMembersPicker after №20 resolves — «Загрузка контактов…» must not hang', async () => {
    // A manually-controlled №20 answer: the release happens strictly
    // AFTER the section is open, so the resolve can never race the
    // toggle — the only way this hangs is the effect cancelling its
    // own in-flight request through its own `contactsStatus` dep.
    let releaseContacts!: (list: ContactView[]) => void
    mockListContacts.mockImplementationOnce(
      () =>
        new Promise<ContactView[]>((resolve) => {
          releaseContacts = resolve
        }),
    )
    renderPanel()

    openAddSection()

    expect(screen.getByText('Загрузка контактов…')).toBeInTheDocument()

    await act(async () => {
      releaseContacts(contacts())
      await Promise.resolve()
    })

    expect(screen.getByLabelText('Контакты для добавления')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Выбрать dave' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Выбрать eve' })).toBeInTheDocument()
    expect(screen.queryByText('Загрузка контактов…')).toBeNull()
  })

  it('renders the №20 problem with «Повторить»; the retry re-queries and then renders the picker', async () => {
    mockListContacts
      .mockRejectedValueOnce({
        title: 'Internal Server Error',
        status: 500,
      } satisfies Problem)
      .mockResolvedValueOnce(contacts())
    renderPanel()

    openAddSection()

    expect(await screen.findByRole('button', { name: 'Повторить' })).toBeInTheDocument()
    expect(mockListContacts).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(await screen.findByLabelText('Контакты для добавления')).toBeInTheDocument()
    expect(screen.queryByText('Загрузка контактов…')).toBeNull()
    expect(mockListContacts).toHaveBeenCalledTimes(2)
  })
})
