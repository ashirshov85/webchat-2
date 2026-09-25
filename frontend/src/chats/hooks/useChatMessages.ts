/**
 * Chat dialog state (feature 004, FR-009): initial load of the latest
 * history page, realtime appends from the shared user event stream
 * (useRealtime, T023) with idempotent dedup by `message.id`, and
 * convergence to the server on every SSE (re)connect — `onOpen`
 * triggers a silent refetch of the latest page; the merged view keeps
 * the server `seq` order and never duplicates or reorders already
 * rendered messages (SC-002/003; contracts/realtime-channel.md §4).
 *
 * Outbox confirmation (US2, T036): `confirmMessage` merges the server
 * `Message` returned by the 201/200 send acknowledgement, turning the
 * optimistic «отправляется» entry into «доставлено»; a racing SSE
 * `message.created` frame for the same id converges to a single
 * instance. Outgoing messages sent from another device arrive as
 * regular `message.created` frames on the own stream and render
 * «доставлено» immediately (US2-6 multidevice).
 *
 * History pagination (US3, T039, FR-008): `loadOlder` fetches the page
 * above the rendered window with the exclusive cursor
 * `before = nextBefore` and prepends it via the same reconcile (dedup +
 * stable `seq` order). The cursor only ever moves back: a convergence
 * refetch of the latest page never re-opens already explored history.
 * When a page comes back without `nextBefore` (or empty — the boundary
 * answer), the history is exhausted and loading stops.
 *
 * Read receipts (US4, T044, FR-010): rendering messages of the open
 * dialog advances the local read watermark — every change of the
 * rendered window (initial page, realtime appends, prepended older
 * pages) schedules `POST /read` up to the highest displayed incoming
 * `seq`, throttled to ≤500 ms per request (comfortably inside the
 * SC-007 ≤2 s budget). The mark is monotonic (only advances, seeded
 * from `ChatView.myReadUpToSeq`) and best-effort: a failed POST rolls
 * the local watermark back so the next displayed change retries.
 * `peerReadUpToSeq` for the ✓✓ rendering starts from `ChatView` and
 * advances monotonically on `chat.read` frames; a missed frame is
 * compensated by the ChatView refetch on every SSE (re)connect
 * (FR-009), so the status never regresses.
 *
 * Catch-up pages (feature 005, T023): `applySyncPage` merges the
 * №26/№15 pages useSync applied — the same dedup-by-id reconcile in
 * stable `seq` order, so a page racing a realtime frame of the same
 * message renders once (US1-3). A `truncatedUpToSeq` page is the
 * server's word that history below the point stays deleted: rendered
 * messages at or below it are dropped and never restored (US1-5,
 * FR-007). The delta's `peerReadUpToSeq` advances the ✓✓ watermark
 * monotonically — offline read marks arrive with the catch-up.
 *
 * Offline read marks (feature 005, T034; sync-protocol.md §6): the
 * read point records the per-chat watermark in pendingReads BEFORE
 * each №17 attempt — bounded by the local delivery cursor, because
 * offline reading applies only to previously synchronized messages
 * (US3-7) — and removes it only after the `204`. A lost request or
 * response therefore leaves the entry in place, and the useSync
 * reconnect flush replays it through the idempotent, monotonic №17
 * (server GREATEST): the server counter converges with what the user
 * actually read while offline, and senders get their ✓✓ (US3-7).
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { getChat, listMessages, markChatRead } from '../../api/chats'
import type { Message, MessagePage } from '../../api/chats'
import { getCursor } from '../../sync/cursors'
import { confirmPendingRead, recordPendingRead } from '../../sync/pendingReads'
import { useRealtime } from './useRealtime'

/** Read-mark throttle window (US4, T044): ≤1 POST /read per 500 ms (SC-007 ≤2 s). */
const READ_RECEIPT_THROTTLE_MS = 500

export type ChatMessagesStatus = 'loading' | 'ready' | 'error'

/**
 * One applied catch-up page of the open chat (feature 005, T023):
 * `messages` ascending by `seq` from a №26 delta or a №15 `after`
 * page; `truncatedUpToSeq`/`peerReadUpToSeq` only when the delta
 * carried them (sync-protocol.md §3).
 */
