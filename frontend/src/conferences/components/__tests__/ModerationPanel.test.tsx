import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 009, US3, T107 — объединённая панель участников: оркестрация
 * модерационных действий (FR-008, №48–№53) поверх кебаб-меню строки и
 * футера панели (демонтаж отдельной панели модерации T042). №49
 * отправляет ПОЛНОЕ SET-состояние (выведенное из фактических grants
 * цели), №50 — противоположную роль, №51/№48/№53 — только после
 * явного подтверждения в МОДАЛИ T109 (единая оболочка ui/ModalShell
 * + ui/ConfirmDialog поверх комнаты: затемнение, Esc, фокус-менеджмент
 * — вместо инлайн-блока в узком сайдбаре). Инструменты видны только
 * роли модератора локального
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
 * T081/T113 (US4): футер панели — standalone-кнопка «Гостевая ссылка»
 * (рамка-обёртка fieldset демонтирована) → модаль формы 'guest-invite'
 * (единая оболочка ModalShell): первое открытие генерит №52, повторные
 * показывают ту же ссылку БЕЗ вызова (GET статуса в контракте нет),
 * «Копировать» через navigator.clipboard, «Отозвать» — с подтверждением
 * T109 (переключением formId в той же подложке; деструктивное —
 * отключение гостей) и молчаливым коллапсом по
 * `404 guest_invite_not_found` (идемпотентность, Q21=A).
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

/**
 * T109: модаль подтверждения деструктивного действия — единая
 * оболочка (role=dialog + подложка .modal-back.show поверх комнаты),
 * кнопки «Отмена»/действие (danger-акцент).
 */
function confirmDialog(): HTMLElement {
  expect(document.querySelector('.modal-back.show')).not.toBeNull()
  return screen.getByRole('dialog')
}

