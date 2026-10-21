/**
 * Панель управления конференцией (009, US1, T028 + US3, T043; FR-004/FR-006):
 * тумблеры микрофона/камеры/демонстрации экрана и «Покинуть» — рендерится
 * в слоте children ConferenceRoom (T027), т.е. внутри RoomContext:
 * состояние тумблеров — НЕ локальный стейт, а проекция фактических
 * публикаций localParticipant (useLocalParticipant: isMicrophoneEnabled/
 * isCameraEnabled/isScreenShareEnabled) — единственный источник истины
 * медиа-плоскость LiveKit, поэтому UI не расходится с комнатой (стартовая
 * публикация — publishInitialTracks T027; серверные мьюты модератора
 * отражаются сами — MutePublishedTrack гасит публикацию).
 *
 * Собственные ограничения (T043, FR-007/FR-008, SC-005): заблокированные
 * тумблеры с пояснением. Два источника сходятся в один блокирующий
 * сигнал (OR — консервативно, сходится к серверному состоянию):
 *  - LIVE-проекция grants подключения (restrictionMarks T041:
 *    canPublish=false — «право слова» Q4=A, срезанный источник —
 *    точечное ограничение): сервер применяет ограничения №49
 *    немедленно UpdateParticipant'ом, RoomEvent.ParticipantPermissions-
 *    Changed переподнимает рендер (SC-005 ≤ 2 c);
 *  - снимок №46 MeView (restrictions-props из useConference.me):
 *    ограничения персистентны (Q5/Q22=A) и вшиты в grants минта,
 *    снимок догоняется повторным №46 (onRestrictionsSync —
 *    useConference.refreshMe) по тому же permission-событию:
 *    после выхода на pre-join блокировки актуальны (T033).
 *
 * FR-006 «только слушаю»: все три тумблера выключены — легальное
 * состояние; включение публикации прямо в звонке — теми же тумблерами
 * (в рамках прав: grants срезаны сервером в токене №47, FR-007 —
 * запрещённый источник просто не публикуется, отказ виден нейтральной
 * строкой без раскрытия деталей). Повторный запрос доступа к устройствам
 * после запрета браузера — попытка включения тумблером (Q6=A; отдельный
 * UX отказа — T032). Отмена собственного выбора экрана (браузерный
 * пикер закрыт) — не ошибка. Старт демонстрации экрана при включённой
 * камере полностью отключает камеру (T077 — трек снимается с публикации,
 * у участников видео сменяется аватаром-заглушкой T073); обратное
 * включение — только вручную тумблером (осознанное решение пользователя).
 *
 * «Покинуть» — onLeave (useConference.leave): слот вкладки (Q10=A)
 * разрывает медиа-подключение через bindConferenceDisconnect (T027),
 * остальные видят выход (departure_timeout). Доступность — стандарты
 * 008: aria-pressed тумблеров (стабильное aria-label + визуальный
 * индикатор «вкл/выкл» aria-hidden), focus-visible (conferences.css),
 * fieldset-семантика группы панели (implicit role=group).
 */
import { useConnectionState, useLocalParticipant, useRoomContext } from '@livekit/components-react'
import { ConnectionState, RoomEvent } from 'livekit-client'
import { useEffect, useRef, useState } from 'react'
import { restrictionMarks } from './ParticipantsPanel'
import type { ParticipantRestrictionMarks } from './ParticipantsPanel'

/** Слоты публикации, переключаемые тумблерами. */
type MediaSlot = 'microphone' | 'camera' | 'screen'

export interface MediaControlsProps {
  /**
   * Снимок собственных ограничений №46 MeView (T043): MeView структурно
   * совместим с ParticipantRestrictionMarks; null/undefined — ограничений
   * нет (источник — только живие grants). Гость №55 сюда не передаётся:
   * его live-права читаются напрямую из grants подключения.
   */
  readonly restrictions?: ParticipantRestrictionMarks | null
  /**
   * Повторный №46 по permission-событию LiveKit (T043: live-обновление
   * снимка me — useConference.refreshMe); не задан — событие игнорируется.
   */
  readonly onRestrictionsSync?: () => void
  /** Выход из конференции: useConference.leave — корректный разрыв слота вкладки (Q10=A). */
  readonly onLeave: () => void
}

/** Описание одного тумблера: проекция состояния + переключатель LiveKit. */
interface SlotToggle {
  readonly slot: MediaSlot
  readonly label: string
  readonly enabled: boolean
  readonly blocked: boolean
  readonly toggle: (enabled: boolean) => Promise<void>
}

/**
 * Пояснения блокировки собственных источников (T043, FR-008):
 * «право слова» (Q4=A) блокирует ВСЕ публикации — одно объединяющее
 * пояснение; точечные — по отдельности (тексты — как на pre-join T033).
 */
function restrictionNotices(effective: ParticipantRestrictionMarks): string[] {
  if (effective.speechRestricted) {
    return ['Публикация отключена модератором (право слова)']
  }
  const notices: string[] = []
  if (effective.micRestricted) {
    notices.push('Микрофон отключён модератором')
  }
  if (effective.cameraRestricted) {
    notices.push('Камера отключена модератором')
  }
  if (effective.screenRestricted) {
    notices.push('Демонстрация экрана отключена модератором')
  }
  return notices
}

