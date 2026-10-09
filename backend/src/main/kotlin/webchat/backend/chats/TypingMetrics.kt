package webchat.backend.chats

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import webchat.backend.chats.domain.port.TypingEvent
import webchat.backend.chats.domain.port.TypingStartedEvent
import webchat.backend.chats.domain.port.TypingStoppedEvent

/**
 * T030 — the FR-017 observability vocabulary of
 * contracts/realtime-events.md 008a §4, on the shared Micrometer stack of
 * 001 — no new dependencies:
 *
 *  * counter `webchat_typing_events_total{event}` — one increment per
 *    PUBLISHED typing frame (`started`/`stopped` legs of the data-model
 *    §2.1 state machine), wired by
 *    [TypingService][webchat.backend.chats.domain.service.TypingService]
 *    on every fan-out it hands to the №18 pipe — the published-events
 *    signal the SC-008 smoke correlates with the push timer (the
 *    `webchat_typing_state_expired_total` sibling of the poller joins in
 *    T032);
 *  * counter `webchat_typing_flood_suppressed_total` — one increment per
 *    №41 signal the `rl:user:typing:` bucket refuses (429 `flood_limit`,
 *    FR-009), wired by the api layer (T031): a refused signal publishes
 *    nothing, and the suppression itself is counted, never silent (the
 *    SC-002 «no chatter» signal — the observers never see the storm).
 *
 * The names carry the Prometheus suffixes verbatim (`_total` is
 * idempotent for counters), so `/actuator/prometheus` renders exactly
 * the series realtime-events.md §4 and T062 assert.
 *
 * Constitution V: the single `event` tag carries the frame vocabulary
 * only — never chat or user identifiers.
 */
@Component
class TypingMetrics(
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Counts one PUBLISHED typing frame — the [event] leg
     * [TypingService][webchat.backend.chats.domain.service.TypingService]
     * hands to the at-most-once pipe; a suppressed signal (the DIRECT
     * block pair, an idempotent repeat `stop`, a refused flood token)
     * never increments.
     */
    fun countEventPublished(event: TypingEvent) {
        meterRegistry
            .counter(TYPING_EVENTS_TOTAL, TAG_EVENT, eventName(event))
            .increment()
    }

    /**
     * FR-009: one №41 signal the `rl:user:typing:` bucket refused —
     * wired by the api layer (T031) on every `429 flood_limit` answer;
     * the counter may only grow.
     */
    fun countFloodSuppressed() {
        floodSuppressedTotal().increment()
    }

    /** realtime-events.md 008a §1: the №18 `event:` name of the frame — exhaustive over the sealed hierarchy. */
    private fun eventName(event: TypingEvent): String =
        when (event) {
            is TypingStartedEvent -> STARTED
            is TypingStoppedEvent -> STOPPED
        }

    private fun floodSuppressedTotal(): Counter =
        Counter
            .builder(TYPING_FLOOD_SUPPRESSED_TOTAL)
            .description(
                "Typing signals refused by the rl:user:typing flood bucket: " +
                    "429 flood_limit, nothing published (FR-009)",
            ).register(meterRegistry)

    private companion object {
        // realtime-events.md 008a §4 observability contract names (FR-017)
        const val TYPING_EVENTS_TOTAL = "webchat_typing_events_total"
        const val TYPING_FLOOD_SUPPRESSED_TOTAL = "webchat_typing_flood_suppressed_total"

        const val TAG_EVENT = "event"

        /** The two §1 frame vocabulary values of the `event` tag. */
        const val STARTED = "started"
        const val STOPPED = "stopped"
    }
}
