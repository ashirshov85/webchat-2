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
 * Typing row (feature 008a, US2, T037; FR-006–FR-008, ui-behavior
 * §2.2): the feed's ephemeral tail — a SINGLE TypingRow instance
 * renders after the messages/local rows while somebody types in the
 * open chat (`.msg.them` → `.bubble.typing-b`, prototype #typingRow
 * — the last element of #messages). The `typing` prop carries raw
 * client-cache lookups: unresolved `userId`s drop out (TypingRow),
 * and an empty chat with a live typist keeps the real feed instead
 * of the empty state. The realtime state behind the prop is T038.
 * T067 (008a Phase 10): the row never mounts out of sight — its
 * appearance/composition is the THIRD bottom key of the append
 * detection (see the autoscroll paragraph below).
 *
 * Open seat (bug 2 T078 → bug 7 T085 → bug 13 T091 → bug 14 T092 →
 * 008a Phase 9 T066): the first render of an open chat with unread
 * incoming renders the accented «Непрочитанные сообщения» divider
 * EXACTLY ONCE above the FIRST unread INCOMING message (the same
 * boundary the seat counts) and seats the feed by the SIZE of the
 * unread block, measured by the actual DOM marks after the first
 * layout: divider + ALL unread incoming fit the viewport →
 * block:'end' on the LAST unread row (the whole block in view); they
 * do NOT fit → block:'start' on the DIVIDER (the block is read from
 * its beginning — the bug 14 intent with the divider as the anchor).
 * The unread run is `senderId ≠ me && seq > unreadFromSeq` (the
 * open-time №13 `myReadUpToSeq` latched by useChatMessages): own
 * outgoing rows are read by the author the moment they leave, so a
 * chat whose tail is own sends carries no unread incoming and the
 * seat degenerates to the feed's very last row with block:'end' —
 * the open lands in the end with the own tail in view (bug 13).
 * When the window starts with the unread incoming run, the chat's
 * true first unread may sit above the loaded window and the seat
 * drives №14 `loadOlder` pages until a loaded row precedes the
 * window's first unread incoming (a failed page never retries on
 * its own); a wholly unread window (watermark 0) keeps the natural
 * top position — the №14 guard never asks for a read boundary that
 * does not exist. The seat is one-shot per open (re-armed by the
 * empty window of a chat switch), waits for the watermark while №13
 * is in flight, and never fires for an empty chat. The anchor
 * elements carry `data-seat-anchor` whose value ('start'/'end') is
 * the seat alignment — the two candidates of an unread window (the
 * divider 'start' and the LAST unread row 'end') plus the degenerate
 * 'end' row of a read window; the pagination anchor of prepended
 * older pages (anchorHeightRef, T053) is untouched — the two scrolls
 * live in separate effects and never act in the same commit. The
 * divider is a STATIC snapshot of the open (the `unreadFromSeq`
 * basis, frozen when the seat fires): realtime incoming in the
 * already-open chat never moves or sprouts it, the scroll stays
 * with the user, and the badge resets through the existing read
 * path (FR-014). Bug 8а (T086): the first `scrollIntoView` runs
 * against a COLD layout (content-visibility placeholders), so a
 * post-paint rAF loop re-checks the deviation of the ALIGNED edge
 * in BOTH directions (|gap| > tolerance — an undershoot AND a
 * complete no-op with the anchor past the edge) AND re-decides the
 * fit itself against the warmed marks until the seat converges — a
 * window without an unread run ends with its last row at the fold,
 * i.e. the bottom of the feed; bounded to SCROLL_SETTLE_FRAMES
 * frames.
 *
 * Bottom autoscroll (bug 2, T079): a send always seats the feed at
 * the bottom — the optimistic outbox row and the server ack that
 * replaces it scroll even while the user reads history (the ack is
 * recognized through the T023 `localIdsRef` pre-commit set, or —
 * when the 201 races the optimistic row's first render so the set
 * never held the id — through the wiring-level `ownAckIds` vouch,
 * bug 8в/T086). Any other append — a new incoming
 * `message.created`, an own message from another device — scrolls
 * ONLY while the user rides the bottom edge (≤
 * BOTTOM_STICKY_THRESHOLD, latched by real scroll events);
 * reading history is never yanked. Older-page prepends keep the T053
 * viewport anchor: the bottom row keys do not change, so the sticky
 * effect stays quiet. A fresh open (mount or the empty window of a
 * chat switch) only arms the tracking — the open seat (T085) owns
 * the open scroll and the first paint of history never jumps.
 * Bug 8б (T086): the `scrollTop = scrollHeight` write itself lands
 * against the cold layout (placeholders keep the scrollHeight low),
 * so a post-paint rAF loop re-drives it until the distance to the
 * real bottom converges — the same bounded SCROLL_SETTLE_FRAMES
 * budget as the seat.
 *
 * Typing-row follow (008a Phase 10, T067): the TypingRow is the
 * feed's LAST element — its appearance or composition change
 * («{имя} и ещё N печатают…») grows the content below the bottom
 * message WITHOUT touching the two append keys above, so it carries
 * a THIRD bottom key of its own (the sorted participant ids, ''
 * when absent). An appearance/composition change while the user
 * rides the bottom edge re-drives `scrollTop = scrollHeight` with
 * the same bug 8б correction loop — the notification «X печатает…»
 * never mounts below the fold of an all-read, bottom-scrolled chat;
 * a reader up in history is never yanked (the T079 rule), and the
 * row's disappearance only shrinks the content — no scroll. A fresh
 * open only ARMS the key: the open seat (T085/T066) stays the sole
 * owner of the open scroll.
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
import type { RefObject } from 'react'
import type { Message } from '../../api/chats'
import type { GroupMember } from '../../api/groups'
import { QUEUE_OVERFLOW_ERROR_CODE } from '../outbox'
import type { OutboxRecord } from '../outbox'
import { Avatar } from '../../ui/Avatar'
import { formatDate, formatTime } from '../../ui/time'
import { TypingRow, renderableTyping } from './TypingRow'
import type { TypingParticipant } from './TypingRow'
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

