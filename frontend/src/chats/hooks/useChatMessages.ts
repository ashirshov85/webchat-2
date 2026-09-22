/**
 * Chat dialog state (feature 004, FR-009): initial load of the latest
 * history page, realtime appends from the shared user event stream
 * (useRealtime, T023) with idempotent dedup by `message.id`, and
 * convergence to the server on every SSE (re)connect — `onOpen`
 * triggers a silent refetch of the latest page; the merged view keeps
 * the server `seq` order and never duplicates or reorders already
 * rendered messages (SC-002/003; contracts/realtime-channel.md §4).
 */
import { useCallback, useEffect, useState } from 'react'
import { listMessages } from '../../api/chats'
import type { Message } from '../../api/chats'
import { useRealtime } from './useRealtime'

export type ChatMessagesStatus = 'loading' | 'ready' | 'error'

export interface UseChatMessagesResult {
  /** Messages of the open chat in ascending `seq` order (oldest first). */
  readonly messages: Message[]
  readonly status: ChatMessagesStatus
  readonly error: unknown
  /** Re-runs the latest-page fetch with the same converge semantics. */
  reload(): void
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

  useEffect(() => {
    setMessages([])
    setError(null)
    setStatus(chatId === null ? 'ready' : 'loading')
  }, [chatId])

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
        setMessages((previous) => reconcileMessages(previous, page.messages))
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
  }, [chatId, refreshCount])

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

  return { messages, status, error, reload }
}
