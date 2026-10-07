import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ChatListItem, ContactView, Message } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * T064 — подключение «латунного звоночка» приёма к событиям 005 (US5;
 * FR-028, research §F, ui-behavior §6): страница сабскрайбит ровно два
 * триггера, оба живут в ui/sound (синтез и молчаливый пропуск — T061/T063):
 *
 *  - реальное время: onMessageCreated(null, …) — демультиплексор useRealtime
 *    отдаёт null-подписке события ЛЮБОГО чата (включая фоновые), один
 *    сигнал на событие (senderId ≠ me фильтруется в chimeOnRealtimeIncoming
 *    — собственные сообщения молчат, в том числе с другого устройства);
 *  - массовая доставка: счётчик входящих цикла catch-up (onChatUpdate
 *    страницы) сбрасывается ОДНИМ вызовом chimeOnSyncBatch(итог цикла) на
 *    завершении цикла — страницы и дельты одного цикла складываются,
 *    последовательные циклы дают по одному вызову на цикл; тишина нуля —
 *    контракт самого sound (T061: «пустой цикл — сигнала нет»);
 *  - исключения (Clarification FR-028): начальная история №16, пагинация
 *    №15 композера страницы и отправка не касаются триггеров вовсе, чужие
 *    кадры потока (chat.read) тоже молчат.
 *
 * Тесты TDD-красные до реализации (триггеры в странице не подключены —
 * моки ui/sound не вызываются).
 */

type ChatListItemSchema = components['schemas']['ChatListItem']
type SyncChatDelta = components['schemas']['SyncChatDelta']
type SyncResponse = components['schemas']['SyncResponse']

const { mockGetCurrentUser, mockChats, mockGroups, mockSse, mockPresence, mockSound } = vi.hoisted(
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
    mockSound: {
      chimeOnRealtimeIncoming: vi.fn(),
      chimeOnSyncBatch: vi.fn(),
    },
  }),
)

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => mockGroups)

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

vi.mock('../../../ui/sound', () => mockSound)

const ME = '11111111-1111-1111-1111-111111111111'
const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'
const ALICE_CHAT_ID = 'chat-direct-1'
const BOB_CHAT_ID = 'chat-direct-2'

