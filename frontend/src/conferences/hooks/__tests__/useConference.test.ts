import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiProblem } from '../../../api/auth'
import type { ConferenceView, JoinTicket, MeView } from '../../api/conferences'
import {
  activeConferenceId,
  bindConferenceDisconnect,
  leaveActiveConference,
  occupyConferenceSlot,
  useConference,
} from '../useConference'

/**
 * 009, US1, T026 — useConference: пакет №45+№46, join №47 → JoinTicket
 * и слот «одна конференция на вкладку» (Q10=A): join второй
 * конференции той же вкладкой корректно покидает первую (disconnect
 * предыдущей ДО занятия нового слота — остальные видят выход);
 * `conference_ended` — терминальный статус по обоим REST-лицам
 * (№47 409 здесь, №45 status='ended' — на экране); `refreshTicket`
 * повторяет №47 при реконнекте с истёкшим токеном (FR-014), не
 * меняя joinStatus; leave освобождает слот и разрешает повторный
 * join.
 */

const api = vi.hoisted(() => ({
  getConference: vi.fn(),
  getConferenceMe: vi.fn(),
  joinConference: vi.fn(),
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  getConference: api.getConference,
  getConferenceMe: api.getConferenceMe,
  joinConference: api.joinConference,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const CONFERENCE_A = 'aaaaaaaa-0000-4000-8000-000000000001'
const CONFERENCE_B = 'bbbbbbbb-0000-4000-8000-000000000002'

function conferenceView(overrides: Partial<ConferenceView> = {}): ConferenceView {
  return {
    id: CONFERENCE_A,
    chatId: 'chat-1',
    status: 'active',
    mode: 'video',
    createdBy: ME,
    createdAt: '2026-10-17T11:59:00.000Z',
    maxParticipants: 16,
    activeParticipants: 1,
    ...overrides,
  }
}

function meView(overrides: Partial<MeView> = {}): MeView {
  return {
    role: 'moderator',
    micRestricted: false,
    cameraRestricted: false,
    screenRestricted: false,
    speechRestricted: false,
    ...overrides,
  }
}

function joinTicket(token: string, overrides: Partial<JoinTicket> = {}): JoinTicket {
  return {
    token,
    wsUrl: 'wss://media.example.com',
    identity: `u:${ME}:conn-1`,
    name: 'alice',
    expiresAt: '2026-10-17T12:15:00.000Z',
    ...overrides,
  }
}

function problem(status: number, code: string): ApiProblem {
  return { title: `HTTP ${status}`, status, errors: { conference: [code] } }
}

describe('useConference (009 US1 T026)', () => {
  beforeEach(() => {
    api.getConference.mockReset()
    api.getConferenceMe.mockReset()
    api.joinConference.mockReset()
  })

  afterEach(() => {
    cleanup()
    act(() => leaveActiveConference())
  })

  it('loads the №45+№46 bundle and exposes view/me with ready status', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValue(meView({ role: 'participant' }))

    const { result } = renderHook(() => useConference(CONFERENCE_A))

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.conference?.id).toBe(CONFERENCE_A)
    expect(result.current.me?.role).toBe('participant')
    expect(result.current.ended).toBe(false)
    expect(api.getConference).toHaveBeenCalledWith(CONFERENCE_A)
    expect(api.getConferenceMe).toHaveBeenCalledWith(CONFERENCE_A)
  })

  it('surfaces a bundle failure as error and reload re-runs №45+№46', async () => {
    api.getConference
      .mockRejectedValueOnce(problem(404, 'conference_not_found'))
      .mockResolvedValueOnce(conferenceView())
    api.getConferenceMe
      .mockRejectedValueOnce(problem(404, 'conference_not_found'))
      .mockResolvedValueOnce(meView())

    const { result } = renderHook(() => useConference(CONFERENCE_A))

    await waitFor(() => expect(result.current.status).toBe('error'))
    expect(result.current.conference).toBeNull()
    expect(result.current.error).toEqual(problem(404, 'conference_not_found'))

    act(() => result.current.reload())

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(api.getConference).toHaveBeenCalledTimes(2)
  })

  it('join №47 stores the JoinTicket and occupies the tab slot exactly once', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValue(meView())
    const ticket = joinTicket('jwt-1')
    api.joinConference.mockResolvedValue(ticket)

    const { result } = renderHook(() => useConference(CONFERENCE_A))

    act(() => result.current.join())
    act(() => result.current.join())

    await waitFor(() => expect(result.current.joinStatus).toBe('joined'))
    expect(result.current.ticket).toEqual(ticket)
    expect(activeConferenceId()).toBe(CONFERENCE_A)
    expect(api.joinConference).toHaveBeenCalledTimes(1)
  })

  it('Q10=A: joining a second conference leaves the first one gracefully', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValue(meView())
    api.joinConference.mockResolvedValueOnce(joinTicket('jwt-a'))

    const first = renderHook(() => useConference(CONFERENCE_A))
    await waitFor(() => expect(first.result.current.status).toBe('ready'))
    act(() => first.result.current.join())
    await waitFor(() => expect(first.result.current.joinStatus).toBe('joined'))
    const disconnectFirst = vi.fn()
    bindConferenceDisconnect(CONFERENCE_A, disconnectFirst)

    api.joinConference.mockResolvedValueOnce(joinTicket('jwt-b'))
    const second = renderHook(() => useConference(CONFERENCE_B))
    await waitFor(() => expect(second.result.current.status).toBe('ready'))
    act(() => second.result.current.join())

    await waitFor(() => expect(second.result.current.joinStatus).toBe('joined'))
    expect(disconnectFirst).toHaveBeenCalledTimes(1)
    expect(activeConferenceId()).toBe(CONFERENCE_B)
  })

  it('maps the №47 409 conference_ended answer to the terminal ended status', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValue(meView())
    api.joinConference.mockRejectedValue(problem(409, 'conference_ended'))

    const { result } = renderHook(() => useConference(CONFERENCE_A))

    act(() => result.current.join())

    await waitFor(() => expect(result.current.joinStatus).toBe('ended'))
    expect(result.current.ended).toBe(true)
    expect(result.current.ticket).toBeNull()
    expect(activeConferenceId()).toBeNull()
  })

  it('refreshTicket re-runs №47 for a reconnect with an expired token and keeps joined', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValue(meView())
    api.joinConference.mockResolvedValueOnce(joinTicket('jwt-1'))

    const { result } = renderHook(() => useConference(CONFERENCE_A))
    act(() => result.current.join())
    await waitFor(() => expect(result.current.joinStatus).toBe('joined'))

    api.joinConference.mockResolvedValueOnce(joinTicket('jwt-2', { identity: `u:${ME}:conn-2` }))
    const outcome: { ticket: JoinTicket | null } = { ticket: null }
    await act(async () => {
      outcome.ticket = await result.current.refreshTicket()
    })

    expect(outcome.ticket?.token).toBe('jwt-2')
    expect(result.current.ticket?.token).toBe('jwt-2')
    expect(result.current.joinStatus).toBe('joined')
    expect(api.joinConference).toHaveBeenCalledTimes(2)
  })

  it('refreshTicket with conference_ended frees the slot and becomes terminal', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValue(meView())
    api.joinConference.mockResolvedValueOnce(joinTicket('jwt-1'))

    const { result } = renderHook(() => useConference(CONFERENCE_A))
    act(() => result.current.join())
    await waitFor(() => expect(result.current.joinStatus).toBe('joined'))
    const disconnect = vi.fn()
    bindConferenceDisconnect(CONFERENCE_A, disconnect)

    api.joinConference.mockRejectedValueOnce(problem(409, 'conference_ended'))
    await act(async () => {
      await result.current.refreshTicket()
    })

    expect(result.current.joinStatus).toBe('ended')
    expect(result.current.ticket).toBeNull()
    expect(activeConferenceId()).toBeNull()
    expect(disconnect).toHaveBeenCalledTimes(1)
  })

  it('leave disconnects the bound room, frees the slot and allows re-joining', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValue(meView())
    api.joinConference.mockResolvedValue(joinTicket('jwt-1'))

    const { result } = renderHook(() => useConference(CONFERENCE_A))
    act(() => result.current.join())
    await waitFor(() => expect(result.current.joinStatus).toBe('joined'))
    const disconnect = vi.fn()
    bindConferenceDisconnect(CONFERENCE_A, disconnect)

    act(() => result.current.leave())

    expect(result.current.joinStatus).toBe('idle')
    expect(result.current.ticket).toBeNull()
    expect(activeConferenceId()).toBeNull()
    expect(disconnect).toHaveBeenCalledTimes(1)

    act(() => result.current.join())
    await waitFor(() => expect(result.current.joinStatus).toBe('joined'))
    expect(api.joinConference).toHaveBeenCalledTimes(2)
  })

  it('the slot release does not disconnect (the room outlives a screen remount)', () => {
    const release = occupyConferenceSlot(CONFERENCE_A)
    const disconnect = vi.fn()
    bindConferenceDisconnect(CONFERENCE_A, disconnect)

    release()

    expect(activeConferenceId()).toBeNull()
    expect(disconnect).not.toHaveBeenCalled()
  })

  it('T043: refreshMe re-runs №46 and replaces the me snapshot without touching №45', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValueOnce(meView())

    const { result } = renderHook(() => useConference(CONFERENCE_A))
    await waitFor(() => expect(result.current.status).toBe('ready'))

    // сервер применил ограничение (№49) → permission-событие → повторный №46
    api.getConferenceMe.mockResolvedValueOnce(meView({ role: 'participant', micRestricted: true }))
    act(() => result.current.refreshMe())

    await waitFor(() => expect(result.current.me?.micRestricted).toBe(true))
    expect(result.current.me?.role).toBe('participant')
    expect(result.current.status).toBe('ready')
    // вспомогательная синхронизация не перезапускает пакет №45+№46
    expect(api.getConference).toHaveBeenCalledTimes(1)
    expect(api.getConferenceMe).toHaveBeenCalledTimes(2)
  })

  it('T043: refreshMe failure is silent — the me snapshot and screen stay intact', async () => {
    api.getConference.mockResolvedValue(conferenceView())
    api.getConferenceMe.mockResolvedValueOnce(meView())

    const { result } = renderHook(() => useConference(CONFERENCE_A))
    await waitFor(() => expect(result.current.status).toBe('ready'))

    api.getConferenceMe.mockRejectedValueOnce(problem(503, 'unavailable'))
    act(() => result.current.refreshMe())

    await waitFor(() => expect(api.getConferenceMe).toHaveBeenCalledTimes(2))
    // снимок остаётся прежним, экран не переводится в error
    expect(result.current.me).toEqual(meView())
    expect(result.current.status).toBe('ready')
    expect(result.current.error).toBeNull()
  })

  it('T043: a refreshMe answer hanging across a conferenceId change is not applied', async () => {
    api.getConference.mockResolvedValue(conferenceView({ id: CONFERENCE_A }))
    api.getConferenceMe.mockResolvedValueOnce(meView())

    const { result, rerender } = renderHook(({ id }) => useConference(id), {
      initialProps: { id: CONFERENCE_A },
    })
    await waitFor(() => expect(result.current.status).toBe('ready'))

    let resolveRefresh: (value: MeView) => void = () => {}
    api.getConferenceMe.mockReturnValueOnce(
      new Promise<MeView>((resolve) => {
        resolveRefresh = resolve
      }),
    )
    act(() => result.current.refreshMe())

    // вкладка ушла на другую конференцию, пока №46 в полёте
    api.getConference.mockResolvedValue(conferenceView({ id: CONFERENCE_B }))
    api.getConferenceMe.mockResolvedValue(meView({ role: 'participant' }))
    rerender({ id: CONFERENCE_B })
    await waitFor(() => expect(result.current.status).toBe('ready'))

    resolveRefresh(meView({ micRestricted: true }))
    await waitFor(() => expect(api.getConferenceMe).toHaveBeenCalledTimes(3))

    // устаревший ответ конференции A не показан на экране конференции B
    expect(result.current.me?.micRestricted).toBe(false)
    expect(result.current.me?.role).toBe('participant')
  })
})
