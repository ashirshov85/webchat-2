/**
 * Dialog message list (feature 004, T026): renders the open chat's
 * messages in ascending `seq` order (US1-2). Outgoing messages (sent
 * by the current user) carry a delivery status — server-confirmed
 * entries render «доставлено ✓» (US1-3); optimistic entries passed via
 * `pending` render «отправляется» until the server acknowledges them.
 * Incoming messages never show check marks (US1-3).
 *
 * `pending` entries whose `clientMessageId` already matches a rendered
 * server message are skipped: the server copy wins (message.id =
 * clientMessageId, FR-004), which keeps the render idempotent when the
 * 201/SSE acknowledgement races the optimistic state.
 *
 * Outbox integration (US2, T036, FR-012): records of the open chat are
 * passed via `outbox` and render after the server messages in creation
 * order (research.md §10). `sending` renders «отправляется», terminal
 * `failed` renders «не отправлено» with the manual actions — retry
 * with the same id and local deletion. A record acknowledged by the
 * server (201/200 — its id is already among `messages`) is skipped:
 * the confirmed copy renders «доставлено ✓» instead. Outgoing from
 * another device arrives as a regular server message via the own SSE
 * stream and renders «доставлено ✓» immediately (US2-6).
 */
import type { Message } from '../../api/chats'
import type { OutboxRecord } from '../outbox'

export interface PendingMessage {
  /** Client-generated UUID (FR-004) — becomes message.id on the server. */
  readonly clientMessageId: string
  readonly text: string
}

export interface MessageListProps {
  /** Server-confirmed messages of the open chat (ascending seq order). */
  readonly messages: readonly Message[]
  /** Current user id: senderId === currentUserId marks an outgoing message. */
  readonly currentUserId: string
  /** Optimistic outgoing entries not yet acknowledged by the server. */
  readonly pending?: readonly PendingMessage[]
  /** Outbox records of the open chat (T036): `sending`/`failed` entries. */
  readonly outbox?: readonly OutboxRecord[]
  /** Manual retry of a `failed` record with the same id (FR-012). */
  readonly onRetry?: (clientMessageId: string) => void
  /** Local deletion of a `failed` record (FR-012). */
  readonly onRemove?: (clientMessageId: string) => void
}

export function MessageList({
  messages,
  currentUserId,
  pending = [],
  outbox = [],
  onRetry,
  onRemove,
}: MessageListProps) {
  const confirmedIds = new Set(messages.map((message) => message.id))
  const activePending = pending.filter((entry) => !confirmedIds.has(entry.clientMessageId))
  const activeOutbox = outbox.filter((entry) => !confirmedIds.has(entry.clientMessageId))

  if (messages.length === 0 && activePending.length === 0 && activeOutbox.length === 0) {
    return <p className="messenger-empty">Сообщений пока нет</p>
  }

  return (
    <ol className="message-list" aria-label="Сообщения диалога">
      {messages.map((message) => {
        const outgoing = message.senderId === currentUserId
        return (
          <li key={message.id} className={outgoing ? 'message outgoing' : 'message incoming'}>
            <p className="message-text">{message.text}</p>
            {outgoing && <span className="message-status">доставлено ✓</span>}
          </li>
        )
      })}
      {activePending.map((entry) => (
        <li key={`pending:${entry.clientMessageId}`} className="message outgoing">
          <p className="message-text">{entry.text}</p>
          <span className="message-status">отправляется</span>
        </li>
      ))}
      {activeOutbox.map((entry) => (
        <li key={`outbox:${entry.clientMessageId}`} className="message outgoing">
          <p className="message-text">{entry.text}</p>
          {entry.state === 'failed' ? (
            <>
              <span className="message-status message-status-failed">не отправлено</span>
              <span className="message-actions">
                <button
                  type="button"
                  className="message-retry"
                  onClick={() => {
                    onRetry?.(entry.clientMessageId)
                  }}
                >
                  Повторить
                </button>
                <button
                  type="button"
                  className="message-remove"
                  onClick={() => {
                    onRemove?.(entry.clientMessageId)
                  }}
                >
                  Удалить
                </button>
              </span>
            </>
          ) : (
            <span className="message-status">отправляется</span>
          )}
        </li>
      ))}
    </ol>
  )
}
