/**
 * Pre-join экран (009, US2, T030; FR-005–FR-007) — единая настройка
 * перед входом для ВСЕХ точек входа (видеозвонок/аудиозвонок/ссылка-
 * приглашение — встраивается ConferencePage T034; гостевая ссылка —
 * GuestJoinPage T049): локальное превью камеры, тумблеры стартового
 * состояния «микрофон»/«камера», выбор устройств ввода/вывода, кнопка
 * «Войти».
 *
 * Дефолты по mode точки входа (FR-005, Q3=A soft): video → камера+
 * микрофон; audio (гостевая ссылка без mode — та же семантика «только
 * микрофон», T049 передаёт 'audio') → только микрофон; тумблер камеры
 * присутствует и доступен ВСЕГДА — жёсткий запрет публикации видео в
 * аудиозвонке не вводится, камера включается в любой момент, в том
 * числе здесь, до входа. Оба тумблера выключены → «только слушаю»
 * (FR-006): onJoin отдаёт фактическое стартовое состояние, публикацию
 * выполняет звонок (T034 → ConferenceRoom initialMedia).
 *
 * До «Войти» НИЧЕГО не публикуется и слот квоты не занимается (FR-007):
 * превью — ЛОКАЛЬНЫЙ трек livekit-client (createLocalVideoTrack,
 * research §10), без подключения к комнате и без вызовов №47/№55.
 * Микрофонный трек не запрашивается вовсе: тумблер — чистый выбор
 * стартового состояния, устройство запрашивается публикацией уже в
 * звонке (T027) — pre-join не держит микрофон лишним индикатором
 * записи браузера. Камерный трек живёт только пока тумблер включён:
 * выключение тумблера, «Войти» и размонтирование останавливают трек
 * (устройство освобождается — «без следов», FR-007). Отказ браузера
 * в доступе к камере (Q6=A) не блокирует вход: нейтральное
 * предупреждение + вход «только слушаю» доступен (полный UX отказа
 * в PreJoinScreen/ConferenceRoom — T032; блокировка тумблеров по
 * ограничениям №46 MeView — T033).
 *
 * Выбор устройств: перечисление MediaDevices + сигналы devicechange
 * (подключение/отключение устройств обновляет списки). Камера
 * переключяется сразу — setDeviceId локального трека (превью
 * обновляется); микрофон/динамики — выбор запоминается и уезжает в
 * onJoin-выборке (применение в звонке — T031/T034:
 * Room.switchActiveDevice/setSinkId). Метки устройств браузер
 * раскрывает только после соответствующего разрешения — список
 * пере-перечисляется после получения камеры. Выбор устройств «на лету»
 * уже в звонке — хук useDevices (T031), здесь только стартовый выбор.
 *
 * Доступность — стандарты 008 (research §10): нативный <dialog> (
 * диалоговая семантика + aria-labelledby заголовка), aria-pressed
 * тумблеров со визуальным индикатором «вкл/выкл» (aria-hidden), label
 * + select устройств, focus-visible (conferences.css), статус-строка
 * <output> (неявный role=status).
 */
import { createLocalVideoTrack } from 'livekit-client'
import type { LocalVideoTrack } from 'livekit-client'
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import type { ConferenceMode } from '../api/conferences'
import '../conferences.css'

/** Выбранные устройства pre-join (применение в звонке — T031/T034); null — системное устройство по умолчанию. */
export interface PreJoinDevices {
  readonly audioInput: string | null
  readonly videoInput: string | null
  readonly audioOutput: string | null
}

/** Стартовая выборка pre-join по «Войти»: тумблеры + выбранные устройства (FR-005/FR-006). */
export interface PreJoinSelection {
  readonly microphone: boolean
  readonly camera: boolean
  readonly devices: PreJoinDevices
}

