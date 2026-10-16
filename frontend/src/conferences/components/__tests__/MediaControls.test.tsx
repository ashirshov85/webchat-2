import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 009, US1, T029 — MediaControls (T028, FR-004/FR-006): три тумблера —
 * проекция фактических публикаций localParticipant (aria-pressed), клик —
 * целевой сеттер LiveKit; все тумблеры недоступны в полёте переключения и
 * вне подключённого состояния («Покинуть» доступна всегда); отказ
 * переключения — нейтральная строка без деталей, отмена браузерного
 * пикера экрана (AbortError) — не ошибка.
 */

const media = vi.hoisted(() => ({
  setMicrophoneEnabled: vi.fn<(enabled: boolean) => Promise<void>>(),
  setCameraEnabled: vi.fn<(enabled: boolean) => Promise<void>>(),
  setScreenShareEnabled: vi.fn<(enabled: boolean) => Promise<void>>(),
  state: { mic: false, camera: false, screen: false, connection: 2 },
}))

vi.mock('@livekit/components-react', () => ({
  useLocalParticipant: () => ({
    localParticipant: {
      setMicrophoneEnabled: media.setMicrophoneEnabled,
      setCameraEnabled: media.setCameraEnabled,
      setScreenShareEnabled: media.setScreenShareEnabled,
    },
    isMicrophoneEnabled: media.state.mic,
    isCameraEnabled: media.state.camera,
    isScreenShareEnabled: media.state.screen,
  }),
  useConnectionState: () => media.state.connection,
}))

vi.mock('livekit-client', () => ({
  ConnectionState: { Disconnected: 0, Connected: 2 },
}))

import MediaControls from '../MediaControls'

function renderControls(onLeave: () => void = vi.fn()) {
  const onLeaveSpy = vi.fn(onLeave)
  const view = render(<MediaControls onLeave={onLeaveSpy} />)
  return { onLeave: onLeaveSpy, ...view }
}

function toggle(name: string): HTMLElement {
  return screen.getByRole('button', { name })
}

describe('MediaControls (009 US1 T028/T029)', () => {
  beforeEach(() => {
    media.state = { mic: false, camera: false, screen: false, connection: 2 }
    media.setMicrophoneEnabled.mockReset()
    media.setCameraEnabled.mockReset()
    media.setScreenShareEnabled.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('три тумблера с aria-pressed проекции публикаций + «Покинуть» (role=group)', () => {
    media.state = { mic: true, camera: false, screen: true, connection: 2 }
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
    rerender(<MediaControls onLeave={vi.fn()} />)
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
