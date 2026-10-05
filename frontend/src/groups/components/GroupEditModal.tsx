/**
 * Модальная форма «Редактировать групповой чат» (feature 008, US4, T056;
 * FR-023, ui-behavior §3, research §D): житель ЕДИНОЙ оболочки ModalShell
 * (MessengerPage, formId 'group-edit'), проекция #grpEditForm нормативного
 * прототипа specs/008-chat-window-styling/design/chats.html — название,
 * описание, состав: черновик ростера №28 (минус собственная строка — уход
 * это «Выйти из чата» шестерёнки, №32 на себе self_forbidden; строка
 * владельца недоступна «✕» любому зрителю — №32 role_hierarchy_violation)
 * с удалением «✕» и добавлением из №20 «+» (активные не предлагаются,
 * removed-участник-контакт возвращается в предложение — renderGrpEditAdd
 * прототипа), валидация 006 (≥1 участник, title 1–64, description ≤256;
 * порядок прототипа — участники, затем название, затем описание).
 *
 * Submit — №29 `updateGroup` ВСЕГДА (обрезанный title, описание только
 * непустое) + №31 `addMembers` (один атомарный батч добавленных, порядок
 * выбора) + №32 `kickMember` (по каждому удалённому, порядок ростера);
 * операции идут последовательно, первая сбойная останавливает конвейер
 * (.modal-err при живом черновике — №29-успех уже сошёлся через
 * onUpdated). Успех ВСЕГО submit — ровно один тост «Групповой чат
 * обновлён — {title}» (T045: тост выдаёт владелец операции-формы; слот
 * z-99 ToastProvider страницы поверх модали).
 *
 * №20 грузится на монтировании (эффект НЕ зависит от собственного
 * статуса — T073-регрессия: ответ не теряется контролируемым релизом);
 * сбой — .modal-err + «Повторить» (ожидание 004, FR-034), пустые
 * состояния предложения — прототипные «Все контакты уже в чате» /
 * «Ничего не найдено».
 *
 * Контракт закреплён тестами GroupEditModal.test.tsx (T049, TDD-красные
 * до этой реализации): аватар группы пересчитывается при переименовании
 * детерминированным colorOf(титул) само́й площадкой отображения (Avatar
 * без storage-ключа) — отдельного поля в №29 нет (FR-023, SC-003).
 */
import { useCallback, useEffect, useMemo, useState, type SubmitEvent } from 'react'
import { listContacts } from '../../api/chats'
import type { ContactView } from '../../api/chats'
import { addMembers, kickMember, updateGroup } from '../../api/groups'
import type { GroupMember, GroupView, UpdateGroupRequest } from '../../api/groups'
import { problemMessage } from '../../auth/problem'
import { Avatar } from '../../ui/Avatar'
import { useToast } from '../../ui/Toast'
import { validateGroupDescription, validateGroupTitle } from '../validation'
import './group-edit-modal.css'

export interface GroupEditModalProps {
  /** The group chatId — the №29/№31/№32 target. */
  readonly chatId: string
  /** №28 `title` — the form opens prefilled with it. */
  readonly title: string
  /** №28 `description` — the form opens prefilled with it ('' when null). */
  readonly description: string | null
  /** №28 active roster — the composition draft base (minus the own row). */
  readonly members: readonly GroupMember[]
  /**
   * The viewer's user id — the own row never joins the draft (№32 on
   * self is `self_forbidden`); when omitted (prototype semantics — the
   * editor IS the creator), the unkickable owner row is filtered alone.
   */
  readonly currentUserId?: string
  /** №29 success: the updated GroupView — the parent converges №28/closes. */
  readonly onUpdated?: (group: GroupView) => void
  /** №31 success: the returned members — the parent converges №28 (№31-response). */
  readonly onMembersAdded?: (members: readonly GroupMember[]) => void
  /** №32 success — the parent converges its №28 state (void response). */
  readonly onMemberRemoved?: () => void
  /** Optional close control for the parent-managed shell lifetime. */
  readonly onCancel?: () => void
}

type ContactsStatus = 'loading' | 'ready' | 'error'

/** Строки, доступные «✕» черновика (renderGrpEditMembers прототипа). */
function draftableMembers(
  members: readonly GroupMember[],
  currentUserId: string | undefined,
): readonly GroupMember[] {
  // Владелец недоступен «✕» любому зрителю (№32 role_hierarchy_violation);
  // собственная строка — уходом «Выйти из чата», не здесь (№32 self_forbidden).
  return members.filter((member) => member.role !== 'owner' && member.user.id !== currentUserId)
}

