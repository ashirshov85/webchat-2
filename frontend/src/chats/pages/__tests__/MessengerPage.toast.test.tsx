import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * T045 — аудит useToast: ровно один тост на каждую ЗАВЕРШАЮЩУЮСЯ операцию
 * во всех точках (FR-025, US3-AS4, ui-behavior §5, data-model 1.4/3.2).
 * Тосты форм-обитателей (ContactsModal №21/№22/№23/№24/№14, ProfileModal
 * №38, CreateGroupDialog №27) закреплены их собственными наборами
 * (T028/T031–T035); здесь — ТОЧКИ СТРАНИЦЫ, до T045 завершавшиеся молча:
 *
 *  - «шестерёнка» чата (T054 — была «Действия» 004): direct №14 «Удалить
 *    чат» → «Чат удалён — контакт сохранён», №23/№24 → «Контакт
 *    заблокирован/разблокирован — {username}» (прототип chats.html:
 *    deleteChat/askBlock/askUnblock — пути меню окна чата, строки
 *    1130/1469/1479);
 *  - группы: №33 выход → «Вы вышли из чата — {title}» (askLeaveGroup,
 *    строка 1507), №30 удаление → «Групповой чат удалён» (строка 1130)
 *    — оба теперь едут через пункты «шестерёнки» + confirm (SC-007),
 *    №29 переименование — тост формы GroupEditModal (T056, отдельный
 *    набор в groups/).
 *
 * Сбои тостом НЕ отмечаются — они остаются инлайн-ошибками форм/окна
 * (контракт T028–T035: .modal-err/ErrorBanner при живой поверхности);
 * тост — только результат завершённой операции (успех или отклонение
 * вида «Уже в контактах»). Авто-скрытие ~3 с — слот ToastProvider
 * страницы (T011, Toast.test.tsx); здесь проверяется РОВНО ОДНА выдача:
 * вторая заменила бы текст слота (одиночный слот, data-model 1.4).
 */

type ChatView = components['schemas']['ChatView']
type GroupView = components['schemas']['GroupView']
type GroupMember = components['schemas']['GroupMember']

const { mockGetCurrentUser, mockChats, mockGroups, mockSse, mockPresence } = vi.hoisted(() => ({
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
  mockGroups: {
    createGroup: vi.fn(),
    getGroup: vi.fn(),
    updateGroup: vi.fn(),
    deleteGroup: vi.fn(),
    leaveGroup: vi.fn(),
    addMembers: vi.fn(),
    kickMember: vi.fn(),
    setMemberRole: vi.fn(),
    transferOwnership: vi.fn(),
  },
  mockSse: { streamUserEvents: vi.fn() },
  mockPresence: {
    fetchPresenceSettings: vi.fn(),
    updatePresenceSettings: vi.fn(),
    createPresenceHeartbeat: vi.fn(() => ({ updateConnectionId: vi.fn(), stop: vi.fn() })),
  },
}))

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => mockGroups)

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const DIRECT_ID = 'chat-direct-1'
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

function directRow(blockedByMe = false): ChatListItem {
  return {
    chatId: DIRECT_ID,
    peer: peer(ALICE, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe,
  }
}

function groupRow(): ChatListItem {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    memberCount: 2,
    myRole: 'member',
    peer: null,
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: null,
  }
}

function directChatView(): ChatView {
  return {
    chatId: DIRECT_ID,
    type: 'direct',
    peer: peer(ALICE, 'alice'),
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  }
}

function groupChatView(): ChatView {
  return {
    chatId: GROUP_ID,
    type: 'group',
    title: 'Проект Альфа',
    description: null,
    myRole: 'member',
    othersReadUpToSeq: 0,
    memberCount: 2,
    peer: null,
    blockedByMe: null,
    peerReadUpToSeq: null,
    myReadUpToSeq: 0,
  }
}

