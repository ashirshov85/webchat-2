import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import type { ApiProblem } from '../../../api/auth'
import { stubLocationAssign } from '../../../test/location'
import type { ConferenceView, JoinTicket, MeView } from '../../api/conferences'
import { leaveActiveConference } from '../../hooks/useConference'
import ConferencePage from '../ConferencePage'

/**
 * 009, US1, T026 + US2, T034 — экран конференции: контракт маршрута
 * (region «Конференция» + data-conference-id, App.test T025), пакет
 * №45+№46, pre-join перед подключением (T034: «Войти» → №47, «Отмена» —
 * отмена подключения без последствий, US2-6), блок joined с «Покинуть»
 * (возврат на pre-join), «Конференция завершена» по обоим
 * лицам (№45 status='ended' и №47 409 conference_ended) и нейтральные
 * ошибки загрузки/join с «Повторить». T087: шапка страницы (заголовок
 * conferenceTitle, статический снимок «Участники: N / M» не-joined
 * фаз, строка «Ваша роль: …») и identity-строка joined-формы удалены —
 * их нет НИ В ОДНОЙ фазе. jsdom без mediaDevices —
 * acquisition превью камеры на pre-join тихо отклоняется (T032),
 * «Войти» остаётся доступной (Q6=A).
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

/** Догрузка профилей (T073) изолирована от сьюта экрана — свой тест у хука. */
const profiles = vi.hoisted(() => ({ useParticipantProfiles: vi.fn() }))

