import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StrictMode } from 'react'

/**
 * 009, US2, T032 (Q6=A) — отказ браузера в доступе к камере/микрофону
 * на pre-join: понятное предупреждение (явный запрет permissions API
 * ИЛИ отказ acquisition камеры — Safari без permissions API), «Войти»
 * остаётся доступной, выборка уезжает без недоступных треков («только
 * слушаю», FR-006/FR-007); повторная попытка камеры тумблером доступна
 * (полный повторный запрос — уже в звонке, тумблеры T028).
 * livekit-client (createLocalVideoTrack) и navigator.mediaDevices/
 * navigator.permissions подменены фейками (паттерн useDevices.test).
 */

const camera = vi.hoisted(() => ({
  createLocalVideoTrack: vi.fn(),
}))

vi.mock('livekit-client', () => ({
  createLocalVideoTrack: camera.createLocalVideoTrack,
  RoomEvent: { ActiveDeviceChanged: 'activeDeviceChanged' },
}))

import PreJoinScreen from '../PreJoinScreen'
import type { PreJoinRestrictions, PreJoinSelection } from '../PreJoinScreen'

/** Fake-трек превью: контракт stop/attach/detach/setDeviceId. */
function fakeVideoTrack(): {
  stop: ReturnType<typeof vi.fn>
  attach: ReturnType<typeof vi.fn>
  detach: ReturnType<typeof vi.fn>
  setDeviceId: ReturnType<typeof vi.fn>
} {
  return {
    stop: vi.fn(),
    attach: vi.fn(),
    detach: vi.fn(),
    setDeviceId: vi.fn(() => Promise.resolve()),
  }
}

/** Fake PermissionStatus: state + событие change (EventTarget). */
class FakePermissionStatus extends EventTarget {
  state: 'granted' | 'denied' | 'prompt'

  constructor(state: 'granted' | 'denied' | 'prompt') {
    super()
    this.state = state
  }
}

function device(kind: MediaDeviceKind, deviceId: string, label = ''): MediaDeviceInfo {
  return { kind, deviceId, label, groupId: 'group-1', toJSON: () => ({}) }
}

/** Подмена navigator.mediaDevices (jsdom без него). */
function installMediaDevices(initial: MediaDeviceInfo[]): void {
  const devices = initial
  const bus = new EventTarget()
  const media = {
    enumerateDevices: (): Promise<MediaDeviceInfo[]> => Promise.resolve(devices),
    addEventListener: bus.addEventListener.bind(bus),
    removeEventListener: bus.removeEventListener.bind(bus),
  }
  Object.defineProperty(navigator, 'mediaDevices', { value: media, configurable: true })
}

/** Подмена navigator.permissions: статус по имени (denied/granted/prompt). */
function installPermissions(states: { microphone?: string; camera?: string }): void {
  const query = ({ name }: { name: string }): Promise<FakePermissionStatus> => {
    const state = name === 'microphone' ? states.microphone : states.camera
    if (state === undefined) {
      return Promise.reject(new Error(`Permission ${name} is not supported`))
    }
    return Promise.resolve(new FakePermissionStatus(state as 'granted' | 'denied' | 'prompt'))
  }
  Object.defineProperty(navigator, 'permissions', { value: { query }, configurable: true })
}

const DEVICES = [device('audioinput', 'mic-1'), device('videoinput', 'cam-1')]

function renderScreen(props: Partial<Parameters<typeof PreJoinScreen>[0]> = {}): {
  onJoin: ReturnType<typeof vi.fn>
  onCancel: ReturnType<typeof vi.fn>
} {
  const onJoin = vi.fn()
  const onCancel = vi.fn()
  render(<PreJoinScreen mode="video" onJoin={onJoin} onCancel={onCancel} {...props} />)
  return { onJoin, onCancel }
}

function joinButton(): HTMLElement {
  return screen.getByRole('button', { name: 'Войти' })
}

