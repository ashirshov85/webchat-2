/**
 * Dialog message list (feature 004, T026): renders the open chat's
 * messages in ascending `seq` order (US1-2). Outgoing messages (sent
 * by the current user) carry a delivery status — server-confirmed
 * entries render the engraved ✓ tick «Доставлено» (US1-3, T022);
 * optimistic entries passed via `pending` render «отправляется»
 * until the server acknowledges them. Incoming messages never show
 * check marks (US1-3).
 *
 * `pending` entries whose `clientMessageId` already matches a rendered
 * server message are skipped: the server copy wins (message.id =
 * clientMessageId, FR-004), which keeps the render idempotent when the
 * 201/SSE acknowledgement races the optimistic state.
 *
 * Outbox integration (US2, T036, FR-012): records of the open chat are
 * passed via `outbox` and render after the server messages in creation
 * order (research.md §10). `sending` renders «отправляется», terminal
 * `failed` renders «не отправлено» with the manual actions — retry
 * with the same id and local deletion. An evicted record (US2, T028,
 * FR-005 — `errorCode='queue_overflow'`, T026) renders the eviction
 * reason «не отправлено (переполнение очереди)» instead of the plain
 * label, so the user sees WHY the message will never leave on its
 * own; the actions stay the same — manual retry by the same id is
 * still allowed (idempotency preserved). A record acknowledged by the
 * server (201/200 — its id is already among `messages`) is skipped:
 * the confirmed copy renders the ✓ tick «Доставлено» instead.
 * Outgoing from another device arrives as a regular server message
 * via the own SSE stream and renders «Доставлено» immediately (US2-6).
 *
 * Outbox-state design system (US4, T050, FR-030): the states stay on
 * the bubble surface pinned by T048 — the static «отправляется» rides
 * the engraved footer typography, the terminal «не отправлено» is the
 * engraved danger (--err-ink + the tick emboss edge), and the manual
 * actions are compact machine buttons of the prototype `.m-btn`
 * lexica with the danger variant «Удалить» (message-list.css). The
 * retry/delete callbacks are addressed by the SAME clientMessageId —
 * the 005 engine idempotency is untouched (SC-002, SC-003).
 *
 * History pagination (US3, T039, FR-008): the list itself is the scroll
 * container; scrolling close to the top calls `onLoadOlder`, and the
 * freshly prepended older page keeps the viewport anchored to the same
 * newest content (no visual jump). An empty dialog renders the plain
 * empty state without errors.
 *
 * Read status (US4, T044, FR-010): outgoing messages with
 * `seq ≤ peerReadUpToSeq` render the ✓✓ tick «Прочитано», the rest
 * stay «Доставлено». The watermark comes from ChatView and `chat.read`
 * frames and is monotonic (US4-5) — a delivered message never loses
 * its second check mark. Incoming messages never show marks.
 *
 * Group variant (feature 006, US2, T038, FR-012): passing the №28
 * roster switches the list to the group rendering — every INCOMING
 * message is attributed to its sender's roster username (several
 * peers are distinguishable), own messages keep only the status
 * marks. ✓✓ follows the group watermark `othersReadUpToSeq` =
 * MAX(last_read_seq) of the OTHER active members — it flips as soon
 * as ANY one of them has read (№13/№26): own messages with
 * `seq ≤ othersReadUpToSeq` render «Прочитано». The watermark is
 * monotonic (max) — the hook holds the maximum, so a stale
 * `chat.read` frame or a reconnect refetch with a lower server
 * projection never rolls a rendered ✓✓ back (FR-012). Without a
 * roster the list stays the plain direct variant — no attribution,
 * `peerReadUpToSeq` drives ✓✓ (004 semantics untouched).
 *
 * «Aethergram» reskin (feature 008, US1, T022; FR-001, FR-018,
 * research §C/§G): the rows ride the PROTOTYPE markup of
 * design/chats.html §7 — `.msg.me/.them` rows carry the sender
 * Avatar (34px, design-tokens §4) and a `.bubble` (`.b-text` +
 * `.b-time` footer hosting the `.tick` stamps); outgoing bubbles are
 * the gold plate (4px 12px 12px 12px), incoming — the dark panel
 * (12px 4px 12px 12px) of design-tokens §5. The 004 test hooks
 * (`message-list`, `message`+`outgoing/incoming`, `message-text`,
 * `message-sender`, `message-status`, `message-retry/remove`,
 * `message-history`) stay as wrappers of the prototype classes
 * (FR-034); the SC-002 status texts «Доставлено»/«Прочитано» ride
 * the tick `title` (ui-behavior §1). Avatar sources: own rows —
 * `meUsername`, direct incoming — `peerUsername`, group incoming —
 * the roster username (the №28 sender attribution); an unknown
 * username falls back to the stable user id, keeping the color
 * deterministic (FR-024). `React.memo` rows + `content-visibility:
 * auto` (message-list.css) keep a 1000+ feed at 60 fps (SC-010,
 * research §G); the `pop` entrance is transform/opacity-only and
 * dies under prefers-reduced-motion (FR-004 via machine.css). The
 * ЧЧ:ММ time of the `.b-time` footer and the `.date-divider`
 * between calendar days (FR-019) are rendered since T026 — the
 * T016(а) feed baselines carry both, so SC-001 at the US1
 * checkpoint needs them; the US3/T044 polish builds on top.
 *
 * Open seat (bug 2 T078 → bug 7 T085): the first render of an open
 * chat seats the feed with the BOTTOM edge of the LAST READ message
 * (`seq ≤ unreadFromSeq` — the open-time №13 `myReadUpToSeq`
 * latched by useChatMessages) at the BOTTOM edge of the viewport:
 * the unread run starts right below the fold, the read history
 * stays a scroll-up away (and `loadOlder` keeps feeding it). A
 * fully read window degenerates to the feed's very last row — the
 * open seats in the end. When the last read row sits ABOVE the
 * loaded window, the seat drives №14 `loadOlder` pages until it
 * enters the window (a failed page never retries on its own); a
 * wholly unread window (watermark 0) has no fold row and keeps the
 * natural top position. The seat is one-shot per open (re-armed by
 * the empty window of a chat switch), waits for the watermark
 * while №13 is in flight, and never fires for an empty chat. The
 * fold row carries `data-seat-anchor`; the pagination anchor of
 * prepended older pages (anchorHeightRef, T053) is untouched — the
 * two scrolls live in separate effects and never act in the same
 * commit.
 *
 * Bottom autoscroll (bug 2, T079): a send always seats the feed at
 * the bottom — the optimistic outbox row and the server ack that
 * replaces it scroll even while the user reads history (the ack is
 * recognized through the T023 `localIdsRef` pre-commit set). Any
 * other append — a new incoming `message.created`, an own message
 * from another device — scrolls ONLY while the user rides the bottom
 * edge (≤ BOTTOM_STICKY_THRESHOLD, latched by real scroll events);
 * reading history is never yanked. Older-page prepends keep the T053
 * viewport anchor: the bottom row keys do not change, so the sticky
 * effect stays quiet. A fresh open (mount or the empty window of a
 * chat switch) only arms the tracking — the open seat (T085) owns
 * the open scroll and the first paint of history never jumps.
 *
 * Delivery-stamp animation (US1, T023, FR-018 edge case): the
 * engraved tick plays the prototype `tickStamp` (.32s,
 * design-tokens §5) ONLY when the stamp of an ALREADY DISPLAYED row
 * changes — never on the first paint of history (initial load,
 * pagination prepend, chat switch render settled stamps). Two
 * transitions animate: «телеграф отбил» — a server ✓ replaces the
 * optimistic «отправляется» row the user has just seen (the
 * prototype inserts the ack tick with `.anim`, chats.html §7
 * sendMessage) — and the in-place ✓ → ✓✓ flip when a read watermark
 * (direct `peerReadUpToSeq` or group `othersReadUpToSeq`) crosses
 * the message (markRead swaps the tick the same way). The ack case
 * rides `stampAnim`: MessageList remembers the ids rendered as LOCAL
 * rows in the previous committed render (`localIdsRef`, rewritten in
 * a post-commit effect — no render-phase writes), so a fresh FeedRow
 * whose id just graduated from the optimistic row mounts WITH the
 * animation, while a message that was never shown locally stays
 * quiet. The flip is detected inside the Tick itself by comparing
 * `read` with the previous render — a one-shot render-phase latch
 * (idempotent under StrictMode double-render: both passes converge
 * to the same output). A mass watermark jump re-renders EXACTLY the
 * flipped rows once (memo-blocked rows render zero extra times — no
 * avalanche); the spent `.anim` marker stays in the class list so
 * the animation never replays on unrelated re-renders.
 */
