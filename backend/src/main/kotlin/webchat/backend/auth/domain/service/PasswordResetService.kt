package webchat.backend.auth.domain.service

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import webchat.backend.auth.domain.model.TokenPurpose
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.model.UserStatus
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.email.EmailType
import webchat.backend.email.JdbcEmailOutboxRepository
import webchat.backend.email.templates.EmailTemplates

/**
 * Password reset by email link (T041; FR-010, US4).
 *
 * Request (api-contract.md №8): a fresh `password_reset` one-time token (TTL
 * 1 h via `auth.token.password-reset-ttl`, previous active ones of the same
 * purpose annulled — one live link, data-model.md §2) plus its outbox letter
 * commit atomically (data-model.md §5). The answer NEVER discloses account
 * existence: only a well-formed email of an `active` account queues the
 * letter; an unknown or incomplete account is an identical silent no-op
 * (US4-4). Unlike resend (US1-6) there is NO per-account PG cooldown — the
 * 429s of this endpoint are born solely by the US5 buckets, uniform for any
 * identifier (api-contract.md §3, the enumeration note).
 *
 * Confirm (api-contract.md №9): the FR-004 policy and the confirmation match
 * are checked via [PasswordPolicyService] BEFORE the link is consumed — a
 * rejected password leaves the link usable for a retry; a compliant password
 * is stored as an Argon2id hash, the link is atomically consumed (FR-012),
 * EVERY session of the user is revoked with reason `password_change` and
 * denylisted via [SessionService.revokeAllForUser], and the step is journaled
 * as `password_reset_completed` — all in one transaction.
 *
 * Reuses [RegistrationValidationException] (400 `Problem.errors`) and
 * [InvalidRegistrationTokenException] (the uniform 400 with a new-link
 * suggestion): contract №8–9 error semantics are identical to №1/№3–4, so
 * the problem+json surface stays single.
 *
 * Security contract (FR-013, SC-005): events carry the user id, hashed IP
 * and secrets-free markers only — never passwords, token values or the raw
 * email; the open link value exists only inside the outbox payload.
 */
