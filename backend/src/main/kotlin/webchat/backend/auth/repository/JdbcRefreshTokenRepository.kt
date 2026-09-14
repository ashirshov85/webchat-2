package webchat.backend.auth.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.auth.domain.model.RefreshToken
import webchat.backend.auth.domain.model.RefreshTokenStatus
import webchat.backend.auth.domain.port.RefreshTokenRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * JDBC adapter for [RefreshTokenRepository] (data-model.md §4; FR-006):
 * rotation is the atomic conditional UPDATE guarded by token_hash, active
 * status and expiry — a zero rowcount means the presented generation was
 * already consumed (reuse) and must trigger chain revocation.
 */
@Repository
class JdbcRefreshTokenRepository(
    private val jdbcTemplate: JdbcTemplate,
) : RefreshTokenRepository {
    override fun insert(token: RefreshToken) {
        jdbcTemplate.update(
            INSERT_SQL,
            token.id,
            token.sessionId,
            token.tokenHash,
            token.status.name.lowercase(),
            Timestamp.from(token.expiresAt),
            token.replacedBy,
            Timestamp.from(token.createdAt),
        )
    }

    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        jdbcTemplate.query(FIND_BY_TOKEN_HASH_SQL, ROW_MAPPER, tokenHash).firstOrNull()

    override fun rotate(
        id: UUID,
        tokenHash: String,
        replacementId: UUID,
    ): Boolean = jdbcTemplate.update(ROTATE_SQL, replacementId, id, tokenHash) == 1

    override fun revokeActiveForSession(sessionId: UUID) {
        jdbcTemplate.update(REVOKE_ACTIVE_FOR_SESSION_SQL, sessionId)
    }

    override fun revokeActiveForUser(userId: UUID) {
        jdbcTemplate.update(REVOKE_ACTIVE_FOR_USER_SQL, userId)
    }

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                RefreshToken(
                    id = rs.getObject("id", UUID::class.java),
                    sessionId = rs.getObject("session_id", UUID::class.java),
                    tokenHash = rs.getString("token_hash"),
                    status = RefreshTokenStatus.valueOf(rs.getString("status").uppercase()),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    replacedBy = rs.getObject("replaced_by", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val INSERT_SQL =
            """
            INSERT INTO refresh_tokens (id, session_id, token_hash, status, expires_at, replaced_by, created_at)
            VALUES (?, ?, ?, ?::refresh_token_status, ?, ?, ?)
            """.trimIndent()

        val FIND_BY_TOKEN_HASH_SQL =
            """
            SELECT id, session_id, token_hash, status, expires_at, replaced_by, created_at
            FROM refresh_tokens
            WHERE token_hash = ?
            """.trimIndent()

        val ROTATE_SQL =
            """
            UPDATE refresh_tokens
            SET status = 'rotated', replaced_by = ?
            WHERE id = ? AND token_hash = ? AND status = 'active' AND expires_at > now()
            """.trimIndent()

        val REVOKE_ACTIVE_FOR_SESSION_SQL =
            """
            UPDATE refresh_tokens
            SET status = 'revoked'
            WHERE session_id = ? AND status = 'active'
            """.trimIndent()

        val REVOKE_ACTIVE_FOR_USER_SQL =
            """
            UPDATE refresh_tokens rt
            SET status = 'revoked'
            FROM sessions s
            WHERE rt.session_id = s.id AND s.user_id = ? AND rt.status = 'active'
            """.trimIndent()
    }
}
