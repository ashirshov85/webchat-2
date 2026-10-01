/**
 * Group roster (feature 006, T045; FR-003/FR-004): renders the №28
 * `GroupView.members` snapshot — every row shows the username (the
 * viewer's own row gets the «(вы)» mark) and the ROLE label
 * («Владелец»/«Админ»/«Участник»).
 *
 * Roster actions are offered strictly by the viewer's `myRole`
 * hierarchy (FR-004): the OWNER manages everyone except himself —
 * №32 kick, №34 grant/revoke admin, №35 transfer ownership; an ADMIN
 * kicks only plain members (a fellow admin or the owner is
 * `role_hierarchy_violation`) and never sees the owner-only №34/№35
 * (`not_group_owner`); a plain member gets no actions at all
 * (`forbidden_role`). Kicking yourself is №32 `self_forbidden` —
 * leaving is №33 (US6), so the own row carries no actions.
 *
 * Purely presentational: the callbacks hand the action upward (the
 * №32/№34/№35 calls and their mutex live in useGroupMembers, T047).
 * `pendingUserId` is the view half of that mutex — the in-flight
 * member's row renders disabled while the others stay armed.
 */
import type { GroupMember } from '../../api/groups'

export interface MemberListProps {
  /** №28 active roster (server-owned order, ≤200 — FR-013). */
  readonly members: readonly GroupMember[]
  /** The viewer's role in the group — gates the row actions (FR-004). */
  readonly myRole: GroupMember['role']
  /** The viewer's user id — marks the own row and blocks self-actions. */
  readonly currentUserId: string
  /** №32 kick (owner: admins+members; admin: members only). */
  readonly onKick: (userId: string) => void
  /** №34 grant/revoke admin (owner-only). */
  readonly onSetRole: (userId: string, role: 'admin' | 'member') => void
  /** №35 transfer ownership (owner-only, never to self). */
  readonly onTransferOwnership: (userId: string) => void
  /** The member with an in-flight action — their row renders disabled (T047). */
  readonly pendingUserId?: string | null
}

const ROLE_LABELS: Readonly<Record<GroupMember['role'], string>> = {
  owner: 'Владелец',
  admin: 'Админ',
  member: 'Участник',
}

/** FR-004 row-action set: what the viewer may do to THIS member. */
interface RowActions {
  readonly kick: boolean
  readonly grantAdmin: boolean
  readonly revokeAdmin: boolean
  readonly transferOwnership: boolean
}

const NO_ACTIONS: RowActions = {
  kick: false,
  grantAdmin: false,
  revokeAdmin: false,
  transferOwnership: false,
}

function actionsFor(myRole: GroupMember['role'], target: GroupMember, isOwn: boolean): RowActions {
  if (isOwn || target.role === 'owner') {
    // №32/№34/№35 on self is `self_forbidden`; the single owner row is
    // the viewer's own by the owner invariant — no roster touches it.
    return NO_ACTIONS
  }
  if (myRole === 'owner') {
    return {
      kick: true,
      grantAdmin: target.role === 'member',
      revokeAdmin: target.role === 'admin',
      transferOwnership: true,
    }
  }
  if (myRole === 'admin') {
    // FR-004: an admin excludes only plain members; №34/№35 are
    // owner-only (`not_group_owner`).
    return target.role === 'member' ? { ...NO_ACTIONS, kick: true } : NO_ACTIONS
  }
  return NO_ACTIONS
}

export function MemberList({
  members,
  myRole,
  currentUserId,
  onKick,
  onSetRole,
  onTransferOwnership,
  pendingUserId = null,
}: MemberListProps) {
  return (
    <ul className="member-list" aria-label="Состав группы">
      {members.map((member) => {
        const isOwn = member.user.id === currentUserId
        const actions = actionsFor(myRole, member, isOwn)
        const pending = pendingUserId === member.user.id
        const username = member.user.username
        return (
          <li key={member.user.id} className="member-row">
            <span className="member-row-main">
              <span className="chat-item-title">{isOwn ? `${username} (вы)` : username}</span>
              <span className="member-row-role">{ROLE_LABELS[member.role]}</span>
            </span>
            {(actions.kick ||
              actions.grantAdmin ||
              actions.revokeAdmin ||
              actions.transferOwnership) && (
              <span className="member-row-actions">
                {actions.kick && (
                  <button
                    type="button"
                    className="member-action member-action-kick"
                    aria-label={`Исключить ${username}`}
                    disabled={pending}
                    onClick={() => {
                      onKick(member.user.id)
                    }}
                  >
                    Исключить
                  </button>
                )}
                {actions.grantAdmin && (
                  <button
                    type="button"
                    className="member-action"
                    aria-label={`Назначить админом ${username}`}
                    disabled={pending}
                    onClick={() => {
                      onSetRole(member.user.id, 'admin')
                    }}
                  >
                    Назначить админом
                  </button>
                )}
                {actions.revokeAdmin && (
                  <button
                    type="button"
                    className="member-action"
                    aria-label={`Снять админа ${username}`}
                    disabled={pending}
                    onClick={() => {
                      onSetRole(member.user.id, 'member')
                    }}
                  >
                    Снять админа
                  </button>
                )}
                {actions.transferOwnership && (
                  <button
                    type="button"
                    className="member-action"
                    aria-label={`Передать владение ${username}`}
                    disabled={pending}
                    onClick={() => {
                      onTransferOwnership(member.user.id)
                    }}
                  >
                    Передать владение
                  </button>
                )}
              </span>
            )}
          </li>
        )
      })}
    </ul>
  )
}
