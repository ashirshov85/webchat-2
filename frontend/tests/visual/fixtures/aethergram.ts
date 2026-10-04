/**
 * Aethergram fixture dataset (feature 008, T015; research §B).
 *
 * The demo set of the normative prototype
 * `specs/008-chat-window-styling/design/chats.html` (§ДАННЫЕ) projected
 * onto the API 0.7.0 entities: same users, same chat catalogue, same
 * messages and ЧЧ:ММ times, same unread counters — so the implementation
 * renders the very same content the prototype baselines (T016) depict.
 *
 * Determinism rules:
 *  * every timestamp is a fixed ISO instant; message wall-clock times are
 *    `2026-09-19T{ЧЧ:ММ}+03:00` — 19 сентября, the demo day of the
 *    prototype's date divider, in the same year as the run so the long
 *    date format renders without a year (design-tokens §6);
 *  * ids are stable UUID-shaped constants derived from chat/user indexes;
 *  * chat order is the prototype's `seq` order — the server-owned №12
 *    order (lastMessage.createdAt DESC), reproduced by useChatList;
 *  * avatar colours/initials are NOT part of the API: the app derives
 *    them from usernames/title (T008), as does the comparison tolerance.
 */
import type {
  ChatListItem,
  ChatView,
  ContactView,
  Message,
  MessagePage,
} from '../../../src/api/chats'
import type { GroupView } from '../../../src/api/groups'
import type { PublicUser, TokenPair } from '../../../src/api/auth'

const DEMO_DAY = '2026-09-19'
const DEMO_OFFSET = '+03:00'
const FIXED_PAST_INSTANT = '2026-07-01T12:00:00.000+03:00'

function demoInstant(hhmm: string): string {
  return `${DEMO_DAY}T${hhmm}:00.000${DEMO_OFFSET}`
}

function userId(suffix: string): string {
  return `10000000-0000-4000-8000-0000000000${suffix}`
}

function chatId(suffix: string): string {
  return `20000000-0000-4000-8000-0000000000${suffix}`
}

function messageId(chatSuffix: string, seq: number): string {
  return `40000000-0000-4000-8000-${chatSuffix}${String(seq).padStart(2, '0')}00000000`
}

export const AETHERGRAM_REFRESH_TOKEN = 'aethergram.visual.fixture.refresh-token'

export const ME: PublicUser = {
  id: userId('00'),
  username: 'ada',
  email: 'ada.lovelace@aethergram.io',
  status: 'active',
  createdAt: FIXED_PAST_INSTANT,
}

const tokenPair: TokenPair = {
  accessToken: 'aethergram.visual.fixture.access-token',
  refreshToken: AETHERGRAM_REFRESH_TOKEN,
  tokenType: 'Bearer',
  expiresInSec: 300,
}

function demoUser(suffix: string, username: string, email: string): PublicUser {
  return { id: userId(suffix), username, email, status: 'active', createdAt: FIXED_PAST_INSTANT }
}

/**
 * USERS of the prototype: u1–u5 (chats), u6–u8 (search-only), u9–u13 (contacts without chats).
 *
 * T026: the API's only name field is `username` — to reproduce «те же
 * имена» of the prototype demo (research §B) the u1–u5 display names ride
 * it verbatim, so the list/header/feed surfaces render the very names the
 * T016 baselines depict (avatar colours stay derived, FR-024 — §E). The
 * login-style ids stay in the emails; u6–u13 keep logins (search/contacts
 * surfaces compare at fullscreen budgets later — US2+).
 */
const users: PublicUser[] = [
  demoUser('01', 'Alex Carter', 'alex.carter@aethergram.io'),
  demoUser('02', 'Maria Lopez', 'maria.lopez@aethergram.io'),
  demoUser('03', 'James Whitmore', 'james.whitmore@aethergram.io'),
  demoUser('04', 'Eleanor Pritchard', 'eleanor.pritchard@aethergram.io'),
  demoUser('05', 'Thomas Reed', 'thomas.reed@aethergram.io'),
  demoUser('06', 'hargrove', 'h.hargrove@aethergram.io'),
  demoUser('07', 'vhart', 'viola.hart@aethergram.io'),
  demoUser('08', 'cogsworth', 'n.cogsworth@aethergram.io'),
  demoUser('09', 'afinch', 'augustus.finch@aethergram.io'),
  demoUser('0a', 'bholloway', 'beatrice.holloway@aethergram.io'),
  demoUser('0b', 'cvane', 'cornelius.vane@aethergram.io'),
  demoUser('0c', 'dashworth', 'dorothea.ashworth@aethergram.io'),
  demoUser('0d', 'eblackwood', 'edmund.blackwood@aethergram.io'),
]

