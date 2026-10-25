import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 009, US1, T029/T098 — MediaControls (T028 → T098, FR-004/FR-006):
 * стимпанк-панель кнопок-иконок по прототипу tmp/conference-controls.html —
 * оболочка machine (fieldset .conference-controls) + 4 круглые кнопки
 * («Микрофон»/«Камера»/«Экран»/«Покинуть»): текстовых узлов «имя»/«вкл/
 * выкл» внутри кнопок НЕТ (queryByText = 0), стабильный aria-label +
 * aria-pressed; состояние дублируется визуалом через data-state
 * (on = лампа + on-части иконки, off = диагональный штрих, blocked =
 * приглушение + замок-бейдж — маппинг data-state → визуал пинится
 * статическим CSS-тестом conferences-css.test.ts). Тумблеры — проекция
 * фактических публикаций localParticipant, клик — целевой сеттер
 * LiveKit; все тумблеры недоступны в полёте переключения и вне
 * подключённого состояния («Покинуть» доступна всегда — компонентный
 * паритет двух режимов, пересмотр скрытия T089); отказ переключения —
 * нейтральная строка без деталей, отмена браузерного пикера экрана
 * (AbortError) — не ошибка.
 *
 * 009, US3, T043/T097/T098 — собственные ограничения (FR-007/FR-008,
 * SC-005): тумблер заблокирован по live-grants подключения (№49 →
 * UpdateParticipant) И/ИЛИ снимку №46 MeView (OR-слияние),
 * RoomEvent.ParticipantPermissionsChanged локального участника
 * запускает повторный №46 (onRestrictionsSync — useConference.refreshMe);
 * точечный срез источника поясняется ТОЛЬКО латунным tooltip'ом кнопки
 * (кастомный .conference-controls-tooltip, T098 — замена нативного
 * title T097; тексты-константы T097 сохранены), а «Все источники»
 * (canPublish=false, Q4=A; доменное speechRestricted, T094) блокирует
 * ВСЕ тумблеры БЕЗ уведомлений (silent — tooltip'ы только с именами).
 *
 * 009, US1, T103 — состояние «в полёте»: клик тумблера держит кнопку в
 * data-state='pending' (переливающийся золотой контур + дышащая лампа —
 * CSS-пин в conferences-css.test.ts) до резолва/реджекта промиса сеттера;
 * aria-pressed — проекция ПОДТВЕРЖДЁННОГО состояния, tooltip полёта —
 * «Включение …/Выключение …» над restrictionNotice/именем; повторные
 * нажатия невозможны (disabled на весь полёт).
 *
 * 009, US1, T105 — два демонтажа/фикса панели: (1) bump-эпоха прав —
 * livekit-client мутирует participant.permissions НА МЕСТЕ, поэтому
 * permission-событие БЕЗ трек-изменений не перерисовывает
 * useLocalParticipant (Object.is-bail out) — панель инкрементит эпоху
 * в обработчике ParticipantPermissionsChanged и перечитывает grants
 * сама (гость без снимка №46 блокируется live-грантами); (2) строка
 * отказа .conference-controls-notice демонтирована — отказ переключения
 * живёт в tooltip СВОЕЙ кнопки («Не удалось включить/выключить …»),
 * полная цепочка приоритета tooltip'а: pending > failure > точечное
 * ограничение > имя; живой области role=status в панели больше нет
 * (осознанная уступка: автообъявление отказа скринридером прекращается).
 */

/** Wire-константы TrackSource (@livekit/protocol) — как в ParticipantsPanel. */
const CAMERA = 1
const MICROPHONE = 2
const SCREEN_SHARE = 3
const SCREEN_SHARE_AUDIO = 4
const ALL_SOURCES = [CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO]

type PermissionsHandler = (
  prevPermissions: unknown,
  participant: { readonly isLocal: boolean },
) => void

const media = vi.hoisted(() => ({
  setMicrophoneEnabled: vi.fn<(enabled: boolean) => Promise<void>>(),
  setCameraEnabled: vi.fn<(enabled: boolean) => Promise<void>>(),
  setScreenShareEnabled: vi.fn<(enabled: boolean) => Promise<void>>(),
  state: {
    mic: false,
    camera: false,
    screen: false,
    connection: 2,
    // литералы wire-констант: hoisted-фабрика выполняется до инициализации ALL_SOURCES
    permissions: { canPublish: true, canPublishSources: [1, 2, 3, 4] },
  },
  room: {
    on: vi.fn<(event: string, handler: PermissionsHandler) => void>(),
    off: vi.fn<(event: string, handler: PermissionsHandler) => void>(),
  },
}))

vi.mock('@livekit/components-react', () => ({
  useLocalParticipant: () => ({
    localParticipant: {
      setMicrophoneEnabled: media.setMicrophoneEnabled,
      setCameraEnabled: media.setCameraEnabled,
      setScreenShareEnabled: media.setScreenShareEnabled,
      permissions: media.state.permissions,
    },
    isMicrophoneEnabled: media.state.mic,
    isCameraEnabled: media.state.camera,
    isScreenShareEnabled: media.state.screen,
  }),
  useConnectionState: () => media.state.connection,
  useRoomContext: () => media.room,
}))

vi.mock('livekit-client', () => ({
  ConnectionState: { Disconnected: 0, Connected: 2 },
  RoomEvent: { ParticipantPermissionsChanged: 'participantPermissionsChanged' },
}))

import MediaControls from '../MediaControls'
import type { ParticipantRestrictionMarks } from '../ParticipantsPanel'

function renderControls(
  onLeave: () => void = vi.fn(),
  restrictions: ParticipantRestrictionMarks | null = null,
  onRestrictionsSync: () => void = vi.fn(),
) {
  const onLeaveSpy = vi.fn(onLeave)
  const syncSpy = vi.fn(onRestrictionsSync)
  const view = render(
    <MediaControls restrictions={restrictions} onRestrictionsSync={syncSpy} onLeave={onLeaveSpy} />,
  )
  return { onLeave: onLeaveSpy, sync: syncSpy, ...view }
}

function toggle(name: string): HTMLElement {
  return screen.getByRole('button', { name })
}

/** Латунный tooltip кнопки (T098): узел .conference-controls-tooltip внутри кнопки. */
function tooltipOf(name: string): HTMLElement {
  const tip = toggle(name).querySelector('.conference-controls-tooltip')
  if (tip === null) {
    throw new Error(`tooltip кнопки «${name}» не найден`)
  }
  return tip as HTMLElement
}

/** Замок-бейдж кнопки (T098): узел .conference-controls-lock (виден в blocked — CSS-пин). */
function lockOf(name: string): HTMLElement | null {
  return toggle(name).querySelector('.conference-controls-lock')
}

/** Подписчик панели на локальную смену прав (эффект [room] — один на монтаж). */
function permissionsHandler(): PermissionsHandler {
  const calls = media.room.on.mock.calls.filter(
    ([event]) => event === 'participantPermissionsChanged',
  )
  const last = calls.at(-1)
  if (last === undefined) {
    throw new Error('панель не подписана на participantPermissionsChanged')
  }
  return last[1]
}

describe('MediaControls: кнопки-иконки прототипа (009 US1 T098)', () => {
  beforeEach(() => {
    media.state = {
      mic: false,
      camera: false,
      screen: false,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
    media.room.on.mockReset()
    media.room.off.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('4 кнопки-иконки без текстовых узлов «имя»/«вкл/выкл»: стабильный aria-label, единственный текст — латунный tooltip', () => {
    media.state = {
      mic: true,
      camera: false,
      screen: true,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    renderControls()

    const group = screen.getByRole('group', { name: 'Управление конференцией' })
    // все 4 кнопки («Покинуть» — компонентный паритет двух режимов, T098
    // пересматривает скрытие T089: панель одна для обоих режимов)
    for (const name of ['Микрофон', 'Камера', 'Экран', 'Покинуть']) {
      const button = toggle(name)
      expect(group).toContainElement(button)
      expect(button).toHaveAttribute('aria-label', name)
      // внутри кнопки нет текстовых тумблеров T028: единственный текстовый
      // узел — tooltip (имя кнопки в обычном состоянии)
      expect(button.textContent).toBe(name)
    }
    expect(screen.queryByText('вкл')).toBeNull()
    expect(screen.queryByText('выкл')).toBeNull()
    // у «Покинуть» нет aria-pressed — это не тумблер
    expect(toggle('Покинуть')).not.toHaveAttribute('aria-pressed')
  })

  it('aria-pressed ↔ визуал-модификаторы: data-state on/off следует за проекцией публикаций', () => {
    const { rerender } = renderControls()
    expect(toggle('Микрофон')).toHaveAttribute('aria-pressed', 'false')
    expect(toggle('Микрофон')).toHaveAttribute('data-state', 'off')
    expect(toggle('Камера')).toHaveAttribute('aria-pressed', 'false')
    expect(toggle('Камера')).toHaveAttribute('data-state', 'off')

    // состояние сходится из комнаты (on = лампа + on-части иконки, off = штрих)
    media.state.mic = true
    media.state.screen = true
    rerender(<MediaControls restrictions={null} onRestrictionsSync={vi.fn()} onLeave={vi.fn()} />)
    expect(toggle('Микрофон')).toHaveAttribute('aria-pressed', 'true')
    expect(toggle('Микрофон')).toHaveAttribute('data-state', 'on')
    expect(toggle('Экран')).toHaveAttribute('aria-pressed', 'true')
    expect(toggle('Экран')).toHaveAttribute('data-state', 'on')
    expect(toggle('Камера')).toHaveAttribute('data-state', 'off')
  })

  it('точечная блокировка: disabled + data-state blocked + замок-бейдж + tooltip с уведомлением (тексты T097)', () => {
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, SCREEN_SHARE, SCREEN_SHARE_AUDIO],
    }
    renderControls()

    const mic = toggle('Микрофон')
    expect(mic).toBeDisabled()
    expect(mic).toHaveAttribute('data-state', 'blocked')
    expect(lockOf('Микрофон')).not.toBeNull()
    expect(tooltipOf('Микрофон').textContent).toBe('Микрофон отключён модератором')
    // чужие кнопки чисты: уведомление только на своей, у остальных — имя
    expect(tooltipOf('Камера').textContent).toBe('Камера')
    expect(tooltipOf('Экран').textContent).toBe('Экран')
    expect(toggle('Камера')).toBeEnabled()
    expect(toggle('Экран')).toBeEnabled()
  })

  it("мастер «Все источники» (canPublish=false, Q4=A): три disabled silent, tooltip'ы только с именами", () => {
    media.state.permissions = { canPublish: false, canPublishSources: [] }
    renderControls()

    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      const button = toggle(name)
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('data-state', 'blocked')
      expect(tooltipOf(name).textContent).toBe(name)
    }
    // уведомлений нет вовсе (T097 silent): ни в tooltip'ах, ни отдельными узлами
    expect(screen.queryByText('Публикация отключена модератором (право слова)')).toBeNull()
    expect(screen.queryByText('Микрофон отключён модератором')).toBeNull()
    expect(screen.queryByText('Камера отключена модератором')).toBeNull()
    expect(screen.queryByText('Демонстрация экрана отключена модератором')).toBeNull()
    expect(document.querySelector('.conference-controls-restrictions')).toBeNull()
  })

  it('клик тумблера вызывает целевой сеттер LiveKit с ЦЕЛЕВЫМ значением', async () => {
    const { rerender } = renderControls()
    media.setMicrophoneEnabled.mockResolvedValue(undefined)

    fireEvent.click(toggle('Микрофон'))

    await waitFor(() => {
      expect(media.setMicrophoneEnabled).toHaveBeenCalledWith(true)
    })
    expect(media.setCameraEnabled).not.toHaveBeenCalled()

    // состояние сходится из комнаты: хук видит включённый микрофон
    media.state.mic = true
    rerender(<MediaControls restrictions={null} onRestrictionsSync={vi.fn()} onLeave={vi.fn()} />)
    expect(toggle('Микрофон')).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(toggle('Микрофон'))
    await waitFor(() => {
      expect(media.setMicrophoneEnabled).toHaveBeenLastCalledWith(false)
    })
  })

  it('переключение в полёте блокирует все тумблеры до завершения (без гонок)', async () => {
    media.setCameraEnabled.mockReturnValue(new Promise<void>(() => {}))
    renderControls()

    fireEvent.click(toggle('Камера'))

    await waitFor(() => {
      expect(toggle('Камера')).toBeDisabled()
    })
    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Экран')).toBeDisabled()
    // «Покинуть» остаётся доступной — выход не ждёт переключений
    expect(toggle('Покинуть')).toBeEnabled()
  })

  it('отказ переключения — tooltip своей кнопки «Не удалось …», строки под панелью нет (T105)', async () => {
    media.setScreenShareEnabled.mockRejectedValue(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Экран'))

    // отказ живёт в tooltip СВОЕЙ кнопки; живой области role=status
    // (<output>-строки) в панели больше нет
    await waitFor(() => {
      expect(tooltipOf('Экран').textContent).toBe('Не удалось включить демонстрацию экрана')
    })
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.querySelector('.conference-controls-notice')).toBeNull()
    expect(toggle('Экран')).toBeEnabled()
  })

  it('отмена браузерного пикера экрана (AbortError) — не ошибка, tooltip остаётся именем', async () => {
    const abort = Object.assign(new Error('Cancelled'), { name: 'AbortError' })
    media.setScreenShareEnabled.mockRejectedValue(abort)
    renderControls()

    fireEvent.click(toggle('Экран'))

    await waitFor(() => {
      expect(media.setScreenShareEnabled).toHaveBeenCalled()
    })
    await waitFor(() => {
      expect(tooltipOf('Экран').textContent).toBe('Экран')
    })
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('вне подключённого состояния тумблеры недоступны, «Покинуть» работает', () => {
    media.state.connection = 0
    const { onLeave } = renderControls()

    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Камера')).toBeDisabled()
    expect(toggle('Экран')).toBeDisabled()

    fireEvent.click(toggle('Покинуть'))
    expect(onLeave).toHaveBeenCalledTimes(1)
  })
})

describe('MediaControls: собственные ограничения — латунный tooltip точечных, silent-мастер (009 US3 T043/T097/T098)', () => {
  beforeEach(() => {
    media.state = {
      mic: false,
      camera: false,
      screen: false,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
    media.room.on.mockReset()
    media.room.off.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('live-grants срезали микрофон (№49 UpdateParticipant): тумблер заблокирован, уведомление только в его tooltip', () => {
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, SCREEN_SHARE, SCREEN_SHARE_AUDIO],
    }
    renderControls()

    expect(toggle('Микрофон')).toBeDisabled()
    expect(tooltipOf('Микрофон').textContent).toBe('Микрофон отключён модератором')
    // чужие tooltip'ы — только имена: каждая подсказка на своей кнопке
    expect(toggle('Камера')).toBeEnabled()
    expect(tooltipOf('Камера').textContent).toBe('Камера')
    expect(toggle('Экран')).toBeEnabled()
    expect(tooltipOf('Экран').textContent).toBe('Экран')
  })

  it('точечные подсказки камера/экран: уведомление только на своей кнопке (тексты — как на pre-join T033)', () => {
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [MICROPHONE, SCREEN_SHARE_AUDIO],
    }
    renderControls()

    expect(toggle('Камера')).toBeDisabled()
    expect(tooltipOf('Камера').textContent).toBe('Камера отключена модератором')
    expect(toggle('Экран')).toBeDisabled()
    expect(tooltipOf('Экран').textContent).toBe('Демонстрация экрана отключена модератором')
    expect(tooltipOf('Микрофон').textContent).toBe('Микрофон')
  })

  it("«Все источники» (canPublish=false, Q4=A): ВСЕ тумблеры заблокированы, tooltip'ы — только имена (T097 — silent)", () => {
    media.state.permissions = { canPublish: false, canPublishSources: [] }
    renderControls()

    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Камера')).toBeDisabled()
    expect(toggle('Экран')).toBeDisabled()
    // состояние «все источники отключены модератором» не выводится вовсе:
    // ни нативного title, ни уведомлений в tooltip'ах, ни текстовых пояснений
    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      expect(toggle(name)).not.toHaveAttribute('title')
      expect(tooltipOf(name).textContent).toBe(name)
    }
    expect(screen.queryByText('Публикация отключена модератором (право слова)')).toBeNull()
    expect(screen.queryByText('Микрофон отключён модератором')).toBeNull()
    expect(screen.queryByText('Камера отключена модератором')).toBeNull()
    expect(screen.queryByText('Демонстрация экрана отключена модератором')).toBeNull()
    expect(document.querySelector('.conference-controls-restrictions')).toBeNull()
  })

  it("снимок №46 speechRestricted: та же единая блокировка с именами в tooltip'ах (OR-слияние до рефетча)", () => {
    renderControls(vi.fn(), {
      micRestricted: false,
      cameraRestricted: false,
      screenRestricted: false,
      speechRestricted: true,
    })

    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Камера')).toBeDisabled()
    expect(toggle('Экран')).toBeDisabled()
    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      expect(tooltipOf(name).textContent).toBe(name)
    }
    expect(screen.queryByText('Публикация отключена модератором (право слова)')).toBeNull()
  })

  it('регресс-пин T094: проекция all-four-true (все три точечных → grants canPublish=false) не меняет исход — единая блокировка', () => {
    // после фикса кодирования T094 третье точечное даёт grants
    // canPublish=false; live-проекция restrictionMarks возвращает все
    // четыре true — MediaControls OR-ит их в speechBlocked, исход тот же
    media.state.permissions = { canPublish: false, canPublishSources: [] }
    renderControls()

    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Камера')).toBeDisabled()
    expect(toggle('Экран')).toBeDisabled()
    // точечные подсказки не плодятся — поглощаются мастером
    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      expect(tooltipOf(name).textContent).toBe(name)
    }
  })

  it('снимок №46 (restrictions-props) блокирует тумблер и при полных live-grants (до рефетча)', () => {
    renderControls(vi.fn(), {
      micRestricted: false,
      cameraRestricted: true,
      screenRestricted: false,
      speechRestricted: false,
    })

    expect(toggle('Камера')).toBeDisabled()
    expect(tooltipOf('Камера').textContent).toBe('Камера отключена модератором')
    expect(toggle('Микрофон')).toBeEnabled()
    expect(toggle('Экран')).toBeEnabled()
  })

  it('OR-слияние: live-грант и снимок №46 вместе блокируют оба слота — уведомление на каждом своём', () => {
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, MICROPHONE, SCREEN_SHARE_AUDIO],
    }
    renderControls(vi.fn(), {
      micRestricted: true,
      cameraRestricted: false,
      screenRestricted: false,
      speechRestricted: false,
    })

    expect(toggle('Микрофон')).toBeDisabled()
    expect(tooltipOf('Микрофон').textContent).toBe('Микрофон отключён модератором')
    expect(toggle('Экран')).toBeDisabled()
    expect(tooltipOf('Экран').textContent).toBe('Демонстрация экрана отключена модератором')
    expect(toggle('Камера')).toBeEnabled()
    expect(tooltipOf('Камера').textContent).toBe('Камера')
  })

  it('permission-событие ЛОКАЛЬНОГО участника запускает повторный №46 (onRestrictionsSync)', () => {
    const { sync } = renderControls()

    permissionsHandler()(undefined, { isLocal: false })
    expect(sync).not.toHaveBeenCalled()

    permissionsHandler()(undefined, { isLocal: true })
    expect(sync).toHaveBeenCalledTimes(1)
  })

  it('live-обновление (SC-005): смена grants переподнимает рендер — блокировка и уведомление появляются', () => {
    const { rerender } = renderControls()
    expect(toggle('Экран')).toBeEnabled()
    expect(tooltipOf('Экран').textContent).toBe('Экран')

    // сервер применил screen_restricted → LiveKit обновил grants
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, MICROPHONE, SCREEN_SHARE_AUDIO],
    }
    rerender(<MediaControls restrictions={null} onRestrictionsSync={vi.fn()} onLeave={vi.fn()} />)

    expect(toggle('Экран')).toBeDisabled()
    expect(tooltipOf('Экран').textContent).toBe('Демонстрация экрана отключена модератором')
  })

  it('live-снятие точечного ограничения возвращает имя в tooltip вместе с разблокировкой (SC-005)', () => {
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, MICROPHONE, SCREEN_SHARE_AUDIO],
    }
    const { rerender } = renderControls()
    expect(toggle('Экран')).toBeDisabled()
    expect(tooltipOf('Экран').textContent).toBe('Демонстрация экрана отключена модератором')

    // модератор снял ограничение → grants вернули источник
    media.state.permissions = { canPublish: true, canPublishSources: [...ALL_SOURCES] }
    rerender(<MediaControls restrictions={null} onRestrictionsSync={vi.fn()} onLeave={vi.fn()} />)

    expect(toggle('Экран')).toBeEnabled()
    expect(tooltipOf('Экран').textContent).toBe('Экран')
  })

  it('демонтаж notice (T105): отказ «Не удалось включить микрофон» — в tooltip кнопки, строки нет', async () => {
    media.setMicrophoneEnabled.mockRejectedValue(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Микрофон'))

    await waitFor(() => {
      expect(tooltipOf('Микрофон').textContent).toBe('Не удалось включить микрофон')
    })
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.querySelector('.conference-controls-notice')).toBeNull()
  })

  it('размонтирование снимает подписку permission-события', () => {
    const { unmount } = renderControls()
    const handler = permissionsHandler()

    unmount()

    expect(media.room.off).toHaveBeenCalledWith('participantPermissionsChanged', handler)
  })
})

