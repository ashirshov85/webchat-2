import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 009, US3, T042 — панель модерации (FR-008, №48–№51): действия
 * модератора адресуются по identity строк LiveKit; №49 отправляет
 * ПОЛНОЕ SET-состояние (выведенное из фактических grants цели),
 * №50 — противоположную роль, №51/№48 — только после явного
 * подтверждения (деструктивные). Панель видна только роли модератора
 * локального подключения (metadata) и живьём следует broadcast'у
 * смены роли (Q9=A). Примитивы @livekit/components-react и API-клиент
 * заменены фейками: предмет теста — оркестрация действий и
 * подтверждения (набор US3 — T043).
 */

/** Wire-константы TrackSource (@livekit/protocol) — как в панели. */
const CAMERA = 1
const MICROPHONE = 2
const SCREEN_SHARE = 3
const SCREEN_SHARE_AUDIO = 4

const ALL_SOURCES = [CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO]

/** Минимальный контракт участника LiveKit, используемый панелью. */
interface FakeParticipant {
  readonly identity: string
  readonly name?: string
  readonly metadata?: string
  readonly isLocal: boolean
  readonly permissions?: { readonly canPublish: boolean; readonly canPublishSources: number[] }
}

const lkc = vi.hoisted(() => ({
  useParticipants: vi.fn(),
  useLocalParticipant: vi.fn(),
}))

vi.mock('@livekit/components-react', () => ({
  useParticipants: lkc.useParticipants,
  useLocalParticipant: lkc.useLocalParticipant,
}))

const api = vi.hoisted(() => ({
  endConference: vi.fn(),
  kickParticipant: vi.fn(),
  updateParticipantRestrictions: vi.fn(),
  updateParticipantRole: vi.fn(),
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  endConference: api.endConference,
  kickParticipant: api.kickParticipant,
  updateParticipantRestrictions: api.updateParticipantRestrictions,
  updateParticipantRole: api.updateParticipantRole,
}))

import ModerationPanel from '../ModerationPanel'

function participant(overrides: Partial<FakeParticipant> & { identity: string }): FakeParticipant {
  return { isLocal: false, name: 'Имя', ...overrides }
}

function memberMetadata(role: 'moderator' | 'participant'): string {
  return JSON.stringify({ role, guest: false })
}

/** Модератор-владелец панели + удалённая цель с полными правами. */
function defaultRoom(): FakeParticipant[] {
  return [
    participant({
      identity: 'u:11111111-1111-1111-1111-111111111111:conn-1',
      name: 'alice',
      metadata: memberMetadata('moderator'),
      isLocal: true,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }),
    participant({
      identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
      name: 'bob',
      metadata: memberMetadata('participant'),
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }),
  ]
}

/** Строка панели по identity цели (кнопки действий — внутри строки). */
function rowOf(identity: string): HTMLElement {
  const row = document.querySelector(`[data-participant-identity="${identity}"]`)
  if (row === null) {
    throw new Error(`строка ${identity} не отрендерена`)
  }
  return row as HTMLElement
}

/** Проблема-отказ домена (RFC 9457, errors: {field: [code]}). */
function problemWith(
  code: string,
  status = 403,
): { status: number; errors: Record<string, string[]> } {
  return { status, errors: { conference: [code] } }
}

