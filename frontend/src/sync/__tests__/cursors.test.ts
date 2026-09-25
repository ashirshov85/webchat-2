import { beforeEach, describe, expect, it } from 'vitest'
import {
  advanceCursor,
  applySyncSelfHeal,
  cursorsStorageKey,
  getCursor,
  readCursors,
  readSyncCursors,
  rewindCursor,
} from '../cursors'
import type { SelfHealDelta } from '../cursors'

const USER = 'user-1'
const CHAT_A = '7dc5d4a2-3b1e-4f6a-9c2d-000000000001'
const CHAT_B = '7dc5d4a2-3b1e-4f6a-9c2d-000000000002'

function delta(fields: Partial<SelfHealDelta> & Pick<SelfHealDelta, 'chatId'>): SelfHealDelta {
  return { startAfterSeq: 0, ...fields }
}

beforeEach(() => {
  window.localStorage.clear()
})

describe('cursor monotonicity — only max, the client mirror of the №25 GREATEST', () => {
  it('advances forward and returns the resulting position', () => {
    expect(advanceCursor(USER, CHAT_A, 10)).toBe(10)
    expect(advanceCursor(USER, CHAT_A, 42)).toBe(42)
    expect(getCursor(USER, CHAT_A)).toBe(42)
  })

  it('a smaller or repeated seq never moves the cursor back', () => {
    advanceCursor(USER, CHAT_A, 100)

    expect(advanceCursor(USER, CHAT_A, 99)).toBe(100)
    expect(advanceCursor(USER, CHAT_A, 100)).toBe(100)
    expect(getCursor(USER, CHAT_A)).toBe(100)
  })

  it('ignores invalid seq values without touching storage state', () => {
    advanceCursor(USER, CHAT_A, 7)

    expect(advanceCursor(USER, CHAT_A, -1)).toBe(7)
    expect(advanceCursor(USER, CHAT_A, 1.5)).toBe(7)
    expect(advanceCursor(USER, CHAT_A, Number.NaN)).toBe(7)
    expect(getCursor(USER, CHAT_A)).toBe(7)
  })

  it('advances chats independently of each other', () => {
    advanceCursor(USER, CHAT_A, 10)
    advanceCursor(USER, CHAT_B, 3)

    expect(getCursor(USER, CHAT_A)).toBe(10)
    expect(getCursor(USER, CHAT_B)).toBe(3)
  })

  it('defaults a never-synced chat to 0 (sync from scratch)', () => {
    expect(getCursor(USER, CHAT_A)).toBe(0)
  })
})

describe('cursors survive a restart (localStorage persistence)', () => {
  it('reads the map back after a reload from the per-user key', () => {
    advanceCursor(USER, CHAT_A, 128)
    advanceCursor(USER, CHAT_B, 7)

    // module holds no memory — a fresh read is exactly the post-restart view
    expect(readCursors(USER)).toEqual({ [CHAT_A]: 128, [CHAT_B]: 7 })
    expect(window.localStorage.getItem(cursorsStorageKey(USER))).toBe(
      JSON.stringify({ [CHAT_A]: 128, [CHAT_B]: 7 }),
    )
    expect(getCursor(USER, CHAT_A)).toBe(128)
  })

  it('keeps accounts isolated under separate storage keys', () => {
    advanceCursor(USER, CHAT_A, 10)
    advanceCursor('user-2', CHAT_A, 99)

    expect(getCursor(USER, CHAT_A)).toBe(10)
    expect(getCursor('user-2', CHAT_A)).toBe(99)
    expect(cursorsStorageKey(USER)).toBe('webchat.sync.cursors.user-1')
    expect(cursorsStorageKey('user-2')).toBe('webchat.sync.cursors.user-2')
  })

  it('degrades silently on corrupt storage: bad JSON reads back empty', () => {
    window.localStorage.setItem(cursorsStorageKey(USER), '{not json')

    expect(readCursors(USER)).toEqual({})
    expect(getCursor(USER, CHAT_A)).toBe(0)
  })

  it('drops individual invalid entries but keeps the valid ones', () => {
    advanceCursor(USER, CHAT_A, 5)
    const raw = window.localStorage.getItem(cursorsStorageKey(USER)) ?? '{}'
    const corrupt = JSON.parse(raw) as Record<string, unknown>
    corrupt[CHAT_B] = -3
    corrupt['chat-c'] = 'not-a-seq'
    window.localStorage.setItem(cursorsStorageKey(USER), JSON.stringify(corrupt))

    expect(readCursors(USER)).toEqual({ [CHAT_A]: 5 })
  })

  it('exposes the map as the №26 cursors body', () => {
    advanceCursor(USER, CHAT_A, 128)
    advanceCursor(USER, CHAT_B, 7)

    expect(readSyncCursors(USER)).toEqual([
      { chatId: CHAT_A, upToSeq: 128 },
      { chatId: CHAT_B, upToSeq: 7 },
    ])
  })
})

