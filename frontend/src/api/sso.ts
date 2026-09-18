import { toApiProblem } from './auth'
import { apiFetch } from './client'
import type { components, operations } from './schema'

export type SsoProvider = components['schemas']['SsoProvider']

export type SsoProvidersResponse = components['schemas']['SsoProvidersResponse']

export type SsoAuthorizeRequest = components['schemas']['SsoAuthorizeRequest']

export type SsoTokenRequest = components['schemas']['SsoTokenRequest']

export type SsoAuthorizeResponse = components['schemas']['SsoAuthorizeResponse']

export type SsoLinkAuthorizeRequest = components['schemas']['SsoLinkAuthorizeRequest']

export type Identity = components['schemas']['Identity']

export type IdentitiesResponse = components['schemas']['IdentitiesResponse']

export type SsoLoginResponse =
  operations['ssoToken']['responses'][200]['content']['application/json']

export const RETURN_TO_STORAGE_KEY = 'sso:returnTo'

const BASE_URL = '/api/v1'

async function request(path: string, method: string, body?: unknown): Promise<Response> {
  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      Accept: 'application/json, application/problem+json',
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return response
}

async function authedRequest(path: string, method: string, body?: unknown): Promise<Response> {
  const response = await apiFetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      Accept: 'application/json, application/problem+json',
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return response
}

export async function listSsoProviders(): Promise<SsoProvidersResponse> {
  const response = await request('/auth/sso/providers', 'GET')
  return (await response.json()) as SsoProvidersResponse
}

export async function authorizeSso(body: SsoAuthorizeRequest): Promise<SsoAuthorizeResponse> {
  const response = await request('/auth/sso/authorize', 'POST', body)
  return (await response.json()) as SsoAuthorizeResponse
}

export async function exchangeSsoToken(body: SsoTokenRequest): Promise<SsoLoginResponse> {
  const response = await request('/auth/sso/token', 'POST', body)
  return (await response.json()) as SsoLoginResponse
}

export async function linkSsoAuthorize(
  body: SsoLinkAuthorizeRequest,
): Promise<SsoAuthorizeResponse> {
  const response = await authedRequest('/auth/sso/link/authorize', 'POST', body)
  return (await response.json()) as SsoAuthorizeResponse
}

export async function listIdentities(): Promise<IdentitiesResponse> {
  const response = await authedRequest('/users/me/identities', 'GET')
  return (await response.json()) as IdentitiesResponse
}

export async function unlinkIdentity(identityId: string): Promise<void> {
  await authedRequest(`/users/me/identities/${encodeURIComponent(identityId)}`, 'DELETE')
}

export function saveReturnTo(returnTo: string): void {
  sessionStorage.setItem(RETURN_TO_STORAGE_KEY, returnTo)
}

export function readReturnTo(): string | null {
  return sessionStorage.getItem(RETURN_TO_STORAGE_KEY)
}

export function clearReturnTo(): void {
  sessionStorage.removeItem(RETURN_TO_STORAGE_KEY)
}
