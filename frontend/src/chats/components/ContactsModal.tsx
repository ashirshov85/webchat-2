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
 * - Список: №20 `listContacts` на монтировании; КЛИЕНТСКАЯ сортировка —
 *   с 008a T022 по цепочке имён (`byDisplayChain`: locale `ru` прототипа,
 *   тай-брейк — прежний `byUsername` 'und', см. комментарии у
 *   компараторов); живой фильтр по подстроке [цепочка, username, email]
 *   без регистра; пустые состояния «Нет контактов — добавьте первого» /
 *   «Ничего не найдено»; сбой №20 — ошибка + «Повторить» с рефетчем
 *   (ожидание 004, FR-034).
 *
 * - Пометки из пропа `chats` (№12, владелец — MessengerPage): связанный
 *   direct-чат с `blockedByMe` → «заблокирован» (+ класс .blocked);
 *   контакта без чата → «чат удалён» (+ .nochat, подсказка title).
 *
 *   T097 (bug 16): чат удалён → №12 теряет пару, и блокировка контакта
 *   больше ниоткуда не проецировалась. Мерж ТРЁХ источников, приоритет
 *   сверху вниз:
 *   1) сессионный optimistic-override `Map<userId, boolean>` — пишется
 *      ТОЛЬКО собственными №23/№24-успехами этой модали (сбой/отмена не
 *      пишут): пометка «заблокирован»/.blocked строки без чата и
 *      переключение пункта «Разблокировать» появляются сразу;
 *   2) №12 связанного чата (живой чат — прежняя T089-семантика: рефетч
 *      владельца после №23/№24 сходится пропом chats; №12 свежее №20,
 *      снятого при монтировании, — при живом чате побеждает №12);
 *   3) №20 `ContactView.blockedByMe` (контракт 0.8.0) — корневой фикс:
 *      состояние строки без чата живёт после F5.
 *
 * - Клик по строке (Row/Enter/Space — FR-035): контакт со связанным чатом
 *   — №11 `ensureChat({peerUserId})` → `onOpenChat(ChatView)` (поведение
 *   004 сохранено, FR-034); контакт без чата — БЕЗ действия
 *   (Clarification FR-013: создание чата — только осознанным пунктом
 *   «Создать чат»).
 *
 * - Меню «⋯» (ContextMenu; FR-013/FR-014): «Переименовать» — ПЕРВЫЙ пункт
 *   (008a T022, ui-behavior §1.2 — см. блок ниже); «Заблокировать»/
 *   «Разблокировать»
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
 * (точное совпадание username/email без регистра — `@`-правило на сервере,
 * 0..1 ответ, ожидание 004); промах — спокойная ошибка «требуется точное
 * совпадание», №21 НЕ вызывается; находка — подтверждение ConfirmDialog
 * (имя <b> + подпись .confirm-sub «username · email», как askConfirm
 * прототипа) → №21 `addContact` (идемпотентно 201/200: контакт уже в
 * книге — тост «Уже в контактах — {username}», иначе «Контакт добавлен —
 * {username}») → №11 `ensureChat` → `onOpenChat(ChatView)` + рефетч №20
 * (новая строка в списке). Сбой №21/№11 — ошибка при живом списке
 * (ожидание 004). Пустой запрос — без запроса на сервер (004).
 *
 * Цепочка имён + «Переименовать» (feature 008a, US1, T022; FR-003/FR-005,
 * ui-behavior §1/§1.2): строки/сортировка/фильтр живут по ЕДИНОЙ цепочке
 * `resolveDisplayName(alias, displayName, username)` (ui/names, T018) —
 * `.c-name` и aria-label строки несут цепочку; сортировка — по цепочке
 * (locale `ru` прототипа renderContactsModal, кириллица раньше латиницы),
 * тай-брейк username — прежняя корневая коллация 'und'; фильтр — подстрока
 * без регистра по [цепочка, username, email]. Пункт «Переименовать» —
 * ПЕРВЫЙ в «⋯»-меню (перед «Заблокировать/Разблокировать»), открывает
 * форму «Имя контакта» (#renameForm прототипа: поле, предзаполненное
 * текущей цепочкой/alias, maxlength 64, readonly username/email .modal-ro):
 * сохранение №40 `setContactAlias` — полностью очищенное поле = явный
 * `null` (сброс alias, возврат к displayName → username); непустая строка
 * только из пробелов — ошибка «Укажите имя контакта» (строка прототипа
 * chats.html:1648, зеркалит 400 invalid_alias) БЕЗ запроса. Успех — строка
 * обновляется ответом №40, тост «Контакт переименован — {цепочка}» и
 * доклад владельцу (onContactRenamed → рефетч №12: peerAlias сходится в
 * «Чатах» без F5, паттерн T089); сбой — инлайн-ошибка формы, список жив.
 * Отдельной кнопки сброса нет — пустым значением той же формы (YAGNI).
 *
 * Встраивание форм в ModalShell MessengerPage (заголовки «Контакты»/
 * «Добавить контакт»/«Редактировать контакт», закрытие Esc/фоном) —
 * T034; форма переименования поднимает formId 'rename' (T022).
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
  setContactAlias,
  unblockUser,
} from '../../api/chats'
import type { ChatListItem, ChatView, ContactView, UserWithAlias } from '../../api/chats'
import { problemMessage } from '../../auth/problem'
import { usePresenceStatus } from '../../presence/usePresence'
import { Avatar } from '../../ui/Avatar'
import { initialsOf } from '../../ui/avatar'
import { ConfirmDialog, type ConfirmVariant } from '../../ui/ConfirmDialog'
import { ContextMenu, type MenuItem } from '../../ui/ContextMenu'
import { useToast } from '../../ui/Toast'
import { resolveDisplayName } from '../../ui/names'
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
   * T089 (Bug 11б): успешный №22 «Удалить контакт» — владелец оболочки
   * синхронизирует книгу №20 страницы (reloadContacts), и
   * «Добавить в контакты» шестерёнки/members-модали возвращается
   * сразу, а не после закрытия оболочки/перезагрузки.
   */
  readonly onContactRemoved?: (userId: string) => void
  /**
   * T022 (008a US1): успешный №40 «Переименовать» — владелец оболочки
   * рефетчит №12 (паттерн T089), и peerAlias/имена peer в «Чатах»
   * сходятся живьём, без закрытия оболочки/F5 (ui-behavior §1.2 «все
   * поверхности владельца обновляются»; realtime-события смены имени
   * нет — рефетч-семантика). Доклад — только об успехе: сбой остаётся
   * инлайн-ошибкой формы переименования (ожидание 004).
   */
  readonly onContactRenamed?: (userId: string) => void
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
  | { readonly kind: 'add-contact'; readonly user: UserWithAlias }

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
function contactFlags(bound: BoundChat | null, blocked: boolean): string[] {
  const flags: string[] = []
  if (blocked) {
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

/**
 * Цепочка отображаемого имени строки (008a T022; ui-behavior §1):
 * `alias → displayName → username` — единая точка `resolveDisplayName`
 * (ui/names, T018). №20 `ContactView` несёт alias вызывающего поверх
 * `user.displayName` (контракт 0.9.0).
 */
function chainOf(contact: ContactView): string {
  return resolveDisplayName(contact.alias, contact.user.displayName, contact.user.username)
}

/**
 * FR-005 сортировка по цепочке (008a T022): locale `ru` ПРОТОТИПА
 * renderContactsModal (chats.html:1292 — `displayName.localeCompare(
 * …, 'ru')`) — кириллица раньше латиницы; тай-брейк — прежняя
 * корневая коллация 'und' (`byUsername`, «текущая коллация»
 * ui-behavior §1): совпадающие цепочки различаются username.
 */
function byDisplayChain(a: ContactView, b: ContactView): number {
  return chainOf(a).localeCompare(chainOf(b), 'ru') || byUsername(a, b)
}

/** Параметры подтверждения по виду действия (ConfirmDialog, ui-behavior §3). */
interface ConfirmUi {
  readonly text: ReactNode
  readonly confirmLabel: string
  readonly variant: ConfirmVariant
}

/** Форма модали (переключение внутри одной оболочки, data-model 3.3): список / добавление / переименование. */
export type ModalForm = 'list' | 'add' | 'rename'

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
      // 008a T020/T022 (FR-004): инициалы — из цепочки отображаемого
      // имени строки, цвет — от username (переименование не перекрашивает).
      initials={initialsOf(chainOf(contact))}
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
  onContactRenamed,
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
  /**
   * Форма «Имя контакта» (008a T022, ui-behavior §1.2): цель №40 и
   * предзаполненное поле (текущая цепочка строки — alias, если задан).
   */
  const [renameTarget, setRenameTarget] = useState<ContactView | null>(null)
  const [renameQuery, setRenameQuery] = useState('')
  const [renameError, setRenameError] = useState<string | null>(null)
  const [renaming, setRenaming] = useState(false)
  /**
   * T097 (а): сессионный optimistic-override блокировки — пишется только
   * собственными №23/№24-успехами этой модали (см. докблок компонента:
   * приоритет мержа override → №12 → №20).
   */
  const [blockedOverrides, setBlockedOverrides] = useState<ReadonlyMap<string, boolean>>(new Map())

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

  /** FR-005: клиентская сортировка по цепочке (ru прототипа, тай-брейк
   * username 'und') + живой фильтр [цепочка, username, email] без регистра. */
  const normalizedQuery = query.trim().toLowerCase()
  const visibleContacts = useMemo(() => {
    const sorted = [...contacts].sort(byDisplayChain)
    if (normalizedQuery === '') {
      return sorted
    }
    return sorted.filter(
      (contact) =>
        chainOf(contact).toLowerCase().includes(normalizedQuery) ||
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

  /** T097 (bug 16): слитое состояние блокировки строки — override → №12 → №20. */
  const blockedStateOf = useCallback(
    (contact: ContactView, bound: BoundChat | null): boolean => {
      const override = blockedOverrides.get(contact.user.id)
      if (override !== undefined) {
        return override
      }
      if (bound !== null) {
        return bound.blockedByMe
      }
      return contact.blockedByMe
    },
    [blockedOverrides],
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

  /** «⋯» → «Переименовать» (ui-behavior §1.2): форма «Имя контакта»,
   * предзаполненная текущей цепочкой строки (alias, если задан). */
  const openRenameForm = useCallback(
    (contact: ContactView) => {
      setRenameTarget(contact)
      setRenameQuery(chainOf(contact))
      setRenameError(null)
      setActionError(null)
      setForm('rename')
      onFormChange?.('rename')
    },
    [onFormChange],
  )

  /** «Отмена» формы переименования — назад к списку, БЕЗ запроса (SC-007). */
  const closeRenameForm = () => {
    setRenameTarget(null)
    setRenameError(null)
    setForm('list')
    onFormChange?.('list')
  }

  /**
   * Submit #renameForm (№40, ui-behavior §1.2): полностью очищенное
   * поле — явный `null` (сброс alias → цепочка возвращается к
   * displayName → username); непустая строка только из пробелов —
   * ошибка «Укажите имя контакта» (прототип chats.html:1648, зеркалит
   * 400 invalid_alias) БЕЗ запроса; иные значения сервер сохраняет
   * после trim (1–64). Успех — строка обновляется ответом №40, тост
   * «Контакт переименован — {цепочка}», доклад владельцу (№12-рефетч);
   * сбой — инлайн-ошибка, форма жива.
   */
  const handleRenameSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (renaming || renameTarget === null) {
      return
    }
    // Непустая строка только из пробелов — не сброс, а ошибка валидации
    // (зеркаль серверной 400 invalid_alias) — запроса нет вовсе.
    if (renameQuery.length > 0 && renameQuery.trim().length === 0) {
      setRenameError('Укажите имя контакта')
      return
    }
    setRenaming(true)
    setRenameError(null)
    const target = renameTarget
    // Полностью очищенное поле — явный null (сброс alias), не «как есть».
    void setContactAlias(target.user.id, {
      alias: renameQuery.trim() === '' ? null : renameQuery,
    })
      .then((updated) => {
        setContacts((current) =>
          current.map((item) => (item.user.id === updated.user.id ? updated : item)),
        )
        showToast(`Контакт переименован — ${chainOf(updated)}`)
        setRenameTarget(null)
        setForm('list')
        onFormChange?.('list')
        // Владелец рефетчит №12 — peerAlias/имена peer «Чатов» сходятся
        // живьём (паттерн T089), без закрытия оболочки.
        onContactRenamed?.(updated.user.id)
      })
      .catch((error: unknown) => {
        setRenameError(problemMessage(error))
      })
      .finally(() => {
        setRenaming(false)
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
    const blocked = blockedStateOf(menuContact, bound)
    return [
      {
        // ПЕРВЫЙ пункт «⋯»-меню (ui-behavior §1.2) — перед
        // «Заблокировать/Разблокировать».
        label: 'Переименовать',
        onSelect: () => {
          openRenameForm(menuContact)
        },
      },
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
  }, [menuContact, chats, blockedStateOf, openRenameForm])

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
            // T097 (а): сессионная пометка строки без чата (№12 пары
            // больше не несёт) — до/вместо №12-рефетча владельца.
            setBlockedOverrides((current) => new Map(current).set(user.id, true))
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
            setBlockedOverrides((current) => new Map(current).set(user.id, false))
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
        case 'add-contact': {
          // askConfirm прототипа: имя + подпись «username · email» (.confirm-sub).
          // 008a T023: имя — ЦЕПОЧКА найденного №19 (`alias → displayName →
          // username`, ui-behavior §1); username/email в подписи различают
          // совпадающие имена (identity-контекст).
          const foundName = resolveDisplayName(
            pending.user.alias,
            pending.user.displayName,
            pending.user.username,
          )
          return {
            text: (
              <>
                <b>{foundName}</b>
                <span className="confirm-sub">
                  {pending.user.username} · {pending.user.email}
                </span>
              </>
            ),
            confirmLabel: 'Добавить',
            variant: 'primary',
          }
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

  /**
   * Форма «Имя контакта» (#renameForm прототипа, 008a T022; ui-behavior
   * §1.2) — вместо списка, в той же оболочке: поле, предзаполненное
   * цепочкой (alias, если задан), maxlength 64; username/email — ТОЛЬКО
   * для чтения (.modal-ro, прототип «меняется только имя»).
   */
  if (form === 'rename' && renameTarget !== null) {
    return (
      <form className="rename-form" onSubmit={handleRenameSubmit}>
        <label htmlFor="ctc-rename-input">Имя контакта</label>
        <input
          id="ctc-rename-input"
          className="rename-input"
          placeholder={chainOf(renameTarget)}
          maxLength={64}
          autoComplete="off"
          value={renameQuery}
          disabled={renaming}
          onChange={(event) => {
            setRenameQuery(event.target.value)
          }}
        />
        <div className="modal-ro">
          <span>username</span>
          <b>{renameTarget.user.username}</b>
        </div>
        <div className="modal-ro">
          <span>email</span>
          <b>{renameTarget.user.email || '—'}</b>
        </div>
        {renameError !== null && (
          <div className="modal-err" role="alert">
            {renameError}
          </div>
        )}
        <div className="modal-btns">
          <button type="button" className="m-btn" disabled={renaming} onClick={closeRenameForm}>
            Отмена
          </button>
          <button type="submit" className="m-btn primary" disabled={renaming}>
            Сохранить
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
            const blocked = blockedStateOf(contact, bound)
            const flags = contactFlags(bound, blocked)
            // Цепочка имён (008a T022, ui-behavior §1): `.c-name` и
            // aria-label строки несут отображаемое имя, username — фолбэк.
            const displayName = chainOf(contact)
            return (
              <button
                type="button"
                key={contact.user.id}
                className={
                  'pick-row ctc-row' +
                  (blocked ? ' blocked' : '') +
                  (bound === null ? ' nochat' : '')
                }
                aria-label={`Контакт ${displayName}`}
                title={bound !== null ? 'Открыть чат' : 'Чат удалён — создайте через меню ⋯'}
                onClick={() => {
                  handleRowActivate(contact)
                }}
              >
                <ContactAvatar contact={contact} />
                <div className="c-main">
                  <div className="c-top">
                    <span className="c-name">{displayName}</span>
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
          Добавить новый контакт
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
