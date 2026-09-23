/**
 * Client outbox of unsent messages (feature 004, T033; FR-012,
 * research.md §10): pending outgoing messages live in localStorage
 * under `webchat.chats.outbox.<userId>` (per account, like the refresh
 * token in 002) so they survive reload/tab close and are retried with
 * the same `clientMessageId` on next launch (FR-004 convergence).
 *
 * Records: `{clientMessageId, chatId, text, state: sending|failed,
 * errorCode?, retryAt?}`. Array order doubles as creation order —
 * outbox messages render after server messages (research.md §10).
 *
 * This module is pure persistence; retries/validation/purge triggers
 * belong to the `useOutbox` hook (T034). Storage failures (quota,
 * security settings) degrade silently to a no-op outbox.
 */

export type OutboxSendState = 'sending' | 'failed'

export interface OutboxRecord {
  readonly clientMessageId: string
  readonly chatId: string
  readonly text: string
  readonly state: OutboxSendState
  /** Contract error code of a terminal failure (`you_are_blocked`, …). */
  readonly errorCode?: string
  /** Epoch ms before which no auto retry must happen (429 Retry-After). */
  readonly retryAt?: number
}

export type OutboxRecordPatch = Partial<Pick<OutboxRecord, 'state' | 'errorCode' | 'retryAt'>>

export function outboxStorageKey(userId: string): string {
  return `webchat.chats.outbox.${userId}`
}

function isOutboxRecord(value: unknown): value is OutboxRecord {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const record = value as Record<string, unknown>
  return (
    typeof record.clientMessageId === 'string' &&
    record.clientMessageId.length > 0 &&
    typeof record.chatId === 'string' &&
    record.chatId.length > 0 &&
    typeof record.text === 'string' &&
    (record.state === 'sending' || record.state === 'failed') &&
    (record.errorCode === undefined || typeof record.errorCode === 'string') &&
    (record.retryAt === undefined || typeof record.retryAt === 'number')
  )
}

export function readOutbox(userId: string): OutboxRecord[] {
  let raw: string | null
  try {
    raw = window.localStorage.getItem(outboxStorageKey(userId))
  } catch {
    return []
  }
  if (raw === null) {
    return []
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) {
    return []
  }
  return parsed.filter(isOutboxRecord)
}

function writeOutbox(userId: string, records: OutboxRecord[]): void {
  try {
    window.localStorage.setItem(outboxStorageKey(userId), JSON.stringify(records))
  } catch {
    // storage unavailable — outbox degrades to memory-only usage by callers
  }
}

/**
 * Adds a record; an existing entry with the same `clientMessageId` is
 * replaced in place (never duplicated — same-id retries stay a single
 * instance, FR-004/FR-012).
 */
export function addOutboxRecord(userId: string, record: OutboxRecord): OutboxRecord[] {
  const records = readOutbox(userId)
  const index = records.findIndex((existing) => existing.clientMessageId === record.clientMessageId)
  if (index === -1) {
    records.push(record)
  } else {
    records[index] = record
  }
  writeOutbox(userId, records)
  return records
}

/**
 * Merges a patch into the record with the given id (state transitions,
 * `errorCode`, `retryAt`); a `sending` → `sending` update clears stale
 * `errorCode`/`retryAt` unless the patch sets them explicitly. Returns
 * the updated list or null when the id is unknown.
 */
export function updateOutboxRecord(
  userId: string,
  clientMessageId: string,
  patch: OutboxRecordPatch,
): OutboxRecord[] | null {
  const records = readOutbox(userId)
  const index = records.findIndex((existing) => existing.clientMessageId === clientMessageId)
  if (index === -1) {
    return null
  }
  const previous = records[index]
  if (previous === undefined) {
    return null
  }
  const nextState = patch.state ?? previous.state
  const nextPatch: OutboxRecordPatch =
    nextState === 'sending' ? { errorCode: undefined, retryAt: undefined, ...patch } : patch
  records[index] = {
    clientMessageId: previous.clientMessageId,
    chatId: previous.chatId,
    text: previous.text,
    state: nextState,
    errorCode: nextPatch.errorCode,
    retryAt: nextPatch.retryAt,
  }
  writeOutbox(userId, records)
  return records
}

/** Removes a single record (server confirmed 201/200, or local delete). */
export function removeOutboxRecord(userId: string, clientMessageId: string): boolean {
  const records = readOutbox(userId)
  const index = records.findIndex((existing) => existing.clientMessageId === clientMessageId)
  if (index === -1) {
    return false
  }
  records.splice(index, 1)
  writeOutbox(userId, records)
  return true
}

/** Purges all records of a chat (chat deletion, FR-021). Returns count removed. */
export function purgeChatOutbox(userId: string, chatId: string): number {
  const records = readOutbox(userId)
  const kept = records.filter((record) => record.chatId !== chatId)
  if (kept.length === records.length) {
    return 0
  }
  writeOutbox(userId, kept)
  return records.length - kept.length
}
