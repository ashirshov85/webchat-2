export interface NewPasswordContext {
  readonly username: string
  readonly email: string
  readonly confirm: string
}

const MIN_LENGTH = 8

const MAX_LENGTH = 128

function normalize(value: string): string {
  return value.trim().toLowerCase()
}

export function validateNewPassword(password: string, context: NewPasswordContext): string | null {
  if (password.trim() === '') {
    return 'Password must not be empty or consist only of whitespace.'
  }
  if (password.length < MIN_LENGTH) {
    return `Password must be at least ${MIN_LENGTH} characters long.`
  }
  if (password.length > MAX_LENGTH) {
    return `Password must be at most ${MAX_LENGTH} characters long.`
  }
  if (password !== context.confirm) {
    return 'Passwords do not match.'
  }
  const normalized = normalize(password)
  if (normalized === normalize(context.username)) {
    return 'Password must be different from your username.'
  }
  if (normalized === normalize(context.email)) {
    return 'Password must be different from your email.'
  }
  return null
}
