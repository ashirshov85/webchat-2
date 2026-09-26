package webchat.backend.groups.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.groups.domain.model.GroupChat
import webchat.backend.groups.domain.model.GroupDescription
import webchat.backend.groups.domain.model.GroupTitle
import webchat.backend.groups.domain.port.GroupRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [GroupRepository] (data-model 006 §Сущности 1/2; DIP:
 * the adapter lives outside the domain, T012).
 *
 * The scope is the `kind='group'` row of `chats` ONLY — the roster rows
 * ride the extended [webchat.backend.chats.domain.port.ParticipantRepository]
 * (T008/T011) and the audit trail through [GroupAdminLogRepository]
 * (T013). Every statement carries the `kind='group'` predicate, so a
 * direct dialog and an unknown id resolve IDENTICALLY (`null`/rowcount 0
 * → the single privacy `404 group_not_found` of the gate, FR-008/FR-009).
 * The stored title/description are CHECK-constrained by V14 to satisfy
 * [GroupTitle]/[GroupDescription], so the read projections re-validate
 * through `normalize` for free — the same discipline as
 * `MessageText.normalize` on read in `JdbcChatRepository`.
 */
@Repository
class JdbcGroupRepository(
    private val jdbcTemplate: JdbcTemplate,
) : GroupRepository {
    /**
     * №27 (data-model 006 §Сущность 2 «№27 создание»): the chats row and
     * the WHOLE roster in ONE transaction — the INSERT…RETURNING resolves
     * the server-generated row, then a single roster INSERT…SELECT joins
     * a VALUES batch (the creator as `owner`, every initial member as
     * `member`, both `state='active'`) against the same `chats` row, so
     * BOTH watermarks of EVERY roster row anchor at `chats.last_seq`
     * read inside the statement (FR-013: a fresh group starts at 0 — the
     * badge-from-0 semantics; the pre-add history never re-delivers).
     * A batch violating the roster PK (a duplicate the service's
     * `self_forbidden`/contact checks missed) surfaces as the key
     * violation that rolls the WHOLE transaction back — an empty group
     * row never survives a rejected create.
     */
    @Suppress("SpreadOperator") // the dynamic VALUES join makes the argument list per-member — a tiny one-off copy
    @Transactional
    override fun create(
        title: GroupTitle,
        description: GroupDescription?,
        ownerId: UUID,
        memberIds: List<UUID>,
    ): GroupChat {
        val group =
            jdbcTemplate
                .query(INSERT_GROUP_SQL, GROUP_ROW_MAPPER, UUID.randomUUID(), title.value, description?.value)
                .firstOrNull()
                ?: error("INSERT … RETURNING must answer")
        jdbcTemplate.update(insertRosterSql(memberIds.size), *rosterArgs(group.id, ownerId, memberIds))
        return group
    }

    override fun find(id: UUID): GroupChat? = jdbcTemplate.query(FIND_SQL, GROUP_ROW_MAPPER, id).firstOrNull()

    /**
     * The №31 serialization (data-model 006 §Сущность 2 «№31
     * добавление»): `SELECT … FOR UPDATE` of the group row joins the
     * AMBIENT transaction — no annotation of its own — so concurrent
     * additions queue on the row lock and the `countActive + batch ≤ 200`
     * check with the subsequent inserts form one serialized section
     * (`409 group_full` can never be outrun by a racing batch).
     */
    override fun findForUpdate(id: UUID): GroupChat? =
        jdbcTemplate
            .query(FIND_FOR_UPDATE_SQL, GROUP_ROW_MAPPER, id)
            .firstOrNull()

    /**
     * №29 (data-model 006 §Сущность 1): the atomic last-confirmed patch —
     * ONE conditional UPDATE…RETURNING, so two concurrent renames
     * converge on exactly one confirmed result; the `kind='group'`
     * predicate keeps direct dialogs untouched (no partial writes).
     */
    override fun updateMetadata(
        id: UUID,
        title: GroupTitle,
        description: GroupDescription?,
    ): GroupChat? =
        jdbcTemplate
            .query(UPDATE_METADATA_SQL, GROUP_ROW_MAPPER, title.value, description?.value, id)
            .firstOrNull()

    /** The 200-limit check of №27/№31 (FR-002) over the partial index of V14. */
    override fun countActive(id: UUID): Int = jdbcTemplate.queryForObject(COUNT_ACTIVE_SQL, Int::class.java, id) ?: 0

    /**
     * №30 (FR-006): the single hard delete — the `chat_participants` and
     * `messages` rows CASCADE away on the existing FKs; rowcount 1 is
     * `true`, a repeated №30, a direct dialog and an unknown id are the
     * `false` → `404` convergence of api-contract.md 006 §2.
     */
    override fun deleteHard(id: UUID): Boolean = jdbcTemplate.update(DELETE_HARD_SQL, id) == 1

    private companion object {
        val GROUP_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                GroupChat(
                    id = rs.getObject("id", UUID::class.java),
                    // The V14 CHECKs guarantee the stored values re-validate
                    // (btrim is idempotent — the same read discipline as
                    // MessageText in JdbcChatRepository).
                    title = GroupTitle.normalize(rs.getString("title")),
                    description = rs.getString("description")?.let { GroupDescription.normalize(it) },
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    lastSeq = rs.getLong("last_seq"),
                )
            }

        /** Every group projection selects the same column set as [GROUP_ROW_MAPPER] maps. */
        const val GROUP_COLUMNS = "id, title, description, created_at, last_seq"

        val INSERT_GROUP_SQL =
            """
            INSERT INTO chats (id, kind, title, description)
            VALUES (?, 'group', ?, ?)
            RETURNING $GROUP_COLUMNS
            """.trimIndent()

        val FIND_SQL =
            """
            SELECT $GROUP_COLUMNS
            FROM chats
            WHERE id = ? AND kind = 'group'
            """.trimIndent()

        val FIND_FOR_UPDATE_SQL =
            """
            SELECT $GROUP_COLUMNS
            FROM chats
            WHERE id = ? AND kind = 'group'
            FOR UPDATE
            """.trimIndent()

        val UPDATE_METADATA_SQL =
            """
            UPDATE chats
            SET title = ?, description = ?
            WHERE id = ? AND kind = 'group'
            RETURNING $GROUP_COLUMNS
            """.trimIndent()

        val COUNT_ACTIVE_SQL =
            """
            SELECT count(*)
            FROM chat_participants
            WHERE chat_id = ? AND state = 'active'
            """.trimIndent()

        val DELETE_HARD_SQL =
            """
            DELETE FROM chats
            WHERE id = ? AND kind = 'group'
            """.trimIndent()

        private const val ROSTER_ROW = "(?::uuid, ?::varchar)"

        /**
         * The roster VALUES join: one row per member — the creator first
         * as `owner`, then every initial member as `member` (the batch is
         * never empty: the creator row is always present).
         */
        private fun insertRosterSql(memberCount: Int): String =
            """
            INSERT INTO chat_participants (chat_id, user_id, role, state, last_read_seq, delivered_up_to_seq)
            SELECT c.id, v.user_id, v.role, 'active', c.last_seq, c.last_seq
            FROM chats c
            JOIN (VALUES ${rosterValues(memberCount)}) AS v(user_id, role) ON true
            WHERE c.id = ?
            """.trimIndent()

        private fun rosterValues(memberCount: Int): String = (0..memberCount).joinToString(", ") { ROSTER_ROW }

        /**
         * The bind order follows the TEXTUAL placeholder order: the VALUES
         * rows of the FROM clause come BEFORE `c.id = ?`.
         */
        private fun rosterArgs(
            chatId: UUID,
            ownerId: UUID,
            memberIds: List<UUID>,
        ): Array<Any> =
            buildList {
                add(ownerId)
                add("owner")
                memberIds.forEach { memberId ->
                    add(memberId)
                    add("member")
                }
                add(chatId)
            }.toTypedArray()
    }
}
