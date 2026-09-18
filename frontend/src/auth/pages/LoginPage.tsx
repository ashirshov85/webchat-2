import { useEffect, useState } from 'react'
import type { SubmitEvent } from 'react'
import { login } from '../../api/auth'
import type { LoginResponse } from '../../api/auth'
import { authorizeSso, listSsoProviders, saveReturnTo } from '../../api/sso'
import type { SsoAuthorizeRequest, SsoProvider } from '../../api/sso'
import { setTokenPair } from '../session'
import { problemMessage } from '../problem'

function currentReturnTo(): string | null {
  const value = new URLSearchParams(window.location.search).get('returnTo')
  return value !== null && value !== '' ? value : null
}

export function LoginPage() {
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [user, setUser] = useState<LoginResponse['user'] | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [providers, setProviders] = useState<SsoProvider[]>([])
  const [ssoError, setSsoError] = useState<string | null>(null)
  const [ssoStartingId, setSsoStartingId] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    listSsoProviders()
      .then((response) => {
        if (!cancelled) setProviders(response.providers)
      })
      .catch(() => {
        if (!cancelled) setProviders([])
      })
    return () => {
      cancelled = true
    }
  }, [])

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

  async function startSsoLogin(provider: SsoProvider) {
    setSsoError(null)
    setSsoStartingId(provider.id)
    try {
      const returnTo = currentReturnTo()
      const request: SsoAuthorizeRequest = { providerId: provider.id }
      if (returnTo !== null) request.returnTo = returnTo
      const { authorizationUrl } = await authorizeSso(request)
      if (returnTo !== null) saveReturnTo(returnTo)
      window.location.assign(authorizationUrl)
    } catch (err) {
      setSsoError(problemMessage(err))
      setSsoStartingId(null)
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
      {providers.length > 0 && (
        <section aria-label="Single sign-on providers">
          {ssoError !== null && <p role="alert">{ssoError}</p>}
          <p>Or continue with</p>
          {providers.map((provider) => (
            <button
              key={provider.id}
              type="button"
              disabled={ssoStartingId !== null}
              onClick={() => void startSsoLogin(provider)}
            >
              {provider.displayName}
            </button>
          ))}
        </section>
      )}
    </section>
  )
}
