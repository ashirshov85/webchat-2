import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import { useState, type MouseEvent as ReactMouseEvent } from 'react'
import { ContextMenu, type ContextMenuAlign, type MenuItem } from '../ContextMenu'
import { ModalShell } from '../ModalShell'

/**
 * Контекстное меню «Aethergram» (feature 008, T013; FR-027, FR-035, data-model
 * 1.5): единый примитив якорных меню (главное FR-007, контактное «⋯» FR-013,
 * «шестерёнка» FR-023). DOM и классы — дословно из прототипа
 * design/chats.html (`.ctx-menu.show` + `.ctx-item[.danger]` + `.ctx-sep`);
 * позиционирование — формулы прототипа: у якоря + clamp в пределы экрана
 * (8px); закрытие — клик-вне/Esc/выбор пункта; клавиатура — ArrowUp/Down,
 * Enter/Space (FR-035), возврат фокуса на инициатора; Esc не проваливается
 * в модаль ниже (data-model 3.1: меню — верхний слой).
 */

/** Габариты меню для placement-тестов (jsdom не считает layout — стаб прототипа). */
const MENU_W = 220
const MENU_H = 120

/** Якорь «в середине экрана»: left 20, top 80, размер 40×20. */
const MID_ANCHOR = new DOMRect(20, 80, 40, 20)

const ON_CLOSE = vi.fn()

/** Свежие пункты меню со шпионами (прототипные группы действий FR-013). */
function makeItems(): readonly MenuItem[] {
  return [
    { label: 'Заблокировать', onSelect: vi.fn() },
    { label: 'Удалить чат', onSelect: vi.fn(), danger: true, sepBefore: true },
    { label: 'Удалить контакт', onSelect: vi.fn(), danger: true },
  ]
}

interface HarnessProps {
  readonly initialOpen?: boolean
  readonly anchor?: DOMRect
  readonly align?: ContextMenuAlign
  readonly items?: readonly MenuItem[]
}

/** Проба-потребитель: кнопка-якорь открывает меню своим rect (как T030/T031/T054). */
function MenuHarness({
  initialOpen = false,
  anchor = MID_ANCHOR,
  align,
  items = makeItems(),
}: HarnessProps) {
  const [open, setOpen] = useState(initialOpen)
  const [rect, setRect] = useState<DOMRect | null>(initialOpen ? anchor : null)
  const openAt = (event: ReactMouseEvent<HTMLButtonElement>): void => {
    setRect(anchor ?? event.currentTarget.getBoundingClientRect())
    setOpen(true)
  }
  const close = (): void => {
    ON_CLOSE()
    setOpen(false)
  }
  return (
    <>
      <button type="button" onClick={openAt}>
        якорь
      </button>
      <ContextMenu
        open={open}
        anchor={open ? rect : null}
        items={items}
        align={align}
        onClose={close}
      />
    </>
  )
}

/** Проба для placement-формул: меню открыто с заданным якорем. */
function PlacementHarness({
  anchor,
  align,
}: {
  readonly anchor: DOMRect
  readonly align?: ContextMenuAlign
}) {
  return <ContextMenu open anchor={anchor} items={makeItems()} align={align} onClose={() => {}} />
}

function menu(): HTMLElement {
  const el = document.querySelector<HTMLElement>('.ctx-menu')
  expect(el).not.toBeNull()
  return el as HTMLElement
}

function items(): HTMLButtonElement[] {
  return Array.from(menu().querySelectorAll<HTMLButtonElement>('.ctx-item'))
}

/** Первый пункт меню (на него входит фокус при открытии). */
function firstItem(): HTMLButtonElement {
  const [item] = items()
  expect(item).toBeDefined()
  return item as HTMLButtonElement
}

let widthSpy: MockInstance<() => number>
let heightSpy: MockInstance<() => number>

beforeEach(() => {
  ON_CLOSE.mockClear()
  // jsdom не делает layout: габариты меню стабильны (min-width 200 ≤ 220).
  widthSpy = vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(MENU_W)
  heightSpy = vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(MENU_H)
  Object.defineProperty(window, 'innerWidth', { value: 1000, configurable: true })
  Object.defineProperty(window, 'innerHeight', { value: 700, configurable: true })
})

