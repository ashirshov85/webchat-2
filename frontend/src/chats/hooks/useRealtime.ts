/**
 * Realtime dispatch for feature 004 (contracts/realtime-channel.md):
 * the user event stream is per-user, not per-chat, so all consumers in
 * the tree share one SSE connection opened by the T022 client (one
 * stream per device/session — US2-6 is served by separate streams).
 *
 * The hook demultiplexes `message.created` frames by `chatId`: a
 * listener registered with a concrete chatId receives only that
 * dialog's events, a `null` chatId receives events of every dialog
 * (chat list, incl. new chats from strangers — FR-019). `chat.read`
 * frames (US4, FR-010) follow the same per-chat demultiplexing: the
 * peer advanced their read watermark, so the sender's outgoing
 * messages up to `readUpToSeq` render ✓✓. `onOpen`
 * mirrors the SSE client callback and fires on every (re)connection —
 * the FR-009 trigger for refetching REST state. The channel is
 * at-most-once: consumers deduplicate by `message.id` and reconcile
 * with the server on (re)connect (SC-002/003).
 *
 * Group `chat.read` (feature 006, US2, T039; realtime-group-events.md
 * §3.7): the frame payload stays the 004 shape, but the group fan-out
 * covers every ACTIVE member EXCEPT the reader — so a group listener
 * sees one frame per reading MEMBER (`byUserId`), and the group
 * consumer (useChatMessages with the №28 roster) folds the marks into
 * the ✓✓ candidate MAX(other members' watermarks — ✓✓ once any one
 * of them has read), held monotonically (max, FR-012). The reader's own stream stays silent about his own
 * read, and the unread badge of the reading side resets locally
 * (markChatReadLocally); everything the at-most-once channel missed —
 * badges, watermarks, roster state — converges on (re)connect through
 * the №12/№13 refetches and the №26 catch-up (useSync carries the
 * group `othersReadUpToSeq`), the checkpoint US2 convergence.
 *
 * Feature 006 (T027, realtime-group-events.md §1): the same per-user
 * stream also carries the `group.*` frames, so the dispatcher parses
 * them and hands every frame to the group listeners (`onGroupEvent`,
 * consumed by groups/hooks/useGroupRealtime) — one connection, one
 * multiplexed feed. Unknown `event:` types stay ignored (forward
 * compatibility, 004 §2).
 *
 * Feature 007 (T020, presence-events.md §2): the stream also carries
 * `presence.updated` frames; they are handed to the presence
 * listeners (`onPresenceUpdated`, consumed by presence/usePresence)
 * untouched — the strictly-greater-rev merge rule lives in the
 * presence store, not here (FR-003).
 */
import { useEffect, useRef } from 'react'
import type { ChatReadEvent, MessageCreatedEvent } from '../../api/chats'
import type { GroupRealtimeEvent } from '../../api/groups'
import { streamUserEvents } from '../../api/sse'
import type { SseConnection } from '../../api/sse'
import type { PresenceUpdatedEvent } from '../../presence/presenceStore'

export type Unsubscribe = () => void

export type MessageCreatedListener = (event: MessageCreatedEvent) => void

export type ChatReadListener = (event: ChatReadEvent) => void

export type RealtimeOpenListener = () => void

export type GroupEventListener = (event: GroupRealtimeEvent) => void

export type PresenceUpdatedListener = (event: PresenceUpdatedEvent) => void

export interface RealtimeStream {
  onMessageCreated(chatId: string | null, listener: MessageCreatedListener): Unsubscribe
  onChatRead(chatId: string | null, listener: ChatReadListener): Unsubscribe
  onOpen(listener: RealtimeOpenListener): Unsubscribe
  /**
   * Subscribes to every №18 group frame of the user's stream
   * (feature 006): the listener applies the described STATE
   * idempotently (FR-015) — dedup/ordering concerns live in the
   * consumer's reducer, not here.
   */
  onGroupEvent(listener: GroupEventListener): Unsubscribe
  /**
   * Subscribes to every №18 `presence.updated` frame of the user's
   * stream (feature 007): the listener applies the STATE by the
   * strictly-greater-rev rule in the presence store (FR-003) —
   * duplicates and stale frames are consumer-side no-ops.
   */
  onPresenceUpdated(listener: PresenceUpdatedListener): Unsubscribe
}

