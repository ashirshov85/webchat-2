/**
 * Group realtime slice (feature 006, T027 + T048; US3; FR-015/FR-010,
 * contracts/realtime-group-events.md §1/§3/§5): the per-user №18
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
 * The US3 slice (T048) adds the FINAL frame of the kicked member:
 * `group.you_removed` removes the group from «Чаты» deterministically,
 * without polling (§5.2), and tombstones its `groupId` — a frame
 * committed before the removal may still be PUBLISHED after it
 * (independent post-commit publications, §1 order race), so every
 * LATER frame of the group is ignored and the group can never
 * resurrect client-side. `group.member.removed`/`group.role.changed`
 * (§3.3/§3.4) carry no list-level aggregates (№12 owns them), so at
 * the list level they are recognized idempotent no-ops; their
 * consumer is the №28 roster view of useGroup (T046a), where the
 * ownership-transfer PAIR of §3.4 frames converges idempotently.
 *
 * The US4 slice (T053) is `group.updated` (§3.1, FR-007): the frame
 * carries the group's NEW metadata as state, so a known group's row
 * adopts the fresh `title` at once — every viewer sees the rename in
 * «Чаты» WITHOUT a reload (US4). The bare aggregates the frame does
 * not describe stay untouched: `memberCount` (and any №12-only field)
 * remains server-authoritative and converges on the next №12 refetch.
 * A frame of a group the list never knew is a no-op — №12 owns row
 * materialization (the FR-019 stranger precedent), and a frame of a
 * TOMBSTONED group is ignored like any other late frame (§1 race).
 * The header half of §3.1 converges through useGroup's own optimistic
 * application (T046a); the №28 description travels only in the frame,
 * not in the list row — №12 carries no description.
 *
 * The US6 slice (T066) is `group.deleted` (§3.5, FR-006): the №30
 * hard-delete broadcast to every FORMER active member removes the row
 * from «Чаты» deterministically and tombstones the `groupId` against
 * the §1 order race exactly like `group.you_removed` — with the
 * tombstone covering a group the list never knew too: the channel is
 * at-most-once, the materializing `group.member.added` frame may have
 * been LOST, and then `group.deleted` is the group's FIRST frame on
 * this device — a later redelivery of the stale frame must not
 * materialize a ghost row of a group №12 will never return (unlike a
 * removed membership, a hard-deleted chat has no re-add revival path).
 *
 * Unknown group `event:` types pass through untouched (§1 forward
 * compatibility).
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
 * The full live state of the hook: the visible groups PLUS the
 * `group.you_removed` tombstones (§1 order race). The tombstones are
 * what makes the final frame FINAL across frames: a redelivered or
 * racing frame of a removed group must find the group dead, so the
 * dead ids travel WITH the state — a bare group array could not
 * remember why its row disappeared.
 */
export interface GroupRealtimeSlice {
  readonly groups: GroupRealtimeState
  readonly removedChatIds: ReadonlySet<string>
}

const NO_REMOVED_CHAT_IDS: ReadonlySet<string> = new Set()

/**
 * Idempotent reducer of one №18 group frame onto the live slice
 * (FR-015: state, not deltas; §1 order race):
 * — `group.member.added` materializes an unknown group, is a no-op
 *   for a known one and is IGNORED for a tombstoned one (a stale
 *   frame published after the kick must not resurrect the group);
 * — `group.member.removed`/`group.role.changed` are recognized
 *   roster/role frames without list-level aggregates — an idempotent
 *   no-op here (same reference, React bails out; the №28 view of
 *   useGroup is their consumer);
 * — `group.updated` (§3.1, FR-007) renames a KNOWN group in place:
 *   the frame's `title` is the described state (US4), everything the
 *   frame does not describe (`memberCount`, №12-only fields) stays
 *   untouched; a duplicate frame with the same title returns the same
 *   reference, an unknown group is a no-op (№12 materializes rows) and
 *   a tombstoned group ignores the frame (§1 race — no resurrection);
 * — `group.you_removed` (§3.6, any `reason`) removes the group from
 *   «Чаты» and tombstones it (§5.2); a redelivered final frame is a
 *   no-op, and a group the state never knew is left untouched;
 * — `group.deleted` (§3.5, FR-006) removes the group from «Чаты» and
 *   tombstones it PERMANENTLY — even a group the list never knew (the
 *   at-most-once channel may have lost the materializing frame, so the
 *   delete frame may be the group's first here; №12 can never return a
 *   hard-deleted chat, so no later frame may resurrect a ghost row);
 *   a redelivered frame of an already-tombstoned-and-dropped group
 *   returns the SAME slice reference.
 * A frame that changes nothing returns the SAME slice reference.
 */
