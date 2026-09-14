package webchat.backend.auth.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.auth.domain.model.RevokedReason
import webchat.backend.auth.domain.model.Session
import webchat.backend.auth.domain.model.SessionStatus
import webchat.backend.auth.domain.port.SessionRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC adapter for [SessionRepository] (data-model.md §3): conditional status
 * transitions evaluated against the database clock — rowcount 0 leaves the
 * session untouched (logout idempotence, FR-011); password change revokes all
 * active sessions of the user and returns their ids for sid denylisting
 * (FR-010).
 */
@Repository
class JdbcSessionRepository(
    private val jdbcTemplate: JdbcTemplate,
) : SessionRepository {
    override fun insert(session: Session) {
        jdbcTemplate.update(
            INSERT_SQL,
            session.id,
            session.userId,
            session.status.name.lowercase(),
            Timestamp.from(session.createdAt),
            Timestamp.from(session.lastRefreshedAt),
            toDb(session.revokedAt),
            session.revokedReason?.name?.lowercase(),
        )
    }

    override fun findById(id: UUID): Session? = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id).firstOrNull()

    override fun markRefreshed(id: UUID) {
        jdbcTemplate.update(MARK_REFRESHED_SQL, id)
    }

    override fun revoke(
        id: UUID,
        reason: RevokedReason,
    ): Boolean = jdbcTemplate.update(REVOKE_SQL, reason.name.lowercase(), id) == 1

    override fun markCompromised(id: UUID): Boolean = jdbcTemplate.update(MARK_COMPROMISED_SQL, id) == 1

    override fun revokeAllForUser(
        userId: UUID,
        reason: RevokedReason,
    ): List<UUID> =
        jdbcTemplate.queryForList(
            REVOKE_ALL_FOR_USER_SQL,
            UUID::class.java,
            reason.name.lowercase(),
            userId,
        )

    private fun toDb(instant: Instant?): Timestamp? = instant?.let(Timestamp::from)

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Session(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    status = SessionStatus.valueOf(rs.getString("status").uppercase()),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    lastRefreshedAt = rs.getTimestamp("last_refreshed_at").toInstant(),
                    revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                    revokedReason = rs.getString("revoked_reason")?.let { RevokedReason.valueOf(it.uppercase()) },
                )
            }

        val INSERT_SQL =
            """
            INSERT INTO sessions (id, user_id, status, created_at, last_refreshed_at, revoked_at, revoked_reason)
            VALUES (?, ?, ?::session_status, ?, ?, ?, ?::session_revoked_reason)
            """.trimIndent()

        val FIND_BY_ID_SQL =
            """
            SELECT id, user_id, status, created_at, last_refreshed_at, revoked_at, revoked_reason
            FROM sessions
            WHERE id = ?
            """.trimIndent()

        val MARK_REFRESHED_SQL =
            """
            UPDATE sessions
            SET last_refreshed_at = now()
            WHERE id = ? AND status = 'active'
            """.trimIndent()

        val REVOKE_SQL =
            """
            UPDATE sessions
            SET status = 'revoked', revoked_at = now(), revoked_reason = ?::session_revoked_reason
            WHERE id = ? AND status = 'active'
            """.trimIndent()

        val MARK_COMPROMISED_SQL =
            """
            UPDATE sessions
            SET status = 'compromised', revoked_at = now(),
                revoked_reason = 'refresh_reuse_detected'
            WHERE id = ? AND status = 'active'
            """.trimIndent()

        val REVOKE_ALL_FOR_USER_SQL =
            """
            UPDATE sessions
            SET status = 'revoked', revoked_at = now(), revoked_reason = ?::session_revoked_reason
            WHERE user_id = ? AND status = 'active'
            RETURNING id
            """.trimIndent()
    }
}
