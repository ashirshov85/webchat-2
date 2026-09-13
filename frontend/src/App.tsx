import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { ConfirmRegistrationPage } from './auth/pages/ConfirmRegistrationPage'
import { RegisterPage } from './auth/pages/RegisterPage'
import { SetPasswordPage } from './auth/pages/SetPasswordPage'

function renderRoute(pathname: string): ReactNode {
  switch (pathname) {
    case '/register':
      return <RegisterPage />
    case '/confirm-registration':
      return <ConfirmRegistrationPage />
    case '/set-password':
      return <SetPasswordPage />
    default:
      return (
        <p>
          Project foundation skeleton. <a href="/register">Create an account</a>.
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

  return (
    <main>
      <h1>WebChat</h1>
      {renderRoute(pathname)}
    </main>
  )
}
