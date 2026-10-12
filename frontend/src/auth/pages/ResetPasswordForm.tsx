import { useState } from 'react'
import type { SubmitEvent } from 'react'
import { confirmPasswordReset } from '../../api/auth'
import { problemMessage } from '../problem'

interface ResetPasswordFormProps {
  readonly token: string
}

export function ResetPasswordForm({ token }: ResetPasswordFormProps) {
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  if (done) {
    return (
      <output>
        Пароль сброшен — все сессии отозваны. Теперь вы можете <a href="/login">войти</a> с новым
        паролем.
      </output>
    )
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    void submit()
  }

  async function submit() {
    setError(null)
    if (password !== confirmPassword) {
      setError('Пароли не совпадают. Ссылка остаётся действующей — исправьте и попробуйте снова.')
      return
    }
    setSubmitting(true)
    try {
      await confirmPasswordReset({ token, password, confirmPassword })
      setDone(true)
    } catch (err) {
      setError(problemMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      {error !== null && <p role="alert">{error}</p>}
      <div className="auth-field">
        <svg
          className="auth-field-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          aria-hidden="true"
        >
          <rect x="4" y="10" width="16" height="10" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" strokeLinecap="round" />
        </svg>
        <label className="auth-label-sr" htmlFor="reset-password">
          Новый пароль
        </label>
        <input
          id="reset-password"
          name="password"
          type="password"
          autoComplete="new-password"
          placeholder="Новый пароль"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
          minLength={8}
          maxLength={128}
        />
      </div>
      <div className="auth-field">
        <svg
          className="auth-field-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          aria-hidden="true"
        >
          <rect x="4" y="10" width="16" height="10" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" strokeLinecap="round" />
        </svg>
        <label className="auth-label-sr" htmlFor="reset-password-confirm">
          Подтвердите пароль
        </label>
        <input
          id="reset-password-confirm"
          name="confirmPassword"
          type="password"
          autoComplete="new-password"
          placeholder="Подтвердите пароль"
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          required
          minLength={8}
          maxLength={128}
        />
      </div>
      <button type="submit" className="auth-submit" disabled={submitting}>
        СБРОСИТЬ ПАРОЛЬ
      </button>
    </form>
  )
}
