/**
 * Медиа-комната конференции (009, US1, T027; FR-004): подключение к
 * медиа-плоскости LiveKit по JoinTicket №47 (wsUrl + короткоживущий
 * токен) — Room livekit-client (simulcast + dynacast + adaptiveStream)
 * и рендер на примитивах @livekit/components-react (RoomContext →
 * useTracks/ParticipantTile/VideoTrack/RoomAudioRenderer, research
 * §10: готовые компоненты вместо ручного WebRTC-рендера — конституция
 * VII). livekit-client остаётся в ленивом чанке страницы (T002/T025 —
 * main-bundle его не содержит).
 *
 * Жизненный цикл комнаты привязан к слоту «одна конференция на
 * вкладку» (Q10=A): bindConferenceDisconnect (useConference) делает
 * room.disconnect() частью корректного выхода — leave/pagehide/join
 * другой конференции той же вкладкой рвут медиа-подключение, остальные
 * участники видят выход (departure_timeout, livekit-integration §2).
 *
 * Room создаётся ЗАНОВО на каждый прогон эффекта подключения (паттерн
 * useLiveKitRoom @livekit/components-react): повторное использование
 * экземпляра после прерванного в полёте connect (StrictMode-перемонтаж
 * dev-сервера: cleanup рвёт подключение, повторный монтаж подключается
 * снова) оставляет Room в поломанном состоянии («skipping incoming
 * track after Room disconnected») — свежая комната на жизненный цикл
 * тикета надёжна и в dev, и в проде: смена ticket.token (реконнект,
 * FR-014) = новая комната, старая корректно разрывается cleanup'ом.
 *
 * Стартовая публикация — дефолты по mode конференции (№44: video →
 * камера+микрофон, audio — только микрофон, Q3=A soft); T034 (US2)
 * передаёт явное стартовое состояние из pre-join (оба выкл — «только
 * слушаю», FR-006) и стартовый выбор устройств (useDevices initial:
 * best-effort применение при появлении комнаты — ДО первой публикации,
 * поэтому микрофон/камера публикуются сразу выбранными на pre-join
 * устройствами; capture-дефолты комнаты настраиваются даже до connect,
 * смена комнаты при реконнекте применяет выбор заново). Отказ браузера
 * в доступе к устройствам не рушит
 * вход — участник остаётся слушателем с понятным предупреждением
 * (T032, Q6=A); повторный запрос доступа — уже в звонке тумблерами
 * MediaControls (T028): успешная публикация слота (LocalTrackPublished)
 * снимает свою часть предупреждения; ограничения права/источников уже
 * срезаны в grants токена сервером (FR-007) — публикация просто не
 * проходит. Своя демонстрация экрана публикуется тумблером
 * MediaControls (T028), удалённые рендерятся широкими тайлами над
 * сеткой камер.
 *
 * Реконнект (T060, FR-014, Q15=B): подключённому LiveKit продлевает
 * токен сам (livekit-integration §3); транзиентная потеря связи —
 * внутренние Reconnecting/Reconnected livekit-client (индикация
 * «переподключение…», медиа-слой остаётся). При ПОЛНОМ разрыве после
 * успешного подключения открывается окно reconnect-window (дефолт
 * 120 с, conferences.reconnect-window Q19): цепочка ретраев с малым
 * интервалом, каждая попытка — СВЕЖИЙ тикет повторным №47 (члены) /
 * №55 (гости, refreshTicket страницы, FR-014: TTL 900 с < 4 ч —
 * истёкший токен не годится, минт даёт новый connId); смена
 * ticket.token пересоздаёт комнату — звонок продолжается. Истечение
 * окна — контролируемое завершение с понятным сообщением («lost»);
 * серийные разрывы одной цепочки держат ОДНО окно от первой потери
 * до восстановления. Серверные разрывы (RemoveParticipant/удаление
 * комнаты/дубль identity/нет прав) — терминальное «Вы отключены»
 * без авто-возврата: кик ≠ бан, но повторный вход — осознанное
 * действие по действующей ссылке (edge spec.md).
 *
 * Предупреждение о лимите длительности (T060 ← T056, FR-015,
 * Q16=B): шедулер шлёт admin-SendData пакет topic
 * `conference.warning`, payload `{"type":"conference.warning",
 * "endsAt":…}` — ВСЕМ подключённым, включая гостей без SSE; приём
 * RoomEvent.DataReceived по теме → тост «конференция завершится по
 * лимиту длительности» (закрывается вручную — событие одно, до
 * контролируемого завершения остаётся warning-before-end).
 */
