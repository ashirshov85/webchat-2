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
 *
 * Catch-up deltas (feature 005, T023): `applySyncUpdate` merges the
 * №26/№15 pages useSync applied — a known chat's preview/position move
 * to the page tail (dedup: only a strictly newer `seq` replaces the
 * preview), and a chat unknown to the list materializes from the
 * delta's metadata (peer, badge) — US1-6's «новый чат появляется со
 * всей перепиской»: the row shows up at once and the №12 refetch of
 * the next (re)connect converges the aggregate.
 *
 * Unread badge convergence (feature 005, T035; US3, FR-007): the
 * counter is server-authoritative and delivery-bounded — the badge is
 * an OPTIMISTIC CACHE of it with three sources:
 *
 *  * №12 refetch replaces the value wholesale (the server truth of
 *    `unread = COUNT(incoming, seq > GREATEST(last_read, deleted),
 *    seq ≤ LEAST(last_seq, delivered))`, data-model 005 сущность 3);
 *  * a №26 delta RE-ANCHORS it: the delta's `unreadCount` is a
 *    pre-ack snapshot (the contract №26 — «сообщения текущей страницы
 *    дельты не входят до ack'а»), so the applied page's incoming
 *    messages are added on top (US3-2: the badge grows by the actual
 *    number of missed messages; offline reads flushed before №26
 *    already lowered the server value, so the anchor may converge the
 *    badge DOWN — US3-7);
 *  * №15 continuation pages and realtime frames ADD optimistically —
 *    each incoming message counts exactly once (a per-chat high-water
 *    of counted `seq`s filters pages against realtime races, US1-3).
 *
 * While `blockedByMe` the badge is FROZEN (US3-5 — FR-020 of 004,
 * semantics unchanged): no realtime increment, no read reset, no
 * delta adoption, and a №12 refetch of a STILL-blocked chat keeps the
 * frozen value; the unblock refetch (block toggles reload №12)
 * converges to the server counter again.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { listChats } from '../../api/chats'
import type { ChatListItem, Message } from '../../api/chats'
import { useRealtime } from './useRealtime'

export type ChatListStatus = 'loading' | 'ready' | 'error'

/**
 * One applied catch-up page handed to the list (feature 005, T023):
 * №26 delta pages carry the chat metadata (`peer`, `blockedByMe`,
 * server counters), №15 continuation pages carry only the tail.
 */
export interface SyncChatListUpdate {
  readonly chatId: string
  readonly messages: Message[]
  readonly peer?: PublicUserLike
  readonly blockedByMe?: boolean
  readonly unreadCount?: number
}

/** Structural `peer` of the delta (№12 projection — id/username/…). */
type PublicUserLike = ChatListItem['peer']

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
   * the user reads; wired into the panel by T058/T060. A blocked chat
   * keeps its value — the FR-020 frozen badge of US3-5.
   */
  readonly markChatReadLocally: (chatId: string) => void
  /**
   * Merges an applied catch-up page into the list (feature 005,
   * T023/T035): preview/position of a known chat follow the page tail
   * (idempotent — only a strictly newer `seq` replaces anything), an
   * unknown chat materializes as a row (US1-6). The badge converges
   * per T035: a №26 delta re-anchors it to the server counter plus
   * the page's incoming (pre-ack snapshot, US3-2/US3-7), a №15
   * continuation adds the not-yet-counted incoming (US1-3), and a
   * blocked chat stays frozen (US3-5).
   */
  readonly applySyncUpdate: (update: SyncChatListUpdate) => void
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
 * position and — for incoming messages — the unread badge (frozen
 * while `blockedByMe`, US3-5). Returns the previous reference when
 * nothing changed (idempotent render). An incremented badge advances
 * the chat's counted high-water, so a later sync page re-carrying the
 * same message does not count it twice (US1-3 race).
 */
function applyMessageCreated(
  chats: ChatListItem[],
  message: Message,
  currentUserId: string | null,
  countedIncomingSeq: Map<string, number>,
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
  const incoming = currentUserId !== null && message.senderId !== currentUserId
  if (incoming && !current.blockedByMe) {
    countedIncomingSeq.set(
      message.chatId,
      Math.max(countedIncomingSeq.get(message.chatId) ?? 0, message.seq),
    )
  }
  const updated: ChatListItem = {
    ...current,
    lastMessage: lastMessage === null || message.seq > lastMessage.seq ? message : lastMessage,
    unreadCount: incoming && !current.blockedByMe ? current.unreadCount + 1 : current.unreadCount,
  }
  if (isSameListItem(current, updated)) {
    return chats
  }
  const next = [...chats]
  next[index] = updated
  return sortChatListItems(next)
}

/**
 * Zeroes the chat's unread badge (FR-014) keeping the position
 * untouched. A blocked chat keeps its value — the FR-020 «заморожен»
 * badge neither grows nor resets until the unblock (US3-5).
 */
function resetUnread(chats: ChatListItem[], chatId: string): ChatListItem[] {
  const index = chats.findIndex((item) => item.chatId === chatId)
  const current = index === -1 ? undefined : chats[index]
  if (current === undefined || current.unreadCount === 0 || current.blockedByMe) {
    return chats
  }
  const next = [...chats]
  next[index] = { ...current, unreadCount: 0 }
  return next
}

/** Incoming (`senderId !== currentUserId`) messages of a page above `aboveSeq`. */
function countIncoming(
  messages: readonly Message[],
  currentUserId: string | null,
  aboveSeq: number,
): number {
  let count = 0
  for (const message of messages) {
    if (message.seq > aboveSeq && currentUserId !== null && message.senderId !== currentUserId) {
      count++
    }
  }
  return count
}

/**
 * Applies one catch-up page to the list (feature 005, T023/T035):
 * known chats move their preview to a strictly newer page tail;
 * unknown chats materialize from the №26 delta metadata (US1-6).
 * The badge follows the T035 convergence rules:
 *
 *  * a №26 delta (`unreadCount` present) RE-ANCHORS: the server
 *    counter is a pre-ack snapshot, the applied page's incoming
 *    messages are added on top (US3-2/US3-7 — up or down);
 *  * a №15 continuation page ADDS only what the counted high-water
 *    has not seen (realtime races count once, US1-3);
 *  * `blockedByMe` freezes the badge (US3-5) — preview and position
 *    still follow the page.
 *
 * Returns the previous reference when nothing changed — idempotent
 * render.
 */
function applySyncDelta(
  chats: ChatListItem[],
  update: SyncChatListUpdate,
  currentUserId: string | null,
  countedIncomingSeq: Map<string, number>,
): ChatListItem[] {
  const tail = update.messages.at(-1)
  const pageTop = tail?.seq ?? 0
  const index = chats.findIndex((item) => item.chatId === update.chatId)
  const current = index === -1 ? undefined : chats[index]
  if (current === undefined) {
    // №15 continuation pages of a chat the list never saw cannot
    // materialize a row (no peer) — the №26 delta of the same cycle
    // always precedes them, so dropping is safe.
    if (update.peer === undefined || tail === undefined) {
      return chats
    }
    const unreadCount =
      update.unreadCount !== undefined
        ? update.unreadCount + countIncoming(update.messages, currentUserId, 0)
        : countIncoming(update.messages, currentUserId, 0)
    countedIncomingSeq.set(
      update.chatId,
      Math.max(countedIncomingSeq.get(update.chatId) ?? 0, pageTop),
    )
    const created: ChatListItem = {
      chatId: update.chatId,
      peer: update.peer,
      lastMessage: tail,
      unreadCount,
      blockedByMe: update.blockedByMe ?? false,
    }
    return sortChatListItems([...chats, created])
  }
  const lastMessage =
    tail !== undefined && (current.lastMessage === null || tail.seq > current.lastMessage.seq)
      ? tail
      : current.lastMessage
  const blockedByMe = update.blockedByMe ?? current.blockedByMe
  let unreadCount = current.unreadCount
  if (!blockedByMe) {
    if (update.unreadCount !== undefined) {
      // The №26 anchor: assignment (not increment) — the server value
      // outranks any local optimism, converging the badge up (missed
      // messages, US3-2) or down (offline reads flushed before №26,
      // US3-7).
      unreadCount = update.unreadCount + countIncoming(update.messages, currentUserId, 0)
    } else {
      // The №15 continuation: only messages above the counted
      // high-water — a realtime-raced message already counted is
      // skipped (US1-3).
      unreadCount =
        current.unreadCount +
        countIncoming(update.messages, currentUserId, countedIncomingSeq.get(update.chatId) ?? 0)
    }
    countedIncomingSeq.set(
      update.chatId,
      Math.max(countedIncomingSeq.get(update.chatId) ?? 0, pageTop),
    )
  }
  const updated: ChatListItem = { ...current, lastMessage, blockedByMe, unreadCount }
  if (isSameListItem(current, updated)) {
    return chats
  }
  const next = [...chats]
  next[index] = updated
  return sortChatListItems(next)
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
  /**
   * Per-chat high-water of incoming `seq`s already reflected in the
   * badge (T035): realtime increments and applied sync pages advance
   * it, so a №15 continuation page re-carrying a raced message counts
   * it once (US1-3). Session-local bookkeeping — never persisted, the
   * server value converges whatever it missed.
   */
  const countedIncomingSeqRef = useRef(new Map<string, number>())

  useEffect(() => {
    // A user switch invalidates every per-chat watermark: the new
    // user's chats (even id-identical ones — the same dialog seen
    // from the peer side) must not inherit the old session's counts.
    countedIncomingSeqRef.current = new Map()
  }, [currentUserId])

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
        applyChats((previous) => {
          // T035/US3-5: a refetch of a STILL-blocked chat keeps the
          // frozen badge (FR-020 «не растёт и не сбрасывается до
          // разблокировки») — the moment of blocking is the value the
          // user saw; a just-blocked or unblocked chat adopts the
          // server value (the unblock refetch converges it).
          const merged = items.map((item) => {
            if (!item.blockedByMe) {
              return item
            }
            const local = previous.find((entry) => entry.chatId === item.chatId)
            return local !== undefined && local.blockedByMe
              ? { ...item, unreadCount: local.unreadCount }
              : item
          })
          return sortChatListItems(merged)
        })
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
        applyMessageCreated(
          previous,
          event.message,
          currentUserIdRef.current,
          countedIncomingSeqRef.current,
        ),
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

  const applySyncUpdate = useCallback(
    (update: SyncChatListUpdate) => {
      applyChats((previous) =>
        applySyncDelta(previous, update, currentUserIdRef.current, countedIncomingSeqRef.current),
      )
    },
    [applyChats],
  )

  return {
    chats,
    status,
    error,
    reload,
    markChatReadLocally,
    applySyncUpdate,
  }
}