const userByUsername = new Map(users.map((user) => [user.username, user]))

/** Presence of the prototype demo (№36 snapshot): the `online: true` flags of USERS/contacts. */
const presenceByUserId = new Map<string, 'online' | 'offline'>([
  [userId('01'), 'online'],
  [userId('02'), 'online'],
  [userId('03'), 'offline'],
  [userId('04'), 'online'],
  [userId('05'), 'offline'],
  [userId('06'), 'offline'],
  [userId('07'), 'online'],
  [userId('08'), 'offline'],
  [userId('09'), 'offline'],
  [userId('0a'), 'online'],
  [userId('0b'), 'offline'],
  [userId('0c'), 'online'],
  [userId('0d'), 'offline'],
])

/** Contacts of the prototype: c1–c10 (u1–u5 + u9–u13). */
const contacts: ContactView[] = ['01', '02', '03', '04', '05', '09', '0a', '0b', '0c', '0d'].map(
  (suffix) => {
    const user = users.find((candidate) => candidate.id === userId(suffix))
    if (user === undefined) {
      throw new Error(`fixture: unknown contact user ${suffix}`)
    }
    return { user, createdAt: FIXED_PAST_INSTANT }
  },
)

type UserKey = 'alex' | 'maria' | 'james' | 'eleanor' | 'thomas' | 'hargrove'

const userKeyIds: Record<UserKey, string> = {
  alex: userId('01'),
  maria: userId('02'),
  james: userId('03'),
  eleanor: userId('04'),
  thomas: userId('05'),
  hargrove: userId('06'),
}

interface DemoMessage {
  readonly from: 'me' | 'them'
  readonly sender?: UserKey
  readonly text: string
  readonly time: string
  readonly read?: boolean
}

interface DemoChat {
  readonly suffix: string
  readonly kind: 'direct' | 'group'
  readonly peer?: UserKey
  readonly title?: string
  readonly myRole?: 'owner' | 'admin' | 'member'
  readonly members?: ReadonlyArray<readonly [UserKey, 'owner' | 'admin' | 'member']>
  readonly unread: number
  readonly messages: readonly DemoMessage[]
}

