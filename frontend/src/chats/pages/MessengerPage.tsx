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
 * contacts / add-contact (ContactsModal — its internal list ↔ add
 * switch lifts into the shell formId via onFormChange) / profile
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
 * title, and the direct-only «Действия» menu (№14/№23/№24 — a 004
 * pair-dialog feature; blocks never apply to groups) stays absent
 * until the group card of US3 joins here. The window body is the
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
 * US3 group card (feature 006, T046a; FR-015): the №28 lifecycle of
 * the open group window — the snapshot AND the live roster/metadata —
 * moved into `useGroup` (one №28 per open, optimistic `group.*`
 * frames on the shared №18 stream, `reload()` convergence), which
 * feeds BOTH the dialog pair (attribution/✓✓, the T038 need) and the
 * GroupInfoPanel card (T046): the header's «Информация о группе»
 * toggle opens the card under the header. The №31 success converges
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
 * US6 leave/delete wiring (feature 006, T066; FR-005/FR-006): the
 * group card mounts the №33/№30 LeaveDeleteControls in its slot —
 * leave for admin/member, hard-delete for the owner, the 403 problem
 * codes (`owner_must_transfer`/`not_group_owner`) surfacing as the
 * card's error hints — and a 204 closes the window at once (the §3.6
 * `group.you_removed {reason:'left'}` / §3.5 `group.deleted` frames of
 * the same commit converge «Чаты» and the batcher tombstone
 * deterministically; the №12 reload the handler fires covers the
 * at-most-once loss of the frame). The `group.deleted` frame itself
 * rides the SAME T058 wiring: it closes an OPEN group window of the
 * hard-deleted chat and tombstones the chatId in the №25 batcher.
 */
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { getCurrentUser } from '../../api/auth'
import type { PublicUser } from '../../api/auth'
import type { GroupView } from '../../api/groups'
import { blockUser, deleteChat, getChat, unblockUser } from '../../api/chats'
import type { ChatView, Message } from '../../api/chats'
import { getAckBatcher } from '../../sync/ack'
import { advanceCursor } from '../../sync/cursors'
import { useSync } from '../../sync/hooks/useSync'
import { ChatHeader } from '../components/ChatHeader'
import type { ChatHeaderChat } from '../components/ChatHeader'
import { ChatListPanel } from '../components/ChatListPanel'
import { ContactsModal } from '../components/ContactsModal'
import { ErrorBanner } from '../components/ErrorBanner'
import { MessageInput } from '../components/MessageInput'
import { MessageList } from '../components/MessageList'
import { ProfileModal } from '../components/ProfileModal'
import { QueueOverflowBanner } from '../components/QueueOverflowBanner'
import { SyncIndicator } from '../components/SyncIndicator'
import { useChatList } from '../hooks/useChatList'
import { useChatMessages } from '../hooks/useChatMessages'
import { useOutbox } from '../hooks/useOutbox'
import { useRealtime } from '../hooks/useRealtime'
import { CreateGroupDialog } from '../../groups/components/CreateGroupDialog'
import { GroupInfoPanel } from '../../groups/components/GroupInfoPanel'
import { LeaveDeleteControls } from '../../groups/components/LeaveDeleteControls'
import { useGroup } from '../../groups/hooks/useGroup'
import type { GroupStatus } from '../../groups/hooks/useGroup'
import { useGroupMembers } from '../../groups/hooks/useGroupMembers'
import type { UseGroupMembersResult } from '../../groups/hooks/useGroupMembers'
import { ConfirmDialog } from '../../ui/ConfirmDialog'
import type { ConfirmVariant } from '../../ui/ConfirmDialog'
import { ModalShell } from '../../ui/ModalShell'
import type { ModalFormId } from '../../ui/ModalShell'
import { ToastProvider } from '../../ui/Toast'
import './messenger.css'