describe('MediaControls: bump-эпоха прав — permission-событие без трек-изменений (009 US1 T105)', () => {
  /**
   * Гостевой баг T105: livekit-client мутирует localParticipant.permissions
   * на месте — на RoomEvent.ParticipantPermissionsChanged БЕЗ трек-событий
   * useLocalParticipant получает Object.is-идентичные значения и НЕ
   * перерисовывается (React bail out). Гость входит «только микрофон»
   * (Q1=C/Q3=A): у срезанных НЕопубликованных источников (экран/камера)
   * нет ни трек-события, ни №46-рефетча (гость без снимка) — панель
   * обязана перерисоваться сама: bump-эпоха в обработчике перечитывает
   * restrictionMarks с мутированного объекта grants.
   */

  beforeEach(() => {
    media.state = {
      mic: false,
      camera: false,
      screen: false,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
    media.room.on.mockReset()
    media.room.off.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('событие прав БЕЗ rerender хука: canPublish=false → все три кнопки blocked + замок (гостевая нога бага)', () => {
    renderControls()
    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      expect(toggle(name)).toBeEnabled()
    }

    // сервер применил «Все источники» (№49), НИ ОДИН трек не менялся —
    // ре-рендер хука нет; до T105 кнопки держали СТАРЫЙ рендер
    media.state.permissions = { canPublish: false, canPublishSources: [] }
    act(() => {
      permissionsHandler()(undefined, { isLocal: true })
    })

    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      expect(toggle(name)).toBeDisabled()
      expect(toggle(name)).toHaveAttribute('data-state', 'blocked')
      expect(lockOf(name)).not.toBeNull()
    }
    // мастер silent (T097/T094): tooltip'ы — только имена
    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      expect(tooltipOf(name).textContent).toBe(name)
    }
  })

  it('симметрично точечное mic-ограничение по событию: замок + tooltip, чужие доступны', () => {
    renderControls()

    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, SCREEN_SHARE, SCREEN_SHARE_AUDIO],
    }
    act(() => {
      permissionsHandler()(undefined, { isLocal: true })
    })

    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Микрофон')).toHaveAttribute('data-state', 'blocked')
    expect(lockOf('Микрофон')).not.toBeNull()
    expect(tooltipOf('Микрофон').textContent).toBe('Микрофон отключён модератором')
    expect(toggle('Камера')).toBeEnabled()
    expect(toggle('Экран')).toBeEnabled()
  })

  it('снятие ограничений по событию возвращает кнопки (эпоха перерисовывает в обе стороны)', () => {
    media.state.permissions = { canPublish: false, canPublishSources: [] }
    renderControls()
    expect(toggle('Камера')).toBeDisabled()

    media.state.permissions = { canPublish: true, canPublishSources: [...ALL_SOURCES] }
    act(() => {
      permissionsHandler()(undefined, { isLocal: true })
    })

    for (const name of ['Микрофон', 'Камера', 'Экран']) {
      expect(toggle(name)).toBeEnabled()
      expect(toggle(name)).toHaveAttribute('data-state', 'off')
    }
  })

  it('bump не трогает чужие события: isLocal=false не перерисовывает панель', () => {
    renderControls()
    expect(toggle('Камера')).toBeEnabled()

    media.state.permissions = { canPublish: false, canPublishSources: [] }
    act(() => {
      permissionsHandler()(undefined, { isLocal: false })
    })

    expect(toggle('Камера')).toBeEnabled()
  })
})

