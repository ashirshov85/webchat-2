/**
 * Модальная форма «Контакты» «Aethergram» (feature 008, US2, T031; FR-011,
 * FR-013, ui-behavior §3, data-model 1.6/2.5): житель ЕДИНОЙ модальной
 * оболочки ModalShell (T034, MessengerPage) — проекция contactsForm
 * прототипа specs/008-chat-window-styling/design/chats.html (`#ctcSearch`,
 * `#ctcList`, строки `.pick-row.ctc-row`, кебаб `.c-menu`), инлайн-
 * подтверждения через ConfirmDialog — переключение формы внутри оболочки,
 * без второй подложки (data-model 3.3). Стили — contacts-modal.css.
 *
 * Реализовано в T031 (список/меню; контракт закреплён красными тестами
 * T028 — написаны ДО реализации, конституция VI):
 *
 * - Список: №20 `listContacts` на монтировании; КЛИЕНТСКАЯ сортировка
 *   username (`byUsername`: алфавит без регистра, латиница раньше
 *   кириллицы — см. комментарий у компаратора; displayName в контрактах
 *   004–007 нет — alias-имена вне фичи, ui-behavior §9; «displayName →
 *   username» FR-011 вырождается в username); живой фильтр по подстроке
 *   username/email без регистра; пустые состояния «Нет контактов —
 *   добавьте первого» / «Ничего не найдено»; сбой №20 — ошибка +
 *   «Повторить» с рефетчем (ожидание 004, FR-034).
 *
 * - Пометки из пропа `chats` (№12, владелец — MessengerPage): связанный
 *   direct-чат с `blockedByMe` → «заблокирован» (+ класс .blocked);
 *   контакта без чата → «чат удалён» (+ .nochat, подсказка title).
 *
 * - Клик по строке (Row/Enter/Space — FR-035): контакт со связанным чатом
 *   — №11 `ensureChat({peerUserId})` → `onOpenChat(ChatView)` (поведение
 *   004 сохранено, FR-034); контакт без чата — БЕЗ действия
 *   (Clarification FR-013: создание чата — только осознанным пунктом
 *   «Создать чат»).
 *
 * - Меню «⋯» (ContextMenu; FR-013/FR-014): «Заблокировать»/«Разблокировать»
 *   (по blockedByMe связанного чата; подтверждение — №23/№24, тосты
 *   «Контакт заблокирован/разблокирован — {username}»); «Удалить чат»
 *   (есть связанный; подтверждение danger — №14, тост «Чат удалён —
 *   контакт сохранён», контакт остаётся) / «Создать чат» (чата нет;
 *   подтверждение — №11 + `onOpenChat`); «Удалить контакт» (danger +
 *   разделитель; подтверждение danger — №22, строка исчезает локально,
 *   чат и история сохраняются — FR-017, тост «Контакт удалён — чат
 *   сохранён»). Отмена подтверждения — действие не выполняется (SC-007).
 *   Сбои действий — ошибка видна (.modal-err), список жив (ожидание 004).
 *   T089 (Bug 11): успехи №23/№24/№22 докладывают владельцу оболочки
 *   (onContactBlockToggled/onContactRemoved) — страница рефетчит №12/№20,
 *   и пометки строки/композер/«шестерёнка» сходятся живьём (паттерн
 *   onChatDeleted T037); сбой владельцу не докладывается.
 *
 * Форма «Добавить контакт» (FR-012, T032) — проекция #addForm прототипа
 * (label «Username или email — точное совпадание», поле, .modal-btns
 * «Отмена»/submit «Добавить»): №19 `searchUsers` с ТРИММИНГОМ запроса
 * (точное совпадение username/email без регистра — `@`-правило на сервере,
 * 0..1 ответ, ожидание 004); промах — спокойная ошибка «требуется точное
 * совпадение», №21 НЕ вызывается; находка — подтверждение ConfirmDialog
 * (имя <b> + подпись .confirm-sub «username · email», как askConfirm
 * прототипа) → №21 `addContact` (идемпотентно 201/200: контакт уже в
 * книге — тост «Уже в контактах — {username}», иначе «Контакт добавлен —
 * {username}») → №11 `ensureChat` → `onOpenChat(ChatView)` + рефетч №20
 * (новая строка в списке). Сбой №21/№11 — ошибка при живом списке
 * (ожидание 004). Пустой запрос — без запроса на сервер (004).
 *
 * Встраивание форм в ModalShell MessengerPage (заголовки «Контакты»/
 * «Добавить контакт», закрытие Esc/фоном) — T034.
 *
 * Presence-точки контактов (feature 008, US3, T042; FR-024,
 * design-tokens §4, контракт T040): статус — ТОЛЬКО из presenceStore
 * 007 через `usePresenceStatus(contact.user.id)` (per-row регистрация
 * поверхности + №36-бэкфилл с микротаск-батчингом — один запрос на
 * монтирование списка): online — зелёная мерцающая, offline — тусклая;
 * bug 12 (T090): unknown точки НЕ выводит — до первого №36 строка без
 * индикатора, ложный «офлайн» запрещён (семантика 007).
 */
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
  type SubmitEvent,
} from 'react'
import {
  addContact,
  blockUser,
  deleteChat,
  ensureChat,
  listContacts,
  removeContact,
  searchUsers,
  unblockUser,
} from '../../api/chats'
import type { ChatListItem, ChatView, ContactView } from '../../api/chats'
import type { PublicUser } from '../../api/auth'
import { problemMessage } from '../../auth/problem'
import { usePresenceStatus } from '../../presence/usePresence'
import { Avatar } from '../../ui/Avatar'
import { ConfirmDialog, type ConfirmVariant } from '../../ui/ConfirmDialog'
import { ContextMenu, type MenuItem } from '../../ui/ContextMenu'
import { useToast } from '../../ui/Toast'
import './contacts-modal.css'

