/**
 * Typed API client (feature 002): Bearer interceptor, single-flight
 * auto-refresh on 401 and unified final-401 handling
 * (contracts/api-contract.md §3). Types are re-exported from the generated
 * OpenAPI schema — `contracts/openapi.yaml` remains the single source of
 * truth; regenerate via `pnpm generate:api` (contracts/api-contract.md §4).
 */
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  notifySessionExpired,
  setTokenPair,
} from '../auth/session'
import type { TokenPair } from '../auth/session'

export type { components, paths } from './schema'

const BASE_URL = '/api/v1'
const REFRESH_URL = `${BASE_URL}/auth/refresh`

async function performRequest(
  path: string,
  init: RequestInit | undefined,
  accessTokenOverride?: string,
): Promise<Response> {
  const token = accessTokenOverride ?? getAccessToken()
  const headers = new Headers(init?.headers)
  if (token !== null && token !== undefined) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  return fetch(`${BASE_URL}${path}`, { ...init, headers })
}

async function performRefresh(refreshToken: string): Promise<TokenPair | null> {
  const response = await fetch(REFRESH_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, application/problem+json',
    },
    body: JSON.stringify({ refreshToken }),
  })
  if (!response.ok) {
    clearTokens()
    notifySessionExpired()
    return null
  }
  const pair = (await response.json()) as TokenPair
  setTokenPair(pair)
  return pair
}

let inflightRefresh: Promise<TokenPair | null> | null = null

function refreshOnce(refreshToken: string): Promise<TokenPair | null> {
  if (inflightRefresh === null) {
    inflightRefresh = performRefresh(refreshToken).finally(() => {
      inflightRefresh = null
    })
  }
  return inflightRefresh
}

export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const response = await performRequest(path, init)
  if (response.status !== 401) {
    return response
  }
  const refreshToken = getRefreshToken()
  if (refreshToken === null) {
    return response
  }
  const refreshedPair = await refreshOnce(refreshToken)
  if (refreshedPair === null) {
    return response
  }
  return performRequest(path, init, refreshedPair.accessToken)
}
