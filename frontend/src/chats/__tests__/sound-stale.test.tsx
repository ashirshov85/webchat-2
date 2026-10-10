import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import type { ChatListItem, ContactView, Message } from '../../api/chats'
import type { IncomingMessageEvent } from '../../ui/sound'
import { MessengerPage } from '../pages/MessengerPage'

/**
 * T065 — stale-сходимость звук-переключателей (008a US4, Phase 8; FR-012/
 * FR-013, ui-behavior §4.2): звук-переключатель чата меняется ТОЛЬКО
 * пользователем (№42-echo, кадр `chat.sound.updated` другого устройства,
 * SC-006) и актуальным №12-рефетчем (реальный ответ сервера ПОСЛЕ
 * переключения) — но НЕ локальными инкрементальными обновлениями списка,
 * которые пересобирают №12-строки spread'ом со СТАРЫМ `soundEnabled`
 * последнего фетча.
 *
 * Дефекты Phase 8 (воспроизводятся красными до фикса):
 *  - дефект 1: mute «жил» одно входящее — входящее №1 молчало, но тем же
 *    тиком `applyMessageCreated` порождал новый массив `chats`, эффект
 *    №12-сходимости затирал свежее №42-echo просроченным `true`, и каждое
 *    следующее входящее звонило;
 *  - дефект 2: сходимость гонялась по ВСЕМ строкам сразу — unmute чата A
 *    откатывался любым локальным апдейтом списка к значению из
 *    последнего №12-фетча, включая ЧУЖИЕ переходы чата B (unmute не
 *    держится, mute «расползается»).
 *
 * Мок-паттерн MessengerPage.sound.test.tsx (vi.hoisted + installStream);
 * ui/sound замокан целиком, но chimeOnRealtimeIncoming/chimeOnSyncBatch
 * несут КОНТРАКТ гейта T056 (false → тишина, undefined/true → playBellTone;
 * count ≥ 1 → playBellTone) — счётчик playBellTone и есть acoustic-исход
 * сценариев («0 вызовов playBellTone» наблюдается буквально).
 */

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
      sendTyping: vi.fn(() => Promise.resolve()),
      setChatSound: vi.fn(),
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
      playBellTone: vi.fn(),
      playMuteTone: vi.fn(),
    },
  }),
)

vi.mock('../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
}))

vi.mock('../../api/chats', () => mockChats)

vi.mock('../../api/groups', () => mockGroups)

vi.mock('../../api/sse', () => mockSse)

vi.mock('../../presence/presenceApi', () => mockPresence)

vi.mock('../../ui/sound', () => mockSound)

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

/**
 * №12-строка с ЯВНЫМ `soundEnabled` — строка последнего фетча несёт
 * значение-носитель stale-сходимости: локальные пересборки списка
 * сохраняют его spread'ом ДО следующего рефетча (T065 root cause).
 */
function chatRow(chatId: string, userId: string, username: string): ChatListItem {
  return {
    chatId,
    peer: peer(userId, username),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
    soundEnabled: true,
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

/** Поток с ручной подачей кадров (паттерн MessengerPage.sound.test). */
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

async function renderPage(chats: ChatListItem[]) {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  // Контракт гейта ui/sound (T056, закреплён sound.test.ts): false →
  // тишина, undefined/true → playBellTone; батч — при итогe ≥ 1.
  // Восстанавливается здесь: afterEach'ный resetAllMocks снимает
  // имплементации между тестами файла.
  mockSound.chimeOnRealtimeIncoming.mockImplementation(
    (event: IncomingMessageEvent, meUserId: string, soundEnabled?: boolean) => {
      if (event.message.senderId === meUserId) return
      if (soundEnabled === false) return
      mockSound.playBellTone()
    },
  )
  mockSound.chimeOnSyncBatch.mockImplementation((incomingCount) => {
    if (incomingCount < 1) return
    mockSound.playBellTone()
  })
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
  // Звуковые подписки страницы живут в ТОМ же коммите пользовательского
  // стейта (паттерн MessengerPage.sound.test) — №20-фетч книги доказывает,
  // что пасса эффектов смонтирована и эммит не гоняется с подпиской.
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

/** Открыть прямой чат по строке списка (заголовок с колоколом готов). */
async function openChat(username: string): Promise<void> {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  fireEvent.click(within(list).getByText(username).closest('button') as HTMLElement)
  await screen.findByRole('heading', { level: 2, name: username })
}

/** Bell-колокол заголовка по title прототипа #btnBell («Звуковые оповещения»). */
function bell(): HTMLElement {
  return screen.getByRole('button', { name: 'Звуковые оповещения' })
}

/**
 * Пользовательский №42-переключатель ОТКРЫТОГО чата: клик колокола,
 * ответ-echo сводит soundStates (тост — маркер завершения: выдаётся в
 * том же блоке ПОСЛЕ setSoundStates), звук отклика гасится заодно.
 */
async function toggleBell(chatId: string, chatName: string, next: boolean): Promise<void> {
  mockChats.setChatSound.mockResolvedValueOnce({ chatId, soundEnabled: next })
  fireEvent.click(bell())
  await screen.findByText(`Звуковые оповещения ${next ? 'включены' : 'отключены'} — ${chatName}`)
  mockSound.playBellTone.mockClear()
  mockSound.playMuteTone.mockClear()
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  localStorage.clear()
})

describe('mute держится на входящих — локальные обновления списка звук не трогают (T065, дефект 1; FR-013)', () => {
  it('(1) mute A → три входящих подряд в A — 0 вызовов playBellTone', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice'),
      chatRow(BOB_CHAT_ID, BOB, 'bob'),
    ])

    await openChat('alice')
    await toggleBell(ALICE_CHAT_ID, 'alice', false)

    for (let seq = 1; seq <= 3; seq += 1) {
      emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, seq, ALICE))
    }

    expect(mockSound.playBellTone).not.toHaveBeenCalled()
  })

  it('(2) mute A → входящие в B (не приглушён) — сигнал звучит для B, A молчит', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice'),
      chatRow(BOB_CHAT_ID, BOB, 'bob'),
    ])

    await openChat('alice')
    await toggleBell(ALICE_CHAT_ID, 'alice', false)

    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 1, BOB))
    expect(mockSound.playBellTone).toHaveBeenCalledTimes(1)
    // Приглушённый A молчит и ПОСЛЕ локального обновления списка от
    // входящего в B (stale-сходимость не откатывает mute чата A).
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 1, ALICE))
    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 2, BOB))
    expect(mockSound.playBellTone).toHaveBeenCalledTimes(2)
  })
})

