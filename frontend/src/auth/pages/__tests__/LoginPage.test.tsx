import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components, operations } from '../../../api/schema'
import type { ApiProblem } from '../../../api/auth'
import { stubLocationAssign } from '../../../test/location'
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

const initialUrl = window.location.href

let assignSpy: ReturnType<typeof stubLocationAssign> | undefined

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', initialUrl)
  assignSpy?.mockRestore()
  assignSpy = undefined
  vi.resetAllMocks()
})

function fillLoginForm(identifier: string, password: string) {
  fireEvent.change(screen.getByLabelText('Email или имя пользователя'), {
    target: { value: identifier },
  })
  fireEvent.change(screen.getByLabelText('Пароль'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'ВОЙТИ' }))
}

describe('LoginPage', () => {
  it('renders the login form', () => {
    render(<LoginPage />)

    expect(screen.getByLabelText('Email или имя пользователя')).toBeInTheDocument()
    expect(screen.getByLabelText('Пароль')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ВОЙТИ' })).toBeInTheDocument()
  })

  it('shows a visible forgot-password link in the footer', () => {
    render(<LoginPage />)

    const forgotPassword = screen.getByRole('link', { name: 'Забыли пароль?' })
    expect(forgotPassword).toBeVisible()
    expect(forgotPassword).toHaveAttribute('href', '/forgot-password')
    expect(screen.getByRole('link', { name: 'Зарегистрироваться' })).toHaveAttribute(
      'href',
      '/register',
    )
  })

  it('toggles the password field type and the eye button aria-label', () => {
    render(<LoginPage />)

    const password = screen.getByLabelText('Пароль')
    expect(password).toHaveAttribute('type', 'password')

    fireEvent.click(screen.getByRole('button', { name: 'Показать пароль' }))
    expect(password).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: 'Скрыть пароль' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Скрыть пароль' }))
    expect(password).toHaveAttribute('type', 'password')
    expect(screen.getByRole('button', { name: 'Показать пароль' })).toBeInTheDocument()
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

  it('redirects to /chat after a successful login', async () => {
    window.history.pushState({}, '', '/login')
    mockLogin.mockResolvedValueOnce(loginResponse())
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    await waitFor(() => expect(assignSpy).toHaveBeenCalledWith('/chat'))
    expect(mockSetTokenPair).toHaveBeenCalledTimes(1)
  })

  it('redirects to the safe returnTo path after a successful login', async () => {
    window.history.pushState({}, '', '/login?returnTo=/settings/security')
    mockLogin.mockResolvedValueOnce(loginResponse())
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    await waitFor(() => expect(assignSpy).toHaveBeenCalledWith('/settings/security'))
    expect(mockSetTokenPair).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['absolute url', 'https://evil.example'],
    ['protocol-relative url', '//evil.example'],
    ['backslash after slash', '/\\evil.com'],
  ])('redirects to /chat when returnTo is an unsafe %s', async (_reason, value) => {
    window.history.pushState({}, '', `/login?returnTo=${value}`)
    mockLogin.mockResolvedValueOnce(loginResponse())
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    await waitFor(() => expect(assignSpy).toHaveBeenCalledWith('/chat'))
    expect(assignSpy).not.toHaveBeenCalledWith(value)
  })

  it('does not redirect and shows the russian 401 error when login fails', async () => {
    window.history.pushState({}, '', '/login?returnTo=/settings/security')
    mockLogin.mockRejectedValueOnce({
      title: 'Unauthorized',
      status: 401,
      detail: 'Invalid credentials',
    } satisfies Problem)
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fillLoginForm('alice', 'wrong password')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Неверный логин или пароль')
    expect(assignSpy).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows the unified russian «Неверный логин или пароль» on 401 (T048)', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Unauthorized',
      status: 401,
      detail: 'Invalid credentials',
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'wrong password')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Неверный логин или пароль')
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows the russian finish-registration message on 403 (T048)', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Forbidden',
      status: 403,
      detail: 'Registration is not completed: confirm your email and set a password',
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Регистрация не завершена — подтвердите email и задайте пароль')
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows «Введите email или имя пользователя» on 400 identifier:missing (T048)', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      detail: 'Invalid request',
      errors: { identifier: ['missing'] },
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Введите email или имя пользователя')
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows «Введите пароль» on 400 password:missing (T048)', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      detail: 'Invalid request',
      errors: { password: ['missing'] },
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Введите пароль')
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it('shows the russian rate-limit hint with seconds on 429 (T048)', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many attempts',
      retryAfterSec: 42,
    } satisfies ApiProblem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Слишком много попыток — попробуйте через 42 секунды')
  })

  it('shows the brute-force lockout hint in minutes on 429 (T048)', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many failed attempts. Try again later.',
      retryAfterSec: 897,
    } satisfies ApiProblem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Слишком много попыток — попробуйте через 15 минут')
  })

  it('shows «Слишком много попыток — попробуйте позже» on 429 without retryAfterSec (T048)', async () => {
    mockLogin.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many attempts',
    } satisfies Problem)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Слишком много попыток — попробуйте позже')
  })

  it.each([
    ['сетевой сбой', new TypeError('NetworkError when attempting to fetch.')],
    [
      'неизвестная ошибка 500',
      { title: 'Internal Server Error', status: 500, detail: 'Unexpected' } satisfies Problem,
    ],
  ])('shows the unified russian fallback on %s (T048)', async (_reason, cause) => {
    mockLogin.mockRejectedValueOnce(cause)
    render(<LoginPage />)

    fillLoginForm('alice', 'correct horse')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Не удалось войти. Проверьте соединение и попробуйте ещё раз')
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })

  it.each([
    ['пустой identifier', ''],
    ['пробельный identifier', '   '],
  ])(
    'client-side check: %s → «Введите email или имя пользователя» без запроса (T048)',
    async (_reason, identifier) => {
      render(<LoginPage />)

      fillLoginForm(identifier, 'correct horse')

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('Введите email или имя пользователя')
      expect(mockLogin).not.toHaveBeenCalled()
      expect(mockSetTokenPair).not.toHaveBeenCalled()
    },
  )

  it('client-side check: пустой password → «Введите пароль» без запроса (T048)', async () => {
    render(<LoginPage />)

    fillLoginForm('alice', '')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Введите пароль')
    expect(mockLogin).not.toHaveBeenCalled()
    expect(mockSetTokenPair).not.toHaveBeenCalled()
  })
})
