import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { GroupMember } from '../../../api/groups'
import { GroupMembersModal } from '../GroupMembersModal'

/**
 * Модальная форма «Участники» (feature 008, US4, T049 → T055 → T095/Bug 17;
 * FR-023, ui-behavior §3, research §D): миграция поведенческих ожиданий 006
 * из MemberList.test.tsx (ростер-действия и роли — БЕЗ изменений, FR-034/
 * SC-002: метки «Владелец»/«Админ»/«Участник», aria-label'ы «Исключить/
 * Назначить админом/Снять админа/Передать владение {username}», матрица
 * видимости по myRole, disable строки pendingUserId из мьютекса
 * useGroupMembers) на обитателя №membersForm нормативного прототипа
 * specs/008-chat-window-styling/design/chats.html.
 *
 * Отличия прототипа (закрепляются здесь как новый контракт T055):
 * собственная строка НЕ выводится вовсе (renderMembersList прототипа
 * пропускает ME — «Вы» живёт только в members-tip заголовка; старая
 * пометка «{username} (вы)» уходит вместе с own-строкой) и строка
 * участника вне адресной книги получает «Добавить в контакты» — №21 и
 * его тост принадлежат странице (T045: тост выдаёт владелец операции),
 * модаль только сообщает userId.
 *
 * T095 (Bug 17): строки — визуальный паритет «Контактов» (.pick-row
 * лексика ctc-row: аватар, .c-main/.c-top/.c-name, метка роли в .c-prev),
 * а ростер-действия переезжают из текстовых кнопок строки в ContextMenu
 * кнопки-кебаба «⋯» (title «Действия с участником», паттерн .c-menu
 * ContactsModal). Матрица ожиданий БЕЗ изменений (FR-034) — вход
 * действий теперь через «⋯»-меню: aria-label'ы `…{username}` живут на
 * пунктах меню (SC-002), «Исключить» — danger-пункт, «Добавить в
 * контакты» — пункт вне-книжных строк (кнопка .add-ctc-btn прототипа
 * ушла вместе с текстовыми кнопками).
 */

type Problem = components['schemas']['Problem']

const ME = '11111111-1111-1111-1111-111111111111'
const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'
const DAVE = '44444444-4444-4444-4444-444444444444'

