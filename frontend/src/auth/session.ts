import type { components } from '../api/schema'

export type TokenPair = components['schemas']['TokenPair']

const REFRESH_TOKEN_STORAGE_KEY = 'webchat.auth.refreshToken'

let accessToken: string | null = null

export type SessionExpiredListener = () => void

const sessionExpiredListeners = new Set<SessionExpiredListener>()

function readStoredRefreshToken(): string | null {
  try {
    return window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)
  } catch {
    // storage unavailable (security settings, quota) — treat as no session
    return null
  }
}

function storeRefreshToken(refreshToken: string | null): void {
  try {
    if (refreshToken === null) {
      window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY)
    } else {
      window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, refreshToken)
    }
  } catch {
    // storage unavailable — session degrades to memory-only access token
  }
}

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return readStoredRefreshToken()
}

export function setTokenPair(pair: TokenPair): void {
  accessToken = pair.accessToken
  storeRefreshToken(pair.refreshToken)
}

export function clearTokens(): void {
  accessToken = null
  storeRefreshToken(null)
}

export function isSessionExpired(): boolean {
  return readStoredRefreshToken() === null
}

export function onSessionExpired(listener: SessionExpiredListener): void {
  sessionExpiredListeners.add(listener)
}

export function notifySessionExpired(): void {
  for (const listener of sessionExpiredListeners) {
    listener()
  }
}
