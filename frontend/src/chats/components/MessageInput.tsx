/**
 * Message composer (feature 004, T026): client-side pre-validation of
 * FR-003 (see chats/validation.ts) — `onSend` fires only with the
 * normalized valid text, so an invalid draft cannot reach the chat or
 * the outbox; instead a local understandable error is shown (US1-4).
 * The server-side 400 (text_blank / text_too_long) remains the
 * authoritative protection.
 */
import { useState } from 'react'
import type { SubmitEvent } from 'react'
import { validateMessageText } from '../validation'

export interface MessageInputProps {
  /** Receives the normalized (trimmed) valid text only. */
  readonly onSend: (text: string) => void
  readonly disabled?: boolean
}

export function MessageInput({ onSend, disabled = false }: MessageInputProps) {
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const validation = validateMessageText(value)
    if (!validation.ok) {
      setError(validation.error)
      return
    }
    setError(null)
    setValue('')
    onSend(validation.text)
  }

  return (
    <form className="message-input" onSubmit={handleSubmit} noValidate>
      {error !== null && (
        <p className="message-input-error" role="alert">
          {error}
        </p>
      )}
      <label className="visually-hidden" htmlFor="message-composer">
        Текст сообщения
      </label>
      <textarea
        id="message-composer"
        value={value}
        placeholder="Написать сообщение…"
        rows={2}
        disabled={disabled}
        onChange={(event) => {
          setValue(event.target.value)
          setError(null)
        }}
      />
      <button type="submit" disabled={disabled}>
        Отправить
      </button>
    </form>
  )
}
