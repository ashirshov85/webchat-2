import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * US1 creation entry wiring (feature 006, T029; quickstart §3.1): the
 * messenger panel carries the «Создать группу» button that opens the
 * CreateGroupDialog (T026), a created №27 `GroupView` opens the GROUP
 * window and refetches №12, and a group row of the unified «Чаты» list
 * (T028) opens the group window — the header shows the group title and
 * NO direct-only actions («Действия» = №14 delete + №23/№24 block are
 * 004 pair-dialog features; blocks never apply to groups). A direct
 * row keeps the 004 pair dialog with its action menu.
 *
 * Feature 008 (US1, T017): the row lookups are anchored by the row
 * title text inside the «Список чатов» list — the T020 rebuild puts
 * the avatar initials INSIDE the row button, so its accessible name no
 * longer starts with the peer login; the title text itself is the
 * stable 004 expectation (FR-034).
 */

type GroupView = components['schemas']['GroupView']
type ChatView = components['schemas']['ChatView']

const { mockGetCurrentUser, mockChats, mockCreateGroup, mockGroups, mockSse } = vi.hoisted(() => ({
  mockGetCurrentUser: vi.fn(),
  mockChats: {
    listChats: vi.fn(),
    getChat: vi.fn(),
    listMessages: vi.fn(),
    markChatRead: vi.fn(),
    listMessagesAfter: vi.fn(),
    sync: vi.fn(),
    sendMessage: vi.fn(),
    deliveryAck: vi.fn(),
    ensureChat: vi.fn(),
    listContacts: vi.fn(),
    removeContact: vi.fn(),
    addContact: vi.fn(),
    searchUsers: vi.fn(),
    deleteChat: vi.fn(),
    blockUser: vi.fn(),
    unblockUser: vi.fn(),
  },
  mockCreateGroup: vi.fn(),
  mockGroups: {
    getGroup: vi.fn(),
    leaveGroup: vi.fn(),
    deleteGroup: vi.fn(),
  },
  mockSse: { streamUserEvents: vi.fn() },
}))

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => ({
  createGroup: mockCreateGroup,
  getGroup: mockGroups.getGroup,
  leaveGroup: mockGroups.leaveGroup,
  deleteGroup: mockGroups.deleteGroup,
}))

vi.mock('../../../api/sse', () => mockSse)

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
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

function groupRow(): ChatListItem {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    memberCount: 3,
    myRole: 'owner',
    peer: null,
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: null,
  }
}

function directRow(): ChatListItem {
  return {
    chatId: 'chat-direct-1',
    peer: peer(ALICE, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
  }
}

function groupChatView(): ChatView {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    description: null,
    myRole: 'owner',
    othersReadUpToSeq: 0,
    memberCount: 3,
    peer: null,
    blockedByMe: null,
    peerReadUpToSeq: null,
    myReadUpToSeq: 0,
  }
}

function createdGroupView(): GroupView {
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

function contacts(): ContactView[] {
  return [{ user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z', blockedByMe: false }]
}

/** The №18 stub: a silent stream — no frames, no (re)connects. */
function installSilentStream(): void {
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe: () => () => {},
    close: () => {},
  }))
}

/** The №18 stub that can emit frames (the US5 `group.you_removed` path). */
function installEmittingStream(): { emit(eventType: string, data: string): void } {
  const listeners = new Map<string, (data: string) => void>()
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe(eventType: string, listener: (data: string) => void) {
      listeners.set(eventType, listener)
      return () => {
        listeners.delete(eventType)
      }
    },
    close: () => {},
  }))
  return {
    emit(eventType, data) {
      act(() => {
        listeners.get(eventType)?.(data)
      })
    },
  }
}

async function renderPage(
  chats: ChatListItem[],
  installStream: () => void = installSilentStream,
): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue(chats)
  mockChats.getChat.mockImplementation((chatId: string) =>
    chatId === GROUP_ID
      ? Promise.resolve(groupChatView())
      : Promise.reject(new Error('unexpected getChat call')),
  )
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listContacts.mockResolvedValue(contacts())
  // №28 жив по умолчанию (owner) — матрица «шестерёнки» и групповые
  // формы оболочки работают на живом ростере; сбой №28 — исключение
  // конкретных тестов.
  mockGroups.getGroup.mockResolvedValue(createdGroupView())
  installStream()
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
}

/**
 * US2 (008 T034): the creation entry is the «Создать групповой чат»
 * item of the sidebar main menu — the legacy panel button is gone with
 * the tabs (ui-behavior §2), the form lives in the single ModalShell.
 */
async function openCreateGroup(): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: 'Меню' }))
  fireEvent.click(await screen.findByRole('menuitem', { name: 'Создать групповой чат' }))
}

