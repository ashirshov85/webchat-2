import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useGroupMembers } from '../useGroupMembers'

/**
 * Roster action hook of US3 (T041 → T047; api T044a): every roster
 * mutation — №32 `kickMember`, №34 `setMemberRole`, №35
 * `transferOwnership` — goes through ONE local mutex, because the
 * roster actions COMPETE for the same group state: a second action
 * fired while one is in flight (two rapid clicks, two tabs) must
 * never reach the API — the server-side rowcount/hierarchy checks
 * (T043) remain the authority, the mutex just keeps the client from
 * piling on. `pendingUserId` is the in-flight member (the MemberList
 * row disable, T045); an action problem (`role_hierarchy_violation`,
 * `not_group_owner`, network) surfaces through `error` and UNBLOCKS
 * the roster — the next action must be possible without remounting.
 */

const api = vi.hoisted(() => ({
  kickMember: vi.fn(),
  setMemberRole: vi.fn(),
  transferOwnership: vi.fn(),
}))

vi.mock('../../../api/groups', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/groups')>()),
  kickMember: api.kickMember,
  setMemberRole: api.setMemberRole,
  transferOwnership: api.transferOwnership,
}))

const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'
const ALICE = '11111111-1111-1111-1111-111111111111'
const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

const mounted: Array<{ unmount(): void }> = []

function mountMembers(chatId: string = GROUP_ID) {
  const rendered = renderHook((id: string) => useGroupMembers(id), { initialProps: chatId })
  mounted.push(rendered)
  return rendered
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.clearAllMocks()
})

describe('useGroupMembers roster actions (№32/№34/№35, T044a)', () => {
  it('kick sends №32 kickMember(chatId, userId) and tracks the in-flight member', async () => {
    const gate = deferred<void>()
    api.kickMember.mockReturnValueOnce(gate.promise)
    const { result } = mountMembers()

    act(() => {
      void result.current.kick(ALICE)
    })

    expect(api.kickMember).toHaveBeenCalledWith(GROUP_ID, ALICE)
    expect(result.current.pendingUserId).toBe(ALICE)

    act(() => {
      gate.resolve()
    })
    await waitFor(() => {
      expect(result.current.pendingUserId).toBeNull()
    })
  })

  it('setRole sends №34 setMemberRole(chatId, userId, role) — grant and revoke', async () => {
    api.setMemberRole.mockResolvedValue(undefined)
    const { result } = mountMembers()

    await act(async () => {
      await result.current.setRole(CAROL, 'admin')
    })
    expect(api.setMemberRole).toHaveBeenCalledWith(GROUP_ID, CAROL, 'admin')

    await act(async () => {
      await result.current.setRole(BOB, 'member')
    })
    expect(api.setMemberRole).toHaveBeenLastCalledWith(GROUP_ID, BOB, 'member')
    expect(result.current.pendingUserId).toBeNull()
  })

  it('transferOwnership sends №35 transferOwnership(chatId, userId)', async () => {
    api.transferOwnership.mockResolvedValue(undefined)
    const { result } = mountMembers()

    await act(async () => {
      await result.current.transferOwnership(BOB)
    })

    expect(api.transferOwnership).toHaveBeenCalledWith(GROUP_ID, BOB)
    expect(result.current.pendingUserId).toBeNull()
  })
})

describe('useGroupMembers mutex — competing roster actions (T047)', () => {
  it('serializes two kicks: the second never reaches the API while the first is in flight', async () => {
    const first = deferred<void>()
    api.kickMember.mockReturnValueOnce(first.promise)
    const { result } = mountMembers()

    act(() => {
      void result.current.kick(ALICE)
    })
    act(() => {
      void result.current.kick(BOB)
    })

    expect(api.kickMember).toHaveBeenCalledTimes(1)
    expect(result.current.pendingUserId).toBe(ALICE)

    act(() => {
      first.resolve()
    })
    await waitFor(() => {
      expect(result.current.pendingUserId).toBeNull()
    })

    act(() => {
      void result.current.kick(BOB)
    })
    expect(api.kickMember).toHaveBeenCalledTimes(2)
    expect(api.kickMember).toHaveBeenLastCalledWith(GROUP_ID, BOB)
  })

  it('covers mixed actions: setRole and transferOwnership stay grounded while a kick is in flight', async () => {
    const kick = deferred<void>()
    api.kickMember.mockReturnValueOnce(kick.promise)
    const { result } = mountMembers()

    act(() => {
      void result.current.kick(ALICE)
    })
    act(() => {
      void result.current.setRole(BOB, 'admin')
    })
    act(() => {
      void result.current.transferOwnership(CAROL)
    })

    expect(api.setMemberRole).not.toHaveBeenCalled()
    expect(api.transferOwnership).not.toHaveBeenCalled()

    act(() => {
      kick.resolve()
    })
    await waitFor(() => {
      expect(result.current.pendingUserId).toBeNull()
    })
  })
})

describe('useGroupMembers error surface', () => {
  it('exposes the action problem, unblocks the roster and keeps the next action possible', async () => {
    const problem = {
      status: 403,
      title: 'Forbidden',
      errors: { userId: ['role_hierarchy_violation'] },
    }
    api.kickMember.mockRejectedValueOnce(problem)
    const { result } = mountMembers()

    await act(async () => {
      await result.current.kick(BOB)
    })

    expect(result.current.error).toEqual(problem)
    expect(result.current.pendingUserId).toBeNull()

    api.kickMember.mockResolvedValueOnce(undefined)
    act(() => {
      void result.current.kick(CAROL)
    })
    expect(api.kickMember).toHaveBeenCalledTimes(2)
  })
})
