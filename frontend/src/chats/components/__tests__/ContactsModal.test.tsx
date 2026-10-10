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
  setContactAlias,
  unblockUser,
} from '../../../api/chats'
import type { ChatListItem, ChatView, ContactView } from '../../../api/chats'
import type { PublicUser } from '../../../api/auth'
import { colorOf } from '../../../ui/avatar'
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
 * становятся клиентскими (с 008a T022 — по цепочке имён
 * `alias → displayName → username` (locale ru прототипа, тай-брейк
 * username 'und') и подстрока [цепочка, username, email] без
 * регистра); пометки «заблокирован»/«чат удалён» — из
 * пропа chats (№12, blockedByMe/наличие связанного чата); клик без
 * чата — БЕЗ действия (Clarification: создание чата — только пунктом
 * «Создать чат»); «⋯»-меню (ContextMenu) с подтверждениями
 * (ConfirmDialog, SC-007) и тостами (ToastProvider, FR-025); форма
 * «Добавить контакт» (T032) — внутри той же модали. 008a T022
 * добавляет пункт «Редактировать» (№40) ПЕРВЫМ в «⋯»-меню —
 * глубокие тесты формы переименования/цепочки пишет T024.
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
  setContactAlias: vi.fn(),
}))

const mockedListContacts = vi.mocked(listContacts)
const mockedEnsureChat = vi.mocked(ensureChat)
const mockedRemoveContact = vi.mocked(removeContact)
const mockedSearchUsers = vi.mocked(searchUsers)
const mockedAddContact = vi.mocked(addContact)
const mockedBlockUser = vi.mocked(blockUser)
const mockedUnblockUser = vi.mocked(unblockUser)
const mockedDeleteChat = vi.mocked(deleteChat)
// №40 (008a T022): поведенческие тесты формы переименования/цепочки — T024.
const mockedSetContactAlias = vi.mocked(setContactAlias)

const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const CAROL = '44444444-4444-4444-4444-444444444444'
const DAVE = '55555555-5555-5555-5555-555555555555'

function publicUser(id: string, username: string, email: string): PublicUser {
  return { id, username, email, status: 'active', createdAt: '2026-09-01T00:00:00.000Z' }
}

function contactView(
  id: string,
  username: string,
  email: string,
  blockedByMe = false,
): ContactView {
  return {
    user: publicUser(id, username, email),
    createdAt: '2026-09-10T00:00:00.000Z',
    blockedByMe,
  }
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
  const onChatDeleted = vi.fn()
  const onContactBlockToggled = vi.fn()
  const onContactRemoved = vi.fn()
  const onContactRenamed = vi.fn()
  const view = render(
    <ToastProvider>
      <ContactsModal
        chats={chats}
        onOpenChat={onOpenChat}
        onChatDeleted={onChatDeleted}
        onContactBlockToggled={onContactBlockToggled}
        onContactRemoved={onContactRemoved}
        onContactRenamed={onContactRenamed}
      />
    </ToastProvider>,
  )
  const rerenderChats = (next: readonly ChatListItem[]) => {
    view.rerender(
      <ToastProvider>
        <ContactsModal
          chats={next}
          onOpenChat={onOpenChat}
          onChatDeleted={onChatDeleted}
          onContactBlockToggled={onContactBlockToggled}
          onContactRemoved={onContactRemoved}
          onContactRenamed={onContactRenamed}
        />
      </ToastProvider>,
    )
  }
  return {
    onOpenChat,
    onChatDeleted,
    onContactBlockToggled,
    onContactRemoved,
    onContactRenamed,
    rerenderChats,
    ...view,
  }
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
  it('сортирует №20 по цепочке имён (locale ru прототипа, тай-брейк username) и рендерит строки', async () => {
    const { container } = renderModal()

    expect(await screen.findByText('alice')).toBeVisible()
    // Сервер вернул [Борис, анна, alice] — модаль пересортировала сама:
    // цепочка без alias/displayName = username, ru-коллация прототипа —
    // кириллица раньше латиницы, «анна» < «Борис» (не code-unit порядок).
    const names = Array.from(container.querySelectorAll('.ctc-row .c-name')).map(
      (node) => node.textContent,
    )
    expect(names).toEqual(['анна', 'Борис', 'alice'])
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

    expect(menuLabels()).toEqual([
      'Редактировать',
      'Заблокировать',
      'Удалить чат',
      'Удалить контакт',
    ])
    // Единственный разделитель — перед «Удалить контакт»; пункт — danger.
    expect(menu.querySelectorAll('.ctx-sep')).toHaveLength(1)
    const danger = menu.querySelector('button.danger')
    expect(danger?.textContent).toBe('Удалить контакт')
  })

  it('заблокированный — «Разблокировать»; без чата — «Создать чат» вместо «Удалить чат»', async () => {
    const { container } = renderModal()
    await screen.findByText('анна')

    openContactMenu(container, 'анна')
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Разблокировать',
      'Удалить чат',
      'Удалить контакт',
    ])
    closeMenu()

    openContactMenu(container, 'Борис')
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Заблокировать',
      'Создать чат',
      'Удалить контакт',
    ])
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
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Разблокировать',
      'Удалить чат',
      'Удалить контакт',
    ])
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
    const { container, rerenderChats, onOpenChat, onChatDeleted } = renderModal()
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
    // T037: владелец оболочки узнаёт об удалении — открытый чат вернётся
    // к «Чат не выбран» (FR-032, без автоперехода).
    expect(onChatDeleted).toHaveBeenCalledWith('chat-alice')
    expect(await screen.findByText('Чат удалён — контакт сохранён')).toBeVisible()
    // Контакт остаётся (FR-017), переписка не открывается.
    expect(rowOf(container, 'alice')).toBeVisible()
    expect(onOpenChat).not.toHaveBeenCalled()

    rerenderChats([CAROL_CHAT])
    expect(rowOf(container, 'alice').querySelector('.c-prev')?.textContent ?? '').toMatch(
      /чат удалён/i,
    )
    openContactMenu(container, 'alice')
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Заблокировать',
      'Создать чат',
      'Удалить контакт',
    ])
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
    ).toEqual(['Борис', 'alice'])
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

