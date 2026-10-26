import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiProblem } from '../../../api/auth'
import type { ConferenceMode, InviteInfoView, JoinTicket } from '../../api/conferences'
import { activeConferenceId, leaveActiveConference } from '../../hooks/useConference'

/**
 * 009, US4, T052 — гостевая join-страница (T049, quickstart §3.4):
 * №54 статус ссылки до ввода имени (валидная → имя → pre-join
 * «только микрофон» Q1=C → «Войти» → №55 → звонок с пометкой «гость»;
 * невалидная/подделанная — терминальный экран понятного отказа),
 * ошибки имени (клиентское зеркало серверного trim 1–50, Q19/Q7=A;
 * серверный 422 name_invalid — возврат к шагу имени), ветвление №55
 * (терминальные состояния ссылки — экран отказа; повторяемые —
 * «Повторить» на pre-join), «Отмена»/«Покинуть» без последствий и
 * многоразовость ссылки (повторный №55, Q2=A). Слот вкладки гостя —
 * `invite:{nonce}` (UUID конференции гостю не раскрывается, Q10=A).
 *
 * ConferenceRoom/MediaControls/ParticipantsPanel — заглушки с захватом
 * props (поведение — их собственные сьюты), API №54/№55 — моки:
 * предмет теста — фазовый автомат страницы и ветвление кодов Problem.
 *
 * T092: output-подпись «Вы в конференции как гость» joined-формы
 * удалена (паритет ConferencePage) — joined-фазу маркирует заглушка
 * комнаты (data-testid="conference-room"): ранний сигнал, рендерится
 * сразу при join.
 *
 * T112: терминальный отказ ссылки (фаза refused) — пластина «звонок
 * окончен» с тихой подсказкой p.guest-join-refused-hint «Можно
 * закрыть эту страницу» для ВСЕХ причин; повторяемые ошибки
 * (infoError №54 / joinError №55 с «Повторить») — прежняя аварийная
 * разметка .conference-error без подсказки.
 */

const conferences = vi.hoisted(() => ({
  getInviteInfo: vi.fn(),
  joinConferenceByInvite: vi.fn(),
}))

const lk = vi.hoisted(() => ({
  createLocalVideoTrack: vi.fn(),
}))

/** Захват props GuestJoinPage → ConferenceRoom (заглушка комнаты рендерит children). */
const roomProps = vi.hoisted(() => ({
  captures: [] as Array<{
    conferenceId: string
    ticket: JoinTicket
    mode: ConferenceMode
    initialMedia?: { microphone: boolean; camera: boolean }
    initialDevices?: {
      audioInput: string | null
      videoInput: string | null
      audioOutput: string | null
    } | null
  }>,
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  getInviteInfo: conferences.getInviteInfo,
  joinConferenceByInvite: conferences.joinConferenceByInvite,
}))

vi.mock('livekit-client', () => ({
  createLocalVideoTrack: lk.createLocalVideoTrack,
  RoomEvent: { ActiveDeviceChanged: 'activeDeviceChanged' },
}))

vi.mock('../../components/ConferenceRoom', () => ({
  default: (props: {
    conferenceId: string
    ticket: JoinTicket
    mode: ConferenceMode
    initialMedia?: { microphone: boolean; camera: boolean }
    initialDevices?: {
      audioInput: string | null
      videoInput: string | null
      audioOutput: string | null
    } | null
    children?: React.ReactNode
  }) => {
    roomProps.captures.push({
      conferenceId: props.conferenceId,
      ticket: props.ticket,
      mode: props.mode,
      initialMedia: props.initialMedia,
      initialDevices: props.initialDevices,
    })
    return <div data-testid="conference-room">{props.children}</div>
  },
}))

vi.mock('../../components/ParticipantsPanel', () => ({
  default: () => <section data-testid="participants-panel" />,
}))

vi.mock('../../components/MediaControls', () => ({
  default: ({ onLeave }: { readonly onLeave: () => void }) => (
    <button type="button" onClick={onLeave}>
      Покинуть
    </button>
  ),
}))

import GuestJoinPage from '../GuestJoinPage'

const INVITE_TOKEN = 'guest-nonce-1'
/** Ключ слота вкладки гостя (Q10=A): UUID конференции не раскрывается. */
const SLOT_KEY = `invite:${INVITE_TOKEN}`

function inviteInfo(overrides: Partial<InviteInfoView> = {}): InviteInfoView {
  return { valid: true, ...overrides }
}

