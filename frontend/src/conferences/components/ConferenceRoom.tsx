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
 * передаст явное стартовое состояние из pre-join (оба выкл — «только
 * слушаю», FR-006). Отказ браузера в доступе к устройствам не рушит
 * вход — участник остаётся слушателем с понятным предупреждением
 * (T032, Q6=A); повторный запрос доступа — уже в звонке тумблерами
 * MediaControls (T028): успешная публикация слота (LocalTrackPublished)
 * снимает свою часть предупреждения; ограничения права/источников уже
 * срезаны в grants токена сервером (FR-007) — публикация просто не
 * проходит. Своя демонстрация экрана публикуется тумблером
 * MediaControls (T028), удалённые рендерятся широкими тайлами над
 * сеткой камер.
 *
 * Реконнект (фундамент T060, FR-014): подключённому LiveKit продлевает
 * токен сам (livekit-integration §3); при ПОЛНОМ разрыве после
 * успешного подключения компонент единожды обновляет тикет повторным
 * №47 (refreshTicket useConference, свежий connId) и переподключается
 * — смена ticket.token пересоздаёт комнату. Серверные разрывы
 * (RemoveParticipant/удаление комнаты/дубль identity/нет прав) —
 * терминальное «Вы отключены» без авто-возврата: кик ≠ бан, но
 * повторный вход — осознанное действие по действующей ссылке (edge
 * spec.md). Полное окно reconnect-window с индикацией и ретраями —
 * T060 (US5).
 */
import {
  ParticipantTile,
  RoomAudioRenderer,
  RoomContext,
  VideoTrack,
  useTracks,
} from '@livekit/components-react'
import { DisconnectReason, Room, RoomEvent, Track } from 'livekit-client'
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { ConferenceMode, JoinTicket } from '../api/conferences'
import { bindConferenceDisconnect } from '../hooks/useConference'
import '../conferences.css'

/** Фаза медиа-подключения комнаты — статус-строка и видимость тайлов. */
export type ConferenceRoomPhase = 'connecting' | 'connected' | 'reconnecting' | 'failed' | 'closed'

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
  /** Повторный минт №47 при полном разрыве (useConference.refreshTicket, FR-014). */
  readonly refreshTicket: () => Promise<JoinTicket | null>
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
  refreshTicket,
  children,
}: ConferenceRoomProps) {
  /** Комната текущего жизненного цикла тикета (создаётся эффектом, T027). */
  const [room, setRoom] = useState<Room | null>(null)
  const [phase, setPhase] = useState<ConferenceRoomPhase>('connecting')
  /** Слоты стартовой публикации, недоступные из-за отказа браузера (T032). */
  const [mediaDenied, setMediaDenied] = useState<MediaDenied>(MEDIA_DENIED_NONE)
  /** Разрыв инициирован выходом (слот вкладки/размонтирование) — без авто-возврата. */
  const intentionalRef = useRef(false)
  /** Успешное подключение уже было — реконнект с новым тикетом допустим (FR-014). */
  const everConnectedRef = useRef(false)
  /** Одна попытка обновления тикета на цепочку разрывов (окно/ретраи — T060). */
  const reconnectSpentRef = useRef(false)
  const refreshTicketRef = useRef(refreshTicket)
  refreshTicketRef.current = refreshTicket

  const startMicrophone = initialMedia?.microphone ?? true
  const startCamera = initialMedia?.camera ?? mode === 'video'

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
    const active = createConferenceRoom()
    setMediaDenied(MEDIA_DENIED_NONE)

    const handleReconnecting = () => {
      if (!disposed) {
        setPhase('reconnecting')
      }
    }
    const handleReconnected = () => {
      if (!disposed) {
        setPhase('connected')
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
        setPhase('closed')
        return
      }
      if (!everConnectedRef.current || reconnectSpentRef.current) {
        setPhase('failed')
        return
      }
      reconnectSpentRef.current = true
      setPhase('reconnecting')
      // Свежий тикет пересоздаёт комнату (смена ticket.token → эффект);
      // null (сбой/устаревание/конференция завершена) — контролируемое
      // «не удалось» (полное окно и ретраи — T060).
      void refreshTicketRef
        .current()
        .then((fresh) => {
          if (fresh === null && !disposed && !intentionalRef.current) {
            setPhase('failed')
          }
        })
        .catch(() => {
          if (!disposed && !intentionalRef.current) {
            setPhase('failed')
          }
        })
    }
    active.on(RoomEvent.Reconnecting, handleReconnecting)
    active.on(RoomEvent.Reconnected, handleReconnected)
    active.on(RoomEvent.Disconnected, handleDisconnected)
    active.on(RoomEvent.LocalTrackPublished, handleLocalTrackPublished)
    bindConferenceDisconnect(conferenceId, () => {
      intentionalRef.current = true
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
        reconnectSpentRef.current = false
        setPhase('connected')
        const denied = await publishInitialTracks(active, startMicrophone, startCamera)
        if (!disposed) {
          setMediaDenied(denied)
        }
      } catch {
        if (!disposed) {
          setPhase('failed')
        }
      }
    })()
    return () => {
      disposed = true
      intentionalRef.current = true
      active.removeAllListeners()
      safeDisconnect(active)
    }
  }, [conferenceId, ticket.wsUrl, ticket.token, startMicrophone, startCamera])

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
        {(phase === 'connected' || phase === 'reconnecting') && <ConferenceMedia />}
        {children}
      </div>
    </RoomContext.Provider>
  )
}
