import { describe, expect, it } from 'vitest'
import { formatDate, formatTime, pluralRu } from '../time'

describe('formatTime — ЧЧ:ММ из createdAt, Intl ru-RU (FR-019, research §J)', () => {
  it('24-часовой формат с ведущим нулём', () => {
    expect(formatTime(new Date(2026, 8, 19, 9, 5))).toBe('09:05')
    expect(formatTime(new Date(2026, 8, 19, 0, 0))).toBe('00:00')
  })

  it('после полудня — 24-часовые часы без суффиксов', () => {
    expect(formatTime(new Date(2026, 8, 19, 19, 5))).toBe('19:05')
    expect(formatTime(new Date(2026, 8, 19, 12, 30))).toBe('12:30')
    expect(formatTime(new Date(2026, 8, 19, 23, 59))).toBe('23:59')
  })

  it('принимает Date, ISO-строку и timestamp с одинаковым результатом', () => {
    const date = new Date(2026, 8, 19, 14, 45)
    expect(formatTime(date)).toBe('14:45')
    expect(formatTime(date.toISOString())).toBe('14:45')
    expect(formatTime(date.getTime())).toBe('14:45')
  })

  it('невалидная дата — пустая строка без падения', () => {
    expect(formatTime(new Date('нет даты'))).toBe('')
    expect(formatTime('not-a-date')).toBe('')
  })
})

describe('formatDate — «19 сентября», +год при отличии от текущего (FR-019, design-tokens §6)', () => {
  it('длинный формат русской локали без года в текущем году', () => {
    const now = new Date(2026, 3, 1)
    expect(formatDate(new Date(2026, 8, 19), now)).toBe('19 сентября')
    expect(formatDate(new Date(2026, 0, 1), now)).toBe('1 января')
  })

  it('добавляет год, когда год даты отличается от текущего', () => {
    const now = new Date(2026, 3, 1)
    expect(formatDate(new Date(2019, 8, 19), now)).toBe('19 сентября 2019 г.')
    expect(formatDate(new Date(2027, 0, 2), now)).toBe('2 января 2027 г.')
  })

  it('29 февраля високосного года форматируется корректно', () => {
    expect(formatDate(new Date(2024, 1, 29), new Date(2024, 5, 1))).toBe('29 февраля')
  })

  it('принимает Date, ISO-строку и timestamp с одинаковым результатом', () => {
    const now = new Date(2026, 3, 1)
    const date = new Date(2026, 8, 19, 10, 0)
    expect(formatDate(date.toISOString(), now)).toBe('19 сентября')
    expect(formatDate(date.getTime(), now)).toBe('19 сентября')
  })

  it('невалидная дата — пустая строка без падения', () => {
    expect(formatDate(new Date(NaN), new Date(2026, 3, 1))).toBe('')
  })
})

describe('pluralRu — русская плюрализация по алгоритму прототипа (FR-016, research §J)', () => {
  it('форма «один»: 1, 21, 31, 101', () => {
    for (const n of [1, 21, 31, 101]) {
      expect(pluralRu(n, 'участник', 'участника', 'участников')).toBe('участник')
    }
  })

  it('форма «несколько»: 2, 3, 4, 22, 23, 24', () => {
    for (const n of [2, 3, 4, 22, 23, 24]) {
      expect(pluralRu(n, 'участник', 'участника', 'участников')).toBe('участника')
    }
  })

  it('форма «много»: 0, 5, 11, 12, 13, 14, 100, 111', () => {
    for (const n of [0, 5, 11, 12, 13, 14, 100, 111]) {
      expect(pluralRu(n, 'участник', 'участника', 'участников')).toBe('участников')
    }
  })

  it('11–14 всегда «много», даже когда последняя цифра 1–4', () => {
    expect(pluralRu(11, 'сообщение', 'сообщения', 'сообщений')).toBe('сообщений')
    expect(pluralRu(12, 'сообщение', 'сообщения', 'сообщений')).toBe('сообщений')
    expect(pluralRu(14, 'сообщение', 'сообщения', 'сообщений')).toBe('сообщений')
  })

  it('работает с произвольными формами', () => {
    expect(pluralRu(1, 'контакт', 'контакта', 'контактов')).toBe('контакт')
    expect(pluralRu(3, 'контакт', 'контакта', 'контактов')).toBe('контакта')
    expect(pluralRu(5, 'контакт', 'контакта', 'контактов')).toBe('контактов')
  })
})
