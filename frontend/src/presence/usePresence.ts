/**
 * Surface wiring for presence (feature 007, T020; contracts/
 * presence-api.md §1, presence-events.md §2/§4):
 *
 * - every MOUNTED indicator is a displayed surface («Чаты» rows, the
 *   visible «Контакты» list, the open 1:1 dialog — presence-api.md §1);
 *   the hooks below refcount those userIds in a module-level registry
 *   shared by the whole tree, because №36 is one batch per screen, not
 *   per component;
 * - a surface appearing without a status in the store backfills №36
 *   ONLY for the missing userIds — known peers are never re-requested
 *   (FR-003); >200 missing ids are chunked into ≤200 batches;
 * - the 005 (re)connect cycle (`onOpen`, after sync/chats/contacts)
 *   refetches the snapshot of ALL displayed surfaces — this is the
 *   recovery path for a `presence.updated` frame lost by the
 *   at-most-once channel: convergence is guaranteed by the strictly
 *   greater snapshot `rev` (constitution III, presence-events.md §4);
 * - live `presence.updated` frames merge into the store directly
 *   (FR-003: duplicates/stale frames are store-level no-ops).
 *
 * The realtime wiring (onOpen + presence.updated) is installed once
 * per process on the shared №18 stream singleton from useRealtime —
 * the same one-connection-per-device feed every other feature uses.
 * Heartbeat №37 scheduling (connectionId from the `connected` frame)
 * lives in presenceApi (T019) and is wired by the page integration.
 */
import { useEffect, useSyncExternalStore } from 'react'
import { useRealtime } from '../chats/hooks/useRealtime'
import type { RealtimeStream } from '../chats/hooks/useRealtime'
import { fetchPresenceSnapshot } from './presenceApi'
import { presenceStore } from './presenceStore'
import type { PresenceStatus } from './presenceStore'

/** №36 batch limit after dedup (contracts/presence-api.md §1). */
const SNAPSHOT_BATCH_LIMIT = 200

/** userId → number of mounted surfaces displaying it. */
const surfaceRefcounts = new Map<string, number>()

/** Backfill candidates not yet handed to №36 (dedup across mounts). */
const backfillQueued = new Set<string>()

/** Ids with a №36 request in flight (no duplicate batches). */
const backfillInflight = new Set<string>()

let backfillScheduled = false

function registerSurfaces(userIds: readonly string[]): void {
  let missing = 0
  for (const userId of new Set(userIds)) {
    const count = surfaceRefcounts.get(userId) ?? 0
    surfaceRefcounts.set(userId, count + 1)
    if (
      count === 0 &&
      presenceStore.getEntry(userId) === undefined &&
      !backfillQueued.has(userId) &&
      !backfillInflight.has(userId)
    ) {
      backfillQueued.add(userId)
      missing += 1
    }
  }
  if (missing > 0) {
    scheduleBackfill()
  }
}

function unregisterSurfaces(userIds: readonly string[]): void {
  for (const userId of new Set(userIds)) {
    const count = surfaceRefcounts.get(userId)
    if (count === undefined) {
      continue
    }
    if (count <= 1) {
      surfaceRefcounts.delete(userId)
    } else {
      surfaceRefcounts.set(userId, count - 1)
    }
  }
}

/**
 * Microtask batching: surfaces mounting in the same commit (a list
 * render) produce ONE №36 batch, not a request per row.
 */
function scheduleBackfill(): void {
  if (backfillScheduled) {
    return
  }
  backfillScheduled = true
  void Promise.resolve().then(() => {
    backfillScheduled = false
    drainBackfill()
  })
}

function drainBackfill(): void {
  const ids = [...backfillQueued]
  backfillQueued.clear()
  for (let start = 0; start < ids.length; start += SNAPSHOT_BATCH_LIMIT) {
    void fetchAndApplySnapshot(ids.slice(start, start + SNAPSHOT_BATCH_LIMIT))
  }
}

