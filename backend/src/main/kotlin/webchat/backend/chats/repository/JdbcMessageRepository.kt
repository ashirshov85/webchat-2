package webchat.backend.chats.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageKind
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewMessage
import webchat.backend.chats.domain.port.NewSystemMessage
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
 *
 * Since 009 (T016) the same table also carries the SYSTEM widget rows
 * (`kind='system'`, `sender_id NULL`, jsonb `payload` — data-model §Сущность
 * 4): [insertSystem] is the plain-INSERT sibling with the IDENTICAL
 * side-effect transaction (the seq allocation, the `last_seq` advance and
 * the FR-021 un-hide), joining the AMBIENT operation transaction of the
 * caller ([webchat.backend.chats.domain.port.SystemMessagePort]).
 */
@Repository
class JdbcMessageRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : MessageRepository {
    override fun findById(id: UUID): Message? = jdbcTemplate.query(FIND_BY_ID_SQL, rowMapper, id).firstOrNull()

    @Transactional
    override fun insert(message: NewMessage): MessageInsertResult {
        val stored =
            jdbcTemplate
                .query(
                    INSERT_ON_CONFLICT_SQL,
                    rowMapper,
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

    /**
     * T016 (009, data-model §Сущность 4): the SYSTEM widget write — a plain
     * `INSERT … RETURNING *` (a server UUID, no ON CONFLICT: the
     * idempotency is the caller's operation invariant, research §8) with
     * the SAME folded side effects as a user record. `@Transactional`
     * (REQUIRED) JOINS the ambient operation transaction of the append
     * caller when one is active, and owns the transaction otherwise (the
     * repository-IT direct calls).
     */
    @Transactional
    override fun insertSystem(message: NewSystemMessage): Message {
        val payloadJson = message.payload.toString()
        val stored =
            jdbcTemplate
                .query(
                    INSERT_SYSTEM_SQL,
                    rowMapper,
                    message.id,
                    message.chatId,
                    message.text.value,
                    payloadJson,
                ).firstOrNull() ?: error("a system-message INSERT with a fresh server UUID always returns its row")

        jdbcTemplate.update(ADVANCE_LAST_SEQ_SQL, stored.seq, message.chatId, stored.seq)
        jdbcTemplate.update(UNHIDE_PARTICIPANTS_SQL, message.chatId)
        return stored
    }

    override fun findVisiblePage(
        chatId: UUID,
        viewerId: UUID,
        before: Long?,
        limit: Int,
    ): List<Message> =
        if (before == null) {
            jdbcTemplate.query(pageSql(cursor = false), rowMapper, viewerId, chatId, limit)
        } else {
            jdbcTemplate.query(pageSql(cursor = true), rowMapper, viewerId, chatId, before, limit)
        }

    /**
     * T013 (sync-protocol.md 005 §2/§4): the ascending catch-up window —
     * the same per-user visibility join as the DESC page, flipped to
     * `seq ASC` with the exclusive `after` bound; the №26 deltas and the
     * №15 `?after=` mode (T015) replay the backlog through it page by
     * page until exhaustion.
     */
    override fun findVisiblePageAfter(
        chatId: UUID,
        viewerId: UUID,
        after: Long,
        limit: Int,
    ): List<Message> = jdbcTemplate.query(PAGE_AFTER_SQL, rowMapper, viewerId, chatId, after, limit)

    /**
     * The empty-`RETURNING` race resolution (research.md 004 §3): the
     * conflicting row is already committed, so the same READ COMMITTED
     * transaction sees it — `error` marks the impossible-by-construction
     * case of a conflict without a resolvable row.
     */
    private fun resolveExisting(id: UUID): Message =
        jdbcTemplate.query(FIND_BY_ID_SQL, rowMapper, id).firstOrNull()
            ?: error("message conflict could not resolve the existing row inside its transaction")

    /**
     * T016: the jsonb `payload` of a system row — read as its JSON text
     * and parsed back to the [ObjectNode] the model/fan-out carry; `null`
     * (a `kind='user'` row) stays `null`. Needs the adapter's
     * [ObjectMapper], hence an instance-level row mapper (the payload of
     * a stored row is an object by the V18 `ck_messages_payload_object`
     * CHECK, so the parse is an identity read).
     */
    private val rowMapper =
        RowMapper { rs: ResultSet, _: Int ->
            Message(
                id = rs.getObject("id", UUID::class.java),
                chatId = rs.getObject("chat_id", UUID::class.java),
                // NULL exactly for the kind='system' widget rows (V18).
                senderId = rs.getObject("sender_id", UUID::class.java),
                // Stored rows are pre-normalized (the V10 CHECK mirrors
                // FR-003), so re-validation is an identity read.
                text = MessageText.normalize(rs.getString("text")),
                seq = rs.getLong("seq"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                kind = kindOf(rs.getString("kind")),
                payload = rs.getString("payload")?.let(objectMapper::readTree) as ObjectNode?,
            )
        }

    private companion object {
        /** The wire/DB label of [MessageKind] (`ck_messages_kind` of V18: 'user'|'system'). */
        fun kindOf(label: String): MessageKind =
            when (label) {
                "user" -> MessageKind.USER
                "system" -> MessageKind.SYSTEM
                else -> error("unknown messages.kind label <$label>")
            }

        val FIND_BY_ID_SQL =
            """
            SELECT id, chat_id, sender_id, text, seq, created_at, kind, payload
            FROM messages
            WHERE id = ?
            """.trimIndent()

        val INSERT_ON_CONFLICT_SQL =
            """
            INSERT INTO messages (id, chat_id, sender_id, text, kind)
            VALUES (?, ?, ?, ?, 'user')
            ON CONFLICT (id) DO NOTHING
            RETURNING id, chat_id, sender_id, text, seq, created_at, kind, payload
            """.trimIndent()

        val INSERT_SYSTEM_SQL =
            """
            INSERT INTO messages (id, chat_id, sender_id, text, kind, payload)
            VALUES (?, ?, NULL, ?, 'system', CAST(? AS jsonb))
            RETURNING id, chat_id, sender_id, text, seq, created_at, kind, payload
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
            SELECT m.id, m.chat_id, m.sender_id, m.text, m.seq, m.created_at, m.kind, m.payload
            FROM messages m
            JOIN chat_participants p ON p.chat_id = m.chat_id AND p.user_id = ?
            WHERE m.chat_id = ? AND m.seq > p.deleted_up_to_seq${if (cursor) " AND m.seq < ?" else ""}
            ORDER BY m.seq DESC
            LIMIT ?
            """.trimIndent()

        val PAGE_AFTER_SQL =
            """
            SELECT m.id, m.chat_id, m.sender_id, m.text, m.seq, m.created_at, m.kind, m.payload
            FROM messages m
            JOIN chat_participants p ON p.chat_id = m.chat_id AND p.user_id = ?
            WHERE m.chat_id = ? AND m.seq > ? AND m.seq > p.deleted_up_to_seq
            ORDER BY m.seq ASC
            LIMIT ?
            """.trimIndent()
    }
}