import {
  ParticipantTile,
  RoomAudioRenderer,
  RoomContext,
  VideoTrack,
  useTracks,
} from '@livekit/components-react'
import { DisconnectReason, Room, RoomEvent, Track } from 'livekit-client'
import type { DataPacket_Kind, RemoteParticipant } from 'livekit-client'
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { ConferenceMode, JoinTicket } from '../api/conferences'
import { bindConferenceDisconnect } from '../hooks/useConference'
import { useDevices } from '../hooks/useDevices'
import type { ConferenceDeviceSelection } from '../hooks/useDevices'
import '../conferences.css'

/** Фаза медиа-подключения комнаты — статус-строка и видимость тайлов. */
export type ConferenceRoomPhase =
  'connecting' | 'connected' | 'reconnecting' | 'failed' | 'lost' | 'closed'

/**
 * Окно реконнекта при полном разрыве (T060, FR-014, Q15=B): зеркало
 * серверного `conferences.reconnect-window` (дефолт 120 с, Q19) —
 * цепочка ретраев со свежими тикетами живёт не дольше окна от ПЕРВОЙ
 * потери связи; истечение — контролируемое завершение (lost).
 */
const DEFAULT_RECONNECT_WINDOW_MS = 120_000

/** Интервал между ретраями минта внутри окна реконнекта (T060). */
const DEFAULT_RECONNECT_RETRY_DELAY_MS = 2_000

/** Тема data-пакета предупреждения о лимите длительности (T056). */
const WARNING_TOPIC = 'conference.warning'

/** payload.type того же пакета (`{"type":"conference.warning","endsAt":…}`). */
const WARNING_TYPE = 'conference.warning'

/** Текст тоста предупреждения (FR-015, Q16=B). */
const WARNING_TOAST_TEXT = 'Конференция завершится по лимиту длительности'

/** Стартовое состояние публикации (pre-join T034; дефолт — по mode, Q3=A). */
export interface ConferenceRoomInitialMedia {
  readonly microphone: boolean
  readonly camera: boolean
}

/** Слоты стартовой публикации, недоступные из-за отказа браузера (T032, Q6=A). */
interface MediaDenied {
  readonly microphone: boolean
  readonly camera: boolean
}

const MEDIA_DENIED_NONE: MediaDenied = { microphone: false, camera: false }

export interface ConferenceRoomProps {
  /** Привязка disconnect к слоту вкладки (Q10=A) — useConference. */
  readonly conferenceId: string
  /** Действующий JoinTicket (№47): wsUrl + токен; смена токена переподключает (реконнект). */
  readonly ticket: JoinTicket
  /** Режим конференции (№45) — дефолты стартовой публикации, когда initialMedia не задан. */
  readonly mode: ConferenceMode
  /** Явное стартовое состояние из pre-join (T034); по умолчанию — дефолты mode. */
  readonly initialMedia?: ConferenceRoomInitialMedia
  /**
   * Стартовый выбор устройств pre-join (T034): применяется best-effort
   * при появлении комнаты (useDevices initial) — до первой публикации
   * треков; null/undefined — системные устройства по умолчанию.
   */
  readonly initialDevices?: ConferenceDeviceSelection | null
  /** Повторный минт №47/№55 при полном разрыве (useConference.refreshTicket, FR-014). */
  readonly refreshTicket: () => Promise<JoinTicket | null>
  /**
   * Окно реконнекта от первой потери связи до контролируемого
   * завершения (T060, conferences.reconnect-window Q19); дефолт —
   * {@link DEFAULT_RECONNECT_WINDOW_MS}; переопределяется тестами.
   */
  readonly reconnectWindowMs?: number
  /** Интервал ретраев минта внутри окна (T060); переопределяется тестами. */
  readonly reconnectRetryDelayMs?: number
  /** Слот для MediaControls (T028) и панелей US3/US4 — внутри RoomContext. */
  readonly children?: ReactNode
}

