import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { sendMessage } from '../../api/chats'
import type { Message } from '../../api/chats'
import { useOutbox } from '../hooks/useOutbox'
import type { EnqueueResult } from '../hooks/useOutbox'
import { addOutboxRecord, outboxStorageKey, readOutbox } from '../outbox'

vi.mock('../../api/chats', () => ({ sendMessage: vi.fn() }))

const mockedSend = vi.mocked(sendMessage)

function makeMessage(chatId: string, id: string, seq: number, text = `text-${id}`): Message {
  return {
    id,
    chatId,
    senderId: '9a2c-9a2c-9a2c',
    text,
    seq,
    createdAt: '2026-09-20T12:00:00.000Z',
  }
}

type ConfirmedCallback = NonNullable<Parameters<typeof useOutbox>[1]>['onConfirmed']

const mounted: Array<{ unmount(): void }> = []

function mountOutbox(userId: string | null, onConfirmed?: ConfirmedCallback) {
  const rendered = renderHook(() =>
    useOutbox(userId, onConfirmed === undefined ? undefined : { onConfirmed }),
  )
  mounted.push(rendered)
  return rendered
}

function enqueueValid(
  result: { current: ReturnType<typeof useOutbox> },
  chatId: string,
  text: string,
): string {
  let outcome: EnqueueResult | undefined
  act(() => {
    outcome = result.current.enqueue(chatId, text)
  })
  if (outcome === undefined || !outcome.ok) {
    throw new Error(`expected enqueue to succeed: ${JSON.stringify(outcome)}`)
  }
  return outcome.clientMessageId
}

