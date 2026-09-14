import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components, operations } from '../../../api/schema'
import { LoginPage } from '../LoginPage'

type Problem = components['schemas']['Problem']

type LoginResponse = operations['login']['responses'][200]['content']['application/json']

const { mockLogin, mockSetTokenPair } = vi.hoisted(() => ({
  mockLogin: vi.fn(),
  mockSetTokenPair: vi.fn(),
}))

vi.mock('../../../api/auth', () => ({
  login: mockLogin,
}))

vi.mock('../../session', () => ({
  setTokenPair: mockSetTokenPair,
}))

function loginResponse(): LoginResponse {
  return {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    tokenType: 'Bearer',
    expiresInSec: 300,
    user: {
      id: '5f0d2b1a-3c6e-4a8b-9d2f-7e4c1b0a6d83',
      username: 'alice',
      email: 'alice@example.com',
    },
  }
}

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function fillLoginForm(identifier: string, password: string) {
  fireEvent.change(screen.getByLabelText('Identifier'), { target: { value: identifier } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Login' }))
}

describe('LoginPage', () => {
  it('renders the login form', () => {
    render(<LoginPage />)

    expect(screen.getByLabelText('Identifier')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Login' })).toBeInTheDocument()
  })

  it('submits credentials and stores the token pair on success', async () => {
    mockLogin.mockResolvedValueOnce(loginResponse())
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('alice')
    expect(mockLogin).toHaveBeenCalledTimes(1)
    expect(mockLogin).toHaveBeenCalledWith({ identifier: 'alice', password: 'correct horse' })
    expect(mockSetTokenPair).toHaveBeenCalledTimes(1)
    expect(mockSetTokenPair).toHaveBeenCalledWith(
      expect.objectContaining({ accessToken: 'access-1', refreshToken: 'refresh-1' }),
    )
  })

  it('shows the unified Invalid credentials error on 401', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Unauthorized',
      status: 401,
      detail: 'Invalid credentials',
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'wrong password')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Invalid credentials')
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows the finish-registration detail on 403', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Forbidden',
      status: 403,
      detail: 'Finish registration',
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Finish registration')
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows the rate-limit error with the retry hint on 429', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many attempts',
      retryAfterSec: 42,
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Too many attempts')
    expect(alert).toHaveTextContent('42')
  })
})
