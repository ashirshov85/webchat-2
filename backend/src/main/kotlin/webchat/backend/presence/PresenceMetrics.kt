package webchat.backend.presence

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import webchat.backend.presence.domain.port.PresencePublishedStatus
import webchat.backend.presence.domain.port.PresenceStore

/**
 * T010 — the FR-009/SC-008 observability vocabulary of
 * contracts/presence-events.md §5 and research.md §F, on the shared
 * Micrometer stack of 001 — no new dependencies:
 *
 *  * counter `webchat_presence_events_published_total{status}` — one
 *    increment per PUBLISHED transition (`online`/`offline` legs of the
 *    §1.2 state machine), wired by the fan-out publication (T017) —
 *    FR-009's «количество опубликованных событий присутствия» and the
 *    §Load audience-size estimator of plan.md;
 *  * counter `webchat_presence_hysteresis_suppressed_total` — one
 *    increment per due offq entry a returning registration cancelled
 *    (T025, `OfflineDueOutcome.Suppressed`) — the SC-002 «метро» signal:
 *    a series of gaps shorter than the window yields ZERO observed
 *    switches, and every suppression is counted, never silent;
 *  * gauge `webchat_presence_online_users` — the projection of the
 *    atomically maintained Redis counter `presence:online:count`
 *    (`PresenceStore.readOnlineUsersCount`, maintained inside the SAME
 *    Lua transitions — T008), sampled by periodic reading at observation
 *    time (research §F: the transition counter is exact by construction,
 *    a ZCARD of `presence:watch` was rejected — it counts expired-but-
 *    unreaped members too);
 *  * timer `webchat_realtime_push_seconds{event=presence.updated}` —
 *    NOT a new pipe: the presence frames ride the EXISTING 004 push
 *    pipeline metric, tagged with the `presence.updated` №18 `event:`
 *    value so the publish/dispatch legs of the SC-001 delivery latency
 *    are separable from the messaging frames ([startPush], wired by the
 *    T017 adapter over RedisRealtimePublisher).
 *
 * The names carry the Prometheus suffixes verbatim (`_total` is
 * idempotent for counters, `_seconds` is the timer base unit), so
 * `/actuator/prometheus` renders exactly the four series quickstart §3
 * step 4 and T036 assert (по образцу GroupObservabilityIT).
 *
 * Constitution V: tag values carry the status/event vocabulary only —
 * never user or connection identifiers.
 */
@Component
class PresenceMetrics(
    private val meterRegistry: MeterRegistry,
    private val presenceStore: PresenceStore,
) {
    /**
     * The gauge registration (research §F «экспорт — периодическое
     * чтение»): Micrometer samples the store's atomic online counter
     * when the exposition is read; the strong field reference keeps the
     * weakly-referenced gauge alive for the bean's lifetime.
     */
    @Suppress("UnusedPrivateProperty") // the ONLY strong reference keeping the weakly-held gauge alive
    private val onlineUsersGauge: Gauge =
        Gauge
            .builder(ONLINE_USERS) { presenceStore.readOnlineUsersCount().toDouble() }
            .description(
                "Currently published-online users: the atomic presence:online:count Redis counter " +
                    "maintained by the Lua status transitions (FR-009/SC-008)",
            ).register(meterRegistry)

    /**
     * Counts one published `presence.updated` transition of [status] —
     * wired by the fan-out publication (T017) on every
     * `PresenceTransition.Switched` leg; a suppressed/unchanged CAS
     * never increments (FR-003 echo suppression stays unobservable).
     */
    fun countEventPublished(status: PresencePublishedStatus) {
        meterRegistry
            .counter(EVENTS_PUBLISHED_TOTAL, TAG_STATUS, status.name.lowercase())
            .increment()
    }

    /**
     * SC-002: one metro gap that returned inside the hysteresis window
     * and was therefore suppressed — wired by the offq poller (T025) on
     * `OfflineDueOutcome.Suppressed`; the counter may only grow.
     */
    fun countHysteresisSuppressed() {
        hysteresisSuppressedTotal().increment()
    }

    /**
     * Starts a `webchat_realtime_push_seconds{event=presence.updated}`
     * sample of the REUSED 004 push-pipeline timer; the caller stops it
     * with [PresencePushSample.stop] on the leg it measured — the SC-001
     * «transition → frame» latency signal of the T017 adapter.
     */
    fun startPush(): PresencePushSample = PresencePushSample(Timer.start(meterRegistry))

    private fun hysteresisSuppressedTotal(): Counter =
        Counter
            .builder(HYSTERESIS_SUPPRESSED_TOTAL)
            .description(
                "Offline publishes suppressed by the hysteresis window: due offq entries cancelled by a " +
                    "returning registration (SC-002)",
            ).register(meterRegistry)

    private fun pushTimer(stage: PushStage): Timer =
        Timer
            .builder(PUSH_SECONDS)
            .description(
                "Realtime push pipeline latency of the presence.updated frames: the post-commit publish " +
                    "leg and the SSE frame dispatch leg (SC-001)",
            ).tag(TAG_EVENT, EVENT_PRESENCE_UPDATED)
            .tag(TAG_STAGE, stage.wireValue)
            .register(meterRegistry)

    /** A running `webchat_realtime_push_seconds{event=presence.updated}` measurement stopped exactly once. */
    inner class PresencePushSample(
        private val sample: Timer.Sample,
    ) {
        /** Stops the sample on the measured [stage] leg of the push pipeline. */
        fun stop(stage: PushStage) {
            sample.stop(pushTimer(stage))
        }
    }

    /** The two timed legs of the reused 004 push pipeline (research 004 §11). */
    enum class PushStage(
        val wireValue: String,
    ) {
        PUBLISH("publish"),
        DISPATCH("dispatch"),
    }

    private companion object {
        // contracts/presence-events.md §5 / research.md §F names (FR-009, SC-008)
        const val EVENTS_PUBLISHED_TOTAL = "webchat_presence_events_published_total"
        const val HYSTERESIS_SUPPRESSED_TOTAL = "webchat_presence_hysteresis_suppressed_total"
        const val ONLINE_USERS = "webchat_presence_online_users"

        /** research.md §F: the REUSED 004 push-pipeline timer (no new pipe). */
        const val PUSH_SECONDS = "webchat_realtime_push_seconds"

        /** contracts/presence-events.md §2: the №18 `event:` value of the presence frame. */
        const val EVENT_PRESENCE_UPDATED = "presence.updated"

        const val TAG_STATUS = "status"
        const val TAG_EVENT = "event"
        const val TAG_STAGE = "stage"
    }
}
