/**
 * Панель управления конференцией (009, US1, T028 → T098 + US3, T043;
 * FR-004/FR-006): стимпанк-панель кнопок-иконок по прототипу
 * tmp/conference-controls.html (визуальный эталон — при расхождениях
 * побеждает прототип, по образцу FR-001 008): оболочка machine
 * (fieldset .conference-controls: двойная латунная рамка, фон-пластина
 * + backdrop-blur, decor-layer с двумя вращающимися шестерёнками) и
 * 4 круглые кнопки-иконки 78px «Микрофон»/«Камера»/«Экран»/«Покинуть».
 * Рендерится в слоте children ConferenceRoom (T027), т.е. внутри
 * RoomContext: состояние кнопок — НЕ локальный стейт, а проекция
 * фактических публикаций localParticipant (useLocalParticipant:
 * isMicrophoneEnabled/isCameraEnabled/isScreenShareEnabled) —
 * единственный источник истины медиа-плоскость LiveKit, поэтому UI не
 * расходится с комнатой (стартовая публикация — publishInitialTracks
 * T027; серверные мьюты модератора отражаются сами — MutePublishedTrack
 * гасит публикацию).
 *
 * Внутри кнопок нет текста «вкл/выкл» — состояние дублируется
 * визуалом (конвенция 008): data-state on = золотая рамка + свечение +
 * горящая лампа-индикатор (flick 4s) + on-части иконки (волны/ирис/
 * луч; шестерня проектора крутится 6s), off = диагональный штрих --err
 * + тусклая иконка + тёмная лампа, blocked («отключён модератором») =
 * приглушение saturate + замок-бейдж в нижнем углу. Пар при
 * переключении — puff (3 клубка, паттерн композера 008 MessageInput).
 * Доступность — стабильный aria-label + aria-pressed, видимый фокус,
 * fieldset-семантика группы (implicit role=group).
 *
 * Собственные ограничения (T043/T097/T098, FR-007/FR-008, SC-005):
 * заблокированные тумблеры; пояснение — ТОЛЬКО латунный tooltip
 * точечного среза (кастомный .conference-controls-tooltip на
 * :hover/:focus-visible — замена нативного title T097, тексты-константы
 * сохранены). Два источника сходятся в один блокирующий сигнал (OR —
 * консервативно, сходится к серверному состоянию):
 *  - LIVE-проекция grants подключения (restrictionMarks T041:
 *    canPublish=false — «Все источники» (доменное speechRestricted,
 *    T094, Q4=A), срезанный источник — точечное ограничение):
 *    сервер применяет ограничения №49 немедленно UpdateParticipant'ом,
 *    RoomEvent.ParticipantPermissionsChanged переподнимает рендер
 *    (SC-005 ≤ 2 c) — tooltip приезжает/уезжает живьём вместе с grants;
 *  - снимок №46 MeView (restrictions-props из useConference.me):
 *    ограничения персистентны (Q5/Q22=A) и вшиты в grants минта,
 *    снимок догоняется повторным №46 (onRestrictionsSync —
 *    useConference.refreshMe) по тому же permission-событию:
 *    после выхода на pre-join блокировки актуальны (T033).
 * Точечное ограничение поясняется tooltip'ом кнопки («Микрофон
 * отключён модератором» и т.п. — те же формулировки, что на pre-join
 * T033); состояние «все источники отключены модератором» в панели
 * управления НЕ выводится вовсе (T097): кнопки просто недоступны
 * (silent), tooltip'ы несут только имена. a11y — aria-describedby на
 * id tooltip-узла; disabled-кнопка фокуса не получает — tooltip
 * дублирует состояние только для мыши, клавиатурный путь не меняется
 * (паритет решения T097).
 *
 * FR-006 «только слушаю»: все три тумблера выключены — легальное
 * состояние; включение публикации прямо в звонке — теми же кнопками
 * (в рамках прав: grants срезаны сервером в токене №47, FR-007 —
 * запрещённый источник просто не публикуется, отказ виден нейтральной
 * строкой .conference-controls-notice без раскрытия деталей).
 * Повторный запрос доступа к устройствам после запрета браузера —
 * попытка включения кнопкой (Q6=A; отдельный UX отказа — T032).
 * Отмена собственного выбора экрана (браузерный пикер закрыт) — не
 * ошибка. Источники своего видео исключают друг друга взаимообразно
 * (T077+T085): старт демонстрации экрана при включённой камере
 * полностью отключает камеру, включение камеры при живой демонстрации
 * останавливает демонстрацию — треки снимаются с публикации, у
 * участников и в собственном тайле трансляция бесшовно сменяется в
 * слитом тайле T084 (ни камеры ни экрана — аватар-заглушка T073).
 *
 * «Покинуть» — onLeave (useConference.leave): слот вкладки (Q10=A)
 * разрывает медиа-подключение через bindConferenceDisconnect (T027),
 * остальные видят выход (departure_timeout); возврат в чат — T095.
 * ПАРИТЕТ двух режимов (T098, пересмотр скрытия T089): панель
 * отображается одинаково в стандартном режиме и пин-оверлее T079 —
 * «Покинуть» видна и кликабельна прямо из развёрнутого тайла (выход
 * одношаговый; Esc/«Свернуть» T082 по-прежнему только сворачивают
 * пин), состав кнопок/интервалы/размер оболочки идентичны — в оверлее
 * отличается только размещение (absolute снизу по центру T079).
 * Гостевой паритет Q1=C: GuestJoinPage монтирует тот же MediaControls.
 */
