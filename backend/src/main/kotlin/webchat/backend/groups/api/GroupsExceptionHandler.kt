package webchat.backend.groups.api

import org.springframework.http.ProblemDetail
import webchat.backend.groups.domain.model.InvalidGroupDescriptionException
import webchat.backend.groups.domain.model.InvalidGroupTitleException

/**
 * T017 compile stub — the RFC 9457 problem+json rendering of the groups
 * failure vocabulary (api-contract.md §1–§4, conventions of
 * [webchat.backend.chats.api.ChatsExceptionHandler]): every typed refusal
 * leaves the controller as an exception and reaches the client as
 * `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only (constitution V).
 *
 * Mapping owned by T017, pinned red-first by
 * GroupsExceptionHandlerTest (T015a). One handler per contract code;
 * compile stub until T017 fills the mapping.
 */
@Suppress("TooManyFunctions", "UnusedParameter")
class GroupsExceptionHandler {
    /** 404 `group_not_found` on `group` — the T015 gate carrier (FR-008). */
    fun onGroupNotFound(): ProblemDetail = TODO("T017")

    /** 403 `forbidden_role` on `group` — the role is not allowed (FR-003/FR-004). */
    fun onForbiddenRole(): ProblemDetail = TODO("T017")

    /** 403 `role_hierarchy_violation` on `userId` — actor not above target (FR-003). */
    fun onRoleHierarchyViolation(): ProblemDetail = TODO("T017")

    /** 403 `not_group_owner` on `group` — owner-only operation (FR-003). */
    fun onNotGroupOwner(): ProblemDetail = TODO("T017")

    /** 403 `owner_must_transfer` on `group` — №33 before №35 (FR-005). */
    fun onOwnerMustTransfer(): ProblemDetail = TODO("T017")

    /** 409 `target_not_member` on `userId` — target is not an active member. */
    fun onTargetNotMember(): ProblemDetail = TODO("T017")

    /** 409 `group_full` on `group` — the 200-member limit (FR-002). */
    fun onGroupFull(): ProblemDetail = TODO("T017")

    /** 422 `not_in_contacts` — №27/№31 contact sourcing, field is operation-scoped. */
    fun onNotInContacts(): ProblemDetail = TODO("T017")

    /** 400 `self_forbidden` — №27/№31/№32/№34/№35 self-targeting, field is operation-scoped. */
    fun onSelfForbidden(): ProblemDetail = TODO("T017")

    /** 400 `invalid_title` on `title` — one code per field (№27/№29). */
    fun onInvalidGroupTitle(exception: InvalidGroupTitleException): ProblemDetail = TODO("T017")

    /** 400 `invalid_description` on `description` (№27/№29). */
    fun onInvalidGroupDescription(exception: InvalidGroupDescriptionException): ProblemDetail = TODO("T017")

    /** 400 `invalid_role` on `role` (№34). */
    fun onInvalidRole(): ProblemDetail = TODO("T017")

    /** 400 `invalid_member_ids` on `memberUserIds` (№27). */
    fun onInvalidMemberIds(): ProblemDetail = TODO("T017")

    /** 400 `invalid_user_ids` on `userIds` (№31). */
    fun onInvalidUserIds(): ProblemDetail = TODO("T017")

    /** 400 `empty_patch` on `body` (№29). */
    fun onEmptyPatch(): ProblemDetail = TODO("T017")
}
