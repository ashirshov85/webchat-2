/**
 * Validation error banner (feature 004, T026): renders API problem
 * details (HTTP 400 validation rejections such as text_too_long, and
 * other server errors) inside the dialog window in a readable form
 * (problemMessage, feature 002 conventions). Renders nothing while
 * `error` is null/undefined; `onDismiss` optionally adds a close
 * control for recoverable errors.
 */
import { problemMessage } from '../../auth/problem'

export interface ErrorBannerProps {
  /** ApiProblem-shaped error (api/auth toApiProblem) or any thrown value. */
  readonly error: unknown
  readonly onDismiss?: () => void
}

export function ErrorBanner({ error, onDismiss }: ErrorBannerProps) {
  if (error === null || error === undefined) {
    return null
  }
  return (
    <div className="error-banner" role="alert">
      <p className="error-banner-text">{problemMessage(error)}</p>
      {onDismiss !== undefined && (
        <button
          type="button"
          className="error-banner-dismiss"
          aria-label="Закрыть ошибку"
          onClick={onDismiss}
        >
          ×
        </button>
      )}
    </div>
  )
}
