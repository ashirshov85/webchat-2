import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { GroupRealtimeEvent, GroupView } from '../../../api/groups'
import { useGroup } from '../useGroup'

/**
 * The №28 group view of US3 (T041 → T046a; realtime-group-events.md §5):
 * the hook loads the GroupView once per chatId and then keeps it live
 * with OPTIMISTIC updates over the №18 `group.*` frames — the channel
 * is at-most-once and frames may be redelivered, so every frame is
 * applied as the DESCRIBED STATE, never a delta (FR-015): a duplicate
 * `group.member.added`/`group.member.removed`/`group.role.changed`
 * frame must leave the view reference-identical, and the ownership
 * transfer pair of §3.4 frames (new owner; former owner → admin)
 * converges to exactly one owner idempotently. Frames of OTHER groups
 * (§1: `groupId = chatId` is the single key) pass by untouched, and
 * the №28 aggregates stay refetch-only — `reload()` re-runs №28 for
 * the error/retry and (re)connect convergence paths.
 */

type PublicUser = components['schemas']['PublicUser']

const api = vi.hoisted(() => ({ getGroup: vi.fn() }))

vi.mock('../../../api/groups', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/groups')>()),
  getGroup: api.getGroup,
}))

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

vi.mock('../../chats/hooks/useRealtime', () => ({
  useRealtime: () => realtime.stream,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'
const DAVE = '44444444-4444-4444-4444-444444444444'
const ACTOR = '55555555-5555-5555-5555-555555555555'
const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'
const OTHER_GROUP = '9a2c9a2c-9a2c-49a2-89a2-c9a2c9a2c9a2'

function user(id: string, username: string): PublicUser {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active',
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function groupView(overrides: Partial<GroupView> = {}): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: 'рабочая группа',
    myRole: 'owner',
    members: [
      { user: user(ME, 'alice'), role: 'owner', joinedAt: '2026-09-20T12:00:00.000Z' },
      { user: user(BOB, 'bob'), role: 'admin', joinedAt: '2026-09-20T12:00:01.000Z' },
      { user: user(CAROL, 'carol'), role: 'member', joinedAt: '2026-09-20T12:00:02.000Z' },
    ],
    ...overrides,
  }
}

function memberAdded(groupId: string, addedId: string): GroupRealtimeEvent {
  return { type: 'group.member.added', groupId, user: user(addedId, 'dave'), actorId: ACTOR }
}

function memberRemoved(groupId: string, userId: string): GroupRealtimeEvent {
  return { type: 'group.member.removed', groupId, userId, actorId: ACTOR }
}

function roleChanged(
  groupId: string,
  userId: string,
  role: 'owner' | 'admin' | 'member',
): GroupRealtimeEvent {
  return { type: 'group.role.changed', groupId, userId, role, actorId: ACTOR }
}

function groupUpdated(
  groupId: string,
  title: string,
  description: string | null,
): GroupRealtimeEvent {
  return { type: 'group.updated', groupId, title, description, actorId: ACTOR }
}

function emitGroupEvent(event: GroupRealtimeEvent) {
  act(() => {
    for (const listener of realtime.groupListeners) {
      listener(event)
    }
  })
}

const mounted: Array<{ unmount(): void }> = []

function mountGroup(chatId: string = GROUP_ID) {
  const rendered = renderHook((id: string) => useGroup(id), { initialProps: chatId })
  mounted.push(rendered)
  return rendered
}

async function mountReady(view: GroupView = groupView()) {
  api.getGroup.mockResolvedValueOnce(view)
  const rendered = mountGroup()
  await waitFor(() => {
    expect(rendered.result.current.status).toBe('ready')
  })
  return rendered
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  for (const rendered of mounted.splice(0)) {
    rendered.unmount()
  }
  vi.clearAllMocks()
})

describe('useGroup №28 load (T046a)', () => {
  it('fetches №28 once and exposes the ready state', async () => {
    const view = groupView()
    api.getGroup.mockResolvedValueOnce(view)

    const { result } = mountGroup()

    expect(result.current.status).toBe('loading')
    await waitFor(() => {
      expect(result.current.status).toBe('ready')
    })
    expect(api.getGroup).toHaveBeenCalledWith(GROUP_ID)
    expect(api.getGroup).toHaveBeenCalledTimes(1)
    expect(result.current.group).toEqual(view)
    expect(result.current.error).toBeNull()
  })

  it('exposes the error and keeps the group null when №28 fails (404 group_not_found)', async () => {
    const problem = { status: 404, title: 'Not Found', errors: { chatId: ['group_not_found'] } }
    api.getGroup.mockRejectedValueOnce(problem)

    const { result } = mountGroup()

    await waitFor(() => {
      expect(result.current.status).toBe('error')
    })
    expect(result.current.error).toEqual(problem)
    expect(result.current.group).toBeNull()
  })

  it('reload() re-runs the №28 fetch (error retry / reconnect convergence)', async () => {
    const { result } = await mountReady()

    api.getGroup.mockResolvedValueOnce(groupView())
    act(() => {
      result.current.reload()
    })
    await waitFor(() => {
      expect(api.getGroup).toHaveBeenCalledTimes(2)
    })
  })
})

describe('useGroup optimistic roster/metadata updates (FR-015: state, not deltas)', () => {
  it('applies group.updated metadata without a refetch (§3.1, FR-007)', async () => {
    const { result } = await mountReady()

    emitGroupEvent(groupUpdated(GROUP_ID, 'Новое название', null))

    expect(result.current.group?.title).toBe('Новое название')
    expect(result.current.group?.description).toBeNull()
    expect(api.getGroup).toHaveBeenCalledTimes(1)
  })

  it('appends a group.member.added member with the member role; a duplicate frame is a no-op (§3.2)', async () => {
    const { result } = await mountReady()

    emitGroupEvent(memberAdded(GROUP_ID, DAVE))

    const afterFirst = result.current.group
    expect(afterFirst?.members.map((m) => m.user.id)).toEqual([ME, BOB, CAROL, DAVE])
    expect(afterFirst?.members.find((m) => m.user.id === DAVE)?.role).toBe('member')

    emitGroupEvent(memberAdded(GROUP_ID, DAVE))
    expect(result.current.group).toBe(afterFirst)
    expect(result.current.group?.members).toHaveLength(4)
  })

  it('drops the member on group.member.removed; a duplicate frame keeps the reference (§3.3)', async () => {
    const { result } = await mountReady()

    emitGroupEvent(memberRemoved(GROUP_ID, CAROL))

    const afterFirst = result.current.group
    expect(afterFirst?.members.map((m) => m.user.id)).toEqual([ME, BOB])

    emitGroupEvent(memberRemoved(GROUP_ID, CAROL))
    expect(result.current.group).toBe(afterFirst)
  })

  it('applies a single group.role.changed frame (§3.4, №34 grant/revoke)', async () => {
    const { result } = await mountReady()

    emitGroupEvent(roleChanged(GROUP_ID, CAROL, 'admin'))
    expect(result.current.group?.members.find((m) => m.user.id === CAROL)?.role).toBe('admin')

    emitGroupEvent(roleChanged(GROUP_ID, CAROL, 'member'))
    expect(result.current.group?.members.find((m) => m.user.id === CAROL)?.role).toBe('member')
  })

  it('applies the ownership-transfer pair of frames idempotently — exactly one owner, the former owner becomes admin (№35, §3.4)', async () => {
    const { result } = await mountReady()

    emitGroupEvent(roleChanged(GROUP_ID, BOB, 'owner'))
    emitGroupEvent(roleChanged(GROUP_ID, ME, 'admin'))

    const afterPair = result.current.group
    const owners = afterPair?.members.filter((m) => m.role === 'owner') ?? []
    expect(owners).toHaveLength(1)
    expect(owners[0]?.user.id).toBe(BOB)
    expect(afterPair?.members.find((m) => m.user.id === ME)?.role).toBe('admin')

    // A redelivered pair of frames must not distort the state (FR-015)
    emitGroupEvent(roleChanged(GROUP_ID, BOB, 'owner'))
    emitGroupEvent(roleChanged(GROUP_ID, ME, 'admin'))
    expect(result.current.group).toBe(afterPair)
  })

  it('ignores frames of other groups — groupId is the single key (§1)', async () => {
    const { result } = await mountReady()
    const before = result.current.group

    emitGroupEvent(memberAdded(OTHER_GROUP, DAVE))
    emitGroupEvent(groupUpdated(OTHER_GROUP, 'Чужая группа', null))

    expect(result.current.group).toBe(before)
    expect(api.getGroup).toHaveBeenCalledTimes(1)
  })
})