function micToggle(): HTMLElement {
  return screen.getByRole('button', { name: 'Микрофон' })
}

function cameraToggle(): HTMLElement {
  return screen.getByRole('button', { name: 'Камера' })
}

/** Стандартное окружение браузера тестов pre-join: устройства + чистый createLocalVideoTrack. */
function resetMediaEnv(): void {
  installMediaDevices(DEVICES.slice())
  camera.createLocalVideoTrack.mockReset()
}

/** Уборка подмен браузера после теста (паттерн describe'а T032). */
function clearMediaEnv(): void {
  cleanup()
  delete (navigator as { mediaDevices?: unknown }).mediaDevices
  delete (navigator as { permissions?: unknown }).permissions
  vi.clearAllMocks()
}

/** Ограничения №46 MeView для pre-join (T033): четыре поля, точечно переопределяются тестом. */
function restrictions(overrides: Partial<PreJoinRestrictions> = {}): PreJoinRestrictions {
  return {
    micRestricted: false,
    cameraRestricted: false,
    screenRestricted: false,
    speechRestricted: false,
    ...overrides,
  }
}

describe('PreJoinScreen — отказ доступа к устройствам (009 US2 T032, Q6=A)', () => {
  beforeEach(() => {
    installMediaDevices(DEVICES.slice())
    camera.createLocalVideoTrack.mockReset()
  })

  afterEach(() => {
    cleanup()
    delete (navigator as { mediaDevices?: unknown }).mediaDevices
    delete (navigator as { permissions?: unknown }).permissions
    vi.clearAllMocks()
  })

  it('отказ acquisition камеры (без permissions API — Safari): предупреждение, «Войти» доступна, выборка без камеры', async () => {
    camera.createLocalVideoTrack.mockRejectedValue(new Error('NotAllowedError'))
    const { onJoin } = renderScreen({ mode: 'video' })

    expect(await screen.findByText('Нет доступа к камере — можно войти без видео')).toBeVisible()
    expect(joinButton()).toBeEnabled()
    // тумблер камеры вернулся в «выкл» — повторная попытка доступна
    expect(cameraToggle()).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(joinButton())
    expect(onJoin).toHaveBeenCalledTimes(1)
    const selection = onJoin.mock.calls[0]?.[0] as PreJoinSelection | undefined
    if (selection === undefined) {
      throw new Error('onJoin не вызван')
    }
    expect(selection.camera).toBe(false)
    expect(selection.microphone).toBe(true)
  })

  it('явный запрет микрофона (permissions API): предупреждение, вход не блокируется', async () => {
    installPermissions({ microphone: 'denied', camera: 'granted' })
    camera.createLocalVideoTrack.mockResolvedValue(fakeVideoTrack())
    const { onJoin } = renderScreen({ mode: 'video' })

    expect(await screen.findByText('Нет доступа к микрофону — можно войти без звука')).toBeVisible()
    expect(joinButton()).toBeEnabled()

    fireEvent.click(joinButton())
    expect(onJoin).toHaveBeenCalledTimes(1)
  })

  it('запрет и камеры, и микрофона: объединённое предупреждение «как слушатель»', async () => {
    installPermissions({ microphone: 'denied', camera: 'denied' })
    camera.createLocalVideoTrack.mockRejectedValue(new Error('NotAllowedError'))
    renderScreen({ mode: 'video' })

    expect(
      await screen.findByText('Нет доступа к камере и микрофону — можно войти как слушатель'),
    ).toBeVisible()
    expect(joinButton()).toBeEnabled()
  })

  it('повторная попытка камеры тумблером после отказа: успех убирает предупреждение', async () => {
    camera.createLocalVideoTrack.mockRejectedValueOnce(new Error('NotAllowedError'))
    renderScreen({ mode: 'video' })

    expect(await screen.findByText('Нет доступа к камере — можно войти без видео')).toBeVisible()

    camera.createLocalVideoTrack.mockResolvedValueOnce(fakeVideoTrack())
    fireEvent.click(cameraToggle())
    await waitFor(() => {
      expect(screen.queryByText(/Нет доступа/)).toBeNull()
    })
    expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')
  })

  it('разрешённые устройства: предупреждений нет', async () => {
    installPermissions({ microphone: 'granted', camera: 'granted' })
    camera.createLocalVideoTrack.mockResolvedValue(fakeVideoTrack())
    renderScreen({ mode: 'video' })

    await waitFor(() => {
      expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')
    })
    expect(screen.queryByText(/Нет доступа/)).toBeNull()
  })
})

