package webchat.backend.chats.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatKind
import webchat.backend.chats.domain.model.ChatListEntry
import webchat.backend.chats.domain.model.ChatPeerSnapshot
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatListRepository
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.groups.domain.model.MemberRole
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
     * №12 (T055, research.md 004 §8; unified by 006 T023, FR-014): the
     * WHOLE panel — direct dialogs AND groups — in ONE query. The direct
     * projection: the caller's participant state, the dialog, the peer
     * `users` row, the caller's own `user_blocks` mark and both per-chat
     * aggregates: `lastMessage` via a LATERAL top-1 by the max VISIBLE
     * `seq` (`seq > deleted_up_to_seq` — the per-user watermark of
     * data-model 004 §2) and `unreadCount` by the server-authoritative
     * formula of data-model 005 сущность 3 (T031, FR-007) — the SAME
     * bounds the ONE reusable calculator
     * `JdbcParticipantRepository.countUnread` (T013) embodies for the №26
     * deltas, inlined here as a correlated COUNT so the panel stays the
     * single aggregate query (an N+1 of per-chat `countUnread` calls is
     * the rejected alternative, research.md 004 §8): incoming
     * `sender_id <> me` with `seq > GREATEST(last_read_seq,
     * deleted_up_to_seq)` and `seq <= LEAST(chats.last_seq,
     * delivered_up_to_seq)` — the badge carries only the CONFIRMED
     * delivered tail (a realtime frame and a №26 page do not count until
     * the ack №25 moves the position), and below the deletion watermark
     * messages are inaccessible, not unread (US1-5). The SAME aggregate
     * pair serves groups unchanged — a group is a `chats` row with
     * shared history (plan.md 006).
     *
     * The group projection (006, api-contract.md §3): `chats.title`, the
     * caller's `role` and the ACTIVE roster count as a correlated COUNT
     * over `ix_chat_participants_chat_active` (≤200 rows, index-only —
     * the same index the capacity check reads); the peer join is a LEFT
     * JOIN guarded by `kind='direct'`, so a group row renders NO peer
     * (`peer` = NULL — the pair is NULL by the V14 shape-CHECK) and its
     * `blocked_by_me` is CASE-nulled: blocks never apply to groups
     * (Assumptions 006), the required-nullable fields of the schema stay
     * explicit.
     *
     * The peer of a two-participant dialog is the CASE-flipped side of
     * the canonical pair. Sorting is `last_message_at DESC NULLS LAST`
     * (either direction lifts the dialog, FR-014; empty dialogs keep
     * their place below, FR-018) with the `chat_id` tie-break —
     * `chats.last_seq`/`seq` are per-chat values and never inter-chat
     * keys (plan.md). The direct exclusion is the data-model §2
     * invariant `hidden AND deleted_up_to_seq >= chats.last_seq`
     * (FR-021); groups never carry `hidden` (№14 is a direct-dialog
     * operation), their membership lives on `state` — `me.state =
     * 'active'` keeps the row ONLY for an active member (FR-008:
     * removed/kicked members lose the group from the panel; direct rows
     * are 'active' forever, V14).
     */
    override fun listForUser(callerId: UUID): List<ChatListEntry> =
        jdbcTemplate.query(
            LIST_FOR_USER_SQL,
            CHAT_LIST_ROW_MAPPER,
            callerId,
        )

    private companion object {
        /**
         * The full V14 `chats` projection (T024): `kind`/`title`/
         * `description` ride along the pair columns, so a GROUP row
         * resolves as itself — `findById` serves №13 of both kinds, while
         * the pair reads ([FIND_BY_PAIR_SQL]) only ever match DIRECT rows
         * (the pair is NULL for groups by the `ck_chats_shape` CHECK).
         */
        val CHAT_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Chat(
                    id = rs.getObject("id", UUID::class.java),
                    kind = ChatKind.valueOf(rs.getString("kind").uppercase()),
                    userLowId = rs.getObject("user_low_id", UUID::class.java),
                    userHighId = rs.getObject("user_high_id", UUID::class.java),
                    title = rs.getString("title"),
                    description = rs.getString("description"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    lastSeq = rs.getLong("last_seq"),
                )
            }

        val CHAT_LIST_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                val kind = ChatKind.valueOf(rs.getString("chat_kind").uppercase())
                ChatListEntry(
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    kind = kind,
                    title = rs.getString("chat_title"),
                    memberCount = rs.getLong("member_count").takeIf { kind == ChatKind.GROUP },
                    myRole = rs.getString("my_role")?.let { role -> MemberRole.valueOf(role.uppercase()) },
                    peer =
                        rs.getObject("peer_id", UUID::class.java)?.let { peerId ->
                            ChatPeerSnapshot(
                                id = peerId,
                                username = rs.getString("peer_username"),
                                email = rs.getString("peer_email"),
                                status = rs.getString("peer_status"),
                                createdAt = rs.getTimestamp("peer_created_at").toInstant(),
                                displayName = rs.getString("peer_display_name"),
                            )
                        },
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
                    blockedByMe = rs.getObject("blocked_by_me")?.let { (it as Boolean) },
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
                c.kind::text AS chat_kind,
                c.title AS chat_title,
                me.role::text AS my_role,
                (SELECT count(*)
                   FROM chat_participants mc
                  WHERE mc.chat_id = c.id
                    AND mc.state = 'active') AS member_count,
                p.id AS peer_id,
                p.username AS peer_username,
                p.email AS peer_email,
                p.status::text AS peer_status,
                p.created_at AS peer_created_at,
                p.display_name AS peer_display_name,
                lm.id AS last_message_id,
                lm.sender_id AS last_message_sender_id,
                lm.text AS last_message_text,
                lm.seq AS last_message_seq,
                lm.created_at AS last_message_created_at,
                (SELECT count(*)
                   FROM messages um
                  WHERE um.chat_id = c.id
                    AND um.sender_id <> me.user_id
                    AND um.seq > GREATEST(me.last_read_seq, me.deleted_up_to_seq)
                    AND um.seq <= LEAST(c.last_seq, me.delivered_up_to_seq)) AS unread_count,
                CASE WHEN c.kind = 'group' THEN NULL
                     ELSE EXISTS (SELECT 1
                                    FROM user_blocks ub
                                   WHERE ub.blocker_id = me.user_id
                                     AND ub.blocked_id = p.id) END AS blocked_by_me
            FROM chat_participants me
            JOIN chats c ON c.id = me.chat_id
            LEFT JOIN users p ON c.kind = 'direct'
                 AND p.id = CASE WHEN c.user_low_id = me.user_id
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
              AND me.state = 'active'
              AND NOT (me.hidden AND me.deleted_up_to_seq >= c.last_seq)
            ORDER BY lm.created_at DESC NULLS LAST, c.id
            """.trimIndent()

        private fun findSql(condition: String): String =
            """
            SELECT id, kind, user_low_id, user_high_id, title, description, created_at, last_seq
            FROM chats
            WHERE $condition
            """.trimIndent()
    }
}
