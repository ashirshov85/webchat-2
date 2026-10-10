/**
 * T077 (tasks.md 008, Phase 9 bug 1): the two-user e2e regression of
 * the reported presence asymmetry — «A открывает → B видит online без
 * рефреша; A уходит → B видит offline».
 *
 * Two real browser contexts (the subject ALICE and the observer BOB)
 * ride the visual-suite harness (`vite preview` build + `page.route`
 * interception, research §B) with a tiny two-user dataset and a shared
 * in-spec «presence backend» that models the 007 contracts honestly:
 *
 *  * №18 stream (`GET /users/me/events`): every connect is answered
 *    with the SSE opening frames (`retry: 3000` + `connected
 *    {connectionId}`); the observer's handler parks until the server
 *    queues a `presence.updated` frame, then delivers it and cycles —
 *    the client's own reconnect loop re-opens the stream, exactly the
 *    production at-most-once cadence;
 *  * the subject's FIRST stream registration publishes `online` with a
 *    fresh strictly-greater rev to the audience (the
 *    `PresenceService.onConnectionOpened` → fanout №18 trace of T074);
 *    her SILENT departure (page closed, no disconnect frame) lapses
 *    the registration by TTL — the server publishes `offline` with a
 *    greater rev (the SC-003 self-expiry path);
 *  * №36 snapshot answers the CURRENT published {status, rev} — the
 *    designated heal of 007 FR-003 that the T075 surface refetch
 *    relies on; №37 heartbeats answer 204.
 *
 * The assertion surface is the observer's 1:1 dialog header
 * (`.status-row .status-txt` — «В сети» / «Был в сети — давно»: the
 * 008a US3 offline label of T048 replaced the 008-era «офлайн»; an
 * unknown entry renders no status text at all): the page is NEVER
 * reloaded across the whole scenario; convergence must happen
 * through the live №18 frame and/or the №36 heal alone.
 */
import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'
import type { ApiDataset } from './fixtures/api'
import { AETHERGRAM_REFRESH_TOKEN, installDatasetApi } from './fixtures/api'
import type { ChatListItem, ChatView, MessagePage } from '../../src/api/chats'
import type { PublicUser, TokenPair } from '../../src/api/auth'

/** The demo instant of the fixture messages (19:00 +03:00, 19.09.2026). */
const DEMO_EPOCH_MS = Date.parse('2026-09-19T19:00:00.000+03:00')

const ALICE: PublicUser = {
  id: '10000000-0000-4000-8000-0000000000a1',
  username: 'alice.engineer',
  email: 'alice@aethergram.io',
  status: 'active',
  createdAt: '2026-07-01T12:00:00.000+03:00',
}

const BOB: PublicUser = {
  id: '10000000-0000-4000-8000-0000000000b2',
  username: 'bob.observer',
  email: 'bob@aethergram.io',
  status: 'active',
  createdAt: '2026-07-01T12:00:00.000+03:00',
}

const CHAT_ID = '20000000-0000-4000-8000-0000000000c3'
const MESSAGES: MessagePage = {
  messages: [
    {
      id: '40000000-0000-4000-8000-000000000001',
      chatId: CHAT_ID,
      senderId: ALICE.id,
      text: 'The aether lamp is lit — join me in the chat.',
      seq: 1,
      createdAt: new Date(DEMO_EPOCH_MS - 120_000).toISOString(),
    },
    {
      id: '40000000-0000-4000-8000-000000000002',
      chatId: CHAT_ID,
      senderId: BOB.id,
      text: 'On my way up the tower.',
      seq: 2,
      createdAt: new Date(DEMO_EPOCH_MS - 60_000).toISOString(),
    },
  ],
}

function tokenPair(refreshToken: string): TokenPair {
  return {
    accessToken: `access.${refreshToken}`,
    refreshToken,
    tokenType: 'Bearer',
    expiresInSec: 300,
  }
}

