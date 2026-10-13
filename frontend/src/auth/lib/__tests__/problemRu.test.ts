import { describe, expect, it } from 'vitest'
import {
  formatRetryAfterRu,
  isFieldErrors,
  NEW_PASSWORD_MESSAGES,
  passwordProblemMessageRu,
  retryAfterMessageRu,
} from '../problemRu'

/**
 * Общий модуль русских сообщений problem+json (008b T047 — извлечение из
 * PasswordChangeModal T046; T048 — переименование passwordProblemRu →
 * problemRu и generic-обёртка retryAfterMessageRu для всех auth-экранов):
 * единый источник текстов для «Смены пароля», «Сброса пароля» и логина.
 * Строки NEW_PASSWORD_MESSAGES — ДОСЛОВНО из T046 (паритет с
 * validateNewPassword); formatRetryAfterRu — винительный падеж после
 * «через» (1 минуту/2 минуты/5 минут), регресс PasswordChangeModal.test
 * держит формы неизменными; 401 в общий маппинг НЕ входит — ветка
 * «Сессия истекла» остаётся локальной для смены пароля (сброс —
 * анонимный флоу, логин имеет свою русскую 401-строку).
 */

const FALLBACK = 'Не удалось изменить пароль. Проверьте соединение и попробуйте ещё раз'

describe('NEW_PASSWORD_MESSAGES', () => {
  it('maps every server password-policy code to the verbatim T046 russian string', () => {
    expect(NEW_PASSWORD_MESSAGES.blank).toBe(
      'Пароль не должен быть пустым или состоять только из пробелов',
    )
    expect(NEW_PASSWORD_MESSAGES.too_short).toBe('Пароль должен быть не короче 8 символов')
    expect(NEW_PASSWORD_MESSAGES.too_long).toBe('Пароль должен быть не длиннее 128 символов')
    expect(NEW_PASSWORD_MESSAGES.too_common).toBe(
      'Этот пароль слишком распространён — выберите другой',
    )
    expect(NEW_PASSWORD_MESSAGES.same_as_username).toBe(
      'Пароль не должен совпадать с именем пользователя',
    )
    expect(NEW_PASSWORD_MESSAGES.same_as_email).toBe('Пароль не должен совпадать с email')
  })
})

describe('formatRetryAfterRu', () => {
  it.each([
    [1, '1 секунду'],
    [2, '2 секунды'],
    [5, '5 секунд'],
    [42, '42 секунды'],
    [59, '59 секунд'],
  ])('%is → «%s»', (seconds, expected) => {
    expect(formatRetryAfterRu(seconds)).toBe(expected)
  })

  it.each([
    [60, '1 минуту'],
    [65, '2 минуты'],
    [300, '5 минут'],
  ])('%is → «%s»', (seconds, expected) => {
    expect(formatRetryAfterRu(seconds)).toBe(expected)
  })

  it.each([
    [3600, '1 час'],
    [7200, '2 часа'],
    [18000, '5 часов'],
  ])('%is → «%s»', (seconds, expected) => {
    expect(formatRetryAfterRu(seconds)).toBe(expected)
  })

  it('uses the many-form for 11–14 and never returns less than one second', () => {
    expect(formatRetryAfterRu(11)).toBe('11 секунд')
    expect(formatRetryAfterRu(14)).toBe('14 секунд')
    expect(formatRetryAfterRu(0)).toBe('1 секунду')
    expect(formatRetryAfterRu(0.4)).toBe('1 секунду')
  })
})

describe('isFieldErrors', () => {
  it('accepts records of string arrays and rejects everything else', () => {
    expect(isFieldErrors({ password: ['too_common'] })).toBe(true)
    expect(isFieldErrors({})).toBe(true)
    expect(isFieldErrors(null)).toBe(false)
    expect(isFieldErrors('password')).toBe(false)
    expect(isFieldErrors({ password: 'too_common' })).toBe(false)
    expect(isFieldErrors({ password: [42] })).toBe(false)
  })
})

describe('retryAfterMessageRu', () => {
  it('429 c retryAfterSec → «Слишком много попыток — попробуйте через N» (T048)', () => {
    expect(retryAfterMessageRu(300)).toBe('Слишком много попыток — попробуйте через 5 минут')
    expect(retryAfterMessageRu(7200)).toBe('Слишком много попыток — попробуйте через 2 часа')
    expect(retryAfterMessageRu(42)).toBe('Слишком много попыток — попробуйте через 42 секунды')
  })

  it.each([
    ['без retryAfterSec', undefined],
    ['retryAfterSec не число', '300'],
    ['retryAfterSec NaN', Number.NaN],
  ])('%s → «Слишком много попыток — попробуйте позже»', (_reason, value) => {
    expect(retryAfterMessageRu(value)).toBe('Слишком много попыток — попробуйте позже')
  })
})

describe('passwordProblemMessageRu', () => {
  it.each(Object.entries(NEW_PASSWORD_MESSAGES))(
    '400 newPassword:%s → the shared russian string',
    (code, message) => {
      expect(
        passwordProblemMessageRu(
          { title: 'Bad Request', status: 400, errors: { newPassword: [code] } },
          FALLBACK,
        ),
      ).toBe(message)
    },
  )

  it.each(Object.entries(NEW_PASSWORD_MESSAGES))(
    '400 password:%s (reset flow field name) → the same russian string',
    (code, message) => {
      expect(
        passwordProblemMessageRu(
          { title: 'Bad Request', status: 400, errors: { password: [code] } },
          FALLBACK,
        ),
      ).toBe(message)
    },
  )

  it('429 + retryAfterSec → «Слишком много попыток — попробуйте через N»', () => {
    expect(
      passwordProblemMessageRu(
        { title: 'Too Many Requests', status: 429, retryAfterSec: 300 },
        FALLBACK,
      ),
    ).toBe('Слишком много попыток — попробуйте через 5 минут')
    expect(
      passwordProblemMessageRu(
        { title: 'Too Many Requests', status: 429, retryAfterSec: 7200 },
        FALLBACK,
      ),
    ).toBe('Слишком много попыток — попробуйте через 2 часа')
  })

  it('429 without retryAfterSec → «Слишком много попыток — попробуйте позже»', () => {
    expect(passwordProblemMessageRu({ title: 'Too Many Requests', status: 429 }, FALLBACK)).toBe(
      'Слишком много попыток — попробуйте позже',
    )
  })

  it('401 is NOT mapped here — the expired-session branch stays local to password change', () => {
    expect(passwordProblemMessageRu({ title: 'Unauthorized', status: 401 }, FALLBACK)).toBe(
      FALLBACK,
    )
  })

  it.each([
    ['сетевой сбой (не problem-объект)', new TypeError('NetworkError when attempting to fetch.')],
    ['неизвестная проблема 500', { title: 'Internal Server Error', status: 500 }],
    [
      '400 с неизвестным кодом поля',
      { title: 'Bad Request', status: 400, errors: { password: ['strange_code'] } },
    ],
    ['объект без status', { title: 'Bad Request' }],
  ])('%s → fallback', (_reason, cause) => {
    expect(passwordProblemMessageRu(cause, FALLBACK)).toBe(FALLBACK)
  })
})
