package webchat.backend.auth.domain.service

import org.springframework.dao.DataIntegrityViolationException
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
 * Account creation and its email steps: register + resend (T022b; FR-001,
 * FR-002). Confirmation and password setup arrive with T022c.
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
 * Everything a letter depends on commits atomically: user + one-time token +
 * outbox row + auth event share one transaction (data-model.md §5), so a crash
 * between commit and dispatch can never lose the letter. Security contract
 * (FR-013, SC-005): events carry the user id, hashed IP and a secrets-free
 * details marker only.
 */
@Service
class RegistrationService(
    private val userRepository: UserRepository,
    private val tokenService: TokenService,
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

            else -> throw RegistrationConflictException(conflictFields(byUsername, byEmail))
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
        enqueueStepLetter(user, nextStepLetter(user.status))
        authEventRecorder.record(
            eventType = AuthEventType.REGISTER_REQUESTED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
            details = mapOf(DETAIL_RESUMED to true),
        )
    }

    /** The letter of the registration step the incomplete account is stuck on. */
    private fun nextStepLetter(status: UserStatus): EmailType =
        when (status) {
            UserStatus.PENDING_EMAIL_CONFIRMATION -> EmailType.EMAIL_VERIFICATION
            UserStatus.AWAITING_PASSWORD -> EmailType.PASSWORD_SETUP
            UserStatus.ACTIVE -> error("resumption is only defined for incomplete registrations (data-model.md §1)")
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
        val issued = tokenService.issue(user.id, type.tokenPurpose())
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

    private fun conflictFields(
        byUsername: User?,
        byEmail: User?,
    ): Set<String> =
        buildSet {
            if (byUsername != null) add(USERNAME_FIELD)
            if (byEmail != null) add(EMAIL_FIELD)
        }

    private fun EmailType.tokenPurpose(): TokenPurpose =
        when (this) {
            EmailType.EMAIL_VERIFICATION, EmailType.EMAIL_VERIFICATION_REPEAT -> TokenPurpose.EMAIL_VERIFICATION
            EmailType.PASSWORD_SETUP -> TokenPurpose.PASSWORD_SETUP
            EmailType.PASSWORD_RESET -> error("password_reset letters are US4 scope (T041)")
        }

    private companion object {
        const val USERNAME_FIELD = "username"
        const val EMAIL_FIELD = "email"
        const val INVALID_FORMAT = "invalid_format"
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
