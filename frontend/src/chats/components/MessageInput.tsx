/**
 * Message composer (feature 004, T026; reskin 008, US1, T024; FR-021,
 * ui-behavior §4 «Композер»): the `.chat-input` block of the prototype
 * specs/008-chat-window-styling/design/chats.html §7 — the golden
 * `.input-frame` around the field (`.msg-input`: Anonymous Pro over
 * `--input-bg`, design-tokens §2 mono role) + the «ОТПРАВИТЬ» plate
 * (`.send-btn`: `--gold-gradient`, Cormorant SC caps). The 004 hooks
 * stay as wrappers of the prototype classes (research §C, FR-034):
 * the form keeps `message-input`, the field — id `message-composer`,
 * the local validation error — `message-input-error` role=alert.
 *
 * Client-side pre-validation of the 004 rule (see chats/validation.ts)
 * is unchanged: `onSend` fires only with the normalized valid text, so
 * an invalid draft cannot reach the chat or the outbox. The overflow
 * hint rides the page TOAST slot (Bug 4, T082; FR-025, ui-behavior
 * §5): a draft over MESSAGE_MAX_LENGTH raises «Сообщение слишком
 * длинное: …» via useToast/ToastProvider (z-99, ~3 s, the single
 * notification replacing any previous one) — every other notification
 * of the feature already toasts this way. The blank-draft error
 * «Сообщение не может быть пустым» keeps its inline place above the
 * field (`message-input-error` role=alert, US1-4). The server-side
 * 400 (text_blank / text_too_long) remains the authoritative
 * protection.
 *
 * Sending (FR-021, US1-AS4): Enter submits the trimmed text (IME
 * composition never submits); Shift+Enter keeps the newline of the
 * 004 multiline drafts. The button never steals the field focus —
 * its mousedown default is prevented, so the caret stays in the
 * field on every send path (button click included). A successful
 * send raises the prototype «steam»: three `.puff` flashes above the
 * composer (design-tokens §5), spawned imperatively into the `.steam`
 * container exactly like the prototype JS and dropped after the
 * 1.1s flight (the timers are tracked for the unmount);
 * prefers-reduced-motion kills the flight via machine.css (FR-004).
 *
 * The blocked-contact state (US2, T036; FR-022, US2-AS5) is the
 * prototype §7 `updateInputState()` verbatim: the field AND the
 * «ОТПРАВИТЬ» button go disabled while the placeholder carries the
 * block hint «Контакт заблокирован — разблокируйте, чтобы писать
 * сообщения» (the only hint surface of the prototype). It is the
 * ONLY input lock of the composer — the flood-limit/overflow states
 * of 005 never disable input (FR-030, Clarification); unblocking
 * (№24, wired by the page through `blockedByMe`) restores the
 * activity and the normal «Сообщение…» placeholder. The 004
 * `disabled` prop stays its own independent wiring (no user).
 *
 * The flood-limit retry line (US4, T051; FR-030, Clarification): a
 * 429/503 deferral of the open chat is a patience state, not an
 * input lock — `floodRetryAt` (the HEAD retryAt record of the open
 * chat, outbox.ts `headFloodRetryAt`, wired by the page) drives a
 * single status line «Повтор через N с» above the field (a polite
 * live region like SyncIndicator): ONE interval ticks the ONE
 * element (ui-behavior §4 — no per-record timers), the countdown
 * hides at the deadline (the 005 engine retries by itself,
 * useOutbox SC-002), and the field/button stay active — a new
 * message transparently enqueues as another optimistic
 * «отправляется» row. The waiting bubble itself never carries a
 * countdown (Clarification).
 */
import { useEffect, useRef, useState } from 'react'
import type {
  KeyboardEvent as ReactKeyboardEvent,
  MouseEvent as ReactMouseEvent,
  SubmitEvent,
} from 'react'
import { useToast } from '../../ui/Toast'
import { MESSAGE_MAX_LENGTH, validateMessageText } from '../validation'
import './message-input.css'

/** Steam spawn count — design-tokens §5 (3 puff's). */
const PUFF_COUNT = 3

/** Puff lifetime (ms) — the 1.1s flight + margin, prototype JS. */
const PUFF_LIFETIME_MS = 1400

/** Block hint of a blocked-contact chat — prototype §7 updateInputState(). */
const BLOCKED_PLACEHOLDER = 'Контакт заблокирован — разблокируйте, чтобы писать сообщения'

/**
 * Cryptographically strong uniform random in [min, max) — the flight
 * offsets of the steam puffs (the prototype's Math.random replaced by
 * Crypto API: the only consumer, but no weak PRNG anywhere).
 */
function randomBetween(min: number, max: number): number {
  const buffer = new Uint32Array(1)
  crypto.getRandomValues(buffer)
  return min + ((max - min) * (buffer[0] ?? 0)) / 2 ** 32
}