describe('ModerationPanel (009 US3 T042)', () => {
  beforeEach(() => {
    lkc.useParticipants.mockReset()
    lkc.useLocalParticipant.mockReset()
    api.endConference.mockReset()
    api.kickParticipant.mockReset()
    api.updateParticipantRestrictions.mockReset()
    api.updateParticipantRole.mockReset()
    lkc.useLocalParticipant.mockReturnValue({
      localParticipant: defaultRoom()[0],
    })
    lkc.useParticipants.mockReturnValue(defaultRoom())
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('не-модератору панель не рендерится (роль локального — metadata; fail-closed)', () => {
    const room = defaultRoom()
    room[0] = participant({
      identity: 'u:11111111-1111-1111-1111-111111111111:conn-1',
      name: 'alice',
      metadata: memberMetadata('participant'),
      isLocal: true,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    })
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: room[0] })
    lkc.useParticipants.mockReturnValue(room)

    const { container } = render(<ModerationPanel conferenceId="c-1" />)

    expect(container.querySelector('.conference-moderation')).toBeNull()
    expect(api.updateParticipantRestrictions).not.toHaveBeenCalled()
  })

  it('модератор: строки участников с действиями, включая себя (равноправие, Q21=A)', () => {
    render(<ModerationPanel conferenceId="c-1" />)

    expect(screen.getByText('Модерация')).toBeInTheDocument()
    expect(screen.getByText('alice (вы)')).toBeInTheDocument()
    expect(screen.getByText('bob')).toBeInTheDocument()
    // обе строки (в т.ч. своя и вторая) несут полный набор действий №49–№51
    for (const identity of [
      'u:11111111-1111-1111-1111-111111111111:conn-1',
      'u:22222222-2222-4222-8222-222222222222:conn-2',
    ]) {
      const row = rowOf(identity)
      expect(within(row).getByRole('button', { name: 'Отключить микрофон' })).toBeInTheDocument()
      expect(within(row).getByRole('button', { name: 'Отключить камеру' })).toBeInTheDocument()
      expect(within(row).getByRole('button', { name: 'Отключить экран' })).toBeInTheDocument()
      expect(within(row).getByRole('button', { name: 'Право слова' })).toBeInTheDocument()
      expect(within(row).getByRole('button', { name: 'Исключить' })).toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: 'Назначить модератором' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeInTheDocument()
  })

  it('№49 мьют микрофона: полное SET-состояние из grants цели с переключённым полем', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    render(<ModerationPanel conferenceId="c-1" />)

    fireEvent.click(
      within(rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')).getByRole('button', {
        name: 'Отключить микрофон',
      }),
    )

    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith(
        'c-1',
        'u:22222222-2222-4222-8222-222222222222:conn-2',
        {
          micRestricted: true,
          cameraRestricted: false,
          screenRestricted: false,
          speechRestricted: false,
        },
      )
    })
  })

  it('№49 переключатель отражает действующие ограничения: срезанный источник возвращает «включить»', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    // цель: микрофон и экран срезаны, камера разрешена
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: true, canPublishSources: [CAMERA] },
      }),
    ])
    render(<ModerationPanel conferenceId="c-1" />)

    const row = rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')
    expect(within(row).getByRole('button', { name: 'Включить микрофон' })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Отключить камеру' })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Включить экран' })).toBeInTheDocument()

    // возврат микрофона: SET с mic=false, прочие состояния сохранены
    fireEvent.click(within(row).getByRole('button', { name: 'Включить микрофон' }))
    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith(
        'c-1',
        'u:22222222-2222-4222-8222-222222222222:conn-2',
        {
          micRestricted: false,
          cameraRestricted: false,
          screenRestricted: true,
          speechRestricted: false,
        },
      )
    })
  })

  it('№49 «право слова» (Q4=A): canPublish=false цели → кнопка возврата, SET ставит speechRestricted', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])
    render(<ModerationPanel conferenceId="c-1" />)

    const row = rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')
    fireEvent.click(within(row).getByRole('button', { name: 'Снять право слова' }))

    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith(
        'c-1',
        'u:22222222-2222-4222-8222-222222222222:conn-2',
        {
          micRestricted: false,
          cameraRestricted: false,
          screenRestricted: false,
          speechRestricted: false,
        },
      )
    })
  })

  it('№50 назначение/снятие модератора: противоположная роль цели — в т.ч. строки другого модератора и себя (Q21=A)', async () => {
    api.updateParticipantRole.mockResolvedValue({})
    lkc.useParticipants.mockReturnValue([
      // второй модератор: собственная строка-модератор ниже покрывает цель-модератора
      defaultRoom()[0],
      participant({
        identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
      }),
    ])
    render(<ModerationPanel conferenceId="c-1" />)

    // цель-участник → назначение модератором
    fireEvent.click(
      within(rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')).getByRole('button', {
        name: 'Назначить модератором',
      }),
    )
    await waitFor(() => {
      expect(api.updateParticipantRole).toHaveBeenCalledWith(
        'c-1',
        'u:22222222-2222-4222-8222-222222222222:conn-2',
        'moderator',
      )
    })

    // цель-модератор (собственная строка) → снятие, включая с себя (равноправие, Q21=A)
    fireEvent.click(
      within(rowOf('u:11111111-1111-1111-1111-111111111111:conn-1')).getByRole('button', {
        name: 'Снять модератора',
      }),
    )
    await waitFor(() => {
      expect(api.updateParticipantRole).toHaveBeenCalledWith(
        'c-1',
        'u:11111111-1111-1111-1111-111111111111:conn-1',
        'participant',
      )
    })
  })

  it('№51 кик — деструктивное: подтверждение обязательно, №51 вызывается только после «Подтвердить»', async () => {
    api.kickParticipant.mockResolvedValue(undefined)
    render(<ModerationPanel conferenceId="c-1" />)

    fireEvent.click(
      within(rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')).getByRole('button', {
        name: 'Исключить',
      }),
    )

    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByText(/Исключить bob/)).toBeInTheDocument()
    expect(api.kickParticipant).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Подтвердить' }))

    await waitFor(() => {
      expect(api.kickParticipant).toHaveBeenCalledWith(
        'c-1',
        'u:22222222-2222-4222-8222-222222222222:conn-2',
      )
    })
  })

  it('№51 кик: «Отмена» закрывает подтверждение без вызова', () => {
    render(<ModerationPanel conferenceId="c-1" />)

    fireEvent.click(
      within(rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')).getByRole('button', {
        name: 'Исключить',
      }),
    )
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Отмена' }))

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(api.kickParticipant).not.toHaveBeenCalled()
  })

  it('№48 завершение конференции — деструктивное: подтверждение обязательно', async () => {
    api.endConference.mockResolvedValue(undefined)
    render(<ModerationPanel conferenceId="c-1" />)

    fireEvent.click(screen.getByRole('button', { name: 'Завершить конференцию' }))

    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByText(/Завершить конференцию для всех/)).toBeInTheDocument()
    expect(api.endConference).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Подтвердить' }))

    await waitFor(() => {
      expect(api.endConference).toHaveBeenCalledWith('c-1')
    })
  })

  it('отказ действия — нейтральная строка по коду Problem (403 moderator_required), состояние не катится вручную', async () => {
    api.updateParticipantRestrictions.mockRejectedValue(problemWith('moderator_required'))
    render(<ModerationPanel conferenceId="c-1" />)

    fireEvent.click(
      within(rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')).getByRole('button', {
        name: 'Отключить микрофон',
      }),
    )

    expect(await screen.findByText('Недостаточно прав')).toBeInTheDocument()
  })

  it('цель ушла (404 participant_not_found) — нейтральная строка «уже не в конференции»', async () => {
    api.kickParticipant.mockRejectedValue(problemWith('participant_not_found', 404))
    render(<ModerationPanel conferenceId="c-1" />)

    fireEvent.click(
      within(rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')).getByRole('button', {
        name: 'Исключить',
      }),
    )
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )

    expect(await screen.findByText('Участник уже не в конференции')).toBeInTheDocument()
  })

  it('панель следует broadcast-у роли live: назначение №50 открывает панель, снятие — скрывает (Q9=A)', () => {
    const room = defaultRoom()
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: room[0] })
    lkc.useParticipants.mockReturnValue(room)
    const { rerender } = render(<ModerationPanel conferenceId="c-1" />)
    expect(screen.getByText('Модерация')).toBeInTheDocument()

    // UpdateParticipant §4: broadcast metadata снятия роли самому себе
    const demoted = { ...room[0], metadata: memberMetadata('participant') }
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: demoted })
    lkc.useParticipants.mockReturnValue([demoted, room[1]])
    rerender(<ModerationPanel conferenceId="c-1" />)
    expect(screen.queryByText('Модерация')).not.toBeInTheDocument()

    // повторное назначение — панель возвращается
    const promoted = { ...room[0], metadata: memberMetadata('moderator') }
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: promoted })
    lkc.useParticipants.mockReturnValue([promoted, room[1]])
    rerender(<ModerationPanel conferenceId="c-1" />)
    expect(screen.getByText('Модерация')).toBeInTheDocument()
  })
})
