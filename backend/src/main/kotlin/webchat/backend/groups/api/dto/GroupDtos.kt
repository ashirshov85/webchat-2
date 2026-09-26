package webchat.backend.groups.api.dto

import webchat.backend.groups.api.EmptyPatchException
import webchat.backend.groups.api.InvalidMemberIdsException
import webchat.backend.groups.api.InvalidUserIdsException
import webchat.backend.groups.domain.model.GroupDescription
import webchat.backend.groups.domain.model.GroupTitle
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.service.InvalidRoleException
import java.time.Instant
import java.util.UUID

/**
 * Contract batch bound shared by the №27 `memberUserIds` and №31
 * `userIds` arrays (openapi.yaml 0.6.0 `maxItems: 199`): the 200-member
 * group cap minus the actor himself (api-contract.md header, FR-002).
 * An over-bound batch is a request-shape 400 (`invalid_member_ids` /
 * `invalid_user_ids`), unlike the runtime capacity check (`409
 * group_full` against `groups.max-members`) owned by GroupService.
 */
private const val MAX_MEMBER_BATCH = 199

/** Contract №34 role labels — `owner` is not assignable by №34 (№35 owns it). */
private const val ROLE_ADMIN = "admin"

private const val ROLE_MEMBER = "member"

/** The lowercase contract label of a [MemberRole] (schema enums owner|admin|member). */
fun memberRoleLabel(role: MemberRole): String = role.name.lowercase()

/**
 * Contract №27 request body — `CreateGroupRequest` (openapi.yaml 0.6.0):
 * `{title, description?, memberUserIds?}`; the initial roster is sourced
 * from the creator's contacts and the whole request is atomic.
 *
 * All values arrive raw on purpose (the [EnsureChatRequest] convention):
 * a malformed field is rejected as its typed contract problem instead of
 * dying in deserialization — the reply stays a typed problem, never the
 * default error page. Validation runs through the T005 value objects
 * ([GroupTitle]/[GroupDescription], whose defaults carry the contract
 * bounds 64/256) so a stored row always re-validates; the contact
 * sourcing (`422 not_in_contacts`), self-targeting (`400 self_forbidden`)
 * and capacity (`409 group_full`) rules stay with GroupService (T021) —
 * they need the caller.
 */
data class CreateGroupRequest(
    val title: String? = null,
    val description: String? = null,
    val memberUserIds: List<String>? = null,
) {
    /**
     * FR-001 title check via the T005 value object: an ABSENT title is
     * refused as blank — the №27 `400 invalid_title` carrier.
     */
    fun validatedTitle(): GroupTitle = GroupTitle.normalize(title.orEmpty())

    /** FR-001 optional description via the T005 value object: an absent field stays `null` (no description). */
    fun validatedDescription(): GroupDescription? = description?.let { GroupDescription.normalize(it) }

    /**
     * №27 shape check of the initial roster: an absent list is an empty
     * roster (a creator-only group is valid — the schema has no
     * `minItems`); a non-uuid element, a duplicate or a batch over 199 is
     * the request-shape `400 invalid_member_ids`.
     */
    fun validatedMemberUserIds(): List<UUID> = parseMemberBatch(memberUserIds, ::InvalidMemberIdsException)
}

/**
 * Contract №29 request body — `UpdateGroupRequest` (openapi.yaml 0.6.0):
 * `{title?, description?}`; at least one field must be present (else
 * `400 empty_patch`) and the present ones validate exactly as №27
 * (FR-007 — the patch applies atomically as a whole).
 */
data class UpdateGroupRequest(
    val title: String? = null,
    val description: String? = null,
) {
    /** №29 `400 empty_patch` unless at least one field arrived. */
    fun requirePatch() {
        if (title == null && description == null) throw EmptyPatchException()
    }

    /** FR-001 title check of the patch via the T005 value object (as №27). */
    fun validatedTitle(): GroupTitle = GroupTitle.normalize(title.orEmpty())

    /** FR-001 optional description check of the patch via the T005 value object (as №27). */
    fun validatedDescription(): GroupDescription? = description?.let { GroupDescription.normalize(it) }
}

/**
 * Contract №31 request body — `AddMembersRequest` (openapi.yaml 0.6.0):
 * `{userIds}` — the batch is REQUIRED and non-empty (`1..199`, unique
 * UUIDs); it is refused as a whole on any shape violation (FR-002
 * atomicity). The contact sourcing, self-targeting, idempotent re-add
 * and capacity rules stay with GroupService (T021) — they need the
 * caller and the roster.
 */
