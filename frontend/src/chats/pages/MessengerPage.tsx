/**
 * Messenger page: the application shell of the messenger — the
 * «Aethergram» corpus-«machine» (feature 008, US1, T018; FR-001,
 * FR-004): the `.machine > .frame-body` grid carries the chats sidebar
 * and the open dialog window as its `.panel` surfaces (`.sidebar` /
 * `.chat`, design-tokens §3) — the 004–007 wiring below (hooks,
 * routing, actions) is unchanged. US2 (T029, FR-006): the sidebar is
 * tab-free — contacts moved out of it into the modal forms (T031+).
 *
 * US2 single modal shell (008 T034; FR-026, data-model 1.6/3.3,
 * ui-behavior §3): EVERY modal form of the app is an inhabitant of
 * ONE `ModalShell` mounted here — the main menu items (T030) open
 * contacts / add-contact / rename-contact (ContactsModal — its internal
 * list ↔ add ↔ rename switch lifts into the shell formId via
 * onFormChange; the rename form is the #renameForm projection of the
 * prototype, 008a T022) / profile
 * (ProfileModal) / create-group (the grpForm projection of the
 * prototype, 008 T035), and the header confirmations (№14/№23/№24)
 * ride the 'confirm' formId. A formId switch NEVER
 * adds a second `.modal-back` (edge case data-model 3.3 — the
 * confirmation swaps the inhabitant inside the same shell); closure
 * is uniform — Esc / backdrop press+release / «Отмена» / submit
 * success → closeShell. The page ToastProvider keeps the single
 * toast slot above the modal (z-99, ui-behavior §5).
 * Mounted on the protected
 * route `/` (with the `/chat` alias — the SSO landing path from
 * feature 003). Without an open chat the window carries the US1 empty
 * state «Чат не выбран» (FR-032 — the prototype is normative, FR-001).
 *
 * T036 wires the dialog window to the outbox (FR-012): every send goes
 * through `useOutbox`, so unsent messages survive reloads and render
 * «отправляется»/«не отправлено» after the server messages; the 201/200
 * acknowledgement replaces the optimistic entry with the confirmed
 * server copy («доставлено»), and `failed` entries expose the manual
 * retry (same `clientMessageId`) and local delete actions. Messages
 * sent from another device arrive via the own SSE stream and render
 * «доставлено» immediately (US2-6).
 *
 * T060 wires the panel (T057–T059) and its actions: a panel row /
 * contact click opens the pair dialog; the dialog header carries the
 * action menu with confirmations («диалоговые меню»):
 *
 *  * «Удалить чат» (№14, FR-021): per-user history hiding + the local
 *    outbox records of the chat are purged (`outbox.purgeChat`) — the
 *    dialog closes, the list refetches and the chat disappears
 *    including its local «отправляется» entries. The contacts-form №14
 *    of the SAME chat converges through `handleChatDeleted` (008 T037,
 *    FR-032): the OPEN window folds to the «Чат не выбран» empty state
 *    with NO auto-transition to a neighbouring chat;
 *  * «Заблокировать»/«Разблокировать» (№23/№24, FR-020): idempotent
 *    block toggles driven by `blockedByMe` (the only block projection
 *    the API exposes); after either action the list refetches so the
 *    «заблокирован» mark and the badge converge to the server state.
 *    The composer lock rides the same flag (008 T036, FR-022): a
 *    blocked direct chat renders MessageInput with the block hint —
 *    field and «ОТПРАВИТЬ» disabled, the ONLY input lock of the app.
 *
 * The unread badge of the open dialog resets locally (FR-014) as its
 * messages render — the same display events that advance the read
 * watermark inside useChatMessages (T044).
 *
 * Delivery-resilience integration (feature 005, T023): `useSync`
 * mounts the §3.1 catch-up loop on every SSE (re)open — its applied
 * pages feed this page through `onChatUpdate`: the chat list adopts
 * previews/positions and materializes new chats (US1-6), the open
 * dialog merges pages with the usual dedup-by-id `seq`-order
 * reconcile (US1-3), and truncation drops deleted history (US1-5).
 * Applied realtime frames confirm through the same shared №25
 * batcher as the pages (sync-protocol.md §2): every applied
 * `message.created` frame acks its `seq` and advances the local
 * cursor — realtime and catch-up move the delivery position alike.
 * The SyncIndicator (T020) lights up in the panel while a cycle runs.
 *
 * US2 queue overflow (feature 005, T028, FR-005): the panel-level
 * QueueOverflowBanner listens to the outbox eviction events of T026 —
 * it spans all chats (the outbox storage is per-user), so it lives
 * here and not inside a dialog; the evicted records themselves render
 * «не отправлено (переполнение очереди)» in their dialogs (T028).
 *
 * Group creation entry + group window (feature 006, T029; US1,
 * quickstart §3.1): the panel carries the «Создать группу» button
 * that swaps into the CreateGroupDialog (T026) — its №27 `onCreated`
 * result closes the dialog, refetches №12 (the group materializes
 * from the server aggregate for the creator at once) and OPENS the
 * group window on the fresh `GroupView`. A group row of the unified
 * list (T028) opens the same window: the header renders the group
 * title, and the gear menu carries the GROUP items only (T054,
 * FR-023) — the direct-only №14/№23/№24 entries never appear there
 * (blocks never apply to groups). The window body is the
 * chatId-agnostic MessageList/MessageInput pair: the №16 history of
 * an active member loads already, while SENDING before the US2 gate
 * (T035) may be refused by the server — the documented interim US1
 * state (tasks.md US1 Dependencies), surfaced by the outbox as the
 * usual «не отправлено» + retry.
 *
 * US2 wiring (feature 006, T038, FR-012): opening a group window
 * fetches its №28 roster and feeds it to the chatId-agnostic dialog
 * pair — MessageList switches to the group variant (sender
 * attribution + ✓✓ by `othersReadUpToSeq`) and useChatMessages
 * switches its `chat.read`/№17 semantics to the group MAX watermark
 * (✓✓ once any one other member has read).
 * The roster is guarded by chatId, so a direct dialog opened next
 * never sees the stale group roster.
 *
 * US3/US4 group forms (feature 006 T046a → 008 T057; FR-015/FR-023):
 * the №28 lifecycle of the open group window — the snapshot AND the
 * live roster/metadata — lives in `useGroup` (one №28 per open,
 * optimistic `group.*` frames on the shared №18 stream, `reload()`
 * convergence), which feeds the dialog pair (attribution/✓✓, the
 * T038 need) and the SHELL FORMS: since T057 the gear's «Участники»/
 * «Редактировать чат» items open the GroupMembersModal/GroupEditModal
 * inhabitants (T055/T056) of the single ModalShell — the interim №28
 * card under the header (006 GroupInfoPanel/LeaveDeleteControls/
 * MemberList/AddMembersPicker) is deleted, its №28 loading/error
 * states live in the shell slot. The №31/№29 success converges
 * through `reload()`; the №32/№34/№35 roster actions mount their
 * mutex with useGroupMembers (T047). The full realtime set
 * (`group.you_removed` etc.) lands with T048/T058.
 *
 * US5 privacy wiring (feature 006, T058; realtime-group-events.md
 * §5.2/§5.5, FR-010): the FINAL `group.you_removed` frame closes an
 * OPEN group window at once — no «зависший» заголовок/композер of a
 * chat the user has no membership in anymore; the chat switch effect
 * above folds the group card/menu together with the window, and
 * useGroup/useChatMessages reset on the null chatId, so nothing of
 * the removed group keeps rendering. The same frame tombstones the
 * group's chatId in the SHARED №25 batcher (`drop`, §5.5): №25 is
 * all-or-refusal, so a pending or racing ack of the removed group
 * would reject the WHOLE batch with 403 not_participant — the
 * batcher-side tombstone also absorbs the late acks useSync/
 * useChatMessages may still feed through the §1 order race.
 *
 * US6 leave/delete wiring (feature 006, T066 → 008 T054/T057; FR-005/
 * FR-006): the №33/№30 entries live in the gear menu behind the shell
 * 'confirm' form (SC-007) — «Выйти из чата» for admin/member,
 * «Удалить чат» for the owner (the card-only LeaveDeleteControls are
 * deleted, T057) — and a 204 closes the window at once (the §3.6
 * `group.you_removed {reason:'left'}` / §3.5 `group.deleted` frames of
 * the same commit converge «Чаты» and the batcher tombstone
 * deterministically; the №12 reload the handler fires covers the
 * at-most-once loss of the frame). The `group.deleted` frame itself
 * rides the SAME T058 wiring: it closes an OPEN group window of the
 * hard-deleted chat and tombstones the chatId in the №25 batcher.
 *
 * T045 (US3; FR-025, ui-behavior §5, data-model 1.4): ровно один тост
 * (~3 с) на каждую ЗАВЕРШАЮЩУЮСЯ операцию во всех точках. Формы-
 * обитатели оболочки выдают свои тосты сами (ContactsModal №21/№22/
 * №23/№24/№14, ProfileModal №38, CreateGroupDialog №27 — слоты
 * T028/T031–T035); операции-владельцы СТРАНИЦЫ — «шестерёнка» чата
 * (T054): direct №14 «Чат удалён — контакт сохранён», №23/№24 «Контакт
 * заблокирован/разблокирован — {username}», №21 «Контакт добавлен/
 * Уже в контактах — {username}» и групповые №33/№30 окна
 * («Вы вышли из чата — {title}», «Групповой чат удалён») — живут в
 * механизме ПОД провайдером (MessengerMachine ниже), поэтому страница
 * тонкая: <ToastProvider> + машина. Тексты — дословно из прототипа
 * (deleteChat/askBlock/askUnblock/askLeaveGroup/addContact); сбои
 * тостом НЕ отмечаются — только инлайн-ошибки поверхностей (контракт
 * форм T028–T035); тост — результат операции, завершившейся на сервере.
 *
 * US5 звук приёма (T064; FR-028, research §F, ui-behavior §6): страница
 * подключает ровно ДВА триггера «латунного звоночка» ui/sound (T061/
 * T063 — синтез и молчаливый пропуск запрета автозвука живут там):
 * реальное время — onMessageCreated(null, …) одного сигнала на
 * входящее событие ЛЮБОГО чата, включая фоновые; массовая доставка —
 * счётчик входящих применённых страниц цикла catch-up (onChatUpdate)
 * сбрасывается одним chimeOnSyncBatch(итог) на завершении цикла
 * (фронт syncing true→false). История №16, пагинация №15 и отправка
 * сигналом не сопровождаются вовсе (Clarification: отправка — только
 * визуально), собственные сообщения молчат (в т.ч. с другого
 * устройства). Настроек и персистентности нет — «всегда включено».
 *
 * US5 burger-drawer (T065; FR-029, design-tokens §9, ui-behavior §7,
 * data-model 3.1): на ≤900px сайдбар — выдвижной drawer поверх контента
 * с затемнением. Плавающая кнопка каталога `.burger` (fixed 42px,
 * скрыта ≥901px CSS прототипа) открывает состояние openSidebar
 * прототипа дословно: `.sidebar.open` + `.backdrop.show` + морф
 * `.burger.open` (+ aria-expanded). Закрытие: повторный burger, клик
 * по затемнению, выбор чата (closeSidebar едет вместе с selectChat) и
 * Esc — СТРОГО верхний слой: слушатель document-bubble гасит drawer
 * только когда модальной оболочки выше нет (ModalShell слушает Esc на
 * document без stopPropagation — охрана «нет слоёв выше» на стороне
 * drawer, 3.1); ctx-menu/members-tip выше гасят Esc capture-фазой
 * (T013/T043) и сюда не доходят вовсе. Возврат на широкий экран
 * сбрасывает drawer (backdrop не ограничен media-блоком — открытое
 * состояние не должно заливать широкий экран, где сайдбар снова
 * статичен в каркасе). Тесты — MessengerPage.drawer.test.tsx (T062).
 *
 * Клавиатура drawer (T070; FR-035, SC-008, ui-behavior §1 — норматив
 * spec.md, сам прототип фокуса не ведает): открытый drawer — клетка
 * Tab-ловушки (словарь FOCUSABLE_SELECTOR ModalShell, Tab/Shift+Tab по
 * кругу; модаль выше — владеет Tab сама, 3.1), закрытие возвращает
 * фокус на burger-инициатор, закрытый off-canvas сайдбар исключён из
 * табуляции (inert — transform-скрытие оставляло бы кнопки достижимыми
 * невидимками, что ломало бы «логичный порядок табуляции»). Тесты —
 * MessengerPage.keyboard.test.tsx.
 *
 * US5 адаптация к visual viewport (T066; FR-029, research §H): экранная
 * клавиатура сжимает visual viewport, но не layout-viewport — `100dvh`
 * её не видит. Слушатель useVisualViewport (ниже) пишет на body
 * `--vvh`/`--vvo` (высота/offsetTop), machine.css сводит по ним дно
 * машины к видимому дну и гасит прокрутку страницы на ≥901px
 * (`html,body{overflow:hidden}` скоупом body:has(.machine)) — композер
 * остаётся над клавиатурой, лента сжимается, прокрутки страницы нет
 * (quickstart E1–E2, SC-005). Тесты — MessengerPage.viewport.test.tsx;
 * мобильные снимки — T068.
 *
 * 008a US2 typing-проводка (T038; FR-006–FR-008, ui-behavior §2.2,
 * realtime-events §1/§3): состояние «кто печатает» ОТКРЫТОГО чата
 * живёт в странице — кадры typing.started/stopped №18 (демультиплексор
 * onTypingEvent хука useRealtime) (пере)заводят per-печатающий
 * 10-секундный дедлайн наблюдателя, message.created от печатающего
 * гасит запись сразу, смена чата и каждый (ре)коннект №18 сбрасывают
 * карту (эфемерное состояние не реплеится); имена разрешаются
 * цепочкой US1 (resolveDisplayName) по клиентскому кэшу участников
 * (№11 peer / №28 ростер), неизвестные userId не рендерятся — и
 * кормят TypingRow ленты (T037). Композер открытого чата ride'ит
 * машину №41-сигналов useTyping (T036, §2.1): изменения черновика и
 * каждая попытка отправки (любой исход) → best-effort start/stop.
 *
 * 008a US4 звук-синхронизация (T058 → T065; FR-012–FR-015,
 * ui-behavior §4, realtime-events §1.3): per-chat карта звук-
 * переключателей вызывающего сходится из ЧЕТЫРЁХ источников — №12-строки
 * (по факту завершения фетча — рефетчи/реконнекты; локальные
 * инкрементальные пересборки списка звук-состояние не трогают, T065),
 * №11/№13 ChatView (adoptChatViewSound: select-фолбэк и
 * модальные пути), ответ №42 (echo, T057) и кадры `chat.sound.updated`
 * СОБСТВЕННОГО канала №18 (onChatSoundUpdated — мультидевайс ≤ 2 с,
 * SC-006; другие участники кадр не получают — приватность). Карта
 * кормит колокол заголовка (T057) и ДВА звук-гейта приёма: реальное
 * время передаёт per-chat состояние в chimeOnRealtimeIncoming
 * (решение — в ui/sound: false → тишина, undefined → звучит),
 * sync-батч 005 считает входящие PER-CHAT — звонок цикла звучит
 * только если среди пришедших есть неприглушённые чаты (итог 0 —
 * тишина самого sound). ВИЗУАЛЬНЫЕ уведомления (бейджи, лента,
 * превью, тосты) гейтом не фильтруются вовсе (FR-014).
 */
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type Dispatch,
  type ReactNode,
  type RefObject,
  type SetStateAction,
} from 'react'
import { getCurrentUser } from '../../api/auth'
import type { PublicUser } from '../../api/auth'
import type { GroupMember, GroupView } from '../../api/groups'
import { deleteGroup, leaveGroup } from '../../api/groups'
import {
  addContact,
  blockUser,
  deleteChat,
  getChat,
  listContacts,
  setChatSound,
  unblockUser,
} from '../../api/chats'
import type { ChatListItem, ChatView, ContactView, Message } from '../../api/chats'
import { problemMessage } from '../../auth/problem'
import { getAckBatcher } from '../../sync/ack'
import { advanceCursor } from '../../sync/cursors'
import { useSync } from '../../sync/hooks/useSync'
import { ChatHeader } from '../components/ChatHeader'
import type { ChatHeaderChat } from '../components/ChatHeader'
import { ChatListPanel } from '../components/ChatListPanel'
import { ContactsModal } from '../components/ContactsModal'
import type { ModalForm } from '../components/ContactsModal'
import { ErrorBanner } from '../components/ErrorBanner'
import { MessageInput } from '../components/MessageInput'
import { MessageList } from '../components/MessageList'
import { ProfileModal } from '../components/ProfileModal'
import { QueueOverflowBanner } from '../components/QueueOverflowBanner'
import { SyncIndicator } from '../components/SyncIndicator'
import type { TypingParticipant } from '../components/TypingRow'
import { useChatList } from '../hooks/useChatList'
import { useChatMessages } from '../hooks/useChatMessages'
import { useOutbox } from '../hooks/useOutbox'
import { useRealtime } from '../hooks/useRealtime'
import type { RealtimeStream } from '../hooks/useRealtime'
import { useTyping } from '../hooks/useTyping'
import { headFloodRetryAt } from '../outbox'
import { CreateGroupDialog } from '../../groups/components/CreateGroupDialog'
import { GroupEditModal } from '../../groups/components/GroupEditModal'
import { GroupMembersModal } from '../../groups/components/GroupMembersModal'
import { useGroup } from '../../groups/hooks/useGroup'
import type { GroupStatus } from '../../groups/hooks/useGroup'
import { useGroupMembers } from '../../groups/hooks/useGroupMembers'
import type { UseGroupMembersResult } from '../../groups/hooks/useGroupMembers'
import { usePresenceHeartbeat } from '../../presence/usePresence'
import { ConfirmDialog } from '../../ui/ConfirmDialog'
import type { ConfirmVariant } from '../../ui/ConfirmDialog'
import { FOCUSABLE_SELECTOR, ModalShell } from '../../ui/ModalShell'
import type { ModalFormId } from '../../ui/ModalShell'
import { ToastProvider, useToast } from '../../ui/Toast'
import {
  chimeOnRealtimeIncoming,
  chimeOnSyncBatch,
  playBellTone,
  playMuteTone,
} from '../../ui/sound'
import { resolveDisplayName } from '../../ui/names'
import '../components/states.css'
import './messenger.css'