import { Fragment, memo, useEffect, useRef } from 'react'
import type { Message } from '../../api/chats'
import type { GroupMember } from '../../api/groups'
import { QUEUE_OVERFLOW_ERROR_CODE } from '../outbox'
import type { OutboxRecord } from '../outbox'
import { Avatar } from '../../ui/Avatar'
import { formatDate, formatTime } from '../../ui/time'
import './message-list.css'
import './states.css'

/** Distance from the top (px) that triggers an older-page request. */
const TOP_LOAD_THRESHOLD = 48

/**
 * Distance from the bottom edge (px) that still counts as «the user
 * rides the bottom» — the sticky-autoscroll gate of new incoming
 * messages (T079): inside the band the feed follows the append,
 * outside it the user is reading history and the scroll stays put.
 */
const BOTTOM_STICKY_THRESHOLD = 32

/** Feed avatar size — design-tokens §4 (лента 34px). */
const FEED_AVATAR_SIZE = 34

/**
 * Local calendar day of an ISO instant — the divider granularity
 * (FR-019): consecutive messages of one local day share the key.
 */
function dayKeyOf(iso: string): string {
  return new Date(iso).toDateString()
}

export interface PendingMessage {
  /** Client-generated UUID (FR-004) — becomes message.id on the server. */
  readonly clientMessageId: string
  readonly text: string
}