data class AddMembersRequest(
    val userIds: List<String>? = null,
) {
    /** №31 shape check: `400 invalid_user_ids` on absent/empty/non-uuid/duplicate/oversized batch. */
    fun validatedUserIds(): List<UUID> {
        if (userIds.isNullOrEmpty()) throw InvalidUserIdsException()
        return parseMemberBatch(userIds, ::InvalidUserIdsException)
    }
}

/**
 * Contract №34 request body — `SetMemberRoleRequest` (openapi.yaml
 * 0.6.0): `{role: 'admin'|'member'}` — the grant/revoke of the admin
 * flag (owner-only). `owner` is refused here exactly like any unknown
 * label: ownership moves only via the №35 transfer.
 */
data class SetMemberRoleRequest(
    val role: String? = null,
) {
    /** №34 `400 invalid_role` on anything but the two labels; the submitted value is never echoed. */
    fun validatedRole(): MemberRole =
        when (role) {
            ROLE_ADMIN -> MemberRole.ADMIN
            ROLE_MEMBER -> MemberRole.MEMBER
            else -> throw InvalidRoleException()
        }
}

/**
 * Contract №35 request body — `TransferOwnershipRequest` (openapi.yaml
 * 0.6.0): `{userId}`.
 *
 * The id arrives as a raw string on purpose (the [EnsureChatRequest]
 * convention): an absent or malformed value is rejected by the CONTROLLER
 * layer (T044) as the contract `400 invalid_uuid` — the same split as
 * №11 `peerUserId`. The `400 self_forbidden` / `409 target_not_member`
 * rules are caller-dependent and stay with GroupService (T043).
 */
data class TransferOwnershipRequest(
    val userId: String? = null,
)

/**
 * Contract №31 answer envelope (openapi.yaml 0.6.0): `{members: […]}` —
 * the CURRENT active roster after the operation (the idempotent re-add
 * included), the exact shape `additionalProperties: false,
 * required: [members]` pins. Owned by the api layer (T022) — the
 * service answers the bare roster, the HTTP adapter wraps it.
 */
data class GroupMembersResponse(
    val members: List<GroupMember>,
)

/**
 * The reused `PublicUser {id, username, email, status, createdAt}`
 * fragment of [GroupMember] (openapi.yaml 0.6.0): the groups-local
 * projection of the contract schema, in the [ChatPeerView] convention —
 * `status` carries the lowercase `user_status` label, no password
 * material leaves the service (SC-005).
 */
data class PublicUserView(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
)

/**
 * Contract `GroupMember` schema (openapi.yaml 0.6.0): a member of the
 * active roster with his role — the element of [GroupView.members] and
 * of the №31/№34 answers. [role] carries the lowercase contract label
 * (see [memberRoleLabel]); [joinedAt] is the FIRST-add moment —
 * re-adding a removed member keeps it (FR-002).
 */
data class GroupMember(
    val user: PublicUserView,
    val role: String,
    val joinedAt: Instant,
)

/**
 * Contract `GroupView` schema (openapi.yaml 0.6.0) — the №27/№28/№29/№35
 * success body: the group metadata plus the ACTIVE roster with roles
 * (≤200, only `state='active'` rows — the gate already filtered the
 * rest). [chatId] is the id of the `chats` row — the single key of
 * №12–№17/№26 and the `groupId` of the realtime events; [description] is
 * rendered as an explicit `null` when absent (the schema type is
 * `string|null` and the field is required); [myRole] is the caller's
 * role label.
 */
data class GroupView(
    val chatId: UUID,
    val title: String,
    val description: String?,
    val myRole: String,
    val members: List<GroupMember>,
)

/**
 * The shared №27/№31 batch shape check: `null` is the valid absence of
 * an optional №27 roster (an empty batch), while every element must
 * parse as a UUID, no duplicates and at most [MAX_MEMBER_BATCH] elements
 * — one refusal code per operation, constructed by [refusal] (the
 * submitted values are never echoed, constitution V). The №31
 * required/non-empty rule is decided by the caller BEFORE this helper.
 */
private fun parseMemberBatch(
    raw: List<String>?,
    refusal: () -> RuntimeException,
): List<UUID> {
    if (raw == null) return emptyList()
    val parsed = raw.map { element -> element.toUuidOrNull() }
    val shapeValid =
        raw.size <= MAX_MEMBER_BATCH &&
            parsed.none { it == null } &&
            parsed.distinct().size == raw.size
    if (!shapeValid) throw refusal()
    return parsed.filterNotNull()
}

private fun String.toUuidOrNull(): UUID? =
    try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
