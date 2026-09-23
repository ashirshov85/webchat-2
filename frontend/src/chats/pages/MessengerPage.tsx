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
 */
import { useCallback, useEffect, useState } from 'react'
import { getCurrentUser } from '../../api/auth'
import type { PublicUser } from '../../api/auth'
import { blockUser, deleteChat, getChat, unblockUser } from '../../api/chats'
import type { Message } from '../../api/chats'
import { ChatListPanel } from '../components/ChatListPanel'
import { ContactList } from '../components/ContactList'
import { ErrorBanner } from '../components/ErrorBanner'
import { MessageInput } from '../components/MessageInput'
import { MessageList } from '../components/MessageList'
import { UserSearchBox } from '../components/UserSearchBox'
import { useChatList } from '../hooks/useChatList'
import { useChatMessages } from '../hooks/useChatMessages'
import { useOutbox } from '../hooks/useOutbox'

/** The open dialog: everything the header actions need (T060). */
interface ActiveChat {
  readonly chatId: string
  readonly peer: PublicUser
  readonly blockedByMe: boolean
}

/** A confirmation awaiting the user's decision («диалоговое меню»). */
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
  } = useChatList(currentUserId)

  const activeChatId = activeChat?.chatId ?? null

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
  } = useChatMessages(activeChatId)

  const handleConfirmed = useCallback(
    (message: Message) => {
      confirmMessage(message)
    },
    [confirmMessage],
  )
  const outbox = useOutbox(currentUserId, { onConfirmed: handleConfirmed })

  // Chat switches close the action menu and its confirmation; a stale
  // dialog must never act on a chat that is no longer open.
  useEffect(() => {
    setMenuOpen(false)
    setPendingAction(null)
  }, [activeChatId])

  // Keep the header's `blockedByMe` in step with the №12 aggregate:
  // every list refetch (block actions, SSE reconnects) reconciles the
  // open dialog's mark to the server state.
  useEffect(() => {
    if (activeChat === null) {
      return
    }
    const item = chats.find((entry) => entry.chatId === activeChat.chatId)
    if (item !== undefined && item.blockedByMe !== activeChat.blockedByMe) {
      setActiveChat((previous) =>
        previous === null ? previous : { ...previous, blockedByMe: item.blockedByMe },
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

  const handleSelectChat = useCallback(
    (chatId: string) => {
      const item = chats.find((entry) => entry.chatId === chatId)
      if (item !== undefined) {
        openChatView({ chatId: item.chatId, peer: item.peer, blockedByMe: item.blockedByMe })
        return
      }
      // The row can only be clicked while present in №12, but the list
      // may refresh mid-click — fall back to the №13 view.
      void (async () => {
        try {
          const view = await getChat(chatId)
          openChatView({ chatId: view.chatId, peer: view.peer, blockedByMe: view.blockedByMe })
        } catch (cause) {
          setActionError(cause)
        }
      })()
    },
    [chats, openChatView],
  )

  /** №14 DELETE /chats/{chatId} + outbox purge (FR-021, T060). */
  const handleConfirmDeleteChat = useCallback(() => {
    const target = activeChat
    if (target === null) {
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

  /** №23/№24 block toggle driven by `blockedByMe` (FR-020, T060). */
  const handleConfirmBlockToggle = useCallback(() => {
    const target = activeChat
    if (target === null) {
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
          previous === null ? previous : { ...previous, blockedByMe: blocking },
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

  const chatOutbox =
    activeChatId === null ? [] : outbox.records.filter((record) => record.chatId === activeChatId)
  const dialogOpen = activeChat !== null
  const confirmation =
    activeChat !== null && pendingAction !== null
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
        activePeerUserId={activeChat?.peer.id ?? null}
        onOpenChat={(view) => {
          openChatView({ chatId: view.chatId, peer: view.peer, blockedByMe: view.blockedByMe })
        }}
      />
    </div>
  )

  return (
    <div className="messenger">
      <aside className="messenger-panel" aria-label="Чаты и контакты">
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
              <h2 className="dialog-title">{activeChat.peer.username}</h2>
              {activeChat.blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
              <div className="dialog-menu">
                <button
                  type="button"
                  className="dialog-menu-toggle"
                  aria-haspopup="menu"
                  aria-expanded={menuOpen}
                  onClick={() => {
                    setMenuOpen((open) => !open)
                  }}
                >
                  Действия
                </button>
                {menuOpen && (
                  <div className="dialog-menu-items" role="menu" aria-label="Действия с чатом">
                    <button
                      type="button"
                      role="menuitem"
                      className="dialog-menu-item"
                      onClick={() => {
                        setMenuOpen(false)
                        setPendingAction('delete-chat')
                      }}
                    >
                      Удалить чат
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      className="dialog-menu-item"
                      onClick={() => {
                        setMenuOpen(false)
                        setPendingAction(activeChat.blockedByMe ? 'unblock' : 'block')
                      }}
                    >
                      {activeChat.blockedByMe
                        ? 'Разблокировать пользователя'
                        : 'Заблокировать пользователя'}
                    </button>
                  </div>
                )}
              </div>
            </header>

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
              <div className="dialog-confirm" role="dialog" aria-label={confirmation.title}>
                <p className="dialog-confirm-text">{confirmation.text}</p>
                <div className="dialog-confirm-actions">
                  <button
                    type="button"
                    className="dialog-confirm-accept"
                    disabled={actionPending}
                    onClick={() => {
                      if (pendingAction === 'delete-chat') {
                        handleConfirmDeleteChat()
                      } else {
                        handleConfirmBlockToggle()
                      }
                    }}
                  >
                    {actionPending ? 'Выполняется…' : confirmation.confirmLabel}
                  </button>
                  <button
                    type="button"
                    className="dialog-confirm-cancel"
                    disabled={actionPending}
                    onClick={() => {
                      setPendingAction(null)
                    }}
                  >
                    Отмена
                  </button>
                </div>
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
              outbox={chatOutbox}
              onRetry={handleRetry}
              onRemove={handleRemove}
              hasOlder={hasOlder}
              loadingOlder={loadingOlder}
              onLoadOlder={loadOlder}
              peerReadUpToSeq={peerReadUpToSeq}
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