export interface PreJoinScreenProps {
  /**
   * mode точки входа (№44/карточка/гость — FR-005): video → камера+
   * микрофон, audio (гость) → только микрофон (тумблер камеры доступен —
   * soft, Q3=A).
   */
  readonly mode: ConferenceMode
  /** «Войти»: стартовое состояние уезжает в звонок (T034 → initialMedia). */
  readonly onJoin: (selection: PreJoinSelection) => void
  /** Выход из pre-join без входа (FR-007: отмена подключения без последствий); кнопки нет, если не задан. */
  readonly onCancel?: () => void
  /** Минт №47/№55 в полёте (T034/T049): «Войти» недоступна, aria-busy. */
  readonly joining?: boolean
}

/** Списки устройств по видам (enumerateDevices, метки — после разрешения). */
interface DeviceKinds {
  readonly audioInputs: readonly MediaDeviceInfo[]
  readonly videoInputs: readonly MediaDeviceInfo[]
  readonly audioOutputs: readonly MediaDeviceInfo[]
}

const EMPTY_DEVICES: DeviceKinds = { audioInputs: [], videoInputs: [], audioOutputs: [] }

/** Дефолт камеры по mode точки входа (FR-005, Q3=A): микрофон включён в обоих режимах, камера — только в video. */
function initialCamera(mode: ConferenceMode): boolean {
  return mode === 'video'
}

/** Метка устройства: браузер скрывает label до разрешения — нумерованный fallback. */
function deviceLabel(device: MediaDeviceInfo, fallbackBase: string, index: number): string {
  return device.label.trim() !== '' ? device.label : `${fallbackBase} ${index + 1}`
}

