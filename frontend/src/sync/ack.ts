/**
 * Delivery-ack debounce batcher (feature 005, T018; sync-protocol.md §2):
 * the server delivery position moves ONLY through №25, so every applied
 * delivery — realtime `message.created` frames, applied №26/№15 pages,
 * the truncation point — funnels into this batcher, which confirms
 * `upToSeq = max(seq)` per chat in batched requests (amortization N:1 —
 * one call per debounce window, never per frame; research.md §1).
 *
 * Debounce: the FIRST pending item arms a flush ≤ 1 s later
 * (ACK_DEBOUNCE_MS); items arriving inside the window merge into the
 * same flush through the per-chat max. Requests carry at most
 * ACK_BATCH_LIMIT items (contract №25): an oversized pending set drains
 * in sequential ≤100-item requests within one flush. A TRANSIENTLY
 * failed request keeps its entries pending (№25 is atomic — no partial
 * effects) and re-arms the timer with an exponential backoff (T088:
 * 1 s → 2 s → 4 s … cap 30 s, reset on success), so the unsuccessful
 * ack is retried by the NEXT flush even without new traffic; retries
 * are safe — №25 is idempotent and monotonic server-side (GREATEST).
 * A TERMINALLY refused request (dead chatId, T088) never retries: the
 * culprit is dropped and the drain confirms the rest (see the T088
 * section below).
 *
 * The batcher is in-memory transport buffering, deliberately NOT
 * persisted: the local cursor (cursors.ts, T017) may legitimately run
 * ahead of the server position by at most the debounce window, and №26
 * takes the effective cursor max(client, server) — a lost un-acked tail
 * costs only a slightly behind delivery position until the next ack
 * source (research.md §7).
 *
 * Per-account instances (getAckBatcher, like the shared SSE stream of
 * useRealtime) keep one user's acks from ever being sent under another
 * user's token across a logout→login switch; dispose() cancels the
 * timer and drops the tail.
 *
 * §5.5 drop (feature 006, US5, T058; realtime-group-events.md §5.5):
 * after `group.you_removed` (and `group.deleted` — US6, T066) the
 * server treats the caller as a non-participant for the group's
 * chatId, and №25 is all-or-refusal — a batch carrying it is rejected
 * WHOLE (403 not_participant). `drop(chatId)` therefore tombstones the
 * chat: its pending position is discarded (never sent) and acks
 * arriving AFTER the removal are suppressed too — the §1 order race
 * may still feed frames committed before the removal through
 * useChatMessages/useSync, and those late acks must not poison the
 * batch. The tombstone is session-local like the pending map itself;
 * dispose() drops it together with the tail.
 *
 * T088 (009, bug-fix of the eternal №25 retry loop): a missed
 * `group.deleted` frame (closed tab) left the dead chatId in the
 * PERSISTED cursors, so each new session's T052 re-feed re-queued it
 * and №25 rejected the whole batch 404 chat_not_found once a second —
 * the live chats merged into the same batch never confirmed either.
 * The drain now CLASSIFIES failures by ApiProblem:
 * - terminal codes (404 chat_not_found, 403 not_participant,
 *   400 invalid_up_to_seq — the position is unreachable forever: the
 *   chat/membership is gone and last_seq never rolls back) isolate the
 *   culprit: a one-element chunk drops it outright; a multi-element
 *   chunk is replayed element-by-element (sequential ≤100-item
 *   requests are already the contract's drain shape), single terminal
 *   refusals drop and the rest confirm. `drop()` of a per-account
 *   batcher ALSO forgets the persisted cursor (cursors.ts
 *   forgetCursor) — the only deletion of the cursor map — so the dead
 *   chatId cannot resurrect from localStorage next session.
 * - everything else (network, 408, 429, 503, 5xx) is transient:
 *   entries stay pending and the retry backs off exponentially
 *   (1 s → 2 s → 4 s … capped at ACK_RETRY_MAX_MS, reset on success,
 *   honoring `retryAfterSec` on 429/503) instead of the fixed 1 s
 *   hammer. 401 stays outside the classification — apiFetch runs its
 *   single-flight refresh cycle and a retried request passes normally.
 *
 * T093 (009, unread badge of the offline recipient): a transient №25
 * failure inside the catch-up cycle's AWAITED flush now fails that
 * cycle (useSync re-anchors №12 only after a completed cycle), so the
 * consumer needs a wake-up when the retry finally succeeds — the
 * batcher fires `onRecovered` exactly once per drain that succeeds
 * AFTER ≥1 transient failure (the flag lives in the instance;
 * terminal T088 drops and quiet drains never fire it, dispose()
 * drops the listeners).
 */
