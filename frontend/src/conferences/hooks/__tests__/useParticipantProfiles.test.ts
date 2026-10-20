import { cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiProblem } from '../../../api/auth'
import type { ChatView } from '../../../api/chats'
import type { GroupView } from '../../../api/groups'

/**
 * 009, T073 — догрузка профилей участников чата-владельца
 * СУЩЕСТВУЮЩИМИ API (контракт/backend не меняются): группа — №27
 * getGroup (ростер №28 GroupMember.user несёт alias/displayName/
 * username — ОДИН запрос на всех, включая вызывающего); direct —
 * №13 getChat (peer + peerAlias) + №10 getCurrentUser (свой профиль);
 * группа отличается единым 404 group_not_found №28 (direct-chat id).
 * Любой сбой — пустая Map (фолбэк инициалов из participant.name,
 * паритет FR-024 008 — без ошибок UI); рефетч-цикла нет: один запрос
 * на chatId.
 */

const api = vi.hoisted(() => ({
  getGroup: vi.fn(),
  getChat: vi.fn(),
  getCurrentUser: vi.fn(),
}))

vi.mock('../../../api/groups', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/groups')>()),
  getGroup: api.getGroup,
}))

vi.mock('../../../api/chats', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/chats')>()),
  getChat: api.getChat,
}))

vi.mock('../../../api/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../api/auth')>()),
  getCurrentUser: api.getCurrentUser,
}))

import { useParticipantProfiles } from '../useParticipantProfiles'

const ME = '11111111-1111-4111-8111-111111111111'
const BOB = '22222222-2222-4222-8222-222222222222'
const CAROL = '33333333-3333-4333-8333-333333333333'

function groupMember(
  id: string,
  username: string,
  user: { displayName?: string; alias?: string } = {},
): GroupView['members'][number] {
  return {
    user: {
      id,
      username,
      email: `${username}@example.com`,
      status: 'active',
      createdAt: '2026-10-01T00:00:00.000Z',
      ...user,
    },
    role: 'member',
    joinedAt: '2026-10-01T00:00:00.000Z',
  }
}

function groupView(members: GroupView['members']): GroupView {
  return { chatId: 'chat-1', title: 'Проект', description: null, myRole: 'owner', members }
}

function directChat(peer: ChatView['peer'], peerAlias?: string): ChatView {
  return {
    chatId: 'chat-1',
    type: 'direct',
    peer,
    peerAlias,
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  }
}

function problem(status: number, code: string, field: string): ApiProblem {
  return { title: `HTTP ${status}`, status, errors: { [field]: [code] } }
}

describe('useParticipantProfiles (009 T073)', () => {
  beforeEach(() => {
    api.getGroup.mockReset()
    api.getChat.mockReset()
    api.getCurrentUser.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('группа — Map из №28-ростера одним запросом №27 (alias/displayName/username каждого)', async () => {
    api.getGroup.mockResolvedValue(
      groupView([
        groupMember(ME, 'alice', { displayName: 'Алиса' }),
        groupMember(BOB, 'bob', { alias: 'Бобик' }),
        groupMember(CAROL, 'carol'),
      ]),
    )

    const { result } = renderHook(() => useParticipantProfiles('chat-1'))

    await waitFor(() => {
      expect(result.current.size).toBe(3)
    })
    expect(result.current.get(ME)).toEqual({
      username: 'alice',
      displayName: 'Алиса',
      alias: undefined,
    })
    expect(result.current.get(BOB)).toEqual({
      username: 'bob',
      displayName: undefined,
      alias: 'Бобик',
    })
    expect(result.current.get(CAROL)).toEqual({
      username: 'carol',
      displayName: undefined,
      alias: undefined,
    })
    // ОДИН запрос на всех; direct-ветка не трогалась
    expect(api.getGroup).toHaveBeenCalledTimes(1)
    expect(api.getGroup).toHaveBeenCalledWith('chat-1')
    expect(api.getChat).not.toHaveBeenCalled()
    expect(api.getCurrentUser).not.toHaveBeenCalled()
  })

  it('direct — peer №13 (с peerAlias) + собственный профиль №10 после единого 404 group_not_found', async () => {
    api.getGroup.mockRejectedValue(problem(404, 'group_not_found', 'group'))
    api.getChat.mockResolvedValue(
      directChat(
        {
          id: BOB,
          username: 'bob',
          email: 'bob@example.com',
          status: 'active',
          createdAt: '2026-10-01T00:00:00.000Z',
          displayName: 'Борис',
        },
        'Бобик',
      ),
    )
    api.getCurrentUser.mockResolvedValue({
      id: ME,
      username: 'alice',
      email: 'alice@example.com',
      status: 'active',
      createdAt: '2026-10-01T00:00:00.000Z',
      displayName: 'Алиса',
      hasPassword: true,
    })

    const { result } = renderHook(() => useParticipantProfiles('chat-1'))

    await waitFor(() => {
      expect(result.current.size).toBe(2)
    })
    expect(result.current.get(ME)).toEqual({
      username: 'alice',
      displayName: 'Алиса',
      alias: undefined,
    })
    expect(result.current.get(BOB)).toEqual({
      username: 'bob',
      displayName: 'Борис',
      alias: 'Бобик',
    })
    expect(api.getChat).toHaveBeenCalledWith('chat-1')
    expect(api.getCurrentUser).toHaveBeenCalledTimes(1)
  })

  it('сбой догрузки — пустая Map без ошибок UI (транспортный отказ №27)', async () => {
    api.getGroup.mockRejectedValue(new Error('network'))

    const { result } = renderHook(() => useParticipantProfiles('chat-1'))

    await waitFor(() => {
      expect(api.getGroup).toHaveBeenCalledTimes(1)
    })
    expect(result.current.size).toBe(0)
    expect(api.getChat).not.toHaveBeenCalled()
  })

  it('сбой direct-ветки — пустая Map (№13 отклонён после 404 группы)', async () => {
    api.getGroup.mockRejectedValue(problem(404, 'group_not_found', 'group'))
    api.getChat.mockRejectedValue(new Error('network'))

    const { result } = renderHook(() => useParticipantProfiles('chat-1'))

    await waitFor(() => {
      expect(api.getChat).toHaveBeenCalledTimes(1)
    })
    expect(result.current.size).toBe(0)
  })

  it('chatId=null (№45 ещё не загружен) — запросов нет, Map пуста', () => {
    const { result } = renderHook(() => useParticipantProfiles(null))

    expect(result.current.size).toBe(0)
    expect(api.getGroup).not.toHaveBeenCalled()
    expect(api.getChat).not.toHaveBeenCalled()
    expect(api.getCurrentUser).not.toHaveBeenCalled()
  })

  it('кэш на монтаж: стабильный chatId не перезапрашивает (нет рефетч-цикла)', async () => {
    api.getGroup.mockResolvedValue(groupView([groupMember(ME, 'alice')]))

    const { result, rerender } = renderHook(() => useParticipantProfiles('chat-1'))
    await waitFor(() => {
      expect(result.current.size).toBe(1)
    })

    rerender()
    rerender()

    expect(api.getGroup).toHaveBeenCalledTimes(1)
  })
})