describe('ContactsModal синхронизация владельца после «⋯»-действий (T089, Bug 11)', () => {
  /**
   * Bug 11 (008 T089): успех №23/№24/№22 из «⋯»-меню сообщает владельцу
   * оболочки (паттерн onChatDeleted T037) — страница рефетчит №12/№20, и
   * пометка строки/композер/шестерёнка меняются живьём, без перезагрузки.
   * Колбэки — только на УСПЕХ: сбой и отмена владельцу не докладывают.
   */

  it('успех №23 — onContactBlockToggled(userId, true) ровно один раз', async () => {
    const { container, onContactBlockToggled } = renderModal()
    await screen.findByText('alice')

    openContactMenu(container, 'alice')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать' }))
    fireEvent.click(screen.getByRole('button', { name: 'Заблокировать' }))

    await waitFor(() => {
      expect(mockedBlockUser).toHaveBeenCalledWith(ALICE)
    })
    await waitFor(() => {
      expect(onContactBlockToggled).toHaveBeenCalledTimes(1)
    })
    expect(onContactBlockToggled).toHaveBeenLastCalledWith(ALICE, true)
  })

  it('успех №24 — onContactBlockToggled(userId, false)', async () => {
    const { container, onContactBlockToggled } = renderModal()
    await screen.findByText('анна')

    openContactMenu(container, 'анна')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Разблокировать' }))
    fireEvent.click(screen.getByRole('button', { name: 'Разблокировать' }))

    await waitFor(() => {
      expect(mockedUnblockUser).toHaveBeenCalledWith(CAROL)
    })
    await waitFor(() => {
      expect(onContactBlockToggled).toHaveBeenCalledTimes(1)
    })
    expect(onContactBlockToggled).toHaveBeenLastCalledWith(CAROL, false)
  })

  it('успех №22 — onContactRemoved(userId) ровно один раз', async () => {
    // vi.clearAllMocks() не сбрасывает persistent mockRejectedValue
    // сбой-теста выше — успех задаём явно.
    mockedRemoveContact.mockResolvedValue(undefined)
    const { container, onContactRemoved } = renderModal()
    await screen.findByText('анна')

    openContactMenu(container, 'анна')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить контакт' }))
    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))

    await waitFor(() => {
      expect(mockedRemoveContact).toHaveBeenCalledWith(CAROL)
    })
    await waitFor(() => {
      expect(onContactRemoved).toHaveBeenCalledTimes(1)
    })
    expect(onContactRemoved).toHaveBeenLastCalledWith(CAROL)
  })

  it('сбой №23/№22 — колбэки не вызываются (доклад только об успехе)', async () => {
    mockedBlockUser.mockRejectedValue({ status: 500, title: 'Internal Server Error' })
    const first = renderModal()
    await screen.findByText('alice')
    openContactMenu(first.container, 'alice')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать' }))
    fireEvent.click(screen.getByRole('button', { name: 'Заблокировать' }))
    expect(await screen.findByText('Internal Server Error')).toBeVisible()

    mockedRemoveContact.mockRejectedValue({ status: 500, title: 'Internal Server Error' })
    const second = renderModal()
    await screen.findByText('анна')
    openContactMenu(second.container, 'анна')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Удалить контакт' }))
    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))
    expect(await screen.findByText('Internal Server Error')).toBeVisible()

    expect(first.onContactBlockToggled).not.toHaveBeenCalled()
    expect(second.onContactRemoved).not.toHaveBeenCalled()
  })
})

