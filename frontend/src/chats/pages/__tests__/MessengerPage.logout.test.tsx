import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import type { ChatListItem } from '../../../api/chats'
import { MessengerPage } from '../MessengerPage'
import { stubLocationAssign } from '../../../test/location'

/**
 * T045 (follow-up Phase 10) — обработчик «Выйти» страницы: пункт
 * главного меню сайдбара (MainMenuButton → ChatListPanel → страница)
 * ревокает ТОЛЬКО текущую сессию (002 FR-011): `logout({ refreshToken
 * })` с refresh-токеном из localStorage, затем — независимо от исхода
 * revoke (best-effort: сеть/401 не блокируют локальный выход, серверная
 * сессия истечёт по TTL) — `clearTokens()` (ключ webchat.auth.refreshToken
 * удалён) и `window.location.assign('/login')` БЕЗ returnTo (auth-маршруты
 * исключены из returnTo — FR-002 008b).
 */

const { mockGetCurrentUser, mockLogout, mockChats, mockCreateGroup, mockSse, mockPresence } =
  vi.hoisted(() => ({
    mockGetCurrentUser: vi.fn(),
    mockLogout: vi.fn(),
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
  }))

vi.mock('../../../api/auth', () => ({
  getCurrentUser: mockGetCurrentUser,
  logout: mockLogout,
}))

vi.mock('../../../api/chats', () => mockChats)

vi.mock('../../../api/groups', () => ({
  createGroup: mockCreateGroup,
}))

vi.mock('../../../api/sse', () => mockSse)

vi.mock('../../../presence/presenceApi', () => mockPresence)

const REFRESH_TOKEN_STORAGE_KEY = 'webchat.auth.refreshToken'
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

async function renderPage(): Promise<void> {
  mockGetCurrentUser.mockResolvedValue(peer(ME, 'me'))
  mockChats.listChats.mockResolvedValue([aliceRow()])
  mockChats.listMessages.mockResolvedValue({ messages: [] })
  mockChats.listContacts.mockResolvedValue([])
  mockPresence.fetchPresenceSettings.mockResolvedValue({ incognito: false })
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe: () => () => {},
    close: () => {},
  }))
  render(<MessengerPage />)
  await screen.findByRole('list', { name: 'Список чатов' })
}

/** Главное меню сайдбара → пункт «Выйти» (последний, T045). */
async function clickLogout(): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: 'Меню' }))
  fireEvent.click(await screen.findByRole('menuitem', { name: 'Выйти' }))
}

let assignMock: MockInstance

beforeEach(() => {
  assignMock = stubLocationAssign()
  window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, 'refresh-token-1')
})

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  assignMock.mockRestore()
  vi.resetAllMocks()
})

describe('MessengerPage logout handler (T045, 002 FR-011)', () => {
  it('«Выйти» revokes the current session: logout with the stored refreshToken, tokens cleared, redirect to /login', async () => {
    await renderPage()
    await clickLogout()

    await waitFor(() => {
      expect(mockLogout).toHaveBeenCalledTimes(1)
    })
    expect(mockLogout).toHaveBeenCalledWith({ refreshToken: 'refresh-token-1' })
    await waitFor(() => {
      expect(window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull()
    })
    expect(assignMock).toHaveBeenCalledWith('/login')
  })

  it('a logout network failure does not block the local sign-out (best-effort revoke)', async () => {
    // Promise.reject в момент вызова (а не mockRejectedValue): отклонение
    // сразу уходит обработчику — resetAllMocks не роняет «осиротевший»
    // заранее отклонённый промис.
    mockLogout.mockImplementation(() => Promise.reject(new Error('network down')))
    await renderPage()
    await clickLogout()

    expect(mockLogout).toHaveBeenCalledTimes(1)
    await waitFor(() => {
      expect(window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull()
    })
    expect(assignMock).toHaveBeenCalledWith('/login')
  })
})
