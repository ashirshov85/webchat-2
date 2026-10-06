/**
 * Deterministic API surface for the visual suite (feature 008, T015;
 * research §B — the chosen «MSW-подобный стаб»).
 *
 * Every request of the messenger — REST and the SSE stream alike — goes
 * through same-origin `fetch` to `/api/v1/*` (api/client.ts, api/sse.ts),
 * so one `page.route` interception feeds the whole page without touching
 * application code. The default dataset is the Aethergram prototype demo
 * (fixtures/aethergram.ts); T069 reuses the interceptor for the perf-scale
 * set (fixtures/perf.ts) via `installDatasetApi`. Responses are static,
 * so repeated runs render identical pixels.
 *
 * Realtime strategy: the №18 event stream is aborted (never opens) —
 * useChatList/useChatMessages converge purely through their initial REST
 * loads and the page stays visually quiescent. The aborted stream retries
 * on the client's backoff, which triggers no data refetches (those run on
 * `onOpen` only) and renders nothing. `POST /auth/refresh` MUST succeed:
 * a failing refresh calls `notifySessionExpired()` and would navigate the
 * SPA to /login.
 */
import type { Page, Route } from '@playwright/test'
import { AETHERGRAM, AETHERGRAM_REFRESH_TOKEN } from './aethergram'
import type { ChatListItem, ChatView, ContactView, MessagePage } from '../../../src/api/chats'
import type { GroupView } from '../../../src/api/groups'
import type { PublicUser, TokenPair } from '../../../src/api/auth'

export { AETHERGRAM_REFRESH_TOKEN }

const API_PREFIX = '/api/v1'

/**
 * Dataset surface a spec can serve through `installDatasetApi` (T069:
 * the perf-scale set of fixtures/perf.ts rides the same interceptor;
 * aethergram.ts satisfies it verbatim). `messagePage` answers the
 * contract №14 shape — `before`/no-cursor pages are DESC windows with
 * `nextBefore`, `after` pages ASC (api-contract.md §1).
 */
export interface ApiDataset {
  readonly tokenPair: TokenPair
  readonly me: PublicUser
  readonly chatList: ChatListItem[]
  chatView(chatId: string): ChatView
  groupView(chatId: string): GroupView
  messagePage(chatId: string, options?: { before?: number; after?: number }): MessagePage
  searchUsers(query: string): PublicUser[]
  contacts(sort: string): ContactView[]
  presenceOf(userId: string): 'online' | 'offline' | 'unknown'
  lastSeqOf(chatId: string): number
}

function problem(title: string, status: number): { title: string; status: number } {
  return { title, status }
}

