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
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { listMessages } from '../../api/chats'
import type { Message, MessagePage } from '../../api/chats'
import { useRealtime } from './useRealtime'

export type ChatMessagesStatus = 'loading' | 'ready' | 'error'

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

export function useChatMessages(chatId: string | null): UseChatMessagesResult {
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

  useEffect(() => {
    setMessages([])
    setError(null)
    setStatus(chatId === null ? 'ready' : 'loading')
    setOldestSeq(null)
    setLoadingOlder(false)
    loadingOlderRef.current = false
    epochRef.current += 1
  }, [chatId])

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
    return () => {
      unsubscribeOpen()
      unsubscribeCreated()
    }
  }, [chatId, realtime])

  const reload = useCallback(() => {
    setRefreshCount((count) => count + 1)
  }, [])

  const confirmMessage = useCallback((message: Message) => {
    setMessages((previous) => reconcileMessages(previous, [message]))
  }, [])

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
  }
}
