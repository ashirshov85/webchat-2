/**
 * SSE client (feature 004): reads the user event stream
 * `GET /api/v1/users/me/events` via `fetch` + `ReadableStream`
 * (contracts/realtime-channel.md). Auth uses the same
 * `Authorization: Bearer` header as REST with the single-flight
 * auto-refresh shared with `apiFetch` — the token never appears in the
 * URL (constitution V). Reconnects follow client-side exponential
 * backoff (1s…30s with jitter): the server `retry:` frame is a hint for
 * third-party contract clients without their own strategy and does not
 * drive this client's schedule (research.md §9). Unknown event types are
 * ignored — forward compatibility (US6-3).
 */
import { getAccessToken } from '../auth/session'
import { refreshTokens } from './client'

const EVENTS_URL = '/api/v1/users/me/events'
const BACKOFF_FIRST_DELAY_MS = 1_000
const BACKOFF_MAX_DELAY_MS = 30_000
const DEFAULT_EVENT_TYPE = 'message'

export interface ParsedSseEvent {
  readonly type: string
  readonly data: string
}

export interface SseParser {
  push(chunk: string): void
}

export function createSseParser(onEvent: (event: ParsedSseEvent) => void): SseParser {
  const newline = /\r\n|\r|\n/
  let buffer = ''
  let started = false
  let eventType = ''
  let dataLines: string[] = []

  function processLine(line: string): void {
    if (line === '') {
      if (dataLines.length > 0) {
        const type = eventType === '' ? DEFAULT_EVENT_TYPE : eventType
        onEvent({ type, data: dataLines.join('\n') })
      }
      eventType = ''
      dataLines = []
      return
    }
    if (line.startsWith(':')) {
      return
    }
    const colonIndex = line.indexOf(':')
    const field = colonIndex === -1 ? line : line.slice(0, colonIndex)
    let value = colonIndex === -1 ? '' : line.slice(colonIndex + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') {
      eventType = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
    // `retry:` and unknown fields (incl. `id`) are accepted per the SSE spec
    // but deliberately not applied: the client's own backoff takes priority.
  }

  return {
    push(chunk: string): void {
      buffer += started ? chunk : chunk.replace(/^\uFEFF/, '')
      started = true
      for (;;) {
        const match = newline.exec(buffer)
        if (match === null) {
          break
        }
        const line = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        processLine(line)
      }
    },
  }
}

export function reconnectDelayMs(failures: number, random: () => number = Math.random): number {
  const exponent = Math.max(failures, 1) - 1
  const base = Math.min(BACKOFF_MAX_DELAY_MS, BACKOFF_FIRST_DELAY_MS * 2 ** exponent)
  return Math.round(base / 2 + (random() * base) / 2)
}

export type SseEventListener = (data: string) => void

export interface SseConnection {
  subscribe(eventType: string, listener: SseEventListener): () => void
  close(): void
}

export interface StreamUserEventsOptions {
  onOpen?: () => void
}

export function streamUserEvents(options?: StreamUserEventsOptions): SseConnection {
  const listeners = new Map<string, Set<SseEventListener>>()
  const controller = new AbortController()

  const dispatch = (event: ParsedSseEvent): void => {
    const current = listeners.get(event.type)
    if (current === undefined) {
      return
    }
    for (const listener of current) {
      listener(event.data)
    }
  }

  const connection: SseConnection = {
    subscribe(eventType: string, listener: SseEventListener): () => void {
      const existing = listeners.get(eventType) ?? new Set<SseEventListener>()
      listeners.set(eventType, existing)
      existing.add(listener)
      return () => {
        existing.delete(listener)
      }
    },
    close(): void {
      controller.abort()
    },
  }

  void runStream(controller.signal, options?.onOpen, dispatch)
  return connection
}

type StreamAttempt =
  | { outcome: 'connected'; body: ReadableStream<Uint8Array> }
  | { outcome: 'retry-now' }
  | { outcome: 'retry-later'; delayMs: number }
  | { outcome: 'stop' }

async function currentToken(): Promise<string | null> {
  let token = getAccessToken()
  if (token === null) {
    if ((await refreshTokens()) === null) {
      return null
    }
    token = getAccessToken()
  }
  return token
}

function retryAfter(failures: number, signal: AbortSignal): StreamAttempt {
  if (signal.aborted) {
    return { outcome: 'stop' }
  }
  return { outcome: 'retry-later', delayMs: reconnectDelayMs(failures) }
}

async function openStream(failures: number, signal: AbortSignal): Promise<StreamAttempt> {
  let token: string | null
  try {
    token = await currentToken()
  } catch {
    // The refresh request itself failed at the network level — a
    // transient condition retried on the usual backoff, never an
    // unhandled rejection that would kill the stream loop.
    return signal.aborted ? { outcome: 'stop' } : retryAfter(failures + 1, signal)
  }
  if (token === null || signal.aborted) {
    return { outcome: 'stop' }
  }
  let response: Response
  try {
    response = await fetch(EVENTS_URL, {
      headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
      signal,
    })
  } catch {
    return signal.aborted ? { outcome: 'stop' } : retryAfter(failures + 1, signal)
  }
  if (response.status === 401) {
    await discardBody(response)
    let refreshed: boolean
    try {
      refreshed = (await refreshTokens()) !== null
    } catch {
      // Same transient semantics as above: backoff, not a crash.
      return signal.aborted ? { outcome: 'stop' } : retryAfter(failures + 1, signal)
    }
    if (!refreshed || signal.aborted) {
      return { outcome: 'stop' }
    }
    return failures === 0 ? { outcome: 'retry-now' } : retryAfter(failures + 1, signal)
  }
  if (!response.ok || response.body === null) {
    await discardBody(response)
    return retryAfter(failures + 1, signal)
  }
  return { outcome: 'connected', body: response.body }
}

async function runStream(
  signal: AbortSignal,
  onOpen: (() => void) | undefined,
  dispatch: (event: ParsedSseEvent) => void,
): Promise<void> {
  const parser = createSseParser(dispatch)
  let failures = 0
  while (!signal.aborted) {
    const attempt = await openStream(failures, signal)
    if (attempt.outcome === 'stop' || signal.aborted) {
      return
    }
    if (attempt.outcome === 'retry-now') {
      failures = 1
      continue
    }
    if (attempt.outcome === 'retry-later') {
      failures += 1
      await sleep(attempt.delayMs, signal)
      continue
    }
    onOpen?.()
    try {
      await readStream(attempt.body, parser)
    } catch {
      // network drop mid-stream — reconnect below
    }
    if (signal.aborted) {
      return
    }
    failures = 1
    await sleep(reconnectDelayMs(failures), signal)
  }
}

async function readStream(body: ReadableStream<Uint8Array>, parser: SseParser): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      parser.push(decoder.decode())
      return
    }
    parser.push(decoder.decode(value, { stream: true }))
  }
}

async function discardBody(response: Response): Promise<void> {
  try {
    await response.body?.cancel()
  } catch {
    // body disposal is best-effort
  }
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const abort = () => {
      clearTimeout(timer)
      resolve()
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, ms)
    if (signal.aborted) {
      abort()
      return
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}
