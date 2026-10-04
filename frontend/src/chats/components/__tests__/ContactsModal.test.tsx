import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addContact,
  blockUser,
  deleteChat,
  ensureChat,
  listContacts,
  removeContact,
  searchUsers,
  unblockUser,
} from '../../../api/chats'
import type { ChatListItem, ChatView, ContactView } from '../../../api/chats'
import type { PublicUser } from '../../../api/auth'
import { ToastProvider } from '../../../ui/Toast'
import { ContactsModal } from '../ContactsModal'

/**
 * Модальная форма «Контакты» (feature 008, US2, T028; FR-011/012/013,
 * FR-014, research §D, ui-behavior §3): миграция поведенческих ожиданий
 * 004 ContactList/UserSearchBox (5fa784c) со сайдбара в модаль — табов
 * больше нет (T027/T029), вся функциональность 004 достижима из меню и
 * модали (SC-004, FR-034).
 *
 * Что сохранено из 004: №20 — источник списка (сбой + «Повторить»);
 * клик по контакту — №11 ensureChat → onOpenChat(ChatView); №22 удаляет
 * ТОЛЬКО запись адресной книги (чат и история не тронуты, диалог не
 * открывается — FR-017, идемпотентный 204); №19 — точный поиск с
 * триммингом запроса (0..1 ответ, промах — спокойная ошибка, не краш);
 * №21 — идемпотентное добавление (201/200 без дублей); сбои действий
 * рендерятся ошибкой, список не размонтируется.
 *
 * Что меняет US2 (FR-011–013 вместо табов 004): сортировка/фильтр
 * становятся клиентскими (username ru-locale, подстрока
 * username/email без регистра — displayName в контрактах нет, alias
 * вне фичи ui-behavior §9); пометки «заблокирован»/«чат удалён» — из
 * пропа chats (№12, blockedByMe/наличие связанного чата); клик без
 * чата — БЕЗ действия (Clarification: создание чата — только пунктом
 * «Создать чат»); «⋯»-меню (ContextMenu) с подтверждениями
 * (ConfirmDialog, SC-007) и тостами (ToastProvider, FR-025); форма
 * «Добавить контакт» (T032) — внутри той же модали.
 *
 * Тесты написаны ДО реализации (конституция VI): красные до T031/T032,
 * ContactsModal.tsx до них — контракт-заглушка (паттерн T027).
 */

vi.mock('../../../api/chats', () => ({
  listContacts: vi.fn(),
  ensureChat: vi.fn(),
  removeContact: vi.fn(),
  searchUsers: vi.fn(),
  addContact: vi.fn(),
  blockUser: vi.fn(),
  unblockUser: vi.fn(),
  deleteChat: vi.fn(),
}))

const mockedListContacts = vi.mocked(listContacts)
const mockedEnsureChat = vi.mocked(ensureChat)
const mockedRemoveContact = vi.mocked(removeContact)
const mockedSearchUsers = vi.mocked(searchUsers)
const mockedAddContact = vi.mocked(addContact)
const mockedBlockUser = vi.mocked(blockUser)
const mockedUnblockUser = vi.mocked(unblockUser)
const mockedDeleteChat = vi.mocked(deleteChat)

const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const CAROL = '44444444-4444-4444-4444-444444444444'
const DAVE = '55555555-5555-5555-5555-555555555555'

function publicUser(id: string, username: string, email: string): PublicUser {
  return { id, username, email, status: 'active', createdAt: '2026-09-01T00:00:00.000Z' }
}

function contactView(id: string, username: string, email: string): ContactView {
  return { user: publicUser(id, username, email), createdAt: '2026-09-10T00:00:00.000Z' }
}

const ALICE_CONTACT = contactView(ALICE, 'alice', 'alice@example.com')
const BOB_CONTACT = contactView(BOB, 'Борис', 'boris@brass.io')
const CAROL_CONTACT = contactView(CAROL, 'анна', 'anna@steam.ru')
const DAVE_CONTACT = contactView(DAVE, 'dave', 'dave@aethergram.io')

