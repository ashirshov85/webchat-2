package webchat.backend.chats.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.chats.domain.model.ChatKind
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.UndeliveredChat
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.model.MembershipState
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [ParticipantRepository] (data-model 004 §2 + 005
 * сущность 1 + 006 §Сущность 2; DIP: the adapter lives outside the
 * domain).
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
 *
 * Since V14 (006) the same rows carry the group roster `role`/`state`
 * (T008/T011): every projection maps them (direct rows read `role=NULL,
 * state='active'`), the ACTIVE-roster legs run on the partial index
 * `ix_chat_participants_chat_active`, and the membership transitions
 * keep the conditional-UPDATE discipline — [removeMember] resolves the
 * kick×leave race by rowcount, [reactivate] preserves BOTH watermarks
 * (FR-002) and [addMember] anchors them at the chat head in the INSERT
 * itself (FR-013).
 *
 * Since V16 (008a, T052) the same rows carry the PERSONAL sound switch
 * `sound_enabled` (data-model 008a §1.3, FR-012): every projection maps
 * it (the column joins [PARTICIPANT_COLUMNS], default TRUE of a new
 * participation — the V16 column DEFAULT, no INSERT names it) and the
 * single №42 leg [updateSoundEnabled] is its ONLY writer.
 */
@Repository
@Suppress("TooManyFunctions") // one member per port rule: 004/005 dialog legs + the 006 roster extension (T008/T011)
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
     *
     * 006 T037 (api-contract.md 006 §3 №26): the candidacy is the
     * caller's ACTIVE memberships only — `me.state = 'active'` drops a
     * REMOVED group row entirely (исключённому дельты группы не
     * приходят, FR-008; direct rows never leave 'active', V14). The
     * GROUP projection rides the same snapshot as correlated aggregates
     * over `ix_chat_participants_chat_active` (≤ 200 rows, the
     * `LIST_FOR_USER` precedent): the ACTIVE [memberCount] and the
     * FR-012 ✓✓ fold `MAX(last_read_seq)` of the other actives
     * (COALESCE 0 — a group of one never sets ✓✓) — one read, no N+1 of
     * per-chat `activeMembers`/`maxOtherReadUpToSeq` calls.
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

    /**
     * T008 (FR-008): the ACTIVE-membership point lookup of the groups
     * gate — the `state='active'` filter is the whole membership rule;
     * every other case reads as `null` and renders identically at the
     * gate (privacy 404).
     */
    override fun findActive(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant? = jdbcTemplate.query(FIND_ACTIVE_SQL, ROW_MAPPER, chatId, userId).firstOrNull()

    /**
     * T008 (FR-011): the ACTIVE roster snapshot — one scan over the
     * V14 partial index `ix_chat_participants_chat_active` (≤ 200 rows
     * by the FR-002 limit), deterministic `created_at, user_id` order
     * (the №28 member order of data-model 006). A pure READ: the
     * addressee snapshot for the post-commit fan-out is taken inside
     * the caller's transaction.
     */
    override fun activeMembers(chatId: UUID): List<ChatParticipant> =
        jdbcTemplate.query(
            ACTIVE_MEMBERS_SQL,
            ROW_MAPPER,
            chatId,
        )

    /**
     * T008 (№27/№31, FR-013): the FIRST add — one INSERT…SELECT anchors
     * BOTH watermarks at the chat head read in the same statement, so
     * the badge starts at 0 and the pre-add history never re-delivers.
     * A missing `chats` row inserts nothing (the service has resolved
     * the group already) and fails the invariant; a pre-existing row
     * of the user surfaces as the PK violation the caller routes to
     * [reactivate].
     */
    override fun addMember(
        chatId: UUID,
        userId: UUID,
        role: MemberRole,
    ): ChatParticipant =
        jdbcTemplate
            .query(ADD_MEMBER_SQL, ROW_MAPPER, chatId, userId, role.name.lowercase(), chatId)
            .firstOrNull()
            ?: error("addMember requires the chats row to exist (the service resolves the group first)")

    /**
     * T008 (№31, FR-002): re-adding a REMOVED member — the single
     * conditional UPDATE restores `state='active'`, resets the role to
     * `member` and clears a stale `hidden` flag while BOTH watermarks
     * ride the row untouched (no re-anchoring); rowcount 0 (no row, or
     * already ACTIVE) reads as `null` with no side effects.
     */
    override fun reactivate(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant? = jdbcTemplate.query(REACTIVATE_SQL, ROW_MAPPER, chatId, userId).firstOrNull()

    /**
     * T008 (№32/№33): the single conditional removal UPDATE resolved by
     * rowcount — `true` exactly once, `false` when the kick×leave race
     * has already resolved or the user was never a member (the
     * convergence of api-contract.md 006 §2). Watermarks stay in the
     * row for the re-add semantics.
     */
    override fun removeMember(
        chatId: UUID,
        userId: UUID,
    ): Boolean = jdbcTemplate.update(REMOVE_MEMBER_SQL, chatId, userId) == 1

    /**
     * T043 (№34/№35, data-model 006 §Сущность 2): the single
     * conditional role UPDATE on an ACTIVE row — the statement-level
     * unit of both the №34 admin grant/revoke and the №35 demote/promote
     * pair; rowcount 0 (a concurrent removal won the race) reads as
     * `null` with no side effects. The partial unique index
     * `ux_chat_participants_owner` arbitrates every OWNER promotion:
     * the №35 caller demotes the former owner FIRST in the same
     * transaction, so the index never sees two active owners.
     */
    override fun updateRole(
        chatId: UUID,
        userId: UUID,
        role: MemberRole,
    ): ChatParticipant? =
        jdbcTemplate
            .query(UPDATE_ROLE_SQL, ROW_MAPPER, role.name.lowercase(), chatId, userId)
            .firstOrNull()

    /**
     * T008 (FR-012, MAX semantics): the ✓✓ fold — `MAX(last_read_seq)` of
     * the ACTIVE members except the reader, an index-only aggregation over
     * the same partial index (removed readers drop out naturally); a group
     * of one folds to 0 via COALESCE.
     */
    override fun maxOtherReadUpToSeq(
        chatId: UUID,
        userId: UUID,
    ): Long = jdbcTemplate.queryForObject(MAX_OTHER_READ_SQL, Long::class.java, chatId, userId) ?: 0L

    /**
     * T052 (№42, data-model 008a §1.3): the single-row UPDATE of the
     * caller's own `sound_enabled` — the ONLY writer of the column. The
     * service (T053) has already resolved the membership gate and the
     * idempotency rule (only a genuine CHANGE reaches this statement),
     * so the per-PK UPDATE carries no condition beyond the key itself —
     * the same unconditional-by-key discipline as [deleteUpTo]. Returns
     * the updated row, or `null` on rowcount 0 (no participant row — a
     * broken invariant after the gate, not a client answer).
     */
    override fun updateSoundEnabled(
        chatId: UUID,
        userId: UUID,
        enabled: Boolean,
    ): ChatParticipant? =
        jdbcTemplate
            .query(UPDATE_SOUND_SQL, ROW_MAPPER, enabled, chatId, userId)
            .firstOrNull()

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
                    // V14 (006): the group roster projection — direct rows
                    // read role=NULL and never leave state='active'.
                    role = rs.getString("role")?.let { MemberRole.valueOf(it.uppercase()) },
                    state = MembershipState.valueOf(rs.getString("state").uppercase()),
                    // V16 (008a): the personal per-chat sound switch — the
                    // №11/№12/№13 `soundEnabled` slot of the row owner.
                    soundEnabled = rs.getBoolean("sound_enabled"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val UNDELIVERED_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                // 006 T037: the group projection rides the candidate only
                // for GROUP rows — a DIRECT candidate keeps the fields
                // null so its delta stays the exact 0.5.0 shape.
                val kind = ChatKind.valueOf(rs.getString("chat_kind").uppercase())
                UndeliveredChat(
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    deliveredUpToSeq = rs.getLong("delivered_up_to_seq"),
                    deletedUpToSeq = rs.getLong("deleted_up_to_seq"),
                    chatLastSeq = rs.getLong("chat_last_seq"),
                    kind = kind,
                    title = rs.getString("chat_title").takeIf { kind == ChatKind.GROUP },
                    memberCount = rs.getLong("member_count").takeIf { kind == ChatKind.GROUP },
                    othersReadUpToSeq = rs.getLong("others_read_up_to_seq").takeIf { kind == ChatKind.GROUP },
                )
            }

        /** Every roster projection selects the same column set as [ROW_MAPPER] maps. */
        const val PARTICIPANT_COLUMNS =
            "chat_id, user_id, last_read_seq, deleted_up_to_seq, " +
                "delivered_up_to_seq, hidden, role, state, sound_enabled, created_at"

        val FIND_SQL =
            """
            SELECT $PARTICIPANT_COLUMNS
            FROM chat_participants
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        /** T043: the two watermark rows of №11/№13 `ChatView` in one read. */
        val FIND_FOR_CHAT_SQL =
            """
            SELECT $PARTICIPANT_COLUMNS
            FROM chat_participants
            WHERE chat_id = ?
            """.trimIndent()

        /** T008: the FR-008 membership point lookup — `state='active'` IS the membership rule. */
        val FIND_ACTIVE_SQL =
            """
            SELECT $PARTICIPANT_COLUMNS
            FROM chat_participants
            WHERE chat_id = ? AND user_id = ? AND state = 'active'
            """.trimIndent()

        /**
         * T008: the ACTIVE roster snapshot — the V14 partial index
         * `ix_chat_participants_chat_active (chat_id) INCLUDE (user_id,
         * role, last_read_seq) WHERE state='active'` serves it as an
         * index-only scan; `created_at, user_id` mirrors the №28 member
         * order deterministically.
         */
        val ACTIVE_MEMBERS_SQL =
            """
            SELECT $PARTICIPANT_COLUMNS
            FROM chat_participants
            WHERE chat_id = ? AND state = 'active'
            ORDER BY created_at, user_id
            """.trimIndent()

        /**
         * T008: the FIRST add — both watermarks anchor at `chats.last_seq`
         * read INSIDE the INSERT (FR-013), `deleted_up_to_seq`/`hidden`
         * ride their defaults (groups never use them, data-model 006
         * §Сущность 2). A missing chats row inserts nothing.
         */
        val ADD_MEMBER_SQL =
            """
            INSERT INTO chat_participants (chat_id, user_id, role, state, last_read_seq, delivered_up_to_seq)
            SELECT ?, ?, ?, 'active', c.last_seq, c.last_seq
            FROM chats c
            WHERE c.id = ?
            RETURNING $PARTICIPANT_COLUMNS
            """.trimIndent()

        /** T008: re-adding a REMOVED member — watermarks untouched, role reset, `hidden` cleared. */
        val REACTIVATE_SQL =
            """
            UPDATE chat_participants
            SET state = 'active', role = 'member', hidden = false
            WHERE chat_id = ? AND user_id = ? AND state = 'removed'
            RETURNING $PARTICIPANT_COLUMNS
            """.trimIndent()

        /** T008: the single conditional №32/№33 removal, resolved by rowcount. */
        val REMOVE_MEMBER_SQL =
            """
            UPDATE chat_participants
            SET state = 'removed', role = 'member'
            WHERE chat_id = ? AND user_id = ? AND state = 'active'
            """.trimIndent()

        /** T043: the single conditional №34/№35 role change on an ACTIVE row. */
        val UPDATE_ROLE_SQL =
            """
            UPDATE chat_participants
            SET role = ?
            WHERE chat_id = ? AND user_id = ? AND state = 'active'
            RETURNING $PARTICIPANT_COLUMNS
            """.trimIndent()

        /** T008 (FR-012, MAX semantics): the ✓✓ fold over the active roster except the reader. */
        val MAX_OTHER_READ_SQL =
            """
            SELECT COALESCE(MAX(last_read_seq), 0)
            FROM chat_participants
            WHERE chat_id = ? AND user_id <> ? AND state = 'active'
            """.trimIndent()

        /** T052 (№42, 008a): the ONLY writer of the personal sound switch — a per-PK single-row UPDATE. */
        val UPDATE_SOUND_SQL =
            """
            UPDATE chat_participants
            SET sound_enabled = ?
            WHERE chat_id = ? AND user_id = ?
            RETURNING $PARTICIPANT_COLUMNS
            """.trimIndent()

        val ADVANCE_READ_SQL =
            """
            UPDATE chat_participants
            SET last_read_seq = GREATEST(last_read_seq, ?)
            WHERE chat_id = ? AND user_id = ? AND last_read_seq < ?
            RETURNING $PARTICIPANT_COLUMNS
            """.trimIndent()

        val DELETE_UP_TO_SQL =
            """
            UPDATE chat_participants
            SET deleted_up_to_seq = ?, hidden = true
            WHERE chat_id = ? AND user_id = ?
            RETURNING $PARTICIPANT_COLUMNS
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
                   c.last_seq AS chat_last_seq,
                   c.kind::text AS chat_kind,
                   c.title AS chat_title,
                   (SELECT count(*)
                      FROM chat_participants mc
                     WHERE mc.chat_id = me.chat_id
                       AND mc.state = 'active') AS member_count,
                   (SELECT COALESCE(MAX(op.last_read_seq), 0)
                      FROM chat_participants op
                     WHERE op.chat_id = me.chat_id
                       AND op.user_id <> me.user_id
                       AND op.state = 'active') AS others_read_up_to_seq
            FROM chat_participants me
            JOIN chats c ON c.id = me.chat_id
            WHERE me.user_id = ?
              AND me.state = 'active'
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
                   c.last_seq AS chat_last_seq,
                   c.kind::text AS chat_kind,
                   c.title AS chat_title,
                   (SELECT count(*)
                      FROM chat_participants mc
                     WHERE mc.chat_id = me.chat_id
                       AND mc.state = 'active') AS member_count,
                   (SELECT COALESCE(MAX(op.last_read_seq), 0)
                      FROM chat_participants op
                     WHERE op.chat_id = me.chat_id
                       AND op.user_id <> me.user_id
                       AND op.state = 'active') AS others_read_up_to_seq
            FROM chat_participants me
            JOIN chats c ON c.id = me.chat_id
            LEFT JOIN (VALUES ${cursorValues(cursorCount)}) AS k(chat_id, cur)
              ON k.chat_id = me.chat_id
            WHERE me.user_id = ?
              AND me.state = 'active'
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
