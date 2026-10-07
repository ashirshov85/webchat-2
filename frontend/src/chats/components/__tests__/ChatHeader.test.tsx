import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import type { GroupMember } from '../../../api/groups'
import { ChatHeader } from '../ChatHeader'
import { ModalShell } from '../../../ui/ModalShell'

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
 * «В сети»/«офлайн» from `usePresenceStatus(peerId)` (T040 contract);
 * bug 12 (T090): the neutral «unknown» renders NOTHING — no lamp, no
 * text — while the EMPTY `.status-row` keeps the header height
 * (min-height of chat-header.css) until №36 converges; group —
 * «N участников» (pluralRu) whose hover/focus
 * opens the `.members-tip` roster: «Вы» first, then the №28 members
 * minus me — the split role marks of bug 18 (T096): owner → «владелец»,
 * admin → «админ», member — no mark, the «Вы» row follows the same
 * scheme by myRole (FR-017; the 006 role model is finer than the
 * prototype demo data — its single «администратор» mark). The tip
 * closes on mouseleave/blur and Esc (the top layer of data-model 3.1).
 *
 * US4 (T054): the ONLY right-side control is the «шестерёнка»
 * ChatGearMenu — the per-kind item sets live in their own suite
 * (ChatGearMenu.test.tsx); here the embedding is pinned: the direct
 * entries fire the page callbacks (№21/№23-24/№14 — the confirm shell
 * belongs to the page, T034), the group matrix follows myRole
 * (№33/№30/«Участники»/«Редактировать чат»), and a group without a
 * role (no №28, no basis) carries no gear at all.
 */

const presence = vi.hoisted(() => ({ status: 'unknown' }))

vi.mock('../../../presence/usePresence', () => ({
  usePresenceStatus: () => presence.status,
}))

const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const ME = '11111111-1111-1111-1111-111111111111'

