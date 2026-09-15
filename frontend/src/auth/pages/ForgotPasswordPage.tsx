import { useState } from 'react'
import type { SubmitEvent } from 'react'
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
    <section>
      <h2>Forgot password</h2>
      {submittedEmail !== null && (
        <output>
          If {submittedEmail} belongs to a registered account, we sent a password reset link. It is
          valid for 1 hour.
        </output>
      )}
      <form onSubmit={handleSubmit} noValidate>
        {error !== null && <p role="alert">{error}</p>}
        <div>
          <label htmlFor="forgot-password-email">Email</label>
          <input
            id="forgot-password-email"
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </div>
        <button type="submit" disabled={submitting}>
          Send reset link
        </button>
      </form>
    </section>
  )
}
