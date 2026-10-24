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
 *
 * T094: тумблеры источников — ПОСТОЯННЫЕ подписи («Микрофон»/
 * «Камера»/«Экран», мастер «Все источники» = прежнее «право слова»)
 * с aria-pressed по проекции grants; SET-семантика: точечный клик
 * при отжатом мастере — flip одного поля, нажатие мастера
 * нормализует точки в speech, отжатие включает всё; третья точечная
 * (фикс grants canPublish=false) НЕ сбрасывает отключения — все три
 * остаются нажатыми и мастер нажат.
 *
 * T081 (US4): блок «Гостевая ссылка» №52/№53 — генерация readonly-
 * поля inviteUrl (срок + предупреждение Q17=A/Q13=B), «Копировать»
 * через navigator.clipboard, отзыв с подтверждением (деструктивное —
 * отключение гостей) и молчаливый коллапс по `404
 * guest_invite_not_found` (идемпотентность отзыва, Q21=A).
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
  createGuestInvite: vi.fn(),
  revokeGuestInvite: vi.fn(),
}))

vi.mock('../../api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/conferences')>()),
  endConference: api.endConference,
  kickParticipant: api.kickParticipant,
  updateParticipantRestrictions: api.updateParticipantRestrictions,
  updateParticipantRole: api.updateParticipantRole,
  createGuestInvite: api.createGuestInvite,
  revokeGuestInvite: api.revokeGuestInvite,
}))

/** navigator.clipboard (T081 «Копировать») — jsdom его не реализует. */
const clipboard = vi.hoisted(() => ({ writeText: vi.fn() }))