async function fulfillJson(route: Route, status: number, body: unknown): Promise<void> {
  await route.fulfill({
    status,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function fulfillNoContent(route: Route): Promise<void> {
  await route.fulfill({ status: 204 })
}

async function serveDataset(route: Route, dataset: ApiDataset): Promise<void> {
  const request = route.request()
  const url = new URL(request.url())
  const prefixAt = url.pathname.indexOf(API_PREFIX)
  const path = prefixAt === -1 ? url.pathname : url.pathname.slice(prefixAt + API_PREFIX.length)
  const method = request.method()

  if (method === 'POST' && path === '/auth/refresh') {
    return fulfillJson(route, 200, dataset.tokenPair)
  }
  if (method === 'GET' && path === '/users/me') {
    return fulfillJson(route, 200, dataset.me)
  }
  if (method === 'GET' && path === '/users/search') {
    const query = url.searchParams.get('query') ?? ''
    return fulfillJson(route, 200, { users: dataset.searchUsers(query) })
  }
  if (method === 'GET' && path === '/contacts') {
    const sort = url.searchParams.get('sort') ?? 'login'
    return fulfillJson(route, 200, { contacts: dataset.contacts(sort) })
  }
  if (method === 'GET' && path === '/users/me/presence') {
    const requested = (url.searchParams.get('userIds') ?? '').split(',').filter(Boolean)
    const items = requested.map((userId) => ({
      userId,
      status: dataset.presenceOf(userId),
      rev: 1,
    }))
    return fulfillJson(route, 200, { items })
  }
  if (path === '/users/me/presence/settings') {
    if (method === 'GET') {
      return fulfillJson(route, 200, { incognito: false })
    }
    if (method === 'PUT') {
      const body = (await request.postDataJSON()) as { incognito?: boolean }
      return fulfillJson(route, 200, { incognito: body.incognito === true })
    }
  }
  if (method === 'POST' && path === '/users/me/presence/heartbeat') {
    return fulfillNoContent(route)
  }
  if (method === 'GET' && path === '/users/me/events') {
    return route.abort()
  }
  if (method === 'POST' && path === '/users/me/sync') {
    return fulfillJson(route, 200, { chats: [], moreChats: false })
  }
  if (method === 'POST' && path === '/users/me/delivery-ack') {
    return fulfillNoContent(route)
  }
  if (method === 'GET' && path === '/chats') {
    return fulfillJson(route, 200, { chats: dataset.chatList })
  }

  const groupMatch = /^\/groups\/([^/]+)$/.exec(path)
  if (groupMatch !== null && method === 'GET') {
    const chatId = decodeURIComponent(groupMatch[1] ?? '')
    try {
      return fulfillJson(route, 200, dataset.groupView(chatId))
    } catch {
      return fulfillJson(route, 404, problem('group_not_found', 404))
    }
  }

  const chatMatch = /^\/chats\/([^/]+)$/.exec(path)
  if (chatMatch !== null && method === 'GET') {
    const chatId = decodeURIComponent(chatMatch[1] ?? '')
    try {
      return fulfillJson(route, 200, dataset.chatView(chatId))
    } catch {
      return fulfillJson(route, 404, problem('chat_not_found', 404))
    }
  }

  const messagesMatch = /^\/chats\/([^/]+)\/messages$/.exec(path)
  if (messagesMatch !== null) {
    const chatId = decodeURIComponent(messagesMatch[1] ?? '')
    if (method === 'GET') {
      const beforeParam = url.searchParams.get('before')
      const afterParam = url.searchParams.get('after')
      try {
        return fulfillJson(
          route,
          200,
          dataset.messagePage(chatId, {
            before: beforeParam !== null ? Number(beforeParam) : undefined,
            after: afterParam !== null ? Number(afterParam) : undefined,
          }),
        )
      } catch {
        return fulfillJson(route, 404, problem('chat_not_found', 404))
      }
    }
    if (method === 'POST') {
      try {
        const body = (await request.postDataJSON()) as { clientMessageId?: string; text?: string }
        const seq = dataset.lastSeqOf(chatId) + 1
        return fulfillJson(route, 200, {
          id: body.clientMessageId ?? `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
          chatId,
          senderId: dataset.me.id,
          text: body.text ?? '',
          seq,
          createdAt: '2026-09-19T19:00:00.000+03:00',
        })
      } catch {
        return fulfillJson(route, 404, problem('chat_not_found', 404))
      }
    }
  }

  const readMatch = /^\/chats\/([^/]+)\/read$/.exec(path)
  if (readMatch !== null && method === 'POST') {
    return fulfillNoContent(route)
  }

  return fulfillJson(route, 404, problem('aethergram-fixture: unmocked endpoint', 404))
}

/**
 * Feeds every `/api/v1` request of the page from the given dataset
 * (T069): the very interceptor the Aethergram suite runs on, opened
 * up for the perf-scale dataset of fixtures/perf.ts.
 */
export async function installDatasetApi(page: Page, dataset: ApiDataset): Promise<void> {
  await page.route('**/api/v1/**', (route) => serveDataset(route, dataset))
}

/** Feeds every `/api/v1` request of the page from the Aethergram dataset. */
export async function installAethergramApi(page: Page): Promise<void> {
  await installDatasetApi(page, AETHERGRAM)
}
