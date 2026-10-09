package webchat.backend.users.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.config.UserRateLimiter
import webchat.backend.config.UsersProperties
import webchat.backend.users.api.dto.PublicUserResponse
import webchat.backend.users.domain.model.DisplayName
import webchat.backend.users.domain.model.InvalidDisplayNameException
import webchat.backend.users.domain.model.Profile
import webchat.backend.users.domain.port.ProfileStore
import java.util.UUID

/**
 * Contract №10 (T038): `GET /api/v1/users/me` — the minimal protected
 * resource fixing the authentication boundary of the chat API (US3,
 * SC-002); every later chat endpoint stays behind the same Bearer gate.
 *
 * The security chain has ALREADY authenticated the request before this
 * adapter runs: [webchat.backend.auth.security.AuthJwtDecoder] verified the
 * ES256 access token (signature, expiry, `typ`, denylisted `sid`) and the
 * authorize rules require `authenticated()` — so the uniform 401 of
 * api-contract.md §3 is produced entirely by the chain, never here. This
 * adapter only resolves the token owner (`sub` claim, minted by JwtService
 * as the user id) into the `PublicUser` contract body.
 *
 * 008a №39 (T012, api-contract.md §1): `PUT /api/v1/users/me/profile`
 * sets/clears the profile display name. A thin HTTP adapter exactly like
 * the №38 legs of
 * [webchat.backend.presence.api.PresenceSettingsController]: the FR-001
 * shape/length gate FIRST (the server trim of
 * [DisplayName.normalize]; `null`/an omitted field is the RESET leg —
 * «not set», never a validation case), the per-user flood bucket
 * `rl:user:profile:{userId}` (30/min, [UsersProperties.RateLimit.profileWritesPerMinute])
 * NEXT and the idempotent last-write-wins store write LAST — a repeat of
 * the same value is a plain 200 with NO side events (there is no
 * name-change realtime event by contract — refetch semantics). The №10
 * read now serves the SAME projection through
 * [ProfileStore.findByUserId], so a fresh `me` always carries the stored
 * name; a user without one keeps the field ABSENT (008 clients render
 * `username`, SC-007).
 *
 * The bucket counts REQUESTS, not outcomes — a refused write performs no
 * SQL leg; Redis unavailability fails open ([UserRateLimiter]). Failures
 * leave as typed exceptions rendered problem+json below; the warn log
 * carries ids and waits only — never the submitted name (constitution V).
 */
