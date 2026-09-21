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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import webchat.backend.chats.api.dto.MessagePageView
import webchat.backend.chats.api.dto.MessageView
import webchat.backend.chats.api.dto.SendMessageRequest
import webchat.backend.chats.domain.model.InvalidMessageTextException
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageTextViolation
import webchat.backend.chats.domain.service.HistoryService
import webchat.backend.chats.domain.service.MessageSendResult
import webchat.backend.chats.domain.service.MessageService
import java.util.UUID

/**
 * The message endpoints of User Story 1 (api-contract.md №15/№16) — a thin
 * HTTP adapter over [MessageService] (the exactly-once send with the
 * `201`/`200` dedup statuses of FR-004) and [HistoryService] (the cursor
 * page of FR-008): every business rule stays in the services; this layer
 * only resolves the token owner, parses the request into typed 400
 * carriers and projects the domain [Message] as the shared contract
 * `Message` schema (№16 answer, №15 pages — one shape, US6).
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as №11); the owner id is the token `sub` claim. All
 * failures leave as typed exceptions rendered problem+json by
 * [ChatsExceptionHandler].
 */
@RestController
@RequestMapping("/api/v1/chats/{chatId}/messages")
class MessageController(
    private val messageService: MessageService,
    private val historyService: HistoryService,
) {
    /**
     * Contract №16: the idempotent send — `201 Message` when a fresh
     * record is stored, `200 Message` when the `clientMessageId` is
     * already recorded (FR-004: the same row returned, never a
     * duplicate). The flood (`429`) and blocking-pair (`403`) gates join
     * this path in later stories (T032/T054) without changing the map.
     */
    @PostMapping
    fun send(
        @PathVariable chatId: UUID,
        @RequestBody request: SendMessageRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<MessageView> {
        val senderId = callerId(accessToken)
        val outcome =
            messageService.send(
                chatId = chatId,
                senderId = senderId,
                clientMessageId = parseClientMessageId(request.clientMessageId),
                rawText = request.text ?: throw InvalidMessageTextException(MessageTextViolation.BLANK),
            )
        return when (outcome) {
            is MessageSendResult.Created -> ResponseEntity.status(HttpStatus.CREATED).body(view(outcome.message))
            is MessageSendResult.Existing -> ResponseEntity.ok(view(outcome.message))
        }
    }

    /**
     * Contract №15: the history page — `messages` by `seq DESC` behind
     * the EXCLUSIVE `before` cursor, `nextBefore` absent at exhaustion.
     * The `limit` bounds (`400 limit_out_of_range`) are checked in
     * [HistoryService] against the contract-fixed `1..50`.
     */
    @GetMapping
    fun list(
        @PathVariable chatId: UUID,
        @RequestParam before: Long? = null,
        @RequestParam limit: Int? = null,
        @AuthenticationPrincipal accessToken: Jwt,
    ): MessagePageView {
        val viewerId = callerId(accessToken)
        val page = historyService.history(chatId, viewerId, before, limit)
        return MessagePageView(
            messages = page.messages.map(::view),
            nextBefore = page.nextBefore,
        )
    }

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * The №16 id gate: an absent or malformed `clientMessageId` becomes
     * the contract 400 `errors: {clientMessageId: [invalid_uuid]}`
     * BEFORE the service is touched — the id is the FR-004 deduplication
     * key, so it must be a well-formed UUID before any send work starts.
     */
    private fun parseClientMessageId(raw: String?): UUID {
        val value = raw ?: throw InvalidClientMessageIdException()
        return try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw InvalidClientMessageIdException(failure)
        }
    }

    /** The domain record projected as the contract `Message` schema — verbatim, no reshaping. */
    private fun view(message: Message): MessageView =
        MessageView(
            id = message.id,
            chatId = message.chatId,
            senderId = message.senderId,
            text = message.text.value,
            seq = message.seq,
            createdAt = message.createdAt,
        )
}

/**
 * 400 (api-contract.md №16): the request `clientMessageId` is absent or
 * not a UUID — rendered by [ChatsExceptionHandler] as
 * `errors: {clientMessageId: [invalid_uuid]}`.
 */
class InvalidClientMessageIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("clientMessageId must be a UUID", cause)
