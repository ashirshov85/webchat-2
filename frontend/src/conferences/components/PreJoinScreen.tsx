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
 * (устройство освобождается — «без следов», FR-007).
 *
 * Отказ браузера в доступе к устройствам (T032, Q6=A) НЕ блокирует
 * вход: понятное предупреждение + «Войти» остаётся доступной, выборка
 * уезжает без недоступных треков («только слушаю», FR-006), повторный
 * запрос доступа — уже в звонке тумблерами T028. Источники отказа
 * (списки устройств и состояния разрешений — хук useDevices T031,
 * единая точка): микрофон — явный 'denied' permissions API (трек на
 * pre-join не запрашивается, acquisition-пути нет); камера — 'denied'
 * permissions API ИЛИ отказ acquisition превью (Safari без permissions
 * API покрывается acquisition-веткой); оба — объединённое
 * предупреждение «как слушатель». Тумблеры остаются доступными:
 * повторное включение камеры здесь — новая попытка acquisition
 * (успех убирает предупреждение), микрофон — выбор на звонок.
 * Блокировка тумблеров по ограничениям прав №46 MeView (T033,
 * FR-007): micRestricted/cameraRestricted блокируют соответствующий
 * тумблер с пояснением, speechRestricted («Все источники» — доменное
 * speechRestricted, T094; Q4=A) блокирует ВСЕ тумблеры публикации —
 * микрофон и камера принудительно
 * «выкл», превью не запрашивается вовсе, выборка «Войти» уезжает без
 * запрещённых источников (сервер всё равно срезал бы grants в токене
 * №47 — UI честен до входа). screenRestricted поясняется строкой:
 * тумблера экрана на pre-join нет (демонстрация начинается в звонке,
 * T028), пояснение снимает сюрприз заблокированного тумблера после
 * входа (T043 — live-обновление). Гостю ограничения не передаются:
 * строка гостя создаётся при входе, на pre-join ограничений нет
 * (data-model §2, T049 не передаёт restrictions).
 *
 * Выбор устройств: перечисление MediaDevices + сигналы devicechange —
 * useDevices (T031; подключение/отключение устройств обновляет списки).
 * Камера переключяется сразу — setDeviceId локального трека (превью
 * обновляется); микрофон/динамики — выбор запоминается и уезжает в
 * onJoin-выборке (применение в звонке — T031/T034:
 * Room.switchActiveDevice/setSinkId). Метки устройств браузер
 * раскрывает только после соответствующего разрешения — список
 * пере-перечисляется после получения камеры. Выбор устройств «на лету»
 * уже в звонке — хук useDevices (T031), здесь только стартовый выбор.
 *
 * Доступность — стандарты 008 (research §10): нативный <dialog> (
 * диалоговая семантика + aria-labelledby заголовка), aria-pressed
 * тумблеров со визуальным индикатором «вкл/выкл» (aria-hidden; классы
 * .conference-prejoin-toggle/-state — текстовая геометрия T028 после
 * переименования T098), label + select устройств, focus-visible
 * (conferences.css), статус-строка <output> (неявный role=status).
 */
import { createLocalVideoTrack } from 'livekit-client'
import type { LocalVideoTrack } from 'livekit-client'
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import type { ConferenceMode, MeView } from '../api/conferences'
import { useDevices } from '../hooks/useDevices'
import '../conferences.css'

/** Выбранные устройства pre-join (применение в звонке — T031/T034); null — системное устройство по умолчанию. */
export interface PreJoinDevices {
  readonly audioInput: string | null
  readonly videoInput: string | null
  readonly audioOutput: string | null
}

/**
 * Ограничения прав принципала для pre-join (T033, FR-007): четыре
 * restricted-поля №46 MeView (роль pre-join не нужна — только блокировки
 * источников). Гость ограничений не передаёт (data-model §2).
 */
export type PreJoinRestrictions = Pick<
  MeView,
  'micRestricted' | 'cameraRestricted' | 'screenRestricted' | 'speechRestricted'
>

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
  /**
   * Ограничения прав №46 MeView (T033, FR-007): undefined/null —
   * ограничений нет (гость на pre-join — строка гостя создаётся при
   * входе, data-model §2); передаётся T034 из useConference.me.
   */
  readonly restrictions?: PreJoinRestrictions | null
}

/**
 * Предупреждение об отказе доступа (T032, Q6=A): микрофон — явный
 * 'denied' permissions API, камера — 'denied' ИЛИ отказ acquisition
 * превью; текст нейтральный, вход остаётся доступным («только слушаю»).
 */
function denialNotice(micDenied: boolean, cameraDenied: boolean): string | null {
  if (micDenied && cameraDenied) {
    return 'Нет доступа к камере и микрофону — можно войти как слушатель'
  }
  if (micDenied) {
    return 'Нет доступа к микрофону — можно войти без звука'
  }
  if (cameraDenied) {
    return 'Нет доступа к камере — можно войти без видео'
  }
  return null
}

/** Дефолт камеры по mode точки входа (FR-005, Q3=A): микрофон включён в обоих режимах, камера — только в video. */
function initialCamera(mode: ConferenceMode): boolean {
  return mode === 'video'
}

