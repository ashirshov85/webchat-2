import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChatGearMenu } from '../ChatGearMenu'
import type { GearMenuChat } from '../ChatGearMenu'

/**
 * Кнопка-«шестерёнка» и меню чата (feature 008, US4, T049 → T054;
 * FR-016/FR-023, ui-behavior §4, research §D): миграция поведенческих
 * ожиданий 006 из LeaveDeleteControls.test.tsx (FR-034/SC-002: матрица
 * достижимости №33/№30 по myRole — владельцу недоступен уход
 * (owner_must_transfer), не-владельцу — удаление (not_group_owner))
 * в пункты меню #chatMenu нормативного прототипа specs/008-chat-
 * window-styling/design/chats.html (openChatMenu: «Удалить чат»/
 * «Выйти из чата» — danger).
 *
 * Меню — только вход: №14/№21/№23/№24/№33/№30 и их подтверждения
 * (ConfirmDialog поверх оболочки, T034) принадлежат странице; каждая
 * ветка §4 закреплена своим колбэком. Закрытие — слушатели ContextMenu
 * (повторный клик, клик-вне, Esc, выбор пункта), якорь — rect кнопки.
 */

type DirectGearChat = Extract<GearMenuChat, { kind: 'direct' }>

function directChat(overrides: Partial<DirectGearChat> = {}): DirectGearChat {
  const base: DirectGearChat = { kind: 'direct', peerInContacts: true, blockedByMe: false }
  return { ...base, ...overrides }
}

function groupChat(myRole: Extract<GearMenuChat, { kind: 'group' }>['myRole']): GearMenuChat {
  return { kind: 'group', myRole }
}

function renderGearMenu(
  chat: GearMenuChat,
  overrides: Partial<Omit<Parameters<typeof ChatGearMenu>[0], 'chat'>> = {},
) {
  const props = {
    chat,
    onAddContact: vi.fn(),
    onToggleBlock: vi.fn(),
    onDeleteChat: vi.fn(),
    onOpenMembers: vi.fn(),
    onOpenEdit: vi.fn(),
    onLeaveChat: vi.fn(),
    ...overrides,
  }
  render(<ChatGearMenu {...props} />)
  return props
}

function openMenu() {
  // Прототип #btnGear: title «Настройки чата» — единственная кнопка
  // заголовка (FR-016).
  fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
  return screen.getByRole('menu')
}

function itemLabels(): string[] {
  return screen.getAllByRole('menuitem').map((item) => item.textContent)
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('ChatGearMenu direct items (ui-behavior §4)', () => {
  it('peer NOT in contacts: «Добавить в контакты» + «Заблокировать контакт» + danger «Удалить чат»', () => {
    renderGearMenu(directChat({ peerInContacts: false }))
    openMenu()

    expect(itemLabels()).toEqual(['Добавить в контакты', 'Заблокировать контакт', 'Удалить чат'])
    expect(screen.getByRole('menuitem', { name: 'Удалить чат' })).toHaveClass('danger')
  })

  it('peer already in contacts: no «Добавить в контакты» item', () => {
    renderGearMenu(directChat({ peerInContacts: true }))
    openMenu()

    expect(itemLabels()).toEqual(['Заблокировать контакт', 'Удалить чат'])
  })

  it('blocked peer: the toggle flips to «Разблокировать контакт»', () => {
    renderGearMenu(directChat({ blockedByMe: true }))
    openMenu()

    expect(itemLabels()).toEqual(['Разблокировать контакт', 'Удалить чат'])
  })
})

describe('ChatGearMenu group items (миграция LeaveDeleteControls, FR-005/006)', () => {
  it('owner: «Участники» + «Редактировать чат» + danger «Удалить чат» — №33 уход недоступен (owner_must_transfer)', () => {
    renderGearMenu(groupChat('owner'))
    openMenu()

    expect(itemLabels()).toEqual(['Участники', 'Редактировать чат', 'Удалить чат'])
    expect(screen.queryByRole('menuitem', { name: 'Выйти из чата' })).toBeNull()
    expect(screen.getByRole('menuitem', { name: 'Удалить чат' })).toHaveClass('danger')
  })

  it('admin: the owner/admin set — №29/№30 доступны, №33 скрыт', () => {
    renderGearMenu(groupChat('admin'))
    openMenu()

    expect(itemLabels()).toEqual(['Участники', 'Редактировать чат', 'Удалить чат'])
    expect(screen.queryByRole('menuitem', { name: 'Выйти из чата' })).toBeNull()
  })

  it('member: «Участники» + danger «Выйти из чата» — удаление/редактирование скрыты (not_group_owner)', () => {
    renderGearMenu(groupChat('member'))
    openMenu()

    expect(itemLabels()).toEqual(['Участники', 'Выйти из чата'])
    expect(screen.queryByRole('menuitem', { name: 'Редактировать чат' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Удалить чат' })).toBeNull()
    expect(screen.getByRole('menuitem', { name: 'Выйти из чата' })).toHaveClass('danger')
  })
})

describe('ChatGearMenu item selection (FR-023 wiring, ui-behavior §4)', () => {
  it('group owner: each item fires its callback and closes the menu', () => {
    const props = renderGearMenu(groupChat('owner'))
    openMenu()

    fireEvent.click(screen.getByRole('menuitem', { name: 'Участники' }))
    expect(props.onOpenMembers).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).toBeNull()

    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Редактировать чат' }))
    expect(props.onOpenEdit).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).toBeNull()

    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить чат' }))
    expect(props.onDeleteChat).toHaveBeenCalledTimes(1)
    expect(props.onLeaveChat).not.toHaveBeenCalled()
  })

  it('group member: «Выйти из чата» fires onLeaveChat — the №33 entry point', () => {
    const props = renderGearMenu(groupChat('member'))
    openMenu()

    fireEvent.click(screen.getByRole('menuitem', { name: 'Выйти из чата' }))

    expect(props.onLeaveChat).toHaveBeenCalledTimes(1)
    expect(props.onDeleteChat).not.toHaveBeenCalled()
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('direct: «Добавить в контакты»/«Заблокировать контакт» fire their callbacks', () => {
    const props = renderGearMenu(directChat({ peerInContacts: false }))
    openMenu()

    fireEvent.click(screen.getByRole('menuitem', { name: 'Добавить в контакты' }))
    expect(props.onAddContact).toHaveBeenCalledTimes(1)

    openMenu()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать контакт' }))
    expect(props.onToggleBlock).toHaveBeenCalledTimes(1)
  })
})

describe('ChatGearMenu open/close (прототип #btnGear, FR-035)', () => {
  it('renders the «Настройки чата» gear button; a repeated click and Escape close the menu', () => {
    renderGearMenu(directChat())
    const gear = screen.getByRole('button', { name: 'Настройки чата' })
    expect(gear).toHaveAttribute('aria-haspopup', 'menu')

    fireEvent.click(gear)
    expect(screen.getByRole('menu')).toBeVisible()
    expect(gear).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(gear)
    expect(screen.queryByRole('menu')).toBeNull()

    fireEvent.click(gear)
    expect(screen.getByRole('menu')).toBeVisible()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
  })
})
