import { describe, expect, it } from 'vitest'
import { AVATAR_PALETTE, colorOf, fnv1a32, initialsOf } from '../avatar'

describe('initialsOf — первые буквы первых двух слов (FR-024, design-tokens §4)', () => {
  it('одиночное слово — одна буква', () => {
    expect(initialsOf('alex')).toBe('A')
    expect(initialsOf('Мария')).toBe('М')
  })

  it('два слова — по первой букве каждого', () => {
    expect(initialsOf('ada lovelace')).toBe('AL')
    expect(initialsOf('Мария Соколова')).toBe('МС')
  })

  it('три и более слов — только первые два', () => {
    expect(initialsOf('Иван Александрович Соколов')).toBe('ИА')
  })

  it('приводит к верхнему регистру', () => {
    expect(initialsOf('sergey esenin')).toBe('SE')
  })

  it('артикль "the" пропускается по алгоритму прототипа', () => {
    expect(initialsOf('The Rolling Stones')).toBe('RS')
    expect(initialsOf('The North')).toBe('N')
  })

  it('пустой источник — пустые инициалы (без падения)', () => {
    expect(initialsOf('')).toBe('')
    expect(initialsOf('   ')).toBe('')
  })

  it('детерминизм — повторные вызовы дают тот же результат', () => {
    expect(initialsOf('ada lovelace')).toBe(initialsOf('ada lovelace'))
  })
})

describe('colorOf — детерминированный цвет из палитры прототипа (FR-024)', () => {
  it('возвращает только цвета палитры прототипа', () => {
    const sources = ['alex', 'olkot', 'Гость', 'Проект Альфа', 'x', 'a b', 'zzz999']
    for (const source of sources) {
      expect(AVATAR_PALETTE).toContain(colorOf(source))
    }
  })

  it('точные значения по FNV-1a 32-bit (fnv1a32(source) mod 6)', () => {
    expect(colorOf('mikhail')).toBe('#c2622a')
    expect(colorOf('The Rolling Stones')).toBe('#7a5a8b')
    expect(colorOf('ada lovelace')).toBe('#4e7a8b')
    expect(colorOf('alex')).toBe('#5e8b7e')
    expect(colorOf('sergey')).toBe('#8b3a3a')
    expect(colorOf('Проект Бета')).toBe('#a08830')
  })

  it('детерминизм — один и тот же источник всегда даёт один цвет', () => {
    expect(colorOf('alex')).toBe(colorOf('alex'))
    expect(colorOf('alex')).toBe('#5e8b7e')
  })

  it('fnv1a32 — беззнаковый 32-битный FNV-1a с эталонными векторами', () => {
    expect(fnv1a32('')).toBe(0x811c9dc5)
    expect(fnv1a32('alex')).toBe(3852794181)
    expect(fnv1a32('Проект Альфа')).toBe(2686332368)
    for (const source of ['a', 'Мария Соколова', 'zzz']) {
      expect(Number.isInteger(fnv1a32(source))).toBe(true)
      expect(fnv1a32(source)).toBeGreaterThanOrEqual(0)
      expect(fnv1a32(source)).toBeLessThanOrEqual(0xffffffff)
    }
  })

  it('все 6 цветов палитры достижимы', () => {
    const sources = [
      'mikhail',
      'The Rolling Stones',
      'ada lovelace',
      'alex',
      'sergey',
      'Проект Бета',
    ]
    const used = new Set(sources.map(colorOf))
    expect(used).toEqual(new Set(AVATAR_PALETTE))
  })
})

describe('пересчёт при переименовании (FR-024, research §E)', () => {
  it('переименование группы пересчитывает инициалы и цвет на лету', () => {
    const before = { initials: initialsOf('Проект Альфа'), color: colorOf('Проект Альфа') }
    const after = { initials: initialsOf('Проект Бета'), color: colorOf('Проект Бета') }

    expect(before.initials).toBe('ПА')
    expect(after.initials).toBe('ПБ')
    expect(before.color).toBe('#4e7a8b')
    expect(after.color).toBe('#a08830')
    expect(after.color).not.toBe(before.color)
  })

  it('деривация чистая и без персистентности — старое имя даёт старый аватар и после переименования', () => {
    expect(colorOf('Проект Бета')).toBe('#a08830')
    expect(colorOf('Проект Альфа')).toBe('#4e7a8b')
    expect(initialsOf('Проект Альфа')).toBe('ПА')
  })

  it('разные источники распределяются по палитре без сбоев', () => {
    const used = new Set<string>()
    for (let i = 0; i < 200; i++) {
      used.add(colorOf(`user-${i}`))
    }
    expect(used.size).toBe(AVATAR_PALETTE.length)
  })
})
