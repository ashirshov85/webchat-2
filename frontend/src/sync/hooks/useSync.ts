/**
 * Catch-up synchronization loop (feature 005, T019; sync-protocol.md §3.1):
 * on every SSE (re)open the client pulls what the at-most-once realtime
 * channel may have missed while disconnected — US1's «ровно N сообщений,
 * каждое один раз, в порядке отправки» after a reconnect (SC-001).
 *
 * Normative cycle (§3.1):
 *
 *   loop A: №26(cursors, 20, 50) → per delta, in order:
 *     self-heal the chat cursor (startAfterSeq adoption, truncation
 *     pinning, future-cursor rewind — cursors.ts) BEFORE the page
 *     renders, emit the applied page (consumers merge idempotently:
 *     dedup by `message.id`, order by `seq` — US1-3), ack
 *     max(page tail, truncatedUpToSeq) through the shared batcher
 *     (№25 — the only mover of the delivery position, FR-001) and
 *     adopt that confirmed position as the new cursor;
 *   loop B: chats with `hasMore` continue via №15 `after` pages
 *     (render + ack + cursor) until `nextAfter` is absent (§4);
 *   repeat loop A while `moreChats` — the cursors already advanced.
 *
 * Single-flight (research.md §7): one cycle at a time — a trigger
 * arriving mid-cycle JOINS the in-flight run instead of starting a
 * competing one (edge «скачок соединения»); realtime keeps rendering
 * in parallel, the race is safe by the id-dedup of the consumers. A
 * cycle broken mid-way (request failure) aborts without touching
 * cursors beyond the last confirmed page — the next (re)open resumes
 * exactly from there (US1-4): cursors are persisted per applied page,
 * so nothing is lost and a re-delivered page renders once.
 *
 * The `flushPendingReads` pre-step of §3.1 (offline read marks) is
 * wired into the loop by US3 (T034) — US1 ships the delivery core.
 *
 * Integration: mount once in MessengerPage (T023) — `onChatUpdate`
 * feeds the chat list and the open dialog, `syncing` drives the
 * SyncIndicator (T020); the ack batcher is the per-user shared
 * instance (ack.ts), so realtime frames and applied pages confirm
 * through the same №25 batches.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { listMessagesAfter, sync } from '../../api/chats'
import type { Message } from '../../api/chats'
import type { PublicUser } from '../../api/auth'
import { useRealtime } from '../../chats/hooks/useRealtime'
import type { Unsubscribe } from '../../chats/hooks/useRealtime'
import { getAckBatcher } from '../ack'
import type { AckBatcher } from '../ack'
import { advanceCursor, applySyncSelfHeal, getCursor, readSyncCursors } from '../cursors'

/** Contract defaults of №26 (api-contract.md §1) — the loop's page sizes. */
export const SYNC_CHAT_LIMIT = 20
export const SYNC_MESSAGE_LIMIT = 50

/** One applied catch-up page handed to consumers for rendering. */
export interface SyncChatUpdate {
  readonly chatId: string
  /**
   * Page messages in ascending `seq` (a №26 delta page or a №15 `after`
   * page); consumers merge idempotently — dedup by `message.id`, order
   * by `seq` (US1-3, quickstart §3.1.3).
   */
  readonly messages: Message[]
  /** Chat metadata of the №26 delta (absent on №15 continuation pages). */
  readonly peer?: PublicUser
  readonly blockedByMe?: boolean
  /** Offline ✓✓ watermark of own messages (US3, T036). */
  readonly peerReadUpToSeq?: number
  /** Server-authoritative unread counter of the snapshot (US3, T035). */
  readonly unreadCount?: number
  /**
   * Truncation point of the delta (§5): history below it stays deleted
   * and must not be restored by the consumers (US1-5/FR-007).
   */
  readonly truncatedUpToSeq?: number
}

export type SyncUpdateListener = (update: SyncChatUpdate) => void

export interface UseSyncResult {
  /** A catch-up cycle is running — the SyncIndicator signal (T020). */
  readonly syncing: boolean
  /** Last failed cycle (diagnostics; the next (re)open retries by itself). */
  readonly error: unknown
  /** Subscribes to applied pages (render by `seq`, dedup by `message.id`). */
  readonly onChatUpdate: (listener: SyncUpdateListener) => Unsubscribe
  /**
   * Runs the catch-up cycle now — single-flight (§3.1): a call while a
   * cycle is in flight awaits that cycle instead of starting a
   * competing one. Resolves when the cycle finishes; failures also
   * resolve (see `error`) so `void syncNow()` never rejects.
   */
  readonly syncNow: () => Promise<void>
}

/** Highest `seq` of an applied page — the position to confirm (§2). */
function pageMaxSeq(messages: Message[]): number {
  let max = 0
  for (const message of messages) {
    if (message.seq > max) {
      max = message.seq
    }
  }
  return max
}

/**
 * Applies one page in §3.1 order: render → ack → confirmed cursor.
 * The ack goes through the shared batcher (debounced №25 batches);
 * the local cursor adopts the same value — the echo of the confirmed
 * position (monotone by cursors.ts, so a repeated page is a no-op).
 */