@RestController
@RequestMapping("/api/v1/users")
class UsersController(
    private val profileStore: ProfileStore,
    private val rateLimiter: UserRateLimiter,
    private val usersProperties: UsersProperties,
) {
    private val log = LoggerFactory.getLogger(UsersController::class.java)

    /** Contract №10: the token owner as `PublicUser {id, username, email, status, createdAt, displayName?}`. */
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal accessToken: Jwt,
    ): PublicUserResponse = publicUserOf(UUID.fromString(accessToken.subject))

    /**
     * Contract №39 `PUT /users/me/profile` → `200 PublicUser` with the
     * STORED projection: the shape gate FIRST (`""`/whitespace-only/oversized
     * or a non-string value is `400 invalid_display_name`; `null`/an
     * omitted field is the reset leg), the flood bucket NEXT and the
     * single-statement [ProfileStore.updateDisplayName] LAST — its
     * `UPDATE … RETURNING` answer cannot race a concurrent write.
     */
    @PutMapping("/me/profile")
    fun updateMyProfile(
        @RequestBody(required = false) request: ProfileUpdateRequest?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): PublicUserResponse {
        val displayName = normalizeOrNull(request?.displayName)
        val callerId = UUID.fromString(accessToken.subject)
        enforceProfileFloodLimit(callerId)
        return publicUserOf(profileStore.updateDisplayName(callerId, displayName))
    }

    /** The shared `PublicUser` projector: the stored name rides ALONG, ABSENT while NULL (SC-007). */
    private fun publicUserOf(userId: UUID): PublicUserResponse {
        val owner = profileStore.findByUserId(userId) ?: throw AuthenticatedUserNotFoundException()
        return publicUserOf(owner)
    }

    private fun publicUserOf(profile: Profile): PublicUserResponse =
        PublicUserResponse(
            id = profile.id,
            username = profile.username,
            email = profile.email,
            status = profile.status,
            createdAt = profile.createdAt,
            displayName = profile.displayName?.value,
        )

    /**
     * The FR-001 single gate of №39: a NON-NULL value must be a JSON
     * string that survives [DisplayName.normalize] (server trim, 1–64
     * chars) — everything else is the one contract refusal
     * `400 invalid_display_name`; `null` passes as the reset leg.
     */
    private fun normalizeOrNull(raw: Any?): DisplayName? =
        when (raw) {
            null -> null
            !is String -> throw InvalidDisplayNameException()
            else -> DisplayName.normalize(raw)
        }

    /**
     * api-contract.md §1 №39: one token per PUT of the per-user bucket
     * `rl:user:profile:{userId}` (capacity
     * [UsersProperties.RateLimit.profileWritesPerMinute] over the 60 s
     * window — the №38 parity): a refused PUT performs NO write and the
     * client repeats after the advertised `Retry-After` wait (the stored
     * name is untouched — a lost toggle is recoverable via №10).
     */
    private fun enforceProfileFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = PROFILE_KEY_FAMILY,
                userId = callerId,
                permitsPerMinute = usersProperties.rateLimit.profileWritesPerMinute.toLong(),
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "profile PUT refused by the flood limit (№39, api-contract.md §1): user <{}> exhausted " +
                    "{} writes/minute, retry after {}s",
                callerId,
                usersProperties.rateLimit.profileWritesPerMinute,
                verdict.retryAfterSeconds,
            )
            throw ProfileFloodException(verdict.retryAfterSeconds)
        }
    }

    /**
     * Defensive-only 401 (contract №10 declares just 200/401): a
     * cryptographically valid token whose owner no longer exists holds no
     * authenticated identity, so the reply is the SAME uniform boundary
     * problem — `Not authenticated` problem+json — never a 500.
     */
    @ExceptionHandler(AuthenticatedUserNotFoundException::class)
    fun onAuthenticatedUserNotFound(): ProblemDetail = problem(HttpStatus.UNAUTHORIZED, NOT_AUTHENTICATED_DETAIL)

    /**
     * 400 (openapi №39): the submitted `displayName` is empty/whitespace
     * after trim, longer than 64 characters or not a JSON string —
     * `errors: {displayName: [invalid_display_name]}`; the submitted
     * value is never echoed (constitution V) and the stored name
     * SURVIVES (US1 AC5 — the gate ran before any SQL leg).
     */
    @ExceptionHandler(InvalidDisplayNameException::class)
    fun onInvalidDisplayName(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_DISPLAY_NAME_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(DISPLAY_NAME_FIELD to listOf(INVALID_DISPLAY_NAME_CODE))) }

    /**
     * 429 (openapi №39): the per-user PUT bucket is exhausted —
     * `Retry-After` carries the integral seconds to the next token; the
     * stored name is NOT touched and the client repeats the save after
     * the wait.
     */
    @ExceptionHandler(ProfileFloodException::class)
    fun onProfileFlood(failure: ProfileFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, PROFILE_FLOOD_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(DISPLAY_NAME_FIELD to listOf(FLOOD_LIMIT_CODE))) },
            )

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private class AuthenticatedUserNotFoundException : RuntimeException(NOT_AUTHENTICATED_DETAIL)

    private companion object {
        /** research.md 008a C2: the Redis key family of the №39 PUT bucket. */
        const val PROFILE_KEY_FAMILY = "rl:user:profile:"
        const val ERRORS_PROPERTY = "errors"
        const val DISPLAY_NAME_FIELD = "displayName"
        const val INVALID_DISPLAY_NAME_CODE = "invalid_display_name"
        const val FLOOD_LIMIT_CODE = "flood_limit"
        const val NOT_AUTHENTICATED_DETAIL = "Not authenticated"
        const val INVALID_DISPLAY_NAME_DETAIL =
            "displayName must be absent/null (reset) or 1..64 characters after trim"
        const val PROFILE_FLOOD_DETAIL =
            "The per-user profile flood limit is exhausted; repeat the save after the advertised wait"
    }
}

/**
 * The №39 PUT body (openapi `ProfileUpdateRequest`): bound leniently as
 * a plain JSON value — the FR-001 shape gate is the controller's
 * `400 invalid_display_name` (errors.displayName), not a framework parse
 * failure (the №38 [webchat.backend.presence.api.PresenceSettingsUpdateRequest]
 * precedent); `null`/omitted = the reset leg.
 */
data class ProfileUpdateRequest(
    val displayName: Any? = null,
)

/**
 * 429 (openapi №39): the per-user profile flood bucket is exhausted —
 * [retryAfterSeconds] is the integral ceiling of the wait for the next
 * available token, rendered as the `Retry-After` header.
 */
class ProfileFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user profile flood limit is exhausted")
