import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import type { ChatListItem, ContactView, Message } from '../../api/chats'
import { MessengerPage } from '../pages/MessengerPage'

/**
 * T059 — звук-гейт 008a US4 по мок-паттерну MessengerPage.sound.test.tsx
 * (vi.hoisted + installStream; FR-012–FR-015, ui-behavior §4.2, SC-006):
 * per-chat карта звук-переключателей страницы (soundStates — №12/№13/№42/
 * chat.sound.updated, T058) гейтит ПРИЁМ, не фильтруя визуальную доставку.
 *
 *  - реальное время: проводка ВСЁГДА вызывает chimeOnRealtimeIncoming
 *    (по одному разу на событие, 008 T064), но передаёт per-chat
 *    состояние третьим аргументом — приглушённый №12-строкой чат несёт
 *    false, явное «вкл» — true, отсутствующее поле (legacy-поверхность
 *    008) — undefined; решение «звонить ли» живёт в самом ui/sound
 *    (T056: false → тишина, undefined → звучит) — здесь проверяется
 *    ПЕРЕДАЧА состояния, синтез замокан;
 *  - sync-батч 005: подсчёт входящих PER-CHAT — приглушённые чаты в
 *    итог цикла не попадают (батч только с приглушёнными → итог 0 —
 *    0 звуков, тишина самого sound); ВИЗУАЛЬНАЯ доставка (превью/
 *    бейджи/лента) гейтом не фильтруется вовсе (FR-014);
 *  - bell-колокол заголовка (T057 — проекция): №12-состояние ложится
 *    в aria-pressed/.off, клик — №42 с ЦЕЛЕВЫМ значением, echo ответа
 *    сходится, отклик «вкл»/«выкл» — playBellTone/playMuteTone, один
 *    тост «Звуковые оповещения включены|отключены — {имя по цепочке
 *    US1}»; ошибка (сеть/429) — состояние НЕ меняется, откликов нет,
 *    тост problemMessage; кадр chat.sound.updated СОБСТВЕННОГО канала
 *    (другой девайс, SC-006 ≤ 2 с) flips колокол и гейт БЕЗ №42 и
 *    без тоста.
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
      // 008a №42 — bell-клик страницы (T057/T058): ответ-echo сводит
      // soundStates, звук отклика и тост.
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
 * №12-строка с опциональным soundEnabled вызывающего: false — чат
 * приглушён (№42), true — явное «вкл», undefined — поле отсутствует
 * (legacy-поверхность без 008a — трактуется как «вкл»).
 */
