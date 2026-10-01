package webchat.backend.groups.domain.service

import org.springframework.stereotype.Component
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.groups.domain.model.MemberRole

/**
 * T020+T042 — the COMPLETE FR-003/FR-004 role matrix of a group, applied
 * strictly AFTER the FR-008 membership gate ([GroupMembershipGate]): the
 * caller is already a proven ACTIVE member, so this policy answers the
 * only remaining question — is his ROLE (and, for №32, his position
 * AGAINST the target) sufficient for the operation.
 *
 *  | operation | owner | admin | member |
 *  |-----------|-------|-------|--------|
 *  | №31 add members (+ the №27 initial roster) | ✓ | ✓ | ✗ `forbidden_role` |
 *  | №32 kick | ✓ everyone but himself (№33-only) | only `member` targets | ✗ `forbidden_role` |
 *  | №29 metadata | ✓ | ✓ | ✗ `forbidden_role` |
 *  | №34 roles / №35 transfer / №30 delete | ✓ only | ✗ `not_group_owner` | ✗ `not_group_owner` |
 *
 * FR-004 hierarchy refusals are the TYPED 403 carriers of [GroupServiceExceptions]:
 * [ForbiddenRoleException] — the caller's role manages nothing at all
 * (`errors: {group: [forbidden_role]}`); [RoleHierarchyViolationException] —
 * an admin targeting NOT below him (№32: an admin excludes admin/owner);
 * [NotGroupOwnerException] — the owner-only operations of FR-003. No
 * metric grows here: `webchat_group_authz_denials_total` counts the
 * privacy refusals of the gate only (T015a), while a role refusal is an
 * answer to a proven member (research.md §8).
 *
 * The FR-003 owner invariant — exactly ONE active owner per group — is
 * pinned at the storage level by the partial unique index
 * `ux_chat_participants_owner` (V14); this policy keeps every path that
 * could race it closed: the owner role is not settable through №34 (the
 * T016 DTO refuses the label as `invalid_role` BEFORE the service), the
 * owner never re-roles/transfers to HIMSELF ([SelfForbiddenException],
 * decided by GroupService BEFORE this policy — the №32/№34/№35 order of
 * api-contract.md §2), and №35 flips the two rows demote-THEN-promote
 * inside ONE transaction (T043) so the partial index never sees two
 * owners — nor zero.
 *
 * Ordering discipline of №32 (api-contract.md №2): the self-kick is
 * answered `400 self_forbidden` BEFORE this policy (leaving is №33
 * only), then the caller's role decides — a plain member kicking
 * HIMSELF never reaches `forbidden_role`; the hierarchy check of an
 * admin runs BEFORE the target lookup races, against the ACTIVE row the
 * caller resolved (T043 — rowcount-protected UPDATE).
 *
 * Stateless and pure (constitution II/VII): judges the rows the gate
 * and the target lookup returned, mutates nothing, consults nothing.
 */
@Component
class GroupRolePolicy {
    /**
     * №31 `POST /groups/{chatId}/members` (FR-002/FR-004): adding
     * members is reserved to [MemberRole.OWNER] and [MemberRole.ADMIN];
     * a plain [MemberRole.MEMBER] manages nothing and is refused with
     * [ForbiddenRoleException]. A NULL role (a direct-dialog row that
     * can no longer reach here once the group row is resolved) is
     * refused identically — an unset role is never a permission.
     */
    fun requireCanAddMembers(caller: ChatParticipant) {
        if (caller.role !in ROLES_THAT_MANAGE_ROSTER) throw ForbiddenRoleException()
    }

    /**
     * №32 `DELETE /groups/{chatId}/members/{userId}` (FR-004, the FULL
     * hierarchy): the caller's role decides first — a plain
     * [MemberRole.MEMBER] is refused [ForbiddenRoleException] (a member
     * manages nothing, whoever the target is); an [MemberRole.ADMIN]
     * excludes ONLY [MemberRole.MEMBER] targets — an admin or owner
     * target is refused [RoleHierarchyViolationException] («исключение
     * admin или owner доступно только owner», FR-004); the
     * [MemberRole.OWNER] may exclude any ACTIVE member of the group —
     * every role but his own (the self-kick is answered BEFORE this
     * policy with `400 self_forbidden`, №33-only). [target] is the
     * caller-resolved ACTIVE row of the target: a non-member never
     * reaches the hierarchy question ([TargetNotMemberException], T043).
     */
    fun requireCanKick(
        caller: ChatParticipant,
        target: ChatParticipant,
    ) {
        when (caller.role) {
            MemberRole.OWNER -> Unit
            MemberRole.ADMIN ->
                if (target.role != MemberRole.MEMBER) throw RoleHierarchyViolationException()
            else -> throw ForbiddenRoleException()
        }
    }

    /**
     * №29 `PATCH /groups/{chatId}` (FR-007/FR-004): the title and the
     * description are managed by [MemberRole.OWNER] and
     * [MemberRole.ADMIN] — the SAME roster-management row of the FR-004
     * matrix as №31; a plain [MemberRole.MEMBER] is refused
     * [ForbiddenRoleException] (`403 forbidden_role`, api-contract.md
     * №29). Consumed by the US4 update scenario (T051); the matrix is
     * complete since T042, so the policy grows no new seam for it.
     */
    fun requireCanUpdateMetadata(caller: ChatParticipant) {
        if (caller.role !in ROLES_THAT_MANAGE_ROSTER) throw ForbiddenRoleException()
    }

    /**
     * №34 `PUT …/members/{userId}/role`, №35 `POST …/owner`, №30
     * `DELETE /groups/{chatId}` (FR-003): appointing/dismissing admins,
     * transferring ownership and deleting the group belong to the
     * [MemberRole.OWNER] ALONE — everyone else (admin, member and the
     * never-here NULL role of a direct row) is refused
     * [NotGroupOwnerException] (`403 not_group_owner`,
     * api-contract.md №30/№34/№35). The owner invariant itself is the
     * partial unique index of V14; this gate merely keeps the paths
     * closed (see the class doc).
     */
    fun requireOwner(caller: ChatParticipant) {
        if (caller.role != MemberRole.OWNER) throw NotGroupOwnerException()
    }

    private companion object {
        /**
         * FR-002/FR-007: «добавление участников … только участниками с
         * ролями owner или admin» — the roster/metadata row of the
         * FR-004 matrix (№31 and №29).
         */
        val ROLES_THAT_MANAGE_ROSTER: Set<MemberRole> = setOf(MemberRole.OWNER, MemberRole.ADMIN)
    }
}
