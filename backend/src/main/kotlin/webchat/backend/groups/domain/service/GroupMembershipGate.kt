package webchat.backend.groups.domain.service

import org.springframework.stereotype.Component
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.groups.GroupMetrics
import java.util.UUID

/**
 * T015 — the per-request authorization primitive of every gated group
 * operation №28–№35 (FR-008): resolves the caller's ACTIVE membership
 * row through [ParticipantRepository.findActive] and refuses everything
 * else (stranger / removed member / unknown group) with the uniform
 * [GroupNotFoundException], incrementing
 * `webchat_group_authz_denials_total{operation}` (FR-017/SC-003).
 *
 * The gate runs BEFORE any role distinction («проверка до различения
 * ролей», T056): every non-membership answer of a group operation is a
 * counted, identifier-free 404 — the group's existence is never
 * disclosed (FR-008/FR-009). Pinned by GroupMembershipGateTest (T015a).
 */
@Component
class GroupMembershipGate(
    private val participants: ParticipantRepository,
    private val metrics: GroupMetrics,
) {
    /**
     * Returns the caller's active membership row (with its role for the
     * FR-003/FR-004 checks) or throws [GroupNotFoundException] after
     * counting the refusal in `webchat_group_authz_denials_total` — one
     * [ParticipantRepository.findActive] read per check, no mutation.
     */
    fun requireActiveMembership(
        chatId: UUID,
        userId: UUID,
        operation: GroupMetrics.AuthzOperation,
    ): ChatParticipant {
        val membership = participants.findActive(chatId, userId)
        if (membership == null) {
            metrics.countAuthzDenial(operation)
            throw GroupNotFoundException()
        }
        return membership
    }
}
