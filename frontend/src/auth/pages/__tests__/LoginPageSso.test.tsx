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
    expect(screen.getByText('ИЛИ')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Продолжить с Google' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Продолжить с GitHub' })).toBeInTheDocument()
    expect(mockListSsoProviders).toHaveBeenCalledTimes(1)
  })

  it('hides the SSO section when the provider list is empty', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: [] })
    render(<LoginPage />)

    await waitFor(() => expect(mockListSsoProviders).toHaveBeenCalledTimes(1))
    await act(async () => {})

    expect(screen.queryByLabelText('Single sign-on providers')).not.toBeInTheDocument()
    expect(screen.queryByText('ИЛИ')).not.toBeInTheDocument()
  })

  it('navigates to the authorization URL on provider click', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockResolvedValueOnce({
      authorizationUrl: 'https://idp.example/oauth/authorize?state=abc',
    })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с GitHub' }))

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

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с Google' }))

    await waitFor(() => expect(assignSpy).toHaveBeenCalledTimes(1))
    expect(mockAuthorizeSso).toHaveBeenCalledWith({
      providerId: 'google',
      returnTo: '/settings/profile',
    })
    expect(storedAtNavigation).toEqual(['/settings/profile'])
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBe('/settings/profile')
  })

  it('shows the russian error instead of navigating when authorize fails with 404 (T048)', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockRejectedValueOnce({
      title: 'Not Found',
      status: 404,
      detail: 'Provider not found',
    })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Провайдер входа не найден или недоступен')
    expect(assignSpy).not.toHaveBeenCalled()
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBeNull()
  })

  it('shows the russian IdP-unavailable error on 502 (T048)', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockRejectedValueOnce({
      title: 'Bad Gateway',
      status: 502,
      detail: 'Identity provider is temporarily unavailable',
    })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Сервис входа временно недоступен — попробуйте позже')
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('shows the russian rate-limit hint on 429 with retryAfterSec (T048)', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many requests',
      retryAfterSec: 300,
    })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Слишком много попыток — попробуйте через 5 минут')
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('shows «Слишком много попыток — попробуйте позже» on 429 without retryAfterSec (T048)', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockRejectedValueOnce({
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many requests',
    })
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Слишком много попыток — попробуйте позже')
  })

  it('shows the russian network fallback when authorize throws (T048)', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockRejectedValueOnce(new TypeError('NetworkError when attempting to fetch.'))
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Не удалось подключиться к сервису входа — попробуйте позже')
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('shows «Недопустимый адрес возврата» on 400 returnTo:invalid_format (T048)', async () => {
    window.history.pushState({}, '', '/login?returnTo=https%3A%2F%2Fevil.example')
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso.mockRejectedValueOnce({
      title: 'Bad Request',
      status: 400,
      detail: 'Invalid request',
      errors: { returnTo: ['invalid_format'] },
    })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Продолжить с Google' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Недопустимый адрес возврата')
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('resets ssoStartingId after a failure so the provider can be clicked again (T048)', async () => {
    mockListSsoProviders.mockResolvedValueOnce({ providers: enabledProviders })
    mockAuthorizeSso
      .mockRejectedValueOnce({ title: 'Not Found', status: 404, detail: 'Provider not found' })
      .mockResolvedValueOnce({ authorizationUrl: 'https://idp.example/oauth/authorize?state=ok' })
    assignSpy = stubLocationAssign()
    render(<LoginPage />)

    const google = await screen.findByRole('button', { name: 'Продолжить с Google' })
    fireEvent.click(google)
    await screen.findByRole('alert')

    // Кнопка снова активна (ssoStartingId сброшен) — повторный клик работает.
    expect(google).toBeEnabled()
    fireEvent.click(google)

    await waitFor(() =>
      expect(assignSpy).toHaveBeenCalledWith('https://idp.example/oauth/authorize?state=ok'),
    )
    expect(mockAuthorizeSso).toHaveBeenCalledTimes(2)
  })
})
