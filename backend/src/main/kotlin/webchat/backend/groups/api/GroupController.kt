package webchat.backend.groups.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.groups.api.dto.AddMembersRequest
import webchat.backend.groups.api.dto.CreateGroupRequest
import webchat.backend.groups.api.dto.GroupMember
import webchat.backend.groups.api.dto.GroupMembersResponse
import webchat.backend.groups.api.dto.GroupView
import webchat.backend.groups.api.dto.SetMemberRoleRequest
import webchat.backend.groups.api.dto.TransferOwnershipRequest
import webchat.backend.groups.domain.service.GroupService
import java.util.UUID

/**
 * The US1 slice of the groups endpoints (api-contract.md 006 §2, tag
 * `groups`) — a thin HTTP adapter over [GroupService], the same split as
 * [webchat.backend.chats.api.ChatController]: every business rule (the
 * FR-002 contact sourcing, the №31 idempotent re-add, the capacity
 * `409 group_full`, the FR-008 membership gate `404 group_not_found`,
 * the FR-004 role refusals) stays in the service; this layer only
 * resolves the token owner, runs the request-shape validation of the
 * T016 DTOs (whose value objects carry the FR-001 bounds) and maps the
 * scenario results to the contract codes — №27 `201 GroupView`, №28
 * `200 GroupView`, №31 `200 {members: […]}`, №32 `204`, №34 `200
 * GroupMember`, №35 `200 GroupView`.
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as every 001 endpoint); the owner id is the token `sub`
 * claim. All failures leave as typed exceptions rendered problem+json
 * by [GroupsExceptionHandler]; the path `chatId` arrives as a RAW
 * string on purpose (the №11 `peerUserId` convention) so a malformed
 * value dies as the contract `400 errors: {chatId: [invalid_uuid]}`,
 * never the default error page — and the №32/№34/№35 single-target
 * `userId` (path variable or №35 body field) carries the SAME split
 * through `parseUserId`.
 *
 * The later stories grow THIS class in place: №29 with US4 (T052),
 * №33/№30 with US6 (T065).
 */
