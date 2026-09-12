package webchat.backend.email

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.email.templates.EmailTemplates
import java.sql.ResultSet
import java.util.UUID

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
 * US1 retry policy (research.md §11 unified schedule): attempts 2–4 run
 * `RETRY_INTERVAL_SECONDS` apart (initial attempt + 3 retries). A row that
 * exhausted them stays `pending`, parked at `next_attempt_at = infinity` so
 * it neither spins nor leaves the queue; T052 continues the unified schedule
 * from attempt 5 up to the 10-attempt cap and `failed_permanent`.
 *
 * Security contract (SC-005, SC-007): the payload and the rendered letter
 * carry the open one-time token and are never logged; only a truncated,
 * secret-free error description reaches `last_error`.
 */
@Component
class OutboxPoller(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val emailGateway: EmailGateway,
    private val emailTemplates: EmailTemplates,
    private val objectMapper: ObjectMapper,
) {
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
            jdbcTemplate.update(MARK_SENT_SQL, row.id)
        } catch (e: Exception) {
            scheduleRetryOrPark(row.id, row.attempts, e)
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

    private fun scheduleRetryOrPark(
        id: UUID,
        attempts: Int,
        error: Exception,
    ) {
        if (attempts + 1 >= MAX_ATTEMPTS) {
            jdbcTemplate.update(PARK_SQL, errorDescription(error), id)
        } else {
            jdbcTemplate.update(SCHEDULE_RETRY_SQL, RETRY_INTERVAL_SECONDS, errorDescription(error), id)
        }
    }

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
            """.trimIndent()

        val SCHEDULE_RETRY_SQL =
            """
            UPDATE email_outbox
            SET attempts = attempts + 1,
                next_attempt_at = now() + make_interval(secs => ?::int),
                last_error = ?
            WHERE id = ?
            """.trimIndent()

        val PARK_SQL =
            """
            UPDATE email_outbox
            SET attempts = attempts + 1, next_attempt_at = 'infinity', last_error = ?
            WHERE id = ?
            """.trimIndent()

        val PAYLOAD_TYPE = object : TypeReference<Map<String, String>>() {}

        const val MAX_ROWS_PER_TICK = 100
        const val MAX_ATTEMPTS = 4
        const val RETRY_INTERVAL_SECONDS = 60
        const val ERROR_MAX_LENGTH = 1000
    }
}
