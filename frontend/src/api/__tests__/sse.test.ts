import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createSseParser, reconnectDelayMs, streamUserEvents } from '../sse'
import type { ParsedSseEvent, SseConnection, SseParser } from '../sse'

const session = vi.hoisted(() => ({ getAccessToken: vi.fn() }))
const client = vi.hoisted(() => ({ refreshTokens: vi.fn() }))

vi.mock('../../auth/session', () => session)
vi.mock('../client', () => client)

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

const fetchMock = vi.fn<FetchLike>()

/**
 * Frames must survive being split across arbitrary read() chunk boundaries,
 * so every parser fixture is also exercised split into single characters.
 */
function pushSplit(parser: SseParser, chunk: string): void {
  for (const character of chunk) {
    parser.push(character)
  }
}

function collect(): { events: ParsedSseEvent[]; parser: SseParser } {
  const events: ParsedSseEvent[] = []
  return { events, parser: createSseParser((event) => events.push(event)) }
}

describe('createSseParser frame parsing', () => {
  it('parses event/data frames completed by a blank line', () => {
    const { events, parser } = collect()

    parser.push('event: message.created\ndata: {"chatId":"c-1"}\n\n')

    expect(events).toEqual([{ type: 'message.created', data: '{"chatId":"c-1"}' }])
  })

  it('defaults the event type to message when the frame has no event field', () => {
    const { events, parser } = collect()

    parser.push('data: plain\n\n')

    expect(events).toEqual([{ type: 'message', data: 'plain' }])
  })

  it('joins multiple data lines with a newline and strips exactly one leading space', () => {
    const single = collect()
    single.parser.push('data: first\ndata: second\n\n')
    const doubled = collect()
    doubled.parser.push('data:  padded\n\n')

    expect(single.events).toEqual([{ type: 'message', data: 'first\nsecond' }])
    expect(doubled.events).toEqual([{ type: 'message', data: ' padded' }])
  })

  it('ignores comment lines, including the :ka heartbeat', () => {
    const { events, parser } = collect()

    parser.push(':ka\nevent: message.created\ndata: {}\n:ka\n\n')

    expect(events).toEqual([{ type: 'message.created', data: '{}' }])
  })

  it('accepts retry frames without emitting events', () => {
    const { events, parser } = collect()

    parser.push('retry: 3000\n\n')

    expect(events).toEqual([])
  })

  it('reassembles frames split across chunk boundaries', () => {
    const { events, parser } = collect()

    pushSplit(parser, 'event: message.created\ndata: {"text":"при')
    parser.push('вет"}\n\n')

    expect(events).toEqual([{ type: 'message.created', data: '{"text":"привет"}' }])
  })

  it('supports CRLF and lone CR line endings', () => {
    const crlf = collect()
    crlf.parser.push('event: message.created\r\ndata: crlf\r\n\r\n')
    const cr = collect()
    cr.parser.push('event: message.created\rdata: cr\r\r')

    expect(crlf.events).toEqual([{ type: 'message.created', data: 'crlf' }])
    expect(cr.events).toEqual([{ type: 'message.created', data: 'cr' }])
  })
})

describe('reconnectDelayMs client backoff', () => {
  it('keeps the first retry inside the 1s base window regardless of jitter', () => {
    expect(reconnectDelayMs(1, () => 0)).toBe(500)
    expect(reconnectDelayMs(1, () => 0.5)).toBe(750)
    expect(reconnectDelayMs(1, () => 1)).toBe(1000)
  })

  it('grows exponentially and caps at 30s', () => {
    expect(reconnectDelayMs(2, () => 1)).toBe(2000)
    expect(reconnectDelayMs(3, () => 1)).toBe(4000)
    expect(reconnectDelayMs(6, () => 1)).toBe(30000)
    expect(reconnectDelayMs(20, () => 1)).toBe(30000)
    expect(reconnectDelayMs(6, () => 0)).toBe(15000)
  })

  it('never schedules the first retry as late as the 3000ms server retry hint', () => {
    for (const random of [0, 0.25, 0.5, 0.75, 1]) {
      const delay = reconnectDelayMs(1, () => random)
      expect(delay).toBeGreaterThanOrEqual(500)
      expect(delay).toBeLessThanOrEqual(1000)
    }
  })
})

interface ControlledStream {
  response: Response
  send(frame: string): void
  drop(): void
}

function sseStream(): ControlledStream {
  const encoder = new TextEncoder()
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c
    },
  })
  return {
    response: new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }),
    send(frame: string): void {
      controller.enqueue(encoder.encode(frame))
    },
    drop(): void {
      controller.error(new Error('network drop'))
    },
  }
}

async function flush(times = 40): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await Promise.resolve()
  }
}

function bearerOf(call: Parameters<FetchLike>): string | null {
  return new Headers(call[1]?.headers).get('Authorization')
}

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.toString()
  return input.url
}

