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
 * Стартовая публикация — дефолты по mode конференции (№44: video →
 * камера+микрофон, audio → только микрофон, Q3=A soft); T034 (US2)
 * передаст явное стартовое состояние из pre-join (оба выкл — «только
 * слушаю», FR-006). Отказ браузера в доступе к устройствам не рушит
 * вход — участник остаётся слушателем (повторный запрос в звонке —
 * T028/T032); ограничения права/источников уже срезаны в grants токена
 * сервером (FR-007) — публикация просто не проходит. Своя демонстрация
 * экрана публикуется тумблером MediaControls (T028), удалённые
 * рендерятся широкими тайлами над сеткой камер.
 *
 * Реконнект (фундамент T060, FR-014): подключённому LiveKit продлевает
 * токен сам (livekit-integration §3); при ПОЛНОМ разрыве после
 * успешного подключения компонент единожды обновляет тикет повторным
 * №47 (refreshTicket useConference, свежий connId) и переподключается
 * — эффект connect срабатывает по смене ticket.token. Серверные
 * разрывы (RemoveParticipant/удаление комнаты/дубль identity/нет
 * прав) — терминальное «Вы отключены» без авто-возврата: кик ≠ бан,
 * но повторный вход — осознанное действие по действующей ссылке (edge
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
import { ConnectionState, DisconnectReason, Room, RoomEvent, Track } from 'livekit-client'
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
 * — T028/T032); право слова/ограничения источников срезаны в grants
 * токена сервером (FR-007) — публикация молча не проходит.
 */
async function publishInitialTracks(
  room: Room,
  microphone: boolean,
  camera: boolean,
): Promise<void> {
  try {
    await room.localParticipant.setMicrophoneEnabled(microphone)
  } catch {
    // микрофон недоступен — вход «только слушаю»
  }
  try {
    await room.localParticipant.setCameraEnabled(camera)
  } catch {
    // камера недоступна — аудио-участие сохраняется
  }
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
  const [room] = useState(createConferenceRoom)
  const [phase, setPhase] = useState<ConferenceRoomPhase>('connecting')
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

  // Слушатели комнаты + привязка разрыва к слоту вкладки (Q10=A):
  // leaveActiveConference (leave/pagehide/join другой конференции)
  // вызывает bound-disconnect — флаг intentional гасит авто-реконнект.
  // StrictMode-перемонтаж сбрасывает флаг: «ложный» cleanup-разрыв
  // не должен блокировать подключение повторного монтажа.
  useEffect(() => {
    intentionalRef.current = false
    const handleReconnecting = () => setPhase('reconnecting')
    const handleReconnected = () => setPhase('connected')
    const handleDisconnected = (reason?: DisconnectReason) => {
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
      // Свежий тикет меняет prop → эффект connect переподключает; null
      // (сбой/устаревание/конференция завершена) — контролируемое
      // «не удалось» (полное окно и ретраи — T060).
      void refreshTicketRef
        .current()
        .then((fresh) => {
          if (fresh === null && !intentionalRef.current) {
            setPhase('failed')
          }
        })
        .catch(() => {
          if (!intentionalRef.current) {
            setPhase('failed')
          }
        })
    }
    room.on(RoomEvent.Reconnecting, handleReconnecting)
    room.on(RoomEvent.Reconnected, handleReconnected)
    room.on(RoomEvent.Disconnected, handleDisconnected)
    bindConferenceDisconnect(conferenceId, () => {
      intentionalRef.current = true
      safeDisconnect(room)
    })
    return () => {
      intentionalRef.current = true
      room.removeAllListeners()
      safeDisconnect(room)
    }
  }, [room, conferenceId])

  // Подключение по тикету: смена ticket.token (реконнект после
  // разрыва) переподключает ту же комнату; уже подключённым повторный
  // вызов не нужен (токен подключённому продлевает LiveKit, §3).
  useEffect(() => {
    if (room.state === ConnectionState.Connected) {
      setPhase('connected')
      return
    }
    let cancelled = false
    setPhase('connecting')
    void (async () => {
      try {
        await room.connect(ticket.wsUrl, ticket.token)
        if (cancelled) {
          return
        }
        everConnectedRef.current = true
        reconnectSpentRef.current = false
        setPhase('connected')
        await publishInitialTracks(room, startMicrophone, startCamera)
      } catch {
        if (!cancelled) {
          setPhase('failed')
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [room, ticket.wsUrl, ticket.token, startMicrophone, startCamera])

  const statusText = phaseStatusText(phase)

  return (
    <RoomContext.Provider value={room}>
      <div className="conference-room" data-conference-room={conferenceId}>
        {statusText !== null && (
          <p className="conference-room-status" role="status">
            {statusText}
          </p>
        )}
        {(phase === 'connected' || phase === 'reconnecting') && <ConferenceMedia />}
        {children}
      </div>
    </RoomContext.Provider>
  )
}