function guestTicket(overrides: Partial<JoinTicket> = {}): JoinTicket {
  return {
    token: 'guest-jwt-1',
    wsUrl: 'wss://media.example.com',
    identity: 'g:conn-guest-1',
    name: 'Виктор',
    expiresAt: '2026-10-18T09:30:00.000Z',
    ...overrides,
  }
}

function problem(status: number, code: string): ApiProblem {
  return { title: `HTTP ${status}`, status, errors: { invite: [code] } }
}

/** Монтаж с ответом №54 и ожиданием шага имени. */
async function openNameStage(info: InviteInfoView = inviteInfo()): Promise<void> {
  conferences.getInviteInfo.mockResolvedValue(info)
  render(<GuestJoinPage inviteToken={INVITE_TOKEN} />)
  await screen.findByRole('heading', { name: 'Подключение к конференции' })
}

/** Шаги имя → pre-join (без «Войти»: №55 ещё не вызывался). */
async function reachPreJoin(name = 'Виктор'): Promise<void> {
  await openNameStage()
  fireEvent.change(screen.getByLabelText('Ваше имя'), { target: { value: name } })
  fireEvent.click(screen.getByRole('button', { name: 'Продолжить' }))
  await screen.findByRole('button', { name: 'Войти' })
}

/** «Войти» на pre-join → звонок (№55 успешен): joined-маркер — заглушка комнаты (T092). */
async function joinAsGuest(ticket: JoinTicket = guestTicket()): Promise<void> {
  conferences.joinConferenceByInvite.mockResolvedValue(ticket)
  fireEvent.click(screen.getByRole('button', { name: 'Войти' }))
  await screen.findByTestId('conference-room')
}

