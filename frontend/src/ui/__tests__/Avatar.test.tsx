import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { colorOf } from '../avatar'
import { Avatar } from '../Avatar'

/**
 * Аватар-примитив «Aethergram» (feature 008, T010; FR-024, data-model 1.2,
 * design-tokens §4): DOM-структура и классы — дословно из прототипа
 * (avatarHTML: `.avatar[.oct] > .av-in > span` + `.av-dot[.off|.unknown]`),
 * обод — `--gold-gradient`, инициалы — Cormorant SC (тема avatar.css),
 * цвет — детерминированный `--av` из ui/avatar.ts (T008), presence-точка —
 * 'online' | 'offline' | 'unknown' | null, где unknown — нейтральная
 * (без ложного «офлайн», семантика 007 / clarify 2026-10-01).
 */

afterEach(cleanup)

function avatarRoot(container: HTMLElement): HTMLElement {
  const root = container.querySelector<HTMLElement>('.avatar')
  expect(root).not.toBeNull()
  return root as HTMLElement
}

describe('Avatar — деривация из единственного источника (FR-024, data-model 1.2)', () => {
  it('показывает инициалы initialsOf(source): первые буквы первых двух слов', () => {
    const { container } = render(<Avatar source="ada lovelace" />)
    expect(avatarRoot(container).querySelector('.av-in span')?.textContent).toBe('AL')
  })

  it('одиночное слово — одна буква, кириллица поддерживается', () => {
    const { container } = render(<Avatar source="Мария" />)
    expect(avatarRoot(container).querySelector('.av-in span')?.textContent).toBe('М')
  })

  it('цвет детерминирован: inline-переменная --av = colorOf(source)', () => {
    const { container } = render(<Avatar source="alex" />)
    expect(avatarRoot(container).style.getPropertyValue('--av')).toBe(colorOf('alex'))
    expect(avatarRoot(container).style.getPropertyValue('--av')).toBe('#5e8b7e')
  })

  it('один и тот же источник — одинаковое сочетание инициалов и цвета', () => {
    const first = render(<Avatar source="Проект Альфа" />)
    const second = render(<Avatar source="Проект Альфа" />)
    const firstRoot = avatarRoot(first.container)
    const secondRoot = avatarRoot(second.container)
    expect(secondRoot.querySelector('.av-in span')?.textContent).toBe(
      firstRoot.querySelector('.av-in span')?.textContent,
    )
    expect(secondRoot.style.getPropertyValue('--av')).toBe(firstRoot.style.getPropertyValue('--av'))
  })

  it('переименование пересчитывает инициалы и цвет на лету (без персистентности)', () => {
    const { container, rerender } = render(<Avatar source="Проект Альфа" />)
    const before = avatarRoot(container)
    expect(before.querySelector('.av-in span')?.textContent).toBe('ПА')
    expect(before.style.getPropertyValue('--av')).toBe('#4e7a8b')

    rerender(<Avatar source="Проект Бета" />)
    const after = avatarRoot(container)
    expect(after.querySelector('.av-in span')?.textContent).toBe('ПБ')
    expect(after.style.getPropertyValue('--av')).toBe('#a08830')
  })

  it('пустой источник — заглушка «?» вместо падения', () => {
    const { container } = render(<Avatar source="   " />)
    expect(avatarRoot(container).querySelector('.av-in span')?.textContent).toBe('?')
  })
})

describe('Avatar — форма круг/октагон и обод (design-tokens §4)', () => {
  it('по умолчанию круг: класс avatar без oct', () => {
    const { container } = render(<Avatar source="alex" />)
    expect(avatarRoot(container).className).toBe('avatar')
  })

  it('shape="octagon" — класс oct (clip-path 30/70% из avatar.css)', () => {
    const { container } = render(<Avatar source="Проект Альфа" shape="octagon" />)
    expect(avatarRoot(container).className).toBe('avatar oct')
  })

  it('внутренность — прототипный DOM .av-in > span (обод и шрифт задаёт avatar.css)', () => {
    const { container } = render(<Avatar source="alex" />)
    const root = avatarRoot(container)
    expect(root.querySelector('.av-in')).not.toBeNull()
    expect(root.querySelector('.av-in > span')?.textContent).toBe('A')
  })

  it('размер по умолчанию 40px; size переопределяет width/height', () => {
    const { container } = render(<Avatar source="alex" />)
    const base = avatarRoot(container)
    expect(base.style.width).toBe('40px')
    expect(base.style.height).toBe('40px')

    const sized = render(<Avatar source="alex" size={46} />)
    const root = avatarRoot(sized.container)
    expect(root.style.width).toBe('46px')
    expect(root.style.height).toBe('46px')
  })
})

