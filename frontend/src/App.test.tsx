import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { App } from './App'
import { stubLocationAssign } from './test/location'

/**
 * 009, US4, T050 — публичный маршрут `/join/:inviteToken`: без
 * аутентификации грузится ленивый чанк GuestJoinPage (T049), чат-роуты
 * остаются недоступными (FR-010). API-клиент конференций замокан
 * (№54 на монтаже страницы): valid-ответ доводит до шага имени —
 * маркер гостевой поверхности без раскрытия чата.
 */
const conferences = vi.hoisted(() => ({
  getInviteInfo: vi.fn(),
}))

vi.mock('./conferences/api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./conferences/api/conferences')>()),
  getInviteInfo: conferences.getInviteInfo,
}))

const REFRESH_TOKEN_STORAGE_KEY = 'webchat.auth.refreshToken'

let assignMock: MockInstance

function navigate(pathname: string): void {
  window.history.pushState({}, '', pathname)
}

function authenticate(): void {
  window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, 'refresh-token-1')
}

beforeEach(() => {
  assignMock = stubLocationAssign()
  conferences.getInviteInfo.mockReset()
})

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  assignMock.mockRestore()
  navigate('/')
  vi.clearAllMocks()
})

describe('App', () => {
  it('does not render the application heading on any route', () => {
    navigate('/forgot-password')
    const publicPage = render(<App />)

    expect(screen.queryByRole('heading', { level: 1, name: 'WebChat' })).not.toBeInTheDocument()

    publicPage.unmount()
    navigate('/set-password')
    render(<App />)

    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    cleanup()

    authenticate()
    navigate('/')
    render(<App />)

    expect(screen.getByRole('complementary', { name: 'Чаты и контакты' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })
})

describe('App protected messenger route', () => {
  it('redirects an unauthenticated visitor of / to the login page with returnTo', () => {
    navigate('/')
    render(<App />)

    expect(assignMock).toHaveBeenCalledWith('/login?returnTo=%2F')
    expect(screen.queryByRole('complementary', { name: 'Чаты и контакты' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Окно диалога' })).not.toBeInTheDocument()
  })

  it('redirects an unauthenticated visitor of /chat back to the login page', () => {
    navigate('/chat')
    render(<App />)

    expect(assignMock).toHaveBeenCalledWith('/login?returnTo=%2Fchat')
  })

  it('renders the messenger layout for an authenticated user on /', () => {
    authenticate()
    navigate('/')
    render(<App />)

    expect(screen.getByRole('complementary', { name: 'Чаты и контакты' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Окно диалога' })).toBeInTheDocument()
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('renders the messenger layout for an authenticated user on /chat', () => {
    authenticate()
    navigate('/chat')
    render(<App />)

    expect(screen.getByRole('region', { name: 'Окно диалога' })).toBeInTheDocument()
    expect(assignMock).not.toHaveBeenCalled()
  })
})

describe('App conference route /conference/:id (009 US1, T025)', () => {
  it('redirects an unauthenticated visitor to the login page with returnTo', () => {
    navigate('/conference/c-1')
    render(<App />)

    expect(assignMock).toHaveBeenCalledWith('/login?returnTo=%2Fconference%2Fc-1')
    expect(screen.queryByRole('region', { name: 'Конференция' })).not.toBeInTheDocument()
  })

  it('renders the lazy conference page for an authenticated user', async () => {
    authenticate()
    navigate('/conference/11111111-2222-3333-4444-555555555555')
    render(<App />)

    const conference = await screen.findByRole('region', { name: 'Конференция' })
    expect(conference).toHaveAttribute('data-conference-id', '11111111-2222-3333-4444-555555555555')
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('matches the route with a mode query and keeps it out of returnTo', async () => {
    authenticate()
    window.history.pushState({}, '', '/conference/c-1?mode=video')
    render(<App />)

    expect(await screen.findByRole('region', { name: 'Конференция' })).toBeInTheDocument()
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('does not claim /conference/ without an id', () => {
    authenticate()
    navigate('/conference/')
    render(<App />)

    expect(screen.queryByRole('region', { name: 'Конференция' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create an account' })).toBeInTheDocument()
  })
})

describe('App guest join route /join/:inviteToken (009 US4, T050)', () => {
  const INVITE_TOKEN = 'guest.nonce-jwt-token'

  it('renders the lazy guest join page for an unauthenticated visitor', async () => {
    conferences.getInviteInfo.mockResolvedValue({ valid: true, live: true })
    navigate(`/join/${INVITE_TOKEN}`)
    render(<App />)

    const guestJoin = await screen.findByRole('region', { name: 'Подключение к конференции' })
    expect(
      await screen.findByRole('heading', { name: 'Подключение к конференции' }),
    ).toBeInTheDocument()
    expect(guestJoin).toHaveAttribute('data-guest-join', INVITE_TOKEN)
    expect(screen.getByLabelText('Ваше имя')).toBeInTheDocument()
    expect(conferences.getInviteInfo).toHaveBeenCalledWith(INVITE_TOKEN)
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('keeps the chat surface excluded for the guest (FR-010)', async () => {
    conferences.getInviteInfo.mockResolvedValue({ valid: true })
    navigate(`/join/${INVITE_TOKEN}`)
    render(<App />)

    await screen.findByRole('region', { name: 'Подключение к конференции' })
    expect(await screen.findByLabelText('Ваше имя')).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Чаты и контакты' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Окно диалога' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Конференция' })).not.toBeInTheDocument()
  })

  it('renders the same public route for an authenticated visitor (Q14=A: enters as guest)', async () => {
    authenticate()
    conferences.getInviteInfo.mockResolvedValue({ valid: true })
    navigate(`/join/${INVITE_TOKEN}`)
    render(<App />)

    expect(
      await screen.findByRole('region', { name: 'Подключение к конференции' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Чаты и контакты' })).not.toBeInTheDocument()
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('does not redirect /join/:inviteToken to login and keeps chat routes protected', () => {
    conferences.getInviteInfo.mockResolvedValue({ valid: false, reason: 'expired' })
    navigate('/join/whatever')
    render(<App />)

    expect(assignMock).not.toHaveBeenCalled()
    cleanup()

    navigate('/chat')
    render(<App />)

    expect(assignMock).toHaveBeenCalledWith('/login?returnTo=%2Fchat')
    expect(
      screen.queryByRole('region', { name: 'Подключение к конференции' }),
    ).not.toBeInTheDocument()
  })

  it('does not claim /join/ without a token', () => {
    conferences.getInviteInfo.mockResolvedValue({ valid: true })
    navigate('/join/')
    render(<App />)

    expect(
      screen.queryByRole('region', { name: 'Подключение к конференции' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Create an account' })).toBeInTheDocument()
    expect(conferences.getInviteInfo).not.toHaveBeenCalled()
  })
})
