/**
 * Roster action mutex (feature 006, T047; US3; FR-004): every №32/
 * №34/№35 roster mutation — `kickMember`, `setMemberRole`,
 * `transferOwnership` (api T044a) — goes through ONE local lock,
 * because the actions COMPETE for the same №28 roster: a second
 * action fired while one is in flight (two rapid clicks, two tabs)
 * is grounded and NEVER reaches the API. The server-side
 * rowcount/hierarchy checks (T043) remain the authority — the mutex
 * only keeps the client from piling on. `pendingUserId` is the
 * in-flight member (the MemberList row disable, T045).
 *
 * The roster itself stays the №28 lifecycle of useGroup (T046a):
 * this hook is the ACTION half. A successful action converges the
 * card through `onApplied` — the parent's №28 `reload()` — until
 * the T048 frames (`group.member.removed`/`group.role.changed`)
 * take over live convergence on top, idempotently (FR-015). An
 * action problem (`role_hierarchy_violation`, `not_group_owner`,
 * network) surfaces through `error` and UNBLOCKS the roster — the
 * next action must be possible without remounting; the returned
 * promises never reject (the problem is state, not a throw).
 */
import { useCallback, useRef, useState } from 'react'
import {
  kickMember,
  setMemberRole,
  transferOwnership as transferOwnershipApi,
} from '../../api/groups'
import type { SetMemberRoleRequest } from '../../api/groups'

export interface UseGroupMembersResult {
  /** The member with an in-flight action; null while the roster is free. */
  readonly pendingUserId: string | null
  /** The last action problem; cleared by the next armed action. */
  readonly error: unknown
  /** №32 kick — grounded while another roster action is in flight. */
  readonly kick: (userId: string) => Promise<void>
  /** №34 grant/revoke admin — grounded while another action is in flight. */
  readonly setRole: (userId: string, role: SetMemberRoleRequest['role']) => Promise<void>
  /** №35 transfer ownership — grounded while another action is in flight. */
  readonly transferOwnership: (userId: string) => Promise<void>
}

export function useGroupMembers(
  chatId: string | null,
  onApplied?: () => void,
): UseGroupMembersResult {
  const [pendingUserId, setPendingUserId] = useState<string | null>(null)
  const [error, setError] = useState<unknown>(null)
  // The lock itself lives in a ref: grounded actions must be decided
  // synchronously (the very click), not one render late.
  const inFlight = useRef<string | null>(null)

  const run = useCallback(
    (userId: string, call: (chatId: string) => Promise<unknown>): Promise<void> => {
      if (chatId === null || inFlight.current !== null) {
        return Promise.resolve()
      }
      inFlight.current = userId
      setError(null)
      setPendingUserId(userId)
      return (async () => {
        try {
          await call(chatId)
          onApplied?.()
        } catch (cause) {
          setError(cause)
        } finally {
          inFlight.current = null
          setPendingUserId(null)
        }
      })()
    },
    [chatId, onApplied],
  )

  const kick = useCallback((userId: string) => run(userId, (id) => kickMember(id, userId)), [run])

  const setRole = useCallback(
    (userId: string, role: SetMemberRoleRequest['role']) =>
      run(userId, (id) => setMemberRole(id, userId, role)),
    [run],
  )

  const transferOwnership = useCallback(
    (userId: string) => run(userId, (id) => transferOwnershipApi(id, userId)),
    [run],
  )

  return { pendingUserId, error, kick, setRole, transferOwnership }
}
