package webchat.backend.groups.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.groups.domain.model.InvalidGroupDescriptionException
import webchat.backend.groups.domain.model.InvalidGroupTitleException
import webchat.backend.groups.domain.service.ForbiddenRoleException
import webchat.backend.groups.domain.service.GroupFullException
import webchat.backend.groups.domain.service.GroupNotFoundException
import webchat.backend.groups.domain.service.InvalidRoleException
import webchat.backend.groups.domain.service.NotGroupOwnerException
import webchat.backend.groups.domain.service.NotInContactsException
import webchat.backend.groups.domain.service.OwnerMustTransferException
import webchat.backend.groups.domain.service.RoleHierarchyViolationException
import webchat.backend.groups.domain.service.SelfForbiddenException
import webchat.backend.groups.domain.service.TargetNotMemberException

/**
 * RFC 9457 rendering for the groups API errors (api-contract.md §1, the
 * same conventions as the chats
 * [webchat.backend.chats.api.ChatsExceptionHandler]): every failure
 * leaves the controller as a typed exception and reaches the client as
 * `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only, never
 * group contents or member details.
 *
 * Ordering is the contract's own: the 404 of the FR-008 membership gate
 * (T015) answers strangers and removed members alike, role refusals
 * resolve only after it; one @ExceptionHandler per contract failure
 * code, pinned red-first by GroupsExceptionHandlerTest (T015a).
 */