function peer(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function chatRow(chatId: string, userId: string, username: string): ChatListItem {
  return {
    chatId,
    peer: peer(userId, username),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
  }
}

function makeMessage(chatId: string, seq: number, senderId: string): Message {
  return {
    id: `m-${chatId}-${seq}`,
    chatId,
    senderId,
    text: `text-${seq}`,
    seq,
    createdAt: `2026-09-24T10:00:${String(seq % 60).padStart(2, '0')}.000Z`,
  }
}

function delta(fields: Partial<SyncChatDelta> & Pick<SyncChatDelta, 'chatId'>): SyncChatDelta {
  return {
    peer: peer(ALICE, 'alice'),
    blockedByMe: false,
    startAfterSeq: 0,
    messages: [],
    hasMore: false,
    peerReadUpToSeq: 0,
    unreadCount: 0,
    lastSeq: 0,
    ...fields,
  }
}

function syncResponse(chats: SyncChatDelta[], moreChats = false): SyncResponse {
  return { chats, moreChats }
}

interface MockStream {
  onOpen: (() => void) | undefined
  emit(eventType: string, data: string): void
}

/** Поток с ручной подачей кадров (паттерн useChatList.test): onOpen — триггер цикла catch-up. */
function installStream(): MockStream {
  const listeners = new Map<string, (data: string) => void>()
  const mock: MockStream = {
    onOpen: undefined,
    emit(eventType, data) {
      listeners.get(eventType)?.(data)
    },
  }
  mockSse.streamUserEvents.mockImplementation((options?: { onOpen?: () => void }) => {
    mock.onOpen = options?.onOpen
    return {
      subscribe(eventType: string, listener: (data: string) => void) {
        listeners.set(eventType, listener)
        return () => {
          listeners.delete(eventType)
        }
      },
      close(): void {
        // not asserted here (covered by useRealtime tests)
      },
    }
  })
  return mock
}

function contacts(): ContactView[] {
  return []
}

async function renderPage(chats: ChatListItemSchema[] = [chatRow(ALICE_CHAT_ID, ALICE, 'alice')]) {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue(chats)
  mockChats.getChat.mockResolvedValue({
    chatId: ALICE_CHAT_ID,
    type: 'direct',
    peer: peer(ALICE, 'alice'),
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  })
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listMessagesAfter.mockResolvedValue({ messages: [] })
  mockChats.sync.mockResolvedValue(syncResponse([]))
  mockChats.markChatRead.mockResolvedValue(undefined)
  mockChats.deliveryAck.mockResolvedValue(undefined)
  mockChats.sendMessage.mockResolvedValue(makeMessage(ALICE_CHAT_ID, 1, ME))
  mockChats.listContacts.mockResolvedValue(contacts())
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  const stream = installStream()
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
  // Список №12 живёт БЕЗ пользователя (useChatList не гейтится), а
  // звуковые подписки страницы — только после getCurrentUser: №20-фетч
  // книги (reloadContacts) идёт в ТОМ же коммите пользовательского
  // стейта и РАНЬШЕ звукового эффекта — его вызов доказывает, что вся
  // пасса эффектов коммита (включая onMessageCreated/null-подписку и
  // onOpen-слушатель useSync) уже смонтирована. Без ожидания эммит
  // события гоняется с монтированием подписки (мок-поток теряет кадры
  // до подписки).
  await waitFor(() => {
    expect(mockChats.listContacts).toHaveBeenCalledTimes(1)
  })
  return stream
}

function emitMessageCreated(stream: MockStream, message: Message): void {
  act(() => {
    stream.emit('message.created', JSON.stringify({ chatId: message.chatId, message }))
  })
}

/** Открыть прямой чат alice (заголовок готов — окно живо, история отрисована). */
async function openAlice(history: Message[] = []): Promise<void> {
  mockChats.listMessages.mockResolvedValue({ messages: history })
  const list = screen.getByRole('list', { name: 'Список чатов' })
  fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
  await screen.findByRole('heading', { level: 2, name: 'alice' })
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  localStorage.clear()
})

describe('реальное время — один сигнал на входящее событие любого чата (T064, FR-028)', () => {
  it('входящее фонового чата (окно не открыто) — ровно один вызов chimeOnRealtimeIncoming', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice'),
      chatRow(BOB_CHAT_ID, BOB, 'bob'),
    ])

    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 3, BOB))

    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledTimes(1)
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledWith(
      expect.objectContaining({ chatId: BOB_CHAT_ID }),
      ME,
    )
  })

  it('входящее открытого чата звучит так же — chatId не фильтруется', async () => {
    const stream = await renderPage()

    await openAlice()
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 2, ALICE))

    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledTimes(1)
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledWith(
      expect.objectContaining({ chatId: ALICE_CHAT_ID }),
      ME,
    )
  })

  it('1:1 — три события подряд это три сигнала, коалесцирования нет', async () => {
    const stream = await renderPage()

    for (let seq = 1; seq <= 3; seq += 1) {
      emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, seq, BOB))
    }

    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledTimes(3)
  })

  it('собственное сообщение передаётся звуку с me — фильтр отправки в sound (T061); чужие кадры молчат', async () => {
    const stream = await renderPage()
    const own = makeMessage(ALICE_CHAT_ID, 5, ME)

    emitMessageCreated(stream, own)
    act(() => {
      stream.emit(
        'chat.read',
        JSON.stringify({ chatId: ALICE_CHAT_ID, readUpToSeq: 5, byUserId: ALICE }),
      )
    })

    // Проводка отдаёт КАЖДОЕ событие №16 с me — тишина собственной
    // отправки держится фильтром chimeOnRealtimeIncoming (T061:
    // «собственное сообщение (senderId === me) — сигнала нет»); wire
    // обязан передать me, чтобы фильтр мог сработать.
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledTimes(1)
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledWith(
      expect.objectContaining({ chatId: ALICE_CHAT_ID, message: own }),
      ME,
    )
    expect(mockSound.chimeOnSyncBatch).not.toHaveBeenCalled()
  })
})

