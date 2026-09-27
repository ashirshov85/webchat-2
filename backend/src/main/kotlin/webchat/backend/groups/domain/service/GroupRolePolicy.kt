package webchat.backend.groups.domain.service

import org.springframework.stereotype.Component
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.groups.domain.model.MemberRole

/**
 * T020 — the FR-004 role matrix of a group, applied strictly AFTER the
 * FR-008 membership gate ([GroupMembershipGate]): the caller is already
 * a proven ACTIVE member, so this policy answers the only remaining
 * question — is his ROLE sufficient for the operation. The US1 slice
 * pins the roster-management rule of FR-002/FR-004:
 *
 *  | operation | owner | admin | member |
 *  |-----------|-------|-------|--------|
 *  | №31 add members (+ the №27 initial roster) | ✓ | ✓ | ✗ `forbidden_role` |
 *  | №32 kick | ✓ (T042) | only `member` targets (T042) | ✗ (T042) |
 *  | №29 metadata | ✓ (T051) | ✓ (T051) | ✗ (T051) |
 *  | №34 roles / №35 transfer / №30 delete | ✓ only (T042/T064) | ✗ (T042/T064) | ✗ (T042/T064) |
 *
 * A refusal is the typed 403 carrier [ForbiddenRoleException]
 * (`errors: {group: [forbidden_role]}`, api-contract.md №31) — no metric
 * grows here: `webchat_group_authz_denials_total` counts the privacy
 * refusals of the gate only (T015a), while a role refusal is an
 * answer to a proven member (research.md §8). Pinned end-to-end by
 * GroupLifecycleIT/GroupLimitsIT (T018/T018a): a plain member adding
 * even his OWN contact gets `403 forbidden_role` — only the role can
 * refuse there.
 *
 * Stateless and pure (constitution II/VII): judges the row the gate
 * returned, mutates nothing, consults nothing — the US3/T042 hierarchy
 * extension (caller-vs-target) grows this class without a new seam.
 */
@Component
class GroupRolePolicy {
    /**
     * №31 `POST /groups/{chatId}/members` (FR-002/FR-004): adding
     * members is reserved to [MemberRole.OWNER] and [MemberRole.ADMIN];
     * a plain [MemberRole.MEMBER] manages nothing and is refused with
     * [ForbiddenRoleException]. A NULL role (a direct-dialog row that
     * can no longer reach here once T021 resolves the group first) is
     * refused identically — an unset role is never a permission.
     */
    fun requireCanAddMembers(caller: ChatParticipant) {
        if (caller.role !in ROLES_THAT_ADD_MEMBERS) throw ForbiddenRoleException()
    }

    private companion object {
        /**
         * FR-002: «добавление участников … только участниками с ролями
         * owner или admin» — the single US1 row of the FR-004 matrix.
         */
        val ROLES_THAT_ADD_MEMBERS: Set<MemberRole> = setOf(MemberRole.OWNER, MemberRole.ADMIN)
    }
}
