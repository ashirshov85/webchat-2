import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Identity, SsoProvider } from '../../../api/sso'
import { stubLocationAssign } from '../../../test/location'
import { SecurityPage } from '../SecurityPage'

const { mockListIdentities, mockListSsoProviders, mockLinkSsoAuthorize, mockUnlinkIdentity } =
  vi.hoisted(() => ({
    mockListIdentities: vi.fn(),
    mockListSsoProviders: vi.fn(),
    mockLinkSsoAuthorize: vi.fn(),
    mockUnlinkIdentity: vi.fn(),
  }))

vi.mock('../../../api/sso', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/sso')>()
  return {
    ...actual,
    listIdentities: mockListIdentities,
    listSsoProviders: mockListSsoProviders,
    linkSsoAuthorize: mockLinkSsoAuthorize,
    unlinkIdentity: mockUnlinkIdentity,
  }
})

const providers: SsoProvider[] = [
  { id: 'google', displayName: 'Google' },
  { id: 'github', displayName: 'GitHub' },
]

const googleIdentity: Identity = {
  id: '1f0b6a52-9d3c-4a2e-8f11-3c5d7a9b1e01',
  providerId: 'google',
  providerDisplayName: 'Google',
  email: 'user@example.com',
  linkedAt: '2026-09-01T12:00:00Z',
}

const githubIdentity: Identity = {
  id: '2a1c7b63-8e4d-5b3f-9f22-4d6e8b0c2f13',
  providerId: 'github',
  providerDisplayName: 'GitHub',
  email: null,
  linkedAt: '2026-09-02T12:00:00Z',
}

function primeInitialLoad(identities: Identity[]): void {
  mockListIdentities.mockResolvedValueOnce({ identities })
  mockListSsoProviders.mockResolvedValueOnce({ providers })
}

const initialUrl = window.location.href

let assignSpy: ReturnType<typeof stubLocationAssign> | undefined

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', initialUrl)
  sessionStorage.clear()
  assignSpy?.mockRestore()
  assignSpy = undefined
  vi.resetAllMocks()
})

describe('SecurityPage', () => {
  it('renders the linked identities list and only linkable providers', async () => {
    primeInitialLoad([googleIdentity, githubIdentity])
    render(<SecurityPage />)

    const section = await screen.findByLabelText('Linked providers')
    expect(section).toBeInTheDocument()
    expect(screen.getByText('Google')).toBeInTheDocument()
    expect(screen.getByText('user@example.com')).toBeInTheDocument()
    expect(screen.getByText('GitHub')).toBeInTheDocument()
    expect(
      screen.getByText(new Date(googleIdentity.linkedAt).toLocaleDateString()),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Link Google' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Link GitHub' })).not.toBeInTheDocument()
    expect(mockListIdentities).toHaveBeenCalledTimes(1)
    expect(mockListSsoProviders).toHaveBeenCalledTimes(1)
  })

  it('shows the empty state when no identities are linked', async () => {
    primeInitialLoad([])
    render(<SecurityPage />)

    expect(
      await screen.findByText('No providers are linked to your account yet.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Link Google' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Link GitHub' })).toBeInTheDocument()
  })

  it('navigates to the authorization URL when linking a provider', async () => {
    primeInitialLoad([googleIdentity])
    mockLinkSsoAuthorize.mockResolvedValueOnce({
      authorizationUrl: 'https://idp.example/oauth/authorize?state=link-abc',
    })
    assignSpy = stubLocationAssign()
    render(<SecurityPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Link GitHub' }))

    await waitFor(() =>
      expect(assignSpy).toHaveBeenCalledWith('https://idp.example/oauth/authorize?state=link-abc'),
    )
    expect(mockLinkSsoAuthorize).toHaveBeenCalledTimes(1)
    expect(mockLinkSsoAuthorize.mock.calls[0]?.[0]).toEqual({ providerId: 'github' })
  })

  it('shows an error instead of navigating when link authorize fails', async () => {
    primeInitialLoad([])
    mockLinkSsoAuthorize.mockRejectedValueOnce({
      title: 'Not Found',
      status: 404,
      detail: 'Provider not found',
    })
    assignSpy = stubLocationAssign()
    render(<SecurityPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Link Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Provider not found')
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('unlinks the identity and refreshes the list', async () => {
    primeInitialLoad([googleIdentity, githubIdentity])
    mockUnlinkIdentity.mockResolvedValueOnce(undefined)
    mockListIdentities.mockResolvedValueOnce({ identities: [githubIdentity] })
    render(<SecurityPage />)

    const unlinkButtons = await screen.findAllByRole('button', { name: 'Unlink' })
    expect(unlinkButtons).toHaveLength(2)
    fireEvent.click(unlinkButtons[0]!)

    await waitFor(() => expect(mockUnlinkIdentity).toHaveBeenCalledTimes(1))
    expect(mockUnlinkIdentity.mock.calls[0]?.[0]).toBe(googleIdentity.id)
    await waitFor(() => expect(mockListIdentities).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('user@example.com')).not.toBeInTheDocument())
    expect(screen.getByText('GitHub')).toBeInTheDocument()
  })

  it('shows the last sign-in method advice with password reset link on 409 last_login_method', async () => {
    primeInitialLoad([googleIdentity])
    mockUnlinkIdentity.mockRejectedValueOnce({
      title: 'Conflict',
      status: 409,
      errors: { identity: ['last_login_method'] },
    })
    render(<SecurityPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Unlink' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This is your last sign-in method, so it cannot be unlinked.')
    expect(screen.getByRole('link', { name: 'password reset email' })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
    expect(screen.getByText('Google')).toBeInTheDocument()
    expect(mockListIdentities).toHaveBeenCalledTimes(1)
  })

  it('shows a plain message for other unlink failures', async () => {
    primeInitialLoad([googleIdentity])
    mockUnlinkIdentity.mockRejectedValueOnce({
      title: 'Internal Server Error',
      status: 500,
      detail: 'Something broke',
    })
    render(<SecurityPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Unlink' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something broke')
  })

  it('shows an error state when the initial load fails', async () => {
    mockListIdentities.mockRejectedValueOnce({ title: 'Unauthorized', status: 401 })
    mockListSsoProviders.mockResolvedValueOnce({ providers })
    render(<SecurityPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not load your sign-in methods. Please refresh the page.')
  })

  it('shows the success banner for ?linked=<providerId> with the provider display name', async () => {
    window.history.pushState({}, '', '/settings/security?linked=github')
    primeInitialLoad([githubIdentity])
    render(<SecurityPage />)

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('Provider GitHub was linked to your account.')
  })

  it('shows the provider error banner for ?sso_error=<code>', async () => {
    window.history.pushState({}, '', '/settings/security?sso_error=identity_taken')
    primeInitialLoad([])
    render(<SecurityPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This identity is already linked to another account.')
  })
})
