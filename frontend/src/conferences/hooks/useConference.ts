/**
 * Хук экрана конференции (009, US1, T026): жизненный цикл вкладки на
 * `/conference/:id` — пакетная загрузка №45 (ConferenceView, включая
 * status='ended') + №46 (MeView — роль/ограничения принципала для
 * pre-join/тумблеров, FR-007), join №47 → JoinTicket и «одна
 * конференция на вкладку» (Q10=A).
 *
 * Слот вкладки (Q10=A, research §10): модуль-level держатель
 * ЕДИНОГО активного подключения. `join` второй конференции той же
 * вкладкой корректно покидает первую — disconnect предыдущей
 * вызывается ДО занятия нового слота (слот освобождается, остальные
 * участники видят выход); параллельное участие — только через
 * несколько вкладок, каждое подключение — отдельный слот квоты
 * (edge spec.md). Медиа-подключение (Room, T027) привязывается к
 * слоту через `bindConferenceDisconnect`: хук управляет жизненным
 * циклом слота, транспортный разрыв — колбэк комнаты. Навигация SPA
 * сюда — full page load (`window.location.assign`), поэтому pagehide
 * тоже покидает конференцию: браузер и так рвёт WS при выгрузке
 * (LiveKit видит уход через departure_timeout), колбэк — best-effort
 * корректного выхода, дублировать разрыв безопасно.
 *
 * `conference_ended` — два REST-лица одного состояния (Q20=A): №45
 * `200 status='ended'` по старой ссылке и №47 `409 conference_ended`
 * — оба дают joinStatus='ended' → «конференция завершена» на экране
 * (финализация статуса — US5, T055/T057; здесь только клиентская
 * ветка). Реконнект с истёкшим токеном (FR-014): токен
 * короткоживущий (TTL 900 c), self-hosted LiveKit не ревоцирует —
 * компенсация повторным минтом `refreshTicket()` (№47, connId свежий
 * на каждый минт) в окне реконнекта (reconnect-window, дефолт
 * 120 с); вызвавший его ConferenceRoom (T027/T060) продолжает
 * подключение с новым тикетом, при неудаче завершается
 * контролируемо.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  conferenceErrorCode,
  getConference,
  getConferenceMe,
  joinConference,
} from '../api/conferences'
import type { ConferenceView, JoinTicket, MeView } from '../api/conferences'

/** Статус пакета загрузки №45+№46: error — повторяемый (reload). */
export type ConferenceLoadStatus = 'loading' | 'ready' | 'error'

/**
 * Статус подключения вкладки: idle → joining → joined; ended —
 * терминальный (№45 status='ended' или №47 409 conference_ended),
 * error — повторяемый (транспорт/403/404/409 full/503 — повторный
 * join возможен).
 */
export type ConferenceJoinStatus = 'idle' | 'joining' | 'joined' | 'ended' | 'error'

export interface UseConferenceResult {
  /** №45 ConferenceView; null — пока грузится/после отказа. */
  readonly conference: ConferenceView | null
  /** №46 MeView — роль и 4 ограничения принципала (FR-007). */
  readonly me: MeView | null
  readonly status: ConferenceLoadStatus
  readonly error: unknown
  /** Повтор пакета №45+№46 (retry после сбоя загрузки). */
  readonly reload: () => void
  /**
   * Повторный №46 без №45 (T043, live-обновление ограничений):
   * вызывается permission-событием LiveKit (MediaControls) после
   * серверного применения ограничений (SC-005) — снимок me догоняет
   * БД для pre-join после выхода; отказ — тихий, снимок остаётся
   * прежним (вспомогательная синхронизация, экран не рушится).
   */
  readonly refreshMe: () => void
  readonly joinStatus: ConferenceJoinStatus
  readonly joinError: unknown
  /** Действующий JoinTicket (№47); null — не подключены/после leave. */
  readonly ticket: JoinTicket | null
  /** №45 status='ended' ИЛИ №47 conference_ended — «конференция завершена». */
  readonly ended: boolean
  /** №47 → JoinTicket + слот вкладки (Q10=A); не-idle вызовы игнорируются. */
  readonly join: () => void
  /**
   * Повторный №47 при реконнекте с истёкшим токеном (FR-014): свежий
   * тикет того же слота, joinStatus не меняется; conference_ended —
   * терминальный ended + освобождение слота, прочие сбои — joinError
   * без смены статуса (контролируемое завершение — за звонком, T060).
   * Возвращает тикет или null при сбое/устаревании ответа.
   */
  readonly refreshTicket: () => Promise<JoinTicket | null>
  /** Корректный выход: disconnect слота + возврат к idle (повторный join возможен). */
  readonly leave: () => void
}

