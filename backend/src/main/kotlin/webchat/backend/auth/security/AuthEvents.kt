package webchat.backend.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class AuthEventType(
    val databaseValue: String,
) {
    REGISTER_REQUESTED("register_requested"),
    EMAIL_CONFIRMED("email_confirmed"),
    PASSWORD_SET("password_set"),
    LOGIN_SUCCESS("login_success"),
    LOGIN_FAILED("login_failed"),
    LOGIN_THROTTLED("login_throttled"),
    REFRESH_ROTATED("refresh_rotated"),
    REFRESH_REUSE_DETECTED("refresh_reuse_detected"),
    LOGOUT("logout"),
    PASSWORD_RESET_REQUESTED("password_reset_requested"),
    PASSWORD_RESET_COMPLETED("password_reset_completed"),
    SESSION_REVOKED("session_revoked"),
}

/**
 * Security journal writer (FR-013, data-model.md §6): persists authentication
 * events to `auth_events` inside the caller's transaction.
 *
 * Security contract: event payloads must never contain passwords, open tokens
 * or raw PII. The client IP is stored only as `SHA-256(IP + server-side pepper)`.
 */
@Component
class AuthEventRecorder(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @param:Value("\${auth.ip-hash-pepper}") private val ipHashPepper: String,
) {
    fun record(
        eventType: AuthEventType,
        clientIp: String,
        userAgent: String? = null,
        userId: UUID? = null,
        details: Map<String, Any> = emptyMap(),
    ) {
        jdbcTemplate.update(
            INSERT_SQL,
            UUID.randomUUID(),
            userId,
            eventType.databaseValue,
            hashIp(clientIp),
            userAgent?.take(USER_AGENT_MAX_LENGTH),
            objectMapper.writeValueAsString(details),
            MDC.get("traceId")?.take(TRACE_ID_MAX_LENGTH),
        )
    }

    private fun hashIp(clientIp: String): String {
        val ipBytes = (clientIp + ipHashPepper).toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance(SHA_256).digest(ipBytes).toHexString()
    }

    private companion object {
        val INSERT_SQL =
            """
            INSERT INTO auth_events (id, user_id, event_type, ip_hash, user_agent, details, trace_id)
            VALUES (?, ?, ?::auth_event_type, ?, ?, ?::jsonb, ?)
            """.trimIndent()

        const val SHA_256 = "SHA-256"
        const val USER_AGENT_MAX_LENGTH = 256
        const val TRACE_ID_MAX_LENGTH = 64
    }
}
