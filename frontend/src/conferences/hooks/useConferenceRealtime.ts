/**
 * Индикатор «звонок идёт» (009, US5, T059; FR-013, US5-1,
 * contracts/realtime-conference-events.md): live-срез активных
 * конференций пользователя по кадрам №18 `conference.started`/
 * `conference.ended` того же per-user SSE-канала (useRealtime —
 * одно соединение на устройство/сессию).
 *
 * Применение — ФУНКЦИЯ СТАТУСА, не накопление (§4): канал
 * at-least-once, кадры могут дублироваться и приезжать вне
 * порядка, поэтому каждый кадр сводится к «звонок идёт / не идёт»
 * чата — дубль возвращает ТОТ ЖЕ срез (React выпадает из
 * ре-рендера), а состояние помнит завершённые конференции
 * (tombstone по chatId → conferenceId): внепорядковый
 * `started` ПОСЛЕ `ended` той же конференции бейдж НЕ поднимает
 * (жизненный цикл — одно статусное поле, переходы только вперёд;
 * конституция III, T057-кейс «поздний participant_joined»).
 *
 * REST-fallback №45 (§4 «полнота»): на КАЖДОМ (пере)подключении
 * SSE каждый чат с поднятым бейджем перепроверяется
 * `GET /conferences/{id}` — источник истины индикатора
 * `ConferenceView.status`: `active` — бейдж стоит (даже если кадр
 * потерян при обрыве), `created`/`ended` — снимается (`ended`
 * дополнительно tombston'ит id). Ошибка транспорта — состояние
 * молча сохраняется (best-effort: следующий (re)open повторит);
 * проблемный ОТВЕТ API (№404 не-члену/несуществующей — единый
 * код, существование не раскрывается) — бейдж снимается: сервер
 * авторитетно сказал «конференции для тебя нет» (edge «исключён
 * из чата» — событий больше не придёт, №18 адресован активным
 * членам).
 *
 * Адресация: кадры приходят ТОЛЬКО членам чата (снимок членства
 * в транзакции перехода статуса), гости SSE не имеют — их канал
 * оповещений LiveKit (research §7), сюда не входит. События
 * конференций — не сообщения: в историю чата ничего не пишется
 * (Q20=A), срез живёт только в памяти сессии.
 *
 * Интеграция (T059): монтируется один раз в MessengerPage,
 * `byChat` уезжает в ChatListPanel (строки «Чатов») и ChatHeader
 * (заголовок открытого чата) — рендер через ConferenceBadge.
 */
import { useEffect, useRef, useState } from 'react'
import { getConference, isConferenceProblem } from '../api/conferences'
import type { ConferenceMode, ConferenceRealtimeEvent, ConferenceView } from '../api/conferences'
import { useRealtime } from '../../chats/hooks/useRealtime'

/** Одна живая конференция чата — всё, что нужно индикатору и кнопке входа. */
export interface LiveConference {
  readonly conferenceId: string
  /** Стартовые дефторты №44 (иконка бейджа: камера/трубка). */
  readonly mode: ConferenceMode
  /** Момент фактического входа первого участника (кадр §1). */
  readonly startedAt: string
}

/**
 * Полный live-срез хука: поднятые бейджи `byChat` ПЛЮС tombstone
 * завершённых (§4 порядок доставки). Tombstone — то, что делает
 * `ended` окончательным: повторная доставка старого `started`
 * находит id мёртвым и не воскрешает бейдж.
 */
export interface ConferenceIndicatorSlice {
  readonly byChat: ReadonlyMap<string, LiveConference>
  /** chatId → conferenceId последней ЗАВЕРШЁННОЙ конференции чата. */
  readonly endedByChat: ReadonlyMap<string, string>
}

const EMPTY_BY_CHAT: ReadonlyMap<string, LiveConference> = new Map()
const EMPTY_ENDED: ReadonlyMap<string, string> = new Map()

/** Стартовое состояние: до первого кадра индикаторов нет. */
export const EMPTY_CONFERENCE_SLICE: ConferenceIndicatorSlice = {
  byChat: EMPTY_BY_CHAT,
  endedByChat: EMPTY_ENDED,
}

/**
 * `conference.started` (§1, US5-1): бейдж «звонок идёт» чата.
 * Дубль (тот же conferenceId) — ТОТ ЖЕ срез; `started` завершённой
 * (tombstone) конференции — no-op (внепорядковая доставка);
 * `started` ДРУГОЙ конференции того же чата заменяет запись —
 * инвариант «одна активная на чат» держит сервер (№44
 * идемпотентен), срез просто принимает заявленный статус.
 */
export function applyConferenceEvent(
  slice: ConferenceIndicatorSlice,
  event: ConferenceRealtimeEvent,
): ConferenceIndicatorSlice {
  if (event.type === 'conference.started') {
    if (slice.endedByChat.get(event.chatId) === event.conferenceId) {
      return slice
    }
    const current = slice.byChat.get(event.chatId)
    if (current?.conferenceId === event.conferenceId) {
      return slice
    }
    const byChat = new Map(slice.byChat)
    byChat.set(event.chatId, {
      conferenceId: event.conferenceId,
      mode: event.mode,
      startedAt: event.startedAt,
    })
    return { ...slice, byChat }
  }
  // conference.ended (§2): tombstone id + снятие бейджа, если он
  // стоит именно на эту конференцию; дубль `ended` — тот же срез.
  const badged = slice.byChat.get(event.chatId)
  let nextByChat: Map<string, LiveConference> | null = null
  if (badged?.conferenceId === event.conferenceId) {
    nextByChat = new Map(slice.byChat)
    nextByChat.delete(event.chatId)
  }
  let nextEnded: Map<string, string> | null = null
  if (slice.endedByChat.get(event.chatId) !== event.conferenceId) {
    nextEnded = new Map(slice.endedByChat)
    nextEnded.set(event.chatId, event.conferenceId)
  }
  if (nextByChat === null && nextEnded === null) {
    return slice
  }
  return {
    byChat: nextByChat ?? slice.byChat,
    endedByChat: nextEnded ?? slice.endedByChat,
  }
}

