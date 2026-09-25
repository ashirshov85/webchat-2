import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deliveryAck, listMessagesAfter, sync } from '../chats'
import type { ApiProblem } from '../auth'

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

const fetchMock = vi.fn<FetchLike>()

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function noContentResponse(): Response {
  return new Response(null, { status: 204 })
}

function problemResponse(status: number, errors: Record<string, string[]>): Response {
  return new Response(JSON.stringify({ title: 'HTTP error', status, errors }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.toString()
  return input.url
}

function requestBody(init?: RequestInit): unknown {
  return typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
}

const CHAT_ID = '7dc5d4a2-3b1e-4f6a-9c2d-000000000001'

function message(seq: number) {
  return {
    id: `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
    chatId: CHAT_ID,
    senderId: '00000000-0000-4000-8000-000000000aa1',
    text: `msg-${seq}`,
    seq,
    createdAt: '2026-09-24T10:00:00Z',
  }
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetAllMocks()
})

describe('deliveryAck (№25)', () => {
  it('posts the ack batch and resolves void on 204', async () => {
    fetchMock.mockReturnValue(Promise.resolve(noContentResponse()))
    const acks = [
      { chatId: CHAT_ID, upToSeq: 128 },
      { chatId: '00000000-0000-4000-8000-000000000002', upToSeq: 7 },
    ]

    await expect(deliveryAck(acks)).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [input, init] = fetchMock.mock.calls[0]!
    expect(urlOf(input)).toBe('/api/v1/users/me/delivery-ack')
    expect(init?.method).toBe('POST')
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
    expect(requestBody(init)).toEqual({ acks })
  })

  it('throws the problem of an atomically rejected batch', async () => {
    fetchMock.mockReturnValue(Promise.resolve(problemResponse(403, { chat: ['not_participant'] })))

    const failure = deliveryAck([{ chatId: CHAT_ID, upToSeq: 1 }])
    await expect(failure).rejects.toMatchObject({
      status: 403,
      errors: { chat: ['not_participant'] },
    } satisfies Partial<ApiProblem>)
  })
})

describe('sync (№26)', () => {
  it('posts cursors with explicit limits and returns the parsed response', async () => {
    const syncResponse = {
      chats: [
        {
          chatId: CHAT_ID,
          peer: {
            id: '00000000-0000-4000-8000-000000000aa1',
            username: 'peer',
            email: 'peer@example.com',
            status: 'active',
            createdAt: '2026-01-01T00:00:00Z',
          },
          blockedByMe: false,
          startAfterSeq: 128,
          messages: [message(129), message(130)],
          hasMore: false,
          peerReadUpToSeq: 100,
          unreadCount: 2,
          lastSeq: 130,
        },
      ],
      moreChats: false,
    }
    fetchMock.mockReturnValue(Promise.resolve(jsonResponse(200, syncResponse)))

    const cursors = [{ chatId: CHAT_ID, upToSeq: 128 }]
    const response = await sync(cursors, 20, 50)

    expect(response).toEqual(syncResponse)
    const [input, init] = fetchMock.mock.calls[0]!
    expect(urlOf(input)).toBe('/api/v1/users/me/sync')
    expect(init?.method).toBe('POST')
    expect(requestBody(init)).toEqual({ cursors, chatLimit: 20, messageLimit: 50 })
  })

  it('applies the contract defaults when limits are unspecified', async () => {
    fetchMock.mockReturnValue(Promise.resolve(jsonResponse(200, { chats: [], moreChats: false })))

    const cursors = [{ chatId: CHAT_ID, upToSeq: 0 }]
    await sync(cursors)

    const [, init] = fetchMock.mock.calls[0]!
    expect(requestBody(init)).toEqual({ cursors, chatLimit: 20, messageLimit: 50 })
  })
})

describe('listMessagesAfter (№15 ascending)', () => {
  it('requests the ascending page after the exclusive cursor', async () => {
    const page = { messages: [message(129), message(130)], nextAfter: 130 }
    fetchMock.mockReturnValue(Promise.resolve(jsonResponse(200, page)))

    const response = await listMessagesAfter(CHAT_ID, 128, 50)

    expect(response).toEqual(page)
    const [input, init] = fetchMock.mock.calls[0]!
    expect(urlOf(input)).toBe(`/api/v1/chats/${CHAT_ID}/messages?after=128&limit=50`)
    expect(init?.method).toBe('GET')
    expect(init?.body).toBeUndefined()
  })

  it('sends only the cursor when no limit is given', async () => {
    fetchMock.mockReturnValue(Promise.resolve(jsonResponse(200, { messages: [] })))

    await listMessagesAfter(CHAT_ID, 7)

    const [input] = fetchMock.mock.calls[0]!
    expect(urlOf(input)).toBe(`/api/v1/chats/${CHAT_ID}/messages?after=7`)
  })
})
