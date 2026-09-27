package webchat.backend.groups.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.groups.api.dto.AddMembersRequest
import webchat.backend.groups.api.dto.CreateGroupRequest
import webchat.backend.groups.api.dto.GroupMembersResponse
import webchat.backend.groups.api.dto.GroupView
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
 * `200 GroupView`, №31 `200 {members: […]}`.
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as every 001 endpoint); the owner id is the token `sub`
 * claim. All failures leave as typed exceptions rendered problem+json
 * by [GroupsExceptionHandler]; the path `chatId` arrives as a RAW
 * string on purpose (the №11 `peerUserId` convention) so a malformed
 * value dies as the contract `400 errors: {chatId: [invalid_uuid]}`,
 * never the default error page.
 *
 * The later stories grow THIS class in place: №32/№34/№35 with US3
 * (T044), №29 with US4 (T052), №33/№30 with US6 (T065).
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
}
