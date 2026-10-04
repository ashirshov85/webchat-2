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
 * The US4 rename UI (T053; FR-007/FR-001) rides the header: the
 * «Переименовать» entry is offered to owner/admin only (a member's №29
 * is a server `403 forbidden_role`, hidden client-side like the №31
 * section above). The form carries the CLIENT half of the №29
 * validation (groups/validation.ts — the same rules the №27 dialog
 * uses), so an invalid draft never reaches the API; submission is ONE
 * atomic №29 `updateGroup` (T052a) — the trimmed title always travels
 * (the form requires it, so the `empty_patch` 400 is unreachable
 * through the UI), the description only when non-empty after trim. A
 * server problem (`forbidden_role` after a role raced away, network)
 * renders in an ErrorBanner WITHOUT closing the form, and the №29
 * GroupView is handed up through `onUpdated` — the parent's №28 state
 * (useGroup) converges at once, then the §3.1 `group.updated` frames
 * keep the OTHER viewers' lists and headers live (T053's realtime
 * half: useGroupRealtime/useChatList).
 *
 * T045 (US3; FR-025, прототип grpEditForm): завершённый №29 отмечается
 * ровно одним тостом «Групповой чат обновлён — {title}» (слот
 * ToastProvider страницы; сбои тостом не отмечаются — только
 * ErrorBanner формы). Контракт сохраняется за №29 при пересборке в
 * GroupEditModal (T056): тост выдаёт владелец операции-формы.
 *
 * The roster mutations stay with the parent — the №32/№34/№35 calls and
 * their mutex live in useGroupMembers (T047): `pendingUserId` (the
 * in-flight row) and `rosterError` (the last action problem) are the
 * panel's reflection of that hook. The US6 №33/№30 LeaveDeleteControls
 * (T066) arrive through the `leaveDeleteControls` slot — the parent
 * (MessengerPage) mounts the reachable half by `myRole` there.
 *
 * Purely presentational otherwise: the №28 data comes via props — the
 * useGroup wiring (T046a) feeds the snapshot and its optimistic
 * `group.*` updates, so this component stays free of any fetching
 * besides the adder's contact list.
 */