/** The open direct dialog: everything the header actions need (T060). */
interface DirectChatView {
  readonly kind: 'direct'
  readonly chatId: string
  readonly peer: PublicUser
  readonly blockedByMe: boolean
}

/**
 * The open GROUP window (feature 006, T029; US1): the №12/№13/№27
 * `title` + `memberCount` are the server-owned basis of the US1
 * header (ChatHeader, 008 T021) — the live №28 GroupView of the open
 * window (useGroup below) overrides both while it is live; the
 * roster/roles card is US3 (T046), the window actions are US3/US6.
 */
interface GroupChatView {
  readonly kind: 'group'
  readonly chatId: string
  readonly title: string
  readonly memberCount: number | null
}

/** The open dialog of either kind (T029): one window, two headers. */
type ActiveChat = DirectChatView | GroupChatView

/**
 * A confirmation awaiting the user's decision («диалоговое меню») —
 * the actions are DIRECT-dialog only (T029: a group window carries no
 * №14/№23/№24 controls until the US3/US6 group card joins).
 */
type PendingAction = 'delete-chat' | 'block' | 'unblock'

/** The shell 'confirm' inhabitant copy (SC-007: имя — <b>, FR-033). */
interface ConfirmUi {
  readonly title: string
  readonly text: ReactNode
  readonly confirmLabel: string
  readonly variant: ConfirmVariant
}

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
  'create-group': 'Новый групповой чат',
  'group-edit': 'Редактировать групповой чат',
  'group-members': 'Участники',
  profile: 'Мой профиль',
  confirm: 'Подтверждение',
}

/**
 * The header props of the open window (T029 → 008 T021/T043): the page
 * resolves the server-owned chat data into the ChatHeader variant —
 * the GROUP window the №28 title/memberCount/roster (or the №12/№27
 * basis), the direct dialog the peer + the «Действия» menu wiring
 * (T060).
 */
function headerChatOf(
  chat: ActiveChat,
  group: GroupView | null,
  meUserId: string | null,
  meUsername: string | null,
): ChatHeaderChat {
  if (chat.kind === 'group') {
    // №28 title/roster while the view is live (the optimistic
    // `group.updated` half of the header), the №12/№27 basis until
    // then; the roster length IS the memberCount (me included). The
    // live roster + myRole feed the members-tip (008 T043, FR-017);
    // the №12 basis carries no names — null keeps the status row a
    // non-source until №28 lands.
    const live = group !== null && group.chatId === chat.chatId
    return {
      kind: 'group',
      title: live ? group.title : chat.title,
      memberCount: live ? group.members.length : chat.memberCount,
      members: live ? group.members : null,
      myRole: live ? group.myRole : null,
      meUserId,
      meUsername,
    }
  }
  return {
    kind: 'direct',
    peerId: chat.peer.id,
    username: chat.peer.username,
    blockedByMe: chat.blockedByMe,
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
    }
  }
  if (view.peer !== null) {
    return {
      kind: 'direct',
      chatId: view.chatId,
      peer: view.peer,
      blockedByMe: view.blockedByMe ?? false,
    }
  }
  return null
}

/**
 * The group card (T046a): the №28 GroupInfoPanel under the header —
 * loading/error states of the №28 lifecycle stay local to the card,
 * the №16 history below is the window's own error surface.
 */
interface GroupCardProps {
  readonly group: GroupView | null
  readonly chatId: string
  readonly status: GroupStatus
  readonly error: unknown
  readonly currentUserId: string
  readonly onReload: () => void
  readonly rosterActions: UseGroupMembersResult
  readonly onMembersAdded: () => void
  readonly onUpdated: () => void
  readonly onLeft: () => void
  readonly onDeleted: () => void
}