export interface ContactsModalProps {
  /** №12-строки (useChatList MessengerPage): связанный чат + blockedByMe. */
  readonly chats: readonly ChatListItem[]
  /** Открывает переписку (№11 ChatView): клик по контакту с чатом, «Создать чат», добавление. */
  readonly onOpenChat: (chat: ChatView) => void
  /**
   * T037 (FR-032): успешный №14 «Удалить чат» строки — владелец
   * оболочки возвращает ОТКРЫТЫЙ чат к «Чат не выбран» (без
   * автоперехода) и сход №12; модаль остаётся открытой (контакт
   * сохранён, FR-017).
   */
  readonly onChatDeleted?: (chatId: string) => void
  /**
   * T089 (Bug 11а): успешный №23/№24 «⋯»-меню — владелец оболочки
   * рефетчит №12 (паттерн onChatDeleted T037), и пометка
   * «заблокирован» строки, композер открытого чата (FR-022) и пункты
   * «шестерёнки» сходятся живьём, без перезагрузки. Доклад — только об
   * успехе: сбой остаётся инлайн-ошибкой модали (ожидание 004).
   */
  readonly onContactBlockToggled?: (userId: string, blockedByMe: boolean) => void
  /**
   * T089 (Bug 11б): успешный №22 «Удалить контакт» — владелец
   * оболочки синхронизирует книгу №20 страницы (reloadContacts), и
   * «Добавить в контакты» шестерёнки/members-модали возвращается
   * сразу, а не после закрытия оболочки/перезагрузки.
   */
  readonly onContactRemoved?: (userId: string) => void
  /**
   * T034 (ModalShell): сообщает владельцу оболочки о внутреннем
   * переключении list ↔ add — page отображает это в formId
   * 'contacts' | 'add-contact' (заголовок оболочки и вход фокуса,
   * data-model 3.3 — форма меняется, подложка остаётся одна).
   */
  readonly onFormChange?: (form: ModalForm) => void
}

/** Связанный direct-чат контакта в терминах строки (№12). */
interface BoundChat {
  readonly chatId: string
  readonly blockedByMe: boolean
}

/** Ожидаемое подтверждение «⋯»-меню и формы добавления (ConfirmDialog, SC-007). */
type PendingConfirm =
  | { readonly kind: 'block'; readonly contact: ContactView }
  | { readonly kind: 'unblock'; readonly contact: ContactView }
  | { readonly kind: 'delete-chat'; readonly contact: ContactView; readonly chatId: string }
  | { readonly kind: 'create-chat'; readonly contact: ContactView }
  | { readonly kind: 'remove-contact'; readonly contact: ContactView }
  | { readonly kind: 'add-contact'; readonly user: PublicUser }

