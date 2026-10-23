import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { App } from './App'
import { HEARTBEAT_INTERVAL_MS } from './presence/presenceApi'
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

/**
 * 009, US1, T086 — хранитель присутствия на уровне App: мок №18-
 * транспорта (installStream по образцу MessengerPage-тестов) + мок
 * транспорта apiFetch — шов наблюдения №37-биений (тот же, что в
 * presenceApi.test.ts: НАСТОЯЩИЙ планировщик presenceApi бьёт в
 * /users/me/presence/heartbeat; кадр `connected` подаётся вручную,
 * fake timers прокручивают 30-секундный интервал). Прочие REST-вызовы
 * дерева отклоняются — сетевой слой маршрутным тестам не нужен.
 */
const { mockSse, mockApiFetch } = vi.hoisted(() => ({
  mockSse: { streamUserEvents: vi.fn() },
  mockApiFetch: vi.fn(),
}))

vi.mock('./conferences/api/conferences', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./conferences/api/conferences')>()),
  getInviteInfo: conferences.getInviteInfo,
}))

vi.mock('./api/sse', () => mockSse)

vi.mock('./api/client', () => ({
  apiFetch: mockApiFetch,
  refreshTokens: vi.fn(() => Promise.resolve(null)),
}))

const REFRESH_TOKEN_STORAGE_KEY = 'webchat.auth.refreshToken'

let assignMock: MockInstance

function navigate(pathname: string): void {
  window.history.pushState({}, '', pathname)
}

function authenticate(): void {
  window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, 'refresh-token-1')
}

interface MockStream {
  emit(eventType: string, data: string): void
}

/** Ручная подача кадров №18 (паттерн MessengerPage.conference.test). */
let stream: MockStream

function installStream(): MockStream {
  const listeners = new Map<string, (data: string) => void>()
  const mock: MockStream = {
    emit(eventType, data) {
      listeners.get(eventType)?.(data)
    },
  }
  mockSse.streamUserEvents.mockImplementation(() => ({
    subscribe(eventType: string, listener: (data: string) => void) {
      listeners.set(eventType, listener)
      return () => {
        listeners.delete(eventType)
      }
    },
    close(): void {
      // not asserted here (covered by useRealtime tests)
    },
    reconnect(): void {
      // №37 404-recovery seam — not asserted here
    },
  }))
  return mock
}

beforeEach(() => {
  assignMock = stubLocationAssign()
  conferences.getInviteInfo.mockReset()
  mockSse.streamUserEvents.mockReset()
  mockApiFetch.mockReset()
  mockApiFetch.mockRejectedValue(new Error('api offline in route tests'))
  stream = installStream()
})

/** №37-биения в моке транспорта (шов presenceApi.test.ts). */
function heartbeatCalls(): Array<[string, RequestInit | undefined]> {
  return (mockApiFetch.mock.calls as Array<[string, RequestInit | undefined]>).filter(
    ([path]) => path === '/users/me/presence/heartbeat',
  )
}

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

/**
 * 009, US1, T086 — хранитель присутствия на уровне App: пользователь в
 * звонке остаётся «онлайн» в чатах. Корень бага: №18-стрим и №37-биения
 * монтировались ТОЛЬКО MessengerPage, а вход в конференцию — full page
 * load (`window.location.assign('/conference/{id}…')`) — выгружал
 * мессенджер, сервер снимал регистрацию, и планировщик (TTL 90 с,
 * гистерезис 45 с) флипал «офлайн» всем наблюдателям на весь звонок.
 * Хранитель (null-компонент, `useRealtime` + `usePresenceHeartbeat`)
 * монтируется ВНУТРИ ProtectedRoute рядом с children — присутствие
 * получает семантику «авторизованная вкладка приложения открыта»
 * (`/`, `/chat`, `/conference/:id`); публичные поверхности исключены
 * автоматически: `/join/:inviteToken` (гость без userId, T050 без
 * ProtectedRoute) и auth-страницы стрим не открывают.
 */
describe('App presence keeper (009 T086 — в конференции пользователь остаётся «онлайн» в чатах)', () => {
  it('opens №18 on /conference/:id under auth and beats №37 from the connected frame', async () => {
    authenticate()
    navigate('/conference/c-1')
    render(<App />)

    // Хранитель монтируется вместе с ProtectedRoute — №18 открывается
    // ещё до разрешения ленивого чанка конференции.
    await waitFor(() => {
      expect(mockSse.streamUserEvents).toHaveBeenCalledTimes(1)
    })
    expect(await screen.findByRole('region', { name: 'Конференция' })).toBeInTheDocument()

    vi.useFakeTimers()
    try {
      act(() => {
        stream.emit('connected', JSON.stringify({ connectionId: 'conn-conf-1' }))
      })
      expect(heartbeatCalls()).toHaveLength(0)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
      })
      const beats = heartbeatCalls()
      expect(beats).toHaveLength(1)
      expect(JSON.parse(beats[0]![1]?.body as string)).toEqual({ connectionId: 'conn-conf-1' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('opens №18 on / and beats №37 exactly once (keeper replaced the dismantled MessengerPage wiring)', async () => {
    authenticate()
    navigate('/')
    render(<App />)

    expect(screen.getByRole('complementary', { name: 'Чаты и контакты' })).toBeInTheDocument()
    await waitFor(() => {
      expect(mockSse.streamUserEvents).toHaveBeenCalledTimes(1)
    })

    vi.useFakeTimers()
    try {
      act(() => {
        stream.emit('connected', JSON.stringify({ connectionId: 'conn-home-1' }))
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
      })
      // Ровно ОДИН планировщик №37: дубль MessengerPage демонтирован
      // (T086) — удвоение частоты биений невозможно.
      const beats = heartbeatCalls()
      expect(beats).toHaveLength(1)
      expect(JSON.parse(beats[0]![1]?.body as string)).toEqual({ connectionId: 'conn-home-1' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not open №18 on /join/:inviteToken (public guest surface, FR-010)', async () => {
    conferences.getInviteInfo.mockResolvedValue({ valid: true, live: true })
    navigate('/join/guest.nonce-token')
    render(<App />)

    await screen.findByRole('region', { name: 'Подключение к конференции' })
    expect(mockSse.streamUserEvents).not.toHaveBeenCalled()
  })

  it('does not open №18 on /login (auth surface)', () => {
    navigate('/login')
    render(<App />)

    expect(screen.getByLabelText('Email или имя пользователя')).toBeInTheDocument()
    expect(mockSse.streamUserEvents).not.toHaveBeenCalled()
  })

  it('does not open №18 for an unauthenticated visitor of /conference/:id (pending redirect)', () => {
    navigate('/conference/c-1')
    render(<App />)

    expect(assignMock).toHaveBeenCalledWith('/login?returnTo=%2Fconference%2Fc-1')
    expect(mockSse.streamUserEvents).not.toHaveBeenCalled()
  })
})