function GroupCard({
  group,
  chatId,
  status,
  error,
  currentUserId,
  onReload,
  rosterActions,
  onMembersAdded,
  onUpdated,
  onLeft,
  onDeleted,
}: GroupCardProps) {
  return (
    <div className="dialog-group-card" id="dialog-group-card">
      {status === 'loading' && <p className="messenger-empty">Загрузка группы…</p>}
      {status === 'error' && (
        <div className="chat-panel-error">
          <ErrorBanner error={error} />
          <button type="button" className="chat-panel-retry" onClick={onReload}>
            Повторить
          </button>
        </div>
      )}
      {group?.chatId === chatId && status === 'ready' && (
        <GroupInfoPanel
          chatId={group.chatId}
          title={group.title}
          description={group.description}
          members={group.members}
          myRole={group.myRole}
          currentUserId={currentUserId}
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
          onMembersAdded={onMembersAdded}
          onUpdated={onUpdated}
          leaveDeleteControls={
            // US6 (T066): the №33 leave / №30 hard-delete controls of
            // the card — the reachable half by `myRole`, the 403 hints
            // of a stale role race render inside the component.
            <LeaveDeleteControls
              chatId={group.chatId}
              myRole={group.myRole}
              onLeft={onLeft}
              onDeleted={onDeleted}
            />
          }
        />
      )}
    </div>
  )
}

