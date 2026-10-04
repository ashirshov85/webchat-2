import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GroupMember } from '../../../api/groups'
import { ChatHeader } from '../ChatHeader'

/**
 * The open dialog header rebuilt per the prototype (feature 008, US1
 * T021 → US3 T043; FR-016/FR-017, data-model 2.3): the `.chat-head`
 * block of design/chats.html §7 — avatar + `.chat-title` (name +
 * status-row) + the right-side controls. The avatar derives from the
 * peer username (circle) or the group title (octagon) via ui/Avatar
 * (FR-024) and recalculates on renames; the name keeps the 004
 * `dialog-title` h2 hook (research §C, FR-034).
 *
 * US3 (T043) delivered the FULL status row: direct — the prototype
 * `.lamp` (+ `.off` ONLY for a true store offline — never a false one
 * for «unknown», семантика 007) with the `.status-txt` label
 * «В сети»/«офлайн»/«неизвестно» from `usePresenceStatus(peerId)`
 * (T040 contract); group — «N участников» (pluralRu) whose hover/focus
 * opens the `.members-tip` roster: «Вы» first (mark «администратор»
 * when owning), then the №28 members minus me with the mark for
 * owner/admin roles (FR-017). The tip closes on mouseleave/blur and
 * Esc (the top layer of data-model 3.1). The «шестерёнка» ChatGearMenu
 * is US4 (T054) — until then the 004/006 controls («Действия» menu,
 * «Информация о группе» toggle) keep their behavioral expectations
 * (SC-002, FR-034).
 */

const presence = vi.hoisted(() => ({ status: 'unknown' }))

vi.mock('../../../presence/usePresence', () => ({
  usePresenceStatus: () => presence.status,
}))

const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const ME = '11111111-1111-1111-1111-111111111111'

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

function groupChat(
  overrides: Partial<{
    title: string
    memberCount: number | null
    members: readonly GroupMember[] | null
    myRole: GroupMember['role'] | null
    meUserId: string | null
    meUsername: string | null
  }> = {},
) {
  return {
    kind: 'group' as const,
    title: 'Проект Альфа',
    memberCount: 3,
    members: null,
    myRole: null,
    meUserId: null,
    meUsername: null,
    ...overrides,
  }
}

/** №28 roster row (the page passes the live GroupView members). */
function rosterMember(id: string, username: string, role: GroupMember['role']): GroupMember {
  return {
    user: {
      id,
      username,
      email: `${username}@example.com`,
      status: 'active',
      createdAt: '2026-09-01T00:00:00.000Z',
    },
    role,
    joinedAt: '2026-09-20T12:00:00.000Z',
  }
}