function applyPage(
  userId: string,
  batcher: AckBatcher,
  emit: (update: SyncChatUpdate) => void,
  update: SyncChatUpdate,
): void {
  emit(update)
  const confirmed = Math.max(pageMaxSeq(update.messages), update.truncatedUpToSeq ?? 0)
  if (confirmed > 0) {
    batcher.ack(update.chatId, confirmed)
    advanceCursor(userId, update.chatId, confirmed)
  }
}

/**
 * Loop B (§4): the `hasMore` tail of one chat via №15 `after` pages —
 * render + ack + cursor per page, until `nextAfter` is absent (an
 * empty page on the boundary is a valid «caught up» answer). A
 * non-advancing `nextAfter` stops the loop defensively — pages are
 * `(after, after+limit]`, so it can only mean a broken server.
 */
async function catchUpTail(
  userId: string,
  chatId: string,
  batcher: AckBatcher,
  emit: (update: SyncChatUpdate) => void,
): Promise<void> {
  let after = getCursor(userId, chatId)
  for (;;) {
    const page = await listMessagesAfter(chatId, after, SYNC_MESSAGE_LIMIT)
    if (page.messages.length > 0) {
      applyPage(userId, batcher, emit, { chatId, messages: page.messages })
    }
    if (page.nextAfter === undefined || page.nextAfter <= after) {
      return
    }
    after = page.nextAfter
  }
}

/**
 * The full §3.1 cycle: loop A (№26) with per-chat loop B (№15)
 * continuation, repeated while `moreChats`. Each applied page is
 * confirmed (ack + cursor) before the next request, so an abort at
 * ANY point leaves the resume position exactly at the last confirmed
 * page (US1-4). Ends with a best-effort batcher flush: №25 failures
 * stay pending in the batcher (its retry timer re-arms) and never
 * fail the cycle itself.
 */
async function runCatchUp(
  userId: string,
  batcher: AckBatcher,
  emit: (update: SyncChatUpdate) => void,
): Promise<void> {
  for (;;) {
    const response = await sync(readSyncCursors(userId), SYNC_CHAT_LIMIT, SYNC_MESSAGE_LIMIT)
    if (response.chats.length === 0) {
      // Nothing undelivered; `moreChats` without deltas would be a
      // pathological server answer — stopping is always safe (the
      // next (re)open re-asks).
      break
    }
    for (const delta of response.chats) {
      // Self-heal BEFORE the page renders (§3.1): startAfterSeq
      // adoption, truncation pinning, future-cursor rewind (§5).
      applySyncSelfHeal(userId, delta)
      applyPage(userId, batcher, emit, {
        chatId: delta.chatId,
        messages: delta.messages,
        peer: delta.peer,
        blockedByMe: delta.blockedByMe,
        peerReadUpToSeq: delta.peerReadUpToSeq,
        unreadCount: delta.unreadCount,
        truncatedUpToSeq: delta.truncatedUpToSeq,
      })
      if (delta.hasMore) {
        await catchUpTail(userId, delta.chatId, batcher, emit)
      }
    }
    if (!response.moreChats) {
      break
    }
  }
  try {
    await batcher.flush()
  } catch {
    // entries stay pending; the batcher retries with its next flush
  }
}

export function useSync(userId: string | null): UseSyncResult {
  const realtime = useRealtime()
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<unknown>(null)
  /**
   * The in-flight cycle (single-flight, research.md §7): triggers
   * arriving mid-cycle join this promise instead of competing —
   * the running cycle already converges (realtime rendered in
   * parallel, id-dedup on the consumers' side).
   */
  const inFlightRef = useRef<Promise<void> | null>(null)
  const listenersRef = useRef(new Set<SyncUpdateListener>())

  const emit = useCallback((update: SyncChatUpdate) => {
    for (const listener of listenersRef.current) {
      listener(update)
    }
  }, [])

  const syncNow = useCallback((): Promise<void> => {
    if (userId === null) {
      return Promise.resolve()
    }
    const inFlight = inFlightRef.current
    if (inFlight !== null) {
      return inFlight
    }
    const batcher = getAckBatcher(userId)
    const run = runCatchUp(userId, batcher, emit)
      .then(() => {
        setError(null)
      })
      .catch((cause: unknown) => {
        // Cursors stay at the last confirmed page — the next (re)open
        // resumes from there (US1-4); surfaced through `error`.
        setError(cause)
      })
      .finally(() => {
        if (inFlightRef.current === run) {
          inFlightRef.current = null
          setSyncing(false)
        }
      })
    inFlightRef.current = run
    setSyncing(true)
    return run
  }, [userId, emit])

  useEffect(() => {
    setError(null)
  }, [userId])

  useEffect(() => {
    if (userId === null) {
      return
    }
    return realtime.onOpen(() => {
      void syncNow()
    })
  }, [realtime, syncNow, userId])

  const onChatUpdate = useCallback((listener: SyncUpdateListener): Unsubscribe => {
    listenersRef.current.add(listener)
    return () => {
      listenersRef.current.delete(listener)
    }
  }, [])

  return { syncing, error, onChatUpdate, syncNow }
}
