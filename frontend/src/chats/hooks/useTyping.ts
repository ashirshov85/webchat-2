/**
 * Typing signal send-state machine (feature 008a, US2, T036; FR-006,
 * ui-behavior.md §2.1): the composer of the open chat translates its
 * draft events into ephemeral №41 signals (T035 `sendTyping`) —
 * `start` on the first character of a typing burst and then at most
 * once per 3 s repeat window while the input continues, `stop` on
 * every send attempt (any outcome, including a blank draft rejected
 * by pre-validation — the edge case of T033), on clearing the field
 * and after 5 s of silence with a non-empty draft; a new burst
 * starts over with `start`.
 *
 * Every signal is fire-and-forget best-effort: network errors and
 * 429 `flood_limit` are swallowed without retries (FR-009 — the
 * server drops excess signals anyway) — the state is ephemeral and
 * the server TTL (8 s) expires anything left over (FR-007). The
 * machine mirrors the server idempotency (T030): a `stop` is only
 * sent while a local `start` is outstanding, so a double trigger
 * (send attempt + field clear) never duplicates requests. A blocked
 * composer (008 FR-022) or no open chat sends nothing at all;
 * switching chats stops the previous chat's signal best-effort.
 */
import { useCallback, useEffect, useRef } from 'react'
import { sendTyping } from '../../api/chats'
import type { TypingAction } from '../../api/chats'

/** `start` repeat window while typing continues — FR-006, realtime-events.md §3. */
export const TYPING_START_REPEAT_WINDOW_MS = 3000

/** Silence window that stops an active state over a non-empty draft — FR-006. */
export const TYPING_SILENCE_STOP_MS = 5000

export interface UseTypingOptions {
  /** The open chat whose composer owns the signals; null — no chat, nothing is sent. */
  readonly chatId: string | null
  /** Blocked composer (008 FR-022: block-pair): signals are not sent. */
  readonly blocked?: boolean
}

export interface UseTypingResult {
  /** Called on every composer draft change (the raw field value). */
  readonly onDraftChange: (value: string) => void
  /** Called on every send attempt, whatever its outcome (including a rejected blank). */
  readonly onSendAttempt: () => void
}

export function useTyping({ chatId, blocked = false }: UseTypingOptions): UseTypingResult {
  const activeRef = useRef(false)
  const lastStartAtRef = useRef(0)
  const silenceTimerRef = useRef<number | null>(null)
  const chatIdRef = useRef(chatId)
  const blockedRef = useRef(blocked)

  const clearSilence = useCallback(() => {
    const timer = silenceTimerRef.current
    if (timer !== null) {
      window.clearTimeout(timer)
      silenceTimerRef.current = null
    }
  }, [])

  /** Best-effort signal: never awaited, failures swallowed (§2.1). */
  const signal = useCallback((target: string, action: TypingAction) => {
    void sendTyping(target, action).catch(() => {})
  }, [])

  const reset = useCallback(() => {
    clearSilence()
    activeRef.current = false
  }, [clearSilence])

  /** Idempotent `stop`: a request leaves only with an outstanding local `start`. */
  const stopActive = useCallback(() => {
    clearSilence()
    if (!activeRef.current) {
      return
    }
    activeRef.current = false
    const current = chatIdRef.current
    if (current !== null) {
      signal(current, 'stop')
    }
  }, [clearSilence, signal])

  /** (Re)arms the 5 s silence window; the timer dies with the state it guards. */
  const armSilence = useCallback(() => {
    clearSilence()
    silenceTimerRef.current = window.setTimeout(() => {
      silenceTimerRef.current = null
      stopActive()
    }, TYPING_SILENCE_STOP_MS)
  }, [clearSilence, stopActive])

  const onDraftChange = useCallback(
    (value: string) => {
      if (blockedRef.current || chatIdRef.current === null) {
        return
      }
      if (value === '') {
        stopActive()
        return
      }
      const chat = chatIdRef.current
      const now = Date.now()
      if (!activeRef.current) {
        activeRef.current = true
        lastStartAtRef.current = now
        signal(chat, 'start')
        armSilence()
        return
      }
      armSilence()
      if (now - lastStartAtRef.current >= TYPING_START_REPEAT_WINDOW_MS) {
        lastStartAtRef.current = now
        signal(chat, 'start')
      }
    },
    [armSilence, signal, stopActive],
  )

  const onSendAttempt = useCallback(() => {
    stopActive()
  }, [stopActive])

  // Chat switch: signals belong to the composer of one chat — the
  // previous chat's outstanding state is stopped best-effort (§2.1).
  useEffect(() => {
    const previous = chatIdRef.current
    if (previous === chatId) {
      return
    }
    chatIdRef.current = chatId
    if (activeRef.current) {
      reset()
      if (previous !== null) {
        signal(previous, 'stop')
      }
    }
  }, [chatId, reset, signal])

  // Blocked composer (FR-022): no signals at all — the machine resets
  // and the server TTL (8 s) expires whatever was outstanding.
  useEffect(() => {
    blockedRef.current = blocked
    if (blocked) {
      reset()
    }
  }, [blocked, reset])

  // Unmount (leaving the messenger): stop the outstanding state the
  // same best-effort way; StrictMode's dry cleanup sees no active
  // state and sends nothing.
  useEffect(() => {
    const active = activeRef
    const chat = chatIdRef
    const timer = silenceTimerRef
    return () => {
      const pending = timer.current
      if (pending !== null) {
        window.clearTimeout(pending)
      }
      if (active.current && chat.current !== null) {
        void sendTyping(chat.current, 'stop').catch(() => {})
      }
    }
  }, [])

  return { onDraftChange, onSendAttempt }
}
