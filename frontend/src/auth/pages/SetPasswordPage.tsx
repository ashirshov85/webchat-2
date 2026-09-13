import { SetPasswordForm } from './SetPasswordForm'

export function SetPasswordPage() {
  const token = new URLSearchParams(window.location.search).get('token')
  return (
    <section>
      <h2>Set password</h2>
      {token === null || token === '' ? (
        <div>
          <p role="alert">The link is invalid: token is missing.</p>
          <p>
            Request a new email on the <a href="/register">registration page</a>.
          </p>
        </div>
      ) : (
        <>
          <p>Choose a password to complete your registration. The link is valid for 1 hour.</p>
          <SetPasswordForm setupToken={token} />
        </>
      )}
    </section>
  )
}
