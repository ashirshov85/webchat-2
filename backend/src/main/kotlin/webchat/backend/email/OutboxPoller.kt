package webchat.backend.email

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.email.templates.EmailTemplates
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Transactional outbox dispatcher (T021; data-model.md §5, FR-008, SC-007).
 *
 * Every tick claims due `pending` rows with `FOR UPDATE SKIP LOCKED` — safe
 * with N replicas and duplicate-send-free: the row lock is held across the
 * synchronous [EmailGateway] send, so a concurrent poller skips an in-flight
 * row instead of double-delivering it. A gateway outage never surfaces on
 * public endpoints: a failed send only bumps `attempts` and pushes
 * `next_attempt_at` further out.
 *
 * Retry policy (research.md §11 unified schedule; base in T021, completed in
 * T052): the delay before each attempt depends only on its ordinal — attempts
 * 2–4 run 60 s apart (initial attempt + 3 retries), attempts 5–10 back off
 * 5→15→30→60→60→60 min (1 h cap). The attempt counter is continuous across
 * both stages: a `pending` row that already spent its US1-era retries (≤3
 * extra attempts under the old parking) keeps counting toward the same
 * 10-attempt cap on this very schedule. A row whose 10th attempt fails is
 * marked `failed_permanent` with a secret-free `last_error` — it leaves the
 * `pending` queue and is never claimed again.
 *
 * Delivery observability (T051, FR-008, SC-001, research.md §6): every
 * attempt bumps `email_delivery_total{type,outcome}` (sent | failed), a
 * successful mark-sent records `email_delivery_duration{type,outcome=sent}`
 * = `sent_at − created_at` — the end-to-end letter latency the SC-001
 * budget is judged by — and `email_outbox_pending` gauges the queue depth.
 * Each attempt also logs its structured trail: `outbox_id`, `email_type`,
 * `recipient_hash`, `attempt`, `provider_error`. `recipient_hash` is an
 * unpeppered SHA-256 of the address, the same shape as the `rl:email:*`
 * rate-limit keys (data-model.md §7), so delivery troubles correlate with
 * the limit/cooldown keys of the same account.
 *
 * Security contract (SC-005, SC-007): the payload and the rendered letter
 * carry the open one-time token and are never logged; only a truncated,
 * secret-free error description reaches `last_error` and `provider_error`.
 */
