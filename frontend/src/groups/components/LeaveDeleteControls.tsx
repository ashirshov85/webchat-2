/**
 * №33/№30 controls of the group card (feature 006, T066; US6,
 * FR-005/FR-006, api-contract.md №33/№30): leaving is offered to
 * admin/member ONLY — an owner's №33 is a server `403
 * owner_must_transfer` (transfer the ownership №35 or delete the group
 * №30 first) — and hard-DELETING is owner-only (a non-owner №30 is a
 * `403 not_group_owner`): the client hides the unreachable half
 * exactly like the roster actions hide theirs (MemberList, FR-004
 * precedent).
 *
 * The viewer's №28 `myRole` may be STALE though — an №35 transfer may
 * have landed while this card still shows the old role — so the server
 * half of the gate must surface as an error HINT: the 403 problem code
 * (`owner_must_transfer`, `not_group_owner`) renders in an ErrorBanner
 * WITHOUT firing the success callback and the button stays armed for
 * the retry after the №28 state converges (the §3.4 frames reload the
 * card through useGroup). Success (204) hands the event up — the parent
 * closes/converges the window; the «Чаты» half converges
 * deterministically through the §3.6/§3.5 frames
 * (`group.you_removed {reason:'left'}` / `group.deleted`), tested in
 * the useGroupRealtime/useChatList/MessengerPage suites.
 *
 * One submission at a time: the in-flight button is disabled, so a
 * double click cannot fire a second №33/№30 (the operations converge
 * server-side, but the UI keeps its single-flight discipline like the
 * roster mutations of useGroupMembers).
 */
import { useCallback, useState } from 'react'
import { deleteGroup, leaveGroup } from '../../api/groups'
import type { GroupMember } from '../../api/groups'
import { ErrorBanner } from '../../chats/components/ErrorBanner'

export interface LeaveDeleteControlsProps {
  /** The group chatId — the №33 membership / №30 group DELETE target. */
  readonly chatId: string
  /** The viewer's №28 role — picks the reachable half (FR-005/FR-006). */
  readonly myRole: GroupMember['role']
  /** №33 success (204): the parent closes/converges the leaving viewer's window. */
  readonly onLeft?: () => void
  /** №30 success (204): the parent closes/converges the deleting owner's window. */
  readonly onDeleted?: () => void
}

export function LeaveDeleteControls({
  chatId,
  myRole,
  onLeft,
  onDeleted,
}: LeaveDeleteControlsProps) {
  const isOwner = myRole === 'owner'
  /** Single-flight guard of the one rendered action (no double submit). */
  const [pending, setPending] = useState(false)
  /** Server problem of the last attempt (403 hints, network) — stays visible. */
  const [error, setError] = useState<unknown>(null)

  const handleSubmit = useCallback(() => {
    if (pending) {
      return
    }
    setError(null)
    setPending(true)
    void (async () => {
      try {
        if (isOwner) {
          await deleteGroup(chatId)
          onDeleted?.()
        } else {
          await leaveGroup(chatId)
          onLeft?.()
        }
      } catch (cause) {
        setError(cause)
      } finally {
        setPending(false)
      }
    })()
  }, [pending, isOwner, chatId, onLeft, onDeleted])

  return (
    <div className="group-leave-delete">
      {error !== null && <ErrorBanner error={error} />}
      {isOwner ? (
        <button
          type="button"
          className="group-leave-delete-button group-leave-delete-delete"
          disabled={pending}
          onClick={handleSubmit}
        >
          Удалить группу
        </button>
      ) : (
        <button
          type="button"
          className="group-leave-delete-button group-leave-delete-leave"
          disabled={pending}
          onClick={handleSubmit}
        >
          Выйти из группы
        </button>
      )}
    </div>
  )
}