export interface MessageListProps {
  /** Server-confirmed messages of the open chat (ascending seq order). */
  readonly messages: readonly Message[]
  /** Current user id: senderId === currentUserId marks an outgoing message. */
  readonly currentUserId: string
  /**
   * Own username — the avatar source of every outgoing row (T022,
   * FR-024). Falls back to `currentUserId` while unknown; the
   * derivation stays deterministic either way.
   */
  readonly meUsername?: string
  /**
   * The PEER username of the open DIRECT dialog — the avatar source
   * of incoming rows (T022, FR-024). Groups attribute through the
   * №28 roster instead; the stable user id is the fallback.
   */
  readonly peerUsername?: string
  /** Optimistic outgoing entries not yet acknowledged by the server. */
  readonly pending?: readonly PendingMessage[]
  /** Outbox records of the open chat (T036): `sending`/`failed` entries. */
  readonly outbox?: readonly OutboxRecord[]
  /** Manual retry of a `failed` record with the same id (FR-012). */
  readonly onRetry?: (clientMessageId: string) => void
  /** Local deletion of a `failed` record (FR-012). */
  readonly onRemove?: (clientMessageId: string) => void
  /** Older history pages exist above the rendered window (US3). */
  readonly hasOlder?: boolean
  /** An older page request is currently in flight (US3). */
  readonly loadingOlder?: boolean
  /** Requests the next older page via `before=nextBefore` (US3). */
  readonly onLoadOlder?: () => void
  /**
   * Peer's read watermark of the open chat (US4): outgoing messages
   * with `seq ≤ peerReadUpToSeq` render ✓✓. Defaults to 0 — nothing
   * read yet, everything stays «доставлено ✓». Direct chats only —
   * groups read the `othersReadUpToSeq` watermark instead.
   */
  readonly peerReadUpToSeq?: number
  /**
   * Active roster of the open GROUP (feature 006, US2, T038): its
   * presence switches the list to the group variant — incoming
   * messages are attributed to the sender's roster username.
   */
  readonly members?: readonly GroupMember[]
  /**
   * Group read watermark (US2, FR-012): own messages with
   * `seq ≤ othersReadUpToSeq` render ✓✓ — the MAX of the other
   * active members' read marks (№13/№26 + `chat.read` frames; ✓✓
   * once any one of them has read). Defaults to 0; monotonic — a
   * rendered ✓✓ never rolls back.
   */
  readonly othersReadUpToSeq?: number
  /**
   * Open-time read watermark of the caller (bug 2 T078 → bug 7
   * T085): the `myReadUpToSeq` latched from the first №13 ChatView
   * answer of the current open (useChatMessages). Messages with
   * `seq ≤ unreadFromSeq` are the read ones — the LAST of them is
   * the fold row the feed seats at on the first render of the open
   * (its bottom edge at the viewport bottom; the unread run starts
   * right below the fold). A watermark at/above the last row means
   * a fully read window — the seat degenerates to the very last row
   * (the end of the feed). `null`/omitted — the watermark is not
   * known yet (№13 in flight): the seat waits and never fires if it
   * never arrives (a failed №13 keeps the current behaviour).
   */
  readonly unreadFromSeq?: number | null
}

