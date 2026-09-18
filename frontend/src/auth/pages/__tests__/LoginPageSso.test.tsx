import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RETURN_TO_STORAGE_KEY } from '../../../api/sso'
import type { SsoProvider } from '../../../api/sso'
import { stubLocationAssign } from '../../../test/location'
import { LoginPage } from '../LoginPage'

const { mockListSsoProviders, mockAuthorizeSso } = vi.hoisted(() => ({
  mockListSsoProviders: vi.fn(),
  mockAuthorizeSso: vi.fn(),
}))

vi.mock('../../../api/sso', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/sso')>()
  return {
    ...actual,
    listSsoProviders: mockListSsoProviders,
    authorizeSso: mockAuthorizeSso,
  }
})

const enabledProviders: SsoProvider[] = [
  { id: 'google', displayName: 'Google' },
  { id: 'github', displayName: 'GitHub' },
]

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

describe('LoginPage SSO', () => {
  it('renders enabled provider buttons from GET /auth/sso/providers', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    render(<LoginPage />)

    const section = await screen.findByLabelText('Single sign-on providers')
    expect(section).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Google' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'GitHub' })).toBeInTheDocument()
    expect(mockListSsoProviders).toHaveBeenCalledTimes(1)
  })

  it('hides the SSO section when the provider list is empty', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: [] })
    render(<LoginPage />)

    await waitFor(() => expect(mockListSsoProviders).toHaveBeenCalledTimes(1))
    await act(async () => {})

    expect(screen.queryByLabelText('Single sign-on providers')).not.toBeInTheDocument()
    expect(screen.queryByText('Or continue with')).not.toBeInTheDocument()
  })

  it('navigates to the authorization URL on provider click', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockResolvedValueOnce({
      authorizationUrl: 'https://idp.example/oauth/authorize?state=abc',
    })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'GitHub' }))

    await waitFor(() =>
      expect(assignSpy).toHaveBeenCalledWith('https://idp.example/oauth/authorize?state=abc'),
    )
    expect(mockAuthorizeSso).toHaveBeenCalledTimes(1)
    expect(mockAuthorizeSso.mock.calls[0]?.[0]).toEqual({ providerId: 'github' })
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBeNull()
  })

  it('saves returnTo to sessionStorage before navigating to the provider', async () => {
    window.history.pushState({}, '', '/login?returnTo=%2Fsettings%2Fprofile')
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockResolvedValueOnce({
      authorizationUrl: 'https://idp.example/oauth/authorize?state=xyz',
    })
    assignSpy = stubLocationAssign()
    const storedAtNavigation: Array<string | null> = []
    assignSpy.mockImplementation(() => {
      storedAtNavigation.push(sessionStorage.getItem(RETURN_TO_STORAGE_KEY))
    })
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Google' }))

    await waitFor(() => expect(assignSpy).toHaveBeenCalledTimes(1))
    expect(mockAuthorizeSso).toHaveBeenCalledWith({
      providerId: 'google',
      returnTo: '/settings/profile',
    })
    expect(storedAtNavigation).toEqual(['/settings/profile'])
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBe('/settings/profile')
  })

  it('shows an error instead of navigating when authorize fails', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockRejectedValueOnce({
      title: 'Not Found',
      status: 404,
      detail: 'Provider not found',
    })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Provider not found')
    expect(assignSpy).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBeNull()
  })
})
