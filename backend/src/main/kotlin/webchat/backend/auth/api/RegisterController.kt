package webchat.backend.auth.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.auth.api.dto.ConfirmRegistrationRequest
import webchat.backend.auth.api.dto.ConfirmRegistrationResponse
import webchat.backend.auth.api.dto.RegisterRequest
import webchat.backend.auth.api.dto.ResendRequest
import webchat.backend.auth.api.dto.SetPasswordRequest
import webchat.backend.auth.domain.service.InvalidRegistrationTokenException
import webchat.backend.auth.domain.service.RegistrationService
import webchat.backend.auth.domain.service.RegistrationValidationException

/**
 * US1 registration endpoints (api-contract.md №1–4), thin HTTP adapter
 * over [RegistrationService]: field presence and client metadata are
 * resolved here, every business rule stays in the service. All errors
 * leave as typed exceptions rendered problem+json by
 * [ApiExceptionHandler] — never inline bodies.
 *
 * `POST /api/v1/auth/register` — 202 created/resumed + queued letter;
 * 400 field format; 409 taken fields (any holder status); 429.
 * `POST /api/v1/auth/register/resend` — uniform 202 (never discloses
 * existence/status); 400; 429 (bucket first, then the 60 s cooldown).
 * `POST /api/v1/auth/register/confirm` — 200 `{setupToken,
 * setupTokenType:"password_setup", expiresInSec}`; 400 uniform.
 * `POST /api/v1/auth/register/password` — 204 `active`; 400 (link/policy
 * /mismatch — a rejected password keeps the link usable); 429.
 */
@RestController
@RequestMapping("/api/v1/auth/register")
class RegisterController(
    private val registrationService: RegistrationService,
) {
    /** Contract №1 (FR-001, FR-002): account creation or resumption with a queued letter. */
    @PostMapping
    fun register(
        @RequestBody request: RegisterRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        val fields =
            requireFields(
                USERNAME_FIELD to request.username,
                EMAIL_FIELD to request.email,
            )
        registrationService.register(
            username = fields.getValue(USERNAME_FIELD),
            email = fields.getValue(EMAIL_FIELD),
            clientIp = clientIp(servletRequest),
            userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
        )
        return ResponseEntity.accepted().build()
    }

    /** Contract №2 (US1-6): the uniform resend answer, letter semantics by account status. */
    @PostMapping("/resend")
    fun resend(
        @RequestBody request: ResendRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        val fields = requireFields(EMAIL_FIELD to request.email)
        registrationService.resend(
            email = fields.getValue(EMAIL_FIELD),
            clientIp = clientIp(servletRequest),
            userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
        )
        return ResponseEntity.accepted().build()
    }

    /** Contract №3 (FR-003): consumes the verification link, replies with the open setup token. */
    @PostMapping("/confirm")
    fun confirm(
        @RequestBody request: ConfirmRegistrationRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<ConfirmRegistrationResponse> {
        val token = request.token ?: throw InvalidRegistrationTokenException()
        val confirmed =
            registrationService.confirm(
                token = token,
                clientIp = clientIp(servletRequest),
                userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
            )
        return ResponseEntity.ok(
            ConfirmRegistrationResponse(
                setupToken = confirmed.setupToken,
                setupTokenType = confirmed.setupTokenType,
                expiresInSec = confirmed.expiresInSec,
            ),
        )
    }

    /** Contract №4 (FR-003, FR-004): the password step that activates the account. */
    @PostMapping("/password")
    fun setPassword(
        @RequestBody request: SetPasswordRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        val setupToken = request.setupToken ?: throw InvalidRegistrationTokenException()
        val fields =
            requireFields(
                PASSWORD_FIELD to request.password,
                CONFIRM_PASSWORD_FIELD to request.confirmPassword,
            )
        registrationService.setPassword(
            setupToken = setupToken,
            password = fields.getValue(PASSWORD_FIELD),
            confirmPassword = fields.getValue(CONFIRM_PASSWORD_FIELD),
            clientIp = clientIp(servletRequest),
            userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * Presence gate: absent fields become a field-level `Problem.errors`
     * 400 (api-contract.md §3); format rules are the service's (T022b).
     * Returns the resolved non-null values keyed by field name.
     */
    private fun requireFields(vararg fields: Pair<String, String?>): Map<String, String> {
        val missing = fields.filter { it.second == null }.map { it.first }
        if (missing.isNotEmpty()) {
            throw RegistrationValidationException(missing.associateWith { listOf(CODE_MISSING) })
        }
        return fields.associate { (name, value) -> name to requireNotNull(value) }
    }

    /**
     * Interim request-source extraction pending the shared US5 resolver
     * (T046): XFF leftmost with a remoteAddr fallback — compatible with
     * `server.forward-headers-strategy=framework`, where Spring rewrites
     * remoteAddr from XFF and strips the header. Only the SHA-256 peppered
     * hash of the value is ever persisted (FR-013).
     */
    private fun clientIp(request: HttpServletRequest): String {
        val forwardedFor = request.getHeader(X_FORWARDED_FOR_HEADER)
        if (forwardedFor != null) {
            val leftmostHop = forwardedFor.substringBefore(COMMA).trim()
            if (leftmostHop.isNotEmpty()) return leftmostHop
        }
        return request.remoteAddr
    }

    private companion object {
        const val USERNAME_FIELD = "username"
        const val EMAIL_FIELD = "email"
        const val PASSWORD_FIELD = "password"
        const val CONFIRM_PASSWORD_FIELD = "confirmPassword"
        const val CODE_MISSING = "missing"
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
        const val USER_AGENT_HEADER = "User-Agent"
        const val COMMA = ","
    }
}