/**
 * Пояснения блокировки источников по №46 MeView (T033, FR-007):
 * «Все источники» (доменное speechRestricted, T094; Q4=A) отключает
 * ВСЕ публикации — одно объединяющее пояснение покрывает
 * микрофон/камеру/экран; отдельные restricted-поля поясняются по
 * отдельности (экран — только строкой: тумблера экрана на pre-join
 * нет, демонстрация начинается в звонке — T028).
 */
function restrictionNotices(restrictions: PreJoinRestrictions | null | undefined): string[] {
  if (restrictions === null || restrictions === undefined) {
    return []
  }
  if (restrictions.speechRestricted) {
    return ['Все источники отключены модератором — вы войдёте как слушатель']
  }
  const notices: string[] = []
  if (restrictions.micRestricted) {
    notices.push('Микрофон отключён модератором')
  }
  if (restrictions.cameraRestricted) {
    notices.push('Камера отключена модератором')
  }
  if (restrictions.screenRestricted) {
    notices.push('Демонстрация экрана отключена модератором')
  }
  return notices
}

/** Метка устройства: браузер скрывает label до разрешения — нумерованный fallback. */
function deviceLabel(device: MediaDeviceInfo, fallbackBase: string, index: number): string {
  return device.label.trim() !== '' ? device.label : `${fallbackBase} ${index + 1}`
}

/** Плейсхолдер превью при выключенной камере: ожидание acquisition, блокировка модератором, «выключена». */
function cameraPlaceholder(cameraPending: boolean, cameraBlocked: boolean): string {
  if (cameraPending) {
    return 'Включение камеры…'
  }
  if (cameraBlocked) {
    return 'Камера недоступна'
  }
  return 'Камера выключена'
}

