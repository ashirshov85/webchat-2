package webchat.backend.auth.domain.service

import org.springframework.dao.DataIntegrityViolationException
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
import java.time.Duration
import java.util.UUID

/**
 * 400: field-level validation failure (api-contract.md §3 — `Problem.errors`
 * with codes, e.g. `{email: [invalid_format]}`).
 *
 * Carries field names and codes only — never the submitted values (SC-005).
 */
class RegistrationValidationException(
    val errors: Map<String, List<String>>,
) : RuntimeException("registration request failed validation on fields: ${errors.keys}")

/**
 * 409: the indicated fields are taken by an account of ANY status, active or
 * incomplete (US1-4, api-contract.md №1). The holder's status is never
 * disclosed — the field list is the whole error payload.
 */
class RegistrationConflictException(
    val fields: Set<String>,
) : RuntimeException("registration fields already taken: $fields")

/**
 * 429: the per-account resend cooldown is still active (US1-6, data-model.md §5);
 * [retryAfter] feeds the controller's `Retry-After` header (60 − elapsed).
 */
class ResendCooldownException(
    val retryAfter: Duration,
) : RuntimeException("resend cooldown is active, retry after ${retryAfter.seconds}s")

/**
 * 400: the one-time link token is unknown, already used or expired — a single
 * uniform reply that never distinguishes the cases (api-contract.md №3–4,
 * FR-012 idempotency).
 */
class InvalidRegistrationTokenException : RuntimeException("one-time token is invalid or expired")

/**
 * Confirm-registration reply (api-contract.md №3): the open password_setup
 * token handed to the SPA for the /set-password step. The open value exists
 * only here and in the response — persistence keeps the SHA-256 hash (SC-005).
 */
data class ConfirmedRegistration(
    val setupToken: String,
    val setupTokenType: String,
    val expiresInSec: Long,
)