@Suppress("TooManyFunctions") // one @ExceptionHandler per contract failure code — the №27–№35 table is the size driver
@RestControllerAdvice
class GroupsExceptionHandler {
    /** 404 (api-contract.md §1, FR-008): the uniform answer of the membership gate — existence is never disclosed. */
    @ExceptionHandler(GroupNotFoundException::class)
    fun onGroupNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, GROUP_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(GROUP_FIELD to listOf(GROUP_NOT_FOUND_CODE))) }

    /** 403 (api-contract.md №29/№31/№32, FR-003/FR-004): an active member without a sufficient role. */
    @ExceptionHandler(ForbiddenRoleException::class)
    fun onForbiddenRole(): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, FORBIDDEN_ROLE_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(GROUP_FIELD to listOf(FORBIDDEN_ROLE_CODE))) }

    /** 403 (api-contract.md №32, FR-003): an admin targets a member not below them in the hierarchy. */
    @ExceptionHandler(RoleHierarchyViolationException::class)
    fun onRoleHierarchyViolation(): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, ROLE_HIERARCHY_VIOLATION_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_ID_FIELD to listOf(ROLE_HIERARCHY_VIOLATION_CODE))) }

    /** 403 (api-contract.md №30/№34/№35, FR-003): an owner-only operation. */
    @ExceptionHandler(NotGroupOwnerException::class)
    fun onNotGroupOwner(): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, NOT_GROUP_OWNER_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(GROUP_FIELD to listOf(NOT_GROUP_OWNER_CODE))) }

    /** 403 (api-contract.md №33, FR-005): the owner must transfer or delete before leaving. */
    @ExceptionHandler(OwnerMustTransferException::class)
    fun onOwnerMustTransfer(): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, OWNER_MUST_TRANSFER_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(GROUP_FIELD to listOf(OWNER_MUST_TRANSFER_CODE))) }

    /** 409 (api-contract.md №32/№34/№35): the target is not an active member. */
    @ExceptionHandler(TargetNotMemberException::class)
    fun onTargetNotMember(): ProblemDetail =
        problem(HttpStatus.CONFLICT, TARGET_NOT_MEMBER_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_ID_FIELD to listOf(TARGET_NOT_MEMBER_CODE))) }

    /** 409 (api-contract.md №27/№31, FR-002): the capacity conflict — active roster plus batch over the limit. */
    @ExceptionHandler(GroupFullException::class)
    fun onGroupFull(): ProblemDetail =
        problem(HttpStatus.CONFLICT, GROUP_FULL_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(GROUP_FIELD to listOf(GROUP_FULL_CODE))) }

    /**
     * 422 (api-contract.md №27/№31, FR-002): the batch is refused
     * atomically — someone is not a contact of the actor. The `errors`
     * field is operation-scoped and rides on the carrier
     * (`memberUserIds` of №27 vs `userIds` of №31); the №27 default
     * serves direct calls only — Spring always injects the thrown
     * carrier, and the per-operation fields are asserted end-to-end by
     * GroupLifecycleIT (T018).
     */
    @ExceptionHandler(NotInContactsException::class)
    fun onNotInContacts(failure: NotInContactsException? = null): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, NOT_IN_CONTACTS_DETAIL)
            .apply {
                setProperty(
                    ERRORS_PROPERTY,
                    mapOf((failure?.field ?: MEMBER_USER_IDS_FIELD) to listOf(NOT_IN_CONTACTS_CODE)),
                )
            }

    /**
     * 400 (api-contract.md №27/№31/№32/№34/№35): the operation targets
     * the caller themselves — the groups flavour renders 400, unlike the
     * 422 chats pair dialog of №11. The `errors` field is
     * operation-scoped and rides on the carrier (`memberUserIds` of №27,
     * `userIds` of №31, `userId` of №32/№34/№35); the `userId` default
     * serves direct calls only — Spring always injects the thrown
     * carrier, and the per-operation fields are asserted end-to-end by
     * GroupLifecycleIT (T018).
     */
    @ExceptionHandler(SelfForbiddenException::class)
    fun onSelfForbidden(failure: SelfForbiddenException? = null): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, SELF_FORBIDDEN_DETAIL)
            .apply {
                setProperty(
                    ERRORS_PROPERTY,
                    mapOf((failure?.field ?: USER_ID_FIELD) to listOf(SELF_FORBIDDEN_CODE)),
                )
            }

    /**
     * 400 (api-contract.md №27/№29): the FR-001 title refusal — both
     * violations of the field share ONE code `invalid_title`, unlike the
     * chats `text_blank`/`text_too_long` split; the submitted text is
     * never echoed (constitution V).
     */
    @ExceptionHandler(InvalidGroupTitleException::class)
    fun onInvalidGroupTitle(exception: InvalidGroupTitleException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "$INVALID_GROUP_TITLE_DETAIL (${exception.violation.name.lowercase()})")
            .apply { setProperty(ERRORS_PROPERTY, mapOf(TITLE_FIELD to listOf(INVALID_TITLE_CODE))) }

    /** 400 (api-contract.md №27/№29): the FR-001 description refusal — the single field code `invalid_description`. */
    @ExceptionHandler(InvalidGroupDescriptionException::class)
    fun onInvalidGroupDescription(exception: InvalidGroupDescriptionException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "$INVALID_GROUP_DESCRIPTION_DETAIL (${exception.violation.name.lowercase()})")
            .apply { setProperty(ERRORS_PROPERTY, mapOf(DESCRIPTION_FIELD to listOf(INVALID_DESCRIPTION_CODE))) }

    /** 400 (api-contract.md №34): `role` outside `admin`|`member` — `owner` moves only via the №35 transfer. */
    @ExceptionHandler(InvalidRoleException::class)
    fun onInvalidRole(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_ROLE_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(ROLE_FIELD to listOf(INVALID_ROLE_CODE))) }

    /** 400 (api-contract.md №27): the initial roster `memberUserIds` is not unique UUIDs within the 199 bound. */
    @ExceptionHandler(InvalidMemberIdsException::class)
    fun onInvalidMemberIds(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_MEMBER_IDS_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(MEMBER_USER_IDS_FIELD to listOf(INVALID_MEMBER_IDS_CODE))) }

    /** 400 (api-contract.md №31): the add-members batch `userIds` is absent or not unique UUIDs (199 bound). */
    @ExceptionHandler(InvalidUserIdsException::class)
    fun onInvalidUserIds(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_USER_IDS_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_IDS_FIELD to listOf(INVALID_USER_IDS_CODE))) }

    /**
     * 400 (api-contract.md №28/№31): the PATH `chatId` is not a UUID —
     * rendered as `errors: {chatId: [invalid_uuid]}`; a well-formed but
     * unknown/foreign chatId never reaches here, it answers the uniform
     * 404 of the service gate instead.
     */
    @ExceptionHandler(InvalidGroupChatIdException::class)
    fun onInvalidGroupChatId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_CHAT_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CHAT_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    /**
     * 400 (api-contract.md №32/№34/№35): the single-target `userId` —
     * the PATH variable of №32/№34, the body field of №35 — is not a
     * UUID; rendered as `errors: {userId: [invalid_uuid]}`. A
     * well-formed but foreign/unknown userId never reaches here — the
     * service answers `409 target_not_member` against the roster
     * instead (or the uniform 404 of the gate for the whole group).
     */
    @ExceptionHandler(InvalidGroupUserIdException::class)
    fun onInvalidGroupUserId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_USER_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    /** 400 (api-contract.md №29, FR-007): a PATCH body carrying none of `title`/`description`. */
    @ExceptionHandler(EmptyPatchException::class)
    fun onEmptyPatch(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, EMPTY_PATCH_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(BODY_FIELD to listOf(EMPTY_PATCH_CODE))) }

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private companion object {
        const val ERRORS_PROPERTY = "errors"
        const val GROUP_FIELD = "group"
        const val USER_ID_FIELD = "userId"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val USER_IDS_FIELD = "userIds"
        const val TITLE_FIELD = "title"
        const val DESCRIPTION_FIELD = "description"
        const val ROLE_FIELD = "role"
        const val BODY_FIELD = "body"
        const val CHAT_ID_FIELD = "chatId"
        const val GROUP_NOT_FOUND_CODE = "group_not_found"
        const val FORBIDDEN_ROLE_CODE = "forbidden_role"
        const val ROLE_HIERARCHY_VIOLATION_CODE = "role_hierarchy_violation"
        const val NOT_GROUP_OWNER_CODE = "not_group_owner"
        const val OWNER_MUST_TRANSFER_CODE = "owner_must_transfer"
        const val TARGET_NOT_MEMBER_CODE = "target_not_member"
        const val GROUP_FULL_CODE = "group_full"
        const val NOT_IN_CONTACTS_CODE = "not_in_contacts"
        const val SELF_FORBIDDEN_CODE = "self_forbidden"
        const val INVALID_TITLE_CODE = "invalid_title"
        const val INVALID_DESCRIPTION_CODE = "invalid_description"
        const val INVALID_ROLE_CODE = "invalid_role"
        const val INVALID_MEMBER_IDS_CODE = "invalid_member_ids"
        const val INVALID_USER_IDS_CODE = "invalid_user_ids"
        const val INVALID_UUID_CODE = "invalid_uuid"
        const val EMPTY_PATCH_CODE = "empty_patch"
        const val GROUP_NOT_FOUND_DETAIL = "The requested group was not found"
        const val FORBIDDEN_ROLE_DETAIL = "The caller's role does not allow this operation"
        const val ROLE_HIERARCHY_VIOLATION_DETAIL = "The caller's role is not above the target member's role"
        const val NOT_GROUP_OWNER_DETAIL = "Only the group owner may perform this operation"
        const val OWNER_MUST_TRANSFER_DETAIL = "The owner must transfer ownership or delete the group before leaving"
        const val TARGET_NOT_MEMBER_DETAIL = "The target user is not an active member of this group"
        const val GROUP_FULL_DETAIL = "The group member limit is reached"
        const val NOT_IN_CONTACTS_DETAIL = "Some of the requested users are not in the caller's contacts"
        const val SELF_FORBIDDEN_DETAIL = "The operation cannot target the caller themselves"
        const val INVALID_GROUP_TITLE_DETAIL = "group title violates FR-001 (blank or over 64 after trim)"
        const val INVALID_GROUP_DESCRIPTION_DETAIL = "group description violates FR-001 (over 256 after trim)"
        const val INVALID_ROLE_DETAIL = "role must be one of: admin, member"
        const val INVALID_MEMBER_IDS_DETAIL = "memberUserIds must be unique UUIDs (at most 199)"
        const val INVALID_USER_IDS_DETAIL = "userIds must be a non-empty array of unique UUIDs (at most 199)"
        const val INVALID_CHAT_ID_DETAIL = "chatId must be a UUID"
        const val INVALID_USER_ID_DETAIL = "userId must be a UUID"
        const val EMPTY_PATCH_DETAIL = "PATCH body must carry at least one of title, description"
    }
}
