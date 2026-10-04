import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * T034 — единая ModalShell на MessengerPage (US2; FR-026, data-model
 * 1.6/3.3, ui-behavior §3): все формы-обитатели приложения живут в ОДНОЙ
 * оболочке — пункты главного меню сайдбара (T030, FR-007) открывают
 * profile / contacts / create-group, инлайн-«Добавить контакт»
 * переключает форму ВНУТРИ оболочки, подтверждение «шестерёнки»-действий
 * прямого чата (№14/№23/№24, T060 wiring) — форма confirm. Переключение
 * formId НЕ рождает вторую подложку `.modal-back` (edge case data-model
 * 3.3); закрытие — Esc / фон / «Отмена» / успех submit. Поведенческие
 * ожидания 004–007 сохранены (FR-034): №20/№11/№14 едут теми же путями.
 */

type ChatView = components['schemas']['ChatView']

const { mockGetCurrentUser, mockChats, mockCreateGroup, mockSse, mockPresence } = vi.hoisted(
  () => ({
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
    mockSse: { streamUserEvents: vi.fn() },
    mockPresence: {
      fetchPresenceSettings: vi.fn(),
      updatePresenceSettings: vi.fn(),
    },
  }),
)

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => ({
  createGroup: mockCreateGroup,
}))

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const DIRECT_ID = 'chat-direct-1'

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

function contacts(): ContactView[] {
  return [{ user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z' }]
}

/** Общая подготовка страницы БЕЗ №12-мока — его ставит вызывающий тест. */
async function renderPageBase(): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listContacts.mockResolvedValue(contacts())
  mockChats.ensureChat.mockResolvedValue(directChatView())
  mockChats.deleteChat.mockResolvedValue(undefined)
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe: () => () => {},
    close: () => {},
  }))
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
}

async function renderPage(chats: ChatListItem[] = [directRow()]): Promise<void> {
  mockChats.listChats.mockResolvedValue(chats)
  await renderPageBase()
}

/** Пункт главного меню сайдбара (T030): «Меню» → пункт. */
async function openMainMenu(item: string): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: 'Меню' }))
  fireEvent.click(await screen.findByRole('menuitem', { name: item }))
}

/** Открытая оболочка: роли dialog с заголовком формы (ModalShell title). */
async function openShellForm(menuItem: string, title: string): Promise<HTMLElement> {
  await openMainMenu(menuItem)
  return screen.findByRole('dialog', { name: title })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('MessengerPage единая ModalShell (T034, data-model 1.6/3.3)', () => {
  it('главное меню открывает форму «Контакты» в единой оболочке; Esc закрывает', async () => {
    await renderPage()

    const dialog = await openShellForm('Контакты', 'Контакты')
    expect(within(dialog).getByPlaceholderText(/Поиск контакта/)).toBeInTheDocument()
    // Одна подложка на приложение — и она открыта (data-model 1.6).
    expect(document.querySelectorAll('.modal-back')).toHaveLength(1)

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Контакты' })).toBeNull()
    })
  })

  it('переключение contacts → add-contact внутри оболочки — без второй подложки (edge case)', async () => {
    await renderPage()
    await openShellForm('Контакты', 'Контакты')

    fireEvent.click(screen.getByRole('button', { name: 'Добавить контакт' }))

    const addDialog = await screen.findByRole('dialog', { name: 'Добавить контакт' })
    expect(
      within(addDialog).getByLabelText('Username или email — точное совпадание'),
    ).toBeInTheDocument()
    // Форма сменилась, подложка — ТА ЖЕ одна (data-model 3.3).
    expect(document.querySelectorAll('.modal-back')).toHaveLength(1)

    // «Отмена» возвращает к списку — тоже без второй подложки.
    fireEvent.click(within(addDialog).getByRole('button', { name: 'Отмена' }))
    await screen.findByRole('dialog', { name: 'Контакты' })
    expect(document.querySelectorAll('.modal-back')).toHaveLength(1)
  })

  it('главное меню открывает форму «Мой профиль» (readonly username/email, №38)', async () => {
    await renderPage()

    const dialog = await openShellForm('Мой профиль', 'Мой профиль')
    expect(await within(dialog).findByText('me')).toBeInTheDocument()
    expect(within(dialog).getByText('me@example.com')).toBeInTheDocument()
    expect(mockPresence.fetchPresenceSettings).toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Мой профиль' })).toBeNull()
    })
  })

  it('главное меню открывает форму создания группы; кнопка-вход из сайдбара удалена', async () => {
    await renderPage()
    // Прототип (ui-behavior §2): сайдбар — только поиск и список; вход
    // создания группы — пункт главного меню «Создать групповой чат».
    expect(screen.queryByRole('button', { name: 'Создать группу' })).toBeNull()

    const dialog = await openShellForm('Создать групповой чат', 'Новый групповой чат')
    // grpForm-проекция прототипа (T035): название + фильтр + подборщик.
    expect(within(dialog).getByLabelText('Название')).toBeInTheDocument()
    expect(
      within(dialog).getByPlaceholderText('Поиск контакта — имя, username или email'),
    ).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Новый групповой чат' })).toBeNull()
    })
  })

  it('открытие переписки из «Контактов» закрывает оболочку и открывает окно чата', async () => {
    await renderPage()
    await openShellForm('Контакты', 'Контакты')

    fireEvent.click(await screen.findByRole('button', { name: 'Контакт alice' }))

    await waitFor(() => {
      expect(mockChats.ensureChat).toHaveBeenCalledWith({ peerUserId: ALICE })
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Контакты' })).toBeNull()
    })
    expect(screen.getByRole('heading', { level: 2, name: 'alice' })).toBeInTheDocument()
  })
})

