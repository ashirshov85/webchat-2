/**
 * Messenger page (feature 004, T025): the application shell of the
 * messenger — the chats/contacts panel on the left and the open dialog
 * window on the right. Mounted on the protected route `/` (with the
 * `/chat` alias — the SSO landing path from feature 003).
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
 *    including its local «отправляется» entries;
 *  * «Заблокировать»/«Разблокировать» (№23/№24, FR-020): idempotent
 *    block toggles driven by `blockedByMe` (the only block projection
 *    the API exposes); after either action the list refetches so the
 *    «заблокирован» mark and the badge converge to the server state.
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
 * switches its `chat.read`/№17 semantics to the group MIN watermark.
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
 */
import { useCallback, useEffect, useState } from 'react'
import { getCurrentUser } from '../../api/auth'
import type { PublicUser } from '../../api/auth'
import type { GroupView } from '../../api/groups'
import { blockUser, deleteChat, getChat, unblockUser } from '../../api/chats'
import type { Message } from '../../api/chats'
import { getAckBatcher } from '../../sync/ack'
import { advanceCursor } from '../../sync/cursors'
import { useSync } from '../../sync/hooks/useSync'
import { ChatListPanel } from '../components/ChatListPanel'
import { ContactList } from '../components/ContactList'
import { ErrorBanner } from '../components/ErrorBanner'
import { MessageInput } from '../components/MessageInput'
import { MessageList } from '../components/MessageList'
import { QueueOverflowBanner } from '../components/QueueOverflowBanner'
import { SyncIndicator } from '../components/SyncIndicator'
import { UserSearchBox } from '../components/UserSearchBox'
import { useChatList } from '../hooks/useChatList'
import { useChatMessages } from '../hooks/useChatMessages'
import { useOutbox } from '../hooks/useOutbox'
import { useRealtime } from '../hooks/useRealtime'
import { CreateGroupDialog } from '../../groups/components/CreateGroupDialog'
import { GroupInfoPanel } from '../../groups/components/GroupInfoPanel'
import { useGroup } from '../../groups/hooks/useGroup'
import { useGroupMembers } from '../../groups/hooks/useGroupMembers'

/** The open direct dialog: everything the header actions need (T060). */
interface DirectChatView {
  readonly kind: 'direct'
  readonly chatId: string
  readonly peer: PublicUser
  readonly blockedByMe: boolean
}

/**
 * The open GROUP window (feature 006, T029; US1): the header title is
 * all the US1 window needs — the roster/roles card is US3 (T046), the
 * window actions are US3/US6. `title` comes from the №12 row or the
 * №27/№13 answer and is server-authoritative.
 */
interface GroupChatView {
  readonly kind: 'group'
  readonly chatId: string
  readonly title: string
}

/** The open dialog of either kind (T029): one window, two headers. */
type ActiveChat = DirectChatView | GroupChatView

/**
 * A confirmation awaiting the user's decision («диалоговое меню») —
 * the actions are DIRECT-dialog only (T029: a group window carries no
 * №14/№23/№24 controls until the US3/US6 group card joins).
 */
type PendingAction = 'delete-chat' | 'block' | 'unblock'

interface ConfirmCopy {
  readonly title: string
  readonly text: string
  readonly confirmLabel: string
}

function confirmCopy(action: PendingAction, peerName: string): ConfirmCopy {
  if (action === 'delete-chat') {
    return {
      title: 'Удаление чата',
      text: `Удалить чат с ${peerName}? История скроется только у вас; неотправленные сообщения этого чата будут удалены.`,
      confirmLabel: 'Удалить чат',
    }
  }
  if (action === 'block') {
    return {
      title: 'Блокировка пользователя',
      text: `Заблокировать ${peerName}? Отправка сообщений будет запрещена в обе стороны.`,
      confirmLabel: 'Заблокировать',
    }
  }
  return {
    title: 'Разблокировка пользователя',
    text: `Разблокировать ${peerName}? Переписка снова станет доступна в обе стороны.`,
    confirmLabel: 'Разблокировать',
  }
}

/**
 * The DIRECT dialog header (T060): the peer title, the «заблокирован»
 * mark and the «Действия» menu (№14 delete + №23/№24 block toggle).
 * A group window carries none of these controls (T029).
 */
