import { describe, expect, it } from 'vitest'
import { resolveDisplayName } from '../names'

describe('resolveDisplayName — цепочка alias → displayName → username (FR-003, ui-behavior §1)', () => {
  it('alias старше displayName и username', () => {
    expect(resolveDisplayName('Маша', 'Мария', 'alice')).toBe('Маша')
  })

  it('без alias — displayName профиля (undefined и null равнозначны)', () => {
    expect(resolveDisplayName(undefined, 'Мария', 'alice')).toBe('Мария')
    expect(resolveDisplayName(null, 'Мария', 'alice')).toBe('Мария')
  })

  it('без alias и displayName — username (финальный фолбэк, обратная совместимость FR-002)', () => {
    expect(resolveDisplayName(undefined, undefined, 'alice')).toBe('alice')
    expect(resolveDisplayName(null, null, 'bob')).toBe('bob')
  })

  it('пустые строки считаются незаданными', () => {
    expect(resolveDisplayName('', 'Мария', 'alice')).toBe('Мария')
    expect(resolveDisplayName(undefined, '', 'alice')).toBe('alice')
    expect(resolveDisplayName('', '', 'alice')).toBe('alice')
  })

  it('пробельные значения пропускаются как незаданные (сервер хранит только trim 1–64)', () => {
    expect(resolveDisplayName('   ', 'Мария', 'alice')).toBe('Мария')
    expect(resolveDisplayName(undefined, ' \t ', 'alice')).toBe('alice')
    expect(resolveDisplayName('  ', '  ', 'alice')).toBe('alice')
  })

  it('значения возвращаются после trim (зеркалит серверный trim №39/№40)', () => {
    expect(resolveDisplayName('  Маша  ', 'Мария', 'alice')).toBe('Маша')
    expect(resolveDisplayName(undefined, ' Мария ', 'alice')).toBe('Мария')
  })

  it('username возвращается как есть — стабильный идентификатор без обработки', () => {
    expect(resolveDisplayName(null, null, 'alice')).toBe('alice')
  })

  it('сценарий quickstart US1: сброс alias возвращает displayName, сброс имени — username', () => {
    expect(resolveDisplayName('Маша', 'Мария', 'alice')).toBe('Маша')
    expect(resolveDisplayName(null, 'Мария', 'alice')).toBe('Мария')
    expect(resolveDisplayName(null, null, 'alice')).toBe('alice')
  })

  it('HTML-вставка в имени проходит насквозь как текст (экранирование — React-инвариант 008 FR-033)', () => {
    expect(resolveDisplayName('<img onerror=alert(1)>', undefined, 'alice')).toBe(
      '<img onerror=alert(1)>',
    )
  })

  it('чистая функция — повторные вызовы дают тот же результат', () => {
    expect(resolveDisplayName('Маша', 'Мария', 'alice')).toBe(
      resolveDisplayName('Маша', 'Мария', 'alice'),
    )
  })
})
