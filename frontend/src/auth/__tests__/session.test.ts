import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  isSessionExpired,
  notifySessionExpired,
  onSessionExpired,
  setTokenPair,
  type TokenPair,
} from '../session'

const REFRESH_TOKEN_STORAGE_KEY = 'webchat.auth.refreshToken'

function tokenPair(accessToken: string, refreshToken: string): TokenPair {
  return { accessToken, refreshToken, tokenType: 'Bearer', expiresInSec: 300 }
}

afterEach(() => {
  clearTokens()
  window.localStorage.clear()
})

describe('session token storage', () => {
  it('keeps the access token in memory only and never persists it', () => {
    setTokenPair(tokenPair('access-1', 'refresh-1'))

    expect(getAccessToken()).toBe('access-1')
    const storedValues = Object.values(window.localStorage)
    expect(storedValues).toHaveLength(1)
    expect(storedValues[0]).not.toContain('access-1')
  })

  it('persists the refresh token in localStorage and reads it back', () => {
    setTokenPair(tokenPair('access-1', 'refresh-1'))

    expect(getRefreshToken()).toBe('refresh-1')
    expect(window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe('refresh-1')
  })

  it('updates the pair when the refresh token rotates', () => {
    setTokenPair(tokenPair('access-1', 'refresh-1'))
    setTokenPair(tokenPair('access-2', 'refresh-2'))

    expect(getAccessToken()).toBe('access-2')
    expect(getRefreshToken()).toBe('refresh-2')
    expect(window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe('refresh-2')
  })

  it('clears both tokens and marks the session expired', () => {
    setTokenPair(tokenPair('access-1', 'refresh-1'))

    clearTokens()

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
    expect(window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull()
    expect(isSessionExpired()).toBe(true)
  })

  it('reports an unexpired session while a refresh token is stored', () => {
    expect(isSessionExpired()).toBe(true)

    setTokenPair(tokenPair('access-1', 'refresh-1'))

    expect(isSessionExpired()).toBe(false)
  })
})

describe('session expiry notification', () => {
  it('notifies every registered listener once', () => {
    const first = vi.fn()
    const second = vi.fn()
    onSessionExpired(first)
    onSessionExpired(second)

    notifySessionExpired()

    expect(first).toHaveBeenCalledTimes(1)
    expect(second).toHaveBeenCalledTimes(1)
  })

  it('does not notify listeners that were never registered', () => {
    const listener = vi.fn()

    notifySessionExpired()

    expect(listener).not.toHaveBeenCalled()
  })
})
