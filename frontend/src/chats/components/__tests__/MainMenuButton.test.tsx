import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MainMenuButton } from '../MainMenuButton'

/**
 * Кнопка-«меню» строки поиска (T030, FR-007) + пункт «Выйти» (T045,
 * follow-up Phase 10): последний пункт главного меню — logout текущей
 * сессии. Кнопка остаётся чистым входом без бизнес-логики — «Выйти»,
 * как «Мой профиль»/«Контакты»/«Создать групповой чат», лишь поднимает
 * колбэк `onLogout`; сам revoke (POST /auth/logout, очистка токенов,
 * редирект на /login) принадлежит странице (MessengerPage, паттерн
 * единых форм T034). Ревока — ТОЛЬКО текущей параллельной сессии
 * (002 FR-011); пункт в кебаб чата (ChatGearMenu) не дублируется.
 */

function renderMenuButton(overrides: Partial<Parameters<typeof MainMenuButton>[0]> = {}) {
  const props = {
    onOpenProfile: vi.fn(),
    onOpenContacts: vi.fn(),
    onCreateGroup: vi.fn(),
    onLogout: vi.fn(),
    ...overrides,
  }
  render(<MainMenuButton {...props} />)
  return props
}

function openMenu() {
  // Прототип #menuBtn: title «Меню» — единственная кнопка панели.
  fireEvent.click(screen.getByRole('button', { name: 'Меню' }))
  return screen.getByRole('menu')
}

function itemLabels(): string[] {
  return screen.getAllByRole('menuitem').map((item) => item.textContent)
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('MainMenuButton «Выйти» item (T045)', () => {
  it('renders «Выйти» as the LAST menu item after the prototype items', () => {
    renderMenuButton()
    openMenu()

    expect(itemLabels()).toEqual(['Мой профиль', 'Контакты', 'Создать групповой чат', 'Выйти'])
  })

  it('clicking «Выйти» fires onLogout exactly once and closes the menu', () => {
    const props = renderMenuButton()
    openMenu()

    fireEvent.click(screen.getByRole('menuitem', { name: 'Выйти' }))

    expect(props.onLogout).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('«Выйти» does not touch the modal-form callbacks', () => {
    const props = renderMenuButton()
    openMenu()

    fireEvent.click(screen.getByRole('menuitem', { name: 'Выйти' }))

    expect(props.onOpenProfile).not.toHaveBeenCalled()
    expect(props.onOpenContacts).not.toHaveBeenCalled()
    expect(props.onCreateGroup).not.toHaveBeenCalled()
  })
})
