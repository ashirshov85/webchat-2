package webchat.backend.chats.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [ChatRepository] (data-model 004 §1; DIP: the adapter
 * lives outside the domain).
 *
 * The pair resolve is ONE PG transaction (FR-018): the canonical
 * least/greatest pair is inserted with `ON CONFLICT (user_low_id,
 * user_high_id) DO NOTHING` — exactly one dialog per pair (FR-001), the
 * rowcount distinguishing [ChatEnsureResult.Created] (`201`, api-contract.md
 * №11) from [ChatEnsureResult.Existing] (`200`) — then the two
 * `chat_participants` rows are created lazily (`ON CONFLICT DO NOTHING`)
 * and, on the existing chat, the CALLER's `hidden` flag is cleared while
 * the deletion watermark survives (FR-021). Parallel ensures of the same
 * pair are safe: the conflict wait guarantees the committed row is visible
 * to the follow-up SELECT of the same READ COMMITTED transaction.
 */
@Repository
class JdbcChatRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ChatRepository {
    override fun findById(chatId: UUID): Chat? = jdbcTemplate.query(FIND_BY_ID_SQL, CHAT_ROW_MAPPER, chatId).firstOrNull()

    @Transactional
    override fun ensure(
        callerId: UUID,
        peerId: UUID,
    ): ChatEnsureResult {
        require(callerId != peerId) { "a dialog requires two distinct users (FR-001)" }
        val candidateId = UUID.randomUUID()
        val (low, high) = Chat.canonicalPair(callerId, peerId)
        val created = jdbcTemplate.update(INSERT_CHAT_SQL, candidateId, low, high) == 1

        val chat =
            jdbcTemplate.query(FIND_BY_PAIR_SQL, CHAT_ROW_MAPPER, low, high).firstOrNull()
                ?: error("ensure could not resolve the pair dialog inside its transaction")

        jdbcTemplate.update(
            INSERT_PARTICIPANTS_SQL,
            chat.id,
            low,
            chat.id,
            high,
        )

        if (!created) {
            jdbcTemplate.update(UNHIDE_CALLER_SQL, chat.id, callerId)
        }
        return if (created) ChatEnsureResult.Created(chat) else ChatEnsureResult.Existing(chat)
    }

    private companion object {
        val CHAT_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Chat(
                    id = rs.getObject("id", UUID::class.java),
                    userLowId = rs.getObject("user_low_id", UUID::class.java),
                    userHighId = rs.getObject("user_high_id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    lastSeq = rs.getLong("last_seq"),
                )
            }

        val FIND_BY_ID_SQL = findSql("id = ?")

        val FIND_BY_PAIR_SQL = findSql("user_low_id = ? AND user_high_id = ?")

        val INSERT_CHAT_SQL =
            """
            INSERT INTO chats (id, user_low_id, user_high_id)
            VALUES (?, ?, ?)
            ON CONFLICT (user_low_id, user_high_id) DO NOTHING
            """.trimIndent()

        val INSERT_PARTICIPANTS_SQL =
            """
            INSERT INTO chat_participants (chat_id, user_id)
            VALUES (?, ?), (?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        val UNHIDE_CALLER_SQL =
            """
            UPDATE chat_participants
            SET hidden = false
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        private fun findSql(condition: String): String =
            """
            SELECT id, user_low_id, user_high_id, created_at, last_seq
            FROM chats
            WHERE $condition
            """.trimIndent()
    }
}