@RestController
@RequestMapping("/api/v1/groups")
class GroupController(
    private val groupService: GroupService,
) {
    /**
     * Contract №27 `POST /api/v1/groups`: the request-shape gates run
     * in the contract's own order — the FR-001 title/description bounds
     * through the T005 value objects, then the initial-roster shape
     * (unique UUIDs ≤ 199) — and the scenario answers `201` with the
     * caller's [GroupView] (the creator the single `owner`). The
     * caller-dependent refusals (`400 self_forbidden`, `422
     * not_in_contacts`, `409 group_full`) are decided inside the
     * service against the roster and the contacts of THIS creator.
     */
    @PostMapping
    fun create(
        @RequestBody request: CreateGroupRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<GroupView> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            groupService.create(
                callerId = callerId(accessToken),
                title = request.validatedTitle(),
                description = request.validatedDescription(),
                memberIds = request.validatedMemberUserIds(),
            ),
        )

    /**
     * Contract №28 `GET /api/v1/groups/{chatId}`: the gated read — a
     * proven ACTIVE member gets HIS [GroupView] (the `myRole` of his
     * own row), everything else (a stranger, a removed former member,
     * an unknown id and the chatId of a DIRECT dialog) is the ONE
     * uniform `404 group_not_found` of the service gate (FR-008/
     * FR-009 — existence is never disclosed).
     */
    @GetMapping("/{chatId}")
    fun get(
        @PathVariable chatId: String,
        @AuthenticationPrincipal accessToken: Jwt,
    ): GroupView = groupService.get(callerId(accessToken), parseChatId(chatId))

    /**
     * Contract №31 `POST /api/v1/groups/{chatId}/members`: the batch
     * shape gate (`400 invalid_user_ids` on an absent/empty/non-uuid/
     * duplicate/oversized batch) runs BEFORE the service, then the
     * scenario answers `200` with the CURRENT active roster envelope
     * `{members: […]}` — the idempotent re-add of an already-active
     * member included (api-contract.md 006 §2 «Идемпотентность»).
     */
    @PostMapping("/{chatId}/members")
    fun addMembers(
        @PathVariable chatId: String,
        @RequestBody request: AddMembersRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): GroupMembersResponse =
        GroupMembersResponse(
            members =
                groupService.addMembers(
                    callerId = callerId(accessToken),
                    chatId = parseChatId(chatId),
                    userIds = request.validatedUserIds(),
                ),
        )

    /**
     * Contract №32 `DELETE /api/v1/groups/{chatId}/members/{userId}`
     * (api-contract.md 006 №32, FR-004/FR-010, T044): the kick — a thin
     * adapter over [GroupService.kick]; the request-shape gate is the raw
     * `chatId`/`userId` pair (each a contract `400 … [invalid_uuid]`
     * BEFORE the service), and every caller-dependent rule (the `404`
     * gate, the `400 self_forbidden` self-kick refusal decided BEFORE the
     * hierarchy, the `403 forbidden_role`/`role_hierarchy_violation` role
     * ladder, the `409 target_not_member` convergence of the repeated
     * №32/«исключение×выход» race) stays in the service. Success is the
     * bodyless `204`; the `group.you_removed {reason:'kicked'}` final
     * frame to the kicked user and `group.member.removed` to the survivors
     * ride the post-commit leg of the service.
     */
    @DeleteMapping("/{chatId}/members/{userId}")
    fun kickMember(
        @PathVariable chatId: String,
        @PathVariable userId: String,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Void> {
        groupService.kick(
            callerId = callerId(accessToken),
            chatId = parseChatId(chatId),
            targetUserId = parseUserId(userId),
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * Contract №34 `PUT /api/v1/groups/{chatId}/members/{userId}/role`
     * (api-contract.md 006 №34, FR-003, T044): the admin grant/revoke —
     * the request-shape gates run in the contract's own order (the raw
     * path pair first, then the T016 `SetMemberRoleRequest` refusing
     * everything but `admin`|`member` as `400 invalid_role` — `owner`
     * moves only through №35), and the scenario answers `200` with the
     * UPDATED [GroupMember]. The caller-dependent refusals (`404` gate,
     * `400 self_forbidden` for the owner's own row, `403
     * not_group_owner`, `409 target_not_member`) and the
     * `group.role.changed` post-commit broadcast stay in the service.
     */
    @PutMapping("/{chatId}/members/{userId}/role")
    fun setMemberRole(
        @PathVariable chatId: String,
        @PathVariable userId: String,
        @RequestBody request: SetMemberRoleRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): GroupMember =
        groupService.setRole(
            callerId = callerId(accessToken),
            chatId = parseChatId(chatId),
            targetUserId = parseUserId(userId),
            role = request.validatedRole(),
        )

    /**
     * Contract №35 `POST /api/v1/groups/{chatId}/owner`
     * (api-contract.md 006 №35, FR-003, T044): the ownership transfer —
     * the request-shape gate is the T016 `TransferOwnershipRequest` raw
     * `userId` body field (a malformed value is the contract `400
     * errors: {userId: [invalid_uuid]}` decided HERE, the №11
     * `peerUserId` convention), and the scenario answers `200` with the
     * caller's [GroupView] — HIS `myRole` is now `admin`, the roster
     * carries the single new owner. The `404` gate, the `400
     * self_forbidden` transfer-to-self edge, `403 not_group_owner`, the
     * `409 target_not_member` target gate and the `group.role.changed`
     * frame PAIR of the demote-then-promote transaction stay in the
     * service.
     */
    @PostMapping("/{chatId}/owner")
    fun transferOwnership(
        @PathVariable chatId: String,
        @RequestBody request: TransferOwnershipRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): GroupView =
        groupService.transferOwnership(
            callerId = callerId(accessToken),
            chatId = parseChatId(chatId),
            targetUserId = parseUserId(request.userId),
        )

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * The №28/№31 path gate (api-contract.md 006 §2): a malformed
     * `chatId` becomes the contract 400 `errors: {chatId:
     * [invalid_uuid]}` BEFORE the service is touched — a WELL-FORMED
     * but foreign/unknown chatId rides along and is refused by the
     * service's own uniform 404, so the two refusals never blur.
     */
    private fun parseChatId(raw: String?): UUID {
        val value = raw ?: throw InvalidGroupChatIdException()
        return try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw InvalidGroupChatIdException(failure)
        }
    }

    /**
     * The №32/№34/№35 single-target gate (api-contract.md 006 §2): a
     * malformed `userId` — the PATH variable of №32/№34, the body
     * field of №35 — becomes the contract `400 errors: {userId:
     * [invalid_uuid]}` BEFORE the service is touched, exactly the two
     * refusal kinds the №28/№31 `chatId` gate above keeps apart: a
     * WELL-FORMED but foreign/removed target rides along and is
     * refused by the service's own `409 target_not_member` (or the
     * uniform 404 of the group gate), so the shape refusal and the
     * roster refusal never blur.
     */
    private fun parseUserId(raw: String?): UUID {
        val value = raw ?: throw InvalidGroupUserIdException()
        return try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw InvalidGroupUserIdException(failure)
        }
    }
}