export default function PreJoinScreen({
  mode,
  onJoin,
  onCancel,
  joining = false,
  restrictions = null,
}: PreJoinScreenProps) {
  const titleId = useId()
  const micSelectId = useId()
  const cameraSelectId = useId()
  const speakerSelectId = useId()

  /**
   * Блокировки источников №46 MeView (T033, FR-007): «Все источники»
   * (доменное speechRestricted, T094; Q4=A) глушит ВСЕ публикации —
   * прибавляется к каждому источнику; отдельные restricted-поля —
   * точечно.
   */
  const speechRestricted = restrictions?.speechRestricted === true
  const micBlocked = speechRestricted || restrictions?.micRestricted === true
  const cameraBlocked = speechRestricted || restrictions?.cameraRestricted === true

  /** Тумблеры стартового состояния: микрофон (вкл в обоих режимах, FR-005) — чистый выбор (трек не держим; запрещённый (T033) — принудительно выкл), камера — живой локальный трек превью (НЕ оптимистично: включён только при живом треке — отказ acquisition возвращает «выкл», T032; запрещённая — не включается вовсе). */
  const [micOn, setMicOn] = useState(() => !micBlocked)
  const [cameraOn, setCameraOn] = useState(false)
  /** Камера в полёте acquisition/переключения — тумблер недоступен, плейсхолдер «Включение камеры…». */
  const [cameraPending, setCameraPending] = useState(false)
  /** Отказ acquisition камеры (T032: Safari без permissions API; сбрасывается новой попыткой/выключением). */
  const [cameraFailed, setCameraFailed] = useState(false)
  /** Нейтральное предупреждение переключения устройства (детали не раскрываются). */
  const [notice, setNotice] = useState<string | null>(null)

  /**
   * Устройства и разрешения — useDevices (T031, единая точка):
   * перечисление + devicechange и состояния permissions API.
   */
  const {
    audioInputs,
    videoInputs,
    audioOutputs,
    microphonePermission,
    cameraPermission,
    refresh: refreshDevices,
  } = useDevices()

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
  /** Стартовый дефолт камеры фиксируется на монтаж (mode точки входа — FR-005): интент включения, а не состояние; запрещённая модератором камера (T033) не запрашивается вовсе. */
  const initialCameraRef = useRef(initialCamera(mode) && !cameraBlocked)

  /**
   * Включение камеры: acquisition локального трека превью (по выбранному
   * устройству, если уже выбрано). Отказ браузера (T032, Q6=A) —
   * cameraFailed → предупреждение (вход «только слушаю» остаётся
   * доступным), успешная повторная попытка предупреждение снимает.
   */
  const enableCamera = useCallback(async (): Promise<void> => {
    if (cameraPendingRef.current) {
      return
    }
    const seq = ++cameraSeqRef.current
    cameraPendingRef.current = true
    setCameraPending(true)
    setCameraFailed(false)
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
      refreshDevices()
    } catch {
      if (seq === cameraSeqRef.current) {
        setCameraFailed(true)
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
    setCameraFailed(false)
  }, [])

  // Монтаж экрана: стартовое приобретение превью по дефолту mode
  // (FR-005); перечисление устройств, devicechange и подписка разрешений
  // — useDevices (T031). Размонтирование/закрытие pre-join — трек
  // остановлен, «без следов» (FR-007).
  useEffect(() => {
    if (initialCameraRef.current) {
      void enableCamera()
    }
    return () => {
      // eslint-disable-next-line react-hooks/exhaustive-deps -- намеренная инвалидация висящих acquisition камеры при размонтировании: монотонный счётчик поколений (не DOM-ref), инкремент в cleanup — сам механизм устаревания (устаревший трек останавливается сам себя, конституция III)
      cameraSeqRef.current++
      // висящее acquisition поколения инвалидируется: сброс pending (как
      // releaseCamera) — иначе StrictMode-перемонтаж dev-рендера блокируется
      // флагом «в полёте» мёртвого поколения, а его finally со stale seq
      // pending не сбрасывает — камера «подвисает» выкл навсегда (T064)
      cameraPendingRef.current = false
      setCameraPending(false)
      cameraTrackRef.current?.stop()
      cameraTrackRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- монтаж экрана: стартовое acquisition живёт весь жизненный цикл pre-join; дефолт mode зафиксирован в initialCameraRef (mode точки входа неизменен для одного экрана)
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

  // Ограничение применилось/ужесточилось после монтажа (T033: refetch №46
  // — T043/T034): запрещённые источники принудительно выключаются,
  // превью-трек освобождается — UI не обещает того, что сервер
  // срежет в grants токена №47 (FR-007, Q4=A).
  useEffect(() => {
    if (micBlocked) {
      setMicOn(false)
    }
  }, [micBlocked])

  useEffect(() => {
    if (cameraBlocked) {
      releaseCamera()
    }
  }, [cameraBlocked, releaseCamera])

  function flipCamera(): void {
    if (cameraBlocked) {
      return
    }
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
   * Запрещённые источники (T033: «Все источники» — все, доменное
   * speechRestricted, T094; Q4=A) в выборку
   * не попадают — защита вдогонку принудительным «выкл» тумблеров.
   */
  function handleJoin(): void {
    const selection: PreJoinSelection = {
      microphone: micOn && !micBlocked,
      camera: cameraOn && !cameraBlocked,
      devices: {
        audioInput: audioInputId === '' ? null : audioInputId,
        videoInput: videoInputId === '' ? null : videoInputId,
        audioOutput: audioOutputId === '' ? null : audioOutputId,
      },
    }
    releaseCamera()
    onJoin(selection)
  }

  /**
   * «Отмена» — закрытие pre-join без «Войти» (US2-6, FR-007): превью-трек
   * освобождается СРАЗУ (устройство свободно, «без следов» — не только
   * при выгрузке страницы навигацией родителя), подключение отменяет
   * родитель (T034/T049 — возврат без последствий).
   */
  function handleCancel(): void {
    releaseCamera()
    onCancel?.()
  }

  /** Отказ доступа (T032, Q6=A): микрофон — permissions API 'denied', камера — 'denied' или отказ acquisition превью. */
  const accessNotice = denialNotice(
    microphonePermission === 'denied',
    cameraPermission === 'denied' || cameraFailed,
  )

  /** Пояснения блокировок №46 MeView (T033, FR-007). */
  const restrictionNoticeList = restrictionNotices(restrictions)

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
            {cameraPlaceholder(cameraPending, cameraBlocked)}
          </p>
        )}
      </div>
      <fieldset className="conference-prejoin-toggles" aria-label="Стартовое состояние">
        <button
          type="button"
          className="conference-prejoin-toggle"
          aria-label="Микрофон"
          aria-pressed={micOn}
          disabled={micBlocked}
          onClick={() => {
            setMicOn((on) => !on)
          }}
        >
          <span aria-hidden="true">Микрофон</span>
          <span className="conference-prejoin-state" aria-hidden="true">
            {micOn ? 'вкл' : 'выкл'}
          </span>
        </button>
        <button
          type="button"
          className="conference-prejoin-toggle"
          aria-label="Камера"
          aria-pressed={cameraOn}
          disabled={cameraPending || cameraBlocked}
          onClick={flipCamera}
        >
          <span aria-hidden="true">Камера</span>
          <span className="conference-prejoin-state" aria-hidden="true">
            {cameraOn ? 'вкл' : 'выкл'}
          </span>
        </button>
      </fieldset>
      {restrictionNoticeList.length > 0 && (
        <div className="conference-prejoin-restrictions">
          {restrictionNoticeList.map((text) => (
            <output key={text} className="conference-prejoin-notice">
              {text}
            </output>
          ))}
        </div>
      )}
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
            {audioInputs.map((device, index) => (
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
            {videoInputs.map((device, index) => (
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
            {audioOutputs.map((device, index) => (
              <option key={device.deviceId || `audio-out-${index}`} value={device.deviceId}>
                {deviceLabel(device, 'Динамик', index)}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="conference-prejoin-actions">
        {onCancel !== undefined && (
          <button type="button" className="conference-prejoin-cancel" onClick={handleCancel}>
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
      {accessNotice !== null && (
        <output className="conference-prejoin-notice">{accessNotice}</output>
      )}
      {notice !== null && <output className="conference-prejoin-notice">{notice}</output>}
    </dialog>
  )
}