/** The open direct dialog: everything the header actions need (T060). */
interface DirectChatView {
  readonly kind: 'direct'
  readonly chatId: string
  readonly peer: PublicUser
  /** Персональный alias вызывающего к peer №11/№12/№13 (008a T023) — цепочка заголовка. */
  readonly peerAlias?: string | null
  readonly blockedByMe: boolean
}

/**
 * The open GROUP window (feature 006, T029; US1): the №12/№13/№27
 * `title` + `memberCount` are the server-owned basis of the US1
 * header (ChatHeader, 008 T021) — the live №28 GroupView of the open
 * window (useGroup below) overrides both while it is live; the №12/
 * №13/№27 `myRole` basis feeds the gear matrix (T054) until №28 lands.
 * The roster/roles card is US3 (T046), the window actions are US3/US6.
 */
interface GroupChatView {
  readonly kind: 'group'
  readonly chatId: string
  readonly title: string
  readonly memberCount: number | null
  /** №12/№13/№27 role basis — the gear menu matrix before №28 is live. */
  readonly myRole: GroupMember['role'] | null
}

/** The open dialog of either kind (T029): one window, two headers. */
type ActiveChat = DirectChatView | GroupChatView

/**
 * A confirmation awaiting the user's decision — the «шестерёнка»
 * entries (T054, ui-behavior §4): the direct items (T029/T060) and the
 * group №33/№30 items that replaced the card-only controls of US6
 * (SC-007: 100% деструктивных операций требуют подтверждения).
 */
type PendingAction = 'delete-chat' | 'block' | 'unblock' | 'leave-group' | 'delete-group'

/** The shell 'confirm' inhabitant copy (SC-007: имя — <b>, FR-033). */
interface ConfirmUi {
  readonly title: string
  readonly text: ReactNode
  readonly confirmLabel: string
  readonly variant: ConfirmVariant
}

/** The direct-kind entries of PendingAction (a group window carries none). */
const DIRECT_PENDING_ACTIONS: ReadonlySet<PendingAction> = new Set([
  'delete-chat',
  'block',
  'unblock',
])

/** The group-kind entries — №33/№30 from the gear menu (T054). */
const GROUP_PENDING_ACTIONS: ReadonlySet<PendingAction> = new Set(['leave-group', 'delete-group'])

/**
 * Bounded size of the own-ack tracking set (T086в, bug 8): the set
 * only matters for the single commit where the confirmed message
 * lands, so a small FIFO cap covers the race window without growing
 * with the session.
 */
const OWN_ACK_TRACK_CAP = 16

/**
 * 008a US2 (T038; FR-008, realtime-events.md §3): страховочный
 * таймаут индикатора наблюдателя — 10 с без продления; каждый
 * повторный `typing.started` (клиентское окно повтора 3 с)
 * перезаводит окно своего печатающего, компенсируя at-most-once
 * потери канала №18 (серверное состояние самоистекает TTL 8 с —
 * парный stopped может не дойти).
 */
const TYPING_SAFETY_TIMEOUT_MS = 10_000

function confirmUiOf(action: PendingAction, peerName: string): ConfirmUi {
  if (action === 'delete-chat') {
    return {
      title: 'Удаление чата',
      text: (
        <>
          Удалить чат с <b>{peerName}</b>? История скроется только у вас; неотправленные сообщения
          этого чата будут удалены.
        </>
      ),
      confirmLabel: 'Удалить чат',
      variant: 'danger',
    }
  }
  if (action === 'block') {
    return {
      title: 'Блокировка пользователя',
      text: (
        <>
          Заблокировать <b>{peerName}</b>? Отправка сообщений будет запрещена в обе стороны.
        </>
      ),
      confirmLabel: 'Заблокировать',
      variant: 'danger',
    }
  }
  if (action === 'delete-group') {
    // Прототип askDeleteChat (групповая ветвь, без имени).
    return {
      title: 'Удаление чата',
      text: 'Групповой чат будет удалён.',
      confirmLabel: 'Удалить',
      variant: 'danger',
    }
  }
  if (action === 'leave-group') {
    // Прототип askLeaveGroup: имя чата — <b>-выделение (SC-007).
    return {
      title: 'Выйти из группового чата',
      text: (
        <>
          Вы покинете чат <b>{peerName}</b>.
        </>
      ),
      confirmLabel: 'Выйти',
      variant: 'danger',
    }
  }
  return {
    title: 'Разблокировка пользователя',
    text: (
      <>
        Разблокировать <b>{peerName}</b>? Переписка снова станет доступна в обе стороны.
      </>
    ),
    confirmLabel: 'Разблокировать',
    variant: 'primary',
  }
}

