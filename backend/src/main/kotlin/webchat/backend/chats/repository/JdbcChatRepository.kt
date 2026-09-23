package webchat.backend.chats.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatListEntry
import webchat.backend.chats.domain.model.ChatPeerSnapshot
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatListRepository
import webchat.backend.chats.domain.port.ChatRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [ChatRepository] and [ChatListRepository] (data-model
 * 004 §1/§2; DIP: the adapter lives outside the domain).
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
) : ChatRepository,
    ChatListRepository {
    override fun findById(chatId: UUID): Chat? =
        jdbcTemplate
            .query(FIND_BY_ID_SQL, CHAT_ROW_MAPPER, chatId)
            .firstOrNull()

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

    /**
     * №12 (T055, research.md 004 §8): the WHOLE panel in ONE query — the
     * caller's participant state, the dialog, the peer `users` row, the
     * caller's own `user_blocks` mark and both per-chat aggregates:
     * `lastMessage` via a LATERAL top-1 by the max VISIBLE `seq`
     * (`seq > deleted_up_to_seq` — the per-user watermark of data-model
     * 004 §2) and `unreadCount` as the count of incoming visible messages
     * above the read watermark. The peer of a two-participant dialog is
     * the CASE-flipped side of the canonical pair. Sorting is
     * `last_message_at DESC NULLS LAST` (either direction lifts the
     * dialog, FR-014; empty dialogs keep their place below, FR-018) with
     * the `chat_id` tie-break — `chats.last_seq`/`seq` are per-chat
     * values and never inter-chat keys (plan.md). The ONLY exclusion is
     * the data-model §2 invariant `hidden AND deleted_up_to_seq >=
     * chats.last_seq` (FR-021).
     */
    override fun listForUser(callerId: UUID): List<ChatListEntry> =
        jdbcTemplate.query(
            LIST_FOR_USER_SQL,
            CHAT_LIST_ROW_MAPPER,
            callerId,
        )

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

        val CHAT_LIST_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ChatListEntry(
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    peer =
                        ChatPeerSnapshot(
                            id = rs.getObject("peer_id", UUID::class.java),
                            username = rs.getString("peer_username"),
                            email = rs.getString("peer_email"),
                            status = rs.getString("peer_status"),
                            createdAt = rs.getTimestamp("peer_created_at").toInstant(),
                        ),
                    lastMessage =
                        rs.getObject("last_message_id", UUID::class.java)?.let { messageId ->
                            Message(
                                id = messageId,
                                chatId = rs.getObject("chat_id", UUID::class.java),
                                senderId = rs.getObject("last_message_sender_id", UUID::class.java),
                                text = MessageText.normalize(rs.getString("last_message_text")),
                                seq = rs.getLong("last_message_seq"),
                                createdAt = rs.getTimestamp("last_message_created_at").toInstant(),
                            )
                        },
                    unreadCount = rs.getLong("unread_count"),
                    blockedByMe = rs.getBoolean("blocked_by_me"),
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

        val LIST_FOR_USER_SQL =
            """
            SELECT
                c.id AS chat_id,
                p.id AS peer_id,
                p.username AS peer_username,
                p.email AS peer_email,
                p.status::text AS peer_status,
                p.created_at AS peer_created_at,
                lm.id AS last_message_id,
                lm.sender_id AS last_message_sender_id,
                lm.text AS last_message_text,
                lm.seq AS last_message_seq,
                lm.created_at AS last_message_created_at,
                (SELECT count(*)
                   FROM messages um
                  WHERE um.chat_id = c.id
                    AND um.seq > me.last_read_seq
                    AND um.seq > me.deleted_up_to_seq
                    AND um.sender_id <> me.user_id) AS unread_count,
                EXISTS (SELECT 1
                          FROM user_blocks ub
                         WHERE ub.blocker_id = me.user_id
                           AND ub.blocked_id = p.id) AS blocked_by_me
            FROM chat_participants me
            JOIN chats c ON c.id = me.chat_id
            JOIN users p ON p.id = CASE WHEN c.user_low_id = me.user_id
                                        THEN c.user_high_id
                                        ELSE c.user_low_id END
            LEFT JOIN LATERAL (
                SELECT m.id, m.sender_id, m.text, m.seq, m.created_at
                  FROM messages m
                 WHERE m.chat_id = c.id
                   AND m.seq > me.deleted_up_to_seq
                 ORDER BY m.seq DESC
                 LIMIT 1
            ) lm ON true
            WHERE me.user_id = ?
              AND NOT (me.hidden AND me.deleted_up_to_seq >= c.last_seq)
            ORDER BY lm.created_at DESC NULLS LAST, c.id
            """.trimIndent()

        private fun findSql(condition: String): String =
            """
            SELECT id, user_low_id, user_high_id, created_at, last_seq
            FROM chats
            WHERE $condition
            """.trimIndent()
    }
}