describe('PreJoinScreen — дефолты тумблеров по mode точки входа (009 US2 T035, FR-005)', () => {
  beforeEach(() => {
    resetMediaEnv()
  })

  afterEach(() => {
    clearMediaEnv()
  })

  it('mode=video (кнопка «видеозвонок»): камера+микрофон — превью запрашивается сразу', async () => {
    const track = fakeVideoTrack()
    camera.createLocalVideoTrack.mockResolvedValue(track)
    renderScreen({ mode: 'video' })

    // микрофон включён по умолчанию (трек не держим — чистый выбор, FR-005)
    expect(micToggle()).toHaveAttribute('aria-pressed', 'true')
    // камера: acquisition локального превью (ДО «Войти» — без подключения и публикации, FR-007)
    await waitFor(() => {
      expect(camera.createLocalVideoTrack).toHaveBeenCalledTimes(1)
    })
    await waitFor(() => {
      expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')
    })
    await waitFor(() => {
      expect(track.attach).toHaveBeenCalled()
    })
  })

  it('mode=audio (кнопка «аудиозвонок»/гостевая ссылка): только микрофон, камера не запрашивается', () => {
    camera.createLocalVideoTrack.mockResolvedValue(fakeVideoTrack())
    renderScreen({ mode: 'audio' })

    expect(micToggle()).toHaveAttribute('aria-pressed', 'true')
    // камера НЕ запрашивается вовсе — тумблер «выкл», но доступен (soft, Q3=A:
    // включение камеры уже здесь — новая попытка acquisition)
    expect(camera.createLocalVideoTrack).not.toHaveBeenCalled()
    expect(cameraToggle()).toHaveAttribute('aria-pressed', 'false')
    expect(cameraToggle()).toBeEnabled()
  })
})

