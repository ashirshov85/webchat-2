import { describe, expect, it } from 'vitest'
import { endedConferenceLabel, formatDuration, formatSpanDuration } from '../duration'

/**
 * 009, US5, T071 — компактная подпись длительности завершённой
 * конференции (частичный пересмотр Q20=A «без деталей, как долго
 * прошла»; новые API/поля не вводятся — данные уже в контракте):
 * границы «менее минуты»/«1 мин» (59/60 с), «59 мин»/«1 ч 0 мин»
 * (59/60 мин), составной «N ч M мин»; пара startedAt/endedAt →
 * подпись или null (нет timestamp'ов — не выдумывать).
 */

describe('formatDuration — компактная подпись из миллисекунд (009 T071)', () => {
  it('граница секунд: 59 с — «менее минуты», 60 с — «1 мин»', () => {
    expect(formatDuration(0)).toBe('менее минуты')
    expect(formatDuration(59_999)).toBe('менее минуты')
    expect(formatDuration(60_000)).toBe('1 мин')
  })

  it('минуты: «2 мин», «59 мин»', () => {
    expect(formatDuration(2 * 60_000)).toBe('2 мин')
    expect(formatDuration(59 * 60_000)).toBe('59 мин')
  })

  it('граница часов: 59 мин 59 с — «59 мин», 60 мин — «1 ч 0 мин»', () => {
    expect(formatDuration(59 * 60_000 + 59_999)).toBe('59 мин')
    expect(formatDuration(60 * 60_000)).toBe('1 ч 0 мин')
  })

  it('составной формат «N ч M мин»', () => {
    expect(formatDuration(125 * 60_000)).toBe('2 ч 5 мин')
    expect(formatDuration(241 * 60_000)).toBe('4 ч 1 мин')
    expect(formatDuration(23 * 3_600_000 + 59 * 60_000)).toBe('23 ч 59 мин')
  })
})

describe('formatSpanDuration — пара startedAt/endedAt (009 T071)', () => {
  it('оба поля → подпись длительности', () => {
    expect(
      formatSpanDuration({
        startedAt: '2026-10-17T12:10:00.000Z',
        endedAt: '2026-10-17T12:35:00.000Z',
      }),
    ).toBe('25 мин')
    expect(
      formatSpanDuration({
        startedAt: '2026-10-17T12:10:00.000Z',
        endedAt: '2026-10-17T12:10:30.000Z',
      }),
    ).toBe('менее минуты')
  })

  it('нет startedAt (никогда не начинавшаяся — room_lost) → null', () => {
    expect(formatSpanDuration({ endedAt: '2026-10-17T12:30:00.000Z' })).toBeNull()
  })

  it('нет endedAt (живая конференция) → null', () => {
    expect(formatSpanDuration({ startedAt: '2026-10-17T12:10:00.000Z' })).toBeNull()
  })

  it('span null/undefined/пустой → null', () => {
    expect(formatSpanDuration(null)).toBeNull()
    expect(formatSpanDuration(undefined)).toBeNull()
    expect(formatSpanDuration({})).toBeNull()
  })

  it('невалидные даты → null (не выдумывать)', () => {
    expect(
      formatSpanDuration({ startedAt: 'not-a-date', endedAt: '2026-10-17T12:30:00.000Z' }),
    ).toBeNull()
    expect(formatSpanDuration({ startedAt: '2026-10-17T12:10:00.000Z', endedAt: 'нет' })).toBeNull()
  })

  it('ended раньше started → null (инвертированный интервал не показывается)', () => {
    expect(
      formatSpanDuration({
        startedAt: '2026-10-17T12:30:00.000Z',
        endedAt: '2026-10-17T12:10:00.000Z',
      }),
    ).toBeNull()
  })
})

describe('endedConferenceLabel — терминальная подпись с длительностью (009 T071)', () => {
  it('без длительности — прежняя подпись (деривация T070, нет timestamp’ов)', () => {
    expect(endedConferenceLabel(null)).toBe('Конференция завершена')
  })

  it('с длительностью — «Конференция завершена · {подпись}»', () => {
    expect(endedConferenceLabel('5 мин')).toBe('Конференция завершена · 5 мин')
    expect(endedConferenceLabel('1 ч 0 мин')).toBe('Конференция завершена · 1 ч 0 мин')
  })
})
