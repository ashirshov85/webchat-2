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
 * with the same id and local deletion. An evicted record (US2, T028,
 * FR-005 — `errorCode='queue_overflow'`, T026) renders the eviction
 * reason «не отправлено (переполнение очереди)» instead of the plain
 * label, so the user sees WHY the message will never leave on its
 * own; the actions stay the same — manual retry by the same id is
 * still allowed (idempotency preserved). A record acknowledged by the
 * server (201/200 — its id is already among `messages`) is skipped:
 * the confirmed copy renders «доставлено ✓» instead. Outgoing from
 * another device arrives as a regular server message via the own SSE
 * stream and renders «доставлено ✓» immediately (US2-6).
 *
 * History pagination (US3, T039, FR-008): the list itself is the scroll
 * container; scrolling close to the top calls `onLoadOlder`, and the
 * freshly prepended older page keeps the viewport anchored to the same
 * newest content (no visual jump). An empty dialog renders the plain
 * empty state without errors.
 *
 * Read status (US4, T044, FR-010): outgoing messages with
 * `seq ≤ peerReadUpToSeq` render «прочитано ✓✓», the rest stay
 * «доставлено ✓». The watermark comes from ChatView and `chat.read`
 * frames and is monotonic (US4-5) — a delivered message never loses
 * its second check mark. Incoming messages never show marks.
 */
import { useEffect, useRef } from 'react'
import type { Message } from '../../api/chats'
import { QUEUE_OVERFLOW_ERROR_CODE } from '../outbox'
import type { OutboxRecord } from '../outbox'

/** Distance from the top (px) that triggers an older-page request. */
const TOP_LOAD_THRESHOLD = 48

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
  /** Older history pages exist above the rendered window (US3). */
  readonly hasOlder?: boolean
  /** An older page request is currently in flight (US3). */
  readonly loadingOlder?: boolean
  /** Requests the next older page via `before=nextBefore` (US3). */
  readonly onLoadOlder?: () => void
  /**
   * Peer's read watermark of the open chat (US4): outgoing messages
   * with `seq ≤ peerReadUpToSeq` render ✓✓. Defaults to 0 — nothing
   * read yet, everything stays «доставлено ✓».
   */
  readonly peerReadUpToSeq?: number
}

export function MessageList({
  messages,
  currentUserId,
  pending = [],
  outbox = [],
  onRetry,
  onRemove,
  hasOlder = false,
  loadingOlder = false,
  onLoadOlder,
  peerReadUpToSeq = 0,
}: MessageListProps) {
  const listRef = useRef<HTMLOListElement>(null)
  /** scrollHeight captured when an older page is requested — the anchor. */
  const anchorHeightRef = useRef<number | null>(null)
  const firstMessageIdRef = useRef<string | undefined>(messages[0]?.id)

  useEffect(() => {
    const list = listRef.current
    const first = messages[0]
    if (list !== null && first !== undefined && anchorHeightRef.current !== null) {
      // Only a prepend (the oldest rendered message changed) shifts the
      // viewport; appends at the bottom keep the scroll untouched.
      if (first.id !== firstMessageIdRef.current) {
        const grown = list.scrollHeight - anchorHeightRef.current
        if (grown > 0) {
          list.scrollTop += grown
        }
      }
    }
    anchorHeightRef.current = null
    firstMessageIdRef.current = first?.id
  }, [messages])

  const handleScroll = () => {
    const list = listRef.current
    if (list === null || loadingOlder || !hasOlder || onLoadOlder === undefined) {
      return
    }
    if (list.scrollTop <= TOP_LOAD_THRESHOLD) {
      anchorHeightRef.current = list.scrollHeight
      onLoadOlder()
    }
  }

  const confirmedIds = new Set(messages.map((message) => message.id))
  const activePending = pending.filter((entry) => !confirmedIds.has(entry.clientMessageId))
  const activeOutbox = outbox.filter((entry) => !confirmedIds.has(entry.clientMessageId))

  if (messages.length === 0 && activePending.length === 0 && activeOutbox.length === 0) {
    return <p className="messenger-empty">Сообщений пока нет</p>
  }

  return (
    <ol
      className="message-list"
      aria-label="Сообщения диалога"
      ref={listRef}
      onScroll={handleScroll}
    >
      {loadingOlder && (
        <li className="message-history" aria-busy="true">
          Загрузка истории…
        </li>
      )}
      {messages.map((message) => {
        const outgoing = message.senderId === currentUserId
        const read = outgoing && message.seq <= peerReadUpToSeq
        return (
          <li key={message.id} className={outgoing ? 'message outgoing' : 'message incoming'}>
            <p className="message-text">{message.text}</p>
            {outgoing && (
              <span className={read ? 'message-status message-status-read' : 'message-status'}>
                {read ? 'прочитано ✓✓' : 'доставлено ✓'}
              </span>
            )}
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
              <span className="message-status message-status-failed">
                {entry.errorCode === QUEUE_OVERFLOW_ERROR_CODE
                  ? 'не отправлено (переполнение очереди)'
                  : 'не отправлено'}
              </span>
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