/**
 * Engraved delivery stamp of a server-confirmed outgoing message
 * (design-tokens §5): dark «гравированные» strokes with the white
 * emboss edge ride the prototype SVG; the SC-002 text lives in the
 * `title` (ui-behavior §1). T023: the `.anim` tickStamp plays when
 * the stamp of an already displayed row CHANGES — on mount only for
 * an ack that replaced a local optimistic row (`animate`), and
 * in-place when `read` flips ✓ → ✓✓ (a watermark advance). The
 * status-keyed span remounts on the flip so the fresh stamp of the
 * NEW status carries the animation; the spent marker then persists
 * in the class list (a stable className never restarts the CSS
 * animation) and a first paint of history stays settled.
 */
function Tick({ read, animate = false }: { readonly read: boolean; readonly animate?: boolean }) {
  const stampedRef = useRef(animate)
  const prevReadRef = useRef(read)
  // One-shot latches (render-phase but idempotent: once set they
  // never reset, so a StrictMode double render converges to the
  // same output — no setState, no extra render pass, one render per
  // flipped row even on a mass watermark jump).
  if (animate) {
    stampedRef.current = true
  }
  if (prevReadRef.current !== read) {
    prevReadRef.current = read
    stampedRef.current = true
  }
  const variant = read ? 'read' : 'dlv'
  const title = read ? 'Прочитано' : 'Доставлено'
  return (
    <span
      key={variant}
      className={stampedRef.current ? `tick ${variant} anim` : `tick ${variant}`}
      title={title}
    >
      {read ? (
        <svg viewBox="0 0 30 16" aria-hidden="true">
          <path d="M2.5 8.5l4.5 4.5L19.5 3.5" />
          <path d="M11 8.5l4.5 4.5L28 3.5" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 16" aria-hidden="true">
          <path d="M6 8.5l4.5 4.5L23 3.5" />
        </svg>
      )}
    </span>
  )
}

/** One server-confirmed message row (memoized, research §G). */
interface FeedRowProps {
  readonly message: Message
  readonly outgoing: boolean
  readonly read: boolean
  /** Group attribution of the sender (undefined — none). */
  readonly sender: string | undefined
  /** Avatar derivation source of the row (username or stable id). */
  readonly avatarSource: string
  /** The ack transition animates the fresh ✓ (T023, FR-018). */
  readonly stampAnim: boolean
  /** The last read row — the T085 fold the open seat scrolls to. */
  readonly seatAnchor: boolean
}

