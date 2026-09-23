/**
 * Exact user search + «add to contacts» (feature 004, T059; FR-016,
 * quickstart §3.5.3–3.5.4): №19 `GET /users/search` matches the FULL
 * email OR the FULL login case-insensitively (`@` in the query →
 * email, otherwise username), so the answer is 0..1 users — an empty
 * result is a normal «no match» and renders as a calm hint, never as
 * an error. The `@`-rule itself lives on the server; the client just
 * sends the trimmed query.
 *
 * «Добавить в контакты» calls №21 `POST /contacts`: `201` for a fresh
 * contact, `200` with the existing one when it is already added — no
 * duplicate either way; both paths notify `onContactAdded` so the
 * parent (T060 wiring) can refresh the ContactList. Adding oneself
 * (found by searching own email/login) → `422 self_forbidden` and the
 * rest of the failures render through the shared problem banner
 * (429 flood includes the Retry-After countdown via problemMessage).
 *
 * A request sequence guard keeps out-of-order responses from
 * overwriting a newer search with an older one.
 */
import { useCallback, useRef, useState } from 'react'
import type { SubmitEvent } from 'react'
import { addContact, searchUsers } from '../../api/chats'
import type { ContactView } from '../../api/chats'
import type { PublicUser } from '../../api/auth'
import { ErrorBanner } from './ErrorBanner'

export interface UserSearchBoxProps {
  /** Notifies after a successful №21 add (201 or 200) — refresh the list. */
  readonly onContactAdded?: (contact: ContactView) => void
}

export function UserSearchBox({ onContactAdded }: UserSearchBoxProps) {
  const [query, setQuery] = useState('')
  /** `undefined` — no search yet; `null` — searched, nothing found. */
  const [result, setResult] = useState<PublicUser | null | undefined>(undefined)
  const [searching, setSearching] = useState(false)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const searchSequence = useRef(0)

  const handleSearch = useCallback(
    (event: SubmitEvent<HTMLFormElement>) => {
      event.preventDefault()
      const trimmed = query.trim()
      if (trimmed === '' || searching || adding) {
        return
      }
      const sequence = ++searchSequence.current
      setSearching(true)
      setError(null)
      setResult(undefined)
      void (async () => {
        try {
          const users = await searchUsers(trimmed)
          if (searchSequence.current !== sequence) {
            return
          }
          setResult(users.length > 0 ? users[0] : null)
        } catch (cause) {
          if (searchSequence.current !== sequence) {
            return
          }
          setError(cause)
        } finally {
          if (searchSequence.current === sequence) {
            setSearching(false)
          }
        }
      })()
    },
    [query, searching, adding],
  )

  const handleAdd = useCallback(
    (user: PublicUser) => {
      if (adding) {
        return
      }
      setAdding(true)
      setError(null)
      void (async () => {
        try {
          const contact = await addContact(user.id)
          onContactAdded?.(contact)
          setResult(undefined)
        } catch (cause) {
          setError(cause)
        } finally {
          setAdding(false)
        }
      })()
    },
    [adding, onContactAdded],
  )

  return (
    <div className="user-search">
      <form className="user-search-form" onSubmit={handleSearch}>
        <input
          className="user-search-input"
          type="text"
          value={query}
          placeholder="Email или логин"
          aria-label="Поиск пользователя"
          autoComplete="off"
          onChange={(event) => {
            setQuery(event.target.value)
          }}
        />
        <button
          type="submit"
          className="user-search-button"
          disabled={searching || adding || query.trim() === ''}
        >
          {searching ? 'Поиск…' : 'Найти'}
        </button>
      </form>

      {error !== null && (
        <div className="chat-panel-error">
          <ErrorBanner
            error={error}
            onDismiss={() => {
              setError(null)
            }}
          />
        </div>
      )}

      {result === null && (
        <p className="user-search-hint">Никого не нашли — проверьте email или логин</p>
      )}

      {result !== null && result !== undefined && (
        <div className="user-search-result">
          <span className="user-search-result-info">
            <span className="chat-item-title">{result.username}</span>
            <span className="contact-row-email">{result.email}</span>
          </span>
          <button
            type="button"
            className="user-search-add"
            disabled={adding}
            onClick={() => {
              handleAdd(result)
            }}
          >
            {adding ? 'Добавляем…' : 'Добавить в контакты'}
          </button>
        </div>
      )}
    </div>
  )
}
