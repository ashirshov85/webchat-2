package webchat.backend.backpressure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * The FR-014 observability of the admission control (T039; research.md §8,
 * tasks.md T045): the state gauges `webchat_backpressure_limit` /
 * `webchat_backpressure_inflight` and the shed counter
 * `webchat_backpressure_rejections_total`, on the shared Micrometer stack
 * of 001 — no new dependencies.
 *
 * The limiter implementation (T040) OWNS the state; this component only
 * projects it onto meters: [registerLimiterGauges] binds both gauges to a
 * [LimiterState] (bind once, at the limiter's construction, so the
 * SC-006 recovery watch on `webchat_backpressure_limit` exists before the
 * first send; the gauge samples the state by a WEAK reference — the
 * limiter bean must hold it for its whole lifetime), and every shed
 * decision increments [rejectionsTotal] exactly once — SC-005's «0 silent
 * refusals»: every overload refusal of the send path is a counted 503.
 */
@Component
class AdmissionMetrics(
    private val meterRegistry: MeterRegistry,
) {
    /**
     * The observable state of one limiter instance — what the two gauges
     * sample. [currentLimit] is the adaptive AIMD bound of the moment (a
     * recovery climbs it back to the configured base, SC-006/FR-013);
     * [currentInFlight] is the number of №16 sends currently holding an
     * admission slot.
     */
    interface LimiterState {
        fun currentLimit(): Double

        fun currentInFlight(): Double
    }

    /**
     * Binds the FR-014 state gauges of the send-path admission control to
     * [state]: `webchat_backpressure_limit` (the AIMD-controlled
     * concurrency bound) and `webchat_backpressure_inflight` (the held
     * admission slots). Called once per limiter lifetime.
     */
    fun registerLimiterGauges(state: LimiterState) {
        Gauge
            .builder(BACKPRESSURE_LIMIT, state) { it.currentLimit() }
            .description(
                "Admission limit of the send path: the AIMD-controlled concurrency bound; " +
                    "recovery = the limit climbing back to its base (SC-006/FR-013)",
            ).register(meterRegistry)
        Gauge
            .builder(BACKPRESSURE_INFLIGHT, state) { it.currentInFlight() }
            .description("№16 sends currently holding an admission slot (the in-flight of the per-instance limiter)")
            .register(meterRegistry)
    }

    /**
     * SC-005: one increment per shed decision — every overload refusal of
     * the send path is an explicit, counted `503 server_busy`, never a
     * silent one.
     */
    fun rejectionsTotal(): Counter =
        Counter
            .builder(BACKPRESSURE_REJECTIONS_TOTAL)
            .description(
                "Admission-control refusals of the №16 send path: one per 503 server_busy shed (SC-005)",
            ).register(meterRegistry)

    private companion object {
        /** research.md 005 §8 / tasks.md T045: the FR-014 meter names. */
        const val BACKPRESSURE_LIMIT = "webchat_backpressure_limit"
        const val BACKPRESSURE_INFLIGHT = "webchat_backpressure_inflight"
        const val BACKPRESSURE_REJECTIONS_TOTAL = "webchat_backpressure_rejections_total"
    }
}
