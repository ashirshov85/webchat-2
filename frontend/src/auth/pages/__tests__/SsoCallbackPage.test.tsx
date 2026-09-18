import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RETURN_TO_STORAGE_KEY } from '../../../api/sso'
import { stubLocationAssign } from '../../../test/location'
import { SsoCallbackPage } from '../SsoCallbackPage'

const { mockExchangeSsoToken, mockSetTokenPair } = vi.hoisted(() => ({
  mockExchangeSsoToken: vi.fn(),
  mockSetTokenPair: vi.fn(),
}))

vi.mock('../../../api/sso', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/sso')>()
  return { ...actual, exchangeSsoToken: mockExchangeSsoToken }
})

vi.mock('../../session', () => ({
  setTokenPair: mockSetTokenPair,
}))

function tokenPair() {
  return {
    accessToken: 'access-9',
    refreshToken: 'refresh-9',
    tokenType: 'Bearer',
    expiresInSec: 300,
    user: {
      id: '0c2f9e6a-8f68-4d3f-9c4e-5a1b2d3f4e5a',
      username: 'sso-user',
      email: 'sso@example.com',
    },
  }
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

describe('SsoCallbackPage', () => {
  it('exchanges the handshake code and redirects to /chat', async () => {
    window.history.pushState({}, '', '/sso/callback?code=handshake-123')
    const pair = tokenPair()
    mockExchangeSsoToken.mockResolvedValueOnce(pair)
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    await waitFor(() => expect(assignSpy).toHaveBeenCalledWith('/chat'))
    expect(mockExchangeSsoToken).toHaveBeenCalledTimes(1)
    expect(mockExchangeSsoToken.mock.calls[0]?.[0]).toEqual({ code: 'handshake-123' })
    expect(mockSetTokenPair).toHaveBeenCalledTimes(1)
    expect(mockSetTokenPair).toHaveBeenCalledWith(pair)
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBeNull()
  })

  it('applies returnTo from sessionStorage after the exchange and clears it', async () => {
    sessionStorage.setItem(RETURN_TO_STORAGE_KEY, '/settings/security')
    window.history.pushState({}, '', '/sso/callback?code=handshake-456')
    mockExchangeSsoToken.mockResolvedValueOnce(tokenPair())
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    await waitFor(() => expect(assignSpy).toHaveBeenCalledWith('/settings/security'))
    expect(mockSetTokenPair).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBeNull()
  })

  it('falls back to /chat when stored returnTo is not a safe relative path', async () => {
    sessionStorage.setItem(RETURN_TO_STORAGE_KEY, '//evil.example')
    window.history.pushState({}, '', '/sso/callback?code=handshake-789')
    mockExchangeSsoToken.mockResolvedValueOnce(tokenPair())
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    await waitFor(() => expect(assignSpy).toHaveBeenCalledWith('/chat'))
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBeNull()
  })

  it('shows the provider error screen without exchanging the code', async () => {
    window.history.pushState({}, '', '/sso/callback?sso_error=invalid_state')
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The sign-in session is invalid or has expired.')
    expect(screen.getByRole('link', { name: 'login page' })).toHaveAttribute('href', '/login')
    expect(mockExchangeSsoToken).not.toHaveBeenCalled()
    expect(assignSpy).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows first-login advice for email_not_verified: sign in with password and link the provider', async () => {
    window.history.pushState({}, '', '/sso/callback?sso_error=email_not_verified')
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The provider did not confirm your email address.')
    expect(screen.getByRole('link', { name: 'Sign in with your password' })).toHaveAttribute(
      'href',
      '/login',
    )
    expect(screen.getByRole('link', { name: 'create an account' })).toHaveAttribute(
      'href',
      '/register',
    )
    expect(screen.getByText(/link this provider in your account settings/i)).toBeInTheDocument()
    expect(mockExchangeSsoToken).not.toHaveBeenCalled()
    expect(assignSpy).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows first-login advice for email_conflict: sign in with password and link the provider', async () => {
    window.history.pushState({}, '', '/sso/callback?sso_error=email_conflict')
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('An account with this email already exists.')
    expect(screen.getByRole('link', { name: 'Sign in with your password' })).toHaveAttribute(
      'href',
      '/login',
    )
    expect(screen.getByRole('link', { name: 'create an account' })).toHaveAttribute(
      'href',
      '/register',
    )
    expect(screen.getByText(/link this provider in your account settings/i)).toBeInTheDocument()
    expect(mockExchangeSsoToken).not.toHaveBeenCalled()
    expect(assignSpy).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows registration advice for registration_incomplete: complete registration via the email link', async () => {
    window.history.pushState({}, '', '/sso/callback?sso_error=registration_incomplete')
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Your registration is not finished yet.')
    expect(screen.getByText(/follow the link from the confirmation email/i)).toBeInTheDocument()
    expect(screen.queryByRole('link')).toBeNull()
    expect(mockExchangeSsoToken).not.toHaveBeenCalled()
    expect(assignSpy).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows the error message when the token exchange fails', async () => {
    window.history.pushState({}, '', '/sso/callback?code=expired-code')
    mockExchangeSsoToken.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      detail: 'invalid_code',
    })
    assignSpy = stubLocationAssign()
    render(<SsoCallbackPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('invalid_code')
    expect(assignSpy).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })
})
