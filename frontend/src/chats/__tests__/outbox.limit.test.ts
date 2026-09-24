/**
 * Outbox storage limit tests (feature 005, T024; FR-005, US2,
 * data-model entity 4 — edge «лимит очереди»): the offline queue is
 * capped at a fixed 1000 records per account (client constant, T026).
 * Above the limit new records are still accepted while the OLDEST
 * `sending` record (start of the array) is evicted to terminal
 * `failed` + `errorCode='queue_overflow'` with auto-retries stopped;
 * `failed` records are never evicted; an evicted record is not
 * re-accepted into the queue automatically (manual retry by the same
 * id stays allowed — idempotency preserved).
 *
 * Written test-first (constitution VI): must fail until T026 lands
 * the eviction in `outbox.ts`.
 */
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { sendMessage } from '../../api/chats'
import type { Message } from '../../api/chats'
import { useOutbox } from '../hooks/useOutbox'
import type { EnqueueResult } from '../hooks/useOutbox'
import { addOutboxRecord, outboxStorageKey, readOutbox, updateOutboxRecord } from '../outbox'
import type { OutboxRecord } from '../outbox'

vi.mock('../../api/chats', () => ({ sendMessage: vi.fn() }))

const mockedSend = vi.mocked(sendMessage)

/** FR-005: fixed client-side storage limit of the offline queue (spec 005). */
const OUTBOX_LIMIT = 1000

const USER = 'user-limit'

let sequence = 0

function record(
  clientMessageId: string,
  state: OutboxRecord['state'],
  errorCode?: string,
  chatId = 'chat-1',
): OutboxRecord {
  sequence += 1
  return {
    clientMessageId,
    chatId,
    text: `text-${clientMessageId}-${sequence}`,
    state,
    errorCode,
  }
}

function seed(records: OutboxRecord[]): void {
  window.localStorage.setItem(outboxStorageKey(USER), JSON.stringify(records))
}

function seedSending(prefix: string, count: number): OutboxRecord[] {
  return Array.from({ length: count }, (_unused, index) =>
    record(`${prefix}-${String(index + 1).padStart(4, '0')}`, 'sending'),
  )
}

function seedFailed(count: number): OutboxRecord[] {
  return Array.from({ length: count }, (_unused, index) =>
    record(`f-${String(index + 1).padStart(4, '0')}`, 'failed', 'you_are_blocked'),
  )
}

function findRecord(records: OutboxRecord[], clientMessageId: string): OutboxRecord | undefined {
  return records.find((entry) => entry.clientMessageId === clientMessageId)
}

describe('outbox storage limit (FR-005, US2 queue-limit edge)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    sequence = 0
  })

  it('accepts a new record at the limit by evicting the oldest sending record', () => {
    seed(seedSending('m', OUTBOX_LIMIT))
    expect(readOutbox(USER)).toHaveLength(OUTBOX_LIMIT)

    addOutboxRecord(USER, record('m-new', 'sending'))

    const records = readOutbox(USER)
    expect(records).toHaveLength(OUTBOX_LIMIT)
    expect(records[0]).toMatchObject({
      clientMessageId: 'm-0001',
      state: 'failed',
      errorCode: 'queue_overflow',
    })
    expect(records[0]?.retryAt).toBeUndefined()
    expect(records[1]).toMatchObject({ clientMessageId: 'm-0002', state: 'sending' })
    expect(records.at(-1)).toMatchObject({ clientMessageId: 'm-new', state: 'sending' })
    expect(records.filter((entry) => entry.state === 'failed')).toHaveLength(1)
  })

  it('never evicts failed records — the oldest sending one is evicted instead', () => {
    seed([record('old-failed', 'failed', 'you_are_blocked'), ...seedSending('s', OUTBOX_LIMIT - 1)])

    addOutboxRecord(USER, record('s-new', 'sending'))

    const records = readOutbox(USER)
    expect(records).toHaveLength(OUTBOX_LIMIT)
    expect(records[0]).toMatchObject({
      clientMessageId: 'old-failed',
      state: 'failed',
      errorCode: 'you_are_blocked',
    })
    expect(findRecord(records, 's-0001')).toMatchObject({
      state: 'failed',
      errorCode: 'queue_overflow',
    })
    expect(findRecord(records, 's-0002')).toMatchObject({ state: 'sending' })
    expect(findRecord(records, 's-new')).toMatchObject({ state: 'sending' })
    expect(records.filter((entry) => entry.errorCode === 'queue_overflow')).toHaveLength(1)
  })

  it('does not re-accept an evicted record into the queue automatically', () => {
    seed(seedSending('m', OUTBOX_LIMIT))
    addOutboxRecord(USER, record('m-new', 'sending'))
    expect(findRecord(readOutbox(USER), 'm-0001')).toMatchObject({
      state: 'failed',
      errorCode: 'queue_overflow',
    })

    addOutboxRecord(USER, record('m-0001', 'sending'))

    const records = readOutbox(USER)
    expect(records.filter((entry) => entry.clientMessageId === 'm-0001')).toHaveLength(1)
    expect(findRecord(records, 'm-0001')).toMatchObject({
      state: 'failed',
      errorCode: 'queue_overflow',
    })
    expect(records).toHaveLength(OUTBOX_LIMIT)
  })

  it('manual retry of an evicted record by the same id re-queues it (idempotency preserved)', () => {
    seed(seedSending('m', OUTBOX_LIMIT))
    addOutboxRecord(USER, record('m-new', 'sending'))

    const updated = updateOutboxRecord(USER, 'm-0001', { state: 'sending' })

    expect(updated).not.toBeNull()
    expect(findRecord(updated ?? [], 'm-0001')).toMatchObject({ state: 'sending' })
    expect(findRecord(updated ?? [], 'm-0001')?.errorCode).toBeUndefined()
    expect(findRecord(updated ?? [], 'm-0001')?.retryAt).toBeUndefined()
  })
})

