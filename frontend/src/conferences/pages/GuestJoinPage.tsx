/**
 * Гостевая join-страница (009, US4, T049; FR-009–FR-012) — публичный
 * экран маршрута `/join/:inviteToken` (маршрут — T050): внешний участник
 * без аккаунта входит по подписанной инвайт-ссылке. Гость видит ТОЛЬКО
 * этот экран и экран конференции — доступ к чату исключён (FR-010:
 * чат-роуты недоступны без аутентификации, API чатов закрыты).
 *
 * Флоу (SC-003 ≤ 3 действий: имя → «Войти» pre-join → звонок):
 * 1) №54 invite-info на монтаж — статус ссылки ДО ввода имени:
 *    `{valid:true}` (+live-подсказка «конференция идёт») → шаг имени;
 *    `{valid:false}` — терминальный экран с ПОНЯТНЫМ сообщением по
 *    reason (expired/revoked/ended); подделанный nonce — единообразный
 *    `{valid:false}` без причины → «Ссылка недействительна» (минимум
 *    раскрытия, api-contract §1/edge); транспорт/429 — повторяемая
 *    ошибка проверки («Повторить»).
 * 2) Имя: клиентская валидация зеркалит серверную (trim, 1–50, Q19/
 *    Q7=A) — пустое/пробельное и 51-символьное дают понятный отказ
 *    до №55; серверный `422 name_invalid` возвращает к шагу имени
 *    с тем же сообщением (защита вдогонку).
 * 3) Pre-join — ПЕРЕИСПОЛЬЗОВАНИЕ PreJoinScreen (T030) c mode='audio':
 *    дефолт «только микрофон», тумблер камеры доступен (soft — как у
 *    членов, Q1=C/Q3=A); restrictions НЕ передаются — у гостя на
 *    pre-join ограничений нет (строка гостя создаётся только при
 *    выдаче тикета №55; применённые модератором ограничения отражаются
 *    в звонке live-права́ми — grants подключения, data-model §2).
 *    До «Войти» ничего не публикуется и слот квоты не занимается
 *    (FR-007); «Отмена» возвращает к шагу имени без последствий.
 * 4) №55 join-by-invite → JoinTicket → ConferenceRoom (T027):
 *    initialMedia/initialDevices из выборки pre-join; подключение,
 *    тайлы и адаптация качества — как у членов (FR-004). Слот «одна
 *    конференция на вкладку» (Q10=A) — ключом гостя служит
 *    `invite:{inviteToken}` (UUID конференции гостю не раскрывается):
 *    ConferenceRoom привязывает disconnect слота, «Покинуть»/
 *    pagehide корректно рвут подключение. Реконнект с истёкшим
 *    токеном (FR-014) — повторный №55 той же многоразовой ссылкой
 *    (Q2=A) со свежим connId.
 *
 * Ошибки №55 ветвятся по коду Problem (конвенция 002): терминальные
 * состояния ссылки (expired/revoked/ended/invalid|not_found) — тот же
 * экран понятного отказа, что и №54; `name_invalid` — к шагу имени;
 * повторяемые (`conference_full` — квота общая с членами, Q23=A;
 * `rate_limited`; транспорт) — сообщение + «Повторить» на pre-join.
 * Панель модератора гостю не рендерится (прав нет, №48–№53
 * аутентифицированы и гейтнуты ролью); участники и пометка «гость» —
 * ParticipantsPanel (бейдж — T051); собственные ограничения — только
 * live-права MediaControls (T043), снимок №46 у гостя отсутствует.
 */
import { useCallback, useEffect, useId, useRef, useState } from 'react'
import type { SubmitEvent } from 'react'
import { guestErrorCode, getInviteInfo, joinConferenceByInvite } from '../api/conferences'
import type { InviteInvalidReason, JoinTicket } from '../api/conferences'
import {
  activeConferenceId,
  leaveActiveConference,
  occupyConferenceSlot,
} from '../hooks/useConference'
import ConferenceRoom from '../components/ConferenceRoom'
import MediaControls from '../components/MediaControls'
import ParticipantsPanel from '../components/ParticipantsPanel'
import PreJoinScreen from '../components/PreJoinScreen'
import type { PreJoinSelection } from '../components/PreJoinScreen'
import '../conferences.css'

/** Имя гостя: серверная граница trim 1–50 (Q19/Q7=A) — зеркало на клиенте. */
const GUEST_NAME_MAX_LENGTH = 50