function user(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function member(id: string, username: string, role: GroupMember['role']): GroupMember {
  return { user: user(id, username), role, joinedAt: '2026-09-20T12:00:00.000Z' }
}

/** alice=owner, bob=admin, carol=member — the §3.3 quickstart roster. */
function roster(): GroupMember[] {
  return [
    member(ME, 'alice', 'owner'),
    member(BOB, 'bob', 'admin'),
    member(CAROL, 'carol', 'member'),
  ]
}

/** По умолчанию вся адресная книга покрывает ростер — «Добавить в
 *  контакты» не предлагается, тесты матрицы действий чисты от него. */
const ALL_MEMBERS_IN_CONTACTS = (): ReadonlySet<string> => new Set([ME, BOB, CAROL, DAVE])

function renderModal(overrides: Partial<Parameters<typeof GroupMembersModal>[0]> = {}) {
  const props = {
    members: roster(),
    myRole: 'owner' as GroupMember['role'],
    currentUserId: ME,
    onKick: vi.fn(),
    onSetRole: vi.fn(),
    onTransferOwnership: vi.fn(),
    pendingUserId: null,
    rosterError: null,
    contactUserIds: ALL_MEMBERS_IN_CONTACTS(),
    onAddContact: vi.fn(),
    ...overrides,
  }
  render(<GroupMembersModal {...props} />)
  return props
}

/** Строка участника по имени (хук .member-row, имя — в .c-name). */
function rowOf(username: string): HTMLElement {
  const row = Array.from(document.querySelectorAll<HTMLElement>('.member-row')).find(
    (node) => node.querySelector('.c-name')?.textContent === username,
  )
  if (row === undefined) {
    throw new Error(`строка участника не найдена: ${username}`)
  }
  return row
}

/**
 * T095: вход ростер-действий — «⋯»-кебаб строки (title «Действия с
 * участником», паттерн .c-menu ContactsModal); меню — портал в body.
 */
function openMemberMenu(username: string): HTMLElement {
  const menuButton = within(rowOf(username)).getByRole('button', {
    name: 'Действия с участником',
  })
  fireEvent.click(menuButton)
  return screen.getByRole('menu')
}

function menuLabels(): string[] {
  return screen.getAllByRole('menuitem').map((item) => item.textContent ?? '')
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('GroupMembersModal roster rendering (FR-003, membersForm)', () => {
  it('renders every member EXCEPT the own row, with the 006 role labels', () => {
    renderModal({ myRole: 'owner' })

    expect(screen.getByText('bob')).toBeVisible()
    expect(screen.getByText('Админ')).toBeVisible()
    expect(screen.getByText('carol')).toBeVisible()
    expect(screen.getByText('Участник')).toBeVisible()
    // membersForm прототипа: «Вы» не выводится — даже как «(вы)»-строка.
    expect(screen.queryByText('alice')).toBeNull()
    expect(screen.queryByText(/alice \(вы\)/)).toBeNull()
    // Владелец — это сам зритель: метки «Владелец» в списке нет.
    expect(screen.queryByText('Владелец')).toBeNull()
  })

  it("renders the «Владелец» label on another member's owner row", () => {
    renderModal({ myRole: 'member', currentUserId: CAROL })

    expect(screen.getByText('alice')).toBeVisible()
    expect(screen.getByText('Владелец')).toBeVisible()
    expect(screen.getByText('bob')).toBeVisible()
    expect(screen.getByText('Админ')).toBeVisible()
    // Собственная строка (carol) не выводится.
    expect(screen.queryByText('carol')).toBeNull()
  })
})

describe('GroupMembersModal action visibility by myRole (FR-004, entry via «⋯» T095)', () => {
  it('owner: offers kick/grant-admin/transfer on a member row and reports the callbacks', () => {
    const props = renderModal()

    // Порядок пунктов — дословно T049/T055; «Исключить» — danger-пункт.
    openMemberMenu('carol')
    expect(menuLabels()).toEqual(['Исключить', 'Назначить админом', 'Передать владение'])
    expect(screen.getByRole('menuitem', { name: 'Исключить carol' }).className).toBe(
      'ctx-item danger',
    )
    fireEvent.click(screen.getByRole('menuitem', { name: 'Исключить carol' }))
    expect(props.onKick).toHaveBeenCalledWith(CAROL)

    openMemberMenu('carol')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Назначить админом carol' }))
    expect(props.onSetRole).toHaveBeenCalledWith(CAROL, 'admin')

    openMemberMenu('carol')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Передать владение carol' }))
    expect(props.onTransferOwnership).toHaveBeenCalledWith(CAROL)
  })

  it('owner: offers revoke-admin on an admin row (№34 grant AND revoke)', () => {
    const props = renderModal()

    openMemberMenu('bob')
    expect(menuLabels()).toEqual(['Исключить', 'Снять админа', 'Передать владение'])
    fireEvent.click(screen.getByRole('menuitem', { name: 'Снять админа bob' }))
    expect(props.onSetRole).toHaveBeenCalledWith(BOB, 'member')

    openMemberMenu('bob')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Исключить bob' }))
    expect(props.onKick).toHaveBeenCalledWith(BOB)

    openMemberMenu('bob')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Передать владение bob' }))
    expect(props.onTransferOwnership).toHaveBeenCalledWith(BOB)
  })

  it('owner: the own row is absent — no self-actions at all (№32 self is self_forbidden)', () => {
    renderModal()

    expect(screen.queryByRole('button', { name: /alice/ })).toBeNull()
  })

  it('admin: kicks only plain members — admin/owner rows carry no «⋯» at all', () => {
    renderModal({
      members: [
        member(ME, 'alice', 'owner'),
        member(BOB, 'bob', 'admin'),
        member(CAROL, 'carol', 'member'),
        member(DAVE, 'dave', 'admin'),
      ],
      myRole: 'admin',
      currentUserId: BOB,
    })

    // member-строка — единственная с действиями: в её меню только «Исключить».
    openMemberMenu('carol')
    expect(menuLabels()).toEqual(['Исключить'])
    expect(screen.getByRole('menuitem', { name: 'Исключить carol' })).toBeInTheDocument()

    // admin/owner-строки кебаба не несут вовсе (№34/№35 owner-only,
    // not_group_owner) — кнопок действий у строк нет.
    expect(within(rowOf('dave')).queryByRole('button')).toBeNull()
    expect(within(rowOf('alice')).queryByRole('button')).toBeNull()
  })

  it('member: renders no roster actions at all (forbidden_role, 006 US3-3)', () => {
    renderModal({ myRole: 'member', currentUserId: CAROL })

    // «⋯» не выводится вовсе (матрица myRole); адресная книга покрывает
    // ростер — пунктов «Добавить в контакты» нет: кнопок в модали НЕТ.
    expect(screen.queryByRole('button')).toBeNull()
    expect(screen.queryByRole('menu')).toBeNull()
  })
})

describe('GroupMembersModal pending row (useGroupMembers mutex UI)', () => {
  it('disables the in-flight member row’s «⋯» while the other rows stay armed', () => {
    renderModal({ pendingUserId: CAROL })

    const carolButton = within(rowOf('carol')).getByRole('button', {
      name: 'Действия с участником',
    })
    const bobButton = within(rowOf('bob')).getByRole('button', {
      name: 'Действия с участником',
    })
    expect(carolButton).toBeDisabled()
    expect(bobButton).toBeEnabled()
  })
})

describe('GroupMembersModal roster-action problem (useGroupMembers error half)', () => {
  it('renders the last roster-action problem as an alert (role_hierarchy_violation)', () => {
    renderModal({
      rosterError: {
        title: 'Forbidden',
        status: 403,
        errors: { role: ['role_hierarchy_violation'] },
      } satisfies Problem,
    })

    expect(screen.getByRole('alert')).toHaveTextContent('role_hierarchy_violation')
  })
})

describe('GroupMembersModal «Добавить в контакты» (ui-behavior §3, пункт «⋯»-меню T095)', () => {
  it('offers «Добавить в контакты» only in the menus of rows outside the adder’s book', () => {
    const props = renderModal({ contactUserIds: new Set([BOB]) })

    // Только carol вне адресной книги — её меню несёт пункт (после
    // разделителя: ростер-действия и книга — разные группы действий),
    // меню bob (в книге) пункта не имеет.
    openMemberMenu('carol')
    expect(menuLabels()).toEqual([
      'Исключить',
      'Назначить админом',
      'Передать владение',
      'Добавить в контакты',
    ])
    const menu = screen.getByRole('menu')
    expect(menu.querySelectorAll('.ctx-sep')).toHaveLength(1)

    fireEvent.click(screen.getByRole('menuitem', { name: 'Добавить в контакты' }))
    expect(props.onAddContact).toHaveBeenCalledTimes(1)
    expect(props.onAddContact).toHaveBeenCalledWith(CAROL)

    openMemberMenu('bob')
    expect(menuLabels()).not.toContain('Добавить в контакты')
  })

  it('renders no offer when every member is already a contact (№20 cover)', () => {
    renderModal({ contactUserIds: ALL_MEMBERS_IN_CONTACTS() })

    for (const username of ['bob', 'carol']) {
      openMemberMenu(username)
      expect(menuLabels()).not.toContain('Добавить в контакты')
    }
  })
})
