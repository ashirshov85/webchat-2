interface ProblemLike {
  title?: unknown
  status?: unknown
  detail?: unknown
  errors?: unknown
  retryAfterSec?: unknown
}

const FALLBACK_MESSAGE = 'Something went wrong. Please try again.'

function formatRetryAfter(seconds: number): string {
  const total = Math.max(1, Math.ceil(seconds))
  if (total < 60) {
    return `${total} s`
  }
  const minutes = Math.ceil(total / 60)
  if (minutes < 60) {
    return `${minutes} minute${minutes === 1 ? '' : 's'}`
  }
  const hours = Math.ceil(minutes / 60)
  return `${hours} hour${hours === 1 ? '' : 's'}`
}

function isFieldErrors(value: unknown): value is Record<string, string[]> {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  return Object.values(value).every(
    (codes) => Array.isArray(codes) && codes.every((code) => typeof code === 'string'),
  )
}

export function problemMessage(error: unknown): string {
  if (typeof error !== 'object' || error === null) {
    return FALLBACK_MESSAGE
  }
  const problem = error as ProblemLike
  if (typeof problem.title !== 'string' || typeof problem.status !== 'number') {
    return FALLBACK_MESSAGE
  }
  const parts: string[] = []
  if (isFieldErrors(problem.errors)) {
    for (const [field, codes] of Object.entries(problem.errors)) {
      parts.push(`${field}: ${codes.join(', ')}`)
    }
  }
  if (typeof problem.detail === 'string' && problem.detail !== '') {
    parts.push(problem.detail)
  }
  let message = parts.length > 0 ? parts.join(' — ') : problem.title
  if (typeof problem.retryAfterSec === 'number' && Number.isFinite(problem.retryAfterSec)) {
    message += `. Try again in ${formatRetryAfter(problem.retryAfterSec)}`
  }
  return message
}