function directChat(
  overrides: Partial<{
    peerId: string
    username: string
    blockedByMe: boolean
    peerInContacts: boolean
  }> = {},
) {
  return {
    kind: 'direct' as const,
    peerId: ALICE,
    username: 'alice',
    blockedByMe: false,
    peerInContacts: true,
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

/** The gear callbacks of the page (T054) — every entry is a plain wire. */
function headerProps() {
  return {
    onAddContact: vi.fn(),
    onToggleBlock: vi.fn(),
    onDeleteChat: vi.fn(),
    onOpenMembers: vi.fn(),
    onOpenEdit: vi.fn(),
    onLeaveChat: vi.fn(),
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
  it('unknown: НИ лампы, НИ текста — пустая .status-row держит высоту (bug 12/T090)', () => {
    const { container } = renderHeader(directChat())

    expect(screen.queryByText('неизвестно')).toBeNull()
    expect(screen.queryByText('офлайн')).toBeNull()
    expect(container.querySelector('.chat-head .status-row .lamp')).toBeNull()
    expect(container.querySelector('.chat-head .status-row .status-txt')).toBeNull()
    // Строка остаётся — якорь высоты (min-height в chat-header.css):
    // заголовок не прыгает, когда №36 сойдётся к online/offline.
    expect(container.querySelector('.chat-head .status-row')).not.toBeNull()
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

describe('ChatHeader offline lamp: no flicker (bug 5 / T083, parity with .av-dot.off)', () => {
  /**
   * The offline header lamp must be a «выключенная лампочка» — dim and
   * STATIC, exactly like `.av-dot.off` (avatar.css, prototype line 197:
   * `animation: none`). The base `.chat-head .lamp` rule carries
   * `animation: flick 4s infinite` and the `.off` override used to
   * re-colour the lamp without silencing the animation — the offline
   * lamp kept blinking (bug 5). The ONLINE lamp keeps flickering, and
   * reduced-motion stays the global FR-004 silencer of machine.css.
   *
   * Vitest resolves component CSS imports as no-ops and jsdom does not
   * cascade real stylesheets, so the contract is pinned statically at
   * its source of truth — the stylesheet text, the same way FR-001
   * treats design/chats.html as normative.
   */
  const headerCss = readFileSync(join(import.meta.dirname, '../chat-header.css'), 'utf8')
  const avatarCss = readFileSync(join(import.meta.dirname, '../../../ui/avatar.css'), 'utf8')

  /** Extracts the declarations block of an exact selector from css text. */
  function ruleBody(css: string, selector: string): string {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const match = css.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
    if (!match) throw new Error(`rule not found: ${selector}`)
    return match[1] ?? ''
  }

  it('.lamp.off is dim AND static — animation: none (the bug 5 fix)', () => {
    const off = ruleBody(headerCss, '.chat-head .lamp.off')

    expect(off).toContain('animation: none')
    expect(off).toContain('background:')
    expect(off).toContain('box-shadow:')
  })

  it('the online .lamp keeps flickering; .av-dot.off parity holds in avatar.css', () => {
    expect(ruleBody(headerCss, '.chat-head .lamp')).toContain('animation: flick 4s infinite')
    expect(ruleBody(avatarCss, '.av-dot.off')).toContain('animation: none')
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

  it('метки ролей (T096): owner → «владелец», admin → «админ», member — без метки; «Вы» — по myRole', () => {
    const { container } = renderHeader(liveOwnerGroup())
    fireEvent.mouseEnter(statusRow(container))

    const rows = Array.from(tip()?.querySelectorAll('.mt-row') ?? [])
    // «Вы» (myRole owner) → «владелец»; alice (member) — без метки;
    // bob (admin) → «админ» — модель ролей 006 точнее одной метки
    // демо-прототипа (bug 18).
    expect(rows[0]?.querySelector('.mt-me')?.textContent).toBe('владелец')
    expect(rows[1]?.querySelector('.mt-me')).toBeNull()
    expect(rows[2]?.querySelector('.mt-me')?.textContent).toBe('админ')
  })

  it('зритель-админ: «Вы» несёт «админ» — та же схема меток по myRole (T096)', () => {
    const { container } = renderHeader(
      groupChat({
        members: [
          rosterMember(ME, 'me', 'admin'),
          rosterMember(ALICE, 'alice', 'member'),
          rosterMember(BOB, 'bob', 'owner'),
        ],
        myRole: 'admin',
        meUserId: ME,
        meUsername: 'me',
      }),
    )
    fireEvent.mouseEnter(statusRow(container))

    const rows = Array.from(tip()?.querySelectorAll('.mt-row') ?? [])
    expect(rows[0]?.querySelector('.mt-me')?.textContent).toBe('админ')
    expect(rows[2]?.querySelector('.mt-me')?.textContent).toBe('владелец')
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
    expect(rows[2]?.querySelector('.mt-me')?.textContent).toBe('владелец')
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
    expect(row).toBeInstanceOf(HTMLElement)
    fireEvent.mouseEnter(row as HTMLElement)
    expect(tip()).toBeNull()
  })
})

describe('ChatHeader members-tip: clamp at screen edges and the Esc top layer (T046, FR-027/FR-035 edge case, data-model 3.1)', () => {
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

  /** Габариты подсказки для clamp-тестов (jsdom не считает layout — стаб). */
  const TIP_W = 240
  const TIP_H = 160

  let widthSpy: MockInstance<() => number>
  let heightSpy: MockInstance<() => number>

  beforeEach(() => {
    widthSpy = vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(TIP_W)
    heightSpy = vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(TIP_H)
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

  /** Открывает подсказку с якорем-строкой статуса в заданном rect (формулы showMembersTip). */
  function openTipAt(container: HTMLElement, rect: DOMRect): void {
    vi.spyOn(statusRow(container), 'getBoundingClientRect').mockReturnValue(rect)
    fireEvent.mouseEnter(statusRow(container))
    expect(tip()).not.toBeNull()
  }

  it('в середине экрана: у левого края якоря, top = низ якоря + 6 (формулы прототипа)', () => {
    const { container } = renderHeader(liveOwnerGroup())
    openTipAt(container, new DOMRect(20, 80, 120, 16))

    expect(tip()?.style.left).toBe('20px')
    expect(tip()?.style.top).toBe('102px')
  })

  it('clamp по правому краю: left ≤ innerWidth − ширина − 8, не обрезается', () => {
    const { container } = renderHeader(liveOwnerGroup())
    openTipAt(container, new DOMRect(990, 80, 40, 16))

    expect(tip()?.style.left).toBe(`${1000 - TIP_W - 8}px`)
  })

  it('clamp по левому краю: left ≥ 8', () => {
    const { container } = renderHeader(liveOwnerGroup())
    openTipAt(container, new DOMRect(-50, 80, 70, 16))

    expect(tip()?.style.left).toBe('8px')
  })

  it('clamp по нижнему краю: top ≤ innerHeight − высота − 8', () => {
    const { container } = renderHeader(liveOwnerGroup())
    openTipAt(container, new DOMRect(20, 690, 120, 16))

    expect(tip()?.style.top).toBe(`${700 - TIP_H - 8}px`)
  })

  it('Esc закрывает подсказку над открытой модалью, не проваливаясь в неё; следующий Esc — модаль', () => {
    const onModalClose = vi.fn()
    const { container } = render(
      <>
        <ModalShell formId="profile" title="Мой профиль" onClose={onModalClose}>
          <input placeholder="username" />
        </ModalShell>
        <ChatHeader chat={liveOwnerGroup()} {...headerProps()} />
      </>,
    )
    openTipAt(container, new DOMRect(20, 80, 120, 16))

    // Esc на строке-якоре (клавиатурный путь FR-035): capture-слушатель
    // подсказки гасит событие — модаль ниже не закрывается (data-model 3.1).
    fireEvent.keyDown(statusRow(container), { key: 'Escape' })
    expect(tip()).toBeNull()
    expect(onModalClose).not.toHaveBeenCalled()

    // Подсказка закрыта — модаль теперь верхний слой, её Esc работает.
    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(onModalClose).toHaveBeenCalledTimes(1)
  })
})

describe('ChatHeader embeds the ChatGearMenu — the ONLY header button (T054, FR-016, ui-behavior §4)', () => {
  /** Прототип #btnGear: title «Настройки чата» — открывает меню чата. */
  function openGearMenu(): HTMLElement {
    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
    return screen.getByRole('menu')
  }

  it('direct: the gear opens the menu; each item fires its page action and closes it', () => {
    const { props } = renderHeader(directChat({ peerInContacts: false }))
    const gear = screen.getByRole('button', { name: 'Настройки чата' })
    expect(gear).toHaveAttribute('aria-haspopup', 'menu')
    expect(gear).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(gear)
    expect(screen.getByRole('menu')).toBeVisible()
    expect(gear).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(screen.getByRole('menuitem', { name: 'Добавить в контакты' }))
    expect(props.onAddContact).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).toBeNull()

    fireEvent.click(gear)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать контакт' }))
    expect(props.onToggleBlock).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('menu')).toBeNull()

    fireEvent.click(gear)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить чат' }))
    expect(props.onDeleteChat).toHaveBeenCalledTimes(1)
  })

  it('direct blocked: the mark stays and the toggle flips to «Разблокировать контакт»', () => {
    renderHeader(directChat({ blockedByMe: true }))

    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))

    expect(screen.getByText('заблокирован')).toBeVisible()
    expect(screen.getByRole('menuitem', { name: 'Разблокировать контакт' })).toBeVisible()
    expect(screen.queryByRole('menuitem', { name: 'Заблокировать контакт' })).toBeNull()
  })

  it('group owner: «Участники»/«Редактировать чат»/danger «Удалить чат» fire their wires (№33 hidden)', () => {
    const { props } = renderHeader(groupChat({ myRole: 'owner' }))

    openGearMenu()
    expect(screen.getByRole('menuitem', { name: 'Удалить чат' }).classList.contains('danger')).toBe(
      true,
    )
    expect(screen.queryByRole('menuitem', { name: 'Выйти из чата' })).toBeNull()

    fireEvent.click(screen.getByRole('menuitem', { name: 'Участники' }))
    expect(props.onOpenMembers).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Редактировать чат' }))
    expect(props.onOpenEdit).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить чат' }))
    expect(props.onDeleteChat).toHaveBeenCalledTimes(1)
    expect(props.onLeaveChat).not.toHaveBeenCalled()
  })

  it('group member: danger «Выйти из чата» fires onLeaveChat; edit/delete hidden (not_group_owner)', () => {
    const { props } = renderHeader(groupChat({ myRole: 'member' }))

    openGearMenu()
    expect(screen.queryByRole('menuitem', { name: 'Редактировать чат' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Удалить чат' })).toBeNull()

    fireEvent.click(screen.getByRole('menuitem', { name: 'Выйти из чата' }))
    expect(props.onLeaveChat).toHaveBeenCalledTimes(1)
    expect(props.onDeleteChat).not.toHaveBeenCalled()
  })

  it('group without a role (no №28, no basis): no gear — the matrix needs the role', () => {
    const { container } = renderHeader(groupChat())

    expect(screen.queryByRole('button', { name: 'Настройки чата' })).toBeNull()
    expect(container.querySelector('.chat-head .ch-btns')).toBeNull()
  })
})
