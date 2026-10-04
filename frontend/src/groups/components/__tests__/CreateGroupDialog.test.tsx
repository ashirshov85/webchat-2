import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ContactView } from '../../../api/chats'
import { ToastProvider } from '../../../ui/Toast'
import { CreateGroupDialog } from '../CreateGroupDialog'

/**
 * Модальная форма «Новый групповой чат» (feature 008, US2, T035; FR-023,
 * ui-behavior §3, research §D): проекция #grpForm нормативного прототипа
 * specs/008-chat-window-styling/design/chats.html — житель ЕДИНОЙ
 * оболочки ModalShell (T034, MessengerPage). Поведенческие ожидания 006
 * сохранены (FR-034): клиентская валидация названия (trim 1–64,
 * groups/validation.ts — невалидный черновик не доходит до №27),
 * мультивыбор участников из №20-контактов, один атомарный №27
 * `createGroup` (обрезанный title + memberUserIds в порядке выбора),
 * серверная проблема (422 not_in_contacts) видна БЕЗ закрытия формы.
 *
 * От прототипа (FR-001 — норматив): поля описания в создании НЕТ
 * (группа из формы — только название + участники; описание — grpEditForm
 * T056); пустой подбор участников отклоняется («Выберите хотя бы одного
 * участника из контактов» — порядок проверок прототипа: участники,
 * затем название); живой фильтр подборщика по username/email без
 * регистра с сохранением выбора; пустые состояния «Нет контактов —
 * сначала добавьте контакт» / «Ничего не найдено»; успех — ровно один
 * тост «Групповой чат создан — {title}» (FR-025) + onCreated.
 */

type GroupView = components['schemas']['GroupView']
type Problem = components['schemas']['Problem']

const { mockListContacts, mockCreateGroup } = vi.hoisted(() => ({
  mockListContacts: vi.fn(),
  mockCreateGroup: vi.fn(),
}))

vi.mock('../../../api/chats', () => ({
  listContacts: mockListContacts,
}))

