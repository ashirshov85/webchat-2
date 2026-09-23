import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ensureChat, listContacts, removeContact } from '../../../api/chats'
import type { ChatView, ContactView } from '../../../api/chats'
import { ContactList } from '../ContactList'

/**
 * «Контакты» list (US5, T059/T061; FR-015/016/017): the alphabetical
 * ordering is SERVER-owned — the login/email toggle only switches the
 * №20 `sort` parameter and refetches, the client never re-sorts. A row
 * click opens the pair dialog through №11 `ensureChat`, and «×» removes
 * ONLY the address book entry (№22) — the row disappears here while
 * the pair chat and its history stay untouched (FR-017).
 */

vi.mock('../../../api/chats', () => ({
  listContacts: vi.fn(),
  ensureChat: vi.fn(),
  removeContact: vi.fn(),
}))

const mockedListContacts = vi.mocked(listContacts)
const mockedEnsureChat = vi.mocked(ensureChat)
const mockedRemoveContact = vi.mocked(removeContact)

const ALICE = '22222222-2222-2222-2222-222222222222'
const BOB = '33333333-3333-3333-3333-333333333333'

function contactView(id: string, username: string, email: string): ContactView {
  return {
    user: {
      id,
      username,
      email,
      status: 'active',
      createdAt: '2026-09-01T00:00:00.000Z',
    },
    createdAt: '2026-09-10T00:00:00.000Z',
  }
}

function chatViewFor(peerId: string, username: string): ChatView {
  return {
    chatId: `chat-${username}`,
    peer: {
      id: peerId,
      username,
      email: `${username}@example.com`,
      status: 'active',
      createdAt: '2026-09-01T00:00:00.000Z',
    },
    blockedByMe: false,
    peerReadUpToSeq: 0,
    myReadUpToSeq: 0,
  }
}

const BY_LOGIN = [
  contactView(BOB, 'bob', 'bob@example.com'),
  contactView(ALICE, 'alice', 'alice@example.com'),
]
const BY_EMAIL = [
  contactView(ALICE, 'alice', 'alice@example.com'),
  contactView(BOB, 'bob', 'bob@example.com'),
]

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(cleanup)

describe('ContactList sorting (FR-015)', () => {
  it('fetches №20 with the default sort=login and renders the server order', async () => {
    mockedListContacts.mockResolvedValue(BY_LOGIN)

    render(<ContactList />)

    expect(await screen.findByText('bob')).toBeVisible()
    expect(mockedListContacts).toHaveBeenCalledWith('login')
    await waitFor(() => {
      const names = Array.from(document.querySelectorAll('.contact-row-main .chat-item-title')).map(
        (node) => node.textContent,
      )
      expect(names).toEqual(['bob', 'alice'])
    })
  })

  it('switching to «По email» refetches with sort=email — the client never re-sorts locally', async () => {
    mockedListContacts.mockResolvedValueOnce(BY_LOGIN).mockResolvedValueOnce(BY_EMAIL)
    render(<ContactList />)
    await screen.findByText('bob')
    expect(mockedListContacts).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'По email' }))

    await waitFor(() => {
      expect(mockedListContacts).toHaveBeenCalledWith('email')
    })
    await waitFor(() => {
      const names = Array.from(document.querySelectorAll('.contact-row-main .chat-item-title')).map(
        (node) => node.textContent,
      )
      expect(names).toEqual(['alice', 'bob'])
    })
    expect(mockedListContacts).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('button', { name: 'По email' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('refetches when refreshKey bumps (a contact was added elsewhere in the panel)', async () => {
    mockedListContacts.mockResolvedValue(BY_LOGIN)
    const { rerender } = render(<ContactList refreshKey={0} />)
    await screen.findByText('bob')

    rerender(<ContactList refreshKey={1} />)

    await waitFor(() => {
      expect(mockedListContacts).toHaveBeenCalledTimes(2)
    })
  })
})

describe('ContactList row actions', () => {
  it('opens the pair dialog through №11 ensureChat on a row click', async () => {
    mockedListContacts.mockResolvedValue(BY_LOGIN)
    const onOpenChat = vi.fn()
    mockedEnsureChat.mockResolvedValue(chatViewFor(ALICE, 'alice'))
    render(<ContactList onOpenChat={onOpenChat} />)

    fireEvent.click(await screen.findByText('alice'))

    await waitFor(() => {
      expect(mockedEnsureChat).toHaveBeenCalledWith({ peerUserId: ALICE })
    })
    await waitFor(() => {
      expect(onOpenChat).toHaveBeenCalledWith(chatViewFor(ALICE, 'alice'))
    })
  })

  it('renders a row-action failure in a banner without unmounting the list', async () => {
    mockedListContacts.mockResolvedValue(BY_LOGIN)
    mockedEnsureChat.mockRejectedValue({ status: 404, title: 'User not found' })
    render(<ContactList />)

    fireEvent.click(await screen.findByText('alice'))

    expect(await screen.findByText('User not found')).toBeVisible()
    expect(screen.getByText('bob')).toBeVisible()
  })

  it('removes only the address book entry on «×»: the row disappears, no dialog is opened (FR-017)', async () => {
    mockedListContacts.mockResolvedValue(BY_LOGIN)
    const onOpenChat = vi.fn()
    mockedRemoveContact.mockResolvedValue(undefined)
    render(<ContactList onOpenChat={onOpenChat} />)
    await screen.findByText('alice')

    fireEvent.click(screen.getByRole('button', { name: 'Удалить alice из контактов' }))

    await waitFor(() => {
      expect(mockedRemoveContact).toHaveBeenCalledWith(ALICE)
    })
    await waitFor(() => {
      expect(screen.queryByText('alice')).toBeNull()
    })
    expect(screen.getByText('bob')).toBeVisible()
    expect(mockedEnsureChat).not.toHaveBeenCalled()
    expect(onOpenChat).not.toHaveBeenCalled()
  })

  it('renders the remove failure in a banner and keeps the row', async () => {
    mockedListContacts.mockResolvedValue(BY_LOGIN)
    mockedRemoveContact.mockRejectedValue({ status: 500, title: 'Internal Server Error' })
    render(<ContactList />)
    await screen.findByText('alice')

    fireEvent.click(screen.getByRole('button', { name: 'Удалить alice из контактов' }))

    expect(await screen.findByText('Internal Server Error')).toBeVisible()
    expect(screen.getByText('alice')).toBeVisible()
  })
})

describe('ContactList states', () => {
  it('renders the calm empty state for an empty address book', async () => {
    mockedListContacts.mockResolvedValue([])

    render(<ContactList />)

    expect(
      await screen.findByText('Контактов пока нет — найдите пользователя через поиск'),
    ).toBeVisible()
  })

  it('renders the №20 failure with a retry that refetches', async () => {
    mockedListContacts
      .mockRejectedValueOnce({ status: 500, title: 'Internal Server Error' })
      .mockResolvedValueOnce(BY_LOGIN)
    render(<ContactList />)

    expect(await screen.findByText('Internal Server Error')).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))

    await waitFor(() => {
      expect(mockedListContacts).toHaveBeenCalledTimes(2)
    })
    expect(await screen.findByText('bob')).toBeVisible()
  })
})