/** №28-ответ открытого окна — роль решает, какая половина LeaveDelete живая. */
function groupView(myRole: GroupMember['role']): GroupView {
  return {
    chatId: GROUP_ID,
    title: 'Проект Альфа',
    description: null,
    myRole,
    members: [
      { user: peer(ME, 'me'), role: 'owner', joinedAt: '2026-09-20T12:00:00.000Z' },
      { user: peer(ALICE, 'alice'), role: 'member', joinedAt: '2026-09-20T12:00:00.000Z' },
    ],
  }
}

function contacts(): ContactView[] {
  return [{ user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z' }]
}

async function renderPage(chats: ChatListItem[] = [directRow()]): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue(chats)
  mockChats.getChat.mockImplementation((chatId: string) =>
    chatId === GROUP_ID ? Promise.resolve(groupChatView()) : Promise.resolve(directChatView()),
  )
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listContacts.mockResolvedValue(contacts())
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe: () => () => {},
    close: () => {},
  }))
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
}

/** Открыть прямой чат alice (заголовок готов — окно живо). */
async function openDirect(): Promise<void> {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
  await screen.findByRole('heading', { level: 2, name: 'alice' })
}

/**
 * Открыть групповое окно и дождаться живого №28: статус-строка «N
 * участников» становится кнопкой-источником подсказки только по живому
 * ростеру — к этому моменту «шестерёнка» несёт live myRole (T054), а
 * не №12-базис.
 */
async function openGroup(myRole: GroupMember['role']): Promise<void> {
  mockGroups.getGroup.mockResolvedValue(groupView(myRole))
  fireEvent.click(screen.getByRole('button', { name: /Проект Альфа/ }))
  await screen.findByRole('heading', { level: 2, name: 'Проект Альфа' })
  await screen.findByRole('button', { name: '2 участника' })
}

/**
 * FR-025 «ровно один»: вызывается ПОСЛЕ схождения конечного состояния
 * операции — текст слота присутствует, слот един и открыт; вторая выдача
 * (если бы была) заменила бы текст к этому моменту.
 */
async function expectSingleToast(text: string): Promise<void> {
  const slot = (await screen.findByText(text)).closest('.toast') as HTMLElement
  expect(slot).toHaveClass('show')
  expect(slot).toHaveTextContent(text)
  expect(document.querySelectorAll('.toast')).toHaveLength(1)
}

/** Слота НЕТ: скрытый пустой слот без .show — тост не выдавался. */
function expectNoToast(): void {
  const toasts = document.querySelectorAll('.toast')
  expect(toasts).toHaveLength(1)
  expect(toasts[0]).not.toHaveClass('show')
  expect(toasts[0]).toHaveTextContent('')
}

/** «Шестерёнка» заголовка (T054) → пункт → confirm кнопка. */
async function runHeaderAction(
  menuItem: string,
  dialogTitle: string,
  confirmLabel: string,
): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: 'Настройки чата' }))
  fireEvent.click(screen.getByRole('menuitem', { name: menuItem }))
  const dialog = await screen.findByRole('dialog', { name: dialogTitle })
  fireEvent.click(within(dialog).getByRole('button', { name: confirmLabel }))
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('MessengerPage №37 heartbeat wiring (008 T076 — живое соединение не экспайрится)', () => {
  it('страница монтирует heartbeat-планировщик присутствия на общем №18-стриме', async () => {
    await renderPage()

    expect(mockPresence.createPresenceHeartbeat).toHaveBeenCalledTimes(1)
  })
})

