import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDevices } from '../useDevices'
import type { ConferenceDeviceSelection } from '../useDevices'

/**
 * 009, US2, T031 — useDevices: перечисление по видам, инференс/подписка
 * разрешений, сигналы devicechange (сброс выбора удалённого устройства),
 * переключение микрофона/камеры/динамиков на лету (Q11=B) через
 * Room.switchActiveDevice (контракт livekit-client подменён фейком),
 * null-выбор → браузерный default-id, стартовое применение initial при
 * появлении комнаты, синхронизация ActiveDeviceChanged, занятость
 * переключения и нейтральный switchError при отказе.
 */

vi.mock('livekit-client', () => ({
  RoomEvent: { ActiveDeviceChanged: 'activeDeviceChanged' },
}))

/** Fake Room: только контракт, используемый хуком (on/off/switchActiveDevice). */
class FakeRoom {
  readonly switchCalls: Array<{ kind: string; deviceId: string }> = []
  /** Управляемое тестом поведение switchActiveDevice (дефолт — успех). */
  switchImpl: ((kind: string, deviceId: string) => Promise<boolean>) | null = null
  private readonly listeners = new Map<string, Set<(kind: string, deviceId: string) => void>>()

  on(event: string, listener: (kind: string, deviceId: string) => void): this {
    let set = this.listeners.get(event)
    if (set === undefined) {
      set = new Set()
      this.listeners.set(event, set)
    }
    set.add(listener)
    return this
  }

  off(event: string, listener: (kind: string, deviceId: string) => void): this {
    this.listeners.get(event)?.delete(listener)
    return this
  }

  emit(event: string, kind: string, deviceId: string): void {
    this.listeners.get(event)?.forEach((listener) => {
      listener(kind, deviceId)
    })
  }

  async switchActiveDevice(kind: string, deviceId: string): Promise<boolean> {
    this.switchCalls.push({ kind, deviceId })
    if (this.switchImpl !== null) {
      return this.switchImpl(kind, deviceId)
    }
    return true
  }
}

/** Fake PermissionStatus: state + событие change (EventTarget). */
class FakePermissionStatus extends EventTarget {
  state: 'granted' | 'denied' | 'prompt'

  constructor(state: 'granted' | 'denied' | 'prompt') {
    super()
    this.state = state
  }

  transition(next: 'granted' | 'denied' | 'prompt'): void {
    this.state = next
    this.dispatchEvent(new Event('change'))
  }
}

function device(kind: MediaDeviceKind, deviceId: string, label = ''): MediaDeviceInfo {
  return { kind, deviceId, label, groupId: 'group-1', toJSON: () => ({}) }
}

/** Подмена navigator.mediaDevices (jsdom без него): enumerate + devicechange-шина. */
function installMediaDevices(initial: MediaDeviceInfo[]) {
  let devices = initial
  const bus = new EventTarget()
  const enumerate = vi.fn((): Promise<MediaDeviceInfo[]> => Promise.resolve(devices))
  const media = {
    enumerateDevices: enumerate,
    addEventListener: bus.addEventListener.bind(bus),
    removeEventListener: bus.removeEventListener.bind(bus),
  }
  Object.defineProperty(navigator, 'mediaDevices', { value: media, configurable: true })
  return {
    enumerate,
    setDevices(next: MediaDeviceInfo[]): void {
      devices = next
    },
    emitDeviceChange(): void {
      bus.dispatchEvent(new Event('devicechange'))
    },
  }
}

/** Подмена navigator.permissions: статус по имени устройства. */
function installPermissions(states: { microphone?: string; camera?: string }): {
  statuses: FakePermissionStatus[]
  query: ReturnType<typeof vi.fn>
} {
  const statuses: FakePermissionStatus[] = []
  const query = vi.fn(({ name }: { name: string }): Promise<FakePermissionStatus> => {
    const state = name === 'microphone' ? states.microphone : states.camera
    if (state === undefined) {
      return Promise.reject(new Error(`Permission ${name} is not supported`))
    }
    const status = new FakePermissionStatus(state as 'granted' | 'denied' | 'prompt')
    statuses.push(status)
    return Promise.resolve(status)
  })
  Object.defineProperty(navigator, 'permissions', { value: { query }, configurable: true })
  return { statuses, query }
}

const MIC_1 = device('audioinput', 'mic-1')
const MIC_2 = device('audioinput', 'mic-2', 'Микрофон USB')
const CAM_1 = device('videoinput', 'cam-1', 'Камера')
const SPK_1 = device('audiooutput', 'spk-1', 'Динамики')
const SPK_DEFAULT = device('audiooutput', 'default', 'По умолчанию')

const SAMPLE_DEVICES = [MIC_1, MIC_2, CAM_1, SPK_1]

