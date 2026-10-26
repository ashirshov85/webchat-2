import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 009, US3, T107 — объединённая панель участников: оркестрация
 * модерационных действий (FR-008, №48–№53) поверх кебаб-меню строки и
 * футера панели (демонтаж отдельной панели модерации T042). №49
 * отправляет ПОЛНОЕ SET-состояние (выведенное из фактических grants
 * цели), №50 — противоположную роль, №51/№48 — только после явного
 * подтверждения (деструктивные; инлайн-блок до переноса в модаль
 * T109). Инструменты видны только роли модератора локального
 * подключения (metadata) и живьём следуют broadcast'у смены роли
 * (Q9=A). Примитивы @livekit/components-react и API-клиент заменены
 * фейками: предмет теста — оркестрация действий и подтверждения.
 *
 * T094: подписи пунктов №49 — от текущего SET-состояния
 * (restrictionMarks): точки «Отключить/Включить …» и мастер
 * «Отключить/Включить все источники» (доменное speechRestricted);
 * третья точечная (фикс grants canPublish=false) НЕ сбрасывает
 * отключения — меню показывает «Включить …» по всем трём точкам и
 * «Включить все источники» мастером.
 *
 * T081 (US4): футер панели — блок «Гостевая ссылка» №52/№53:
 * генерация readonly-поля inviteUrl (срок + предупреждение Q17=A/
 * Q13=B), «Копировать» через navigator.clipboard, отзыв с
 * подтверждением (деструктивное — отключение гостей) и молчаливый
 * коллапс по `404 guest_invite_not_found` (идемпотентность, Q21=A).
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
  readonly getTrackPublication?: (source: string) => { readonly isMuted: boolean } | undefined
}

const lkc = vi.hoisted(() => ({
  useParticipants: vi.fn(),
  useLocalParticipant: vi.fn(),
  useTracks: vi.fn(),
}))

vi.mock('@livekit/components-react', () => ({
  useParticipants: lkc.useParticipants,
  useLocalParticipant: lkc.useLocalParticipant,
  useTracks: lkc.useTracks,
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

import ParticipantsPanel from '../ParticipantsPanel'

const ALICE_ID = 'u:11111111-1111-1111-1111-111111111111:conn-1'
const BOB_ID = 'u:22222222-2222-4222-8222-222222222222:conn-2'

function participant(overrides: Partial<FakeParticipant> & { identity: string }): FakeParticipant {
  // T108: индикаторы строк читают публикации mic/cam — без треков «выкл»
  return {
    isLocal: false,
    name: 'Имя',
    getTrackPublication: () => undefined,
    ...overrides,
  }
}

function memberMetadata(role: 'moderator' | 'participant'): string {
  return JSON.stringify({ role, guest: false })
}

/** Модератор-владелец панели + удалённая цель с полными правами. */
function defaultRoom(): FakeParticipant[] {
  return [
    participant({
      identity: ALICE_ID,
      name: 'alice',
      metadata: memberMetadata('moderator'),
      isLocal: true,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }),
    participant({
      identity: BOB_ID,
      name: 'bob',
      metadata: memberMetadata('participant'),
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }),
  ]
}

/** Строка панели по имени цели (кебаб — внутри строки). */
function rowOf(name: string): HTMLElement {
  const row = screen.getByText(name).closest('li')
  if (row === null) {
    throw new Error(`строка ${name} не отрендерена`)
  }
  return row
}

/** Открыть кебаб-меню строки цели. */
function openMenu(name: string): void {
  fireEvent.click(
    within(rowOf(name)).getByRole('button', { name: `Действия с участником ${name}` }),
  )
}

/** Выбрать пункт открытого меню. */
function pickItem(label: string): void {
  fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: label }))
}

/** Проблема-отказ домена (RFC 9457, errors: {field: [code]}). */
function problemWith(
  code: string,
  status = 403,
): { status: number; errors: Record<string, string[]> } {
  return { status, errors: { conference: [code] } }
}

