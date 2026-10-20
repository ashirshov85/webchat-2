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
 *
 * T069 (US5, баг-фикс проводки T024×T059): ЖИВАЯ проекция завершения —
 * пропс `ended` (tombstone `endedByChat` live-среза useConferenceRealtime
 * №18, пробрасывается MessengerPage → MessageList → SystemRow по паре
 * «chatId ленты + conferenceId карточки»): `true` — терминальная подпись
 * «Конференция завершена» дословно клик-ветки `state='ended'`, БЕЗ
 * кнопки и БЕЗ запроса №45 — кадр №18 мессенджеру уже доставлен.
 * Терминальное состояние ЛИПКОЕ: узнав «завершено», карточка не
 * откатывается (render-phase latch, паттерн Tick) — tombstone хранит
 * только ПОСЛЕДНЮЮ завершённую конференцию чата, и когда ended B
 * вытесняет A из среза (prop уходит в false), карточка A остаётся
 * завершённой; функция статуса — внепорядковый `started` завершённой
 * конференции бейдж не поднимает и здесь (конституция III). Конференции,
 * завершённые ВНЕ живой сессии (до монтажа useConferenceRealtime), этим
 * путём не узнаются — их страховка остаётся клик-проверка №45; история
 * чата не дополняется и не меняется (Q20=A).
 *
 * T070 (US5, баг-фикс после T069): терминальное состояние конференций,
 * завершённых ВНЕ живой сессии (пользователь был офлайн в момент
 * завершения / открыл чат позже — кадр №18 не доставлен). Пропс
 * `probe` (MessageList помечает им НОВЕЙШУЮ карточку-приглашение окна
 * ленты): `true` — ОДИН фоновый №45-чек на монтаж: `status='ended'` →
 * терминальная подпись (как клик-ветка); `created`/`active` → кнопка
 * остаётся (живая сходится кадрами T069); Problem
 * `conference_not_found` → нейтральная «Конференция недоступна»;
 * транспортный сбой — тихо (без баннера; кнопка остаётся, клик-проверка
 * №45 — страховка), ретрай-цикл НЕ вводится. Мемоизация — сессионный
 * кэш conferenceInviteProbe: не более одного №45 на конференцию за
 * сессию; повторный монтаж восстанавливает осевший исход из кэша
 * (ленивый initial state) БЕЗ запроса. Уже-терминальная живым кадром
 * карточка (ended на монтаже) не проверяется вовсе.
 *
 * T071 (US5, длительность — частичный пересмотр Q20=A): терминальная
 * подпись дополняется «· {N мин}» ТОЛЬКО там, где данные уже в руках
 * клиента: (1) клик-проверка №45 и фоновый чек T070 — view несёт
 * startedAt/endedAt (раньше клик-ветка его отбрасывала; span живёт
 * в сессионном кэше чека и состоянии карточки); (2) живая сессия
 * T069 — пропс `endedSpan` (endedAt кадра №18 + startedAt бейджа
 * T059 из tombstone live-среза). Деривация T070 (терминальная БЕЗ
 * запроса) и кадр без timestamp'ов — подпись без длительности (не
 * выдумывать); дополнительных №45 ради длительности НЕ вводится.
 * Длительность живого пути липкая, как и само терминальное состояние
 * (render-phase latch): ended B вытеснил A из среза — длительность
 * A остаётся.
 */
import { useEffect, useRef, useState } from 'react'
import { conferenceErrorCode, getConference } from '../../conferences/api/conferences'
import type { ConferenceMode } from '../../conferences/api/conferences'
import { endedConferenceLabel, formatSpanDuration } from '../../ui/duration'
import type { DurationSpan } from '../../ui/duration'
import { formatTime } from '../../ui/time'
import { cachedInviteProbeOutcome, rememberInviteProbeOutcome } from './conferenceInviteProbe'
import type {
  ConferenceInviteProbeOutcome,
  ConferenceInviteProbeRecord,
} from './conferenceInviteProbe'

/** Состояние клика «Подключиться»: проверка №45 → переход/подпись. */
type JoinState = 'idle' | 'checking' | 'ended' | 'missing' | 'error'

/**
 * T070: исход фонового №45-чека → состояние карточки (`live` —
 * active/created/транспортный отказ — кнопка остаётся, «idle»).
 */
function probeOutcomeToJoinState(outcome: ConferenceInviteProbeOutcome): JoinState {
  if (outcome === 'ended' || outcome === 'missing') {
    return outcome
  }
  return 'idle'
}

/** T071: span завершённой конференции из №45-ответа (клик/фоновый чек). */
function endedSpanOfView(view: {
  readonly startedAt?: string
  readonly endedAt?: string
}): DurationSpan {
  return { startedAt: view.startedAt, endedAt: view.endedAt }
}

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
  /**
   * T069: живая проекция завершения — конференция карточки совпала с
   * tombstone `endedByChat` чата (кадр №18 `conference.ended`).
   * `true` — терминальная «Конференция завершена» без клика; липкое
   * (однажды узнав, не откатывается); `false`/`undefined` — текущее
   * поведение с клик-проверкой №45.
   */
  readonly ended?: boolean
  /**
   * T070: фоновый №45-чек на монтаж — MessageList помечает им НОВЕЙШУЮ
   * карточку-приглашение окна ленты (единственную потенциально живую;
   * все предыдущие завершены по инварианту «одна активная на чат» и
   * терминальны чистой деривацией). Один запрос на конференцию за
   * сессию (сессионный кэш conferenceInviteProbe); `false`/`undefined`
   * — карточка фоново не проверяется (прямые рендеры, старые карточки).
   */
  readonly probe?: boolean
  /**
   * T071: span живого завершения — endedAt кадра №18 + startedAt
   * бейджа T059 (tombstone live-среза, MessengerPage → MessageList →
   * SystemRow по паре chatId+conferenceId). Применяется ТОЛЬКО в
   * терминальной ветке `ended` (липкая, как и само состояние): нет
   * timestamp'ов — подпись без длительности.
   */
  readonly endedSpan?: DurationSpan | null
}

