package webchat.backend.chats.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.ParticipantRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [ParticipantRepository] (data-model 004 §2; DIP: the
 * adapter lives outside the domain).
 *
 * Every transition is a conditional single-row UPDATE against the
 * `(chat_id, user_id)` PK evaluated with `RETURNING` against the database
 * state: an empty result (rowcount 0) means the transition does not apply
 * and NO side effects occur — the same discipline as the auth feature
 * repositories. The read watermark moves only forward (GREATEST-update,
 * US4-5); the per-user deletion is atomic and O(1), never touching the
 * peer's row (FR-021).
 */
@Repository
class JdbcParticipantRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ParticipantRepository {
    override fun find(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant? = jdbcTemplate.query(FIND_SQL, ROW_MAPPER, chatId, userId).firstOrNull()

    override fun advanceReadUpTo(
        chatId: UUID,
        userId: UUID,
        upToSeq: Long,
    ): ChatParticipant? =
        jdbcTemplate
            .query(ADVANCE_READ_SQL, ROW_MAPPER, upToSeq, chatId, userId, upToSeq)
            .firstOrNull()

    override fun deleteUpTo(
        chatId: UUID,
        userId: UUID,
        chatLastSeq: Long,
    ): ChatParticipant? = jdbcTemplate.query(DELETE_UP_TO_SQL, ROW_MAPPER, chatLastSeq, chatId, userId).firstOrNull()

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ChatParticipant(
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    lastReadSeq = rs.getLong("last_read_seq"),
                    deletedUpToSeq = rs.getLong("deleted_up_to_seq"),
                    hidden = rs.getBoolean("hidden"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val FIND_SQL =
            """
            SELECT chat_id, user_id, last_read_seq, deleted_up_to_seq, hidden, created_at
            FROM chat_participants
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        val ADVANCE_READ_SQL =
            """
            UPDATE chat_participants
            SET last_read_seq = GREATEST(last_read_seq, ?)
            WHERE chat_id = ? AND user_id = ? AND last_read_seq < ?
            RETURNING chat_id, user_id, last_read_seq, deleted_up_to_seq, hidden, created_at
            """.trimIndent()

        val DELETE_UP_TO_SQL =
            """
            UPDATE chat_participants
            SET deleted_up_to_seq = ?, hidden = true
            WHERE chat_id = ? AND user_id = ?
            RETURNING chat_id, user_id, last_read_seq, deleted_up_to_seq, hidden, created_at
            """.trimIndent()
    }
}