export function GroupEditModal({
  chatId,
  title,
  description,
  members,
  currentUserId,
  onUpdated,
  onMembersAdded,
  onMemberRemoved,
  onCancel,
}: GroupEditModalProps) {
  // T045 (FR-025): тост завершённого submit — слот ToastProvider страницы.
  const showToast = useToast()

  // Черновик открывается снимком ТЕКУЩЕГО №28 (прецедент GroupInfoPanel):
  // приехавший group.updated-кадр не затирает правки середины редактуры.
  const [titleDraft, setTitleDraft] = useState(() => title)
  const [descriptionDraft, setDescriptionDraft] = useState(() => description ?? '')
  /** Порядок = №28-ростер минус own/owner, добавления — в порядке «+». */
  const [draftIds, setDraftIds] = useState<readonly string[]>(() =>
    draftableMembers(members, currentUserId).map((member) => member.user.id),
  )
  /** Живой фильтр предложения №20 (renderGrpEditAdd прототипа). */
  const [query, setQuery] = useState('')
  /** Валидационная/серверная проблема последней попытки; форма жива. */
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  const [contacts, setContacts] = useState<readonly ContactView[]>([])
  const [contactsStatus, setContactsStatus] = useState<ContactsStatus>('loading')
  const [contactsError, setContactsError] = useState<string | null>(null)
  const [reloadCount, setReloadCount] = useState(0)

  // №20 на монтировании + «Повторить» (паттерн CreateGroupDialog T035):
  // эффект зависит ТОЛЬКО от reloadCount — собственная запись статуса не
  // перезапускает его и не помечает ответ `cancelled` (ядро T072-регрессии:
  // контролируемый релиз посадки сходится, «Загрузка контактов…» не висит).
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

  const rosterById = useMemo(
    () => new Map(members.map((member) => [member.user.id, member] as const)),
    [members],
  )
  const contactsById = useMemo(
    () => new Map(contacts.map((contact) => [contact.user.id, contact] as const)),
    [contacts],
  )
  const draftIdSet = useMemo(() => new Set(draftIds), [draftIds])

  /** Имя строки черновика: ростер №28 либо предложение №20 (добавление). */
  const usernameOf = useCallback(
    (userId: string): string => {
      const member = rosterById.get(userId)
      if (member !== undefined) {
        return member.user.username
      }
      const contact = contactsById.get(userId)
      return contact !== undefined ? contact.user.username : ''
    },
    [rosterById, contactsById],
  )

  const addDraftMember = useCallback((userId: string) => {
    setDraftIds((previous) => (previous.includes(userId) ? previous : [...previous, userId]))
  }, [])

  const removeDraftMember = useCallback((userId: string) => {
    setDraftIds((previous) => previous.filter((id) => id !== userId))
  }, [])

  const normalizedQuery = query.trim().toLowerCase()
  /** renderGrpEditAdd прототипа: активные (черновик) не предлагаются. */
  const offer = useMemo(
    () =>
      contacts.filter((contact) => {
        if (draftIdSet.has(contact.user.id)) {
          return false
        }
        if (normalizedQuery === '') {
          return true
        }
        return (
          contact.user.username.toLowerCase().includes(normalizedQuery) ||
          contact.user.email.toLowerCase().includes(normalizedQuery)
        )
      }),
    [contacts, draftIdSet, normalizedQuery],
  )

  const handleSubmit = useCallback(
    (event: SubmitEvent<HTMLFormElement>) => {
      event.preventDefault()
      if (pending) {
        return
      }
      // Порядок прототипа (#grpEditForm submit): сперва участники, затем
      // название, затем описание (валидация 006 — клиентская половина).
      if (draftIds.length === 0) {
        setError('В групповом чате должен остаться хотя бы один участник')
        return
      }
      const validatedTitle = validateGroupTitle(titleDraft)
      if (!validatedTitle.ok) {
        setError(validatedTitle.error)
        return
      }
      const validatedDescription = validateGroupDescription(descriptionDraft)
      if (!validatedDescription.ok) {
        setError(validatedDescription.error)
        return
      }
      // №29 всегда несёт title (форма требует его — empty_patch недостижим),
      // описание — только непустое после обрезки.
      const body: UpdateGroupRequest = { title: validatedTitle.title }
      if (validatedDescription.description !== undefined) {
        body.description = validatedDescription.description
      }
      // Диф состава: №31 — батч новых id (порядок выбора), №32 — по каждому
      // удалённому (порядок ростера); удалённый-и-возвращённый — нейтрален.
      const rosterIds = new Set(members.map((member) => member.user.id))
      const addedIds = draftIds.filter((id) => !rosterIds.has(id))
      const removedMembers = draftableMembers(members, currentUserId).filter(
        (member) => !draftIdSet.has(member.user.id),
      )
      setError(null)
      setPending(true)
      void (async () => {
        try {
          // Конвейер №29 → №31 → №32: первая сбойная останавливает остаток
          // (.modal-err при живом черновике), успехи сходятся сразу.
          const view = await updateGroup(chatId, body)
          onUpdated?.(view)
          if (addedIds.length > 0) {
            const added = await addMembers(chatId, { userIds: [...addedIds] })
            onMembersAdded?.(added)
          }
          for (const member of removedMembers) {
            await kickMember(chatId, member.user.id)
            onMemberRemoved?.()
          }
          // FR-025 (T045): один тост на ЗАВЕРШЁННЫЙ submit — текст
          // grpEditForm прототипа (отправленное название).
          showToast(`Групповой чат обновлён — ${validatedTitle.title}`)
        } catch (cause) {
          setError(problemMessage(cause))
        } finally {
          setPending(false)
        }
      })()
    },
    [
      pending,
      draftIds,
      titleDraft,
      descriptionDraft,
      members,
      currentUserId,
      draftIdSet,
      chatId,
      onUpdated,
      onMembersAdded,
      onMemberRemoved,
      showToast,
    ],
  )

  return (
    <form className="grp-edit-form" onSubmit={handleSubmit}>
      <label htmlFor="grp-edit-name">Название</label>
      <input
        id="grp-edit-name"
        name="title"
        type="text"
        className="grp-edit-name"
        autoComplete="off"
        value={titleDraft}
        onChange={(event) => {
          setTitleDraft(event.target.value)
        }}
      />

      <label htmlFor="grp-edit-description">Описание</label>
      <input
        id="grp-edit-description"
        name="description"
        type="text"
        className="grp-edit-description"
        placeholder="Описание группового чата"
        autoComplete="off"
        value={descriptionDraft}
        onChange={(event) => {
          setDescriptionDraft(event.target.value)
        }}
      />

      <label htmlFor="grp-edit-search">Участники</label>
      <div className="pick-list grp-edit-members">
        {draftIds.length === 0 ? (
          <div className="pick-empty">Нет участников</div>
        ) : (
          draftIds.map((userId) => {
            const username = usernameOf(userId)
            return (
              <div className="pick-row" key={userId}>
                <Avatar source={username} size={32} />
                <div className="c-main">
                  <div className="c-top">
                    <span className="c-name">{username}</span>
                  </div>
                </div>
                <button
                  type="button"
                  className="m-rm"
                  title="Удалить участника"
                  aria-label={`Удалить участника ${username}`}
                  disabled={pending}
                  onClick={() => {
                    removeDraftMember(userId)
                  }}
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M6 6l12 12M18 6L6 18" />
                  </svg>
                </button>
              </div>
            )
          })
        )}
      </div>

      <input
        id="grp-edit-search"
        type="text"
        className="grp-edit-search"
        placeholder="Добавить контакт — имя, username или email"
        aria-label="Поиск контакта для добавления"
        autoComplete="off"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value)
        }}
      />
      <div className="pick-list grp-edit-add">
        {contactsStatus === 'loading' && (
          <output className="pick-empty">Загрузка контактов…</output>
        )}
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
        {contactsStatus === 'ready' && offer.length === 0 && (
          <div className="pick-empty">
            {normalizedQuery === '' ? 'Все контакты уже в чате' : 'Ничего не найдено'}
          </div>
        )}
        {contactsStatus === 'ready' &&
          offer.map((contact) => (
            <div className="pick-row" key={contact.user.id}>
              <Avatar source={contact.user.username} size={32} />
              <div className="c-main">
                <div className="c-top">
                  <span className="c-name">{contact.user.username}</span>
                </div>
                <div className="c-prev">{contact.user.username}</div>
              </div>
              <button
                type="button"
                className="m-add"
                title="Добавить участника"
                aria-label={`Добавить участника ${contact.user.username}`}
                disabled={pending}
                onClick={() => {
                  addDraftMember(contact.user.id)
                }}
              >
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </button>
            </div>
          ))}
      </div>

      {/* #grpEditErr прототипа: постоянная строка ошибки (min-height
          держит компоновку), role=alert появляется вместе с текстом. */}
      <div className="modal-err" role={error !== null ? 'alert' : undefined}>
        {error}
      </div>
      <div className="modal-btns">
        <button type="button" className="m-btn" onClick={() => onCancel?.()} disabled={pending}>
          Отмена
        </button>
        <button type="submit" className="m-btn primary" disabled={pending}>
          Сохранить
        </button>
      </div>
    </form>
  )
}