/** Заголовки форм-обитателей оболочки (openXxx() прототипа, T034). */
const MODAL_TITLES: Record<ModalFormId, string> = {
  contacts: 'Контакты',
  'add-contact': 'Добавить контакт',
  // openRename прототипа (chats.html): «Редактировать контакт».
  'rename-contact': 'Редактировать контакт',
  'create-group': 'Новый групповой чат',
  'group-edit': 'Редактировать групповой чат',
  'group-members': 'Участники',
  profile: 'Мой профиль',
  confirm: 'Подтверждение',
}

/** Проекция внутренних форм «Контактов» (ModalForm) в обитателей оболочки (T034). */
const CONTACTS_SHELL_FORMS: Record<ModalForm, ModalFormId> = {
  list: 'contacts',
  add: 'add-contact',
  rename: 'rename-contact',
}

/**
 * Заголовок оболочки (T034): ожидающее подтверждение несёт свой
 * заголовок, иначе — заголовок открытой формы; пустая оболочка — ''.
 */
function shellTitleOf(confirmation: ConfirmUi | null, formId: ModalFormId | null): string {
  if (confirmation !== null) {
    return confirmation.title
  }
  return formId !== null ? MODAL_TITLES[formId] : ''
}

/**
 * Контрольная точка ≤900px (US5/T065; design-tokens §9, FR-029): оба
 * стиля запроса прототипа (max-width:900px / min-width:901px) сводятся
 * к одному ответу этой формы. jsdom без matchMedia (юнит-среда без
 * стаба T062) отвечает «широкий экран» — burger остаётся скрытым CSS,
 * поведение страницы не меняется; скрытие burger на ≥901px — CSS-паттерн
 * прототипа, его проверяют T068/quickstart E.
 */
function useNarrowViewport(): boolean {
  const [narrow, setNarrow] = useState(
    () =>
      typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 900px)').matches,
  )
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') {
      return
    }
    const query = window.matchMedia('(max-width: 900px)')
    const report = () => {
      setNarrow(query.matches)
    }
    report()
    query.addEventListener('change', report)
    return () => {
      query.removeEventListener('change', report)
    }
  }, [])
  return narrow
}

/**
 * US5 адаптация к visual viewport (T066; FR-029, research §H,
 * design-tokens §9 «Клавиатура», ui-behavior §7): экранная клавиатура
 * сжимает visual viewport, но НЕ layout-viewport (iOS Safari) —
 * `100dvh` её не видит, дно корпуса-машины ушло бы под клавиатуру.
 * Слушатель `resize`/`scroll` пишет на body пару CSS-переменных:
 * `--vvh` = высота visual viewport, `--vvo` = offsetTop (панорамирование
 * при Autoraise-скролле iOS) — machine.css считает высоту машины по их
 * сумме: дно корпуса сходится к видимому дну, композер остаётся над
 * клавиатурой, лента сжимается, прокрутки страницы не появляется.
 * Поверхностям больше ничего не нужно — переменные наследуются с body.
 * jsdom/движки без visualViewport: hook тихо бездействует (переменные
 * не пишутся, CSS живёт на фолбэке `var(--vvh, 100dvh)`), StrictMode-
 * ремаунт идемпотентен (report чистая, подписки/свойства снимаются
 * cleanup'ом). Тесты — MessengerPage.viewport.test.tsx; CSS-половина
 * (высота/overflow) — T068 и quickstart E1–E2 (SC-005).
 */
function useVisualViewport(): void {
  useEffect(() => {
    const viewport = window.visualViewport
    if (!viewport) {
      return
    }
    const report = () => {
      document.body.style.setProperty('--vvh', `${viewport.height}px`)
      document.body.style.setProperty('--vvo', `${viewport.offsetTop}px`)
    }
    report()
    viewport.addEventListener('resize', report)
    viewport.addEventListener('scroll', report)
    return () => {
      viewport.removeEventListener('resize', report)
      viewport.removeEventListener('scroll', report)
      document.body.style.removeProperty('--vvh')
      document.body.style.removeProperty('--vvo')
    }
  }, [])
}

