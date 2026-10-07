/**
 * Perf-scale fixture dataset (feature 008, T069; SC-010/SC-011).
 *
 * The Aethergram demo of fixtures/aethergram.ts is a fidelity set of
 * ~8 chats × 3–8 messages — far below the budgets the spec
 * instrument-measures: SC-010 wants a feed that stays smooth with
 * 1000+ MESSAGES in the loaded history, SC-011 a sidebar that stays
 * smooth with 200+ CHATS. This module synthesizes that scale
 * deterministically and serves it through the very same interceptor
 * (installDatasetApi, fixtures/api.ts) — the app code, the boot
 * harness and the №12/№14/№36 call patterns are untouched, so the
 * measurement exercises the production render paths (React.memo rows,
 * content-visibility, №14 pagination through loadOlder).
 *
 * Dataset shape (everything deterministic, no RNG):
 *  * the «marathon» direct chat — 1200 messages spaced 90 s apart
 *    ending at the demo instant 19:00 +03:00 of 19 сентября 2026, so
 *    the loaded history spans several calendar days and the feed
 *    renders real `.date-divider`s between pages; №14 answers are
 *    contract-shaped windows — ≤ 50 messages DESC with `nextBefore`,
 *    `nextBefore` absent once the page reaches seq 1 (history
 *    exhausted), exactly what useChatMessages/loadOlder consumes;
 *  * 220 «guest» direct chats (sidebar scale of SC-011) with 1–3
 *    messages each, unique usernames `Aether Guest 00XX`, last
 *    messages strictly ordered inside the demo day so the №12 order
 *    (server-owned, lastMessage.createdAt DESC) is total and stable;
 *  * presence №36, contacts №20, search №19 answer deterministically;
 *    the №18 stream is aborted by the shared interceptor, so the page
 *    converges through its initial REST loads only.
 */
import type {
  ChatListItem,
  ChatView,
  ContactView,
  Message,
  MessagePage,
} from '../../../src/api/chats'
import type { PublicUser, TokenPair } from '../../../src/api/auth'

/** SC-011 sidebar scale: 200+ chats (guests + the marathon dialog). */
export const GUEST_CHAT_COUNT = 220
/** SC-010 feed scale: 1000+ messages in the loaded history of one chat. */
export const MARATHON_MESSAGE_COUNT = 1200
/** №14 page size the contract caps (`MessagePage.messages` maxItems 50). */
const PAGE_SIZE = 50

export const PERF_CHAT_COUNT = GUEST_CHAT_COUNT + 1
export const MARATHON_PEER_NAME = 'Marathon Engineer'
export const PERF_REFRESH_TOKEN = 'aethergram.perf.fixture.refresh-token'

/** The demo instant everything counts back from: 19:00 +03:00, 19.09.2026. */
const DEMO_EPOCH_MS = Date.parse('2026-09-19T19:00:00.000+03:00')
const MSK_OFFSET_MS = 3 * 60 * 60 * 1000

/** ISO-8601 +03:00 string of a fixed instant (fixture times stay human-readable ЧЧ:ММ). */
function isoAt(msSinceEpoch: number): string {
  return new Date(msSinceEpoch + MSK_OFFSET_MS).toISOString().replace('Z', '+03:00')
}

function userId(suffix: string): string {
  return `10000000-0000-4000-8000-0000000000${suffix}`
}

function chatId(suffix: string): string {
  return `20000000-0000-4000-8000-0000000000${suffix}`
}

function messageId(chatSuffix: string, seq: number): string {
  return `40000000-0000-4000-8000-${chatSuffix}${String(seq).padStart(6, '0')}0000`
}

function hexSuffix(index: number): string {
  return index.toString(16).padStart(2, '0')
}

const ME: PublicUser = {
  id: userId('00'),
  username: 'ada',
  email: 'ada.lovelace@aethergram.io',
  status: 'active',
  createdAt: '2026-07-01T12:00:00.000+03:00',
}

const tokenPair: TokenPair = {
  accessToken: 'aethergram.perf.fixture.access-token',
  refreshToken: PERF_REFRESH_TOKEN,
  tokenType: 'Bearer',
  expiresInSec: 300,
}

const MARATHON_CHAT_SUFFIX = 'ff'
const MARATHON_CHAT_ID = chatId(MARATHON_CHAT_SUFFIX)
const MARATHON_PEER: PublicUser = {
  id: userId('fe'),
  username: MARATHON_PEER_NAME,
  email: 'marathon.engineer@aethergram.io',
  status: 'active',
  createdAt: '2026-07-01T12:00:00.000+03:00',
}