describe('объединённая панель: модерация №49–№51 через кебаб (009 US3 T107)', () => {
  beforeEach(() => {
    lkc.useParticipants.mockReset()
    lkc.useLocalParticipant.mockReset()
    // T108: индикаторы источников строки — экран по screen-рефам useTracks
    lkc.useTracks.mockReset()
    lkc.useTracks.mockReturnValue([])
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

  it('№49 точечный пункт: полное SET-состояние из grants цели с переключённым одним полем (мастер отжат — проекция точна)', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    render(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    pickItem('Отключить микрофон')

    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith('c-1', BOB_ID, {
        micRestricted: true,
        cameraRestricted: false,
        screenRestricted: false,
        speechRestricted: false,
      })
    })
    // меню закрыто выбором пункта
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('№49 возврат срезанного источника сохраняет прочие состояния (SET по проекции grants)', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    // цель: микрофон и экран срезаны, камера разрешена
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: BOB_ID,
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: true, canPublishSources: [CAMERA] },
      }),
    ])
    render(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    pickItem('Включить микрофон')

    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith('c-1', BOB_ID, {
        micRestricted: false,
        cameraRestricted: false,
        screenRestricted: true,
        speechRestricted: false,
      })
    })
  })

  it('№49 мастер «Все источники» (Q4=A): нажатие нормализует точки в speech; отжатие (после canPublish=false) включает всё', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    const { rerender } = render(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    pickItem('Отключить все источники')

    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith('c-1', BOB_ID, {
        micRestricted: false,
        cameraRestricted: false,
        screenRestricted: false,
        speechRestricted: true,
      })
    })

    // сервер применил (broadcast permissions → canPublish=false): меню
    // цели показывает «Включить …» по точкам И мастером — сброса нет
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: BOB_ID,
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])
    rerender(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    // мастер нажат: точки — «Включить …» (эффективно «всё заблокировано»),
    // мастер — «Включить все источники» (truthful состав)
    expect(
      within(screen.getByRole('menu'))
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual([
      'Включить микрофон',
      'Включить камеру',
      'Включить экран',
      'Включить все источники',
      'Назначить модератором',
      'Исключить',
    ])

    // отжатие мастера включает всё
    pickItem('Включить все источники')
    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenLastCalledWith('c-1', BOB_ID, {
        micRestricted: false,
        cameraRestricted: false,
        screenRestricted: false,
        speechRestricted: false,
      })
    })
  })

  it('№49 третья точечная не сбрасывает отключения (регресс бага T094): mic+camera+screen → grants canPublish=false, меню держит «Включить …» по всем трём', async () => {
    api.updateParticipantRestrictions.mockResolvedValue({})
    // цель: микрофон и камера уже срезаны (экран разрешён)
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: BOB_ID,
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: true, canPublishSources: [SCREEN_SHARE, SCREEN_SHARE_AUDIO] },
      }),
    ])
    const { rerender } = render(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    pickItem('Отключить экран')

    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledWith('c-1', BOB_ID, {
        micRestricted: true,
        cameraRestricted: true,
        screenRestricted: true,
        speechRestricted: false,
      })
    })
    // панель успокоилась (pending снят) ДО смены проекции grants —
    // детерминизм повторного открытия меню
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeEnabled()
    })

    // сервер применил (фикс T094): остаток-множество пуст → grants
    // canPublish=false → проекция all-four-true — сброса не происходит
    lkc.useParticipants.mockReturnValue([
      defaultRoom()[0],
      participant({
        identity: BOB_ID,
        name: 'bob',
        metadata: memberMetadata('participant'),
        permissions: { canPublish: false, canPublishSources: [] },
      }),
    ])
    rerender(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    // состав меню цели: все точки «Включить …» И мастер «Включить все
    // источники» — сброса ограничений не произошло
    expect(
      within(screen.getByRole('menu'))
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual([
      'Включить микрофон',
      'Включить камеру',
      'Включить экран',
      'Включить все источники',
      'Назначить модератором',
      'Исключить',
    ])
  })

  it('№50 назначение/снятие модератора через меню: противоположная роль цели — в т.ч. строки другого модератора и себя (Q21=A)', async () => {
    api.updateParticipantRole.mockResolvedValue({})
    render(<ParticipantsPanel conferenceId="c-1" />)

    // цель-участник → назначение модератором
    openMenu('bob')
    pickItem('Назначить модератором')
    await waitFor(() => {
      expect(api.updateParticipantRole).toHaveBeenCalledWith('c-1', BOB_ID, 'moderator')
    })

    // цель-модератор (собственная строка) → снятие, включая с себя (равноправие, Q21=A)
    openMenu('alice')
    pickItem('Снять модератора')
    await waitFor(() => {
      expect(api.updateParticipantRole).toHaveBeenCalledWith('c-1', ALICE_ID, 'participant')
    })
  })

  it('№51 кик — деструктивное: подтверждение обязательно, №51 вызывается только после «Подтвердить»', async () => {
    api.kickParticipant.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    pickItem('Исключить bob')

    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByText(/Исключить bob/)).toBeInTheDocument()
    expect(api.kickParticipant).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Подтвердить' }))

    await waitFor(() => {
      expect(api.kickParticipant).toHaveBeenCalledWith('c-1', BOB_ID)
    })
  })

  it('№48 завершение конференции (футер) — деструктивное: подтверждение обязательно; «Отмена» — без вызова', async () => {
    api.endConference.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)

    fireEvent.click(screen.getByRole('button', { name: 'Завершить конференцию' }))

    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).getByText(/Завершить конференцию для всех/)).toBeInTheDocument()
    expect(api.endConference).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(api.endConference).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Завершить конференцию' }))
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )
    await waitFor(() => {
      expect(api.endConference).toHaveBeenCalledWith('c-1')
    })
  })

  it('отказ действия — нейтральная строка по коду Problem (403 moderator_required), состояние не катится вручную', async () => {
    api.updateParticipantRestrictions.mockRejectedValue(problemWith('moderator_required'))
    render(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    pickItem('Отключить микрофон')

    expect(await screen.findByText('Недостаточно прав')).toBeInTheDocument()
  })

  it('цель ушла (404 participant_not_found) — нейтральная строка «уже не в конференции»', async () => {
    api.kickParticipant.mockRejectedValue(problemWith('participant_not_found', 404))
    render(<ParticipantsPanel conferenceId="c-1" />)

    openMenu('bob')
    pickItem('Исключить bob')
    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Подтвердить' }),
    )

    expect(await screen.findByText('Участник уже не в конференции')).toBeInTheDocument()
  })

  it('«в полёте» кебаби и кнопки футера недоступны — повторные вызовы блокированы (единый pending)', async () => {
    render(<ParticipantsPanel conferenceId="c-1" />)

    // №49 в полёте: кебаби строк disabled, футер недоступен
    let resolveUpdate: (value: unknown) => void = () => {}
    api.updateParticipantRestrictions.mockReturnValue(
      new Promise((resolve) => {
        resolveUpdate = resolve
      }),
    )
    openMenu('bob')
    pickItem('Отключить микрофон')
    await waitFor(() => {
      expect(api.updateParticipantRestrictions).toHaveBeenCalledTimes(1)
    })
    for (const name of ['alice', 'bob']) {
      expect(
        within(rowOf(name)).getByRole('button', { name: `Действия с участником ${name}` }),
      ).toBeDisabled()
    }
    expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeDisabled()

    resolveUpdate({})
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Завершить конференцию' })).toBeEnabled()
    })
  })
})

