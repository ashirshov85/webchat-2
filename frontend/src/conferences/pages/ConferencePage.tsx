/**
 * Экран конференции (009, US1, T026) — цель ленивого чанка маршрута
 * `/conference/:id` (T025): тяжёлый `livekit-client`/
 * `@livekit/components-react` попадает ТОЛЬКО сюда и не раздувает
 * основной бандл (research §10, plan.md Structure Decision). Хук
 * useConference грузит пакет №45+№46, join №47 → JoinTicket и держит
 * слот «одна конференция на вкладку» (Q10=A).
 *
 * «Конференция завершена» (Q20=A) показывается по обоим REST-лицам:
 * №45 `200 status='ended'` (старая ссылка) и №47 `409
 * conference_ended` — терминальное состояние без кнопки подключения.
 * Кнопка «Подключиться» — до pre-join (T034 встраивает PreJoinScreen
 * перед №47 для всех точек входа: слот квоты не занимается до
 * «Войти», FR-007); e2e T029 на фазе US1 меряет SC-001 от неё.
 * Блок joined — точка монтирования ConferenceRoom (T027): пока медиа
 * нет, экран показывает факт подключения (identity тикета) и выход.
 * Сообщения об ошибках — нейтральные, без раскрытия деталей
 * (существование не раскрывается, api-contract §1): ветвление UX по
 * коду Problem, а не HTTP-статусу (конвенция 002).
 */
import { conferenceErrorCode } from '../api/conferences'
import type { ConferenceErrorCode, ConferenceMode, JoinTicket } from '../api/conferences'
import { useConference } from '../hooks/useConference'

/** Props экрана конференции: id из маршрута /conference/:id. */
export interface ConferencePageProps {
  readonly conferenceId: string
}

/** Заголовок экрана по режиму конференции (№45 view.mode — фиксация сервера). */
function conferenceTitle(mode: ConferenceMode): string {
  return mode === 'video' ? 'Видеоконференция' : 'Аудиоконференция'
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

/** Блок подключенной вкладки (T026): точка монтирования ConferenceRoom (T027). */
function JoinedPanel({
  ticket,
  onLeave,
}: {
  readonly ticket: JoinTicket
  readonly onLeave: () => void
}) {
  return (
    <div className="conference-joined">
      <p role="status">Вы в конференции</p>
      <p className="conference-identity">{ticket.identity}</p>
      <button type="button" onClick={onLeave}>
        Покинуть
      </button>
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
    joinStatus,
    joinError,
    ticket,
    ended,
    join,
    leave,
  } = useConference(conferenceId)

  return (
    <section aria-label="Конференция" data-conference-id={conferenceId}>
      {status === 'loading' && <p role="status">Загрузка конференции…</p>}
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
          <header className="conference-header">
            <h1>{conferenceTitle(conference.mode)}</h1>
            <p>
              Участники: {conference.activeParticipants} / {conference.maxParticipants}
            </p>
            {me !== null && <p>Ваша роль: {me.role === 'moderator' ? 'модератор' : 'участник'}</p>}
          </header>
          {ended ? (
            <p role="status">Конференция завершена</p>
          ) : (
            <div className="conference-join">
              {joinStatus === 'idle' && (
                <button type="button" onClick={join}>
                  Подключиться
                </button>
              )}
              {joinStatus === 'joining' && (
                <button type="button" disabled aria-busy="true">
                  Подключение…
                </button>
              )}
              {joinStatus === 'joined' && ticket !== null && (
                <JoinedPanel ticket={ticket} onLeave={leave} />
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