import { useConnectionState, useLocalParticipant, useRoomContext } from '@livekit/components-react'
import { ConnectionState, RoomEvent } from 'livekit-client'
import { useEffect, useId, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { restrictionMarks } from './ParticipantsPanel'
import type { ParticipantRestrictionMarks } from './ParticipantsPanel'

/** Слоты публикации, переключаемые кнопками. */
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

/** Описание одной кнопки-источника: проекция состояния + переключатель LiveKit. */
interface SlotToggle {
  readonly slot: MediaSlot
  readonly label: string
  readonly enabled: boolean
  readonly blocked: boolean
  /**
   * Уведомление точечного ограничения (тексты-константы T097): null —
   * tooltip кнопки показывает имя; строка — только при ТОЧЕЧНОМ срезе
   * источника (без поглощения мастером «Все источники»).
   */
  readonly restrictionNotice: string | null
  readonly toggle: (enabled: boolean) => Promise<void>
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

/* ---------------------------------------------------------------
 * Иконки прототипа tmp/conference-controls.html (винтажный микрофон
 * с волнами, коробочная камера с ирисом, проекционный аппарат с лучом
 * и шестернёй, дверь со стрелкой): on-части (волны/ирис/луч/шестерня)
 * проявляются в data-state='on', диагональный штрих — в 'off'.
 * --------------------------------------------------------------- */

const MICROPHONE_ICON = (
  <svg
    className="conference-controls-icon"
    viewBox="0 0 32 32"
    aria-hidden="true"
    focusable="false"
  >
    <rect x="12" y="3.5" width="8" height="11.5" rx="4" />
    <path d="M13.8 6.5h4.4M13.4 9.2h5.2M13.8 11.9h4.4" strokeWidth="1.1" opacity=".8" />
    <path d="M8.8 10.5v1.8a7.2 7.2 0 0 0 14.4 0v-1.8" />
    <path d="M16 19.5v3.6M12.2 24h7.6" />
    <path
      className="conference-controls-wave conference-controls-on-part"
      d="M6.2 12.6a4.2 4.2 0 0 0 0 6.8"
    />
    <path
      className="conference-controls-wave conference-controls-on-part"
      d="M3.6 10.6a8 8 0 0 0 0 10.8"
    />
    <path
      className="conference-controls-wave conference-controls-on-part"
      d="M25.8 12.6a4.2 4.2 0 0 1 0 6.8"
    />
    <path
      className="conference-controls-wave conference-controls-on-part"
      d="M28.4 10.6a8 8 0 0 1 0 10.8"
    />
    <line className="conference-controls-off-slash" x1="5" y1="27" x2="27" y2="5" />
  </svg>
)

const CAMERA_ICON = (
  <svg
    className="conference-controls-icon"
    viewBox="0 0 32 32"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M12 7.5v-.9a4 4 0 0 1 8 0v.9" />
    <rect x="5.8" y="7.5" width="20.4" height="15" rx="2.6" />
    <circle cx="16" cy="15" r="4.6" />
    <circle
      className="conference-controls-iris conference-controls-on-part"
      cx="16"
      cy="15"
      r="1.7"
    />
    <path
      className="conference-controls-glint conference-controls-on-part"
      d="M14.6 13.6a2 2 0 0 1 1.6-.5"
    />
    <circle cx="8.4" cy="9.9" r=".7" fill="currentColor" stroke="none" opacity=".7" />
    <circle cx="23.6" cy="9.9" r=".7" fill="currentColor" stroke="none" opacity=".7" />
    <circle cx="8.4" cy="20.1" r=".7" fill="currentColor" stroke="none" opacity=".7" />
    <circle cx="23.6" cy="20.1" r=".7" fill="currentColor" stroke="none" opacity=".7" />
    <path d="M9.5 22.5v1.8M22.5 22.5v1.8" />
    <line className="conference-controls-off-slash" x1="5" y1="27" x2="27" y2="5" />
  </svg>
)

const SCREEN_ICON = (
  <svg
    className="conference-controls-icon"
    viewBox="0 0 32 32"
    aria-hidden="true"
    focusable="false"
  >
    <rect x="3.2" y="10.8" width="8.6" height="7" rx="1.6" />
    <circle cx="7.5" cy="8.2" r="2.1" />
    <path d="M7.5 10.3v.5" />
    <rect x="11.8" y="12.6" width="2.6" height="3" rx="1" />
    <circle
      className="conference-controls-gear conference-controls-on-part"
      cx="6"
      cy="14.3"
      r="2"
      strokeDasharray="2.6 2.1"
      strokeWidth="1.5"
    />
    <rect x="17.8" y="6.2" width="10.8" height="7.6" rx="1.2" />
    <path d="M23.2 13.8v2.6M20.6 16.6h5.2" />
    <path
      className="conference-controls-beam conference-controls-on-part"
      d="M14.6 12.2 17.6 9.2M14.6 16 17.6 13.4"
    />
    <path
      className="conference-controls-pic conference-controls-on-part"
      d="M20 8.4h6.4M20 10.6h4"
      strokeWidth="1.2"
    />
    <line className="conference-controls-off-slash" x1="5" y1="27" x2="27" y2="5" />
  </svg>
)

const LEAVE_ICON = (
  <svg
    className="conference-controls-icon"
    viewBox="0 0 32 32"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M6.2 26V7.4a1.8 1.8 0 0 1 1.8-1.8h8.4a1.8 1.8 0 0 1 1.8 1.8V26" />
    <path d="M4 26h24" />
    <circle cx="14" cy="15.8" r=".9" fill="currentColor" stroke="none" />
    <path d="M12.8 15.8h11.6M20.6 11.8l4 4-4 4" />
  </svg>
)

const SLOT_ICONS: Record<MediaSlot, ReactNode> = {
  microphone: MICROPHONE_ICON,
  camera: CAMERA_ICON,
  screen: SCREEN_ICON,
}

/** Замок-бейдж «отключён модератором» (виден в data-state='blocked'). */
const LOCK_BADGE = (
  <span className="conference-controls-lock" aria-hidden="true">
    <svg viewBox="0 0 16 16" focusable="false">
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.2" />
      <path d="M5.5 7V5.2a2.5 2.5 0 0 1 5 0V7" />
    </svg>
  </span>
)

/** Число клубков пара при переключении — прототип puff() (3 шт). */
const PUFF_COUNT = 3
/** Время жизни клубка (мс): полёт 0.8s + запас (паттерн MessageInput). */
const PUFF_LIFETIME_MS = 1_600

export default function MediaControls({
  restrictions = null,
  onRestrictionsSync,
  onLeave,
}: MediaControlsProps) {
  const room = useRoomContext()
  const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } =
    useLocalParticipant()
  const connectionState = useConnectionState()
  /** Кнопка в полёте: все кнопки недоступны до завершения (без гонок переключений). */
  const [pending, setPending] = useState<MediaSlot | null>(null)
  /** Нейтральная строка последнего отказа (сбрасывается следующей попыткой). */
  const [notice, setNotice] = useState<string | null>(null)
  /** Зеркало колбэка: эффект подписки живёт на комнату, без пересозданий. */
  const syncRef = useRef(onRestrictionsSync)
  syncRef.current = onRestrictionsSync
  /** Таймеры уборки клубков пара (паттерн композера 008 — умирают с панелью). */
  const puffTimersRef = useRef<number[]>([])

  /** Стабильные id tooltip-узлов кнопок (aria-describedby). */
  const tipIds: Record<MediaSlot, string> = {
    microphone: useId(),
    camera: useId(),
    screen: useId(),
  }
  const leaveTipId = useId()

  // Сервер применил/снял ограничения живому подключению (№49 →
  // UpdateParticipant, SC-005): RoomEvent.ParticipantPermissionsChanged
  // приходит парой (prevPermissions, participant) — локальный случай
  // запускает повторный №46 (снимок me догоняет БД: pre-join после
  // выхода показывает актуальные блокировки, T033/T043); сама
  // блокировка кнопок уже пересчитана live-проекцией grants
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

  // Клубки пара умирают вместе с панелью: висящие таймеры уборки
  // не переживают размонтирование (паттерн MessageInput).
  useEffect(() => {
    const timers = puffTimersRef.current
    return () => {
      for (const timer of timers) {
        window.clearTimeout(timer)
      }
    }
  }, [])

  const connected = connectionState === ConnectionState.Connected

  // Live-ограничения — фактические grants подключения (T041-проекция);
  // снимок №46 прибавляется (OR): консервативно до подтверждения
  // рефетчем, сходится к серверскому SET-состоянию (идемпотентность №49).
  const live = restrictionMarks(localParticipant.permissions)
  const speechBlocked = live.speechRestricted || restrictions?.speechRestricted === true
  // Точечные срезы — БЕЗ поглощения мастером: только из них выводится
  // уведомление в tooltip (T097/T098); blocked ниже сохраняет поглощение
  // Q4=A (canPublish=false → все три disabled — регресс поведения
  // T043/T094 не допускается).
  const micRestricted = live.micRestricted || restrictions?.micRestricted === true
  const cameraRestricted = live.cameraRestricted || restrictions?.cameraRestricted === true
  const screenRestricted = live.screenRestricted || restrictions?.screenRestricted === true
  const micBlocked = speechBlocked || micRestricted
  const cameraBlocked = speechBlocked || cameraRestricted
  const screenBlocked = speechBlocked || screenRestricted

  const toggles: readonly SlotToggle[] = [
    {
      slot: 'microphone',
      label: 'Микрофон',
      enabled: isMicrophoneEnabled,
      blocked: micBlocked,
      restrictionNotice: !speechBlocked && micRestricted ? 'Микрофон отключён модератором' : null,
      toggle: async (enabled) => {
        await localParticipant.setMicrophoneEnabled(enabled)
      },
    },
    {
      slot: 'camera',
      label: 'Камера',
      enabled: isCameraEnabled,
      blocked: cameraBlocked,
      restrictionNotice: !speechBlocked && cameraRestricted ? 'Камера отключена модератором' : null,
      toggle: async (enabled) => {
        await localParticipant.setCameraEnabled(enabled)
        // T085: при УСПЕШНОМ включении камеры живая демонстрация экрана
        // останавливается (симметрия T077 — взаимоисключение источников
        // своего видео). Ошибка включения камеры бросает выше —
        // демонстрация не тронута (камера реально не пошла); выключение
        // — не публикация, screenRestricted не блокирует.
        if (enabled && isScreenShareEnabled) {
          await localParticipant.setScreenShareEnabled(false)
        }
      },
    },
    {
      slot: 'screen',
      label: 'Экран',
      enabled: isScreenShareEnabled,
      blocked: screenBlocked,
      restrictionNotice:
        !speechBlocked && screenRestricted ? 'Демонстрация экрана отключена модератором' : null,
      toggle: async (enabled) => {
        await localParticipant.setScreenShareEnabled(enabled)
        // T077: при УСПЕШНОМ старте демонстрации собственная камера
        // полностью снимается с публикации (экономия исходящего канала/
        // CPU; симметричное включение камеры гасит демонстрацию — T085).
        // Отмена пикера/ошибка старта экрана бросают выше — камера не
        // тронута; выключение — не публикация, cameraRestricted не
        // блокирует.
        if (enabled && isCameraEnabled) {
          await localParticipant.setCameraEnabled(false)
        }
      },
    },
  ]

  /** Пар над кнопкой при переключении — прототип puff(), паттерн композера 008. */
  function spawnPuff(button: HTMLElement): void {
    for (let index = 0; index < PUFF_COUNT; index += 1) {
      const puff = document.createElement('i')
      puff.className = 'conference-controls-puff'
      puff.setAttribute('aria-hidden', 'true')
      puff.style.left = `${50 + (index - 1) * 14}%`
      puff.style.animationDelay = `${index * 90}ms`
      button.appendChild(puff)
      puff.addEventListener('animationend', () => puff.remove(), { once: true })
      puffTimersRef.current.push(
        window.setTimeout(() => {
          puff.remove()
        }, PUFF_LIFETIME_MS),
      )
    }
  }

  async function flip(toggle: SlotToggle, button: HTMLElement): Promise<void> {
    const enable = !toggle.enabled
    setNotice(null)
    setPending(toggle.slot)
    spawnPuff(button)
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
      <div className="conference-controls-decor" aria-hidden="true">
        <svg className="conference-controls-decor-tr" viewBox="0 0 48 48">
          <g fill="none" stroke="#c9a24a">
            <circle cx="24" cy="24" r="17" strokeWidth="5" strokeDasharray="5.4 4.6" />
            <circle cx="24" cy="24" r="11.5" strokeWidth="1.4" />
            <circle cx="24" cy="24" r="3.4" strokeWidth="1.4" />
            <path d="M24 12.5v-3M24 35.5v3M12.5 24h-3M35.5 24h3" strokeWidth="1.4" />
          </g>
        </svg>
        <svg className="conference-controls-decor-bl" viewBox="0 0 48 48">
          <g fill="none" stroke="#c9a24a">
            <circle cx="24" cy="24" r="14" strokeWidth="4.5" strokeDasharray="4.6 3.9" />
            <circle cx="24" cy="24" r="9" strokeWidth="1.3" />
            <circle cx="24" cy="24" r="2.6" strokeWidth="1.3" />
          </g>
        </svg>
      </div>
      {toggles.map((toggle) => (
        <button
          key={toggle.slot}
          type="button"
          className="conference-controls-toggle"
          aria-label={toggle.label}
          aria-pressed={toggle.enabled}
          aria-describedby={tipIds[toggle.slot]}
          data-state={toggle.blocked ? 'blocked' : toggle.enabled ? 'on' : 'off'}
          disabled={!connected || pending !== null || toggle.blocked}
          onClick={(event) => {
            void flip(toggle, event.currentTarget)
          }}
        >
          <span className="conference-controls-lamp" aria-hidden="true" />
          {SLOT_ICONS[toggle.slot]}
          {LOCK_BADGE}
          <span className="conference-controls-tooltip" id={tipIds[toggle.slot]}>
            {toggle.restrictionNotice ?? toggle.label}
          </span>
        </button>
      ))}
      <button
        type="button"
        className="conference-controls-leave"
        aria-label="Покинуть"
        aria-describedby={leaveTipId}
        onClick={(event) => {
          spawnPuff(event.currentTarget)
          onLeave()
        }}
      >
        {LEAVE_ICON}
        <span className="conference-controls-tooltip" id={leaveTipId}>
          Покинуть
        </span>
      </button>
      {notice !== null && <output className="conference-controls-notice">{notice}</output>}
    </fieldset>
  )
}
