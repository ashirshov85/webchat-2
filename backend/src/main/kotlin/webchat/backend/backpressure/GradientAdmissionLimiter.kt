package webchat.backend.backpressure

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import webchat.backend.config.DeliveryProperties
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.max

/**
 * T040 (US4; research.md 005 §6, data-model сущность 6): the adaptive
 * per-instance concurrency limiter behind [SendAdmissionGate] — an
 * in-flight counter against an AIMD-controlled limit driven by the
 * gradient of the send-path latency (the EWMA of admission-to-settle
 * PG-commit holds vs the configured `latency-baseline`):
 *
 *  * **Admit/Shed** — the check is NON-blocking (a try-acquire
 *    semaphore): `in-flight < limit` admits holding one slot, anything
 *    at or beyond the limit is shed instantly as
 *    [AdmissionDecision.Shed] with the drain estimate of §6
 *    (`ceil(in-flight × EWMA / max(limit, 1))` seconds, floored at 1) —
 *    the api layer of T041 renders it as the `503 server_busy` +
 *    `Retry-After`. Every shed grows
 *    `webchat_backpressure_rejections_total` (SC-005: never a silent
 *    refusal).
 *  * **AIMD by gradient** — only a durably settled send ([AdmissionLease.acked]
 *    → `201/200`) feeds its hold duration into the EWMA (α = 0.2); a
 *    refused send abandons its sample so a fast 4xx/429 can never
 *    deflate the overload signal. `EWMA > baseline` (gradient > 1 —
 *    the PG-commit path stretches) shrinks the limit multiplicatively
 *    (× 0.9, FR-013 degradation); a healthy sample grows it linearly
 *    (+ 1) back to `max-limit` (SC-006 self-healing — exactly what an
 *    operator watches on `webchat_backpressure_limit`).
 *  * **Transport state** — everything lives in memory per instance and
 *    self-heals by measuring: a reset/restart simply re-enters at the
 *    configured bounds (`limit = max-limit`, `EWMA = baseline`), which
 *    is safe (constitution II — the SSE-registry precedent of 004).
 *
 * No new dependencies; the whole controller is one lock-guarded state
 * cell (~100 lines, plan.md VII).
 */
@ConditionalOnProperty(
    prefix = "delivery.backpressure",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@Component
class GradientAdmissionLimiter(
    deliveryProperties: DeliveryProperties,
    admissionMetrics: AdmissionMetrics,
) : SendAdmissionGate,
    AdmissionMetrics.LimiterState {
    private val minLimit: Double = deliveryProperties.backpressure.minLimit.toDouble()
    private val maxLimit: Double = deliveryProperties.backpressure.maxLimit.toDouble()
    private val baselineNanos: Double =
        deliveryProperties.backpressure.latencyBaseline
            .toNanos()
            .toDouble()

    /** The whole limiter state — admit/shed, AIMD and the EWMA — under one lock. */
    private val lock = ReentrantLock()

    private var inFlight = 0
    private var limit = maxLimit
    private var ewmaNanos = baselineNanos

    private val rejectionsTotal = admissionMetrics.rejectionsTotal()

    init {
        // T039: bind the FR-014 state gauges once, BEFORE the first send —
        // the gauge samples this bean by a weak reference it outlives.
        admissionMetrics.registerLimiterGauges(this)
    }

    override fun currentLimit(): Double = lock.withLock { limit }

    override fun currentInFlight(): Double = lock.withLock { inFlight.toDouble() }

    override fun admit(
        chatId: UUID,
        senderId: UUID,
    ): AdmissionDecision =
        lock.withLock {
            if (inFlight >= limit) {
                rejectionsTotal.increment()
                AdmissionDecision.Shed(retryAfterSecondsLocked())
            } else {
                inFlight += 1
                AdmissionDecision.Admitted(AdmissionLeaseImpl(System.nanoTime()))
            }
        }

    /**
     * research.md §6: the integral drain estimate
     * `ceil(in-flight × EWMA_латентности / max(limit, 1))` seconds,
     * floored at 1 — how long the parked work needs to drain before a
     * retried send can be admitted. [inFlight] ≥ [limit] ≥ 1 on the shed
     * path, and the EWMA is strictly positive, so the ceil itself never
     * rounds to zero — the floor guards the exact-zero corner anyway.
     */
    private fun retryAfterSecondsLocked(): Long {
        val drainSeconds = inFlight * (ewmaNanos / NANOS_PER_SECOND) / max(limit, 1.0)
        return ceil(drainSeconds).toLong().coerceAtLeast(RETRY_AFTER_FLOOR_SECONDS)
    }

    /**
     * The lease of one admitted send: [close] settles it exactly once —
     * the slot always goes back, and only an [AdmissionLease.acked]
     * settle feeds the admission-to-settle hold into the EWMA/AIMD.
     */
    private inner class AdmissionLeaseImpl(
        private val admittedAtNanos: Long,
    ) : AdmissionLease {
        private var acked = false
        private var closed = false

        override fun acked() {
            acked = true
        }

        override fun close() {
            if (closed) return
            closed = true
            val sampleNanos = if (acked) System.nanoTime() - admittedAtNanos else null
            lock.withLock {
                inFlight -= 1
                if (sampleNanos != null) {
                    ewmaNanos += EWMA_ALPHA * (sampleNanos - ewmaNanos)
                    limit =
                        if (ewmaNanos > baselineNanos) {
                            (limit * AIMD_SHRINK).coerceAtLeast(minLimit)
                        } else {
                            (limit + AIMD_GROWTH).coerceAtMost(maxLimit)
                        }
                }
            }
        }
    }

    private companion object {
        /** research.md §6: the EWMA smoothing of the latency samples. */
        const val EWMA_ALPHA = 0.2

        /** research.md §6: the AIMD step — multiplicative shrink × 0.9, linear growth + 1. */
        const val AIMD_SHRINK = 0.9
        const val AIMD_GROWTH = 1.0

        /** api-contract.md §3: `Retry-After` is integral and ≥ 1 second. */
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
