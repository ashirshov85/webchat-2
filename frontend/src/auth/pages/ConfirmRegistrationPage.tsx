import { useEffect, useRef, useState } from 'react'
import { confirmRegistration } from '../../api/auth'
import { problemMessage } from '../problem'
import { SetPasswordForm } from './SetPasswordForm'

type ConfirmationState =
  | { phase: 'confirming' }
  | { phase: 'error'; message: string }
  | { phase: 'confirmed'; setupToken: string }

export function ConfirmRegistrationPage() {
  const [state, setState] = useState<ConfirmationState>({ phase: 'confirming' })
  const startedRef = useRef(false)

  useEffect(() => {
    if (startedRef.current) {
      return
    }
    startedRef.current = true
    const token = new URLSearchParams(window.location.search).get('token')
    if (token === null || token === '') {
      setState({ phase: 'error', message: 'The confirmation link is invalid: token is missing.' })
      return
    }
    confirmRegistration({ token })
      .then((response) => setState({ phase: 'confirmed', setupToken: response.setupToken }))
      .catch((err: unknown) => setState({ phase: 'error', message: problemMessage(err) }))
  }, [])

  return (
    <section>
      <h2>Confirm registration</h2>
      {state.phase === 'confirming' && <p>Confirming your email…</p>}
      {state.phase === 'error' && (
        <div>
          <p role="alert">{state.message}</p>
          <p>
            Request a new confirmation email on the <a href="/register">registration page</a>.
          </p>
        </div>
      )}
      {state.phase === 'confirmed' && (
        <>
          <output>Email confirmed. Choose a password to finish creating your account.</output>
          <SetPasswordForm setupToken={state.setupToken} />
        </>
      )}
    </section>
  )
}