/**
 * Post-paint scroll tolerance (bug 8, T086): a seat/autoscroll is
 * «converged» when the geometry sits within this many pixels of the
 * target. The raw deviation never reaches 0 for the LAST-row seat:
 * the feed's trailing row margin + bottom padding (~15px) always
 * separates the seat bottom from the fold, and the browser clamps
 * the alignment scroll to the maximum — so the tolerance must stay
 * trailing-aware while still catching every real undershoot (the
 * reproduced defects measured hundreds of pixels).
 */
const SCROLL_SETTLE_TOLERANCE_PX = 24

/**
 * Consecutive stable frames that end a post-paint correction loop
 * (bug 8, T086): content-visibility renders ripple for a few paints
 * after the last correction, so a single quiet frame is not
 * convergence — three in a row are.
 */
const SCROLL_SETTLE_STABLE_FRAMES = 3

/**
 * Post-paint correction budget in animation frames (bug 8, T086):
 * `content-visibility: auto` keeps 64px placeholders until the first
 * paint, so the opening seat and the send autoscroll both write
 * against a COLD layout that underestimates the feed. Each correction
 * reveals more real rows and the layout keeps drifting, so the loops
 * re-check after every paint — bounded to this many frames and ended
 * early by sustained convergence. jsdom's zero layout converges on
 * the first stable run.
 */
const SCROLL_SETTLE_FRAMES = 90

/** Feed avatar size — design-tokens §4 (лента 34px). */
const FEED_AVATAR_SIZE = 34

/**
 * Local calendar day of an ISO instant — the divider granularity
 * (FR-019): consecutive messages of one local day share the key.
 */
function dayKeyOf(iso: string): string {
  return new Date(iso).toDateString()
}

/**
 * Forced layout read (prototype jumpToMessage chats.html): reading a
 * layout property flushes pending style changes, so re-adding the
 * `.flash` class after it restarts the CSS animation even on a
 * repeated jump to the same row. Was `void row.offsetWidth` — the
 * wrapped read keeps the exact mechanism without the `void` operator.
 */
function forceReflow(element: HTMLElement): number {
  return element.offsetWidth
}

/**
 * T053 prepend anchor: only a prepend (the oldest rendered message
 * changed) shifts the viewport by the grown content; appends at the
 * bottom keep the scroll untouched.
 */
function keepPrependViewport(
  list: HTMLOListElement | null,
  messages: readonly Message[],
  anchorHeightRef: RefObject<number | null>,
  firstMessageIdRef: RefObject<string | undefined>,
): void {
  const first = messages[0]
  if (list !== null && first !== undefined && anchorHeightRef.current !== null) {
    if (first.id !== firstMessageIdRef.current) {
      const grown = list.scrollHeight - anchorHeightRef.current
      if (grown > 0) {
        list.scrollTop += grown
      }
    }
  }
  anchorHeightRef.current = null
  firstMessageIdRef.current = first?.id
}

/**
 * Scroll handler body: refreshes the bottom-edge latch on EVERY
 * scroll (T079) — the append effect reads «was the user at the
 * bottom BEFORE the new row landed», and this handler is the only
 * witness of that — then fires the older-page request near the top.
 */
function handleFeedScroll(
  list: HTMLOListElement | null,
  triggers: {
    readonly loadingOlder: boolean
    readonly hasOlder: boolean
    readonly onLoadOlder: (() => void) | undefined
  },
  bottomRef: RefObject<boolean>,
  anchorHeightRef: RefObject<number | null>,
): void {
  if (list === null) {
    return
  }
  bottomRef.current =
    list.scrollHeight - list.scrollTop - list.clientHeight <= BOTTOM_STICKY_THRESHOLD
  const onLoadOlder = triggers.onLoadOlder
  if (triggers.loadingOlder || !triggers.hasOlder || onLoadOlder === undefined) {
    return
  }
  if (list.scrollTop <= TOP_LOAD_THRESHOLD) {
    anchorHeightRef.current = list.scrollHeight
    onLoadOlder()
  }
}

/** The measured seat target — the anchor element and its alignment. */
interface SeatTarget {
  readonly element: HTMLElement
  readonly block: ScrollLogicalPosition
}