/** Порядок сервера намеренно ≠ клиентской сортировке — модаль сортирует сама. */
const CONTACTS = [BOB_CONTACT, CAROL_CONTACT, ALICE_CONTACT]

function directChat(
  peerId: string,
  username: string,
  email: string,
  overrides: Partial<ChatListItem> = {},
): ChatListItem {
  return {
    chatId: `chat-${username}`,
    type: 'direct',
    peer: publicUser(peerId, username, email),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
    ...overrides,
  }
}

const ALICE_CHAT = directChat(ALICE, 'alice', 'alice@example.com')
const CAROL_CHAT = directChat(CAROL, 'анна', 'anna@steam.ru', { blockedByMe: true })

function chatViewOf(item: ChatListItem): ChatView {
  return {
    chatId: item.chatId,
    peer: item.peer,
    blockedByMe: item.blockedByMe ?? false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  }
}

function renderModal(chats: readonly ChatListItem[] = [ALICE_CHAT, CAROL_CHAT]) {
  const onOpenChat = vi.fn()
  const view = render(
    <ToastProvider>
      <ContactsModal chats={chats} onOpenChat={onOpenChat} />
    </ToastProvider>,
  )
  const rerenderChats = (next: readonly ChatListItem[]) => {
    view.rerender(
      <ToastProvider>
        <ContactsModal chats={next} onOpenChat={onOpenChat} />
      </ToastProvider>,
    )
  }
  return { onOpenChat, rerenderChats, ...view }
}

/** Строка контакта по имени (прототипный хук .ctc-row, имя — в .c-name). */
function rowOf(container: HTMLElement, username: string): HTMLElement {
  const row = Array.from(container.querySelectorAll<HTMLElement>('.ctc-row')).find(
    (node) => node.querySelector('.c-name')?.textContent === username,
  )
  if (row === undefined) {
    throw new Error(`строка контакта не найдена: ${username}`)
  }
  return row
}

/** Открывает «⋯»-меню строки (кнопка-прототип .c-menu) и возвращает меню. */
function openContactMenu(container: HTMLElement, username: string): HTMLElement {
  const row = rowOf(container, username)
  const menuButton = within(row).getByRole('button', { name: 'Действия с контактом' })
  fireEvent.click(menuButton)
  return screen.getByRole('menu')
}

function menuLabels(): string[] {
  return screen.getAllByRole('menuitem').map((item) => item.textContent ?? '')
}

function closeMenu(): void {
  fireEvent.click(document.body)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedListContacts.mockResolvedValue(CONTACTS)
})

afterEach(cleanup)

