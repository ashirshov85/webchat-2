package webchat.backend.conferences.repository

import org.springframework.stereotype.Repository
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.conferences.domain.port.ChatMembershipChecker
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
 * Both legs are pure reads with NO mutation of the 006 state:
 * [isMember] is one `findActive` row read per check (the every-join
 * authorization gate, FR-003/SC-002), [activeMemberIds] is the
 * `activeMembers` roster snapshot in its deterministic
 * `created_at, user_id` order (the SSE addressee fold of T054/T055).
 */
@Repository
class ChatMembershipCheckerImpl(
    private val participants: ParticipantRepository,
) : ChatMembershipChecker {
    override fun isMember(
        chatId: UUID,
        userId: UUID,
    ): Boolean = participants.findActive(chatId, userId) != null

    override fun activeMemberIds(chatId: UUID): List<UUID> = participants.activeMembers(chatId).map { it.userId }
}
