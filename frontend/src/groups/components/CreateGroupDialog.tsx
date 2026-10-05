/**
 * Модальная форма «Новый групповой чат» «Aethergram» (feature 008, US2,
 * T035; FR-023, ui-behavior §3, research §D): проекция #grpForm
 * нормативного прототипа specs/008-chat-window-styling/design/chats.html
 * — житель ЕДИНОЙ модальной оболочки ModalShell (T034, MessengerPage
 * монтирует форму на formId 'create-group', закрытие — Esc/фон/
 * «Отмена»/успех submit через onClose-колбэки владельца).
 *
 * - Поля прототипа: «Название» (#grpName, плейсхоллер «Название
 *   группового чата»), «Участники» + живой фильтр (#grpSearch —
 *   username/email без регистра, выбор переживает фильтр) и
 *   подборщик-пикер №20-контактов (#grpMembers: строки .pick-row —
 *   чекбокс + аватар + имя + подпись «username · email»; порядок №20
 *   серверный, FR-034). Поля описания в создании НЕТ — прототип его не
 *   несёт (описание редактируется позже, grpEditForm — T056).
 *
 * - Валидация (порядок прототипа): сперва ≥1 участник («Выберите хотя
 *   бы одного участника из контактов» — норматив FR-001), затем
 *   название 006 (validateGroupTitle: trim 1–64 — невалидный черновик
 *   не доходит до №27). Пустые состояния подборщика — прототипные
 *   «Нет контактов — сначала добавьте контакт» / «Ничего не найдено»;
 *   сбой №20 — .modal-err + «Повторить» (ожидание 004, FR-034).
 *
 * - Submit — один атомарный №27 `createGroup`: обрезанный title +
 *   memberUserIds в порядке выбора (точное №27-тело без description).
 *   Успех — ровно один тост «Групповой чат создан — {title}» (FR-025,
 *   тост прототипа; слот z-99 живёт в ToastProvider страницы поверх
 *   модали) и `onCreated(GroupView)` — MessengerPage закрывает оболочку,
 *   сходится №12 и открывает окно группы (quickstart §3.1). Серверная
 *   проблема (422 not_in_contacts, сеть) — .modal-err без закрытия:
 *   черновик и выбор остаются исправимыми.
 */
import { useCallback, useEffect, useMemo, useState, type SubmitEvent } from 'react'
import { listContacts } from '../../api/chats'
import type { ContactView } from '../../api/chats'
import { createGroup } from '../../api/groups'
import type { CreateGroupRequest, GroupView } from '../../api/groups'
import { problemMessage } from '../../auth/problem'
import { Avatar } from '../../ui/Avatar'
import { useToast } from '../../ui/Toast'
import { validateGroupTitle } from '../validation'
import './create-group.css'

export interface CreateGroupDialogProps {
  /** Hands over the №27 result (201 GroupView) — the page opens the group. */
  readonly onCreated?: (group: GroupView) => void
  /** Optional close control for the parent-managed shell lifetime. */
  readonly onCancel?: () => void
}

type ContactsStatus = 'loading' | 'ready' | 'error'

