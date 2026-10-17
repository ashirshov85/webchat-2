package webchat.backend.conferences.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.conferences.domain.port.ChatInfo
import webchat.backend.conferences.domain.port.ChatMembershipChecker
import java.sql.ResultSet
import java.util.UUID

/**
 * T013 — the DIP adapter of [ChatMembershipChecker]: a thin projection
 * over the ONE reusable 006 membership mechanism, the chats
 * [ParticipantRepository] port — the same single ACTIVE-row read the
 * [webchat.backend.groups.domain.service.GroupMembershipGate]
 * resolution half (`findActiveMembership`) performs for the group
 * paths, taken directly here so the projection stays identical for
 * BOTH chat kinds (an ACTIVE row of `chat_participants` answers for a
 * direct dialog and a group alike; a REMOVED row of №32/№33 drops out
 * of the read's `state='active'` condition on its own).
 *
 * Deliberately NOT the gate's refusal half: `requireActiveMembership`
 * counts `webchat_group_authz_denials_total` and throws the 004
 * privacy `GroupNotFoundException` — the counter's operation
 * vocabulary is the gated №28–№35 only (research.md §2), and the
 * conferences join gate renders every non-membership answer as the
 * same `403 conference_forbidden` at ITS caller (T019) — the adapter
 * answers a plain Boolean and never discloses anything.
 *
 * The T017 chat row leg ([findChatInfo]) reads `chats` directly over
 * [JdbcTemplate] instead of the chats `ChatRepository` port: the only
 * attribute it needs — the V18 `e2ee` boundary flag — is not part of
 * the chats [webchat.backend.chats.domain.model.Chat] projection, and
 * the cross-domain SQL read of `chats` is the established monolith
 * convention ([JdbcGroupRepository] reads `chats` with its
 * `kind='group'` predicates the same way). The membership legs stay
 * pure reads with NO mutation of the 006 state: [ChatMembershipCheckerImpl.isMember]
 * is one `findActive` row read per check (the every-join authorization
 * gate, FR-003/SC-002), [ChatMembershipCheckerImpl.activeMemberIds] is
 * the `activeMembers` roster snapshot in its deterministic
 * `created_at, user_id` order (the SSE addressee fold of T054/T055).
 */
@Repository
class ChatMembershipCheckerImpl(
    private val participants: ParticipantRepository,
    private val jdbcTemplate: JdbcTemplate,
) : ChatMembershipChecker {
    override fun findChatInfo(chatId: UUID): ChatInfo? =
        jdbcTemplate.query(FIND_CHAT_INFO_SQL, CHAT_INFO_MAPPER, chatId).firstOrNull()

    override fun isMember(
        chatId: UUID,
        userId: UUID,
    ): Boolean = participants.findActive(chatId, userId) != null

    override fun activeMemberIds(chatId: UUID): List<UUID> = participants.activeMembers(chatId).map { it.userId }

    private companion object {
        val CHAT_INFO_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ChatInfo(
                    id = rs.getObject("id", UUID::class.java),
                    e2ee = rs.getBoolean("e2ee"),
                )
            }

        val FIND_CHAT_INFO_SQL =
            """
            SELECT id, e2ee
            FROM chats
            WHERE id = ?
            """.trimIndent()
    }
}
