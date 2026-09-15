import { ResetPasswordForm } from './ResetPasswordForm'

export function ResetPasswordPage() {
  const token = new URLSearchParams(window.location.search).get('token')
  return (
    <section>
      <h2>Reset password</h2>
      {token === null || token === '' ? (
        <div>
          <p role="alert">The link is invalid: token is missing.</p>
          <p>
            Request a new one on the <a href="/forgot-password">forgot password page</a>.
          </p>
        </div>
      ) : (
        <>
          <p>Choose a new password. The link is valid for 1 hour.</p>
          <ResetPasswordForm token={token} />
        </>
      )}
    </section>
  )
}
