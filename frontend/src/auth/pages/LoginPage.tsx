import { useState } from 'react'
import type { SubmitEvent } from 'react'
import { login } from '../../api/auth'
import type { LoginResponse } from '../../api/auth'
import { setTokenPair } from '../session'
import { problemMessage } from '../problem'

export function LoginPage() {
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [user, setUser] = useState<LoginResponse['user'] | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    void submit()
  }

  async function submit() {
    setError(null)
    setSubmitting(true)
    try {
      const pair = await login({ identifier, password })
      setTokenPair(pair)
      setUser(pair.user)
    } catch (err) {
      setError(problemMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section>
      <h2>Log in</h2>
      {user !== null && <output>Welcome, {user.username}!</output>}
      <form onSubmit={handleSubmit} noValidate>
        {error !== null && <p role="alert">{error}</p>}
        <div>
          <label htmlFor="login-identifier">Identifier</label>
          <input
            id="login-identifier"
            name="identifier"
            type="text"
            autoComplete="username"
            value={identifier}
            onChange={(event) => setIdentifier(event.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="login-password">Password</label>
          <input
            id="login-password"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </div>
        <button type="submit" disabled={submitting}>
          Login
        </button>
      </form>
    </section>
  )
}