/** Props страницы: nonce из маршрута /join/:inviteToken (T050). */
export interface GuestJoinPageProps {
  readonly inviteToken: string
}

/**
 * Фаза гостевого флоу: checking — №54 в полёте; name — ввод имени;
 * prejoin — переиспользуемый PreJoinScreen; joined — звонок; refused —
 * терминальный отказ ссылки (вход недоступен); infoError — повторяемый
 * сбой проверки №54.
 */
type GuestJoinPhase = 'checking' | 'name' | 'prejoin' | 'joined' | 'refused' | 'infoError'

/**
 * Терминальное состояние ссылки: понятные причины №54 reason (истёк/
 * отозван/завершён) + единообразный отказ подделки/отсутствия (без
 * различения причин — минимум раскрытия, edge).
 */
type LinkRefusal = InviteInvalidReason | 'invalid'

/** Слот «одна конференция на вкладку» (Q10=A) гостя — по стабильному nonce ссылки. */
function guestSlotKey(inviteToken: string): string {
  return `invite:${inviteToken}`
}

/** Понятное сообщение терминального отказа (№54 reason / №55 terminal). */
function refusalMessage(refusal: LinkRefusal): string {
  switch (refusal) {
    case 'expired':
      return 'Срок действия ссылки истёк'
    case 'revoked':
      return 'Ссылка отозвана'
    case 'ended':
      return 'Конференция завершена'
    default:
      return 'Ссылка недействительна'
  }
}

/**
 * Терминальный отказ №54/№55 — единый экран: одна и та же причина с
 * обоих REST-лиц (№54 `{valid:false, reason}` и №55 409), подделка и
 * отсутствие ссылки — единообразный `invalid`.
 */
function terminalRefusal(code: ReturnType<typeof guestErrorCode>): LinkRefusal | null {
  switch (code) {
    case 'invite_expired':
      return 'expired'
    case 'invite_revoked':
      return 'revoked'
    case 'conference_ended':
      return 'ended'
    case 'invite_invalid':
    case 'invite_not_found':
      return 'invalid'
    default:
      return null
  }
}

/** Повторяемый отказ №55 — остаёмся на pre-join с «Повторить» (ссылка жива). */
function joinRetryMessage(code: ReturnType<typeof guestErrorCode>): string {
  switch (code) {
    case 'conference_full':
      return 'Конференция заполнена — попробуйте позже'
    case 'rate_limited':
      return 'Слишком много попыток — подождите немного и повторите'
    default:
      return 'Не удалось подключиться'
  }
}

