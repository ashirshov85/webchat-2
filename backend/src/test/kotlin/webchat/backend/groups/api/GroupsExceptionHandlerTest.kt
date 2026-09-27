package webchat.backend.groups.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import webchat.backend.groups.domain.model.GroupDescriptionViolation
import webchat.backend.groups.domain.model.GroupTitleViolation
import webchat.backend.groups.domain.model.InvalidGroupDescriptionException
import webchat.backend.groups.domain.model.InvalidGroupTitleException
import java.util.stream.Stream

/**
 * T015a — unit-level acceptance of the RFC 9457 problem+json mapping
 * that T017 (`GroupsExceptionHandler`) must render, written red-first
 * per the constitution VI discipline of Phase 2. The conventions are the
 * ones of [webchat.backend.chats.api.ChatsExceptionHandler]: every
 * failure leaves the controller as a typed exception and reaches the
 * client as `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only, never
 * group contents or member details (constitution V).
 *
 * The typed carriers the table below pins (one @ExceptionHandler per
 * contract failure code):
 *  * `webchat.backend.groups.domain.service` — `GroupNotFoundException`
 *    (thrown by the T015 gate), `ForbiddenRoleException`,
 *    `RoleHierarchyViolationException`, `NotGroupOwnerException`,
 *    `OwnerMustTransferException`, `NotInContactsException`,
 *    `GroupFullException`, `TargetNotMemberException`,
 *    `SelfForbiddenException` (the groups flavour renders 400 — №27/
 *    №31/№32/№34/№35, unlike the 422 chats pair dialog of №11) and
 *    `InvalidRoleException`;
 *  * `webchat.backend.groups.domain.model` — `InvalidGroupTitleException`
 *    / `InvalidGroupDescriptionException` (both violations of a field
 *    share ONE code, api-contract.md №27/№29 — unlike the chats
 *    `text_blank`/`text_too_long` split);
 *  * `webchat.backend.groups.api` — the request-shape refusals
 *    `InvalidMemberIdsException` (№27), `InvalidUserIdsException` (№31)
 *    and `EmptyPatchException` (№29).
 *
 * The `errors` field name is pinned where the contract binds it to ONE
 * field for every operation (`group`, `title`, `description`, `role`,
 * `userId`, `memberUserIds`, `userIds`, `body`); `not_in_contacts` and
 * `self_forbidden` are operation-scoped (`memberUserIds` of №27 vs
 * `userIds` of №31 vs `userId` of №32/№34/№35), so only the code is
 * pinned here and the per-operation fields are asserted end-to-end by
 * GroupLifecycleIT (T018/T040/T049/T059). The `invalid_uuid` rendering
 * of malformed path parameters is owned by the controller layer
 * (T022/T044/T065) and the №15–№17/№25 chats semantics stay with the
 * 004 handler (`chat_not_found`/`not_participant`, FR-008 оговорка).
 */
class GroupsExceptionHandlerTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("mappingCases")
    fun `every typed refusal renders its contract status and problem code`(
        code: String,
        status: HttpStatus,
        field: String?,
        rendering: () -> ProblemDetail,
    ) {
        val problem = rendering()

        assertThat(problem.status)
            .overridingErrorMessage("$code must render as HTTP ${status.value()}")
            .isEqualTo(status.value())

        val errors = errorsOf(problem)
        assertThat(errors.values.flatten())
            .overridingErrorMessage("$code must arrive as the single problem code of the errors map")
            .containsExactly(code)
        if (field != null) {
            assertThat(errors)
                .overridingErrorMessage("$code must sit on the \"$field\" field of the errors map")
                .containsOnlyKeys(field)
        }
    }

    companion object {
        private val handler = GroupsExceptionHandler()

        @JvmStatic
        fun mappingCases(): Stream<Arguments> =
            listOf(
                // §1 privacy core: the single 404 answer of every gated operation (T015/T056)
                case("group_not_found", HttpStatus.NOT_FOUND, FIELD_GROUP) { handler.onGroupNotFound() },
                // §2 role refusals — resolved strictly AFTER the membership gate (FR-008)
                case("forbidden_role", HttpStatus.FORBIDDEN, FIELD_GROUP) { handler.onForbiddenRole() },
                case("role_hierarchy_violation", HttpStatus.FORBIDDEN, FIELD_USER_ID) {
                    handler.onRoleHierarchyViolation()
                },
                case("not_group_owner", HttpStatus.FORBIDDEN, FIELD_GROUP) { handler.onNotGroupOwner() },
                case("owner_must_transfer", HttpStatus.FORBIDDEN, FIELD_GROUP) { handler.onOwnerMustTransfer() },
                case("target_not_member", HttpStatus.CONFLICT, FIELD_USER_ID) { handler.onTargetNotMember() },
                case("group_full", HttpStatus.CONFLICT, FIELD_GROUP) { handler.onGroupFull() },
                // №27/№31 contact sourcing — 422, field is operation-scoped (memberUserIds/userIds)
                case("not_in_contacts", HttpStatus.UNPROCESSABLE_ENTITY, null) { handler.onNotInContacts() },
                // the groups self-targeting refusals render 400; field is operation-scoped
                case("self_forbidden", HttpStatus.BAD_REQUEST, null) { handler.onSelfForbidden() },
                // the invalid_* family: one code per field regardless of the violated rule
                case("invalid_title", HttpStatus.BAD_REQUEST, FIELD_TITLE) {
                    handler.onInvalidGroupTitle(InvalidGroupTitleException(GroupTitleViolation.BLANK))
                },
                case("invalid_title", HttpStatus.BAD_REQUEST, FIELD_TITLE) {
                    handler.onInvalidGroupTitle(InvalidGroupTitleException(GroupTitleViolation.TOO_LONG))
                },
                case("invalid_description", HttpStatus.BAD_REQUEST, FIELD_DESCRIPTION) {
                    handler.onInvalidGroupDescription(
                        InvalidGroupDescriptionException(GroupDescriptionViolation.TOO_LONG),
                    )
                },
                case("invalid_role", HttpStatus.BAD_REQUEST, FIELD_ROLE) { handler.onInvalidRole() },
                case("invalid_member_ids", HttpStatus.BAD_REQUEST, FIELD_MEMBER_USER_IDS) {
                    handler.onInvalidMemberIds()
                },
                case("invalid_user_ids", HttpStatus.BAD_REQUEST, FIELD_USER_IDS) { handler.onInvalidUserIds() },
                case("empty_patch", HttpStatus.BAD_REQUEST, FIELD_BODY) { handler.onEmptyPatch() },
            ).stream()

        private fun case(
            code: String,
            status: HttpStatus,
            field: String?,
            rendering: () -> ProblemDetail,
        ): Arguments = Arguments.of(code, status, field, rendering)

        @Suppress("UNCHECKED_CAST")
        private fun errorsOf(problem: ProblemDetail): Map<String, List<String>> =
            problem.properties?.get(ERRORS_PROPERTY) as? Map<String, List<String>> ?: emptyMap()

        private const val ERRORS_PROPERTY = "errors"
        private const val FIELD_GROUP = "group"
        private const val FIELD_USER_ID = "userId"
        private const val FIELD_TITLE = "title"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_ROLE = "role"
        private const val FIELD_MEMBER_USER_IDS = "memberUserIds"
        private const val FIELD_USER_IDS = "userIds"
        private const val FIELD_BODY = "body"
    }
}
