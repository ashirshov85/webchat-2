import { toApiProblem } from './auth'
import type { PublicUser } from './auth'
import { apiFetch } from './client'
import type { components } from './schema'

export type EnsureChatRequest = components['schemas']['EnsureChatRequest']

export type ChatView = components['schemas']['ChatView']

export type ChatListItem = components['schemas']['ChatListItem']

export type ContactView = components['schemas']['ContactView']

export type Message = components['schemas']['Message']

export type MessagePage = components['schemas']['MessagePage']

export type SendMessageRequest = components['schemas']['SendMessageRequest']

export type ReadRequest = components['schemas']['ReadRequest']

export type MessageCreatedEvent = components['schemas']['MessageCreatedEvent']

export type ChatReadEvent = components['schemas']['ChatReadEvent']

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

export async function ensureChat(body: EnsureChatRequest): Promise<ChatView> {
  const response = await authedRequest('/chats/ensure', 'POST', body)
  return (await response.json()) as ChatView
}

/**
 * №12 `GET /chats`: the caller's dialog list in one request — the
 * server-side aggregates (peer, last visible message, unread badge,
 * `blockedByMe`) already carry everything the «Чаты» panel renders
 * (FR-013/014); ordering by the last visible message's `createdAt`
 * DESC is server-owned, «99+» is a client-side render decision.
 */
export async function listChats(): Promise<ChatListItem[]> {
  const response = await authedRequest('/chats', 'GET')
  const body = (await response.json()) as { chats: ChatListItem[] }
  return body.chats
}

export async function getChat(chatId: string): Promise<ChatView> {
  const response = await authedRequest(`/chats/${encodeURIComponent(chatId)}`, 'GET')
  return (await response.json()) as ChatView
}

export async function sendMessage(chatId: string, body: SendMessageRequest): Promise<Message> {
  const response = await authedRequest(
    `/chats/${encodeURIComponent(chatId)}/messages`,
    'POST',
    body,
  )
  return (await response.json()) as Message
}

/**
 * №17 `POST /chats/{chatId}/read`: advances the caller's read watermark
 * (US4, FR-010). Idempotent and monotonic server-side; `204` — the
 * mark is a background best-effort update with no response body.
 */
export async function markChatRead(chatId: string, upToSeq: number): Promise<void> {
  await authedRequest(`/chats/${encodeURIComponent(chatId)}/read`, 'POST', { upToSeq })
}

export async function listMessages(
  chatId: string,
  options?: { before?: number; limit?: number },
): Promise<MessagePage> {
  const query = new URLSearchParams()
  if (options?.before !== undefined) query.set('before', String(options.before))
  if (options?.limit !== undefined) query.set('limit', String(options.limit))
  const queryString = query.toString()
  const queryPart = queryString === '' ? '' : `?${queryString}`
  const path = `/chats/${encodeURIComponent(chatId)}/messages${queryPart}`
  const response = await authedRequest(path, 'GET')
  return (await response.json()) as MessagePage
}

/** №20 `sort` parameter: the alphabetical ordering is server-owned (FR-015). */
export type ContactSort = 'login' | 'email'

/**
 * №19 `GET /users/search?query=`: exact full email OR full login match,
 * case-insensitive (`@` in the query → email, otherwise username); the
 * answer is 0..1 users — an empty list is a valid «no match» (FR-016).
 */
export async function searchUsers(query: string): Promise<PublicUser[]> {
  const search = new URLSearchParams({ query })
  const response = await authedRequest(`/users/search?${search.toString()}`, 'GET')
  const body = (await response.json()) as components['schemas']['UsersSearchResponse']
  return body.users
}

/**
 * №20 `GET /contacts?sort=login|email`: the caller's contacts in the
 * server-side case-insensitive alphabetical order of the chosen field
 * (FR-015) — the client switches the parameter, never re-sorts.
 */
export async function listContacts(sort: ContactSort = 'login'): Promise<ContactView[]> {
  const search = new URLSearchParams({ sort })
  const response = await authedRequest(`/contacts?${search.toString()}`, 'GET')
  const body = (await response.json()) as components['schemas']['ContactsResponse']
  return body.contacts
}

/**
 * №21 `POST /contacts {userId}`: `201` for a fresh contact, `200` with
 * the existing one when already added (no duplicate is created,
 * quickstart §3.5.4); self → `422 self_forbidden`.
 */
export async function addContact(userId: string): Promise<ContactView> {
  const response = await authedRequest('/contacts', 'POST', { userId })
  return (await response.json()) as ContactView
}

/**
 * №22 `DELETE /contacts/{userId}`: idempotent `204` (a missing contact
 * is also `204`); the pair chat and its history are NOT touched
 * (FR-017) — only the address book entry disappears.
 */
export async function removeContact(userId: string): Promise<void> {
  await authedRequest(`/contacts/${encodeURIComponent(userId)}`, 'DELETE')
}
