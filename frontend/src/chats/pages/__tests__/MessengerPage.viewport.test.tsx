import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatListItem, ContactView } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'

/**
 * T066 — тесты адаптации к visual viewport (US5; FR-029, research §H,
 * design-tokens §9 «Клавиатура», ui-behavior §7): экранная клавиатура
 * сжимает visual viewport, но НЕ layout-viewport (iOS Safari) —
 * `100dvh` её не видит, дно корпуса-машины ушло бы под клавиатуру.
 * Механизм (решение research §H): слушатель `resize`/`scroll` в
 * MessengerPage пишет на body пару CSS-переменных — `--vvh` (высота
 * visual viewport) и `--vvo` (offsetTop — панорамирование/сдвиг), а
 * machine.css считает высоту машины по ним: дно корпуса сходится к
 * видимому дну — композер над клавиатурой, лента сжимается, прокрутки
 * страницы нет. Геометрию (высоты/overflow) jsdom не считает — эти
 * тесты пинят JS-половину контракта (переменные на body в px, живой
 * отклик на resize/scroll, снятие слушателей и переменных при
 * размонтировании, тихий пропуск без visualViewport); CSS-половину
 * (`height: calc(var(--vvh) + var(--vvo) - …)` и
 * `html,body{overflow:hidden}` ≥901px) держат мобильные снимки T068 и
 * ручной quickstart E1–E2 (SC-005). jsdom не реализует visualViewport
 * (проверено: `window.visualViewport === undefined`) — стаб ставится
 * own-property с configurable:true и снимается удалением, паттерн
 * matchMedia-стаба MessengerPage.drawer.test.tsx (T062).
 */

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
      createPresenceHeartbeat: vi.fn(() => ({ updateConnectionId: vi.fn(), stop: vi.fn() })),
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

function peer(id: string, username: string) {
  return {
    id,
    username,
    email: `${username}@example.com`,
    status: 'active' as const,
    createdAt: '2026-09-01T00:00:00.000Z',
  }
}

function aliceRow(): ChatListItem {
  return {
    chatId: 'chat-direct-1',
    peer: peer(ALICE, 'alice'),
    lastMessage: null,
    unreadCount: 0,
    blockedByMe: false,
  }
}

function contacts(): ContactView[] {
  return [{ user: peer(ALICE, 'alice'), createdAt: '2026-09-02T00:00:00.000Z', blockedByMe: false }]
}

/**
 * Стаб visualViewport: jsdom его не реализует — ставится own-property
 * окна (configurable — снимается удалением в afterEach). Книжное
 * ведение слушателей: emit дёргает только живые подписки, removed —
 * по removeEventListener (проверка снятия при размонтировании).
 */
function stubVisualViewport(height: number, offsetTop: number) {
  const listeners = new Map<string, Set<() => void>>()
  const addEventListener = vi.fn((type: string, listener: () => void) => {
    const set = listeners.get(type) ?? new Set()
    set.add(listener)
    listeners.set(type, set)
  })
  const removeEventListener = vi.fn((type: string, listener: () => void) => {
    listeners.get(type)?.delete(listener)
  })
  const stub = {
    height,
    offsetTop,
    addEventListener,
    removeEventListener,
    /** Сдвиг геометрии + событие (как реальный visualViewport). */
    moveTo(nextHeight: number, nextOffsetTop: number, type: 'resize' | 'scroll') {
      stub.height = nextHeight
      stub.offsetTop = nextOffsetTop
      for (const listener of listeners.get(type) ?? []) {
        listener()
      }
    },
  }
  Object.defineProperty(window, 'visualViewport', {
    value: stub,
    configurable: true,
  })
  return stub
}

/** Ответы API харнесса (паттерн renderPage MessengerPage.drawer.test.tsx). */
function stubApi(): void {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue([aliceRow()])
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listContacts.mockResolvedValue(contacts())
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe: () => () => {},
    close: () => {},
  }))
}

async function renderPage(): Promise<void> {
  stubApi()
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
}

beforeEach(() => {
  // Слушатель пишет inline-переменные на body — чистый лист теста.
  document.body.style.removeProperty('--vvh')
  document.body.style.removeProperty('--vvo')
})

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  Reflect.deleteProperty(window, 'visualViewport')
  document.body.style.removeProperty('--vvh')
  document.body.style.removeProperty('--vvo')
})

describe('MessengerPage visual viewport (T066, FR-029, research §H)', () => {
  it('монтирование пишет --vvh/--vvo на body (высота и offsetTop в px)', async () => {
    stubVisualViewport(612.5, 40)
    await renderPage()

    expect(document.body.style.getPropertyValue('--vvh')).toBe('612.5px')
    expect(document.body.style.getPropertyValue('--vvo')).toBe('40px')
  })

  it('resize (клавиатура сжала viewport) обновляет --vvh — машина сжимается', async () => {
    const viewport = stubVisualViewport(728, 0)
    await renderPage()
    expect(document.body.style.getPropertyValue('--vvh')).toBe('728px')

    viewport.moveTo(380, 0, 'resize')

    expect(document.body.style.getPropertyValue('--vvh')).toBe('380px')
    expect(document.body.style.getPropertyValue('--vvo')).toBe('0px')
  })

  it('scroll (панорамирование visual viewport) обновляет --vvo', async () => {
    const viewport = stubVisualViewport(500, 0)
    await renderPage()

    viewport.moveTo(500, 96, 'scroll')

    expect(document.body.style.getPropertyValue('--vvo')).toBe('96px')
  })

  it('размонтирование снимает слушатели и убирает переменные с body', async () => {
    stubApi()
    const viewport = stubVisualViewport(644, 0)
    const { unmount } = render(<MessengerPage />)
    await screen.findByRole('list', { name: 'Список чатов' })
    expect(document.body.style.getPropertyValue('--vvh')).toBe('644px')

    unmount()

    expect(document.body.style.getPropertyValue('--vvh')).toBe('')
    expect(document.body.style.getPropertyValue('--vvo')).toBe('')
    expect(viewport.removeEventListener).toHaveBeenCalledWith('resize', expect.any(Function))
    expect(viewport.removeEventListener).toHaveBeenCalledWith('scroll', expect.any(Function))

    // Сняты — события больше не пишут на body (unmount StrictMode чист).
    viewport.moveTo(300, 12, 'resize')
    viewport.moveTo(300, 12, 'scroll')
    expect(document.body.style.getPropertyValue('--vvh')).toBe('')
    expect(document.body.style.getPropertyValue('--vvo')).toBe('')
  })

  it('без visualViewport страница жива, переменные не пишутся (фолбэк 100dvh)', async () => {
    // jsdom-дефолт: window.visualViewport === undefined — hook тихо
    // бездействует, machine.css живёт на фолбэке var(--vvh, 100dvh).
    expect(window.visualViewport).toBeUndefined()
    await renderPage()

    expect(document.body.style.getPropertyValue('--vvh')).toBe('')
    expect(document.body.style.getPropertyValue('--vvo')).toBe('')
  })
})