/** Deviation of the ALIGNED edge of the seat target (bug 8а, T086). */
function seatGap(target: SeatTarget, list: HTMLOListElement): number {
  return target.block === 'start'
    ? target.element.getBoundingClientRect().top - list.getBoundingClientRect().top
    : list.getBoundingClientRect().bottom - target.element.getBoundingClientRect().bottom
}

/**
 * The seat target by the ACTUAL DOM marks (T066): the unread block
 * (the divider top → the LAST unread bottom) against the list
 * `clientHeight` — it fits → block:'end' on the LAST unread row (the
 * whole block lands in view); it does not → block:'start' on the
 * DIVIDER (the block is read from its beginning — bug 14 semantics
 * with the divider as the anchor). The degenerate window (no unread
 * incoming) keeps its very last row with block:'end' (bug 13). jsdom
 * ships no scrollIntoView — the guard keeps every non-visual suite at
 * the current behaviour, and its all-zero layout measures the block
 * as fitting (0 ≤ 0).
 */
function pickSeat(
  dividerSeat: HTMLElement | null,
  tailSeat: HTMLElement | null,
  list: HTMLOListElement,
): SeatTarget | null {
  if (dividerSeat === null || tailSeat === null) {
    const degenerate = tailSeat ?? dividerSeat
    if (!degenerate?.isConnected) {
      return null
    }
    return { element: degenerate, block: tailSeat !== null ? 'end' : 'start' }
  }
  if (!dividerSeat.isConnected || !tailSeat.isConnected) {
    return null
  }
  const fits =
    tailSeat.getBoundingClientRect().bottom - dividerSeat.getBoundingClientRect().top <=
    list.clientHeight
  return fits ? { element: tailSeat, block: 'end' } : { element: dividerSeat, block: 'start' }
}

/**
 * Seat correction loop (bug 8а, T086): the first `scrollIntoView` ran
 * against a COLD layout (content-visibility placeholders), so a
 * post-paint rAF loop re-checks the deviation of the ALIGNED edge in
 * BOTH directions (|gap| > tolerance — an undershoot AND a complete
 * no-op with the anchor past the edge) AND re-decides the fit itself
 * against the warmed marks until the seat converges — a window
 * without an unread run ends with its last row at the fold, i.e. the
 * bottom of the feed; bounded to SCROLL_SETTLE_FRAMES frames.
 */
function reseatUntilStable(
  list: HTMLOListElement,
  isLive: () => boolean,
  pickTarget: () => SeatTarget | null,
): void {
  const reseat = (framesLeft: number, stable: number): void => {
    if (!isLive()) {
      return
    }
    const target = pickTarget()
    if (target === null) {
      return
    }
    const gap = seatGap(target, list)
    if (Math.abs(gap) <= SCROLL_SETTLE_TOLERANCE_PX) {
      stable += 1
    } else {
      stable = 0
      if (framesLeft > 0 && typeof target.element.scrollIntoView === 'function') {
        target.element.scrollIntoView({ block: target.block })
      }
    }
    if (stable < SCROLL_SETTLE_STABLE_FRAMES && framesLeft > 0) {
      requestAnimationFrame(() => {
        reseat(framesLeft - 1, stable)
      })
    }
  }
  requestAnimationFrame(() => {
    reseat(SCROLL_SETTLE_FRAMES, 0)
  })
}

/** Open-seat application inputs — the T085/T066 effect body arguments. */
interface OpenSeatDeps {
  readonly listRef: RefObject<HTMLOListElement | null>
  readonly messages: readonly Message[]
  readonly currentUserId: string
  readonly unreadFromSeq: number | null
  readonly hasOlder: boolean
  readonly loadingOlder: boolean
  readonly onLoadOlder: (() => void) | undefined
  readonly seatArmedRef: RefObject<boolean>
  readonly seatLoadedOldestRef: RefObject<number | null>
  readonly unreadBarrierSeqRef: RefObject<number | null | undefined>
}

/**
 * The window STARTS with the unread incoming run (neither seat
 * candidate rendered) — the chat's true first unread incoming may sit
 * ABOVE the loaded window (the run itself reaches up out of sight;
 * at watermark 0 everything incoming is unread). While older history
 * may still carry an earlier head of the run, the seat drives №14
 * pages until a loaded row precedes the window's first unread
 * incoming — that row IS then the chat's first unread
 * (T085/T091/T092); the memo keeps one request per window state. A
 * watermark of 0 means no read incoming exists anywhere — the whole
 * feed IS the unread run, its first loaded row already opens it at
 * the natural top, and the catch-up would walk the entire history
 * for a boundary that does not exist: №14 is never asked (the
 * T085/T091 guard).
 */
function requestSeatCatchUp(deps: OpenSeatDeps): void {
  const { messages, unreadFromSeq } = deps
  const onLoadOlder = deps.onLoadOlder
  const oldest = messages[0]
  if (
    oldest !== undefined &&
    unreadFromSeq !== null &&
    unreadFromSeq > 0 &&
    deps.hasOlder &&
    !deps.loadingOlder &&
    onLoadOlder !== undefined &&
    deps.seatLoadedOldestRef.current !== oldest.seq
  ) {
    deps.seatLoadedOldestRef.current = oldest.seq
    onLoadOlder()
  }
}

