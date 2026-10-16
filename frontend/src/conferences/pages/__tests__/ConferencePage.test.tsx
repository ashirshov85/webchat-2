import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiProblem } from '../../../api/auth'
import type { ConferenceView, JoinTicket, MeView } from '../../api/conferences'
import { leaveActiveConference } from '../../hooks/useConference'
import ConferencePage from '../ConferencePage'

/**
 * 009, US1, T026 — экран конференции: контракт маршрута (region
 * «Конференция» + data-conference-id, App.test T025), пакет №45+№46,
 * кнопка «Подключиться» → №47 → блок joined с identity и «Покинуть»,
 * «Конференция завершена» по обоим лицам (№45 status='ended' и №47
 * 409 conference_ended) и нейтральные ошибки загрузки/join с
 * «Повторить».
 */

const conferences = vi.hoisted(() => ({
  getConference: vi.fn(),
  getConferenceMe: vi.fn(),
  joinConference: vi.fn(),
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  getConference: conferences.getConference,
  getConferenceMe: conferences.getConferenceMe,
  joinConference: conferences.joinConference,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const CONFERENCE_ID = 'aaaaaaaa-0000-4000-8000-000000000001'

function conferenceView(overrides: Partial<ConferenceView> = {}): ConferenceView {
  return {
    id: CONFERENCE_ID,
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

function joinTicket(overrides: Partial<JoinTicket> = {}): JoinTicket {
  return {
    token: 'jwt-1',
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

function resolveBundle(view: ConferenceView = conferenceView(), me: MeView = meView()): void {
  conferences.getConference.mockResolvedValue(view)
  conferences.getConferenceMe.mockResolvedValue(me)
}

describe('ConferencePage (009 US1 T026)', () => {
  beforeEach(() => {
    conferences.getConference.mockReset()
    conferences.getConferenceMe.mockReset()
    conferences.joinConference.mockReset()
  })

  afterEach(() => {
    cleanup()
    leaveActiveConference()
  })

  it('keeps the route contract: region «Конференция» with data-conference-id', async () => {
    resolveBundle()
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    const region = await screen.findByRole('region', { name: 'Конференция' })
    expect(region).toHaveAttribute('data-conference-id', CONFERENCE_ID)
    expect(screen.getByRole('heading', { name: 'Видеоконференция' })).toBeInTheDocument()
    expect(screen.getByText('Участники: 1 / 16')).toBeInTheDocument()
    expect(screen.getByText('Ваша роль: модератор')).toBeInTheDocument()
  })

  it('shows «Конференция завершена» for a №45 ended status without a join button', async () => {
    resolveBundle(conferenceView({ status: 'ended', endReason: 'last_left' }))
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    const ended = await screen.findByText('Конференция завершена')
    expect(ended.tagName).toBe('OUTPUT')
    expect(screen.queryByRole('button', { name: 'Подключиться' })).not.toBeInTheDocument()
    expect(conferences.joinConference).not.toHaveBeenCalled()
  })

  it('join №47 renders the joined block with the ticket identity and leave returns to idle', async () => {
    resolveBundle()
    const ticket = joinTicket()
    conferences.joinConference.mockResolvedValue(ticket)
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Подключиться' }))

    const joined = await screen.findByText('Вы в конференции')
    expect(joined.tagName).toBe('OUTPUT')
    expect(screen.getByText(`u:${ME}:conn-1`)).toBeInTheDocument()
    expect(conferences.joinConference).toHaveBeenCalledWith(CONFERENCE_ID)

    // комната (и панель управления в её слоте) появляется следующим
    // коммитом — Room создаётся эффектом жизненного цикла тикета (T027)
    fireEvent.click(await screen.findByRole('button', { name: 'Покинуть' }))

    expect(await screen.findByRole('button', { name: 'Подключиться' })).toBeInTheDocument()
    expect(screen.queryByText('Вы в конференции')).not.toBeInTheDocument()
  })

  it('shows «Конференция завершена» when №47 answers 409 conference_ended', async () => {
    resolveBundle()
    conferences.joinConference.mockRejectedValue(problem(409, 'conference_ended'))
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Подключиться' }))

    const ended = await screen.findByText('Конференция завершена')
    expect(ended.tagName).toBe('OUTPUT')
    expect(screen.queryByRole('button', { name: 'Подключиться' })).not.toBeInTheDocument()
  })

  it('renders a neutral join error with «Повторить» (conference_full)', async () => {
    resolveBundle()
    conferences.joinConference
      .mockRejectedValueOnce(problem(409, 'conference_full'))
      .mockResolvedValueOnce(joinTicket())
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Подключиться' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Конференция заполнена')

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    const joined = await screen.findByText('Вы в конференции')
    expect(joined.tagName).toBe('OUTPUT')
  })

  it('renders a neutral load error and «Повторить» re-runs the №45+№46 bundle', async () => {
    conferences.getConference
      .mockRejectedValueOnce(problem(404, 'conference_not_found'))
      .mockResolvedValueOnce(conferenceView())
    conferences.getConferenceMe
      .mockRejectedValueOnce(problem(404, 'conference_not_found'))
      .mockResolvedValueOnce(meView())
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Конференция недоступна')

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Видеоконференция' })).toBeInTheDocument(),
    )
    expect(conferences.getConference).toHaveBeenCalledTimes(2)
  })
})