/**
 * Фокус остаётся в поле — кнопка не забирает его на mousedown (FR-021,
 * US1-AS4): default предотвращён до click-отправки. Модульная область —
 * состояния компонента не требует (S7721).
 */
function handleSendMouseDown(event: ReactMouseEvent<HTMLButtonElement>) {
  event.preventDefault()
}

export interface MessageInputProps {
  /** Receives the normalized (trimmed) valid text only. */
  readonly onSend: (text: string) => void
  readonly disabled?: boolean
  /** Blocked-contact chat (FR-022): the only input lock of the composer. */
  readonly blocked?: boolean
  /**
   * Epoch-ms deadline of the HEAD retryAt record of the open chat
   * (429/503 deferral, outbox.ts `headFloodRetryAt`): drives the
   * single «Повтор через N с» countdown line (FR-030). Null — no
   * deferral, no line; the input stays active either way.
   */
  readonly floodRetryAt?: number | null
}

export function MessageInput({
  onSend,
  disabled = false,
  blocked = false,
  floodRetryAt = null,
}: MessageInputProps) {
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [floodSecondsLeft, setFloodSecondsLeft] = useState<number | null>(null)
  const showToast = useToast()
  const fieldRef = useRef<HTMLTextAreaElement>(null)
  const steamRef = useRef<HTMLDivElement>(null)
  const puffTimersRef = useRef<number[]>([])

  useEffect(
    () => () => {
      // The flight outlives the composer only on a chat switch mid-
      // animation — the puffs die with the container, the timers die
      // here.
      for (const timer of puffTimersRef.current) {
        window.clearTimeout(timer)
      }
    },
    [],
  )

  // Отсчёт флуд-лимита (FR-030): ОДИН интервал тикает ОДИН элемент —
  // строку «Повтор через N с» из головной retryAt-записи (ui-behavior
  // §4); на дедлайне строка гаснет — ретрай делает сам движок 005
  // (useOutbox), новая отсрочка приедет новой головой через проп.
  useEffect(() => {
    if (floodRetryAt === null) {
      setFloodSecondsLeft(null)
      return
    }
    const tick = () => {
      const seconds = Math.ceil((floodRetryAt - Date.now()) / 1000)
      setFloodSecondsLeft(seconds > 0 ? seconds : null)
    }
    tick()
    const timer = window.setInterval(tick, 1000)
    return () => {
      window.clearInterval(timer)
    }
  }, [floodRetryAt])

  function send() {
    const validation = validateMessageText(value)
    if (!validation.ok) {
      // Bug 4 (T082): подсказка переполнения — тост (FR-025, слот
      // z-99, ~3 с, одно уведомление); остальные локальные ошибки
      // композера (пустой черновик) остаются инлайн role=alert.
      if (value.trim().length > MESSAGE_MAX_LENGTH) {
        showToast(validation.error)
      } else {
        setError(validation.error)
      }
      return
    }
    setError(null)
    setValue('')
    onSend(validation.text)
    puffSteam()
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    send()
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>) {
    // Enter отправляет (FR-021); Shift+Enter — перенос строки черновика
    // 004; набор композиции (IME) отправку не завершает.
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      send()
    }
  }

  /** «Пар» над композером при отправке — прототип puffSteam(), §7 JS. */
  function puffSteam() {
    const steam = steamRef.current
    if (steam === null) {
      return
    }
    for (let index = 0; index < PUFF_COUNT; index += 1) {
      const puff = document.createElement('i')
      puff.className = 'puff'
      puff.style.left = `${randomBetween(10, 70)}px`
      puff.style.setProperty('--dx', `${randomBetween(-20, 20)}px`)
      puff.style.animationDelay = `${index * 0.08}s`
      steam.appendChild(puff)
      puffTimersRef.current.push(
        window.setTimeout(() => {
          puff.remove()
        }, PUFF_LIFETIME_MS),
      )
    }
  }

  return (
    <form className="message-input chat-input" onSubmit={handleSubmit} noValidate>
      {error !== null && (
        <p className="message-input-error" role="alert">
          {error}
        </p>
      )}
      {floodSecondsLeft !== null && (
        <output className="message-input-retry">Повтор через {floodSecondsLeft} с</output>
      )}
      <div className="steam" ref={steamRef} aria-hidden="true" />
      <div className="input-frame">
        <label className="visually-hidden" htmlFor="message-composer">
          Текст сообщения
        </label>
        <textarea
          id="message-composer"
          className="msg-input"
          ref={fieldRef}
          value={value}
          placeholder={blocked ? BLOCKED_PLACEHOLDER : 'Сообщение…'}
          rows={1}
          disabled={disabled || blocked}
          onChange={(event) => {
            setValue(event.target.value)
            setError(null)
          }}
          onKeyDown={handleKeyDown}
        />
      </div>
      <button
        type="submit"
        className="send-btn"
        disabled={disabled || blocked}
        onMouseDown={handleSendMouseDown}
      >
        ОТПРАВИТЬ
      </button>
    </form>
  )
}