/**
 * T078/T085 → T092/T066 open-seat application: seats the feed exactly
 * once per open by the SIZE of the unread block (see the component
 * doc above) and freezes the unread-divider snapshot at the fire
 * commit (T066) — the seq of the FIRST unread incoming of THIS open
 * (`null` — none). Later realtime appends and prepends render against
 * the frozen value, so the divider never moves or sprouts after the
 * open decided.
 */
function applyOpenSeat(deps: OpenSeatDeps): void {
  const { messages, unreadFromSeq } = deps
  const list = deps.listRef.current
  if (
    list === null ||
    !deps.seatArmedRef.current ||
    messages.length === 0 ||
    unreadFromSeq === null
  ) {
    return
  }
  // T066 seat candidates: the divider ('start') and the LAST unread
  // row ('end') of a window with unread incoming, or the single
  // degenerate 'end' row of a window without any. A window that
  // STARTS with the unread incoming run renders NEITHER — the
  // catch-up branch below owns it.
  const dividerSeat = list.querySelector<HTMLElement>('[data-seat-anchor="start"]')
  const tailSeat = list.querySelector<HTMLElement>('[data-seat-anchor="end"]')
  if (dividerSeat === null && tailSeat === null) {
    requestSeatCatchUp(deps)
    return
  }
  deps.seatArmedRef.current = false
  deps.unreadBarrierSeqRef.current =
    messages.find(
      (message) => message.senderId !== deps.currentUserId && message.seq > unreadFromSeq,
    )?.seq ?? null
  const seat = pickSeat(dividerSeat, tailSeat, list)
  if (seat === null || typeof seat.element.scrollIntoView !== 'function') {
    return
  }
  seat.element.scrollIntoView({ block: seat.block })
  if (typeof requestAnimationFrame === 'function') {
    reseatUntilStable(
      list,
      () => deps.listRef.current === list,
      () => pickSeat(dividerSeat, tailSeat, list),
    )
  }
}

/** T079: does the append carry an own send (always lands in view)? */
function isOwnAppend(
  localAppended: boolean,
  serverAppended: boolean,
  lastServerId: string | undefined,
  localIds: ReadonlySet<string>,
  ownAckIds: ReadonlySet<string> | undefined,
): boolean {
  return (
    localAppended ||
    (serverAppended &&
      lastServerId !== undefined &&
      (localIds.has(lastServerId) || (ownAckIds?.has(lastServerId) ?? false)))
  )
}

/**
 * T079 bottom-autoscroll effect body: an own send always lands in
 * view — the optimistic outbox row AND the server ack that replaces
 * it (the id was displayed locally one commit ago — `localIdsRef`
 * still holds the pre-commit set when this effect runs first; an ack
 * that RACED the optimistic row's first render is vouched for by the
 * wiring-level `ownAckIds` set instead — bug 8в, T086). Any OTHER
 * append (a new incoming `message.created`, an own message from
 * another device) scrolls only while the user rides the bottom edge —
 * reading history is never yanked. Prepends never reach here (both
 * keys unchanged), which keeps the T053 anchor the only writer of
 * prepend scrolls.
 */
function applyBottomAutoscroll(deps: {
  readonly listRef: RefObject<HTMLOListElement | null>
  readonly lastServerId: string | undefined
  readonly lastLocalKey: string | undefined
  readonly ownAckIds: ReadonlySet<string> | undefined
  readonly lastServerIdRef: RefObject<string | undefined>
  readonly lastLocalKeyRef: RefObject<string | undefined>
  readonly localIdsRef: RefObject<ReadonlySet<string>>
  readonly bottomRef: RefObject<boolean>
}): void {
  const previousServerId = deps.lastServerIdRef.current
  const previousLocalKey = deps.lastLocalKeyRef.current
  deps.lastServerIdRef.current = deps.lastServerId
  deps.lastLocalKeyRef.current = deps.lastLocalKey
  const list = deps.listRef.current
  if (list === null) {
    return
  }
  const serverAppended =
    deps.lastServerId !== undefined &&
    previousServerId !== undefined &&
    deps.lastServerId !== previousServerId
  const localAppended = deps.lastLocalKey !== undefined && deps.lastLocalKey !== previousLocalKey
  if (!serverAppended && !localAppended) {
    return
  }
  const ownAppend = isOwnAppend(
    localAppended,
    serverAppended,
    deps.lastServerId,
    deps.localIdsRef.current,
    deps.ownAckIds,
  )
  if (!ownAppend && !deps.bottomRef.current) {
    return
  }
  list.scrollTop = list.scrollHeight
  // Cold-layout correction (bug 8б, T086): the write above lands
  // against placeholders — `content-visibility: auto` keeps 64px
  // stubs until the first paint, the scrollHeight is underestimated
  // and the clamped write stops ABOVE the real bottom.
  if (typeof requestAnimationFrame === 'function') {
    settleBottomScroll(list, () => deps.listRef.current === list)
  }
}

