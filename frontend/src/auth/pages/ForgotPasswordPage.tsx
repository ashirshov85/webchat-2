import { useState } from 'react'
import type { SubmitEvent } from 'react'
import './auth-theme.css'
import { AuthBadge } from './AuthBadge'
import { requestPasswordReset } from '../../api/auth'
import { problemMessage } from '../problem'

export function ForgotPasswordPage() {
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
      await requestPasswordReset({ email })
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

        <AuthBadge />

        <h1 className="auth-brand-title">Aethergram</h1>
        <div className="auth-brand-sub">
          {'CONNECT'}
          <span className="auth-brand-sub-sep">·</span>
          {'CHAT'}
          <span className="auth-brand-sub-sep">·</span>
          {'EXPLORE'}
        </div>
        <h2 className="auth-form-title">Forgot password</h2>

        {submittedEmail !== null && (
          <output>
            If {submittedEmail} belongs to a registered account, we sent a password reset link. It
            is valid for 1 hour.
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
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="m3 7 9 6 9-6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <label className="auth-label-sr" htmlFor="forgot-password-email">
              Email
            </label>
            <input
              id="forgot-password-email"
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
            Send reset link
          </button>
        </form>

        <div className="auth-footer">
          <a href="/login">Back to login</a>
        </div>
      </div>
    </div>
  )
}
