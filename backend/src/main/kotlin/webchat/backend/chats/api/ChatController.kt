package webchat.backend.chats.api

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
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.api.dto.ChatPeerView
import webchat.backend.chats.api.dto.ChatView
import webchat.backend.chats.api.dto.EnsureChatRequest
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.service.ChatService
import java.util.UUID

/**
 * User Story 1 dialog endpoints (api-contract.md №11/№13) — a thin HTTP
 * adapter over [ChatService]: every business rule (the `422 self_forbidden`
 * pair guard, `404 peer_not_found`, the membership gate `404 → 403` of
 * FR-002) stays in the service; this layer only resolves the token owner,
 * maps [ChatEnsureResult] to `201 ChatView` / `200 ChatView` and projects
 * the peer as the reused `PublicUser` schema.
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as contract №10); the owner id is the token `sub` claim.
 * All failures leave as typed exceptions rendered problem+json by
 * [ChatsExceptionHandler].
 */
@RestController
@RequestMapping("/api/v1/chats")
class ChatController(
    private val chatService: ChatService,
    private val userRepository: UserRepository,
) {
    /**
     * Contract №11: the idempotent pair resolve — `201` when the dialog is
     * created, `200` for the existing one (the caller's `hidden` flag has
     * been cleared by the repository transaction, FR-018/FR-021).
     */
    @PostMapping("/ensure")
    fun ensure(
        @RequestBody request: EnsureChatRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<ChatView> {
        val callerId = callerId(accessToken)
        val result = chatService.ensure(callerId, parsePeerId(request.peerUserId))
        return when (result) {
            is ChatEnsureResult.Created -> ResponseEntity.status(HttpStatus.CREATED).body(view(result.chat, callerId))
            is ChatEnsureResult.Existing -> ResponseEntity.ok(view(result.chat, callerId))
        }
    }

    /** Contract №13: the membership-gated dialog read (FR-002). */
    @GetMapping("/{chatId}")
    fun getChat(
        @PathVariable chatId: UUID,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ChatView {
        val callerId = callerId(accessToken)
        return view(chatService.get(chatId, callerId), callerId)
    }

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * The peer id gate of №11: absent or malformed values become the
     * contract 400 `errors: {peerUserId: [invalid_uuid]}` before the
     * service is touched.
     */
    private fun parsePeerId(raw: String?): UUID {
        val value = raw ?: throw InvalidPeerUserIdException()
        return try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw InvalidPeerUserIdException(failure)
        }
    }

    /**
     * The dialog projected for its participant: the peer side resolved via
     * [UserRepository] and the US4 read watermarks via
     * [ChatService.readWatermarks] (T043 — `myReadUpToSeq` for the caller,
     * `peerReadUpToSeq` for the ✓✓ of the sender). The peer row always
     * exists (the FK pair of V10 and the №11 pre-check guarantee it) — a
     * miss is a broken invariant, not a client answer.
     */
    private fun view(
        chat: Chat,
        callerId: UUID,
    ): ChatView {
        val peerId =
            chat.peerOf(callerId)
                ?: error("chat ${chat.id} does not involve the authenticated caller")
        val peer =
            userRepository.findById(peerId)
                ?: error("chat ${chat.id} peer $peerId does not resolve")
        val watermarks = chatService.readWatermarks(chat, callerId)
        return ChatView(
            chatId = chat.id,
            peer =
                ChatPeerView(
                    id = peer.id,
                    username = peer.username,
                    email = peer.email,
                    status = peer.status.name.lowercase(),
                    createdAt = peer.createdAt,
                ),
            peerReadUpToSeq = watermarks.peerReadUpToSeq,
            myReadUpToSeq = watermarks.myReadUpToSeq,
        )
    }
}

/**
 * 400 (api-contract.md №11): the request `peerUserId` is absent or not a
 * UUID — rendered by [ChatsExceptionHandler] as
 * `errors: {peerUserId: [invalid_uuid]}`.
 */
class InvalidPeerUserIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("peerUserId must be a UUID", cause)
