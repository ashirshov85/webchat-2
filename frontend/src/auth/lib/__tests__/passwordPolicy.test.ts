import { describe, expect, it } from 'vitest'
import { validateNewPassword } from '../passwordPolicy'

const ACCOUNT = {
  username: 'runner',
  email: 'runner@example.com',
}

const VALID_PASSWORD = 's3cure-pass!'

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
    ['shorter than 8 characters', 'Str0ng!'],
    ['longer than 128 characters', `a1B!`.repeat(32) + 'z'],
  ])('returns an error message for a password %s', (_reason, password) => {
    const result = validateNewPassword(password, { ...ACCOUNT, confirm: password })
    expect(result).toBeTypeOf('string')
    expect(result).not.toHaveLength(0)
  })

  it.each([
    ['an empty string', ''],
    ['a single space', ' '],
    ['eight spaces (length-valid but blank)', '        '],
    ['mixed whitespace characters', ' \t\n '],
  ])('returns an error message for %s', (_reason, password) => {
    const result = validateNewPassword(password, { ...ACCOUNT, confirm: password })
    expect(result).toBeTypeOf('string')
    expect(result).not.toHaveLength(0)
  })

  it.each([
    ['a different confirmation', 'Str0ng!2', 'Str0ng!3'],
    ['an empty confirmation', 'Str0ng!2', ''],
    ['a case-different confirmation', 'Str0ng!2', 'str0ng!2'],
  ])('returns an error message for %s', (_reason, password, confirm) => {
    const result = validateNewPassword(password, { ...ACCOUNT, confirm })
    expect(result).toBeTypeOf('string')
    expect(result).not.toHaveLength(0)
  })

  it.each([
    ['the exact username', 'runner'],
    ['the uppercased username', 'RUNNER'],
    ['the padded username', '  runner  '],
    ['the exact email', 'runner@example.com'],
    ['the uppercased email', 'RUNNER@EXAMPLE.COM'],
    ['the padded email', ' runner@example.com '],
  ])('returns an error message for %s', (_reason, password) => {
    const result = validateNewPassword(password, { ...ACCOUNT, confirm: password })
    expect(result).toBeTypeOf('string')
    expect(result).not.toHaveLength(0)
  })

  it('accepts a password similar to but different from the username and email', () => {
    const password = 'runner-2024!x'
    expect(validateNewPassword(password, { ...ACCOUNT, confirm: password })).toBeNull()
  })
})