/** The prototype's `chats` array verbatim (order = ascending last-message time). */
const demoChats: readonly DemoChat[] = [
  {
    suffix: '01',
    kind: 'direct',
    peer: 'alex',
    unread: 0,
    messages: [
      { from: 'them', text: 'Hey! Are you free this evening?', time: '18:41' },
      {
        from: 'me',
        text: 'Hi Alex! Yes, I am free after seven.',
        time: '18:43',
        read: true,
      },
      {
        from: 'them',
        text: "We're planning to go to the old observatory. Want to join?",
        time: '18:44',
      },
      {
        from: 'me',
        text: 'The old observatory? Sounds intriguing! Count me in.',
        time: '18:45',
        read: true,
      },
      {
        from: 'them',
        text: "Splendid! We're meeting at 7 PM near the old clock tower.",
        time: '18:46',
      },
      { from: 'them', text: 'Bring a coat — it gets chilly up on the hill.', time: '18:47' },
      { from: 'me', text: 'Will do. See you at the tower!', time: '18:48' },
      { from: 'them', text: "Jolly good! It's going to be a night to remember.", time: '18:50' },
    ],
  },
  {
    suffix: '02',
    kind: 'direct',
    peer: 'maria',
    unread: 2,
    messages: [
      { from: 'them', text: '¡Hola! Have you seen the new airship designs?', time: '16:12' },
      { from: 'me', text: 'Not yet! The brass ones from the exhibition?', time: '16:15' },
      {
        from: 'them',
        text: "Exactly! Come by the workshop tomorrow — I'll show you everything.",
        time: '16:17',
      },
    ],
  },
  {
    suffix: '03',
    kind: 'direct',
    peer: 'james',
    unread: 0,
    messages: [
      { from: 'them', text: 'The automatons have arrived. All six of them.', time: '14:02' },
      { from: 'me', text: 'Already? Splendid news!', time: '14:05', read: true },
      {
        from: 'them',
        text: 'Come to the laboratory tonight — we must calibrate them before midnight.',
        time: '14:06',
      },
    ],
  },
  {
    suffix: 'a1',
    kind: 'group',
    title: 'Project Phoenix',
    myRole: 'owner',
    members: [
      ['maria', 'member'],
      ['james', 'member'],
      ['alex', 'member'],
    ],
    unread: 1,
    messages: [
      {
        from: 'them',
        sender: 'maria',
        text: 'The boiler tests are scheduled for Friday morning.',
        time: '12:20',
      },
      {
        from: 'them',
        sender: 'james',
        text: "Excellent. I'll bring the new pressure valves.",
        time: '12:24',
      },
      { from: 'them', sender: 'alex', text: 'Count me in for the test run!', time: '12:31' },
    ],
  },
  {
    suffix: '04',
    kind: 'direct',
    peer: 'eleanor',
    unread: 0,
    messages: [
      {
        from: 'them',
        text: "Darling, the tea society convenes at four o'clock sharp.",
        time: '11:30',
      },
      {
        from: 'me',
        text: 'I shall be there. Is Lord Ashworth attending?',
        time: '11:45',
        read: true,
      },
      { from: 'them', text: 'He promised. You know how he is with promises, dear.', time: '11:52' },
    ],
  },
  {
    suffix: '05',
    kind: 'direct',
    peer: 'thomas',
    unread: 0,
    messages: [
      {
        from: 'them',
        text: 'The gondola envelope arrived from Manchester this morning.',
        time: '09:58',
      },
      {
        from: 'me',
        text: 'Stupendous! Shall we begin the fitting on Friday?',
        time: '10:02',
        read: true,
      },
      { from: 'them', text: 'Friday it is. Inform the guild.', time: '10:04' },
    ],
  },
  {
    suffix: 'a2',
    kind: 'group',
    title: 'The Aether Society',
    myRole: 'member',
    members: [
      ['eleanor', 'owner'],
      ['hargrove', 'admin'],
      ['thomas', 'member'],
    ],
    unread: 3,
    messages: [
      {
        from: 'them',
        sender: 'eleanor',
        text: "Tonight's lecture: 'On the Nature of Aether Currents'.",
        time: '09:15',
      },
      {
        from: 'them',
        sender: 'hargrove',
        text: 'Do bring your notebooks, ladies and gentlemen.',
        time: '09:20',
      },
      { from: 'them', sender: 'thomas', text: 'A fascinating subject, as always.', time: '09:33' },
    ],
  },
  {
    suffix: 'a3',
    kind: 'group',
    title: 'Skyward Engineers',
    myRole: 'owner',
    members: [
      ['thomas', 'member'],
      ['maria', 'member'],
    ],
    unread: 0,
    messages: [
      {
        from: 'them',
        sender: 'thomas',
        text: 'The gondola frame is complete at last.',
        time: '08:40',
      },
      {
        from: 'me',
        text: 'Superb work! When do we attach the envelope?',
        time: '08:55',
        read: true,
      },
      {
        from: 'them',
        sender: 'thomas',
        text: 'Next week, once the fabric arrives from Manchester.',
        time: '09:01',
      },
    ],
  },
]

function chatIdOf(chat: DemoChat): string {
  return chatId(chat.suffix)
}

function peerUserOf(chat: DemoChat): PublicUser {
  const peerKey = chat.peer
  if (peerKey === undefined) {
    throw new Error(`fixture: direct chat ${chat.suffix} without peer`)
  }
  const user = users.find((candidate) => candidate.id === userKeyIds[peerKey])
  if (user === undefined) {
    throw new Error(`fixture: unknown peer key ${peerKey}`)
  }
  return user
}

function buildMessages(chat: DemoChat): Message[] {
  const id = chatIdOf(chat)
  return chat.messages.map((message, index): Message => {
    const seq = index + 1
    const senderId =
      message.from === 'me'
        ? ME.id
        : message.sender !== undefined
          ? userKeyIds[message.sender]
          : peerUserOf(chat).id
    return {
      id: messageId(chat.suffix, seq),
      chatId: id,
      senderId,
      text: message.text,
      seq,
      createdAt: demoInstant(message.time),
    }
  })
}

/** Highest seq of MY messages the peer(s) read — the ✓✓ watermark (0 — nothing of mine is read). */
function peerReadUpToSeq(chat: DemoChat): number {
  return chat.messages.reduce(
    (watermark, message, index) =>
      message.from === 'me' && message.read === true ? index + 1 : watermark,
    0,
  )
}

const chatByChatId = new Map(demoChats.map((chat) => [chatIdOf(chat), chat]))
const messagesByChatId = new Map(demoChats.map((chat) => [chatIdOf(chat), buildMessages(chat)]))

function groupMembers(chat: DemoChat): GroupView['members'] {
  const roster: GroupView['members'] = [
    { user: ME, role: chat.myRole === 'owner' ? 'owner' : 'member', joinedAt: FIXED_PAST_INSTANT },
  ]
  for (const [key, role] of chat.members ?? []) {
    const user = users.find((candidate) => candidate.id === userKeyIds[key])
    if (user === undefined) {
      throw new Error(`fixture: unknown group member key ${String(key)}`)
    }
    roster.push({ user, role, joinedAt: FIXED_PAST_INSTANT })
  }
  return roster
}