describe('PreJoinScreen — блокировка тумблеров по №46 MeView (009 US2 T033/T035, FR-007)', () => {
  beforeEach(() => {
    resetMediaEnv()
  })

  afterEach(() => {
    clearMediaEnv()
  })

  it('micRestricted: тумблер микрофона заблокирован и «выкл», пояснение; выборка без микрофона', () => {
    const { onJoin } = renderScreen({
      mode: 'audio',
      restrictions: restrictions({ micRestricted: true }),
    })

    const mic = micToggle()
    expect(mic).toBeDisabled()
    expect(mic).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText('Микрофон отключён модератором')).toBeVisible()
    expect(cameraToggle()).toBeEnabled()

    fireEvent.click(joinButton())
    const selection = onJoin.mock.calls[0]?.[0] as PreJoinSelection | undefined
    if (selection === undefined) {
      throw new Error('onJoin не вызван')
    }
    expect(selection.microphone).toBe(false)
    expect(selection.camera).toBe(false)
  })

  it('cameraRestricted: камера не запрашивается вовсе, тумблер заблокирован, пояснение', async () => {
    camera.createLocalVideoTrack.mockResolvedValue(fakeVideoTrack())
    const { onJoin } = renderScreen({
      mode: 'video',
      restrictions: restrictions({ cameraRestricted: true }),
    })

    // превью НЕ запрашивается даже в video (запрещённый источник не обещается UI)
    await waitFor(() => {
      expect(joinButton()).toBeEnabled()
    })
    expect(camera.createLocalVideoTrack).not.toHaveBeenCalled()
    const cam = cameraToggle()
    expect(cam).toBeDisabled()
    expect(cam).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText('Камера недоступна')).toBeVisible()
    expect(screen.getByText('Камера отключена модератором')).toBeVisible()
    expect(micToggle()).toBeEnabled()

    fireEvent.click(joinButton())
    const selection = onJoin.mock.calls[0]?.[0] as PreJoinSelection | undefined
    if (selection === undefined) {
      throw new Error('onJoin не вызван')
    }
    expect(selection.camera).toBe(false)
    expect(selection.microphone).toBe(true)
  })

  it('speechRestricted («Все источники», доменное speechRestricted — T094; Q4=A): заблокированы ВСЕ тумблеры, одно объединяющее пояснение', () => {
    camera.createLocalVideoTrack.mockResolvedValue(fakeVideoTrack())
    const { onJoin } = renderScreen({
      mode: 'video',
      restrictions: restrictions({ speechRestricted: true }),
    })

    expect(micToggle()).toBeDisabled()
    expect(cameraToggle()).toBeDisabled()
    expect(micToggle()).toHaveAttribute('aria-pressed', 'false')
    expect(camera.createLocalVideoTrack).not.toHaveBeenCalled()
    expect(
      screen.getByText('Все источники отключены модератором — вы войдёте как слушатель'),
    ).toBeVisible()
    // отдельные per-source пояснения не дублируются
    expect(screen.queryByText('Микрофон отключён модератором')).toBeNull()
    expect(screen.queryByText('Камера отключена модератором')).toBeNull()

    fireEvent.click(joinButton())
    const selection = onJoin.mock.calls[0]?.[0] as PreJoinSelection | undefined
    if (selection === undefined) {
      throw new Error('onJoin не вызван')
    }
    // выборка уезжает без каких-либо публикаций — «как слушатель»
    expect(selection.microphone).toBe(false)
    expect(selection.camera).toBe(false)
  })

  it('screenRestricted: только строка-пояснение (тумблера экрана на pre-join нет — T028)', () => {
    renderScreen({ mode: 'audio', restrictions: restrictions({ screenRestricted: true }) })

    expect(screen.getByText('Демонстрация экрана отключена модератором')).toBeVisible()
    expect(micToggle()).toBeEnabled()
    expect(cameraToggle()).toBeEnabled()
  })

  it('гость: ограничения не передаются — на pre-join блокировок нет (data-model §2)', () => {
    // GuestJoinPage (T049) не передаёт restrictions: строка гостя создаётся
    // при входе, применённые модератором ограничения придут live-правами́
    // уже в звонке — pre-join гостя чист (FR-007, FR-011: старт «только микрофон»)
    for (const absent of [undefined, null] as const) {
      cleanup()
      renderScreen({ mode: 'audio', restrictions: absent })

      expect(screen.queryByText(/отключён модератором/)).toBeNull()
      expect(micToggle()).toBeEnabled()
      expect(micToggle()).toHaveAttribute('aria-pressed', 'true')
      expect(cameraToggle()).toBeEnabled()
      expect(cameraToggle()).toHaveAttribute('aria-pressed', 'false')
    }
  })
})

