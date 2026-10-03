package webchat.backend.realtime

import java.util.UUID

/**
 * The presence registration seam of the №18 channel (tasks.md 007 T013;
 * contracts/presence-events.md §1, data-model 007 §1.1): the realtime
 * channel mints one connectionId per SSE open, frames it to the client
 * as the `connected` opening frame right after `retry: 3000` and hands
 * the REGISTRATION lifecycle over to the presence feature through this
 * port. The dependency direction follows plan.md 007 (Structure
 * Decision): the integration lives in `realtime/` and calls presence
 * LATE-BOUND — Spring wires whatever bean implements this interface
 * (T014 `PresenceService`), so the channel itself stays
 * presence-agnostic and keeps working before/without presence exactly
 * as it did through 004–006.
 *
 * Contract duties of an implementation:
 *  - the calls must be CHEAP (the open leg runs on the request thread of
 *    the SSE handshake, SC-001 «online» ≤ 2 s);
 *  - they must never assume the call arrived on the instance that opened
 *    the stream — registrations live in the shared store, pods stay
 *    stateless (constitution II, FR-005);
 *  - [onConnectionClosed] may fire more than once per connection
 *    (completion and error callbacks can both run) and for connections
 *    whose registration already expired — a repeat is a no-op
 *    (PresenceStore.unregister, FR-002/FR-003 idempotence).
 */
interface PresenceConnectionLifecycle {
    /**
     * SSE №18 open: the registration leg (data-model 007 §1.1) —
     * [connectionId] was just framed to the client as `connected` and is
     * the id the №37 heartbeat will renew; [sessionId] is the `sid` claim
     * of the access token that opened the stream, tying the registration
     * to its 002 session for the per-session logout (T029, edge
     * «мультидевайс-logout»).
     */
    fun onConnectionOpened(
        userId: UUID,
        sessionId: UUID,
        connectionId: UUID,
    )

    /**
     * SSE №18 close/error/completion: the unregister leg — the emitter's
     * lifecycle callbacks of RealtimeController funnel here; a MISSED
     * close (half-open TCP, crashed pod) is NOT this port's problem: the
     * registration self-expires by its TTL score and the watch poller
     * reaps it (FR-002, SC-003).
     */
    fun onConnectionClosed(
        userId: UUID,
        connectionId: UUID,
    )
}
