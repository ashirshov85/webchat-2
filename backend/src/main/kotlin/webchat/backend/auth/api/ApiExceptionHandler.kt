package webchat.backend.auth.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.auth.domain.service.InvalidCredentialsException
import webchat.backend.auth.domain.service.InvalidRefreshTokenException
import webchat.backend.auth.domain.service.InvalidRegistrationTokenException
import webchat.backend.auth.domain.service.RegistrationConflictException
import webchat.backend.auth.domain.service.RegistrationIncompleteException
import webchat.backend.auth.domain.service.RegistrationValidationException
import webchat.backend.auth.domain.service.ResendCooldownException
import java.time.Duration

/**
 * RFC 9457 rendering for the auth API errors (api-contract.md §3): every
 * failure leaves the service as a typed exception and reaches the client
 * as `application/problem+json` — `title` + `status` always, field-level
 * `errors: map<string, string[]>` for validation (400) and conflicts
 * (409), `Retry-After` for the resend cooldown (429).
 *
 * Security contract: field names and codes only — never the submitted
 * values, holder statuses or token/SQL details (US1-4, SC-005).
 */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(RegistrationValidationException::class)
    fun onValidationFailure(exception: RegistrationValidationException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, VALIDATION_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, exception.errors) }

    /**
     * 409 (api-contract.md №1): the taken fields are named, the holder's
     * status is never disclosed (US1-4) — the field list is the payload.
     */
    @ExceptionHandler(RegistrationConflictException::class)
    fun onConflict(exception: RegistrationConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, CONFLICT_DETAIL)
            .apply {
                setProperty(ERRORS_PROPERTY, exception.fields.associateWith { listOf(CODE_TAKEN) })
            }

    /**
     * 400 (api-contract.md №3–4): the single uniform reply for unknown,
     * used and expired one-time links — the cases are never distinguished
     * (FR-012).
     */
    @ExceptionHandler(InvalidRegistrationTokenException::class)
    fun onInvalidToken(): ProblemDetail = problem(HttpStatus.BAD_REQUEST, INVALID_TOKEN_DETAIL)

    /**
     * 429 (api-contract.md №2): the per-account PG cooldown, second tier
     * after the Redis buckets (research.md §11) — `Retry-After` carries
     * the remaining window (60 − elapsed), rounded up to whole seconds.
     */
    @ExceptionHandler(ResendCooldownException::class)
    fun onResendCooldown(exception: ResendCooldownException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds(exception.retryAfter).toString())
            .body(problem(HttpStatus.TOO_MANY_REQUESTS, COOLDOWN_DETAIL))

    /**
     * 401 (api-contract.md №5): the SINGLE uniform login failure — a wrong
     * password, an unknown identifier and absent fields are never
     * distinguished (US2-3; detail is always "Invalid credentials").
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun onInvalidCredentials(): ProblemDetail = problem(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS_DETAIL)

    /**
     * 403 (api-contract.md №5): the identifier resolves to an account whose
     * registration is not completed — guidance to finish registration
     * instead of the uniform 401 (US1-5).
     */
    @ExceptionHandler(RegistrationIncompleteException::class)
    fun onRegistrationIncomplete(): ProblemDetail = problem(HttpStatus.FORBIDDEN, REGISTRATION_INCOMPLETE_DETAIL)

    /**
     * 401 (api-contract.md №6): the uniform refresh failure — unknown,
     * expired, revoked and reused (reuse-detected) values are never
     * distinguished (US2-4).
     */
    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun onInvalidRefreshToken(): ProblemDetail = problem(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_TOKEN_DETAIL)

    /** Malformed JSON bodies also answer problem+json (contract §3), not the default error page. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onUnreadableBody(): ProblemDetail = problem(HttpStatus.BAD_REQUEST, MALFORMED_BODY_DETAIL)

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private fun retryAfterSeconds(remaining: Duration): Long =
        ((remaining.toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
            .coerceAtLeast(RETRY_AFTER_FLOOR_SECONDS)

    private companion object {
        const val ERRORS_PROPERTY = "errors"
        const val CODE_TAKEN = "taken"
        const val VALIDATION_DETAIL = "One or more request fields are invalid"
        const val CONFLICT_DETAIL = "One or more fields are already taken"
        const val INVALID_TOKEN_DETAIL = "Token is invalid or expired; request a new link"
        const val COOLDOWN_DETAIL = "Resend cooldown is active; retry after the indicated interval"
        const val INVALID_CREDENTIALS_DETAIL = "Invalid credentials"
        const val REGISTRATION_INCOMPLETE_DETAIL =
            "Registration is not completed: confirm your email and set a password"
        const val INVALID_REFRESH_TOKEN_DETAIL = "Invalid refresh token"
        const val MALFORMED_BODY_DETAIL = "Malformed request body"
        const val MILLIS_PER_SECOND = 1000L
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
    }
}