@Service
@Suppress("LongParameterList") // one cohesive domain service over the US4 ports (tasks.md T041)
class PasswordResetService(
    private val userRepository: UserRepository,
    private val tokenService: TokenService,
    private val passwordPolicyService: PasswordPolicyService,
    private val passwordEncoder: PasswordEncoder,
    private val outboxRepository: JdbcEmailOutboxRepository,
    private val emailTemplates: EmailTemplates,
    private val sessionService: SessionService,
    private val authEventRecorder: AuthEventRecorder,
    private val clock: Clock,
) {
    /**
     * Contract №8 (US4-1, US4-4): issues the reset link for an `active`
     * account; every other well-formed email is the same silent success.
     */
    @Transactional
    fun requestPasswordReset(
        email: String,
        clientIp: String,
        userAgent: String? = null,
    ) {
        emailFormatErrors(email)?.let {
            throw RegistrationValidationException(mapOf(EMAIL_FIELD to it))
        }

        val user =
            userRepository
                .findByEmail(email)
                ?.takeIf { it.status == UserStatus.ACTIVE }
                ?: return // uniform 202: no letter, no journal — no enumeration signal (US4-4)

        val issued = tokenService.issue(user.id, TokenPurpose.PASSWORD_RESET)
        outboxRepository.insert(
            userId = user.id,
            recipientEmail = user.email,
            type = EmailType.PASSWORD_RESET,
            payload = emailTemplates.payload(EmailType.PASSWORD_RESET, user.username, issued.openValue),
        )
        authEventRecorder.record(
            eventType = AuthEventType.PASSWORD_RESET_REQUESTED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
        )
    }

    /**
     * Contract №9 (US4-2, US4-3, FR-012): consumes the reset link exactly
     * once and replaces the password. Unknown, used and expired links are
     * the single uniform [InvalidRegistrationTokenException]; a policy or
     * mismatch failure happens BEFORE consumption, so the SAME link stays
     * consumable for a corrected retry (T039).
     */
    @Transactional
    fun confirmPasswordReset(
        token: String,
        password: String,
        confirmPassword: String,
        clientIp: String,
        userAgent: String? = null,
    ) {
        val user = activeAccountFor(token) ?: throw InvalidRegistrationTokenException()

        validateNewPassword(user, password, confirmPassword)

        if (tokenService.consume(token, TokenPurpose.PASSWORD_RESET) == null) {
            // lost the single-use race between findActive and the conditional UPDATE
            throw InvalidRegistrationTokenException()
        }
        userRepository.update(user.changePassword(passwordEncoder.encode(password), clock.now()))
        sessionService.revokeAllForUser(user.id, clientIp, userAgent)
        authEventRecorder.record(
            eventType = AuthEventType.PASSWORD_RESET_COMPLETED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
        )
    }

    /**
     * The `active` account an ACTIVE reset link points at — resolved WITHOUT
     * consuming the link so that a rejected password leaves it usable (US4-5);
     * `null` maps every link failure mode onto the single uniform 400.
     */
    private fun activeAccountFor(token: String): User? {
        val resetToken =
            tokenService.findActive(token, TokenPurpose.PASSWORD_RESET)
                ?: return null
        return userRepository
            .findById(resetToken.userId)
            ?.takeIf { it.status == UserStatus.ACTIVE }
    }

    /**
     * FR-004 via [PasswordPolicyService] plus the confirmation match — both
     * BEFORE any token consumption (a 400 here keeps the reset link alive).
     */
    private fun validateNewPassword(
        user: User,
        password: String,
        confirmPassword: String,
    ) {
        passwordPolicyService
            .validate(password, user.username, user.email)
            ?.let { violation ->
                throw RegistrationValidationException(
                    mapOf(
                        PASSWORD_FIELD to
                            listOf(
                                when (violation) {
                                    PasswordPolicyViolation.BLANK -> CODE_BLANK
                                    PasswordPolicyViolation.TOO_SHORT -> CODE_TOO_SHORT
                                    PasswordPolicyViolation.TOO_LONG -> CODE_TOO_LONG
                                    PasswordPolicyViolation.TOO_COMMON -> CODE_TOO_COMMON
                                    PasswordPolicyViolation.SAME_AS_USERNAME -> CODE_SAME_AS_USERNAME
                                    PasswordPolicyViolation.SAME_AS_EMAIL -> CODE_SAME_AS_EMAIL
                                },
                            ),
                    ),
                )
            }
        if (confirmPassword != password) {
            throw RegistrationValidationException(mapOf(CONFIRM_PASSWORD_FIELD to listOf(MISMATCH)))
        }
    }

    /** Format/length codes for the email field or `null` when it is well-formed. */
    private fun emailFormatErrors(email: String): List<String>? =
        when {
            email.length > EMAIL_MAX_LENGTH || !EMAIL_REGEX.matches(email) -> listOf(INVALID_FORMAT)
            else -> null
        }

    private companion object {
        const val EMAIL_FIELD = "email"
        const val PASSWORD_FIELD = "password"
        const val CONFIRM_PASSWORD_FIELD = "confirmPassword"
        const val INVALID_FORMAT = "invalid_format"
        const val MISMATCH = "mismatch"
        const val CODE_BLANK = "blank"
        const val CODE_TOO_SHORT = "too_short"
        const val CODE_TOO_LONG = "too_long"
        const val CODE_TOO_COMMON = "too_common"
        const val CODE_SAME_AS_USERNAME = "same_as_username"
        const val CODE_SAME_AS_EMAIL = "same_as_email"
        const val EMAIL_MAX_LENGTH = 254

        /** PasswordResetRequest.email (api-contract.md §4): same format family as RegisterRequest.email. */
        val EMAIL_REGEX =
            Regex(
                "^[A-Za-z0-9+_.-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$",
            )
    }
}
