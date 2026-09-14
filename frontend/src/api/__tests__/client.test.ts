import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../client'

const session = vi.hoisted(() => ({
  getAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  setTokenPair: vi.fn(),
  clearTokens: vi.fn(),
  onSessionExpired: vi.fn(),
  notifySessionExpired: vi.fn(),
}))

vi.mock('../../auth/session', () => session)

type FetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

const fetchMock = vi.fn<FetchLike>(() => Promise.resolve(jsonResponse(200, {})))

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function problemResponse(status: number, detail: string): Response {
  return new Response(JSON.stringify({ title: 'HTTP error', status, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

function tokenPair(accessToken: string, refreshToken: string) {
  return { accessToken, refreshToken, tokenType: 'Bearer', expiresInSec: 300 }
}

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.toString()
  return input.url
}

function authorizationOf(input: RequestInfo | URL, init?: RequestInit): string | null {
  const headers = new Headers(init?.headers)
  if (typeof input !== 'string' && !(input instanceof URL)) {
    new Headers(input.headers).forEach((value, key) => headers.set(key, value))
  }
  return headers.get('Authorization')
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

type FetchCall = [input: RequestInfo | URL, init?: RequestInit | undefined]

function refreshCalls(): FetchCall[] {
  return fetchMock.mock.calls.filter(([input]) => urlOf(input) === '/api/v1/auth/refresh')
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  session.getAccessToken.mockReturnValue(null)
  session.getRefreshToken.mockReturnValue(null)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetAllMocks()
})

describe('apiFetch bearer interceptor', () => {
  it('sends the stored access token as a Bearer header', async () => {
    session.getAccessToken.mockReturnValue('access-1')
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, { id: 'user-1' })))

    const response = await apiFetch('/users/me')

    expect(response.status).toBe(200)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const original = fetchMock.mock.calls[0]!
    expect(urlOf(original[0])).toBe('/api/v1/users/me')
    expect(authorizationOf(original[0], original[1])).toBe('Bearer access-1')
  })

  it('sends no Authorization header when no access token is stored', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, {})))

    const response = await apiFetch('/auth/login', { method: 'POST' })

    expect(response.status).toBe(200)
    const original = fetchMock.mock.calls[0]!
    expect(authorizationOf(original[0], original[1])).toBeNull()
  })
})

describe('apiFetch auto-refresh', () => {
  it('refreshes the token pair once and retries the original request on 401', async () => {
    session.getAccessToken.mockReturnValue('access-old')
    session.getRefreshToken.mockReturnValue('refresh-1')
    fetchMock.mockImplementation((input, init) => {
      if (urlOf(input) === '/api/v1/auth/refresh') {
        return Promise.resolve(jsonResponse(200, tokenPair('access-new', 'refresh-2')))
      }
      return authorizationOf(input, init) === 'Bearer access-old'
        ? Promise.resolve(problemResponse(401, 'Not authenticated'))
        : Promise.resolve(jsonResponse(200, { id: 'user-1' }))
    })

    const response = await apiFetch('/users/me')

    expect(response.status).toBe(200)
    expect(refreshCalls()).toHaveLength(1)
    const refreshInit = refreshCalls()[0]![1]
    expect(refreshInit?.method).toBe('POST')
    const refreshBody = typeof refreshInit?.body === 'string' ? refreshInit.body : ''
    expect(JSON.parse(refreshBody)).toEqual({ refreshToken: 'refresh-1' })
    expect(session.setTokenPair).toHaveBeenCalledTimes(1)
    expect(session.setTokenPair).toHaveBeenCalledWith(tokenPair('access-new', 'refresh-2'))
    expect(fetchMock).toHaveBeenCalledTimes(3)
    const retry = fetchMock.mock.calls[2]!
    expect(authorizationOf(retry[0], retry[1])).toBe('Bearer access-new')
  })

  it('shares a single in-flight refresh between parallel 401s', async () => {
    session.getAccessToken.mockReturnValue('access-old')
    session.getRefreshToken.mockReturnValue('refresh-1')
    const refresh = deferred<Response>()
    fetchMock.mockImplementation((input, init) => {
      if (urlOf(input) === '/api/v1/auth/refresh') {
        return refresh.promise
      }
      return authorizationOf(input, init) === 'Bearer access-old'
        ? Promise.resolve(problemResponse(401, 'Not authenticated'))
        : Promise.resolve(jsonResponse(200, { id: 'user-1' }))
    })

    const first = apiFetch('/users/me')
    const second = apiFetch('/users/me')

    await vi.waitFor(() => {
      expect(refreshCalls()).toHaveLength(1)
    })
    refresh.resolve(jsonResponse(200, tokenPair('access-new', 'refresh-2')))

    const [firstResponse, secondResponse] = await Promise.all([first, second])
    expect(firstResponse.status).toBe(200)
    expect(secondResponse.status).toBe(200)
    expect(refreshCalls()).toHaveLength(1)
    expect(session.setTokenPair).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(5)
    const firstRetry = fetchMock.mock.calls[3]!
    const secondRetry = fetchMock.mock.calls[4]!
    expect(authorizationOf(firstRetry[0], firstRetry[1])).toBe('Bearer access-new')
    expect(authorizationOf(secondRetry[0], secondRetry[1])).toBe('Bearer access-new')
  })

  it('reports session expiry once when the shared refresh fails', async () => {
    session.getAccessToken.mockReturnValue('access-old')
    session.getRefreshToken.mockReturnValue('refresh-1')
    const refresh = deferred<Response>()
    fetchMock.mockImplementation((input, init) => {
      if (urlOf(input) === '/api/v1/auth/refresh') {
        return refresh.promise
      }
      return authorizationOf(input, init) === 'Bearer access-old'
        ? Promise.resolve(problemResponse(401, 'Not authenticated'))
        : Promise.resolve(jsonResponse(200, { id: 'user-1' }))
    })

    const first = apiFetch('/users/me')
    const second = apiFetch('/users/me')

    await vi.waitFor(() => {
      expect(refreshCalls()).toHaveLength(1)
    })
    refresh.resolve(problemResponse(401, 'Not authenticated'))

    const [firstResponse, secondResponse] = await Promise.all([first, second])
    expect(firstResponse.status).toBe(401)
    expect(secondResponse.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(session.clearTokens).toHaveBeenCalledTimes(1)
    expect(session.notifySessionExpired).toHaveBeenCalledTimes(1)
  })
})

describe('apiFetch unified 401 handling', () => {
  it('passes a 401 through untouched when no refresh token is stored', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(problemResponse(401, 'Not authenticated')))

    const response = await apiFetch('/users/me')

    expect(response.status).toBe(401)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(session.setTokenPair).not.toHaveBeenCalled()
    expect(session.clearTokens).not.toHaveBeenCalled()
    expect(session.notifySessionExpired).not.toHaveBeenCalled()
  })

  it('passes non-401 responses through without refreshing', async () => {
    session.getAccessToken.mockReturnValue('access-1')
    session.getRefreshToken.mockReturnValue('refresh-1')
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ title: 'Too Many Requests', status: 429 }), {
          status: 429,
          headers: {
            'Content-Type': 'application/problem+json',
            'Retry-After': '42',
          },
        }),
      ),
    )

    const response = await apiFetch('/users/me')

    expect(response.status).toBe(429)
    expect(response.headers.get('Retry-After')).toBe('42')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(session.setTokenPair).not.toHaveBeenCalled()
    expect(session.clearTokens).not.toHaveBeenCalled()
    expect(session.notifySessionExpired).not.toHaveBeenCalled()
  })
})