describe('Avatar — presence-точка (FR-024, design-tokens §4, семантика 007)', () => {
  it('по умолчанию (null) точки нет — группы, свой аватар, отправители ленты', () => {
    const { container } = render(<Avatar source="alex" />)
    expect(avatarRoot(container).querySelector('.av-dot')).toBeNull()
  })

  it('online — базовый класс av-dot (зелёная + свечение + flick из avatar.css)', () => {
    const { container } = render(<Avatar source="alex" presenceDot="online" />)
    const dot = avatarRoot(container).querySelector<HTMLElement>('.av-dot')
    expect(dot).not.toBeNull()
    expect(dot?.className).toBe('av-dot')
  })

  it('offline — класс av-dot off (тусклая, без анимации)', () => {
    const { container } = render(<Avatar source="alex" presenceDot="offline" />)
    const dot = avatarRoot(container).querySelector<HTMLElement>('.av-dot.off')
    expect(dot).not.toBeNull()
    expect(dot?.className).toBe('av-dot off')
  })

  it('unknown — нейтральная точка класса unknown, НЕ «офлайн» (без ложного «офлайн»)', () => {
    const { container } = render(<Avatar source="alex" presenceDot="unknown" />)
    const dot = avatarRoot(container).querySelector<HTMLElement>('.av-dot.unknown')
    expect(dot).not.toBeNull()
    expect(dot?.className).toBe('av-dot unknown')
    expect(avatarRoot(container).querySelector('.av-dot.off')).toBeNull()
  })

  it('точка доступна скринридерам: role=img с aria-label состояния', () => {
    render(<Avatar source="alex" presenceDot="online" />)
    expect(screen.getByRole('img', { name: 'онлайн' })).toBeInTheDocument()

    cleanup()
    render(<Avatar source="alex" presenceDot="offline" />)
    expect(screen.getByRole('img', { name: 'офлайн' })).toBeInTheDocument()

    cleanup()
    render(<Avatar source="alex" presenceDot="unknown" />)
    expect(screen.getByRole('img', { name: 'неизвестно' })).toBeInTheDocument()
  })

  it('инициалы декоративны для скринридера (имя рядом несёт поверхность)', () => {
    const { container } = render(<Avatar source="ada lovelace" />)
    expect(avatarRoot(container).querySelector('.av-in')?.getAttribute('aria-hidden')).toBe('true')
  })
})

describe('Avatar — разделение источников (008a T020, FR-004: инициалы из имени, цвет из username)', () => {
  it('проп initials переопределяет деривацию, цвет остаётся colorOf(source)', () => {
    const { container } = render(<Avatar source="olkot" initials="МС" />)
    const root = avatarRoot(container)
    expect(root.querySelector('.av-in span')?.textContent).toBe('МС')
    expect(root.style.getPropertyValue('--av')).toBe(colorOf('olkot'))
  })

  it('смена отображаемого имени (новые инициалы) не перекрашивает аватар — цвет от username (FR-004)', () => {
    const { container, rerender } = render(<Avatar source="olkot" initials="МС" />)
    const colorBefore = avatarRoot(container).style.getPropertyValue('--av')

    rerender(<Avatar source="olkot" initials="АК" />)
    const root = avatarRoot(container)
    expect(root.querySelector('.av-in span')?.textContent).toBe('АК')
    expect(root.style.getPropertyValue('--av')).toBe(colorBefore)
    expect(root.style.getPropertyValue('--av')).toBe(colorOf('olkot'))
  })

  it('эмодзи-инициалы рендерятся кластером целиком (SC-006, без «тофу»)', () => {
    const { container } = render(<Avatar source="mikhail" initials="😀М" />)
    expect(avatarRoot(container).querySelector('.av-in span')?.textContent).toBe('😀М')
  })

  it('пустой проп initials — фолбэк к деривации initialsOf(source)', () => {
    const { container } = render(<Avatar source="ada lovelace" initials="" />)
    expect(avatarRoot(container).querySelector('.av-in span')?.textContent).toBe('AL')
  })

  it('без пропа ничего не меняется для существующих поверхностей (проводка — T023)', () => {
    const { container } = render(<Avatar source="Проект Альфа" />)
    const root = avatarRoot(container)
    expect(root.querySelector('.av-in span')?.textContent).toBe('ПА')
    expect(root.style.getPropertyValue('--av')).toBe(colorOf('Проект Альфа'))
  })
})