/**
 * Комната livekit-client с адаптацией качества без обрыва (FR-004):
 * simulcast (до трёх слоёв публикации — publishDefaults), dynacast
 * (издатель ставит на паузу слои, на которые никто не подписан) и
 * adaptiveStream (подписчик ставит на паузу невидимые тайлы) — при
 * деградации сети слой понижается, подключение сохраняется (quickstart
 * §3.1 шаг 4). Подписка на удалённые треки — autoSubscribe (дефолт).
 */
function createConferenceRoom(): Room {
  return new Room({
    dynacast: true,
    adaptiveStream: true,
    publishDefaults: { simulcast: true },
  })
}

/**
 * Серверные/неустранимые причины разрыва: авто-переподключение
 * бессмысленно или вредно — кик/завершение конференции не возвращают
 * (повторный вход — осознанный, по действующей ссылке, edge spec.md),
 * дубль identity и отсутствие прав — терминальные ошибки подключения.
 */
function isTerminalDisconnect(reason: DisconnectReason | undefined): boolean {
  switch (reason) {
    case DisconnectReason.PARTICIPANT_REMOVED:
    case DisconnectReason.ROOM_DELETED:
    case DisconnectReason.ROOM_CLOSED:
    case DisconnectReason.DUPLICATE_IDENTITY:
    case DisconnectReason.USER_REJECTED:
      return true
    default:
      return false
  }
}

/** Разрыв комнаты без выброса отклонённого промиса наружу (best-effort). */
function safeDisconnect(room: Room): void {
  room.disconnect().catch(() => {})
}

/**
 * Стартовая публикация: микрофон/камера по стартовому состоянию.
 * Отказ браузера в доступе (нет устройств/запрет) не рушит вход —
 * участник остаётся слушателем (FR-006; повторный запрос уже в звонке
 * — тумблеры T028, Q6=A): сбой включения помечается в возвращаемом
 * флаге слота → предупреждение; намеренно выключенный старт отказом
 * НЕ считается. Право слова/ограничения источников срезаны в grants
 * токена сервером (FR-007) — публикация молча не проходит.
 */
async function publishInitialTracks(
  room: Room,
  microphone: boolean,
  camera: boolean,
): Promise<MediaDenied> {
  const denied: { microphone: boolean; camera: boolean } = { microphone: false, camera: false }
  try {
    await room.localParticipant.setMicrophoneEnabled(microphone)
  } catch {
    denied.microphone = microphone
  }
  try {
    await room.localParticipant.setCameraEnabled(camera)
  } catch {
    denied.camera = camera
  }
  return denied
}

/** Отображаемое имя участника тайла: name тикета/метаданных, иначе identity. */
function participantLabel(name: string | undefined, identity: string): string {
  return name !== undefined && name.trim() !== '' ? name : identity
}

/** Статус-строка фазы подключения (нейтральные подписи без деталей). */
function phaseStatusText(phase: ConferenceRoomPhase): string | null {
  switch (phase) {
    case 'connecting':
      return 'Подключение к конференции…'
    case 'reconnecting':
      return 'Соединение потеряно — переподключение…'
    case 'failed':
      return 'Не удалось подключиться к медиа-серверу'
    case 'lost':
      return 'Не удалось восстановить связь с конференцией'
    case 'closed':
      return 'Вы отключены от конференции'
    default:
      return null
  }
}

/**
 * Предупреждение об отказе доступа к устройствам (T032, Q6=A): вход
 * сохранён («только слушаю», FR-006), повторный запрос — тумблерами
 * уже в звонке (T028); текст нейтральный, без раскрытия причин отказа.
 */
function mediaFailureNotice(denied: MediaDenied): string | null {
  if (denied.microphone && denied.camera) {
    return 'Нет доступа к камере и микрофону — можно включить их в звонке'
  }
  if (denied.microphone) {
    return 'Нет доступа к микрофону — можно включить его в звонке'
  }
  if (denied.camera) {
    return 'Нет доступа к камере — можно включить её в звонке'
  }
  return null
}

