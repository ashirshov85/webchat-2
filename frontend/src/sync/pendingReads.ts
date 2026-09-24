/**
 * Offline read marks (feature 005, T032; data-model.md entity 5,
 * sync-protocol.md §6): a per-chat watermark `upToSeq` of messages the
 * user has READ but the server has not confirmed yet, stored in
 * localStorage under `webchat.sync.pendingReads.<userId>` (per account,
 * like the cursors and the outbox) so offline reads survive a reload
 * or restart and are flushed on reconnect (FR-008/US3-7).
 *
 * Protocol discipline: the watermark is recorded BEFORE the №17
 * attempt (`POST /chats/{id}/read`) and removed only after its `204`
 * — a lost request or a lost response simply leaves the entry in
 * place, and the reconnect flush replays it through the idempotent,
 * monotonic №17 (server-side GREATEST makes a repeat flush after a
 * lost response safe — no double effects). Locally the watermark is
 * monotone (only max, mirroring №17) and bounded by the client's
 * delivery position: offline reading applies only to previously
 * synchronized messages (`upToSeq ≤ delivered` of the client), so a
 * read is clamped to the local cursor (cursors.ts, T017).
 *
 * This module is pure persistence: the №17 calls, throttling and the
 * reconnect flush belong to the read point and `useSync` (T034).
 * Storage failures (quota, security settings) degrade silently — the
 * map reads back empty and an unconfirmed read costs at most a stale
 * unread badge until the next open of the chat.
 */

/** Shape persisted under {@link pendingReadsStorageKey}: `{chatId: upToSeq}`. */
export type PendingReadMap = Record<string, number>

export function pendingReadsStorageKey(userId: string): string {
  return `webchat.sync.pendingReads.${userId}`
}

/** Entity 5: the watermark is an int64 ≥ 1 (`0`/absent = nothing pending). */
function isUpToSeq(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 1
}

function isChatId(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function parsePendingReads(raw: string | null): PendingReadMap {
  if (raw === null) {
    return {}
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return {}
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return {}
  }
  const map: PendingReadMap = {}
  for (const [chatId, upToSeq] of Object.entries(parsed as Record<string, unknown>)) {
    if (isChatId(chatId) && isUpToSeq(upToSeq)) {
      map[chatId] = upToSeq
    }
  }
  return map
}

function writePendingReads(userId: string, map: PendingReadMap): void {
  try {
    window.localStorage.setItem(pendingReadsStorageKey(userId), JSON.stringify(map))
  } catch {
    // storage unavailable — the read stays confirmed only in the UI cache
  }
}

function loadPendingReads(userId: string): PendingReadMap {
  let raw: string | null
  try {
    raw = window.localStorage.getItem(pendingReadsStorageKey(userId))
  } catch {
    return {}
  }
  return parsePendingReads(raw)
}

/** Full persisted map (validated; corrupt entries are dropped, never crash). */
export function readPendingReads(userId: string): Readonly<PendingReadMap> {
  return loadPendingReads(userId)
}

/** Watermark of one chat; `0` = nothing pending (nothing read unconfirmed). */
export function getPendingRead(userId: string, chatId: string): number {
  return readPendingReads(userId)[chatId] ?? 0
}

/**
 * Records a read BEFORE the №17 attempt. Monotone — a smaller, equal
 * or invalid `upToSeq` never moves the watermark back (client mirror
 * of the №17 GREATEST). `deliveredUpToSeq` (the client's local cursor,
 * cursors.ts) bounds the mark to previously synchronized messages:
 * anything above the delivery position is clamped down, because only
 * delivered messages can have been read (US3-7). Returns the chat's
 * resulting pending watermark.
 */
export function recordPendingRead(
  userId: string,
  chatId: string,
  upToSeq: number,
  deliveredUpToSeq?: number,
): number {
  if (!isChatId(chatId) || !isUpToSeq(upToSeq)) {
    return getPendingRead(userId, chatId)
  }
  if (isUpToSeq(deliveredUpToSeq) && upToSeq > deliveredUpToSeq) {
    return recordPendingRead(userId, chatId, deliveredUpToSeq)
  }
  const map = loadPendingReads(userId)
  const current = map[chatId] ?? 0
  if (upToSeq <= current) {
    return current
  }
  map[chatId] = upToSeq
  writePendingReads(userId, map)
  return upToSeq
}

/**
 * Removes the mark after №17 answered `204`. Removal is conditional on
 * the confirmed value: a NEWER offline read recorded while the request
 * was in flight must stay pending for the next flush, so the entry is
 * dropped only when its watermark is ≤ `upToSeq`. A missing entry is a
 * no-op — the confirm is idempotent, like the flush it closes.
 */
export function confirmPendingRead(userId: string, chatId: string, upToSeq: number): void {
  if (!isChatId(chatId) || !isUpToSeq(upToSeq)) {
    return
  }
  const map = loadPendingReads(userId)
  if ((map[chatId] ?? 0) > upToSeq) {
    return
  }
  if (!(chatId in map)) {
    return
  }
  delete map[chatId]
  writePendingReads(userId, map)
}
