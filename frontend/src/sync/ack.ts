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
 * in sequential ≤100-item requests within one flush. A failed request
 * keeps its entries pending (№25 is atomic — no partial effects) and
 * re-arms the timer, so the unsuccessful ack is retried by the NEXT
 * flush (≤ 1 s later even without new traffic); retries are safe — №25
 * is idempotent and monotonic server-side (GREATEST).
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
 */
import { deliveryAck } from '../api/chats'
import type { DeliveryAckItem } from '../api/chats'

/** Upper bound on the age of the oldest pending item (sync-protocol.md §2). */
export const ACK_DEBOUNCE_MS = 1000

/** Contract limit of №25: `acks` must not exceed 100 items (api-contract.md §1). */
export const ACK_BATCH_LIMIT = 100

export interface AckBatcher {
  /**
   * Records a delivery position to confirm: applied realtime frame,
   * applied sync/ascending page tail, or truncation point. Merges
   * monotonically per chat (only max — mirror of the server GREATEST);
   * arms the debounce timer if idle. Invalid input is ignored.
   */
  ack(chatId: string, seq: number): void
  /**
   * Sends everything pending now (sequential ≤100-item requests).
   * Resolves when the whole tail is confirmed; rejects on the first
   * failed request — the entries stay pending and the retry timer is
   * already re-armed, so a rejection only reports, never strands.
   * A call while a flush is in flight joins it (single-flight).
   */
  flush(): Promise<void>
  /** Number of chats with an unconfirmed position (diagnostics/tests). */
  pendingSize(): number
  /** Cancels the timer and drops pending entries (logout/teardown). */
  dispose(): void
}

class DeliveryAckBatcher implements AckBatcher {
  private readonly pending = new Map<string, number>()
  private timer: number | null = null
  private inFlight: Promise<void> | null = null

  ack(chatId: string, seq: number): void {
    if (typeof chatId !== 'string' || chatId.length === 0) {
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

  dispose(): void {
    if (this.timer !== null) {
      window.clearTimeout(this.timer)
      this.timer = null
    }
    this.pending.clear()
  }

  /**
   * Leading-edge debounce: armed by the first pending item and never
   * reset, so the oldest entry is confirmed within ACK_DEBOUNCE_MS. A
   * timer firing during an in-flight drain is harmless — the drain loop
   * re-checks the map on every iteration and flush() joins it.
   */
  private schedule(): void {
    if (this.timer !== null) {
      return
    }
    this.timer = window.setTimeout(() => {
      this.timer = null
      this.flush().catch(() => {
        // entries stay pending; drain() has re-armed the retry timer
      })
    }, ACK_DEBOUNCE_MS)
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
      } catch (cause) {
        this.schedule()
        throw cause
      }
      // A larger seq may have merged for the same chat while the
      // request was in flight — only the confirmed value is consumed.
      for (const chatId of chunk.keys()) {
        if (this.pending.get(chatId) === chunk.get(chatId)) {
          this.pending.delete(chatId)
        }
      }
    }
  }
}

/** Standalone batcher for custom owners and tests (no shared registry). */
export function createAckBatcher(): AckBatcher {
  return new DeliveryAckBatcher()
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
  const instance = createAckBatcher()
  const batcher: AckBatcher = {
    ack: (chatId, seq) => instance.ack(chatId, seq),
    flush: () => instance.flush(),
    pendingSize: () => instance.pendingSize(),
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