describe('ContactsModal список (FR-011)', () => {
  it('сортирует №20 по username (ru-locale, без регистра) и рендерит строки прототипа', async () => {
    const { container } = renderModal()

    expect(await screen.findByText('alice')).toBeVisible()
    // Сервер вернул [Борис, анна, alice] — модаль пересортировала сама:
    // латиница раньше кириллицы, «анна» < «Борис» (не code-unit порядок).
    const names = Array.from(container.querySelectorAll('.ctc-row .c-name')).map(
      (node) => node.textContent,
    )
    expect(names).toEqual(['alice', 'анна', 'Борис'])
  })

  it('фильтрует живьём по username/email без регистра; промах — «Ничего не найдено»', async () => {
    const { container } = renderModal()
    const search = await screen.findByPlaceholderText('Поиск контакта — имя, username или email')

    fireEvent.change(search, { target: { value: 'АН' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(1)
    expect(rowOf(container, 'анна')).toBeVisible()

    fireEvent.change(search, { target: { value: 'BORIS@BRASS.IO' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(1)
    expect(rowOf(container, 'Борис')).toBeVisible()

    fireEvent.change(search, { target: { value: 'zzz' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(0)
    expect(screen.getByText('Ничего не найдено')).toBeVisible()

    fireEvent.change(search, { target: { value: '' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(3)
  })

  it('пустая книга — спокойное пустое состояние «Нет контактов»', async () => {
    mockedListContacts.mockResolvedValue([])
    renderModal()

    expect(await screen.findByText(/Нет контактов/)).toBeVisible()
  })

  it('сбой №20 — ошибка с «Повторить», повторный заход загружает список (004)', async () => {
    mockedListContacts
      .mockRejectedValueOnce({ status: 500, title: 'Internal Server Error' })
      .mockResolvedValueOnce(CONTACTS)
    renderModal()

    expect(await screen.findByText('Internal Server Error')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    await waitFor(() => {
      expect(mockedListContacts).toHaveBeenCalledTimes(2)
    })
    expect(await screen.findByText('alice')).toBeVisible()
  })
})

describe('ContactsModal пометки и клики строк (FR-013)', () => {
  it('помечает «заблокирован» (blockedByMe связанного чата) и «чат удалён» (чата нет)', async () => {
    const { container } = renderModal()
    await screen.findByText('alice')

    expect(rowOf(container, 'анна').querySelector('.c-prev')?.textContent ?? '').toMatch(
      /заблокирован/i,
    )
    expect(rowOf(container, 'Борис').querySelector('.c-prev')?.textContent ?? '').toMatch(
      /чат удалён/i,
    )
    expect(within(rowOf(container, 'alice')).queryByText(/заблокирован/i)).toBeNull()
    expect(within(rowOf(container, 'alice')).queryByText(/чат удалён/i)).toBeNull()
  })

  it('клик по контакту с чатом — №11 ensureChat → onOpenChat(ChatView) (004)', async () => {
    const { container, onOpenChat } = renderModal()
    mockedEnsureChat.mockResolvedValue(chatViewOf(ALICE_CHAT))
    await screen.findByText('alice')

    fireEvent.click(rowOf(container, 'alice'))

    await waitFor(() => {
      expect(mockedEnsureChat).toHaveBeenCalledWith({ peerUserId: ALICE })
    })
    await waitFor(() => {
      expect(onOpenChat).toHaveBeenCalledWith(chatViewOf(ALICE_CHAT))
    })
  })

  it('клик по контакту без чата — без действия (Clarification FR-013)', async () => {
    const { container, onOpenChat } = renderModal()
    await screen.findByText('Борис')

    fireEvent.click(rowOf(container, 'Борис'))

    expect(mockedEnsureChat).not.toHaveBeenCalled()
    expect(onOpenChat).not.toHaveBeenCalled()
  })

  it('сбой ensureChat — ошибка видна, список остаётся живым (004)', async () => {
    const { container } = renderModal()
    mockedEnsureChat.mockRejectedValue({ status: 404, title: 'User not found' })
    await screen.findByText('alice')

    fireEvent.click(rowOf(container, 'alice'))

    expect(await screen.findByText('User not found')).toBeVisible()
    expect(rowOf(container, 'Борис')).toBeVisible()
  })
})

describe('ContactsModal меню «⋯» (FR-013, FR-014)', () => {
  it('связанный незаблокированный: Заблокировать / Удалить чат / Удалить контакт (danger + разделитель)', async () => {
    const { container } = renderModal()
    await screen.findByText('alice')

    const menu = openContactMenu(container, 'alice')

    expect(menuLabels()).toEqual(['Заблокировать', 'Удалить чат', 'Удалить контакт'])
    // Единственный разделитель — перед «Удалить контакт»; пункт — danger.
    expect(menu.querySelectorAll('.ctx-sep')).toHaveLength(1)
    const danger = menu.querySelector('button.danger')
    expect(danger?.textContent).toBe('Удалить контакт')
  })

  it('заблокированный — «Разблокировать»; без чата — «Создать чат» вместо «Удалить чат»', async () => {
    const { container } = renderModal()
    await screen.findByText('анна')

    openContactMenu(container, 'анна')
    expect(menuLabels()).toEqual(['Разблокировать', 'Удалить чат', 'Удалить контакт'])
    closeMenu()

    openContactMenu(container, 'Борис')
    expect(menuLabels()).toEqual(['Заблокировать', 'Создать чат', 'Удалить контакт'])
  })

  it('блокировка: подтверждение (danger, имя жирным) → №23 → тост; метка после обновления №12', async () => {
    const { container, rerenderChats } = renderModal()
    await screen.findByText('alice')

    openContactMenu(container, 'alice')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать' }))

    // Подтверждение до вызова API (SC-007): ConfirmDialog с <b>именем</b>.
    const confirm = document.querySelector('.confirm-form')
    expect(confirm).not.toBeNull()
    expect(confirm?.querySelector('.confirm-text b')?.textContent).toBe('alice')
    expect(confirm?.querySelector('button.danger')?.textContent).toBe('Заблокировать')
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeVisible()
    expect(mockedBlockUser).not.toHaveBeenCalled()

    fireEvent.click(within(confirm as HTMLElement).getByRole('button', { name: 'Заблокировать' }))

    await waitFor(() => {
      expect(mockedBlockUser).toHaveBeenCalledWith(ALICE)
    })
    expect(await screen.findByText('Контакт заблокирован — alice')).toBeVisible()

    // Родитель (№12 reload) приносит blockedByMe — появляется метка и пункт меняется.
    rerenderChats([
      directChat(ALICE, 'alice', 'alice@example.com', { blockedByMe: true }),
      CAROL_CHAT,
    ])
    expect(rowOf(container, 'alice').querySelector('.c-prev')?.textContent ?? '').toMatch(
      /заблокирован/i,
    )
    openContactMenu(container, 'alice')
    expect(menuLabels()).toEqual(['Разблокировать', 'Удалить чат', 'Удалить контакт'])
  })

  it('разблокировка: подтверждение → №24 + тост «Контакт разблокирован»', async () => {
    const { container } = renderModal()
    await screen.findByText('анна')

    openContactMenu(container, 'анна')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Разблокировать' }))

    fireEvent.click(screen.getByRole('button', { name: 'Разблокировать' }))

    await waitFor(() => {
      expect(mockedUnblockUser).toHaveBeenCalledWith(CAROL)
    })
    expect(await screen.findByText('Контакт разблокирован — анна')).toBeVisible()
  })

  it('удаление чата: подтверждение → №14 + тост; после №12-обновления — «чат удалён» и пункт «Создать чат»', async () => {
    const { container, rerenderChats, onOpenChat } = renderModal()
    mockedEnsureChat.mockResolvedValue(chatViewOf(ALICE_CHAT))
    await screen.findByText('alice')

    openContactMenu(container, 'alice')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить чат' }))

    const confirm = document.querySelector('.confirm-form')
    expect(confirm?.querySelector('button.danger')?.textContent).toBe('Удалить')
    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))

    await waitFor(() => {
      expect(mockedDeleteChat).toHaveBeenCalledWith('chat-alice')
    })
    expect(await screen.findByText('Чат удалён — контакт сохранён')).toBeVisible()
    // Контакт остаётся (FR-017), переписка не открывается.
    expect(rowOf(container, 'alice')).toBeVisible()
    expect(onOpenChat).not.toHaveBeenCalled()

    rerenderChats([CAROL_CHAT])
    expect(rowOf(container, 'alice').querySelector('.c-prev')?.textContent ?? '').toMatch(
      /чат удалён/i,
    )
    openContactMenu(container, 'alice')
    expect(menuLabels()).toEqual(['Заблокировать', 'Создать чат', 'Удалить контакт'])
  })

  it('создание чата: подтверждение («Создать чат с <b>имя</b>?») → №11 → onOpenChat', async () => {
    const { container, onOpenChat } = renderModal()
    mockedEnsureChat.mockResolvedValue(chatViewOf(directChat(BOB, 'Борис', 'boris@brass.io')))
    await screen.findByText('Борис')

    openContactMenu(container, 'Борис')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Создать чат' }))

    const confirm = document.querySelector('.confirm-form')
    expect(confirm?.querySelector('.confirm-text b')?.textContent).toBe('Борис')
    expect(mockedEnsureChat).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Создать' }))

    await waitFor(() => {
      expect(mockedEnsureChat).toHaveBeenCalledWith({ peerUserId: BOB })
    })
    await waitFor(() => {
      expect(onOpenChat).toHaveBeenCalledWith(
        chatViewOf(directChat(BOB, 'Борис', 'boris@brass.io')),
      )
    })
  })

  it('удаление контакта: подтверждение → №22, строка исчезает, диалог не открывается, тост (FR-017)', async () => {
    const { container, onOpenChat } = renderModal()
    await screen.findByText('анна')

    openContactMenu(container, 'анна')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить контакт' }))

    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))

    await waitFor(() => {
      expect(mockedRemoveContact).toHaveBeenCalledWith(CAROL)
    })
    expect(await screen.findByText('Контакт удалён — чат сохранён')).toBeVisible()
    await waitFor(() => {
      expect(container.querySelectorAll('.ctc-row')).toHaveLength(2)
    })
    expect(
      Array.from(container.querySelectorAll('.ctc-row .c-name')).map((n) => n.textContent),
    ).toEqual(['alice', 'Борис'])
    expect(mockedEnsureChat).not.toHaveBeenCalled()
    expect(onOpenChat).not.toHaveBeenCalled()
  })

  it('отмена подтверждения — действие не выполняется, список на месте (SC-007)', async () => {
    const { container } = renderModal()
    await screen.findByText('alice')

    openContactMenu(container, 'alice')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать' }))

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(mockedBlockUser).not.toHaveBeenCalled()
    expect(await screen.findByText('alice')).toBeVisible()
    expect(rowOf(container, 'анна')).toBeVisible()
  })

  it('сбой №22 — ошибка видна, строка остаётся (004)', async () => {
    const { container } = renderModal()
    mockedRemoveContact.mockRejectedValue({ status: 500, title: 'Internal Server Error' })
    await screen.findByText('анна')

    openContactMenu(container, 'анна')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить контакт' }))
    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))

    expect(await screen.findByText('Internal Server Error')).toBeVisible()
    expect(rowOf(container, 'анна')).toBeVisible()
  })
})

describe('ContactsModal форма «Добавить контакт» (FR-012)', () => {
  it('промах №19 — ошибка «требуется точное совпадение», №21 не вызывается; запрос триммится (004)', async () => {
    renderModal()
    fireEvent.click(await screen.findByRole('button', { name: 'Добавить контакт' }))

    const query = screen.getByLabelText('Username или email — точное совпадание')
    mockedSearchUsers.mockResolvedValueOnce([])
    fireEvent.change(query, { target: { value: '  hargrove  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Добавить' }))

    await waitFor(() => {
      expect(mockedSearchUsers).toHaveBeenCalledWith('hargrove')
    })
    expect(
      await screen.findByText(
        'Пользователь не найден — требуется точное совпадение username или email',
      ),
    ).toBeVisible()
    expect(mockedAddContact).not.toHaveBeenCalled()
  })

  it('находка: подтверждение (имя + username · email) → №21 → №11 → открытие переписки + тост', async () => {
    mockedListContacts
      .mockResolvedValueOnce(CONTACTS)
      .mockResolvedValueOnce([...CONTACTS, DAVE_CONTACT])
    const { onOpenChat } = renderModal()
    const daveChat = directChat(DAVE, 'dave', 'dave@aethergram.io')
    mockedSearchUsers.mockResolvedValueOnce([publicUser(DAVE, 'dave', 'dave@aethergram.io')])
    mockedAddContact.mockResolvedValueOnce(DAVE_CONTACT)
    mockedEnsureChat.mockResolvedValue(chatViewOf(daveChat))

    fireEvent.click(await screen.findByRole('button', { name: 'Добавить контакт' }))
    fireEvent.change(screen.getByLabelText('Username или email — точное совпадание'), {
      target: { value: 'dave@aethergram.io' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добавить' }))

    // Подтверждение заменяет форму (без наложения): имя <b> + подпись username · email.
    // №19 — асинхронный ответ: подтверждение монтируется после него, поэтому
    // ждём форму (стабилизация харнесса против синхронного чтения DOM,
    // паттерн T026; поведенческое ожидание неизменно).
    const confirm = await waitFor(() => {
      const el = document.querySelector('.confirm-form')
      expect(el).not.toBeNull()
      return el as HTMLElement
    })
    expect(confirm?.querySelector('.confirm-text b')?.textContent).toBe('dave')
    expect(confirm?.querySelector('.confirm-sub')?.textContent).toBe('dave · dave@aethergram.io')
    expect(mockedAddContact).not.toHaveBeenCalled()

    fireEvent.click(within(confirm).getByRole('button', { name: 'Добавить' }))

    await waitFor(() => {
      expect(mockedAddContact).toHaveBeenCalledWith(DAVE)
    })
    await waitFor(() => {
      expect(mockedEnsureChat).toHaveBeenCalledWith({ peerUserId: DAVE })
    })
    await waitFor(() => {
      expect(onOpenChat).toHaveBeenCalledWith(chatViewOf(daveChat))
    })
    expect(await screen.findByText('Контакт добавлен — dave')).toBeVisible()
    // Новый контакт появляется в списке (рефетч №20 либо локальное добавление).
    expect(await screen.findByText('dave')).toBeVisible()
  })

  it('идемпотентность: уже в контактах — №21 один раз, тост «Уже в контактах», дубля нет, переписка открывается', async () => {
    const { container, onOpenChat } = renderModal()
    const bobChat = directChat(BOB, 'Борис', 'boris@brass.io')
    mockedSearchUsers.mockResolvedValueOnce([publicUser(BOB, 'Борис', 'boris@brass.io')])
    mockedAddContact.mockResolvedValueOnce(BOB_CONTACT)
    mockedEnsureChat.mockResolvedValue(chatViewOf(bobChat))

    fireEvent.click(await screen.findByRole('button', { name: 'Добавить контакт' }))
    fireEvent.change(screen.getByLabelText('Username или email — точное совпадание'), {
      target: { value: 'БОРИС' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добавить' }))
    const confirm = await waitFor(() => {
      const el = document.querySelector('.confirm-form')
      expect(el).not.toBeNull()
      return el as HTMLElement
    })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Добавить' }))

    await waitFor(() => {
      expect(mockedAddContact).toHaveBeenCalledTimes(1)
    })
    expect(mockedAddContact).toHaveBeenCalledWith(BOB)
    expect(await screen.findByText('Уже в контактах — Борис')).toBeVisible()
    await waitFor(() => {
      expect(onOpenChat).toHaveBeenCalledWith(chatViewOf(bobChat))
    })
    await waitFor(() => {
      expect(container.querySelectorAll('.ctc-row')).toHaveLength(3)
    })
  })

  it('сбой №21 — ошибка видна, переписка не открывается', async () => {
    const { onOpenChat } = renderModal()
    mockedSearchUsers.mockResolvedValueOnce([publicUser(DAVE, 'dave', 'dave@aethergram.io')])
    mockedAddContact.mockRejectedValueOnce({ status: 429, title: 'Too Many Requests' })

    fireEvent.click(await screen.findByRole('button', { name: 'Добавить контакт' }))
    fireEvent.change(screen.getByLabelText('Username или email — точное совпадание'), {
      target: { value: 'dave@aethergram.io' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Добавить' }))
    const confirm = await waitFor(() => {
      const el = document.querySelector('.confirm-form')
      expect(el).not.toBeNull()
      return el as HTMLElement
    })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Добавить' }))

    expect(await screen.findByText('Too Many Requests')).toBeVisible()
    expect(mockedEnsureChat).not.toHaveBeenCalled()
    expect(onOpenChat).not.toHaveBeenCalled()
  })

  it('«Отмена» формы добавления возвращает список контактов', async () => {
    renderModal()
    fireEvent.click(await screen.findByRole('button', { name: 'Добавить контакт' }))

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(await screen.findByText('alice')).toBeVisible()
    expect(screen.getByText('анна')).toBeVisible()
  })
})
