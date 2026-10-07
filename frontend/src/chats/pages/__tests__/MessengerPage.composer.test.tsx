import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView } from '../../../api/chats'
import type { EnqueueResult, UseOutboxResult } from '../../../chats/hooks/useOutbox'
import { MessengerPage } from '../MessengerPage'

/**
 * Bug 15 (T093в): обе подсказки композера на уровне СТРАНИЦЫ едут
 * тост-слотом ToastProvider (лексика T082/T045, FR-025, ui-behavior
 * §5) — инлайн-поверхностей композера больше нет:
 *
 *  - пустой черновик «Сообщение не может быть пустым» (T093а):
 *    MessageInput.send() подаёт ЛЮБОЙ отказ validateOutgoingMessage
 *    тостом (как «слишком длинное» T082); состояние/разметка/CSS
 *    `message-input-error` удалены — инлайн-ветка опустела;
 *  - отказ постановки в очередь (T093б): handleSend больше не держит
 *    `messenger-composer-error` абзац — отказ outbox.enqueue подаётся
 *    тостом о факте отклонения отправки; queue_overflow при этом
 *    остаётся за персистентным QueueOverflowBanner T052 (eviction в
 *    enqueue НЕ является отказом — запись принимается, старейшая
 *    вытесняется баннером), тост — только о действительном отказе.
 *
 * Отказ enqueue — защитная ветка (реальный UI не доходит: композер
 * disabled без пользователя, текст предвалидирован MessageInput),
 * поэтому хук useOutbox замокан с управляемым исходом enqueue;
 * контракты и валидация не меняются (SC-003 — validation.ts как есть).
 * Успех постановки молчит: тост — только результат завершённой
 * операции-уведомления (T045), отправка подтверждается своей лентой
 * («отправляется» → ✓/✓✓, SC-002).
 */

type ChatView = components['schemas']['ChatView']

const { mockGetCurrentUser, mockChats, mockGroups, mockSse, mockPresence, mockUseOutbox } =
  vi.hoisted(() => ({
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
    mockUseOutbox: vi.fn(),
  }))

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => mockGroups)

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

// Управляемый исход enqueue (T093б): страница зовёт useOutbox(currentUserId);
// остальная поверхность хука странице не нужна для этих сценариев.
vi.mock('../../../chats/hooks/useOutbox', () => ({ useOutbox: mockUseOutbox }))

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

function directRow(): ChatListItem {
  return {
    chatId: DIRECT_ID,
    peer: peer(ALICE, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
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

/** Исход enqueue по умолчанию — тихий успех (T045: успех молчит). */
const mockEnqueue = vi.fn((): EnqueueResult => ({ ok: true, clientMessageId: 'cm-test-1' }))

function installOutbox() {
  mockUseOutbox.mockReturnValue({
    records: [],
    enqueue: mockEnqueue,
    retry: vi.fn(),
    remove: vi.fn(),
    purgeChat: vi.fn(),
  } satisfies UseOutboxResult)
}

async function renderPage(): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue([directRow()])
  mockChats.getChat.mockResolvedValue(directChatView())
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

/** FR-025 «ровно один»: слот един, открыт и несёт текст. */
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

beforeEach(() => {
  installOutbox()
})

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('MessengerPage композер — уведомления тост-слотом (Bug 15, T093, FR-025)', () => {
  it('пустой черновик: ровно один тост «Сообщение не может быть пустым», инлайн-ошибки нет, enqueue не зван', async () => {
    await renderPage()
    await openDirect()

    const field = screen.getByLabelText('Текст сообщения')
    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    await expectSingleToast('Сообщение не может быть пустым')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(document.querySelector('.message-input-error')).toBeNull()

    // Черновик не потерян; постановка в очередь не случилась.
    expect(field).toHaveValue('   ')
    expect(mockEnqueue).not.toHaveBeenCalled()
  })

  it('отказ outbox.enqueue: ровно один тост о факте отклонения, инлайн-абзаца messenger-composer-error нет (T093б)', async () => {
    mockEnqueue.mockReturnValueOnce({
      ok: false,
      error: 'Отправка недоступна: нет активного пользователя',
    } satisfies EnqueueResult)
    await renderPage()
    await openDirect()

    const field = screen.getByLabelText('Текст сообщения')
    fireEvent.change(field, { target: { value: 'Привет' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    await expectSingleToast('Отправка недоступна: нет активного пользователя')
    expect(mockEnqueue).toHaveBeenCalledWith(DIRECT_ID, 'Привет')

    // Инлайн-поверхность композера удалена — только тост (FR-025).
    expect(document.querySelector('.messenger-composer-error')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('успех enqueue молчит — тоста нет, черновик очищен (отправка подтверждается лентой, T045)', async () => {
    await renderPage()
    await openDirect()

    const field = screen.getByLabelText('Текст сообщения')
    fireEvent.change(field, { target: { value: '  Привет  ' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    expect(mockEnqueue).toHaveBeenCalledWith(DIRECT_ID, 'Привет')
    expect(field).toHaveValue('')
    expectNoToast()
  })
})
