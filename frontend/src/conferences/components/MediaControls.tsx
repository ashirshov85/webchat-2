/**
 * Панель управления конференцией (009, US1, T028; FR-004/FR-006):
 * тумблеры микрофона/камеры/демонстрации экрана и «Покинуть» — рендерится
 * в слоте children ConferenceRoom (T027), т.е. внутри RoomContext:
 * состояние тумблеров — НЕ локальный стейт, а проекция фактических
 * публикаций localParticipant (useLocalParticipant: isMicrophoneEnabled/
 * isCameraEnabled/isScreenShareEnabled) — единственный источник истины
 * медиа-плоскость LiveKit, поэтому UI не расходится с комнатой (стартовая
 * публикация — publishInitialTracks T027; серверные мьюты модератора
 * отражаются сами, T043 добавит пояснения блокировок).
 *
 * FR-006 «только слушаю»: все три тумблера выключены — легальное
 * состояние; включение публикации прямо в звонке — теми же тумблерами
 * (в рамках прав: grants срезаны сервером в токене №47, FR-007 —
 * запрещённый источник просто не публикуется, отказ виден нейтральной
 * строкой без раскрытия деталей). Повторный запрос доступа к устройствам
 * после запрета браузера — попытка включения тумблером (Q6=A; отдельный
 * UX отказа — T032). Отмена собственного выбора экрана (браузерный
 * пикер закрыт) — не ошибка.
 *
 * «Покинуть» — onLeave (useConference.leave): слот вкладки (Q10=A)
 * разрывает медиа-подключение через bindConferenceDisconnect (T027),
 * остальные видят выход (departure_timeout). Доступность — стандарты
 * 008: aria-pressed тумблеров (стабильное aria-label + визуальный
 * индикатор «вкл/выкл» aria-hidden), focus-visible (conferences.css),
 * role=group семантика панели.
 */
import { useConnectionState, useLocalParticipant } from '@livekit/components-react'
import { ConnectionState } from 'livekit-client'
import { useState } from 'react'

/** Слоты публикации, переключаемые тумблерами. */
type MediaSlot = 'microphone' | 'camera' | 'screen'

export interface MediaControlsProps {
  /** Выход из конференции: useConference.leave — корректный разрыв слота вкладки (Q10=A). */
  readonly onLeave: () => void
}

/** Описание одного тумблера: проекция состояния + переключатель LiveKit. */
interface SlotToggle {
  readonly slot: MediaSlot
  readonly label: string
  readonly enabled: boolean
  readonly toggle: (enabled: boolean) => Promise<void>
}

/** Нейтральное сообщение об отказе переключения (детали причин не раскрываются). */
function toggleFailureText(slot: MediaSlot, enable: boolean): string {
  const noun =
    slot === 'microphone' ? 'микрофон' : slot === 'camera' ? 'камеру' : 'демонстрацию экрана'
  return enable ? `Не удалось включить ${noun}` : `Не удалось выключить ${noun}`
}

/** Отмена браузерного пикера экрана самим пользователем (AbortError) — не ошибка. */
function isUserCancelledScreenPick(cause: unknown): boolean {
  return (
    typeof cause === 'object' &&
    cause !== null &&
    (cause as { name?: unknown }).name === 'AbortError'
  )
}

export default function MediaControls({ onLeave }: MediaControlsProps) {
  const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } =
    useLocalParticipant()
  const connectionState = useConnectionState()
  /** Тумблер в полёте: все тумблеры недоступны до завершения (без гонок переключений). */
  const [pending, setPending] = useState<MediaSlot | null>(null)
  /** Нейтральная строка последнего отказа (сбрасывается следующей попыткой). */
  const [notice, setNotice] = useState<string | null>(null)

  const connected = connectionState === ConnectionState.Connected

  const toggles: readonly SlotToggle[] = [
    {
      slot: 'microphone',
      label: 'Микрофон',
      enabled: isMicrophoneEnabled,
      toggle: async (enabled) => {
        await localParticipant.setMicrophoneEnabled(enabled)
      },
    },
    {
      slot: 'camera',
      label: 'Камера',
      enabled: isCameraEnabled,
      toggle: async (enabled) => {
        await localParticipant.setCameraEnabled(enabled)
      },
    },
    {
      slot: 'screen',
      label: 'Экран',
      enabled: isScreenShareEnabled,
      toggle: async (enabled) => {
        await localParticipant.setScreenShareEnabled(enabled)
      },
    },
  ]

  async function flip(toggle: SlotToggle): Promise<void> {
    const enable = !toggle.enabled
    setNotice(null)
    setPending(toggle.slot)
    try {
      await toggle.toggle(enable)
    } catch (cause) {
      // Состояние не откатывается вручную — источник истины комната;
      // отказ (запрет устройств/грантов/медиа) — только нейтральная строка.
      if (!isUserCancelledScreenPick(cause)) {
        setNotice(toggleFailureText(toggle.slot, enable))
      }
    } finally {
      setPending(null)
    }
  }

  return (
    <div className="conference-controls" role="group" aria-label="Управление конференцией">
      {toggles.map((toggle) => (
        <button
          key={toggle.slot}
          type="button"
          className="conference-controls-toggle"
          aria-label={toggle.label}
          aria-pressed={toggle.enabled}
          disabled={!connected || pending !== null}
          onClick={() => {
            void flip(toggle)
          }}
        >
          <span aria-hidden="true">{toggle.label}</span>
          <span className="conference-controls-state" aria-hidden="true">
            {toggle.enabled ? 'вкл' : 'выкл'}
          </span>
        </button>
      ))}
      <button type="button" className="conference-controls-leave" onClick={onLeave}>
        Покинуть
      </button>
      {notice !== null && (
        <p className="conference-controls-notice" role="status">
          {notice}
        </p>
      )}
    </div>
  )
}
