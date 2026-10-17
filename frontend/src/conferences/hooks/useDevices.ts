/**
 * Хук устройств конференции (009, US2, T031; FR-004, Q11=B) — единая
 * точка работы с медиа-устройствами для pre-join (T034) и звонка:
 *
 *  - перечисление устройств ввода/вывода (enumerateDevices) с живым
 *    обновлением по сигналу devicechange — подключение/отключение
 *    гарнитуры или камеры перестраивает списки, а удаление ВЫБРАННОГО
 *    устройства сбрасывает выбор этой категории на «По умолчанию»
 *    (только bookkeeping: фактический трек живёт в медиа-плоскости
 *    LiveKit и переживает потерю устройства сам);
 *  - состояние разрешений браузера на микрофон/камеру: permissions API
 *    там, где поддержано (Chrome/Firefox), подписка на изменение;
 *    метки enumerateDevices браузер раскрывает только после разрешения —
 *    видимость меток апгрейдит состояние до 'granted' (браузеры без
 *    permissions API — Safari — остаются на 'unknown', T032 различает
 *    только «есть доступ / нет явного отказа»);
 *  - переключение микрофона/камеры/динамиков НА ЛЕТУ в звонке (Q11=B):
 *    Room.switchActiveDevice(kind, deviceId) — переключает уже
 *    опубликованные треки И запоминает выбор в capture-дефолтах комнаты
 *    (мьют-он/офф и позднее включение используют выбранное устройство);
 *    'audiooutput' применяется как sinkId ко входящему аудио — часть
 *    браузеров этого не умеет (Safari): отказ нейтрален и не рушит
 *    звонок, вид отказа — switchError (текст за компонентом, T032/T043);
 *  - стартовый выбор из pre-join (T034): initial применяется best-effort
 *    при появлении комнаты — ДО первой публикации треков, поэтому
 *    микрофон/камера публикуются сразу выбранными устройствами; сбой
 *    фонового применения (устройство выдернуто) — тихий, participant
 *    остаётся слушателем (T027).
 *
 * null-выбор = «По умолчанию»: вне звонка — чистый bookkeeping
 * (устройство запрашивается публикацией уже в звонке, FR-007); в звонке
 * переключение адресуется браузерному default-устройству — Chrome/Edge
 * экспонируют deviceId 'default', Firefox/Safari не имеют выделенного id,
 * адресуется первое перечисленное (порядок браузера). Выбор хранится
 * deviceId (НЕ индексом): списки меняются devicechange'ом, индексная
 * адресация ненадёжна. Синхронизация извне — событие ActiveDeviceChanged
 * комнаты (наши переключения приходят тем же событием — идемпотентно).
 */
import { RoomEvent } from 'livekit-client'
import type { Room } from 'livekit-client'
import { useCallback, useEffect, useRef, useState } from 'react'

/** Вид медиа-устройства (DOM MediaDeviceKind): микрофон / камера / динамики. */
export type ConferenceDeviceKind = MediaDeviceKind

/**
 * Состояние разрешения браузера на устройство: 'unknown' — permissions
 * API недоступен (Safari) и метки ещё не раскрыты; 'denied' — только из
 * permissions API (явный отказ, Q6=A обрабатывает T032).
 */
export type DevicePermission = 'granted' | 'denied' | 'prompt' | 'unknown'

/** Явный выбор устройств; null — системное устройство по умолчанию. */
export interface ConferenceDeviceSelection {
  readonly audioInput: string | null
  readonly videoInput: string | null
  readonly audioOutput: string | null
}

export interface UseDevicesOptions {
  /** Комната звонка (livekit-client): переключение на лету (Q11=B); null — вне звонка (pre-join). */
  readonly room?: Room | null
  /** Стартовый выбор pre-join (T034): применяется best-effort при появлении комнаты. */
  readonly initial?: ConferenceDeviceSelection
}

