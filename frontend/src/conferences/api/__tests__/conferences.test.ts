import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiProblem } from '../../../api/auth'

/**
 * 009, US1, T029 — API-клиент тега conferences (T022, №44–№47): пути и
 * тела строго по api-contract §2, ответы — сгенерированные типы; отказ —
 * типизированный Problem через toApiProblem (ветвление UX по коду, не по
 * HTTP-статусу); conferenceErrorCode достаёт единственный код из
 * `errors: {field: [code]}`.
 */

const http = vi.hoisted(() => ({
  fetch: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(),
  problem: vi.fn<(response: Response) => Promise<ApiProblem>>(),
}))

vi.mock('../../../api/client', () => ({ apiFetch: http.fetch }))
vi.mock('../../../api/auth', () => ({ toApiProblem: http.problem }))

import {
  conferenceErrorCode,
  createConference,
  getConference,
  getConferenceMe,
  isConferenceProblem,
  joinConference,
} from '../conferences'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function problem(status: number, code: string): ApiProblem {
  return { title: `HTTP ${status}`, status, errors: { conference: [code] } }
}

/** Разобранный вызов apiFetch: [path, init]. */
function call(index = 0): [string, RequestInit | undefined] {
  const invocation = http.fetch.mock.calls[index]
  if (invocation === undefined) {
    throw new Error(`apiFetch вызов №${index} не производился`)
  }
  return [invocation[0], invocation[1]]
}

describe('conferences API client (009 US1 T022/T029)', () => {
  beforeEach(() => {
    http.fetch.mockReset()
    http.problem.mockReset()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('№44 createConference: POST /chats/{id}/conferences с телом {mode}', async () => {
    const view = { id: 'c-1', chatId: 'chat-1', status: 'created' }
    http.fetch.mockResolvedValue(jsonResponse(201, view))

    const answer = await createConference('chat-1', 'video')

    expect(answer).toEqual(view)
    const [path, init] = call()
    expect(path).toBe('/chats/chat-1/conferences')
    expect(init?.method).toBe('POST')
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json')
    expect(init?.body).toBe('{"mode":"video"}')
  })

  it('№45 getConference: GET /conferences/{id}', async () => {
    http.fetch.mockResolvedValue(jsonResponse(200, { id: 'c-1', status: 'ended' }))

    const answer = await getConference('c-1')

    expect(answer.status).toBe('ended')
    const [path, init] = call()
    expect(path).toBe('/conferences/c-1')
    expect(init?.method).toBe('GET')
    expect(init?.body).toBeUndefined()
  })

  it('№46 getConferenceMe: GET /conferences/{id}/me', async () => {
    http.fetch.mockResolvedValue(
      jsonResponse(200, {
        role: 'participant',
        micRestricted: false,
        cameraRestricted: false,
        screenRestricted: false,
        speechRestricted: false,
      }),
    )

    const answer = await getConferenceMe('c-1')

    expect(answer.role).toBe('participant')
    expect(call()[0]).toBe('/conferences/c-1/me')
  })

  it('№47 joinConference: POST /conferences/{id}/join без тела', async () => {
    const ticket = { token: 'jwt', wsUrl: 'wss://media', identity: 'u:1:c', name: 'a' }
    http.fetch.mockResolvedValue(jsonResponse(200, ticket))

    const answer = await joinConference('c-1')

    expect(answer.token).toBe('jwt')
    const [path, init] = call()
    expect(path).toBe('/conferences/c-1/join')
    expect(init?.method).toBe('POST')
    expect(init?.body).toBeUndefined()
  })

  it('id кодируется encodeURIComponent — неожиданные символы пути исключены', async () => {
    http.fetch.mockResolvedValue(jsonResponse(200, {}))
    await getConference('c 1/evil')
    expect(call()[0]).toBe('/conferences/c%201%2Fevil')
  })

  it('отказ — типизированный Problem: toApiProblem(response) и бросок', async () => {
    const response = jsonResponse(409, {})
    http.fetch.mockResolvedValue(response)
    const problem409 = problem(409, 'conference_full')
    http.problem.mockResolvedValue(problem409)

    await expect(joinConference('c-1')).rejects.toEqual(problem409)
    expect(http.problem).toHaveBeenCalledWith(response)
  })

  it('conferenceErrorCode: единственный код из errors; не-Problem — null', () => {
    expect(conferenceErrorCode(problem(409, 'conference_ended'))).toBe('conference_ended')
    expect(conferenceErrorCode(problem(403, 'conference_forbidden'))).toBe('conference_forbidden')
    expect(conferenceErrorCode(new Error('network'))).toBeNull()
    expect(conferenceErrorCode(null)).toBeNull()
    // несколько полей — первый код детерминированно
    expect(
      conferenceErrorCode({
        status: 400,
        errors: { mode: ['invalid_mode'], id: ['invalid_uuid'] },
      }),
    ).toBe('invalid_mode')
  })

  it('isConferenceProblem: guard по числовому статусу', () => {
    expect(isConferenceProblem(problem(409, 'conference_ended'))).toBe(true)
    expect(isConferenceProblem(new Error('network'))).toBe(false)
  })
})
