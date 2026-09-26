package webchat.backend.groups.domain.service

/**
 * 403 `forbidden_role` (api-contract.md §1, №29/№31/№32): the caller IS
 * an active member but their role is not sufficient for the operation —
 * resolved strictly AFTER the FR-008 membership gate
 * ([GroupMembershipGate], «проверка до различения ролей»). The message
 * carries no chat or user identifiers (constitution V).
 *
 * Thrown by the role policy of GroupService (T022+), rendered by T017
 * (`GroupsExceptionHandler`); pinned red-first by
 * GroupsExceptionHandlerTest (T015a).
 */
class ForbiddenRoleException : RuntimeException("the caller's role does not allow this operation") {
    val code: String = CODE_FORBIDDEN_ROLE
}

/**
 * 403 `role_hierarchy_violation` (api-contract.md №32, FR-003): an admin
 * targets a member NOT below them in the hierarchy (admin excludes
 * admin/owner) — the refusal of the kick operation only, self-targeting
 * is answered earlier with `self_forbidden` (№32 ordering).
 *
 * Thrown by the role policy of GroupService (T022+), rendered by T017;
 * pinned red-first by GroupsExceptionHandlerTest (T015a).
 */
class RoleHierarchyViolationException : RuntimeException("the caller's role is not above the target member's role") {
    val code: String = CODE_ROLE_HIERARCHY_VIOLATION
}

/**
 * 403 `not_group_owner` (api-contract.md №30/№34/№35, FR-003): the
 * operation belongs to the owner alone — delete the group, appoint or
 * dismiss admins, transfer ownership. Resolved after the membership
 * gate; no identifiers in the message (constitution V).
 *
 * Thrown by the role policy of GroupService (T022+), rendered by T017;
 * pinned red-first by GroupsExceptionHandlerTest (T015a).
 */
class NotGroupOwnerException : RuntimeException("only the group owner may perform this operation") {
    val code: String = CODE_NOT_GROUP_OWNER
}

/**
 * 403 `owner_must_transfer` (api-contract.md №33, FR-005): the owner
 * cannot just LEAVE — a group always has exactly one owner, so the
 * ownership must be transferred (№35) or the group deleted (№30)
 * first.
 *
 * Thrown by GroupService leave (T022+), rendered by T017; pinned
 * red-first by GroupsExceptionHandlerTest (T015a).
 */
class OwnerMustTransferException : RuntimeException("the owner must transfer ownership or delete the group first") {
    val code: String = CODE_OWNER_MUST_TRANSFER
}

/**
 * 422 `not_in_contacts` (api-contract.md №27/№31, FR-002): someone in
 * the batch is not a contact of the actor — the WHOLE request is
 * refused atomically, the group is not created / the roster does not
 * change. [field] is the operation-scoped `errors` key: `memberUserIds`
 * of №27 vs `userIds` of №31; the submitted values are never echoed
 * (constitution V).
 *
 * Thrown by GroupService contact sourcing (T022+), rendered by T017
 * (`GroupsExceptionHandler`); pinned red-first by
 * GroupsExceptionHandlerTest (T015a) — the unit pin checks the code
 * only, the per-operation fields are asserted end-to-end by
 * GroupLifecycleIT (T018).
 */
class NotInContactsException(
    val field: String,
) : RuntimeException("some of the requested users are not in the caller's contacts") {
    val code: String = CODE_NOT_IN_CONTACTS
}

/**
 * 409 `group_full` (api-contract.md №27/№31, FR-002): the active roster
 * plus the batch exceeds the configured capacity (`groups.max-members`,
 * 200 by default) — a capacity conflict, nothing is written.
 *
 * Thrown by GroupService capacity checks (T022+), rendered by T017;
 * pinned red-first by GroupsExceptionHandlerTest (T015a).
 */
class GroupFullException : RuntimeException("the group member limit is reached") {
    val code: String = CODE_GROUP_FULL
}

/**
 * 409 `target_not_member` (api-contract.md №32/№34/№35): the target of
 * a member operation is not an ACTIVE member (never was or already
 * removed) — resolved after the membership gate of the CALLER.
 *
 * Thrown by GroupService member operations (T022+), rendered by T017;
 * pinned red-first by GroupsExceptionHandlerTest (T015a).
 */
class TargetNotMemberException : RuntimeException("the target user is not an active member of this group") {
    val code: String = CODE_TARGET_NOT_MEMBER
}

/**
 * 400 `self_forbidden` (api-contract.md №27/№31/№32/№34/№35): the
 * operation targets the caller themselves — the creator in the initial
 * roster (№27), the adder in his own batch (№31), self-exclusion instead
 * of the №33 leave (№32 — checked BEFORE the role hierarchy), the owner
 * re-role/transfer to himself (№34/№35). [field] is the operation-scoped
 * `errors` key: `memberUserIds` of №27, `userIds` of №31, `userId` of
 * №32/№34/№35. The groups flavour renders 400, unlike the 422 chats
 * pair dialog of №11 (api-contract.md §1).
 *
 * Thrown by GroupService (T022+), rendered by T017
 * (`GroupsExceptionHandler`); pinned red-first by
 * GroupsExceptionHandlerTest (T015a) — the unit pin checks the code
 * only, the per-operation fields are asserted end-to-end by
 * GroupLifecycleIT (T018).
 */
class SelfForbiddenException(
    val field: String,
) : RuntimeException("the operation cannot target the caller themselves") {
    val code: String = CODE_SELF_FORBIDDEN
}

private const val CODE_FORBIDDEN_ROLE = "forbidden_role"
private const val CODE_ROLE_HIERARCHY_VIOLATION = "role_hierarchy_violation"
private const val CODE_NOT_GROUP_OWNER = "not_group_owner"
private const val CODE_OWNER_MUST_TRANSFER = "owner_must_transfer"
private const val CODE_NOT_IN_CONTACTS = "not_in_contacts"
private const val CODE_GROUP_FULL = "group_full"
private const val CODE_TARGET_NOT_MEMBER = "target_not_member"
private const val CODE_SELF_FORBIDDEN = "self_forbidden"
