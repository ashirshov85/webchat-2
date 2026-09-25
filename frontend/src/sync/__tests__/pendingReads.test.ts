import { beforeEach, describe, expect, it } from 'vitest'
import {
  confirmPendingRead,
  getPendingRead,
  pendingReadsStorageKey,
  readPendingReads,
  recordPendingRead,
} from '../pendingReads'

const USER = 'user-1'
const CHAT_A = '7dc5d4a2-3b1e-4f6a-9c2d-000000000001'
const CHAT_B = '7dc5d4a2-3b1e-4f6a-9c2d-000000000002'

beforeEach(() => {
  window.localStorage.clear()
})

describe('watermark monotonicity — only max, the client mirror of the №17 GREATEST', () => {
  it('records a read and returns the resulting watermark', () => {
    expect(recordPendingRead(USER, CHAT_A, 10)).toBe(10)
    expect(recordPendingRead(USER, CHAT_A, 42)).toBe(42)
    expect(getPendingRead(USER, CHAT_A)).toBe(42)
  })

  it('a smaller or repeated seq never moves the watermark back', () => {
    recordPendingRead(USER, CHAT_A, 100)

    expect(recordPendingRead(USER, CHAT_A, 99)).toBe(100)
    expect(recordPendingRead(USER, CHAT_A, 100)).toBe(100)
    expect(getPendingRead(USER, CHAT_A)).toBe(100)
  })

  it('ignores invalid upToSeq values without touching storage state', () => {
    recordPendingRead(USER, CHAT_A, 7)

    expect(recordPendingRead(USER, CHAT_A, 0)).toBe(7)
    expect(recordPendingRead(USER, CHAT_A, -1)).toBe(7)
    expect(recordPendingRead(USER, CHAT_A, 1.5)).toBe(7)
    expect(recordPendingRead(USER, CHAT_A, Number.NaN)).toBe(7)
    expect(getPendingRead(USER, CHAT_A)).toBe(7)
  })

  it('tracks chats independently of each other', () => {
    recordPendingRead(USER, CHAT_A, 10)
    recordPendingRead(USER, CHAT_B, 3)

    expect(getPendingRead(USER, CHAT_A)).toBe(10)
    expect(getPendingRead(USER, CHAT_B)).toBe(3)
  })

  it('defaults a never-read chat to 0 (nothing pending)', () => {
    expect(getPendingRead(USER, CHAT_A)).toBe(0)
  })

  it('ignores an invalid chatId entirely', () => {
    expect(recordPendingRead(USER, '', 5)).toBe(0)
    expect(readPendingReads(USER)).toEqual({})
  })
})

describe('an offline read applies only to previously synchronized messages (US3-7)', () => {
  it('clamps a read above the delivery position down to the local cursor', () => {
    expect(recordPendingRead(USER, CHAT_A, 50, 30)).toBe(30)
    expect(getPendingRead(USER, CHAT_A)).toBe(30)
  })

  it('records a read at or below the delivery position as-is', () => {
    expect(recordPendingRead(USER, CHAT_A, 30, 30)).toBe(30)
    expect(recordPendingRead(USER, CHAT_B, 12, 40)).toBe(12)
  })

  it('a clamp that lands below the current watermark never drags it back', () => {
    recordPendingRead(USER, CHAT_A, 9)

    expect(recordPendingRead(USER, CHAT_A, 50, 5)).toBe(9)
    expect(getPendingRead(USER, CHAT_A)).toBe(9)
  })

  it('a clamp may still advance the watermark up to the delivery position', () => {
    recordPendingRead(USER, CHAT_A, 5)

    expect(recordPendingRead(USER, CHAT_A, 50, 7)).toBe(7)
  })

  it('an absent or invalid delivery bound disables clamping', () => {
    expect(recordPendingRead(USER, CHAT_A, 50)).toBe(50)
    expect(recordPendingRead(USER, CHAT_B, 50, 0)).toBe(50)
  })
})

describe('pending reads survive a restart (localStorage persistence)', () => {
  it('reads the map back after a reload from the per-user key', () => {
    recordPendingRead(USER, CHAT_A, 128)
    recordPendingRead(USER, CHAT_B, 7)

    // module holds no memory — a fresh read is exactly the post-restart view
    expect(readPendingReads(USER)).toEqual({ [CHAT_A]: 128, [CHAT_B]: 7 })
    expect(window.localStorage.getItem(pendingReadsStorageKey(USER))).toBe(
      JSON.stringify({ [CHAT_A]: 128, [CHAT_B]: 7 }),
    )
    expect(getPendingRead(USER, CHAT_A)).toBe(128)
  })

  it('keeps accounts isolated under separate storage keys', () => {
    recordPendingRead(USER, CHAT_A, 10)
    recordPendingRead('user-2', CHAT_A, 99)

    expect(getPendingRead(USER, CHAT_A)).toBe(10)
    expect(getPendingRead('user-2', CHAT_A)).toBe(99)
    expect(pendingReadsStorageKey(USER)).toBe('webchat.sync.pendingReads.user-1')
    expect(pendingReadsStorageKey('user-2')).toBe('webchat.sync.pendingReads.user-2')
  })

  it('degrades silently on corrupt storage: bad JSON reads back empty', () => {
    window.localStorage.setItem(pendingReadsStorageKey(USER), '{not json')

    expect(readPendingReads(USER)).toEqual({})
    expect(getPendingRead(USER, CHAT_A)).toBe(0)
  })

  it('drops individual invalid entries but keeps the valid ones', () => {
    recordPendingRead(USER, CHAT_A, 5)
    const raw = window.localStorage.getItem(pendingReadsStorageKey(USER)) ?? '{}'
    const corrupt = JSON.parse(raw) as Record<string, unknown>
    corrupt[CHAT_B] = -3
    corrupt['chat-c'] = 'not-a-seq'
    window.localStorage.setItem(pendingReadsStorageKey(USER), JSON.stringify(corrupt))

    expect(readPendingReads(USER)).toEqual({ [CHAT_A]: 5 })
  })
})

