import type { components, operations } from './schema'

export type Problem = components['schemas']['Problem']

export type ApiProblem = Problem & { retryAfterSec?: number }

export type RegisterRequest = components['schemas']['RegisterRequest']

export type ResendRequest = components['schemas']['ResendRequest']

export type ConfirmRegistrationRequest = components['schemas']['ConfirmRegistrationRequest']

export type SetPasswordRequest = components['schemas']['SetPasswordRequest']

export type ConfirmRegistrationResponse =
  operations['confirmRegistration']['responses'][200]['content']['application/json']

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

async function toApiProblem(response: Response): Promise<ApiProblem> {
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