describe('переключения нескольких чатов независимы (T065, дефект 2; FR-012/FR-013)', () => {
  it('(3) mute A + mute B → unmute A: входящее в A звучит, в B — тишина', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice'),
      chatRow(BOB_CHAT_ID, BOB, 'bob'),
    ])

    await openChat('alice')
    await toggleBell(ALICE_CHAT_ID, 'alice', false)
    await openChat('bob')
    await toggleBell(BOB_CHAT_ID, 'bob', false)
    await openChat('alice')
    await toggleBell(ALICE_CHAT_ID, 'alice', true)

    // Входящее в A локально обновляет список — но звук B не откатывается.
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 1, ALICE))
    expect(mockSound.playBellTone).toHaveBeenCalledTimes(1)
    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 1, BOB))
    expect(mockSound.playBellTone).toHaveBeenCalledTimes(1)
  })

  it('(5) повторное включение №42 — звук возвращается со следующего сообщения', async () => {
    const stream = await renderPage([chatRow(ALICE_CHAT_ID, ALICE, 'alice')])

    await openChat('alice')
    await toggleBell(ALICE_CHAT_ID, 'alice', false)
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 1, ALICE))
    expect(mockSound.playBellTone).not.toHaveBeenCalled()

    await toggleBell(ALICE_CHAT_ID, 'alice', true)
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 2, ALICE))
    expect(mockSound.playBellTone).toHaveBeenCalledTimes(1)
  })
})

describe('№12-рефетч — легитимный источник сходимости (T065; FR-012, reconnect)', () => {
  it('(4) mute A → рефетч (реконнект) с A=false — тишина сохраняется; с A=true — звук возвращается', async () => {
    const stream = await renderPage([chatRow(ALICE_CHAT_ID, ALICE, 'alice')])

    await openChat('alice')
    await toggleBell(ALICE_CHAT_ID, 'alice', false)

    // (Ре)подключение рефетчит №12 — серверная правда ПОСЛЕ переключения.
    mockChats.listChats.mockResolvedValue([
      { ...chatRow(ALICE_CHAT_ID, ALICE, 'alice'), soundEnabled: false },
    ])
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockChats.listChats).toHaveBeenCalledTimes(2)
    })
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 1, ALICE))
    expect(mockSound.playBellTone).not.toHaveBeenCalled()

    // Рефетч — реальный ответ сервера: значение чата меняется и сходится
    // (колокол-проекция это и доказывает — aria-pressed flips).
    mockChats.listChats.mockResolvedValue([chatRow(ALICE_CHAT_ID, ALICE, 'alice')])
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockChats.listChats).toHaveBeenCalledTimes(3)
    })
    await waitFor(() => {
      expect(bell()).toHaveAttribute('aria-pressed', 'true')
    })
    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 2, ALICE))
    expect(mockSound.playBellTone).toHaveBeenCalledTimes(1)
  })
})

describe('sync-батч — per-chat подсчёт не меняется (T065, критерий г; FR-013/FR-014)', () => {
  it('батч только с приглушёнными — тишина; с ≥1 неприглушённым — один сигнал', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice'),
      chatRow(BOB_CHAT_ID, BOB, 'bob'),
    ])

    await openChat('alice')
    await toggleBell(ALICE_CHAT_ID, 'alice', false)
    // Серверная правда №12 после переключения (рефетчи реконнектов ниже).
    mockChats.listChats.mockResolvedValue([
      { ...chatRow(ALICE_CHAT_ID, ALICE, 'alice'), soundEnabled: false },
      chatRow(BOB_CHAT_ID, BOB, 'bob'),
    ])

    // Цикл 1: только приглушённый A — итог 0, тишина.
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
      expect(mockChats.sync).toHaveBeenCalledTimes(1)
    })

    // Цикл 2: смешанный — считается только неприглушённый B (один сигнал).
    mockChats.sync.mockResolvedValueOnce(
      syncResponse([
        delta({ chatId: ALICE_CHAT_ID, messages: [makeMessage(ALICE_CHAT_ID, 3, ALICE)] }),
        delta({ chatId: BOB_CHAT_ID, messages: [makeMessage(BOB_CHAT_ID, 1, BOB)] }),
      ]),
    )
    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockChats.sync).toHaveBeenCalledTimes(2)
    })

    // Ровно один звонок обоих циклов суммарно: цикл 1 — тишина, цикл 2 —
    // один сигнал (≥1 неприглушённый). Любой звонок цикла 1 дал бы 2.
    await waitFor(() => {
      expect(mockSound.playBellTone).toHaveBeenCalledTimes(1)
    })
  })
})
