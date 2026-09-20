import { useEffect, useState } from 'react'
import type { SubmitEvent } from 'react'
import '@fontsource/cinzel/700.css'
import '@fontsource/cinzel/800.css'
import '@fontsource/eb-garamond/400.css'
import './auth-theme.css'
import { AuthBadge } from './AuthBadge'
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

function ProviderIcon({ providerId }: { readonly providerId: string }) {
  switch (providerId) {
    case 'google':
      return (
        <svg viewBox="0 0 48 48" aria-hidden="true">
          <path
            fill="#FFC107"
            d="M43.6 20.5H42V20H24v8h11.3C33.9 32.1 29.4 35 24 35c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.9 1.2 8 3.1l5.7-5.7C34.6 4.9 29.6 3 24 3 12.4 3 3 12.4 3 24s9.4 21 21 21 21-9.4 21-21c0-1.2-.1-2.4-.4-3.5z"
          />
          <path
            fill="#FF3D00"
            d="M6.3 14.7l6.6 4.8C14.6 15.4 18.9 12 24 12c3.1 0 5.9 1.2 8 3.1l5.7-5.7C34.6 6.9 29.6 5 24 5 16.3 5 9.6 9.2 6.3 14.7z"
          />
          <path
            fill="#4CAF50"
            d="M24 43c5.4 0 10.3-1.8 14-5.9l-6.5-5.5C29.5 33.6 26.9 35 24 35c-5.4 0-9.9-2.9-11.3-7.9l-6.6 5.1C9.6 38.8 16.3 43 24 43z"
          />
          <path
            fill="#1976D2"
            d="M43.6 20.5H24v8h11.3c-1 3-3.2 5.3-6 6.6l6.5 5.5c3.8-3.5 6.6-8.7 6.6-15.6 0-1.2-.1-2.4-.8-4.5z"
          />
        </svg>
      )
    case 'github':
      return (
        <svg viewBox="0 0 24 24" fill="#e8dcc0" aria-hidden="true">
          <path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2.2c-3.3.7-4-1.4-4-1.4-.5-1.4-1.3-1.8-1.3-1.8-1.1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1 1.8 2.7 1.3 3.4 1 .1-.7.4-1.3.7-1.6-2.6-.3-5.3-1.3-5.3-5.7 0-1.3.4-2.3 1.2-3.1-.1-.3-.5-1.5.1-3.1 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0c2.3-1.5 3.3-1.2 3.3-1.2.6 1.6.2 2.8.1 3.1.8.8 1.2 1.9 1.2 3.1 0 4.4-2.7 5.4-5.3 5.7.4.4.8 1.1.8 2.2v3.3c0 .3.2.7.8.6A12 12 0 0 0 12 .3" />
        </svg>
      )
    case 'yandex':
      return (
        <svg viewBox="0 0 23 23" aria-hidden="true">
          <rect x="1" y="1" width="21" height="21" rx="4" fill="#FC3F1D" />
          <text
            x="11.5"
            y="16.2"
            textAnchor="middle"
            fill="#fff"
            fontSize="13"
            fontWeight="700"
            fontFamily="Arial, sans-serif"
          >
            Я
          </text>
        </svg>
      )
    case 'vk':
      return (
        <svg viewBox="0 0 23 23" aria-hidden="true">
          <rect x="1" y="1" width="21" height="21" rx="4" fill="#0077FF" />
          <text
            x="11.5"
            y="15.6"
            textAnchor="middle"
            fill="#fff"
            fontSize="9"
            fontWeight="700"
            fontFamily="Arial, sans-serif"
          >
            VK
          </text>
        </svg>
      )
    case 'dex':
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="#c9a24a" strokeWidth="1.8" aria-hidden="true">
          <path d="M12 2.5 20.2 7v10L12 21.5 3.8 17V7L12 2.5z" strokeLinejoin="round" />
          <path d="M9.5 8.5v7h3a3.5 3.5 0 0 0 0-7h-3z" strokeLinejoin="round" />
        </svg>
      )
    default:
      return (
        <svg viewBox="0 0 24 24" fill="none" stroke="#c9a24a" strokeWidth="1.8" aria-hidden="true">
          <circle cx="8" cy="15" r="4" />
          <path
            d="M10.9 12.1 20 3m-4.5 1.5 3 3m-5 0.5 2 2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      )
  }
}

export function LoginPage() {
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
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
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-rivet auth-rivet-tl" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-tr" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-bl" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-br" aria-hidden="true" />

        <AuthBadge />

        <h1 className="auth-brand-title">STEAMCHAT</h1>
        <div className="auth-brand-sub">
          {'CONNECT'}
          <span className="auth-brand-sub-sep">·</span>
          {'CHAT'}
          <span className="auth-brand-sub-sep">·</span>
          {'EXPLORE'}
        </div>

        {user !== null && <output>Welcome, {user.username}!</output>}
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
            <label className="auth-label-sr" htmlFor="login-identifier">
              Email или имя пользователя
            </label>
            <input
              id="login-identifier"
              name="identifier"
              type="text"
              autoComplete="username"
              placeholder="Email или имя пользователя"
              value={identifier}
              onChange={(event) => setIdentifier(event.target.value)}
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
              <rect x="4" y="10" width="16" height="10" rx="2" />
              <path d="M8 10V7a4 4 0 0 1 8 0v3" strokeLinecap="round" />
            </svg>
            <label className="auth-label-sr" htmlFor="login-password">
              Пароль
            </label>
            <input
              id="login-password"
              name="password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="current-password"
              placeholder="Пароль"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
            <button
              type="button"
              className="auth-toggle-eye"
              aria-label={showPassword ? 'Скрыть пароль' : 'Показать пароль'}
              onClick={() => setShowPassword((visible) => !visible)}
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                aria-hidden="true"
              >
                <path
                  d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <circle cx="12" cy="12" r="3" />
              </svg>
            </button>
          </div>
          <button type="submit" className="auth-submit" disabled={submitting}>
            ВОЙТИ
          </button>
        </form>

        {providers.length > 0 && (
          <>
            <div className="auth-divider">
              <span>ИЛИ</span>
            </div>
            <section aria-label="Single sign-on providers">
              {ssoError !== null && <p role="alert">{ssoError}</p>}
              {providers.map((provider) => (
                <button
                  key={provider.id}
                  type="button"
                  className="auth-sso-button"
                  disabled={ssoStartingId !== null}
                  onClick={() => void startSsoLogin(provider)}
                >
                  <ProviderIcon providerId={provider.id} />
                  Продолжить с {provider.displayName}
                </button>
              ))}
            </section>
          </>
        )}

        <div className="auth-footer">
          Нет аккаунта? <a href="/register">Зарегистрироваться</a>
        </div>
      </div>
    </div>
  )
}