/** The «Чаты» row button of a chat, anchored by its visible title (T017). */
function chatRowButton(title: string): HTMLElement {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  return within(list).getByText(title).closest('button') as HTMLElement
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('MessengerPage «Создать группу» entry (US1, quickstart §3.1)', () => {
  it('opens the grpForm from the main menu and closes it on «Отмена»', async () => {
    await renderPage([directRow()])

    await openCreateGroup()
    expect(screen.getByRole('dialog', { name: 'Новый групповой чат' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
    expect(screen.queryByRole('dialog', { name: 'Новый групповой чат' })).toBeNull()
    // The modal shell closed — the main menu entry stays reachable.
    expect(screen.getByRole('button', { name: 'Меню' })).toBeInTheDocument()
  })

  it('creates the group via №27, closes the dialog, refetches №12 and opens the group window', async () => {
    await renderPage([directRow()])

    await openCreateGroup()
    fireEvent.change(await screen.findByLabelText('Название'), {
      target: { value: 'Проект Альфа' },
    })
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Выбрать alice' }))
    mockCreateGroup.mockResolvedValueOnce(createdGroupView())
    const callsBefore = mockChats.listChats.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }))

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledWith({
        title: 'Проект Альфа',
        memberUserIds: [ALICE],
      })
    })
    await waitFor(() => {
      expect(mockChats.listChats.mock.calls.length).toBeGreaterThan(callsBefore)
    })
    expect(screen.queryByRole('dialog', { name: 'Новый групповой чат' })).toBeNull()
    // The group window opened on the №27 result: its title is the header.
    expect(screen.getByRole('heading', { level: 2, name: 'Проект Альфа' })).toBeInTheDocument()
    // FR-025: ровно один тост на завершённую операцию (прототип grpForm).
    expect(await screen.findByText('Групповой чат создан — Проект Альфа')).toBeInTheDocument()
  })

  it('keeps the dialog open on a №27 problem so the draft stays fixable', async () => {
    await renderPage([directRow()])

    await openCreateGroup()
    fireEvent.change(await screen.findByLabelText('Название'), {
      target: { value: 'Проект Альфа' },
    })
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Выбрать alice' }))
    mockCreateGroup.mockRejectedValueOnce({
      title: 'Unprocessable Entity',
      status: 422,
      errors: { memberUserIds: ['not_in_contacts'] },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('not_in_contacts')
    expect(screen.getByRole('dialog', { name: 'Новый групповой чат' })).toBeInTheDocument()
  })
})

describe('MessengerPage group window from the unified list (US1)', () => {
  it('opens the group window on a group row: title header, composer, the gear carries group items only', async () => {
    await renderPage([groupRow(), directRow()])

    fireEvent.click(screen.getByRole('button', { name: /Проект Альфа/ }))

    await waitFor(() => {
      expect(mockChats.getChat).toHaveBeenCalledWith(GROUP_ID)
    })
    expect(screen.getByRole('heading', { level: 2, name: 'Проект Альфа' })).toBeInTheDocument()
    expect(screen.getByLabelText('Текст сообщения')).toBeInTheDocument()
    // FR-016/FR-023 (T054): the gear is the only header control; a group
    // window carries the GROUP matrix — never the direct №14-direct/
    // №23/№24 entries (blocks never apply to groups). The №28 basis
    // (getGroup is not mocked here → error state) leaves the №12 myRole
    // ('owner') driving the matrix.
    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
    expect(screen.getByRole('menuitem', { name: 'Участники' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Заблокировать контакт' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Добавить в контакты' })).toBeNull()
    // Presence (007, T022): a group window carries NO presence UI.
    const groupDialog = screen.getByRole('region', { name: 'Окно диалога' })
    expect(groupDialog.querySelector('.presence-indicator')).toBeNull()
    expect(within(groupDialog).queryByText('неизвестно')).toBeNull()
  })

  it('keeps the direct dialog intact: peer header with the gear chat menu', async () => {
    await renderPage([groupRow(), directRow()])

    fireEvent.click(chatRowButton('alice'))

    expect(screen.getByRole('heading', { level: 2, name: 'alice' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Настройки чата' })).toBeInTheDocument()
    // Presence (007, T022 → bug 12/T090): the 1:1 header is the ONLY
    // surface with a presence indicator — but the neutral «unknown»
    // (before the first №36 snapshot) renders NOTHING: no lamp, no
    // text (never a false «офлайн»); only the empty .status-row keeps
    // the header height until №36 converges.
    const directDialog = screen.getByRole('region', { name: 'Окно диалога' })
    expect(within(directDialog).queryByText('неизвестно')).toBeNull()
    expect(within(directDialog).queryByText('офлайн')).toBeNull()
    expect(directDialog.querySelector('.chat-head .lamp')).toBeNull()
    expect(directDialog.querySelector('.chat-head .status-row')).not.toBeNull()
  })

  it('gear «Участники»/«Редактировать чат» open the members/edit forms of the single shell (T055/T056/T057)', async () => {
    await renderPage([groupRow(), directRow()])

    fireEvent.click(screen.getByRole('button', { name: /Проект Альфа/ }))
    await screen.findByRole('heading', { level: 2, name: 'Проект Альфа' })
    // №28 жив — «2 участника» статус-строка сходится к ростеру; форма
    // «Участники» открывается по живым данным (SC-004: вся
    // функциональность 006 — без карточки под заголовком).
    await screen.findByRole('button', { name: '2 участника' })

    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Участники' }))
    const members = await screen.findByRole('dialog', { name: 'Участники' })
    // Ростер №28 минус собственная строка; ростер-действия владельца
    // достижимы из оболочки через «⋯»-меню строки (контракт T049/T055,
    // вход T095/Bug 17 — меню порталится в body, поверх оболочки).
    expect(within(members).getByText('alice')).toBeInTheDocument()
    expect(within(members).queryByText(/me \(вы\)/)).toBeNull()
    const aliceRow = within(members).getByText('alice').closest('.pick-row') as HTMLElement
    fireEvent.click(within(aliceRow).getByRole('button', { name: 'Действия с участником' }))
    expect(screen.getByRole('menuitem', { name: 'Исключить alice' })).toBeInTheDocument()
    // Одна подложка на приложение (data-model 1.6).
    expect(document.querySelectorAll('.modal-back')).toHaveLength(1)

    // Esc слоист (data-model 3.1): первый гасит «⋯»-меню (верхний слой,
    // T095), второй — саму оболочку «Участники».
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('menuitem', { name: 'Исключить alice' })).toBeNull()
    })
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Участники' })).toBeNull()
    })

    // «Редактировать чат» (T056/T057): черновик = №28 минус себя,
    // название предзаполнено; «Отмена» возвращает оболочку.
    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Редактировать чат' }))
    const edit = await screen.findByRole('dialog', { name: 'Редактировать групповой чат' })
    expect(within(edit).getByLabelText('Название')).toHaveValue('Проект Альфа')
    expect(
      within(edit).getByRole('button', { name: 'Удалить участника alice' }),
    ).toBeInTheDocument()

    fireEvent.click(within(edit).getByRole('button', { name: 'Отмена' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Редактировать групповой чат' })).toBeNull()
    })
  })
})