async function advance(ms: number): Promise<void> {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

const problem = (status: number, code: string): Record<string, unknown> => ({
  title: 'Problem',
  status,
  errors: { clientMessageId: [code] },
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  window.localStorage.clear()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('useOutbox invalid text never reaches the chat or the outbox (FR-003, US1-4)', () => {
  it.each([
    ['empty text', ''],
    ['whitespace-only text', ' \n\t  \r\n'],
    ['4097 characters after trim', 'a'.repeat(4097)],
  ])('rejects %s before any send or storage', (_name, text) => {
    mockedSend.mockResolvedValue(makeMessage('chat-1', 'srv-1', 1))
    const { result } = mountOutbox('user-1')

    let outcome: EnqueueResult | undefined
    act(() => {
      outcome = result.current.enqueue('chat-1', text)
    })

    expect(outcome?.ok).toBe(false)
    expect(result.current.records).toEqual([])
    expect(window.localStorage.getItem(outboxStorageKey('user-1'))).toBeNull()
    expect(mockedSend).not.toHaveBeenCalled()
  })

  it('stores a valid 4096-char text trimmed at the ends with inner whitespace preserved', () => {
    const inner = `${'a'.repeat(4094)} b`
    expect(inner).toHaveLength(4096)
    const { result } = mountOutbox('user-1')

    const clientMessageId = enqueueValid(result, 'chat-1', `  ${inner}  `)

    expect(result.current.records).toEqual([
      { clientMessageId, chatId: 'chat-1', text: inner, state: 'sending' },
    ])
    expect(readOutbox('user-1')).toHaveLength(1)
  })
})

describe('useOutbox retries reuse the same clientMessageId (FR-004/FR-012)', () => {
  it('retries network failures with 1s/2s backoff by the same id and converges on 201', async () => {
    const serverMessage = makeMessage('chat-1', 'srv-1', 7, 'hello')
    mockedSend
      .mockRejectedValueOnce(new Error('offline'))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(serverMessage)
    const onConfirmed = vi.fn<NonNullable<ConfirmedCallback>>()
    const { result } = mountOutbox('user-1', onConfirmed)

    const clientMessageId = enqueueValid(result, 'chat-1', 'hello')
    expect(result.current.records).toHaveLength(1)

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records[0]?.state).toBe('sending')
    expect(result.current.records[0]?.retryAt).toBeUndefined()

    await advance(1000)
    expect(mockedSend).toHaveBeenCalledTimes(2)

    await advance(2000)
    expect(mockedSend).toHaveBeenCalledTimes(3)
    expect(result.current.records).toEqual([])

    const ids = mockedSend.mock.calls.map((call) => call[1].clientMessageId)
    expect(ids).toEqual([clientMessageId, clientMessageId, clientMessageId])
    expect(mockedSend.mock.calls[0]?.[1]).toEqual({ clientMessageId, text: 'hello' })
    expect(onConfirmed).toHaveBeenCalledOnce()
    expect(onConfirmed.mock.calls[0]?.[0]).toBe(serverMessage)
    expect(onConfirmed.mock.calls[0]?.[1].clientMessageId).toBe(clientMessageId)

    await advance(30000)
    expect(mockedSend).toHaveBeenCalledTimes(3)
  })

  it('keeps a single record instance across retries — no duplicates appear', async () => {
    mockedSend.mockRejectedValue(new Error('offline'))
    const { result } = mountOutbox('user-1')

    enqueueValid(result, 'chat-1', 'solo')

    await advance(0)
    await advance(1000)
    await advance(2000)

    expect(mockedSend).toHaveBeenCalledTimes(3)
    expect(result.current.records).toHaveLength(1)
    expect(readOutbox('user-1')).toHaveLength(1)
  })
})

describe('useOutbox flood countdown (FR-011)', () => {
  it('stays sending with retryAt = now + Retry-After and retries after the pause', async () => {
    const startedAt = Date.now()
    mockedSend
      .mockRejectedValueOnce({ title: 'Too Many Requests', status: 429, retryAfterSec: 5 })
      .mockResolvedValueOnce(makeMessage('chat-1', 'srv-1', 7, 'flooded'))
    const { result } = mountOutbox('user-1')

    const clientMessageId = enqueueValid(result, 'chat-1', 'flooded')

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records[0]?.state).toBe('sending')
    expect(result.current.records[0]?.retryAt).toBe(startedAt + 5000)

    await advance(4999)
    expect(mockedSend).toHaveBeenCalledTimes(1)

    await advance(1)
    expect(mockedSend).toHaveBeenCalledTimes(2)
    expect(mockedSend.mock.calls[1]?.[1].clientMessageId).toBe(clientMessageId)
    expect(result.current.records).toEqual([])
  })
})

describe('useOutbox terminal failures (FR-012)', () => {
  it.each([
    [400, 'text_too_long'],
    [403, 'you_are_blocked'],
    [404, 'chat_not_found'],
    [409, 'message_id_conflict'],
    [422, 'self_forbidden'],
  ])('marks the record failed (%s %s) and stops auto-retries', async (status, errorCode) => {
    mockedSend.mockRejectedValueOnce(problem(status, errorCode))
    const { result } = mountOutbox('user-1')

    const clientMessageId = enqueueValid(result, 'chat-1', 'doomed')

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(result.current.records).toEqual([
      { clientMessageId, chatId: 'chat-1', text: 'doomed', state: 'failed', errorCode },
    ])

    await advance(60000)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(readOutbox('user-1')[0]?.state).toBe('failed')
  })
})

describe('useOutbox manual actions (FR-012)', () => {
  it('manual retry reuses the same id, clears the error and converges on success', async () => {
    const serverMessage = makeMessage('chat-1', 'srv-1', 9, 'again')
    mockedSend
      .mockRejectedValueOnce(problem(403, 'you_are_blocked'))
      .mockResolvedValueOnce(serverMessage)
    const { result } = mountOutbox('user-1')

    const clientMessageId = enqueueValid(result, 'chat-1', 'again')
    await advance(0)
    expect(result.current.records[0]?.state).toBe('failed')

    act(() => {
      result.current.retry(clientMessageId)
    })
    expect(result.current.records[0]?.state).toBe('sending')
    expect(result.current.records[0]?.errorCode).toBeUndefined()

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(2)
    expect(mockedSend.mock.calls[1]?.[1].clientMessageId).toBe(clientMessageId)
    expect(result.current.records).toEqual([])
  })

  it('local delete removes the failed record from state and storage and stops retries', async () => {
    mockedSend.mockRejectedValueOnce(problem(409, 'message_id_conflict'))
    const { result } = mountOutbox('user-1')

    const clientMessageId = enqueueValid(result, 'chat-1', 'gone')
    await advance(0)
    expect(result.current.records[0]?.state).toBe('failed')

    act(() => {
      result.current.remove(clientMessageId)
    })
    expect(result.current.records).toEqual([])
    expect(readOutbox('user-1')).toEqual([])

    await advance(60000)
    expect(mockedSend).toHaveBeenCalledTimes(1)
  })
})

describe('useOutbox purge on chat deletion (FR-021)', () => {
  it('drops only the deleted chat records and keeps other chats retrying', async () => {
    mockedSend.mockRejectedValue(new Error('offline'))
    const { result } = mountOutbox('user-1')

    const purgedId = enqueueValid(result, 'chat-1', 'old chat')
    const keptId = enqueueValid(result, 'chat-2', 'live chat')
    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(2)

    act(() => {
      result.current.purgeChat('chat-1')
    })
    expect(result.current.records.map((record) => record.clientMessageId)).toEqual([keptId])
    expect(readOutbox('user-1').map((record) => record.chatId)).toEqual(['chat-2'])

    await advance(1000)
    expect(mockedSend).toHaveBeenCalledTimes(3)
    expect(mockedSend.mock.calls[2]?.[1].clientMessageId).toBe(keptId)
    // Only the pre-purge attempt touched the deleted chat's record.
    expect(mockedSend.mock.calls.slice(2).map((call) => call[1].clientMessageId)).not.toContain(
      purgedId,
    )
  })
})

describe('useOutbox flush on start/login (US2-7)', () => {
  it('retries stored sending records immediately and leaves failed ones untouched', async () => {
    addOutboxRecord('user-1', {
      clientMessageId: 'pending-1',
      chatId: 'chat-1',
      text: 'hi',
      state: 'sending',
    })
    addOutboxRecord('user-1', {
      clientMessageId: 'failed-1',
      chatId: 'chat-1',
      text: 'blocked text',
      state: 'failed',
      errorCode: 'you_are_blocked',
    })
    mockedSend.mockResolvedValueOnce(makeMessage('chat-1', 'pending-1', 5, 'hi'))
    const onConfirmed = vi.fn<NonNullable<ConfirmedCallback>>()
    const { result } = mountOutbox('user-1', onConfirmed)

    expect(result.current.records.map((record) => record.clientMessageId)).toEqual([
      'pending-1',
      'failed-1',
    ])

    await advance(0)
    expect(mockedSend).toHaveBeenCalledTimes(1)
    expect(mockedSend.mock.calls[0]?.[1]).toEqual({ clientMessageId: 'pending-1', text: 'hi' })
    expect(result.current.records.map((record) => record.clientMessageId)).toEqual(['failed-1'])
    expect(onConfirmed).toHaveBeenCalledOnce()
    expect(onConfirmed.mock.calls[0]?.[0].id).toBe('pending-1')

    await advance(60000)
    expect(mockedSend).toHaveBeenCalledTimes(1)
  })
})