/**
 * Account creation and its email steps: register + resend (T022b; FR-001,
 * FR-002) followed by confirmation and password setup (T022c; FR-003, FR-012).
 *
 * Register (api-contract.md №1): format validation; uniqueness across ALL
 * accounts — a taken username or email of any status → 409 naming the field(s)
 * without disclosing the holder's status (US1-4); the single exception is a
 * full (username+email) pair match on an INCOMPLETE registration → resumption:
 * previous tokens of the next step are annulled, a fresh letter of that step is
 * queued and no duplicate account is created (data-model.md §1). A new account
 * starts as `pending_email_confirmation` with an `email_verification` letter.
 *
 * Resend (api-contract.md №2): uniform 202 that never discloses account
 * existence or status — `pending_email_confirmation` → `email_verification_repeat`
 * letter; `awaiting_password` (incl. an expired setup link) → `password_setup`
 * letter with a `/set-password` link; `active`/unknown email → no letter. The
 * per-account PG cooldown (second tier after the `rl:email:resend` bucket,
 * research.md §11) → 429 with the remaining window.
 *
 * Confirm (api-contract.md №3, FR-003): conditional consumption of the
 * `email_verification` link → account `awaiting_password` + a fresh
 * `password_setup` token returned in the response; anything else about the
 * token (unknown/used/expired) is the uniform 400 above (FR-012).
 *
 * Set password (api-contract.md №4, FR-003): the FR-004 policy is checked via
 * [PasswordPolicyService] BEFORE the setup link is consumed — a rejected
 * password leaves the link usable for a retry; a compliant password is stored
 * as an Argon2id hash, the account becomes `active` and the link is consumed
 * atomically in the same transaction.
 *
 * Everything a letter depends on commits atomically: user + one-time token +
 * outbox row + auth event share one transaction (data-model.md §5), so a crash
 * between commit and dispatch can never lose the letter. Security contract
 * (FR-013, SC-005): events carry the user id, hashed IP and a secrets-free
 * details marker only.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // the four US1 endpoints live in one service (tasks.md T022b–T022c)
class RegistrationService(
    private val userRepository: UserRepository,
    private val tokenService: TokenService,
    private val passwordPolicyService: PasswordPolicyService,
    private val passwordEncoder: PasswordEncoder,
    private val outboxRepository: JdbcEmailOutboxRepository,
    private val emailTemplates: EmailTemplates,
    private val authEventRecorder: AuthEventRecorder,
    private val clock: Clock,
) {
    @Transactional
    fun register(
        username: String,
        email: String,
        clientIp: String,
        userAgent: String? = null,
    ) {
        validateRegisterRequest(username, email)

        val byUsername = userRepository.findByUsername(username)
        val byEmail = userRepository.findByEmail(email)
        val fullMatch = byUsername?.takeIf { user -> user.id == byEmail?.id }

        when {
            fullMatch?.registrationIncomplete == true ->
                resumeRegistration(fullMatch, clientIp, userAgent)

            byUsername == null && byEmail == null ->
                createPendingAccount(username, email, clientIp, userAgent)

            else ->
                throw RegistrationConflictException(
                    buildSet {
                        if (byUsername != null) add(USERNAME_FIELD)
                        if (byEmail != null) add(EMAIL_FIELD)
                    },
                )
        }
    }

    @Transactional
    fun resend(
        email: String,
        clientIp: String,
        userAgent: String? = null,
    ) {
        emailFormatErrors(email)?.let {
            throw RegistrationValidationException(mapOf(EMAIL_FIELD to it))
        }

        val user = userRepository.findByEmail(email)
        if (user == null || !user.registrationIncomplete) {
            // uniform 202 without a letter: resend must not disclose account
            // existence or status (api-contract.md №2)
            return
        }

        val letterType =
            when (user.status) {
                UserStatus.PENDING_EMAIL_CONFIRMATION -> EmailType.EMAIL_VERIFICATION_REPEAT
                UserStatus.AWAITING_PASSWORD -> EmailType.PASSWORD_SETUP
                UserStatus.ACTIVE -> return
            }

        outboxRepository.resendCooldownRemaining(user.id, letterType)?.let { remaining ->
            throw ResendCooldownException(remaining)
        }

        enqueueStepLetter(user, letterType)
        authEventRecorder.record(
            eventType = AuthEventType.REGISTER_REQUESTED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
            details = mapOf(DETAIL_RESEND to true),
        )
    }

    /**
     * Consumes the email_verification link and hands out the next step's open
     * password_setup token (US1-2). Token consumption, the status transition
     * and the fresh token share one transaction; every failure mode of the
     * link is the uniform [InvalidRegistrationTokenException].
     */
    @Transactional
    fun confirm(
        token: String,
        clientIp: String,
        userAgent: String? = null,
    ): ConfirmedRegistration {
        val verificationToken =
            tokenService.consume(token, TokenPurpose.EMAIL_VERIFICATION)
                ?: throw InvalidRegistrationTokenException()
        val user =
            userRepository
                .findById(verificationToken.userId)
                ?.takeIf { it.status == UserStatus.PENDING_EMAIL_CONFIRMATION }
                ?: throw InvalidRegistrationTokenException()

        userRepository.update(user.confirmEmail(clock.now()))
        val setupToken = tokenService.issue(user.id, TokenPurpose.PASSWORD_SETUP)
        authEventRecorder.record(
            eventType = AuthEventType.EMAIL_CONFIRMED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
        )
        return ConfirmedRegistration(
            setupToken = setupToken.openValue,
            setupTokenType = TokenPurpose.PASSWORD_SETUP.name.lowercase(),
            expiresInSec = tokenService.ttlFor(TokenPurpose.PASSWORD_SETUP).seconds,
        )
    }

    /**
     * Completes registration with a policy-compliant password (US1-3): the
     * account becomes `active` with an Argon2id hash. The FR-004 policy and the
     * confirmation match are checked before consumption, so a rejected attempt
     * leaves the setup link usable; only then is the link consumed atomically
     * (FR-012) and the whole step committed together.
     */
    @Transactional
    fun setPassword(
        setupToken: String,
        password: String,
        confirmPassword: String,
        clientIp: String,
        userAgent: String? = null,
    ) {
        val user =
            setupAccount(setupToken) ?: throw InvalidRegistrationTokenException()

        validateNewPassword(user, password, confirmPassword)

        if (tokenService.consume(setupToken, TokenPurpose.PASSWORD_SETUP) == null) {
            // lost the single-use race between findActive and the conditional UPDATE
            throw InvalidRegistrationTokenException()
        }
        userRepository.update(user.setPassword(passwordEncoder.encode(password), clock.now()))
        authEventRecorder.record(
            eventType = AuthEventType.PASSWORD_SET,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
        )
    }

    /**
     * The account an ACTIVE password_setup link points at — resolved WITHOUT
     * consuming the link so that a rejected password leaves it usable (US1-3).
     */
    private fun setupAccount(setupToken: String): User? {
        val token =
            tokenService.findActive(setupToken, TokenPurpose.PASSWORD_SETUP)
                ?: return null
        return userRepository
            .findById(token.userId)
            ?.takeIf { it.status == UserStatus.AWAITING_PASSWORD }
    }

    /**
     * FR-004 via [PasswordPolicyService] plus the confirmation match — both
     * BEFORE any token consumption (a 400 here keeps the setup link alive).
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

    private fun createPendingAccount(
        username: String,
        email: String,
        clientIp: String,
        userAgent: String?,
    ) {
        val user = User.register(UUID.randomUUID(), username, email, clock.now())
        try {
            userRepository.insert(user)
        } catch (
            @Suppress("SwallowedException")
            e: DataIntegrityViolationException,
        ) {
            // A concurrent registration won the check-then-insert race on a
            // unique lower(username)/lower(email) index. The transaction is
            // already aborted by PG, so the exact field cannot be re-resolved
            // here — report the pair; the thrown conflict rolls everything back
            // (SQL details stay in server logs, never in the API response).
            throw RegistrationConflictException(setOf(USERNAME_FIELD, EMAIL_FIELD))
        }
        enqueueStepLetter(user, EmailType.EMAIL_VERIFICATION)
        authEventRecorder.record(
            eventType = AuthEventType.REGISTER_REQUESTED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
        )
    }

    private fun resumeRegistration(
        user: User,
        clientIp: String,
        userAgent: String?,
    ) {
        // the letter of the registration step the incomplete account is stuck on
        val letterType =
            when (user.status) {
                UserStatus.PENDING_EMAIL_CONFIRMATION -> EmailType.EMAIL_VERIFICATION
                UserStatus.AWAITING_PASSWORD -> EmailType.PASSWORD_SETUP
                UserStatus.ACTIVE -> error("resumption is only defined for incomplete registrations (data-model.md §1)")
            }
        enqueueStepLetter(user, letterType)
        authEventRecorder.record(
            eventType = AuthEventType.REGISTER_REQUESTED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
            details = mapOf(DETAIL_RESUMED to true),
        )
    }

    /**
     * Issues the step's one-time token (annulling previous active ones of the
     * same purpose, one-live-link invariant) and queues its letter in the same
     * transaction (data-model.md §5).
     */
    private fun enqueueStepLetter(
        user: User,
        type: EmailType,
    ) {
        val purpose =
            when (type) {
                EmailType.EMAIL_VERIFICATION, EmailType.EMAIL_VERIFICATION_REPEAT -> TokenPurpose.EMAIL_VERIFICATION
                EmailType.PASSWORD_SETUP -> TokenPurpose.PASSWORD_SETUP
                EmailType.PASSWORD_RESET -> error("password_reset letters are US4 scope (T041)")
            }
        val issued = tokenService.issue(user.id, purpose)
        outboxRepository.insert(
            userId = user.id,
            recipientEmail = user.email,
            type = type,
            payload = emailTemplates.payload(type, user.username, issued.openValue),
        )
    }

    private fun validateRegisterRequest(
        username: String,
        email: String,
    ) {
        val errors = linkedMapOf<String, List<String>>()
        if (!USERNAME_REGEX.matches(username)) {
            errors[USERNAME_FIELD] = listOf(INVALID_FORMAT)
        }
        emailFormatErrors(email)?.let { errors[EMAIL_FIELD] = it }
        if (errors.isNotEmpty()) {
            throw RegistrationValidationException(errors)
        }
    }

    /** Format/length codes for the email field or `null` when it is well-formed. */
    private fun emailFormatErrors(email: String): List<String>? =
        when {
            email.length > EMAIL_MAX_LENGTH || !EMAIL_REGEX.matches(email) -> listOf(INVALID_FORMAT)
            else -> null
        }

    private companion object {
        const val USERNAME_FIELD = "username"
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
        const val DETAIL_RESUMED = "resumed"
        const val DETAIL_RESEND = "resend"

        /** RegisterRequest.username pattern (api-contract.md §4): 3–32, alphanumeric borders. */
        val USERNAME_REGEX = Regex("^[a-zA-Z0-9]([a-zA-Z0-9_.-]{1,30}[a-zA-Z0-9])$")

        /** RegisterRequest.email: local@dot-separated labels, TLD of 2+ letters (api-contract.md §4). */
        val EMAIL_REGEX =
            Regex(
                "^[A-Za-z0-9+_.-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$",
            )
    }
}
