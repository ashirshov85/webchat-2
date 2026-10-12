package webchat.backend.auth.onboarding

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * T032 [US4] (research.md §2, FR-011, US4 AC2): the asynchronous
 * AFTER_COMMIT delivery path of the starter-contact onboarding. The event
 * is published INSIDE the registration transaction by the two account
 * activation points (T033 `RegistrationService.setPassword`, T034
 * `IdentityResolutionService.provisionJitAccount`); this listener only
 * runs once that transaction has COMMITTED — a rolled-back registration
 * never onboards anything.
 *
 * `@Async` moves the work off the request thread onto the Boot
 * auto-configured executor (virtual threads, T029 `@EnableAsync`): the
 * registration response latency never pays for the onboarding, and an
 * async void listener can never break the publisher.
 *
 * The whole body is best-effort: [StarterContactService.ensureOnboarded]
 * swallows its own failures by design, so this catch covers what escapes
 * anyway — e.g. the REQUIRES_NEW transaction proxy failing before the
 * method body is entered. A delivery loss is never fatal: the
 * self-healing `GET /contacts` fallback of T035 restores it later
 * (research.md §3), which is exactly why no durable queue is needed
 * (constitution VII).
 *
 * Observability: the MDC `traceId` does NOT propagate into the @Async
 * pool, so every log line names the `user_id` explicitly;
 * `starter_contact_listener_total{outcome=failed}` counts the losses the
 * fallback is expected to heal.
 */
@Component
class StarterContactEventListener(
    private val starterContactService: StarterContactService,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(StarterContactEventListener::class.java)

    /**
     * Hands the freshly activated account to [StarterContactService.ensureOnboarded]
     * — idempotent, so a redelivery or a race with the T035 fallback collapses
     * into the same single contact row (SC-003).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onRegistrationCompleted(event: RegistrationCompletedEvent) {
        try {
            starterContactService.ensureOnboarded(event.userId)
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // best-effort by design (research.md §2–3): the async void never
            // breaks the caller, but the loss must not pass silently — the
            // next contacts listing self-heals it
            log.warn(
                "starter contact event delivery failed for user_id={} — " +
                    "the self-healing contacts listing will cover it",
                event.userId,
                ex,
            )
            meterRegistry.counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_FAILED).increment()
        }
    }

    private companion object {
        /** Distinct from the service's `starter_contact_total` — no double counting of its outcomes. */
        const val METRIC_TOTAL = "starter_contact_listener_total"

        const val TAG_OUTCOME = "outcome"

        const val OUTCOME_FAILED = "failed"
    }
}