class UserEventStream implements RealtimeStream {
  private refCount = 0
  private connection: SseConnection | null = null
  private readonly messageListeners = new Map<string | null, Set<MessageCreatedListener>>()
  private readonly chatReadListeners = new Map<string | null, Set<ChatReadListener>>()
  private readonly openListeners = new Set<RealtimeOpenListener>()
  private readonly groupListeners = new Set<GroupEventListener>()
  private readonly presenceListeners = new Set<PresenceUpdatedListener>()

  retain(): void {
    this.refCount += 1
    if (this.connection !== null) {
      return
    }
    this.connection = streamUserEvents({
      onOpen: () => {
        for (const listener of this.openListeners) {
          listener()
        }
      },
    })
    const connection = this.connection
    connection.subscribe('message.created', (data) => {
      this.handleMessageCreated(data)
    })
    connection.subscribe('chat.read', (data) => {
      this.handleChatRead(data)
    })
    for (const eventType of GROUP_EVENT_TYPES) {
      connection.subscribe(eventType, (data) => {
        this.handleGroupEvent(eventType, data)
      })
    }
    connection.subscribe('presence.updated', (data) => {
      this.handlePresenceUpdated(data)
    })
  }

  release(): void {
    this.refCount -= 1
    if (this.refCount > 0) {
      return
    }
    this.connection?.close()
    this.connection = null
    this.refCount = 0
    this.messageListeners.clear()
    this.chatReadListeners.clear()
    this.openListeners.clear()
    this.groupListeners.clear()
    this.presenceListeners.clear()
  }

  onMessageCreated(chatId: string | null, listener: MessageCreatedListener): Unsubscribe {
    return this.addListener(this.messageListeners, chatId, listener)
  }

  onChatRead(chatId: string | null, listener: ChatReadListener): Unsubscribe {
    return this.addListener(this.chatReadListeners, chatId, listener)
  }

  onOpen(listener: RealtimeOpenListener): Unsubscribe {
    this.openListeners.add(listener)
    return () => {
      this.openListeners.delete(listener)
    }
  }

  onGroupEvent(listener: GroupEventListener): Unsubscribe {
    this.groupListeners.add(listener)
    return () => {
      this.groupListeners.delete(listener)
    }
  }

  onPresenceUpdated(listener: PresenceUpdatedListener): Unsubscribe {
    this.presenceListeners.add(listener)
    return () => {
      this.presenceListeners.delete(listener)
    }
  }

  private addListener<T>(
    listeners: Map<string | null, Set<T>>,
    chatId: string | null,
    listener: T,
  ): Unsubscribe {
    const set = listeners.get(chatId) ?? new Set<T>()
    listeners.set(chatId, set)
    set.add(listener)
    return () => {
      const current = listeners.get(chatId)
      if (current === undefined) {
        return
      }
      current.delete(listener)
      if (current.size === 0) {
        listeners.delete(chatId)
      }
    }
  }

  private handleMessageCreated(data: string): void {
    const event = parseMessageCreatedEvent(data)
    if (event === null) {
      return
    }
    this.emitTo(this.messageListeners, event.chatId, event)
    this.emitTo(this.messageListeners, null, event)
  }

  private handleChatRead(data: string): void {
    const event = parseChatReadEvent(data)
    if (event === null) {
      return
    }
    this.emitTo(this.chatReadListeners, event.chatId, event)
    this.emitTo(this.chatReadListeners, null, event)
  }

  private handleGroupEvent(eventType: GroupEventType, data: string): void {
    const event = parseGroupEvent(eventType, data)
    if (event === null) {
      return
    }
    for (const listener of this.groupListeners) {
      listener(event)
    }
  }

  private handlePresenceUpdated(data: string): void {
    const event = parsePresenceUpdatedEvent(data)
    if (event === null) {
      return
    }
    for (const listener of this.presenceListeners) {
      listener(event)
    }
  }

  private emitTo<T>(
    listeners: Map<string | null, Set<(event: T) => void>>,
    chatId: string | null,
    event: T,
  ): void {
    const set = listeners.get(chatId)
    if (set === undefined) {
      return
    }
    for (const listener of set) {
      listener(event)
    }
  }
}

