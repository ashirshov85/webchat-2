package webchat.backend.presence.api

import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.config.PresenceProperties
import webchat.backend.config.UserRateLimiter
import webchat.backend.presence.domain.PresenceService
import java.util.UUID

/**
 * The №38 HTTP surface of 007 (contracts/presence-api.md §3):
 * `GET|PUT /api/v1/users/me/presence/settings` — the «невидимка» mode
 * (FR-007), one operation shared by the standalone SPA and the widget.
 * The name mapping is fixed by the contract: «невидимка» (spec/UI) ≡
 * the `incognito` wire field ≡ the V15 `users.presence_hidden` column.
 *
 * A thin HTTP adapter exactly like the №36/№37 legs of
 * [PresenceController]: this layer validates the body shape, enforces
 * the per-user PUT flood bucket and projects the contract outcomes —
 * `200 {incognito}` for both directions (the PUT answer carries the
 * STORED value; presence-api.md §3), `400 malformed_request` for an
 * absent body, a missing/null `incognito` or any non-boolean value
 * (errors.incognito) and `429 flood_limit` + `Retry-After` for a PUT
 * beyond the bucket. The mode itself is durable per-USER (data-model
 * §1.5): it survives sessions and never depends on a live №18 stream.
 *
 * The IDEMPOTENT PUT is the port's own contract: a repeat of the very
 * same value is a NO-OP without events and without a rev advance — the
 * atomic change verdict of the conditional V15 write
 * ([webchat.backend.presence.domain.PresenceService.updatePresenceSettings])
 * decides, so a racing double-submit or a retried tab stays perfectly
 * unobservable. The SWITCH semantics of a REAL flip (enable → one
 * immediate `offline` + the freeze; disable → the actual-status reveal;
 * ≤ one displayed switch per toggle) are the T033 legs of
 * [PresenceService] — the controller never branches on them.
 *
 * The flood gate (the per-user `rl:user:presence-settings:{userId}`
 * bucket of [PresenceProperties.RateLimit.settingsWritesPerMinute],
 * conservative like №26/№36) guards the PUT ONLY — the GET is a pure
 * point read the settings page polls freely. It sits AFTER the shape
 * validation and BEFORE any SQL leg (the №36/№37 precedent): a refused
 * request performs no write, and the bucket counts REQUESTS, not
 * outcomes — an idempotent no-op PUT still draws its token, which is
 * exactly how the T031 drain burst throttles. Redis unavailability
 * fails open ([UserRateLimiter]).
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as every /users/me route); the caller id is the token
 * `sub` claim. Failures leave as typed exceptions rendered problem+json
 * by [PresenceExceptionHandler]; the warn log carries ids and waits
 * only — never a payload (constitution V).
 */
@RestController
@RequestMapping("/api/v1/users/me/presence/settings")
class PresenceSettingsController(
    private val presenceService: PresenceService,
    private val rateLimiter: UserRateLimiter,
    private val presenceProperties: PresenceProperties,
) {
    private val log = LoggerFactory.getLogger(PresenceSettingsController::class.java)

    /**
     * №38 GET → `200 {incognito}`: the persisted mode (the V15 default
     * answers `false`). No shape to validate, no bucket — the pure read
     * leg of [PresenceService.presenceSettings].
     */
    @GetMapping
    fun getSettings(
        @AuthenticationPrincipal accessToken: Jwt,
    ): PresenceSettingsResponse =
        PresenceSettingsResponse(
            incognito = presenceService.presenceSettings(UUID.fromString(accessToken.subject)),
        )

    /**
     * №38 PUT `{incognito}` → `200` with the STORED value: the shape
     * gate FIRST (a missing body, a missing/null [PresenceSettingsUpdateRequest.incognito]
     * or any non-boolean value is `400 malformed_request`,
     * errors.incognito — the openapi №38 400 contract verbatim), the
     * flood bucket NEXT and the durable idempotent write of
     * [PresenceService.updatePresenceSettings] LAST.
     */
    @PutMapping
    fun updateSettings(
        @RequestBody(required = false) request: PresenceSettingsUpdateRequest?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): PresenceSettingsResponse {
        val incognito = requireIncognito(request)
        val callerId = UUID.fromString(accessToken.subject)
        enforceSettingsFloodLimit(callerId)
        return PresenceSettingsResponse(
            incognito = presenceService.updatePresenceSettings(callerId, incognito).incognito,
        )
    }

    /** openapi №38 400: the field must be present and a JSON boolean — anything less is malformed. */
    private fun requireIncognito(request: PresenceSettingsUpdateRequest?): Boolean {
        val value = request?.incognito
        if (value !is Boolean) throw SettingsMalformedException("incognito is required and must be a boolean")
        return value
    }

    /**
     * presence-api.md §3: one token per PUT of the per-user bucket
     * `rl:user:presence-settings:{userId}` (capacity
     * [PresenceProperties.RateLimit.settingsWritesPerMinute] over the
     * 60 s window — conservative like №26/№36) — a refused PUT performs
     * NO write. The refusal is the `429 flood_limit` problem+json +
     * `Retry-After` of №38: the client repeats the toggle after the
     * advertised wait (a lost toggle is recoverable — the settings page
     * re-reads the stored mode).
     */
    private fun enforceSettingsFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = SETTINGS_KEY_FAMILY,
                userId = callerId,
                permitsPerMinute = presenceProperties.rateLimit.settingsWritesPerMinute.toLong(),
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "presence settings PUT refused by the flood limit (№38, presence-api.md §3): user <{}> exhausted " +
                    "{} writes/minute, retry after {}s",
                callerId,
                presenceProperties.rateLimit.settingsWritesPerMinute,
                verdict.retryAfterSeconds,
            )
            throw PresenceSettingsFloodException(verdict.retryAfterSeconds)
        }
    }

    private companion object {
        /** research.md 004 §7 parity: the Redis key family of the №38 PUT bucket. */
        const val SETTINGS_KEY_FAMILY = "rl:user:presence-settings:"
    }
}

/**
 * The №38 success body (openapi `PresenceSettingsResponse`): exactly
 * the stored mode — GET and PUT answer the same shape.
 */
data class PresenceSettingsResponse(
    val incognito: Boolean,
)

/**
 * The №38 PUT body (openapi `PresenceSettingsUpdateRequest`): bound
 * leniently as a plain JSON value — the boolean shape gate is the
 * controller's `400 malformed_request` (errors.incognito), not a
 * framework parse failure (the №37 [PresenceHeartbeatRequest]
 * precedent).
 */
data class PresenceSettingsUpdateRequest(
    val incognito: Any? = null,
)

/**
 * 400 (openapi №38): the body is absent, `incognito` is not set or is
 * not a boolean — rendered by [PresenceExceptionHandler] as
 * `errors: {incognito: [malformed_request]}`.
 */
class SettingsMalformedException(
    detail: String,
) : RuntimeException(detail)

/**
 * 429 (openapi №38): the per-user PUT flood bucket is exhausted —
 * [retryAfterSeconds] is the integral ceiling of the wait for the next
 * available token, rendered as the `Retry-After` header. The stored
 * mode is NOT touched; the client repeats the toggle after the wait.
 */
class PresenceSettingsFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user presence settings flood limit is exhausted")