const FeedRow = memo(function FeedRow({
  message,
  outgoing,
  read,
  sender,
  avatarSource,
  stampAnim,
  seatAnchor,
}: FeedRowProps) {
  return (
    <li
      className={outgoing ? 'message outgoing msg me' : 'message incoming msg them'}
      data-seat-anchor={seatAnchor ? '' : undefined}
    >
      <Avatar source={avatarSource} size={FEED_AVATAR_SIZE} />
      <div className="bubble">
        {sender !== undefined && <div className="sender message-sender">{sender}</div>}
        <div className="b-text message-text">{message.text}</div>
        <div className="b-time">
          {formatTime(message.createdAt)}
          {outgoing && <Tick read={read} animate={stampAnim} />}
        </div>
      </div>
    </li>
  )
})

/** One local outgoing row — optimistic `pending` or an outbox record. */
interface LocalRowProps {
  readonly clientMessageId: string
  readonly text: string
  readonly failed: boolean
  /** Terminal-failure reason label (the plain or queue-overflow one). */
  readonly failedReason: string
  readonly avatarSource: string
  readonly onRetry?: (clientMessageId: string) => void
  readonly onRemove?: (clientMessageId: string) => void
}

const LocalRow = memo(function LocalRow({
  clientMessageId,
  text,
  failed,
  failedReason,
  avatarSource,
  onRetry,
  onRemove,
}: LocalRowProps) {
  return (
    <li className="message outgoing msg me">
      <Avatar source={avatarSource} size={FEED_AVATAR_SIZE} />
      <div className="bubble">
        <div className="b-text message-text">{text}</div>
        <div className="b-time">
          {failed ? (
            <>
              <span className="message-status message-status-failed">{failedReason}</span>
              <span className="message-actions">
                <button
                  type="button"
                  className="message-retry"
                  onClick={() => {
                    onRetry?.(clientMessageId)
                  }}
                >
                  Повторить
                </button>
                <button
                  type="button"
                  className="message-remove"
                  onClick={() => {
                    onRemove?.(clientMessageId)
                  }}
                >
                  Удалить
                </button>
              </span>
            </>
          ) : (
            <span className="message-status">отправляется</span>
          )}
        </div>
      </div>
    </li>
  )
})

