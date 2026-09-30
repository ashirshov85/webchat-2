import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { GroupRealtimeEvent, GroupRealtimeState } from '../useGroupRealtime'
import { reduceGroupEvent, useGroupRealtime } from '../useGroupRealtime'

/**
 * Group events reducer — the US1 starting slice (T019 → T027;
 * FR-015, realtime-group-events.md §1/§3.2): `group.member.added`
 * materializes a group the user was added to (the added member sees
 * the group in «Чаты» at once), and the channel being at-most-once
 * PLUS the handler being idempotent means a DUPLICATE frame must
 * never create a duplicate group — the reducer applies STATE (the
 * set of known groups keyed by `chatId = groupId`), never deltas.
 * Unknown group event types pass through untouched (forward
 * compatibility, §1).
 *
 * The US3 slice (T041 → T048) adds the FINAL frame of the kicked
 * member: `group.you_removed` removes the group from «Чаты»
 * deterministically, without polling (§5.2), and every LATER frame
 * of that group — the §1 post-commit race may redeliver a message or
 * roster frame committed before the kick — must be ignored, so the
 * group can never resurrect client-side. `group.role.changed`/
 * `group.member.removed` frames carry no list-level aggregates (the
 * №12 refetch owns them), so their consumer is the №28 view of
 * useGroup (T041 useGroup.test); US4/US6 events join with T053/T066.
 *
 * The US4 slice (T050 → T053) is `group.updated` (§3.1, FR-007): the
 * frame describes the group's NEW metadata as state, so a known
 * group's `title` in «Чаты» follows the frame at once — every
 * viewer sees the rename in the list WITHOUT a reload; the №28
 * header half (the open window's title) converges through useGroup's
 * own optimistic application (T041 useGroup.test §3.1) and the
 * remaining aggregates (`memberCount`) stay №12-owned.
 */

const realtime = vi.hoisted(() => {
  const groupListeners = new Set<(event: GroupRealtimeEvent) => void>()
  return {
    groupListeners,
    stream: {
      onGroupEvent(listener: (event: GroupRealtimeEvent) => void) {
        groupListeners.add(listener)
        return () => {
          groupListeners.delete(listener)
        }
      },
    },
  }
})

vi.mock('../../../chats/hooks/useRealtime', () => ({
  useRealtime: () => realtime.stream,
}))

type PublicUser = components['schemas']['PublicUser']

const ME = '11111111-1111-1111-1111-111111111111'
const ACTOR = '55555555-5555-5555-5555-555555555555'
const CAROL = '44444444-4444-4444-4444-444444444444'
const GROUP_A = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'
const GROUP_B = '9a2c9a2c-9a2c-49a2-89a2-c9a2c9a2c9a2'

function user(id: string, username: string): PublicUser {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active',
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function memberAdded(groupId: string, addedId: string): GroupRealtimeEvent {
  return {
    type: 'group.member.added',
    groupId,
    user: user(addedId, 'carol'),
    actorId: ACTOR,
  }
}

describe('reduceGroupEvent — group.member.added (US1 slice, §3.2)', () => {
  it('materializes a group the user was added to', () => {
    const next = reduceGroupEvent([], memberAdded(GROUP_A, CAROL))

    expect(next).toHaveLength(1)
    expect(next[0]?.chatId).toBe(GROUP_A)
  })

  it('a duplicate frame never creates a duplicate group (FR-015)', () => {
    const first = reduceGroupEvent([], memberAdded(GROUP_A, CAROL))
    expect(first).toHaveLength(1)

    const second = reduceGroupEvent(first, memberAdded(GROUP_A, CAROL))
    expect(second).toBe(first)
  })

  it('keeps groups keyed by chatId: another group is a separate entry', () => {
    const state = reduceGroupEvent([], memberAdded(GROUP_A, CAROL))
    const next = reduceGroupEvent(state, memberAdded(GROUP_B, ME))

    expect(next.map((entry) => entry.chatId)).toEqual([GROUP_A, GROUP_B])
  })

  it('a frame for a group already known from №12 keeps the aggregate fields intact', () => {
    const seeded: GroupRealtimeState = [{ chatId: GROUP_A, title: 'Проект Альфа', memberCount: 3 }]

    expect(reduceGroupEvent(seeded, memberAdded(GROUP_A, CAROL))).toBe(seeded)
  })

  it('passes unknown group event types through untouched (§1 forward compatibility)', () => {
    const state = reduceGroupEvent([], memberAdded(GROUP_A, CAROL))
    const unknownEvent = {
      type: 'group.future.event',
      groupId: GROUP_A,
    } as unknown as GroupRealtimeEvent

    expect(reduceGroupEvent(state, unknownEvent)).toBe(state)
  })
})

function youRemoved(groupId: string, reason: 'kicked' | 'left'): GroupRealtimeEvent {
  return { type: 'group.you_removed', groupId, reason }
}

function groupUpdated(
  groupId: string,
  title: string,
  description: string | null,
): GroupRealtimeEvent {
  return { type: 'group.updated', groupId, title, description, actorId: ACTOR }
}

function roleChanged(
  groupId: string,
  userId: string,
  role: 'owner' | 'admin' | 'member',
): GroupRealtimeEvent {
  return { type: 'group.role.changed', groupId, userId, role, actorId: ACTOR }
}

function emitGroupEvent(event: GroupRealtimeEvent) {
  act(() => {
    for (const listener of realtime.groupListeners) {
      listener(event)
    }
  })
}

const mounted: Array<{ unmount(): void }> = []

function mountGroupRealtime() {
  const rendered = renderHook(() => useGroupRealtime())
  mounted.push(rendered)
  return rendered
}

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.clearAllMocks()
})

