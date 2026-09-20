import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { ConfirmRegistrationPage } from './auth/pages/ConfirmRegistrationPage'
import { ForgotPasswordPage } from './auth/pages/ForgotPasswordPage'
import { LoginPage } from './auth/pages/LoginPage'
import { RegisterPage } from './auth/pages/RegisterPage'
import { ResetPasswordPage } from './auth/pages/ResetPasswordPage'
import { SetPasswordPage } from './auth/pages/SetPasswordPage'
import { SsoCallbackPage } from './auth/pages/SsoCallbackPage'
import { SecurityPage } from './settings/pages/SecurityPage'

function renderRoute(pathname: string): ReactNode {
  switch (pathname) {
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
