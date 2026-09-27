package webchat.backend.groups.api

/**
 * 400 `invalid_member_ids` (api-contract.md №27): the initial roster
 * `memberUserIds` is not an array of unique UUIDs within the 199 batch
 * bound — a request-shape refusal decided before any contact or capacity
 * rule runs (`422 not_in_contacts` / `409 group_full` stay with
 * GroupService). The submitted values are never echoed (constitution V).
 *
 * Thrown by the №27 request validation (T016 `CreateGroupRequest`),
 * rendered by T017 (`GroupsExceptionHandler`); pinned red-first by
 * GroupsExceptionHandlerTest (T015a).
 */
class InvalidMemberIdsException : RuntimeException("memberUserIds must be unique UUIDs (at most 199)")

/**
 * 400 `invalid_user_ids` (api-contract.md №31): the add-members batch
 * `userIds` is absent, empty or not an array of unique UUIDs within the
 * 199 batch bound — the batch is refused as a whole (FR-002 atomicity).
 * The submitted values are never echoed (constitution V).
 *
 * Thrown by the №31 request validation (T016 `AddMembersRequest`),
 * rendered by T017 (`GroupsExceptionHandler`); pinned red-first by
 * GroupsExceptionHandlerTest (T015a).
 */
class InvalidUserIdsException : RuntimeException("userIds must be a non-empty array of unique UUIDs (at most 199)")

/**
 * 400 `invalid_uuid` (api-contract.md №28/№31): the PATH `chatId` of a
 * groups endpoint is not a UUID — the refusal a raw-string path
 * variable makes possible (the №11 `peerUserId` convention), decided in
 * the controller (T022) BEFORE the service and its uniform 404 gate are
 * touched. The submitted value is never echoed (constitution V).
 *
 * Thrown by [GroupController], rendered by T017
 * (`GroupsExceptionHandler`).
 */
class InvalidGroupChatIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("chatId must be a UUID", cause)

/**
 * 400 `empty_patch` (api-contract.md №29): a `PATCH /groups/{chatId}`
 * body carrying none of `title`/`description` — at least one field must
 * be present for the metadata update to apply (FR-007).
 *
 * Thrown by the №29 request validation (T016 `UpdateGroupRequest`),
 * rendered by T017 (`GroupsExceptionHandler`); pinned red-first by
 * GroupsExceptionHandlerTest (T015a).
 */
class EmptyPatchException : RuntimeException("PATCH body must carry at least one of title, description")