vi.mock('../../../api/groups', () => ({
  createGroup: mockCreateGroup,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const CAROL = '44444444-4444-4444-4444-444444444444'
const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'

function peer(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function contacts(): ContactView[] {
  return [
    { user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z' },
    { user: peer(BOB, 'bob'), createdAt: '2026-09-03T00:00:00.000Z' },
    { user: peer(CAROL, 'carol'), createdAt: '2026-09-04T00:00:00.000Z' },
  ]
}

function groupView(): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: null,
    myRole: 'owner',
    members: [
      { user: peer(ME, 'me'), role: 'owner', joinedAt: '2026-09-20T12:00:00.000Z' },
      { user: peer(ALICE, 'alice'), role: 'member', joinedAt: '2026-09-20T12:00:00.000Z' },
    ],
  }
}

/** useToast требует ToastProvider — слот тоста проверяем в том же дереве. */
function renderDialog(props: { onCreated?: (group: GroupView) => void; onCancel?: () => void }) {
  render(
    <ToastProvider>
      <CreateGroupDialog {...props} />
    </ToastProvider>,
  )
}

function fillTitle(value: string) {
  fireEvent.change(screen.getByLabelText('Название'), { target: { value } })
}

function fillSearch(value: string) {
  fireEvent.change(screen.getByPlaceholderText('Поиск контакта — имя, username или email'), {
    target: { value },
  })
}

function pick(username: string) {
  fireEvent.click(screen.getByRole('checkbox', { name: `Выбрать ${username}` }))
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Создать' }))
}

async function renderReady() {
  mockListContacts.mockResolvedValueOnce(contacts())
  renderDialog({})
  await screen.findByRole('checkbox', { name: 'Выбрать alice' })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('CreateGroupDialog grpForm-проекция прототипа (T035, FR-001)', () => {
  it('renders Название + фильтр + подборщик №20-контактов (строки с чекбоксом и подписью)', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    renderDialog({})

    expect(screen.getByLabelText('Название')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Название группового чата')).toBeInTheDocument()
    expect(screen.getByText('Участники')).toBeInTheDocument()
    expect(
      screen.getByPlaceholderText('Поиск контакта — имя, username или email'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Создать' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeInTheDocument()

    const aliceRow = await screen.findByRole('checkbox', { name: 'Выбрать alice' })
    expect(aliceRow).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Выбрать bob' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Выбрать carol' })).toBeInTheDocument()
    // Строка прототипа: имя + подпись «username · email».
    expect(
      within(aliceRow.closest('label') as HTMLElement).getByText('alice · alice@example.com'),
    ).toBeInTheDocument()
  })

  it('renders empty states: нет контактов / ничего не найдено (renderPickList прототипа)', async () => {
    mockListContacts.mockResolvedValueOnce([])
    renderDialog({})
    await screen.findByText('Нет контактов — сначала добавьте контакт')

    // №20 пришёл — фильтр-промах даёт своё пустое состояние.
    mockListContacts.mockResolvedValueOnce(contacts())
    cleanup()
    renderDialog({})
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })
    fillSearch('нет-такого')
    expect(await screen.findByText('Ничего не найдено')).toBeInTheDocument()
  })

  it('filters the picker live by username/email case-insensitively and keeps picks across filters', async () => {
    await renderReady()

    fillSearch('AL')
    expect(screen.getByRole('checkbox', { name: 'Выбрать alice' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Выбрать bob' })).toBeNull()
    expect(screen.queryByRole('checkbox', { name: 'Выбрать carol' })).toBeNull()

    fillSearch('BOB@EXAMPLE.COM')
    expect(screen.getByRole('checkbox', { name: 'Выбрать bob' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Выбрать alice' })).toBeNull()

    // Выбор переживает фильтр: снятый с показа контакт остаётся выбранным.
    fillSearch('carol')
    pick('carol')
    fillSearch('')
    expect(screen.getByRole('checkbox', { name: 'Выбрать carol' })).toBeChecked()
  })

  it('№20 failure renders .modal-err with «Повторить» that refetches (expectation 004)', async () => {
    mockListContacts.mockRejectedValueOnce({
      title: 'Server Error',
      status: 500,
      detail: 'network down',
    } satisfies Problem)
    renderDialog({})

    expect(await screen.findByRole('alert')).toHaveTextContent(/network down/i)

    mockListContacts.mockResolvedValueOnce(contacts())
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(await screen.findByRole('checkbox', { name: 'Выбрать alice' })).toBeInTheDocument()
  })

  it('«Отмена» hands control back to the shell owner (onCancel)', async () => {
    const onCancel = vi.fn()
    mockListContacts.mockResolvedValueOnce(contacts())
    renderDialog({ onCancel })
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })
})

describe('CreateGroupDialog валидация (006 + прототип, FR-001/FR-002)', () => {
  it('rejects an empty participant pick without calling №27 (прототип: участники — первая проверка)', async () => {
    await renderReady()

    fillTitle('Проект Альфа')
    submit()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Выберите хотя бы одного участника из контактов',
    )
    expect(mockCreateGroup).not.toHaveBeenCalled()
  })

  it('rejects an empty or whitespace-only title without calling №27 (validateGroupTitle 006)', async () => {
    await renderReady()
    pick('alice')

    submit()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockCreateGroup).not.toHaveBeenCalled()

    fillTitle('   ')
    submit()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockCreateGroup).not.toHaveBeenCalled()
  })

  it('rejects a 65-character title without calling №27', async () => {
    await renderReady()
    pick('alice')

    fillTitle('a'.repeat(65))
    submit()

    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockCreateGroup).not.toHaveBeenCalled()
  })
})

describe('CreateGroupDialog №27 submission (FR-002, FR-025)', () => {
  it('accepts the 64-boundary, trims the title and submits the picked members in pick order', async () => {
    await renderReady()

    fillTitle(`  ${'a'.repeat(64)}  `)
    pick('carol')
    pick('alice')
    submit()

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockCreateGroup).toHaveBeenCalledWith({
      title: 'a'.repeat(64),
      memberUserIds: [CAROL, ALICE],
    })
  })

  it('a toggled-off pick leaves the batch (мультивыбор 006)', async () => {
    await renderReady()

    fillTitle('Проект Альфа')
    pick('alice')
    pick('bob')
    pick('alice')
    submit()

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockCreateGroup).toHaveBeenCalledWith({
      title: 'Проект Альфа',
      memberUserIds: [BOB],
    })
  })

  it('shows exactly one toast and hands the GroupView to onCreated (№27 201)', async () => {
    const onCreated = vi.fn()
    const view = groupView()
    mockListContacts.mockResolvedValueOnce(contacts())
    mockCreateGroup.mockResolvedValueOnce(view)
    renderDialog({ onCreated })
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('Проект Альфа')
    pick('alice')
    submit()

    await waitFor(() => {
      expect(onCreated).toHaveBeenCalledWith(view)
    })
    expect(await screen.findByText('Групповой чат создан — Проект Альфа')).toBeInTheDocument()
  })

  it('renders the 422 problem and keeps the form open with the draft (not_in_contacts)', async () => {
    mockListContacts.mockResolvedValueOnce(contacts())
    mockCreateGroup.mockRejectedValueOnce({
      title: 'Unprocessable Entity',
      status: 422,
      errors: { memberUserIds: ['not_in_contacts'] },
    } satisfies Problem)
    renderDialog({})
    await screen.findByRole('checkbox', { name: 'Выбрать alice' })

    fillTitle('Проект Альфа')
    pick('alice')
    submit()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('not_in_contacts')
    expect(screen.getByLabelText('Название')).toHaveValue('Проект Альфа')
    expect(screen.getByRole('checkbox', { name: 'Выбрать alice' })).toBeChecked()
  })
})
