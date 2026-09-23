package webchat.backend.chats.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewMessage
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [MessageRepository] (data-model 004 §3; DIP: the adapter
 * lives outside the domain).
 *
 * The write is the exactly-once PG transaction of research.md 004 §3 step 4:
 * `INSERT … ON CONFLICT (id) DO NOTHING RETURNING *` — the client UUID is
 * the PK, so a retry of any age converges to the SAME row (FR-004,
 * constitution III). Only an ACTUAL new row has side effects, folded into
 * the same transaction: `chats.last_seq` advances (monotone — an
 * out-of-order commit never regresses the MAX(seq) cache) and `hidden` is
 * cleared for BOTH participants (FR-021: a new incoming returns the chat to
 * the list while the deleted history stays behind the watermark). The
 * dedup path (empty `RETURNING` — a race of parallel retries) performs NO
 * writes at all and resolves the row by a follow-up SELECT in the same
 * transaction; the caller (T014/T031) maps [MessageInsertResult.Duplicate]
 * to `200`/`409` by comparing chat/sender with the request.
 */
@Repository
class JdbcMessageRepository(
    private val jdbcTemplate: JdbcTemplate,
) : MessageRepository {
    override fun findById(id: UUID): Message? = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id).firstOrNull()

    @Transactional
    override fun insert(message: NewMessage): MessageInsertResult {
        val stored =
            jdbcTemplate
                .query(
                    INSERT_ON_CONFLICT_SQL,
                    ROW_MAPPER,
                    message.id,
                    message.chatId,
                    message.senderId,
                    message.text.value,
                ).firstOrNull()

        return if (stored != null) {
            jdbcTemplate.update(ADVANCE_LAST_SEQ_SQL, stored.seq, message.chatId, stored.seq)
            jdbcTemplate.update(UNHIDE_PARTICIPANTS_SQL, message.chatId)
            MessageInsertResult.Inserted(stored)
        } else {
            MessageInsertResult.Duplicate(resolveExisting(message.id))
        }
    }

    override fun findVisiblePage(
        chatId: UUID,
        viewerId: UUID,
        before: Long?,
        limit: Int,
    ): List<Message> =
        if (before == null) {
            jdbcTemplate.query(pageSql(cursor = false), ROW_MAPPER, viewerId, chatId, limit)
        } else {
            jdbcTemplate.query(pageSql(cursor = true), ROW_MAPPER, viewerId, chatId, before, limit)
        }

    /**
     * The empty-`RETURNING` race resolution (research.md 004 §3): the
     * conflicting row is already committed, so the same READ COMMITTED
     * transaction sees it — `error` marks the impossible-by-construction
     * case of a conflict without a resolvable row.
     */
    private fun resolveExisting(id: UUID): Message =
        jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id).firstOrNull()
            ?: error("message conflict could not resolve the existing row inside its transaction")

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Message(
                    id = rs.getObject("id", UUID::class.java),
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    senderId = rs.getObject("sender_id", UUID::class.java),
                    // Stored rows are pre-normalized (the V10 CHECK mirrors
                    // FR-003), so re-validation is an identity read.
                    text = MessageText.normalize(rs.getString("text")),
                    seq = rs.getLong("seq"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val FIND_BY_ID_SQL =
            """
            SELECT id, chat_id, sender_id, text, seq, created_at
            FROM messages
            WHERE id = ?
            """.trimIndent()

        val INSERT_ON_CONFLICT_SQL =
            """
            INSERT INTO messages (id, chat_id, sender_id, text)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            RETURNING id, chat_id, sender_id, text, seq, created_at
            """.trimIndent()

        val ADVANCE_LAST_SEQ_SQL =
            """
            UPDATE chats
            SET last_seq = ?
            WHERE id = ? AND last_seq < ?
            """.trimIndent()

        val UNHIDE_PARTICIPANTS_SQL =
            """
            UPDATE chat_participants
            SET hidden = false
            WHERE chat_id = ?
            """.trimIndent()

        private fun pageSql(cursor: Boolean): String =
            """
            SELECT m.id, m.chat_id, m.sender_id, m.text, m.seq, m.created_at
            FROM messages m
            JOIN chat_participants p ON p.chat_id = m.chat_id AND p.user_id = ?
            WHERE m.chat_id = ? AND m.seq > p.deleted_up_to_seq${if (cursor) " AND m.seq < ?" else ""}
            ORDER BY m.seq DESC
            LIMIT ?
            """.trimIndent()
    }
}
