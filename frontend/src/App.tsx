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
import { useRealtime } from './chats/hooks/useRealtime'
import { usePresenceHeartbeat } from './presence/usePresence'
import { PresenceSettingsPage } from './settings/pages/PresenceSettingsPage'
import { SecurityPage } from './settings/pages/SecurityPage'

/**
 * Экран конференции (009 US1, T025) — ленивый чанк: динамический
 * импорт выносит модуль `conferences/` (и тянущийся в него
 * `livekit-client`) из основного бандла (research §10); чанк
 * запрашивается только когда protected-роут действительно отрендерен.
 */
const ConferencePage = lazy(() => import('./conferences/pages/ConferencePage'))

/**
 * Гостевая join-страница (009 US4, T050) — ленивый чанк того же
 * модуля `conferences/` (`livekit-client` остаётся вне main-bundle,
 * research §10); запрашивается при заходе по инвайт-ссылке.
 */
const GuestJoinPage = lazy(() => import('./conferences/pages/GuestJoinPage'))

/** `/conference/{id}` — единственный сегмент-id после префикса. */
const CONFERENCE_ROUTE = /^\/conference\/([^/]+)$/

/**
 * `/join/{inviteToken}` — публичный гостевой маршрут (FR-009):
 * подписанный nonce ES256 из инвайт-ссылки; base64url-сегменты
 * JWT безопасны в pathname и матчатся как есть (без декодирования).
 */
const GUEST_JOIN_ROUTE = /^\/join\/([^/]+)$/

/**
 * Presence keeper (009 T086): a null component holding the user's
 * presence alive on EVERY authenticated route — `useRealtime()`
 * retains the shared №18 stream (SSE registration) and
 * `usePresenceHeartbeat` renews it with №37 beats. Before T086 the
 * stream and the beats were mounted ONLY by MessengerPage, so the
 * full-page-load jump into a conference
 * (`window.location.assign('/conference/{id}…')`) unloaded the
 * messenger, the server dropped the registration, and the 007
 * scheduler (TTL 90 s + 45 s hysteresis) flipped the caller «офлайн»
 * for every observer for the whole call. Mounted INSIDE
 * ProtectedRoute next to children, presence gains the semantics
 * «an authorized application tab is open» (`/`, `/chat`,
 * `/conference/:id`): public surfaces are excluded automatically —
 * `/join/:inviteToken` (guest without userId, mounted without
 * ProtectedRoute, T050) and the auth pages never open the stream, so
 * no 401 cycles. The full-page-load gap itself is covered by the 007
 * offq hysteresis (45 s): the new tab registers before the deadline.
 * Imports stay chats/presence-only (both already in the main bundle)
 * — the conferences module (livekit-client) remains a lazy chunk
 * (T002/T025). Known limitation (candidate for 007): `/settings/*`
 * routes sit outside ProtectedRoute and are NOT covered here.
 */
function PresenceKeeper() {
  const stream = useRealtime()
  usePresenceHeartbeat(stream)
  return null
}

/**
 * Protected route (T025): a visitor without a refresh token (no
 * session) is redirected to the login page; `returnTo` lets the SSO
 * flow land the user back on the protected path after sign-in. While
 * the redirect is pending nothing is rendered — the messenger never
 * flashes for unauthenticated visitors. Every authenticated route
 * also mounts the presence keeper (T086) next to its children.
 */
function ProtectedRoute({
  children,
  pathname,
}: {
  readonly children: ReactNode
  readonly pathname: string
}) {
  const authenticated = !isSessionExpired()
  useEffect(() => {
    if (!authenticated) {
      window.location.assign(`/login?returnTo=${encodeURIComponent(pathname)}`)
    }
  }, [authenticated, pathname])
  if (!authenticated) {
    return null
  }
  return (
    <>
      <PresenceKeeper />
      {children}
    </>
  )
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
  /**
   * `/join/:inviteToken` (009 US4, T050): ПУБЛИЧНЫЙ роут — без
   * аутентификации (внешний гость без аккаунта, FR-009) и без
   * ProtectedRoute: ленивый чанк грузится сразу, перенаправления на
   * login нет (returnTo гостю не нужен). Гость видит только экран гостевого входа
   * и экран конференции — чат-поверхность исключена (FR-010): `/`, `/chat`
   * и `/conference/:id` остаются protected (перенаправляют без сессии),
   * API чатов аутентифицированы на сервере (SecurityConfig 009 T047).
   * Аутентифицированный посетитель ссылки тоже входит гостем (Q14=A) —
   * роут одинаково открыт для обоих, гостизна решается токеном №55.
   */
  const guestJoinRoute = GUEST_JOIN_ROUTE.exec(pathname)
  const inviteToken = guestJoinRoute?.[1]
  if (guestJoinRoute !== null && inviteToken !== undefined) {
    return (
      <Suspense fallback={<p>Загрузка приглашения…</p>}>
        <GuestJoinPage inviteToken={inviteToken} />
      </Suspense>
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
