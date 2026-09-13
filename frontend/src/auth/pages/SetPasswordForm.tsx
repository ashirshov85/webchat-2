import { useState } from 'react'
import type { FormEvent } from 'react'
import { setPassword } from '../../api/auth'
import { problemMessage } from '../problem'

interface SetPasswordFormProps {
  setupToken: string
}

export function SetPasswordForm({ setupToken }: SetPasswordFormProps) {
  const [password, setPasswordValue] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  if (done) {
    return (
      <p role="status">
        Password set — registration complete. You can now <a href="/login">log in</a>.
      </p>
    )
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
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
      await setPassword({ setupToken, password, confirmPassword })
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
        <label htmlFor="set-password">Password</label>
        <input
          id="set-password"
          name="password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(event) => setPasswordValue(event.target.value)}
          required
          minLength={8}
          maxLength={128}
        />
      </div>
      <div>
        <label htmlFor="set-password-confirm">Confirm password</label>
        <input
          id="set-password-confirm"
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
        Set password
      </button>
    </form>
  )
}
