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

export type DeliveryAckItem = components['schemas']['DeliveryAckItem']

export type DeliveryAckRequest = components['schemas']['DeliveryAckRequest']

export type SyncCursor = components['schemas']['SyncCursor']

export type SyncRequest = components['schemas']['SyncRequest']

export type SyncChatDelta = components['schemas']['SyncChatDelta']

export type SyncResponse = components['schemas']['SyncResponse']

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

/**
 * №15 `GET /chats/{chatId}/messages?after=` (005): ascending catch-up
 * page `(after, after+limit]` by `seq ASC` — `nextAfter` is the last
 * record's `seq` and is absent when nothing newer remains; an empty
 * page on the boundary is a valid «caught up» response
 * (sync-protocol.md §4).
 */
export async function listMessagesAfter(
  chatId: string,
  after: number,
  limit?: number,
): Promise<MessagePage> {
  const query = new URLSearchParams({ after: String(after) })
  if (limit !== undefined) query.set('limit', String(limit))
  const path = `/chats/${encodeURIComponent(chatId)}/messages?${query.toString()}`
  const response = await authedRequest(path, 'GET')
  return (await response.json()) as MessagePage
}

/**
 * №25 `POST /users/me/delivery-ack`: the only mover of the caller's
 * delivery position (FR-001) — batched monotonic (GREATEST) advance,
 * atomic (any rejected item rejects the whole batch without partial
 * effects); `204` with no body, safe to retry idempotently
 * (sync-protocol.md §2).
 */
export async function deliveryAck(acks: DeliveryAckItem[]): Promise<void> {
  const body: DeliveryAckRequest = { acks }
  await authedRequest('/users/me/delivery-ack', 'POST', body)
}

/**
 * №26 `POST /users/me/sync`: catch-up delta for chats with undelivered
 * messages (US1) — client cursors act as the lower bound of the server
 * position; the response carries per-chat pages, server counters and
 * self-heal hints (`truncatedUpToSeq`, `desynced`/`serverUpToSeq`),
 * computed in a single read snapshot (sync-protocol.md §3). The
 * operation itself never moves the delivery position (only №25 does).
 * Unspecified limits fall back to the contract defaults (20 chats /
 * 50 messages — api-contract.md §1).
 */
export async function sync(
  cursors: SyncCursor[],
  chatLimit: number = 20,
  messageLimit: number = 50,
): Promise<SyncResponse> {
  const body: SyncRequest = { cursors, chatLimit, messageLimit }
  const response = await authedRequest('/users/me/sync', 'POST', body)
  return (await response.json()) as SyncResponse
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

/**
 * №14 `DELETE /chats/{chatId}`: per-user removal (FR-021) — history is
 * hidden behind the `deleted_up_to_seq = last_seq` watermark only for
 * the caller (`hidden = true`), the peer keeps everything; idempotent
 * `204`. A new incoming message returns the chat to the list WITHOUT
 * the old history. The client drops its local outbox records of the
 * chat together with the deletion (T060).
 */
export async function deleteChat(chatId: string): Promise<void> {
  await authedRequest(`/chats/${encodeURIComponent(chatId)}`, 'DELETE')
}

/**
 * №23 `PUT /users/{userId}/block`: one-way block owned by the blocker
 * (FR-020). Idempotent `204`; while active both send directions are
 * refused (`403 chat_blocked_by_you` / `403 you_are_blocked`), the
 * blocker's badge freezes and their read marks are not published.
 */
export async function blockUser(userId: string): Promise<void> {
  await authedRequest(`/users/${encodeURIComponent(userId)}/block`, 'PUT')
}

/**
 * №24 `DELETE /users/{userId}/block`: removes the caller's block;
 * idempotent `204` — sending, read marks, the badge and read events
 * resume without losing history (FR-020).
 */
export async function unblockUser(userId: string): Promise<void> {
  await authedRequest(`/users/${encodeURIComponent(userId)}/block`, 'DELETE')
}
