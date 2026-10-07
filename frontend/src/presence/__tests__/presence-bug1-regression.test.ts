import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { presenceStore, resetPresenceStore } from '../presenceStore'
import type { PresenceStatusItem } from '../presenceStore'
import { usePresenceStatus, usePresenceSurfaces } from '../usePresence'

/**
 * T077 (tasks.md 008, Phase 9 bug 1): the REGRESSION suite of the
 * reported asymmetry — «в сторе наблюдателя уже есть entry, но свежий
 * статус не сходится». The observer's store holds a STALE entry (the
 * initial №36 backfill captured the pre-connect status), the only №18
 * `presence.updated` frame about the peer's transition was LOST by the
 * at-most-once channel, and the fix under test (T075, variant б) makes
 * every new surface appearance refetch №36 for ALL displayed surfaces
 * — known peers included — while the strictly-greater-rev merge of
 * presenceStore keeps the convergence idempotent and order-independent
 * (presence-events.md §4, constitution III).
 *
 * Both directions of the bug report are pinned:
 *  * «A заходит» — offline rev 5 + lost online frame + №36 online rev 6
 *    → converges online, no page refresh / reconnect;
 *  * «A уходит» — online rev 6 + lost offline frame + №36 offline
 *    rev 7 → converges offline the same way.
 *
 * Anti-regression guards: a stale №36 answer never rewinds a fresher
 * entry, and the late-arriving lost frame (rev ≤ the store's) is a
 * no-op whenever it finally shows up.
 */
const api = vi.hoisted(() => ({
  fetchPresenceSnapshot: vi.fn(),
  createPresenceHeartbeat: vi.fn(() => ({ updateConnectionId: vi.fn(), stop: vi.fn() })),
}))

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

describe('bug 1 regression — a stale store entry converges via №36 with a greater rev (T075/T077)', () => {
  it('«A заходит»: stale offline + LOST №18 online frame + surface appearance №36 rev 6 → online, no refresh', async () => {
    // The observer's store already knows ALICE — the chat-list backfill
    // captured her pre-connect status offline rev 5 (she had not opened
    // the app yet). The №18 frame about her online transition (rev 6)
    // was published while this observer had no live SSE session — LOST;
    // nothing fires it below.
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('offline')

    // A NEW surface appears (B opens the 1:1 dialog with A): the T075
    // heal refetches №36 for the KNOWN peer; the backend answers the
    // published truth — online with a strictly greater rev.
    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 6)])
    renderHook(() => usePresenceSurfaces([ALICE]))
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE])
    expect(result.current).toBe('online')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'online', rev: 6 })
  })

  it('«A уходит»: online entry + LOST №18 offline frame + №36 offline rev 7 → offline, same heal', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 6)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('online')

    // A's registration expired silently; the only offline frame (rev 7)
    // is lost for this observer. The №36 snapshot is the designated
    // heal on the next surface appearance / (re)connect alike.
    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 7)])
    act(() => {
      realtime.fireOpen()
    })
    await flush()

    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE])
    expect(result.current).toBe('offline')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'offline', rev: 7 })
  })

  it('the lost №18 frame arriving AFTER a fresher №36 is a no-op — order independence', async () => {
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()

    // the heal already converged the store to rev 6…
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 6)])
    renderHook(() => usePresenceSurfaces([ALICE]))
    await flush()
    expect(result.current).toBe('online')

    // …and only THEN the delayed №18 frame of the same transition
    // finally arrives: strictly-greater-rev rules it a stale no-op.
    act(() => {
      realtime.firePresenceUpdated({ userId: ALICE, status: 'online', rev: 6 })
    })
    expect(result.current).toBe('online')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'online', rev: 6 })

    // a duplicate №36 answer with the pre-convergence rev never rewinds
    act(() => {
      presenceStore.applySnapshot([item(ALICE, 'offline', 5)])
    })
    expect(result.current).toBe('online')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'online', rev: 6 })
  })

  it('the reported repro walk: B stale on A → A connects (frame lost) → B opens the chat → online; A leaves (frame lost) → B re-enters contacts → offline', async () => {
    // B's page loaded before A connected: the stale offline entry.
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'offline', 5)])
    const { result } = renderHook(() => usePresenceStatus(ALICE))
    await flush()
    expect(result.current).toBe('offline')

    // A connects — her online frame (rev 6) never reaches B. B OPENS
    // THE CHAT (the reported repro step): the dialog surface mounts and
    // the appearance refetch heals the whole displayed set.
    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([item(ALICE, 'online', 6)])
    renderHook(() => usePresenceSurfaces([ALICE, BOB]))
    await flush()
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE, BOB])
    expect(result.current).toBe('online')

    // A walks away — her offline frame (rev 7) is lost too; B later
    // opens the «Контакты» surface: the same appearance heal converges.
    api.fetchPresenceSnapshot.mockClear()
    api.fetchPresenceSnapshot.mockResolvedValue([
      item(ALICE, 'offline', 7),
      item(BOB, 'unknown', 1),
    ])
    renderHook(() => usePresenceSurfaces([BOB]))
    await flush()
    expect(api.fetchPresenceSnapshot).toHaveBeenCalledWith([ALICE, BOB])
    expect(result.current).toBe('offline')
    expect(presenceStore.getEntry(ALICE)).toEqual({ status: 'offline', rev: 7 })
  })
})
