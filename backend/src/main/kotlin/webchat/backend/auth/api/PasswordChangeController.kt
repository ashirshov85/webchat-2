package webchat.backend.auth.api

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.auth.api.dto.TokenPairResponse
import webchat.backend.auth.domain.service.IssuedTokenPair
import webchat.backend.auth.domain.service.PasswordChangeService
import webchat.backend.auth.ratelimit.ClientIpResolver
import webchat.backend.config.AuthRateLimitProperties
import webchat.backend.config.UserRateLimiter
import java.util.UUID

/**
 * Contract 0.10.0 §1 (008b T019): `POST /api/v1/auth/password-change` —
 * a thin Bearer-gated HTTP adapter over [PasswordChangeService]; every
 * business rule (the current-password verification with the JIT timing
 * parity, the 002 policy, the Argon2id hash update, the other-session
 * revocation and the current-session rotation) stays in the service.
 *
 * The security chain has ALREADY authenticated the request — the route is
 * NOT in the permitAll table of SecurityConfig, so `anyRequest,
 * authenticated` holds and the uniform 401 boundary is produced entirely
 * by the chain, never here. This adapter only resolves the owner
 * (`userId` = the token `sub` claim) and the session to keep alive
 * (`sid` claim, AuthJwtDecoder always sets it on a verified token — the
 * RealtimeController precedent).
 *
 * Rate limiting is two-tiered (research.md 008b §6, FR-007): the pre-auth
 * per-IP flood guard `rl:ip:password-change:<ip>` (10/1m) has already run
 * in [webchat.backend.auth.ratelimit.RateLimitFilter]; the authenticated
 * per-user tier `rl:user:pwdchange:{userId}` (5/1m,
 * [AuthRateLimitProperties.passwordChange]) runs HERE via
 * [UserRateLimiter.tryAcquire] — BEFORE the service leg, so a refused
 * attempt performs no verification, is not journaled and changes nothing.
 * Redis unavailability fails open (the inherited invariant).
 *
 * All non-throttle failures leave as the typed exceptions of the service
 * and reach the client as problem+json via [ApiExceptionHandler]: the
 * uniform 400 `{currentPassword: [invalid]}` / `{newPassword: [code]}`
 * of [webchat.backend.auth.domain.service.RegistrationValidationException]
 * and the uniform 401 of
 * [webchat.backend.auth.domain.service.InvalidRefreshTokenException] —
 * never inline bodies.
 */
@RestController
@RequestMapping("/api/v1/auth")
class PasswordChangeController(
    private val passwordChangeService: PasswordChangeService,
    private val rateLimiter: UserRateLimiter,
    private val rateLimitProperties: AuthRateLimitProperties,
    private val clientIpResolver: ClientIpResolver,
) {
    private val log = LoggerFactory.getLogger(PasswordChangeController::class.java)

    /**
     * Contract №43: the password change of the signed-in owner — 200 the
     * rotated `TokenPair` of the CURRENT session (parity with
     * `POST /auth/refresh`, a bare pair without the `user` fragment); the
     * client applies the pair itself, the user stays signed in (FR-008).
     */
    @PostMapping("/password-change")
    fun changePassword(
        @RequestBody request: PasswordChangeRequest,
        @AuthenticationPrincipal accessToken: Jwt,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<TokenPairResponse> {
        val userId = UUID.fromString(accessToken.subject)
        val sessionId = UUID.fromString(accessToken.getClaimAsString(SESSION_ID_CLAIM))
        enforceUserFloodLimit(userId)
        val pair =
            passwordChangeService.changePassword(
                userId = userId,
                currentSessionId = sessionId,
                currentPassword = request.currentPassword.orEmpty(),
                newPassword = request.newPassword.orEmpty(),
                clientIp = clientIpResolver.resolve(servletRequest),
                userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
            )
        return ResponseEntity.ok(pair.toResponse())
    }

    /**
     * research.md 008b §6: one token per attempt of the per-user bucket
     * `rl:user:pwdchange:{userId}` (capacity
     * [AuthRateLimitProperties.PasswordChangeLimits.userPerMinute] over
     * the 60 s window) — a refused request performs NO service leg and is
     * NOT journaled; the client repeats after the advertised
     * `Retry-After` wait. The warn log carries ids and the wait only —
     * never a submitted password (constitution V).
     */
    private fun enforceUserFloodLimit(userId: UUID) {
        val permitsPerMinute = rateLimitProperties.passwordChange.userPerMinute.toLong()
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = USER_KEY_FAMILY,
                userId = userId,
                permitsPerMinute = permitsPerMinute,
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "password change refused by the per-user flood limit (№43, contract 0.10.0 §1): " +
                    "user <{}> exhausted {} changes/minute, retry after {}s",
                userId,
                permitsPerMinute,
                verdict.retryAfterSeconds,
            )
            throw PasswordChangeFloodException(verdict.retryAfterSeconds)
        }
    }

    /**
     * 429 (contract 0.10.0 §1): the per-user bucket is exhausted —
     * `Retry-After` carries the integral seconds to the next token;
     * nothing was verified, revoked or journaled.
     */
    @ExceptionHandler(PasswordChangeFloodException::class)
    fun onPasswordChangeFlood(failure: PasswordChangeFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                ProblemDetail
                    .forStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .apply {
                        title = HttpStatus.TOO_MANY_REQUESTS.reasonPhrase
                        detail = PASSWORD_CHANGE_FLOOD_DETAIL
                        setProperty(ERRORS_PROPERTY, mapOf(CURRENT_PASSWORD_FIELD to listOf(FLOOD_LIMIT_CODE)))
                    },
            )

    private fun IssuedTokenPair.toResponse(): TokenPairResponse =
        TokenPairResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = TOKEN_TYPE_BEARER,
            expiresInSec = expiresInSec,
        )

    private companion object {
        /** AuthJwtDecoder: the 002 session claim every verified access token carries. */
        const val SESSION_ID_CLAIM = "sid"

        /** research.md 008b §6: the Redis key family of the №43 per-user bucket. */
        const val USER_KEY_FAMILY = "rl:user:pwdchange:"

        const val TOKEN_TYPE_BEARER = "Bearer"
        const val USER_AGENT_HEADER = "User-Agent"
        const val ERRORS_PROPERTY = "errors"

        /** The typing-family convention: the flood code rides the request's primary field. */
        const val CURRENT_PASSWORD_FIELD = "currentPassword"
        const val FLOOD_LIMIT_CODE = "flood_limit"
        const val PASSWORD_CHANGE_FLOOD_DETAIL =
            "The per-user password-change limit is exhausted; retry after the advertised wait"
    }
}

/**
 * Contract 0.10.0 §1 request body — `PasswordChangeRequest`: the current
 * password plus the new one. NO `confirmPassword` — the match is a client
 * check before submission (FR-005, research.md 008b §5-alternatives d).
 *
 * Fields are nullable so an absent field stays on the field-coded 400 leg
 * of the service (an empty current password can only fail the
 * verification; an empty new password is the `blank` policy code) instead
 * of breaking deserialization — every error response stays problem+json
 * (the [webchat.backend.auth.api.dto.LoginRequest] convention).
 */
data class PasswordChangeRequest(
    val currentPassword: String? = null,
    val newPassword: String? = null,
)

/**
 * 429 (contract 0.10.0 §1): the per-user password-change bucket is
 * exhausted — [retryAfterSeconds] is the integral ceiling of the wait for
 * the next available token, rendered as the `Retry-After` header.
 */
class PasswordChangeFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user password-change flood limit is exhausted")
