import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { presenceStore, resetPresenceStore } from '../presenceStore'
import type { PresenceStatusItem } from '../presenceStore'
import { usePresenceStatus, usePresenceSurfaces } from '../usePresence'

/**
 * T074 (tasks.md 008, Phase 9 bug 1): the DIAGNOSTIC repro of the
 * online-visibility asymmetry «A заходит в чат, B заходит в чат — A
 * видит B онлайн, B НЕ видит A онлайн; после рефреша окна B статус A
 * появляется». This suite pins the MECHANISM as executable evidence —
 * it asserts the CURRENT behavior, defect included, and is the input
 * for the T075 fix (variant б: refetch №36 for ALL displayed surfaces
 * on surface appearance; the strictly-greater-rev merge of
 * presenceStore keeps that idempotent, presence-events.md §4).
 *
 * The traced mechanism (backend trace: PresenceService.onConnectionOpened
 * → register + transitionOnlineIfDue → publishIfSwitched →
 * RedisPresenceEventPublisher.fanoutPresenceUpdated → Redis Pub/Sub
 * `rt:user:{observerId}` — at-most-once; frontend trace: useRealtime
 * → presenceStore.applyEvent / usePresence.refetchAllSurfaces):
 *
 * 1. BACKEND loses №18 `presence.updated` frames by DESIGN whenever the
 *    observer cannot receive them at publish time: no live SSE session
 *    (Redis Pub/Sub delivers only to currently subscribed connections),
 *    the dynamic per-pod SUBSCRIBE still racing the publish at the
 *    observer's own handshake, or a half-dead socket swallowing the
 *    dispatch write. The transition itself is DURABLE (store CAS +
 *    rev++) and №36 always answers the published truth — the backend's
 *    designated heal is the snapshot, not re-delivery.
 * 2. FRONTEND never invokes that heal for a peer it already knows:
 *    registerSurfaces backfills №36 ONLY for userIds with NO store
 *    entry (usePresence.ts — known peers are never re-requested), and
 *    the reconnect-cycle refetch (refetchAllSurfaces) fires only on
 *    the №18 onOpen — which a healthy, heartbeat-kept connection may
 *    not fire again for hours. An observer holding a stale entry
 *    (offline, old rev) therefore stays stale indefinitely after one
 *    lost frame — until a manual refresh rebuilds the store.
 * 3. The asymmetry is ORDER-DETERMINISTIC: whoever appears SECOND has
 *    an empty store → the backfill №36 reads the CURRENT published
 *    status (online) and converges; whoever appeared FIRST holds the
 *    pre-connect snapshot (offline) and depends entirely on the №18
 *    frame — exactly the frame lost per (1).
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

describe('T074 presence asymmetry diagnosis (bug 1)', () => {
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

  it('scene B «первый зашедший» — THE SYMPTOM: a stale entry never converges without a reconnect', async () => {
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
    // is a NEW displayed surface mounting for an ALREADY-KNOWN peer —
    // registerSurfaces skips it (the entry exists), №36 is NOT sent.
    renderHook(() => usePresenceSurfaces([ALICE]))
    renderHook(() => usePresenceStatus(ALICE))
    await flush()
    await act(async () => {
      await new Promise((resolve) => {
        setTimeout(resolve, 0)
      })
    })

    // The defect, asserted: no snapshot request, no convergence — the
    // stale offline survives on a healthy never-reconnecting stream.
    expect(api.fetchPresenceSnapshot).not.toHaveBeenCalled()
    expect(result.current).toBe('offline')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'offline', rev: 5 })
  })

  it('scene C «после рефреша»: the reconnect-cycle snapshot heals the stale entry — the manual-refresh fix explained', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('offline')

    // A real page REFRESH rebuilds the store (scene A again); in-place,
    // the equivalent is the №18 (re)connect cycle: refetchAllSurfaces
    // covers displayed ids EVEN when already known — strictly-greater
    // rev merges, which is why T075 variant б can reuse exactly this
    // path on surface appearance.
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

  it('scene E: an unrelated surface peer (BOB) still backfills normally next to the stale ALICE — chunking unaffected', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    renderHook(() => usePresenceStatus(ALICE))
    await flush()

    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(BOB, 'online', 1)])
    renderHook(() => usePresenceSurfaces([ALICE, BOB]))
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledTimes(1)
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([BOB])
  })
})
