import { useState } from 'react'
import type { FormEvent } from 'react'
import { register } from '../../api/auth'
import { problemMessage } from '../problem'

export function RegisterPage() {
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
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
    <section>
      <h2>Create your account</h2>
      {submittedEmail !== null && (
        <output>
          Check your email ({submittedEmail}): we sent a confirmation link. It is valid for 24
          hours.
        </output>
      )}
      <form onSubmit={handleSubmit} noValidate>
        {error !== null && <p role="alert">{error}</p>}
        <div>
          <label htmlFor="register-username">Username</label>
          <input
            id="register-username"
            name="username"
            type="text"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            required
          />
        </div>
        <div>
          <label htmlFor="register-email">Email</label>
          <input
            id="register-email"
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
          />
        </div>
        <button type="submit" disabled={submitting}>
          Register
        </button>
      </form>
    </section>
  )
}