describe('массовая доставка — один вызов chimeOnSyncBatch на цикл (T064, FR-028)', () => {
  it('цикл с входящими — ровно один сигнал с итогом цикла', async () => {
    const stream = await renderPage()
    mockChats.sync.mockResolvedValueOnce(
      syncResponse([
        delta({
          chatId: ALICE_CHAT_ID,
          messages: [makeMessage(ALICE_CHAT_ID, 1, ALICE), makeMessage(ALICE_CHAT_ID, 2, ALICE)],
        }),
      ]),
    )

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledTimes(1)
    })
    expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledWith(2)
  })

  it('страницы одного цикла (№26-дельта + №15-хвост hasMore) складываются в ОДИН сигнал', async () => {
    const stream = await renderPage()
    mockChats.sync.mockResolvedValueOnce(
      syncResponse([
        delta({
          chatId: ALICE_CHAT_ID,
          messages: [makeMessage(ALICE_CHAT_ID, 1, ALICE), makeMessage(ALICE_CHAT_ID, 2, ALICE)],
          hasMore: true,
        }),
      ]),
    )
    mockChats.listMessagesAfter.mockResolvedValueOnce({
      messages: [makeMessage(ALICE_CHAT_ID, 3, ALICE)],
    })

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledTimes(1)
    })
    expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledWith(3)
  })

  it('два последовательных цикла — по одному вызову на цикл (пустой цикл несёт 0 — тишина sound)', async () => {
    const stream = await renderPage()
    mockChats.sync.mockResolvedValueOnce(
      syncResponse([
        delta({ chatId: ALICE_CHAT_ID, messages: [makeMessage(ALICE_CHAT_ID, 1, ALICE)] }),
      ]),
    )

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledTimes(1)
    })

    // Второй (re)open запускает новый цикл — сервер отвечает «доставлено всё».
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledTimes(2)
    })
    expect(mockSound.chimeOnSyncBatch).toHaveBeenNthCalledWith(1, 1)
    expect(mockSound.chimeOnSyncBatch).toHaveBeenNthCalledWith(2, 0)
  })

  it('цикл только с собственными сообщениями — счётчик входящих пуст (вызов с 0)', async () => {
    const stream = await renderPage()
    mockChats.sync.mockResolvedValueOnce(
      syncResponse([
        delta({ chatId: ALICE_CHAT_ID, messages: [makeMessage(ALICE_CHAT_ID, 1, ME)] }),
      ]),
    )

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledTimes(1)
    })
    expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledWith(0)
  })
})

describe('исключения — история/отправка сигналом не сопровождаются (T064, FR-028)', () => {
  it('открытие чата с входящей историей №16 и собственная отправка не звонят', async () => {
    await renderPage()

    await openAlice([makeMessage(ALICE_CHAT_ID, 1, ALICE), makeMessage(ALICE_CHAT_ID, 2, ALICE)])
    await screen.findByText('text-1')

    const field = screen.getByLabelText('Текст сообщения')
    fireEvent.change(field, { target: { value: 'привет из теста' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    await waitFor(() => {
      expect(mockChats.sendMessage).toHaveBeenCalledTimes(1)
    })
    expect(mockSound.chimeOnRealtimeIncoming).not.toHaveBeenCalled()
    expect(mockSound.chimeOnSyncBatch).not.toHaveBeenCalled()
  })
})