/**
 * T067 typing-row bottom-follow effect body: the row is the feed's
 * LAST element — it appears/changes below the bottom message without
 * touching the T079 append keys, so its own key drives the follow.
 * The gate mirrors T079: ONLY a user riding the bottom edge
 * (BOTTOM_STICKY_THRESHOLD, latched by real scroll events — all
 * messages read, the chat scrolled to the end) is followed; a reader
 * up in history is never yanked. An APPEARANCE or a COMPOSITION
 * change (a second typist grows «{имя} и ещё N печатают…») re-drives
 * `scrollTop = scrollHeight`; a DISAPPEARANCE only shrinks the
 * content — no scroll, nothing new to reveal. A fresh open (mount,
 * or the re-armed empty window of a chat switch) only ARMS the key
 * (`undefined` → observed): the open seat (T085/T066) owns the open
 * scroll, and a typist already active at the open never hijacks it —
 * which is also why empty-window commits are skipped entirely (the
 * re-arm marker must survive them). Own sends stay the T079 effect's
 * business alone.
 */
function applyTypingFollow(deps: {
  readonly listRef: RefObject<HTMLOListElement | null>
  readonly messages: readonly Message[]
  readonly typingKey: string
  readonly typingKeyRef: RefObject<string | undefined>
  readonly bottomRef: RefObject<boolean>
}): void {
  if (deps.messages.length === 0) {
    return
  }
  const previousTypingKey = deps.typingKeyRef.current
  deps.typingKeyRef.current = deps.typingKey
  if (
    previousTypingKey === undefined ||
    previousTypingKey === deps.typingKey ||
    deps.typingKey === ''
  ) {
    return
  }
  if (!deps.bottomRef.current) {
    return
  }
  const list = deps.listRef.current
  if (list === null) {
    return
  }
  list.scrollTop = list.scrollHeight
  // Cold-layout correction (bug 8б, T086): the typing row mounts
  // behind a content-visibility placeholder, so the write above
  // lands against an underestimated scrollHeight — the shared
  // post-paint correction loop re-drives it (settleBottomScroll).
  if (typeof requestAnimationFrame === 'function') {
    settleBottomScroll(list, () => deps.listRef.current === list)
  }
}

/**
 * Post-paint bottom-scroll correction loop shared by the T079 send
 * autoscroll and the T067 typing-row follow (bug 8б, T086): the
 * `scrollTop = scrollHeight` write lands against a COLD layout
 * (content-visibility placeholders keep the scrollHeight low), so
 * after every paint the distance to the real bottom is re-checked
 * and the scroll re-driven until it stays within the trailing-aware
 * tolerance for SUSTAINED frames (late renders keep rippling after
 * the last correction) — bounded to the SCROLL_SETTLE_FRAMES budget.
 * jsdom's zero layout (and any settled bottom) rests quickly.
 */
function settleBottomScroll(list: HTMLOListElement, isLive: () => boolean): void {
  const settle = (framesLeft: number, stable: number): void => {
    if (!isLive()) {
      return
    }
    if (list.scrollHeight - list.scrollTop - list.clientHeight <= SCROLL_SETTLE_TOLERANCE_PX) {
      stable += 1
    } else {
      stable = 0
      if (framesLeft > 0) {
        list.scrollTop = list.scrollHeight
      }
    }
    if (stable < SCROLL_SETTLE_STABLE_FRAMES && framesLeft > 0) {
      requestAnimationFrame(() => {
        settle(framesLeft - 1, stable)
      })
    }
  }
  requestAnimationFrame(() => {
    settle(SCROLL_SETTLE_FRAMES, 0)
  })
}

/**
 * 008a Phase 11 (T068; прототип jumpToMessage :1563): прыжок к
 * найденному сообщению — `scrollIntoView({block:'center',
 * behavior:'smooth'})` строки `li[data-mid]` + золотая вспышка
 * `.flash` пузыря с перезапуском анимации (remove → reflow → add —
 * повторный клик по той же строке вспыхивает снова). Прыжок к
 * ДРУГОЙ строке гасит вспышку предыдущей — подсветка всегда одна.
 * Класс пишется императивно: React-проп className строк не меняется,
 * ререндеры вспышку не стирают. jsdom без scrollIntoView — тихо без
 * прокрутки; id вне окна (сообщение не загружено) — no-op.
 */
function jumpToRow(
  list: HTMLOListElement | null,
  messageId: string,
  lastFlashRef: RefObject<HTMLElement | null>,
): void {
  if (list === null) {
    return
  }
  const escaped =
    typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
      ? CSS.escape(messageId)
      : messageId
  const row = list.querySelector<HTMLElement>(`li[data-mid="${escaped}"]`)
  if (row === null) {
    return
  }
  if (typeof row.scrollIntoView === 'function') {
    row.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }
  if (lastFlashRef.current !== null && lastFlashRef.current !== row) {
    lastFlashRef.current.classList.remove('flash')
  }
  row.classList.remove('flash')
  forceReflow(row)
  row.classList.add('flash')
  lastFlashRef.current = row
}

/**
 * T067: the THIRD bottom key — the fact/composition of the typing
 * row ('' — absent; otherwise the participant ids sorted
 * alphabetically, so an appearance, a disappearance or a «{имя} и
 * ещё N печатают…» composition change re-keys it).
 */