describe('streamUserEvents connection', () => {
  let connection: SseConnection | undefined

  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(Math, 'random').mockReturnValue(0)
    vi.stubGlobal('fetch', fetchMock)
    session.getAccessToken.mockReturnValue('access-1')
    client.refreshTokens.mockResolvedValue(null)
  })

  afterEach(() => {
    connection?.close()
    connection = undefined
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('connects with a Bearer header and keeps the token out of the URL', async () => {
    fetchMock.mockReturnValueOnce(Promise.resolve(sseStream().response))
    const onOpen = vi.fn()

    connection = streamUserEvents({ onOpen })
    await flush()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const firstCall = fetchMock.mock.calls[0]!
    expect(urlOf(firstCall[0])).toBe('/api/v1/users/me/events')
    expect(bearerOf(firstCall)).toBe('Bearer access-1')
    expect(new Headers(firstCall[1]?.headers).get('Accept')).toBe('text/event-stream')
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('delivers parsed frames to subscribers and ignores unknown event types', async () => {
    const stream = sseStream()
    fetchMock.mockReturnValueOnce(Promise.resolve(stream.response))
    const created = vi.fn()
    const future = vi.fn()

    connection = streamUserEvents()
    connection.subscribe('message.created', created)
    connection.subscribe('some.future.event', future)
    await flush()

    stream.send('event: message.created\ndata: {"chatId":"c-1"}\n\n')
    stream.send('event: totally.unknown\ndata: {}\n\n')
    await flush()

    expect(created).toHaveBeenCalledTimes(1)
    expect(created).toHaveBeenCalledWith('{"chatId":"c-1"}')
    expect(future).not.toHaveBeenCalled()
  })

  it('reconnects after a network drop on the client backoff, not the server retry hint', async () => {
    const first = sseStream()
    const second = sseStream()
    fetchMock
      .mockReturnValueOnce(Promise.resolve(first.response))
      .mockReturnValueOnce(Promise.resolve(second.response))
    const onOpen = vi.fn()

    connection = streamUserEvents({ onOpen })
    await flush()
    first.send('retry: 3000\n\n')
    first.send('event: message.created\ndata: {}\n\n')
    await flush()
    first.drop()
    await flush()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(onOpen).toHaveBeenCalledTimes(1)

    // Client backoff for the first failure is 500ms (jitter pinned to 0);
    // the server's `retry: 3000` hint must not delay the reconnect to 3s.
    await vi.advanceTimersByTimeAsync(499)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const secondCall = fetchMock.mock.calls[1]!
    expect(urlOf(secondCall[0])).toBe('/api/v1/users/me/events')
    expect(bearerOf(secondCall)).toBe('Bearer access-1')

    await flush()
    expect(onOpen).toHaveBeenCalledTimes(2)
  })

  it('retries a failed connection after the backoff and fires onOpen only once connected', async () => {
    fetchMock
      .mockRejectedValueOnce(new Error('offline'))
      .mockReturnValueOnce(Promise.resolve(sseStream().response))
    const onOpen = vi.fn()

    connection = streamUserEvents({ onOpen })
    await flush()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(onOpen).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(499)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await flush()
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('refreshes the token on 401 and reconnects without a backoff delay', async () => {
    session.getAccessToken.mockReturnValueOnce('access-stale').mockReturnValue('access-fresh')
    client.refreshTokens.mockResolvedValue({
      accessToken: 'access-fresh',
      refreshToken: 'refresh-2',
      tokenType: 'Bearer',
      expiresInSec: 300,
    })
    fetchMock
      .mockReturnValueOnce(Promise.resolve(new Response(null, { status: 401 })))
      .mockReturnValueOnce(Promise.resolve(sseStream().response))
    const onOpen = vi.fn()

    connection = streamUserEvents({ onOpen })
    await flush()

    expect(client.refreshTokens).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(bearerOf(fetchMock.mock.calls[1]!)).toBe('Bearer access-fresh')
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('stops without fetching when there is no session to restore', async () => {
    session.getAccessToken.mockReturnValue(null)
    client.refreshTokens.mockResolvedValue(null)

    connection = streamUserEvents()
    await flush()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('retries on the backoff when the initial token refresh fails at the network level', async () => {
    session.getAccessToken.mockReturnValue(null)
    client.refreshTokens.mockRejectedValueOnce(new Error('refresh offline'))
    fetchMock.mockReturnValueOnce(Promise.resolve(sseStream().response))
    const onOpen = vi.fn()

    connection = streamUserEvents({ onOpen })
    await flush()

    // A failed refresh request behaves like a failed connection attempt:
    // no fetch, no crash — the client backs off and tries again.
    expect(fetchMock).not.toHaveBeenCalled()
    expect(onOpen).not.toHaveBeenCalled()

    // The next attempt finds a valid token in memory (refresh succeeded
    // elsewhere) and connects normally.
    session.getAccessToken.mockReturnValue('access-fresh')
    client.refreshTokens.mockResolvedValue(null)

    await vi.advanceTimersByTimeAsync(499)
    expect(fetchMock).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(bearerOf(fetchMock.mock.calls[0]!)).toBe('Bearer access-fresh')
    await flush()
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('retries on the backoff when a mid-stream 401 refresh fails at the network level', async () => {
    session.getAccessToken.mockReturnValue('access-1')
    client.refreshTokens.mockRejectedValueOnce(new Error('refresh offline'))
    fetchMock
      .mockReturnValueOnce(Promise.resolve(new Response(null, { status: 401 })))
      .mockReturnValueOnce(Promise.resolve(sseStream().response))
    const onOpen = vi.fn()

    connection = streamUserEvents({ onOpen })
    await flush()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(onOpen).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(500)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await flush()
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('cancels pending reconnection attempts after close()', async () => {
    const stream = sseStream()
    fetchMock.mockReturnValueOnce(Promise.resolve(stream.response))

    connection = streamUserEvents()
    await flush()
    stream.drop()
    await flush()
    connection.close()

    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