describe('GuestJoinPage (009 US4 T049/T052)', () => {
  beforeEach(() => {
    conferences.getInviteInfo.mockReset()
    conferences.joinConferenceByInvite.mockReset()
    lk.createLocalVideoTrack.mockReset()
    roomProps.captures.length = 0
  })

  afterEach(() => {
    cleanup()
    leaveActiveConference()
  })

  it('валидный инвайт: №54 → имя (live-подсказка) → pre-join «только микрофон» (Q1=C) → №55 → звонок с пометкой «гость» и слотом вкладки', async () => {
    await openNameStage(inviteInfo({ live: true }))

    // №54 live=true — UX-подсказка «конференция идёт» до ввода имени
    expect(screen.getByText('Конференция идёт прямо сейчас')).toBeInTheDocument()
    expect(conferences.getInviteInfo).toHaveBeenCalledWith(INVITE_TOKEN)

    fireEvent.change(screen.getByLabelText('Ваше имя'), { target: { value: 'Виктор' } })
    fireEvent.click(screen.getByRole('button', { name: 'Продолжить' }))

    // pre-join гостя — переиспользование T030 c mode='audio': дефолт
    // «только микрофон», тумблер камеры ДОСТУПЕН (soft, Q1=C/Q3=A);
    // до «Войти» №55 не вызывался и слот не занимался (FR-007)
    const mic = await screen.findByRole('button', { name: 'Микрофон' })
    expect(mic).toHaveAttribute('aria-pressed', 'true')
    const camera = screen.getByRole('button', { name: 'Камера' })
    expect(camera).toHaveAttribute('aria-pressed', 'false')
    expect(camera).toBeEnabled()
    expect(conferences.joinConferenceByInvite).not.toHaveBeenCalled()
    expect(activeConferenceId()).toBeNull()

    await joinAsGuest()

    // звонок смонтирован; T087: identity-строка joined-формы (имя
    // тикета №55) удалена; T092: output-подпись «Вы в конференции как
    // гость» удалена — joined-фазу маркирует комната
    expect(screen.getByTestId('conference-room')).toBeInTheDocument()
    expect(screen.queryByText('Вы в конференции как гость')).not.toBeInTheDocument()
    expect(screen.queryByText('Виктор')).not.toBeInTheDocument()
    expect(conferences.joinConferenceByInvite).toHaveBeenCalledTimes(1)
    expect(conferences.joinConferenceByInvite).toHaveBeenCalledWith(INVITE_TOKEN, 'Виктор')

    // комнате — тикет, слот вкладки по nonce (UUID не раскрывается,
    // Q10=A) и стартовая выборка pre-join (микрофон без камеры)
    expect(roomProps.captures).toHaveLength(1)
    expect(roomProps.captures[0]?.conferenceId).toBe(SLOT_KEY)
    expect(roomProps.captures[0]?.ticket).toEqual(guestTicket())
    expect(roomProps.captures[0]?.mode).toBe('audio')
    expect(roomProps.captures[0]?.initialMedia).toEqual({ microphone: true, camera: false })
    expect(roomProps.captures[0]?.initialDevices).toEqual({
      audioInput: null,
      videoInput: null,
      audioOutput: null,
    })
    expect(activeConferenceId()).toBe(SLOT_KEY)
  })

  it('live-подсказка отсутствует, когда №54 не передал live (конференция ещё не началась)', async () => {
    await openNameStage(inviteInfo())

    expect(screen.queryByText('Конференция идёт прямо сейчас')).not.toBeInTheDocument()
  })

  it('имя: пустое и пробельное → «Введите имя», к pre-join нет перехода, №55 не вызывается', async () => {
    await openNameStage()

    fireEvent.click(screen.getByRole('button', { name: 'Продолжить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Введите имя')

    fireEvent.change(screen.getByLabelText('Ваше имя'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Продолжить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Введите имя')

    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
    expect(conferences.joinConferenceByInvite).not.toHaveBeenCalled()
  })

  it('имя 51 символ → понятный отказ; ровно 50 — граница проходит к pre-join (Q19/Q7=A)', async () => {
    await openNameStage()
    const input = screen.getByLabelText('Ваше имя')

    fireEvent.change(input, { target: { value: 'а'.repeat(51) } })
    fireEvent.click(screen.getByRole('button', { name: 'Продолжить' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Имя — не длиннее 50 символов')
    expect(conferences.joinConferenceByInvite).not.toHaveBeenCalled()

    fireEvent.change(input, { target: { value: 'а'.repeat(50) } })
    fireEvent.click(screen.getByRole('button', { name: 'Продолжить' }))
    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
  })

  it('трим имени фиксируется: «  Виктор  » → №55 получает «Виктор» (зеркало серверного trim)', async () => {
    await reachPreJoin('  Виктор  ')
    await joinAsGuest(guestTicket())

    expect(conferences.joinConferenceByInvite).toHaveBeenCalledWith(INVITE_TOKEN, 'Виктор')
  })

  it.each([
    ['expired', 'Срок действия ссылки истёк'],
    ['revoked', 'Ссылка отозвана'],
    ['ended', 'Конференция завершена'],
  ])(
    'невалидная ссылка №54 reason=%s → терминальный экран «%s» без ввода имени',
    async (reason, message) => {
      conferences.getInviteInfo.mockResolvedValue(
        inviteInfo({ valid: false, reason: reason as InviteInfoView['reason'] }),
      )
      render(<GuestJoinPage inviteToken={INVITE_TOKEN} />)

      expect(await screen.findByRole('alert')).toHaveTextContent(message)
      // T112: терминальная пластина с тихой подсказкой — у гостя нет
      // следующего шага (ни чата, ни кнопки — FR-010)
      expect(screen.getByText('Можно закрыть эту страницу')).toHaveClass('guest-join-refused-hint')
      expect(screen.getByRole('alert').closest('div')).toHaveClass('guest-join-refused')
      expect(screen.queryByLabelText('Ваше имя')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
      expect(conferences.joinConferenceByInvite).not.toHaveBeenCalled()
    },
  )

  it('подделанный nonce: №54 {valid:false} без reason → единообразное «Ссылка недействительна» (минимум раскрытия, edge)', async () => {
    conferences.getInviteInfo.mockResolvedValue(inviteInfo({ valid: false }))
    render(<GuestJoinPage inviteToken={INVITE_TOKEN} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Ссылка недействительна')
    // T112: подсказка присутствует и для единого invalid-отказа
    expect(screen.getByText('Можно закрыть эту страницу')).toHaveClass('guest-join-refused-hint')
    expect(screen.queryByLabelText('Ваше имя')).not.toBeInTheDocument()
    expect(conferences.joinConferenceByInvite).not.toHaveBeenCalled()
  })

  it('сбой проверки №54 (транспорт) → «Не удалось проверить ссылку» + «Повторить»; повтор успешен', async () => {
    conferences.getInviteInfo.mockRejectedValueOnce(new Error('network down'))
    render(<GuestJoinPage inviteToken={INVITE_TOKEN} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось проверить ссылку')
    // T112: повторяемая ошибка — прежняя аварийная разметка без
    // терминальной подсказки
    expect(screen.queryByText('Можно закрыть эту страницу')).not.toBeInTheDocument()
    expect(screen.getByRole('alert').closest('div')).not.toHaveClass('guest-join-refused')

    conferences.getInviteInfo.mockResolvedValueOnce(inviteInfo())
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    await screen.findByRole('heading', { name: 'Подключение к конференции' })
    expect(conferences.getInviteInfo).toHaveBeenCalledTimes(2)
  })

  it('серверный 422 name_invalid на №55 → возврат к шагу имени с сообщением (защита вдогонку)', async () => {
    await reachPreJoin()
    conferences.joinConferenceByInvite.mockRejectedValue(problem(422, 'name_invalid'))

    fireEvent.click(screen.getByRole('button', { name: 'Войти' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Имя — не длиннее 50 символов')
    expect(screen.getByLabelText('Ваше имя')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
  })

  it.each([
    ['invite_expired', 409, 'Срок действия ссылки истёк'],
    ['invite_revoked', 409, 'Ссылка отозвана'],
    ['conference_ended', 409, 'Конференция завершена'],
    ['invite_invalid', 404, 'Ссылка недействительна'],
    ['invite_not_found', 404, 'Ссылка недействительна'],
  ])(
    'терминальный отказ №55 %s → единый экран отказа «%s», без «Повторить»',
    async (code, status, message) => {
      await reachPreJoin()
      conferences.joinConferenceByInvite.mockRejectedValue(problem(status, code))

      fireEvent.click(screen.getByRole('button', { name: 'Войти' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(message)
      // T112: подсказка терминальной пластины — для всех причин №55
      expect(screen.getByText('Можно закрыть эту страницу')).toHaveClass('guest-join-refused-hint')
      expect(screen.queryByRole('button', { name: 'Войти' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument()
      expect(activeConferenceId()).toBeNull()
    },
  )

  it('повторяемый отказ №55 conference_full → сообщение + «Повторить» на pre-join; повтор успешен → звонок', async () => {
    await reachPreJoin()
    conferences.joinConferenceByInvite
      .mockRejectedValueOnce(problem(409, 'conference_full'))
      .mockResolvedValueOnce(guestTicket())

    fireEvent.click(screen.getByRole('button', { name: 'Войти' }))

    // квота общая с членами (Q23=A) — повторяемый отказ, ссылка жива:
    // остаёмся на pre-join с «Повторить»
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Конференция заполнена — попробуйте позже',
    )
    // T112: повторяемая ошибка — аварийная разметка без подсказки
    expect(screen.queryByText('Можно закрыть эту страницу')).not.toBeInTheDocument()
    expect(screen.getByRole('alert').closest('div')).not.toHaveClass('guest-join-refused')
    expect(screen.getByRole('button', { name: 'Войти' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(await screen.findByTestId('conference-room')).toBeInTheDocument()
    expect(conferences.joinConferenceByInvite).toHaveBeenCalledTimes(2)
  })

  it('«Отмена» на pre-join → назад к шагу имени (имя сохранено); №55 не вызывался, слот свободен', async () => {
    await reachPreJoin('Виктор')

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(
      await screen.findByRole('heading', { name: 'Подключение к конференции' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Ваше имя')).toHaveValue('Виктор')
    expect(conferences.joinConferenceByInvite).not.toHaveBeenCalled()
    expect(activeConferenceId()).toBeNull()
  })

  it('«Покинуть» в звонке → возврат на pre-join и освобождение слота; повторный «Войти» — повторный №55 (ссылка многоразовая, Q2=A)', async () => {
    await reachPreJoin('Виктор')
    await joinAsGuest()
    expect(activeConferenceId()).toBe(SLOT_KEY)

    fireEvent.click(screen.getByRole('button', { name: 'Покинуть' }))

    expect(await screen.findByRole('button', { name: 'Войти' })).toBeInTheDocument()
    // T092: комната размонтирована, подпись «как гость» не появляется
    expect(screen.queryByTestId('conference-room')).not.toBeInTheDocument()
    expect(screen.queryByText('Вы в конференции как гость')).not.toBeInTheDocument()
    expect(activeConferenceId()).toBeNull()

    conferences.joinConferenceByInvite.mockClear()
    await joinAsGuest(guestTicket({ token: 'guest-jwt-2', identity: 'g:conn-guest-2' }))

    expect(conferences.joinConferenceByInvite).toHaveBeenCalledTimes(1)
    expect(activeConferenceId()).toBe(SLOT_KEY)
  })
})