function installClipboard(): void {
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText: clipboard.writeText },
    configurable: true,
  })
}

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
    api.createGuestInvite.mockReset()
    api.revokeGuestInvite.mockReset()
    clipboard.writeText.mockReset()
    installClipboard()
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

  it('модератор: строки участников с действиями, включая себя (равноправие, Q21=A); тумблеры №49 — постоянные подписи (T094)', () => {
    render(<ModerationPanel conferenceId="c-1" />)

    expect(screen.getByText('Модерация')).toBeInTheDocument()
    expect(screen.getByText('alice (вы)')).toBeInTheDocument()
    expect(screen.getByText('bob')).toBeInTheDocument()
    // обе строки (в т.ч. своя и вторая) несут полный набор действий №49–№51;
    // тумблеры источников — постоянные подписи + aria-pressed по проекции
    for (const identity of [
      'u:11111111-1111-1111-1111-111111111111:conn-1',
      'u:22222222-2222-4222-8222-222222222222:conn-2',
    ]) {
      const row = rowOf(identity)
      expect(within(row).getByRole('button', { name: 'Микрофон' })).toHaveAttribute(
        'aria-pressed',
        'false',
      )
      expect(within(row).getByRole('button', { name: 'Камера' })).toHaveAttribute(
        'aria-pressed',
        'false',
      )
      expect(within(row).getByRole('button', { name: 'Экран' })).toHaveAttribute(
        'aria-pressed',
        'false',
      )
      expect(within(row).getByRole('button', { name: 'Все источники' })).toHaveAttribute(
        'aria-pressed',
        'false',
      )
      expect(within(row).getByRole('button', { name: 'Исключить' })).toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: 'Назначить модератором' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeInTheDocument()
  })

  it('№49 точечный тумблер: полное SET-состояние из grants цели с переключённым одним полем (мастер отжат — проекция точна)', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    render(<ModerationPanel conferenceId="c-1" />)

    fireEvent.click(
      within(rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')).getByRole('button', {
        name: 'Микрофон',
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

  it('№49 тумблеры отражают действующие ограничения (aria-pressed); возврат микрофона сохраняет прочие состояния', async () => {
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
    expect(within(row).getByRole('button', { name: 'Микрофон' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(within(row).getByRole('button', { name: 'Камера' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
    expect(within(row).getByRole('button', { name: 'Экран' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(within(row).getByRole('button', { name: 'Все источники' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )

    // возврат микрофона: SET с mic=false, прочие состояния сохранены
    fireEvent.click(within(row).getByRole('button', { name: 'Микрофон' }))
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

  it('№49 мастер «Все источники» (Q4=A): нажатие нормализует точки в speech; точечные под мастером нажаты и недоступны с пояснением', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    const { rerender } = render(<ModerationPanel conferenceId="c-1" />)

    const row = rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')
    fireEvent.click(within(row).getByRole('button', { name: 'Все источники' }))

    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith(
        'c-1',
        'u:22222222-2222-4222-8222-222222222222:conn-2',
        {
          micRestricted: false,
          cameraRestricted: false,
          screenRestricted: false,
          speechRestricted: true,
        },
      )
    })

    // после применения (broadcast permissions → canPublish=false): все
    // четыре тумблера нажаты, точечные недоступны с доступным пояснением
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])
    rerender(<ModerationPanel conferenceId="c-1" />)

    const appliedRow = rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')
    for (const label of ['Микрофон', 'Камера', 'Экран', 'Все источники']) {
      expect(within(appliedRow).getByRole('button', { name: label })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
    }
    for (const label of ['Микрофон', 'Камера', 'Экран']) {
      const point = within(appliedRow).getByRole('button', { name: label })
      expect(point).toBeDisabled()
      expect(point).toHaveAttribute('title', 'Блокируется кнопкой «Все источники»')
    }
    // мастер остаётся доступным — отжатие включает всё
    fireEvent.click(within(appliedRow).getByRole('button', { name: 'Все источники' }))
    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenLastCalledWith(
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

  it('№49 третья точечная не сбрасывает отключения (регресс бага T094): mic+camera+screen → grants canPublish=false, все три нажаты И мастер нажат', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    // цель: микрофон и камера уже срезаны (экран разрешён)
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: true, canPublishSources: [SCREEN_SHARE, SCREEN_SHARE_AUDIO] },
      }),
    ])
    const { rerender } = render(<ModerationPanel conferenceId="c-1" />)

    const row = rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')
    expect(within(row).getByRole('button', { name: 'Экран' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )

    // третья точечная: SET экрана поверх двух действующих
    fireEvent.click(within(row).getByRole('button', { name: 'Экран' }))
    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith(
        'c-1',
        'u:22222222-2222-4222-8222-222222222222:conn-2',
        {
          micRestricted: true,
          cameraRestricted: true,
          screenRestricted: true,
          speechRestricted: false,
        },
      )
    })

    // сервер применил (фикс T094): остаток-множество пуст → grants
    // canPublish=false → проекция all-four-true — сброса не происходит
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: 'u:22222222-2222-4222-8222-222222222222:conn-2',
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])
    rerender(<ModerationPanel conferenceId="c-1" />)

    const appliedRow = rowOf('u:22222222-2222-4222-8222-222222222222:conn-2')
    for (const label of ['Микрофон', 'Камера', 'Экран', 'Все источники']) {
      expect(within(appliedRow).getByRole('button', { name: label })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
    }
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
        name: 'Микрофон',
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

describe('ModerationPanel: гостевая ссылка №52/№53 (009 US4 T081)', () => {
  /** Успешный №52-ответ: публичный маршрут /join/{nonce} + срок TTL. */
  function inviteView(nonce: string): { inviteUrl: string; expiresAt: string } {
    return { inviteUrl: `https://app.example/join/${nonce}`, expiresAt: '2026-10-22T10:00:00Z' }
  }

  /** Генерация №52 по кнопке блока → поле ссылки видно. */
  async function generateInvite(nonce: string): Promise<void> {
    api.createGuestInvite.mockResolvedValueOnce(inviteView(nonce))
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))
    expect(await screen.findByDisplayValue(`https://app.example/join/${nonce}`)).toBeInTheDocument()
  }

  beforeEach(() => {
    lkc.useParticipants.mockReset()
    lkc.useLocalParticipant.mockReset()
    api.createGuestInvite.mockReset()
    api.revokeGuestInvite.mockReset()
    clipboard.writeText.mockReset()
    installClipboard()
    lkc.useLocalParticipant.mockReturnValue({
      localParticipant: defaultRoom()[0],
    })
    lkc.useParticipants.mockReturnValue(defaultRoom())
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('(а) по монтажу блок свёрнут; модератор: «Пригласить гостя» → №52 → ссылка/срок/предупреждение', async () => {
    const { container } = render(<ModerationPanel conferenceId="c-1" />)

    // свёрнуто: только кнопка генерации, без поля (GET статуса в контракте нет)
    expect(screen.getByRole('button', { name: 'Пригласить гостя' })).toBeInTheDocument()
    expect(container.querySelector('.conference-guest-invite-url')).toBeNull()

    api.createGuestInvite.mockResolvedValueOnce(inviteView('nonce-1'))
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))

    await waitFor(() => {
      expect(api.createGuestInvite).toHaveBeenCalledWith('c-1')
    })
    // ссылка, срок (локальное время) и предупреждение Q17=A/Q13=B
    expect(await screen.findByDisplayValue('https://app.example/join/nonce-1')).toBeInTheDocument()
    expect(screen.getByText(/^Действует до /)).toBeInTheDocument()
    expect(screen.getByText(/отзовёт предыдущую/i)).toBeInTheDocument()
  })

  it('(б) «Копировать» → clipboard.writeText(inviteUrl) и статус «Скопировано» (output aria-live)', async () => {
    clipboard.writeText.mockResolvedValue(undefined)
    render(<ModerationPanel conferenceId="c-1" />)
    await generateInvite('nonce-1')

    fireEvent.click(screen.getByRole('button', { name: 'Копировать' }))

    await waitFor(() => {
      expect(clipboard.writeText).toHaveBeenCalledWith('https://app.example/join/nonce-1')
    })
    expect(await screen.findByText('Скопировано')).toBeInTheDocument()
    expect(document.querySelector('.conference-guest-invite-copied')).not.toBeNull()
  })

  it('(в) «Отозвать ссылку» — деструктивное: подтверждение упоминает отключение гостей; Отмена не вызывает; Подтвердить → №53', async () => {
    api.revokeGuestInvite.mockResolvedValue(undefined)
    render(<ModerationPanel conferenceId="c-1" />)
    await generateInvite('nonce-1')

    fireEvent.click(screen.getByRole('button', { name: 'Отозвать ссылку' }))

    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByText(/гост[ейы]/i)).toBeInTheDocument()
    expect(within(dialog).getByText(/отключ/i)).toBeInTheDocument()
    expect(api.revokeGuestInvite).not.toHaveBeenCalled()

    // «Отмена» закрывает подтверждение без вызова
    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(api.revokeGuestInvite).not.toHaveBeenCalled()

    // повтор + «Подтвердить» → №53
    fireEvent.click(screen.getByRole('button', { name: 'Отозвать ссылку' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )
    await waitFor(() => {
      expect(api.revokeGuestInvite).toHaveBeenCalledWith('c-1')
    })
  })

  it('(г) успешный отзыв — блок свёрнут; 404 guest_invite_not_found — молчаливый коллапс без notice (Q21=A)', async () => {
    const { container } = render(<ModerationPanel conferenceId="c-1" />)
    await generateInvite('nonce-1')

    api.revokeGuestInvite.mockResolvedValueOnce(undefined)
    fireEvent.click(screen.getByRole('button', { name: 'Отозвать ссылку' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )
    await waitFor(() => {
      expect(container.querySelector('.conference-guest-invite-url')).toBeNull()
    })
    expect(screen.getByRole('button', { name: 'Пригласить гостя' })).toBeInTheDocument()

    // ссылку мог отозвать другой модератор — идемпотентность без notice
    await generateInvite('nonce-2')
    api.revokeGuestInvite.mockRejectedValueOnce(problemWith('guest_invite_not_found', 404))
    fireEvent.click(screen.getByRole('button', { name: 'Отозвать ссылку' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )
    await waitFor(() => {
      expect(container.querySelector('.conference-guest-invite-url')).toBeNull()
    })
    expect(container.querySelector('.conference-moderation-notice')).toBeNull()
  })

  it('(д) повторная генерация при живой ссылке — та же кнопка №52, поле заменено новым inviteUrl', async () => {
    render(<ModerationPanel conferenceId="c-1" />)
    await generateInvite('nonce-1')

    api.createGuestInvite.mockResolvedValueOnce(inviteView('nonce-2'))
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))

    expect(await screen.findByDisplayValue('https://app.example/join/nonce-2')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('https://app.example/join/nonce-1')).not.toBeInTheDocument()
    expect(api.createGuestInvite).toHaveBeenCalledTimes(2)
  })

  it('(е) не-модератор блока не видит (наследованный гейт — панель null)', () => {
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

    render(<ModerationPanel conferenceId="c-1" />)

    expect(screen.queryByRole('button', { name: 'Пригласить гостя' })).not.toBeInTheDocument()
    expect(api.createGuestInvite).not.toHaveBeenCalled()
  })

  it('(ж) отказы №52 — нейтральные строки: 403 moderator_required и 409 conference_ended', async () => {
    render(<ModerationPanel conferenceId="c-1" />)

    api.createGuestInvite.mockRejectedValueOnce(problemWith('moderator_required'))
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))
    expect(await screen.findByText('Недостаточно прав')).toBeInTheDocument()

    api.createGuestInvite.mockRejectedValueOnce(problemWith('conference_ended', 409))
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))
    expect(await screen.findByText('Конференция завершена')).toBeInTheDocument()

    // ссылка не появилась — блок остался свёрнут
    expect(document.querySelector('.conference-guest-invite-url')).toBeNull()
  })

  it('(з) «в полёте» кнопки блока недоступны — повторные вызовы блокированы (единый pending панели)', async () => {
    render(<ModerationPanel conferenceId="c-1" />)

    // №52 в полёте: повторный клик по «Пригласить гостя» не плодит вызовы
    let resolveCreate: (value: { inviteUrl: string; expiresAt: string }) => void = () => {}
    api.createGuestInvite.mockReturnValue(
      new Promise((resolve) => {
        resolveCreate = resolve
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))
    expect(screen.getByRole('button', { name: 'Пригласить гостя' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))
    expect(api.createGuestInvite).toHaveBeenCalledTimes(1)

    resolveCreate(inviteView('nonce-1'))
    expect(await screen.findByDisplayValue('https://app.example/join/nonce-1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Пригласить гостя' })).toBeEnabled()

    // №53 в полёте: все кнопки блока недоступны
    let resolveRevoke: (value: undefined) => void = () => {}
    api.revokeGuestInvite.mockReturnValue(
      new Promise((resolve) => {
        resolveRevoke = resolve
      }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Отозвать ссылку' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )
    await waitFor(() => {
      expect(api.revokeGuestInvite).toHaveBeenCalledTimes(1)
    })
    expect(screen.getByRole('button', { name: 'Пригласить гостя' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Копировать' })).toBeDisabled()

    resolveRevoke(undefined)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Пригласить гостя' })).toBeEnabled()
    })
  })
})