function typingKeyOf(typing: readonly TypingParticipant[]): string {
  if (typing.length === 0) {
    return ''
  }
  return `typing:${typing
    .map((participant) => participant.userId)
    .sort((a, b) => a.localeCompare(b))
    .join(',')}`
}

/**
 * T079 append detection: the bottom-most LOCAL row of the window —
 * optimistic `pending` first, outbox records second. An older-page
 * prepend keeps the key (the newest content does not move), an
 * append changes it.
 */
function lastLocalKeyOf(
  pending: readonly PendingMessage[],
  outbox: readonly OutboxRecord[],
): string | undefined {
  if (pending.length > 0) {
    return `pending:${pending.at(-1)?.clientMessageId}`
  }
  if (outbox.length > 0) {
    return `outbox:${outbox.at(-1)?.clientMessageId}`
  }
  return undefined
}

/**
 * T066: the unread barrier — the index the «Непрочитанные
 * сообщения» divider renders above. Until the seat fires it follows
 * the LIVE boundary (the FIRST unread INCOMING row of the window,
 * `senderId ≠ me && seq > unreadFromSeq`); the fire freezes the
 * boundary seq, and every later render of this open places the
 * divider by the FROZEN value (−1 when the open carried no unread
 * barrier at all).
 */
function findBarrierIndex(
  messages: readonly Message[],
  currentUserId: string,
  unreadFromSeq: number,
  frozen: number | null | undefined,
): number {
  if (frozen === null) {
    return -1
  }
  if (frozen === undefined) {
    return messages.findIndex(
      (message) => message.senderId !== currentUserId && message.seq > unreadFromSeq,
    )
  }
  return messages.findIndex((message) => message.seq === frozen)
}

/** T092/T066: the seat anchor candidates of the rendered window. */
interface SeatCandidates {
  readonly anchorId: string | undefined
  readonly anchorBlock: ScrollLogicalPosition | undefined
  readonly dividerCandidate: boolean
  readonly lastUnreadId: string | undefined
}

const NO_SEAT_CANDIDATES: SeatCandidates = {
  anchorId: undefined,
  anchorBlock: undefined,
  dividerCandidate: false,
  lastUnreadId: undefined,
}

/**
 * T092/T066 seat-candidate resolution —
 *  • a window WITH unread incoming inside it (barrierIndex > 0 — a
 *    loaded row precedes the run's head): the divider carries
 *    'start' and the LAST unread incoming row 'end'; the seat effect
 *    measures the block and picks one (T066);
 *  • NO unread incoming in the window (barrierIndex −1): the feed's
 *    very last row with block:'end' — the degenerate seat (the open
 *    lands in the end — a chat ending with own sends opens at its
 *    own tail, bug 13);
 *  • the window STARTS with the unread incoming run (barrierIndex
 *    0): NO candidates — the seat effect drives №14 pages down to
 *    the true boundary.
 */
function seatCandidatesOf(
  messages: readonly Message[],
  currentUserId: string,
  unreadFromSeq: number | null,
  barrierIndex: number,
): SeatCandidates {
  if (unreadFromSeq === null || messages.length === 0) {
    return NO_SEAT_CANDIDATES
  }
  if (barrierIndex === -1) {
    return { ...NO_SEAT_CANDIDATES, anchorId: messages.at(-1)?.id, anchorBlock: 'end' }
  }
  if (barrierIndex === 0) {
    return NO_SEAT_CANDIDATES
  }
  let lastUnreadId: string | undefined
  for (let index = messages.length - 1; index >= barrierIndex; index -= 1) {
    const candidate = messages[index]
    if (candidate !== undefined && candidate.senderId !== currentUserId) {
      lastUnreadId = candidate.id
      break
    }
  }
  return { ...NO_SEAT_CANDIDATES, dividerCandidate: true, lastUnreadId }
}

/** The FeedRow seat-anchor prop of one message row (T092/T066). */
function rowSeatAnchor(
  messageId: string,
  candidates: SeatCandidates,
): ScrollLogicalPosition | undefined {
  if (candidates.dividerCandidate && messageId === candidates.lastUnreadId) {
    return 'end'
  }
  if (messageId === candidates.anchorId) {
    return candidates.anchorBlock
  }
  return undefined
}

/** Terminal-failure reason label (the plain or queue-overflow one). */
function failedReasonOf(entry: OutboxRecord): string {
  return entry.errorCode === QUEUE_OVERFLOW_ERROR_CODE
    ? 'не отправлено (переполнение очереди)'
    : 'не отправлено'
}