/**
 * T098 (Bug 20): строка участника «Участников» — НАВИГАЦИЯ (паттерн
 * ctc-строки «Контактов», штатный onOpenChat владельца оболочки):
 * клик открывает существующий direct-чат участника из №12 (БЕЗ №11)
 * или создаёт его №11 ensureChat({peerUserId}) и открывает; оболочка
 * закрывается как у «Контактов» (closeShell + окно + №12-рефетч).
 * Прототип membersList строк-кликов не несёт — требование владельца
 * поверх демо-прототипа (класс исключения T060).
 */
describe('MessengerPage открытие чата из «Участников» (T098, Bug 20)', () => {
  /** №28-ростер с bob — участником БЕЗ direct-чата в №12 (№11-путь). */
  function groupViewWithBob(): GroupView {
    return {
      ...createdGroupView(),
      members: [
        ...createdGroupView().members,
        { user: peer(BOB, 'bob'), role: 'member', joinedAt: '2026-09-20T12:00:00.000Z' },
      ],
    }
  }

  function bobChatView(): ChatView {
    return {
      chatId: 'chat-direct-bob',
      type: 'direct',
      peer: peer(BOB, 'bob'),
      blockedByMe: false,
      peerReadUpToSeq: 0,
      myReadUpToSeq: 0,
    }
  }

  /** Группа открыта → «шестерёнка» → «Участники» (форма оболочки). */
  async function openMembersForm(): Promise<HTMLElement> {
    fireEvent.click(screen.getByRole('button', { name: /Проект Альфа/ }))
    await screen.findByRole('heading', { level: 2, name: 'Проект Альфа' })
    fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Участники' }))
    return screen.findByRole('dialog', { name: 'Участники' })
  }

  it('клик по строке с №12-чатом открывает его без №11; без чата — №11 создаёт и открывает', async () => {
    await renderPage([groupRow(), directRow()])
    // После renderPage (её дефолты перезаписывают): №28 несёт bob —
    // участника БЕЗ direct-чата в №12 (№11-путь), №11 отвечает его чатом.
    mockGroups.getGroup.mockResolvedValue(groupViewWithBob())
    mockChats.ensureChat.mockResolvedValue(bobChatView())

    // alice имеет direct-чат в №12 — клик открывает его БЕЗ №11.
    const members = await openMembersForm()
    fireEvent.click(within(members).getByRole('button', { name: 'Участник alice' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Участники' })).toBeNull()
    })
    expect(screen.getByRole('heading', { level: 2, name: 'alice' })).toBeInTheDocument()
    expect(mockChats.ensureChat).not.toHaveBeenCalled()

    // bob без чата — №11 создаёт пару и открывает её (как «Создать чат»
    // из «⋯» «Контактов»): оболочка закрывается, окно — у bob.
    const membersAgain = await openMembersForm()
    fireEvent.click(within(membersAgain).getByRole('button', { name: 'Участник bob' }))

    await waitFor(() => {
      expect(mockChats.ensureChat).toHaveBeenCalledWith({ peerUserId: BOB })
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Участники' })).toBeNull()
    })
    expect(screen.getByRole('heading', { level: 2, name: 'bob' })).toBeInTheDocument()
  })
})

/**
 * US5 privacy slice (T055 → T058; realtime-group-events.md §5.2, FR-010):
 * `group.you_removed` is the FINAL frame of the group — an OPEN group
 * window must close at once (no «зависший» заголовок/композер of a
 * chat the user has no membership in anymore), the row leaves «Чаты»
 * WITHOUT a №12 refetch round, and the rest of the messenger stays
 * usable. `group.deleted` joins with the same shape in US6 (T062/T066).
 */
describe('MessengerPage group.you_removed with the group window open (US5, T055 → T058)', () => {
  it('closes the open group window, drops the row without polling and keeps the rest usable', async () => {
    const stream = installEmittingStream()
    await renderPage([groupRow(), directRow()], () => {})

    fireEvent.click(screen.getByRole('button', { name: /Проект Альфа/ }))
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Проект Альфа' }),
    ).toBeInTheDocument()
    const callsBeforeRemoval = mockChats.listChats.mock.calls.length

    stream.emit('group.you_removed', JSON.stringify({ groupId: GROUP_ID, reason: 'kicked' }))

    // The window closed — no dangling group header; the row is gone.
    await waitFor(() => {
      expect(screen.queryByRole('heading', { level: 2, name: 'Проект Альфа' })).toBeNull()
    })
    expect(screen.queryByRole('button', { name: /Проект Альфа/ })).toBeNull()
    // Deterministic removal — no №12 refetch round (§5.2 без поллинга).
    expect(mockChats.listChats.mock.calls).toHaveLength(callsBeforeRemoval)
    // The direct dialog of the same list keeps flowing.
    expect(chatRowButton('alice')).toBeInTheDocument()
  })
})