export default function GuestJoinPage({ inviteToken }: GuestJoinPageProps) {
  const nameInputId = useId()

  const [phase, setPhase] = useState<GuestJoinPhase>('checking')
  /** Терминальный отказ ссылки (фаза refused). */
  const [refusal, setRefusal] = useState<LinkRefusal | null>(null)
  /** live-подсказка №54: конференция идёт прямо сейчас. */
  const [live, setLive] = useState(false)
  /** Повтор №54 (транспорт/429 — проверка повторяема). */
  const [infoAttempt, setInfoAttempt] = useState(0)
  /** Введённое имя (фиксируется тримом при переходе к pre-join). */
  const [name, setName] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  /** Стартовая выборка pre-join по «Войти» — в звонок (как T034). */
  const [selection, setSelection] = useState<PreJoinSelection | null>(null)
  /** №55 в полёте: «Войти» недоступна, aria-busy (T049 → PreJoinScreen). */
  const [joining, setJoining] = useState(false)
  /** Повторяемая ошибка №55 — сообщение + «Повторить» на pre-join. */
  const [joinError, setJoinError] = useState<string | null>(null)
  const [ticket, setTicket] = useState<JoinTicket | null>(null)

  const joiningRef = useRef(false)
  const releaseSlotRef = useRef<(() => void) | null>(null)
  /**
   * Имя минта: фиксируется на шаге имени — №55/реконнект всегда идут с
   * уже провалидированным значением (смена имени — только через шаг имени).
   */
  const mintNameRef = useRef<string | null>(null)
  /** Аннулирует висящие №55 при размонтировании (устаревший ответ не занимается слот). */
  const joinSeqRef = useRef(0)

  const slotKey = guestSlotKey(inviteToken)

  // Смена inviteToken — новый жизненный цикл страницы: фазы/снимки
  // сбрасываются, висящие №55 аннулируются, владение слотом
  // освобождается (медиа-комната рвёт подключение в собственном cleanup
  // раньше родителя — как useConference, T026).
  useEffect(() => {
    joinSeqRef.current++
    joiningRef.current = false
    mintNameRef.current = null
    releaseSlotRef.current?.()
    releaseSlotRef.current = null
    setPhase('checking')
    setRefusal(null)
    setLive(false)
    setName('')
    setNameError(null)
    setSelection(null)
    setJoining(false)
    setJoinError(null)
    setTicket(null)
  }, [inviteToken])

  // Размонтирование: освободить владение слотом без разрыва (комната —
  // дочерний компонент — сама рвёт подключение в своём cleanup) и
  // погасить висящие №55.
  useEffect(() => {
    return () => {
      // eslint-disable-next-line react-hooks/exhaustive-deps -- намеренная инвалидация висящих №55 при размонтировании: монотонный счётчик операций (не DOM-ref), рост в cleanup — сам механизм устаревания (устаревший ответ не занимается слот, конституция III)
      joinSeqRef.current++
      releaseSlotRef.current?.()
      releaseSlotRef.current = null
    }
  }, [])

  // №54 invite-info: статус ссылки до ввода имени (SC-003); повтор —
  // infoAttempt (транспорт/429 повторяемы, терминальные reason — экран
  // отказа). Смена inviteToken/устаревший ответ гасятся cancelled.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const info = await getInviteInfo(inviteToken)
        if (cancelled) {
          return
        }
        if (info.valid) {
          setLive(info.live === true)
          setPhase('name')
        } else {
          setRefusal(info.reason ?? 'invalid')
          setPhase('refused')
        }
      } catch {
        if (!cancelled) {
          setPhase('infoError')
        }
      }
    })()
    return () => {
      cancelled = true
    }
  }, [inviteToken, infoAttempt])

  /** Повтор №54 после сбоя проверки. */
  const retryInfo = useCallback((): void => {
    setInfoAttempt((attempt) => attempt + 1)
  }, [])

  /**
   * Шаг имени → pre-join: клиентская валидация — зеркало серверной
   * (trim 1–50, Q19/Q7=A): пустое/пробельное и длиннее 50 — понятный
   * отказ ДО №55; трим фиксируется (сервер тримит так же).
   */
  const submitName = useCallback(
    (event: SubmitEvent<HTMLFormElement>): void => {
      event.preventDefault()
      const trimmed = name.trim()
      if (trimmed === '') {
        setNameError('Введите имя')
        return
      }
      if (trimmed.length > GUEST_NAME_MAX_LENGTH) {
        setNameError(`Имя — не длиннее ${GUEST_NAME_MAX_LENGTH} символов`)
        return
      }
      setNameError(null)
      setName(trimmed)
      mintNameRef.current = trimmed
      setJoinError(null)
      setPhase('prejoin')
    },
    [name],
  )

  /** Pre-join «Отмена»: назад к шагу имени (№55 не вызывался, слот свободен, превью освобождено pre-join). */
  const backToName = useCallback((): void => {
    setJoinError(null)
    setPhase('name')
  }, [])

  /**
   * №55 «Войти» (FR-007: слот квоты занимается только теперь; строка
   * гостя создаётся при выдаче тикета — Q7=A): успех → слот вкладки
   * (Q10=A) и звонок; отказ ветвится по коду Problem — терминальные
   * состояния ссылки на экран отказа, name_invalid к шагу имени,
   * прочие повторяемы. seq гасит ответ, висящий через смену ссылки.
   */
  const join = useCallback((): void => {
    if (joiningRef.current) {
      return
    }
    const mintName = mintNameRef.current
    if (mintName === null) {
      return
    }
    const seq = ++joinSeqRef.current
    joiningRef.current = true
    setJoining(true)
    setJoinError(null)
    void (async () => {
      try {
        const minted = await joinConferenceByInvite(inviteToken, mintName)
        if (seq !== joinSeqRef.current) {
          return
        }
        releaseSlotRef.current?.()
        releaseSlotRef.current = occupyConferenceSlot(slotKey)
        setTicket(minted)
        setPhase('joined')
      } catch (cause) {
        if (seq !== joinSeqRef.current) {
          return
        }
        const code = guestErrorCode(cause)
        const terminal = terminalRefusal(code)
        if (terminal !== null) {
          setRefusal(terminal)
          setPhase('refused')
          return
        }
        if (code === 'name_invalid') {
          setNameError(`Имя — не длиннее ${GUEST_NAME_MAX_LENGTH} символов`)
          setPhase('name')
          return
        }
        setJoinError(joinRetryMessage(code))
      } finally {
        if (seq === joinSeqRef.current) {
          joiningRef.current = false
          setJoining(false)
        }
      }
    })()
  }, [inviteToken, slotKey])

  /**
   * «Войти» pre-join (T030): стартовая выборка едет в звонок (как
   * T034 → ConferenceRoom initialMedia/initialDevices), затем №55.
   */
  const handlePreJoin = useCallback(
    (picked: PreJoinSelection): void => {
      setSelection(picked)
      join()
    },
    [join],
  )

  /**
   * Повторный №55 при реконнекте с истёкшим токеном (FR-014): ссылка
   * многоразовая (Q2=A) — свежий connId тем же именем; смена токена
   * пересоздаёт комнату. null (сбой/терминальное состояние) —
   * контролируемое «не удалось» комнаты (полное окно — T060).
   */
  const refreshTicket = useCallback(async (): Promise<JoinTicket | null> => {
    const mintName = mintNameRef.current
    if (mintName === null) {
      return null
    }
    try {
      const minted = await joinConferenceByInvite(inviteToken, mintName)
      setTicket(minted)
      return minted
    } catch {
      return null
    }
  }, [inviteToken])

  /**
   * «Покинуть» (MediaControls onLeave): корректный выход слота вкладки
   * (Q10=A) — медиа-подключение рвётся привязанным disconnect; ссылка
   * многоразовая — возврат на pre-join для возможного повторного входа.
   */
  const leave = useCallback((): void => {
    joinSeqRef.current++
    joiningRef.current = false
    setJoining(false)
    if (activeConferenceId() === slotKey) {
      leaveActiveConference()
    }
    releaseSlotRef.current = null
    setTicket(null)
    setJoinError(null)
    setPhase('prejoin')
  }, [slotKey])

  return (
    <section aria-label="Подключение к конференции" data-guest-join={inviteToken}>
      {phase === 'checking' && <output className="guest-join-status">Проверка ссылки…</output>}
      {phase === 'infoError' && (
        <div className="conference-error">
          <p role="alert">Не удалось проверить ссылку</p>
          <button type="button" onClick={retryInfo}>
            Повторить
          </button>
        </div>
      )}
      {phase === 'refused' && refusal !== null && (
        <div className="conference-error guest-join-refused">
          <p role="alert">{refusalMessage(refusal)}</p>
        </div>
      )}
      {phase === 'name' && (
        <form className="guest-join-name" onSubmit={submitName}>
          <h1 className="guest-join-title">Подключение к конференции</h1>
          {live && <output className="guest-join-live">Конференция идёт прямо сейчас</output>}
          <div className="guest-join-name-field">
            <label htmlFor={nameInputId}>Ваше имя</label>
            <input
              id={nameInputId}
              className="guest-join-name-input"
              type="text"
              value={name}
              autoComplete="name"
              aria-invalid={nameError !== null}
              onChange={(event) => {
                setName(event.target.value)
              }}
            />
          </div>
          {nameError !== null && (
            <p role="alert" className="guest-join-name-error">
              {nameError}
            </p>
          )}
          <div className="guest-join-actions">
            <button type="submit" className="guest-join-submit">
              Продолжить
            </button>
          </div>
        </form>
      )}
      {phase === 'prejoin' && (
        <div className="guest-join-prejoin">
          {joinError !== null && (
            <div className="conference-error">
              <p role="alert">{joinError}</p>
              <button type="button" onClick={join} disabled={joining}>
                Повторить
              </button>
            </div>
          )}
          <PreJoinScreen
            mode="audio"
            joining={joining}
            onJoin={handlePreJoin}
            onCancel={backToName}
          />
        </div>
      )}
      {phase === 'joined' && ticket !== null && (
        <div className="conference-joined">
          <ConferenceRoom
            conferenceId={slotKey}
            ticket={ticket}
            mode="audio"
            initialMedia={
              selection !== null
                ? { microphone: selection.microphone, camera: selection.camera }
                : undefined
            }
            initialDevices={selection?.devices}
            refreshTicket={refreshTicket}
          >
            <ParticipantsPanel />
            <MediaControls onLeave={leave} />
          </ConferenceRoom>
        </div>
      )}
    </section>
  )
}