const MARATHON_TEXTS = [
  'The pressure gauge holds steady at six atmospheres.',
  'Recalibrated the aetheric condenser — the readings are nominal again.',
  'Brass fittings arrived from the guild workshop; quality is superb.',
  'Meet me by the telegraph office at half past seven.',
  'The observatory dome rotates without a single creak now.',
  'Aether currents above the valley are unusually calm tonight.',
  'Chapter twelve of the engineering manual covers governor valves.',
  'Steam carriage no. 4 departs from the northern platform.',
  'Copper wiring for the signal lamps is ready for inspection.',
  'Your last schematic was magnificent — the margins, the linework!',
]

const MARATHON_LONG_TEXT =
  'Field report, entry the hundred-and-tenth: the gondola envelope holds pressure through the ' +
  'night cycle, the trim valves answer within a second, and the aetheric lift gauges agree with ' +
  'the mercury barometers to within a hair. I have re-seated every gasket twice, lubricated the ' +
  'governor linkage with the good oil from Manchester, and pinned the rotation schedule to the ' +
  'notice board by the stairwell. Next stop: the mountain station, weather permitting.'

function buildMarathonMessages(): Message[] {
  const messages: Message[] = []
  for (let seq = 1; seq <= MARATHON_MESSAGE_COUNT; seq += 1) {
    const outgoing = seq % 2 === 0
    const long = seq % 10 === 0
    messages.push({
      id: messageId(MARATHON_CHAT_SUFFIX, seq),
      chatId: MARATHON_CHAT_ID,
      senderId: outgoing ? ME.id : MARATHON_PEER.id,
      text: long ? MARATHON_LONG_TEXT : (MARATHON_TEXTS[seq % MARATHON_TEXTS.length] as string),
      seq,
      // 90 s apart ending at the demo instant: the run spans ~30 h and
      // several calendar days, so real `.date-divider`s render in the feed.
      createdAt: isoAt(DEMO_EPOCH_MS - (MARATHON_MESSAGE_COUNT - seq) * 90_000),
    })
  }
  return messages
}

const marathonMessages = buildMarathonMessages()

const GUEST_TEXTS = [
  'The workshop opens at dawn tomorrow.',
  'Splendid! I shall bring the blueprints.',
  'Governor valves are in the third cabinet.',
  'Aether levels over the city are stable.',
]

interface GuestChat {
  readonly chatId: string
  readonly user: PublicUser
  readonly unread: number
  readonly messages: Message[]
  readonly presence: 'online' | 'offline'
}

function buildGuestChats(): GuestChat[] {
  const chats: GuestChat[] = []
  for (let index = 1; index <= GUEST_CHAT_COUNT; index += 1) {
    const suffix = hexSuffix(index)
    const user: PublicUser = {
      id: userId(suffix),
      username: `Aether Guest ${String(index).padStart(4, '0')}`,
      email: `guest.${String(index).padStart(4, '0')}@aethergram.io`,
      status: 'active',
      createdAt: '2026-07-01T12:00:00.000+03:00',
    }
    const messageCount = (index % 3) + 1
    // Last messages strictly 15 s apart from 18:00 down the demo day —
    // a total №12 order with no ties (the server owns it, FR-008).
    const lastAt = DEMO_EPOCH_MS - 3_600_000 - index * 15_000
    const messages: Message[] = []
    for (let k = 0; k < messageCount; k += 1) {
      const seq = k + 1
      const fromMe = k % 2 === 1
      messages.push({
        id: messageId(suffix, seq),
        chatId: chatId(suffix),
        senderId: fromMe ? ME.id : user.id,
        text: GUEST_TEXTS[(index + k) % GUEST_TEXTS.length] as string,
        seq,
        createdAt: isoAt(lastAt - (messageCount - 1 - k) * 70_000),
      })
    }
    chats.push({
      chatId: chatId(suffix),
      user,
      unread: index % 5 === 0 ? 3 : 0,
      messages,
      presence: index % 2 === 0 ? 'online' : 'offline',
    })
  }
  return chats
}

const guestChats = buildGuestChats()

const presenceByUserId = new Map<string, 'online' | 'offline'>([
  [MARATHON_PEER.id, 'online'],
  ...guestChats.map((chat) => [chat.user.id, chat.presence] as const),
])