/**
 * Медиа-слой комнаты (внутри RoomContext): аудио всех удалённых
 * участников — RoomAudioRenderer (микрофоны и звук демонстраций
 * экрана), видео — тайлы: сетка камер/плейсхолдеров участников
 * (ParticipantTile: имя, индикатор речи, заглушка без камеры) и
 * широкие тайлы демонстраций экрана (VideoTrack). adaptiveStream
 * держит паузу невидимых тайлов (FR-004).
 */
function ConferenceMedia() {
  const cameraTiles = useTracks([{ source: Track.Source.Camera, withPlaceholder: true }], {
    onlySubscribed: false,
  })
  const screenTiles = useTracks([Track.Source.ScreenShare], { onlySubscribed: false })
  return (
    <>
      <RoomAudioRenderer />
      {screenTiles.length > 0 && (
        <section className="conference-screens" aria-label="Демонстрации экрана">
          {screenTiles.map((ref) => (
            <figure
              className="conference-tile conference-tile-screen"
              key={`${ref.participant.identity}:${ref.source}`}
            >
              <VideoTrack trackRef={ref} />
              <figcaption className="conference-tile-name">
                Экран — {participantLabel(ref.participant.name, ref.participant.identity)}
              </figcaption>
            </figure>
          ))}
        </section>
      )}
      <section className="conference-tiles" aria-label="Участники конференции">
        {cameraTiles.map((ref) => (
          <ParticipantTile
            key={`${ref.participant.identity}:${ref.source}`}
            trackRef={ref}
            className="conference-tile"
          />
        ))}
      </section>
    </>
  )
}