/** Кнопка действия в модали подтверждения (danger-акцент). */
function confirmAction(label: string): HTMLElement {
  return within(confirmDialog()).getByRole('button', { name: label })
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

  it('№51 кик — деструктивное: пункт меню открывает МОДАЛЬ (T109), №51 вызывается только после действия в ней; Esc/«Отмена» закрывают без вызова, фокус возвращается на кебаб', async () => {
    api.kickParticipant.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)

    // инициатор — кебаб строки цели (браузер фокусирует кнопку по клику)
    const kebab = within(rowOf('bob')).getByRole('button', {
      name: 'Действия с участником bob',
    })
    kebab.focus()
    openMenu('bob')
    pickItem('Исключить bob')

    // модаль поверх комнаты (T109): единая оболочка, текст T042,
    // вход фокуса — безопасная «Отмена» (008)
    const dialog = confirmDialog()
    expect(within(dialog).getByText(/Исключить bob/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Отмена' })).toHaveFocus()
    expect(api.kickParticipant).not.toHaveBeenCalled()

    // Esc закрывает модаль без вызова; фокус — на инициаторе-кебабе
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.kickParticipant).not.toHaveBeenCalled()
    expect(kebab).toHaveFocus()

    // повтор + действие в модали («Исключить», danger) → №51
    openMenu('bob')
    pickItem('Исключить bob')
    fireEvent.click(confirmAction('Исключить'))
    await waitFor(() => {
      expect(api.kickParticipant).toHaveBeenCalledWith('c-1', BOB_ID)
    })
  })

  it('№48 завершение конференции (футер) — деструктивное: модаль T109, действие «Завершить»; «Отмена» — без вызова, фокус возвращается на кнопку футера', async () => {
    api.endConference.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)

    const endButton = screen.getByRole('button', { name: 'Завершить конференцию' })
    endButton.focus()
    fireEvent.click(endButton)

    const dialog = confirmDialog()
    expect(within(dialog).getByText(/Завершить конференцию для всех/)).toBeInTheDocument()
    expect(api.endConference).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.endConference).not.toHaveBeenCalled()
    expect(endButton).toHaveFocus()

    fireEvent.click(endButton)
    fireEvent.click(confirmAction('Завершить'))
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
    fireEvent.click(confirmAction('Исключить'))

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

describe('объединённая панель: гостевая ссылка №52/№53 — кнопка + модаль (009 US4 T081/T107/T113)', () => {
  /** Успешный №52-ответ: публичный маршрут /join/{nonce} + срок TTL. */
  function inviteView(nonce: string): { inviteUrl: string; expiresAt: string } {
    return { inviteUrl: `https://app.example/join/${nonce}`, expiresAt: '2026-10-22T10:00:00Z' }
  }

  /**
   * T113: модаль гостевой ссылки — та же единая оболочка .modal-back.show
   * (formId 'guest-invite', stackPolicy одна на приложение), доступное имя
   * диалога — заголовок «Гостевая ссылка».
   */
  function inviteDialog(): HTMLElement {
    expect(document.querySelector('.modal-back.show')).not.toBeNull()
    return screen.getByRole('dialog', { name: 'Гостевая ссылка' })
  }

  /** Открыть модаль кнопкой футера; первое открытие генерит №52 (nonce). */
  async function openInviteModal(nonce: string): Promise<void> {
    api.createGuestInvite.mockResolvedValueOnce(inviteView(nonce))
    fireEvent.click(screen.getByRole('button', { name: 'Гостевая ссылка' }))
    expect(
      await within(inviteDialog()).findByDisplayValue(`https://app.example/join/${nonce}`),
    ).toBeInTheDocument()
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

  it('(а) первое открытие — №52 + модаль со ссылкой/сроком; hint Q17 демонтирован (перегенерации тем же путём больше нет)', async () => {
    const { container } = render(<ParticipantsPanel conferenceId="c-1" />)

    // по монтажу модали нет (GET статуса ссылки в контракте нет)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Пригласить гостя' })).not.toBeInTheDocument()

    await openInviteModal('nonce-1')

    await waitFor(() => {
      expect(api.createGuestInvite).toHaveBeenCalledWith('c-1')
    })
    const dialog = inviteDialog()
    // срок — локальным временем (ui/time)
    expect(within(dialog).getByText(/^Действует до /)).toBeInTheDocument()
    // инлайн-разворот в футере демонтирован: содержимое — ТОЛЬКО в модали
    expect(container.querySelector('fieldset.conference-guest-invite')).toBeNull()
    expect(screen.queryByText(/отзовёт предыдущую/i)).not.toBeInTheDocument()
  })

  it('(б) закрытие «Отмена» — без вызовов API и без изменений состояния; повторное открытие — БЕЗ №52, та же ссылка', async () => {
    api.revokeGuestInvite.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)
    await openInviteModal('nonce-1')

    fireEvent.click(within(inviteDialog()).getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.revokeGuestInvite).not.toHaveBeenCalled()

    // повторное открытие: ссылка живёт в стейте панели — №52 НЕ дергается
    fireEvent.click(screen.getByRole('button', { name: 'Гостевая ссылка' }))
    expect(
      await within(inviteDialog()).findByDisplayValue('https://app.example/join/nonce-1'),
    ).toBeInTheDocument()
    expect(api.createGuestInvite).toHaveBeenCalledTimes(1)
  })

  it('(б′) закрытие кликом по фону (press+release вне окна) — тот же исход: без API, состояние сохранено', async () => {
    render(<ParticipantsPanel conferenceId="c-1" />)
    await openInviteModal('nonce-1')

    const back = document.querySelector('.modal-back.show')
    expect(back).not.toBeNull()
    fireEvent.mouseDown(back as Element, { button: 0 })
    fireEvent.mouseUp(back as Element, { button: 0 })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.revokeGuestInvite).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Гостевая ссылка' }))
    expect(
      await within(inviteDialog()).findByDisplayValue('https://app.example/join/nonce-1'),
    ).toBeInTheDocument()
    expect(api.createGuestInvite).toHaveBeenCalledTimes(1)
  })

  it('(в) «Копировать» → clipboard.writeText(inviteUrl) и статус «Скопировано» (output aria-live)', async () => {
    clipboard.writeText.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)
    await openInviteModal('nonce-1')

    fireEvent.click(within(inviteDialog()).getByRole('button', { name: 'Копировать' }))

    await waitFor(() => {
      expect(clipboard.writeText).toHaveBeenCalledWith('https://app.example/join/nonce-1')
    })
    expect(await within(inviteDialog()).findByText('Скопировано')).toBeInTheDocument()
  })

  it('(г) «Отозвать» — подтверждение T109 поверх (одна подложка, formId-переключение); отмена подтверждения возвращает ссылку; действие → №53 → модаль закрыта', async () => {
    api.revokeGuestInvite.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)
    await openInviteModal('nonce-1')

    fireEvent.click(within(inviteDialog()).getByRole('button', { name: 'Отозвать' }))

    // подтверждение T109 — в ТОЙ ЖЕ оболочке (вторая подложка не рождается)
    const dialog = screen.getByRole('dialog', { name: 'Отозвать гостевую ссылку' })
    expect(document.querySelectorAll('.modal-back.show')).toHaveLength(1)
    expect(
      within(dialog).getByText(/Все вошедшие по ней гости отключатся немедленно/),
    ).toBeInTheDocument()
    expect(api.revokeGuestInvite).not.toHaveBeenCalled()

    // «Отмена» подтверждения — возврат к ссылке, без вызова
    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    expect(
      await within(inviteDialog()).findByDisplayValue('https://app.example/join/nonce-1'),
    ).toBeInTheDocument()
    expect(api.revokeGuestInvite).not.toHaveBeenCalled()

    // действие в подтверждении → №53 → успех закрывает модаль целиком
    fireEvent.click(within(inviteDialog()).getByRole('button', { name: 'Отозвать' }))
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Отозвать гостевую ссылку' })).getByRole('button', {
        name: 'Отозвать',
      }),
    )
    await waitFor(() => {
      expect(api.revokeGuestInvite).toHaveBeenCalledWith('c-1')
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })

  it('(д) после отзыва повторное открытие — НОВЫЙ №52 и новая ссылка (null-состояние генерит заново)', async () => {
    api.revokeGuestInvite.mockResolvedValue(undefined)
    render(<ParticipantsPanel conferenceId="c-1" />)
    await openInviteModal('nonce-1')

    fireEvent.click(within(inviteDialog()).getByRole('button', { name: 'Отозвать' }))
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Отозвать гостевую ссылку' })).getByRole('button', {
        name: 'Отозвать',
      }),
    )
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    await openInviteModal('nonce-2')
    expect(api.createGuestInvite).toHaveBeenCalledTimes(2)
    expect(
      within(inviteDialog()).getByDisplayValue('https://app.example/join/nonce-2'),
    ).toBeInTheDocument()
  })

  it('(е) 404 guest_invite_not_found на №53 — идемпотентный успех: модаль закрыта, без notice (Q21=A)', async () => {
    render(<ParticipantsPanel conferenceId="c-1" />)
    await openInviteModal('nonce-1')

    api.revokeGuestInvite.mockRejectedValueOnce(problemWith('guest_invite_not_found', 404))
    fireEvent.click(within(inviteDialog()).getByRole('button', { name: 'Отозвать' }))
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Отозвать гостевую ссылку' })).getByRole('button', {
        name: 'Отозвать',
      }),
    )
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(document.querySelector('.conference-moderation-notice')).toBeNull()

    // ссылка сброшена — следующее открытие генерит заново
    await openInviteModal('nonce-2')
    expect(api.createGuestInvite).toHaveBeenCalledTimes(2)
  })

  it('(ж) не-модератор кнопки и модали не видит (гейт инструментов панели)', () => {
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

    expect(screen.queryByRole('button', { name: 'Гостевая ссылка' })).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.createGuestInvite).not.toHaveBeenCalled()
  })

  it('(з) отказы №52 — нейтральные строки 403/409, модаль закрыта без ссылки', async () => {
    render(<ParticipantsPanel conferenceId="c-1" />)

    api.createGuestInvite.mockRejectedValueOnce(problemWith('moderator_required'))
    fireEvent.click(screen.getByRole('button', { name: 'Гостевая ссылка' }))
    await waitFor(() => {
      expect(screen.getByText('Недостаточно прав')).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    api.createGuestInvite.mockRejectedValueOnce(problemWith('conference_ended', 409))
    fireEvent.click(screen.getByRole('button', { name: 'Гостевая ссылка' }))
    await waitFor(() => {
      expect(screen.getByText('Конференция завершена')).toBeInTheDocument()
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('(и) «в полёте» кнопки недоступны — повторные вызовы блокированы (единый pending панели)', async () => {
    render(<ParticipantsPanel conferenceId="c-1" />)

    // №52 в полёте: повторный клик по «Гостевая ссылка» не плодит вызовы
    let resolveCreate: (value: { inviteUrl: string; expiresAt: string }) => void = () => {}
    api.createGuestInvite.mockReturnValue(
      new Promise((resolve) => {
        resolveCreate = resolve
      }),
    )
    const footerButton = screen.getByRole('button', { name: 'Гостевая ссылка' })
    fireEvent.click(footerButton)
    expect(footerButton).toBeDisabled()
    fireEvent.click(footerButton)
    expect(api.createGuestInvite).toHaveBeenCalledTimes(1)

    resolveCreate(inviteView('nonce-1'))
    expect(
      await within(inviteDialog()).findByDisplayValue('https://app.example/join/nonce-1'),
    ).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Гостевая ссылка' })).toBeEnabled()
    })

    // №53 в полёте: кнопки модали недоступны
    let resolveRevoke: (value: undefined) => void = () => {}
    api.revokeGuestInvite.mockReturnValue(
      new Promise((resolve) => {
        resolveRevoke = resolve
      }),
    )
    fireEvent.click(within(inviteDialog()).getByRole('button', { name: 'Отозвать' }))
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Отозвать гостевую ссылку' })).getByRole('button', {
        name: 'Отозвать',
      }),
    )
    await waitFor(() => {
      expect(api.revokeGuestInvite).toHaveBeenCalledTimes(1)
    })
    const dialog = inviteDialog()
    expect(within(dialog).getByRole('button', { name: 'Копировать' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Отозвать' })).toBeDisabled()

    resolveRevoke(undefined)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})
