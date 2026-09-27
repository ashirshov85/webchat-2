/**
 * Group realtime slice (feature 006, T027; FR-015,
 * contracts/realtime-group-events.md §1/§3.2/§5): the per-user №18
 * stream carries `group.*` frames, and this module is their
 * idempotent projection onto the «Чаты» state.
 *
 * The US1 starting slice is `group.member.added` (§3.2): the frame is
 * fanned out to every ACTIVE participant INCLUDING the added user, so
 * for the added user it materializes a group row his №12 list has not
 * seen yet — the group becomes visible in «Чаты» at once (US1-2).
 * The channel is at-most-once AND frames may be delivered more than
 * once, so the reducer applies STATE, never deltas (§5.1): the set of
 * known groups keyed by `chatId = groupId` (§1) — a duplicate frame
 * therefore cannot create a duplicate group. A group already known
 * (e.g. seeded from №12) keeps its aggregate fields (`title`,
 * `memberCount`) untouched: they are server-authoritative and are
 * converged by the №12 refetch of the next (re)connect, not summed
 * from frame arithmetic. Rows materialized from frames carry the bare
 * `chatId` for the same reason — the aggregate catches up via REST.
 *
 * Unknown group `event:` types pass through untouched (§1 forward
 * compatibility). The remaining §3 events join the reducer with their
 * own stories: `group.updated` — T053 (US4), `group.member.removed`/
 * `group.role.changed`/`group.you_removed` — T048 (US3/US5),
 * `group.deleted` — T066 (US6).
 */
import { useEffect, useState } from 'react'
import type { GroupRealtimeEvent } from '../../api/groups'
import { useRealtime } from '../../chats/hooks/useRealtime'

export type { GroupRealtimeEvent }

/**
 * One group of the realtime «Чаты» slice: `chatId` equals the frame's
 * `groupId` (§1 — the single key of №12–№17/№26 and №27–№35); the
 * optional aggregates are №12-sourced and stay server-authoritative.
 */
export interface GroupListEntry {
  readonly chatId: string
  readonly title?: string
  readonly memberCount?: number
}

export type GroupRealtimeState = readonly GroupListEntry[]

/**
 * Idempotent reducer of one №18 group frame (FR-015: state, not
 * deltas): `group.member.added` materializes an unknown group and is
 * a no-op for a known one — so a duplicate delivery frame returns the
 * SAME reference (React bails out of the re-render) and never
 * duplicates the group. Every other event type — the not-yet-wired
 * §3 stories and unknown future types — passes the state through.
 */
export function reduceGroupEvent(
  state: GroupRealtimeState,
  event: GroupRealtimeEvent,
): GroupRealtimeState {
  if (event.type !== 'group.member.added') {
    return state
  }
  if (state.some((entry) => entry.chatId === event.groupId)) {
    return state
  }
  return [...state, { chatId: event.groupId }]
}

export interface UseGroupRealtimeResult {
  /**
   * Groups of the user's «Чаты» known to realtime: rows materialized
   * by `group.member.added` frames (US1-2) merged over whatever the
   * caller seeded; aggregate fields converge via №12 on (re)connect.
   */
  readonly groups: GroupRealtimeState
}

/**
 * Keeps the group realtime slice live on the shared per-user №18
 * stream (one SSE connection per device/session — useRealtime): every
 * parsed `group.*` frame goes through `reduceGroupEvent`, so the state
 * converges idempotently whatever the at-most-once channel redelivers
 * or loses (lost frames are REST/sync-converged, §5.4 — not replayed).
 */
export function useGroupRealtime(): UseGroupRealtimeResult {
  const realtime = useRealtime()
  const [groups, setGroups] = useState<GroupRealtimeState>([])
  useEffect(() => {
    return realtime.onGroupEvent((event) => {
      setGroups((previous) => reduceGroupEvent(previous, event))
    })
  }, [realtime])
  return { groups }
}