function useSidebarDrawer(modalAbove: boolean): {
  narrowViewport: boolean
  drawerOpen: boolean
  setDrawerOpen: Dispatch<SetStateAction<boolean>>
  backdropRef: RefObject<HTMLDivElement | null>
  sidebarRef: RefObject<HTMLElement | null>
} {
  /**
   * US5 burger-drawer (T065; FR-029): на ≤900px сайдбар — выдвижной
   * drawer поверх контента; `.open`-словарь прототипа (openSidebar/
   * closeSidebar) ведёт это состояние, DOM-проекция — ниже (burger /
   * sidebar / backdrop).
   */
  const narrowViewport = useNarrowViewport()
  const [drawerOpen, setDrawerOpen] = useState(false)
  /** Подложка drawer: нативный click-слушатель закрытия (как ModalShell). */
  const backdropRef = useRef<HTMLDivElement>(null)
  /**
   * Корень ловушки drawer (T070; FR-035): сам сайдбар — Tab не покидает
   * его, пока drawer — верхний слой; сюда же смотрит inert закрытого
   * off-canvas состояния.
   */
  const sidebarRef = useRef<HTMLElement>(null)

  // Возврат на широкий экран сбрасывает drawer: .backdrop не ограничен
  // media-блоком — открытое состояние не должно заливать широкий экран,
  // где сайдбар снова статичен в каркасе (T065).
  useEffect(() => {
    if (!narrowViewport) {
      setDrawerOpen(false)
    }
  }, [narrowViewport])

  // Esc закрывает drawer ТОЛЬКО как верхний слой (T065, data-model
  // 3.1): модальная оболочка слушает Esc на document без
  // stopPropagation, поэтому охрана «модалей выше нет» — на стороне
  // drawer; ctx-menu/members-tip гасят Esc capture-фазой (T013/T043) и
  // сюда не доходят вовсе.
  useEffect(() => {
    if (!drawerOpen) {
      return
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') {
        return
      }
      if (modalAbove) {
        return
      }
      setDrawerOpen(false)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [drawerOpen, modalAbove])

  // Инициатор drawer (T070; FR-035, SC-008, ui-behavior §1): захват при
  // открытии (burger — единственный вход), возврат фокуса при закрытии.
  // Паттерн ModalShell/ContextMenu: перебиваем фокус ТОЛЬКО если он ещё
  // внутри drawer или ни на чём — клик мимо уже увёл фокус на свою цель.
  // Ключ только [drawerOpen]: formId-переключения выше не должны
  // «закрывать» drawer и возвращать фокус (модаль живёт над ним).
  useEffect(() => {
    if (!drawerOpen) {
      return
    }
    const initiator = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const sidebar = sidebarRef.current
    return () => {
      const active = document.activeElement
      const inside = sidebar !== null && active instanceof Node && sidebar.contains(active)
      if (inside || active === null || active === document.body) {
        initiator?.focus()
      }
    }
  }, [drawerOpen])

  // Tab-ловушка drawer (T070; FR-035, SC-008): пока drawer — верхний
  // слой, Tab/Shift+Tab крутятся по фокусируемым сайдбара (словарь
  // FOCUSABLE_SELECTOR — тот же, что у ModalShell). Модаль выше — её
  // ловушка владеет Tab (data-model 3.1): здесь тихо отступаем.
  useEffect(() => {
    if (!drawerOpen) {
      return
    }
    if (modalAbove) {
      return
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Tab') {
        return
      }
      const root = sidebarRef.current
      if (root === null) {
        return
      }
      const focusables = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      const [first] = focusables
      const last = focusables.at(-1)
      if (first === undefined || last === undefined) {
        return
      }
      const active = document.activeElement
      const inside = root.contains(active)
      if (event.shiftKey && (active === first || !inside)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (active === last || !inside)) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [drawerOpen, modalAbove])

  // Клик по затемнению закрывает drawer (T065): нативный слушатель на
  // самой подложке — JSX-хендлер на статичном div это S6848/S1082
  // (паттерн ModalShell); с клавиатуры drawer гасит Esc-эффект выше.
  useEffect(() => {
    const back = backdropRef.current
    if (back === null) {
      return
    }
    const onClick = (): void => {
      setDrawerOpen(false)
    }
    back.addEventListener('click', onClick)
    return () => {
      back.removeEventListener('click', onClick)
    }
  }, [])

  return { narrowViewport, drawerOpen, setDrawerOpen, backdropRef, sidebarRef }
}

function useTypingParticipants(
  activeChat: ActiveChat | null,
  activeChatId: string | null,
  activeGroupMembers: readonly GroupMember[] | undefined,
  realtime: RealtimeStream,
): readonly TypingParticipant[] {
  // 008a T038 (US2; FR-006–FR-008, ui-behavior §2.2): «кто печатает»
  // активного чата — эфемерная карта дедлайнов наблюдателя. Каждый
  // typing.started (пере)заводит 10-секундное окно своего печатающего
  // (страховка at-most-once, TYPING_SAFETY_TIMEOUT_MS); typing.stopped
  // и message.created от печатающего гасят запись; смена чата и каждый
  // (ре)коннект №18 сбрасывают карту целиком — состояние не реплеится
  // при (пере)подключении (realtime-events §1.1, edge спеки). События
  // чужих диалогов сюда не приходят вовсе: подписка идёт под
  // конкретным chatId открытого окна и пересоздаётся при переключении.
  const [typingDeadlines, setTypingDeadlines] = useState<ReadonlyMap<string, number>>(
    () => new Map(),
  )

  // Один свипер на всю карту: таймаут до САМОГО РАННЕГО дедлайна
  // выбрасывает истёкшие записи; смена состояния перезаводит его, а
  // продление печатающего отодвигает его дедлайн за работающий таймер —
  // свип остаётся точным для каждого участника в отдельности.
  useEffect(() => {
    if (typingDeadlines.size === 0) {
      return
    }
    const earliest = Math.min(...typingDeadlines.values())
    const timer = window.setTimeout(
      () => {
        const now = Date.now()
        setTypingDeadlines((previous) => {
          let expired = false
          const next = new Map<string, number>()
          for (const [userId, deadline] of previous) {
            if (deadline > now) {
              next.set(userId, deadline)
            } else {
              expired = true
            }
          }
          return expired ? next : previous
        })
      },
      Math.max(0, earliest - Date.now()),
    )
    return () => {
      window.clearTimeout(timer)
    }
  }, [typingDeadlines])

  // typing.started/stopped открытого чата (onTypingEvent демультиплексора):
  // старт (пере)заводит окно (в т.ч. продления — «индикатор не отвисает»,
  // §1.1), стоп гасит запись идемпотентно (отсутствующая — no-op).
  useEffect(() => {
    if (activeChatId === null) {
      return
    }
    return realtime.onTypingEvent(activeChatId, (_chatId, userId, started) => {
      // Демультиплексор уже отфильтровал чужие диалоги (подписка под
      // конкретным chatId) — первый аргумент совпадает с активным по
      // построению и не используется.
      if (started) {
        setTypingDeadlines((previous) =>
          new Map(previous).set(userId, Date.now() + TYPING_SAFETY_TIMEOUT_MS),
        )
        return
      }
      setTypingDeadlines((previous) => {
        if (!previous.has(userId)) {
          return previous
        }
        const next = new Map(previous)
        next.delete(userId)
        return next
      })
    })
  }, [activeChatId, realtime])

  // message.created от печатающего (§2.2): коммит сообщения завершает
  // набор — сервер гасит состояние при INSERT (T033), но парный
  // typing.stopped может отставать или теряться, поэтому наблюдатель
  // скрывает запись сразу по самому сообщению (его отправитель и есть
  // печатающий).
  useEffect(() => {
    if (activeChatId === null) {
      return
    }
    return realtime.onMessageCreated(activeChatId, (event) => {
      const senderId = event.message.senderId
      setTypingDeadlines((previous) => {
        if (!previous.has(senderId)) {
          return previous
        }
        const next = new Map(previous)
        next.delete(senderId)
        return next
      })
    })
  }, [activeChatId, realtime])

  // Смена чата скрывает строку немедленно (§2.2 — единый экземпляр
  // индикатора, скрыт при смене чата).
  useEffect(() => {
    setTypingDeadlines((previous) => (previous.size === 0 ? previous : new Map()))
  }, [activeChatId])

  // (Ре)коннект №18 сбрасывает карту — эфемерное состояние не
  // реплеится подключающимся (§1.1): onOpen стреляет на каждом
  // (пере)подключении, начальный коннект чистит уже пустую карту.
  useEffect(() => {
    return realtime.onOpen(() => {
      setTypingDeadlines((previous) => (previous.size === 0 ? previous : new Map()))
    })
  }, [realtime])

  // TypingParticipant-проекция дедлайнов: имена разрешаются цепочкой
  // US1 (resolveDisplayName) по КЛИЕНТСКОМУ кэшу участников — peer №11
  // открытого личного чата (peerAlias → displayName → username) или
  // ростер №28 открытой группы (alias → displayName → username).
  // Неизвестный кэшу userId не даёт записи — такой печатающий не
  // рендерится и выпадает из счёта N (§2.2, YAGNI — новых API нет).
  const typingParticipants = useMemo<readonly TypingParticipant[]>(() => {
    if (activeChat === null || typingDeadlines.size === 0) {
      return []
    }
    const participants: TypingParticipant[] = []
    for (const userId of typingDeadlines.keys()) {
      if (activeChat.kind === 'direct') {
        if (activeChat.peer.id === userId) {
          participants.push({
            userId,
            name: resolveDisplayName(
              activeChat.peerAlias,
              activeChat.peer.displayName,
              activeChat.peer.username,
            ),
            username: activeChat.peer.username,
          })
        }
        continue
      }
      const member = activeGroupMembers?.find((entry) => entry.user.id === userId)
      if (member !== undefined) {
        participants.push({
          userId,
          name: resolveDisplayName(
            member.user.alias,
            member.user.displayName,
            member.user.username,
          ),
          username: member.user.username,
        })
      }
    }
    return participants
  }, [typingDeadlines, activeChat, activeGroupMembers])

  return typingParticipants
}

/**
 * The pending confirmation's copy (T054): null — nothing pending or
 * the window kind carries no such entry; direct entries take the peer
 * username, group ones the chat title.
 */
function confirmationOf(action: PendingAction | null, chat: ActiveChat | null): ConfirmUi | null {
  if (action === null || chat === null) {
    return null
  }
  if (DIRECT_PENDING_ACTIONS.has(action) && chat.kind === 'direct') {
    return confirmUiOf(action, chat.peer.username)
  }
  if (GROUP_PENDING_ACTIONS.has(action) && chat.kind === 'group') {
    return confirmUiOf(action, chat.title)
  }
  return null
}

/**
 * The header props of the open window (T029 → 008 T021/T043/T054): the
 * page resolves the server-owned chat data into the ChatHeader variant —
 * the GROUP window the №28 title/roster/role (or the №12/№27 basis),
 * the direct dialog the peer + the №20 membership for the gear matrix.
 */
function headerChatOf(
  chat: ActiveChat,
  group: GroupView | null,
  contacts: readonly ContactView[],
  meUserId: string | null,
  meUsername: string | null,
): ChatHeaderChat {
  if (chat.kind === 'group') {
    // №28 title/roster while the view is live (the optimistic
    // `group.updated` half of the header), the №12/№27 basis until
    // then; the roster length IS the memberCount (me included). The
    // live roster + myRole feed the members-tip (008 T043, FR-017);
    // the №12 basis carries no names — null keeps the status row a
    // non-source until №28 lands. myRole: live №28, else the basis —
    // the gear matrix driver (T054).
    const live = group !== null && group.chatId === chat.chatId
    return {
      kind: 'group',
      title: live ? group.title : chat.title,
      memberCount: live ? group.members.length : chat.memberCount,
      members: live ? group.members : null,
      myRole: live ? group.myRole : chat.myRole,
      meUserId,
      meUsername,
    }
  }
  return {
    kind: 'direct',
    peerId: chat.peer.id,
    username: chat.peer.username,
    // 008a T023: цепочка заголовка — peerAlias №11/№12/№13 поверх
    // профильного displayName peer (ui-behavior §1).
    displayName: chat.peer.displayName,
    peerAlias: chat.peerAlias ?? null,
    blockedByMe: chat.blockedByMe,
    peerInContacts: contacts.some((contact) => contact.user.id === chat.peer.id),
  }
}

/** Связывание №11/№13 ChatView с окном страницы (T034: путь из модалей). */
function activeChatOfView(view: ChatView): ActiveChat | null {
  if (view.type === 'group') {
    return {
      kind: 'group',
      chatId: view.chatId,
      title: view.title ?? '',
      memberCount: view.memberCount ?? null,
      myRole: view.myRole ?? null,
    }
  }
  if (view.peer !== null) {
    return {
      kind: 'direct',
      chatId: view.chatId,
      peer: view.peer,
      peerAlias: view.peerAlias ?? null,
      blockedByMe: view.blockedByMe ?? false,
    }
  }
  return null
}

/**
 * The group forms of the single shell (008 T057; FR-023, ui-behavior
 * §3): the №28 lifecycle of the inhabitant slot — loading/error
 * states stay local to the slot (the modal lexicon: ErrorBanner +
 * «Повторить»), the ready roster feeds the members (T055) / edit
 * (T056) forms. The №32/№34/№35 roster actions ride the page's
 * useGroupMembers mutex (T047) verbatim; the №21 offer of the
 * members rows belongs to the page (its toast too, T045).
 *
 * T098 (Bug 20): the members rows are NAVIGATION — the page hands the
 * form its №12 basis (`chats`) and the shell owner's standard
 * `onOpenChat` (handleOpenChatFromModal, the same prop ContactsModal
 * gets): a member row click opens the peer dialog (№12 hit — no №11;
 * miss — №11 creates and opens) and closes the shell exactly like a
 * contacts row.
 */
interface GroupShellFormProps {
  readonly formId: 'group-members' | 'group-edit'
  readonly group: GroupView | null
  readonly chatId: string
  readonly status: GroupStatus
  readonly error: unknown
  readonly currentUserId: string | null
  readonly rosterActions: UseGroupMembersResult
  /** №12 rows (useChatList) — the members-row direct-chat basis (T098). */
  readonly chats: readonly ChatListItem[]
  /** Opens the peer dialog from a members row (T098 — the shell owner's
   * standard prop, the same one ContactsModal receives). */
  readonly onOpenChat: (chat: ChatView) => void
  /** №20 keys of the viewer's book — hides the «Добавить в контакты» offer. */
  readonly contactUserIds: ReadonlySet<string>
  readonly onAddContact: (userId: string) => void
  readonly onReload: () => void
  readonly onMembersAdded: () => void
  readonly onUpdated: () => void
  readonly onCancel: () => void
}

function GroupShellForm({
  formId,
  group,
  chatId,
  status,
  error,
  currentUserId,
  rosterActions,
  chats,
  onOpenChat,
  contactUserIds,
  onAddContact,
  onReload,
  onMembersAdded,
  onUpdated,
  onCancel,
}: GroupShellFormProps) {
  return (
    <>
      {status === 'loading' && <p className="messenger-empty">Загрузка группы…</p>}
      {status === 'error' && (
        <>
          <ErrorBanner error={error} />
          <div className="modal-btns">
            <button type="button" className="m-btn" onClick={onReload}>
              Повторить
            </button>
          </div>
        </>
      )}
      {group?.chatId === chatId && status === 'ready' && formId === 'group-members' && (
        <GroupMembersModal
          members={group.members}
          myRole={group.myRole}
          currentUserId={currentUserId ?? ''}
          chats={chats}
          onOpenChat={onOpenChat}
          onKick={(userId) => {
            void rosterActions.kick(userId)
          }}
          onSetRole={(userId, role) => {
            void rosterActions.setRole(userId, role)
          }}
          onTransferOwnership={(userId) => {
            void rosterActions.transferOwnership(userId)
          }}
          pendingUserId={rosterActions.pendingUserId}
          rosterError={rosterActions.error}
          contactUserIds={contactUserIds}
          onAddContact={onAddContact}
        />
      )}
      {group?.chatId === chatId && status === 'ready' && formId === 'group-edit' && (
        <GroupEditModal
          chatId={chatId}
          title={group.title}
          description={group.description}
          members={group.members}
          currentUserId={currentUserId ?? undefined}
          onUpdated={onUpdated}
          onMembersAdded={onMembersAdded}
          onMemberRemoved={onMembersAdded}
          onCancel={onCancel}
        />
      )}
    </>
  )
}

/**
 * T045 (FR-025): страница тонкая — провайдер единого тост-слота над
 * «машиной»; все операции, завершающиеся на уровне страницы (шапка
 * прямого чата, групповые №33/№30 окна), выполняются в механизме ПОД
 * провайдером и отмечаются ровно одним тостом прототипа.
 */
export function MessengerPage() {
  return (
    <ToastProvider>
      <MessengerMachine />
    </ToastProvider>
  )
}

function MessengerMachine() {
  const showToast = useToast()
  const [currentUserId, setCurrentUserId] = useState<string | null>(null)
  /** Own username — the feed avatar source of outgoing rows (008 T022). */
  const [meUsername, setMeUsername] = useState<string | null>(null)
  const [activeChat, setActiveChat] = useState<ActiveChat | null>(null)
  /** The pending «шестерёнка» confirmation + its in-flight guard (T054). */
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  const [actionPending, setActionPending] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  /**
   * The №20 address book of the viewer (T054): the gear's «Добавить в
   * контакты» visibility for the open direct dialog. Fetched on boot
   * and on every shell closure (the contacts form mutates the book);
   * a stale copy is harmless — №21 is idempotent (201/200), a wrong
   * offer converges to the «Уже в контактах» toast.
   */
  const [contacts, setContacts] = useState<readonly ContactView[]>([])
  /**
   * The SINGLE modal shell form (T034, data-model 1.6/3.3): the
   * sidebar main menu (T030) opens contacts / add-contact / profile /
   * create-group here, the header confirmations ride the 'confirm'
   * formId — one `.modal-back` for every form, formId switches never
   * stack a second backdrop.
   */
  const [modalForm, setModalForm] = useState<ModalFormId | null>(null)
  /**
   * Персональные звук-переключатели чатов вызывающего (008a US4,
   * T057/T058 → T065; FR-012): chatId → soundEnabled. Источники
   * сходятся здесь: №12-строки — ТОЛЬКО по факту завершения №12-фетча
   * (chatListRevision; локальные пересборки списка несут просроченное
   * значение последнего фетча и звук-состояние не трогают вовсе),
   * №11/№13 ChatView (select-фолбэк и модальные пути —
   * adoptChatViewSound), ответ №42 (echo сохранённого) и кадры
   * `chat.sound.updated` собственного канала (мультидевайс ≤ 2 с,
   * SC-006). Отсутствие записи = «вкл» — умолчание контракта
   * (отсутствие поля №12/№13 клиент трактует как true; обратная
   * совместимость 008).
   */
  const [soundStates, setSoundStates] = useState<ReadonlyMap<string, boolean>>(() => new Map())
  /**
   * Синхронное зеркало звук-состояния для слушателей реального времени
   * (T058): гейт chimeOnRealtimeIncoming и per-chat подсчёт sync-батчей
   * читают актуальное значение БЕЗ пересоздания подписок на каждый
   * переключатель (паттерн chatsRef/currentUserIdRef useChatList).
   */
  const soundStatesRef = useRef(soundStates)
  soundStatesRef.current = soundStates
  const { narrowViewport, drawerOpen, setDrawerOpen, backdropRef, sidebarRef } = useSidebarDrawer(
    modalForm !== null || pendingAction !== null,
  )

  // US5 клавиатура (T066; FR-029, research §H): --vvh/--vvo на body для
  // высоты машины (machine.css) — композер над клавиатурой, лента сжимается.
  useVisualViewport()

  /** №20 refetch of the gear's address-book basis (T054). */
  const reloadContacts = useCallback(() => {
    if (currentUserId === null) {
      return
    }
    void (async () => {
      try {
        setContacts(await listContacts('login'))
      } catch {
        // Non-fatal: a stale book only risks a redundant (idempotent,
        // №21) offer of «Добавить в контакты».
      }
    })()
  }, [currentUserId])

  /**
   * №20 keys of the viewer's book (T057): the «Добавить в контакты»
   * offer basis of the members modal rows (T049/T055 contract).
   */
  const contactUserIds = useMemo(
    () => new Set(contacts.map((contact) => contact.user.id)),
    [contacts],
  )

  useEffect(() => {
    reloadContacts()
  }, [reloadContacts])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const user = await getCurrentUser()
        if (!cancelled) {
          setCurrentUserId(user.id)
          setMeUsername(user.username)
        }
      } catch {
        // Session refresh happens in apiFetch; without a user the
        // composer stays disabled and the route guard redirects.
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const {
    chats,
    status: chatListStatus,
    error: chatListError,
    reload: reloadChatList,
    markChatReadLocally,
    applySyncUpdate: applyChatListSync,
    listRevision: chatListRevision,
  } = useChatList(currentUserId)

  /**
   * Синхронное зеркало строк №12 для эффектов, ключеных на ревизию
   * базиса (008a T065): в коммите завершённого фетча реф уже несёт
   * свежие строки (ревизия и строки сходят в одном апдейте useChatList),
   * а локальные пересборки массива его не трогают вовсе.
   */
  const chatsRef = useRef(chats)
  chatsRef.current = chats

  const activeChatId = activeChat?.chatId ?? null

  // №28 lifecycle of the open group window (US3, T046a → T057): useGroup
  // owns the GroupView — one №28 per open, optimistic `group.*` frames,
  // `reload()` convergence — feeding both the dialog pair (the T038
  // attribution/✓✓ roster) and the group forms of the shell below. The
  // chatId guard keeps a direct dialog opened next free of the stale
  // group view before useGroup's switch reset lands.
  const activeGroupChatId = activeChat?.kind === 'group' ? activeChat.chatId : null
  const {
    group: activeGroup,
    status: activeGroupStatus,
    error: activeGroupError,
    reload: reloadActiveGroup,
  } = useGroup(activeGroupChatId)
  const activeGroupMembers =
    activeGroup !== null && activeGroup.chatId === activeChatId ? activeGroup.members : undefined

  // №32/№34/№35 roster actions of the members form (US3, T047 → T057):
  // useGroupMembers runs every roster mutation through ONE local
  // mutex — `pendingUserId` disables the in-flight modal row, a
  // problem (`role_hierarchy_violation`, `not_group_owner`, network)
  // surfaces as the form's rosterError and unblocks the roster. A
  // successful action converges the №28 roster through `reload()` (the
  // `group.member.removed`/`group.role.changed` frames land on top,
  // idempotently — T048).
  const rosterActions = useGroupMembers(activeGroupChatId, reloadActiveGroup)

  const {
    messages,
    status,
    error,
    reload,
    confirmMessage,
    hasOlder,
    loadingOlder,
    loadOlder,
    peerReadUpToSeq,
    othersReadUpToSeq,
    unreadFromSeq,
    applySyncPage: applyDialogSync,
  } = useChatMessages(activeChatId, currentUserId, activeGroupMembers)

  // The §3.1 catch-up loop (feature 005): runs on every SSE (re)open,
  // its `syncing` drives the SyncIndicator below.
  const { syncing, onChatUpdate } = useSync(currentUserId)
  const realtime = useRealtime()

  // №37 presence heartbeat (007 research.md §E2; 008 T076): keep THIS
  // connection's registration alive on the shared №18 stream — every
  // `connected` frame restarts the 30 s beats against the 90 s TTL,
  // and 404 reconnects the channel immediately. Without it a live
  // connection expired and the observers saw «офлайн».
  usePresenceHeartbeat(realtime)

  // №28 reconnect convergence of the open group window (T046a,
  // realtime-group-events.md §5.4): the at-most-once channel may have
  // missed roster/role/metadata frames while disconnected — every SSE
  // (re)open re-runs №28, the same convergence useChatList gives №12.
  useEffect(() => {
    if (activeGroupChatId === null) {
      return
    }
    return realtime.onOpen(() => {
      reloadActiveGroup()
    })
  }, [activeGroupChatId, realtime, reloadActiveGroup])

  const typingParticipants = useTypingParticipants(
    activeChat,
    activeChatId,
    activeGroupMembers,
    realtime,
  )

  // 008a T038 (US2; FR-006, ui-behavior §2.1): композер открытого чата
  // ride'ит машину №41-сигналов (T036) — изменения черновика и каждая
  // попытка отправки (любой исход, вкл. отклонённую пустую) переводятся
  // в best-effort start/stop; заблокированный композер (FR-022) сигналов
  // не шлёт вовсе.
  const typingSignals = useTyping({
    chatId: activeChatId,
    blocked: activeChat?.kind === 'direct' && activeChat.blockedByMe,
  })

  // T086в (bug 8): ids the outbox engine confirmed via 201/200.
  // When the ack races the first render of the optimistic row (a
  // batched flush skips the intermediate state entirely), MessageList's
  // own `localIdsRef` never held the id and the append would lose the
  // own-branch of the T079 autoscroll. This set vouches for such acks
  // regardless of the render interleaving — bounded to the most
  // recent OWN_ACK_TRACK_CAP ids (Set keeps the insertion order), far
  // beyond the one commit the race window spans.
  const [ownAckIds, setOwnAckIds] = useState<ReadonlySet<string>>(() => new Set())
  const handleConfirmed = useCallback(
    (message: Message) => {
      confirmMessage(message)
      setOwnAckIds((previous) => {
        const next = new Set(previous)
        next.add(message.id)
        for (const id of next) {
          if (next.size <= OWN_ACK_TRACK_CAP) {
            break
          }
          next.delete(id)
        }
        return next
      })
    },
    [confirmMessage],
  )
  const outbox = useOutbox(currentUserId, { onConfirmed: handleConfirmed })

  // Chat switches close the pending confirmation and the group forms
  // of the shell; a stale dialog must never act on a chat that is no
  // longer open. The realtime group.you_removed/group.deleted frames
  // can fold the window under an open «Участники»/«Редактировать
  // чат» form — the form never outlives its window. Menu-opened forms
  // do not see this path: every modal → chat transition closes the
  // shell first (handleOpenChatFromModal).
  useEffect(() => {
    setPendingAction(null)
    setModalForm(null)
  }, [activeChatId])

  // Applied catch-up pages (feature 005, T023): the list adopts
  // previews/positions and materializes new chats (US1-6), the open
  // dialog merges the page idempotently (dedup by `message.id`, order
  // by `seq`, US1-3) — `applyDialogSync` ignores other chats by
  // itself, so no activeChatId filter is needed here.
  useEffect(() => {
    return onChatUpdate((update) => {
      applyChatListSync(update)
      applyDialogSync(update)
    })
  }, [onChatUpdate, applyChatListSync, applyDialogSync])

  // US5 приём-звоночек, массовая доставка (T064; FR-028, research §F):
  // РОВНО один сигнал на пакет пропущенного после разрыва — счётчик
  // входящих (senderId ≠ me) копится по применённым страницам цикла
  // catch-up (дельты №26 и №15-хвосты hasMore одного цикла складываются)
  // и сбрасывается одним chimeOnSyncBatch(итог цикла) на завершении
  // цикла — фронт true→false флага syncing (setSyncing(false) в finally
  //useSync — после всех emit), поэтому вызов всегда один на цикл;
  // тишина нуля — контракт самого sound (T061). История №16 и пагинация
  // композера страницы этим путём не идут вовсе, отправка звука не
  // имеет (Clarification); sync-цикл не тронут — только слушатель
  // страницы (research «Сводка без изменений»).
  //
  // 008a US4 (T058; FR-013, ui-behavior §4.2): подсчёт PER-CHAT —
  // входящие приглушённого чата (soundEnabled === false) в счётчик не
  // попадают: звонок батча звучит только если среди пришедших есть
  // НЕприглушённые чаты (батч только с приглушёнными → итог 0 →
  // тишина sound). ВИЗУАЛЬНАЯ доставка (бейджи/лента/позиции —
  // эффект applyChatListSync/applyDialogSync выше) гейтом не
  // фильтруется вовсе (FR-014); слушатель не пересоздаётся на каждое
  // переключение — состояние читается через soundStatesRef.
  const syncIncomingRef = useRef(0)
  const syncCycleRanRef = useRef(false)
  useEffect(() => {
    return onChatUpdate((update) => {
      if (soundStatesRef.current.get(update.chatId) === false) {
        return
      }
      for (const message of update.messages) {
        if (message.senderId !== currentUserId) {
          syncIncomingRef.current += 1
        }
      }
    })
  }, [onChatUpdate, currentUserId])
  useEffect(() => {
    if (syncing) {
      syncCycleRanRef.current = true
      return
    }
    if (!syncCycleRanRef.current) {
      return
    }
    syncCycleRanRef.current = false
    chimeOnSyncBatch(syncIncomingRef.current)
    syncIncomingRef.current = 0
  }, [syncing])

  // Applied realtime frames confirm through the same shared №25
  // batcher as the catch-up pages (sync-protocol.md §2): the frame's
  // `seq` acks its chat and the local cursor echoes it — realtime and
  // sync move the delivery position alike (US1-1).
  useEffect(() => {
    if (currentUserId === null) {
      return
    }
    const batcher = getAckBatcher(currentUserId)
    return realtime.onMessageCreated(null, (event) => {
      batcher.ack(event.chatId, event.message.seq)
      advanceCursor(currentUserId, event.chatId, event.message.seq)
    })
  }, [currentUserId, realtime])

  // US5 приём-звоночек, реальное время (T064; FR-028, research §F,
  // ui-behavior §6): один сигнал на каждое входящее событие ЛЮБОГО
  // чата, включая фоновые — null-подписка демультиплексора useRealtime
  // получает события всех диалогов. Фильтр senderId ≠ me (собственные
  // сообщения молчат, в том числе с другого устройства — отправка
  // подтверждается только визуально) и сам синтез живут в ui/sound
  // (T061/T063); сюда приходит каждое событие №18 потока, «двойного»
  // сигнала на событие нет — слушатель один.
  //
  // 008a US4 (T058; FR-013, ui-behavior §4.2): per-chat mute-гейт —
  // странице принадлежит только ПЕРЕДАЧА состояния (soundStates:
  // №12/№13/№42/chat.sound.updated), решение «звонить ли» — в самом
  // sound (soundEnabled === false → тишина, undefined → звучит,
  // обратная совместимость 008); визуальные поверхности события не
  // фильтруются (FR-014).
  useEffect(() => {
    if (currentUserId === null) {
      return
    }
    return realtime.onMessageCreated(null, (event) => {
      chimeOnRealtimeIncoming(event, currentUserId, soundStatesRef.current.get(event.chatId))
    })
  }, [currentUserId, realtime])

  // US5/US6 final-frame wiring (T058/T066, realtime-group-events.md
  // §5.2/§5.5/§3.5): the FINAL `group.you_removed` frame — and the №30
  // `group.deleted` broadcast alike — closes an OPEN group window at
  // once (the chat switch effect above folds the card/menu with it;
  // useGroup/useChatMessages reset on the null chatId — no «зависшие»
  // states) and tombstones the group's chatId in the shared №25
  // batcher — a pending or racing ack of a non-participant chat would
  // reject the WHOLE batch (403 not_participant, all-or-refusal).
  useEffect(() => {
    if (currentUserId === null) {
      return
    }
    const batcher = getAckBatcher(currentUserId)
    return realtime.onGroupEvent((event) => {
      if (event.type !== 'group.you_removed' && event.type !== 'group.deleted') {
        return
      }
      batcher.drop(event.groupId)
      setActiveChat((previous) =>
        previous !== null && previous.kind === 'group' && previous.chatId === event.groupId
          ? null
          : previous,
      )
    })
  }, [currentUserId, realtime])

  // Keep the header's `blockedByMe` in step with the №12 aggregate:
  // every list refetch (block actions, SSE reconnects) reconciles the
  // open dialog's mark to the server state. A GROUP window never
  // carries the mark — blocks never apply to groups (006 Assumptions).
  // 008a T023: тем же №12-рефетчем сходится и ИМЯ заголовка (peer
  // displayName + peerAlias) — «все поверхности владельца обновляются»
  // по рефетчу (ui-behavior §1.2; realtime-события смены имени нет).
  useEffect(() => {
    if (activeChat?.kind !== 'direct') {
      return
    }
    const item = chats.find((entry) => entry.chatId === activeChat.chatId)
    const blockedByMe = item?.blockedByMe ?? false
    const peerChanged =
      item !== undefined &&
      item.peer !== null &&
      (item.peer.displayName !== activeChat.peer.displayName ||
        (item.peerAlias ?? null) !== (activeChat.peerAlias ?? null))
    if (item !== undefined && (blockedByMe !== activeChat.blockedByMe || peerChanged)) {
      setActiveChat((previous) =>
        previous !== null && previous.kind === 'direct' && previous.chatId === item.chatId
          ? {
              ...previous,
              blockedByMe,
              peer: item.peer ?? previous.peer,
              peerAlias: item.peer !== null ? (item.peerAlias ?? null) : previous.peerAlias,
            }
          : previous,
      )
    }
  }, [chats, activeChat])

  // 008a US4 (T057 → T065; FR-012, ui-behavior §4.1): №12-сходимость
  // звук-переключателей — ТОЛЬКО по факту завершения №12-фетча
  // (chatListRevision useChatList): строки доставленного сервером
  // агрегата несут актуальное `soundEnabled` вызывающего, поэтому
  // колокол и звук-гейт приёма (T058) стартуют с серверной правды
  // каждой загрузки/рефетча/реконнекта. Локальные инкрементальные
  // обновления списка (входящие/прочтения/sync-дельты) пересобирают
  // строки spread'ом со СТАРЫМ значением последнего фетча — сходимость
  // по ним откатывала бы свежие №42-echo и кадры chat.sound.updated
  // просроченным значением (Phase 8, дефекты 1/2: mute «жил» одно
  // входящее, unmute откатывался сам, переключения чатов влияли друг
  // на друга), поэтому на `[chats]` эффект не ключится вовсе.
  useEffect(() => {
    setSoundStates((previous) => {
      let changed = false
      const next = new Map(previous)
      for (const item of chatsRef.current) {
        if (item.soundEnabled !== undefined && next.get(item.chatId) !== item.soundEnabled) {
          next.set(item.chatId, item.soundEnabled)
          changed = true
        }
      }
      return changed ? next : previous
    })
  }, [chatListRevision])

  // 008a US4 (T058; FR-015, realtime-events §1.3): кадр
  // `chat.sound.updated` приходит ТОЛЬКО в собственный канал — другой
  // девайс того же пользователя переключил №42; состояние (колокол
  // `aria-pressed`, звук-гейт приёма) сходится ≤ 2 с (SC-006).
  // Дублей не бывает (сервер публикует только фактическую СМЕНУ), а
  // (ре)подключение сходится №12/№13-ресурсами выше — кадр живого
  // соединения не реплеится.
  useEffect(() => {
    return realtime.onChatSoundUpdated((chatId, soundEnabled) => {
      setSoundStates((previous) => {
        if (previous.get(chatId) === soundEnabled) {
          return previous
        }
        const next = new Map(previous)
        next.set(chatId, soundEnabled)
        return next
      })
    })
  }, [realtime])

  // FR-014 badge reset of the open dialog: rendering its incoming
  // messages is exactly when useChatMessages advances the read
  // watermark (T044), so the panel counter zeroes in step with it.
  useEffect(() => {
    if (activeChatId === null || currentUserId === null) {
      return
    }
    if (messages.some((message) => message.senderId !== currentUserId)) {
      markChatReadLocally(activeChatId)
    }
  }, [activeChatId, messages, currentUserId, markChatReadLocally])

  const openChatView = useCallback((view: ActiveChat) => {
    setActiveChat(view)
  }, [])

  /**
   * 008a US4 (T058; FR-012, api-contract §1.3 №42): звук-состояние из
   * №11/№13 ChatView — select-фолбэк №13 и модальные пути №11
   * (ContactsModal/GroupMembersModal → ensureChat) несут актуальное
   * `soundEnabled` вызывающего; (ре)подключение сходится тем же
   * правилом через №12 (эффект сходимости выше). Отсутствие поля
   * (старый сервер) — no-op: умолчание «вкл» живёт в потребителях.
   */
  const adoptChatViewSound = useCallback((view: ChatView) => {
    const soundEnabled = view.soundEnabled
    if (soundEnabled === undefined) {
      return
    }
    setSoundStates((previous) => {
      if (previous.get(view.chatId) === soundEnabled) {
        return previous
      }
      const next = new Map(previous)
      next.set(view.chatId, soundEnabled)
      return next
    })
  }, [])

  /** Shell closure (data-model 3.3): Esc / фон / «Отмена» / успех submit. */
  const closeShell = useCallback(() => {
    setModalForm(null)
    setPendingAction(null)
    // The contacts form may have mutated the №20 book (№21/№22) — the
    // gear basis converges with the closure (T054).
    reloadContacts()
  }, [reloadContacts])

  /**
   * Opening a pair dialog FROM a modal form (T034): the shell closes
   * (closeModal + selectChat прототипа), the №11 ChatView becomes the
   * open window and №12 converges — the new chat lands in «Чаты» at
   * once instead of waiting for the next sync round.
   */
  const handleOpenChatFromModal = useCallback(
    (view: ChatView) => {
      closeShell()
      // 008a T058: №11-ответ несёт звук-состояние чата вызывающего.
      adoptChatViewSound(view)
      const next = activeChatOfView(view)
      if (next !== null) {
        openChatView(next)
      }
      reloadChatList()
    },
    [closeShell, openChatView, reloadChatList, adoptChatViewSound],
  )

  /**
   * №27 success (T029): close the shell, converge №12 (the creator's
   * group materializes from the server aggregate — title, memberCount,
   * `myRole`, the FR-013 zero badge) and open the group window on the
   * fresh `GroupView` (quickstart §3.1: the group is immediately in
   * «Чаты» and open for the creator).
   */
  const handleGroupCreated = useCallback(
    (group: GroupView) => {
      setModalForm(null)
      reloadChatList()
      openChatView({
        kind: 'group',
        chatId: group.chatId,
        title: group.title,
        memberCount: group.members.length,
        myRole: group.myRole,
      })
    },
    [reloadChatList, openChatView],
  )

  const handleSelectChat = useCallback(
    (chatId: string) => {
      // Строка кликнута из drawer — closeSidebar едет вместе с
      // selectChat (прототип §События): затемнение уходит, окно
      // открывается (T065).
      setDrawerOpen(false)
      const item = chats.find((entry) => entry.chatId === chatId)
      if (item?.type === 'group') {
        // The unified list (T028): a group row opens the GROUP window —
        // the №12 title/memberCount/myRole is the server-owned header
        // basis of US1 (superseded by the №28 view once it is live).
        openChatView({
          kind: 'group',
          chatId: item.chatId,
          title: item.title ?? '',
          memberCount: item.memberCount ?? null,
          myRole: item.myRole ?? null,
        })
        return
      }
      if (item !== undefined && item.peer !== null) {
        openChatView({
          kind: 'direct',
          chatId: item.chatId,
          peer: item.peer,
          peerAlias: item.peerAlias ?? null,
          blockedByMe: item.blockedByMe ?? false,
        })
        return
      }
      // The row can only be clicked while present in №12, but the list
      // may refresh mid-click — fall back to the №13 view.
      void (async () => {
        try {
          const view = await getChat(chatId)
          // 008a T058: №13-ответ несёт звук-состояние чата вызывающего.
          adoptChatViewSound(view)
          if (view.type === 'group') {
            openChatView({
              kind: 'group',
              chatId: view.chatId,
              title: view.title ?? '',
              memberCount: view.memberCount ?? null,
              myRole: view.myRole ?? null,
            })
            return
          }
          if (view.peer !== null) {
            openChatView({
              kind: 'direct',
              chatId: view.chatId,
              peer: view.peer,
              peerAlias: view.peerAlias ?? null,
              blockedByMe: view.blockedByMe ?? false,
            })
          }
        } catch (cause) {
          setActionError(cause)
        }
      })()
    },
    [chats, openChatView, setDrawerOpen, adoptChatViewSound],
  )

  /** №14 DELETE /chats/{chatId} + outbox purge (FR-021, T060) — direct only. */
  const handleConfirmDeleteChat = useCallback(() => {
    const target = activeChat
    if (target?.kind !== 'direct') {
      return
    }
    setActionPending(true)
    void (async () => {
      try {
        await deleteChat(target.chatId)
        // Local optimistic/failed records die with the chat — a later
        // resurrecting incoming (FR-021) starts from a clean slate.
        outbox.purgeChat(target.chatId)
        // T045 (FR-025): один тост на завершённый №14 — текст
        // прототипа deleteChat (private), как и у пути «Контактов».
        showToast('Чат удалён — контакт сохранён')
        setPendingAction(null)
        setActiveChat(null)
        reloadChatList()
      } catch (cause) {
        setActionError(cause)
        setPendingAction(null)
      } finally {
        setActionPending(false)
      }
    })()
  }, [activeChat, outbox, reloadChatList, showToast])

  /** №23/№24 block toggle driven by `blockedByMe` (FR-020, T060) — direct only. */
  const handleConfirmBlockToggle = useCallback(() => {
    const target = activeChat
    if (target?.kind !== 'direct') {
      return
    }
    const blocking = !target.blockedByMe
    setActionPending(true)
    void (async () => {
      try {
        if (blocking) {
          await blockUser(target.peer.id)
        } else {
          await unblockUser(target.peer.id)
        }
        // T045 (FR-025): один тост на завершённый №23/№24 — тексты
        // прототипа askBlock/askUnblock, как и у пути «Контактов».
        showToast(
          blocking
            ? `Контакт заблокирован — ${target.peer.username}`
            : `Контакт разблокирован — ${target.peer.username}`,
        )
        setActiveChat((previous) =>
          previous !== null && previous.kind === 'direct'
            ? { ...previous, blockedByMe: blocking }
            : previous,
        )
        setPendingAction(null)
        // The №12 refetch converges the «заблокирован» mark and the
        // badge to the server state (both endpoints are idempotent).
        reloadChatList()
      } catch (cause) {
        setActionError(cause)
        setPendingAction(null)
      } finally {
        setActionPending(false)
      }
    })()
  }, [activeChat, reloadChatList, showToast])

  /**
   * «Добавить в контакты» of the gear menu (T054, FR-012): №21 with the
   * peer's userId — идемпотентно 201/200 без дублей, поэтому без
   * подтверждения; «уже в контактах» видно по текущей книге (паттерн
   * ContactsModal), один тост прототипа addContact, №20 сходится.
   */
  const handleGearAddContact = useCallback(() => {
    const target = activeChat
    if (target?.kind !== 'direct') {
      return
    }
    const known = contacts.some((contact) => contact.user.id === target.peer.id)
    void (async () => {
      try {
        await addContact(target.peer.id)
        showToast(
          known
            ? `Уже в контактах — ${target.peer.username}`
            : `Контакт добавлен — ${target.peer.username}`,
        )
        reloadContacts()
      } catch (cause) {
        // Сбой — инлайн-ошибкой окна, тостом не отмечается (T045).
        setActionError(cause)
      }
    })()
  }, [activeChat, contacts, reloadContacts, showToast])

  /**
   * «Заблокировать/Разблокировать контакт» of the gear (T054): entry to
   * the confirm form — №23/№24 stay with handleConfirmBlockToggle above.
   */
  const handleGearToggleBlock = useCallback(() => {
    setPendingAction(activeChat?.kind === 'direct' && activeChat.blockedByMe ? 'unblock' : 'block')
  }, [activeChat])

  /**
   * «Удалить чат» of the gear (T054): direct — №14, group — №30; the
   * entries ride the SAME confirm form of the shell (SC-007).
   */
  const handleGearDeleteChat = useCallback(() => {
    setPendingAction(activeChat?.kind === 'group' ? 'delete-group' : 'delete-chat')
  }, [activeChat])

  /**
   * Bell-колокол заголовка (008a US4, T057; FR-013, ui-behavior §4.1):
   * №42 с ЦЕЛЕВЫМ значением кнопки. Успех — состояние сходится ОТВЕТОМ
   * №42 (echo сохранённого, не локальным оптимизмом), один тост
   * «Звуковые оповещения включены/отключены — {имя чата}» (личный —
   * peer по цепочке FR-003, группа — название 006: живой №28-титул,
   * иначе №12-базис) и звук отклика §4.2 («вкл» — playBellTone, «выкл»
   * — глухой щелчок playMuteTone). Ошибка сети/429 — тост ошибки
   * problemMessage, состояние НЕ меняется (офлайн-очереди нет — №42
   * идемпотентен, повторный клик просто пошлёт то же значение).
   */
  const handleToggleChatSound = useCallback(
    (next: boolean) => {
      const target = activeChat
      if (target === null) {
        return
      }
      const liveGroupTitle =
        activeGroup !== null && activeGroup.chatId === target.chatId && target.kind === 'group'
          ? activeGroup.title
          : null
      const chatName =
        target.kind === 'direct'
          ? resolveDisplayName(
              target.peerAlias ?? undefined,
              target.peer.displayName,
              target.peer.username,
            )
          : (liveGroupTitle ?? target.title)
      void (async () => {
        try {
          const answer = await setChatSound(target.chatId, next)
          setSoundStates((previous) => {
            const updated = new Map(previous)
            updated.set(target.chatId, answer.soundEnabled)
            return updated
          })
          if (answer.soundEnabled) {
            playBellTone()
          } else {
            playMuteTone()
          }
          showToast(
            `Звуковые оповещения ${answer.soundEnabled ? 'включены' : 'отключены'} — ${chatName}`,
          )
        } catch (cause) {
          showToast(problemMessage(cause))
        }
      })()
    },
    [activeChat, activeGroup, showToast],
  )

  /**
   * «Добавить в контакты» строки модали «Участники» (T055/T057,
   * FR-012): №21 с id участника — идемпотентно 201/200 без дублей,
   * один тост прототипа addContact (имя — из живого №28-ростра),
   * №20-база шестерёнки/модали сходится; сбой — инлайн-ошибка окна,
   * тостом не отмечается (T045).
   */
  const handleMemberAddContact = useCallback(
    (userId: string) => {
      const known = contacts.some((contact) => contact.user.id === userId)
      const username =
        activeGroup?.members.find((member) => member.user.id === userId)?.user.username ?? ''
      void (async () => {
        try {
          await addContact(userId)
          showToast(known ? `Уже в контактах — ${username}` : `Контакт добавлен — ${username}`)
          reloadContacts()
        } catch (cause) {
          setActionError(cause)
        }
      })()
    },
    [activeGroup, contacts, reloadContacts, showToast],
  )

  /**
   * №14 success FROM the contacts form (008 T037; FR-032,
   * Clarification edge case): the deleted chat may be the OPEN one —
   * the window returns to the «Чат не выбран» empty state WITHOUT any
   * auto-transition to a neighbouring chat; a delete of a non-open
   * chat never touches the open window. The local outbox records of
   * the chat die with it (FR-021 — a later resurrecting incoming
   * starts from a clean slate), and №12 re-runs so the sidebar row
   * converges (the modal itself stays open — the contact survives,
   * FR-017).
   */
  const handleChatDeleted = useCallback(
    (chatId: string) => {
      outbox.purgeChat(chatId)
      setActiveChat((previous) =>
        previous !== null && previous.chatId === chatId ? null : previous,
      )
      reloadChatList()
    },
    [outbox, reloadChatList],
  )

  /**
   * №23/№24 success FROM the contacts form (008 T089; Bug 11а, FR-020):
   * the №12 refetch converges the mark of the contacts row and the
   * sidebar row («заблокирован»), the composer lock of the open dialog
   * and the gear label LIVE — the header effect reconciles
   * `blockedByMe` from the fresh aggregate, without a reload, while the
   * modal stays open (the onChatDeleted pattern of T037). Both
   * endpoints are idempotent — a duplicate report refetches the same
   * truth.
   */
  const handleContactBlockToggled = useCallback(() => {
    reloadChatList()
  }, [reloadChatList])

  /**
   * №40 success FROM the contacts form (008a T022; FR-003, ui-behavior
   * §1.2 «все поверхности владельца обновляются»): №12 converges AT
   * ONCE — peerAlias/displayName of «Чаты» rows stop being stale
   * without closing the shell/F5 (рефетч-семантика: realtime-события
   * смены имени нет).
   */
  const handleContactRenamed = useCallback(() => {
    reloadChatList()
  }, [reloadChatList])

  /**
   * №22 success FROM the contacts form (008 T089; Bug 11б, FR-017): the
   * №20 book of the page converges AT ONCE — the «Добавить в контакты»
   * offers of the gear and the members modal return immediately, not
   * only with the shell closure (closeShell keeps its own convergence
   * as the safety net of every other mutation path).
   */
  const handleContactRemoved = useCallback(() => {
    reloadContacts()
  }, [reloadContacts])

  /**
   * Bug 15 (T093б; FR-025, лексика T082/T045): отказ постановки в
   * очередь подаётся ТОСТОМ о факте отклонения отправки — инлайн-абзац
   * `messenger-composer-error` удалён. Отказ — защитная ветка (композер
   * disabled без пользователя, текст предвалидирован MessageInput);
   * queue_overflow НЕ является отказом (eviction в enqueue принимает
   * запись) и остаётся за персистентным QueueOverflowBanner T052.
   */
  const handleSend = useCallback(
    (text: string) => {
      if (activeChatId === null) {
        return
      }
      const result = outbox.enqueue(activeChatId, text)
      if (!result.ok) {
        showToast(result.error)
      }
    },
    [activeChatId, outbox, showToast],
  )

  const handleRetry = useCallback(
    (clientMessageId: string) => {
      outbox.retry(clientMessageId)
    },
    [outbox],
  )

  const handleRemove = useCallback(
    (clientMessageId: string) => {
      outbox.remove(clientMessageId)
    },
    [outbox],
  )

  /**
   * №31 success (T046a): the panel hands up the returned active roster
   * — the №28 `reload()` converges the card (and the attribution
   * roster) to the server truth at once; the `group.member.added`
   * frames of the same commit land optimistically meanwhile.
   */
  const handleMembersAdded = useCallback(() => {
    reloadActiveGroup()
  }, [reloadActiveGroup])

  /**
   * №29 success (US4, T053 → T056/T057): прототип grpEditForm
   * закрывает оболочку на завершённый submit (closeModal), №28
   * `reload()` сходится сразу; конвейер №31/№32 остатка доедет в фоне
   * теми же колбэками (handleMembersAdded). Тост завершённого submit
   * выдаёт сама форма (T045). The §3.1 `group.updated` frame of the
   * same commit lands on the «Чаты» row (useChatList) and every other
   * viewer's state optimistically.
   */
  const handleGroupUpdated = useCallback(() => {
    closeShell()
    reloadActiveGroup()
  }, [closeShell, reloadActiveGroup])

  /**
   * №33/№30 success (US6, T066; FR-005/FR-006): the 204 means the
   * viewer's membership (№33) or the whole chat (№30) is gone
   * server-side — the window closes at once, the chatId leaves the
   * shared №25 batcher (§5.5 — the caller is a non-participant now,
   * №25 is all-or-refusal), and №12 re-runs: the row's deterministic
   * removal normally arrives through the §3.6 `group.you_removed
   * {reason:'left'}` / §3.5 `group.deleted` frames of the same commit,
   * the reload covers an at-most-once loss of the frame.
   */
  const handleWindowGroupGone = useCallback(
    (chatId: string) => {
      if (currentUserId !== null) {
        getAckBatcher(currentUserId).drop(chatId)
      }
      setActiveChat((previous) =>
        previous !== null && previous.kind === 'group' && previous.chatId === chatId
          ? null
          : previous,
      )
      reloadChatList()
    },
    [currentUserId, reloadChatList],
  )

  /** №33 leave success of the open group window (gear → confirm, T054/T057). */
  const handleLeftGroup = useCallback(() => {
    if (activeGroupChatId !== null) {
      // T045 (FR-025): один тост на завершённый №33 — текст
      // прототипа askLeaveGroup; имя — живой №28-титул, иначе №12-базис.
      const live = activeGroup?.chatId === activeGroupChatId
      const basisTitle = activeChat?.kind === 'group' ? activeChat.title : ''
      const title = live ? activeGroup.title : basisTitle
      showToast(`Вы вышли из чата — ${title}`)
      handleWindowGroupGone(activeGroupChatId)
    }
  }, [activeGroupChatId, activeGroup, activeChat, handleWindowGroupGone, showToast])

  /** №30 hard-delete success of the open group window (gear → confirm, T054/T057). */
  const handleDeletedGroup = useCallback(() => {
    if (activeGroupChatId !== null) {
      // T045 (FR-025): один тост на завершённый №30 — текст прототипа
      // deleteChat (групповая ветвь, без имени).
      showToast('Групповой чат удалён')
      handleWindowGroupGone(activeGroupChatId)
    }
  }, [activeGroupChatId, handleWindowGroupGone, showToast])

  /**
   * №33 leave confirmed FROM the gear menu (T054): the askLeaveGroup
   * confirm runs №33 here — success rides the SAME toast + window
   * convergence as the card controls (handleLeftGroup above); a
   * failure stays an inline window error, toast-free (T045).
   */
  const handleConfirmLeaveGroup = useCallback(() => {
    const target = activeChat
    if (target?.kind !== 'group' || actionPending) {
      return
    }
    setActionPending(true)
    void (async () => {
      try {
        await leaveGroup(target.chatId)
        setPendingAction(null)
        handleLeftGroup()
      } catch (cause) {
        setActionError(cause)
        setPendingAction(null)
      } finally {
        setActionPending(false)
      }
    })()
  }, [activeChat, actionPending, handleLeftGroup])

  /**
   * №30 hard-delete confirmed FROM the gear menu (T054): the group
   * branch of askDeleteChat runs №30 here — the shared toast + window
   * convergence of handleDeletedGroup above.
   */
  const handleConfirmDeleteGroup = useCallback(() => {
    const target = activeChat
    if (target?.kind !== 'group' || actionPending) {
      return
    }
    setActionPending(true)
    void (async () => {
      try {
        await deleteGroup(target.chatId)
        setPendingAction(null)
        handleDeletedGroup()
      } catch (cause) {
        setActionError(cause)
        setPendingAction(null)
      } finally {
        setActionPending(false)
      }
    })()
  }, [activeChat, actionPending, handleDeletedGroup])

  const chatOutbox =
    activeChatId === null ? [] : outbox.records.filter((record) => record.chatId === activeChatId)
  const dialogOpen = activeChat !== null
  const confirmation = confirmationOf(pendingAction, activeChat)
  /**
   * The shell formId (T034): a pending header confirmation rides
   * 'confirm' — an open(X) → open(confirm) switch inside the SAME
   * shell, never a second backdrop (data-model 3.3).
   */
  const shellFormId: ModalFormId | null = pendingAction !== null ? 'confirm' : modalForm
  const shellTitle = shellTitleOf(confirmation, shellFormId)

  const renderShellInhabitants = () => (
    <>
      {(shellFormId === 'contacts' ||
        shellFormId === 'add-contact' ||
        shellFormId === 'rename-contact') && (
        <ContactsModal
          chats={chats}
          onOpenChat={handleOpenChatFromModal}
          onChatDeleted={handleChatDeleted}
          onContactBlockToggled={handleContactBlockToggled}
          onContactRemoved={handleContactRemoved}
          onContactRenamed={handleContactRenamed}
          onFormChange={(form) => {
            setModalForm(CONTACTS_SHELL_FORMS[form])
          }}
        />
      )}
      {shellFormId === 'profile' && <ProfileModal onClose={closeShell} />}
      {/* grpForm-проекция прототипа (T035): №27 + валидация 006, тост
          «Групповой чат создан — {title}» у формы, окно группы —
          handleGroupCreated. */}
      {shellFormId === 'create-group' && (
        <CreateGroupDialog onCreated={handleGroupCreated} onCancel={closeShell} />
      )}
      {/* Групповые формы «шестерёнки» (T057): №28-ростер сходится в
          слот оболочки — «Участники» (T055) несут ростер-действия
          мьютекса useGroupMembers и №21-предложение строки (тост — у
          страницы), «Редактировать» (T056) — конвейер №29+№31/№32;
          успех №29 закрывает оболочку (прототип grpEditForm →
          closeModal), тост завершённого submit — у самой формы (T045).
          T098: строки «Участников» — навигация: №12-базис + штатный
          onOpenChat владельца оболочки (как у «Контактов»). */}
      {(shellFormId === 'group-members' || shellFormId === 'group-edit') &&
        activeChat?.kind === 'group' && (
          <GroupShellForm
            formId={shellFormId}
            group={activeGroup}
            chatId={activeChat.chatId}
            status={activeGroupStatus}
            error={activeGroupError}
            currentUserId={currentUserId}
            rosterActions={rosterActions}
            chats={chats}
            onOpenChat={handleOpenChatFromModal}
            contactUserIds={contactUserIds}
            onAddContact={handleMemberAddContact}
            onReload={reloadActiveGroup}
            onMembersAdded={handleMembersAdded}
            onUpdated={handleGroupUpdated}
            onCancel={closeShell}
          />
        )}
      {shellFormId === 'confirm' && confirmation !== null && (
        <ConfirmDialog
          text={confirmation.text}
          confirmLabel={confirmation.confirmLabel}
          variant={confirmation.variant}
          onConfirm={() => {
            if (actionPending) {
              return
            }
            if (pendingAction === 'delete-chat') {
              handleConfirmDeleteChat()
            } else if (pendingAction === 'leave-group') {
              handleConfirmLeaveGroup()
            } else if (pendingAction === 'delete-group') {
              handleConfirmDeleteGroup()
            } else {
              handleConfirmBlockToggle()
            }
          }}
          onCancel={closeShell}
        />
      )}
    </>
  )

  return (
    <>
      {/* US5 кнопка каталога (T065; FR-029, design-tokens §9): плавающий
          burger открывает drawer сайдбара на ≤900px (≥901px скрыт CSS
          прототипа); title «Directory» прототипа — русская строка,
          доступное имя — паттерн menu-btn «Меню» (T030). */}
      <button
        type="button"
        className={drawerOpen ? 'burger open' : 'burger'}
        title="Каталог чатов"
        aria-expanded={drawerOpen}
        onClick={() => {
          setDrawerOpen((open) => !open)
        }}
      >
        <span />
        <span />
        <span />
      </button>
      <div className="machine">
        <div className="frame-body">
          <aside
            ref={sidebarRef}
            className={drawerOpen ? 'sidebar panel open' : 'sidebar panel'}
            aria-label="Чаты и контакты"
            inert={narrowViewport && !drawerOpen}
          >
            <QueueOverflowBanner userId={currentUserId} />
            <SyncIndicator syncing={syncing} />
            <ChatListPanel
              chats={chats}
              status={chatListStatus}
              error={chatListError}
              onReload={reloadChatList}
              activeChatId={activeChatId}
              onSelectChat={handleSelectChat}
              currentUserId={currentUserId}
              onOpenProfile={() => {
                setModalForm('profile')
              }}
              onOpenContacts={() => {
                setModalForm('contacts')
              }}
              onCreateGroup={() => {
                setModalForm('create-group')
              }}
            />
          </aside>
          <section className="chat panel" aria-label="Окно диалога">
            {dialogOpen && activeChat !== null ? (
              <>
                <ChatHeader
                  chat={headerChatOf(activeChat, activeGroup, contacts, currentUserId, meUsername)}
                  soundEnabled={soundStates.get(activeChat.chatId) ?? true}
                  onToggleSound={handleToggleChatSound}
                  onAddContact={handleGearAddContact}
                  onToggleBlock={handleGearToggleBlock}
                  onDeleteChat={handleGearDeleteChat}
                  onOpenMembers={() => {
                    setModalForm('group-members')
                  }}
                  onOpenEdit={() => {
                    setModalForm('group-edit')
                  }}
                  onLeaveChat={() => {
                    setPendingAction('leave-group')
                  }}
                />

                {actionError !== null && (
                  <div className="dialog-action-error">
                    <ErrorBanner
                      error={actionError}
                      onDismiss={() => {
                        setActionError(null)
                      }}
                    />
                  </div>
                )}

                {status === 'error' && <ErrorBanner error={error} onDismiss={reload} />}
                <MessageList
                  messages={messages}
                  currentUserId={currentUserId ?? ''}
                  meUsername={meUsername ?? undefined}
                  peerUsername={activeChat.kind === 'direct' ? activeChat.peer.username : undefined}
                  outbox={chatOutbox}
                  onRetry={handleRetry}
                  onRemove={handleRemove}
                  hasOlder={hasOlder}
                  loadingOlder={loadingOlder}
                  onLoadOlder={loadOlder}
                  peerReadUpToSeq={peerReadUpToSeq}
                  members={activeGroupMembers}
                  othersReadUpToSeq={othersReadUpToSeq}
                  unreadFromSeq={unreadFromSeq}
                  ownAckIds={ownAckIds}
                  typing={typingParticipants}
                />
                <MessageInput
                  onSend={handleSend}
                  disabled={currentUserId === null}
                  blocked={activeChat.kind === 'direct' && activeChat.blockedByMe}
                  floodRetryAt={headFloodRetryAt(chatOutbox)}
                  onDraftChange={typingSignals.onDraftChange}
                  onSendAttempt={typingSignals.onSendAttempt}
                />
              </>
            ) : (
              <p className="messenger-empty">Чат не выбран</p>
            )}
          </section>
        </div>
      </div>

      {/* Затемнение drawer (T065): клик закрывает (closeSidebar
          прототипа; слушатель — нативный, в эффекте выше); подложка
          живёт в DOM всегда — паттерн ModalShell, показ классом .show. */}
      <div
        ref={backdropRef}
        className={drawerOpen ? 'backdrop show' : 'backdrop'}
        aria-hidden={drawerOpen ? undefined : true}
      />

      {/* Единая модальная оболочка (T034, data-model 1.6/3.3): все формы
          приложения — жители ОДНОГО .modal-back; переключение formId —
          смена обитателя, подложка не удваивается. Тост-слот ToastProvider
          (z-99) рендерится после — поверх модали (ui-behavior §5). */}
      <ModalShell formId={shellFormId} title={shellTitle} onClose={closeShell}>
        {renderShellInhabitants()}
      </ModalShell>
    </>
  )
}