export function reduceGroupSlice(
  slice: GroupRealtimeSlice,
  event: GroupRealtimeEvent,
): GroupRealtimeSlice {
  switch (event.type) {
    case 'group.member.added': {
      if (
        slice.removedChatIds.has(event.groupId) ||
        slice.groups.some((entry) => entry.chatId === event.groupId)
      ) {
        return slice
      }
      return { ...slice, groups: [...slice.groups, { chatId: event.groupId }] }
    }
    case 'group.you_removed': {
      if (slice.removedChatIds.has(event.groupId)) {
        return slice
      }
      if (!slice.groups.some((entry) => entry.chatId === event.groupId)) {
        return slice
      }
      const removedChatIds = new Set(slice.removedChatIds)
      removedChatIds.add(event.groupId)
      return {
        groups: slice.groups.filter((entry) => entry.chatId !== event.groupId),
        removedChatIds,
      }
    }
    case 'group.updated': {
      // §3.1 (FR-007): the frame's title IS the group's new state —
      // rename a known row in place; the frame describes no other
      // aggregate, so `memberCount` and №12-only fields pass through.
      if (slice.removedChatIds.has(event.groupId)) {
        return slice
      }
      const index = slice.groups.findIndex((entry) => entry.chatId === event.groupId)
      const current = index === -1 ? undefined : slice.groups[index]
      if (current === undefined) {
        // №12 owns row materialization (US1-2 precedent).
        return slice
      }
      if (current.title === event.title) {
        return slice
      }
      const groups = [...slice.groups]
      groups[index] = { ...current, title: event.title }
      return { ...slice, groups }
    }
    case 'group.deleted': {
      // §3.5 (FR-006): the hard-delete broadcast to every FORMER
      // active member — the row leaves «Чаты» deterministically and
      // the id is tombstoned FOREVER (№12 can never return the chat,
      // so there is no re-add revival path). The tombstone covers a
      // group the list never knew: the materializing frame may have
      // been lost (at-most-once, §1), making this the group's first
      // frame here — a later stale redelivery must stay dead.
      const known = slice.groups.some((entry) => entry.chatId === event.groupId)
      if (!known && slice.removedChatIds.has(event.groupId)) {
        return slice
      }
      const removedChatIds = new Set(slice.removedChatIds)
      removedChatIds.add(event.groupId)
      return {
        groups: slice.groups.filter((entry) => entry.chatId !== event.groupId),
        removedChatIds,
      }
    }
    case 'group.member.removed':
    case 'group.role.changed':
      // §3.3/§3.4: no list-level aggregates — №12/№28 converge them;
      // recognized so the stories stay explicit and idempotent.
      return slice
    default:
      // Unknown future types (§1) pass through untouched.
      return slice
  }
}

/**
 * Idempotent reducer of one №18 group frame onto the group list
 * alone (the T027/T019 pure projection): `group.member.added`
 * materializes an unknown group and is a no-op for a known one — so a
 * duplicate delivery frame returns the SAME reference (React bails
 * out of the re-render) and never duplicates the group; a
 * `group.you_removed` frame drops the group from the list. Carries no
 * tombstones across calls — the LIVE race handling is
 * `reduceGroupSlice`, which the hook rides.
 */
export function reduceGroupEvent(
  state: GroupRealtimeState,
  event: GroupRealtimeEvent,
): GroupRealtimeState {
  return reduceGroupSlice({ groups: state, removedChatIds: NO_REMOVED_CHAT_IDS }, event).groups
}

export interface UseGroupRealtimeResult {
  /**
   * Groups of the user's «Чаты» known to realtime: rows materialized
   * by `group.member.added` frames (US1-2) merged over whatever the
   * caller seeded, minus the rows dropped by the final
   * `group.you_removed` (US3); aggregate fields converge via №12 on
   * (re)connect.
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
  const [slice, setSlice] = useState<GroupRealtimeSlice>(() => ({
    groups: [],
    removedChatIds: NO_REMOVED_CHAT_IDS,
  }))
  useEffect(() => {
    return realtime.onGroupEvent((event) => {
      setSlice((previous) => reduceGroupSlice(previous, event))
    })
  }, [realtime])
  return { groups: slice.groups }
}
