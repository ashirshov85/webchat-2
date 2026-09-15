import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../../api/schema'
import type { ApiProblem } from '../../../api/auth'
import { ForgotPasswordPage } from '../ForgotPasswordPage'
import { ResetPasswordPage } from '../ResetPasswordPage'

type Problem = components['schemas']['Problem']

const { mockRequestPasswordReset, mockConfirmPasswordReset } = vi.hoisted(() => ({
  mockRequestPasswordReset: vi.fn(),
  mockConfirmPasswordReset: vi.fn(),
}))

vi.mock('../../../api/auth', () => ({
  requestPasswordReset: mockRequestPasswordReset,
  confirmPasswordReset: mockConfirmPasswordReset,
}))

const initialUrl = window.location.href

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', initialUrl)
  vi.resetAllMocks()
})

function fillForgotPasswordForm(email: string) {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } })
  fireEvent.click(screen.getByRole('button', { name: 'Send reset link' }))
}

function fillResetPasswordForm(password: string, confirmPassword: string) {
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.change(screen.getByLabelText('Confirm password'), {
    target: { value: confirmPassword },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Reset password' }))
}

describe('ForgotPasswordPage', () => {
  it('renders the forgot-password form', () => {
    render(<ForgotPasswordPage />)

    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send reset link' })).toBeInTheDocument()
  })

  it('submits the email and shows the uniform check-your-email notice on 202', async () => {
    mockRequestPasswordReset.mockResolvedValueOnce(undefined)
    render(<ForgotPasswordPage />)

    fillForgotPasswordForm('alice@example.com')

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('alice@example.com')
    expect(mockRequestPasswordReset).toHaveBeenCalledTimes(1)
    expect(mockRequestPasswordReset).toHaveBeenCalledWith({ email: 'alice@example.com' })
  })

  it('shows field validation codes from a 400 problem', async () => {
    mockRequestPasswordReset.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { email: ['invalid_format'] },
    } satisfies Problem)
    render(<ForgotPasswordPage />)

    fillForgotPasswordForm('not-an-email')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('invalid_format')
    expect(mockRequestPasswordReset).toHaveBeenCalledTimes(1)
  })

  it('shows the rate-limit detail with the retry hint on 429', async () => {
    mockRequestPasswordReset.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Rate limit exceeded',
      retryAfterSec: 42,
    } satisfies ApiProblem)
    render(<ForgotPasswordPage />)

    fillForgotPasswordForm('alice@example.com')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Rate limit exceeded')
    expect(alert).toHaveTextContent('42')
  })
})

describe('ResetPasswordPage', () => {
  it('uses the token from the /reset-password link and completes the reset on 204', async () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset.mockResolvedValueOnce(undefined)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent(/log in/i)
    expect(screen.queryByRole('button', { name: 'Reset password' })).not.toBeInTheDocument()
    expect(mockConfirmPasswordReset).toHaveBeenCalledTimes(1)
    expect(mockConfirmPasswordReset).toHaveBeenCalledWith({
      token: 'reset-token',
      password: 'correct horse',
      confirmPassword: 'correct horse',
    })
  })

  it('completes the forgot → reset flow via the emailed link', async () => {
    mockRequestPasswordReset.mockResolvedValueOnce(undefined)
    const { unmount } = render(<ForgotPasswordPage />)

    fillForgotPasswordForm('alice@example.com')
    expect(await screen.findByRole('status')).toBeInTheDocument()
    unmount()

    window.history.pushState({}, '', '/reset-password?token=emailed-token')
    mockConfirmPasswordReset.mockResolvedValueOnce(undefined)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('staple horse', 'staple horse')

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(mockConfirmPasswordReset).toHaveBeenCalledWith({
      token: 'emailed-token',
      password: 'staple horse',
      confirmPassword: 'staple horse',
    })
  })

  it('shows the invalid-or-expired-link detail on 400', async () => {
    window.history.pushState({}, '', '/reset-password?token=used-token')
    mockConfirmPasswordReset.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      detail: 'Token invalid or expired',
    } satisfies Problem)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Token invalid or expired')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('shows policy error codes from a 400 and keeps the link usable for a retry', async () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset
      .mockRejectedValueOnce({
        title: 'Bad Request',
        status: 400,
        errors: { password: ['too_common'] },
      } satisfies Problem)
      .mockResolvedValueOnce(undefined)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('password123', 'password123')

    expect(await screen.findByRole('alert')).toHaveTextContent('too_common')

    fillResetPasswordForm('correct horse', 'correct horse')

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(mockConfirmPasswordReset).toHaveBeenCalledTimes(2)
    expect(mockConfirmPasswordReset).toHaveBeenLastCalledWith({
      token: 'reset-token',
      password: 'correct horse',
      confirmPassword: 'correct horse',
    })
  })

  it('shows the rate-limit detail on 429', async () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Rate limit exceeded',
    } satisfies Problem)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    expect(await screen.findByRole('alert')).toHaveTextContent('Rate limit exceeded')
  })
})