function chatRow(
  chatId: string,
  userId: string,
  username: string,
  soundEnabled?: boolean,
): ChatListItem {
  return {
    chatId,
    peer: peer(userId, username),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
    ...(soundEnabled !== undefined ? { soundEnabled } : {}),
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

async function renderPage(chats: ChatListItem[] = [chatRow(ALICE_CHAT_ID, ALICE, 'alice')]) {
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
  // Звуковые подписки страницы (гейт приёма, onChatSoundUpdated) живут
  // в ТОМ же коммите пользовательского стейта — вызов №20-фетча книги
  // доказывает, что вся пасса эффектов смонтирована (гонка с эммитом
  // исключена, паттерн MessengerPage.sound.test).
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

/** Кадр №18 `chat.sound.updated` собственного канала (другой девайс, SC-006). */
function emitSoundFrame(stream: MockStream, chatId: string, soundEnabled: boolean): void {
  act(() => {
    stream.emit('chat.sound.updated', JSON.stringify({ chatId, soundEnabled }))
  })
}

/** Открыть прямой чат alice (заголовок с колоколом готов — окно живо). */
async function openAlice(): Promise<void> {
  const list = screen.getByRole('list', { name: 'Список чатов' })
  fireEvent.click(within(list).getByText('alice').closest('button') as HTMLElement)
  await screen.findByRole('heading', { level: 2, name: 'alice' })
}

/** Bell-колокол заголовка по title прототипа #btnBell («Звуковые оповещения»). */
function bell(): HTMLElement {
  return screen.getByRole('button', { name: 'Звуковые оповещения' })
}

/** FR-025 «ровно один»: слот един, открыт и несёт текст выдачи. */
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
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  localStorage.clear()
})

describe('реальное время — per-chat состояние доходит до звука (T059, FR-013, ui-behavior §4.2)', () => {
  it('приглушённый №12-строкой чат: входящее передаётся звуку с false — тишина (решение — sound)', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice', false),
      chatRow(BOB_CHAT_ID, BOB, 'bob', true),
    ])

    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 1, ALICE))

    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledTimes(1)
    // Проводка ПЕРЕДАЁТ состояние (false = приглушён №42/№12) — звонок
    // подавляет сам ui/sound (T056), здесь синтез замокан.
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledWith(
      expect.objectContaining({ chatId: ALICE_CHAT_ID }),
      ME,
      false,
    )
  })

  it('обычный чат: явное true звучит; отсутствующее поле (legacy) — undefined, звучит', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice', true),
      chatRow(BOB_CHAT_ID, BOB, 'bob'),
    ])

    emitMessageCreated(stream, makeMessage(ALICE_CHAT_ID, 1, ALICE))
    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 1, BOB))

    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledTimes(2)
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ chatId: ALICE_CHAT_ID }),
      ME,
      true,
    )
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ chatId: BOB_CHAT_ID }),
      ME,
      undefined,
    )
  })

  it('кадр chat.sound.updated сходится в гейт: «выкл» → false, «вкл» → true (мультидевайс, SC-006)', async () => {
    const stream = await renderPage([chatRow(BOB_CHAT_ID, BOB, 'bob')])

    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 1, BOB))
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenLastCalledWith(
      expect.objectContaining({ chatId: BOB_CHAT_ID }),
      ME,
      undefined,
    )

    emitSoundFrame(stream, BOB_CHAT_ID, false)
    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 2, BOB))
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenLastCalledWith(
      expect.objectContaining({ chatId: BOB_CHAT_ID }),
      ME,
      false,
    )

    emitSoundFrame(stream, BOB_CHAT_ID, true)
    emitMessageCreated(stream, makeMessage(BOB_CHAT_ID, 3, BOB))
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenLastCalledWith(
      expect.objectContaining({ chatId: BOB_CHAT_ID }),
      ME,
      true,
    )
    expect(mockSound.chimeOnRealtimeIncoming).toHaveBeenCalledTimes(3)
  })
})

describe('sync-батч — входящие считаются per-chat (T059, FR-013/FR-014, ui-behavior §4.2)', () => {
  it('батч только с приглушёнными — итог 0: 0 звуков, но ВИЗУАЛЬНАЯ доставка не фильтруется', async () => {
    const initial = [chatRow(ALICE_CHAT_ID, ALICE, 'alice', false)]
    const stream = await renderPage(initial)
    mockChats.sync.mockResolvedValueOnce(
      syncResponse([
        delta({
          chatId: ALICE_CHAT_ID,
          messages: [makeMessage(ALICE_CHAT_ID, 1, ALICE), makeMessage(ALICE_CHAT_ID, 2, ALICE)],
        }),
      ]),
    )
    // (Ре)подключение также рефетчит №12 (useChatList onOpen, FR-009) —
    // серверная правда ПОСЛЕ доставки несёт то же превью/бейдж: оба
    // пути (дельта №26 и рефетч №12) сходятся в одинаковую строку.
    mockChats.listChats.mockResolvedValue([
      {
        ...initial[0],
        lastMessage: makeMessage(ALICE_CHAT_ID, 2, ALICE),
        unreadCount: 2,
      },
    ])

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledTimes(1)
    })
    // Приглушённый чат в итог цикла не попал — итог 0, тишина самого
    // sound (T061: «пустой цикл — сигнала нет»).
    expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledWith(0)
    // FR-014: превью и бейдж строки приглушённого чата всё равно
    // сошлись — гейт фильтрует ТОЛЬКО звук, не визуальную доставку.
    const list = screen.getByRole('list', { name: 'Список чатов' })
    expect(within(list).getByText('text-2')).toBeVisible()
    expect(within(list).getByText('2')).toBeVisible()
  })

  it('смешанный батч: приглушённые не считаются — итог только неприглушённые', async () => {
    const stream = await renderPage([
      chatRow(ALICE_CHAT_ID, ALICE, 'alice', false),
      chatRow(BOB_CHAT_ID, BOB, 'bob', true),
    ])
    mockChats.sync.mockResolvedValueOnce(
      syncResponse([
        delta({
          chatId: ALICE_CHAT_ID,
          messages: [makeMessage(ALICE_CHAT_ID, 1, ALICE), makeMessage(ALICE_CHAT_ID, 2, ALICE)],
        }),
        delta({ chatId: BOB_CHAT_ID, messages: [makeMessage(BOB_CHAT_ID, 1, BOB)] }),
      ]),
    )

    act(() => {
      stream.onOpen?.()
    })
    await waitFor(() => {
      expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledTimes(1)
    })
    // 2 входящих приглушённого alice + 1 входящий bob → звонок цикла
    // несёт только 1 (неприглушённый чат).
    expect(mockSound.chimeOnSyncBatch).toHaveBeenCalledWith(1)
  })
})

