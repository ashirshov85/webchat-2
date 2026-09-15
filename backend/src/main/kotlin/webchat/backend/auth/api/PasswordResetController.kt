package webchat.backend.auth.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.auth.api.dto.PasswordResetConfirmRequest
import webchat.backend.auth.api.dto.PasswordResetRequest
import webchat.backend.auth.domain.service.InvalidRegistrationTokenException
import webchat.backend.auth.domain.service.PasswordResetService
import webchat.backend.auth.domain.service.RegistrationValidationException
import webchat.backend.auth.ratelimit.ClientIpResolver

/**
 * US4 password-reset endpoints (api-contract.md №8–9), thin HTTP adapter
 * over [PasswordResetService]: field presence and client metadata are
 * resolved here, every business rule stays in the service. All errors
 * leave as typed exceptions rendered problem+json by
 * [ApiExceptionHandler] — never inline bodies. The 429s of both routes
 * are born solely by the US5 rate-limit filter (T047) — there is no
 * per-account cooldown on this flow (api-contract.md §1, the
 * enumeration note), so the adapter adds nothing on that leg.
 *
 * `POST /api/v1/auth/password-reset` — 202 ALWAYS uniform, never
 * disclosing account existence (US4-4); 400 email format; 429 buckets.
 * `POST /api/v1/auth/password-reset/confirm` — 204 password replaced,
 * every session revoked; 400 uniform link failure (FR-012) or FR-004
 * policy/mismatch — a rejected password keeps the link consumable;
 * 429 buckets.
 */
@RestController
@RequestMapping("/api/v1/auth/password-reset")
class PasswordResetController(
    private val passwordResetService: PasswordResetService,
    private val clientIpResolver: ClientIpResolver,
) {
    /** Contract №8 (US4-1, US4-4): queues the reset letter for an active account, else a silent no-op. */
    @PostMapping
    fun requestPasswordReset(
        @RequestBody request: PasswordResetRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        val fields = requireFields(EMAIL_FIELD to request.email)
        passwordResetService.requestPasswordReset(
            email = fields.getValue(EMAIL_FIELD),
            clientIp = clientIpResolver.resolve(servletRequest),
            userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
        )
        return ResponseEntity.accepted().build()
    }

    /** Contract №9 (US4-2, US4-3, FR-010): consumes the reset link and replaces the password. */
    @PostMapping("/confirm")
    fun confirmPasswordReset(
        @RequestBody request: PasswordResetConfirmRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        val token = request.token ?: throw InvalidRegistrationTokenException()
        val fields =
            requireFields(
                PASSWORD_FIELD to request.password,
                CONFIRM_PASSWORD_FIELD to request.confirmPassword,
            )
        passwordResetService.confirmPasswordReset(
            token = token,
            password = fields.getValue(PASSWORD_FIELD),
            confirmPassword = fields.getValue(CONFIRM_PASSWORD_FIELD),
            clientIp = clientIpResolver.resolve(servletRequest),
            userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * Presence gate: absent fields become a field-level `Problem.errors`
     * 400 (api-contract.md §3); format and policy rules are the
     * service's (T041). Returns the resolved non-null values keyed by
     * field name.
     */
    private fun requireFields(vararg fields: Pair<String, String?>): Map<String, String> {
        val missing = fields.filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            throw RegistrationValidationException(missing.associateWith { listOf(CODE_MISSING) })
        }
        return fields.associate { (name, value) -> name to requireNotNull(value) }
    }

    private companion object {
        const val EMAIL_FIELD = "email"
        const val PASSWORD_FIELD = "password"
        const val CONFIRM_PASSWORD_FIELD = "confirmPassword"
        const val CODE_MISSING = "missing"
        const val USER_AGENT_HEADER = "User-Agent"
    }
}