/**
 * №14 window: `before`/no-cursor — the ≤ 50 newest messages strictly
 * below the cursor, DESC, with `nextBefore` = the oldest seq of the
 * page, absent when the page reaches seq 1 (history exhausted);
 * `after` — everything newer, ASC (the sync catch-up shape).
 */
function messagePageOf(
  messages: readonly Message[],
  options?: { before?: number; after?: number },
): MessagePage {
  const after = options?.after
  if (after !== undefined) {
    return { messages: messages.filter((message) => message.seq > after) }
  }
  const before = options?.before
  const upperBound = before === undefined ? Number.MAX_SAFE_INTEGER : before
  const windowAscending = messages.filter((message) => message.seq < upperBound).slice(-PAGE_SIZE)
  const oldest = windowAscending[0]
  if (oldest === undefined) {
    return { messages: [] }
  }
  const descending = [...windowAscending].reverse()
  const nextBefore = oldest.seq > 1 ? oldest.seq : undefined
  return { messages: descending, nextBefore }
}

const guestByChatId = new Map(guestChats.map((chat) => [chat.chatId, chat]))

function asDirectChatListItem(
  chatIdValue: string,
  peer: PublicUser,
  messages: readonly Message[],
  unread: number,
): ChatListItem {
  return {
    chatId: chatIdValue,
    type: 'direct',
    peer,
    lastMessage: messages[messages.length - 1] ?? null,
    unreadCount: unread,
    blockedByMe: false,
  }
}

const chatList: ChatListItem[] = [
  asDirectChatListItem(MARATHON_CHAT_ID, MARATHON_PEER, marathonMessages, 0),
  ...guestChats.map((chat) =>
    asDirectChatListItem(chat.chatId, chat.user, chat.messages, chat.unread),
  ),
].sort((left, right) => {
  const leftTime = left.lastMessage?.createdAt ?? ''
  const rightTime = right.lastMessage?.createdAt ?? ''
  return rightTime.localeCompare(leftTime)
})

/**
 * The perf-scale dataset served through installDatasetApi: same shape
 * as fixtures/aethergram.ts, SC-010/SC-011 scale (see the module
 * header). No groups exist in this set — the №28 handler 404s.
 */
export const PERF = {
  tokenPair,
  me: ME,
  chatList,
  chatView(chatIdValue: string): ChatView {
    if (chatIdValue === MARATHON_CHAT_ID) {
      return {
        chatId: chatIdValue,
        type: 'direct',
        peer: MARATHON_PEER,
        blockedByMe: false,
        // ✓/✓✓ mix over the loaded history: my messages below 1150 are
        // read by the peer, the newest fifty stay single-ticked.
        peerReadUpToSeq: 1150,
        myReadUpToSeq: MARATHON_MESSAGE_COUNT,
      }
    }
    const guest = guestByChatId.get(chatIdValue)
    if (guest === undefined) {
      throw new Error(`perf fixture: unknown chat ${chatIdValue}`)
    }
    return {
      chatId: chatIdValue,
      type: 'direct',
      peer: guest.user,
      blockedByMe: false,
      peerReadUpToSeq: 0,
      myReadUpToSeq: guest.messages.length - guest.unread,
    }
  },
  groupView(chatIdValue: string): never {
    throw new Error(`perf fixture: unknown group ${chatIdValue}`)
  },
  messagePage(chatIdValue: string, options?: { before?: number; after?: number }): MessagePage {
    if (chatIdValue === MARATHON_CHAT_ID) {
      return messagePageOf(marathonMessages, options)
    }
    const guest = guestByChatId.get(chatIdValue)
    if (guest === undefined) {
      throw new Error(`perf fixture: unknown chat ${chatIdValue}`)
    }
    return messagePageOf(guest.messages, options)
  },
  searchUsers(): PublicUser[] {
    return []
  },
  contacts(): ContactView[] {
    return []
  },
  presenceOf(userIdValue: string): 'online' | 'offline' | 'unknown' {
    return presenceByUserId.get(userIdValue) ?? 'unknown'
  },
  lastSeqOf(chatIdValue: string): number {
    if (chatIdValue === MARATHON_CHAT_ID) {
      return MARATHON_MESSAGE_COUNT
    }
    return guestByChatId.get(chatIdValue)?.messages.length ?? 0
  },
}