export interface SyncPageUpdate {
  readonly chatId: string
  readonly messages: Message[]
  readonly truncatedUpToSeq?: number
  readonly peerReadUpToSeq?: number
}

export interface UseChatMessagesResult {
  /** Messages of the open chat in ascending `seq` order (oldest first). */
  readonly messages: Message[]
  readonly status: ChatMessagesStatus
  readonly error: unknown
  /** Re-runs the latest-page fetch with the same converge semantics. */
  readonly reload: () => void
  /**
   * Merges the server copy of a sent message (201/200 response of the
   * outbox send, T036) with the usual dedup/order semantics.
   */
  readonly confirmMessage: (message: Message) => void
  /** Older history pages exist above the rendered window (FR-008). */
  readonly hasOlder: boolean
  /** An older page request is currently in flight. */
  readonly loadingOlder: boolean
  /**
   * Loads one older page (`before = nextBefore`, exclusive cursor) and
   * prepends it. No-op when the history is exhausted, no chat is open
   * or a page is already in flight; keeps the cursor on failure so the
   * user can retry by scrolling again.
   */
  readonly loadOlder: () => void
  /**
   * Peer's read watermark of the open chat (US4): outgoing messages
   * with `seq ≤ peerReadUpToSeq` render ✓✓. Monotonic — starts from
   * `ChatView.peerReadUpToSeq`, advances on `chat.read` frames and
   * ChatView refetches, never regresses.
   */
  readonly peerReadUpToSeq: number
  /**
   * Merges an applied catch-up page of THIS chat into the rendered
   * window (feature 005, T023): dedup by `message.id`, stable `seq`
   * order, drop at/below `truncatedUpToSeq`, monotonic ✓✓ watermark.
   * Pages of other chats are ignored — each dialog consumes its own.
   */
  readonly applySyncPage: (update: SyncPageUpdate) => void
}

function isSameMessage(a: Message, b: Message): boolean {
  return (
    a.id === b.id &&
    a.chatId === b.chatId &&
    a.senderId === b.senderId &&
    a.text === b.text &&
    a.seq === b.seq &&
    a.createdAt === b.createdAt
  )
}

/**
 * Merges incoming server messages into the rendered list: dedup by
 * `message.id` (server copy wins), stable ascending `seq` order. Returns
 * the previous array reference when nothing changed — idempotent render.
 */
function reconcileMessages(existing: Message[], incoming: Message[]): Message[] {
  if (incoming.length === 0) {
    return existing
  }
  const byId = new Map<string, Message>()
  for (const message of existing) {
    byId.set(message.id, message)
  }
  let changed = false
  for (const message of incoming) {
    const current = byId.get(message.id)
    if (current === undefined || !isSameMessage(current, message)) {
      byId.set(message.id, message)
      changed = true
    }
  }
  if (!changed) {
    return existing
  }
  return [...byId.values()].sort((a, b) => a.seq - b.seq)
}