import { useCallback, useEffect, useId, useState, type ReactNode, type SubmitEvent } from 'react'
import { listContacts } from '../../api/chats'
import type { ContactView } from '../../api/chats'
import { addMembers, updateGroup } from '../../api/groups'
import type { GroupMember, GroupView, UpdateGroupRequest } from '../../api/groups'
import { membersLabel } from '../membersLabel'
import { validateGroupDescription, validateGroupTitle } from '../validation'
import { ErrorBanner } from '../../chats/components/ErrorBanner'
import { useToast } from '../../ui/Toast'
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
   * №29 success (US4, T053): the updated GroupView — the parent
   * converges its №28 state at once; the `group.updated` frames keep
   * the other viewers live.
   */
  readonly onUpdated?: (group: GroupView) => void
  /**
   * LeaveDeleteControls entry (US6, T066): the parent mounts the №33
   * leave / №30 hard-delete controls here (MessengerPage passes the
   * component with its success callbacks); undefined renders no slot.
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
  onUpdated,
  leaveDeleteControls,
}: GroupInfoPanelProps) {
  // T045 (FR-025): тост завершённого №29 — слот ToastProvider страницы.
  const showToast = useToast()
  /** №31 entry visibility — owner/admin (FR-004); a member never sees it. */
  const canAddMembers = myRole === 'owner' || myRole === 'admin'
  /** №29 entry visibility — the same owner/admin gate (FR-007). */
  const canRename = canAddMembers

  const [addOpen, setAddOpen] = useState(false)
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set<string>())
  /** Server problem of the last №31 attempt; the section stays open. */
  const [addError, setAddError] = useState<unknown>(null)
  const [addPending, setAddPending] = useState(false)

  const [contacts, setContacts] = useState<readonly ContactView[]>([])
  const [contactsStatus, setContactsStatus] = useState<ContactsStatus>('idle')
  const [contactsError, setContactsError] = useState<unknown>(null)
  const [contactsReload, setContactsReload] = useState(0)

  // The №29 rename form (US4, T053): opening snapshots the CURRENT №28
  // metadata into the draft, so a rename racing an incoming
  // `group.updated` frame never leaks the stale server title into the
  // input mid-edit — the form always starts from what the card shows.
  const [renameOpen, setRenameOpen] = useState(false)
  const [titleDraft, setTitleDraft] = useState('')
  const [descriptionDraft, setDescriptionDraft] = useState('')
  /** Client-side FR-001 rejection of the №29 draft (never reaches the API). */
  const [renameValidationError, setRenameValidationError] = useState<string | null>(null)
  /** Server problem of the last №29 attempt; the form stays open. */
  const [renameError, setRenameError] = useState<unknown>(null)
  const [renamePending, setRenamePending] = useState(false)
  const renameTitleId = useId()
  const renameDescriptionId = useId()

  const openRenameForm = useCallback(() => {
    setTitleDraft(title)
    setDescriptionDraft(description ?? '')
    setRenameValidationError(null)
    setRenameError(null)
    setRenameOpen(true)
  }, [title, description])

  const handleCancelRename = useCallback(() => {
    setRenameOpen(false)
    setRenameValidationError(null)
    setRenameError(null)
  }, [])

  const handleSubmitRename = useCallback(
    (event: SubmitEvent<HTMLFormElement>) => {
      event.preventDefault()
      if (renamePending) {
        return
      }
      // The client half of the №29 validation (FR-001) — shared with
      // the №27 dialog, so an invalid draft never reaches the API.
      const validatedTitle = validateGroupTitle(titleDraft)
      if (!validatedTitle.ok) {
        setRenameError(null)
        setRenameValidationError(validatedTitle.error)
        return
      }
      const validatedDescription = validateGroupDescription(descriptionDraft)
      if (!validatedDescription.ok) {
        setRenameError(null)
        setRenameValidationError(validatedDescription.error)
        return
      }
      // The title is required by the form, so №29 always carries it
      // (`empty_patch` is unreachable through the UI); the description
      // travels only when non-empty after trim.
      const body: UpdateGroupRequest = { title: validatedTitle.title }
      if (validatedDescription.description !== undefined) {
        body.description = validatedDescription.description
      }
      setRenameValidationError(null)
      setRenameError(null)
      setRenamePending(true)
      void (async () => {
        try {
          const view = await updateGroup(chatId, body)
          setRenameOpen(false)
          // T045 (FR-025): один тост на завершённый №29 — текст
          // прототипа grpEditForm (отправленное название).
          showToast(`Групповой чат обновлён — ${validatedTitle.title}`)
          onUpdated?.(view)
        } catch (cause) {
          setRenameError(cause)
        } finally {
          setRenamePending(false)
        }
      })()
    },
    [renamePending, titleDraft, descriptionDraft, chatId, onUpdated, showToast],
  )

  // The adder's №20 contacts load LAZILY — only when the №31 section
  // first opens (a plain member never fetches); the cancelled guard
  // keeps a fast toggle from racing the answer (CreateGroupDialog
  // precedent). `contactsStatus` is deliberately NOT a dep (T073): the
  // effect's own `idle → loading` write would re-run it, and the
  // cleanup of that own iteration would mark the in-flight №20 request
  // `cancelled` and discard the answer — the section hung on
  // «Загрузка контактов…» forever. The stale-closure risk is nil: the
  // only paths that re-run the effect (open toggle, «Повторить»)
  // render with an up-to-date status — 'idle' on first open (React
  // batches the retry's `setContactsStatus('idle')` with the
  // `contactsReload` bump), 'ready'/'error' on later opens (cached, no
  // refetch — the laziness holds).
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
    // eslint-disable-next-line react-hooks/exhaustive-deps -- contactsStatus intentionally excluded (see above, T073)
  }, [addOpen, contactsReload])

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
        {canRename && (
          <button
            type="button"
            className="group-info-rename-toggle"
            aria-expanded={renameOpen}
            onClick={renameOpen ? handleCancelRename : openRenameForm}
          >
            {renameOpen ? 'Скрыть переименование' : 'Переименовать'}
          </button>
        )}
      </header>
      {renameOpen && (
        <form className="group-info-rename" onSubmit={handleSubmitRename}>
          {renameValidationError !== null && (
            <p className="error-banner-text" role="alert">
              {renameValidationError}
            </p>
          )}
          {renameError !== null && <ErrorBanner error={renameError} />}
          <div className="group-dialog-field">
            <label htmlFor={renameTitleId}>Название группы</label>
            <input
              id={renameTitleId}
              name="title"
              type="text"
              autoComplete="off"
              value={titleDraft}
              onChange={(event) => {
                setTitleDraft(event.target.value)
              }}
            />
          </div>
          <div className="group-dialog-field">
            <label htmlFor={renameDescriptionId}>Описание группы</label>
            <input
              id={renameDescriptionId}
              name="description"
              type="text"
              autoComplete="off"
              value={descriptionDraft}
              onChange={(event) => {
                setDescriptionDraft(event.target.value)
              }}
            />
          </div>
          <div className="group-info-rename-actions">
            <button type="submit" className="group-info-rename-submit" disabled={renamePending}>
              {renamePending ? 'Сохраняется…' : 'Сохранить'}
            </button>
            <button
              type="button"
              className="group-info-rename-cancel"
              disabled={renamePending}
              onClick={handleCancelRename}
            >
              Отмена
            </button>
          </div>
        </form>
      )}
      {description !== null && !renameOpen && (
        <p className="group-info-description">{description}</p>
      )}

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