import { deliveryAck } from '../api/chats'
import type { DeliveryAckItem } from '../api/chats'
import type { ApiProblem } from '../api/auth'
import { forgetCursor } from './cursors'

/** Upper bound on the age of the oldest pending item (sync-protocol.md §2). */
export const ACK_DEBOUNCE_MS = 1000

/** Contract limit of №25: `acks` must not exceed 100 items (api-contract.md §1). */
export const ACK_BATCH_LIMIT = 100

/** Cap of the transient-retry backoff (T088): 1 s → 2 s → … → 30 s. */
export const ACK_RETRY_MAX_MS = 30_000

function isApiProblem(value: unknown): value is ApiProblem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { status?: unknown }).status === 'number'
  )
}

/** Contract codes ride in `errors: {field: [code]}` (openapi Problem). */
function problemErrorCodes(problem: ApiProblem): string[] {
  return Object.values(problem.errors ?? {}).flat()
}

/**
 * №25 failures whose retry can never succeed (T088), matched as exact
 * status+code pairs of api-contract.md №25 — 404 {chat:
 * [chat_not_found]}, 403 {chat: [not_participant]}, 400 {upToSeq:
 * [invalid_up_to_seq]}; the codes ride in `Problem.errors`.
 */
function isTerminalAckProblem(cause: unknown): boolean {
  if (!isApiProblem(cause)) {
    return false
  }
  const codes = problemErrorCodes(cause)
  return (
    (cause.status === 404 && codes.includes('chat_not_found')) ||
    (cause.status === 403 && codes.includes('not_participant')) ||
    (cause.status === 400 && codes.includes('invalid_up_to_seq'))
  )
}

/** A transient №25 failure plus the server's deferral hint, if any. */
interface TransientAckFailure {
  readonly retryAfterSec?: number
}

function asTransientAckFailure(cause: unknown): TransientAckFailure {
  if (isApiProblem(cause) && cause.retryAfterSec !== undefined && cause.retryAfterSec > 0) {
    return { retryAfterSec: cause.retryAfterSec }
  }
  return {}
}

export interface AckBatcher {
  /**
   * Records a delivery position to confirm: applied realtime frame,
   * applied sync/ascending page tail, or truncation point. Merges
   * monotonically per chat (only max — mirror of the server GREATEST);
   * arms the debounce timer if idle. Invalid input is ignored.
   */
  ack(chatId: string, seq: number): void
  /**
   * §5.5 (realtime-group-events.md): tombstones a chat the user lost
   * the membership of (`group.you_removed`; `group.deleted` joins with
   * the same call in US6) — its pending position is discarded and
   * acks arriving after the removal are suppressed, because №25 is
   * all-or-refusal: a batch carrying a non-participant chatId would
   * be rejected whole (403 not_participant). Idempotent; a chat with
   * nothing pending is tombstoned all the same (the §1 race may feed
   * late acks later). A per-account batcher (getAckBatcher) ALSO
   * forgets the chat's persisted cursor (cursors.ts forgetCursor,
   * T088) — the dead chatId must not resurrect from localStorage by
   * the next session's T052 re-feed; a standalone batcher has no
   * account context and stays session-local.
   */
  drop(chatId: string): void
  /**
   * Sends everything pending now (sequential ≤100-item requests).
   * Resolves when the whole tail is confirmed; rejects on the first
   * transient failure — the entries stay pending and the retry timer
   * is already re-armed (exponential backoff, T088), so a rejection
   * only reports, never strands. A TERMINAL №25 refusal (404/403/
   * invalid_up_to_seq) never rejects: the culprit is isolated and
   * dropped, the drain confirms the rest and resolves. A call while a
   * flush is in flight joins it (single-flight).
   */
  flush(): Promise<void>
  /** Number of chats with an unconfirmed position (diagnostics/tests). */
  pendingSize(): number
  /**
   * T093: fires ONCE when a drain completes successfully AFTER at
   * least one transient failure armed the T088 backoff — the delivery
   * position has just caught up, so №12-dependent consumers (the
   * unread-badge re-anchor of the failed catch-up cycle) may re-ask
   * now instead of waiting for the next reconnect. Quiet drains (no
   * preceding transient failure) and terminal T088 drops never fire
   * it; dispose() drops the listeners.
   */
  onRecovered(listener: () => void): () => void
  /** Cancels the timer and drops pending entries (logout/teardown). */
  dispose(): void
}