export interface UseDevicesResult {
  /** enumerateDevices доступен (false — небезопасный контекст/старый браузер: списки пусты, select — false). */
  readonly supported: boolean
  readonly audioInputs: readonly MediaDeviceInfo[]
  readonly videoInputs: readonly MediaDeviceInfo[]
  readonly audioOutputs: readonly MediaDeviceInfo[]
  readonly microphonePermission: DevicePermission
  readonly cameraPermission: DevicePermission
  /** Текущий выбор (null — «По умолчанию»); синхронизируется ActiveDeviceChanged. */
  readonly selection: ConferenceDeviceSelection
  /** Переключение в полёте — селект этой категории недоступен; параллельные select игнорируются. */
  readonly switching: ConferenceDeviceKind | null
  /** Вид последнего НЕУДАВШЕГОСЯ переключения (нейтральный текст — за компонентом); null — отказов нет. */
  readonly switchError: ConferenceDeviceKind | null
  /** Выбор микрофона: в звонке — switchActiveDevice, вне звонка — bookkeeping; false — отказ/занятость. */
  readonly selectAudioInput: (deviceId: string | null) => Promise<boolean>
  readonly selectVideoInput: (deviceId: string | null) => Promise<boolean>
  readonly selectAudioOutput: (deviceId: string | null) => Promise<boolean>
  /** Пере-перечисление (после включения трека метки устройств раскрываются). */
  readonly refresh: () => void
}

/** Поле selection для вида устройства. */
type SelectionField = 'audioInput' | 'videoInput' | 'audioOutput'

const KIND_FIELD: Record<ConferenceDeviceKind, SelectionField> = {
  audioinput: 'audioInput',
  videoinput: 'videoInput',
  audiooutput: 'audioOutput',
}

const DEFAULT_SELECTION: ConferenceDeviceSelection = {
  audioInput: null,
  videoInput: null,
  audioOutput: null,
}

interface DeviceLists {
  readonly audioInputs: readonly MediaDeviceInfo[]
  readonly videoInputs: readonly MediaDeviceInfo[]
  readonly audioOutputs: readonly MediaDeviceInfo[]
}

const EMPTY_LISTS: DeviceLists = { audioInputs: [], videoInputs: [], audioOutputs: [] }

function listForKind(lists: DeviceLists, kind: ConferenceDeviceKind): readonly MediaDeviceInfo[] {
  switch (kind) {
    case 'audioinput':
      return lists.audioInputs
    case 'videoinput':
      return lists.videoInputs
    default:
      return lists.audioOutputs
  }
}

function withSelection(
  current: ConferenceDeviceSelection,
  field: SelectionField,
  value: string | null,
): ConferenceDeviceSelection {
  switch (field) {
    case 'audioInput':
      return current.audioInput === value ? current : { ...current, audioInput: value }
    case 'videoInput':
      return current.videoInput === value ? current : { ...current, videoInput: value }
    default:
      return current.audioOutput === value ? current : { ...current, audioOutput: value }
  }
}

/**
 * Метки устройств видны только при выданном разрешении: раскрытые метки
 * апгрейдят 'prompt'/'unknown' до 'granted'; явный 'denied' (permissions
 * API) и уже 'granted' не пересматриваются.
 */
function inferPermission(
  list: readonly MediaDeviceInfo[],
  current: DevicePermission,
): DevicePermission {
  if (current === 'granted' || current === 'denied') {
    return current
  }
  return list.some((item) => item.label.trim() !== '') ? 'granted' : current
}

/**
 * Удаление выбранного устройства (devicechange) — сброс категории на
 * «По умолчанию». Пустой список НЕ считается удалением (часть браузеров
 * возвращает [] до разрешения — выбор не стираем).
 */
function retainSelection(selected: string | null, list: readonly MediaDeviceInfo[]): string | null {
  if (selected === null || list.length === 0) {
    return selected
  }
  return list.some((item) => item.deviceId === selected) ? selected : null
}

/**
 * Адресат переключения «По умолчанию» в звонке: браузерный default-id
 * (Chrome/Edge экспонируют deviceId 'default'), иначе первое
 * перечисленное устройство (порядок браузера, Firefox/Safari); null —
 * устройств категории нет, переключать нечего.
 */