describe('useDevices (009 US2 T031)', () => {
  beforeEach(() => {
    installMediaDevices(SAMPLE_DEVICES.slice())
  })

  afterEach(() => {
    cleanup()
    delete (navigator as { mediaDevices?: unknown }).mediaDevices
    delete (navigator as { permissions?: unknown }).permissions
    vi.clearAllMocks()
  })

  it('enumerates devices split by kind', async () => {
    const { result } = renderHook(() => useDevices())

    expect(result.current.supported).toBe(true)
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))
    expect(result.current.videoInputs.map((item) => item.deviceId)).toEqual(['cam-1'])
    expect(result.current.audioOutputs.map((item) => item.deviceId)).toEqual(['spk-1'])
    expect(result.current.selection).toEqual({
      audioInput: null,
      videoInput: null,
      audioOutput: null,
    })
  })

  it('infers granted permission from visible labels when permissions API is missing', async () => {
    const { result } = renderHook(() => useDevices())

    await waitFor(() => expect(result.current.microphonePermission).toBe('granted'))
    expect(result.current.cameraPermission).toBe('granted')
  })

  it('keeps unknown permission when labels are hidden and permissions API is missing', async () => {
    const hidden = [
      device('audioinput', 'mic-1'),
      device('videoinput', 'cam-1'),
      device('audiooutput', 'spk-1'),
    ]
    installMediaDevices(hidden)

    const { result } = renderHook(() => useDevices())

    await waitFor(() => expect(result.current.audioInputs).toHaveLength(1))
    expect(result.current.microphonePermission).toBe('unknown')
    expect(result.current.cameraPermission).toBe('unknown')
  })

  it('tracks permission state via permissions API and re-enumerates when granted', async () => {
    const hidden = [MIC_1, CAM_1, SPK_1]
    const media = installMediaDevices(hidden)
    const { statuses } = installPermissions({ microphone: 'prompt', camera: 'denied' })

    const { result } = renderHook(() => useDevices())

    await waitFor(() => expect(result.current.microphonePermission).toBe('prompt'))
    expect(result.current.cameraPermission).toBe('denied')
    // 'denied' из permissions API не пересматривается инференсом меток
    media.setDevices([device('videoinput', 'cam-1', 'Камера')])
    act(() => {
      media.emitDeviceChange()
    })
    await waitFor(() => expect(result.current.cameraPermission).toBe('denied'))

    // выдача разрешения → change → пере-перечисление с раскрытыми метками
    media.setDevices([device('audioinput', 'mic-1', 'Микрофон USB'), CAM_1, SPK_1])
    const microphoneStatus = statuses[0]
    if (microphoneStatus === undefined) {
      throw new Error('статус микрофона не запрошен')
    }
    act(() => {
      microphoneStatus.transition('granted')
    })
    await waitFor(() => expect(result.current.microphonePermission).toBe('granted'))
    expect(media.enumerate.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it('devicechange re-enumerates; removing the selected device resets that choice to default', async () => {
    const media = installMediaDevices(SAMPLE_DEVICES.slice())
    const { result } = renderHook(() => useDevices())
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    let applied = false
    await act(async () => {
      applied = await result.current.selectAudioInput('mic-2')
    })
    expect(applied).toBe(true)
    expect(result.current.selection.audioInput).toBe('mic-2')

    // гарнитура mic-2 выдернута, остался системный mic-1
    media.setDevices([MIC_1, CAM_1, SPK_1])
    act(() => {
      media.emitDeviceChange()
    })

    await waitFor(() => expect(result.current.audioInputs).toHaveLength(1))
    expect(result.current.selection.audioInput).toBeNull()
    // выборы других категорий не тронуты (пустой список не считается удалением)
    expect(result.current.selection.videoInput).toBeNull()
  })

  it('switches devices on the fly through Room.switchActiveDevice (Q11=B)', async () => {
    const room = new FakeRoom()
    const { result } = renderHook(({ room: current }) => useDevices({ room: current }), {
      initialProps: { room: room as never },
    })
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    let applied = false
    await act(async () => {
      applied = await result.current.selectAudioInput('mic-1')
    })

    expect(applied).toBe(true)
    expect(room.switchCalls).toEqual([{ kind: 'audioinput', deviceId: 'mic-1' }])
    expect(result.current.selection.audioInput).toBe('mic-1')
    expect(result.current.switching).toBeNull()
    expect(result.current.switchError).toBeNull()

    await act(async () => {
      applied = await result.current.selectVideoInput('cam-1')
    })
    expect(applied).toBe(true)
    expect(room.switchCalls).toEqual([
      { kind: 'audioinput', deviceId: 'mic-1' },
      { kind: 'videoinput', deviceId: 'cam-1' },
    ])
  })

  it('null choice in a call targets the browser default device id', async () => {
    installMediaDevices([MIC_1, CAM_1, SPK_DEFAULT, SPK_1])
    const room = new FakeRoom()
    const { result } = renderHook(({ room: current }) => useDevices({ room: current }), {
      initialProps: { room: room as never },
    })
    await waitFor(() => expect(result.current.audioOutputs).toHaveLength(2))

    let applied = false
    await act(async () => {
      applied = await result.current.selectAudioOutput(null)
    })

    expect(applied).toBe(true)
    expect(room.switchCalls).toEqual([{ kind: 'audiooutput', deviceId: 'default' }])
    expect(result.current.selection.audioOutput).toBeNull()
  })

  it('without an explicit default id the first enumerated device is the default target', async () => {
    const room = new FakeRoom()
    const { result } = renderHook(({ room: current }) => useDevices({ room: current }), {
      initialProps: { room: room as never },
    })
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    let applied = false
    await act(async () => {
      applied = await result.current.selectAudioInput(null)
    })

    expect(applied).toBe(true)
    expect(room.switchCalls).toEqual([{ kind: 'audioinput', deviceId: 'mic-1' }])
    expect(result.current.selection.audioInput).toBeNull()
  })

  it('a failed switch keeps the previous selection and surfaces a neutral switchError', async () => {
    const room = new FakeRoom()
    room.switchImpl = () => Promise.resolve(false)
    const { result } = renderHook(({ room: current }) => useDevices({ room: current }), {
      initialProps: { room: room as never },
    })
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    let applied = false
    await act(async () => {
      applied = await result.current.selectAudioInput('mic-2')
    })
    expect(applied).toBe(false)
    expect(result.current.selection.audioInput).toBeNull()
    expect(result.current.switchError).toBe('audioinput')

    // успешная повторная попытка сбрасывает отказ
    room.switchImpl = () => Promise.resolve(true)
    await act(async () => {
      applied = await result.current.selectAudioInput('mic-2')
    })
    expect(applied).toBe(true)
    expect(result.current.switchError).toBeNull()
    expect(result.current.selection.audioInput).toBe('mic-2')
  })

  it('ignores parallel selects while a switch is pending', async () => {
    const room = new FakeRoom()
    room.switchImpl = () => new Promise<boolean>(() => {})
    const { result } = renderHook(({ room: current }) => useDevices({ room: current }), {
      initialProps: { room: room as never },
    })
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    let second = true
    await act(async () => {
      void result.current.selectAudioInput('mic-1')
      second = await result.current.selectAudioInput('mic-2')
    })

    expect(second).toBe(false)
    expect(room.switchCalls).toEqual([{ kind: 'audioinput', deviceId: 'mic-1' }])
  })

  it('outside a call a select is pure bookkeeping for the future join', async () => {
    const { result } = renderHook(() => useDevices())
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    let applied = false
    await act(async () => {
      applied = await result.current.selectAudioInput('mic-2')
    })

    expect(applied).toBe(true)
    expect(result.current.selection.audioInput).toBe('mic-2')
  })

  it('applies the pre-join initial selection best-effort when the room appears', async () => {
    const initial: ConferenceDeviceSelection = {
      audioInput: 'mic-2',
      videoInput: null,
      audioOutput: 'spk-1',
    }
    const room = new FakeRoom()
    const { result, rerender } = renderHook(
      ({ room: current }) => useDevices({ room: current, initial }),
      { initialProps: { room: null as never } },
    )
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    act(() => {
      rerender({ room: room as never })
    })

    await waitFor(() => expect(room.switchCalls).toHaveLength(2))
    expect(room.switchCalls).toEqual([
      { kind: 'audioinput', deviceId: 'mic-2' },
      { kind: 'audiooutput', deviceId: 'spk-1' },
    ])
    // сбой фон-применения (выдернутое устройство) — тихий, без switchError
    const failing = new FakeRoom()
    failing.switchImpl = () => Promise.reject(new Error('device is gone'))
    act(() => {
      rerender({ room: failing as never })
    })
    await waitFor(() => expect(failing.switchCalls).toHaveLength(2))
    expect(result.current.switchError).toBeNull()
  })

  it('syncs the selection with ActiveDeviceChanged from the room', async () => {
    const room = new FakeRoom()
    const { result } = renderHook(({ room: current }) => useDevices({ room: current }), {
      initialProps: { room: room as never },
    })
    await waitFor(() => expect(result.current.audioInputs).toHaveLength(2))

    act(() => {
      room.emit('activeDeviceChanged', 'audioinput', 'mic-9')
    })
    expect(result.current.selection.audioInput).toBe('mic-9')

    act(() => {
      room.emit('activeDeviceChanged', 'audioinput', 'default')
    })
    expect(result.current.selection.audioInput).toBeNull()
  })

  it('degrades gracefully without mediaDevices support', async () => {
    delete (navigator as { mediaDevices?: unknown }).mediaDevices

    const { result } = renderHook(() => useDevices())

    expect(result.current.supported).toBe(false)
    expect(result.current.audioInputs).toEqual([])
    expect(result.current.microphonePermission).toBe('unknown')
    let applied = true
    await act(async () => {
      applied = await result.current.selectAudioInput('mic-1')
    })
    expect(applied).toBe(false)
    expect(result.current.selection.audioInput).toBeNull()
  })
})