describe('self-heal from a №26 delta (sync-protocol.md §3.1/§5)', () => {
  it('a desynced chat rewinds to serverUpToSeq — the single legal rewind', () => {
    advanceCursor(USER, CHAT_A, 500)

    const result = applySyncSelfHeal(
      USER,
      delta({ chatId: CHAT_A, startAfterSeq: 300, desynced: true, serverUpToSeq: 280 }),
    )

    expect(result).toBe(280)
    expect(getCursor(USER, CHAT_A)).toBe(280)
  })

  it('a desynced chat without serverUpToSeq falls back to startAfterSeq', () => {
    advanceCursor(USER, CHAT_A, 500)

    const result = applySyncSelfHeal(
      USER,
      delta({ chatId: CHAT_A, startAfterSeq: 260, desynced: true }),
    )

    expect(result).toBe(260)
  })

  it('a healthy chat adopts the server resume point monotonically', () => {
    advanceCursor(USER, CHAT_A, 10)
    advanceCursor(USER, CHAT_B, 50)

    expect(applySyncSelfHeal(USER, delta({ chatId: CHAT_A, startAfterSeq: 15 }))).toBe(15)
    // a resume point below the local cursor never drags it back
    expect(applySyncSelfHeal(USER, delta({ chatId: CHAT_B, startAfterSeq: 5 }))).toBe(50)
  })

  it('truncatedUpToSeq pins the cursor on the truncation point', () => {
    advanceCursor(USER, CHAT_A, 10)

    const result = applySyncSelfHeal(
      USER,
      delta({ chatId: CHAT_A, startAfterSeq: 10, truncatedUpToSeq: 42 }),
    )

    expect(result).toBe(42)
    expect(getCursor(USER, CHAT_A)).toBe(42)
  })

  it('truncation pins even after a desync rewind to a lower server position', () => {
    advanceCursor(USER, CHAT_A, 500)

    const result = applySyncSelfHeal(
      USER,
      delta({
        chatId: CHAT_A,
        startAfterSeq: 5,
        desynced: true,
        serverUpToSeq: 5,
        truncatedUpToSeq: 42,
      }),
    )

    expect(result).toBe(42)
  })

  it('self-heal of one chat leaves other chats untouched', () => {
    advanceCursor(USER, CHAT_A, 10)
    advanceCursor(USER, CHAT_B, 77)

    applySyncSelfHeal(USER, delta({ chatId: CHAT_A, startAfterSeq: 40, truncatedUpToSeq: 42 }))

    expect(getCursor(USER, CHAT_B)).toBe(77)
  })

  it('rewindCursor is an exact set for the desynced chat only', () => {
    advanceCursor(USER, CHAT_A, 300)

    expect(rewindCursor(USER, CHAT_A, 200)).toBe(200)
    expect(getCursor(USER, CHAT_A)).toBe(200)
  })
})