export function MessengerPage() {
  const [currentUserId, setCurrentUserId] = useState<string | null>(null)
  /** Own username — the feed avatar source of outgoing rows (008 T022). */
  const [meUsername, setMeUsername] = useState<string | null>(null)
  const [activeChat, setActiveChat] = useState<ActiveChat | null>(null)
  const [composerError, setComposerError] = useState<string | null>(null)
  /** Dialog action menu (T060) + its pending confirmation. */
  const [menuOpen, setMenuOpen] = useState(false)
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  const [actionPending, setActionPending] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  /**
   * The SINGLE modal shell form (T034, data-model 1.6/3.3): the
   * sidebar main menu (T030) opens contacts / add-contact / profile /
   * create-group here, the header confirmations ride the 'confirm'
   * formId — one `.modal-back` for every form, formId switches never
   * stack a second backdrop.
   */
  const [modalForm, setModalForm] = useState<ModalFormId | null>(null)
  /**
   * The GroupInfoPanel card of the open group window (T046a): the
   * header toggle opens/closes it; a chat switch closes it together
   * with the action menu below.
   */
  const [groupInfoOpen, setGroupInfoOpen] = useState(false)

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
  } = useChatList(currentUserId)

  const activeChatId = activeChat?.chatId ?? null

  // №28 lifecycle of the open group window (US3, T046a): useGroup owns
  // the GroupView — one №28 per open, optimistic `group.*` frames,
  // `reload()` convergence — feeding both the dialog pair (the T038
  // attribution/✓✓ roster) and the GroupInfoPanel card below. The
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

  // №32/№34/№35 roster actions of the open group card (US3, T047):
  // useGroupMembers runs every roster mutation through ONE local
  // mutex — `pendingUserId` disables the in-flight MemberList row, a
  // problem (`role_hierarchy_violation`, `not_group_owner`, network)
  // surfaces as the card's rosterError and unblocks the roster. A
  // successful action converges the №28 card through `reload()` (the
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
    applySyncPage: applyDialogSync,
  } = useChatMessages(activeChatId, currentUserId, activeGroupMembers)

  // The §3.1 catch-up loop (feature 005): runs on every SSE (re)open,
  // its `syncing` drives the SyncIndicator below.
  const { syncing, onChatUpdate } = useSync(currentUserId)
  const realtime = useRealtime()

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

  const handleConfirmed = useCallback(
    (message: Message) => {
      confirmMessage(message)
    },
    [confirmMessage],
  )
  const outbox = useOutbox(currentUserId, { onConfirmed: handleConfirmed })

  // Chat switches close the action menu and its confirmation; a stale
  // dialog must never act on a chat that is no longer open. The group
  // card folds together with its window.
  useEffect(() => {
    setMenuOpen(false)
    setPendingAction(null)
    setGroupInfoOpen(false)
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
  useEffect(() => {
    if (activeChat?.kind !== 'direct') {
      return
    }
    const item = chats.find((entry) => entry.chatId === activeChat.chatId)
    const blockedByMe = item?.blockedByMe ?? false
    if (item !== undefined && blockedByMe !== activeChat.blockedByMe) {
      setActiveChat((previous) =>
        previous !== null && previous.kind === 'direct' ? { ...previous, blockedByMe } : previous,
      )
    }
  }, [chats, activeChat])

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

  /** Shell closure (data-model 3.3): Esc / фон / «Отмена» / успех submit. */
  const closeShell = useCallback(() => {
    setModalForm(null)
    setPendingAction(null)
  }, [])

  /**
   * Opening a pair dialog FROM a modal form (T034): the shell closes
   * (closeModal + selectChat прототипа), the №11 ChatView becomes the
   * open window and №12 converges — the new chat lands in «Чаты» at
   * once instead of waiting for the next sync round.
   */
  const handleOpenChatFromModal = useCallback(
    (view: ChatView) => {
      closeShell()
      const next = activeChatOfView(view)
      if (next !== null) {
        openChatView(next)
      }
      reloadChatList()
    },
    [closeShell, openChatView, reloadChatList],
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
      })
    },
    [reloadChatList, openChatView],
  )

  const handleSelectChat = useCallback(
    (chatId: string) => {
      const item = chats.find((entry) => entry.chatId === chatId)
      if (item?.type === 'group') {
        // The unified list (T028): a group row opens the GROUP window —
        // the №12 title/memberCount is the server-owned header basis
        // of US1 (superseded by the №28 view once it is live).
        openChatView({
          kind: 'group',
          chatId: item.chatId,
          title: item.title ?? '',
          memberCount: item.memberCount ?? null,
        })
        return
      }
      if (item !== undefined && item.peer !== null) {
        openChatView({
          kind: 'direct',
          chatId: item.chatId,
          peer: item.peer,
          blockedByMe: item.blockedByMe ?? false,
        })
        return
      }
      // The row can only be clicked while present in №12, but the list
      // may refresh mid-click — fall back to the №13 view.
      void (async () => {
        try {
          const view = await getChat(chatId)
          if (view.type === 'group') {
            openChatView({
              kind: 'group',
              chatId: view.chatId,
              title: view.title ?? '',
              memberCount: view.memberCount ?? null,
            })
            return
          }
          if (view.peer !== null) {
            openChatView({
              kind: 'direct',
              chatId: view.chatId,
              peer: view.peer,
              blockedByMe: view.blockedByMe ?? false,
            })
          }
        } catch (cause) {
          setActionError(cause)
        }
      })()
    },
    [chats, openChatView],
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
  }, [activeChat, outbox, reloadChatList])

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
  }, [activeChat, reloadChatList])

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

  const handleSend = useCallback(
    (text: string) => {
      if (activeChatId === null) {
        return
      }
      const result = outbox.enqueue(activeChatId, text)
      setComposerError(result.ok ? null : result.error)
    },
    [activeChatId, outbox],
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
   * №29 success (US4, T053): the №28 `reload()` converges the card and
   * the window header to the server metadata at once; the §3.1
   * `group.updated` frame of the same commit lands on the «Чаты» row
   * (useChatList) and every other viewer's state optimistically.
   */
  const handleGroupUpdated = useCallback(() => {
    reloadActiveGroup()
  }, [reloadActiveGroup])

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

  /** №33 leave success of the open group window (LeaveDeleteControls). */
  const handleLeftGroup = useCallback(() => {
    if (activeGroupChatId !== null) {
      handleWindowGroupGone(activeGroupChatId)
    }
  }, [activeGroupChatId, handleWindowGroupGone])

  /** №30 hard-delete success of the open group window (LeaveDeleteControls). */
  const handleDeletedGroup = useCallback(() => {
    if (activeGroupChatId !== null) {
      handleWindowGroupGone(activeGroupChatId)
    }
  }, [activeGroupChatId, handleWindowGroupGone])

  const chatOutbox =
    activeChatId === null ? [] : outbox.records.filter((record) => record.chatId === activeChatId)
  const dialogOpen = activeChat !== null
  const confirmation =
    activeChat !== null && activeChat.kind === 'direct' && pendingAction !== null
      ? confirmUiOf(pendingAction, activeChat.peer.username)
      : null
  /**
   * The shell formId (T034): a pending header confirmation rides
   * 'confirm' — an open(X) → open(confirm) switch inside the SAME
   * shell, never a second backdrop (data-model 3.3).
   */
  const shellFormId: ModalFormId | null = pendingAction !== null ? 'confirm' : modalForm
  let shellTitle = ''
  if (confirmation !== null) {
    shellTitle = confirmation.title
  } else if (shellFormId !== null) {
    shellTitle = MODAL_TITLES[shellFormId]
  }

  return (
    <ToastProvider>
      <div className="machine">
        <div className="frame-body">
          <aside className="sidebar panel" aria-label="Чаты и контакты">
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
                  chat={headerChatOf(activeChat, activeGroup, currentUserId, meUsername)}
                  menuOpen={menuOpen}
                  onToggleMenu={() => {
                    setMenuOpen((open) => !open)
                  }}
                  onDeleteChat={() => {
                    setMenuOpen(false)
                    setPendingAction('delete-chat')
                  }}
                  onToggleBlock={() => {
                    setMenuOpen(false)
                    setPendingAction(
                      activeChat.kind === 'direct' && activeChat.blockedByMe ? 'unblock' : 'block',
                    )
                  }}
                  groupInfoOpen={groupInfoOpen}
                  onToggleGroupInfo={() => {
                    setGroupInfoOpen((open) => !open)
                  }}
                />

                {activeChat.kind === 'group' && groupInfoOpen && (
                  <GroupCard
                    group={activeGroup}
                    chatId={activeChat.chatId}
                    status={activeGroupStatus}
                    error={activeGroupError}
                    currentUserId={currentUserId ?? ''}
                    onReload={reloadActiveGroup}
                    rosterActions={rosterActions}
                    onMembersAdded={handleMembersAdded}
                    onUpdated={handleGroupUpdated}
                    onLeft={handleLeftGroup}
                    onDeleted={handleDeletedGroup}
                  />
                )}

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
                {composerError !== null && (
                  <p className="messenger-composer-error" role="alert">
                    {composerError}
                  </p>
                )}
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
                />
                <MessageInput
                  onSend={handleSend}
                  disabled={currentUserId === null}
                  blocked={activeChat.kind === 'direct' && activeChat.blockedByMe}
                />
              </>
            ) : (
              <p className="messenger-empty">Чат не выбран</p>
            )}
          </section>
        </div>
      </div>

      {/* Единая модальная оболочка (T034, data-model 1.6/3.3): все формы
          приложения — жители ОДНОГО .modal-back; переключение formId —
          смена обитателя, подложка не удваивается. Тост-слот ToastProvider
          (z-99) рендерится после — поверх модали (ui-behavior §5). */}
      <ModalShell formId={shellFormId} title={shellTitle} onClose={closeShell}>
        {(shellFormId === 'contacts' || shellFormId === 'add-contact') && (
          <ContactsModal
            chats={chats}
            onOpenChat={handleOpenChatFromModal}
            onChatDeleted={handleChatDeleted}
            onFormChange={(form) => {
              setModalForm(form === 'add' ? 'add-contact' : 'contacts')
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
              } else {
                handleConfirmBlockToggle()
              }
            }}
            onCancel={closeShell}
          />
        )}
      </ModalShell>
    </ToastProvider>
  )
}
