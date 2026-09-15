import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ApiProblem } from '../../../api/auth'
import { ConfirmRegistrationPage } from '../ConfirmRegistrationPage'
import { RegisterPage } from '../RegisterPage'
import { SetPasswordPage } from '../SetPasswordPage'

type Problem = components['schemas']['Problem']

const { mockRegister, mockConfirmRegistration, mockSetPassword } = vi.hoisted(() => ({
  mockRegister: vi.fn(),
  mockConfirmRegistration: vi.fn(),
  mockSetPassword: vi.fn(),
}))

vi.mock('../../../api/auth', () => ({
  register: mockRegister,
  resendRegistrationEmail: vi.fn(),
  confirmRegistration: mockConfirmRegistration,
  setPassword: mockSetPassword,
}))

const initialUrl = window.location.href

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', initialUrl)
  vi.resetAllMocks()
})

function fillRegistrationForm(username: string, email: string) {
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: username } })
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } })
  fireEvent.click(screen.getByRole('button', { name: 'Register' }))
}

describe('RegisterPage', () => {
  it('renders the registration form', () => {
    render(<RegisterPage />)

    expect(screen.getByLabelText('Username')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Register' })).toBeInTheDocument()
  })

  it('submits username and email and shows the check-your-email notice on 202', async () => {
    mockRegister.mockResolvedValueOnce(undefined)
    render(<RegisterPage />)

    fillRegistrationForm('alice', 'alice@example.com')

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('alice@example.com')
    expect(mockRegister).toHaveBeenCalledTimes(1)
    expect(mockRegister).toHaveBeenCalledWith({ username: 'alice', email: 'alice@example.com' })
  })

  it('shows field validation codes from a 400 problem', async () => {
    mockRegister.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { email: ['invalid_format'] },
    } satisfies Problem)
    render(<RegisterPage />)

    fillRegistrationForm('alice', 'not-an-email')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('invalid_format')
    expect(mockRegister).toHaveBeenCalledTimes(1)
  })

  it('shows which field is occupied on a 409 conflict', async () => {
    mockRegister.mockRejectedValueOnce({
      title: 'Conflict',
      status: 409,
      errors: { username: ['already_taken'] },
    } satisfies Problem)
    render(<RegisterPage />)

    fillRegistrationForm('alice', 'alice@example.com')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/username/i)
    expect(alert).toHaveTextContent('already_taken')
  })

  it('shows the rate-limit detail with the retry hint on 429', async () => {
    mockRegister.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Rate limit exceeded',
      retryAfterSec: 42,
    } satisfies ApiProblem)
    render(<RegisterPage />)

    fillRegistrationForm('alice', 'alice@example.com')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Rate limit exceeded')
    expect(alert).toHaveTextContent('Try again in 42 s')
  })
})

describe('ConfirmRegistrationPage', () => {
  it('confirms the token from the email link and reveals the set-password form', async () => {
    window.history.pushState({}, '', '/confirm-registration?token=ev-token')
    mockConfirmRegistration.mockResolvedValueOnce({
      setupToken: 'st-123',
      setupTokenType: 'password_setup',
      expiresInSec: 3600,
    })
    render(<ConfirmRegistrationPage />)

    await waitFor(() => {
      expect(mockConfirmRegistration).toHaveBeenCalledTimes(1)
      expect(mockConfirmRegistration).toHaveBeenCalledWith({ token: 'ev-token' })
    })

    expect(await screen.findByLabelText('Password')).toBeInTheDocument()
    expect(screen.getByLabelText('Confirm password')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set password' })).toBeInTheDocument()
  })

  it('shows the invalid-or-expired-link detail on 400', async () => {
    window.history.pushState({}, '', '/confirm-registration?token=used-token')
    mockConfirmRegistration.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      detail: 'Token invalid or expired',
    } satisfies Problem)
    render(<ConfirmRegistrationPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Token invalid or expired')
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument()
  })

  it('shows the rate-limit detail with the retry hint on 429', async () => {
    window.history.pushState({}, '', '/confirm-registration?token=ev-token')
    mockConfirmRegistration.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Rate limit exceeded',
      retryAfterSec: 42,
    } satisfies ApiProblem)
    render(<ConfirmRegistrationPage />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Rate limit exceeded')
    expect(alert).toHaveTextContent('Try again in 42 s')
  })
})

describe('SetPasswordPage', () => {
  it('uses the setup token from the /set-password link and completes registration on 204', async () => {
    window.history.pushState({}, '', '/set-password?token=link-token')
    mockSetPassword.mockResolvedValueOnce(undefined)
    render(<SetPasswordPage />)

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'correct horse' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Set password' })).not.toBeInTheDocument()
    expect(mockSetPassword).toHaveBeenCalledTimes(1)
    expect(mockSetPassword).toHaveBeenCalledWith({
      setupToken: 'link-token',
      password: 'correct horse',
      confirmPassword: 'correct horse',
    })
  })

  it('uses the setupToken issued by confirm (register → confirm → set-password flow)', async () => {
    window.history.pushState({}, '', '/confirm-registration?token=ev-token')
    mockConfirmRegistration.mockResolvedValueOnce({
      setupToken: 'st-issued-by-confirm',
      setupTokenType: 'password_setup',
      expiresInSec: 3600,
    })
    mockSetPassword.mockResolvedValueOnce(undefined)
    render(<ConfirmRegistrationPage />)

    fireEvent.change(await screen.findByLabelText('Password'), {
      target: { value: 'staple horse' },
    })
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'staple horse' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(mockSetPassword).toHaveBeenCalledWith({
      setupToken: 'st-issued-by-confirm',
      password: 'staple horse',
      confirmPassword: 'staple horse',
    })
  })

  it('shows policy error codes from a 400 and keeps the link usable for a retry', async () => {
    window.history.pushState({}, '', '/set-password?token=link-token')
    mockSetPassword
      .mockRejectedValueOnce({
        title: 'Bad Request',
        status: 400,
        errors: { password: ['too_common'] },
      } satisfies Problem)
      .mockResolvedValueOnce(undefined)
    render(<SetPasswordPage />)

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password123' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'password123' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('too_common')

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'correct horse' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(mockSetPassword).toHaveBeenCalledTimes(2)
    expect(mockSetPassword).toHaveBeenLastCalledWith({
      setupToken: 'link-token',
      password: 'correct horse',
      confirmPassword: 'correct horse',
    })
  })

  it('shows the rate-limit error with the retry hint on 429 and keeps the link usable', async () => {
    window.history.pushState({}, '', '/set-password?token=link-token')
    mockSetPassword.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many attempts',
      retryAfterSec: 900,
    } satisfies ApiProblem)
    render(<SetPasswordPage />)

    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct horse' } })
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'correct horse' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Too many attempts')
    expect(alert).toHaveTextContent('Try again in 15 minutes')
    expect(screen.getByRole('button', { name: 'Set password' })).toBeInTheDocument()
  })
})
