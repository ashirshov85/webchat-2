/**
 * Карточка-приглашение конференции (009, US1, T024; FR-002, api-contract
 * §4): рендер системного сообщения `kind='system'` с
 * `payload={"type":"conference_invite","conferenceId":uuid,
 * "mode":"video"|"audio"}` в ленте сообщений — панель машины с заголовком
 * режима («Видеоконференция»/«Аудиоконференция») и кнопкой
 * «Подключиться» (переход по ссылке-приглашению). Сама лента
 * (MessageList) выбирает карточку по type guard
 * `isConferenceInvitePayload`; неизвестные клиентам `payload.type`
 * рендерятся НЕЙТРАЛЬНЫМ плейсхолдером (OCP, конституция IV) — новые
 * типы виджетов добавляются аддитивно, этот компонент про них не знает.
 *
 * Клик «Подключиться» (№45 при клике, api-contract §2): сначала
 * `GET /conferences/{id}` — `status='ended'` оставляет на карточке
 * подпись «Конференция завершена» (ответ по старой ссылке, Q20=A;
 * перехода нет), живая конференция — переход
 * `/conference/{view.id}?mode={view.mode}` тем же механизмом
 * `window.location.assign`, что и кнопки звонка заголовка (T023):
 * mode — из ОТВЕТА №45, а не из payload карточки — существующая
 * конференция создаётся с тем режимом, что зафиксирован на сервере, а
 * дефолты pre-join (T034) следуют режиму конференции. Отказ
 * `conference_not_found` (не-члену и для несуществующей — единый
 * ответ, существование не раскрывается) — нейтральная подпись
 * «Конференция недоступна» без деталей; транспортный сбой —
 * «Не удалось подключиться», кнопка остаётся (повторный клик —
 * повторная проверка). Проверка идёт ДО перехода: маршрут
 * `/conference/:id` (T025) и pre-join (T030) достраиваются позже, но
 * контракт клика («переход по ссылке, статус проверяется №45») уже
 * закреплён api-contract §2 №45.
 */
import { useState } from 'react'
import { conferenceErrorCode, getConference } from '../../conferences/api/conferences'
import type { ConferenceMode } from '../../conferences/api/conferences'
import { formatTime } from '../../ui/time'

/** Состояние клика «Подключиться»: проверка №45 → переход/подпись. */
type JoinState = 'idle' | 'checking' | 'ended' | 'missing' | 'error'

/** Первый системный виджет 009 — payload сообщения-приглашения (§4). */
export interface ConferenceInvitePayload {
  readonly type: 'conference_invite'
  readonly conferenceId: string
  readonly mode: ConferenceMode
}

/**
 * Runtime type guard payload'а системного сообщения (генерация
 * schema.d.ts даёт free-form object): карточка рендерится ТОЛЬКО при
 * полном совпадении формы `conference_invite` — поля неверного типа
 * или лишний/неизвестный `type` трактуются как неизвестный виджет и
 * уходят в нейтральный плейсхолдер MessageList (OCP).
 */
// eslint-disable-next-line react-refresh/only-export-components -- type guard неотделим от карточки: определяет форму её собственного payload (MessageList — только диспетчер выбора карточка/плейсхолдер, OCP)
export function isConferenceInvitePayload(value: unknown): value is ConferenceInvitePayload {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const payload = value as Record<string, unknown>
  return (
    payload['type'] === 'conference_invite' &&
    typeof payload['conferenceId'] === 'string' &&
    (payload['mode'] === 'video' || payload['mode'] === 'audio')
  )
}

/** Заголовок карточки по режиму конференции (стартовые дефолты №44). */
function inviteTitle(mode: ConferenceMode): string {
  return mode === 'video' ? 'Видеоконференция' : 'Аудиоконференция'
}

export interface ConferenceInviteCardProps {
  /** `payload.conferenceId` — цель проверки №45 и перехода. */
  readonly conferenceId: string
  /** `payload.mode` — заголовок карточки (переход — по mode из №45). */
  readonly mode: ConferenceMode
  /** Время системного сообщения — ножка карточки ЧЧ:ММ (FR-019). */
  readonly createdAt: string
}

export function ConferenceInviteCard({ conferenceId, mode, createdAt }: ConferenceInviteCardProps) {
  const [state, setState] = useState<JoinState>('idle')

  const handleJoin = () => {
    if (state === 'checking' || state === 'ended' || state === 'missing') {
      return
    }
    setState('checking')
    void (async () => {
      try {
        const view = await getConference(conferenceId)
        if (view.status === 'ended') {
          setState('ended')
          return
        }
        window.location.assign(`/conference/${view.id}?mode=${view.mode}`)
      } catch (cause) {
        setState(conferenceErrorCode(cause) === 'conference_not_found' ? 'missing' : 'error')
      }
    })()
  }

  return (
    <div className="sys-card conference-invite">
      <div className="sys-title">{inviteTitle(mode)}</div>
      <div className="sys-row">
        {state === 'ended' || state === 'missing' ? (
          <span className="sys-status" role="status">
            {state === 'ended' ? 'Конференция завершена' : 'Конференция недоступна'}
          </span>
        ) : (
          <>
            {state === 'error' && (
              <span className="sys-status sys-status-error" role="alert">
                Не удалось подключиться
              </span>
            )}
            <button
              type="button"
              className="sys-join"
              onClick={handleJoin}
              disabled={state === 'checking'}
              aria-busy={state === 'checking'}
            >
              {state === 'checking' ? 'Подключение…' : 'Подключиться'}
            </button>
          </>
        )}
      </div>
      <div className="b-time">{formatTime(createdAt)}</div>
    </div>
  )
}