/**
 * REST-fallback №45 как чистая функция статуса: `status='active'`
 * — бейдж стоит (mode/startedAt — из ответа, кадры могли не дойти);
 * `created` — бейджа быть не должно (started-кадр был
 * преждевременным), tombstone НЕ ставится — конференция ещё может
 * фактически начаться; `ended` — бейдж снят + tombstone id (поздний
 * потерянный `started` той же конференции не поднимет его снова).
 * Чужой id (в чате уже другая конференция) не трогает текущий
 * бейдж, но tombstone всё равно помечает завершённый id.
 */
export function applyConferenceView(
  slice: ConferenceIndicatorSlice,
  view: ConferenceView,
): ConferenceIndicatorSlice {
  if (view.status === 'active') {
    const current = slice.byChat.get(view.chatId)
    if (
      current?.conferenceId === view.id &&
      current.mode === view.mode &&
      (view.startedAt === undefined || current.startedAt === view.startedAt)
    ) {
      return slice
    }
    const byChat = new Map(slice.byChat)
    byChat.set(view.chatId, {
      conferenceId: view.id,
      mode: view.mode,
      startedAt: view.startedAt ?? current?.startedAt ?? view.createdAt,
    })
    return { ...slice, byChat }
  }
  const badged = slice.byChat.get(view.chatId)
  let nextByChat: Map<string, LiveConference> | null = null
  if (badged?.conferenceId === view.id) {
    nextByChat = new Map(slice.byChat)
    nextByChat.delete(view.chatId)
  }
  if (view.status !== 'ended') {
    return nextByChat === null ? slice : { ...slice, byChat: nextByChat }
  }
  let nextEnded: Map<string, string> | null = null
  if (slice.endedByChat.get(view.chatId) !== view.id) {
    nextEnded = new Map(slice.endedByChat)
    nextEnded.set(view.chatId, view.id)
  }
  if (nextByChat === null && nextEnded === null) {
    return slice
  }
  return {
    byChat: nextByChat ?? slice.byChat,
    endedByChat: nextEnded ?? slice.endedByChat,
  }
}

/**
 * Снятие бейджа по id конференции (проблемный ОТВЕТ №45 — например
 * 404 не-члену: существование не раскрывается, но сервер
 * авторитетно отрицает конферению для этого принципала). Транспорт
 * (не-Problem) сюда не попадает — тот случай оставляет состояние.
 */
function dropConferenceById(
  slice: ConferenceIndicatorSlice,
  conferenceId: string,
): ConferenceIndicatorSlice {
  let changed = false
  const byChat = new Map<string, LiveConference>()
  for (const [chatId, live] of slice.byChat) {
    if (live.conferenceId === conferenceId) {
      changed = true
      continue
    }
    byChat.set(chatId, live)
  }
  return changed ? { ...slice, byChat } : slice
}

/**
 * Держит live-срез «звонок идёт» на общем №18-канале (T059):
 * каждый кадр конференции — через `applyConferenceEvent`, каждое
 * (пере)подключение SSE — REST-fallback №45 по всем поднятым
 * бейджам (§4). Single-flight: параллельные onOpen (быстрый
 * обрыв/рестарт канала) присоединяются к идущему циклу, а не
 * устраивают гонку ответов.
 */
export function useConferenceRealtime(): ConferenceIndicatorSlice {
  const realtime = useRealtime()
  const [slice, setSlice] = useState<ConferenceIndicatorSlice>(EMPTY_CONFERENCE_SLICE)
  const sliceRef = useRef(slice)
  sliceRef.current = slice
  const reconcileInFlight = useRef(false)

  useEffect(() => {
    return realtime.onConferenceEvent((event) => {
      setSlice((previous) => applyConferenceEvent(previous, event))
    })
  }, [realtime])

  useEffect(() => {
    return realtime.onOpen(() => {
      if (reconcileInFlight.current) {
        return
      }
      const live = [...sliceRef.current.byChat.values()]
      if (live.length === 0) {
        return
      }
      reconcileInFlight.current = true
      void Promise.allSettled(
        live.map(async (entry) => {
          try {
            const view = await getConference(entry.conferenceId)
            setSlice((previous) => applyConferenceView(previous, view))
          } catch (cause) {
            // Транспорт — сохранить (следующий (re)open повторит);
            // Problem-ответ — сервер отрицает конференцию: снять.
            if (isConferenceProblem(cause)) {
              setSlice((previous) => dropConferenceById(previous, entry.conferenceId))
            }
          }
        }),
      ).finally(() => {
        reconcileInFlight.current = false
      })
    })
  }, [realtime])

  return slice
}