/** Активное подключение вкладки: разрыв привязывается комнатой (T027). */
interface ActiveConferenceSlot {
  readonly conferenceId: string
  readonly token: number
  disconnect: () => void
}

let activeSlot: ActiveConferenceSlot | null = null
let slotTokenSeq = 0

/** Конференция, чей слот занимает вкладка (null — слот свободен). */
export function activeConferenceId(): string | null {
  return activeSlot?.conferenceId ?? null
}

/**
 * Привязать разрыв медиа-подключения к занятому слоту (T027:
 * `room.disconnect()` после connect по JoinTicket) — вызывается
 * комнатой; вне активного слота этой конференции — no-op.
 */
export function bindConferenceDisconnect(conferenceId: string, disconnect: () => void): void {
  if (activeSlot?.conferenceId === conferenceId) {
    activeSlot.disconnect = disconnect
  }
}

/** Корректно покинуть активную конференцию вкладки: слот освобождается, подключение разрывается. */
export function leaveActiveConference(): void {
  const previous = activeSlot
  activeSlot = null
  previous?.disconnect()
}

/**
 * Занять слот «одна конференция на вкладку» (Q10=A): активная ДРУГАЯ
 * конференция корректно покидается ДО занятия нового слота; та же
 * конференция — тихая замена без разрыва (реконнект). Возврат —
 * release: освобождает только собственное владение, без разрыва
 * (им управляют leave/disconnect — комната переживает release).
 */