describe('PreJoinScreen — закрытие без «Войти»: отключение без следов (009 US2 T035/US2-6, FR-007)', () => {
  beforeEach(() => {
    resetMediaEnv()
  })

  afterEach(() => {
    clearMediaEnv()
  })

  it('«Отмена» с живым превью: трек остановлен, onJoin не вызван', async () => {
    const track = fakeVideoTrack()
    camera.createLocalVideoTrack.mockResolvedValue(track)
    const { onJoin, onCancel } = renderScreen({ mode: 'video' })
    await waitFor(() => {
      expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(track.stop).toHaveBeenCalledTimes(1)
    expect(onJoin).not.toHaveBeenCalled()
  })

  it('размонтирование (закрытие pre-join) останавливает трек — устройство освобождено', async () => {
    const track = fakeVideoTrack()
    camera.createLocalVideoTrack.mockResolvedValue(track)
    const view = render(<PreJoinScreen mode="video" onJoin={vi.fn()} />)
    await waitFor(() => {
      expect(camera.createLocalVideoTrack).toHaveBeenCalledTimes(1)
    })

    view.unmount()

    expect(track.stop).toHaveBeenCalledTimes(1)
  })

  it('«Войти» освобождает превью сразу: устройство свободно под публикацию звонка', async () => {
    const track = fakeVideoTrack()
    camera.createLocalVideoTrack.mockResolvedValue(track)
    const { onJoin } = renderScreen({ mode: 'video' })
    await waitFor(() => {
      expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')
    })

    fireEvent.click(joinButton())

    expect(track.stop).toHaveBeenCalledTimes(1)
    const selection = onJoin.mock.calls[0]?.[0] as PreJoinSelection | undefined
    if (selection === undefined) {
      throw new Error('onJoin не вызван')
    }
    // включённая на входе камера уезжает в выборке — публикация уже звонком (T034)
    expect(selection.camera).toBe(true)
  })
})

describe('PreJoinScreen — StrictMode-перемонтаж acquisition камеры (009 регрессия T064)', () => {
  /**
   * Регрессия e2e T064 (dev-рендер: main.tsx StrictMode двойной монтаж
   * эффектов): cleanup инвалидирует поколение висящего acquisition, но
   * НЕ сбрасывал cameraPending — повторный монтаж блокировался флагом
   * «в полёте» мёртвого поколения, а finally со stale seq pending не
   * сбрасывал: камера «подвисала» выкл навсегда, выборка «Войти» уезжала
   * с camera:false (e2e SC-001/SC-005 — без видео). Здесь: acquisition
   * монтажа #1 «медленный» (резолвится позже перемонтажа) — повторный
   * запрос #2 обязан завершиться и включить камеру; поздний трек #1
   * останавливается как устаревший.
   */
  beforeEach(() => {
    resetMediaEnv()
  })

  afterEach(() => {
    clearMediaEnv()
  })

  it('повторное acquisition перемонтажа не блокируется pending мёртвого поколения; выборка с камерой', async () => {
    const staleTrack = fakeVideoTrack()
    const liveTrack = fakeVideoTrack()
    let resolveFirst!: (track: ReturnType<typeof fakeVideoTrack>) => void
    const firstAcquisition = new Promise<ReturnType<typeof fakeVideoTrack>>((resolve) => {
      resolveFirst = resolve
    })
    camera.createLocalVideoTrack
      .mockReturnValueOnce(firstAcquisition)
      .mockReturnValueOnce(Promise.resolve(liveTrack))
    const onJoin = vi.fn()
    render(
      <StrictMode>
        <PreJoinScreen mode="video" onJoin={onJoin} onCancel={vi.fn()} />
      </StrictMode>,
    )

    // перемонтаж StrictMode уже случился: acquisition #2 завершено — камера вкл
    await waitFor(() => {
      expect(camera.createLocalVideoTrack).toHaveBeenCalledTimes(2)
    })
    await waitFor(() => {
      expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')
    })
    expect(cameraToggle()).toBeEnabled()

    // поздний резолв мёртвого поколения #1 — трек останавливается, состояние не меняется
    resolveFirst(staleTrack)
    await waitFor(() => {
      expect(staleTrack.stop).toHaveBeenCalledTimes(1)
    })
    expect(cameraToggle()).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(joinButton())
    const selection = onJoin.mock.calls[0]?.[0] as PreJoinSelection | undefined
    if (selection === undefined) {
      throw new Error('onJoin не вызван')
    }
    expect(selection.camera).toBe(true)
    expect(liveTrack.stop).toHaveBeenCalledTimes(1)
  })
})
