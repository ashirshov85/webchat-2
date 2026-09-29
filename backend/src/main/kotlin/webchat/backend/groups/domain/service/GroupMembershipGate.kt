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
 *
 * T034 additionally lends the RESOLUTION half of the gate
 * ([findActiveMembership]) to the chats paths №15–№17/№25 (006,
 * api-contract.md §3): they resolve the same ACTIVE row but keep their
 * own 004 refusal order and never count the №28–№35 denial counter.
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

    /**
     * T034 (tasks.md Phase 4, api-contract.md 006 §3): the RESOLUTION half
     * of the gate, lent to the chats paths №15–№17/№25 — the caller's
     * ACTIVE membership row of a group with NO refusal semantics of its
     * own. The 004 refusal order (`404 chat_not_found` →
     * `403 not_participant`, a `state='removed'` row answering as a
     * non-participant) belongs to the calling `ChatService.get`, and no
     * authz sample is counted here: the counter's operation vocabulary is
     * the gated №28–№35 only (research.md §8) — the 004 paths never grow
     * `webchat_group_authz_denials_total`.
     */
    fun findActiveMembership(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant? = participants.findActive(chatId, userId)
}