describe('MessengerPage тосты «шестерёнки» прямого чата (T045 → T054, FR-025, US3-AS4)', () => {
  it('№14 «Удалить чат»: ровно один тост «Чат удалён — контакт сохранён»', async () => {
    await renderPage()
    await openDirect()
    mockChats.deleteChat.mockResolvedValue(undefined)

    await runHeaderAction('Удалить чат', 'Удаление чата', 'Удалить чат')

    await waitFor(() => {
      expect(mockChats.deleteChat).toHaveBeenCalledWith(DIRECT_ID)
    })
    await waitFor(() => {
      expect(screen.getByRole('region', { name: 'Окно диалога' })).toHaveTextContent(
        'Чат не выбран',
      )
    })
    await expectSingleToast('Чат удалён — контакт сохранён')
  })

  it('сбой №14 — тоста нет: ошибка остаётся инлайн у окна (контракт форм)', async () => {
    await renderPage()
    await openDirect()
    mockChats.deleteChat.mockRejectedValueOnce(new Error('network down'))

    await runHeaderAction('Удалить чат', 'Удаление чата', 'Удалить чат')

    await waitFor(() => {
      expect(mockChats.deleteChat).toHaveBeenCalledTimes(1)
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Удаление чата' })).toBeNull()
    })
    expectNoToast()
  })

  it('№23 «Заблокировать контакт»: ровно один тост «Контакт заблокирован — alice»', async () => {
    await renderPage()
    await openDirect()
    mockChats.blockUser.mockResolvedValue(undefined)

    await runHeaderAction('Заблокировать контакт', 'Блокировка пользователя', 'Заблокировать')

    await waitFor(() => {
      expect(mockChats.blockUser).toHaveBeenCalledWith(ALICE)
    })
    await expectSingleToast('Контакт заблокирован — alice')
  })

  it('№24 «Разблокировать»: ровно один тост «Контакт разблокирован — alice»', async () => {
    await renderPage([directRow(true)])
    await openDirect()
    mockChats.unblockUser.mockResolvedValue(undefined)

    await runHeaderAction('Разблокировать контакт', 'Разблокировка пользователя', 'Разблокировать')

    await waitFor(() => {
      expect(mockChats.unblockUser).toHaveBeenCalledWith(ALICE)
    })
    await expectSingleToast('Контакт разблокирован — alice')
  })
})

describe('MessengerPage тосты групповых операций «шестерёнки» (T045 → T054, FR-025)', () => {
  it('№33 «Выйти из чата»: ровно один тост «Вы вышли из чата — {title}»', async () => {
    await renderPage([groupRow(), directRow()])
    await openGroup('member')
    mockGroups.leaveGroup.mockResolvedValue(undefined)

    await runHeaderAction('Выйти из чата', 'Выйти из группового чата', 'Выйти')

    await waitFor(() => {
      expect(mockGroups.leaveGroup).toHaveBeenCalledWith(GROUP_ID)
    })
    await waitFor(() => {
      expect(screen.getByRole('region', { name: 'Окно диалога' })).toHaveTextContent(
        'Чат не выбран',
      )
    })
    await expectSingleToast('Вы вышли из чата — Проект Альфа')
  })

  it('№33 сбой — тоста нет: инлайн-ошибка окна, окно живо', async () => {
    await renderPage([groupRow(), directRow()])
    await openGroup('member')
    mockGroups.leaveGroup.mockRejectedValueOnce(new Error('server says no'))

    await runHeaderAction('Выйти из чата', 'Выйти из группового чата', 'Выйти')

    await waitFor(() => {
      expect(mockGroups.leaveGroup).toHaveBeenCalledTimes(1)
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Выйти из группового чата' })).toBeNull()
    })
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Проект Альфа' }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expectNoToast()
  })

  it('№30 «Удалить чат» (owner): ровно один тост «Групповой чат удалён»', async () => {
    await renderPage([groupRow(), directRow()])
    await openGroup('owner')
    mockGroups.deleteGroup.mockResolvedValue(undefined)

    await runHeaderAction('Удалить чат', 'Удаление чата', 'Удалить')

    await waitFor(() => {
      expect(mockGroups.deleteGroup).toHaveBeenCalledWith(GROUP_ID)
    })
    await waitFor(() => {
      expect(screen.getByRole('region', { name: 'Окно диалога' })).toHaveTextContent(
        'Чат не выбран',
      )
    })
    await expectSingleToast('Групповой чат удалён')
  })
})