type ListStatus = 'loading' | 'ready' | 'error'

/** №12-строка контакта: direct-чат с собеседником = contact.user (type у direct-элементов опционален). */
function boundChatOf(chats: readonly ChatListItem[], contact: ContactView): BoundChat | null {
  for (const item of chats) {
    if ((item.type ?? 'direct') === 'direct' && item.peer?.id === contact.user.id) {
      return { chatId: item.chatId, blockedByMe: item.blockedByMe ?? false }
    }
  }
  return null
}

/** Пометки строки (renderContactsModal прототипа): «заблокирован», «чат удалён». */
function contactFlags(bound: BoundChat | null): string[] {
  const flags: string[] = []
  if (bound?.blockedByMe) {
    flags.push('заблокирован')
  }
  if (bound === null) {
    flags.push('чат удалён')
  }
  return flags
}

/** capFirst прототипа: «Заблокирован, чат удалён». */
function capFirst(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/**
 * FR-011 сортировка username: алфавитная, без регистра, латиница раньше
 * кириллицы, «анна» < «Борис» (фиксация тестов T028). Корневая коллация
 * ICU ('und'): ru-тюнинг CLDR переупорядочивает кириллицу ПЕРЕД
 * латиницей (Node/Browser full-ICU: «анна, Борис, alice»), что противоречит
 * контракту — корневой порядок даёт ровно ожидаемое, сохраняя
 * регистронезависимость (прототип renderContactsModal, §9 chats.html).
 */
function byUsername(a: ContactView, b: ContactView): number {
  return a.user.username.localeCompare(b.user.username, 'und')
}

/** Параметры подтверждения по виду действия (ConfirmDialog, ui-behavior §3). */
interface ConfirmUi {
  readonly text: ReactNode
  readonly confirmLabel: string
  readonly variant: ConfirmVariant
}

/** Форма модали (переключение внутри одной оболочки, data-model 3.3): список / добавление. */
export type ModalForm = 'list' | 'add'

/**
 * Аватар контакта с presence-точкой (T042): отдельный компонент — хук
 * статуса нужен СТРОКЕ (контракт T040: per-peer ключ contact.user.id),
 * а строки выводятся циклом. Регистрация поверхности и №36-бэкфилл —
 * внутри `usePresenceStatus` (007). Bug 12 (T090): `unknown` точку НЕ
 * выводит — скрытие не выдаёт статуса (ложного «офлайна» нет), точка
 * появляется, когда №36 сойдётся к online/offline.
 */
function ContactAvatar({ contact }: { readonly contact: ContactView }) {
  const status = usePresenceStatus(contact.user.id)
  return (
    <Avatar
      source={contact.user.username}
      size={32}
      presenceDot={status === 'unknown' ? null : status}
    />
  )
}

export function ContactsModal({
  chats,
  onOpenChat,
  onChatDeleted,
  onContactBlockToggled,
  onContactRemoved,
  onFormChange,
}: ContactsModalProps) {
  const showToast = useToast()
  const [status, setStatus] = useState<ListStatus>('loading')
  const [contacts, setContacts] = useState<readonly ContactView[]>([])
  const [listError, setListError] = useState<unknown>(null)
  const [query, setQuery] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [menuContact, setMenuContact] = useState<ContactView | null>(null)
  const [menuAnchor, setMenuAnchor] = useState<DOMRect | null>(null)
  const [pending, setPending] = useState<PendingConfirm | null>(null)
  const [form, setForm] = useState<ModalForm>('list')
  const [addQuery, setAddQuery] = useState('')
  const [addError, setAddError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)

  /** №20 + «Повторить»: единственный путь загрузки списка. */
  const loadContacts = useCallback(() => {
    setStatus('loading')
    setListError(null)
    listContacts()
      .then((list) => {
        setContacts(list)
        setStatus('ready')
      })
      .catch((error: unknown) => {
        setListError(error)
        setStatus('error')
      })
  }, [])
  useEffect(() => {
    loadContacts()
  }, [loadContacts])

  /** FR-011: клиентская сортировка username (ru-locale) + живой фильтр username/email. */
  const normalizedQuery = query.trim().toLowerCase()
  const visibleContacts = useMemo(() => {
    const sorted = [...contacts].sort(byUsername)
    if (normalizedQuery === '') {
      return sorted
    }
    return sorted.filter(
      (contact) =>
        contact.user.username.toLowerCase().includes(normalizedQuery) ||
        contact.user.email.toLowerCase().includes(normalizedQuery),
    )
  }, [contacts, normalizedQuery])

  /** №11 → onOpenChat; клик без чата отсекается вызывающим (Clarification FR-013). */
  const openChatWith = useCallback(
    (contact: ContactView) => {
      setActionError(null)
      Promise.resolve(ensureChat({ peerUserId: contact.user.id }))
        .then((view) => {
          onOpenChat(view)
        })
        .catch((error: unknown) => {
          setActionError(problemMessage(error))
        })
    },
    [onOpenChat],
  )

  const handleRowActivate = (contact: ContactView) => {
    if (boundChatOf(chats, contact) === null) {
      return
    }
    openChatWith(contact)
  }

  /** #ctcAdd прототипа → openModal('add'): форма добавления с чистым полем. */
  const openAddForm = () => {
    setAddQuery('')
    setAddError(null)
    setActionError(null)
    setForm('add')
    onFormChange?.('add')
  }

  /**
   * №19 с триммингом (FR-012): пустой запрос — без похода на сервер (004);
   * 0..1 ответ: промах — спокойная ошибка прототипа без №21, находка —
   * подтверждение поверх (pending), поле сохранено до исхода.
   */
  const handleAddSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    const trimmed = addQuery.trim()
    if (trimmed === '' || adding) {
      return
    }
    setAdding(true)
    setAddError(null)
    void searchUsers(trimmed)
      .then((users) => {
        const [found] = users
        if (found === undefined) {
          setAddError('Пользователь не найден — требуется точное совпадение username или email')
          return
        }
        setPending({ kind: 'add-contact', user: found })
      })
      .catch((error: unknown) => {
        setAddError(problemMessage(error))
      })
      .finally(() => {
        setAdding(false)
      })
  }

  /** Кебаб «⋯»: якорь — rect кнопки (паттерн MainMenuButton); клик не
   * проваливается в строку (menuBtn-early-return прототипа). */
  const handleMenuButtonClick = (
    event: ReactMouseEvent<HTMLButtonElement>,
    contact: ContactView,
  ) => {
    event.stopPropagation()
    setMenuAnchor(event.currentTarget.getBoundingClientRect())
    setMenuContact(contact)
  }

  /** Пункты «⋯»-меню строки (FR-013): состав зависит от связанного чата. */
  const menuItems = useMemo<readonly MenuItem[]>(() => {
    if (menuContact === null) {
      return []
    }
    const bound = boundChatOf(chats, menuContact)
    const blocked = bound?.blockedByMe === true
    return [
      {
        label: blocked ? 'Разблокировать' : 'Заблокировать',
        onSelect: () => {
          setPending({ kind: blocked ? 'unblock' : 'block', contact: menuContact })
        },
      },
      bound !== null
        ? {
            label: 'Удалить чат',
            onSelect: () => {
              setPending({ kind: 'delete-chat', contact: menuContact, chatId: bound.chatId })
            },
          }
        : {
            label: 'Создать чат',
            onSelect: () => {
              setPending({ kind: 'create-chat', contact: menuContact })
            },
          },
      {
        label: 'Удалить контакт',
        danger: true,
        sepBefore: true,
        onSelect: () => {
          setPending({ kind: 'remove-contact', contact: menuContact })
        },
      },
    ]
  }, [menuContact, chats])

  /** Подтверждённое действие (submit ConfirmDialog): форма возвращается к
   * списку, операция идёт своим ходом; успех — ровно один тост (FR-025),
   * сбой — ошибка при живом списке (ожидание 004). */
  const runPendingAction = () => {
    if (pending === null) {
      return
    }
    const user = pending.kind === 'add-contact' ? pending.user : pending.contact.user
    setPending(null)
    setActionError(null)
    switch (pending.kind) {
      case 'block':
        void Promise.resolve(blockUser(user.id))
          .then(() => {
            showToast(`Контакт заблокирован — ${user.username}`)
            // T089 (Bug 11а): владелец рефетчит №12 — пометка строки,
            // композер и «шестерёнка» сходятся живьём (без F5).
            onContactBlockToggled?.(user.id, true)
          })
          .catch((error: unknown) => {
            setActionError(problemMessage(error))
          })
        break
      case 'unblock':
        void Promise.resolve(unblockUser(user.id))
          .then(() => {
            showToast(`Контакт разблокирован — ${user.username}`)
            onContactBlockToggled?.(user.id, false)
          })
          .catch((error: unknown) => {
            setActionError(problemMessage(error))
          })
        break
      case 'delete-chat':
        void Promise.resolve(deleteChat(pending.chatId))
          .then(() => {
            // T037 (FR-032): открытый чат вернётся к «Чат не выбран» —
            // решает владелец оболочки (MessengerPage), модаль жива.
            onChatDeleted?.(pending.chatId)
            showToast('Чат удалён — контакт сохранён')
          })
          .catch((error: unknown) => {
            setActionError(problemMessage(error))
          })
        break
      case 'create-chat':
        void Promise.resolve(ensureChat({ peerUserId: user.id }))
          .then((view) => {
            onOpenChat(view)
          })
          .catch((error: unknown) => {
            setActionError(problemMessage(error))
          })
        break
      case 'remove-contact':
        void Promise.resolve(removeContact(user.id))
          .then(() => {
            setContacts((current) => current.filter((item) => item.user.id !== user.id))
            showToast('Контакт удалён — чат сохранён')
            // T089 (Bug 11б): №20 книги страницы сходится сразу —
            // «Добавить в контакты» шестерёнки/members-модали
            // возвращается без закрытия оболочки/F5.
            onContactRemoved?.(user.id)
          })
          .catch((error: unknown) => {
            setActionError(problemMessage(error))
          })
        break
      case 'add-contact':
        // Идемпотентность №21 (201/200 без дублей): «уже в контактах» видно
        // по текущей книге — тост один, далее переписка (FR-012).
        setForm('list')
        onFormChange?.('list')
        setAddQuery('')
        {
          const known = contacts.some((item) => item.user.id === user.id)
          void Promise.resolve(addContact(user.id))
            .then(() => {
              showToast(
                known
                  ? `Уже в контактах — ${user.username}`
                  : `Контакт добавлен — ${user.username}`,
              )
              return ensureChat({ peerUserId: user.id })
            })
            .then((view) => {
              onOpenChat(view)
              loadContacts()
            })
            .catch((error: unknown) => {
              setActionError(problemMessage(error))
            })
        }
        break
    }
  }

  /** Подтверждение поверх списка — переключением формы внутри одной
   * оболочки, без наложения (data-model 3.3; T034 формализует в ModalShell). */
  if (pending !== null) {
    const username =
      pending.kind === 'add-contact' ? pending.user.username : pending.contact.user.username
    const confirmUi: ConfirmUi = (() => {
      switch (pending.kind) {
        case 'block':
          return {
            text: (
              <>
                Заблокировать контакт <b>{username}</b>?
              </>
            ),
            confirmLabel: 'Заблокировать',
            variant: 'danger',
          }
        case 'unblock':
          return {
            text: (
              <>
                Разблокировать контакт <b>{username}</b>?
              </>
            ),
            confirmLabel: 'Разблокировать',
            variant: 'primary',
          }
        case 'delete-chat':
          return {
            text: (
              <>
                Удалить чат с <b>{username}</b>?
              </>
            ),
            confirmLabel: 'Удалить',
            variant: 'danger',
          }
        case 'create-chat':
          return {
            text: (
              <>
                Создать чат с <b>{username}</b>?
              </>
            ),
            confirmLabel: 'Создать',
            variant: 'primary',
          }
        case 'remove-contact':
          return {
            text: (
              <>
                Удалить контакт <b>{username}</b>? Чат и история сохранятся.
              </>
            ),
            confirmLabel: 'Удалить',
            variant: 'danger',
          }
        case 'add-contact':
          // askConfirm прототипа: имя + подпись «username · email» (.confirm-sub).
          return {
            text: (
              <>
                <b>{username}</b>
                <span className="confirm-sub">
                  {username} · {pending.user.email}
                </span>
              </>
            ),
            confirmLabel: 'Добавить',
            variant: 'primary',
          }
      }
    })()
    return (
      <ConfirmDialog
        text={confirmUi.text}
        confirmLabel={confirmUi.confirmLabel}
        variant={confirmUi.variant}
        onConfirm={runPendingAction}
        onCancel={() => {
          setPending(null)
        }}
      />
    )
  }

  /** Форма добавления (#addForm прототипа) — вместо списка, в той же оболочке. */
  if (form === 'add') {
    return (
      <form className="add-form" onSubmit={handleAddSubmit}>
        <label htmlFor="ctc-add-query">Username или email — точное совпадание</label>
        <input
          id="ctc-add-query"
          className="add-query"
          placeholder="например, hargrove или h.hargrove@aethergram.io"
          autoComplete="off"
          value={addQuery}
          onChange={(event) => {
            setAddQuery(event.target.value)
          }}
        />
        {addError !== null && (
          <div className="modal-err" role="alert">
            {addError}
          </div>
        )}
        <div className="modal-btns">
          <button
            type="button"
            className="m-btn"
            onClick={() => {
              setForm('list')
              onFormChange?.('list')
            }}
          >
            Отмена
          </button>
          <button type="submit" className="m-btn primary" disabled={adding}>
            Добавить
          </button>
        </div>
      </form>
    )
  }

  return (
    <div className="contacts-form">
      <input
        className="ctc-search"
        placeholder="Поиск контакта — имя, username или email"
        autoComplete="off"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value)
        }}
      />
      {status === 'error' && (
        <>
          <div className="modal-err" role="alert">
            {problemMessage(listError)}
          </div>
          <div className="modal-btns">
            <button type="button" className="m-btn" onClick={loadContacts}>
              Повторить
            </button>
          </div>
        </>
      )}
      {status === 'ready' && (
        <div className="pick-list ctc-list">
          {contacts.length === 0 && (
            <div className="pick-empty">Нет контактов — добавьте первого</div>
          )}
          {contacts.length > 0 && visibleContacts.length === 0 && (
            <div className="pick-empty">Ничего не найдено</div>
          )}
          {visibleContacts.map((contact) => {
            const bound = boundChatOf(chats, contact)
            const blocked = bound?.blockedByMe === true
            const flags = contactFlags(bound)
            return (
              <button
                type="button"
                key={contact.user.id}
                className={
                  'pick-row ctc-row' +
                  (blocked ? ' blocked' : '') +
                  (bound === null ? ' nochat' : '')
                }
                aria-label={`Контакт ${contact.user.username}`}
                title={bound !== null ? 'Открыть чат' : 'Чат удалён — создайте через меню ⋯'}
                onClick={() => {
                  handleRowActivate(contact)
                }}
              >
                <ContactAvatar contact={contact} />
                <div className="c-main">
                  <div className="c-top">
                    <span className="c-name">{contact.user.username}</span>
                  </div>
                  {flags.length > 0 && <div className="c-prev">{capFirst(flags.join(', '))}</div>}
                </div>
                <button
                  type="button"
                  className="c-menu"
                  title="Действия с контактом"
                  aria-haspopup="menu"
                  aria-expanded={menuContact?.user.id === contact.user.id}
                  onClick={(event) => {
                    handleMenuButtonClick(event, contact)
                  }}
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                    <circle cx="12" cy="5" r="1.8" />
                    <circle cx="12" cy="12" r="1.8" />
                    <circle cx="12" cy="19" r="1.8" />
                  </svg>
                </button>
              </button>
            )
          })}
        </div>
      )}
      {/* #ctcAdd прототипа: .modal-btns под списком — путь к форме добавления. */}
      <div className="modal-btns">
        <button type="button" className="m-btn primary" onClick={openAddForm}>
          Добавить контакт
        </button>
      </div>
      {actionError !== null && (
        <div className="modal-err" role="alert">
          {actionError}
        </div>
      )}
      <ContextMenu
        open={menuContact !== null}
        anchor={menuContact !== null ? menuAnchor : null}
        items={menuItems}
        onClose={() => {
          setMenuContact(null)
        }}
      />
    </div>
  )
}
