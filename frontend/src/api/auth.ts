import { apiFetch } from './client'
import type { components, operations } from './schema'

export type Problem = components['schemas']['Problem']

export type ApiProblem = Problem & { retryAfterSec?: number }

export type RegisterRequest = components['schemas']['RegisterRequest']

export type ResendRequest = components['schemas']['ResendRequest']

export type ConfirmRegistrationRequest = components['schemas']['ConfirmRegistrationRequest']

export type SetPasswordRequest = components['schemas']['SetPasswordRequest']

export type ConfirmRegistrationResponse =
  operations['confirmRegistration']['responses'][200]['content']['application/json']

export type LoginRequest = components['schemas']['LoginRequest']

export type RefreshRequest = components['schemas']['RefreshRequest']

export type LogoutRequest = components['schemas']['LogoutRequest']

export type PasswordResetRequest = components['schemas']['PasswordResetRequest']

export type PasswordResetConfirmRequest = components['schemas']['PasswordResetConfirmRequest']

export type TokenPair = components['schemas']['TokenPair']

export type PublicUser = components['schemas']['PublicUser']

export type LoginResponse = operations['login']['responses'][200]['content']['application/json']

const BASE_URL = '/api/v1'

function isProblem(value: unknown): value is Problem {
  return (
    typeof value === 'object' &&
    value !== null &&
    'title' in value &&
    'status' in value &&
    typeof value.title === 'string' &&
    typeof value.status === 'number'
  )
}

export async function toApiProblem(response: Response): Promise<ApiProblem> {
  const parsed: unknown = await response.json().catch(() => null)
  const problem: ApiProblem = isProblem(parsed)
    ? parsed
    : {
        title: response.statusText === '' ? `HTTP ${response.status}` : response.statusText,
        status: response.status,
      }
  const retryAfter = response.headers.get('Retry-After')
  if (retryAfter !== null) {
    const seconds = Number(retryAfter)
    if (Number.isFinite(seconds)) problem.retryAfterSec = seconds
  }
  return problem
}

async function post(path: string, body: unknown): Promise<Response> {
  const response = await fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, application/problem+json',
    },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return response
}

export async function register(body: RegisterRequest): Promise<void> {
  await post('/auth/register', body)
}

export async function resendRegistrationEmail(body: ResendRequest): Promise<void> {
  await post('/auth/register/resend', body)
}

export async function confirmRegistration(
  body: ConfirmRegistrationRequest,
): Promise<ConfirmRegistrationResponse> {
  const response = await post('/auth/register/confirm', body)
  return (await response.json()) as ConfirmRegistrationResponse
}

export async function setPassword(body: SetPasswordRequest): Promise<void> {
  await post('/auth/register/password', body)
}

export async function login(body: LoginRequest): Promise<LoginResponse> {
  const response = await post('/auth/login', body)
  return (await response.json()) as LoginResponse
}

export async function refreshTokens(body: RefreshRequest): Promise<TokenPair> {
  const response = await post('/auth/refresh', body)
  return (await response.json()) as TokenPair
}

export async function requestPasswordReset(body: PasswordResetRequest): Promise<void> {
  await post('/auth/password-reset', body)
}

export async function confirmPasswordReset(body: PasswordResetConfirmRequest): Promise<void> {
  await post('/auth/password-reset/confirm', body)
}

async function authedPost(path: string, body: unknown): Promise<Response> {
  const response = await apiFetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, application/problem+json',
    },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return response
}

export async function logout(body: LogoutRequest): Promise<void> {
  await authedPost('/auth/logout', body)
}

export async function getCurrentUser(): Promise<PublicUser> {
  const response = await apiFetch('/users/me', {
    headers: { Accept: 'application/json, application/problem+json' },
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return (await response.json()) as PublicUser
}
