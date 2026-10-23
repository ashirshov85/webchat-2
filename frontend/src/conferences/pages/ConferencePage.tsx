/**
 * Экран конференции (009, US1, T026 + US2, T034) — цель ленивого чанка
 * маршрута `/conference/:id` (T025): тяжёлый `livekit-client`/
 * `@livekit/components-react` попадает ТОЛЬКО сюда и не раздувает
 * основной бандл (research §10, plan.md Structure Decision). Хук
 * useConference грузит пакет №45+№46, join №47 → JoinTicket и держит
 * слот «одна конференция на вкладку» (Q10=A).
 *
 * Pre-join перед подключением (T034, FR-005–FR-007) — единый для ВСЕХ
 * точек входа: дефолты тумблеров задаёт mode точки входа (`?mode=`
 * маршрута от кнопок заголовка T023/карточки T024 — навигация полный
 * page load, query фиксируется на монтаж; прямая ссылка без mode —
 * дефолты по №45 view.mode), ограничения прав — №46 MeView (T033).
 * До «Войти» НИЧЕГО не публикуется и слот квоты не занимается (FR-007):
 * №47 вызывается только из onJoin; «Отмена» (выход из pre-join без
 * «Войти») отменяет подключение без последствий — возврат в мессенджер,
 * слот не занимался, превью-трек уже освобождён самим pre-join
 * (US2-6). Стартовое состояние «Войти» (оба выкл — «только слушаю»,
 * FR-006) и выбранные устройства передаются в ConferenceRoom
 * (initialMedia/initialDevices), «Повторить» после ошибки join
 * повторяет №47 с той же выборкой (тумблеры/устройства на №47 не
 * влияют).
 *
 * «Конференция завершена» (Q20=A) показывается по обоим REST-лицам:
 * №45 `200 status='ended'` (старая ссылка) и №47 `409
 * conference_ended` — терминальное состояние без pre-join и кнопки
 * входа. T071: терминальный экран дополняется длительностью
 * `endedAt − startedAt` загруженного №45-вида (оба поля присутствуют
 * для status='ended'; никогда не начинавшаяся — empty-timeout →
 * room_lost без startedAt — длительность не выводится, не выдумывать);
 * №47-409 без полей вида — подпись без длительности.
 * Блок joined монтирует медиа-комнату ConferenceRoom (T027):
 * подключение по JoinTicket, тайлы участников и адаптацию качества
 * (FR-004); в слоте children — ParticipantsPanel (T041): список
 * участников из LiveKit (identity/name, роль, online, ограничения),
 * ModerationPanel (T042): панель действий модератора №48–№51
 * (рендерится только роли модератора — сама скрывается broadcast'ом
 * снятия роли), и MediaControls (T028): тумблеры микрофона/камеры/
 * экрана и «Покинуть» (выход — leave слота вкладки,
 * Q10=A, возврат на pre-join для возможного повторного входа).
 * T080: в звонке ЖИВОЕ число подключённых держит ConferenceLiveCount
 * в слоте children комнаты (useParticipants — источник T041, совпадает
 * с «Участники (N)» панели); leave обновляет пакет №45+№46 один раз,
 * без запрос-циклов. T087: шапка страницы (заголовок conferenceTitle,
 * статический снимок «Участники: N / M» не-joined фаз, строка «Ваша
 * роль: …» №46-снимка) удалена во ВСЕХ фазах, identity-строка
 * joined-формы — тоже: остаётся только output-подпись «Вы в
 * конференции».
 * Сообщения об ошибках — нейтральные, без раскрытия деталей
 * (существование не раскрывается, api-contract §1): ветвление UX по
 * коду Problem, а не HTTP-статусу (конвенция 002).
 */
