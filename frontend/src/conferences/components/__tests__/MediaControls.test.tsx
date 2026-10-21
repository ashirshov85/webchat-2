import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 009, US1, T029 — MediaControls (T028, FR-004/FR-006): три тумблера —
 * проекция фактических публикаций localParticipant (aria-pressed), клик —
 * целевой сеттер LiveKit; все тумблеры недоступны в полёте переключения и
 * вне подключённого состояния («Покинуть» доступна всегда); отказ
 * переключения — нейтральная строка без деталей, отмена браузерного
 * пикера экрана (AbortError) — не ошибка.
 *
 * 009, US3, T043 — собственные ограничения (FR-007/FR-008, SC-005):
 * тумблер заблокирован с пояснением по live-grants подключения
 * (№49 → UpdateParticipant) И/ИЛИ снимку №46 MeView (OR-слияние),
 * RoomEvent.ParticipantPermissionsChanged локального участника
 * запускает повторный №46 (onRestrictionsSync — useConference.refreshMe);
 * «право слова» (canPublish=false, Q4=A) блокирует ВСЕ тумблеры одним
 * объединяющим пояснением.
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

describe('MediaControls (009 US1 T028/T029)', () => {
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

  it('три тумблера с aria-pressed проекции публикаций + «Покинуть» (role=group)', () => {
    media.state = {
      mic: true,
      camera: false,
      screen: true,
      connection: 2,
      permissions: { canPublish: true, canPublishSources: [...ALL_SOURCES] },
    }
    renderControls()

    const group = screen.getByRole('group', { name: 'Управление конференцией' })
    expect(group).toContainElement(toggle('Микрофон'))
    expect(toggle('Микрофон')).toHaveAttribute('aria-pressed', 'true')
    expect(toggle('Камера')).toHaveAttribute('aria-pressed', 'false')
    expect(toggle('Экран')).toHaveAttribute('aria-pressed', 'true')
    expect(group).toContainElement(toggle('Покинуть'))
    // визуальный индикатор состояния рядом со стабильным aria-label
    expect(toggle('Микрофон')).toHaveTextContent('вкл')
    expect(toggle('Камера')).toHaveTextContent('выкл')
    // без ограничений пояснений нет
    expect(screen.queryByText('Микрофон отключён модератором')).toBeNull()
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

  it('отказ переключения — нейтральная строка (role=status), тумблеры снова доступны', async () => {
    media.setScreenShareEnabled.mockRejectedValue(new Error('NotAllowedError'))
    renderControls()

    fireEvent.click(toggle('Экран'))

    const notice = await screen.findByRole('status')
    expect(notice).toHaveTextContent('Не удалось включить демонстрацию экрана')
    expect(toggle('Экран')).toBeEnabled()
  })

  it('отмена браузерного пикера экрана (AbortError) — не ошибка, строки нет', async () => {
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

describe('MediaControls: собственные ограничения (009 US3 T043)', () => {
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

  it('live-grants срезали микрофон (№49 UpdateParticipant): тумблер заблокирован с пояснением, соседние доступны', () => {
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, SCREEN_SHARE, SCREEN_SHARE_AUDIO],
    }
    renderControls()

    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Камера')).toBeEnabled()
    expect(toggle('Экран')).toBeEnabled()
    expect(screen.getByText('Микрофон отключён модератором')).toBeVisible()
  })

  it('«право слова» (canPublish=false, Q4=A): ВСЕ тумблеры заблокированы, единое пояснение', () => {
    media.state.permissions = { canPublish: false, canPublishSources: [] }
    renderControls()

    expect(toggle('Микрофон')).toBeDisabled()
    expect(toggle('Камера')).toBeDisabled()
    expect(toggle('Экран')).toBeDisabled()
    expect(screen.getByText('Публикация отключена модератором (право слова)')).toBeVisible()
    // точечные пояснения не дублируются — поглощаются «правом слова»
    expect(screen.queryByText('Микрофон отключён модератором')).toBeNull()
  })

  it('снимок №46 (restrictions-props) блокирует тумблер и при полных live-grants (до рефетча)', () => {
    renderControls(vi.fn(), {
      micRestricted: false,
      cameraRestricted: true,
      screenRestricted: false,
      speechRestricted: false,
    })

    expect(toggle('Камера')).toBeDisabled()
    expect(toggle('Микрофон')).toBeEnabled()
    expect(toggle('Экран')).toBeEnabled()
    expect(screen.getByText('Камера отключена модератором')).toBeVisible()
  })

  it('OR-слияние: live-грант и снимок №46 вместе блокируют оба слота с двумя пояснениями', () => {
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
    expect(toggle('Экран')).toBeDisabled()
    expect(toggle('Камера')).toBeEnabled()
    expect(screen.getByText('Микрофон отключён модератором')).toBeVisible()
    expect(screen.getByText('Демонстрация экрана отключена модератором')).toBeVisible()
  })

  it('permission-событие ЛОКАЛЬНОГО участника запускает повторный №46 (onRestrictionsSync)', () => {
    const { sync } = renderControls()

    permissionsHandler()(undefined, { isLocal: false })
    expect(sync).not.toHaveBeenCalled()

    permissionsHandler()(undefined, { isLocal: true })
    expect(sync).toHaveBeenCalledTimes(1)
  })

  it('live-обновление (SC-005): смена grants переподнимает рендер — блокировка появляется', () => {
    const { rerender } = renderControls()
    expect(toggle('Экран')).toBeEnabled()

    // сервер применил screen_restricted → LiveKit обновил grants
    media.state.permissions = {
      canPublish: true,
      canPublishSources: [CAMERA, MICROPHONE, SCREEN_SHARE_AUDIO],
    }
    rerender(<MediaControls restrictions={null} onRestrictionsSync={vi.fn()} onLeave={vi.fn()} />)

    expect(toggle('Экран')).toBeDisabled()
    expect(screen.getByText('Демонстрация экрана отключена модератором')).toBeVisible()
  })

  it('размонтирование снимает подписку permission-события', () => {
    const { unmount } = renderControls()
    const handler = permissionsHandler()

    unmount()

    expect(media.room.off).toHaveBeenCalledWith('participantPermissionsChanged', handler)
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

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Не удалось включить демонстрацию экрана',
    )
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
