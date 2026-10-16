import { lazy, Suspense, useEffect, useState } from 'react'
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
import { PresenceSettingsPage } from './settings/pages/PresenceSettingsPage'
import { SecurityPage } from './settings/pages/SecurityPage'

/**
 * Экран конференции (009 US1, T025) — ленивый чанк: динамический
 * импорт выносит модуль `conferences/` (и тянущийся в него
 * `livekit-client`) из основного бандла (research §10); чанк
 * запрашивается только когда protected-роут действительно отрендерен.
 */
const ConferencePage = lazy(() => import('./conferences/pages/ConferencePage'))

/** `/conference/{id}` — единственный сегмент-id после префикса. */
const CONFERENCE_ROUTE = /^\/conference\/([^/]+)$/

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
  /**
   * `/conference/:id` (009 US1, T025): protected-роут с ленивым чанком
   * `conferences/pages/ConferencePage` — без сессии редирект на
   * `/login?returnTo=…` (чат-поверхность недоступна гостям, FR-010),
   * под auth грузится чанк и рендерится экран конференции; query
   * (`?mode=…` от кнопок заголовка T023 и карточки T024) в матчинге не
   * участвует — pathname его не содержит, mode читает страница (T034).
   */
  const conferenceRoute = CONFERENCE_ROUTE.exec(pathname)
  const conferenceId = conferenceRoute?.[1]
  if (conferenceRoute !== null && conferenceId !== undefined) {
    return (
      <ProtectedRoute pathname={pathname}>
        <Suspense fallback={<p>Загрузка конференции…</p>}>
          <ConferencePage conferenceId={conferenceId} />
        </Suspense>
      </ProtectedRoute>
    )
  }
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
    case '/settings/presence':
      return <PresenceSettingsPage />
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

  return <main>{renderRoute(pathname)}</main>
}