export function occupyConferenceSlot(conferenceId: string): () => void {
  const token = ++slotTokenSeq
  if (activeSlot !== null && activeSlot.conferenceId !== conferenceId) {
    leaveActiveConference()
  }
  activeSlot = { conferenceId, token, disconnect: () => {} }
  return () => {
    if (activeSlot?.token === token) {
      activeSlot = null
    }
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', leaveActiveConference)
}

export function useConference(conferenceId: string): UseConferenceResult {
  const [conference, setConference] = useState<ConferenceView | null>(null)
  const [me, setMe] = useState<MeView | null>(null)
  const [status, setStatus] = useState<ConferenceLoadStatus>('loading')
  const [error, setError] = useState<unknown>(null)
  const [refreshCount, setRefreshCount] = useState(0)
  const [joinStatus, setJoinStatus] = useState<ConferenceJoinStatus>('idle')
  const [joinError, setJoinError] = useState<unknown>(null)
  const [ticket, setTicket] = useState<JoinTicket | null>(null)
  const joinStatusRef = useRef<ConferenceJoinStatus>('idle')
  const releaseSlotRef = useRef<(() => void) | null>(null)
  const operationSeqRef = useRef(0)
  /** Аннулирует висящие №46-refresh при смене conferenceId/размонтировании. */
  const meSeqRef = useRef(0)

  const applyJoinStatus = useCallback((next: ConferenceJoinStatus) => {
    joinStatusRef.current = next
    setJoinStatus(next)
  }, [])

  // Смена conferenceId — новый жизненный цикл экрана: снимки и
  // статусы сбрасываются, висящие №47 аннулируются (устаревший ответ
  // не применяется — конституция III), владение слотом освобождается.
  // Cleanup при размонтировании — release без разрыва: комната (T027,
  // дочерний компонент) рвёт подключение в собственном cleanup
  // раньше родителя.
  useEffect(() => {
    meSeqRef.current++
    setConference(null)
    setMe(null)
    setStatus('loading')
    setError(null)
    setJoinStatus('idle')
    joinStatusRef.current = 'idle'
    setJoinError(null)
    setTicket(null)
    operationSeqRef.current++
    releaseSlotRef.current?.()
    releaseSlotRef.current = null
    return () => {
      // eslint-disable-next-line react-hooks/exhaustive-deps -- намеренная инвалидация висящих №47 при размонтировании/смене экрана: монотонный счётчик операций (не DOM-ref), рост в cleanup — сам механизм устаревания (устаревший ответ не занимается слот, конституция III)
      operationSeqRef.current++
      // eslint-disable-next-line react-hooks/exhaustive-deps -- тот же механизм для висящих №46-refresh (T043): счётчик не DOM-ref, устаревший снимок me не показывается на новом экране
      meSeqRef.current++
      releaseSlotRef.current?.()
      releaseSlotRef.current = null
    }
  }, [conferenceId])

  // Пакет №45+№46: оба ответа нужны до любого UX (ended — из №45,
  // роль/ограничения — из №46); отказ пакета — единая ошибка загрузки
  // с retry: №46 падает тем же кодом conference_not_found, что и №45
  // (различения не требуется).
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const [view, meView] = await Promise.all([
          getConference(conferenceId),
          getConferenceMe(conferenceId),
        ])
        if (cancelled) {
          return
        }
        setConference(view)
        setMe(meView)
        setError(null)
        setStatus('ready')
      } catch (cause) {
        if (cancelled) {
          return
        }
        setError(cause)
        setStatus('error')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [conferenceId, refreshCount])

  const reload = useCallback(() => {
    setRefreshCount((count) => count + 1)
  }, [])

  /**
   * Повторный №46 (T043): снимок me обновляется после серверного
   * применения ограничений (permission-событие LiveKit — SC-005);
   * seq гасит ответ, висящий через смену conferenceId (устаревший
   * снимок не показывается, конституция III). Тихий best-effort:
   * отказ синхронизации не рушит экран — живые grants LiveKit
   * остаются источником блокировок тумблеров.
   */
  const refreshMe = useCallback((): void => {
    const seq = meSeqRef.current
    void getConferenceMe(conferenceId)
      .then((view) => {
        if (meSeqRef.current === seq) {
          setMe(view)
        }
      })
      .catch(() => {})
  }, [conferenceId])

  const join = useCallback(() => {
    const current = joinStatusRef.current
    if (current === 'joining' || current === 'joined' || current === 'ended') {
      return
    }
    const seq = ++operationSeqRef.current
    applyJoinStatus('joining')
    setJoinError(null)
    void (async () => {
      try {
        const minted = await joinConference(conferenceId)
        if (seq !== operationSeqRef.current) {
          return
        }
        releaseSlotRef.current?.()
        releaseSlotRef.current = occupyConferenceSlot(conferenceId)
        setTicket(minted)
        applyJoinStatus('joined')
      } catch (cause) {
        if (seq !== operationSeqRef.current) {
          return
        }
        setJoinError(cause)
        applyJoinStatus(conferenceErrorCode(cause) === 'conference_ended' ? 'ended' : 'error')
      }
    })()
  }, [applyJoinStatus, conferenceId])

  const refreshTicket = useCallback(async (): Promise<JoinTicket | null> => {
    const seq = ++operationSeqRef.current
    try {
      const minted = await joinConference(conferenceId)
      if (seq !== operationSeqRef.current) {
        return null
      }
      setTicket(minted)
      return minted
    } catch (cause) {
      if (seq !== operationSeqRef.current) {
        return null
      }
      if (conferenceErrorCode(cause) === 'conference_ended') {
        if (activeConferenceId() === conferenceId) {
          leaveActiveConference()
        }
        releaseSlotRef.current = null
        setTicket(null)
        applyJoinStatus('ended')
      }
      setJoinError(cause)
      return null
    }
  }, [applyJoinStatus, conferenceId])

  const leave = useCallback(() => {
    operationSeqRef.current++
    if (activeConferenceId() === conferenceId) {
      leaveActiveConference()
    }
    releaseSlotRef.current = null
    setTicket(null)
    setJoinError(null)
    applyJoinStatus('idle')
  }, [applyJoinStatus, conferenceId])

  const ended = conference?.status === 'ended' || joinStatus === 'ended'

  return {
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
  }
}