interface DirectChatHeaderProps {
  readonly username: string
  readonly blockedByMe: boolean
  readonly menuOpen: boolean
  readonly onToggleMenu: () => void
  readonly onDeleteChat: () => void
  readonly onToggleBlock: () => void
}

function DirectChatHeader({
  username,
  blockedByMe,
  menuOpen,
  onToggleMenu,
  onDeleteChat,
  onToggleBlock,
}: DirectChatHeaderProps) {
  return (
    <>
      <h2 className="dialog-title">{username}</h2>
      {blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
      <div className="dialog-menu">
        <button
          type="button"
          className="dialog-menu-toggle"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onClick={onToggleMenu}
        >
          Действия
        </button>
        {menuOpen && (
          <div className="dialog-menu-items" role="menu" aria-label="Действия с чатом">
            <button
              type="button"
              role="menuitem"
              className="dialog-menu-item"
              onClick={onDeleteChat}
            >
              Удалить чат
            </button>
            <button
              type="button"
              role="menuitem"
              className="dialog-menu-item"
              onClick={onToggleBlock}
            >
              {blockedByMe ? 'Разблокировать пользователя' : 'Заблокировать пользователя'}
            </button>
          </div>
        )}
      </div>
    </>
  )
}

/** The «диалоговое меню» confirmation of a pending T060 action. */
interface ConfirmDialogProps {
  readonly confirmation: ConfirmCopy
  readonly pending: boolean
  readonly onAccept: () => void
  readonly onCancel: () => void
}

function ConfirmDialog({ confirmation, pending, onAccept, onCancel }: ConfirmDialogProps) {
  return (
    <dialog className="dialog-confirm" open aria-label={confirmation.title}>
      <p className="dialog-confirm-text">{confirmation.text}</p>
      <div className="dialog-confirm-actions">
        <button
          type="button"
          className="dialog-confirm-accept"
          disabled={pending}
          onClick={onAccept}
        >
          {pending ? 'Выполняется…' : confirmation.confirmLabel}
        </button>
        <button
          type="button"
          className="dialog-confirm-cancel"
          disabled={pending}
          onClick={onCancel}
        >
          Отмена
        </button>
      </div>
    </dialog>
  )
}

export function MessengerPage() {
  const [currentUserId, setCurrentUserId] = useState<string | null>(null)
  const [activeChat, setActiveChat] = useState<ActiveChat | null>(null)
  const [composerError, setComposerError] = useState<string | null>(null)
  /** Dialog action menu (T060) + its pending confirmation. */
  const [menuOpen, setMenuOpen] = useState(false)
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  const [actionPending, setActionPending] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  /** Bumps ContactList refetch after UserSearchBox added a contact. */
  const [contactsRefresh, setContactsRefresh] = useState(0)
  /** The CreateGroupDialog lifetime (T029): closed = the entry button. */
  const [createGroupOpen, setCreateGroupOpen] = useState(false)
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

  /**
   * №27 success (T029): close the dialog, converge №12 (the creator's
   * group materializes from the server aggregate — title, memberCount,
   * `myRole`, the FR-013 zero badge) and open the group window on the
   * fresh `GroupView` (quickstart §3.1: the group is immediately in
   * «Чаты» and open for the creator).
   */
  const handleGroupCreated = useCallback(
    (group: GroupView) => {
      setCreateGroupOpen(false)
      reloadChatList()
      openChatView({ kind: 'group', chatId: group.chatId, title: group.title })
    },
    [reloadChatList, openChatView],
  )

  const handleSelectChat = useCallback(
    (chatId: string) => {
      const item = chats.find((entry) => entry.chatId === chatId)
      if (item?.type === 'group') {
        // The unified list (T028): a group row opens the GROUP window —
        // the №12 title is the server-owned header of US1.
        openChatView({ kind: 'group', chatId: item.chatId, title: item.title ?? '' })
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
            openChatView({ kind: 'group', chatId: view.chatId, title: view.title ?? '' })
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

  const chatOutbox =
    activeChatId === null ? [] : outbox.records.filter((record) => record.chatId === activeChatId)
  const dialogOpen = activeChat !== null
  const confirmation =
    activeChat !== null && activeChat.kind === 'direct' && pendingAction !== null
      ? confirmCopy(pendingAction, activeChat.peer.username)
      : null

  const contactsSection = (
    <div className="panel-contacts">
      <UserSearchBox
        onContactAdded={() => {
          setContactsRefresh((count) => count + 1)
        }}
      />
      <ContactList
        refreshKey={contactsRefresh}
        activePeerUserId={activeChat?.kind === 'direct' ? activeChat.peer.id : null}
        onOpenChat={(view) => {
          if (view.peer !== null) {
            openChatView({
              kind: 'direct',
              chatId: view.chatId,
              peer: view.peer,
              blockedByMe: view.blockedByMe ?? false,
            })
          }
        }}
      />
    </div>
  )

  return (
    <div className="messenger">
      <aside className="messenger-panel" aria-label="Чаты и контакты">
        <QueueOverflowBanner userId={currentUserId} />
        <SyncIndicator syncing={syncing} />
        {createGroupOpen ? (
          <CreateGroupDialog
            onCreated={handleGroupCreated}
            onCancel={() => {
              setCreateGroupOpen(false)
            }}
          />
        ) : (
          <div className="panel-actions">
            <button
              type="button"
              className="panel-create-group"
              onClick={() => {
                setCreateGroupOpen(true)
              }}
            >
              Создать группу
            </button>
          </div>
        )}
        <ChatListPanel
          chats={chats}
          status={chatListStatus}
          error={chatListError}
          onReload={reloadChatList}
          activeChatId={activeChatId}
          onSelectChat={handleSelectChat}
          currentUserId={currentUserId}
          contacts={contactsSection}
        />
      </aside>
      <section className="messenger-dialog" aria-label="Окно диалога">
        {dialogOpen && activeChat !== null ? (
          <>
            <header className="dialog-header">
              {activeChat.kind === 'group' ? (
                <>
                  <span className="chat-item-avatar" aria-hidden="true">
                    #
                  </span>
                  <h2 className="dialog-title">
                    {/* №28 title while the view is live (the optimistic
                        `group.updated` half of the header), the №12/№27
                        title until then. */}
                    {activeGroup?.chatId === activeChat.chatId
                      ? activeGroup.title
                      : activeChat.title}
                  </h2>
                  <button
                    type="button"
                    className="dialog-group-info-toggle"
                    aria-expanded={groupInfoOpen}
                    aria-controls="dialog-group-card"
                    onClick={() => {
                      setGroupInfoOpen((open) => !open)
                    }}
                  >
                    Информация о группе
                  </button>
                </>
              ) : (
                <DirectChatHeader
                  username={activeChat.peer.username}
                  blockedByMe={activeChat.blockedByMe}
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
                    setPendingAction(activeChat.blockedByMe ? 'unblock' : 'block')
                  }}
                />
              )}
            </header>

            {/* The group card (T046a): the №28 GroupInfoPanel under the
                header — loading/error states of the №28 lifecycle stay
                local to the card, the №16 history below is the window's
                own error surface. */}
            {activeChat.kind === 'group' && groupInfoOpen && (
              <div className="dialog-group-card" id="dialog-group-card">
                {activeGroupStatus === 'loading' && (
                  <p className="messenger-empty">Загрузка группы…</p>
                )}
                {activeGroupStatus === 'error' && (
                  <div className="chat-panel-error">
                    <ErrorBanner error={activeGroupError} />
                    <button type="button" className="chat-panel-retry" onClick={reloadActiveGroup}>
                      Повторить
                    </button>
                  </div>
                )}
                {activeGroup?.chatId === activeChat.chatId && activeGroupStatus === 'ready' && (
                  <GroupInfoPanel
                    chatId={activeGroup.chatId}
                    title={activeGroup.title}
                    description={activeGroup.description}
                    members={activeGroup.members}
                    myRole={activeGroup.myRole}
                    currentUserId={currentUserId ?? ''}
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
                    onMembersAdded={handleMembersAdded}
                    onUpdated={handleGroupUpdated}
                  />
                )}
              </div>
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

            {confirmation !== null && pendingAction !== null && (
              <ConfirmDialog
                confirmation={confirmation}
                pending={actionPending}
                onAccept={
                  pendingAction === 'delete-chat'
                    ? handleConfirmDeleteChat
                    : handleConfirmBlockToggle
                }
                onCancel={() => {
                  setPendingAction(null)
                }}
              />
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
            <MessageInput onSend={handleSend} disabled={currentUserId === null} />
          </>
        ) : (
          <p className="messenger-empty">Выберите чат, чтобы начать общение</p>
        )}
      </section>
    </div>
  )
}