export function CreateGroupDialog({ onCreated, onCancel }: CreateGroupDialogProps) {
  const showToast = useToast()
  const [title, setTitle] = useState('')
  const [query, setQuery] = useState('')
  /** Insertion order = memberUserIds order of №27. */
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(() => new Set<string>())
  /** Validation/№27 problem of the last attempt; the form stays open. */
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  const [contacts, setContacts] = useState<readonly ContactView[]>([])
  const [contactsStatus, setContactsStatus] = useState<ContactsStatus>('loading')
  const [contactsError, setContactsError] = useState<string | null>(null)
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    let cancelled = false
    setContactsStatus('loading')
    void listContacts()
      .then((loaded) => {
        if (cancelled) {
          return
        }
        setContacts(loaded)
        setContactsError(null)
        setContactsStatus('ready')
      })
      .catch((cause: unknown) => {
        if (cancelled) {
          return
        }
        setContactsError(problemMessage(cause))
        setContactsStatus('error')
      })
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

  /** renderPickList прототипа: живой фильтр username/email без регистра. */
  const normalizedQuery = query.trim().toLowerCase()
  const visibleContacts = useMemo(() => {
    if (normalizedQuery === '') {
      return contacts
    }
    return contacts.filter(
      (contact) =>
        contact.user.username.toLowerCase().includes(normalizedQuery) ||
        contact.user.email.toLowerCase().includes(normalizedQuery),
    )
  }, [contacts, normalizedQuery])

  const handleSubmit = useCallback(
    (event: SubmitEvent<HTMLFormElement>) => {
      event.preventDefault()
      if (pending) {
        return
      }
      // Порядок прототипа (#grpForm submit): сперва участники, затем название.
      if (selectedIds.size === 0) {
        setError('Выберите хотя бы одного участника из контактов')
        return
      }
      const validatedTitle = validateGroupTitle(title)
      if (!validatedTitle.ok) {
        setError(validatedTitle.error)
        return
      }
      const body: CreateGroupRequest = {
        title: validatedTitle.title,
        memberUserIds: [...selectedIds],
      }
      setError(null)
      setPending(true)
      void Promise.resolve(createGroup(body))
        .then((group) => {
          showToast(`Групповой чат создан — ${validatedTitle.title}`)
          onCreated?.(group)
        })
        .catch((cause: unknown) => {
          setError(problemMessage(cause))
        })
        .finally(() => {
          setPending(false)
        })
    },
    [pending, selectedIds, title, onCreated, showToast],
  )

  return (
    <form className="grp-form" onSubmit={handleSubmit}>
      <label htmlFor="grp-name">Название</label>
      <input
        id="grp-name"
        name="title"
        type="text"
        className="grp-name"
        placeholder="Название группового чата"
        autoComplete="off"
        value={title}
        onChange={(event) => {
          setTitle(event.target.value)
        }}
      />

      <label htmlFor="grp-search">Участники</label>
      <input
        id="grp-search"
        type="text"
        className="grp-search"
        placeholder="Поиск контакта — имя, username или email"
        autoComplete="off"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value)
        }}
      />

      <div className="pick-list grp-members">
        {contactsStatus === 'loading' && <output className="pick-empty">Загрузка…</output>}
        {contactsStatus === 'error' && (
          <>
            <div className="modal-err" role="alert">
              {contactsError}
            </div>
            <div className="modal-btns">
              <button
                type="button"
                className="m-btn"
                onClick={() => {
                  setReloadCount((count) => count + 1)
                }}
              >
                Повторить
              </button>
            </div>
          </>
        )}
        {contactsStatus === 'ready' && contacts.length === 0 && (
          <div className="pick-empty">Нет контактов — сначала добавьте контакт</div>
        )}
        {contactsStatus === 'ready' && contacts.length > 0 && visibleContacts.length === 0 && (
          <div className="pick-empty">Ничего не найдено</div>
        )}
        {contactsStatus === 'ready' &&
          visibleContacts.map((contact) => (
            <label key={contact.user.id} className="pick-row">
              <input
                type="checkbox"
                aria-label={`Выбрать ${contact.user.username}`}
                checked={selectedIds.has(contact.user.id)}
                onChange={() => {
                  toggleMember(contact.user.id)
                }}
              />
              <Avatar source={contact.user.username} size={32} />
              <div className="c-main">
                <div className="c-top">
                  <span className="c-name">{contact.user.username}</span>
                </div>
                <div className="c-prev">
                  {contact.user.username} · {contact.user.email}
                </div>
              </div>
            </label>
          ))}
      </div>

      {/* #grpErr прототипа: постоянная строка ошибки (min-height держит
          компоновку), role=alert появляется вместе с текстом. */}
      <div className="modal-err" role={error !== null ? 'alert' : undefined}>
        {error}
      </div>
      <div className="modal-btns">
        <button type="button" className="m-btn" onClick={() => onCancel?.()} disabled={pending}>
          Отмена
        </button>
        <button type="submit" className="m-btn primary" disabled={pending}>
          Создать
        </button>
      </div>
    </form>
  )
}
