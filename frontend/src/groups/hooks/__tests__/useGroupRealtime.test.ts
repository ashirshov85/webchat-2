import { describe, expect, it } from 'vitest'
import type { components } from '../../../api/schema'
import type { GroupRealtimeEvent, GroupRealtimeState } from '../useGroupRealtime'
import { reduceGroupEvent } from '../useGroupRealtime'

/**
 * Group events reducer — the US1 starting slice (T019 → T027;
 * FR-015, realtime-group-events.md §1/§3.2): `group.member.added`
 * materializes a group the user was added to (the added member sees
 * the group in «Чаты» at once), and the channel being at-most-once
 * PLUS the handler being idempotent means a DUPLICATE frame must
 * never create a duplicate group — the reducer applies STATE (the
 * set of known groups keyed by `chatId = groupId`), never deltas.
 * Unknown group event types pass through untouched (forward
 * compatibility, §1); the US3/US4/US6 events join the reducer with
 * their own stories (T048/T053/T066).
 */

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
