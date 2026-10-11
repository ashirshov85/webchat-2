const CHAT_PATH = '/chat'

const AUTH_PATHS: ReadonlySet<string> = new Set([
  '/login',
  '/register',
  '/confirm-registration',
  '/set-password',
  '/forgot-password',
  '/reset-password',
  '/sso/callback',
])

const CONTROL_CHARS = /\p{Cc}/u

function pathnameOf(value: string): string {
  const boundary = value.search(/[?#]/)
  return boundary === -1 ? value : value.slice(0, boundary)
}

export function isSafeReturnPath(value: string | null | undefined): boolean {
  if (typeof value !== 'string' || value === '') {
    return false
  }
  if (!value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
    return false
  }
  if (CONTROL_CHARS.test(value)) {
    return false
  }
  const pathname = pathnameOf(value)
  if (pathname.split('/').some((segment) => segment === '..')) {
    return false
  }
  return !AUTH_PATHS.has(pathname)
}

export function loginRedirectTarget(returnTo: string | null | undefined): string {
  return isSafeReturnPath(returnTo) && typeof returnTo === 'string' ? returnTo : CHAT_PATH
}
