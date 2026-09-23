import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { ConfirmRegistrationPage } from './auth/pages/ConfirmRegistrationPage'
import { ForgotPasswordPage } from './auth/pages/ForgotPasswordPage'
import { LoginPage } from './auth/pages/LoginPage'
import { RegisterPage } from './auth/pages/RegisterPage'
import { ResetPasswordPage } from './auth/pages/ResetPasswordPage'
import { SetPasswordPage } from './auth/pages/SetPasswordPage'
import { SsoCallbackPage } from './auth/pages/SsoCallbackPage'
import { isSessionExpired } from './auth/session'
import { MessengerPage } from './chats/pages/MessengerPage'
import { SecurityPage } from './settings/pages/SecurityPage'

/**
 * Protected route (T025): a visitor without a refresh token (no
 * session) is redirected to the login page; `returnTo` lets the SSO
 * flow land the user back on the protected path after sign-in. While
 * the redirect is pending nothing is rendered — the messenger never
 * flashes for unauthenticated visitors.
 */
function ProtectedRoute({ children, pathname }: { children: ReactNode; pathname: string }) {
  const authenticated = !isSessionExpired()
  useEffect(() => {
    if (!authenticated) {
      window.location.assign(`/login?returnTo=${encodeURIComponent(pathname)}`)
    }
  }, [authenticated, pathname])
  if (!authenticated) {
    return null
  }
  return children
}

function renderRoute(pathname: string): ReactNode {
  switch (pathname) {
    case '/':
    case '/chat':
      return (
        <ProtectedRoute pathname={pathname}>
          <MessengerPage />
        </ProtectedRoute>
      )
    case '/register':
      return <RegisterPage />
    case '/confirm-registration':
      return <ConfirmRegistrationPage />
    case '/set-password':
      return <SetPasswordPage />
    case '/login':
      return <LoginPage />
    case '/forgot-password':
      return <ForgotPasswordPage />
    case '/reset-password':
      return <ResetPasswordPage />
    case '/sso/callback':
      return <SsoCallbackPage />
    case '/settings/security':
      return <SecurityPage />
    default:
      return (
        <p>
          Project foundation skeleton. <a href="/register">Create an account</a> or{' '}
          <a href="/login">log in</a>.
        </p>
      )
  }
}

export function App() {
  const [pathname, setPathname] = useState(() => window.location.pathname)

  useEffect(() => {
    const sync = () => setPathname(window.location.pathname)
    window.addEventListener('popstate', sync)
    return () => window.removeEventListener('popstate', sync)
  }, [])

  const hideHeading = pathname === '/login' || pathname === '/register'

  return (
    <main>
      {hideHeading ? null : <h1>WebChat</h1>}
      {renderRoute(pathname)}
    </main>
  )
}
