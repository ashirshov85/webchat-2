import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChatHeader } from '../ChatHeader'

/**
 * The open dialog header rebuilt per the prototype (feature 008, US1,
 * T021; FR-016 base part, data-model 2.3): the `.chat-head` block of
 * design/chats.html §7 — avatar + `.chat-title` (name + status-row) +
 * the right-side controls. The avatar derives from the peer username
 * (circle) or the group title (octagon) via ui/Avatar (FR-024) and
 * recalculates on renames; the name keeps the 004 `dialog-title` h2
 * hook (research §C, FR-034).
 *
 * The BASIC status rides existing data only: direct — the 007
 * PresenceIndicator with its visible label (neutral «неизвестно»
 * before the first №36 snapshot, never a false «офлайн»); group —
 * «N участников» with the prototype pluralRu. The presence-лампа
 * styling and the members-tip are US3 (T043), the «шестерёнка»
 * ChatGearMenu is US4 (T054) — until then the 004/006 controls
 * («Действия» menu, «Информация о группе» toggle) keep their
 * behavioral expectations (SC-002, FR-034).
 */

const presence = vi.hoisted(() => ({ status: 'unknown' }))

vi.mock('../../../presence/usePresence', () => ({
  usePresenceStatus: () => presence.status,
}))

const ALICE = '22222222-2222-2222-2222-222222222222'

function directChat(
  overrides: Partial<{ peerId: string; username: string; blockedByMe: boolean }> = {},
) {
  return {
    kind: 'direct' as const,
    peerId: ALICE,
    username: 'alice',
    blockedByMe: false,
    ...overrides,
  }
}

function groupChat(overrides: Partial<{ title: string; memberCount: number | null }> = {}) {
  return {
    kind: 'group' as const,
    title: 'Проект Альфа',
    memberCount: 3,
    ...overrides,
  }
}

function headerProps(overrides: Partial<{ menuOpen: boolean; groupInfoOpen: boolean }> = {}) {
  return {
    menuOpen: false,
    onToggleMenu: vi.fn(),
    onDeleteChat: vi.fn(),
    onToggleBlock: vi.fn(),
    groupInfoOpen: false,
    onToggleGroupInfo: vi.fn(),
    ...overrides,
  }
}

type HeaderProps = ReturnType<typeof headerProps>

function renderHeader(
  chat: ReturnType<typeof directChat> | ReturnType<typeof groupChat>,
  props: HeaderProps = headerProps(),
) {
  const view = render(<ChatHeader chat={chat} {...props} />)
  return { ...view, props }
}

afterEach(() => {
  cleanup()
  presence.status = 'unknown'
})

describe('ChatHeader prototype block (T021, FR-016 base, data-model 2.3)', () => {
  it('renders the direct header: circle avatar from the peer username, h2 dialog-title, presence label', () => {
    const { container } = renderHeader(directChat())

    const head = container.querySelector('.chat-head') as HTMLElement
    expect(head).not.toBeNull()

    // Avatar: circle of the peer username, initials «A» (FR-024).
    expect(head.querySelector('.head-av .avatar:not(.oct)')).not.toBeNull()
    expect(head.querySelector('.avatar .av-in span')?.textContent).toBe('A')

    // Name: the 004 hook stays — h2.dialog-title (FR-034).
    expect(screen.getByRole('heading', { level: 2, name: 'alice' })).toHaveClass(
      'dialog-title',
      'chat-name',
    )
  })

  it('renders the group header: octagon avatar from the title, group title heading', () => {
    const { container } = renderHeader(groupChat())

    const head = container.querySelector('.chat-head') as HTMLElement
    expect(head.querySelector('.head-av .avatar.oct')).not.toBeNull()
    expect(head.querySelector('.avatar .av-in span')?.textContent).toBe('ПА')
    expect(screen.getByRole('heading', { level: 2, name: 'Проект Альфа' })).toHaveClass(
      'dialog-title',
      'chat-name',
    )
  })
})

describe('ChatHeader basic status on existing data (FR-016 base part)', () => {
  it('shows the neutral «неизвестно» presence of the 007 header surface — never a false «офлайн»', () => {
    renderHeader(directChat())

    expect(screen.getByText('неизвестно')).toBeVisible()
    expect(screen.queryByText('офлайн')).toBeNull()
    expect(screen.getByRole('img', { name: 'неизвестно' })).toBeInTheDocument()
  })

  it('follows the presence store status of the peer', () => {
    presence.status = 'online'
    renderHeader(directChat())

    expect(screen.getByText('онлайн')).toBeVisible()
  })

  it('pluralizes the group status «N участников» (pluralRu boundaries)', () => {
    renderHeader(groupChat({ memberCount: 3 }))
    expect(screen.getByText('3 участника')).toHaveClass('status-txt')

    cleanup()
    renderHeader(groupChat({ memberCount: 1 }))
    expect(screen.getByText('1 участник')).toBeInTheDocument()

    cleanup()
    renderHeader(groupChat({ memberCount: 5 }))
    expect(screen.getByText('5 участников')).toBeInTheDocument()

    cleanup()
    renderHeader(groupChat({ memberCount: 12 }))
    expect(screen.getByText('12 участников')).toBeInTheDocument()
  })

  it('renders no group status while memberCount is unknown (empty status row)', () => {
    renderHeader(groupChat({ memberCount: null }))

    expect(screen.queryByText(/участник/)).toBeNull()
  })

  it('carries no presence UI in the group header (006/007: groups carry none)', () => {
    const { container } = renderHeader(groupChat())

    expect(container.querySelector('.presence-indicator')).toBeNull()
    expect(screen.queryByText('неизвестно')).toBeNull()
  })
})

describe('ChatHeader interim controls keep the 004/006 behavior (SC-002, FR-034)', () => {
  it('toggles the direct «Действия» menu and fires №14/№23-24 actions', () => {
    const { unmount, props } = renderHeader(directChat())

    // The menu is page-controlled (menuOpen prop): the closed render
    // only fires the toggle — the 004 MessengerPage wiring keeps it.
    const toggle = screen.getByRole('button', { name: 'Действия' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('menu', { name: 'Действия с чатом' })).toBeNull()

    fireEvent.click(toggle)
    expect(props.onToggleMenu).toHaveBeenCalledTimes(1)

    // The open render carries both items; each fires its action.
    unmount()
    cleanup()
    const { props: openProps } = renderHeader(directChat(), headerProps({ menuOpen: true }))

    expect(screen.getByRole('button', { name: 'Действия' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить чат' }))
    expect(openProps.onDeleteChat).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать пользователя' }))
    expect(openProps.onToggleBlock).toHaveBeenCalledTimes(1)
  })

  it('shows the blocker mark and the unblock item of a blocked direct chat', () => {
    renderHeader(directChat({ blockedByMe: true }), headerProps({ menuOpen: true }))

    expect(screen.getByText('заблокирован')).toBeVisible()
    expect(screen.getByRole('menuitem', { name: 'Разблокировать пользователя' })).toBeVisible()
  })

  it('toggles «Информация о группе» from the group header and carries no direct menu there', () => {
    const { props } = renderHeader(groupChat())

    const toggle = screen.getByRole('button', { name: 'Информация о группе' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', 'dialog-group-card')

    fireEvent.click(toggle)
    expect(props.onToggleGroupInfo).toHaveBeenCalledTimes(1)

    expect(screen.queryByRole('button', { name: 'Действия' })).toBeNull()
  })
})
