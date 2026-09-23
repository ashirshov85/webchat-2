/**
 * Chat list state (feature 004, T057; FR-013/014, research.md 004 §8):
 * the «Чаты» panel is served by the single №12 aggregate request and
 * stays live WITHOUT manual refreshes:
 *
 *  * `message.created` frames update a known chat locally — the last
 *    message preview, the position (any message, incoming OR outgoing,
 *    lifts the chat: re-sort by `lastMessage.createdAt` DESC, messageless
 *    chats stay below) and the unread badge (incoming messages only,
 *    `senderId !== currentUserId`; while a block is active no frames can
 *    arrive at all — both send directions are refused, FR-020);
 *  * `message.created` with an UNKNOWN `chatId` is the first incoming
 *    message from a stranger (FR-019, US5-4): the hook refetches the
 *    whole list, so the new chat appears by itself — no contact is
 *    required to exist beforehand;
 *  * `chat.read` frames addressed to this user (byUserId === me) zero
 *    the chat's badge — the FR-014 «сбрасывается по мере прочтения»
 *    path; frames from the peer (the only ones the server publishes
 *    today, realtime-channel.md §3.2) belong to the ✓✓ rendering of
 *    the open dialog (useChatMessages) and leave the list untouched;
 *  * every SSE (re)connect (`onOpen`) refetches the list — the FR-009
 *    convergence for everything the at-most-once channel may have
 *    missed while disconnected.
 *
 * Duplicate frames are idempotent at the item level: a frame whose
 * `message.id` already is the rendered preview changes nothing (the
 * badge was counted before), and refetches replace the local state
 * wholesale, so the server stays the single source of truth.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { listChats } from '../../api/chats'
import type { ChatListItem, Message } from '../../api/chats'
import { useRealtime } from './useRealtime'

export type ChatListStatus = 'loading' | 'ready' | 'error'

export interface UseChatListResult {
  /** Chats sorted by the last visible message (FR-014): newest first, messageless last. */
  readonly chats: ChatListItem[]
  readonly status: ChatListStatus
  readonly error: unknown
  /** Re-runs the №12 fetch (error retry, panel refresh actions). */
  readonly reload: () => void
  /**
   * Zeroes the chat's unread badge locally (FR-014): the read marks of
   * the open dialog (useChatMessages POSTs /read) reset the counter as
   * the user reads; wired into the panel by T058/T060.
   */
  readonly markChatReadLocally: (chatId: string) => void
}

/**
 * FR-014 ordering of the panel: by `lastMessage.createdAt` DESC, chats
 * without visible messages after the ones with messages. The sort is
 * stable, so ties keep the server's order (№12 tie-break `chat_id`) and
 * incremental frame updates never reshuffle equal neighbours.
 */
function sortChatListItems(chats: ChatListItem[]): ChatListItem[] {
  return [...chats].sort((a, b) => {
    const aAt = a.lastMessage?.createdAt
    const bAt = b.lastMessage?.createdAt
    if (aAt === undefined && bAt === undefined) {
      return 0
    }
    if (aAt === undefined) {
      return 1
    }
    if (bAt === undefined) {
      return -1
    }
    if (aAt === bAt) {
      return 0
    }
    return aAt < bAt ? 1 : -1
  })
}

function isSameListItem(a: ChatListItem, b: ChatListItem): boolean {
  return (
    a.chatId === b.chatId &&
    a.unreadCount === b.unreadCount &&
    a.blockedByMe === b.blockedByMe &&
    a.lastMessage?.id === b.lastMessage?.id
  )
}

/**
 * Applies one `message.created` frame to a known chat: preview,
 * position and — for incoming messages — the unread badge. Returns the
 * previous reference when nothing changed (idempotent render).
 */
function applyMessageCreated(
  chats: ChatListItem[],
  message: Message,
  currentUserId: string | null,
): ChatListItem[] {
  const index = chats.findIndex((item) => item.chatId === message.chatId)
  const current = index === -1 ? undefined : chats[index]
  if (current === undefined) {
    return chats
  }
  const lastMessage = current.lastMessage
  if (lastMessage !== null && lastMessage.id === message.id) {
    return chats
  }
  const updated: ChatListItem = {
    ...current,
    lastMessage: lastMessage === null || message.seq > lastMessage.seq ? message : lastMessage,
    unreadCount:
      currentUserId !== null && message.senderId !== currentUserId
        ? current.unreadCount + 1
        : current.unreadCount,
  }
  if (isSameListItem(current, updated)) {
    return chats
  }
  const next = [...chats]
  next[index] = updated
  return sortChatListItems(next)
}

/** Zeroes the chat's unread badge (FR-014) keeping the position untouched. */
function resetUnread(chats: ChatListItem[], chatId: string): ChatListItem[] {
  const index = chats.findIndex((item) => item.chatId === chatId)
  const current = index === -1 ? undefined : chats[index]
  if (current === undefined || current.unreadCount === 0) {
    return chats
  }
  const next = [...chats]
  next[index] = { ...current, unreadCount: 0 }
  return next
}

export function useChatList(currentUserId: string | null): UseChatListResult {
  const realtime = useRealtime()
  const [chats, setChats] = useState<ChatListItem[]>([])
  const [status, setStatus] = useState<ChatListStatus>('loading')
  const [error, setError] = useState<unknown>(null)
  const [refreshCount, setRefreshCount] = useState(0)
  /**
   * Synchronous mirror of the list for the realtime listeners (the
   * unknown-`chatId` check of FR-019 must not wait for a commit); a
   * stale read at worst triggers an extra refetch — always safe, the
   * server answer replaces local state (FR-009).
   */
  const chatsRef = useRef<ChatListItem[]>([])
  /** Latest user id without resubscribing the realtime listeners. */
  const currentUserIdRef = useRef<string | null>(currentUserId)
  currentUserIdRef.current = currentUserId

  const applyChats = useCallback((updater: (previous: ChatListItem[]) => ChatListItem[]) => {
    setChats((previous) => {
      const next = updater(previous)
      chatsRef.current = next
      return next
    })
  }, [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const items = await listChats()
        if (cancelled) {
          return
        }
        setError(null)
        setStatus('ready')
        applyChats(() => sortChatListItems(items))
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
  }, [refreshCount, applyChats])

  useEffect(() => {
    const unsubscribeOpen = realtime.onOpen(() => {
      setRefreshCount((count) => count + 1)
    })
    const unsubscribeCreated = realtime.onMessageCreated(null, (event) => {
      if (!chatsRef.current.some((item) => item.chatId === event.chatId)) {
        // FR-019/US5-4: a first incoming message from a stranger — the
        // chat is not in the list yet, so only the server aggregate can
        // materialize it (peer, preview, badge).
        setRefreshCount((count) => count + 1)
        return
      }
      applyChats((previous) =>
        applyMessageCreated(previous, event.message, currentUserIdRef.current),
      )
    })
    const unsubscribeRead = realtime.onChatRead(null, (event) => {
      if (event.byUserId !== currentUserIdRef.current) {
        return
      }
      applyChats((previous) => resetUnread(previous, event.chatId))
    })
    return () => {
      unsubscribeOpen()
      unsubscribeCreated()
      unsubscribeRead()
    }
  }, [realtime, applyChats])

  const reload = useCallback(() => {
    setRefreshCount((count) => count + 1)
  }, [])

  const markChatReadLocally = useCallback(
    (chatId: string) => {
      applyChats((previous) => resetUnread(previous, chatId))
    },
    [applyChats],
  )

  return {
    chats,
    status,
    error,
    reload,
    markChatReadLocally,
  }
}
