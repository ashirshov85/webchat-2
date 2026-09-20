import { useState } from 'react'
import type { SubmitEvent } from 'react'
import './auth-theme.css'
import { register } from '../../api/auth'
import { problemMessage } from '../problem'

export function RegisterPage() {
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    void submit()
  }

  async function submit() {
    setError(null)
    setSubmitting(true)
    try {
      await register({ username, email })
      setSubmittedEmail(email)
    } catch (err) {
      setError(problemMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-rivet auth-rivet-tl" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-tr" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-bl" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-br" aria-hidden="true" />

        <div className="auth-badge" aria-hidden="true">
          <svg className="auth-badge-cog" viewBox="0 0 100 100" fill="none">
            <circle cx="50" cy="50" r="30" stroke="#c9a24a" strokeWidth="3" opacity="0.5" />
            <g fill="#c9a24a" opacity="0.5">
              <rect x="46" y="2" width="8" height="14" rx="2" />
              <rect x="46" y="84" width="8" height="14" rx="2" />
              <rect x="2" y="46" width="14" height="8" rx="2" />
              <rect x="84" y="46" width="14" height="8" rx="2" />
              <rect x="14" y="14" width="10" height="10" rx="2" transform="rotate(45 19 19)" />
              <rect x="76" y="14" width="10" height="10" rx="2" transform="rotate(45 81 19)" />
              <rect x="14" y="76" width="10" height="10" rx="2" transform="rotate(45 19 81)" />
              <rect x="76" y="76" width="10" height="10" rx="2" transform="rotate(45 81 81)" />
            </g>
          </svg>
          <svg viewBox="0 0 24 24" fill="none" stroke="#e8c877" strokeWidth="1.6">
            <path
              d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </div>

        <h1 className="auth-brand-title">STEAMCHAT</h1>
        <div className="auth-brand-sub">
          CONNECT<span className="auth-brand-sub-sep">·</span>CHAT
          <span className="auth-brand-sub-sep">·</span>EXPLORE
        </div>
        <h2 className="auth-form-title">Создание аккаунта</h2>

        {submittedEmail !== null && (
          <output>
            Проверьте свою почту ({submittedEmail}): мы отправили ссылку для подтверждения, она
            действует 24 часа.
          </output>
        )}
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
              <circle cx="12" cy="8" r="4" />
              <path d="M4 20c0-4 3.6-6 8-6s8 2 8 6" strokeLinecap="round" />
            </svg>
            <label className="auth-label-sr" htmlFor="register-username">
              Имя пользователя
            </label>
            <input
              id="register-username"
              name="username"
              type="text"
              autoComplete="username"
              placeholder="Имя пользователя"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              required
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
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="m3 7 9 6 9-6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <label className="auth-label-sr" htmlFor="register-email">
              Email
            </label>
            <input
              id="register-email"
              name="email"
              type="email"
              autoComplete="email"
              placeholder="Email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
          </div>
          <button type="submit" className="auth-submit" disabled={submitting}>
            ЗАРЕГИСТРИРОВАТЬСЯ
          </button>
        </form>

        <div className="auth-footer">
          Уже есть аккаунт? <a href="/login">Войти</a>
        </div>
      </div>
    </div>
  )
}
