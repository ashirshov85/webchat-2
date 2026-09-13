interface ProblemLike {
  title?: unknown
  status?: unknown
  detail?: unknown
  errors?: unknown
  retryAfterSec?: unknown
}

const FALLBACK_MESSAGE = 'Something went wrong. Please try again.'

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
  if (typeof problem.retryAfterSec === 'number') {
    message += `. Try again in ${Math.ceil(problem.retryAfterSec)} s`
  }
  return message
}