describe('removal happens only after №17 answered 204', () => {
  it('a confirmed watermark removes the entry', () => {
    recordPendingRead(USER, CHAT_A, 12)

    confirmPendingRead(USER, CHAT_A, 12)

    expect(getPendingRead(USER, CHAT_A)).toBe(0)
    expect(readPendingReads(USER)).toEqual({})
  })

  it('a newer offline read recorded while the request was in flight stays pending', () => {
    recordPendingRead(USER, CHAT_A, 5)
    // №17 for 5 is in flight — the user reads further offline
    recordPendingRead(USER, CHAT_A, 9)

    confirmPendingRead(USER, CHAT_A, 5)

    expect(getPendingRead(USER, CHAT_A)).toBe(9)
    // the next flush replays the newer watermark and only then clears it
    confirmPendingRead(USER, CHAT_A, 9)
    expect(getPendingRead(USER, CHAT_A)).toBe(0)
  })

  it('confirming an absent entry is a no-op (idempotent confirm)', () => {
    expect(() => confirmPendingRead(USER, CHAT_A, 5)).not.toThrow()
    expect(readPendingReads(USER)).toEqual({})
  })

  it('invalid confirm arguments never wipe a pending entry', () => {
    recordPendingRead(USER, CHAT_A, 9)

    confirmPendingRead(USER, CHAT_A, 0)
    confirmPendingRead(USER, '', 9)

    expect(getPendingRead(USER, CHAT_A)).toBe(9)
  })

  it('confirming one chat leaves other chats untouched', () => {
    recordPendingRead(USER, CHAT_A, 10)
    recordPendingRead(USER, CHAT_B, 77)

    confirmPendingRead(USER, CHAT_A, 10)

    expect(readPendingReads(USER)).toEqual({ [CHAT_B]: 77 })
  })
})

describe('idempotent flush replay — a repeat after a lost response is safe (server GREATEST)', () => {
  it('a lost request leaves the entry in place for the next flush', () => {
    recordPendingRead(USER, CHAT_A, 5)

    // №17 attempt fails (offline / lost) — no 204, so no confirm happens
    const firstFlushWatermark = getPendingRead(USER, CHAT_A)
    expect(firstFlushWatermark).toBe(5)

    // the reconnect flush replays the very same watermark — №17's server-side
    // GREATEST makes the repeat a no-op, so the replayed value is unchanged
    expect(getPendingRead(USER, CHAT_A)).toBe(5)

    // the eventual 204 clears it; a second replay finds nothing left
    confirmPendingRead(USER, CHAT_A, 5)
    expect(readPendingReads(USER)).toEqual({})
    expect(() => confirmPendingRead(USER, CHAT_A, 5)).not.toThrow()
    expect(readPendingReads(USER)).toEqual({})
  })

  it('an unconfirmed read survives a restart and is still pending for the flush', () => {
    recordPendingRead(USER, CHAT_A, 21)

    // restart — the entry must still be there for the reconnect flush (FR-008)
    expect(readPendingReads(USER)).toEqual({ [CHAT_A]: 21 })

    confirmPendingRead(USER, CHAT_A, 21)
    expect(readPendingReads(USER)).toEqual({})
  })

  it('a duplicate 204 replayed after a lost response does not resurrect the entry', () => {
    recordPendingRead(USER, CHAT_A, 5)
    confirmPendingRead(USER, CHAT_A, 5)

    // the first 204 was lost; the retry also answers 204 — already removed
    confirmPendingRead(USER, CHAT_A, 5)

    expect(readPendingReads(USER)).toEqual({})
    expect(getPendingRead(USER, CHAT_A)).toBe(0)
  })

  it('replaying a flush whose response was lost merges with a newer offline read by max', () => {
    recordPendingRead(USER, CHAT_A, 5)
    // the 5-flush's response is lost; before the replay the user reads further
    recordPendingRead(USER, CHAT_A, 9)

    // the replay observes the merged (monotone) watermark — one value, no split
    expect(getPendingRead(USER, CHAT_A)).toBe(9)

    confirmPendingRead(USER, CHAT_A, 5) // the lost response arrives late — stale
    expect(getPendingRead(USER, CHAT_A)).toBe(9) // the newer read is still pending

    confirmPendingRead(USER, CHAT_A, 9)
    expect(readPendingReads(USER)).toEqual({})
  })
})