describe('MediaControls: отказ в tooltip своей кнопки — жизненный цикл и приоритет (009 US1 T105)', () => {
  beforeEach(() => {
    media.state = {
      mic: false,
      camera: false,
      screen: false,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
    media.room.on.mockReset()
    media.room.off.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('новая попытка сбрасывает отказ: tooltip возвращается к имени (сбрасывается следующей попыткой)', async () => {
    media.setMicrophoneEnabled.mockRejectedValueOnce(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Микрофон'))
    await waitFor(() => {
      expect(tooltipOf('Микрофон').textContent).toBe('Не удалось включить микрофон')
    })

    // вторая попытка (успешная) — отказ сброшен началом flip
    media.setMicrophoneEnabled.mockResolvedValue(undefined)
    fireEvent.click(toggle('Микрофон'))
    await waitFor(() => {
      expect(tooltipOf('Микрофон').textContent).toBe('Микрофон')
    })
  })

  it('новый отказ заменяет прежний — только на своей кнопке', async () => {
    media.setMicrophoneEnabled.mockRejectedValueOnce(new Error('NotAllowedError'))
    media.setCameraEnabled.mockRejectedValueOnce(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Микрофон'))
    await waitFor(() => {
      expect(tooltipOf('Микрофон').textContent).toBe('Не удалось включить микрофон')
    })

    // отказ камеры: микрофонный отказ сброшен началом ЧУЖОЙ попытки,
    // текст камеры — на своей кнопке
    fireEvent.click(toggle('Камера'))
    await waitFor(() => {
      expect(tooltipOf('Камера').textContent).toBe('Не удалось включить камеру')
    })
    expect(tooltipOf('Микрофон').textContent).toBe('Микрофон')
  })

  it('цепочка приоритета: failure > точечное ограничение (отказ переживает подъехавший срез)', async () => {
    media.setCameraEnabled.mockRejectedValueOnce(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Камера'))
    await waitFor(() => {
      expect(tooltipOf('Камера').textContent).toBe('Не удалось включить камеру')
    })

    // модератор срезал камеру ПОСЛЕ отказа: кнопка заблокирована, tooltip
    // держит последнюю неудачу (приоритет failure над restrictionNotice)
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO],
    }
    act(() => {
      permissionsHandler()(undefined, { isLocal: true })
    })
    await waitFor(() => {
      expect(toggle('Камера')).toBeDisabled()
    })
    expect(tooltipOf('Камера').textContent).toBe('Не удалось включить камеру')
  })
})

describe('MediaControls: состояние «в полёте» — pending-кольцо тумблера (009 US1 T103)', () => {
  /**
   * Клик тумблера держит кнопку в data-state='pending' до резолва/реджекта
   * промиса сеттера LiveKit: переливающийся золотой контур + дышащая лампа
   * (прототип tmp/conference-controls.html, CSS-пин — conferences-css.test.ts).
   * aria-pressed остаётся проекцией ПОДТВЕРЖДЁННОГО состояния (не-оптимистично),
   * все тумблеры disabled (повторные нажатия невозможны), tooltip полёта —
   * тексты-константы «Включение …/Выключение …» над restrictionNotice/именем.
   */

  /** Отложенный промис сеттера: полёт держится до явного резолва/реджекта. */
  function deferred(): {
    readonly promise: Promise<void>
    readonly resolve: () => void
    readonly reject: (cause?: unknown) => void
  } {
    let resolve!: () => void
    let reject!: (cause?: unknown) => void
    const promise = new Promise<void>((done, fail) => {
      resolve = done
      reject = fail
    })
    return { promise, resolve, reject }
  }

  beforeEach(() => {
    media.state = {
      mic: false,
      camera: false,
      screen: false,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
    media.room.on.mockReset()
    media.room.off.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('полёт включения камеры: pending + disabled + aria-pressed=false + tooltip «Включение камеры…»; публикация → on', async () => {
    const flight = deferred()
    media.setCameraEnabled.mockReturnValue(flight.promise)
    renderControls()

    fireEvent.click(toggle('Камера'))

    const camera = toggle('Камера')
    await waitFor(() => {
      expect(camera).toHaveAttribute('data-state', 'pending')
    })
    expect(camera).toBeDisabled()
    // не-оптимистично: aria-pressed — ПОДТВЕРЖДЁННОЕ состояние, не цель клика
    expect(camera).toHaveAttribute('aria-pressed', 'false')
    expect(tooltipOf('Камера').textContent).toBe('Включение камеры…')
    // чужие tooltip'ы не в полёте: имя как обычно
    expect(tooltipOf('Микрофон').textContent).toBe('Микрофон')

    // резолв сеттера = фактическая публикация: pending снят, комната
    // переподнимает рендер уже с включённой камерой — состояние on
    media.state.camera = true
    flight.resolve()
    await waitFor(() => {
      expect(toggle('Камера')).toHaveAttribute('data-state', 'on')
    })
    expect(toggle('Камера')).toBeEnabled()
    expect(toggle('Камера')).toHaveAttribute('aria-pressed', 'true')
    expect(tooltipOf('Камера').textContent).toBe('Камера')
  })

  it('повторные клики в полёте невозможны: второй вызов сеттера нет, чужие тумблеры игнорируются', async () => {
    const flight = deferred()
    media.setCameraEnabled.mockReturnValue(flight.promise)
    renderControls()

    fireEvent.click(toggle('Камера'))
    await waitFor(() => {
      expect(toggle('Камера')).toHaveAttribute('data-state', 'pending')
    })

    // повторный клик по той же кнопке и клики по двум другим — disabled-гвард
    fireEvent.click(toggle('Камера'))
    fireEvent.click(toggle('Микрофон'))
    fireEvent.click(toggle('Экран'))

    expect(media.setCameraEnabled).toHaveBeenCalledTimes(1)
    expect(media.setMicrophoneEnabled).not.toHaveBeenCalled()
    expect(media.setScreenShareEnabled).not.toHaveBeenCalled()

    flight.resolve()
    await waitFor(() => {
      expect(toggle('Камера')).not.toBeDisabled()
    })
  })

  it('полёт выключения камеры: tooltip «Выключение камеры…», aria-pressed=true — подтверждённое состояние', async () => {
    media.state.camera = true
    const flight = deferred()
    media.setCameraEnabled.mockReturnValue(flight.promise)
    renderControls()

    fireEvent.click(toggle('Камера'))

    await waitFor(() => {
      expect(toggle('Камера')).toHaveAttribute('data-state', 'pending')
    })
    // камера ещё опубликована до подтверждения выключения
    expect(toggle('Камера')).toHaveAttribute('aria-pressed', 'true')
    expect(tooltipOf('Камера').textContent).toBe('Выключение камеры…')

    media.state.camera = false
    flight.resolve()
    await waitFor(() => {
      expect(toggle('Камера')).toHaveAttribute('data-state', 'off')
    })
    expect(tooltipOf('Камера').textContent).toBe('Камера')
  })

  it('симметрия микрофона: полный pending-цикл «Включение микрофона…» → on → «Выключение микрофона…» → off', async () => {
    renderControls()

    const enable = deferred()
    media.setMicrophoneEnabled.mockReturnValue(enable.promise)
    fireEvent.click(toggle('Микрофон'))
    await waitFor(() => {
      expect(toggle('Микрофон')).toHaveAttribute('data-state', 'pending')
    })
    expect(tooltipOf('Микрофон').textContent).toBe('Включение микрофона…')
    media.state.mic = true
    enable.resolve()
    await waitFor(() => {
      expect(toggle('Микрофон')).toHaveAttribute('data-state', 'on')
    })

    const disable = deferred()
    media.setMicrophoneEnabled.mockReturnValue(disable.promise)
    fireEvent.click(toggle('Микрофон'))
    await waitFor(() => {
      expect(toggle('Микрофон')).toHaveAttribute('data-state', 'pending')
    })
    expect(tooltipOf('Микрофон').textContent).toBe('Выключение микрофона…')
    media.state.mic = false
    disable.resolve()
    await waitFor(() => {
      expect(toggle('Микрофон')).toHaveAttribute('data-state', 'off')
    })
  })

  it('симметрия экрана: полный pending-цикл «Включение демонстрации…» → on → «Выключение демонстрации…» → off', async () => {
    renderControls()

    const enable = deferred()
    media.setScreenShareEnabled.mockReturnValue(enable.promise)
    fireEvent.click(toggle('Экран'))
    await waitFor(() => {
      expect(toggle('Экран')).toHaveAttribute('data-state', 'pending')
    })
    expect(tooltipOf('Экран').textContent).toBe('Включение демонстрации…')
    media.state.screen = true
    enable.resolve()
    await waitFor(() => {
      expect(toggle('Экран')).toHaveAttribute('data-state', 'on')
    })

    const disable = deferred()
    media.setScreenShareEnabled.mockReturnValue(disable.promise)
    fireEvent.click(toggle('Экран'))
    await waitFor(() => {
      expect(toggle('Экран')).toHaveAttribute('data-state', 'pending')
    })
    expect(tooltipOf('Экран').textContent).toBe('Выключение демонстрации…')
    media.state.screen = false
    disable.resolve()
    await waitFor(() => {
      expect(toggle('Экран')).toHaveAttribute('data-state', 'off')
    })
  })

  it('реджект промиса: pending снят, data-state — подтверждённый off, отказ — в tooltip (T105)', async () => {
    const flight = deferred()
    media.setCameraEnabled.mockReturnValue(flight.promise)
    renderControls()

    fireEvent.click(toggle('Камера'))
    await waitFor(() => {
      expect(toggle('Камера')).toHaveAttribute('data-state', 'pending')
    })

    flight.reject(new Error('NotAllowedError'))

    await waitFor(() => {
      expect(tooltipOf('Камера').textContent).toBe('Не удалось включить камеру')
    })
    // состояние не откатывается вручную — источник истины комната;
    // строки role=status под панелью больше нет (демонтаж T105)
    expect(screen.queryByRole('status')).toBeNull()
    expect(toggle('Камера')).toHaveAttribute('data-state', 'off')
    expect(toggle('Камера')).toBeEnabled()
  })

  it('отмена пикера экрана (AbortError): pending снят, tooltip остаётся именем (не отказ)', async () => {
    const flight = deferred()
    media.setScreenShareEnabled.mockReturnValue(flight.promise)
    renderControls()

    fireEvent.click(toggle('Экран'))
    await waitFor(() => {
      expect(toggle('Экран')).toHaveAttribute('data-state', 'pending')
    })

    const abort = Object.assign(new Error('Cancelled'), { name: 'AbortError' })
    flight.reject(abort)

    await waitFor(() => {
      expect(toggle('Экран')).toHaveAttribute('data-state', 'off')
    })
    expect(toggle('Экран')).toBeEnabled()
    expect(tooltipOf('Экран').textContent).toBe('Экран')
    expect(screen.queryByRole('status')).toBeNull()
  })
})

describe('MediaControls: автоотключение камеры при демонстрации экрана (009 US1 T077)', () => {
  beforeEach(() => {
    media.state = {
      mic: false,
      camera: false,
      screen: false,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
    media.room.on.mockReset()
    media.room.off.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('успешный старт демонстрации при включённой камере гасит камеру ПОСЛЕ старта экрана', async () => {
    media.state.camera = true
    media.setScreenShareEnabled.mockResolvedValue(undefined)
    media.setCameraEnabled.mockResolvedValue(undefined)
    renderControls()

    fireEvent.click(toggle('Экран'))

    await waitFor(() => {
      expect(media.setScreenShareEnabled).toHaveBeenCalledWith(true)
      expect(media.setCameraEnabled).toHaveBeenCalledWith(false)
    })
    // порядок: демонстрация реально пошла — только потом камера гасится
    const screenCall = media.setScreenShareEnabled.mock.invocationCallOrder[0]
    const cameraCall = media.setCameraEnabled.mock.invocationCallOrder[0]
    if (screenCall === undefined || cameraCall === undefined) {
      throw new Error('сеттеры экрана/камеры не вызваны')
    }
    expect(screenCall).toBeLessThan(cameraCall)
  })

  it('старт демонстрации при ВЫКЛЮЧЕННОЙ камере — без лишних вызовов камеры', async () => {
    media.setScreenShareEnabled.mockResolvedValue(undefined)
    renderControls()

    fireEvent.click(toggle('Экран'))

    await waitFor(() => {
      expect(media.setScreenShareEnabled).toHaveBeenCalledWith(true)
    })
    expect(media.setCameraEnabled).not.toHaveBeenCalled()
  })

  it('отмена браузерного пикера экрана (AbortError) — камера не тронута', async () => {
    media.state.camera = true
    const abort = Object.assign(new Error('Cancelled'), { name: 'AbortError' })
    media.setScreenShareEnabled.mockRejectedValue(abort)
    renderControls()

    fireEvent.click(toggle('Экран'))

    await waitFor(() => {
      expect(media.setScreenShareEnabled).toHaveBeenCalled()
    })
    await new Promise((resolve) => {
      setTimeout(resolve, 10)
    })
    expect(media.setCameraEnabled).not.toHaveBeenCalled()
  })

  it('ошибка старта демонстрации (reject setScreenShareEnabled) — камера не тронута', async () => {
    media.state.camera = true
    media.setScreenShareEnabled.mockRejectedValue(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Экран'))

    await waitFor(() => {
      expect(tooltipOf('Экран').textContent).toBe('Не удалось включить демонстрацию экрана')
    })
    expect(media.setCameraEnabled).not.toHaveBeenCalled()
  })

  it('остановка демонстрации НЕ включает камеру обратно (обратное включение — только вручную)', async () => {
    // камера уже погашена стартом демонстрации, экран идёт
    media.state.camera = false
    media.state.screen = true
    media.setScreenShareEnabled.mockResolvedValue(undefined)
    renderControls()

    fireEvent.click(toggle('Экран'))

    await waitFor(() => {
      expect(media.setScreenShareEnabled).toHaveBeenCalledWith(false)
    })
    expect(media.setCameraEnabled).not.toHaveBeenCalled()
  })
})

describe('MediaControls: взаимное исключение «камера ↔ экран» (009 US1 T085)', () => {
  beforeEach(() => {
    media.state = {
      mic: false,
      camera: false,
      screen: false,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
    media.room.on.mockReset()
    media.room.off.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('успешное включение камеры при живой демонстрации останавливает демонстрацию ПОСЛЕ старта камеры', async () => {
    media.state.screen = true
    media.setCameraEnabled.mockResolvedValue(undefined)
    media.setScreenShareEnabled.mockResolvedValue(undefined)
    renderControls()

    fireEvent.click(toggle('Камера'))

    await waitFor(() => {
      expect(media.setCameraEnabled).toHaveBeenCalledWith(true)
      expect(media.setScreenShareEnabled).toHaveBeenCalledWith(false)
    })
    // порядок: камера реально публикуется — только потом демонстрация гасится
    const cameraCall = media.setCameraEnabled.mock.invocationCallOrder[0]
    const screenCall = media.setScreenShareEnabled.mock.invocationCallOrder[0]
    if (cameraCall === undefined || screenCall === undefined) {
      throw new Error('сеттеры камеры/экрана не вызваны')
    }
    expect(cameraCall).toBeLessThan(screenCall)
  })

  it('включение камеры БЕЗ демонстрации — setScreenShareEnabled не вызывается вовсе', async () => {
    media.setCameraEnabled.mockResolvedValue(undefined)
    renderControls()

    fireEvent.click(toggle('Камера'))

    await waitFor(() => {
      expect(media.setCameraEnabled).toHaveBeenCalledWith(true)
    })
    expect(media.setScreenShareEnabled).not.toHaveBeenCalled()
  })

  it('ошибка включения камеры (reject setCameraEnabled) — демонстрация не тронута', async () => {
    media.state.screen = true
    media.setCameraEnabled.mockRejectedValue(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Камера'))

    await waitFor(() => {
      expect(tooltipOf('Камера').textContent).toBe('Не удалось включить камеру')
    })
    expect(media.setScreenShareEnabled).not.toHaveBeenCalled()
  })

  it('выключение камеры при живой демонстрации — демонстрация продолжается', async () => {
    // оба источника живут (короткое окно двойной публикации T085)
    media.state.camera = true
    media.state.screen = true
    media.setCameraEnabled.mockResolvedValue(undefined)
    renderControls()

    fireEvent.click(toggle('Камера'))

    await waitFor(() => {
      expect(media.setCameraEnabled).toHaveBeenCalledWith(false)
    })
    expect(media.setScreenShareEnabled).not.toHaveBeenCalled()
  })
})
