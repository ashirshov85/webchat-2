package webchat.backend.chats.api

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.chats.TypingMetrics
import webchat.backend.chats.domain.service.TypingService
import webchat.backend.config.ChatsProperties
import webchat.backend.config.UserRateLimiter
import java.util.UUID

/**
 * Contract №41 (T031, api-contract.md 008a §1): `POST
 * /api/v1/chats/{chatId}/typing` — the ephemeral typing signal HTTP
 * adapter, a thin leg exactly like the №38 presence routes: the SHAPE
 * gates first (the raw [chatId] path segment becomes the contract 400
 * `errors: {chatId: [invalid_uuid]}` and an absent/non-`start`/`stop`
 * [TypingRequest.action] the `errors: {action: [invalid_action]}` —
 * both BEFORE any bucket or store leg, so a malformed signal burns
 * nothing), the per-user flood bucket
 * `rl:user:typing:{userId}` (60/min,
 * [ChatsProperties.RateLimit.typingSignalsPerMinute]) NEXT and the
 * [TypingService] state machine LAST — the membership gate of the №16
 * refusal order (`404 chat_not_found` → `403 not_participant`) and the
 * DIRECT block-pair suppression live THERE, never here.
 *
 * Every accepted signal answers the bare `204` (openapi №41): `start`
 * re-arms/renews the state, `stop` is the idempotent extinguish — the
 * publication side effects (frames to everyone except the sender) are
 * decided by the service and its [TypingMetrics]. A REFUSED flood
 * signal performs NO store leg and publishes NOTHING (api-contract.md:
 * «избыточные сигналы не публикуются» — the observers never see the
 * storm); the refusal itself is counted by
 * [TypingMetrics.countFloodSuppressed] (FR-009, never silent) and the
 * client repeats after the advertised `Retry-After` wait — the state of
 * the moment simply lapses by its TTL if the wait outlives it.
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as every /chats route); the caller id is the token `sub`
 * claim. Failures leave as typed exceptions rendered problem+json by
 * [ChatsExceptionHandler]; the warn log carries ids and waits only —
 * never the payload (constitution V).
 */
@RestController
@RequestMapping("/api/v1/chats/{chatId}/typing")
class TypingController(
    private val typingService: TypingService,
    private val rateLimiter: UserRateLimiter,
    private val chatsProperties: ChatsProperties,
    private val typingMetrics: TypingMetrics,
) {
    private val log = LoggerFactory.getLogger(TypingController::class.java)

    /**
     * №41 → `204 No Content`: the signal is accepted (idempotently — a
     * repeat `start` renews the state, a repeat `stop` publishes
     * nothing) once the shape gates pass, a flood token is consumed and
     * the service gate (membership → block-pair suppression) admits it.
     */
    @PostMapping
    fun sendTypingSignal(
        @PathVariable chatId: String,
        @RequestBody(required = false) request: TypingRequest?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Unit> {
        val parsedChatId = parseChatId(chatId)
        val action = parseAction(request?.action)
        val callerId = UUID.fromString(accessToken.subject)
        enforceTypingFloodLimit(callerId)
        when (action) {
            TypingAction.START -> typingService.start(parsedChatId, callerId)
            TypingAction.STOP -> typingService.stop(parsedChatId, callerId)
        }
        return ResponseEntity.noContent().build()
    }

    /**
     * The №41 path gate: a non-UUID `chatId` segment is the contract 400
     * `errors: {chatId: [invalid_uuid]}` — bound as a RAW string on
     * purpose, so the refusal stays the typed problem of
     * [ChatsExceptionHandler] instead of a framework conversion error
     * page (the [InvalidClientMessageIdException] discipline).
     */
    private fun parseChatId(raw: String): UUID =
        try {
            UUID.fromString(raw)
        } catch (failure: IllegalArgumentException) {
            throw InvalidChatIdException(failure)
        }

    /**
     * The №41 body gate: the `action` must be the JSON string `start`
     * or `stop` (openapi `TypingRequest`) — absent, non-string or any
     * other value is the ONE contract refusal `400 invalid_action`.
     */
    private fun parseAction(raw: Any?): TypingAction =
        when (raw) {
            ACTION_START -> TypingAction.START
            ACTION_STOP -> TypingAction.STOP
            else -> throw InvalidTypingActionException()
        }

    /**
     * api-contract.md §1 №41 (FR-009): one token per signal of the
     * per-user bucket `rl:user:typing:{userId}` (capacity
     * [ChatsProperties.RateLimit.typingSignalsPerMinute] over the 60 s
     * window — every device and every chat of the user draws from ONE
     * bucket): a refused signal performs NO store leg and publishes
     * NOTHING, the suppression is counted
     * ([TypingMetrics.countFloodSuppressed]) and the client repeats
     * after the advertised `Retry-After` wait (the honest client's ≤ 3 s
     * repeat window never drains the 60/min allowance, research.md A5).
     */
    private fun enforceTypingFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = TYPING_KEY_FAMILY,
                userId = callerId,
                permitsPerMinute = chatsProperties.rateLimit.typingSignalsPerMinute.toLong(),
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            typingMetrics.countFloodSuppressed()
            log.warn(
                "№41 typing signal refused by the flood limit (api-contract.md §1): user <{}> exhausted " +
                    "{} signals/minute, retry after {}s",
                callerId,
                chatsProperties.rateLimit.typingSignalsPerMinute,
                verdict.retryAfterSeconds,
            )
            throw TypingFloodException(verdict.retryAfterSeconds)
        }
    }

    /** The two wire values of the openapi `TypingRequest.action` enum. */
    private enum class TypingAction {
        START,
        STOP,
    }

    private companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        /** research.md 008a A5: the Redis key family of the №41 bucket. */
        const val TYPING_KEY_FAMILY = "rl:user:typing:"
    }
}

/**
 * The №41 request body (openapi `TypingRequest`): bound leniently as a
 * plain JSON value — the shape gate is the controller's `400
 * invalid_action` (errors.action), not a framework parse failure (the
 * [ProfileUpdateRequest][webchat.backend.users.api.ProfileUpdateRequest]
 * convention).
 */
data class TypingRequest(
    val action: Any? = null,
)

/**
 * 400 (api-contract.md №41): the path `chatId` is not a UUID — rendered
 * by [ChatsExceptionHandler] as `errors: {chatId: [invalid_uuid]}`.
 */
class InvalidChatIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("chatId must be a UUID", cause)

/**
 * 400 (api-contract.md №41): `action` is absent or outside
 * `start|stop` — rendered by [ChatsExceptionHandler] as
 * `errors: {action: [invalid_action]}`.
 */
class InvalidTypingActionException : RuntimeException("action must be one of: start, stop")

/**
 * 429 (api-contract.md №41): the per-user typing flood bucket is
 * exhausted — [retryAfterSeconds] is the integral ceiling of the wait
 * for the next available token, rendered as the `Retry-After` header;
 * the refused signal published nothing (FR-009).
 */
class TypingFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user typing flood limit is exhausted")
