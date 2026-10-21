import { describe, expect, it } from 'vitest'
import { inviteExpiresLabel } from '../invite'

/**
 * 009, US4, T081 — подпись срока действия гостевой ссылки №52
 * (expiresAt → локальное время модератора): сегодня → «до ЧЧ:ММ»,
 * иначе «до {дата}, ЧЧ:ММ» (+год при отличии от текущего —
 * форматы ui/time, паттерн lastSeenFormat); невалидное значение —
 * null (подпись не выдумывается, паритет T071).
 */

/** Локальная date-time строка API (аналог createdAt №45). */
function iso(year: number, month: number, day: number, hours: number, minutes: number): string {
  return new Date(year, month, day, hours, minutes).toISOString()
}

describe('inviteExpiresLabel — срок гостевой ссылки локальным временем (009 US4 T081)', () => {
  it('истечение сегодня — только время «до ЧЧ:ММ»', () => {
    const now = new Date(2026, 9, 21, 12, 0)
    expect(inviteExpiresLabel(iso(2026, 9, 21, 18, 30), now)).toBe('до 18:30')
    expect(inviteExpiresLabel(iso(2026, 9, 21, 12, 0), now)).toBe('до 12:00')
  })

  it('истечение в другой день — «до {день месяца}, ЧЧ:ММ»', () => {
    const now = new Date(2026, 9, 21, 12, 0)
    // TTL 24 ч: типичный случай — переход через полночь
    expect(inviteExpiresLabel(iso(2026, 9, 22, 10, 5), now)).toBe('до 22 октября, 10:05')
  })

  it('другой год — дата с годом (формат ui/time)', () => {
    const now = new Date(2026, 11, 31, 23, 0)
    expect(inviteExpiresLabel(iso(2027, 0, 1, 9, 15), now)).toBe('до 1 января 2027 г., 09:15')
  })

  it('невалидное/пустое значение — null без падения (подпись не выдумывается)', () => {
    expect(inviteExpiresLabel('not-a-date')).toBeNull()
    expect(inviteExpiresLabel('', new Date(2026, 9, 21))).toBeNull()
  })
})