export function MessageList({
  messages,
  currentUserId,
  meUsername,
  peerUsername,
  pending = [],
  outbox = [],
  onRetry,
  onRemove,
  hasOlder = false,
  loadingOlder = false,
  onLoadOlder,
  peerReadUpToSeq = 0,
  members,
  othersReadUpToSeq = 0,
  unreadFromSeq = null,
}: MessageListProps) {
  const listRef = useRef<HTMLOListElement>(null)
  /** scrollHeight captured when an older page is requested — the anchor. */
  const anchorHeightRef = useRef<number | null>(null)
  const firstMessageIdRef = useRef<string | undefined>(messages[0]?.id)
  /**
   * Whether the viewport rides the bottom edge (T079) — latched by
   * real scroll events only: a fresh open is NOT «at the bottom»
   * (the №16 window renders from the top, the open seat T085 owns
   * the open scroll), and once the user scrolls, every event
   * refreshes the latch (including the programmatic shifts of the
   * effects — an autoscroll to the bottom re-arms the stickiness).
   */
  const bottomRef = useRef(false)
  /** Bottom-most server row of the previous commit (T079). */
  const lastServerIdRef = useRef<string | undefined>(undefined)
  /** Bottom-most local row of the previous commit (T079). */
  const lastLocalKeyRef = useRef<string | undefined>(undefined)

  useEffect(() => {
    const list = listRef.current
    const first = messages[0]
    if (list !== null && first !== undefined && anchorHeightRef.current !== null) {
      // Only a prepend (the oldest rendered message changed) shifts the
      // viewport; appends at the bottom keep the scroll untouched.
      if (first.id !== firstMessageIdRef.current) {
        const grown = list.scrollHeight - anchorHeightRef.current
        if (grown > 0) {
          list.scrollTop += grown
        }
      }
    }
    anchorHeightRef.current = null
    firstMessageIdRef.current = first?.id
  }, [messages])

  const handleScroll = () => {
    const list = listRef.current
    if (list === null) {
      return
    }
    // T079: refresh the bottom-edge latch on EVERY scroll — the
    // append effect reads «was the user at the bottom BEFORE the new
    // row landed», and this handler is the only witness of that.
    bottomRef.current =
      list.scrollHeight - list.scrollTop - list.clientHeight <= BOTTOM_STICKY_THRESHOLD
    if (loadingOlder || !hasOlder || onLoadOlder === undefined) {
      return
    }
    if (list.scrollTop <= TOP_LOAD_THRESHOLD) {
      anchorHeightRef.current = list.scrollHeight
      onLoadOlder()
    }
  }

  /**
   * T078/T085 open seat — the one-shot latch. Armed on mount and
   * re-armed by every EMPTY window (the chat-switch reset of
   * useChatMessages rides through `messages: []`), spent exactly once
   * per open: the first commit that has BOTH the initial page and the
   * open-time watermark seats the feed, everything after (appends,
   * prepends, watermark advances) leaves the scroll to the user.
   */
  const seatArmedRef = useRef(true)
  /**
   * Oldest seq of the window a seat-driven №14 page was already
   * requested against: a FAILED page keeps the window as it was, and
   * without the memo the effect (re-running on the `loadingOlder`
   * flip) would hammer №14 in a loop. A SUCCESSFUL page changes the
   * oldest seq, so the catch-up continues; the user's own scroll
   * still retries through the standard handleScroll path.
   */
  const seatLoadedOldestRef = useRef<number | null>(null)
  useEffect(() => {
    if (messages.length === 0) {
      seatArmedRef.current = true
      seatLoadedOldestRef.current = null
    }
  }, [messages])

  useEffect(() => {
    const list = listRef.current
    if (list === null || !seatArmedRef.current || messages.length === 0 || unreadFromSeq === null) {
      return
    }
    const seat = list.querySelector('[data-seat-anchor]')
    if (seat === null) {
      // No read row in the loaded window — everything rendered is
      // unread, the fold sits ABOVE the window (or does not exist at
      // watermark 0). While older history may still carry the read
      // row, the seat drives №14 pages down to it (T085); the memo
      // keeps one request per window state. A watermark of 0 means
      // nothing read exists anywhere — pagination can never find a
      // fold row, so the feed keeps its natural top position (the
      // whole feed IS the unread run) and №14 is never asked.
      const oldest = messages[0]
      if (
        oldest !== undefined &&
        unreadFromSeq > 0 &&
        hasOlder &&
        !loadingOlder &&
        onLoadOlder !== undefined &&
        seatLoadedOldestRef.current !== oldest.seq
      ) {
        seatLoadedOldestRef.current = oldest.seq
        onLoadOlder()
      }
      return
    }
    seatArmedRef.current = false
    if (typeof seat.scrollIntoView === 'function') {
      // block:'end' seats the LAST READ row's bottom edge at the
      // viewport bottom — the unread run starts right below the fold,
      // the read history stays a scroll-up away; a fully read window
      // degenerates to the feed's very last row (the end). jsdom
      // ships no scrollIntoView — the guard keeps every non-visual
      // suite at the current behaviour.
      seat.scrollIntoView({ block: 'end' })
      // content-visibility warm-up (research §G): the offscreen rows
      // render lazily behind 64px placeholders, so the first attempt
      // may run against a COLD layout that underestimates the feed
      // and finds the seat «already in view» (a silent no-op while
      // the real heights overflow). Re-check after the paint and
      // correct the seat once the layout is warm — bounded to two
      // frames, still one-shot (the latch stays spent). jsdom layout
      // is all zeros: the re-check sees a perfect seat and rests.
      if (typeof requestAnimationFrame === 'function') {
        const reseat = (attempt: number) => {
          const gap = list.getBoundingClientRect().bottom - seat.getBoundingClientRect().bottom
          if (gap > 2 && typeof seat.scrollIntoView === 'function') {
            seat.scrollIntoView({ block: 'end' })
          }
          if (attempt > 0) {
            requestAnimationFrame(() => {
              reseat(attempt - 1)
            })
          }
        }
        requestAnimationFrame(() => {
          reseat(1)
        })
      }
    }
  }, [messages, unreadFromSeq, hasOlder, loadingOlder, onLoadOlder])

  const confirmedIds = new Set(messages.map((message) => message.id))
  const activePending = pending.filter((entry) => !confirmedIds.has(entry.clientMessageId))
  const activeOutbox = outbox.filter((entry) => !confirmedIds.has(entry.clientMessageId))

  // T079 append detection: the bottom-most SERVER row and the
  // bottom-most LOCAL row of the window. An older-page prepend keeps
  // both keys (the newest content does not move), an append changes
  // exactly one of them.
  const lastServerId = messages.at(-1)?.id
  let lastLocalKey: string | undefined
  if (activePending.length > 0) {
    lastLocalKey = `pending:${activePending.at(-1)?.clientMessageId}`
  } else if (activeOutbox.length > 0) {
    lastLocalKey = `outbox:${activeOutbox.at(-1)?.clientMessageId}`
  }

  /**
   * T023 ack tracking: ids rendered as LOCAL rows in the previous
   * committed render. A server message with such an id has just
   * replaced the optimistic «отправляется»/«не отправлено» row the
   * user saw — its fresh ✓ stamp mounts WITH the tickStamp
   * (prototype sendMessage ack). Written only in a post-commit
   * effect (never during render): a chat switch back, a pagination
   * prepend or a refetch stays quiet because their ids were never
   * locally displayed here. The ref itself is declared before the
   * T079 autoscroll effect below, which reads the pre-commit set on
   * the ack commit — the UPDATING effect stays after it, so the
   * rewrite always lands once the reader is done.
   */
  const localIdsRef = useRef<ReadonlySet<string>>(new Set())

  /**
   * T079 bottom autoscroll: an own send always lands in view — the
   * optimistic outbox row AND the server ack that replaces it (the
   * id was displayed locally one commit ago — `localIdsRef` still
   * holds the pre-commit set when this effect runs first). Any OTHER
   * append (a new incoming `message.created`, an own message from
   * another device) scrolls only while the user rides the bottom
   * edge — reading history is never yanked. A fresh open (mount, or
   * the empty window of a chat switch resetting both keys) only ARMS
   * the tracking: the open seat (T085) owns the open scroll.
   * Prepends never reach here (both keys unchanged), which keeps the
   * T053 anchor the only writer of prepend scrolls.
   */
  useEffect(() => {
    const previousServerId = lastServerIdRef.current
    const previousLocalKey = lastLocalKeyRef.current
    lastServerIdRef.current = lastServerId
    lastLocalKeyRef.current = lastLocalKey
    const list = listRef.current
    if (list === null) {
      return
    }
    const serverAppended =
      lastServerId !== undefined &&
      previousServerId !== undefined &&
      lastServerId !== previousServerId
    const localAppended = lastLocalKey !== undefined && lastLocalKey !== previousLocalKey
    if (!serverAppended && !localAppended) {
      return
    }
    const ownAppend =
      localAppended ||
      (serverAppended && lastServerId !== undefined && localIdsRef.current.has(lastServerId))
    if (!ownAppend && !bottomRef.current) {
      return
    }
    list.scrollTop = list.scrollHeight
  })

  useEffect(() => {
    localIdsRef.current = new Set(
      [...activePending, ...activeOutbox].map((entry) => entry.clientMessageId),
    )
  })

  // Group variant (T038): the roster presence discriminates; sender
  // attribution resolves through it, and ✓✓ follows the group
  // watermark instead of the direct-chat `peerReadUpToSeq`.
  const isGroup = members !== undefined
  const senderNames = isGroup
    ? new Map(members.map((member) => [member.user.id, member.user.username]))
    : undefined
  const readUpToSeq = isGroup ? othersReadUpToSeq : peerReadUpToSeq

  // Avatar sources (T022, FR-024): usernames wherever the surface
  // knows them, the stable user id as the deterministic fallback.
  const meAvatarSource = meUsername ?? currentUserId
  const incomingAvatarSource = (senderId: string): string =>
    senderNames?.get(senderId) ?? peerUsername ?? senderId

  // T085: the fold row — the LAST READ message of the open-time
  // watermark (`seq ≤ unreadFromSeq`); a watermark at/above the last
  // row degenerates to the feed's very last row (fully read — the
  // seat lands in the end).
  let seatAnchorId: string | undefined
  if (unreadFromSeq !== null) {
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      const candidate = messages[index]
      if (candidate !== undefined && candidate.seq <= unreadFromSeq) {
        seatAnchorId = candidate.id
        break
      }
    }
  }

  if (messages.length === 0 && activePending.length === 0 && activeOutbox.length === 0) {
    return <p className="messenger-empty">Сообщений пока нет</p>
  }

  return (
    <ol
      className="message-list chat-scroll"
      aria-label="Сообщения диалога"
      ref={listRef}
      onScroll={handleScroll}
    >
      {loadingOlder && (
        <li className="message-history" aria-busy="true">
          Загрузка истории…
        </li>
      )}
      {messages.map((message, index) => {
        const outgoing = message.senderId === currentUserId
        const read = outgoing && message.seq <= readUpToSeq
        const sender = outgoing ? undefined : senderNames?.get(message.senderId)
        // Date divider (FR-019): one per local calendar-day run — before
        // a message whose day differs from its predecessor's, and above
        // the oldest row once the history is known complete (`hasOlder`
        // false); a same-day pagination junction never grows a second
        // one (data-model 1.3).
        const previous = index > 0 ? messages[index - 1] : undefined
        const startsNewDay =
          previous === undefined
            ? !hasOlder
            : dayKeyOf(message.createdAt) !== dayKeyOf(previous.createdAt)
        return (
          <Fragment key={message.id}>
            {startsNewDay && <li className="date-divider">{formatDate(message.createdAt)}</li>}
            <FeedRow
              message={message}
              outgoing={outgoing}
              read={read}
              sender={sender}
              avatarSource={outgoing ? meAvatarSource : incomingAvatarSource(message.senderId)}
              stampAnim={localIdsRef.current.has(message.id)}
              seatAnchor={message.id === seatAnchorId}
            />
          </Fragment>
        )
      })}
      {activePending.map((entry) => (
        <LocalRow
          key={`pending:${entry.clientMessageId}`}
          clientMessageId={entry.clientMessageId}
          text={entry.text}
          failed={false}
          failedReason=""
          avatarSource={meAvatarSource}
        />
      ))}
      {activeOutbox.map((entry) => (
        <LocalRow
          key={`outbox:${entry.clientMessageId}`}
          clientMessageId={entry.clientMessageId}
          text={entry.text}
          failed={entry.state === 'failed'}
          failedReason={
            entry.errorCode === QUEUE_OVERFLOW_ERROR_CODE
              ? 'не отправлено (переполнение очереди)'
              : 'не отправлено'
          }
          avatarSource={meAvatarSource}
          onRetry={onRetry}
          onRemove={onRemove}
        />
      ))}
    </ol>
  )
}
