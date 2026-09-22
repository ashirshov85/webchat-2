import { toApiProblem } from './auth'
import { apiFetch } from './client'
import type { components } from './schema'

export type EnsureChatRequest = components['schemas']['EnsureChatRequest']

export type ChatView = components['schemas']['ChatView']

export type ChatListItem = components['schemas']['ChatListItem']

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
