import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ContactView } from '../../../api/chats'
import type { GroupMember } from '../../../api/groups'
import { ToastProvider } from '../../../ui/Toast'
import { GroupEditModal } from '../GroupEditModal'

/**
 * Модальная форма «Редактировать групповой чат» (feature 008, US4,
 * T049 → T056; FR-023, ui-behavior §3, research §D): миграция
 * поведенческих ожиданий 006 из GroupInfoPanel.rename.test.tsx и
 * GroupInfoPanel.add-members.test.tsx (FR-034/SC-002: клиентская
 * валидация №29 — trim 1–64/≤256, тексты /64//256/, обрезка title и
 * пропуск пустого description, 403 forbidden_role при живом черновике,
 * тост «Групповой чат обновлён — {title}» из T045; №20-загрузка
 * предложения с «Загрузка контактов…»/сбоем/«Повторить», исключение
 * активных из предложения — идемпотентность №31) на проекцию
 * #grpEditForm нормативного прототипа specs/008-chat-window-styling/
 * design/chats.html.
 *
 * Отличия grpEditForm (закрепляются как контракт T056): черновик состава
 * — ростер №28 МИНУС собственная строка (уход — «Выйти из чата»
 * шестерёнки, №32 на себе self_forbidden), добавление — кнопкой «+»
 * строки предложения (renderGrpEditAdd прототипа), удаление — «✕»
 * строки черновика (renderGrpEditMembers, title «Удалить участника»);
 * №20 грузится на монтировании; порядок проверок прототипа — сперва
 * ≥1 участника («В групповом чате должен остаться хотя бы один
 * участник»), затем название 006; submit — №29 всегда + №31 батчем
 * добавленных + №32 по каждому удалённому; пустые состояния предложения
 * — «Все контакты уже в чате» / «Ничего не найдено» (тексты
 * renderGrpEditAdd прототипа).
 */

type GroupView = components['schemas']['GroupView']
type Problem = components['schemas']['Problem']

const { mockListContacts, mockUpdateGroup, mockAddMembers, mockKickMember } = vi.hoisted(() => ({
  mockListContacts: vi.fn(),
  mockUpdateGroup: vi.fn(),
  mockAddMembers: vi.fn(),
  mockKickMember: vi.fn(),
}))

vi.mock('../../../api/chats', () => ({
  listContacts: mockListContacts,
}))

vi.mock('../../../api/groups', () => ({
  updateGroup: mockUpdateGroup,
  addMembers: mockAddMembers,
  kickMember: mockKickMember,
}))

const ME = '11111111-1111-1111-1111-111111111111'
const BOB = '22222222-2222-2222-2222-222222222222'
const CAROL = '33333333-3333-3333-3333-333333333333'
const DAVE = '44444444-4444-4444-4444-444444444444'
const EVE = '55555555-5555-5555-5555-555555555555'
const GROUP_ID = '7dc5dc5d-dc5d-4dc5-8dc5-dc5dc5dc5dc5'

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

/** alice=owner, bob/carol=members — базис черновика состава. */
function roster(): GroupMember[] {
  return [
    member(ME, 'alice', 'owner'),
    member(BOB, 'bob', 'member'),
    member(CAROL, 'carol', 'member'),
  ]
}

/** №20-книга редактирующего — dave/eve ещё не в группе. */
function offerContacts(): ContactView[] {
  return [
    { user: user(DAVE, 'dave'), createdAt: '2026-09-02T00:00:00.000Z', blockedByMe: false },
    { user: user(EVE, 'eve'), createdAt: '2026-09-03T00:00:00.000Z', blockedByMe: false },
  ]
}

function groupView(overrides: Partial<GroupView> = {}): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: 'рабочая группа',
    myRole: 'owner',
    members: roster(),
    ...overrides,
  }
}

/** Пропсы последнего рендера — колбэки успеха/закрытия формы. */
let draftProps: ReturnType<typeof renderModal>

