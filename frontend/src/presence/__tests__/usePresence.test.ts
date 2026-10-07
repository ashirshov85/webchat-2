import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { presenceStore, resetPresenceStore } from '../presenceStore'
import type { PresenceStatusItem } from '../presenceStore'
import { usePresenceStatus, usePresenceSurfaces } from '../usePresence'

/**
 * Surface snapshot wiring (feature 007, T012 → T020; 008 T075 variant б;
 * contracts/presence-api.md §1, presence-events.md §2/§4):
 *
 * - every MOUNTED indicator is a displayed surface («Чаты» rows,
 *   the «Контакты» list, the open 1:1 dialog); a surface APPEARING
 *   refetches №36 for ALL displayed surfaces — known peers included
 *   (008 T075, bug 1 «асимметрия»): the at-most-once channel may lose
 *   the only `presence.updated` frame about a peer the observer
 *   already knows, and №36 is the backend's designated heal (FR-003);
 *   the strictly-greater-rev merge keeps repeated snapshots idempotent
 *   (constitution III);
 * - the 005 (re)connect cycle refetches the snapshot of all displayed
 *   surfaces, so a presence.updated frame lost by the at-most-once
 *   channel converges via a strictly greater snapshot rev
 *   (constitution III);
 * - presence.updated frames merge into the store live; duplicates and
 *   stale frames are no-ops (FR-003);
 * - >200 displayed userIds are chunked into batches of ≤200.
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

function uuid(index: number): string {
  return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
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

describe('usePresence surfaces (T012/T020)', () => {
  it('fetches №36 for a surface userId missing from the store and converges to its status', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 5)])

    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledTimes(1)
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE])
    expect(result.current).toBe('online')
  })

  it('keeps the neutral "unknown" while the surface snapshot has not answered yet', async () => {
    let resolveSnapshot: (items: PresenceStatusItem[]) => void = () => {}
    api.fetchPresenceSnapshot.mockReturnValue(
      new Promise<PresenceStatusItem[]>((resolve) => {
        resolveSnapshot = resolve
      }),
    )

    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()

    expect(result.current).toBe('unknown')

    await act(async () => {
      resolveSnapshot([item(ALICE, 'offline', 2)])
      await Promise.resolve()
    })
    expect(result.current).toBe('offline')
  })

  it('a new surface (chat row / contact / opened dialog) refetches №36 for ALL displayed surfaces — the T075 lost-frame heal', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 5)])
    const first = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(first.result.current).toBe('online')

    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 5), item(BOB, 'offline', 3)])

    // The «Контакты» surface now also shows BOB while ALICE is already
    // known: №36 covers BOTH — a surface appearance heals every
    // displayed peer, stale entries included (008 T075 variant б).
    renderHook(() => usePresenceSurfaces([ALICE, BOB]))
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledTimes(1)
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE, BOB])

    const bob = renderHook(() => usePresenceStatus(BOB))
    await flush()
    expect(bob.result.current).toBe('offline')
    // The status row itself is another appearance: the heal repeats —
    // idempotent by rev, the store state does not churn.
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledTimes(2)
    expect(api.fetchPresenceSnapshot).toHaveBeenLastCalledWith([ALICE, BOB])
    expect(presenceStore.getEntry(BOB)).toEqual({ status: 'offline', rev: 3 })
  })

  it('chunks >200 missing surface userIds into №36 batches of ≤200', async () => {
    const ids = Array.from({ length: 250 }, (_, index) => uuid(index + 1))

    renderHook(() => usePresenceSurfaces(ids))
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledTimes(2)
    const batches = api.fetchPresenceSnapshot.mock.calls.map((call) => call[0] as string[])
    for (const batch of batches) {
      expect(batch.length).toBeLessThanOrEqual(200)
    }
    expect(new Set(batches.flat())).toEqual(new Set(ids))
  })

  it('refetches the snapshot of all displayed surfaces on every (re)connect — 005 cycle after sync', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 5)])
    renderHook(() => usePresenceStatus(ALICE))
    await flush()

    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 5)])

    act(() => {
      realtime.fireOpen()
    })
    await flush()

    // ALICE is already known — the reconnect snapshot still covers the
    // surface because its purpose is recovering LOST frames (stale
    // items merge as no-ops by rev).
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledTimes(1)
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE])
  })

  it('a missed presence.updated frame converges via the reconnect snapshot with strictly greater rev (constitution III)', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('online')

    // rev 6 «offline» is LOST by the channel — nothing is fired here.

    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 7)])
    act(() => {
      realtime.fireOpen()
    })
    await flush()

    expect(result.current).toBe('offline')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'offline', rev: 7 })
  })

  it('an older reconnect snapshot never rewinds a fresher applied frame', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 9)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('offline')

    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 4)])
    act(() => {
      realtime.fireOpen()
    })
    await flush()

    expect(result.current).toBe('offline')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'offline', rev: 9 })
  })

  it('merges live presence.updated frames; duplicates and stale frames leave the state intact (FR-003)', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('online')

    act(() => {
      realtime.firePresenceUpdated({ userId: ALICE, status: 'offline', rev: 6 })
    })
    expect(result.current).toBe('offline')

    act(() => {
      realtime.firePresenceUpdated({ userId: ALICE, status: 'offline', rev: 6 }) // duplicate
      realtime.firePresenceUpdated({ userId: ALICE, status: 'online', rev: 2 }) // stale
    })
    expect(result.current).toBe('offline')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'offline', rev: 6 })
  })
})
