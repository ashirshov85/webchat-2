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
 * «доставлено» immediately (US2-6). The active chat selection and the
 * panel actions are wired in T057–T060.
 */
import { useCallback, useEffect, useState } from 'react'
import { getCurrentUser } from '../../api/auth'
import type { Message } from '../../api/chats'
import { ErrorBanner } from '../components/ErrorBanner'
import { MessageInput } from '../components/MessageInput'
import { MessageList } from '../components/MessageList'
import { useChatMessages } from '../hooks/useChatMessages'
import { useOutbox } from '../hooks/useOutbox'

export function MessengerPage() {
  const [currentUserId, setCurrentUserId] = useState<string | null>(null)
  // The panel (T057–T060) will select the dialog; until then no chat is open.
  const [activeChatId] = useState<string | null>(null)
  const [composerError, setComposerError] = useState<string | null>(null)

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

  const { messages, status, error, reload, confirmMessage, hasOlder, loadingOlder, loadOlder } =
    useChatMessages(activeChatId)

  const handleConfirmed = useCallback(
    (message: Message) => {
      confirmMessage(message)
    },
    [confirmMessage],
  )
  const outbox = useOutbox(currentUserId, { onConfirmed: handleConfirmed })

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
  const dialogOpen = activeChatId !== null

  return (
    <div className="messenger">
      <aside className="messenger-panel" aria-label="Чаты и контакты">
        <p className="messenger-empty">Разделы «Чаты» и «Контакты» появятся здесь</p>
      </aside>
      <section className="messenger-dialog" aria-label="Окно диалога">
        {dialogOpen ? (
          <>
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
