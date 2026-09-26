package webchat.backend.groups.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.groups.domain.port.GroupAdminAction
import webchat.backend.groups.domain.port.GroupAdminLogEntry
import webchat.backend.groups.domain.port.GroupAdminLogRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [GroupAdminLogRepository] (data-model 006 §Сущность 4,
 * FR-017; DIP: the adapter lives outside the domain, T013).
 *
 * Append-only by construction: exactly ONE INSERT statement and ONE
 * ordered SELECT exist — no update, no delete; the table deliberately
 * carries NO FK on `chats`, so entries SURVIVE the №30 hard delete of
 * their group (FR-006: the deletion fact itself is journaled against
 * the already-gone group id). The V14 CHECK `ck_group_admin_log_action`
 * and the Kotlin enum admit the same ten actions; the storage form is
 * the lower-case snake-case wire value. Message CONTENT never reaches
 * this adapter — only the facts (clarify 2026-09-25, constitution V).
 */
@Repository
class JdbcGroupAdminLogRepository(
    private val jdbcTemplate: JdbcTemplate,
) : GroupAdminLogRepository {
    /**
     * [Propagation.REQUIRED] (default): the journal row lands in the
     * AMBIENT transaction of the operation that caused it (T021+
     * service discipline — a lost fact must be impossible once the
     * operation is durable); with no ambient transaction (the adapter
     * slice of T012a) the append runs in its own.
     */
    @Transactional
    override fun append(entry: GroupAdminLogEntry) {
        jdbcTemplate.update(
            APPEND_SQL,
            UUID.randomUUID(),
            entry.groupId,
            entry.actorId,
            entry.targetUserId,
            wireNameOf(entry.action),
        )
    }

    override fun chronology(groupId: UUID): List<GroupAdminLogEntry> =
        jdbcTemplate
            .query(CHRONOLOGY_SQL, ROW_MAPPER, groupId)
            .toList()

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                GroupAdminLogEntry(
                    groupId = rs.getObject("group_id", UUID::class.java),
                    actorId = rs.getObject("actor_id", UUID::class.java),
                    targetUserId = rs.getObject("target_user_id", UUID::class.java),
                    action = actionOf(rs.getString("action")),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val APPEND_SQL =
            """
            INSERT INTO group_admin_log (id, group_id, actor_id, target_user_id, action)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()

        /** The audit read: ONE group, oldest first — the chronology index of V14 serves it. */
        val CHRONOLOGY_SQL =
            """
            SELECT group_id, actor_id, target_user_id, action, created_at
            FROM group_admin_log
            WHERE group_id = ?
            ORDER BY created_at, id
            """.trimIndent()

        /** The V14 CHECK form: lower-case snake_case, identical to the contract wire values. */
        private fun wireNameOf(action: GroupAdminAction): String = action.name.lowercase()

        private fun actionOf(wireName: String): GroupAdminAction = GroupAdminAction.valueOf(wireName.uppercase())
    }
}
