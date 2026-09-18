import { useEffect, useRef, useState } from 'react'
import { clearReturnTo, exchangeSsoToken, readReturnTo } from '../../api/sso'
import { setTokenPair } from '../session'
import { problemMessage } from '../problem'

type CallbackState = { phase: 'exchanging' } | { phase: 'error'; message: string }

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

function messageFor(code: string): string {
  return SSO_ERROR_MESSAGES[code] ?? 'Sign-in failed. Please try again.'
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
      setState({ phase: 'error', message: messageFor(ssoError) })
      return
    }
    const code = params.get('code')
    if (code === null || code === '') {
      setState({ phase: 'error', message: 'The sign-in link is invalid: code is missing.' })
      return
    }
    exchangeSsoToken({ code })
      .then((pair) => {
        setTokenPair(pair)
        window.location.assign(redirectTarget())
      })
      .catch((err: unknown) => setState({ phase: 'error', message: problemMessage(err) }))
  }, [])

  return (
    <section>
      <h2>Signing in</h2>
      {state.phase === 'exchanging' && <output>Completing sign-in…</output>}
      {state.phase === 'error' && (
        <div>
          <p role="alert">{state.message}</p>
          <p>
            Return to the <a href="/login">login page</a> to try again.
          </p>
        </div>
      )}
    </section>
  )
}