const chatList: ChatListItem[] = demoChats
  .map((chat): ChatListItem => {
    const messages = messagesByChatId.get(chatIdOf(chat)) ?? []
    const lastMessage = messages[messages.length - 1] ?? null
    if (chat.kind === 'group') {
      return {
        chatId: chatIdOf(chat),
        type: 'group',
        title: chat.title,
        memberCount: (chat.members?.length ?? 0) + 1,
        myRole: chat.myRole,
        peer: null,
        lastMessage,
        unreadCount: chat.unread,
        blockedByMe: null,
      }
    }
    return {
      chatId: chatIdOf(chat),
      type: 'direct',
      peer: peerUserOf(chat),
      lastMessage,
      unreadCount: chat.unread,
      blockedByMe: false,
    }
  })
  .sort((left, right) => {
    const leftTime = left.lastMessage?.createdAt ?? ''
    const rightTime = right.lastMessage?.createdAt ?? ''
    return rightTime.localeCompare(leftTime)
  })

function chatView(chatIdValue: string): ChatView {
  const chat = chatByChatId.get(chatIdValue)
  const messages = messagesByChatId.get(chatIdValue) ?? []
  if (chat === undefined) {
    throw new Error(`fixture: unknown chat ${chatIdValue}`)
  }
  const lastSeq = messages.length
  if (chat.kind === 'group') {
    return {
      chatId: chatIdValue,
      type: 'group',
      title: chat.title,
      description: null,
      myRole: chat.myRole,
      othersReadUpToSeq: peerReadUpToSeq(chat),
      memberCount: (chat.members?.length ?? 0) + 1,
      peer: null,
      blockedByMe: null,
      peerReadUpToSeq: null,
      myReadUpToSeq: lastSeq - chat.unread,
    }
  }
  return {
    chatId: chatIdValue,
    type: 'direct',
    peer: peerUserOf(chat),
    blockedByMe: false,
    peerReadUpToSeq: peerReadUpToSeq(chat),
    myReadUpToSeq: lastSeq - chat.unread,
  }
}

function groupView(chatIdValue: string): GroupView {
  const chat = chatByChatId.get(chatIdValue)
  if (chat === undefined || chat.kind !== 'group') {
    throw new Error(`fixture: unknown group ${chatIdValue}`)
  }
  return {
    chatId: chatIdValue,
    title: chat.title ?? '',
    description: null,
    myRole: chat.myRole ?? 'member',
    members: groupMembers(chat),
  }
}

function messagePage(
  chatIdValue: string,
  options?: { before?: number; after?: number },
): MessagePage {
  const messages = messagesByChatId.get(chatIdValue)
  if (messages === undefined) {
    throw new Error(`fixture: unknown chat ${chatIdValue}`)
  }
  const after = options?.after
  if (after !== undefined) {
    return { messages: messages.filter((message) => message.seq > after) }
  }
  const before = options?.before
  const descending = [...messages]
    .filter((message) => before === undefined || message.seq < before)
    .reverse()
  return { messages: descending }
}

function searchUsers(query: string): PublicUser[] {
  const normalized = query.trim().toLowerCase()
  if (normalized === '') {
    return []
  }
  return users.filter(
    (user) => user.username.toLowerCase() === normalized || user.email.toLowerCase() === normalized,
  )
}

function contactsSorted(sort: string): ContactView[] {
  const field =
    sort === 'email'
      ? (contact: ContactView) => contact.user.email
      : (contact: ContactView) => contact.user.username
  return [...contacts].sort((left, right) => field(left).localeCompare(field(right)))
}

export const AETHERGRAM_CHAT_COUNT = demoChats.length

/**
 * The deterministic API surface served to the messenger page (fixtures/api.ts):
 * the №12 list in the prototype order, per-chat views/pages, №19/№20/№36 answers.
 */
export const AETHERGRAM = {
  refreshToken: AETHERGRAM_REFRESH_TOKEN,
  tokenPair,
  me: ME,
  chatList,
  chatView,
  groupView,
  messagePage,
  searchUsers,
  contacts: contactsSorted,
  presenceOf(userIdValue: string): 'online' | 'offline' | 'unknown' {
    return presenceByUserId.get(userIdValue) ?? 'unknown'
  },
  lastSeqOf(chatIdValue: string): number {
    return (messagesByChatId.get(chatIdValue) ?? []).length
  },
  userByUsername(username: string): PublicUser | undefined {
    return userByUsername.get(username)
  },
}