describe('useOutbox eviction at the storage limit (FR-005, US2)', () => {
  const mounted: Array<{ unmount(): void }> = []

  function makeMessage(chatId: string, id: string, seq: number): Message {
    return {
      id,
      chatId,
      senderId: '9a2c-9a2c-9a2c',
      text: 'confirmed',
      seq,
      createdAt: '2026-09-23T12:00:00.000Z',
    }
  }

  function mountOutbox(userId: string | null) {
    const rendered = renderHook(() => useOutbox(userId))
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

  function sentIds(): string[] {
    return mockedSend.mock.calls.map((call) => call[1].clientMessageId)
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    window.localStorage.clear()
    sequence = 0
  })

  afterEach(() => {
    for (const rendered of mounted.splice(0)) {
      rendered.unmount()
    }
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('evicts the oldest sending record on enqueue, stops its auto-retries and allows a manual retry by the same id', async () => {
    mockedSend.mockRejectedValue(new Error('offline'))
    const victim = record('victim', 'sending', undefined, 'chat-9')
    seed([...seedFailed(OUTBOX_LIMIT - 2), victim])

    const { result } = mountOutbox(USER)
    expect(result.current.records).toHaveLength(OUTBOX_LIMIT - 1)

    // Reaching exactly the limit does not evict anything yet.
    const firstExtra = enqueueValid(result, 'chat-1', 'extra one')
    expect(result.current.records).toHaveLength(OUTBOX_LIMIT)
    expect(findRecord(result.current.records, 'victim')?.state).toBe('sending')

    // One record above the limit: the oldest `sending` is evicted, failed ones are skipped.
    const secondExtra = enqueueValid(result, 'chat-1', 'extra two')
    expect(result.current.records).toHaveLength(OUTBOX_LIMIT)
    expect(findRecord(result.current.records, 'victim')).toMatchObject({
      state: 'failed',
      errorCode: 'queue_overflow',
    })
    expect(findRecord(result.current.records, firstExtra)?.state).toBe('sending')
    expect(findRecord(result.current.records, secondExtra)?.state).toBe('sending')
    expect(
      result.current.records.filter((entry) => entry.errorCode === 'you_are_blocked'),
    ).toHaveLength(OUTBOX_LIMIT - 2)
    // Eviction is persisted — the queue stays at the limit across reloads.
    expect(readOutbox(USER)).toHaveLength(OUTBOX_LIMIT)
    expect(findRecord(readOutbox(USER), 'victim')).toMatchObject({
      state: 'failed',
      errorCode: 'queue_overflow',
    })

    // The evicted record never gets an automatic retry; live records keep retrying.
    await advance(0)
    await advance(60000)
    expect(sentIds()).not.toContain('victim')
    expect(sentIds().filter((id) => id === firstExtra).length).toBeGreaterThan(0)
    expect(sentIds().filter((id) => id === secondExtra).length).toBeGreaterThan(0)

    // Manual retry by the same id converges (FR-005 idempotency).
    mockedSend.mockResolvedValueOnce(makeMessage('chat-9', 'srv-victim', 42))
    act(() => {
      result.current.retry('victim')
    })
    await advance(0)
    expect(mockedSend).toHaveBeenLastCalledWith('chat-9', {
      clientMessageId: 'victim',
      text: victim.text,
    })
    expect(findRecord(result.current.records, 'victim')).toBeUndefined()
    expect(result.current.records).toHaveLength(OUTBOX_LIMIT - 1)
  })
})