/** The feed renders the plain empty state only with nobody typing. */
function isFeedEmpty(
  messages: readonly Message[],
  pending: readonly PendingMessage[],
  outbox: readonly OutboxRecord[],
  typing: readonly TypingParticipant[],
): boolean {
  return messages.length === 0 && pending.length === 0 && outbox.length === 0 && typing.length === 0
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
   * T085 → bug 13 T091 → bug 14 T092 → 008a Phase 9 T066): the
   * `myReadUpToSeq` latched from the first №13 ChatView answer of the
   * current open (useChatMessages). The unread run is the INCOMING
   * messages with `seq > unreadFromSeq` (own outgoing rows are read
   * by the author the moment they leave) — its FIRST row carries the
   * «Непрочитанные сообщения» divider above it, and the open seats
   * the feed by the size of the divider + unread-run block: it fits
   * the viewport → block:'end' at the LAST unread row; it does not →
   * block:'start' at the divider (T066). A window with NO unread
   * incoming degenerates to the feed's very last row — the open
   * seats in the end (bug 13: the own-send tail lands in view).
   * `null`/omitted — the watermark is not known yet (№13 in flight):
   * the seat waits and never fires if it never arrives (a failed №13
   * keeps the current behaviour).
   */
  readonly unreadFromSeq?: number | null
  /**
   * Ids the outbox engine confirmed via 201/200 that may have never
   * rendered as optimistic rows (bug 8в, T086): when the ack races
   * the first render of the outbox row (a batched flush skips the
   * intermediate state entirely), `localIdsRef` never held the id and
   * the append used to lose the own-branch. The wiring (MessengerPage
   * feeds this from useOutbox's `onConfirmed`) vouches for such ids:
   * the server copy scrolls as own, unconditionally.
   */
  readonly ownAckIds?: ReadonlySet<string>
  /**
   * Typing participants of the open chat (feature 008a, US2, T037;
   * FR-006–FR-008, ui-behavior §2.2): raw client-cache lookups —
   * entries whose name resolved (№11 peer / №28 roster, US1 chain)
   * render in the SINGLE TypingRow instance after the feed; unknown
   * `userId`s are suppressed (TypingRow). The realtime wiring of the
   * list itself is T038 (MessengerPage).
   */
  readonly typing?: readonly TypingParticipant[]
  /**
   * Запрос прыжка к найденному сообщению (008a Phase 11, T068; прототип
   * jumpToMessage chats.html:1563): клик строки-результата псевдо-поиска
   * создаёт НОВЫЙ объект ({messageId, requestId}) — эффект прокручивает
   * ленту к строке `li[data-mid]` (scrollIntoView block:'center',
   * smooth прототипа) и запускает золотую вспышку пузыря `.flash`
   * (srFlash 1.8s) с перезапуском на повторный клик. null/отсутствие —
   * прыжка нет; не найденный id (сообщение за пределами окна) — тихий
   * no-op.
   */
  readonly jumpTo?: { readonly messageId: string; readonly requestId: number } | null
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
  /**
   * The seat anchor row and its alignment (T092, bug 14): 'start' —
   * the FIRST unread incoming (the unread run is read from its
   * first row); 'end' — the degenerate last-row seat of a window
   * without unread incoming (bug 13). Undefined — not the anchor.
   */
  readonly seatAnchor: ScrollLogicalPosition | undefined
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
      data-mid={message.id}
      data-seat-anchor={seatAnchor}
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

/** Per-render row-rendering context (pure — no hooks inside). */
interface FeedRowContext {
  readonly messages: readonly Message[]
  readonly currentUserId: string
  readonly hasOlder: boolean
  readonly barrierIndex: number
  readonly readUpToSeq: number
  readonly senderNames: Map<string, string> | undefined
  readonly meAvatarSource: string
  readonly peerUsername: string | undefined
  readonly seat: SeatCandidates
  readonly localIds: ReadonlySet<string>
}

/**
 * Avatar source of an incoming row (T022, FR-024): usernames
 * wherever the surface knows them (the №28 roster first, the direct
 * peer second), the stable user id as the deterministic fallback.
 */
function incomingAvatarSource(ctx: FeedRowContext, senderId: string): string {
  return ctx.senderNames?.get(senderId) ?? ctx.peerUsername ?? senderId
}

