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

/**
 * Клиентская политика нового пароля (008b T020; русский текст — T046:
 * единственный потребитель — модаль «Смена пароля», сообщения рендерятся
 * в .modal-err дословно).
 */
export function validateNewPassword(password: string, context: NewPasswordContext): string | null {
  if (password.trim() === '') {
    return 'Пароль не должен быть пустым или состоять только из пробелов'
  }
  if (password.length < MIN_LENGTH) {
    return `Пароль должен быть не короче ${MIN_LENGTH} символов`
  }
  if (password.length > MAX_LENGTH) {
    return `Пароль должен быть не длиннее ${MAX_LENGTH} символов`
  }
  if (password !== context.confirm) {
    return 'Пароли не совпадают'
  }
  const normalized = normalize(password)
  if (normalized === normalize(context.username)) {
    return 'Пароль не должен совпадать с именем пользователя'
  }
  if (normalized === normalize(context.email)) {
    return 'Пароль не должен совпадать с email'
  }
  return null
}