async function fetchAndApplySnapshot(batch: readonly string[]): Promise<void> {
  for (const userId of batch) {
    backfillInflight.add(userId)
  }
  try {
    presenceStore.applySnapshot(await fetchPresenceSnapshot(batch))
  } catch {
    // The surface keeps the neutral «unknown» (never a false
    // «офлайн»); the next surface appearance or (re)connect retries.
  } finally {
    for (const userId of batch) {
      backfillInflight.delete(userId)
    }
  }
}

/**
 * The 005 (re)connect cycle: refetch EVERY displayed surface —
 * including peers already in the store, because the purpose is
 * recovering frames lost while offline; stale items merge as no-ops
 * by rev (presence-events.md §4, constitution III).
 */
function refetchAllSurfaces(): void {
  const ids = [...surfaceRefcounts.keys()].filter((userId) => !backfillQueued.has(userId))
  for (let start = 0; start < ids.length; start += SNAPSHOT_BATCH_LIMIT) {
    const batch = ids.slice(start, start + SNAPSHOT_BATCH_LIMIT)
    fetchPresenceSnapshot(batch)
      .then((items) => {
        presenceStore.applySnapshot(items)
      })
      .catch(() => {
        // a failed reconnect snapshot converges on the next (re)connect
      })
  }
}

let realtimeWired = false

/**
 * Installs the process-wide №18 presence handlers ONCE on the shared
 * stream: `onOpen` → №36 surface snapshot, `presence.updated` → store
 * merge. The stream singleton outlives every surface, so the
 * subscriptions are never removed.
 */
function ensurePresenceWiring(stream: RealtimeStream): void {
  if (realtimeWired) {
    return
  }
  realtimeWired = true
  stream.onOpen(() => {
    refetchAllSurfaces()
  })
  stream.onPresenceUpdated((event) => {
    presenceStore.applyEvent(event)
  })
}

function subscribeToPresenceStore(listener: () => void): () => void {
  return presenceStore.subscribe(listener)
}

/**
 * One displayed surface (a «Чаты» row, a contact, the open 1:1
 * dialog): registers the peer, backfills №36 when the store has no
 * entry yet and re-renders on every store change. Returns the merged
 * status — «unknown» both before the first snapshot and for №36
 * «нет доступа» (FR-006/FR-007: a false «офлайн» is forbidden).
 * `null`/`undefined` (e.g. group chats, which carry no presence UI)
 * registers nothing and reports «unknown».
 */
export function usePresenceStatus(userId: string | null | undefined): PresenceStatus {
  const stream = useRealtime()
  useEffect(() => {
    ensurePresenceWiring(stream)
  }, [stream])
  useEffect(() => {
    if (userId === null || userId === undefined) {
      return
    }
    registerSurfaces([userId])
    return () => {
      unregisterSurfaces([userId])
    }
  }, [userId])
  return useSyncExternalStore(subscribeToPresenceStore, () => presenceStore.getStatus(userId ?? ''))
}

/**
 * A set of displayed surfaces (the visible «Контакты» list, the
 * mounted «Чаты» rows): refcounts every userId and backfills the
 * MISSING ones in ≤200-id №36 batches. Mounting a list whose peers
 * are already known does not re-request them (FR-003).
 */
export function usePresenceSurfaces(userIds: readonly string[]): void {
  const stream = useRealtime()
  useEffect(() => {
    ensurePresenceWiring(stream)
  }, [stream])
  // Stable dependency for an array that may get a new identity every
  // render: identical contents must not churn the refcounts.
  const surfaceKey = userIds.filter((userId) => userId.length > 0).join('\n')
  useEffect(() => {
    if (surfaceKey === '') {
      return
    }
    const ids = surfaceKey.split('\n')
    registerSurfaces(ids)
    return () => {
      unregisterSurfaces(ids)
    }
  }, [surfaceKey])
}