/** One server-message map entry: date divider, unread divider, row. */
function renderMessageRow(message: Message, index: number, ctx: FeedRowContext) {
  const outgoing = message.senderId === ctx.currentUserId
  const read = outgoing && message.seq <= ctx.readUpToSeq
  const sender = outgoing ? undefined : ctx.senderNames?.get(message.senderId)
  // Date divider (FR-019): one per local calendar-day run — before
  // a message whose day differs from its predecessor's, and above
  // the oldest row once the history is known complete (`hasOlder`
  // false); a same-day pagination junction never grows a second
  // one (data-model 1.3).
  const previous = index > 0 ? ctx.messages[index - 1] : undefined
  const startsNewDay =
    previous === undefined
      ? !ctx.hasOlder
      : dayKeyOf(message.createdAt) !== dayKeyOf(previous.createdAt)
  return (
    <Fragment key={message.id}>
      {startsNewDay && <li className="date-divider">{formatDate(message.createdAt)}</li>}
      {index === ctx.barrierIndex && (
        // T066 (008a Phase 9): the unread-run divider — the
        // `.date-divider` lexica accented by message-list.css
        // (`.unread-divider`), a STATIC non-interactive
        // separator whose accessible name names the run; the
        // 'start' seat candidate while the boundary sits inside
        // the window (barrierIndex > 0).
        <li
          className="date-divider unread-divider"
          role="separator"
          aria-label="Непрочитанные сообщения"
          data-seat-anchor={ctx.seat.dividerCandidate ? 'start' : undefined}
        >
          <span className="unread-dot" aria-hidden="true" />
          {'Непрочитанные сообщения'}
        </li>
      )}
      <FeedRow
        message={message}
        outgoing={outgoing}
        read={read}
        sender={sender}
        avatarSource={outgoing ? ctx.meAvatarSource : incomingAvatarSource(ctx, message.senderId)}
        stampAnim={ctx.localIds.has(message.id)}
        seatAnchor={rowSeatAnchor(message.id, ctx.seat)}
      />
    </Fragment>
  )
}

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
  ownAckIds,
  typing = [],
  jumpTo = null,
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
  /**
   * Bottom typing-row key of the previous commit (T067): `undefined` —
   * never observed (fresh open, the tracking is armed only).
   */
  const typingKeyRef = useRef<string | undefined>(undefined)

  useEffect(() => {
    keepPrependViewport(listRef.current, messages, anchorHeightRef, firstMessageIdRef)
  }, [messages])

  const handleScroll = () => {
    handleFeedScroll(
      listRef.current,
      { loadingOlder, hasOlder, onLoadOlder },
      bottomRef,
      anchorHeightRef,
    )
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
  /**
   * T066 snapshot of the unread-divider boundary: the seq of the
   * FIRST unread incoming of the commit the seat fired at (`null` —
   * this open carried no unread barrier at all), `undefined` — the
   * seat has not fired yet and the divider follows the live boundary
   * (№14 catch-up, wholly-unread natural top). Frozen once per open
   * so realtime appends and later prepends never move or sprout the
   * divider; reset by the chat-switch re-arm below.
   */
  const unreadBarrierSeqRef = useRef<number | null | undefined>(undefined)
  useEffect(() => {
    if (messages.length === 0) {
      seatArmedRef.current = true
      seatLoadedOldestRef.current = null
      unreadBarrierSeqRef.current = undefined
      typingKeyRef.current = undefined
    }
  }, [messages])

  useEffect(() => {
    applyOpenSeat({
      listRef,
      messages,
      currentUserId,
      unreadFromSeq,
      hasOlder,
      loadingOlder,
      onLoadOlder,
      seatArmedRef,
      seatLoadedOldestRef,
      unreadBarrierSeqRef,
    })
  }, [messages, unreadFromSeq, hasOlder, loadingOlder, onLoadOlder, currentUserId])

  const confirmedIds = new Set(messages.map((message) => message.id))
  const activePending = pending.filter((entry) => !confirmedIds.has(entry.clientMessageId))
  const activeOutbox = outbox.filter((entry) => !confirmedIds.has(entry.clientMessageId))

  // Typing row gate (T037): the row renders only when at least one
  // participant resolved — and an empty chat with somebody typing
  // keeps the real feed (the empty state steps aside).
  const knownTyping = renderableTyping(typing)

  // T079 append detection: the bottom-most SERVER row and the
  // bottom-most LOCAL row of the window. An older-page prepend keeps
  // both keys (the newest content does not move), an append changes
  // exactly one of them.
  const lastServerId = messages.at(-1)?.id
  const lastLocalKey = lastLocalKeyOf(activePending, activeOutbox)
  const typingKey = typingKeyOf(knownTyping)

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

  useEffect(() => {
    applyBottomAutoscroll({
      listRef,
      lastServerId,
      lastLocalKey,
      ownAckIds,
      lastServerIdRef,
      lastLocalKeyRef,
      localIdsRef,
      bottomRef,
    })
  })

  useEffect(() => {
    localIdsRef.current = new Set(
      [...activePending, ...activeOutbox].map((entry) => entry.clientMessageId),
    )
  })

  useEffect(() => {
    applyTypingFollow({ listRef, messages, typingKey, typingKeyRef, bottomRef })
  })

  const lastFlashRef = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (jumpTo === null) {
      return
    }
    jumpToRow(listRef.current, jumpTo.messageId, lastFlashRef)
  }, [jumpTo])

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

  // T066: the unread barrier and the seat candidates (the frozen
  // snapshot semantics live in findBarrierIndex/seatCandidatesOf).
  let barrierIndex = -1
  if (unreadFromSeq !== null && messages.length > 0) {
    barrierIndex = findBarrierIndex(
      messages,
      currentUserId,
      unreadFromSeq,
      unreadBarrierSeqRef.current,
    )
  }
  const seat = seatCandidatesOf(messages, currentUserId, unreadFromSeq, barrierIndex)

  if (isFeedEmpty(messages, activePending, activeOutbox, knownTyping)) {
    return <p className="messenger-empty">Сообщений пока нет</p>
  }

  const feedCtx: FeedRowContext = {
    messages,
    currentUserId,
    hasOlder,
    barrierIndex,
    readUpToSeq,
    senderNames,
    meAvatarSource,
    peerUsername,
    seat,
    localIds: localIdsRef.current,
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
      {messages.map((message, index) => renderMessageRow(message, index, feedCtx))}
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
          failedReason={failedReasonOf(entry)}
          avatarSource={meAvatarSource}
          onRetry={onRetry}
          onRemove={onRemove}
        />
      ))}
      {knownTyping.length > 0 && <TypingRow typing={knownTyping} />}
    </ol>
  )
}