import { useCallback, useState } from 'react'
import { conferenceErrorCode } from '../api/conferences'
import type { ConferenceErrorCode, ConferenceMode, JoinTicket, MeView } from '../api/conferences'
import { useConference } from '../hooks/useConference'
import { useParticipantProfiles } from '../hooks/useParticipantProfiles'
import { ParticipantProfilesContext } from '../profiles'
import type { ConferenceDeviceSelection } from '../hooks/useDevices'
import { endedConferenceLabel, formatSpanDuration } from '../../ui/duration'
import ConferenceLiveCount from '../components/ConferenceLiveCount'
import ConferenceRoom from '../components/ConferenceRoom'
import type { ConferenceRoomInitialMedia } from '../components/ConferenceRoom'
import MediaControls from '../components/MediaControls'
import ModerationPanel from '../components/ModerationPanel'
import ParticipantsPanel from '../components/ParticipantsPanel'
import PreJoinScreen from '../components/PreJoinScreen'
import type { PreJoinSelection } from '../components/PreJoinScreen'

/** Props экрана конференции: id из маршрута /conference/:id. */
export interface ConferencePageProps {
  readonly conferenceId: string
}

/**
 * mode точки входа из `?mode=` маршрута (T034): кнопки «видеозвонок»/
 * «аудиозвонок» заголовка (T023) и карточка-приглашение (T024) идут на
 * `/conference/{id}?mode=…` полным page load — query читается один раз
 * на монтаж; неверное значение/отсутствие (прямая ссылка) → null —
 * дефолты pre-join берутся из №45 conference.mode.
 */
function entryModeFromSearch(search: string): ConferenceMode | null {
  const mode = new URLSearchParams(search).get('mode')
  return mode === 'video' || mode === 'audio' ? mode : null
}

/**
 * Нейтральное сообщение об ошибке №47 по коду Problem: детали не
 * раскрываются, коды контракта → фиксированные подписи.
 */
function joinErrorMessage(code: ConferenceErrorCode | null): string {
  switch (code) {
    case 'conference_ended':
      return 'Конференция завершена'
    case 'conference_forbidden':
      return 'Нет доступа к конференции'
    case 'conference_not_found':
      return 'Конференция недоступна'
    case 'conference_full':
      return 'Конференция заполнена'
    case 'conference_media_unavailable':
      return 'Медиа-сервер недоступен'
    default:
      return 'Не удалось подключиться'
  }
}

/** Нейтральное сообщение об ошибке загрузки №45/№46 (404 — не раскрывается). */
function loadErrorMessage(code: ConferenceErrorCode | null): string {
  return code === 'conference_not_found'
    ? 'Конференция недоступна'
    : 'Не удалось загрузить конференцию'
}

/** Блок подключенной вкладки (T026/T027/T028 + панель участников T041 + живой счётчик T080): медиа-комната + живое число подключённых + список участников + панель управления (тумблеры микрофона/камеры/экрана, «Покинуть»). */
function JoinedPanel({
  conferenceId,
  ticket,
  mode,
  maxParticipants,
  initialMedia,
  initialDevices,
  refreshTicket,
  restrictions,
  onRestrictionsSync,
  onLeave,
}: {
  readonly conferenceId: string
  readonly ticket: JoinTicket
  readonly mode: ConferenceMode
  /**
   * Потолок квоты №45-вида (T080): проброс в живой счётчик —
   * ConferenceLiveCount в слоте children комнаты (внутри RoomContext —
   * хуку нужна живая комната), новый запрос не вводится.
   */
  readonly maxParticipants: number
  /** Стартовое состояние pre-join (T034): оба выкл — «только слушаю» (FR-006). */
  readonly initialMedia?: ConferenceRoomInitialMedia
  /** Стартовый выбор устройств pre-join (T034) — применение в useDevices комнаты. */
  readonly initialDevices?: ConferenceDeviceSelection | null
  readonly refreshTicket: () => Promise<JoinTicket | null>
  /**
   * Собственные ограничения №46 MeView (T043): снимок для блокировки
   * тумблеров MediaControls (live-гранты — сама комната); null —
   * ограничений нет.
   */
  readonly restrictions?: MeView | null
  /** Повторный №46 по permission-событию LiveKit (T043 — useConference.refreshMe). */
  readonly onRestrictionsSync: () => void
  readonly onLeave: () => void
}) {
  return (
    <div className="conference-joined">
      <output>Вы в конференции</output>
      <ConferenceRoom
        conferenceId={conferenceId}
        ticket={ticket}
        mode={mode}
        initialMedia={initialMedia}
        initialDevices={initialDevices}
        refreshTicket={refreshTicket}
      >
        <ConferenceLiveCount maxParticipants={maxParticipants} />
        <ParticipantsPanel />
        <ModerationPanel conferenceId={conferenceId} />
        <MediaControls
          restrictions={restrictions}
          onRestrictionsSync={onRestrictionsSync}
          onLeave={onLeave}
        />
      </ConferenceRoom>
    </div>
  )
}

