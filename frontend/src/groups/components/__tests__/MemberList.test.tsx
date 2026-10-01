import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GroupMember } from '../../../api/groups'
import { MemberList } from '../MemberList'

/**
 * Group roster with the US3 action matrix (T041 → T045; FR-003/FR-004):
 * every row renders the member's ROLE («Владелец»/«Админ»/«Участник»),
 * and the roster actions — №32 kick, №34 grant/revoke admin, №35
 * transfer ownership — are offered strictly by the viewer's `myRole`
 * hierarchy: the owner manages everyone EXCEPT himself (№32 on self is
 * `self_forbidden` — leaving is №33, US6), an admin kicks only plain
 * members (a fellow admin or the owner is `role_hierarchy_violation`),
 * a plain member gets no roster actions at all (`forbidden_role`).
 *
 * `pendingUserId` is the view half of the useGroupMembers mutex (T047):
 * the row with the in-flight action renders disabled while the other
 * rows stay armed — SERIALIZATION of competing actions is owned by the
 * hook, the list only reflects which member is being acted on.
 */

const ME = '11111111-1111-1111-1111-111111111111'
const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'
const DAVE = '44444444-4444-4444-4444-444444444444'

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

function renderList(overrides: Partial<Parameters<typeof MemberList>[0]> = {}) {
  const props = {
    members: roster(),
    myRole: 'owner' as const,
    currentUserId: ME,
    onKick: vi.fn(),
    onSetRole: vi.fn(),
    onTransferOwnership: vi.fn(),
    ...overrides,
  }
  render(<MemberList {...props} />)
  return props
}

afterEach(cleanup)

describe('MemberList roster rendering (FR-003)', () => {
  it('renders every member with the role label and marks the own row', () => {
    renderList()

    expect(screen.getByText('alice (вы)')).toBeVisible()
    expect(screen.getByText('Владелец')).toBeVisible()
    expect(screen.getByText('bob')).toBeVisible()
    expect(screen.getByText('Админ')).toBeVisible()
    expect(screen.getByText('carol')).toBeVisible()
    expect(screen.getByText('Участник')).toBeVisible()
  })
})

describe('MemberList action visibility by myRole (FR-004)', () => {
  it('owner: offers kick/grant-admin/transfer on a member row and reports the callbacks', () => {
    const props = renderList()

    fireEvent.click(screen.getByRole('button', { name: 'Исключить carol' }))
    expect(props.onKick).toHaveBeenCalledWith(CAROL)

    fireEvent.click(screen.getByRole('button', { name: 'Назначить админом carol' }))
    expect(props.onSetRole).toHaveBeenCalledWith(CAROL, 'admin')

    fireEvent.click(screen.getByRole('button', { name: 'Передать владение carol' }))
    expect(props.onTransferOwnership).toHaveBeenCalledWith(CAROL)
  })

  it('owner: offers revoke-admin on an admin row (№34 grant AND revoke)', () => {
    const props = renderList()

    fireEvent.click(screen.getByRole('button', { name: 'Снять админа bob' }))
    expect(props.onSetRole).toHaveBeenCalledWith(BOB, 'member')

    fireEvent.click(screen.getByRole('button', { name: 'Исключить bob' }))
    expect(props.onKick).toHaveBeenCalledWith(BOB)

    fireEvent.click(screen.getByRole('button', { name: 'Передать владение bob' }))
    expect(props.onTransferOwnership).toHaveBeenCalledWith(BOB)
  })

  it('owner: renders no actions on the own row (№32 self is self_forbidden; №35 needs another owner)', () => {
    renderList()

    expect(screen.queryByRole('button', { name: /alice/ })).toBeNull()
  })

  it('admin: kicks only plain members — admin/owner rows and the own row carry no actions', () => {
    renderList({
      members: [
        member(ME, 'alice', 'owner'),
        member(BOB, 'bob', 'admin'),
        member(CAROL, 'carol', 'member'),
        member(DAVE, 'dave', 'admin'),
      ],
      myRole: 'admin',
      currentUserId: BOB,
    })

    expect(screen.getByRole('button', { name: 'Исключить carol' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Исключить dave' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Исключить alice' })).toBeNull()
    // №34/№35 are owner-only — an admin never sees them (not_group_owner)
    expect(screen.queryByRole('button', { name: /админом/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Снять админа/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Передать владение/ })).toBeNull()
  })

  it('member: renders no roster actions at all (forbidden_role, US3-3)', () => {
    renderList({ myRole: 'member', currentUserId: CAROL })

    expect(screen.queryByRole('button')).toBeNull()
  })
})

describe('MemberList pending row (T047 mutex UI)', () => {
  it('disables the in-flight member row while the other rows stay armed', () => {
    renderList({ pendingUserId: CAROL })

    expect(screen.getByRole('button', { name: 'Исключить carol' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Назначить админом carol' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Исключить bob' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Снять админа bob' })).toBeEnabled()
  })
})
