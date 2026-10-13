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
  fireEvent.click(screen.getByRole('button', { name: 'ОТПРАВИТЬ ССЫЛКУ' }))
}

function fillResetPasswordForm(password: string, confirmPassword: string) {
  fireEvent.change(screen.getByLabelText('Новый пароль'), { target: { value: password } })
  fireEvent.change(screen.getByLabelText('Подтвердите пароль'), {
    target: { value: confirmPassword },
  })
  fireEvent.click(screen.getByRole('button', { name: 'СБРОСИТЬ ПАРОЛЬ' }))
}

describe('ForgotPasswordPage', () => {
  it('renders the forgot-password form', () => {
    render(<ForgotPasswordPage />)

    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ ССЫЛКУ' })).toBeInTheDocument()
  })

  it('renders the auth-card shell with badge, brand block and form title', () => {
    const { container } = render(<ForgotPasswordPage />)

    const card = container.querySelector('.auth-card')
    expect(card).not.toBeNull()
    expect(card?.querySelector('.auth-badge')).not.toBeNull()
    const brandTitle = container.querySelector('.auth-card .auth-brand-title')
    expect(brandTitle).not.toBeNull()
    expect(brandTitle).toHaveTextContent('Aethergram')
    expect(container.querySelector('.auth-card .auth-brand-sub')).not.toBeNull()
    expect(screen.getByRole('heading', { name: 'Восстановление пароля' })).toHaveClass(
      'auth-form-title',
    )
  })

  it('uses the auth-field pattern with an svg icon for the email input', () => {
    render(<ForgotPasswordPage />)

    const field = screen.getByLabelText('Email').closest('.auth-field')
    expect(field).not.toBeNull()
    expect(field?.querySelector('svg.auth-field-icon')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ ССЫЛКУ' })).toHaveClass('auth-submit')
  })

  it('shows the back-to-login link in the auth-footer in every state', async () => {
    mockRequestPasswordReset.mockResolvedValueOnce(undefined)
    render(<ForgotPasswordPage />)

    const backLink = screen.getByRole('link', { name: 'Вернуться ко входу' })
    expect(backLink).toHaveAttribute('href', '/login')
    expect(backLink.closest('.auth-footer')).not.toBeNull()

    fillForgotPasswordForm('alice@example.com')
    expect(await screen.findByRole('status')).toBeInTheDocument()

    expect(screen.getByRole('link', { name: 'Вернуться ко входу' })).toHaveAttribute(
      'href',
      '/login',
    )
  })

  it('submits the email and shows the uniform check-your-email notice on 202', async () => {
    mockRequestPasswordReset.mockResolvedValueOnce(undefined)
    render(<ForgotPasswordPage />)

    fillForgotPasswordForm('alice@example.com')

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('alice@example.com')
    expect(status).toHaveTextContent('действует 1 час')
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
    expect(alert).toHaveTextContent('Try again in 42 s')
  })
})

describe('ResetPasswordPage', () => {
  it('renders the auth-card shell with badge, brand block and form title', () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    const { container } = render(<ResetPasswordPage />)

    const card = container.querySelector('.auth-card')
    expect(card).not.toBeNull()
    expect(card?.querySelector('.auth-badge')).not.toBeNull()
    const brandTitle = container.querySelector('.auth-card .auth-brand-title')
    expect(brandTitle).not.toBeNull()
    expect(brandTitle).toHaveTextContent('Aethergram')
    expect(container.querySelector('.auth-card .auth-brand-sub')).not.toBeNull()
    expect(screen.getByRole('heading', { name: 'Сброс пароля' })).toHaveClass('auth-form-title')
  })

  it('shows the intro with the 1-hour validity hint and the back-to-login footer', () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    render(<ResetPasswordPage />)

    expect(screen.getByText(/Придумайте новый пароль/)).toHaveTextContent('действует 1 час')
    const backLink = screen.getByRole('link', { name: 'Вернуться ко входу' })
    expect(backLink).toHaveAttribute('href', '/login')
    expect(backLink.closest('.auth-footer')).not.toBeNull()
  })

  it('uses the auth-field pattern with lock svg icons for both password inputs', () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    render(<ResetPasswordPage />)

    const field = screen.getByLabelText('Новый пароль').closest('.auth-field')
    expect(field).not.toBeNull()
    expect(field?.querySelector('svg.auth-field-icon')).not.toBeNull()
    const confirmField = screen.getByLabelText('Подтвердите пароль').closest('.auth-field')
    expect(confirmField).not.toBeNull()
    expect(confirmField?.querySelector('svg.auth-field-icon')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'СБРОСИТЬ ПАРОЛЬ' })).toHaveClass('auth-submit')
  })

  it('shows an alert with a forgot-password link when the token is missing', () => {
    window.history.pushState({}, '', '/reset-password')
    render(<ResetPasswordPage />)

    expect(screen.getByRole('heading', { name: 'Сброс пароля' })).toHaveClass('auth-form-title')
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('отсутствует токен')
    const forgotLink = screen.getByRole('link', { name: /восстановления пароля/ })
    expect(forgotLink).toHaveAttribute('href', '/forgot-password')
  })

  it('uses the token from the /reset-password link and completes the reset on 204', async () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset.mockResolvedValueOnce(undefined)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('Пароль сброшен — все сессии отозваны.')
    const loginLink = screen.getByRole('link', { name: 'войти' })
    expect(loginLink).toHaveAttribute('href', '/login')
    expect(screen.queryByRole('button', { name: 'СБРОСИТЬ ПАРОЛЬ' })).not.toBeInTheDocument()
    expect(mockConfirmPasswordReset).toHaveBeenCalledTimes(1)
    expect(mockConfirmPasswordReset).toHaveBeenCalledWith({
      token: 'reset-token',
      password: 'correct horse',
      confirmPassword: 'correct horse',
    })
  })

  it('shows the Russian mismatch message without consuming the link', async () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'different horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Пароли не совпадают.')
    expect(alert).toHaveTextContent('Ссылка остаётся действующей — исправьте и попробуйте снова.')
    expect(mockConfirmPasswordReset).not.toHaveBeenCalled()
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

  it('shows the russian invalid-or-expired-link message on a uniform 400 without errors', async () => {
    window.history.pushState({}, '', '/reset-password?token=used-token')
    mockConfirmPasswordReset.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      detail: 'Token is invalid or expired; request a new link',
    } satisfies Problem)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      'Ссылка недействительна или истекла — запросите новое письмо на странице восстановления пароля',
    )
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it.each([
    ['blank', '        ', 'Пароль не должен быть пустым или состоять только из пробелов'],
    ['too_short', 'Sh0rt!1', 'Пароль должен быть не короче 8 символов'],
    ['too_long', 'a1B!'.repeat(32) + 'z', 'Пароль должен быть не длиннее 128 символов'],
    ['too_common', 'password123', 'Этот пароль слишком распространён — выберите другой'],
    ['same_as_username', 'hargrove', 'Пароль не должен совпадать с именем пользователя'],
    ['same_as_email', 'h.hargrove@aethergram.io', 'Пароль не должен совпадать с email'],
  ])(
    '400 password:%s → the russian message shared with the password-change modal',
    async (code, password, message) => {
      window.history.pushState({}, '', '/reset-password?token=reset-token')
      mockConfirmPasswordReset.mockRejectedValueOnce({
        title: 'Bad Request',
        status: 400,
        errors: { password: [code] },
      } satisfies Problem)
      render(<ResetPasswordPage />)

      fillResetPasswordForm(password, password)

      expect(await screen.findByRole('alert')).toHaveTextContent(message)
      // отклонённый пароль НЕ расходует ссылку (контракт №9, T039)
      expect(mockConfirmPasswordReset).toHaveBeenCalledTimes(1)
    },
  )

  it.each([
    ['mismatch', 'Пароли не совпадают'],
    ['missing', 'Подтвердите пароль'],
  ])('400 confirmPassword:%s → «%s»', async (code, message) => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { confirmPassword: [code] },
    } satisfies Problem)
    render(<ResetPasswordPage />)

    // значения равны — клиентская проверка совпадения проходит,
    // до сервера доходит серверный код (напр. нормализация)
    fillResetPasswordForm('correct horse', 'correct horse')

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })

  it('400 password:missing → «Введите пароль»', async () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      errors: { password: ['missing'] },
    } satisfies Problem)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    expect(await screen.findByRole('alert')).toHaveTextContent('Введите пароль')
  })

  it('keeps the link usable for a retry after a policy 400 (russian message)', async () => {
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

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Этот пароль слишком распространён — выберите другой',
    )

    fillResetPasswordForm('correct horse', 'correct horse')

    expect(await screen.findByRole('status')).toBeInTheDocument()
    expect(mockConfirmPasswordReset).toHaveBeenCalledTimes(2)
    expect(mockConfirmPasswordReset).toHaveBeenLastCalledWith({
      token: 'reset-token',
      password: 'correct horse',
      confirmPassword: 'correct horse',
    })
  })

  it('shows the russian rate-limit message with the pluralized retry hint on 429', async () => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Rate limit exceeded',
      retryAfterSec: 42,
    } satisfies ApiProblem)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Слишком много попыток — попробуйте через 42 секунды')
    expect(screen.getByRole('button', { name: 'СБРОСИТЬ ПАРОЛЬ' })).toBeInTheDocument()
  })

  it.each([
    ['сетевой сбой', new TypeError('NetworkError when attempting to fetch resource.')],
    ['неизвестная проблема 500', { title: 'Internal Server Error', status: 500 } satisfies Problem],
  ])('%s → единый русский fallback', async (_reason, cause) => {
    window.history.pushState({}, '', '/reset-password?token=reset-token')
    mockConfirmPasswordReset.mockRejectedValueOnce(cause)
    render(<ResetPasswordPage />)

    fillResetPasswordForm('correct horse', 'correct horse')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Не удалось сбросить пароль. Проверьте соединение и попробуйте ещё раз',
    )
  })
})