export default function ConferencePage({ conferenceId }: ConferencePageProps) {
  const {
    conference,
    me,
    status,
    error,
    reload,
    refreshMe,
    joinStatus,
    joinError,
    ticket,
    ended,
    join,
    refreshTicket,
    leave,
  } = useConference(conferenceId)

  /**
   * Профили участников чата-владельца (T073): догрузка СУЩЕСТВУЮЩИМИ
   * API по chatId №45-вида (группа — №27-ростер одним запросом,
   * direct — №13 peer + №10 me; сбой — пустая Map → фолбэк инициалов
   * из name). Кэш на монтаж, раздача вниз контекстом — аватары тайлов
   * и строк панели «как в контактах».
   */
  const participantProfiles = useParticipantProfiles(conference !== null ? conference.chatId : null)

  /** mode точки входа (?mode= маршрута — T034): фиксируется на монтаж (page load навигация). */
  const [entryMode] = useState(() => entryModeFromSearch(window.location.search))
  /** Стартовая выборка pre-join по «Войти» (T034): тумблеры + устройства — в звонок. */
  const [selection, setSelection] = useState<PreJoinSelection | null>(null)

  /** «Войти» (FR-007: только теперь занимается слот и минтится №47): выборка едет в звонок. */
  const handlePreJoin = useCallback(
    (picked: PreJoinSelection): void => {
      setSelection(picked)
      join()
    },
    [join],
  )

  /**
   * «Отмена» — выход из pre-join без «Войти» (US2-6, FR-007): подключение
   * отменяется без последствий — №47 не вызывался, слот не занимался,
   * превью-трек освобождён самим pre-join; возврат в мессенджер.
   */
  const cancelPreJoin = useCallback((): void => {
    window.location.assign('/')
  }, [])

  return (
    <section aria-label="Конференция" data-conference-id={conferenceId}>
      {status === 'loading' && <output>Загрузка конференции…</output>}
      {status === 'error' && (
        <div className="conference-error">
          <p role="alert">{loadErrorMessage(conferenceErrorCode(error))}</p>
          <button type="button" onClick={reload}>
            Повторить
          </button>
        </div>
      )}
      {status === 'ready' && conference !== null && (
        <>
          {ended ? (
            <output>{endedConferenceLabel(formatSpanDuration(conference))}</output>
          ) : (
            <div className="conference-join">
              {(joinStatus === 'idle' || joinStatus === 'joining') && (
                <PreJoinScreen
                  mode={entryMode ?? conference.mode}
                  joining={joinStatus === 'joining'}
                  restrictions={me}
                  onJoin={handlePreJoin}
                  onCancel={cancelPreJoin}
                />
              )}
              {joinStatus === 'joined' && ticket !== null && (
                <ParticipantProfilesContext.Provider value={participantProfiles}>
                  <JoinedPanel
                    conferenceId={conferenceId}
                    ticket={ticket}
                    mode={conference.mode}
                    maxParticipants={conference.maxParticipants}
                    initialMedia={
                      selection !== null
                        ? { microphone: selection.microphone, camera: selection.camera }
                        : undefined
                    }
                    initialDevices={selection?.devices}
                    refreshTicket={refreshTicket}
                    restrictions={me}
                    onRestrictionsSync={refreshMe}
                    onLeave={leave}
                  />
                </ParticipantProfilesContext.Provider>
              )}
              {joinStatus === 'error' && (
                <div className="conference-error">
                  <p role="alert">{joinErrorMessage(conferenceErrorCode(joinError))}</p>
                  <button type="button" onClick={join}>
                    Повторить
                  </button>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </section>
  )
}
