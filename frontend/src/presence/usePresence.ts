/**
 * Surface wiring for presence (feature 007, T020; 008 T075 variant б;
 * contracts/presence-api.md §1, presence-events.md §2/§4):
 *
 * - every MOUNTED indicator is a displayed surface («Чаты» rows, the
 *   visible «Контакты» list, the open 1:1 dialog — presence-api.md §1);
 *   the hooks below refcount those userIds in a module-level registry
 *   shared by the whole tree, because №36 is one batch per screen, not
 *   per component;
 * - a surface APPEARING refetches №36 for EVERY displayed surface —
 *   known peers included (008 T075, bug 1 «асимметрия видимости»):
 *   the at-most-once channel may lose the only `presence.updated`
 *   frame about a peer the observer already knows, leaving the store
 *   entry stale until a page refresh; №36 is the backend's designated
 *   heal (007 FR-003: «потеря или дубль кадра сходится актуальным
 *   снимком»), and the strictly-greater-rev merge of presenceStore
 *   keeps repeated snapshots idempotent (constitution III,
 *   presence-events.md §4). Appearances are coalesced per commit
 *   (microtask), batches are chunked ≤200 and deduped in flight;
 * - the 005 (re)connect cycle (`onOpen`, after sync/chats/contacts)
 *   refetches the same displayed set immediately — the recovery path
 *   for frames lost while the observer itself was offline;
 * - live `presence.updated` frames merge into the store directly
 *   (FR-003: duplicates/stale frames are store-level no-ops).
 *
 * The realtime wiring (onOpen + presence.updated) is installed once
 * per process on the shared №18 stream singleton from useRealtime —
 * the same one-connection-per-device feed every other feature uses.
 * Heartbeat №37 scheduling (connectionId from the `connected` frame)
 * lives in presenceApi (T019) and is wired by [usePresenceHeartbeat]
 * — the page integration mounts it once on the shared stream
 * (008 T076: the scheduler existed but was never wired, so every
 * registration lapsed by its 90 s TTL and a live connection flipped
 * «офлайн» for the observers).
 */
import { useEffect, useSyncExternalStore } from 'react'
import { useRealtime } from '../chats/hooks/useRealtime'
import type { RealtimeStream } from '../chats/hooks/useRealtime'
import { createPresenceHeartbeat, fetchPresenceSnapshot } from './presenceApi'
import { presenceStore } from './presenceStore'
import type { PresenceStatus } from './presenceStore'

/** №36 batch limit after dedup (contracts/presence-api.md §1). */
const SNAPSHOT_BATCH_LIMIT = 200

/** userId → number of mounted surfaces displaying it. */
const surfaceRefcounts = new Map<string, number>()

/** Ids with a №36 request in flight (no duplicate batches). */
const refetchInflight = new Set<string>()

let refetchScheduled = false

function registerSurfaces(userIds: readonly string[]): void {
  for (const userId of new Set(userIds)) {
    const count = surfaceRefcounts.get(userId) ?? 0
    surfaceRefcounts.set(userId, count + 1)
  }
  // Any surface appearance (a chat row, a contact row, the opened 1:1
  // dialog) is a heal opportunity for EVERY displayed peer — T075.
  scheduleSurfaceRefetch()
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
 * Microtask coalescing: surfaces mounting in the same commit (a list
 * render, the dialog opening over the list) produce ONE №36 batch
 * set, not a request per row.
 */
function scheduleSurfaceRefetch(): void {
  if (refetchScheduled) {
    return
  }
  refetchScheduled = true
  void Promise.resolve().then(() => {
    refetchScheduled = false
    refetchDisplayedSurfaces()
  })
}

/**
 * №36 for every displayed surface — known peers included (008 T075
 * variant б): this is the designated heal for a `presence.updated`
 * frame lost by the at-most-once channel (007 FR-003). Stale items
 * merge as no-ops by the strictly greater snapshot rev
 * (presence-events.md §4, constitution III); ids already in flight
 * are skipped — a request per displayed peer, not per surface.
 */
function refetchDisplayedSurfaces(): void {
  const ids = [...surfaceRefcounts.keys()].filter((userId) => !refetchInflight.has(userId))
  for (let start = 0; start < ids.length; start += SNAPSHOT_BATCH_LIMIT) {
    void fetchAndApplySnapshot(ids.slice(start, start + SNAPSHOT_BATCH_LIMIT))
  }
}

async function fetchAndApplySnapshot(batch: readonly string[]): Promise<void> {
  for (const userId of batch) {
    refetchInflight.add(userId)
  }
  try {
    presenceStore.applySnapshot(await fetchPresenceSnapshot(batch))
  } catch {
    // The surface keeps the last known status (or the neutral
    // «unknown» before the first snapshot — never a false «офлайн»);
    // the next surface appearance or (re)connect retries.
  } finally {
    for (const userId of batch) {
      refetchInflight.delete(userId)
    }
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
    refetchDisplayedSurfaces()
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
 * dialog): registers the peer, refetches №36 for ALL displayed
 * surfaces on appearance (the T075 lost-frame heal — known peers
 * included, idempotent by rev) and re-renders on every store change.
 * Returns the merged status — «unknown» both before the first
 * snapshot and for №36 «нет доступа» (FR-006/FR-007: a false
 * «офлайн» is forbidden). `null`/`undefined` (e.g. group chats,
 * which carry no presence UI) registers nothing and reports
 * «unknown».
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
 * mounted «Чаты» rows): refcounts every userId and refetches №36 for
 * the WHOLE displayed set on appearance — known peers included
 * (008 T075): the rev merge keeps repeated snapshots idempotent, and
 * one coalesced batch per commit covers every displayed peer.
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

/**
 * The №37 heartbeat wiring (008 T076 «статус сбрасывается со
 * временем»): the page integration mounts this ONCE on the shared №18
 * stream — without it the scheduler of presenceApi (007 T019) never
 * started, the registration lapsed by its 90 s TTL and the watch
 * poller reaped a LIVE connection into «офлайн» for every observer.
 * Every `connected` frame hands the scheduler the fresh connectionId
 * (a late mount replays the current one); 404
 * `presence_connection_not_found` reconnects the SSE channel
 * IMMEDIATELY (presence-api.md §2), and the new `connected` frame
 * restarts the 30 s beats with the new id — 429/network errors retry
 * in the next interval inside the scheduler (the 3× TTL margin).
 */
export function usePresenceHeartbeat(stream: RealtimeStream): void {
  useEffect(() => {
    const heartbeat = createPresenceHeartbeat({
      onReconnectRequired: () => {
        stream.reconnect()
      },
    })
    const unsubscribe = stream.onConnected((connectionId) => {
      heartbeat.updateConnectionId(connectionId)
    })
    return () => {
      unsubscribe()
      heartbeat.stop()
    }
  }, [stream])
}