export default function PreJoinScreen({
  mode,
  onJoin,
  onCancel,
  joining = false,
}: PreJoinScreenProps) {
  const titleId = useId()
  const micSelectId = useId()
  const cameraSelectId = useId()
  const speakerSelectId = useId()

  /** Тумблеры стартового состояния: микрофон (вкл в обоих режимах, FR-005) — чистый выбор (трек не держим), камера — живой локальный трек превью. */
  const [micOn, setMicOn] = useState(true)
  const [cameraOn, setCameraOn] = useState(() => initialCamera(mode))
  /** Камера в полёте acquisition/переключения — тумблер недоступен, плейсхолдер «Включение камеры…». */
  const [cameraPending, setCameraPending] = useState(false)
  /** Нейтральное предупреждение (отказ доступа/переключения устройства; детали не раскрываются). */
  const [notice, setNotice] = useState<string | null>(null)

  const [devices, setDevices] = useState<DeviceKinds>(EMPTY_DEVICES)
  /** '' — системное устройство по умолчанию (FR-005: выбор ввода/вывода). */
  const [audioInputId, setAudioInputId] = useState('')
  const [videoInputId, setVideoInputId] = useState('')
  const [audioOutputId, setAudioOutputId] = useState('')

  /** Локальный трек превью: живёт только при включённой камере (FR-007 — «без следов»). */
  const cameraTrackRef = useRef<LocalVideoTrack | null>(null)
  const videoElementRef = useRef<HTMLVideoElement | null>(null)
  /** Монотонное поколение камеры: аннулирует висящие acquisition при выключении/«Войти»/размонтировании. */
  const cameraSeqRef = useRef(0)
  const cameraPendingRef = useRef(false)
  const videoInputIdRef = useRef('')
  videoInputIdRef.current = videoInputId
  /** Стартовый дефолт камеры фиксируется на монтаж (mode точки входа — FR-005). */
  const initialCameraRef = useRef(cameraOn)

  /** Пере-перечисление устройств: начальный список, devicechange, unlock меток после разрешения камеры. */
  const refreshDevices = useCallback(async (): Promise<void> => {
    const media = navigator.mediaDevices
    if (media === undefined || typeof media.enumerateDevices !== 'function') {
      return
    }
    try {
      const list = await media.enumerateDevices()
      setDevices({
        audioInputs: list.filter((device) => device.kind === 'audioinput'),
        videoInputs: list.filter((device) => device.kind === 'videoinput'),
        audioOutputs: list.filter((device) => device.kind === 'audiooutput'),
      })
    } catch {
      // перечисление недоступно — селекты остаются с «По умолчанию»
    }
  }, [])

  /**
   * Включение камеры: acquisition локального трека превью (по выбранному
   * устройству, если уже выбрано). Отказ браузера (Q6=A) — предупреждение
   * и возврат тумблера: вход «только слушаю» остаётся доступным.
   */
  const enableCamera = useCallback(async (): Promise<void> => {
    if (cameraPendingRef.current) {
      return
    }
    const seq = ++cameraSeqRef.current
    cameraPendingRef.current = true
    setCameraPending(true)
    setNotice(null)
    try {
      const preferred = videoInputIdRef.current
      const track = await createLocalVideoTrack(
        preferred === '' ? undefined : { deviceId: preferred },
      )
      if (seq !== cameraSeqRef.current) {
        track.stop()
        return
      }
      cameraTrackRef.current = track
      setCameraOn(true)
      // разрешение получено — браузер раскрывает метки, обновляем списки
      void refreshDevices()
    } catch {
      if (seq === cameraSeqRef.current) {
        setNotice('Нет доступа к камере — можно войти без видео')
      }
    } finally {
      if (seq === cameraSeqRef.current) {
        cameraPendingRef.current = false
        setCameraPending(false)
      }
    }
  }, [refreshDevices])

  /** Освобождение камеры: трек останавливается (устройство свободно), поколение аннулирует висящие acquisition. */
  const releaseCamera = useCallback((): void => {
    cameraSeqRef.current++
    cameraPendingRef.current = false
    setCameraPending(false)
    cameraTrackRef.current?.stop()
    cameraTrackRef.current = null
    setCameraOn(false)
  }, [])

  // Монтаж экрана: стартовое приобретение превью по дефолту mode
  // (FR-005), перечисление устройств + сигналы devicechange. Размонтиро-
  // вание/закрытие pre-join — трек остановлен, «без следов» (FR-007).
  useEffect(() => {
    void refreshDevices()
    if (initialCameraRef.current) {
      void enableCamera()
    }
    const media = navigator.mediaDevices
    const onDeviceChange = (): void => {
      void refreshDevices()
    }
    media?.addEventListener('devicechange', onDeviceChange)
    return () => {
      media?.removeEventListener('devicechange', onDeviceChange)
      // eslint-disable-next-line react-hooks/exhaustive-deps -- намеренная инвалидация висящих acquisition камеры при размонтировании: монотонный счётчик поколений (не DOM-ref), инкремент в cleanup — сам механизм устаревания (устаревший трек останавливается сам себя, конституция III)
      cameraSeqRef.current++
      cameraTrackRef.current?.stop()
      cameraTrackRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- монтаж экрана: стартовые acquisition/слушатель живут весь жизненный цикл pre-join; дефолт mode зафиксирован в initialCameraRef (mode точки входа неизменен для одного экрана)
  }, [])

  // Превью: attach локального трека к video-элементу только при
  // включённой камере (смена поколения — через cameraOn false→true,
  // свежий трек подхватывается из ref).
  useEffect(() => {
    const element = videoElementRef.current
    const track = cameraTrackRef.current
    if (!cameraOn || element === null || track === null) {
      return
    }
    track.attach(element)
    return () => {
      track.detach(element)
    }
  }, [cameraOn])

  function flipCamera(): void {
    if (cameraOn) {
      releaseCamera()
    } else {
      void enableCamera()
    }
  }

  /** Микрофон/динамики: выбор запоминается (применение — T031/T034), камера — переключается сразу (превью). */
  function handleVideoInputChange(event: ChangeEvent<HTMLSelectElement>): void {
    const deviceId = event.target.value
    setVideoInputId(deviceId)
    setNotice(null)
    const track = cameraTrackRef.current
    if (track !== null && deviceId !== '') {
      track.setDeviceId(deviceId).catch(() => {
        setNotice('Не удалось переключить камеру')
      })
    }
  }

  /**
   * «Войти» (FR-007: до этого момента ничего не публиковалось и слот
   * не занимался): стартовое состояние + выбранные устройства уходят
   * родителю (T034/T049 → join №47/№55 и ConferenceRoom), превью-трек
   * освобождается сразу — устройства под публикацию звонка.
   */
  function handleJoin(): void {
    const selection: PreJoinSelection = {
      microphone: micOn,
      camera: cameraOn,
      devices: {
        audioInput: audioInputId === '' ? null : audioInputId,
        videoInput: videoInputId === '' ? null : videoInputId,
        audioOutput: audioOutputId === '' ? null : audioOutputId,
      },
    }
    releaseCamera()
    onJoin(selection)
  }

  return (
    <dialog open className="conference-prejoin" aria-labelledby={titleId}>
      <h2 className="conference-prejoin-title" id={titleId}>
        Настройка перед входом
      </h2>
      <div className="conference-prejoin-preview">
        {cameraOn ? (
          <video
            ref={videoElementRef}
            className="conference-prejoin-video"
            muted
            playsInline
            autoPlay
            aria-label="Предпросмотр камеры"
          />
        ) : (
          <p className="conference-prejoin-placeholder" aria-hidden="true">
            {cameraPending ? 'Включение камеры…' : 'Камера выключена'}
          </p>
        )}
      </div>
      <fieldset className="conference-prejoin-toggles" aria-label="Стартовое состояние">
        <button
          type="button"
          className="conference-controls-toggle"
          aria-label="Микрофон"
          aria-pressed={micOn}
          onClick={() => {
            setMicOn((on) => !on)
          }}
        >
          <span aria-hidden="true">Микрофон</span>
          <span className="conference-controls-state" aria-hidden="true">
            {micOn ? 'вкл' : 'выкл'}
          </span>
        </button>
        <button
          type="button"
          className="conference-controls-toggle"
          aria-label="Камера"
          aria-pressed={cameraOn}
          disabled={cameraPending}
          onClick={flipCamera}
        >
          <span aria-hidden="true">Камера</span>
          <span className="conference-controls-state" aria-hidden="true">
            {cameraOn ? 'вкл' : 'выкл'}
          </span>
        </button>
      </fieldset>
      <div className="conference-prejoin-devices">
        <div className="conference-prejoin-device">
          <label htmlFor={micSelectId}>Микрофон</label>
          <select
            id={micSelectId}
            value={audioInputId}
            onChange={(event) => {
              setAudioInputId(event.target.value)
            }}
          >
            <option value="">По умолчанию</option>
            {devices.audioInputs.map((device, index) => (
              <option key={device.deviceId || `audio-in-${index}`} value={device.deviceId}>
                {deviceLabel(device, 'Микрофон', index)}
              </option>
            ))}
          </select>
        </div>
        <div className="conference-prejoin-device">
          <label htmlFor={cameraSelectId}>Камера</label>
          <select id={cameraSelectId} value={videoInputId} onChange={handleVideoInputChange}>
            <option value="">По умолчанию</option>
            {devices.videoInputs.map((device, index) => (
              <option key={device.deviceId || `video-in-${index}`} value={device.deviceId}>
                {deviceLabel(device, 'Камера', index)}
              </option>
            ))}
          </select>
        </div>
        <div className="conference-prejoin-device">
          <label htmlFor={speakerSelectId}>Динамик</label>
          <select
            id={speakerSelectId}
            value={audioOutputId}
            onChange={(event) => {
              setAudioOutputId(event.target.value)
            }}
          >
            <option value="">По умолчанию</option>
            {devices.audioOutputs.map((device, index) => (
              <option key={device.deviceId || `audio-out-${index}`} value={device.deviceId}>
                {deviceLabel(device, 'Динамик', index)}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="conference-prejoin-actions">
        {onCancel !== undefined && (
          <button type="button" className="conference-prejoin-cancel" onClick={onCancel}>
            Отмена
          </button>
        )}
        <button
          type="button"
          className="conference-prejoin-join"
          disabled={joining}
          aria-busy={joining}
          onClick={handleJoin}
        >
          Войти
        </button>
      </div>
      {notice !== null && <output className="conference-prejoin-notice">{notice}</output>}
    </dialog>
  )
}