describe('объединённая панель: гостевая ссылка №52/№53 в футере (009 US4 T081/T107)', () => {
  /** Успешный №52-ответ: публичный маршрут /join/{nonce} + срок TTL. */
  function inviteView(nonce: string): { inviteUrl: string; expiresAt: string } {
    return { inviteUrl: `https://app.example/join/${nonce}`, expiresAt: '2026-10-22T10:00:00Z' }
  }

  /** Генерация №52 по кнопке футера → поле ссылки видно. */
  async function generateInvite(nonce: string): Promise<void> {
    api.createGuestInvite.mockResolvedValueOnce(inviteView(nonce))
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))
    expect(await screen.findByDisplayValue(`https://app.example/join/${nonce}`)).toBeInTheDocument()
  }

  beforeEach(() => {
    lkc.useParticipants.mockReset()
    lkc.useLocalParticipant.mockReset()
    lkc.useTracks.mockReset()
    lkc.useTracks.mockReturnValue([])
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
    const { container } = render(<ParticipantsPanel conferenceId="c-1" />)

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
    render(<ParticipantsPanel conferenceId="c-1" />)
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
    render(<ParticipantsPanel conferenceId="c-1" />)
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
    const { container } = render(<ParticipantsPanel conferenceId="c-1" />)
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
    render(<ParticipantsPanel conferenceId="c-1" />)
    await generateInvite('nonce-1')

    api.createGuestInvite.mockResolvedValueOnce(inviteView('nonce-2'))
    fireEvent.click(screen.getByRole('button', { name: 'Пригласить гостя' }))

    expect(await screen.findByDisplayValue('https://app.example/join/nonce-2')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('https://app.example/join/nonce-1')).not.toBeInTheDocument()
    expect(api.createGuestInvite).toHaveBeenCalledTimes(2)
  })

  it('(е) не-модератор блока не видит (гейт инструментов панели)', () => {
    const room = defaultRoom()
    room[0] = participant({
      identity: ALICE_ID,
      name: 'alice',
      metadata: memberMetadata('participant'),
      isLocal: true,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    })
    lkc.useLocalParticipant.mockReturnValue({ localParticipant: room[0] })
    lkc.useParticipants.mockReturnValue(room)

    render(<ParticipantsPanel conferenceId="c-1" />)

    expect(screen.queryByRole('button', { name: 'Пригласить гостя' })).not.toBeInTheDocument()
    expect(api.createGuestInvite).not.toHaveBeenCalled()
  })

  it('(ж) отказы №52 — нейтральные строки: 403 moderator_required и 409 conference_ended', async () => {
    render(<ParticipantsPanel conferenceId="c-1" />)

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
    render(<ParticipantsPanel conferenceId="c-1" />)

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
