import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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
import type { PreJoinSelection } from '../PreJoinScreen'

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
} {
  const onJoin = vi.fn()
  render(<PreJoinScreen mode="video" onJoin={onJoin} {...props} />)
  return { onJoin }
}

function joinButton(): HTMLElement {
  return screen.getByRole('button', { name: 'Войти' })
}

function cameraToggle(): HTMLElement {
  return screen.getByRole('button', { name: 'Камера' })
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
