import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPresenceStore, presenceStore, resetPresenceStore } from '../presenceStore'
import type { PresenceStatusItem, PresenceUpdatedEvent } from '../presenceStore'

/**
 * Presence store (feature 007, T012 → T018; research.md §C2):
 * `Map<userId, {status, rev}>` with the single client-side merge rule —
 * apply only a STRICTLY greater `rev` per-user. Everything else
 * (duplicate №18 frame, delayed frame, stale №36 snapshot) is a no-op:
 * the at-most-once channel may drop or duplicate frames freely, and the
 * monotone revision makes every convergence path deterministic
 * (FR-003/FR-006, constitution III).
 *
 * The neutral «unknown» default is NOT «offline»: a fresh surface (a
 * chat-list row, a contact, an opened 1:1 dialog) shows no presence
 * signal at all until the first №36 snapshot arrives — a false
 * «офлайн» before data is a spec violation (clarify 2026-10-01).
 */

const ALICE = '11111111-1111-4111-8111-111111111111'
const BOB = '22222222-2222-4222-8222-222222222222'

function onlineEvent(userId: string, rev: number): PresenceUpdatedEvent {
  return { userId, status: 'online', rev }
}

function offlineEvent(userId: string, rev: number): PresenceUpdatedEvent {
  return { userId, status: 'offline', rev }
}

function snapshotItem(
  userId: string,
  status: PresenceStatusItem['status'],
  rev: number,
): PresenceStatusItem {
  return { userId, status, rev }
}

describe('presenceStore (T012/T018)', () => {
  it('returns the neutral "unknown" before the first snapshot — never a false "offline"', () => {
    const store = createPresenceStore()

    expect(store.getStatus(ALICE)).toBe('unknown')
    expect(store.getEntry(ALICE)).toBeUndefined()
  })

  it('applies the first event for a user', () => {
    const store = createPresenceStore()

    expect(store.applyEvent(onlineEvent(ALICE, 5))).toBe(true)
    expect(store.getStatus(ALICE)).toBe('online')
    expect(store.getEntry(ALICE)).toEqual({ status: 'online', rev: 5 })
  })

  it('ignores a duplicate frame (same rev) — idempotent merge, FR-003', () => {
    const store = createPresenceStore()
    store.applyEvent(onlineEvent(ALICE, 5))

    expect(store.applyEvent(onlineEvent(ALICE, 5))).toBe(false)
    expect(store.getEntry(ALICE)).toEqual({ status: 'online', rev: 5 })
  })

  it('ignores a stale frame (lower rev) — a delayed №18 frame never rewinds state', () => {
    const store = createPresenceStore()
    store.applyEvent(offlineEvent(ALICE, 7))

    expect(store.applyEvent(onlineEvent(ALICE, 6))).toBe(false)
    expect(store.getStatus(ALICE)).toBe('offline')
    expect(store.getEntry(ALICE)).toEqual({ status: 'offline', rev: 7 })
  })

  it('applies strictly greater rev per user, keeping users independent', () => {
    const store = createPresenceStore()
    store.applyEvent(onlineEvent(ALICE, 10))
    store.applyEvent(onlineEvent(BOB, 3))

    expect(store.applyEvent(offlineEvent(ALICE, 11))).toBe(true)
    expect(store.applyEvent(onlineEvent(BOB, 2))).toBe(false)

    expect(store.getEntry(ALICE)).toEqual({ status: 'offline', rev: 11 })
    expect(store.getEntry(BOB)).toEqual({ status: 'online', rev: 3 })
  })

  it('merges №36 snapshot items by the same strictly-greater rule', () => {
    const store = createPresenceStore()
    store.applyEvent(onlineEvent(ALICE, 5))
    store.applyEvent(offlineEvent(BOB, 8))

    const applied = store.applySnapshot([
      snapshotItem(ALICE, 'online', 5), // duplicate rev — no-op
      snapshotItem(BOB, 'online', 9), // strictly greater — applied
      snapshotItem('33333333-3333-4333-8333-333333333333', 'unknown', 1), // fresh «нет доступа»
    ])

    expect(applied).toBe(2)
    expect(store.getEntry(ALICE)).toEqual({ status: 'online', rev: 5 })
    expect(store.getEntry(BOB)).toEqual({ status: 'online', rev: 9 })
    expect(store.getEntry('33333333-3333-4333-8333-333333333333')).toEqual({
      status: 'unknown',
      rev: 1,
    })
  })

  it('recovers a missed presence.updated frame via a snapshot with strictly greater rev (constitution III)', () => {
    const store = createPresenceStore()
    store.applyEvent(onlineEvent(ALICE, 5))
    // rev 6 «offline» frame is LOST by the at-most-once channel; the
    // reconnect №36 snapshot carries the latest server truth instead.
    store.applyEvent(onlineEvent(ALICE, 7)) // unrelated later transition

    const applied = store.applySnapshot([snapshotItem(ALICE, 'offline', 8)])

    expect(applied).toBe(1)
    expect(store.getStatus(ALICE)).toBe('offline')
    expect(store.getEntry(ALICE)).toEqual({ status: 'offline', rev: 8 })
  })

  it('ignores a stale snapshot item — an older №36 answer never overwrites a fresher frame', () => {
    const store = createPresenceStore()
    store.applyEvent(offlineEvent(ALICE, 9))

    expect(store.applySnapshot([snapshotItem(ALICE, 'online', 4)])).toBe(0)
    expect(store.getStatus(ALICE)).toBe('offline')
  })

  it('notifies subscribers on applied changes only, not on no-op merges', () => {
    const store = createPresenceStore()
    const listener = vi.fn()
    const unsubscribe = store.subscribe(listener)

    store.applyEvent(onlineEvent(ALICE, 1))
    expect(listener).toHaveBeenCalledTimes(1)

    store.applyEvent(onlineEvent(ALICE, 1)) // duplicate — silent
    store.applyEvent(offlineEvent(ALICE, 0)) // stale — silent
    expect(listener).toHaveBeenCalledTimes(1)

    unsubscribe()
    store.applyEvent(onlineEvent(ALICE, 2))
    expect(listener).toHaveBeenCalledTimes(1)
  })
})

describe('shared presenceStore (T012 seam for hook/component tests)', () => {
  beforeEach(() => {
    resetPresenceStore()
  })

  it('starts empty and is resettable', () => {
    presenceStore.applyEvent(onlineEvent(BOB, 1))
    expect(presenceStore.getStatus(BOB)).toBe('online')

    resetPresenceStore()
    expect(presenceStore.getStatus(BOB)).toBe('unknown')
    expect(presenceStore.getEntry(BOB)).toBeUndefined()
  })
})
