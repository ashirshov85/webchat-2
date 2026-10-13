/**
 * Общий модуль русских сообщений problem+json для auth-экранов.
 * История имён (решение зафиксировано здесь, T048): T046 — локальный
 * маппинг PasswordChangeModal; T047 — извлечение в passwordProblemRu.ts;
 * T048 — ПЕРЕИМЕНОВАН в problemRu.ts, т.к. утилиты (formatRetryAfterRu,
 * isFieldErrors, retryAfterMessageRu) стали общими для ВСЕХ auth-экранов
 * (логин/SSO — не «парольные» флоу, старое имя вводило в заблуждение);
 * PasswordChangeModal (T046) и ResetPasswordForm (T047) переведены на
 * новые импорты БЕЗ изменения текстов/поведения. passwordProblemMessageRu
 * и NEW_PASSWORD_MESSAGES остаются парольной частью модуля. Глобальный
 * problemMessage (auth/problem.ts, английский) НЕ трогается; 401 «Сессия
 * истекла» и currentPassword: invalid остаются ЛОКАЛЬНЫМИ ветками смены
 * пароля — сброс пароля анонимен, логин имеет свою русскую 401-строку.
 */

/** Русское согласование количественных форм: 1 час / 2 часа / 5 часов (11–14 — «много»). */
function pluralRu(count: number, one: string, few: string, many: string): string {
  const mod100 = count % 100
  if (mod100 >= 11 && mod100 <= 14) {
    return many
  }
  const mod10 = count % 10
  if (mod10 === 1) {
    return one
  }
  if (mod10 >= 2 && mod10 <= 4) {
    return few
  }
  return many
}

/** retryAfterSec → «N секунд/минут/часов» (ceil-округление, паритет problem.ts). */
export function formatRetryAfterRu(seconds: number): string {
  const total = Math.max(1, Math.ceil(seconds))
  if (total < 60) {
    return `${total} ${pluralRu(total, 'секунду', 'секунды', 'секунд')}`
  }
  const minutes = Math.ceil(total / 60)
  if (minutes < 60) {
    return `${minutes} ${pluralRu(minutes, 'минуту', 'минуты', 'минут')}`
  }
  const hours = Math.ceil(minutes / 60)
  return `${hours} ${pluralRu(hours, 'час', 'часа', 'часов')}`
}

export function isFieldErrors(value: unknown): value is Record<string, string[]> {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  return Object.values(value).every(
    (codes) => Array.isArray(codes) && codes.every((code) => typeof code === 'string'),
  )
}

/**
 * Generic-обёртка 429 → «Слишком много попыток…» для всех auth-экранов
 * (T048): с числовым retryAfterSec — «…через N» (склонение секунд/минут/
 * часов), без него — «…позже».
 */
export function retryAfterMessageRu(retryAfterSec: unknown): string {
  if (typeof retryAfterSec === 'number' && Number.isFinite(retryAfterSec)) {
    return `Слишком много попыток — попробуйте через ${formatRetryAfterRu(retryAfterSec)}`
  }
  return 'Слишком много попыток — попробуйте позже'
}

/**
 * Серверные коды политики нового пароля → русские тексты ДОСЛОВНО из
 * T046 (паритет клиентской validateNewPassword); ключ поля у флоу
 * разный — newPassword (смена) / password (сброс), коды совпадают.
 */
export const NEW_PASSWORD_MESSAGES: Readonly<Record<string, string>> = {
  blank: 'Пароль не должен быть пустым или состоять только из пробелов',
  too_short: 'Пароль должен быть не короче 8 символов',
  too_long: 'Пароль должен быть не длиннее 128 символов',
  too_common: 'Этот пароль слишком распространён — выберите другой',
  same_as_username: 'Пароль не должен совпадать с именем пользователя',
  same_as_email: 'Пароль не должен совпадать с email',
}

/**
 * Generic-обёртка problem → русское сообщение: 400 полевые коды
 * newPassword/password → NEW_PASSWORD_MESSAGES; 429 ± retryAfterSec →
 * retryAfterMessageRu; прочее (включая 401 — локальная ветка
 * смены пароля) → fallback вызывающего.
 */
export function passwordProblemMessageRu(cause: unknown, fallback: string): string {
  if (typeof cause !== 'object' || cause === null) {
    return fallback
  }
  const problem = cause as { status?: unknown; errors?: unknown; retryAfterSec?: unknown }
  if (typeof problem.status !== 'number') {
    return fallback
  }
  if (problem.status === 429) {
    return retryAfterMessageRu(problem.retryAfterSec)
  }
  if (problem.status === 400 && isFieldErrors(problem.errors)) {
    const code = problem.errors.newPassword?.[0] ?? problem.errors.password?.[0]
    if (code !== undefined && NEW_PASSWORD_MESSAGES[code] !== undefined) {
      return NEW_PASSWORD_MESSAGES[code]
    }
  }
  return fallback
}
