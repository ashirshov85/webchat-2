/**
 * Group info card (feature 006, T046; US3): the «card» half of the group
 * window — the header (title + «N участников» counter over the №28
 * roster, the optional №28 `description`) and the roster itself
 * (MemberList, T045) with its №32/№34/№35 actions offered by the
 * viewer's `myRole` hierarchy (FR-004).
 *
 * The №31 add-members entry (FR-002) reuses the AddMembersPicker
 * multiselect (T026) over the ADDER's №20 contacts — owner/admin only
 * (a member's №31 is a server `403 forbidden_role`; FR-004 hides the
 * entry client-side like MemberList hides its actions). The panel owns
 * the picker section exactly like CreateGroupDialog owns its №27 form:
 * contacts load lazily on the first open, the batch is submitted as ONE
 * atomic №31 `addMembers`, a server problem (422 `not_in_contacts`,
 * 409 `group_full`, network) renders without closing the section, and
 * the returned roster is handed up through `onMembersAdded` — the
 * parent's №28 state converges immediately, then the `group.member.added`
 * frames (T046a/T048) keep it live. `excludeUserIds` = the active
 * roster: already-active members are not offered again (the server-side
 * half of the №31 idempotency).
 *
 * The roster mutations stay with the parent — the №32/№34/№35 calls and
 * their mutex live in useGroupMembers (T047): `pendingUserId` (the
 * in-flight row) and `rosterError` (the last action problem) are the
 * panel's reflection of that hook. The LeaveDeleteControls entry is a
 * PLACEHOLDER slot until US6 (T066) mounts the №33/№30 controls there.
 *
 * Purely presentational otherwise: the №28 data comes via props — the
 * useGroup wiring (T046a) feeds the snapshot and its optimistic
 * `group.*` updates, so this component stays free of any fetching
 * besides the adder's contact list.
 */
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { listContacts } from '../../api/chats'
import type { ContactView } from '../../api/chats'
import { addMembers } from '../../api/groups'
import type { GroupMember } from '../../api/groups'
import { membersLabel } from '../membersLabel'
import { ErrorBanner } from '../../chats/components/ErrorBanner'
import { AddMembersPicker } from './AddMembersPicker'
import { MemberList } from './MemberList'

export interface GroupInfoPanelProps {
  /** The group chatId — the №31 addMembers target. */
  readonly chatId: string
  /** №28 `title` (server-authoritative; rename UI is US4/T053). */
  readonly title: string
  /** №28 `description` — rendered only when present. */
  readonly description: string | null
  /** №28 active roster with roles (≤200, FR-013). */
  readonly members: readonly GroupMember[]
  /** The viewer's role — gates the roster actions AND the №31 entry (FR-004). */
  readonly myRole: GroupMember['role']
  /** The viewer's user id — the own roster row mark (T045). */
  readonly currentUserId: string
  /** №32 kick — owner: admins+members, admin: members only (useGroupMembers, T047). */
  readonly onKick: (userId: string) => void
  /** №34 grant/revoke admin — owner-only (useGroupMembers, T047). */
  readonly onSetRole: (userId: string, role: 'admin' | 'member') => void
  /** №35 transfer ownership — owner-only (useGroupMembers, T047). */
  readonly onTransferOwnership: (userId: string) => void
  /** The member with an in-flight roster action (T047 mutex view half). */
  readonly pendingUserId?: string | null
  /** The last roster-action problem (role_hierarchy_violation, network…) — T047 `error`. */
  readonly rosterError?: unknown
  /** №31 success: the returned active roster — the parent converges its №28 state at once. */
  readonly onMembersAdded?: (members: readonly GroupMember[]) => void
  /**
   * LeaveDeleteControls entry (US6/T066 PLACEHOLDER): the №33 leave /
   * №30 delete controls mount here when US6 lands — until then nothing
   * renders in the slot.
   */
  readonly leaveDeleteControls?: ReactNode
}

type ContactsStatus = 'idle' | 'loading' | 'ready' | 'error'