/** The live №28 basis of the members-tip tests: me-owner + alice + admin-bob. */
function ownerRoster(): readonly GroupMember[] {
  return [
    rosterMember(ME, 'me', 'owner'),
    rosterMember(ALICE, 'alice', 'member'),
    rosterMember(BOB, 'bob', 'admin'),
  ]
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

describe('ChatHeader direct status: the prototype lamp (T043, FR-016, data-model 2.3)', () => {
  it('shows the neutral «неизвестно» status — lamp WITHOUT .off, never a false «офлайн»', () => {
    const { container } = renderHeader(directChat())

    expect(screen.getByText('неизвестно')).toBeVisible()
    expect(screen.queryByText('офлайн')).toBeNull()
    const lamp = container.querySelector('.chat-head .status-row .lamp')
    expect(lamp).not.toBeNull()
    expect(lamp?.classList.contains('off')).toBe(false)
  })

  it('online: lamp without .off + «В сети»; offline: .lamp.off + «офлайн» (store truth)', () => {
    presence.status = 'online'
    const online = renderHeader(directChat())
    expect(online.container.querySelector('.status-row .lamp')?.classList.contains('off')).toBe(
      false,
    )
    expect(online.getByText('В сети')).toBeVisible()
    online.unmount()

    presence.status = 'offline'
    const offline = renderHeader(directChat())
    expect(offline.container.querySelector('.status-row .lamp')?.className).toBe('lamp off')
    expect(offline.getByText('офлайн')).toBeVisible()
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

    expect(container.querySelector('.status-row .lamp')).toBeNull()
    expect(screen.queryByText('неизвестно')).toBeNull()
  })
})

describe('ChatHeader members-tip (T043, FR-017, data-model 2.3, ui-behavior §4)', () => {
  /** The tip is portaled to document.body (the prototype #membersTip) — screen-level lookup. */
  function tip(): HTMLElement | null {
    return document.querySelector('.members-tip')
  }

  /** The group status row — the tip anchor (hover/focus source). */
  function statusRow(container: HTMLElement): HTMLElement {
    const row = container.querySelector('.chat-head .status-row')
    if (!(row instanceof HTMLElement)) {
      throw new Error('status-row не найден')
    }
    return row
  }

  function liveOwnerGroup() {
    return groupChat({ members: ownerRoster(), myRole: 'owner', meUserId: ME, meUsername: 'me' })
  }

  it('hover on the group status opens the tip: «Участники», «Вы» first, members minus me', () => {
    const { container } = renderHeader(liveOwnerGroup())

    expect(tip()).toBeNull()
    fireEvent.mouseEnter(statusRow(container))

    const opened = tip()
    expect(opened).not.toBeNull()
    expect(opened?.classList.contains('show')).toBe(true)
    expect(opened?.querySelector('.mt-title')?.textContent).toBe('Участники')

    const rows = Array.from(opened?.querySelectorAll('.mt-row') ?? [])
    expect(rows).toHaveLength(3)
    // «Вы» — первым (FR-017); собственная строка №28 не дублируется.
    expect(rows[0]?.querySelector('.mt-name')?.textContent).toBe('Вы')
    expect(rows[0]?.querySelector('.avatar .av-in span')?.textContent).toBe('M')
    expect(rows.slice(1).map((row) => row.querySelector('.mt-name')?.textContent)).toEqual([
      'alice',
      'bob',
    ])
  })

  it('marks «администратор»: «Вы» при владении, участники с ролью owner/admin', () => {
    const { container } = renderHeader(liveOwnerGroup())
    fireEvent.mouseEnter(statusRow(container))

    const rows = Array.from(tip()?.querySelectorAll('.mt-row') ?? [])
    expect(rows[0]?.querySelector('.mt-me')?.textContent).toBe('администратор')
    // alice (member) — без пометки; bob (admin) — с пометкой.
    expect(rows[1]?.querySelector('.mt-me')).toBeNull()
    expect(rows[2]?.querySelector('.mt-me')?.textContent).toBe('администратор')
  })

  it('a non-owner viewer: «Вы» carries no mark, the №28 owner row does', () => {
    const { container } = renderHeader(
      groupChat({
        members: [
          rosterMember(ME, 'me', 'member'),
          rosterMember(ALICE, 'alice', 'member'),
          rosterMember(BOB, 'bob', 'owner'),
        ],
        myRole: 'member',
        meUserId: ME,
        meUsername: 'me',
      }),
    )
    fireEvent.mouseEnter(statusRow(container))

    const rows = Array.from(tip()?.querySelectorAll('.mt-row') ?? [])
    expect(rows.map((row) => row.querySelector('.mt-name')?.textContent)).toEqual([
      'Вы',
      'alice',
      'bob',
    ])
    expect(rows[0]?.querySelector('.mt-me')).toBeNull()
    expect(rows[1]?.querySelector('.mt-me')).toBeNull()
    expect(rows[2]?.querySelector('.mt-me')?.textContent).toBe('администратор')
  })

  it('closes on mouseleave', () => {
    const { container } = renderHeader(liveOwnerGroup())
    const row = statusRow(container)
    fireEvent.mouseEnter(row)
    expect(tip()).not.toBeNull()

    fireEvent.mouseLeave(row)
    expect(tip()).toBeNull()
  })

  it('keyboard parity (FR-035): focus opens the tip, blur and Esc close it', () => {
    const { container } = renderHeader(liveOwnerGroup())
    const row = statusRow(container)
    expect(row).toHaveAttribute('tabindex', '0')

    fireEvent.focus(row)
    expect(tip()).not.toBeNull()

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(tip()).toBeNull()

    fireEvent.focus(row)
    expect(tip()).not.toBeNull()

    fireEvent.blur(row)
    expect(tip()).toBeNull()
  })

  it('no roster (№28 not live): the status row is no tip source — not focusable, hover is a no-op', () => {
    const { container } = renderHeader(groupChat({ memberCount: 3 }))
    const row = statusRow(container)

    expect(row).not.toHaveAttribute('tabindex')
    fireEvent.mouseEnter(row)
    fireEvent.focus(row)
    expect(tip()).toBeNull()
  })

  it('the direct header never opens a members-tip', () => {
    const { container } = renderHeader(directChat())
    const row = container.querySelector('.chat-head .status-row')
    expect(row instanceof HTMLElement).toBe(true)
    fireEvent.mouseEnter(row as HTMLElement)
    expect(tip()).toBeNull()
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