@Component
class OutboxPoller(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val emailGateway: EmailGateway,
    private val emailTemplates: EmailTemplates,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(OutboxPoller::class.java)

    init {
        Gauge
            .builder(PENDING_GAUGE) { pendingRowCount() }
            .description("Transactional outbox rows awaiting delivery (status = pending)")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${auth.outbox.poll-fixed-delay}")
    fun dispatchPending() {
        var dispatched = 0
        while (dispatched < MAX_ROWS_PER_TICK && transactionTemplate.execute { dispatchDueRow() } == true) {
            dispatched++
        }
    }

    @Suppress("TooGenericExceptionCaught") // EmailGateway signals any delivery failure by throwing
    private fun dispatchDueRow(): Boolean {
        val row = claimDueRow() ?: return false
        try {
            val rendered = emailTemplates.render(row.type, row.payload)
            emailGateway.send(
                EmailMessage(recipient = row.recipientEmail, subject = rendered.subject, text = rendered.text),
            )
            markSent(row)
        } catch (e: Exception) {
            scheduleRetryOrFailPermanently(row, e)
        }
        return true
    }

    private fun claimDueRow(): PendingRow? =
        jdbcTemplate
            .query(CLAIM_SQL) { rs, _ -> mapRow(rs) }
            .firstOrNull()

    private fun mapRow(rs: ResultSet): PendingRow {
        val typeValue = rs.getString("email_type")
        val type =
            EmailType.entries.firstOrNull { it.databaseValue == typeValue }
                ?: error("Unknown email_outbox.email_type: $typeValue")
        return PendingRow(
            id = rs.getObject("id", UUID::class.java),
            recipientEmail = rs.getString("recipient_email"),
            type = type,
            payload = objectMapper.readValue(rs.getString("payload"), PAYLOAD_TYPE),
            attempts = rs.getInt("attempts"),
        )
    }

    /**
     * Marks the claimed row `sent` and records the delivery observability
     * (T051): the outcome counter, the end-to-end timer (`sent_at −
     * created_at`, both on the database clock — SC-001) read back through
     * `UPDATE ... RETURNING`, and the secret-free structured success trail.
     */
    private fun markSent(row: PendingRow) {
        val deliverySeconds = jdbcTemplate.queryForObject(MARK_SENT_SQL, Double::class.javaObjectType, row.id) ?: 0.0
        meterRegistry.counter(DELIVERY_TOTAL, "type", row.type.databaseValue, "outcome", OUTCOME_SENT).increment()
        Timer
            .builder(DELIVERY_DURATION)
            .description("End-to-end delivery latency: sent_at minus created_at (SC-001)")
            .tag("type", row.type.databaseValue)
            .tag("outcome", OUTCOME_SENT)
            .register(meterRegistry)
            .record((deliverySeconds * NANOS_PER_SECOND).toLong(), TimeUnit.NANOSECONDS)
        log.info(
            "email delivered: outbox_id={} email_type={} recipient_hash={} attempt={} delivery_seconds={}",
            row.id,
            row.type.databaseValue,
            recipientHash(row.recipientEmail),
            row.attempts + 1,
            deliverySeconds,
        )
    }

    private fun scheduleRetryOrFailPermanently(
        row: PendingRow,
        error: Exception,
    ) {
        meterRegistry.counter(DELIVERY_TOTAL, "type", row.type.databaseValue, "outcome", OUTCOME_FAILED).increment()
        val providerError = errorDescription(error)
        log.warn(
            "email delivery failed: outbox_id={} email_type={} recipient_hash={} attempt={} provider_error={}",
            row.id,
            row.type.databaseValue,
            recipientHash(row.recipientEmail),
            row.attempts + 1,
            providerError,
        )
        val failedAttemptNumber = row.attempts + 1
        if (failedAttemptNumber >= MAX_ATTEMPTS) {
            jdbcTemplate.update(FAIL_PERMANENT_SQL, providerError, row.id)
        } else {
            val delaySeconds = retryDelaySeconds(nextAttempt = failedAttemptNumber + 1)
            jdbcTemplate.update(SCHEDULE_RETRY_SQL, delaySeconds, providerError, row.id)
        }
    }

    private fun pendingRowCount(): Double =
        jdbcTemplate
            .queryForObject(PENDING_COUNT_SQL, Long::class.javaObjectType)
            ?.toDouble() ?: 0.0

    private fun recipientHash(recipientEmail: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(recipientEmail.toByteArray(StandardCharsets.UTF_8))
            .toHexString()

    private fun errorDescription(e: Exception): String = e.toString().take(ERROR_MAX_LENGTH)

    private data class PendingRow(
        val id: UUID,
        val recipientEmail: String,
        val type: EmailType,
        val payload: Map<String, String>,
        val attempts: Int,
    )

    private companion object {
        val CLAIM_SQL =
            """
            SELECT id, recipient_email, email_type::text AS email_type, payload::text AS payload, attempts
            FROM email_outbox
            WHERE status = 'pending' AND next_attempt_at <= now()
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """.trimIndent()

        val MARK_SENT_SQL =
            """
            UPDATE email_outbox
            SET status = 'sent', attempts = attempts + 1, last_error = NULL, sent_at = now()
            WHERE id = ?
            RETURNING EXTRACT(epoch FROM (sent_at - created_at))::double precision
            """.trimIndent()

        val PENDING_COUNT_SQL =
            """
            SELECT count(*) FROM email_outbox WHERE status = 'pending'
            """.trimIndent()

        val SCHEDULE_RETRY_SQL =
            """
            UPDATE email_outbox
            SET attempts = attempts + 1,
                next_attempt_at = now() + make_interval(secs => ?::int),
                last_error = ?
            WHERE id = ?
            """.trimIndent()

        val FAIL_PERMANENT_SQL =
            """
            UPDATE email_outbox
            SET status = 'failed_permanent', attempts = attempts + 1, next_attempt_at = 'infinity', last_error = ?
            WHERE id = ?
            """.trimIndent()

        val PAYLOAD_TYPE = object : TypeReference<Map<String, String>>() {}

        // T051 observability contract names (research.md §6, FR-008, SC-001)
        const val DELIVERY_TOTAL = "email_delivery_total"
        const val DELIVERY_DURATION = "email_delivery_duration"
        const val PENDING_GAUGE = "email_outbox_pending"
        const val OUTCOME_SENT = "sent"
        const val OUTCOME_FAILED = "failed"
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val SHA_256 = "SHA-256"

        const val MAX_ROWS_PER_TICK = 100
        const val MAX_ATTEMPTS = 10

        // research.md §11 unified retry schedule: seconds to wait before the
        // given attempt number (1 h cap). Attempts 2–4 are the T021 base,
        // 5–10 the T052 backoff; the ordinal is continuous across stages.
        val RETRY_SCHEDULE_SECONDS: Map<Int, Int> =
            mapOf(
                2 to 60,
                3 to 60,
                4 to 60,
                5 to 5 * 60,
                6 to 15 * 60,
                7 to 30 * 60,
                8 to 60 * 60,
                9 to 60 * 60,
                10 to 60 * 60,
            )

        fun retryDelaySeconds(nextAttempt: Int): Int =
            requireNotNull(RETRY_SCHEDULE_SECONDS[nextAttempt]) {
                "no retry slot for attempt $nextAttempt beyond the $MAX_ATTEMPTS-attempt cap"
            }

        const val ERROR_MAX_LENGTH = 1000
    }
}