afterEach(() => {
  widthSpy.mockRestore()
  heightSpy.mockRestore()
  Object.defineProperty(window, 'innerWidth', { value: 1024, configurable: true })
  Object.defineProperty(window, 'innerHeight', { value: 768, configurable: true })
  cleanup()
})

describe('ContextMenu — DOM прототипа и пункты (FR-027, data-model 1.5)', () => {
  it('в закрытом состоянии в DOM ничего нет', () => {
    render(<MenuHarness />)
    expect(document.querySelector('.ctx-menu')).toBeNull()
  })

  it('открытое меню: .ctx-menu.show + role=menu, пункты .ctx-item с role=menuitem', () => {
    render(<MenuHarness initialOpen />)
    expect(menu().className).toBe('ctx-menu show')
    expect(menu()).toHaveAttribute('role', 'menu')
    const labels = items().map((button) => button.textContent)
    expect(labels).toEqual(['Заблокировать', 'Удалить чат', 'Удалить контакт'])
    for (const button of items()) {
      expect(button).toHaveAttribute('role', 'menuitem')
      expect(button).toHaveAttribute('type', 'button')
    }
  })

  it('danger-пункты получают класс danger, sepBefore выводит .ctx-sep перед пунктом', () => {
    render(<MenuHarness initialOpen />)
    const [block, deleteChat, deleteContact] = items()
    expect(deleteChat?.className).toBe('ctx-item danger')
    expect(deleteContact?.className).toBe('ctx-item danger')
    expect(block?.className).toBe('ctx-item')
    const seps = menu().querySelectorAll('.ctx-sep')
    expect(seps).toHaveLength(1)
    expect(seps.item(0)?.nextElementSibling).toBe(deleteChat)
  })

  it('пустой список пунктов — меню без .ctx-item и без падения', () => {
    render(<MenuHarness initialOpen items={[]} />)
    expect(items()).toHaveLength(0)
  })
})

describe('ContextMenu — позиционирование формулами прототипа (FR-027)', () => {
  it('start (по умолчанию): у левого края якоря, top = низ якоря + 6', () => {
    render(<PlacementHarness anchor={MID_ANCHOR} />)
    expect(menu().style.left).toBe('20px')
    expect(menu().style.top).toBe('106px')
  })

  it('clamp по правому краю: left ≤ innerWidth − ширина − 8', () => {
    render(<PlacementHarness anchor={new DOMRect(990, 80, 40, 20)} />)
    expect(menu().style.left).toBe(`${1000 - MENU_W - 8}px`)
  })

  it('clamp по левому краю: left ≥ 8', () => {
    render(<PlacementHarness anchor={new DOMRect(-50, 80, 70, 20)} />)
    expect(menu().style.left).toBe('8px')
  })

  it('clamp по нижнему краю: top ≤ innerHeight − высота − 8', () => {
    render(<PlacementHarness anchor={new DOMRect(20, 690, 40, 20)} />)
    expect(menu().style.top).toBe(`${700 - MENU_H - 8}px`)
  })

  it('end («шестерёнка»): прижато к правому краю якоря (r.right − ширина)', () => {
    render(<PlacementHarness anchor={new DOMRect(900, 80, 80, 20)} align="end" />)
    expect(menu().style.left).toBe(`${980 - MENU_W}px`)
    expect(menu().style.top).toBe('106px')
  })

  it('end у края экрана тоже clampится по правому краю', () => {
    render(<PlacementHarness anchor={new DOMRect(960, 80, 35, 20)} align="end" />)
    expect(menu().style.left).toBe(`${1000 - MENU_W - 8}px`)
  })
})

