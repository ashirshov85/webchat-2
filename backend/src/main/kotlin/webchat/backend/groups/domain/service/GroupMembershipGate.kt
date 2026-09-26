package webchat.backend.groups.domain.service

import org.springframework.stereotype.Component
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.groups.GroupMetrics
import java.util.UUID

/**
 * T015 compile stub — the per-request authorization primitive of every
 * gated group operation №28–№35 (FR-008): resolves the caller's ACTIVE
 * membership row through [ParticipantRepository.findActive] and refuses
 * everything else (stranger / removed member / unknown group) with the
 * uniform [GroupNotFoundException], incrementing
 * `webchat_group_authz_denials_total{operation}` (FR-017/SC-003).
 *
 * Behaviour owned by T015, pinned red-first by
 * GroupMembershipGateTest (T015a).
 */
@Component
@Suppress("UnusedPrivateProperty", "UnusedParameter") // compile stub until T015 fills the body
class GroupMembershipGate(
    private val participants: ParticipantRepository,
    private val metrics: GroupMetrics,
) {
    /**
     * Returns the caller's active membership row (with its role for the
     * FR-003/FR-004 checks) or throws [GroupNotFoundException].
     */
    fun requireActiveMembership(
        chatId: UUID,
        userId: UUID,
        operation: GroupMetrics.AuthzOperation,
    ): ChatParticipant = TODO("T015 — GroupMembershipGate behaviour (red-first: GroupMembershipGateTest)")
}
