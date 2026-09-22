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
 * 201/SSE acknowledgement races the optimistic state. The outbox
 * sending/failed states (US2, T036) extend this list later.
 */
import type { Message } from '../../api/chats'

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
}

export function MessageList({ messages, currentUserId, pending = [] }: MessageListProps) {
  const confirmedIds = new Set(messages.map((message) => message.id))
  const activePending = pending.filter((entry) => !confirmedIds.has(entry.clientMessageId))

  if (messages.length === 0 && activePending.length === 0) {
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
    </ol>
  )
}
