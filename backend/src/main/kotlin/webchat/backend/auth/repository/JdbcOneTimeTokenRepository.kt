package webchat.backend.auth.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.auth.domain.model.OneTimeToken
import webchat.backend.auth.domain.model.TokenPurpose
import webchat.backend.auth.domain.port.TokenRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC adapter for [TokenRepository] (data-model.md §2): conditional consumption
 * guarded by used_at/expires_at (FR-012) and annulment of previous active tokens
 * of the same user+purpose, both evaluated against the database clock.
 */
@Repository
class JdbcOneTimeTokenRepository(
    private val jdbcTemplate: JdbcTemplate,
) : TokenRepository {
    override fun insert(token: OneTimeToken) {
        jdbcTemplate.update(
            INSERT_SQL,
            token.id,
            token.userId,
            token.purpose.name.lowercase(),
            token.tokenHash,
            Timestamp.from(token.expiresAt),
            toDb(token.usedAt),
            Timestamp.from(token.createdAt),
        )
    }

    override fun findByTokenHash(tokenHash: String): OneTimeToken? =
        jdbcTemplate.query(FIND_BY_TOKEN_HASH_SQL, ROW_MAPPER, tokenHash).firstOrNull()

    override fun consume(
        id: UUID,
        tokenHash: String,
    ): Boolean =
        jdbcTemplate.update(
            CONSUME_SQL,
            id,
            tokenHash,
        ) == 1

    @Transactional
    override fun annulActiveFor(
        userId: UUID,
        purpose: TokenPurpose,
    ) {
        jdbcTemplate.update(
            ANNUL_ACTIVE_SQL,
            userId,
            purpose.name.lowercase(),
        )
    }

    private fun toDb(instant: Instant?): Timestamp? = instant?.let(Timestamp::from)

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                OneTimeToken(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    purpose = TokenPurpose.valueOf(rs.getString("purpose").uppercase()),
                    tokenHash = rs.getString("token_hash"),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    usedAt = rs.getTimestamp("used_at")?.toInstant(),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val INSERT_SQL =
            """
            INSERT INTO one_time_tokens (id, user_id, purpose, token_hash, expires_at, used_at, created_at)
            VALUES (?, ?, ?::one_time_token_purpose, ?, ?, ?, ?)
            """.trimIndent()

        val FIND_BY_TOKEN_HASH_SQL =
            """
            SELECT id, user_id, purpose, token_hash, expires_at, used_at, created_at
            FROM one_time_tokens
            WHERE token_hash = ?
            """.trimIndent()

        val CONSUME_SQL =
            """
            UPDATE one_time_tokens
            SET used_at = now()
            WHERE id = ? AND token_hash = ? AND used_at IS NULL AND expires_at > now()
            """.trimIndent()

        val ANNUL_ACTIVE_SQL =
            """
            UPDATE one_time_tokens
            SET used_at = now()
            WHERE user_id = ? AND purpose = ?::one_time_token_purpose AND used_at IS NULL
            """.trimIndent()
    }
}