describe('useGroupRealtime — group.you_removed (US3 slice → T048, §1/§3.6/§5.2)', () => {
  it('removes the group from «Чаты» deterministically, without polling', () => {
    const { result } = mountGroupRealtime()

    emitGroupEvent(memberAdded(GROUP_A, CAROL))
    emitGroupEvent(memberAdded(GROUP_B, ME))
    expect(result.current.groups.map((entry) => entry.chatId)).toEqual([GROUP_A, GROUP_B])

    emitGroupEvent(youRemoved(GROUP_A, 'kicked'))
    expect(result.current.groups.map((entry) => entry.chatId)).toEqual([GROUP_B])

    // A redelivered final frame must not distort anything (FR-015)
    emitGroupEvent(youRemoved(GROUP_A, 'kicked'))
    expect(result.current.groups.map((entry) => entry.chatId)).toEqual([GROUP_B])
  })

  it('never resurrects the group: later frames of the removed group are ignored (race §1)', () => {
    const { result } = mountGroupRealtime()

    emitGroupEvent(memberAdded(GROUP_A, CAROL))
    emitGroupEvent(youRemoved(GROUP_A, 'kicked'))

    emitGroupEvent(memberAdded(GROUP_A, CAROL))
    emitGroupEvent(roleChanged(GROUP_A, ME, 'admin'))
    expect(result.current.groups).toEqual([])
  })

  it('is a no-op for a group the state never knew', () => {
    const { result } = mountGroupRealtime()

    emitGroupEvent(memberAdded(GROUP_B, ME))
    const before = result.current.groups

    emitGroupEvent(youRemoved(GROUP_A, 'kicked'))
    expect(result.current.groups).toBe(before)
  })
})

describe('reduceGroupEvent — group.updated (US4 slice → T053, §3.1/FR-007)', () => {
  it('updates the title of a known group — state, not delta; memberCount stays №12-owned', () => {
    const seeded: GroupRealtimeState = [{ chatId: GROUP_A, title: 'Проект Альфа', memberCount: 3 }]

    const next = reduceGroupEvent(seeded, groupUpdated(GROUP_A, 'Новое название', null))

    expect(next).toEqual([{ chatId: GROUP_A, title: 'Новое название', memberCount: 3 }])
  })

  it('a duplicate frame with the same title returns the SAME reference (FR-015)', () => {
    const seeded: GroupRealtimeState = [
      { chatId: GROUP_A, title: 'Новое название', memberCount: 3 },
    ]

    expect(reduceGroupEvent(seeded, groupUpdated(GROUP_A, 'Новое название', null))).toBe(seeded)
  })

  it('fills the title of a bare member.added row — the frame describes the state', () => {
    const bare = reduceGroupEvent([], memberAdded(GROUP_A, CAROL))
    expect(bare[0]?.title).toBeUndefined()

    const next = reduceGroupEvent(bare, groupUpdated(GROUP_A, 'Новое название', 'рабочая группа'))

    expect(next).toEqual([{ chatId: GROUP_A, title: 'Новое название' }])
  })

  it('is a no-op for a group the list never knew — №12 owns row materialization', () => {
    const seeded: GroupRealtimeState = [{ chatId: GROUP_B, title: 'Проект Бета' }]

    expect(reduceGroupEvent(seeded, groupUpdated(GROUP_A, 'Новое название', null))).toBe(seeded)
  })
})

describe('useGroupRealtime — group.updated (US4 slice → T053: the «Чаты» list half)', () => {
  it('renames the group in the live list without a refetch', () => {
    const { result } = mountGroupRealtime()

    emitGroupEvent(memberAdded(GROUP_A, CAROL))
    emitGroupEvent(groupUpdated(GROUP_A, 'Новое название', null))

    expect(result.current.groups).toEqual([{ chatId: GROUP_A, title: 'Новое название' }])
  })

  it('a group.updated frame after group.you_removed cannot resurrect the group (race §1)', () => {
    const { result } = mountGroupRealtime()

    emitGroupEvent(memberAdded(GROUP_A, CAROL))
    emitGroupEvent(youRemoved(GROUP_A, 'kicked'))

    emitGroupEvent(groupUpdated(GROUP_A, 'Новое название', null))
    expect(result.current.groups).toEqual([])
  })
})