/** Нейтральное сообщение об отказе переключения (детали причин не раскрываются). */
function toggleFailureText(slot: MediaSlot, enable: boolean): string {
  const noun = slotNoun(slot)
  return enable ? `Не удалось включить ${noun}` : `Не удалось выключить ${noun}`
}

/** Существительное слота для строки отказа переключения. */
function slotNoun(slot: MediaSlot): string {
  switch (slot) {
    case 'microphone':
      return 'микрофон'
    case 'camera':
      return 'камеру'
    default:
      return 'демонстрацию экрана'
  }
}

/** Отмена браузерного пикера экрана самим пользователем (AbortError) — не ошибка. */
function isUserCancelledScreenPick(cause: unknown): boolean {
  return (
    typeof cause === 'object' &&
    cause !== null &&
    (cause as { name?: unknown }).name === 'AbortError'
  )
}

export default function MediaControls({
  restrictions = null,
  onRestrictionsSync,
  onLeave,
}: MediaControlsProps) {
  const room = useRoomContext()
  const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } =
    useLocalParticipant()
  const connectionState = useConnectionState()
  /** Тумблер в полёте: все тумблеры недоступны до завершения (без гонок переключений). */
  const [pending, setPending] = useState<MediaSlot | null>(null)
  /** Нейтральная строка последнего отказа (сбрасывается следующей попыткой). */
  const [notice, setNotice] = useState<string | null>(null)
  /** Зеркало колбэка: эффект подписки живёт на комнату, без пересозданий. */
  const syncRef = useRef(onRestrictionsSync)
  syncRef.current = onRestrictionsSync

  // Сервер применил/снял ограничения живому подключению (№49 →
  // UpdateParticipant, SC-005): RoomEvent.ParticipantPermissionsChanged
  // приходит парой (prevPermissions, participant) — локальный случай
  // запускает повторный №46 (снимок me догоняет БД: pre-join после
  // выхода показывает актуальные блокировки, T033/T043); сама
  // блокировка тумблеров уже пересчитана live-проекцией grants
  // (useLocalParticipant переподнимает рендер тем же событием).
  useEffect(() => {
    const handlePermissionsChanged = (
      _prevPermissions: unknown,
      participant: { readonly isLocal: boolean },
    ): void => {
      if (participant.isLocal) {
        syncRef.current?.()
      }
    }
    room.on(RoomEvent.ParticipantPermissionsChanged, handlePermissionsChanged)
    return () => {
      room.off(RoomEvent.ParticipantPermissionsChanged, handlePermissionsChanged)
    }
  }, [room])

  const connected = connectionState === ConnectionState.Connected

  // Live-ограничения — фактические grants подключения (T041-проекция);
  // снимок №46 прибавляется (OR): консервативно до подтверждения
  // рефетчем, сходится к серверному SET-состоянию (идемпотентность №49).
  const live = restrictionMarks(localParticipant.permissions)
  const speechBlocked = live.speechRestricted || restrictions?.speechRestricted === true
  const micBlocked = speechBlocked || live.micRestricted || restrictions?.micRestricted === true
  const cameraBlocked =
    speechBlocked || live.cameraRestricted || restrictions?.cameraRestricted === true
  const screenBlocked =
    speechBlocked || live.screenRestricted || restrictions?.screenRestricted === true
  /** Пояснения показываются по объединённому состоянию (поглощение «правом слова» — Q4=A). */
  const restrictionNoticeList = restrictionNotices({
    micRestricted: micBlocked,
    cameraRestricted: cameraBlocked,
    screenRestricted: screenBlocked,
    speechRestricted: speechBlocked,
  })

  const toggles: readonly SlotToggle[] = [
    {
      slot: 'microphone',
      label: 'Микрофон',
      enabled: isMicrophoneEnabled,
      blocked: micBlocked,
      toggle: async (enabled) => {
        await localParticipant.setMicrophoneEnabled(enabled)
      },
    },
    {
      slot: 'camera',
      label: 'Камера',
      enabled: isCameraEnabled,
      blocked: cameraBlocked,
      toggle: async (enabled) => {
        await localParticipant.setCameraEnabled(enabled)
      },
    },
    {
      slot: 'screen',
      label: 'Экран',
      enabled: isScreenShareEnabled,
      blocked: screenBlocked,
      toggle: async (enabled) => {
        await localParticipant.setScreenShareEnabled(enabled)
        // T077: при УСПЕШНОМ старте демонстрации собственная камера
        // полностью снимается с публикации (экономия исходящего канала/
        // CPU); обратного автопереключения нет — включение только
        // вручную тумблером. Отмена пикера/ошибка старта экрана
        // бросают выше — камера не тронута; выключение — не публикация,
        // cameraRestricted не блокирует.
        if (enabled && isCameraEnabled) {
          await localParticipant.setCameraEnabled(false)
        }
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
    <fieldset className="conference-controls" aria-label="Управление конференцией">
      {toggles.map((toggle) => (
        <button
          key={toggle.slot}
          type="button"
          className="conference-controls-toggle"
          aria-label={toggle.label}
          aria-pressed={toggle.enabled}
          disabled={!connected || pending !== null || toggle.blocked}
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
      {restrictionNoticeList.length > 0 && (
        <div className="conference-controls-restrictions">
          {restrictionNoticeList.map((text) => (
            <output key={text} className="conference-controls-notice">
              {text}
            </output>
          ))}
        </div>
      )}
      {notice !== null && <output className="conference-controls-notice">{notice}</output>}
    </fieldset>
  )
}