function defaultDeviceId(list: readonly MediaDeviceInfo[]): string | null {
  const explicit = list.find((item) => item.deviceId === 'default')
  return explicit?.deviceId ?? list[0]?.deviceId ?? null
}

/** permission-имена не входят в TS PermissionName — каст для запроса. */
const PERMISSION_MICROPHONE = 'microphone' as PermissionName
const PERMISSION_CAMERA = 'camera' as PermissionName

export function useDevices({
  room = null,
  initial = DEFAULT_SELECTION,
}: UseDevicesOptions = {}): UseDevicesResult {
  const [lists, setLists] = useState<DeviceLists>(EMPTY_LISTS)
  const [microphonePermission, setMicrophonePermission] = useState<DevicePermission>('unknown')
  const [cameraPermission, setCameraPermission] = useState<DevicePermission>('unknown')
  const [selection, setSelection] = useState<ConferenceDeviceSelection>(initial)
  const [switching, setSwitching] = useState<ConferenceDeviceKind | null>(null)
  const [switchError, setSwitchError] = useState<ConferenceDeviceKind | null>(null)

  /** Зеркала для стабильных колбэков/эффектов (значения читаются в момент вызова). */
  const listsRef = useRef(lists)
  listsRef.current = lists
  const roomRef = useRef(room)
  roomRef.current = room
  const switchingRef = useRef(false)
  /** initial фиксируется на монтаж (выбор pre-join не «догоняет» звонок повторами рендера). */
  const initialRef = useRef(initial)

  const supported =
    typeof navigator !== 'undefined' &&
    navigator.mediaDevices !== undefined &&
    typeof navigator.mediaDevices.enumerateDevices === 'function'

  const refreshDevices = useCallback(async (): Promise<void> => {
    const media = navigator.mediaDevices
    if (media === undefined) {
      return
    }
    try {
      const enumerated = await media.enumerateDevices()
      const next: DeviceLists = {
        audioInputs: enumerated.filter((item) => item.kind === 'audioinput'),
        videoInputs: enumerated.filter((item) => item.kind === 'videoinput'),
        audioOutputs: enumerated.filter((item) => item.kind === 'audiooutput'),
      }
      setLists(next)
      setMicrophonePermission((current) => inferPermission(next.audioInputs, current))
      setCameraPermission((current) => inferPermission(next.videoInputs, current))
      setSelection((current) => ({
        audioInput: retainSelection(current.audioInput, next.audioInputs),
        videoInput: retainSelection(current.videoInput, next.videoInputs),
        audioOutput: retainSelection(current.audioOutput, next.audioOutputs),
      }))
    } catch {
      // перечисление недоступно — списки остаются как есть
    }
  }, [])

  // Монтаж: первичное перечисление.
  useEffect(() => {
    void refreshDevices()
  }, [refreshDevices])

  // Сигналы добавления/удаления устройств: devicechange → пере-перечисление.
  useEffect(() => {
    const media = navigator.mediaDevices
    if (media === undefined) {
      return
    }
    const onDeviceChange = (): void => {
      void refreshDevices()
    }
    media.addEventListener('devicechange', onDeviceChange)
    return () => {
      media.removeEventListener('devicechange', onDeviceChange)
    }
  }, [refreshDevices])

  // Разрешения: permissions API (Chrome/Firefox); отсутствие/отказ
  // запроса — 'unknown' (инференс меток — в refreshDevices). Выдача
  // разрешения меняет state на 'granted' → пере-перечисление: метки
  // устройств раскрываются только после разрешения.
  useEffect(() => {
    const permissions = navigator.permissions
    if (permissions === undefined) {
      return
    }
    let cancelled = false
    const cleanups: Array<() => void> = []
    const watch = (name: PermissionName, apply: (next: DevicePermission) => void): void => {
      permissions
        .query({ name })
        .then((status) => {
          if (cancelled) {
            return
          }
          apply(status.state)
          const onChange = (): void => {
            if (cancelled) {
              return
            }
            apply(status.state)
            if (status.state === 'granted') {
              void refreshDevices()
            }
          }
          status.addEventListener('change', onChange)
          cleanups.push(() => {
            status.removeEventListener('change', onChange)
          })
        })
        .catch(() => {
          // имя не поддержано (Safari) — остаётся инференс по меткам
        })
    }
    watch(PERMISSION_MICROPHONE, setMicrophonePermission)
    watch(PERMISSION_CAMERA, setCameraPermission)
    return () => {
      cancelled = true
      for (const cleanup of cleanups) {
        cleanup()
      }
    }
  }, [refreshDevices])

  // Комната: стартовое применение initial (до первой публикации — capture-
  // дефолты комнаты настраиваются даже до connect, T034) и синхронизация
  // выбора событием ActiveDeviceChanged (внешние переключения и наши —
  // идемпотентно). Смена экземпляра комнаты (реконнект, T027) — выбор
  // применяется заново к свежей комнате.
  useEffect(() => {
    if (room === null) {
      return
    }
    const handleActiveDeviceChanged = (kind: ConferenceDeviceKind, deviceId: string): void => {
      const normalized = deviceId === 'default' || deviceId === '' ? null : deviceId
      setSelection((current) => withSelection(current, KIND_FIELD[kind], normalized))
    }
    room.on(RoomEvent.ActiveDeviceChanged, handleActiveDeviceChanged)
    const wanted = initialRef.current
    const targets: ReadonlyArray<readonly [ConferenceDeviceKind, string | null]> = [
      ['audioinput', wanted.audioInput],
      ['videoinput', wanted.videoInput],
      ['audiooutput', wanted.audioOutput],
    ]
    for (const [kind, deviceId] of targets) {
      if (deviceId !== null) {
        // фон-применение предпочтения: сбой (устройство выдернуто/браузер
        // без setSinkId) не мешает звонку — участник остаётся слушателем
        room.switchActiveDevice(kind, deviceId).catch(() => {})
      }
    }
    return () => {
      room.off(RoomEvent.ActiveDeviceChanged, handleActiveDeviceChanged)
    }
  }, [room])

  const applySelection = useCallback(
    async (kind: ConferenceDeviceKind, deviceId: string | null): Promise<boolean> => {
      if (!supported || switchingRef.current) {
        return false
      }
      switchingRef.current = true
      setSwitching(kind)
      setSwitchError(null)
      try {
        const activeRoom = roomRef.current
        if (activeRoom !== null) {
          const target = deviceId ?? defaultDeviceId(listForKind(listsRef.current, kind))
          if (target !== null) {
            const applied = await activeRoom.switchActiveDevice(kind, target)
            if (!applied) {
              throw new Error('switchActiveDevice rejected the device')
            }
          }
        }
        setSelection((current) => withSelection(current, KIND_FIELD[kind], deviceId))
        return true
      } catch {
        // выбор не меняется (системный дефолт/прежнее устройство остаётся
        // активным), отказ — нейтральный switchError для UI
        setSwitchError(kind)
        return false
      } finally {
        switchingRef.current = false
        setSwitching(null)
      }
    },
    [supported],
  )

  const selectAudioInput = useCallback(
    (deviceId: string | null): Promise<boolean> => applySelection('audioinput', deviceId),
    [applySelection],
  )
  const selectVideoInput = useCallback(
    (deviceId: string | null): Promise<boolean> => applySelection('videoinput', deviceId),
    [applySelection],
  )
  const selectAudioOutput = useCallback(
    (deviceId: string | null): Promise<boolean> => applySelection('audiooutput', deviceId),
    [applySelection],
  )
  const refresh = useCallback((): void => {
    void refreshDevices()
  }, [refreshDevices])

  return {
    supported,
    audioInputs: lists.audioInputs,
    videoInputs: lists.videoInputs,
    audioOutputs: lists.audioOutputs,
    microphonePermission,
    cameraPermission,
    selection,
    switching,
    switchError,
    selectAudioInput,
    selectVideoInput,
    selectAudioOutput,
    refresh,
  }
}