describe('bell-колокол страницы — №12-проекция, №42, тосты, мультидевайс (T059, T057/T058; FR-012, SC-006)', () => {
  it('№12-состояние ложится в колокол: приглушённый чат — aria-pressed=false + .off', async () => {
    await renderPage([chatRow(ALICE_CHAT_ID, ALICE, 'alice', false)])

    await openAlice()

    expect(bell()).toHaveAttribute('aria-pressed', 'false')
    expect(bell()).toHaveClass('off')
  })

  it('клик «выкл»: №42 с ЦЕЛЕВЫМ false, echo гасит, глухой щелчок и тост «отключены — alice»', async () => {
    await renderPage([chatRow(ALICE_CHAT_ID, ALICE, 'alice')])
    mockChats.setChatSound.mockResolvedValueOnce({ chatId: ALICE_CHAT_ID, soundEnabled: false })

    await openAlice()
    expect(bell()).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(bell())

    await waitFor(() => {
      expect(mockChats.setChatSound).toHaveBeenCalledTimes(1)
    })
    expect(mockChats.setChatSound).toHaveBeenCalledWith(ALICE_CHAT_ID, false)
    await expectSingleToast('Звуковые оповещения отключены — alice')
    expect(bell()).toHaveAttribute('aria-pressed', 'false')
    expect(bell()).toHaveClass('off')
    // Отклик «выкл» — «глухой щелчок» (§4.2), не «динь-динь».
    expect(mockSound.playMuteTone).toHaveBeenCalledTimes(1)
    expect(mockSound.playBellTone).not.toHaveBeenCalled()
  })

  it('клик «вкл» из приглушённого: playBellTone и тост «включены — alice», .off снят', async () => {
    await renderPage([chatRow(ALICE_CHAT_ID, ALICE, 'alice', false)])
    mockChats.setChatSound.mockResolvedValueOnce({ chatId: ALICE_CHAT_ID, soundEnabled: true })

    await openAlice()
    expect(bell()).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(bell())

    await waitFor(() => {
      expect(mockChats.setChatSound).toHaveBeenCalledTimes(1)
    })
    expect(mockChats.setChatSound).toHaveBeenCalledWith(ALICE_CHAT_ID, true)
    await expectSingleToast('Звуковые оповещения включены — alice')
    expect(bell()).toHaveAttribute('aria-pressed', 'true')
    expect(bell()).not.toHaveClass('off')
    expect(mockSound.playBellTone).toHaveBeenCalledTimes(1)
    expect(mockSound.playMuteTone).not.toHaveBeenCalled()
  })

  it('ошибка №42 (429): состояние НЕ меняется, откликов нет, тост problemMessage', async () => {
    await renderPage([chatRow(ALICE_CHAT_ID, ALICE, 'alice')])
    mockChats.setChatSound.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'flood_limit',
    })

    await openAlice()
    fireEvent.click(bell())

    await expectSingleToast('flood_limit')
    // Состояние колокола не тронуто — офлайн-очереди нет, №42
    // идемпотентен (повторный клик пошлёт то же значение).
    expect(bell()).toHaveAttribute('aria-pressed', 'true')
    expect(bell()).not.toHaveClass('off')
    expect(mockSound.playBellTone).not.toHaveBeenCalled()
    expect(mockSound.playMuteTone).not.toHaveBeenCalled()
  })

  it('кадр chat.sound.updated другого девайса flips колокол БЕЗ №42 и тоста (SC-006 ≤ 2 с)', async () => {
    const stream = await renderPage([chatRow(ALICE_CHAT_ID, ALICE, 'alice')])

    await openAlice()
    expect(bell()).toHaveAttribute('aria-pressed', 'true')

    emitSoundFrame(stream, ALICE_CHAT_ID, false)

    await waitFor(() => {
      expect(bell()).toHaveAttribute('aria-pressed', 'false')
    })
    expect(bell()).toHaveClass('off')
    // Кадр — ТОЛЬКО сходимость состояния: своё №42 страница не шлёт,
    // тост операции не выдаётся.
    expect(mockChats.setChatSound).not.toHaveBeenCalled()
    expectNoToast()
  })
})
