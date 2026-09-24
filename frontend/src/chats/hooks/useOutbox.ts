/**
 * Outbox send orchestration (feature 004, T034; FR-012,
 * research.md §10): optimistic messages live in the per-account
 * localStorage outbox (outbox.ts, T033) and are retried with the SAME
 * `clientMessageId` until the server confirms (201/200 — FR-004 makes
 * retries converge to a single instance).
 *
 * Only text passing the client pre-validation (validation.ts, FR-003)
 * enters the outbox — invalid drafts never reach the chat or the
 * storage (US1-4); the server 400 remains the authoritative guard and
 * maps to a terminal `failed` state.
 *
 * Retry policy: network/timeout/5xx/401 → auto retry with exponential
 * backoff 1s…30s; 429 flood and 503 `server_busy` (feature 005, T027,
 * FR-009 — uniform handling) → stay `sending` with
 * `retryAt = now + Retry-After` (UI countdown, FR-011); other 4xx
 * (400/403/404/409/422 — validation, blocking, id conflict) → terminal
 * `failed` («не отправлено») with auto retries stopped; the user can
 * retry manually with the same id or delete the record locally.
 * All `sending` records are flushed on mount/login (FIFO per chat —
 * sync-protocol.md §7: a chat never waits on another chat's lock); a
 * persisted `retryAt` is honoured by the flush scheduler, so restarts
 * never bypass the 30/min flood window with early retries (FR-010).
 * Temporary failures retry indefinitely by the same id — no time-based
 * escalation to `failed` (FR-009). `failed` ones wait for a manual
 * action. Chat deletion purges its records (FR-021, T060 wires the UI
 * action).
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiProblem } from '../../api/auth'
import { sendMessage } from '../../api/chats'
import type { Message } from '../../api/chats'
import {
  addOutboxRecord,
  purgeChatOutbox,
  readOutbox,
  removeOutboxRecord,
  updateOutboxRecord,
} from '../outbox'
import type { OutboxRecord } from '../outbox'
import { validateMessageText } from '../validation'

export const OUTBOX_RETRY_BASE_DELAY_MS = 1000
export const OUTBOX_RETRY_MAX_DELAY_MS = 30000

export type EnqueueResult =
  | { readonly ok: true; readonly clientMessageId: string }
  | { readonly ok: false; readonly error: string }

export interface UseOutboxOptions {
  /**
   * Fires when the server confirms a record (201 or 200 dedup): the
   * optimistic entry is already removed from the outbox; the returned
   * server `Message` replaces it in the rendered dialog (T036).
   */
  readonly onConfirmed?: (message: Message, record: OutboxRecord) => void
}

export interface UseOutboxResult {
  /** All outbox records of the account in creation order (outbox.ts). */
  readonly records: OutboxRecord[]
  /**
   * Validates the text (FR-003) and stores a `sending` record; the
   * first send attempt is scheduled immediately. Invalid text is
   * rejected before touching the chat or the outbox (US1-4).
   */
  enqueue(chatId: string, text: string): EnqueueResult
  /** Manual retry of a `failed` record with the same id (FR-012). */
  retry(clientMessageId: string): void
  /** Local deletion of a record (FR-012). */
  remove(clientMessageId: string): void
  /** Drops every record of the chat and stops its retries (FR-021). */
  purgeChat(chatId: string): void
}

type SendOutcome =
  | { readonly kind: 'deferred'; readonly retryAfterSec?: number }
  | { readonly kind: 'transient' }
  | { readonly kind: 'terminal'; readonly errorCode: string }

function isApiProblem(value: unknown): value is ApiProblem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { status?: unknown }).status === 'number'
  )
}

/** Contract codes ride in `errors: {field: [code]}` (openapi Problem). */
function problemErrorCode(problem: ApiProblem): string {
  const codes = Object.values(problem.errors ?? {}).flat()
  const code = codes[0]
  return code ?? `http_${problem.status}`
}

