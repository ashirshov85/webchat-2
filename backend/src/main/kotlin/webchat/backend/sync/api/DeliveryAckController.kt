package webchat.backend.sync.api

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.chats.domain.service.InvalidUpToSeqException
import webchat.backend.sync.api.dto.DeliveryAckItem
import webchat.backend.sync.api.dto.DeliveryAckRequest
import webchat.backend.sync.domain.service.DeliveryAck
import webchat.backend.sync.domain.service.DeliveryAckService
import java.util.UUID

/**
 * The delivery-acknowledgement endpoint of User Story 1 (T014;
 * api-contract.md №25 `POST /users/me/delivery-ack`, FR-001) — a thin
 * HTTP adapter over [DeliveryAckService]: every business rule (the
 * 1..100 batch bound, the membership precheck of every item, the seq
 * bound, the one-transaction GREATEST advance) stays in the service;
 * this layer only resolves the token owner and parses the raw batch
 * into typed [DeliveryAck] commands, so the domain never sees a
 * malformed `chatId` (the contract 400 `errors: {chatId:
 * [invalid_uuid]}` of [InvalidChatIdException]) or an absent `upToSeq`
 * (the reused [InvalidUpToSeqException], as №17 in 004).
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as contract №11 — `401` answers uniformly before any
 * controller code runs); the owner id is the token `sub` claim. All
 * failures leave as typed exceptions rendered problem+json by
 * [SyncExceptionHandler] and the global
 * [webchat.backend.chats.api.ChatsExceptionHandler].
 */
@RestController
@RequestMapping("/api/v1/users/me")
class DeliveryAckController(
    private val deliveryAckService: DeliveryAckService,
) {
    /**
     * Contract №25: `204` in every non-refused case — an actual
     * advance, an idempotent repeat and a smaller `upToSeq` (the
     * GREATEST no-op) all succeed silently with an empty body; the
     * whole batch lands through the service in one transaction, so a
     * refusal (400/403/404) never leaves partial effects. A missing
     * `acks` array folds to an empty batch and is refused by the
     * service's 1..100 bound exactly like an oversized one.
     */
    @PostMapping("/delivery-ack")
    fun acknowledge(
        @RequestBody request: DeliveryAckRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Unit> {
        deliveryAckService.acknowledge(callerId(accessToken), request.acks.orEmpty().map(::ackOf))
        return ResponseEntity.noContent().build()
    }

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * One raw item parsed into the typed №25 command: the `chatId` gate
     * rejects an absent or malformed value as the contract 400 before
     * the service is touched, an absent `upToSeq` is the reused
     * `400 invalid_up_to_seq` (the №17 precedent — the value is judged
     * against the chat head only inside the service, after the
     * membership precheck covers the whole batch).
     */
    private fun ackOf(item: DeliveryAckItem): DeliveryAck =
        DeliveryAck(
            chatId = parseChatId(item.chatId),
            upToSeq = item.upToSeq ?: throw InvalidUpToSeqException(),
        )
}

/** The №25/№26 `chatId` gate — shared by [DeliveryAckController] and [SyncController]. */
internal fun parseChatId(raw: String?): UUID {
    val value = raw ?: throw InvalidChatIdException()
    return try {
        UUID.fromString(value)
    } catch (failure: IllegalArgumentException) {
        throw InvalidChatIdException(failure)
    }
}