export function useChatMessages(
  chatId: string | null,
  userId: string | null = null,
): UseChatMessagesResult {
  const realtime = useRealtime()
  const [messages, setMessages] = useState<Message[]>([])
  const [status, setStatus] = useState<ChatMessagesStatus>(() =>
    chatId === null ? 'ready' : 'loading',
  )
  const [error, setError] = useState<unknown>(null)
  const [refreshCount, setRefreshCount] = useState(0)
  /**
   * Exclusive cursor of the oldest explored page (FR-008): the `before`
   * value for the next older-page request. `null` — no older history is
   * known to exist (exhausted or nothing loaded yet).
   */
  const [oldestSeq, setOldestSeq] = useState<number | null>(null)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const loadingOlderRef = useRef(false)
  /** Invalidates in-flight older-page responses on chat switch. */
  const epochRef = useRef(0)
  /** Peer id of the open chat (from ChatView) — identifies incoming messages. */
  const [peerUserId, setPeerUserId] = useState<string | null>(null)
  /** Peer's read watermark for the ✓✓ rendering (US4) — monotonic. */
  const [peerReadUpToSeq, setPeerReadUpToSeq] = useState(0)
  /** Highest seq already marked read locally (monotonic, per open chat). */
  const lastSentReadSeqRef = useRef(0)
  /** Coalesced read-mark target waiting for the throttle window (US4). */
  const pendingReadSeqRef = useRef(0)
  /** Timestamp of the last POST /read — the ≤500 ms throttle anchor. */
  const lastReadSentAtRef = useRef(0)
  const readTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    setMessages([])
    setError(null)
    setStatus(chatId === null ? 'ready' : 'loading')
    setOldestSeq(null)
    setLoadingOlder(false)
    loadingOlderRef.current = false
    epochRef.current += 1
    setPeerUserId(null)
    setPeerReadUpToSeq(0)
    if (readTimerRef.current !== null) {
      clearTimeout(readTimerRef.current)
      readTimerRef.current = null
    }
    pendingReadSeqRef.current = 0
    lastSentReadSeqRef.current = 0
    lastReadSentAtRef.current = 0
  }, [chatId])

  useEffect(() => {
    return () => {
      if (readTimerRef.current !== null) {
        clearTimeout(readTimerRef.current)
      }
    }
  }, [])

  /**
   * Applies a latest-page fetch (initial load / onOpen convergence):
   * merges the messages and moves the cursor back only — a refetch
   * never re-opens history that was already explored by `loadOlder`.
   */
  const applyLatestPage = useCallback((page: MessagePage) => {
    setMessages((previous) => reconcileMessages(previous, page.messages))
    const nextBefore = page.nextBefore
    if (nextBefore === undefined) {
      setOldestSeq(null)
      return
    }
    setOldestSeq((previous) => (previous === null ? nextBefore : Math.min(previous, nextBefore)))
  }, [])

  useEffect(() => {
    if (chatId === null) {
      return
    }
    let cancelled = false
    void (async () => {
      try {
        const page = await listMessages(chatId)
        if (cancelled) {
          return
        }
        setError(null)
        setStatus('ready')
        applyLatestPage(page)
      } catch (cause) {
        if (cancelled) {
          return
        }
        setError(cause)
        setStatus('error')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [chatId, refreshCount, applyLatestPage])

  /**
   * ChatView of the open chat (US4, T044): seeds `peerReadUpToSeq` for
   * the ✓✓ rendering and `myReadUpToSeq` for the local read watermark.
   * Refetched together with the history on every SSE (re)connect — a
   * `chat.read` frame missed during the disconnect is compensated here
   * (FR-009). Both watermarks only advance (monotonic, US4-5).
   */
  useEffect(() => {
    if (chatId === null) {
      return
    }
    let cancelled = false
    void (async () => {
      try {
        const view = await getChat(chatId)
        if (cancelled) {
          return
        }
        setPeerUserId(view.peer.id)
        setPeerReadUpToSeq((previous) => Math.max(previous, view.peerReadUpToSeq))
        lastSentReadSeqRef.current = Math.max(lastSentReadSeqRef.current, view.myReadUpToSeq)
      } catch {
        // Read marks and ✓✓ are background enhancements: the history
        // request stays the single source of the dialog's error state.
      }
    })()
    return () => {
      cancelled = true
    }
  }, [chatId, refreshCount])

  /**
   * Sends the coalesced read mark (best-effort, monotonic, ≤1 per 500 ms).
   * T034 (§6): the offline watermark is recorded BEFORE the №17 attempt
   * (clamped to the local delivery cursor — offline reading applies
   * only to previously synchronized messages) and removed only after
   * its `204`, so an offline read survives a lost request/response and
   * replays on the useSync reconnect flush.
   */
  const flushReadReceipt = useCallback(
    (chatId: string) => {
      const target = pendingReadSeqRef.current
      if (target <= 0) {
        return
      }
      pendingReadSeqRef.current = 0
      lastReadSentAtRef.current = Date.now()
      const previous = lastSentReadSeqRef.current
      lastSentReadSeqRef.current = target
      if (userId !== null) {
        recordPendingRead(userId, chatId, target, getCursor(userId, chatId))
      }
      void markChatRead(chatId, target)
        .then(() => {
          if (userId !== null) {
            confirmPendingRead(userId, chatId, target)
          }
        })
        .catch(() => {
          // Roll the local watermark back so the next displayed change
          // retries the advance (idempotent server-side, FR-010); the
          // pendingReads entry stays for the reconnect flush (T034).
          if (lastSentReadSeqRef.current === target) {
            lastSentReadSeqRef.current = previous
          }
        })
    },
    [userId],
  )

  /**
   * Read marks on display (US4, T044): every change of the rendered
   * window (initial page, realtime appends, prepended older pages of
   * T039) advances the watermark to the highest displayed incoming
   * `seq` — throttled to ≤500 ms, coalescing rapid updates into the
   * maximal target. Nothing is sent until the peer id is known and the
   * mark actually advances beyond what the server already has.
   */
  useEffect(() => {
    if (chatId === null || peerUserId === null) {
      return
    }
    let target = 0
    for (const message of messages) {
      if (message.senderId === peerUserId && message.seq > target) {
        target = message.seq
      }
    }
    if (target <= lastSentReadSeqRef.current) {
      return
    }
    pendingReadSeqRef.current = Math.max(pendingReadSeqRef.current, target)
    const elapsed = Date.now() - lastReadSentAtRef.current
    if (elapsed >= READ_RECEIPT_THROTTLE_MS) {
      flushReadReceipt(chatId)
      return
    }
    readTimerRef.current ??= setTimeout(() => {
      readTimerRef.current = null
      flushReadReceipt(chatId)
    }, READ_RECEIPT_THROTTLE_MS - elapsed)
  }, [chatId, peerUserId, messages, flushReadReceipt])

  useEffect(() => {
    if (chatId === null) {
      return
    }
    const unsubscribeOpen = realtime.onOpen(() => {
      setRefreshCount((count) => count + 1)
    })
    const unsubscribeCreated = realtime.onMessageCreated(chatId, (event) => {
      if (event.chatId !== chatId) {
        return
      }
      setMessages((previous) => reconcileMessages(previous, [event.message]))
    })
    const unsubscribeRead = realtime.onChatRead(chatId, (event) => {
      if (event.chatId !== chatId) {
        return
      }
      setPeerReadUpToSeq((previous) => Math.max(previous, event.readUpToSeq))
    })
    return () => {
      unsubscribeOpen()
      unsubscribeCreated()
      unsubscribeRead()
    }
  }, [chatId, realtime])

  const reload = useCallback(() => {
    setRefreshCount((count) => count + 1)
  }, [])

  const confirmMessage = useCallback((message: Message) => {
    setMessages((previous) => reconcileMessages(previous, [message]))
  }, [])

  const applySyncPage = useCallback(
    (update: SyncPageUpdate) => {
      if (update.chatId !== chatId) {
        return
      }
      const truncatedUpToSeq = update.truncatedUpToSeq
      if (truncatedUpToSeq !== undefined) {
        // US1-5/FR-007: everything at/below the truncation point is
        // deleted for this user — the server's word outranks any stale
        // local copy of the history.
        setMessages((previous) => previous.filter((message) => message.seq > truncatedUpToSeq))
      }
      setMessages((previous) => reconcileMessages(previous, update.messages))
      const peerReadUpToSeq = update.peerReadUpToSeq
      if (peerReadUpToSeq !== undefined) {
        setPeerReadUpToSeq((previous) => Math.max(previous, peerReadUpToSeq))
      }
    },
    [chatId],
  )

  const loadOlder = useCallback(() => {
    if (chatId === null || oldestSeq === null || loadingOlderRef.current) {
      return
    }
    const epoch = epochRef.current
    loadingOlderRef.current = true
    setLoadingOlder(true)
    void (async () => {
      try {
        const page = await listMessages(chatId, { before: oldestSeq })
        if (epochRef.current !== epoch) {
          return
        }
        // A boundary answer (empty page and/or no nextBefore) is a
        // normal exhaustion signal — stop asking for older history.
        setOldestSeq(
          page.nextBefore !== undefined && page.messages.length > 0 ? page.nextBefore : null,
        )
        setMessages((previous) => reconcileMessages(previous, page.messages))
      } catch {
        // Keep the cursor and the rendered window; the next scroll to
        // the top retries the page.
      } finally {
        if (epochRef.current === epoch) {
          loadingOlderRef.current = false
          setLoadingOlder(false)
        }
      }
    })()
  }, [chatId, oldestSeq])

  return {
    messages,
    status,
    error,
    reload,
    confirmMessage,
    hasOlder: oldestSeq !== null,
    loadingOlder,
    loadOlder,
    peerReadUpToSeq,
    applySyncPage,
  }
}
