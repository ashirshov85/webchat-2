package webchat.backend.contacts.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.contacts.domain.model.UserBlock
import webchat.backend.contacts.domain.port.BlockRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [BlockRepository] (data-model 004 §5; DIP: the adapter
 * lives outside the domain).
 *
 * All block semantics are point lookups on the `(blocker_id, blocked_id)`
 * PK pair — in both directions where the caller needs them (the send path
 * of T054 calls [exists] twice with swapped arguments). The block is
 * idempotent: `INSERT … ON CONFLICT (blocker_id, blocked_id) DO NOTHING`
 * followed by the SELECT of the stored relation (created OR pre-existing —
 * observationally the same, both map to `204`); the unblock is an
 * unconditional single-row DELETE. Blocks never touch `user_contacts` or
 * the V10 tables (FR-020).
 */
@Repository
class JdbcBlockRepository(
    private val jdbcTemplate: JdbcTemplate,
) : BlockRepository {
    @Transactional
    override fun block(
        blockerId: UUID,
        blockedId: UUID,
    ): UserBlock {
        require(blockerId != blockedId) { "a block requires two distinct users (FR-020)" }
        jdbcTemplate.update(INSERT_SQL, blockerId, blockedId)
        return jdbcTemplate
            .query(FIND_SQL, ROW_MAPPER, blockerId, blockedId)
            .firstOrNull()
            ?: error("block could not resolve the relation inside its transaction")
    }

    override fun unblock(
        blockerId: UUID,
        blockedId: UUID,
    ) {
        jdbcTemplate.update(DELETE_SQL, blockerId, blockedId)
    }

    override fun exists(
        blockerId: UUID,
        blockedId: UUID,
    ): Boolean = jdbcTemplate.queryForObject(EXISTS_SQL, Boolean::class.java, blockerId, blockedId)

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                UserBlock(
                    blockerId = rs.getObject("blocker_id", UUID::class.java),
                    blockedId = rs.getObject("blocked_id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val INSERT_SQL =
            """
            INSERT INTO user_blocks (blocker_id, blocked_id)
            VALUES (?, ?)
            ON CONFLICT (blocker_id, blocked_id) DO NOTHING
            """.trimIndent()

        val FIND_SQL =
            """
            SELECT blocker_id, blocked_id, created_at
            FROM user_blocks
            WHERE blocker_id = ? AND blocked_id = ?
            """.trimIndent()

        val DELETE_SQL =
            """
            DELETE FROM user_blocks
            WHERE blocker_id = ? AND blocked_id = ?
            """.trimIndent()

        val EXISTS_SQL =
            """
            SELECT EXISTS (
                SELECT 1 FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?
            )
            """.trimIndent()
    }
}