describe('ContactsModal блокировка контакта с удалённым чатом (T097, bug 16)', () => {
  /**
   * Bug 16: чат удалён → №12 теряет blockedByMe пары, и до 0.8.0 состояние
   * блокировки не проецировалось вовсе. (а) сессионный optimistic-override
   * Map<userId, boolean> по собственным №23/№24-успехам; (б) №20
   * ContactView.blockedByMe (контракт 0.8.0) — переживает F5; при живом
   * чате источником остаётся №12 (T089): мерж override → №12 → №20.
   */

  it('(б) №20 blockedByMe=true без чата — строка помечена, пункт «Разблокировать» (переживает F5)', async () => {
    mockedListContacts.mockResolvedValue([contactView(DAVE, 'dave', 'dave@aethergram.io', true)])
    const { container } = renderModal()
    await screen.findByText('dave')

    const row = rowOf(container, 'dave')
    expect(row.className).toMatch(/\bblocked\b/)
    expect(row.querySelector('.c-prev')?.textContent ?? '').toMatch(/заблокирован/i)

    openContactMenu(container, 'dave')
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Разблокировать',
      'Создать чат',
      'Удалить контакт',
    ])
  })

  it('(а) блокировка без чата: №23 → метка/.blocked сразу, пункт переключается, T089-доклад на месте', async () => {
    // vi.clearAllMocks() не сбрасывает persistent mockRejectedValue
    // сбой-тестов выше — успех задаём явно (паттерн T089-блока).
    mockedBlockUser.mockResolvedValue(undefined)
    mockedListContacts.mockResolvedValue([DAVE_CONTACT, ...CONTACTS])
    const { container, onContactBlockToggled } = renderModal()
    await screen.findByText('dave')

    openContactMenu(container, 'dave')
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Заблокировать',
      'Создать чат',
      'Удалить контакт',
    ])
    fireEvent.click(screen.getByRole('menuitem', { name: 'Заблокировать' }))
    fireEvent.click(screen.getByRole('button', { name: 'Заблокировать' }))

    await waitFor(() => {
      expect(mockedBlockUser).toHaveBeenCalledWith(DAVE)
    })
    expect(await screen.findByText('Контакт заблокирован — dave')).toBeVisible()
    // Сессионный override: метка появляется БЕЗ обновления пропа chats
    // (№12 чат не несёт — рефетч ничего бы не дал).
    const row = rowOf(container, 'dave')
    expect(row.className).toMatch(/\bblocked\b/)
    expect(row.querySelector('.c-prev')?.textContent ?? '').toMatch(/заблокирован/i)
    openContactMenu(container, 'dave')
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Разблокировать',
      'Создать чат',
      'Удалить контакт',
    ])
    expect(onContactBlockToggled).toHaveBeenCalledWith(DAVE, true)
  })

  it('(а) разблокировка без чата: №24 → метка снята, пункт «Заблокировать»', async () => {
    mockedUnblockUser.mockResolvedValue(undefined)
    mockedListContacts.mockResolvedValue([contactView(DAVE, 'dave', 'dave@aethergram.io', true)])
    const { container, onContactBlockToggled } = renderModal()
    await screen.findByText('dave')

    openContactMenu(container, 'dave')
    fireEvent.click(screen.getByRole('menuitem', { name: 'Разблокировать' }))
    fireEvent.click(screen.getByRole('button', { name: 'Разблокировать' }))

    await waitFor(() => {
      expect(mockedUnblockUser).toHaveBeenCalledWith(DAVE)
    })
    expect(await screen.findByText('Контакт разблокирован — dave')).toBeVisible()
    expect(rowOf(container, 'dave').className).not.toMatch(/\bblocked\b/)
    openContactMenu(container, 'dave')
    expect(menuLabels()).toEqual([
      'Редактировать',
      'Заблокировать',
      'Создать чат',
      'Удалить контакт',
    ])
    expect(onContactBlockToggled).toHaveBeenCalledWith(DAVE, false)
  })

  it('живой чат — №12 важнее №20 в обе стороны (T089-семантика не меняется)', async () => {
    // №20 при монтировании говорит true, №12 живого чата — false: №12
    // свежее (рефетч T089), метки нет.
    mockedListContacts.mockResolvedValue([contactView(ALICE, 'alice', 'alice@example.com', true)])
    const { container, rerenderChats } = renderModal([ALICE_CHAT])
    await screen.findByText('alice')
    expect(rowOf(container, 'alice').className).not.toMatch(/\bblocked\b/)
    openContactMenu(container, 'alice')
    expect(menuLabels()[1]).toBe('Заблокировать')
    closeMenu()

    // №12 приехал с blockedByMe=true — метка есть поверх устаревшего №20.
    rerenderChats([directChat(ALICE, 'alice', 'alice@example.com', { blockedByMe: true })])
    expect(rowOf(container, 'alice').className).toMatch(/\bblocked\b/)
    openContactMenu(container, 'alice')
    expect(menuLabels()[1]).toBe('Разблокировать')
  })
})