/** useToast требует ToastProvider — слот тоста проверяем в том же дереве. */
function renderModal(overrides: Partial<Parameters<typeof GroupEditModal>[0]> = {}) {
  // №20 грузится на монтировании: нейтральный ответ по умолчанию, если
  // тест не управляет mock'ом сам (контролируемые промисы/сбои ниже).
  if (mockListContacts.getMockImplementation() === undefined) {
    mockListContacts.mockResolvedValue(offerContacts())
  }
  const props = {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: 'рабочая группа',
    members: roster(),
    onUpdated: vi.fn(),
    onMembersAdded: vi.fn(),
    onMemberRemoved: vi.fn(),
    onCancel: vi.fn(),
    ...overrides,
  }
  render(
    <ToastProvider>
      <GroupEditModal {...props} />
    </ToastProvider>,
  )
  draftProps = props
  return props
}

function fillTitle(value: string) {
  fireEvent.change(screen.getByLabelText('Название'), { target: { value } })
}

function fillDescription(value: string) {
  fireEvent.change(screen.getByLabelText('Описание'), { target: { value } })
}

function fillSearch(value: string) {
  fireEvent.change(screen.getByPlaceholderText('Добавить контакт — имя, username или email'), {
    target: { value },
  })
}

function addContactRow(username: string) {
  fireEvent.click(screen.getByRole('button', { name: `Добавить участника ${username}` }))
}

function removeMemberRow(username: string) {
  fireEvent.click(screen.getByRole('button', { name: `Удалить участника ${username}` }))
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))
}

