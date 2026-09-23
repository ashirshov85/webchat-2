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
 */
import { useEffect, useRef } from 'react'
import type { ChatReadEvent, MessageCreatedEvent } from '../../api/chats'
import { streamUserEvents } from '../../api/sse'
import type { SseConnection } from '../../api/sse'

export type Unsubscribe = () => void

export type MessageCreatedListener = (event: MessageCreatedEvent) => void

export type ChatReadListener = (event: ChatReadEvent) => void

export type RealtimeOpenListener = () => void

export interface RealtimeStream {
  onMessageCreated(chatId: string | null, listener: MessageCreatedListener): Unsubscribe
  onChatRead(chatId: string | null, listener: ChatReadListener): Unsubscribe
  onOpen(listener: RealtimeOpenListener): Unsubscribe
}

class UserEventStream implements RealtimeStream {
  private refCount = 0
  private connection: SseConnection | null = null
  private readonly messageListeners = new Map<string | null, Set<MessageCreatedListener>>()
  private readonly chatReadListeners = new Map<string | null, Set<ChatReadListener>>()
  private readonly openListeners = new Set<RealtimeOpenListener>()

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
    this.connection.subscribe('message.created', (data) => {
      this.handleMessageCreated(data)
    })
    this.connection.subscribe('chat.read', (data) => {
      this.handleChatRead(data)
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