describe('MessengerPage форма confirm действий чата в оболочке (T034, SC-007)', () => {
  async function openDeleteConfirm(): Promise<HTMLElement> {
    const list = screen.getByRole('list', { name: 'Список чатов' })
    fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
    await screen.findByRole('heading', { level: 2, name: 'alice' })
    fireEvent.click(screen.getByRole('button', { name: 'Действия' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить чат' }))
    return screen.findByRole('dialog', { name: 'Удаление чата' })
  }

  it('«Удалить чат» открывает confirm в ТОЙ ЖЕ оболочке — без второй подложки', async () => {
    await renderPage()

    const dialog = await openDeleteConfirm()
    expect(within(dialog).getByText('alice')).toBeInTheDocument()
    expect(document.querySelectorAll('.modal-back')).toHaveLength(1)
    // Опасное действие — danger-кнопка действия (ui-behavior §3).
    expect(within(dialog).getByRole('button', { name: 'Удалить чат' })).toHaveClass('danger')

    fireEvent.click(within(dialog).getByRole('button', { name: 'Отмена' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Удаление чата' })).toBeNull()
    })
    expect(mockChats.deleteChat).not.toHaveBeenCalled()
  })

  it('подтверждение выполняет №14 и возвращает окно к «Чат не выбран»', async () => {
    await renderPage()
    const dialog = await openDeleteConfirm()

    fireEvent.click(within(dialog).getByRole('button', { name: 'Удалить чат' }))

    await waitFor(() => {
      expect(mockChats.deleteChat).toHaveBeenCalledWith(DIRECT_ID)
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Удаление чата' })).toBeNull()
    })
    expect(screen.getByRole('region', { name: 'Окно диалога' })).toHaveTextContent('Чат не выбран')
  })
})

describe('MessengerPage композер заблокированного контакта (T036, FR-022, US2-AS5)', () => {
  it('заблокированный чат глушит поле и кнопку с подсказкой; разблокировка (№24 через confirm) возвращает активность', async () => {
    // №12-агрегат — мутируемое состояние мока: стартовый ответ несёт
    // блокировку, всё после разблокировки сходится к свободному чату
    // (любой порядок фетчей даёт один и тот же итог — рефетч №24
    // перезаписывает блокированное состояние).
    let blockedByMe = true
    mockChats.listChats.mockImplementation(() => Promise.resolve([directRow(blockedByMe)]))
    await renderPageBase()
    await screen.findByText('alice')
    blockedByMe = false
    mockChats.unblockUser.mockResolvedValue(undefined)

    const list = screen.getByRole('list', { name: 'Список чатов' })
    fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
    await screen.findByRole('heading', { level: 2, name: 'alice' })

    // FR-022: поле и кнопка неактивны, подсказка о блокировке — в плейсхолдере
    // (прототип §7 updateInputState, дословно).
    const field = screen.getByLabelText('Текст сообщения')
    expect(field).toBeDisabled()
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' })).toBeDisabled()
    expect(field).toHaveAttribute(
      'placeholder',
      'Контакт заблокирован — разблокируйте, чтобы писать сообщения',
    )

    // Разблокировка: «Действия» → «Разблокировать пользователя» → confirm (№24).
    fireEvent.click(screen.getByRole('button', { name: 'Действия' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Разблокировать пользователя' }))
    const dialog = await screen.findByRole('dialog', { name: 'Разблокировка пользователя' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Разблокировать' }))

    await waitFor(() => {
      expect(mockChats.unblockUser).toHaveBeenCalledWith(ALICE)
    })
    // US2-AS5: композер снова активен. Оптимистический сброс и рефетч №12
    // сходятся сюда при любом порядке эффектов — ждём конечное состояние
    // (поле, кнопка и обычный плейсхолдер).
    await waitFor(() => {
      expect(screen.getByLabelText('Текст сообщения')).toBeEnabled()
      expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' })).toBeEnabled()
      expect(screen.getByLabelText('Текст сообщения')).toHaveAttribute('placeholder', 'Сообщение…')
    })
  })
})
