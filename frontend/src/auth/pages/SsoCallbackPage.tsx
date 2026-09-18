import { useEffect, useRef, useState } from 'react'
import { clearReturnTo, exchangeSsoToken, readReturnTo } from '../../api/sso'
import { setTokenPair } from '../session'
import { problemMessage } from '../problem'

type RecoveryAdvice = 'password-login' | 'complete-registration' | 'retry'

type CallbackState =
  { phase: 'exchanging' } | { phase: 'error'; message: string; recovery: RecoveryAdvice }

const CHAT_PATH = '/chat'

const SSO_ERROR_MESSAGES: Record<string, string> = {
  invalid_state: 'The sign-in session is invalid or has expired. Please start sign-in again.',
  provider_disabled: 'This sign-in provider is currently disabled.',
  provider_error: 'The sign-in provider failed. Please try again later.',
  email_not_verified: 'The provider did not confirm your email address.',
  email_conflict:
    'An account with this email already exists. Sign in with your password and link the provider in settings.',
  registration_incomplete:
    'Your registration is not finished yet. Complete it via the link from the confirmation email.',
  identity_taken: 'This identity is already linked to another account.',
  rejected: 'Sign-in was rejected.',
}

const FIRST_LOGIN_ERROR_CODES: ReadonlySet<string> = new Set([
  'email_not_verified',
  'email_conflict',
])

function messageFor(code: string): string {
  return SSO_ERROR_MESSAGES[code] ?? 'Sign-in failed. Please try again.'
}

function recoveryAdviceFor(code: string | null): RecoveryAdvice {
  if (code !== null && FIRST_LOGIN_ERROR_CODES.has(code)) {
    return 'password-login'
  }
  if (code === 'registration_incomplete') {
    return 'complete-registration'
  }
  return 'retry'
}

function isSafeReturnPath(value: string): boolean {
  return value.startsWith('/') && !value.startsWith('//')
}

function redirectTarget(): string {
  const returnTo = readReturnTo()
  clearReturnTo()
  if (returnTo !== null && returnTo !== '' && isSafeReturnPath(returnTo)) {
    return returnTo
  }
  return CHAT_PATH
}

export function SsoCallbackPage() {
  const [state, setState] = useState<CallbackState>({ phase: 'exchanging' })
  const startedRef = useRef(false)

  useEffect(() => {
    if (startedRef.current) {
      return
    }
    startedRef.current = true
    const params = new URLSearchParams(window.location.search)
    const ssoError = params.get('sso_error')
    if (ssoError !== null && ssoError !== '') {
      setState({
        phase: 'error',
        message: messageFor(ssoError),
        recovery: recoveryAdviceFor(ssoError),
      })
      return
    }
    const code = params.get('code')
    if (code === null || code === '') {
      setState({
        phase: 'error',
        message: 'The sign-in link is invalid: code is missing.',
        recovery: 'retry',
      })
      return
    }
    exchangeSsoToken({ code })
      .then((pair) => {
        setTokenPair(pair)
        window.location.assign(redirectTarget())
      })
      .catch((err: unknown) =>
        setState({ phase: 'error', message: problemMessage(err), recovery: 'retry' }),
      )
  }, [])

  return (
    <section>
      <h2>Signing in</h2>
      {state.phase === 'exchanging' && <output>Completing sign-in…</output>}
      {state.phase === 'error' && (
        <div>
          <p role="alert">{state.message}</p>
          {state.recovery === 'password-login' && (
            <p>
              <a href="/login">Sign in with your password</a> or{' '}
              <a href="/register">create an account</a>, then link this provider in your account
              settings to use it for sign-in.
            </p>
          )}
          {state.recovery === 'complete-registration' && (
            <p>
              Check your inbox and follow the link from the confirmation email we sent you, then try
              signing in again.
            </p>
          )}
          {state.recovery === 'retry' && (
            <p>
              Return to the <a href="/login">login page</a> to try again.
            </p>
          )}
        </div>
      )}
    </section>
  )
}
