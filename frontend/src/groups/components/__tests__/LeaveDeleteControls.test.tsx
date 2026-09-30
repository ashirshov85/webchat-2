import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { GroupMember } from '../../../api/groups'
import { LeaveDeleteControls } from '../LeaveDeleteControls'

/**
 * №33/№30 controls of the group card (US6, T062 → T066; FR-005/FR-006,
 * api-contract.md №33/№30): leaving is offered to admin/member ONLY —
 * an owner's №33 is a server `403 owner_must_transfer` (transfer the
 * ownership or delete the group first) — and DELETING is owner-only
 * (a non-owner №30 is `403 not_group_owner`): the client hides the
 * unreachable half exactly like the roster actions hide theirs
 * (FR-004 precedent, T045).
 *
 * The viewer's №28 `myRole` may be STALE though — an №35 transfer may
 * have landed while this card still shows the old role — so the server
 * half of the gate must surface as an error HINT: the 403 problem code
 * (`owner_must_transfer`, `not_group_owner`) renders in an alert
 * WITHOUT firing the success callback and the buttons stay armed for
 * the retry after the №28 state converges. Success (204) hands the
 * event up — the parent closes/converges the window; the «Чаты» half
 * converges deterministically through the §3.6/§3.5 frames
 * (`group.you_removed {reason:'left'}` / `group.deleted`), tested in
 * useGroupRealtime/useChatList tests.
 */

type Problem = components['schemas']['Problem']

const { mockLeaveGroup, mockDeleteGroup } = vi.hoisted(() => ({
  mockLeaveGroup: vi.fn(),
  mockDeleteGroup: vi.fn(),
}))

vi.mock('../../../api/groups', () => ({
  leaveGroup: mockLeaveGroup,
  deleteGroup: mockDeleteGroup,
}))

const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'

/** The T066 props contract of the controls (asserted by this suite). */
interface ControlsPropsOverride {
  readonly chatId?: string
  readonly myRole?: GroupMember['role']
  readonly onLeft?: () => void
  readonly onDeleted?: () => void
}

function renderControls(overrides: ControlsPropsOverride = {}) {
  const props = {
    chatId: GROUP_ID,
    myRole: 'member' as GroupMember['role'],
    onLeft: vi.fn(),
    onDeleted: vi.fn(),
    ...overrides,
  }
  render(<LeaveDeleteControls {...props} />)
  return props
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('LeaveDeleteControls visibility by myRole (FR-005/FR-006)', () => {
  it('owner: sees №30 delete only — an owner №33 is owner_must_transfer, hidden client-side', () => {
    renderControls({ myRole: 'owner' })

    expect(screen.getByRole('button', { name: 'Удалить группу' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Выйти из группы' })).toBeNull()
  })

  it('admin: sees №33 leave only — №30 is not_group_owner for non-owners', () => {
    renderControls({ myRole: 'admin' })

    expect(screen.getByRole('button', { name: 'Выйти из группы' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Удалить группу' })).toBeNull()
  })

  it('member: sees №33 leave only as well (FR-005: admin/member leave alike)', () => {
    renderControls({ myRole: 'member' })

    expect(screen.getByRole('button', { name: 'Выйти из группы' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Удалить группу' })).toBeNull()
  })
})

describe('LeaveDeleteControls №33 leave submission (DELETE membership)', () => {
  it('calls leaveGroup(chatId) and hands the 204 up via onLeft', async () => {
    mockLeaveGroup.mockResolvedValueOnce(undefined)
    const props = renderControls({ myRole: 'member' })

    fireEvent.click(screen.getByRole('button', { name: 'Выйти из группы' }))

    await waitFor(() => {
      expect(mockLeaveGroup).toHaveBeenCalledWith(GROUP_ID)
    })
    await waitFor(() => {
      expect(props.onLeft).toHaveBeenCalledTimes(1)
    })
    expect(props.onDeleted).not.toHaveBeenCalled()
  })

  it('renders the 403 owner_must_transfer hint and keeps the card usable (stale №28 role race)', async () => {
    // The card still shows admin/member, but №35 made this user the
    // owner — the server refuses №33 with the transfer hint.
    mockLeaveGroup.mockRejectedValueOnce({
      title: 'Forbidden',
      status: 403,
      errors: { membership: ['owner_must_transfer'] },
    } satisfies Problem)
    const props = renderControls({ myRole: 'admin' })

    fireEvent.click(screen.getByRole('button', { name: 'Выйти из группы' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('owner_must_transfer')
    expect(props.onLeft).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Выйти из группы' })).toBeInTheDocument()
  })

  it('disables the button while №33 is in flight — no double submit', async () => {
    let resolveLeave: (() => void) | undefined
    mockLeaveGroup.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          resolveLeave = resolve
        }),
    )
    const props = renderControls({ myRole: 'member' })
    const leave = screen.getByRole('button', { name: 'Выйти из группы' })

    fireEvent.click(leave)
    fireEvent.click(leave)

    expect(mockLeaveGroup).toHaveBeenCalledTimes(1)
    expect(leave).toBeDisabled()

    await act(async () => {
      resolveLeave?.()
      await Promise.resolve()
    })

    await waitFor(() => {
      expect(props.onLeft).toHaveBeenCalledTimes(1)
    })
  })
})

describe('LeaveDeleteControls №30 delete submission (DELETE the group)', () => {
  it('calls deleteGroup(chatId) and hands the 204 up via onDeleted', async () => {
    mockDeleteGroup.mockResolvedValueOnce(undefined)
    const props = renderControls({ myRole: 'owner' })

    fireEvent.click(screen.getByRole('button', { name: 'Удалить группу' }))

    await waitFor(() => {
      expect(mockDeleteGroup).toHaveBeenCalledWith(GROUP_ID)
    })
    await waitFor(() => {
      expect(props.onDeleted).toHaveBeenCalledTimes(1)
    })
    expect(props.onLeft).not.toHaveBeenCalled()
  })

  it('renders the 403 not_group_owner hint and keeps the card usable (stale №28 role race)', async () => {
    // The card still shows owner, but №35 moved the ownership away —
    // the server refuses №30 with not_group_owner.
    mockDeleteGroup.mockRejectedValueOnce({
      title: 'Forbidden',
      status: 403,
      errors: { role: ['not_group_owner'] },
    } satisfies Problem)
    const props = renderControls({ myRole: 'owner' })

    fireEvent.click(screen.getByRole('button', { name: 'Удалить группу' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('not_group_owner')
    expect(props.onDeleted).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Удалить группу' })).toBeInTheDocument()
  })
})