export default function ConferenceRoom({
  conferenceId,
  ticket,
  mode,
  initialMedia,
  initialDevices,
  refreshTicket,
  reconnectWindowMs = DEFAULT_RECONNECT_WINDOW_MS,
  reconnectRetryDelayMs = DEFAULT_RECONNECT_RETRY_DELAY_MS,
  children,
}: ConferenceRoomProps) {
  /** Комната текущего жизненного цикла тикета (создаётся эффектом, T027). */
  const [room, setRoom] = useState<Room | null>(null)
  const [phase, setPhase] = useState<ConferenceRoomPhase>('connecting')
  /** Слоты стартовой публикации, недоступные из-за отказа браузера (T032). */
  const [mediaDenied, setMediaDenied] = useState<MediaDenied>(MEDIA_DENIED_NONE)
  /** Тост conference.warning (T056 → T060): показан до закрытия/завершения. */
  const [warningVisible, setWarningVisible] = useState(false)
  /** Разрыв инициирован выходом (слот вкладки/размонтирование) — без авто-возврата. */
  const intentionalRef = useRef(false)
  /** Успешное подключение уже было — реконнект с новым тикетом допустим (FR-014). */
  const everConnectedRef = useRef(false)
  /**
   * Дедлайн окна реконнекта (T060): timestamp от ПЕРВОЙ полной потери
   * связи цепочки; null — окно закрыто (подключение восстановлено /
   * терминальный выход). Переживает смену комнаты (смена тикета) —
   * серийные разрывы держат одно окно, при восстановлении
   * сбрасывается и следующая потеря открывает заново.
   */
  const reconnectDeadlineRef = useRef<number | null>(null)
  /** Таймер ретрая минта внутри окна (гасится cleanup'ом прогона). */
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const refreshTicketRef = useRef(refreshTicket)
  refreshTicketRef.current = refreshTicket

  const startMicrophone = initialMedia?.microphone ?? true
  const startCamera = initialMedia?.camera ?? mode === 'video'

  // Устройства — единая точка useDevices (T031): стартовый выбор pre-join
  // (T034) применяется best-effort при появлении комнаты — switchActive-
  // Device до connect настраивает capture-дефолты, публикация стартует
  // выбранными устройствами; синхронизация выбора — ActiveDeviceChanged.
  useDevices({ room, initial: initialDevices ?? undefined })

  // Жизненный цикл тикета = жизненный цикл комнаты: СВЕЖАЯ Room на каждый
  // прогон эффекта (паттерн useLiveKitRoom — см. комментарий модуля),
  // слушатели + подключение + привязка разрыва к слоту вкладки (Q10=A):
  // leaveActiveConference (leave/pagehide/join другой конференции)
  // вызывает bound-disconnect — флаг intentional гасит авто-реконнект;
  // cleanup (смена тикета/размонтирование/StrictMode-перемонтаж) рвёт
  // подключение ЭТОЙ комнаты, повторный прогон строит свежую.
  useEffect(() => {
    intentionalRef.current = false
    let disposed = false
    /**
     * Разрыв этой комнаты уже запустил цепочку реконнекта (T060):
     * повторные Disconnected мёртвого экземпляра — дубликаты, новый
     * минт не инициируют (свежий тикет уже получен/запланирован).
     */
    let chainTriggered = false
    const active = createConferenceRoom()
    setMediaDenied(MEDIA_DENIED_NONE)

    /**
     * Окно истекло — контролируемое завершение (T060, Q15=B):
     * понятное сообщение, окно закрывается, ретраи прекращаются.
     */
    const expireReconnectWindow = (): void => {
      reconnectDeadlineRef.current = null
      if (retryTimerRef.current !== null) {
        clearTimeout(retryTimerRef.current)
        retryTimerRef.current = null
      }
      setPhase('lost')
    }
    /** Остаток окна: null — окно закрыто (не реконнект) или ещё активно. */
    const reconnectExpired = (): boolean => {
      const deadline = reconnectDeadlineRef.current
      return deadline !== null && Date.now() >= deadline
    }
    const scheduleReconnectRetry = (): void => {
      if (disposed || intentionalRef.current) {
        return
      }
      if (reconnectExpired()) {
        expireReconnectWindow()
        return
      }
      if (retryTimerRef.current !== null) {
        return
      }
      retryTimerRef.current = setTimeout(() => {
        retryTimerRef.current = null
        attemptReconnect()
      }, reconnectRetryDelayMs)
    }
    /**
     * Попытка реконнекта (T060, FR-014): свежий тикет повторным
     * №47/№55 — истёкший TTL не годится (900 с < 4 ч). Успех с НОВЫМ
     * токеном — эстафета родителю (смена ticket.token пересоздаёт
     * комнату, цепочка продолжится прогоном свежего тикета); null
     * (сбой/устаревание/конференция завершена) и прочие сбои —
     * ретрай, пока окно живо.
     */
    const attemptReconnect = (): void => {
      if (disposed || intentionalRef.current) {
        return
      }
      if (reconnectExpired()) {
        expireReconnectWindow()
        return
      }
      void refreshTicketRef
        .current()
        .then((fresh) => {
          if (disposed || intentionalRef.current) {
            return
          }
          if (fresh !== null && fresh.token !== ticket.token) {
            chainTriggered = true
            return
          }
          scheduleReconnectRetry()
        })
        .catch(() => {
          if (!disposed && !intentionalRef.current) {
            scheduleReconnectRetry()
          }
        })
    }

    const handleReconnecting = () => {
      if (!disposed) {
        setPhase('reconnecting')
      }
    }
    const handleReconnected = () => {
      if (!disposed) {
        // Транзиентный восстановился сам — окно не нужно (T060).
        reconnectDeadlineRef.current = null
        setPhase('connected')
      }
    }
    /**
     * Предупреждение о лимите длительности (T056 → T060, Q16=B):
     * data-пакет topic `conference.warning` (серверный SendData —
     * единственный канал гостям без SSE) → тост; чужие темы и битый
     * payload игнорируются нейтрально.
     */
    const handleDataReceived = (
      payload: Uint8Array,
      _participant?: RemoteParticipant,
      _kind?: DataPacket_Kind,
      topic?: string,
    ): void => {
      if (disposed || topic !== WARNING_TOPIC) {
        return
      }
      try {
        const parsed: unknown = JSON.parse(new TextDecoder().decode(payload))
        if (
          parsed !== null &&
          typeof parsed === 'object' &&
          (parsed as { type?: unknown }).type === WARNING_TYPE
        ) {
          setWarningVisible(true)
        }
      } catch {
        // битый payload — без реакции (нейтрально)
      }
    }
    /**
     * Повторный запрос доступа уже в звонке (T032, Q6=A): успешное
     * включение слота тумблером MediaControls (T028) публикует трек —
     * LocalTrackPublished снимает свою часть предупреждения об отказе.
     */
    const handleLocalTrackPublished = (publication: { source: Track.Source }) => {
      if (disposed) {
        return
      }
      if (publication.source === Track.Source.Microphone) {
        setMediaDenied((current) =>
          current.microphone ? { ...current, microphone: false } : current,
        )
      } else if (publication.source === Track.Source.Camera) {
        setMediaDenied((current) => (current.camera ? { ...current, camera: false } : current))
      }
    }
    const handleDisconnected = (reason?: DisconnectReason) => {
      if (disposed) {
        return
      }
      if (intentionalRef.current || reason === DisconnectReason.CLIENT_INITIATED) {
        return
      }
      if (isTerminalDisconnect(reason)) {
        reconnectDeadlineRef.current = null
        setPhase('closed')
        return
      }
      if (!everConnectedRef.current) {
        setPhase('failed')
        return
      }
      // Дубликаты разрыва той же мёртвой комнаты — уже в цепочке (T060).
      if (chainTriggered) {
        return
      }
      chainTriggered = true
      // Окно — от ПЕРВОЙ потери цепочки (серийные разрывы держат одно).
      reconnectDeadlineRef.current ??= Date.now() + reconnectWindowMs
      setPhase('reconnecting')
      attemptReconnect()
    }
    active.on(RoomEvent.Reconnecting, handleReconnecting)
    active.on(RoomEvent.Reconnected, handleReconnected)
    active.on(RoomEvent.Disconnected, handleDisconnected)
    active.on(RoomEvent.DataReceived, handleDataReceived)
    active.on(RoomEvent.LocalTrackPublished, handleLocalTrackPublished)
    bindConferenceDisconnect(conferenceId, () => {
      intentionalRef.current = true
      reconnectDeadlineRef.current = null
      safeDisconnect(active)
    })
    setRoom(active)
    setPhase('connecting')
    void (async () => {
      try {
        await active.connect(ticket.wsUrl, ticket.token)
        if (disposed) {
          return
        }
        everConnectedRef.current = true
        // Подключение восстановлено (в т.ч. цепочкой реконнекта) —
        // окно закрыто, следующая потеря откроет заново (T060).
        reconnectDeadlineRef.current = null
        setPhase('connected')
        const denied = await publishInitialTracks(active, startMicrophone, startCamera)
        if (!disposed) {
          setMediaDenied(denied)
        }
      } catch {
        if (disposed) {
          return
        }
        if (everConnectedRef.current && reconnectDeadlineRef.current !== null) {
          // Свежий тикет цепочки не подключился — ретрай до конца окна.
          setPhase('reconnecting')
          scheduleReconnectRetry()
          return
        }
        setPhase('failed')
      }
    })()
    return () => {
      disposed = true
      intentionalRef.current = true
      if (retryTimerRef.current !== null) {
        clearTimeout(retryTimerRef.current)
        retryTimerRef.current = null
      }
      active.removeAllListeners()
      safeDisconnect(active)
    }
  }, [
    conferenceId,
    ticket.wsUrl,
    ticket.token,
    startMicrophone,
    startCamera,
    reconnectWindowMs,
    reconnectRetryDelayMs,
  ])

  const statusText = phaseStatusText(phase)
  const mediaNotice = mediaFailureNotice(mediaDenied)

  // До создания комнаты (первый рендер до эффекта) — только статус-оболочка:
  // children (MediaControls и панели US3/US4) живут ВНУТРИ RoomContext —
  // хуки @livekit/components-react без комнаты нерендерибельны.
  if (room === null) {
    return (
      <div className="conference-room" data-conference-room={conferenceId}>
        {statusText !== null && <output className="conference-room-status">{statusText}</output>}
      </div>
    )
  }
  return (
    <RoomContext.Provider value={room}>
      <div className="conference-room" data-conference-room={conferenceId}>
        {statusText !== null && <output className="conference-room-status">{statusText}</output>}
        {mediaNotice !== null && <output className="conference-room-media">{mediaNotice}</output>}
        {warningVisible && (
          <output className="conference-warning-toast" data-conference-warning>
            <span>{WARNING_TOAST_TEXT}</span>
            <button type="button" onClick={() => setWarningVisible(false)}>
              Закрыть
            </button>
          </output>
        )}
        {(phase === 'connected' || phase === 'reconnecting') && <ConferenceMedia />}
        {children}
      </div>
    </RoomContext.Provider>
  )
}
