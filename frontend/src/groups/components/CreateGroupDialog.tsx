/**
 * «Создать группу» dialog (feature 006, T026; FR-001/FR-002): the
 * client half of the №27 validation — title trim 1–64, optional
 * description ≤256 (groups/validation.ts) — so an invalid draft
 * never reaches the API, and the initial members are MULTI-selected
 * from the creator's №20 contacts through AddMembersPicker.
 *
 * Submission is one atomic №27 `createGroup` (T025): the trimmed
 * title, the optional trimmed description and the picked
 * `memberUserIds` travel in a single request; `description` /
 * `memberUserIds` are omitted entirely when empty — the exact №27
 * body. A server problem (422 `not_in_contacts` — the server-side
 * half of FR-002, `group_full`, network) renders in an ErrorBanner
 * WITHOUT closing the dialog, so the user can fix the draft and
 * retry; the created `GroupView` is handed to the parent via
 * `onCreated` (T029 wiring opens the group window).
 */
import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { listContacts } from '../../api/chats'
import type { ContactView } from '../../api/chats'
import { createGroup } from '../../api/groups'
import type { CreateGroupRequest, GroupView } from '../../api/groups'
import { ErrorBanner } from '../../chats/components/ErrorBanner'
import { validateGroupDescription, validateGroupTitle } from '../validation'
import { AddMembersPicker } from './AddMembersPicker'

export interface CreateGroupDialogProps {
  /** Hands over the №27 result (201 GroupView) — T029 opens the group. */
  readonly onCreated?: (group: GroupView) => void
  /** Optional close control for the parent-managed dialog lifetime. */
  readonly onCancel?: () => void
}

type ContactsStatus = 'loading' | 'ready' | 'error'

export function CreateGroupDialog({ onCreated, onCancel }: CreateGroupDialogProps) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set<string>())
  /** Client-side FR-001 rejection (never reaches №27). */
  const [validationError, setValidationError] = useState<string | null>(null)
  /** Server problem of the last №27 attempt; the form stays open. */
  const [serverError, setServerError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)

  const [contacts, setContacts] = useState<readonly ContactView[]>([])
  const [contactsStatus, setContactsStatus] = useState<ContactsStatus>('loading')
  const [contactsError, setContactsError] = useState<unknown>(null)
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    let cancelled = false
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
  }, [reloadCount])

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

  const handleSubmit = useCallback(
    (event: FormEvent) => {
      event.preventDefault()
      if (pending) {
        return
      }
      const validatedTitle = validateGroupTitle(title)
      if (!validatedTitle.ok) {
        setServerError(null)
        setValidationError(validatedTitle.error)
        return
      }
      const validatedDescription = validateGroupDescription(description)
      if (!validatedDescription.ok) {
        setServerError(null)
        setValidationError(validatedDescription.error)
        return
      }
      const body: CreateGroupRequest = { title: validatedTitle.title }
      if (validatedDescription.description !== undefined) {
        body.description = validatedDescription.description
      }
      if (selectedIds.size > 0) {
        body.memberUserIds = [...selectedIds]
      }
      setValidationError(null)
      setServerError(null)
      setPending(true)
      void (async () => {
        try {
          const group = await createGroup(body)
          onCreated?.(group)
        } catch (cause) {
          setServerError(cause)
        } finally {
          setPending(false)
        }
      })()
    },
    [pending, title, description, selectedIds, onCreated],
  )

  return (
    <section className="group-dialog" role="dialog" aria-label="Создание группы">
      <h2 className="group-dialog-title">Новая группа</h2>
      <form className="group-dialog-form" onSubmit={handleSubmit}>
        {validationError !== null && (
          <p className="error-banner-text" role="alert">
            {validationError}
          </p>
        )}
        {serverError !== null && <ErrorBanner error={serverError} />}

        <div className="group-dialog-field">
          <label htmlFor="create-group-title">Название группы</label>
          <input
            id="create-group-title"
            name="title"
            type="text"
            autoComplete="off"
            value={title}
            onChange={(event) => {
              setTitle(event.target.value)
            }}
          />
        </div>

        <div className="group-dialog-field">
          <label htmlFor="create-group-description">Описание группы</label>
          <input
            id="create-group-description"
            name="description"
            type="text"
            autoComplete="off"
            value={description}
            onChange={(event) => {
              setDescription(event.target.value)
            }}
          />
        </div>

        {contactsStatus === 'loading' && <p className="messenger-empty">Загрузка контактов…</p>}
        {contactsStatus === 'error' && (
          <div className="chat-panel-error">
            <ErrorBanner error={contactsError} />
            <button
              type="button"
              className="chat-panel-retry"
              onClick={() => {
                setReloadCount((count) => count + 1)
              }}
            >
              Повторить
            </button>
          </div>
        )}
        {contactsStatus === 'ready' && (
          <AddMembersPicker contacts={contacts} selectedIds={selectedIds} onToggle={toggleMember} />
        )}

        <div className="group-dialog-actions">
          <button type="submit" className="group-dialog-submit" disabled={pending}>
            {pending ? 'Создаётся…' : 'Создать группу'}
          </button>
          {onCancel !== undefined && (
            <button type="button" className="group-dialog-cancel" onClick={onCancel} disabled={pending}>
              Отмена
            </button>
          )}
        </div>
      </form>
    </section>
  )
}
