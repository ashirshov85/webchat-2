import { describe, expect, it } from 'vitest'
import { validateNewPassword } from '../passwordPolicy'

/**
 * Русские строки клиентской политики нового пароля (008b T020 → T046:
 * единственный потребитель — модаль «Смена пароля»; сообщения рендерятся
 * в .modal-err дословно).
 */

// username ≥ 8 символов: совпадающий с ним пароль проходит проверку
// длины ДО сравнения — до ветки username добирается именно она.
const ACCOUNT = {
  username: 'runner-42',
  email: 'runner@example.com',
}

const VALID_PASSWORD = 's3cure-pass!'

const EMPTY_MESSAGE = 'Пароль не должен быть пустым или состоять только из пробелов'
const SHORT_MESSAGE = 'Пароль должен быть не короче 8 символов'
const LONG_MESSAGE = 'Пароль должен быть не длиннее 128 символов'
const MISMATCH_MESSAGE = 'Пароли не совпадают'
const USERNAME_MESSAGE = 'Пароль не должен совпадать с именем пользователя'
const EMAIL_MESSAGE = 'Пароль не должен совпадать с email'

describe('validateNewPassword', () => {
  it.each([
    ['a compliant password', VALID_PASSWORD],
    ['the minimum boundary of 8 characters', 'Str0ng!2'],
    ['the maximum boundary of 128 characters', 'a1B!'.repeat(32)],
    ['a passphrase with inner spaces', 'correct horse battery'],
  ])('returns null for %s', (_reason, password) => {
    expect(validateNewPassword(password, { ...ACCOUNT, confirm: password })).toBeNull()
  })

  it.each([
    ['shorter than 8 characters', 'Str0ng!', SHORT_MESSAGE],
    ['longer than 128 characters', `a1B!`.repeat(32) + 'z', LONG_MESSAGE],
  ])('returns the russian length error for a password %s', (_reason, password, message) => {
    expect(validateNewPassword(password, { ...ACCOUNT, confirm: password })).toBe(message)
  })

  it.each([
    ['an empty string', '', EMPTY_MESSAGE],
    ['a single space', ' ', EMPTY_MESSAGE],
    ['eight spaces (length-valid but blank)', '        ', EMPTY_MESSAGE],
    ['mixed whitespace characters', ' \t\n ', EMPTY_MESSAGE],
  ])('returns the russian blank error for %s', (_reason, password, message) => {
    expect(validateNewPassword(password, { ...ACCOUNT, confirm: password })).toBe(message)
  })

  it.each([
    ['a different confirmation', 'Str0ng!2', 'Str0ng!3'],
    ['an empty confirmation', 'Str0ng!2', ''],
    ['a case-different confirmation', 'Str0ng!2', 'str0ng!2'],
  ])('returns the russian mismatch error for %s', (_reason, password, confirm) => {
    expect(validateNewPassword(password, { ...ACCOUNT, confirm })).toBe(MISMATCH_MESSAGE)
  })

  it.each([
    ['the exact username', 'runner-42'],
    ['the uppercased username', 'RUNNER-42'],
    ['the padded username', '  runner-42  '],
  ])('returns the russian username error for %s', (_reason, password) => {
    expect(validateNewPassword(password, { ...ACCOUNT, confirm: password })).toBe(USERNAME_MESSAGE)
  })

  it.each([
    ['the exact email', 'runner@example.com'],
    ['the uppercased email', 'RUNNER@EXAMPLE.COM'],
    ['the padded email', ' runner@example.com '],
  ])('returns the russian email error for %s', (_reason, password) => {
    expect(validateNewPassword(password, { ...ACCOUNT, confirm: password })).toBe(EMAIL_MESSAGE)
  })

  it('accepts a password similar to but different from the username and email', () => {
    const password = 'runner-2024!x'
    expect(validateNewPassword(password, { ...ACCOUNT, confirm: password })).toBeNull()
  })
})
