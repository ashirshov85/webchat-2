package webchat.backend.chats.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.UndeliveredChat
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.chats.domain.port.ParticipantRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [ParticipantRepository] (data-model 004 §2 + 005
 * сущность 1; DIP: the adapter lives outside the domain).
 *
 * Every transition is a conditional single-row UPDATE against the
 * `(chat_id, user_id)` PK evaluated with `RETURNING` against the database
 * state: an empty result (rowcount 0) means the transition does not apply
 * and NO side effects occur — the same discipline as the auth feature
 * repositories. The read watermark moves only forward (GREATEST-update,
 * US4-5); the per-user deletion is atomic and O(1), never touching the
 * peer's row (FR-021). Since V12 (005) the delivery position rides the
 * same discipline: the №25 ack batch of [advanceDelivered] is ONE
 * transaction over per-PK GREATEST-updates (rowcount 0 — no effect,
 * idempotent; FR-001: the ONLY writer of `delivered_up_to_seq`), and
 * [loadForSync] is a pure READ joining `chats` — it never moves the
 * position.
 */
@Repository
class JdbcParticipantRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ParticipantRepository {
    override fun find(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant? = jdbcTemplate.query(FIND_SQL, ROW_MAPPER, chatId, userId).firstOrNull()

    override fun findForChat(chatId: UUID): List<ChatParticipant> =
        jdbcTemplate.query(
            FIND_FOR_CHAT_SQL,
            ROW_MAPPER,
            chatId,
        )

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

    /**
     * T010 (№25, data-model 005 сущность 1): the whole ack batch in ONE
     * transaction — per entry the conditional per-PK GREATEST-update; a
     * zero rowcount (a repeated or smaller `upToSeq`, or no row) is a
     * plain no-op. The service (T012) has already validated membership
     * and the `upToSeq ≤ chats.last_seq` bound of the WHOLE batch, so no
     * partial refusals reach this point; an empty batch performs no
     * statements.
     */
    @Transactional
    override fun advanceDelivered(
        userId: UUID,
        acks: Map<UUID, Long>,
    ) {
        if (acks.isEmpty()) return
        acks.forEach { (chatId, upToSeq) ->
            jdbcTemplate.update(ADVANCE_DELIVERED_SQL, upToSeq, chatId, userId, upToSeq)
        }
    }

    /**
     * T010 (№26, data-model 005 сущность 2): ONE read snapshot joining
     * `chats` — the caller's dialogs with a visible undelivered tail
     * (`hasUndeliveredVisible`: `chats.last_seq > GREATEST(delivered,
     * deleted)`), latest activity first (`chats.last_seq DESC`, tie-break
     * `created_at DESC, chat_id`), up to [chatLimit] entries with
     * [UndeliveredChatPage.moreChats] computed by the remainder in the
     * SAME query (fetching `chatLimit + 1` rows) — «частичный список как
     * полный» исключён. The client cursors are folded in by the service
     * (T013) as the effective cursor `max(client, server)`; this read
     * never writes the delivery position (FR-001).
     */
    override fun loadForSync(
        userId: UUID,
        chatLimit: Int,
    ): UndeliveredChatPage {
        require(chatLimit >= 1) { "chatLimit must be positive (validated 1–50 by the caller)" }
        val rows = jdbcTemplate.query(LOAD_FOR_SYNC_SQL, UNDELIVERED_ROW_MAPPER, userId, chatLimit + 1)
        val moreChats = rows.size > chatLimit
        return UndeliveredChatPage(chats = rows.take(chatLimit), moreChats = moreChats)
    }

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ChatParticipant(
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    lastReadSeq = rs.getLong("last_read_seq"),
                    deletedUpToSeq = rs.getLong("deleted_up_to_seq"),
                    deliveredUpToSeq = rs.getLong("delivered_up_to_seq"),
                    hidden = rs.getBoolean("hidden"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val UNDELIVERED_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                UndeliveredChat(
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    deliveredUpToSeq = rs.getLong("delivered_up_to_seq"),
                    deletedUpToSeq = rs.getLong("deleted_up_to_seq"),
                    chatLastSeq = rs.getLong("chat_last_seq"),
                )
            }

        val FIND_SQL =
            """
            SELECT chat_id, user_id, last_read_seq, deleted_up_to_seq, delivered_up_to_seq, hidden, created_at
            FROM chat_participants
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        /** T043: the two watermark rows of №11/№13 `ChatView` in one read. */
        val FIND_FOR_CHAT_SQL =
            """
            SELECT chat_id, user_id, last_read_seq, deleted_up_to_seq, delivered_up_to_seq, hidden, created_at
            FROM chat_participants
            WHERE chat_id = ?
            """.trimIndent()

        val ADVANCE_READ_SQL =
            """
            UPDATE chat_participants
            SET last_read_seq = GREATEST(last_read_seq, ?)
            WHERE chat_id = ? AND user_id = ? AND last_read_seq < ?
            RETURNING chat_id, user_id, last_read_seq, deleted_up_to_seq, delivered_up_to_seq, hidden, created_at
            """.trimIndent()

        val DELETE_UP_TO_SQL =
            """
            UPDATE chat_participants
            SET deleted_up_to_seq = ?, hidden = true
            WHERE chat_id = ? AND user_id = ?
            RETURNING chat_id, user_id, last_read_seq, deleted_up_to_seq, delivered_up_to_seq, hidden, created_at
            """.trimIndent()

        val ADVANCE_DELIVERED_SQL =
            """
            UPDATE chat_participants
            SET delivered_up_to_seq = GREATEST(delivered_up_to_seq, ?)
            WHERE chat_id = ? AND user_id = ? AND delivered_up_to_seq < ?
            """.trimIndent()

        val LOAD_FOR_SYNC_SQL =
            """
            SELECT me.chat_id,
                   me.delivered_up_to_seq,
                   me.deleted_up_to_seq,
                   c.last_seq AS chat_last_seq
            FROM chat_participants me
            JOIN chats c ON c.id = me.chat_id
            WHERE me.user_id = ?
              AND c.last_seq > GREATEST(me.delivered_up_to_seq, me.deleted_up_to_seq)
            ORDER BY c.last_seq DESC, c.created_at DESC, c.id
            LIMIT ?
            """.trimIndent()
    }
}