export function ConferenceInviteCard({
  conferenceId,
  mode,
  createdAt,
  ended = false,
  probe = false,
  endedSpan = null,
}: ConferenceInviteCardProps) {
  // T070: осевший исход фонового чека восстанавливается из сессионного
  // кэша СРАЗУ (ленивый initial state) — повторный монтаж показывает
  // терминальное состояние (с длительностью T071 из span'а записи)
  // без повторного №45.
  const cachedRecord = probe ? cachedInviteProbeOutcome(conferenceId) : undefined
  const [state, setState] = useState<JoinState>(() =>
    cachedRecord === undefined ? 'idle' : probeOutcomeToJoinState(cachedRecord.outcome),
  )
  const [endedSpanState, setEndedSpanState] = useState<DurationSpan | null>(
    cachedRecord?.outcome === 'ended' ? (cachedRecord.span ?? null) : null,
  )
  // T069: липкий latch живого завершения (render-phase, идемпотентен —
  // паттерн Tick: однажды выставленный не сбрасывается, StrictMode-
  // двойной рендер сходится к тому же выводу).
  const endedLiveRef = useRef(false)
  if (ended) {
    endedLiveRef.current = true
  }
  const endedLive = endedLiveRef.current
  // T071: длительность живого пути липкая вместе с терминальным
  // состоянием (tombstone хранит только ПОСЛЕДНЮЮ завершённую
  // конференцию чата — ended B вытесняет A, span A уже не вернуть).
  const endedLiveDurationRef = useRef<string | null>(null)
  const liveDuration = formatSpanDuration(endedSpan)
  if (liveDuration !== null) {
    endedLiveDurationRef.current = liveDuration
  }

  /**
   * T070: фоновый №45-чек новейшей карточки на монтаж — тихий (без
   * `checking`: кнопка не «замигает»), один на конференцию за сессию.
   * Гварды: already-terminal живым кадром (ended на монтаже — кадр №18
   * уже доставлен, запроса не нужно), осевший кэш, повторные рендеры
   * этого инстанса (probedRef). Исход сводится в состояние карточки:
   * ended → терминальная подпись, missing → «Конференция недоступна»,
   * live (active/created/транспорт) → кнопка остаётся — клик-проверка
   * №45 остаётся страховкой; ретрай-цикл не вводится.
   */
  const probedRef = useRef(false)
  useEffect(() => {
    if (!probe || probedRef.current || endedLiveRef.current) {
      return
    }
    if (cachedInviteProbeOutcome(conferenceId) !== undefined) {
      return
    }
    probedRef.current = true
    void (async () => {
      let outcome: ConferenceInviteProbeOutcome = 'live'
      let span: DurationSpan | undefined
      try {
        const view = await getConference(conferenceId)
        if (view.status === 'ended') {
          // T071: терминальный №45 несёт startedAt/endedAt — span
          // оседает в кэше чека (ремоунт покажет длительность без №45)
          outcome = 'ended'
          span = endedSpanOfView(view)
        }
      } catch (cause) {
        if (conferenceErrorCode(cause) === 'conference_not_found') {
          outcome = 'missing'
        }
      }
      const record: ConferenceInviteProbeRecord =
        span === undefined ? { outcome } : { outcome, span }
      rememberInviteProbeOutcome(conferenceId, record)
      setState(probeOutcomeToJoinState(outcome))
      setEndedSpanState(span ?? null)
    })()
  }, [probe, conferenceId])

  const handleJoin = () => {
    if (state === 'checking' || state === 'ended' || state === 'missing' || endedLive) {
      return
    }
    setState('checking')
    void (async () => {
      try {
        const view = await getConference(conferenceId)
        if (view.status === 'ended') {
          // T071: длительность из №45-ответа (клик-ветка больше не
          // отбрасывает поля терминального вида)
          setEndedSpanState(endedSpanOfView(view))
          setState('ended')
          return
        }
        window.location.assign(`/conference/${view.id}?mode=${view.mode}`)
      } catch (cause) {
        setState(conferenceErrorCode(cause) === 'conference_not_found' ? 'missing' : 'error')
      }
    })()
  }

  let terminalStatus: string | null = null
  if (state === 'ended') {
    terminalStatus = endedConferenceLabel(formatSpanDuration(endedSpanState))
  } else if (state === 'missing') {
    terminalStatus = 'Конференция недоступна'
  } else if (endedLive) {
    terminalStatus = endedConferenceLabel(endedLiveDurationRef.current)
  }

  return (
    <div className="sys-card conference-invite">
      <div className="sys-title">{inviteTitle(mode)}</div>
      <div className="sys-row">
        {terminalStatus !== null ? (
          <output className="sys-status">{terminalStatus}</output>
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
