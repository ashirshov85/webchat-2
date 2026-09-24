/**
 * Client delivery cursors (feature 005, T017; sync-protocol.md §1):
 * per-chat `seq` of the last confirmed-delivered message, stored in
 * localStorage under `webchat.sync.cursors.<userId>` (per account, like
 * the outbox in 004) so catch-up sync resumes from the last confirmed
 * position across reloads and reconnects (US1-4).
 *
 * The cursor is the echo of a successful ack: it is applied
 * monotonically (only max — a smaller or repeated value never moves it,
 * mirroring the server-side GREATEST of №25). The single legal rewind
 * is the future-cursor repair to `serverUpToSeq` (§5): a desynced chat
 * rewinds to the server's actual delivery position, all other chats
 * sync untouched. `startAfterSeq` adoption and `truncatedUpToSeq`
 * pinning self-heal the cursor to the server's resume point before a
 * delta's messages are applied (§3.1); messages below the truncation
 * point stay unreachable and never come back (FR-003/FR-007).
 *
 * This module is pure persistence: the catch-up loop, ack batching and
 * single-flight orchestration belong to `useSync` (T019) and `ack.ts`
 * (T018). Storage failures (quota, security settings) degrade silently
 * — the map simply reads back empty and sync restarts from the
 * server-echoed positions.
 */

import type { SyncChatDelta, SyncCursor } from '../api/chats'

/** Shape persisted under {@link cursorsStorageKey}: `{chatId: seq}`. */
export type CursorMap = Record<string, number>

/** Fields of a №26 delta the self-heal consumes (rest is render data). */
export type SelfHealDelta = Pick<
  SyncChatDelta,
  'chatId' | 'startAfterSeq' | 'truncatedUpToSeq' | 'desynced' | 'serverUpToSeq'
>

export function cursorsStorageKey(userId: string): string {
  return `webchat.sync.cursors.${userId}`
}

function isSeq(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function isChatId(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function parseCursors(raw: string | null): CursorMap {
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
  const map: CursorMap = {}
  for (const [chatId, seq] of Object.entries(parsed as Record<string, unknown>)) {
    if (isChatId(chatId) && isSeq(seq)) {
      map[chatId] = seq
    }
  }
  return map
}

function writeCursors(userId: string, map: CursorMap): void {
  try {
    window.localStorage.setItem(cursorsStorageKey(userId), JSON.stringify(map))
  } catch {
    // storage unavailable — cursors degrade to starting over from server echoes
  }
}

function loadCursors(userId: string): CursorMap {
  let raw: string | null
  try {
    raw = window.localStorage.getItem(cursorsStorageKey(userId))
  } catch {
    return {}
  }
  return parseCursors(raw)
}

/** Full persisted map (validated; corrupt entries are dropped, never crash). */
export function readCursors(userId: string): Readonly<CursorMap> {
  return loadCursors(userId)
}

/** Cursor of one chat; `0` = nothing confirmed yet (sync from scratch). */
export function getCursor(userId: string, chatId: string): number {
  return readCursors(userId)[chatId] ?? 0
}

/** The map as the №26 `cursors` body: server takes it as its lower bound. */
export function readSyncCursors(userId: string): SyncCursor[] {
  return Object.entries(readCursors(userId)).map(([chatId, upToSeq]) => ({ chatId, upToSeq }))
}

/**
 * Monotone advance — the only way forward (echo of a successful ack,
 * applied page last `seq`, `startAfterSeq` adoption or
 * `truncatedUpToSeq` pinning). A smaller, equal or invalid `seq` never
 * moves the cursor (client mirror of the GREATEST in №25). Returns the
 * resulting position.
 */
export function advanceCursor(userId: string, chatId: string, seq: number): number {
  if (!isChatId(chatId) || !isSeq(seq)) {
    return getCursor(userId, chatId)
  }
  const map = loadCursors(userId)
  const current = map[chatId] ?? 0
  if (seq <= current) {
    return current
  }
  map[chatId] = seq
  writeCursors(userId, map)
  return seq
}

/**
 * Forced set — the single legal rewind (§5): a `desynced` chat's local
 * cursor contradicts server data, so it is re-wound to the server's
 * actual delivery position (`serverUpToSeq`) and catch-up continues
 * from there. Returns the resulting position.
 */
export function rewindCursor(userId: string, chatId: string, seq: number): number {
  if (!isChatId(chatId) || !isSeq(seq)) {
    return getCursor(userId, chatId)
  }
  const map = loadCursors(userId)
  if (map[chatId] === seq) {
    return seq
  }
  map[chatId] = seq
  writeCursors(userId, map)
  return seq
}

/**
 * Self-heal step of the catch-up loop (§3.1), applied to the local
 * cursor BEFORE the delta's messages render: a `desynced` chat rewinds
 * to `serverUpToSeq`; otherwise the cursor adopts the server's resume
 * point `startAfterSeq`; a present `truncatedUpToSeq` pins the cursor
 * on the truncation point so deleted history is neither re-fetched nor
 * restored. Returns the chat's resulting cursor.
 */
export function applySyncSelfHeal(userId: string, delta: SelfHealDelta): number {
  if (delta.desynced === true) {
    rewindCursor(userId, delta.chatId, delta.serverUpToSeq ?? delta.startAfterSeq)
  } else {
    advanceCursor(userId, delta.chatId, delta.startAfterSeq)
  }
  if (delta.truncatedUpToSeq !== undefined) {
    advanceCursor(userId, delta.chatId, delta.truncatedUpToSeq)
  }
  return getCursor(userId, delta.chatId)
}