describe('ContactsModal форма «Добавить контакт» (FR-012)', () => {
  it('промах №19 — ошибка «требуется точное совпадение», №21 не вызывается; запрос триммится (004)', async () => {
    renderModal()
    fireEvent.click(await screen.findByRole('button', { name: 'Добавить новый контакт' }))

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

    fireEvent.click(await screen.findByRole('button', { name: 'Добавить новый контакт' }))
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

    fireEvent.click(await screen.findByRole('button', { name: 'Добавить новый контакт' }))
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

    fireEvent.click(await screen.findByRole('button', { name: 'Добавить новый контакт' }))
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
    fireEvent.click(await screen.findByRole('button', { name: 'Добавить новый контакт' }))

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(await screen.findByText('alice')).toBeVisible()
    expect(screen.getByText('анна')).toBeVisible()
  })
})

/**
 * Цепочка имён, форма «Редактировать» и экранирование (feature 008a, US1,
 * T024; FR-003/FR-005, US1 AC5/008 FR-033; ui-behavior §1/§1.2): глубокие
 * тесты поверх T022 — сортировка/фильтр живут по ЕДИНОЙ цепочке
 * `resolveDisplayName(alias, displayName, username)` (locale ru прототипа,
 * тай-брейк username 'und'), №40-форма (предзаполнение цепочкой, сброс
 * пустым значением = явный null, пробельная строка — ошибка без запроса,
 * тост «Контакт переименован — {цепочка}», доклад onContactRenamed) и
 * HTML-вставки в displayName/alias рендерятся как текст (React-инвариант,
 * dangerouslySetInnerHTML не вводится).
 */

/** Контакт с произвольными звеньями цепочки (008a T024): alias/displayName опциональны. */
function namedContact(
  id: string,
  username: string,
  email: string,
  names: { alias?: string; displayName?: string } = {},
): ContactView {
  return {
    user: {
      ...publicUser(id, username, email),
      ...(names.displayName === undefined ? {} : { displayName: names.displayName }),
    },
    createdAt: '2026-09-10T00:00:00.000Z',
    blockedByMe: false,
    ...(names.alias === undefined ? {} : { alias: names.alias }),
  }
}

