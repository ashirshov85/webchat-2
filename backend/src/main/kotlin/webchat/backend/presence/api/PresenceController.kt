package webchat.backend.presence.api

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.config.PresenceProperties
import webchat.backend.config.UserRateLimiter
import webchat.backend.presence.domain.PresenceService
import java.util.UUID

/**
 * The №37 HTTP surface of 007 (contracts/presence-api.md §2):
 * `POST /api/v1/users/me/presence/heartbeat` — the APPLICATION-level
 * presence heartbeat that atomically (one Lua leg of
 * [webchat.backend.presence.repository.RedisPresenceStore]) extends the
 * alive AND watch horizons of the registration to `now + 90 s`. It is
 * the counterpart of the №18 opening frame `connected {connectionId}`
 * (T013): the client beats with the connectionId it received, keeping
 * the registration proof-of-life across silence of the SSE stream.
 *
 * This is a thin HTTP adapter (the state machine legs live in
 * [PresenceService], T014): it validates the body shape, enforces the
 * per-user flood bucket and projects the two contract outcomes —
 * `204` (renewed; the published status and rev stay EXACTLY as they
 * were: a kept-alive registration must not advance the rev) and `404
 * presence_connection_not_found` (the connectionId is unknown, expired
 * or someone else's — the client's cue to reconnect the №18 stream
 * immediately; a revival is the register leg of the reconnect, never
 * the beat, presence-api.md §2).
 *
 * The flood gate (per-user `rl:user:presence-heartbeat:{userId}`,
 * ~10 requests/30 s → [PresenceProperties.RateLimit.heartbeatsPerMinute]):
 * checked AFTER the shape validation and BEFORE the renewal — a refused
 * beat performs NO store round trip, exactly like the №19 precedent.
 * The `429 flood_limit` + `Retry-After` never tears the №18 stream: the
 * transport `:ka` keepalive of the channel is orthogonal to №37 (the
 * terminology split of presence-api.md §2), and the 90 s TTL gives the
 * recommended 30 s cadence a 3× headroom — the client simply retries in
 * the next interval. The bucket counts BEATS, not outcomes: every
 * device and replica of the user draws from one bucket (constitution
 * II). Redis unavailability fails open ([UserRateLimiter]).
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as every /users/me route); the caller id is the token
 * `sub` claim. Failures leave as typed exceptions rendered problem+json
 * by [PresenceExceptionHandler]; the warn log carries ids and waits
 * only — never a payload (constitution V).
 */
@RestController
@RequestMapping("/api/v1/users/me/presence")
class PresenceController(
    private val presenceService: PresenceService,
    private val rateLimiter: UserRateLimiter,
    private val presenceProperties: PresenceProperties,
) {
    private val log = LoggerFactory.getLogger(PresenceController::class.java)

    /**
     * №37: `{connectionId}` → `204`. A missing body, a missing/null
     * [PresenceHeartbeatRequest.connectionId] or a non-UUID value is
     * `400 malformed_request` (errors.connectionId) BEFORE any bucket or
     * store leg — the openapi №37 400 contract verbatim.
     */
    @PostMapping("/heartbeat")
    fun heartbeat(
        @RequestBody(required = false) request: PresenceHeartbeatRequest?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Void> {
        val connectionId = requireConnectionId(request)
        val callerId = UUID.fromString(accessToken.subject)
        enforceHeartbeatFloodLimit(callerId)
        if (!presenceService.heartbeat(callerId, connectionId)) throw HeartbeatConnectionNotFoundException()
        return ResponseEntity.noContent().build()
    }

    /** openapi №37 400: the body, the field and the UUID shape — anything less is malformed. */
    private fun requireConnectionId(request: PresenceHeartbeatRequest?): UUID {
        if (request?.connectionId == null) throw HeartbeatMalformedException("connectionId is required")
        return try {
            UUID.fromString(request.connectionId)
        } catch (_: IllegalArgumentException) {
            throw HeartbeatMalformedException("connectionId must be a UUID")
        }
    }

    /**
     * presence-api.md §2: one token per beat of the per-user contract
     * bucket (capacity [PresenceProperties.RateLimit.heartbeatsPerWindow]
     * over the 30 s [PresenceProperties.RateLimit.heartbeatWindow]) —
     * refused beats renew nothing. The refusal is the `429 flood_limit`
     * problem+json + `Retry-After` of №37 with the client contract
     * «keep the stream, retry in the next interval» (the 3× TTL
     * headroom makes the wait safe).
     */
    private fun enforceHeartbeatFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = HEARTBEAT_KEY_FAMILY,
                userId = callerId,
                permits = presenceProperties.rateLimit.heartbeatsPerWindow.toLong(),
                window = presenceProperties.rateLimit.heartbeatWindow,
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "heartbeat refused by the flood limit (№37, presence-api.md §2): user <{}> exhausted {} beats/{}s, " +
                    "retry after {}s",
                callerId,
                presenceProperties.rateLimit.heartbeatsPerWindow,
                presenceProperties.rateLimit.heartbeatWindow.toSeconds(),
                verdict.retryAfterSeconds,
            )
            throw PresenceHeartbeatFloodException(verdict.retryAfterSeconds)
        }
    }

    private companion object {
        /** research.md 004 §7 parity: the Redis key family of the №37 heartbeat bucket. */
        const val HEARTBEAT_KEY_FAMILY = "rl:user:presence-heartbeat:"
    }
}

/**
 * The №37 request body (openapi `PresenceHeartbeatRequest`): the
 * connectionId of the №18 opening frame `connected`. Bound leniently —
 * the shape errors are the controller's `400 malformed_request`
 * (errors.connectionId), not a framework parse failure.
 */
data class PresenceHeartbeatRequest(
    val connectionId: String? = null,
)

/**
 * 400 (openapi №37): the body is absent, connectionId is not set or is
 * not a UUID — rendered by [PresenceExceptionHandler] as
 * `errors: {connectionId: [malformed_request]}`.
 */
class HeartbeatMalformedException(
    detail: String,
) : RuntimeException(detail)

/**
 * 404 (openapi №37, data-model 007 §1.1): the connectionId is not a
 * LIVE registration of the caller — unknown, expired or someone else's.
 * The client's contract reaction is an immediate №18 reconnect (the
 * beat never revives anything).
 */
class HeartbeatConnectionNotFoundException : RuntimeException("the connectionId is not a live registration")

/**
 * 429 (openapi №37): the per-user heartbeat flood bucket is exhausted —
 * [retryAfterSeconds] is the integral ceiling of the wait for the next
 * available token, rendered as the `Retry-After` header. The №18 stream
 * stays up; the registration survives the throttle on its TTL
 * headroom.
 */
class PresenceHeartbeatFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user heartbeat flood limit is exhausted")