/** The two-user dataset: one direct chat between the pair, nothing else. */
function twoUserDataset(me: PublicUser, peer: PublicUser): ApiDataset {
  const chatListItem: ChatListItem = {
    chatId: CHAT_ID,
    type: 'direct',
    peer,
    lastMessage: MESSAGES.messages[1] ?? null,
    unreadCount: 0,
    blockedByMe: false,
  }
  const chatView: ChatView = {
    chatId: CHAT_ID,
    type: 'direct',
    peer,
    blockedByMe: false,
    peerReadUpToSeq: 2,
    myReadUpToSeq: 2,
  }
  return {
    tokenPair: tokenPair(AETHERGRAM_REFRESH_TOKEN),
    me,
    chatList: [chatListItem],
    chatView: (chatId) => {
      if (chatId !== CHAT_ID) {
        throw new Error(`presence e2e fixture: unknown chat ${chatId}`)
      }
      return chatView
    },
    groupView: (chatId): never => {
      throw new Error(`presence e2e fixture: unknown group ${chatId}`)
    },
    messagePage: (chatId, options) => {
      if (chatId !== CHAT_ID) {
        throw new Error(`presence e2e fixture: unknown chat ${chatId}`)
      }
      const after = options?.after
      if (after !== undefined) {
        return { messages: MESSAGES.messages.filter((message) => message.seq > after) }
      }
      return MESSAGES
    },
    searchUsers: () => [],
    contacts: () => [],
    presenceOf: () => 'unknown',
    lastSeqOf: () => MESSAGES.messages.length,
  }
}

/**
 * The shared «presence backend» of the scenario: the published
 * {status, rev} of the subject plus the observer's №18 frame queue.
 */
class PresenceServer {
  private status: 'online' | 'offline' = 'offline'
  private rev = 5
  private readonly observerFrames: string[] = []

  /** The current №36 truth — the designated heal of 007 FR-003. */
  snapshot(userId: string): { userId: string; status: 'online' | 'offline'; rev: number } {
    return { userId, status: this.status, rev: this.rev }
  }

  /** The subject's first №18 registration publishes `online` (T074 trace). */
  subjectConnected(subjectId: string): void {
    this.publish(subjectId, 'online')
  }

  /** A silent departure lapses by TTL → the SC-003 offline publication. */
  subjectExpired(subjectId: string): void {
    this.publish(subjectId, 'offline')
  }

  private publish(subjectId: string, status: 'online' | 'offline'): void {
    this.status = status
    this.rev += 1
    this.observerFrames.push(
      `event: presence.updated\ndata: ${JSON.stringify({ userId: subjectId, status, rev: this.rev })}\n\n`,
    )
  }

  /**
   * Waits up to [timeoutMs] for a queued observer frame; returns
   * everything queued so far (possibly nothing — a silent keep-alive
   * cycle, the client reconnects on its own backoff).
   */
  async drainObserverFrames(timeoutMs: number): Promise<string> {
    const deadline = Date.now() + timeoutMs
    while (this.observerFrames.length === 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 25))
    }
    return this.observerFrames.splice(0).join('')
  }
}

const SSE_HEADERS = { 'content-type': 'text/event-stream' }

/** The №18 opening frames every connect must answer (presence-events.md §1). */
function sseOpening(connectionId: string): string {
  return `retry: 3000\n\nevent: connected\ndata: {"connectionId":"${connectionId}"}\n\n`
}

/** The observer's stream: opening frames + whatever №18 frames the server queued. */
function installObserverStream(page: Page, server: PresenceServer): void {
  void page.route('**/api/v1/users/me/events', async (route: Route) => {
    const frames = await server.drainObserverFrames(2_500)
    await route.fulfill({
      status: 200,
      headers: SSE_HEADERS,
      body: sseOpening(crypto.randomUUID()) + frames,
    })
  })
}

