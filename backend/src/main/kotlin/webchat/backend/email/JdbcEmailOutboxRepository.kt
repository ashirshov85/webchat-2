package webchat.backend.email

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

enum class EmailType(
    val databaseValue: String,
) {
    EMAIL_VERIFICATION("email_verification"),
    EMAIL_VERIFICATION_REPEAT("email_verification_repeat"),
    PASSWORD_SETUP("password_setup"),
    PASSWORD_RESET("password_reset"),
}

/** Resend cooldown step family (data-model.md §5): verification letters share one step. */
val EmailType.cooldownFamily: Set<EmailType>
    get() =
        when (this) {
            EmailType.EMAIL_VERIFICATION, EmailType.EMAIL_VERIFICATION_REPEAT ->
                setOf(EmailType.EMAIL_VERIFICATION, EmailType.EMAIL_VERIFICATION_REPEAT)
            EmailType.PASSWORD_SETUP -> setOf(EmailType.PASSWORD_SETUP)
            EmailType.PASSWORD_RESET -> setOf(EmailType.PASSWORD_RESET)
        }

/**
 * Transactional outbox writer (data-model.md §5; FR-002, FR-008, SC-007).
 *
 * [insert] runs inside the caller's transaction — the pending row is committed
 * atomically with the user and one-time token rows, so a crash between commit
 * and dispatch can never lose the letter (research.md §6). Dispatch itself is
 * the [OutboxPoller]'s concern (T021); this type only enqueues and answers the
 * resend-cooldown question.
 *
 * Security contract: the payload carries the open one-time token value for the
 * email link and is therefore never logged (SC-005); only hashes may leave
 * this class (e.g. recipient_hash in the poller's metrics, T051).
 */
@Repository
class JdbcEmailOutboxRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @param:Value("\${auth.resend.cooldown}") private val resendCooldown: Duration,
) {
    /**
     * Enqueues a `pending` letter with the DB defaults for status/attempts/
     * next_attempt_at/created_at; timestamps stay on the database clock so the
     * cooldown window is evaluated consistently regardless of app-host skew.
     */
    fun insert(
        userId: UUID,
        recipientEmail: String,
        type: EmailType,
        payload: Map<String, String>,
    ) {
        jdbcTemplate.update(
            INSERT_SQL,
            UUID.randomUUID(),
            userId,
            recipientEmail,
            type.databaseValue,
            objectMapper.writeValueAsString(payload),
        )
    }

    /**
     * Resend cooldown remaining for the step family of [type] (US1-6,
     * research.md §11 second tier): `null` when a new letter may be queued —
     * no family row exists or the last one is older than the configured
     * `auth.resend.cooldown`; otherwise the remaining window rounded up to
     * whole seconds (the caller's `Retry-After`).
     *
     * The window is measured from `MAX(created_at)` across the family
     * regardless of status (pending/sent/failed_permanent): the poller marks
     * rows `sent` within seconds, so a pending-only filter would empty the
     * cooldown (data-model.md §5). Per-user in PG, the check survives Redis
     * loss and ignores source-IP rotation.
     */
    fun resendCooldownRemaining(
        userId: UUID,
        type: EmailType,
    ): Duration? {
        val familyValues = type.cooldownFamily.joinToString(",") { it.databaseValue }
        val remainingSeconds =
            jdbcTemplate
                .query(
                    COOLDOWN_SQL,
                    { rs, _ -> rs.getObject(1, java.lang.Long::class.java)?.toLong() },
                    resendCooldown.seconds,
                    userId,
                    familyValues,
                ).firstOrNull()
        return remainingSeconds
            ?.takeIf { it > 0 }
            ?.let(Duration::ofSeconds)
    }

    private companion object {
        val INSERT_SQL =
            """
            INSERT INTO email_outbox (id, user_id, recipient_email, email_type, payload)
            VALUES (?, ?, ?, ?::email_type, ?::jsonb)
            """.trimIndent()

        val COOLDOWN_SQL =
            """
            SELECT CEIL(EXTRACT(epoch FROM MAX(created_at) + make_interval(secs => ?) - now()))::bigint
            FROM email_outbox
            WHERE user_id = ?
              AND email_type = ANY (string_to_array(?, ',')::email_type[])
            """.trimIndent()
    }
}
