import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { presenceStore, resetPresenceStore } from '../presenceStore'
import type { PresenceStatusItem } from '../presenceStore'
import { usePresenceStatus, usePresenceSurfaces } from '../usePresence'

/**
 * T074 → T075 (tasks.md 008, Phase 9 bug 1): the DIAGNOSTIC repro of
 * the online-visibility asymmetry «A заходит в чат, B заходит в чат —
 * A видит B онлайн, B НЕ видит A онлайн; после рефреша окна B статус
 * A появляется», now pinning the FIXED behavior (variant б: refetch
 * №36 for ALL displayed surfaces on every surface appearance; the
 * strictly-greater-rev merge of presenceStore keeps that idempotent,
 * presence-events.md §4).
 *
 * The traced mechanism (backend trace: PresenceService.onConnectionOpened
 * → register + transitionOnlineIfDue → publishIfSwitched →
 * RedisPresenceEventPublisher.fanoutPresenceUpdated → Redis Pub/Sub
 * `rt:user:{observerId}` — at-most-once; frontend trace: useRealtime
 * → presenceStore.applyEvent / usePresence.refetchDisplayedSurfaces):
 *
 * 1. BACKEND loses №18 `presence.updated` frames by DESIGN whenever the
 *    observer cannot receive them at publish time: no live SSE session
 *    (Redis Pub/Sub delivers only to currently subscribed connections),
 *    the dynamic per-pod SUBSCRIBE still racing the publish at the
 *    observer's own handshake, or a half-dead socket swallowing the
 *    dispatch write. The transition itself is DURABLE (store CAS +
 *    rev++) and №36 always answers the published truth — the backend's
 *    designated heal is the snapshot, not re-delivery (007 FR-003).
 * 2. FRONTEND (the T074 defect) never invoked that heal for a peer it
 *    already knew: registerSurfaces backfilled №36 ONLY for userIds
 *    with NO store entry, and the reconnect-cycle refetch fired only
 *    on the №18 onOpen — which a healthy, heartbeat-kept connection
 *    may not fire again for hours. An observer holding a stale entry
 *    (offline, old rev) therefore stayed stale indefinitely after one
 *    lost frame — until a manual refresh rebuilt the store.
 * 3. The asymmetry was ORDER-DETERMINISTIC: whoever appeared SECOND had
 *    an empty store → the backfill №36 read the CURRENT published
 *    status (online) and converged; whoever appeared FIRST held the
 *    pre-connect snapshot (offline) and depended entirely on the №18
 *    frame — exactly the frame lost per (1).
 *
 * The T075 fix (variant б) makes EVERY surface appearance — a chat-row
 * mount, a contact row, the opened 1:1 dialog — refetch №36 for the
 * WHOLE displayed set, known peers included; scenes A–E below now
 * assert the convergence contract end to end.
 */
const api = vi.hoisted(() => ({ fetchPresenceSnapshot: vi.fn() }))

vi.mock('../presenceApi', () => api)

const realtime = vi.hoisted(() => {
  const openListeners = new Set<() => void>()
  const presenceListeners = new Set<(event: unknown) => void>()
  return {
    onOpen(listener: () => void) {
      openListeners.add(listener)
      return () => {
        openListeners.delete(listener)
      }
    },
    onPresenceUpdated(listener: (event: unknown) => void) {
      presenceListeners.add(listener)
      return () => {
        presenceListeners.delete(listener)
      }
    },
    fireOpen() {
      for (const listener of openListeners) {
        listener()
      }
    },
    firePresenceUpdated(event: unknown) {
      for (const listener of presenceListeners) {
        listener(event)
      }
    },
  }
})

vi.mock('../../chats/hooks/useRealtime', () => ({ useRealtime: () => realtime }))

const ALICE = '11111111-1111-4111-8111-111111111111'
const BOB = '22222222-2222-4222-8222-222222222222'

function item(
  userId: string,
  status: PresenceStatusItem['status'],
  rev: number,
): PresenceStatusItem {
  return { userId, status, rev }
}

async function flush(): Promise<void> {
  await act(async () => {
    await Promise.resolve()
  })
}

beforeEach(() => {
  resetPresenceStore()
  api.fetchPresenceSnapshot.mockReset()
  api.fetchPresenceSnapshot.mockResolvedValue([])
})

afterEach(cleanup)

describe('T074/T075 presence asymmetry (bug 1, fixed by variant б)', () => {
  it('scene A «второй зашедший»: an empty store backfills №36 and reads the CURRENT status — B sees A online', async () => {
    // A connected earlier: presence:pub already says online rev 6. B's
    // store is empty (fresh page load), the №18 frame about A's
    // transition was published while B had no live stream — lost,
    // irrelevant: the backfill reads the durable truth.
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 6)])

    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE])
    expect(result.current).toBe('online')
  })

  it('scene B «первый зашедший» — THE SYMPTOM, FIXED: opening the chat refetches №36 for the KNOWN peer and converges', async () => {
    // B's app loaded BEFORE A connected: the chat-list backfill №36
    // captured A=offline rev 5. Then A connected —
    // transitionOnlineIfDue resolved Switched and the backend
    // PUBLISHED presence.updated {A, online, 6} to rt:user:{B} once
    // (at-most-once). The frame is lost for B (B's pod subscription
    // raced at B's own handshake / the socket was half-dead / B's SSE
    // reconnected across the publish): nothing fires it below.
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('offline')

    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 6)])

    // B OPENS THE CHAT WITH A (the reported repro step): the ChatHeader
    // and the chat-row surfaces mount for an ALREADY-KNOWN peer — the
    // T075 appearance heal refetches №36 for ALL displayed surfaces,
    // the stale ALICE included (variant б).
    renderHook(() => usePresenceSurfaces([ALICE]))
    renderHook(() => usePresenceStatus(ALICE))
    await flush()

    // The fix, asserted: the snapshot request covers the known peer,
    // the strictly-greater rev 6 merges over the stale offline — no
    // page refresh, no reconnect needed.
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE])
    expect(result.current).toBe('online')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'online', rev: 6 })
  })

  it('scene C «после рефреша»: the reconnect-cycle snapshot heals the stale entry — the manual-refresh fix explained', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('offline')

    // A real page REFRESH rebuilds the store (scene A again); in-place,
    // the equivalent is the №18 (re)connect cycle:
    // refetchDisplayedSurfaces covers displayed ids EVEN when already
    // known — strictly-greater rev merges, which is why the T075
    // appearance heal (variant б) reuses exactly this path on every
    // surface appearance.
    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 6)])
    act(() => {
      realtime.fireOpen()
    })
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE])
    expect(result.current).toBe('online')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'online', rev: 6 })
  })

  it('scene D: the same store DOES accept the lost frame whenever it finally arrives — the merge rule is not the defect', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()

    act(() => {
      realtime.firePresenceUpdated({ userId: ALICE, status: 'online', rev: 6 })
    })

    expect(result.current).toBe('online')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'online', rev: 6 })
  })

  it('scene E: an unrelated surface peer (BOB) still backfills normally next to the stale ALICE — the heal covers every displayed surface', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    renderHook(() => usePresenceStatus(ALICE))
    await flush()

    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(BOB, 'online', 1)])
    renderHook(() => usePresenceSurfaces([ALICE, BOB]))
    await flush()

    // One coalesced batch per commit: the appearance fetches BOTH the
    // unknown BOB (the old backfill duty) and the stale ALICE (the
    // T075 heal) in a single №36 request.
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledTimes(1)
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE, BOB])
    expect(presenceStore.getEntry(BOB)).toEqual({ status: 'online', rev: 1 })
  })
})