const VALYA_ID = '66666666-6666-6666-6666-666666666666'
const ANNA_ID = '77777777-7777-7777-7777-777777777777'
const XENA_ID = '88888888-8888-8888-8888-888888888888'
const AALTO_ID = '99999999-9999-9999-9999-999999999999'
const ACKER_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
const XSS_ID = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

/** alias бьёт displayName (Валя), displayName бьёт username (Anna), фолбэк username (xena). */
const VALYA = namedContact(VALYA_ID, 'carol', 'carol@steam.ru', {
  displayName: 'Zeta',
  alias: 'Валя',
})
const ANNA = namedContact(ANNA_ID, 'dave', 'dave@brass.io', { displayName: 'Anna' })
const XENA = namedContact(XENA_ID, 'xena', 'xena@aethergram.io')
/** Совпадающие цепочки «Sam» — порядок решает тай-брейк username ('und');
 * пара aalto/acker даёт РАЗНЫЕ цвета аватаров (colorOf = fnv1a mod 6 —
 * «aaron» колллит с «aalto» и строки неразличимы по --av). */
const SAM_AALTO = namedContact(AALTO_ID, 'aalto', 'aalto@example.com', { alias: 'Sam' })
const SAM_ACKER = namedContact(ACKER_ID, 'acker', 'acker@example.com', { displayName: 'Sam' })

/** Строка по username (совпадающие цепочки неразличимы по .c-name): цвет
 * аватара детерминированно выводится из username (FR-004) — inline --av. */
function rowByUsername(container: HTMLElement, username: string): HTMLElement {
  const row = Array.from(container.querySelectorAll<HTMLElement>('.ctc-row')).find(
    (node) =>
      node.querySelector<HTMLElement>('.avatar')?.style.getPropertyValue('--av') ===
      colorOf(username),
  )
  if (row === undefined) {
    throw new Error(`строка контакта не найдена по username: ${username}`)
  }
  return row
}

/** Открывает форму «Имя контакта» через «⋯» → «Редактировать» и возвращает поле. */
async function openRenameForm(
  container: HTMLElement,
  chainName: string,
): Promise<HTMLInputElement> {
  openContactMenu(container, chainName)
  fireEvent.click(screen.getByRole('menuitem', { name: 'Редактировать' }))
  return screen.findByLabelText<HTMLInputElement>('Имя контакта')
}