/** Открытая форма с готовым предложением №20 (dave/eve). */
async function renderReady() {
  mockListContacts.mockResolvedValue(offerContacts())
  renderModal()
  await screen.findByRole('button', { name: 'Добавить участника dave' })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('GroupEditModal grpEditForm-проекция (T056: черновик + №20-предложение)', () => {
  it('opens prefilled with the №28 metadata and the roster minus the own row', async () => {
    await renderReady()

    expect(screen.getByLabelText('Название')).toHaveValue('Проект Альфа')
    expect(screen.getByLabelText('Описание')).toHaveValue('рабочая группа')
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeInTheDocument()
    // Черновик состава: bob/carol с «✕», собственной строки нет.
    expect(screen.getByRole('button', { name: 'Удалить участника bob' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Удалить участника carol' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Удалить участника alice' })).toBeNull()
    // Предложение №20: активные не предлагаются — только dave/eve.
    expect(screen.getByRole('button', { name: 'Добавить участника dave' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Добавить участника eve' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добавить участника bob' })).toBeNull()
  })

  it('loads №20 on mount: «Загрузка контактов…» gives way to the offer (T072 regression core)', async () => {
    // Управляемый ответ №20: релиз строго ПОСЛЕ монтирования — ответ
    // не может гоняться с эффектом загрузки; зависание «Загрузка
    // контактов…» возможно только если эффект теряет собственный ответ.
    let releaseContacts!: (list: ContactView[]) => void
    mockListContacts.mockImplementationOnce(
      () =>
        new Promise<ContactView[]>((resolve) => {
          releaseContacts = resolve
        }),
    )
    renderModal()

    expect(mockListContacts).toHaveBeenCalledTimes(1)
    expect(screen.getByText('Загрузка контактов…')).toBeInTheDocument()

    await act(async () => {
      releaseContacts(offerContacts())
      await Promise.resolve()
    })

    expect(screen.getByRole('button', { name: 'Добавить участника dave' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Добавить участника eve' })).toBeInTheDocument()
    expect(screen.queryByText('Загрузка контактов…')).toBeNull()
  })

  it('renders the №20 problem with «Повторить»; the retry re-queries and then renders the offer', async () => {
    mockListContacts
      .mockRejectedValueOnce({
        title: 'Internal Server Error',
        status: 500,
      } satisfies Problem)
      .mockResolvedValueOnce(offerContacts())
    renderModal()

    expect(await screen.findByRole('button', { name: 'Повторить' })).toBeInTheDocument()
    expect(mockListContacts).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    expect(
      await screen.findByRole('button', { name: 'Добавить участника dave' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('Загрузка контактов…')).toBeNull()
    expect(mockListContacts).toHaveBeenCalledTimes(2)
  })

  it('filters the offer live and renders the prototype empty states (renderGrpEditAdd)', async () => {
    await renderReady()

    fillSearch('нет-такого')
    expect(await screen.findByText('Ничего не найдено')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добавить участника dave' })).toBeNull()

    fillSearch('EVE@EXAMPLE.COM')
    expect(screen.getByRole('button', { name: 'Добавить участника eve' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добавить участника dave' })).toBeNull()

    // Пустая №20-книга при непустом ростере — «все уже в чате»
    // (промах фильтра и отсутствие контактов — разные состояния).
    mockListContacts.mockReset()
    mockListContacts.mockResolvedValue([])
    cleanup()
    renderModal()
    expect(await screen.findByText('Все контакты уже в чате')).toBeInTheDocument()
  })

  it('«Отмена» hands control back to the shell owner (onCancel)', async () => {
    await renderReady()

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(draftProps.onCancel).toHaveBeenCalledTimes(1)
  })
})

describe('GroupEditModal composition draft (№31 «+» / №32 «✕», grpEditForm)', () => {
  it('moves a contact into the draft by «+» — the offer drops them at once', async () => {
    await renderReady()

    addContactRow('dave')

    expect(screen.getByRole('button', { name: 'Удалить участника dave' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Добавить участника dave' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Добавить участника eve' })).toBeInTheDocument()
  })

  it('removes a member from the draft by «✕»', async () => {
    await renderReady()

    removeMemberRow('carol')

    expect(screen.queryByRole('button', { name: 'Удалить участника carol' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Удалить участника bob' })).toBeInTheDocument()
  })

  it('submit with additions fires ONE atomic №31 and hands the returned roster up', async () => {
    const added = [member(DAVE, 'dave', 'member')]
    mockAddMembers.mockResolvedValueOnce(added)
    await renderReady()

    addContactRow('dave')
    fillTitle('Новое название')
    submit()

    await waitFor(() => {
      expect(mockAddMembers).toHaveBeenCalledTimes(1)
    })
    expect(mockAddMembers).toHaveBeenCalledWith(GROUP_ID, { userIds: [DAVE] })
    expect(mockKickMember).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(draftProps.onMembersAdded).toHaveBeenCalledTimes(1)
    })
    expect(draftProps.onMembersAdded).toHaveBeenCalledWith(added)
  })

  it('submit with removals fires №32 per removed member and reports onMemberRemoved', async () => {
    mockKickMember.mockResolvedValueOnce(undefined)
    await renderReady()

    removeMemberRow('bob')
    fillTitle('Новое название')
    submit()

    await waitFor(() => {
      expect(mockKickMember).toHaveBeenCalledWith(GROUP_ID, BOB)
    })
    expect(mockAddMembers).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(draftProps.onMemberRemoved).toHaveBeenCalledTimes(1)
    })
  })

  it('a metadata-only submit fires №29 alone — no empty №31/№32 rides along', async () => {
    mockUpdateGroup.mockResolvedValueOnce(groupView({ title: 'Новое название' }))
    await renderReady()

    fillTitle('Новое название')
    submit()

    await waitFor(() => {
      expect(mockUpdateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockAddMembers).not.toHaveBeenCalled()
    expect(mockKickMember).not.toHaveBeenCalled()
  })
})

describe('GroupEditModal validation (006 + порядок прототипа)', () => {
  it('rejects an emptied roster without any API call — participants go first (прототип)', async () => {
    await renderReady()

    removeMemberRow('bob')
    removeMemberRow('carol')
    fillTitle('')
    submit()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'В групповом чате должен остаться хотя бы один участник',
    )
    expect(mockUpdateGroup).not.toHaveBeenCalled()
    expect(mockAddMembers).not.toHaveBeenCalled()
    expect(mockKickMember).not.toHaveBeenCalled()
  })

  it('rejects an empty or whitespace-only title without calling №29 (validateGroupTitle 006)', async () => {
    await renderReady()

    fillTitle('')
    submit()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()

    fillTitle('   ')
    submit()
    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()
  })

  it('rejects a 65-character title without calling №29', async () => {
    await renderReady()

    fillTitle('a'.repeat(65))
    submit()

    expect(screen.getByRole('alert')).toHaveTextContent(/64/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()
  })

  it('rejects a 257-character description without calling №29', async () => {
    await renderReady()

    fillTitle('Новое название')
    fillDescription('d'.repeat(257))
    submit()

    expect(screen.getByRole('alert')).toHaveTextContent(/256/)
    expect(mockUpdateGroup).not.toHaveBeenCalled()
  })
})

describe('GroupEditModal submission (№29 + состав, FR-025)', () => {
  it('trims the title and omits the description when empty', async () => {
    await renderReady()

    fillTitle(`  Новое название  `)
    fillDescription('')
    submit()

    await waitFor(() => {
      expect(mockUpdateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockUpdateGroup).toHaveBeenCalledWith(GROUP_ID, { title: 'Новое название' })
  })

  it('accepts the 64/256 boundary and submits the trimmed description', async () => {
    await renderReady()

    fillTitle(`  ${'a'.repeat(64)}  `)
    fillDescription(`  ${'d'.repeat(256)}  `)
    submit()

    await waitFor(() => {
      expect(mockUpdateGroup).toHaveBeenCalledTimes(1)
    })
    expect(mockUpdateGroup).toHaveBeenCalledWith(GROUP_ID, {
      title: 'a'.repeat(64),
      description: 'd'.repeat(256),
    })
  })

  it('hands the №29 GroupView up via onUpdated (the page closes the shell)', async () => {
    const view = groupView({ title: 'Новое название', description: null })
    mockUpdateGroup.mockResolvedValueOnce(view)
    await renderReady()

    fillTitle('Новое название')
    submit()

    await waitFor(() => {
      expect(draftProps.onUpdated).toHaveBeenCalledWith(view)
    })
    expect(draftProps.onCancel).not.toHaveBeenCalled()
  })

  it('№29 успех — ровно один тост «Групповой чат обновлён — {title}» (T045, FR-025)', async () => {
    mockUpdateGroup.mockResolvedValueOnce(groupView({ title: 'Новое название' }))
    await renderReady()

    fillTitle('Новое название')
    submit()

    // Слот един, открыт и несёт текст тоста прототипа (grpEditForm);
    // вторая выдача заменила бы текст — выдача ровно одна.
    const slot = (await screen.findByText('Групповой чат обновлён — Новое название')).closest(
      '.toast',
    ) as HTMLElement
    expect(slot).toHaveClass('show')
    expect(document.querySelectorAll('.toast')).toHaveLength(1)
  })

  it('№29 сбой — тоста нет: ошибка в форме, черновик жив (контракт форм)', async () => {
    mockUpdateGroup.mockRejectedValueOnce({
      title: 'Forbidden',
      status: 403,
      errors: { role: ['forbidden_role'] },
    } satisfies Problem)
    await renderReady()

    fillTitle('Новое название')
    submit()

    expect(await screen.findByRole('alert')).toHaveTextContent('forbidden_role')
    const toasts = document.querySelectorAll('.toast')
    expect(toasts).toHaveLength(1)
    expect(toasts[0]).not.toHaveClass('show')
    expect(toasts[0]).toHaveTextContent('')
    // Черновик жив: название и состав не потеряны.
    expect(screen.getByLabelText('Название')).toHaveValue('Новое название')
    expect(screen.getByRole('button', { name: 'Удалить участника bob' })).toBeInTheDocument()
  })

  it('№31 сбой (422 not_in_contacts) — ошибка в форме, черновик жив', async () => {
    // Тост-полутон №29 не проверяем: владелец операции решает сам,
    // помечать ли частично сошедший submit (T056) — контракт ошибки
    // один: .modal-err при живом черновике, без закрытия формы.
    mockAddMembers.mockRejectedValueOnce({
      title: 'Unprocessable Entity',
      status: 422,
      errors: { memberUserIds: ['not_in_contacts'] },
    } satisfies Problem)
    await renderReady()

    addContactRow('dave')
    fillTitle('Новое название')
    submit()

    expect(await screen.findByRole('alert')).toHaveTextContent('not_in_contacts')
    expect(screen.getByRole('button', { name: 'Удалить участника dave' })).toBeInTheDocument()
    expect(screen.getByLabelText('Название')).toHaveValue('Новое название')
  })
})