vi.mock('../../hooks/useParticipantProfiles', () => ({
  useParticipantProfiles: profiles.useParticipantProfiles,
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

describe('ConferencePage (009 US1 T026 + US2 T034)', () => {
  let assignMock: MockInstance

  beforeEach(() => {
    conferences.getConference.mockReset()
    conferences.getConferenceMe.mockReset()
    conferences.joinConference.mockReset()
    profiles.useParticipantProfiles.mockReset().mockReturnValue(new Map())
    assignMock = stubLocationAssign()
  })

  afterEach(() => {
    cleanup()
    leaveActiveConference()
    assignMock.mockRestore()
  })

  it('keeps the route contract: region «Конференция» with data-conference-id', async () => {
    resolveBundle()
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    const region = await screen.findByRole('region', { name: 'Конференция' })
    expect(region).toHaveAttribute('data-conference-id', CONFERENCE_ID)
    expect(screen.getByRole('button', { name: 'Войти' })).toBeInTheDocument()
    // T087: шапка удалена — ни заголовка conferenceTitle, ни снимка
    // «Участники: N / M», ни строки роли №46 (ниже — во всех фазах)
    expect(screen.queryByRole('heading', { name: 'Видеоконференция' })).not.toBeInTheDocument()
    expect(screen.queryByText('Участники: 1 / 16')).not.toBeInTheDocument()
    expect(screen.queryByText('Ваша роль: модератор')).not.toBeInTheDocument()
  })

  it('T087: шапка .conference-header и identity-строка отсутствуют во всех фазах страницы', async () => {
    // pre-join
    resolveBundle()
    const { unmount } = render(<ConferencePage conferenceId={CONFERENCE_ID} />)
    await screen.findByRole('button', { name: 'Войти' })
    expect(document.querySelector('.conference-header')).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Видеоконференция' })).not.toBeInTheDocument()

    // joined: output-подпись остаётся, identity-строка удалена
    conferences.joinConference.mockResolvedValue(joinTicket())
    fireEvent.click(screen.getByRole('button', { name: 'Войти' }))
    await screen.findByText('Вы в конференции')
    expect(document.querySelector('.conference-header')).toBeNull()
    expect(document.querySelector('.conference-identity')).toBeNull()
    expect(screen.queryByText(`u:${ME}:conn-1`)).not.toBeInTheDocument()
    expect(screen.queryByText('Ваша роль: модератор')).not.toBeInTheDocument()

    unmount()
    cleanup()
    // терминальная фаза — только подпись завершения (T071 без регресса)
    conferences.getConference.mockResolvedValue(
      conferenceView({ status: 'ended', endReason: 'moderator' }),
    )
    conferences.getConferenceMe.mockResolvedValue(meView())
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)
    expect(await screen.findByText('Конференция завершена')).toBeInTheDocument()
    expect(document.querySelector('.conference-header')).toBeNull()
  })

  it('joined: живой счётчик useParticipants вместо застывшего снимка №45 в шапке (T080)', async () => {
    // снимок №45 устарел уже через минуту в звонке: здесь 3 — чтобы
    // статическая строка и живой счётчик различались в DOM
    resolveBundle(conferenceView({ activeParticipants: 3 }))
    conferences.joinConference.mockResolvedValue(joinTicket())
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Войти' }))

    await screen.findByText('Вы в конференции')
    // статическая строка снимка №45 в header уходит (дубль цифр)…
    expect(screen.queryByText('Участники: 3 / 16')).not.toBeInTheDocument()
    // …её замещает живой счётчик в слоте children комнаты: jsdom-комната
    // livekit — только localParticipant (1); источник — useParticipants,
    // тот же, что панель «Участники (N)» (T041, конституция III)
    expect(await screen.findByText('Участники: 1 / 16')).toBeInTheDocument()
  })

  it('после «Покинуть» снимок №45+№46 обновляется один раз — без запрос-циклов (T080)', async () => {
    resolveBundle(conferenceView({ activeParticipants: 2 }))
    conferences.joinConference.mockResolvedValue(joinTicket())
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Войти' }))
    await screen.findByText('Вы в конференции')
    fireEvent.click(await screen.findByRole('button', { name: 'Покинуть' }))

    // возврат на pre-join + ровно один повтор пакета №45+№46 —
    // актуализация вида после выхода; шапка со снимком удалена (T087) —
    // статическая строка «Участники: 2 / 16» больше не выводится
    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    expect(screen.queryByText('Участники: 2 / 16')).not.toBeInTheDocument()
    await waitFor(() => expect(conferences.getConference).toHaveBeenCalledTimes(2))
    expect(conferences.getConferenceMe).toHaveBeenCalledTimes(2)
  })

  it('shows «Конференция завершена» for a №45 ended status without a pre-join', async () => {
    resolveBundle(conferenceView({ status: 'ended', endReason: 'last_left' }))
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    const ended = await screen.findByText('Конференция завершена')
    expect(ended.tagName).toBe('OUTPUT')
    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
    expect(conferences.joinConference).not.toHaveBeenCalled()
  })

  it('shows the duration on the ended screen when №45 carries startedAt/endedAt (T071)', async () => {
    resolveBundle(
      conferenceView({
        status: 'ended',
        endReason: 'moderator',
        startedAt: '2026-10-17T12:00:00.000Z',
        endedAt: '2026-10-17T12:30:00.000Z',
      }),
    )
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    const ended = await screen.findByText('Конференция завершена · 30 мин')
    expect(ended.tagName).toBe('OUTPUT')
    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
    expect(conferences.joinConference).not.toHaveBeenCalled()
  })

  it('ended without startedAt (never started, room_lost) — plain label without duration (T071)', async () => {
    resolveBundle(conferenceView({ status: 'ended', endReason: 'room_lost' }))
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    const ended = await screen.findByText('Конференция завершена')
    expect(ended.tagName).toBe('OUTPUT')
    expect(screen.queryByText(/Конференция завершена ·/)).not.toBeInTheDocument()
  })

  it('«Войти» on pre-join mints №47 and renders the joined block; leave returns to pre-join', async () => {
    resolveBundle()
    const ticket = joinTicket()
    conferences.joinConference.mockResolvedValue(ticket)
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    // до «Войти» №47 не вызывался и слот не занимался (FR-007, US2-5)
    const joinButton = await screen.findByRole('button', { name: 'Войти' })
    expect(conferences.joinConference).not.toHaveBeenCalled()
    fireEvent.click(joinButton)

    const joined = await screen.findByText('Вы в конференции')
    expect(joined.tagName).toBe('OUTPUT')
    // T087: технический LiveKit identity в joined-форме не выводится
    expect(screen.queryByText(`u:${ME}:conn-1`)).not.toBeInTheDocument()
    expect(conferences.joinConference).toHaveBeenCalledWith(CONFERENCE_ID)

    // комната (и панель управления в её слоте) появляется следующим
    // коммитом — Room создаётся эффектом жизненного цикла тикета (T027)
    fireEvent.click(await screen.findByRole('button', { name: 'Покинуть' }))

    // выход возвращает на pre-join — повторный вход доступен (T034)
    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    expect(screen.queryByText('Вы в конференции')).not.toBeInTheDocument()
  })

  it('«Отмена» на pre-join отменяет подключение без последствий: №47 не вызывался, возврат в мессенджер (US2-6, T034)', async () => {
    resolveBundle()
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(assignMock).toHaveBeenCalledWith('/')
    expect(conferences.joinConference).not.toHaveBeenCalled()
  })

  it('shows «Конференция завершена» when №47 answers 409 conference_ended', async () => {
    resolveBundle()
    conferences.joinConference.mockRejectedValue(problem(409, 'conference_ended'))
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Войти' }))

    const ended = await screen.findByText('Конференция завершена')
    expect(ended.tagName).toBe('OUTPUT')
    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
  })

  it('renders a neutral join error with «Повторить» (conference_full)', async () => {
    resolveBundle()
    conferences.joinConference
      .mockRejectedValueOnce(problem(409, 'conference_full'))
      .mockResolvedValueOnce(joinTicket())
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Войти' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Конференция заполнена')

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    const joined = await screen.findByText('Вы в конференции')
    expect(joined.tagName).toBe('OUTPUT')
  })

  it('догружает профили участников по chatId №45-вида (T073: аватары «как в контактах»)', async () => {
    resolveBundle(conferenceView({ chatId: 'chat-42' }))
    render(<ConferencePage conferenceId={CONFERENCE_ID} />)

    await screen.findByRole('button', { name: 'Войти' })
    expect(profiles.useParticipantProfiles).toHaveBeenCalledWith('chat-42')
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

    await waitFor(() => expect(screen.getByRole('button', { name: 'Войти' })).toBeInTheDocument())
    expect(conferences.getConference).toHaveBeenCalledTimes(2)
  })
})
