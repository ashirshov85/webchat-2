import { describe, expect, it } from 'vitest'
import { findMatch, snippetAround } from '../search'

/**
 * T068 (008a Phase 11) — чистые примитивы псевдо-поиска по сообщениям
 * открытого чата (по образцу ui/names.ts — единый модуль, прототип
 * chats.html renderSearchResults): совпадение — подстрока без регистра
 * по message.text; сниппет — окно ~96 символов вокруг ПЕРВОГО вхождения
 * с ведущим/хвостовым многоточием. Экранирование — React-инвариант 008
 * FR-033: функция возвращает сырой текст, HTML-вставка остаётся ТЕКСТОМ
 * (никакой разметки не рождается).
 */

describe('findMatch (T068: подстрока без регистра, прототип renderSearchResults)', () => {
  it('находит первое вхождение без регистра (латиница и кириллица)', () => {
    expect(findMatch('Jolly good!', 'GOOD')).toBe(6)
    expect(findMatch('Ночной эфир', 'ноч')).toBe(0)
    expect(findMatch('abc ABC AbC', 'abc')).toBe(0)
  })

  it('первое из нескольких вхождений', () => {
    expect(findMatch('раз раз раз', 'раз')).toBe(0)
    expect(findMatch('aXbXc', 'x')).toBe(1)
  })

  it('нет совпадения → -1; пустой запрос не совпадает ни с чем', () => {
    expect(findMatch('Jolly good!', 'night')).toBe(-1)
    expect(findMatch('текст', '')).toBe(-1)
  })

  it('совпадение больше остатка текста → -1 (хвост короче запроса)', () => {
    expect(findMatch('ab', 'abc')).toBe(-1)
  })
})

describe('snippetAround (T068: окно ~96 символов вокруг вхождения, прототип :866-870)', () => {
  it('вхождение у начала: без ведущего многоточия, короткий текст — целиком', () => {
    expect(snippetAround('Ночной эфир', 0)).toBe('Ночной эфир')
  })

  it('вхождение у начала длинного текста: окно 96 от начала + хвостовое многоточие', () => {
    const text = 'а'.repeat(120)
    const snippet = snippetAround(text, 0)
    expect(snippet).toBe(`${'а'.repeat(96)}…`)
    expect(snippet.length).toBe(97)
  })

  it('вхождение глубже 24 символов: старт за 24 до вхождения + ведущее многоточие', () => {
    const head = 'x'.repeat(40)
    const text = `${head}QUERY${'y'.repeat(200)}`
    const snippet = snippetAround(text, head.length)
    expect(snippet.startsWith('…')).toBe(true)
    // окно открывается ровно на 24 символа до вхождения (start = pos − 24)
    // и тянется 96: 24x + QUERY(5) + 67y, обрезанный хвост — многоточием
    expect(snippet).toBe(`…${'x'.repeat(24)}QUERY${'y'.repeat(67)}…`)
  })

  it('вхождение ближе 24 к началу: старт от нуля, без ведущего многоточия', () => {
    const text = `${'x'.repeat(10)}needle${'y'.repeat(200)}`
    const snippet = snippetAround(text, 10)
    expect(snippet.startsWith('…')).toBe(false)
    expect(snippet.startsWith(`${'x'.repeat(10)}needle`)).toBe(true)
    expect(snippet.endsWith('…')).toBe(true)
  })

  it('хвост корше окна: без хвостового многоточия', () => {
    const head = 'x'.repeat(60)
    const text = `${head}needle${'y'.repeat(20)}`
    const snippet = snippetAround(text, head.length)
    expect(snippet).toBe(`…${'x'.repeat(24)}needle${'y'.repeat(20)}`)
    expect(snippet.endsWith('…')).toBe(false)
  })

  it('экранирование (008 FR-033): HTML-вставка остаётся сырым текстом сниппета', () => {
    const text = 'до <img onerror=alert(1) src=x> после'
    const snippet = snippetAround(text, text.indexOf('<img'))
    expect(snippet).toContain('<img onerror=alert(1) src=x>')
    // многоточия — единственные добавленные символы, никакой разметки
    expect(snippet.replace(/…/g, '')).toBe(text)
  })
})