describe('ContactsModal цепочка имён: сортировка и фильтр (008a T024, FR-005)', () => {
  it('сортирует по цепочке alias → displayName → username (locale ru), а не по username', async () => {
    // Сервер вернул [xena, Anna, Валя] — модаль пересортировала по ЦЕПОЧКЕ:
    // кириллический alias «Валя» (звено выше displayName «Zeta») раньше
    // латинских «Anna»/«xena» (ru-коллация прототипа — кириллица первее).
    mockedListContacts.mockResolvedValue([XENA, ANNA, VALYA])
    const { container } = renderModal([])

    expect(await screen.findByText('Валя')).toBeVisible()
    expect(
      Array.from(container.querySelectorAll('.ctc-row .c-name')).map((node) => node.textContent),
    ).toEqual(['Валя', 'Anna', 'xena'])
  })

  it('совпадающие цепочки различает тай-брейк username (коллация «und»)', async () => {
    mockedListContacts.mockResolvedValue([SAM_ACKER, SAM_AALTO])
    const { container } = renderModal([])

    await screen.findAllByText('Sam')
    const rows = Array.from(container.querySelectorAll('.ctc-row'))
    expect(rows).toHaveLength(2)
    // «aalto» < «acker» корневой коллацией — строка aalto первая.
    expect(rows.indexOf(rowByUsername(container, 'aalto'))).toBe(0)
    expect(rows.indexOf(rowByUsername(container, 'acker'))).toBe(1)
  })

  it('фильтрует по цепочке без регистра: alias и displayName находятся, перекрытое имя — нет', async () => {
    mockedListContacts.mockResolvedValue([VALYA, ANNA, XENA])
    const { container } = renderModal([])
    const search = await screen.findByPlaceholderText('Поиск контакта — имя, username или email')

    // alias-звено цепочки (ВАЛ в верхнем регистре находит «Валя»)
    fireEvent.change(search, { target: { value: 'ВАЛ' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(1)
    expect(rowOf(container, 'Валя')).toBeVisible()

    // displayName-звено цепочки (Anna задан поверх username dave)
    fireEvent.change(search, { target: { value: 'nn' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(1)
    expect(rowOf(container, 'Anna')).toBeVisible()

    // username-фолбэк
    fireEvent.change(search, { target: { value: 'XEN' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(1)
    expect(rowOf(container, 'xena')).toBeVisible()

    // перекрытый alias-ом displayName «Zeta» — НЕ часть цепочки: фильтр
    // ищет по [цепочка, username, email], промах — «Ничего не найдено»
    fireEvent.change(search, { target: { value: 'zeta' } })
    expect(container.querySelectorAll('.ctc-row')).toHaveLength(0)
    expect(screen.getByText('Ничего не найдено')).toBeVisible()
  })
})

describe('ContactsModal форма «Редактировать» (008a T024, ui-behavior §1.2)', () => {
  it('«⋯» → «Редактировать»: поле предзаполнено цепочкой (alias), maxlength 64, username/email readonly', async () => {
    mockedListContacts.mockResolvedValue([VALYA])
    const { container } = renderModal([])
    await screen.findByText('Валя')

    const field = await openRenameForm(container, 'Валя')

    // Предзаполнение — текущая ЦЕПОЧКА строки (alias «Валя», не displayName «Zeta»)
    expect(field).toHaveValue('Валя')
    expect(field).toHaveAttribute('maxlength', '64')
    // «меняется только имя»: username/email — строки .modal-ro, не поля
    expect(screen.getByText('carol')).toBeVisible()
    expect(screen.getByText('carol@steam.ru')).toBeVisible()
    expect(screen.getAllByRole('textbox')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Отмена' })).toBeVisible()
  })

  it('сохранение нового alias: №40 → строка обновляется ответом, тост с цепочкой, доклад владельцу', async () => {
    mockedListContacts.mockResolvedValue([VALYA, XENA])
    const { container, onContactRenamed } = renderModal([])
    await screen.findByText('Валя')
    mockedSetContactAlias.mockResolvedValueOnce(
      namedContact(VALYA_ID, 'carol', 'carol@steam.ru', {
        displayName: 'Zeta',
        alias: 'Валентина',
      }),
    )

    const field = await openRenameForm(container, 'Валя')
    fireEvent.change(field, { target: { value: 'Валентина' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => {
      expect(mockedSetContactAlias).toHaveBeenCalledWith(VALYA_ID, { alias: 'Валентина' })
    })
    // Строка обновляется ОТВЕТОМ №40 (без рефетча №20) и пересортируется
    expect(await screen.findByText('Валентина')).toBeVisible()
    expect(rowOf(container, 'Валентина')).toBeVisible()
    // Тост — цепочка обновлённого контакта (ui-behavior §5)
    expect(await screen.findByText('Контакт переименован — Валентина')).toBeVisible()
    // T022: владелец рефетчит №12 — peerAlias «Чатов» сходится без F5
    await waitFor(() => {
      expect(onContactRenamed).toHaveBeenCalledTimes(1)
    })
    expect(onContactRenamed).toHaveBeenLastCalledWith(VALYA_ID)
    // Форма закрылась — вернулся список (поиск + вторая строка живы)
    expect(screen.getByPlaceholderText('Поиск контакта — имя, username или email')).toBeVisible()
    expect(rowOf(container, 'xena')).toBeVisible()
  })

  it('полностью очищенное поле — явный null (сброс alias): цепочка возвращается к displayName', async () => {
    mockedListContacts.mockResolvedValue([VALYA])
    const { container } = renderModal([])
    await screen.findByText('Валя')
    mockedSetContactAlias.mockResolvedValueOnce(
      namedContact(VALYA_ID, 'carol', 'carol@steam.ru', { displayName: 'Zeta' }),
    )

    const field = await openRenameForm(container, 'Валя')
    fireEvent.change(field, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => {
      expect(mockedSetContactAlias).toHaveBeenCalledWith(VALYA_ID, { alias: null })
    })
    // Сброс: alias снят — цепочка падает на displayName «Zeta»
    expect(await screen.findByText('Zeta')).toBeVisible()
    expect(await screen.findByText('Контакт переименован — Zeta')).toBeVisible()
  })

  it('непустая строка только из пробелов — ошибка «Укажите имя контакта» БЕЗ запроса (зеркало 400 invalid_alias)', async () => {
    mockedListContacts.mockResolvedValue([VALYA])
    const { container } = renderModal([])
    await screen.findByText('Валя')

    const field = await openRenameForm(container, 'Валя')
    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Укажите имя контакта')).toBeVisible()
    expect(mockedSetContactAlias).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')?.textContent).toBe('')
    // Форма жива: значение сохранено, можно исправить и сохранить
    expect(screen.getByLabelText('Имя контакта')).toHaveValue('   ')
  })

  it('сбой №40 — инлайн-ошибка формы, тоста и доклада нет; «Отмена» возвращает список', async () => {
    mockedListContacts.mockResolvedValue([VALYA])
    const { container, onContactRenamed } = renderModal([])
    await screen.findByText('Валя')
    mockedSetContactAlias.mockRejectedValueOnce({ status: 429, title: 'Too Many Requests' })

    const field = await openRenameForm(container, 'Валя')
    fireEvent.change(field, { target: { value: 'Валентина' } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    expect(await screen.findByText('Too Many Requests')).toBeVisible()
    expect(screen.queryByRole('status')?.textContent).toBe('')
    expect(onContactRenamed).not.toHaveBeenCalled()
    // Форма жива (сбой — не закрытие), строка не изменилась
    expect(screen.getByLabelText('Имя контакта')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))
    expect(await screen.findByText('Валя')).toBeVisible()
    expect(screen.queryByLabelText('Имя контакта')).toBeNull()
  })

  it('«Отмена» без ввода — назад к списку, №40 не вызывается (SC-007)', async () => {
    mockedListContacts.mockResolvedValue([VALYA])
    const { container } = renderModal([])
    await screen.findByText('Валя')

    await openRenameForm(container, 'Валя')
    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }))

    expect(await screen.findByText('Валя')).toBeVisible()
    expect(mockedSetContactAlias).not.toHaveBeenCalled()
  })
})

describe('ContactsModal экранирование цепочки имён (008a T024, US1 AC5, 008 FR-033)', () => {
  /** HTML-вставка из спеки: рендерится как ТЕКСТ, не как разметка. */
  const XSS_IMG = '<img src=x onerror="alert(1)">'

  it('displayName с HTML-вставкой — строка и aria-label несут literal-текст, инъекции в DOM нет', async () => {
    mockedListContacts.mockResolvedValue([
      namedContact(XSS_ID, 'mallory', 'mallory@evil.io', { displayName: XSS_IMG }),
    ])
    const { container } = renderModal([])
    await screen.findByText(XSS_IMG)

    // .c-name — текстовый узел с literal-строкой, <img>-элемента нет
    expect(container.querySelector('.c-name img')).toBeNull()
    expect(document.querySelector('img[onerror]')).toBeNull()
    // aria-label строки — тоже текст (не разметка)
    expect(screen.getByRole('button', { name: `Контакт ${XSS_IMG}` })).toBeVisible()
  })

  it('alias с HTML-вставкой: №40-ответ обновляет строку и тост как текст, инъекции нет', async () => {
    mockedListContacts.mockResolvedValue([XENA])
    const { container } = renderModal([])
    await screen.findByText('xena')
    mockedSetContactAlias.mockResolvedValueOnce(
      namedContact(XENA_ID, 'xena', 'xena@aethergram.io', { alias: XSS_IMG }),
    )

    const field = await openRenameForm(container, 'xena')
    fireEvent.change(field, { target: { value: XSS_IMG } })
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }))

    await waitFor(() => {
      expect(mockedSetContactAlias).toHaveBeenCalledWith(XENA_ID, { alias: XSS_IMG })
    })
    expect(await screen.findByText(XSS_IMG)).toBeVisible()
    // Тост несёт вставку как текст (React-инвариант 008 FR-033)
    expect(await screen.findByText(`Контакт переименован — ${XSS_IMG}`)).toBeVisible()
    expect(container.querySelector('.c-name img')).toBeNull()
    expect(document.querySelector('img[onerror]')).toBeNull()
    expect(document.querySelector('.toast img')).toBeNull()
  })
})
