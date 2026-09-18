package webchat.backend.sso.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.sso.domain.service.SsoIdentityNotFoundException
import webchat.backend.sso.domain.service.SsoLastLoginMethodException

/**
 * RFC 9457 rendering for the SSO API errors (contracts/sso-api.md §2, §4,
 * §7) — the same conventions as the auth
 * [webchat.backend.auth.api.ApiExceptionHandler]: every failure leaves the
 * controller as a typed exception and reaches the client as
 * `application/problem+json`, with field-level
 * `errors: map<string, string[]>` for the two uniform 400s and the 409 of
 * the unlink guard. The browser-leg callback never throws — its outcomes
 * are 302 redirects (sso-api.md §3).
 *
 * Security contract: codes and field names only — never provider
 * configuration, holder details or code/token values (SC-004/FR-011).
 */
@RestControllerAdvice
class SsoExceptionHandler {
    /** 404 (sso-api.md §2): unknown and disabled providers are indistinguishable. */
    @ExceptionHandler(SsoProviderNotFoundException::class)
    fun onProviderNotFound(): ProblemDetail = problem(HttpStatus.NOT_FOUND, PROVIDER_NOT_FOUND_DETAIL)

    /** 400 (sso-api.md §2, US1-6): a non-relative or over-long `returnTo`. */
    @ExceptionHandler(SsoReturnToInvalidException::class)
    fun onInvalidReturnTo(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_FIELD_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(RETURN_TO_FIELD to listOf(INVALID_FORMAT_CODE))) }

    /** 400 (sso-api.md §4): unknown, used and expired handshake codes are never distinguished. */
    @ExceptionHandler(SsoHandshakeCodeInvalidException::class)
    fun onInvalidHandshakeCode(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_HANDSHAKE_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CODE_FIELD to listOf(INVALID_CODE_CODE))) }

    /** 404 (sso-api.md §7): a missing or foreign identity — one answer, no holder disclosure. */
    @ExceptionHandler(SsoIdentityNotFoundException::class)
    fun onIdentityNotFound(): ProblemDetail = problem(HttpStatus.NOT_FOUND, IDENTITY_NOT_FOUND_DETAIL)

    /** 409 (sso-api.md §7, US3-4): the unlink would remove the last login method — set a password first. */
    @ExceptionHandler(SsoLastLoginMethodException::class)
    fun onLastLoginMethod(): ProblemDetail =
        problem(HttpStatus.CONFLICT, LAST_LOGIN_METHOD_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(IDENTITY_FIELD to listOf(LAST_LOGIN_METHOD_CODE))) }

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private companion object {
        const val ERRORS_PROPERTY = "errors"
        const val RETURN_TO_FIELD = "returnTo"
        const val CODE_FIELD = "code"
        const val IDENTITY_FIELD = "identity"
        const val INVALID_FORMAT_CODE = "invalid_format"
        const val INVALID_CODE_CODE = "invalid_code"
        const val LAST_LOGIN_METHOD_CODE = "last_login_method"
        const val PROVIDER_NOT_FOUND_DETAIL = "Provider not found"
        const val INVALID_FIELD_DETAIL = "One or more request fields are invalid"
        const val INVALID_HANDSHAKE_DETAIL = "Token is invalid or expired; request a new login"
        const val IDENTITY_NOT_FOUND_DETAIL = "Identity not found"
        const val LAST_LOGIN_METHOD_DETAIL = "Set a password before removing the last login method"
    }
}
