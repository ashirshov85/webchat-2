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
        Password reset — all sessions were revoked. You can now <a href="/login">log in</a> with the
        new password.
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
      setError('Passwords do not match. The link stays valid — correct them and try again.')
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
      <div>
        <label htmlFor="reset-password">Password</label>
        <input
          id="reset-password"
          name="password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
          minLength={8}
          maxLength={128}
        />
      </div>
      <div>
        <label htmlFor="reset-password-confirm">Confirm password</label>
        <input
          id="reset-password-confirm"
          name="confirmPassword"
          type="password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(event) => setConfirmPassword(event.target.value)}
          required
          minLength={8}
          maxLength={128}
        />
      </div>
      <button type="submit" disabled={submitting}>
        Reset password
      </button>
    </form>
  )
}