describe('ContextMenu — закрытие: клик-вне, повторный клик по якорю, выбор пункта (FR-027)', () => {
  it('клик-вне закрывает, клик внутри меню — нет', () => {
    render(<MenuHarness initialOpen />)
    fireEvent.click(menu().querySelector('.ctx-sep') as HTMLElement)
    expect(ON_CLOSE).not.toHaveBeenCalled()
    fireEvent.click(document.body)
    expect(ON_CLOSE).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.ctx-menu')).toBeNull()
  })

  it('клик, открывший меню, не закрывает его самомгновенно', () => {
    render(<MenuHarness />)
    fireEvent.click(screen.getByRole('button', { name: 'якорь' }))
    expect(document.querySelector('.ctx-menu')).not.toBeNull()
    expect(ON_CLOSE).not.toHaveBeenCalled()
  })

  it('повторный клик по якорю при открытом меню закрывает его', () => {
    render(<MenuHarness />)
    const opener = screen.getByRole('button', { name: 'якорь' })
    fireEvent.click(opener)
    expect(document.querySelector('.ctx-menu')).not.toBeNull()
    fireEvent.click(opener)
    expect(ON_CLOSE).toHaveBeenCalled()
    expect(document.querySelector('.ctx-menu')).toBeNull()
  })

  it('выбор пункта: сначала onClose, затем onSelect (как closeXxx(); act() прототипа)', () => {
    const onSelect = vi.fn()
    render(
      <MenuHarness
        initialOpen
        items={[
          { label: 'Мой профиль', onSelect },
          { label: 'Удалить контакт', onSelect: vi.fn(), danger: true },
        ]}
      />,
    )
    fireEvent.click(screen.getByRole('menuitem', { name: 'Мой профиль' }))
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(ON_CLOSE).toHaveBeenCalledTimes(1)
    const [closeOrder] = ON_CLOSE.mock.invocationCallOrder
    const [selectOrder] = onSelect.mock.invocationCallOrder
    expect(closeOrder).toBeDefined()
    expect(selectOrder).toBeDefined()
    expect(closeOrder as number).toBeLessThan(selectOrder as number)
    expect(document.querySelector('.ctx-menu')).toBeNull()
  })
})

describe('ContextMenu — клавиатура (FR-035, ui-behavior §1)', () => {
  it('при открытии фокус входит на первый пункт', () => {
    render(<MenuHarness initialOpen />)
    expect(document.activeElement).toBe(items()[0])
  })

  it('ArrowDown/ArrowUp двигают фокус по пунктам с заворотом', () => {
    render(<MenuHarness initialOpen />)
    const [first, second, third] = items()
    fireEvent.keyDown(menu(), { key: 'ArrowDown' })
    expect(document.activeElement).toBe(second)
    fireEvent.keyDown(menu(), { key: 'ArrowDown' })
    expect(document.activeElement).toBe(third)
    fireEvent.keyDown(menu(), { key: 'ArrowDown' })
    expect(document.activeElement).toBe(first)
    fireEvent.keyDown(menu(), { key: 'ArrowUp' })
    expect(document.activeElement).toBe(third)
  })

  it('Enter активирует сфокусированный пункт и закрывает меню', () => {
    const onSelect = vi.fn()
    render(<MenuHarness initialOpen items={[{ label: 'Контакты', onSelect }]} />)
    fireEvent.keyDown(firstItem(), { key: 'Enter' })
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(ON_CLOSE).toHaveBeenCalledTimes(1)
  })

  it('Space активирует сфокусированный пункт и закрывает меню', () => {
    const onSelect = vi.fn()
    render(<MenuHarness initialOpen items={[{ label: 'Контакты', onSelect }]} />)
    fireEvent.keyDown(firstItem(), { key: ' ' })
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(ON_CLOSE).toHaveBeenCalledTimes(1)
  })

  it('Esc закрывает меню; после закрытия фокус возвращается на инициатора', () => {
    render(<MenuHarness />)
    const opener = screen.getByRole('button', { name: 'якорь' })
    opener.focus()
    fireEvent.click(opener)
    expect(menu().contains(document.activeElement)).toBe(true)
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Escape' })
    expect(ON_CLOSE).toHaveBeenCalledTimes(1)
    expect(document.activeElement).toBe(opener)
  })

  it('Esc не проваливается в открытую модаль ниже (data-model 3.1), а после — закрывает её', () => {
    const onModalClose = vi.fn()
    render(
      <>
        <ModalShell formId="profile" title="Мой профиль" onClose={onModalClose}>
          <input placeholder="username" />
        </ModalShell>
        <MenuHarness initialOpen />
      </>,
    )
    fireEvent.keyDown(firstItem(), { key: 'Escape' })
    expect(ON_CLOSE).toHaveBeenCalledTimes(1)
    expect(onModalClose).not.toHaveBeenCalled()
    // Меню закрыто — модаль теперь верхний слой, её Esc работает.
    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(onModalClose).toHaveBeenCalledTimes(1)
  })
})
