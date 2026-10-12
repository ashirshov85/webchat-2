package webchat.backend.auth.onboarding

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.contacts.domain.port.ContactRepository
import java.util.UUID

/**
 * T031 [US4] (research.md §3 / data-model.md §1.1, §1.4, §3.1–3.2,
 * FR-011/FR-013, SC-003): the starter-contact onboarding core — the single
 * `ensureOnboarded(userId)` operation BOTH delivery paths converge on: the
 * AFTER_COMMIT `@Async` listener of T032 and the `GET /contacts`
 * self-healing fallback of T035. With T031 alone nothing calls it yet —
 * the wiring tasks light it up.
 *
 * The method runs in its own NEW transaction ([Propagation.REQUIRES_NEW]):
 * the event path arrives with no ambient transaction, and the fallback path
 * must never join (or mark rollback-only) whatever context a future caller
 * of the contacts listing might open. Inside, the whole body is best-effort:
 * any failure is logged (always with an explicit `user_id` — the `@Async`
 * pool of T032 does not carry the MDC `traceId`) and counted as `failed`,
 * never rethrown — the loss is self-healed by the NEXT `GET /contacts`
 * (data-model.md §3.1–3.2).
 *
 * Onboarding is a latch, never a toggle (data-model.md §1.1):
 * 1. point read of `users.starter_contact_added` by the PK — `TRUE` exits
 *    forever (a user who DELETED the admin contact is never re-added,
 *    US4 AC5; a pre-V17 account latched by the migration gets no backfill,
 *    spec.md:109 edge);
 * 2. resolve the admin id by `lower(username) = 'admin'` — a missing admin
 *    (seed skipped or failed, T030 best-effort) leaves the flag `FALSE`
 *    with a warn + counter, so the NEXT listing retries (spec.md:111 edge);
 * 3. self-guard: the admin never onboards itself (spec.md:112 edge);
 * 4. ONE transaction: the idempotent contact insert
 *    ([ContactRepository.add] — `INSERT … ON CONFLICT DO NOTHING` of the
 *    V11 composite PK) + the conditional
 *    `UPDATE … WHERE id = ? AND NOT starter_contact_added`.
 *
 * The races «event × fallback × redelivery» all collapse into the
 * `ON CONFLICT DO NOTHING` insert + the conditional UPDATE: the
 * `(owner, admin)` pair exists 0 or 1 times, never more (SC-003).
 *
 * Observability: `starter_contact_total{outcome=onboarded|admin_missing|failed}`.
 */
@Service
class StarterContactService(
    private val userRepository: UserRepository,
    private val contactRepository: ContactRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(StarterContactService::class.java)

    /**
     * Ensures the system admin is a contact of [userId] — idempotent,
     * best-effort, self-healing. Safe to call from ANY context: a request
     * thread (the fallback of T035) or the async pool (the listener of
     * T032); it never throws and never mutates anything beyond the single
     * contact row and the monotone flag.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureOnboarded(userId: UUID) {
        try {
            onboard(userId)
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // best-effort by design (research.md §3): the delivery loss is
            // covered by the self-healing fallback — never break the caller
            log.warn(
                "starter contact onboarding failed for user_id={} — will be retried by the next contacts listing",
                userId,
                ex,
            )
            meterRegistry.counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_FAILED).increment()
        }
    }

    @Suppress("ReturnCount") // each guard clause is one onboarding exit of data-model.md §3.2
    private fun onboard(userId: UUID) {
        // 1. the eternal short-circuit: the flag is a one-way latch (data-model.md §1.1)
        val alreadyOnboarded = onboardingFlagOf(userId) ?: return
        if (alreadyOnboarded) return

        // 2. resolve the admin by lower(username)='admin' (research.md §1/§3)
        val adminId = userRepository.findByUsername(ADMIN_USERNAME)?.id
        if (adminId == null) {
            log.warn(
                "starter contact skipped for user_id={}: the system account 'admin' is missing " +
                    "(seed not executed or failed) — the flag stays FALSE for a retry on the next listing",
                userId,
            )
            meterRegistry.counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_ADMIN_MISSING).increment()
            return
        }

        // 3. self-guard: the admin is never its own starter contact (spec.md:112)
        if (adminId == userId) return

        // 4. ONE transaction: idempotent insert + conditional latch — a
        // redelivery racing past the flag read collapses into the SAME row
        contactRepository.add(userId, adminId)
        jdbcTemplate.update(MARK_ONBOARDED_SQL, userId)
        log.info("starter contact onboarded for user_id={}", userId)
        meterRegistry.counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_ONBOARDED).increment()
    }

    /** Point read of the latch by the PK; `null` = the user row is gone — nothing to onboard. */
    private fun onboardingFlagOf(userId: UUID): Boolean? =
        jdbcTemplate
            .queryForList(FLAG_SQL, Boolean::class.java, userId)
            .firstOrNull()

    private companion object {
        /** The system username the admin is resolved by (research.md §1). */
        const val ADMIN_USERNAME = "admin"

        /** Monotone latch: repeatable without effect, `TRUE → FALSE` does not exist (data-model.md §1.1). */
        const val MARK_ONBOARDED_SQL =
            """
            UPDATE users SET starter_contact_added = TRUE WHERE id = ? AND NOT starter_contact_added
            """

        const val FLAG_SQL =
            """
            SELECT starter_contact_added FROM users WHERE id = ?
            """

        const val METRIC_TOTAL = "starter_contact_total"

        const val TAG_OUTCOME = "outcome"

        const val OUTCOME_ONBOARDED = "onboarded"

        const val OUTCOME_ADMIN_MISSING = "admin_missing"

        const val OUTCOME_FAILED = "failed"
    }
}
