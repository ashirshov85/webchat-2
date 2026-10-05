/**
 * Модальная форма «Участники» группового чата (feature 008, US4, T055;
 * FR-023, ui-behavior §3, research §D): житель ЕДИНОЙ оболочки ModalShell
 * (MessengerPage, formId 'group-members'), проекция #membersForm
 * нормативного прототипа specs/008-chat-window-styling/design/chats.html
 * (renderMembersList): ростер №28 минус собственная строка — «Вы» живёт
 * только в members-tip заголовка, старая пометка «{username} (вы)» ушла
 * вместе с own-строкой (контракт T049).
 *
 * Метки ролей 006 («Владелец»/«Админ»/«Участник») и ростер-действия
 * №32/№34/№35 — дословно из MemberList (миграция T049, FR-034/SC-002):
 * строго по иерархии myRole (owner — всем кроме себя; admin — kick только
 * member-строк, №34/№35 owner-only; member — без действий), aria-label'ы
 * «Исключить/Назначить админом/Снять админа/Передать владение {username}».
 * `pendingUserId` — отображение мьютекса useGroupMembers (строка в полёте
 * disabled), `rosterError` — последняя проблема ростер-действия (ErrorBanner).
 *
 * «Добавить в контакты» (.add-ctc-btn прототипа) — только на строках вне
 * адресной книги вызывающего (№20): модаль сообщает userId наверх
 * (onAddContact), №21 и его тост принадлежат странице (T045).
 */
import type { GroupMember } from '../../api/groups'
import { ErrorBanner } from '../../chats/components/ErrorBanner'
import { Avatar } from '../../ui/Avatar'
import './group-members-modal.css'

export interface GroupMembersModalProps {
  /** №28 active roster (server-owned order, ≤200 — FR-013). */
  readonly members: readonly GroupMember[]
  /** The viewer's role in the group — gates the row actions (FR-004). */
  readonly myRole: GroupMember['role']
  /** The viewer's user id — the own row is NOT rendered (ui-behavior §3). */
  readonly currentUserId: string
  /** №32 kick (owner: admins+members; admin: members only). */
  readonly onKick: (userId: string) => void
  /** №34 grant/revoke admin (owner-only). */
  readonly onSetRole: (userId: string, role: 'admin' | 'member') => void
  /** №35 transfer ownership (owner-only, never to self). */
  readonly onTransferOwnership: (userId: string) => void
  /** The member with an in-flight roster action (useGroupMembers mutex view). */
  readonly pendingUserId?: string | null
  /** The last roster-action problem (useGroupMembers `error`). */
  readonly rosterError?: unknown
  /** User ids already in the adder's address book (№20) — hides the offer. */
  readonly contactUserIds?: ReadonlySet<string>
  /** «Добавить в контакты» — the parent owns №21 and its toast (T045/T057). */
  readonly onAddContact?: (userId: string) => void
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

function actionsFor(myRole: GroupMember['role'], target: GroupMember): RowActions {
  if (target.role === 'owner') {
    // The single owner row is untouchable by any viewer (the owner
    // himself does not see it — the own row is filtered out above;
    // №32/№34/№35 on the owner is `role_hierarchy_violation`).
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

export function GroupMembersModal({
  members,
  myRole,
  currentUserId,
  onKick,
  onSetRole,
  onTransferOwnership,
  pendingUserId = null,
  rosterError = null,
  contactUserIds,
  onAddContact,
}: GroupMembersModalProps) {
  // renderMembersList прототипа: собственная строка не выводится вовсе.
  const rows = members.filter((member) => member.user.id !== currentUserId)

  return (
    <div className="members-form">
      {rosterError !== null && <ErrorBanner error={rosterError} />}
      <div className="pick-list members-list">
        {rows.length === 0 ? (
          <div className="pick-empty">Нет участников</div>
        ) : (
          rows.map((member) => {
            const actions = actionsFor(myRole, member)
            const pending = pendingUserId === member.user.id
            const username = member.user.username
            const hasActions =
              actions.kick || actions.grantAdmin || actions.revokeAdmin || actions.transferOwnership
            // №20: предложение — только строкам вне адресной книги
            // вызывающего (собственная строка уже отфильтрована).
            const offerContact =
              contactUserIds !== undefined && onAddContact !== undefined
                ? !contactUserIds.has(member.user.id)
                : false
            return (
              <div className="pick-row member-row" key={member.user.id}>
                <Avatar source={username} size={32} />
                <div className="c-main">
                  <div className="c-top">
                    <span className="c-name">{username}</span>
                  </div>
                  <div className="c-prev">{ROLE_LABELS[member.role]}</div>
                </div>
                {hasActions && (
                  <div className="member-actions">
                    {actions.kick && (
                      <button
                        type="button"
                        className="member-action danger"
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
                  </div>
                )}
                {offerContact && (
                  <button
                    type="button"
                    className="add-ctc-btn"
                    title="Добавить участника в контакты"
                    disabled={pending}
                    onClick={() => {
                      onAddContact?.(member.user.id)
                    }}
                  >
                    Добавить в контакты
                  </button>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