class DeliveryAckBatcher implements AckBatcher {
  private readonly pending = new Map<string, number>()
  /** §5.5 tombstones: chatIds whose acks must never reach №25 again. */
  private readonly dropped = new Set<string>()
  /**
   * Owner of the persisted cursors this batcher may forget on drop()
   * (T088); null on standalone instances — they stay session-local.
   */
  private readonly userId: string | null
  /** Transient retries armed so far without an intervening success (T088 backoff). */
  private transientRetries = 0
  /**
   * A transient failure armed the backoff since the last success
   * (T093): the drain that succeeds afterwards is a «recovery» and
   * fires onRecovered exactly once.
   */
  private hadTransientFailure = false
  /** T093 recovery listeners: drain success after ≥1 transient failure. */
  private readonly recoveredListeners = new Set<() => void>()
  private timer: number | null = null
  private inFlight: Promise<void> | null = null

  constructor(userId: string | null = null) {
    this.userId = userId
  }

  ack(chatId: string, seq: number): void {
    if (typeof chatId !== 'string' || chatId.length === 0) {
      return
    }
    if (this.dropped.has(chatId)) {
      // §5.5: a late ack of a removed chat is suppressed — the §1
      // order race may still deliver frames committed before the
      // removal, and №25 is all-or-refusal.
      return
    }
    if (typeof seq !== 'number' || !Number.isSafeInteger(seq) || seq < 1) {
      return
    }
    const current = this.pending.get(chatId)
    if (current !== undefined && seq <= current) {
      return
    }
    this.pending.set(chatId, seq)
    this.schedule()
  }

  drop(chatId: string): void {
    if (typeof chatId !== 'string' || chatId.length === 0) {
      return
    }
    this.dropped.add(chatId)
    this.pending.delete(chatId)
    if (this.userId !== null) {
      // T088: the dead chatId must not resurrect from localStorage by
      // the next session's T052 re-feed (useSync).
      forgetCursor(this.userId, chatId)
    }
  }

  flush(): Promise<void> {
    if (this.inFlight !== null) {
      return this.inFlight
    }
    const run = this.drain().finally(() => {
      this.inFlight = null
    })
    this.inFlight = run
    return run
  }

  pendingSize(): number {
    return this.pending.size
  }

  onRecovered(listener: () => void): () => void {
    this.recoveredListeners.add(listener)
    return () => {
      this.recoveredListeners.delete(listener)
    }
  }

  dispose(): void {
    if (this.timer !== null) {
      window.clearTimeout(this.timer)
      this.timer = null
    }
    this.pending.clear()
    this.dropped.clear()
    this.recoveredListeners.clear()
    this.hadTransientFailure = false
  }

  /**
   * Leading-edge debounce: armed by the first pending item and never
   * reset, so the oldest entry is confirmed within ACK_DEBOUNCE_MS. A
   * timer firing during an in-flight drain is harmless — the drain loop
   * re-checks the map on every iteration and flush() joins it. A
   * transient-failure re-arm passes the T088 backoff delay (with the
   * 429/503 `retryAfterSec` floor) instead of the debounce window.
   */
  private schedule(delayMs: number = ACK_DEBOUNCE_MS): void {
    if (this.timer !== null) {
      return
    }
    this.timer = window.setTimeout(() => {
      this.timer = null
      this.flush().catch(() => {
        // entries stay pending; drain() has re-armed the retry timer
      })
    }, delayMs)
  }

  /**
   * T088 backoff of the nth consecutive transient retry:
   * 1 s → 2 s → 4 s → … capped at ACK_RETRY_MAX_MS (30 s); a 429/503
   * `retryAfterSec` raises the delay to the server's window. Reset by
   * any successful №25 request (batch or isolated single).
   */
  private transientRetryDelayMs(failure: TransientAckFailure): number {
    const backoffMs = Math.min(ACK_DEBOUNCE_MS * 2 ** this.transientRetries, ACK_RETRY_MAX_MS)
    this.transientRetries += 1
    const deferredMs = failure.retryAfterSec !== undefined ? failure.retryAfterSec * 1000 : 0
    return Math.max(backoffMs, deferredMs)
  }

  private armTransientRetry(failure: TransientAckFailure): void {
    this.hadTransientFailure = true
    this.schedule(this.transientRetryDelayMs(failure))
  }

