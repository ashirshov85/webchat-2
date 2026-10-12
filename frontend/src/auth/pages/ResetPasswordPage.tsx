import './auth-theme.css'
import { AuthBadge } from './AuthBadge'
import { ResetPasswordForm } from './ResetPasswordForm'

export function ResetPasswordPage() {
  const token = new URLSearchParams(window.location.search).get('token')
  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-rivet auth-rivet-tl" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-tr" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-bl" aria-hidden="true" />
        <div className="auth-rivet auth-rivet-br" aria-hidden="true" />

        <AuthBadge />

        <h1 className="auth-brand-title">Aethergram</h1>
        <div className="auth-brand-sub">
          {'CONNECT'}
          <span className="auth-brand-sub-sep">·</span>
          {'CHAT'}
          <span className="auth-brand-sub-sep">·</span>
          {'EXPLORE'}
        </div>
        <h2 className="auth-form-title">Сброс пароля</h2>

        {token === null || token === '' ? (
          <>
            <p role="alert">Ссылка недействительна: отсутствует токен.</p>
            <div className="auth-footer">
              Запросите новую на странице <a href="/forgot-password">восстановления пароля</a>.
            </div>
          </>
        ) : (
          <>
            <p>Придумайте новый пароль. Ссылка действует 1 час.</p>
            <ResetPasswordForm token={token} />
          </>
        )}

        <div className="auth-footer">
          <a href="/login">Вернуться ко входу</a>
        </div>
      </div>
    </div>
  )
}