/**
 * US6 delete slice (T062 → T066; realtime-group-events.md §3.5, FR-006):
 * `group.deleted` — the №30 hard-delete broadcast to every former
 * active member, the deleter included — closes an OPEN group window at
 * once (no «зависший» заголовок/композер of a chat that no longer
 * exists server-side), drops the row from «Чаты» WITHOUT a №12
 * refetch round and tombstones the chatId in the §5.5 №25 batcher,
 * while the rest of the messenger stays usable — the same shape the
 * US5 you_removed wiring established (T058).
 */
describe('MessengerPage group.deleted with the group window open (US6, T062 → T066, §3.5)', () => {
  it('closes the open group window, drops the row without polling and keeps the rest usable', async () => {
    const stream = installEmittingStream()
    await renderPage([groupRow(), directRow()], () => {})

    fireEvent.click(screen.getByRole('button', { name: /Проект Альфа/ }))
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Проект Альфа' }),
    ).toBeInTheDocument()
    const callsBeforeDelete = mockChats.listChats.mock.calls.length

    stream.emit(
      'group.deleted',
      JSON.stringify({ groupId: GROUP_ID, actorId: '55555555-5555-5555-5555-555555555555' }),
    )

    // The window closed — no dangling group header; the row is gone.
    await waitFor(() => {
      expect(screen.queryByRole('heading', { level: 2, name: 'Проект Альфа' })).toBeNull()
    })
    expect(screen.queryByRole('button', { name: /Проект Альфа/ })).toBeNull()
    // Deterministic removal — no №12 refetch round (§3.5 без поллинга).
    expect(mockChats.listChats.mock.calls).toHaveLength(callsBeforeDelete)
    // The direct dialog of the same list keeps flowing.
    expect(chatRowButton('alice')).toBeInTheDocument()
  })
})