/** The subject's stream: the FIRST registration publishes `online` to the audience. */
function installSubjectStream(page: Page, server: PresenceServer, subjectId: string): void {
  let registered = false
  void page.route('**/api/v1/users/me/events', async (route: Route) => {
    if (!registered) {
      registered = true
      server.subjectConnected(subjectId)
    }
    await route.fulfill({
      status: 200,
      headers: SSE_HEADERS,
      body: sseOpening(crypto.randomUUID()),
    })
  })
}

/** №36 answered from the live server state (registered AFTER the dataset to take precedence). */
function installPresenceSnapshot(page: Page, server: PresenceServer): void {
  // The glob would miss the `?userIds=` query — №36 is matched by regex;
  // the heartbeat path (`…/presence/heartbeat`) stays with the dataset.
  void page.route(/\/api\/v1\/users\/me\/presence(\?|$)/, async (route: Route) => {
    const url = new URL(route.request().url())
    const requested = (url.searchParams.get('userIds') ?? '').split(',').filter(Boolean)
    await route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ items: requested.map((userId) => server.snapshot(userId)) }),
    })
  })
}

/** Boots a fresh context of the pair on the two-user dataset (research §B boot recipe). */
async function bootUser(page: Page, me: PublicUser, peer: PublicUser): Promise<void> {
  await page.addInitScript((refreshToken) => {
    try {
      window.localStorage.clear()
    } catch {
      // storage unavailable — the token write below degrades the same way
    }
    window.localStorage.setItem('webchat.auth.refreshToken', refreshToken)
  }, AETHERGRAM_REFRESH_TOKEN)
  await installDatasetApi(page, twoUserDataset(me, peer))
}

test.describe('T077 bug 1 e2e — two users converge without a page refresh', () => {
  test('A opens → B sees «В сети» live; A leaves silently → B sees the offline label', async ({
    browser,
  }) => {
    const server = new PresenceServer()

    const observerContext = await browser.newContext()
    const observerPage = await observerContext.newPage()
    await bootUser(observerPage, BOB, ALICE)
    installPresenceSnapshot(observerPage, server)
    installObserverStream(observerPage, server)

    // BOB is in the chat with ALICE; her last №36 backfill captured the
    // pre-connect truth — offline rev 5 (ALICE has not opened the app yet).
    // ≤900px the catalogue rides the off-canvas drawer — the burger opens
    // it first (US5, the visual harness openChat recipe).
    await observerPage.goto('/')
    const burger = observerPage.getByRole('button', { name: 'Каталог чатов' })
    if (await burger.isVisible()) {
      await burger.click()
    }
    await observerPage.locator('.chat-item').first().click()
    const observerStatus = observerPage.locator('.chat-head .status-row .status-txt')
    // 008a US3 (T048): the offline label is «Был в сети — давно» —
    // the ALICE fixture discloses no `lastSeenAt`, the neutral
    // fallback of ui-behavior §3 (the two-user dataset answers №36
    // without the field).
    await expect(observerStatus).toHaveText('Был в сети — давно')

    // ALICE OPENS THE APP: her №18 registration publishes `online` rev 6
    // to the audience; BOB's page receives the frame (or the №36 heal)
    // and the header flips — the page is NEVER reloaded.
    const subjectContext = await browser.newContext()
    const subjectPage = await subjectContext.newPage()
    await bootUser(subjectPage, ALICE, BOB)
    installSubjectStream(subjectPage, server, ALICE.id)
    await subjectPage.goto('/')
    await expect(subjectPage.locator('.chat-item').first()).toBeVisible()

    await expect(observerStatus).toHaveText('В сети')

    // ALICE LEAVES SILENTLY (tab closed — no disconnect frame): the
    // registration lapses by TTL and the server publishes `offline`
    // rev 7; BOB converges the same way, still without a refresh.
    await subjectPage.close()
    await subjectContext.close()
    server.subjectExpired(ALICE.id)

    await expect(observerStatus).toHaveText('Был в сети — давно')

    await observerContext.close()
  })
})
