package webchat.backend.chats.api

import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.chats.ChatSoundMetrics
import webchat.backend.chats.SoundUpdateResult
import webchat.backend.chats.domain.service.ChatService
import webchat.backend.config.ChatsProperties
import webchat.backend.config.UserRateLimiter
import java.util.UUID

/**
 * Contract №42 (T053, api-contract.md 008a §1): `PUT
 * /api/v1/chats/{chatId}/sound` — the per-chat sound toggle HTTP
 * adapter, a thin leg exactly like the №38 presence settings route: the
 * SHAPE gates first (the raw [chatId] path segment becomes the contract
 * 400 `errors: {chatId: [invalid_uuid]}` and an absent/null/non-boolean
 * [ChatSoundRequest.enabled] the `errors: {enabled:
 * [malformed_request]}` — both BEFORE any bucket or store leg, so a
 * malformed toggle burns nothing), the per-user flood bucket
 * `rl:user:chat-sound:{userId}` (30/min, the №38 parity,
 * [ChatsProperties.RateLimit.soundWritesPerMinute]) NEXT and the
 * [ChatService.updateSound] verdict LAST — the №13/№16 membership gate
 * (`404 chat_not_found` → `403 not_participant`) and the idempotency
 * rule (a repeat of the stored value — `200` WITHOUT an event) live
 * THERE, never here.
 *
 * Every admitted toggle answers `200 ChatSoundResponse` carrying the
 * STORED value (openapi №42): a genuine change additionally lands the
 * `chat.sound.updated` frame on the toggler's OWN №18 channel (the
 * multi-device sync of SC-006 — the service's addressing decision), a
 * repeated value is the counted no-op of [ChatSoundMetrics]. A REFUSED
 * flood toggle performs NO store leg and publishes NOTHING
 * (api-contract.md №42); the refusal itself is counted by
 * [ChatSoundMetrics.countUpdate] as the `rejected` verdict (FR-017,
 * never silent) and the client repeats after the advertised
 * `Retry-After` wait.
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as every /chats route); the caller id is the token `sub`
 * claim. Failures leave as typed exceptions rendered problem+json by
 * [ChatsExceptionHandler]; the warn log carries ids and waits only —
 * never the payload (constitution V).
 */
@RestController
@RequestMapping("/api/v1/chats/{chatId}/sound")
class ChatSoundController(
    private val chatService: ChatService,
    private val rateLimiter: UserRateLimiter,
    private val chatsProperties: ChatsProperties,
    private val chatSoundMetrics: ChatSoundMetrics,
) {
    private val log = LoggerFactory.getLogger(ChatSoundController::class.java)

    /**
     * №42 → `200 ChatSoundResponse {soundEnabled}`: the stored value of
     * the caller's own switch — the idempotent echo for a repeat, the
     * flipped value plus the own-channel `chat.sound.updated` frame for
     * a genuine change (the service's verdict, never this layer's).
     */
    @PutMapping
    fun setChatSound(
        @PathVariable chatId: String,
        @RequestBody(required = false) request: ChatSoundRequest?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ChatSoundResponse {
        val parsedChatId = parseChatId(chatId)
        val enabled = requireEnabled(request)
        val callerId = UUID.fromString(accessToken.subject)
        enforceSoundFloodLimit(callerId)
        return ChatSoundResponse(soundEnabled = chatService.updateSound(parsedChatId, callerId, enabled))
    }

    /**
     * The №42 path gate: a non-UUID `chatId` segment is the contract 400
     * `errors: {chatId: [invalid_uuid]}` — bound as a RAW string on
     * purpose (the [TypingController.parseChatId] discipline), so the
     * refusal stays the typed problem of [ChatsExceptionHandler]
     * instead of a framework conversion error page.
     */
    private fun parseChatId(raw: String): UUID =
        try {
            UUID.fromString(raw)
        } catch (failure: IllegalArgumentException) {
            throw InvalidChatIdException(failure)
        }

    /**
     * The №42 body gate (openapi `ChatSoundRequest`): the `enabled`
     * field must be present and a JSON boolean — an absent body, a
     * missing/null field or any non-boolean value is the ONE contract
     * refusal `400 errors: {enabled: [malformed_request]}` (the №38
     * `requireIncognito` precedent).
     */
    private fun requireEnabled(request: ChatSoundRequest?): Boolean {
        val value = request?.enabled
        if (value !is Boolean) throw ChatSoundMalformedException("enabled is required and must be a boolean")
        return value
    }

    /**
     * api-contract.md §1 №42 (research.md D2): one token per toggle of
     * the per-user bucket `rl:user:chat-sound:{userId}` (capacity
     * [ChatsProperties.RateLimit.soundWritesPerMinute] over the 60 s
     * window — the conservative 30/min №38 parity for a manual button;
     * every device and every chat of the user draws from ONE bucket): a
     * refused toggle performs NO store leg and publishes NOTHING, the
     * refusal is counted as the `rejected` verdict
     * ([ChatSoundMetrics.countUpdate]) and the client repeats after the
     * advertised `Retry-After` wait.
     */
    private fun enforceSoundFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = SOUND_KEY_FAMILY,
                userId = callerId,
                permitsPerMinute = chatsProperties.rateLimit.soundWritesPerMinute.toLong(),
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            chatSoundMetrics.countUpdate(SoundUpdateResult.REJECTED)
            log.warn(
                "№42 sound toggle refused by the flood limit (api-contract.md §1): user <{}> exhausted " +
                    "{} writes/minute, retry after {}s",
                callerId,
                chatsProperties.rateLimit.soundWritesPerMinute,
                verdict.retryAfterSeconds,
            )
            throw ChatSoundFloodException(verdict.retryAfterSeconds)
        }
    }

    private companion object {
        /** research.md 008a D2: the Redis key family of the №42 bucket. */
        const val SOUND_KEY_FAMILY = "rl:user:chat-sound:"
    }
}

/**
 * The №42 request body (openapi `ChatSoundRequest`): bound leniently as
 * a plain JSON value — the boolean shape gate is the controller's `400
 * malformed_request` (errors.enabled), not a framework parse failure
 * (the [ProfileUpdateRequest][webchat.backend.users.api.ProfileUpdateRequest]
 * convention).
 */
data class ChatSoundRequest(
    val enabled: Any? = null,
)

/**
 * The №42 success body (openapi `ChatSoundResponse`): exactly the
 * STORED `soundEnabled` — a repeat echoes the stored value, a change
 * the flipped one.
 */
data class ChatSoundResponse(
    val soundEnabled: Boolean,
)

/**
 * 400 (api-contract.md №42): the body is absent, `enabled` is not set
 * or is not a boolean — rendered by [ChatsExceptionHandler] as
 * `errors: {enabled: [malformed_request]}`.
 */
class ChatSoundMalformedException(
    detail: String,
) : RuntimeException(detail)

/**
 * 429 (api-contract.md №42): the per-user sound-toggle flood bucket is
 * exhausted — [retryAfterSeconds] is the integral ceiling of the wait
 * for the next available token, rendered as the `Retry-After` header;
 * the refused toggle wrote nothing and published nothing (FR-012).
 */
class ChatSoundFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user chat-sound flood limit is exhausted")