function classifyFailure(cause: unknown): SendOutcome {
  if (!isApiProblem(cause)) {
    return { kind: 'transient' }
  }
  // 429 `flood_limit` (004) and 503 `server_busy` (005, №16
  // backpressure) are deferrals: retryAt = now + Retry-After, the
  // record stays `sending` and the same id is retried at the deadline
  // (FR-009/FR-010 — retries never bypass the server's window).
  if (cause.status === 429 || cause.status === 503) {
    return { kind: 'deferred', retryAfterSec: cause.retryAfterSec }
  }
  if (cause.status === 401 || cause.status >= 500) {
    return { kind: 'transient' }
  }
  return { kind: 'terminal', errorCode: problemErrorCode(cause) }
}

function backoffDelayMs(retry: number): number {
  return Math.min(OUTBOX_RETRY_BASE_DELAY_MS * 2 ** retry, OUTBOX_RETRY_MAX_DELAY_MS)
}

function newClientMessageId(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  const version = bytes[6] ?? 0
  const variant = bytes[8] ?? 0
  bytes[6] = (version & 0x0f) | 0x40
  bytes[8] = (variant & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function useOutbox(userId: string | null, options?: UseOutboxOptions): UseOutboxResult {
  const [records, setRecords] = useState<OutboxRecord[]>([])
  const userIdRef = useRef(userId)
  const onConfirmedRef = useRef(options?.onConfirmed)
  const timers = useRef(new Map<string, number>())
  const retries = useRef(new Map<string, number>())
  const inFlight = useRef(new Set<string>())
  const attemptRef = useRef<(_clientMessageId: string) => Promise<void>>(async () => {})

  useEffect(() => {
    onConfirmedRef.current = options?.onConfirmed
  }, [options?.onConfirmed])

  const clearTimer = useCallback((clientMessageId: string) => {
    const timer = timers.current.get(clientMessageId)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timers.current.delete(clientMessageId)
    }
  }, [])

  const schedule = useCallback(
    (clientMessageId: string, delayMs: number) => {
      clearTimer(clientMessageId)
      timers.current.set(
        clientMessageId,
        window.setTimeout(() => {
          timers.current.delete(clientMessageId)
          void attemptRef.current(clientMessageId)
        }, delayMs),
      )
    },
    [clearTimer],
  )

  const handleFailure = useCallback(
    (ownerId: string, record: OutboxRecord, cause: unknown) => {
      const outcome = classifyFailure(cause)
      if (outcome.kind === 'terminal') {
        clearTimer(record.clientMessageId)
        retries.current.delete(record.clientMessageId)
        if (
          updateOutboxRecord(ownerId, record.clientMessageId, {
            state: 'failed',
            errorCode: outcome.errorCode,
          })
        ) {
          setRecords(readOutbox(ownerId))
        }
        return
      }
      const retry = retries.current.get(record.clientMessageId) ?? 0
      retries.current.set(record.clientMessageId, retry + 1)
      let delayMs: number
      let retryAt: number | undefined
      if (outcome.kind === 'deferred') {
        const seconds =
          outcome.retryAfterSec !== undefined && outcome.retryAfterSec > 0
            ? outcome.retryAfterSec
            : backoffDelayMs(retry) / 1000
        delayMs = seconds * 1000
        retryAt = Date.now() + delayMs
      } else {
        delayMs = backoffDelayMs(retry)
      }
      const updated = updateOutboxRecord(
        ownerId,
        record.clientMessageId,
        retryAt === undefined ? { state: 'sending' } : { state: 'sending', retryAt },
      )
      if (updated !== null) {
        setRecords(readOutbox(ownerId))
        schedule(record.clientMessageId, delayMs)
      }
    },
    [clearTimer, schedule],
  )

  const attempt = useCallback(
    async (clientMessageId: string) => {
      const ownerId = userIdRef.current
      if (ownerId === null) {
        return
      }
      const record = readOutbox(ownerId).find((entry) => entry.clientMessageId === clientMessageId)
      if (record?.state !== 'sending' || inFlight.current.has(clientMessageId)) {
        return
      }
      inFlight.current.add(clientMessageId)
      try {
        const message = await sendMessage(record.chatId, {
          clientMessageId: record.clientMessageId,
          text: record.text,
        })
        // The record may have been removed/purged while the request was in flight.
        const confirmedOwner = userIdRef.current
        if (confirmedOwner !== null && removeOutboxRecord(confirmedOwner, clientMessageId)) {
          setRecords(readOutbox(confirmedOwner))
          onConfirmedRef.current?.(message, record)
        }
      } catch (cause) {
        const currentOwner = userIdRef.current
        if (currentOwner !== null) {
          handleFailure(currentOwner, record, cause)
        }
      } finally {
        inFlight.current.delete(clientMessageId)
      }
    },
    [handleFailure],
  )

  useEffect(() => {
    attemptRef.current = attempt
  }, [attempt])

  // Flush at start/login: every `sending` record is retried with the
  // same id (array walk = FIFO per chat, sync-protocol.md §7 — one
  // chat's Retry-After lock never delays another chat); a persisted
  // `retryAt` is respected, so the flush never fires inside the
  // server's 30/min window (FR-010); `failed` ones wait for a manual
  // retry (US2-7).
  useEffect(() => {
    userIdRef.current = userId
    for (const timer of timers.current.values()) {
      window.clearTimeout(timer)
    }
    timers.current.clear()
    retries.current.clear()
    if (userId === null) {
      setRecords([])
      return
    }
    const stored = readOutbox(userId)
    setRecords(stored)
    for (const record of stored) {
      if (record.state === 'sending') {
        const delayMs = record.retryAt === undefined ? 0 : Math.max(0, record.retryAt - Date.now())
        schedule(record.clientMessageId, delayMs)
      }
    }
  }, [userId, schedule])

  useEffect(() => {
    const pending = timers
    return () => {
      for (const timer of pending.current.values()) {
        window.clearTimeout(timer)
      }
      pending.current.clear()
    }
  }, [])

  const enqueue = useCallback(
    (chatId: string, text: string): EnqueueResult => {
      const validation = validateMessageText(text)
      if (!validation.ok) {
        return { ok: false, error: validation.error }
      }
      const ownerId = userIdRef.current
      if (ownerId === null) {
        return { ok: false, error: 'Отправка недоступна: нет активного пользователя' }
      }
      const clientMessageId = newClientMessageId()
      const stored = addOutboxRecord(ownerId, {
        clientMessageId,
        chatId,
        text: validation.text,
        state: 'sending',
      })
      setRecords(stored)
      retries.current.delete(clientMessageId)
      schedule(clientMessageId, 0)
      return { ok: true, clientMessageId }
    },
    [schedule],
  )

  const retry = useCallback(
    (clientMessageId: string) => {
      const ownerId = userIdRef.current
      if (ownerId === null) {
        return
      }
      const record = readOutbox(ownerId).find((entry) => entry.clientMessageId === clientMessageId)
      if (record?.state !== 'failed') {
        return
      }
      const updated = updateOutboxRecord(ownerId, clientMessageId, { state: 'sending' })
      if (updated === null) {
        return
      }
      setRecords(readOutbox(ownerId))
      retries.current.delete(clientMessageId)
      schedule(clientMessageId, 0)
    },
    [schedule],
  )

  const remove = useCallback(
    (clientMessageId: string) => {
      const ownerId = userIdRef.current
      if (ownerId === null) {
        return
      }
      clearTimer(clientMessageId)
      retries.current.delete(clientMessageId)
      if (removeOutboxRecord(ownerId, clientMessageId)) {
        setRecords(readOutbox(ownerId))
      }
    },
    [clearTimer],
  )

  const purgeChat = useCallback(
    (chatId: string) => {
      const ownerId = userIdRef.current
      if (ownerId === null) {
        return
      }
      for (const record of readOutbox(ownerId)) {
        if (record.chatId === chatId) {
          clearTimer(record.clientMessageId)
          retries.current.delete(record.clientMessageId)
        }
      }
      if (purgeChatOutbox(ownerId, chatId) > 0) {
        setRecords(readOutbox(ownerId))
      }
    },
    [clearTimer],
  )

  return { records, enqueue, retry, remove, purgeChat }
}