  /**
   * Every successful №25 request lands here (the batch drain and the
   * T088 isolation singles alike — the centralized success point):
   * resets the backoff and — after a transient failure — fires the
   * T093 recovery event exactly once.
   */
  private noteSuccess(): void {
    this.transientRetries = 0
    if (this.hadTransientFailure) {
      this.hadTransientFailure = false
      for (const listener of this.recoveredListeners) {
        listener()
      }
    }
  }

  /**
   * Consumes a confirmed position: only the value actually sent is
   * removed — a larger seq merged while the request was in flight
   * stays for the drain loop's next iteration, never lost.
   */
  private consumeConfirmed(chunk: Map<string, number>): void {
    for (const [chatId, seq] of chunk) {
      if (this.pending.get(chatId) === seq) {
        this.pending.delete(chatId)
      }
    }
  }

  /**
   * T088 terminal isolation of a refused multi-element chunk: №25 is
   * all-or-refusal, so the elements are replayed one by one (the
   * contract's sequential ≤100-item requests) — a single terminal
   * refusal drops its culprit, a success consumes the entry. A
   * transient failure mid-isolation never gets stuck: the remaining
   * entries stay pending, the retry timer is armed with the backoff
   * and the cause propagates like any transient drain failure.
   */
  private async isolateTerminalCulprit(chunk: Map<string, number>): Promise<void> {
    for (const [chatId, seq] of chunk) {
      const single = new Map<string, number>([[chatId, seq]])
      try {
        await deliveryAck([{ chatId, upToSeq: seq }])
        this.noteSuccess()
        this.consumeConfirmed(single)
      } catch (cause) {
        if (!isTerminalAckProblem(cause)) {
          this.armTransientRetry(asTransientAckFailure(cause))
          throw cause
        }
        this.drop(chatId)
      }
    }
  }

  private async drain(): Promise<void> {
    while (this.pending.size > 0) {
      const chunk = new Map<string, number>()
      for (const [chatId, seq] of this.pending) {
        chunk.set(chatId, seq)
        if (chunk.size >= ACK_BATCH_LIMIT) {
          break
        }
      }
      const items: DeliveryAckItem[] = Array.from(chunk, ([chatId, upToSeq]) => ({
        chatId,
        upToSeq,
      }))
      try {
        await deliveryAck(items)
        this.noteSuccess()
      } catch (cause) {
        if (!isTerminalAckProblem(cause)) {
          // transient (network/408/429/5xx): entries stay pending and
          // the backoff re-arm replaces the old fixed-1 s hammer
          this.armTransientRetry(asTransientAckFailure(cause))
          throw cause
        }
        // terminal (T088): retrying is pointless — the position is
        // unreachable forever. A one-element chunk IS the culprit; a
        // multi-element one is replayed per element to find it.
        if (chunk.size === 1) {
          this.drop(chunk.keys().next().value as string)
        } else {
          await this.isolateTerminalCulprit(chunk)
        }
        continue
      }
      // A larger seq may have merged for the same chat while the
      // request was in flight — only the confirmed value is consumed.
      this.consumeConfirmed(chunk)
    }
  }
}

/**
 * Standalone batcher for custom owners and tests (no shared registry,
 * no persisted-cursor forgetting — an optional userId opts in to it).
 */
export function createAckBatcher(userId?: string): AckBatcher {
  return new DeliveryAckBatcher(userId ?? null)
}

const sharedBatchers = new Map<string, AckBatcher>()

/**
 * Per-account shared instance (one active client per user — Assumptions):
 * useSync (T019) and the realtime wiring (T023) must feed the same
 * batcher; per-user keying keeps positions of different accounts
 * isolated. dispose() also unregisters the instance.
 */
export function getAckBatcher(userId: string): AckBatcher {
  const registered = sharedBatchers.get(userId)
  if (registered !== undefined) {
    return registered
  }
  const instance = createAckBatcher(userId)
  const batcher: AckBatcher = {
    ack: (chatId, seq) => instance.ack(chatId, seq),
    drop: (chatId) => instance.drop(chatId),
    flush: () => instance.flush(),
    pendingSize: () => instance.pendingSize(),
    onRecovered: (listener) => instance.onRecovered(listener),
    dispose: () => {
      instance.dispose()
      if (sharedBatchers.get(userId) === batcher) {
        sharedBatchers.delete(userId)
      }
    },
  }
  sharedBatchers.set(userId, batcher)
  return batcher
}
