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
 * «Войти») отменяет подключение без последствий — возврат в чат
 * конференции (FR-019), слот не занимался, превью-трек уже освобождён
 * самим pre-join (US2-6). Стартовое состояние «Войти» (оба выкл —
 * «только слушаю», FR-006) и выбранные устройства передаются в
 * ConferenceRoom (initialMedia/initialDevices), «Повторить» после
 * ошибки join повторяет №47 с той же выборкой (тумблеры/устройства на
 * №47 не влияют).
 *
 * «Конференция завершена» (Q20=A) показывается по обоим REST-лицам:
 * №45 `200 status='ended'` (старая ссылка) и №47 `409
 * conference_ended` — терминальное состояние без pre-join и кнопки
 * входа. T071: терминальный экран дополняется длительностью
 * `endedAt − startedAt` загруженного №45-вида (оба поля присутствуют
 * для status='ended'; никогда не начинавшаяся — empty-timeout →
 * room_lost без startedAt — длительность не выводится, не выдумывать);
 * №47-409 без полей вида — подпись без длительности. T095 (FR-019):
 * под терминальной подписью — кнопка «Вернуться в чат» (deep-link
 * `/chat?chat=…`), автопереход НЕ выполняется — подпись и длительность
 * остаются видимыми (Q20=A, уточнения T071/T076). T112: карточка —
 * пластина корпуса machine с глифом-колоколом «звонок окончен»
 * (конференции.css, прототип tmp/conference-ended-participant.html);
 * разметка не меняется — глобальные element-правила index.css
 * (зелёная output-плашка, синяя кнопка) перекрываются классами.
 * Блок joined монтирует медиа-комнату ConferenceRoom (T027):
 * подключение по JoinTicket, тайлы участников и адаптацию качества
 * (FR-004); T106: в слоте sidebar — ПРАВАЯ КОЛОНКА рабочей области
 * (сворачивается/разворачивается кнопкой conference-panel-toggle):
 * ОБЪЕДИНЁННАЯ панель участников (T041/T107) — единый список строк
 * с кебабом действий модератора №49–№51 и футером инструментов
 * (гостевая ссылка №52/№53 + «Завершить конференцию» №48; кебаби и
 * футер — только роли модератора, скрываются broadcast'ом снятия
 * роли), а в слоте children — MediaControls (T028): тумблеры
 * микрофона/камеры/экрана и «Покинуть» — всегда внизу по центру
 * экрана; выход (leave слота вкладки, Q10=A) возвращает
 * в мессенджер с открытым чатом конференции (FR-019, T095: возврат на
 * pre-join отменён; путь модератора после №48 — комната 'closed' →
 * «Покинуть» → чат — сходится этой же кнопкой).
 * T087: шапка страницы (заголовок conferenceTitle, статический снимок
 * «Участники: N / M» не-joined фаз, строка «Ваша роль: …» №46-снимка)
 * удалена во ВСЕХ фазах, identity-строка joined-формы — тоже.
 * T092: output-подпись «Вы в конференции» joined-формы и живой счётчик
 * подключённых T080 (ConferenceLiveCount) демонтированы — живое число
 * подключённых остаётся в панели «Участники (N)» (T041,
 * useParticipants); leave обновляет пакет №45+№46 один раз, без
 * запрос-циклов.
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
import ConferenceRoom from '../components/ConferenceRoom'
import type { ConferenceRoomInitialMedia } from '../components/ConferenceRoom'
import MediaControls from '../components/MediaControls'
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
 * Адрес возврата в чат конференции (FR-019, T095): мессенджер с
 * открытым окном чата — deep-link `/chat?chat={chatId}` (chatId — из
 * уже загруженного №45-вида ConferenceView.chatId, обе точки кнопок
 * живут в ready-блоке — fallback не нужен; новые API не вводятся).
 * Разбирает адрес MessengerPage (выбор по ростеру №12, молчаливое
 * игнорирование недоступного чата — FR-003).
 */
function chatReturnUrl(chatId: string): string {
  return `/chat?chat=${encodeURIComponent(chatId)}`
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

/** Блок подключенной вкладки (T026/T027/T028 + объединённая панель участников T041/T107): медиа-комната + список участников с кебабом модератора + панель управления (тумблеры микрофона/камеры/экрана, «Покинуть»). */
function JoinedPanel({
  conferenceId,
  ticket,
  mode,
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
      <ConferenceRoom
        conferenceId={conferenceId}
        ticket={ticket}
        mode={mode}
        initialMedia={initialMedia}
        initialDevices={initialDevices}
        refreshTicket={refreshTicket}
        sidebar={<ParticipantsPanel conferenceId={conferenceId} />}
      >
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
   * Возврат в чат конференции (FR-019, T095): «Отмена» из pre-join,
   * «Покинуть» и кнопка терминального экрана ведут в мессенджер с
   * открытым окном чата (deep-link разбирает MessengerPage); чат
   * недоступен (исключён/удалён) — id молча игнорируется там по FR-003.
   */
  const returnToChat = useCallback((): void => {
    if (conference !== null) {
      window.location.assign(chatReturnUrl(conference.chatId))
    }
  }, [conference])

  /**
   * «Отмена» — выход из pre-join без «Войти» (US2-6, FR-007): подключение
   * отменяется без последствий — №47 не вызывался, слот не занимался,
   * превью-трек освобождён самим pre-join; возврат — в чат конференции
   * (FR-019).
   */
  const cancelPreJoin = returnToChat

  /**
   * «Покинуть» (FR-019, T095): leave() рвёт слот/медиа (фоновое
   * обновление №45+№46 остаётся best-effort и гаснет выгрузкой вкладки —
   * безвредно), затем навигация в мессенджер с открытым чатом
   * конференции; возврат на pre-join отменён (пересмотр клиентской
   * части Q10=A — семантика слота не меняется, повторный вход —
   * точками входа чата, FR-001).
   */
  const handleLeave = useCallback((): void => {
    leave()
    returnToChat()
  }, [leave, returnToChat])

  return (
    <section aria-label="Конференция" data-conference-id={conferenceId}>
      {status === 'loading' && (
        <output className="conference-loading-status">Загрузка конференции…</output>
      )}
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
            <div className="conference-ended">
              <output>{endedConferenceLabel(formatSpanDuration(conference))}</output>
              <button type="button" onClick={returnToChat}>
                Вернуться в чат
              </button>
            </div>
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
                    initialMedia={
                      selection !== null
                        ? { microphone: selection.microphone, camera: selection.camera }
                        : undefined
                    }
                    initialDevices={selection?.devices}
                    refreshTicket={refreshTicket}
                    restrictions={me}
                    onRestrictionsSync={refreshMe}
                    onLeave={handleLeave}
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
