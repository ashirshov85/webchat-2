/**
 * №28 GroupView state (feature 006, T046a; US3; FR-015,
 * contracts/realtime-group-events.md §1/§3/§5): the group window's
 * card — header metadata + roster with roles — is served by ONE №28
 * `GET /groups/{chatId}` per open and then stays live with OPTIMISTIC
 * `group.*` frame application, the same shared №18 stream the «Чаты»
 * slice rides in useGroupRealtime (T027) — one multiplexed per-user
 * connection (useRealtime).
 *
 * The channel is at-most-once AND frames may be redelivered, so every
 * frame is applied as the DESCRIBED STATE, never a delta (§5.1):
 * a duplicate `group.member.added`/`group.member.removed`/
 * `group.role.changed` frame leaves the view REFERENCE-IDENTICAL
 * (React bails out of the re-render), and the ownership-transfer pair
 * of §3.4 frames (new owner; former owner → admin) converges to
 * exactly one owner idempotently. Frames of OTHER groups pass by
 * untouched — `groupId` is the single key (§1). A frame racing the №28
 * answer (the view is still null) is dropped: the answer is the newer
 * server snapshot, and whatever the channel missed converges through
 * `reload()` — the error/retry and (re)connect path (§5.4) — and the
 * №12/№26 aggregates.
 *
 * The №28 snapshot stays refetch-only: `reload()` re-runs №28 (the №31
 * addMembers success converges the roster through it), and the frame
 * application fills only what a frame DESCRIBES — a member materialized
 * from a `group.member.added` frame carries the arrival moment as the
 * optimistic `joinedAt` (the frame has no timestamp), converging with
 * the next №28. `group.you_removed`/`group.deleted` belong to the
 * US5/US6 client rules (T058/T066) and pass through untouched here.
 */
import { useCallback, useEffect, useState } from 'react'
import { getGroup } from '../../api/groups'
import type { GroupMember, GroupRealtimeEvent, GroupView } from '../../api/groups'
import { useRealtime } from '../../chats/hooks/useRealtime'

export type GroupStatus = 'idle' | 'loading' | 'ready' | 'error'

export interface UseGroupResult {
  /**
   * The №28 GroupView with the optimistic `group.*` frames applied;
   * null while loading, after a failure, or when no group is open
   * (`chatId === null` → `status:'idle'`).
   */
  readonly group: GroupView | null
  readonly status: GroupStatus
  readonly error: unknown
  /** Re-runs the №28 fetch (error retry, №31 convergence, reconnect). */
  readonly reload: () => void
}

type GroupUpdatedFrame = Extract<GroupRealtimeEvent, { type: 'group.updated' }>
type GroupMemberAddedFrame = Extract<GroupRealtimeEvent, { type: 'group.member.added' }>
type GroupMemberRemovedFrame = Extract<GroupRealtimeEvent, { type: 'group.member.removed' }>
type GroupRoleChangedFrame = Extract<GroupRealtimeEvent, { type: 'group.role.changed' }>

/** §3.1 metadata: title/description are the frame's described state. */
function applyGroupUpdated(view: GroupView, event: GroupUpdatedFrame): GroupView {
  if (view.title === event.title && view.description === event.description) {
    return view
  }
  return { ...view, title: event.title, description: event.description }
}

/** §3.2 roster growth: the added user joins as a plain member (FR-002). */
function applyMemberAdded(view: GroupView, event: GroupMemberAddedFrame): GroupView {
  if (view.members.some((member) => member.user.id === event.user.id)) {
    return view
  }
  const added: GroupMember = {
    user: event.user,
    role: 'member',
    joinedAt: new Date().toISOString(),
  }
  return { ...view, members: [...view.members, added] }
}

/** §3.3 roster shrink: the kicked/left user leaves the roster. */
function applyMemberRemoved(view: GroupView, event: GroupMemberRemovedFrame): GroupView {
  if (!view.members.some((member) => member.user.id === event.userId)) {
    return view
  }
  return { ...view, members: view.members.filter((member) => member.user.id !== event.userId) }
}

/** §3.4 role change: the frame's role IS the member's new role. */
function applyRoleChanged(view: GroupView, event: GroupRoleChangedFrame): GroupView {
  const current = view.members.find((member) => member.user.id === event.userId)
  if (current === undefined || current.role === event.role) {
    return view
  }
  return {
    ...view,
    members: view.members.map((member) =>
      member.user.id === event.userId ? { ...member, role: event.role } : member,
    ),
  }
}

/**
 * Idempotent application of one №18 group frame onto a №28 GroupView
 * (FR-015: state, not deltas): returns the SAME reference when the
 * frame changes nothing (duplicate delivery, foreign `groupId`, a
 * role the member already has), so React bails out of re-renders.
 */
export function applyGroupEventToView(view: GroupView, event: GroupRealtimeEvent): GroupView {
  if (event.groupId !== view.chatId) {
    return view
  }
  switch (event.type) {
    case 'group.updated':
      return applyGroupUpdated(view, event)
    case 'group.member.added':
      return applyMemberAdded(view, event)
    case 'group.member.removed':
      return applyMemberRemoved(view, event)
    case 'group.role.changed':
      return applyRoleChanged(view, event)
    default:
      // `group.deleted`/`group.you_removed` — the US5/US6 client
      // rules (T058/T066); the view itself stays untouched here.
      return view
  }
}

export function useGroup(chatId: string | null): UseGroupResult {
  const realtime = useRealtime()
  const [group, setGroup] = useState<GroupView | null>(null)
  const [status, setStatus] = useState<GroupStatus>(() => (chatId === null ? 'idle' : 'loading'))
  const [error, setError] = useState<unknown>(null)
  const [refreshCount, setRefreshCount] = useState(0)

  // A chatId switch (another group, a direct dialog, no window) starts
  // a fresh №28 lifecycle: the previous group's card must never flash
  // in the new window, and its frames stop applying the moment the
  // view is cleared.
  useEffect(() => {
    if (chatId === null) {
      setGroup(null)
      setStatus('idle')
      setError(null)
      return
    }
    setGroup(null)
    setStatus('loading')
    setError(null)
  }, [chatId])

  useEffect(() => {
    if (chatId === null) {
      return
    }
    let cancelled = false
    void (async () => {
      try {
        const view = await getGroup(chatId)
        if (cancelled) {
          return
        }
        setError(null)
        setGroup(view)
        setStatus('ready')
      } catch (cause) {
        if (cancelled) {
          return
        }
        setError(cause)
        setStatus('error')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [chatId, refreshCount])

  useEffect(() => {
    return realtime.onGroupEvent((event) => {
      setGroup((previous) =>
        previous === null ? previous : applyGroupEventToView(previous, event),
      )
    })
  }, [realtime])

  const reload = useCallback(() => {
    setRefreshCount((count) => count + 1)
  }, [])

  return { group, status, error, reload }
}
