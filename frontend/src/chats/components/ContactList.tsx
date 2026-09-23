/**
 * «Контакты» list (feature 004, T059; FR-015/016/017): the caller's
 * address book served by №20 `GET /contacts`. The alphabetical
 * ordering is SERVER-owned (research.md 004 §8): the login/email
 * toggle only switches the `sort` query parameter and refetches, so
 * the client never duplicates the collation logic (FR-015).
 *
 * Row click is the FR-016 entry into a dialog: `ensureChat` (№11)
 * with the contact's id resolves the single pair dialog (creating it
 * when missing — the pair chat exists independently of contacts) and
 * hands the resulting `ChatView` to the parent via `onOpenChat`.
 *
 * The «×» action (№22 `DELETE /contacts/{userId}`) removes ONLY the
 * address book entry: the pair chat and its whole history stay
 * untouched (FR-017) — the row disappears here, the chat stays in
 * «Чаты» and the correspondence continues. The endpoint is
 * idempotent (`204` either way), so a double click is harmless; the
 * row is dropped locally and the next refetch reconciles.
 *
 * `refreshKey` lets the parent (T060 wiring) bump a counter to
 * refetch — e.g. after UserSearchBox added a contact; data otherwise
 * flows through №20 only. Failures of the row actions (ensure with
 * `peer_not_found`, network) render in a banner above the list
 * without unmounting it.
 */
import { useCallback, useEffect, useState } from 'react'
import { ensureChat, listContacts, removeContact } from '../../api/chats'
import type { ChatView, ContactSort, ContactView } from '../../api/chats'
import { ErrorBanner } from './ErrorBanner'

export interface ContactListProps {
  /** Hands over the №11 result after a contact row click (open dialog). */
  readonly onOpenChat?: (chat: ChatView) => void
  /** Increment to refetch №20 (a contact was added elsewhere in the panel). */
  readonly refreshKey?: number
  /** The open dialog's peer: their contact row renders highlighted. */
  readonly activePeerUserId?: string | null
  /** Initial sort field; FR-015 default is `login`. */
  readonly defaultSort?: ContactSort
}

type ContactListStatus = 'loading' | 'ready' | 'error'

export function ContactList({
  onOpenChat,
  refreshKey = 0,
  activePeerUserId = null,
  defaultSort = 'login',
}: ContactListProps) {
  const [contacts, setContacts] = useState<ContactView[]>([])
  const [sort, setSort] = useState<ContactSort>(defaultSort)
  const [status, setStatus] = useState<ContactListStatus>('loading')
  const [error, setError] = useState<unknown>(null)
  const [reloadCount, setReloadCount] = useState(0)
  /** Row-action failures (№11 ensure / №22 remove): the list stays rendered. */
  const [actionError, setActionError] = useState<unknown>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const loaded = await listContacts(sort)
        if (cancelled) {
          return
        }
        setContacts(loaded)
        setError(null)
        setStatus('ready')
      } catch (cause) {
        if (cancelled) {
          return
        }
        setError(cause)
        setStatus('error')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [sort, refreshKey, reloadCount])

  const handleOpen = useCallback(
    (userId: string) => {
      setActionError(null)
      void (async () => {
        try {
          const chat = await ensureChat({ peerUserId: userId })
          onOpenChat?.(chat)
        } catch (cause) {
          setActionError(cause)
        }
      })()
    },
    [onOpenChat],
  )

  const handleRemove = useCallback((userId: string) => {
    setActionError(null)
    void (async () => {
      try {
        await removeContact(userId)
        setContacts((previous) => previous.filter((contact) => contact.user.id !== userId))
      } catch (cause) {
        setActionError(cause)
      }
    })()
  }, [])

  return (
    <div className="contact-list">
      <div className="contact-sort" role="group" aria-label="Сортировка контактов">
        <button
          type="button"
          className={
            sort === 'login'
              ? 'contact-sort-button contact-sort-button-active'
              : 'contact-sort-button'
          }
          aria-pressed={sort === 'login'}
          onClick={() => {
            setSort('login')
          }}
        >
          По логину
        </button>
        <button
          type="button"
          className={
            sort === 'email'
              ? 'contact-sort-button contact-sort-button-active'
              : 'contact-sort-button'
          }
          aria-pressed={sort === 'email'}
          onClick={() => {
            setSort('email')
          }}
        >
          По email
        </button>
      </div>

      {actionError !== null && (
        <div className="chat-panel-error">
          <ErrorBanner
            error={actionError}
            onDismiss={() => {
              setActionError(null)
            }}
          />
        </div>
      )}

      {status === 'loading' && <p className="messenger-empty">Загрузка контактов…</p>}
      {status === 'error' && (
        <div className="chat-panel-error">
          <ErrorBanner error={error} />
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
      {status === 'ready' && contacts.length === 0 && (
        <p className="messenger-empty">Контактов пока нет — найдите пользователя через поиск</p>
      )}
      {contacts.length > 0 && (
        <ul className="contact-list-items" aria-label="Список контактов">
          {contacts.map((contact) => (
            <li key={contact.user.id} className="contact-row">
              <button
                type="button"
                className={
                  contact.user.id === activePeerUserId
                    ? 'contact-row-main contact-row-main-active'
                    : 'contact-row-main'
                }
                aria-current={contact.user.id === activePeerUserId ? 'true' : undefined}
                onClick={() => {
                  handleOpen(contact.user.id)
                }}
              >
                <span className="chat-item-title">{contact.user.username}</span>
                <span className="contact-row-email">{contact.user.email}</span>
              </button>
              <button
                type="button"
                className="contact-row-remove"
                aria-label={`Удалить ${contact.user.username} из контактов`}
                title="Удалить из контактов"
                onClick={() => {
                  handleRemove(contact.user.id)
                }}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