export function GroupInfoPanel({
  chatId,
  title,
  description,
  members,
  myRole,
  currentUserId,
  onKick,
  onSetRole,
  onTransferOwnership,
  pendingUserId = null,
  rosterError = null,
  onMembersAdded,
  leaveDeleteControls,
}: GroupInfoPanelProps) {
  /** №31 entry visibility — owner/admin (FR-004); a member never sees it. */
  const canAddMembers = myRole === 'owner' || myRole === 'admin'

  const [addOpen, setAddOpen] = useState(false)
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set<string>())
  /** Server problem of the last №31 attempt; the section stays open. */
  const [addError, setAddError] = useState<unknown>(null)
  const [addPending, setAddPending] = useState(false)

  const [contacts, setContacts] = useState<readonly ContactView[]>([])
  const [contactsStatus, setContactsStatus] = useState<ContactsStatus>('idle')
  const [contactsError, setContactsError] = useState<unknown>(null)
  const [contactsReload, setContactsReload] = useState(0)

  // The adder's №20 contacts load LAZILY — only when the №31 section
  // first opens (a plain member never fetches); the cancelled guard
  // keeps a fast toggle from racing the answer (CreateGroupDialog
  // precedent).
  useEffect(() => {
    if (!addOpen || contactsStatus !== 'idle') {
      return
    }
    let cancelled = false
    setContactsStatus('loading')
    void (async () => {
      try {
        const loaded = await listContacts()
        if (cancelled) {
          return
        }
        setContacts(loaded)
        setContactsError(null)
        setContactsStatus('ready')
      } catch (cause) {
        if (cancelled) {
          return
        }
        setContactsError(cause)
        setContactsStatus('error')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [addOpen, contactsStatus, contactsReload])

  const toggleMember = useCallback((userId: string) => {
    setSelectedIds((previous) => {
      const next = new Set(previous)
      if (next.has(userId)) {
        next.delete(userId)
      } else {
        next.add(userId)
      }
      return next
    })
  }, [])

  const handleSubmitAdd = useCallback(() => {
    if (addPending || selectedIds.size === 0) {
      return
    }
    setAddError(null)
    setAddPending(true)
    void (async () => {
      try {
        const added = await addMembers(chatId, { userIds: [...selectedIds] })
        setSelectedIds(new Set<string>())
        onMembersAdded?.(added)
      } catch (cause) {
        setAddError(cause)
      } finally {
        setAddPending(false)
      }
    })()
  }, [addPending, selectedIds, chatId, onMembersAdded])

  // Already-active members are not offered again (№31 idempotency —
  // the server half; the offer hides them client-side).
  const memberIds = new Set(members.map((member) => member.user.id))

  return (
    <section className="group-info" aria-label="Информация о группе">
      <header className="group-info-header">
        <span className="chat-item-avatar" aria-hidden="true">
          #
        </span>
        <h3 className="group-info-title">{title}</h3>
        <span className="group-info-members">
          {members.length} {membersLabel(members.length)}
        </span>
      </header>
      {description !== null && <p className="group-info-description">{description}</p>}

      {rosterError !== null && <ErrorBanner error={rosterError} />}

      <MemberList
        members={members}
        myRole={myRole}
        currentUserId={currentUserId}
        onKick={onKick}
        onSetRole={onSetRole}
        onTransferOwnership={onTransferOwnership}
        pendingUserId={pendingUserId}
      />

      {canAddMembers && (
        <div className="group-info-add">
          <button
            type="button"
            className="group-info-add-toggle"
            aria-expanded={addOpen}
            onClick={() => {
              setAddOpen((open) => !open)
            }}
          >
            {addOpen ? 'Скрыть добавление' : 'Добавить участников'}
          </button>
          {addOpen && (
            <div className="group-info-add-body">
              {addError !== null && <ErrorBanner error={addError} />}
              {contactsStatus === 'loading' && (
                <p className="messenger-empty">Загрузка контактов…</p>
              )}
              {contactsStatus === 'error' && (
                <div className="chat-panel-error">
                  <ErrorBanner error={contactsError} />
                  <button
                    type="button"
                    className="chat-panel-retry"
                    onClick={() => {
                      setContactsStatus('idle')
                      setContactsReload((count) => count + 1)
                    }}
                  >
                    Повторить
                  </button>
                </div>
              )}
              {contactsStatus === 'ready' && (
                <>
                  <AddMembersPicker
                    contacts={contacts}
                    selectedIds={selectedIds}
                    onToggle={toggleMember}
                    excludeUserIds={memberIds}
                  />
                  <div className="group-info-add-actions">
                    <button
                      type="button"
                      className="group-info-add-submit"
                      disabled={addPending || selectedIds.size === 0}
                      onClick={handleSubmitAdd}
                    >
                      {addPending ? 'Добавляется…' : 'Добавить'}
                    </button>
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      )}

      {leaveDeleteControls !== undefined && (
        <div className="group-info-leave-delete">{leaveDeleteControls}</div>
      )}
    </section>
  )
}