function parseMessageCreatedEvent(data: string): MessageCreatedEvent | null {
  let payload: unknown
  try {
    payload = JSON.parse(data)
  } catch {
    return null
  }
  return isMessageCreatedEvent(payload) ? payload : null
}

function isMessageCreatedEvent(value: unknown): value is MessageCreatedEvent {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as { chatId?: unknown; message?: { id?: unknown } | null }
  return (
    typeof candidate.chatId === 'string' &&
    typeof candidate.message === 'object' &&
    candidate.message !== null &&
    typeof candidate.message.id === 'string'
  )
}

function parseChatReadEvent(data: string): ChatReadEvent | null {
  let payload: unknown
  try {
    payload = JSON.parse(data)
  } catch {
    return null
  }
  return isChatReadEvent(payload) ? payload : null
}

function isChatReadEvent(value: unknown): value is ChatReadEvent {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as { chatId?: unknown; readUpToSeq?: unknown; byUserId?: unknown }
  return (
    typeof candidate.chatId === 'string' &&
    typeof candidate.readUpToSeq === 'number' &&
    typeof candidate.byUserId === 'string'
  )
}

/** №18 group `event:` types (feature 006, realtime-group-events.md §3). */
const GROUP_EVENT_TYPES = [
  'group.updated',
  'group.member.added',
  'group.member.removed',
  'group.role.changed',
  'group.deleted',
  'group.you_removed',
] as const

type GroupEventType = (typeof GROUP_EVENT_TYPES)[number]

function parseGroupEvent(eventType: GroupEventType, data: string): GroupRealtimeEvent | null {
  let payload: unknown
  try {
    payload = JSON.parse(data)
  } catch {
    return null
  }
  if (typeof payload !== 'object' || payload === null || !isGroupEventPayload(eventType, payload)) {
    return null
  }
  return { ...payload, type: eventType } as GroupRealtimeEvent
}

/** №18 `presence.updated` payload guard (feature 007, presence-events.md §2). */
function parsePresenceUpdatedEvent(data: string): PresenceUpdatedEvent | null {
  let payload: unknown
  try {
    payload = JSON.parse(data)
  } catch {
    return null
  }
  if (!isPresenceUpdatedEvent(payload)) {
    return null
  }
  return payload
}

function isPresenceUpdatedEvent(value: unknown): value is PresenceUpdatedEvent {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as { userId?: unknown; status?: unknown; rev?: unknown }
  return (
    typeof candidate.userId === 'string' &&
    (candidate.status === 'online' || candidate.status === 'offline') &&
    typeof candidate.rev === 'number'
  )
}

function isGroupEventPayload(eventType: GroupEventType, value: unknown): boolean {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as Record<string, unknown>
  // `groupId` (= chatId) is the one field every §3 payload carries.
  if (typeof candidate.groupId !== 'string') {
    return false
  }
  switch (eventType) {
    case 'group.updated':
      return (
        typeof candidate.title === 'string' &&
        (candidate.description === null || typeof candidate.description === 'string') &&
        typeof candidate.actorId === 'string'
      )
    case 'group.member.added':
      return isPublicUser(candidate.user) && typeof candidate.actorId === 'string'
    case 'group.member.removed':
      return (
        typeof candidate.userId === 'string' &&
        (candidate.actorId === null || typeof candidate.actorId === 'string')
      )
    case 'group.role.changed':
      return (
        typeof candidate.userId === 'string' &&
        (candidate.role === 'owner' || candidate.role === 'admin' || candidate.role === 'member') &&
        typeof candidate.actorId === 'string'
      )
    case 'group.deleted':
      return typeof candidate.actorId === 'string'
    case 'group.you_removed':
      return candidate.reason === 'kicked' || candidate.reason === 'left'
  }
}

function isPublicUser(value: unknown): boolean {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as { id?: unknown; username?: unknown }
  return typeof candidate.id === 'string' && typeof candidate.username === 'string'
}

let sharedStream: UserEventStream | null = null

export function useRealtime(): RealtimeStream {
  const streamRef = useRef<UserEventStream | null>(null)
  if (streamRef.current === null) {
    sharedStream ??= new UserEventStream()
    streamRef.current = sharedStream
  }
  const stream = streamRef.current
  useEffect(() => {
    stream.retain()
    return () => {
      stream.release()
    }
  }, [stream])
  return stream
}
