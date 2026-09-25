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
     * T010/T013 (№26, data-model 005 сущность 2): ONE read snapshot
     * joining `chats` — the caller's dialogs with a visible undelivered
     * tail beyond the EFFECTIVE cursor. The client cursors fold into the
     * candidacy bound through a `VALUES` join [k]: a sane cursor raises
     * the bar (`COALESCE(k.cur, 0)` inside the GREATEST — a chat caught
     * up by its client cursor is excluded entirely, foreign ids never
     * match the caller's rows), while a cursor BEYOND the chat head (the
     * «курсор из будущего» repair) falls back to the server position
     * (`CASE WHEN k.cur > c.last_seq THEN me.delivered_up_to_seq`) so the
     * desynced chat still delivers its backlog. Latest activity first
     * (`chats.last_seq DESC`, tie-break `created_at DESC, chat_id`), up
     * to [chatLimit] entries with [UndeliveredChatPage.moreChats]
     * computed by the remainder in the SAME query (fetching
     * `chatLimit + 1` rows) — cursor-aware pagination keeps «частичный
     * список как полный» исключён. This read never writes the delivery
     * position (FR-001).
     */
    @Suppress("SpreadOperator") // the dynamic VALUES join makes the argument list per-cursor — a tiny one-off copy
    override fun loadForSync(
        userId: UUID,
        clientCursors: Map<UUID, Long>,
        chatLimit: Int,
    ): UndeliveredChatPage {
        require(chatLimit >= 1) { "chatLimit must be positive (validated 1–50 by the caller)" }
        val rows =
            if (clientCursors.isEmpty()) {
                jdbcTemplate.query(LOAD_FOR_SYNC_SQL, UNDELIVERED_ROW_MAPPER, userId, chatLimit + 1)
            } else {
                jdbcTemplate.query(
                    loadForSyncSql(clientCursors.size),
                    UNDELIVERED_ROW_MAPPER,
                    *loadForSyncArgs(userId, clientCursors, chatLimit),
                )
            }
        val moreChats = rows.size > chatLimit
        return UndeliveredChatPage(chats = rows.take(chatLimit), moreChats = moreChats)
    }

    /**
     * T013 (data-model 005 сущность 3, FR-007): the ONE reusable unread
     * calculator — the exact contract formula as a single COUNT over the
     * `(chat_id, seq)` index range, bounded below by
     * `GREATEST(last_read_seq, deleted_up_to_seq)` (read + truncation)
     * and above by `LEAST(chats.last_seq, delivered_up_to_seq)` (the
     * confirmed delivery position). Pure read; a missing participant row
     * counts 0.
     */
    override fun countUnread(
        userId: UUID,
        chatId: UUID,
    ): Long = jdbcTemplate.queryForObject(COUNT_UNREAD_SQL, Long::class.java, userId, chatId, userId) ?: 0L

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

        /** T013: the cursor-folded candidacy bound of №26 (see [loadForSync]). */
        private fun loadForSyncSql(cursorCount: Int): String =
            """
            SELECT me.chat_id,
                   me.delivered_up_to_seq,
                   me.deleted_up_to_seq,
                   c.last_seq AS chat_last_seq
            FROM chat_participants me
            JOIN chats c ON c.id = me.chat_id
            LEFT JOIN (VALUES ${cursorValues(cursorCount)}) AS k(chat_id, cur)
              ON k.chat_id = me.chat_id
            WHERE me.user_id = ?
              AND c.last_seq > GREATEST(
                    me.delivered_up_to_seq,
                    me.deleted_up_to_seq,
                    CASE WHEN k.cur > c.last_seq THEN me.delivered_up_to_seq ELSE COALESCE(k.cur, 0) END)
            ORDER BY c.last_seq DESC, c.created_at DESC, c.id
            LIMIT ?
            """.trimIndent()

        private const val CURSOR_ROW = "(?::uuid, ?::bigint)"

        private fun cursorValues(cursorCount: Int): String = (1..cursorCount).joinToString(", ") { CURSOR_ROW }

        private fun loadForSyncArgs(
            userId: UUID,
            clientCursors: Map<UUID, Long>,
            chatLimit: Int,
        ): Array<Any> =
            buildList {
                // The bind order follows the TEXTUAL placeholder order: the
                // VALUES rows of the FROM clause come BEFORE `me.user_id = ?`.
                clientCursors.forEach { (chatId, cursor) ->
                    add(chatId)
                    add(cursor)
                }
                add(userId)
                add(chatLimit + 1)
            }.toTypedArray()

        val COUNT_UNREAD_SQL =
            """
            SELECT count(*)
            FROM messages m
            JOIN chats c ON c.id = m.chat_id
            JOIN chat_participants me ON me.chat_id = m.chat_id AND me.user_id = ?
            WHERE m.chat_id = ?
              AND m.sender_id <> ?
              AND m.seq > GREATEST(me.last_read_seq, me.deleted_up_to_seq)
              AND m.seq <= LEAST(c.last_seq, me.delivered_up_to_seq)
            """.trimIndent()
    }
}
