package webchat.backend.auth.domain.service

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Password change from the profile (T018; FR-004–FR-010, research 008b §5,
 * data-model 008b §3.3): verifies the current password, enforces the 002
 * policy, stores the new Argon2id hash and hands the session revocation to
 * [SessionService.revokeOthersAndRotateCurrent] — every other device dies,
 * the current one stays signed in with a rotated pair.
 *
 * JIT accounts (SSO 003, `password_hash IS NULL`) never reach a server-side
 * "set" branch (FR-004): the current password is verified against the very
 * same fictitious Argon2 hash as an unknown identifier in [LoginService] —
 * one evaluation, the same timing — and the leg ALWAYS ends in the single
 * uniform 400 `currentPassword: invalid`; the account's password-less state
 * is never disclosed.
 *
 * Journal (FR-010, data-model 008b §1.2): `password_change_failed` with
 * `details={reason:"invalid_current_password"}` is written OUTSIDE any
 * wrapping transaction (LoginService precedent) so it survives the thrown
 * 400; `password_change_completed` with `details={reason:"profile"}` joins
 * the atomic mutation transaction together with the hash update, the
 * revocations and the rotation. Policy violations are plain 400 validation
 * and are NOT journaled. Events carry the user id, hashed IP and the reason
 * marker only — never passwords or token values (SC-004).
 *
 * A new password EQUAL to the old one does not branch: any compliant change
 * walks the full FR-008 path (clarify spec.md:17).
 */
@Service
// one cohesive domain service over the 002/008b ports (tasks.md T018)
@Suppress("LongParameterList")
class PasswordChangeService(
    private val userRepository: UserRepository,
    private val passwordPolicyService: PasswordPolicyService,
    private val passwordEncoder: PasswordEncoder,
    private val sessionService: SessionService,
    private val authEventRecorder: AuthEventRecorder,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    /**
     * REQUIRED template for the atomic mutation phase (data-model 008b §3.3
     * steps 5–7): hash update, other-session revocation, current-session
     * rotation and the completion journal entry commit — or roll back — as
     * one unit; [SessionService.revokeOthersAndRotateCurrent] joins it
     * through its own REQUIRED propagation.
     */
    private val mutationTx = TransactionTemplate(transactionManager)

    /**
     * The password-change use case: returns the rotated pair of the CURRENT
     * session (parity with `POST /auth/refresh`, contract 0.10.0 §1).
     *
     * The wrong-current and JIT legs are the single uniform
     * [RegistrationValidationException] 400 `{currentPassword: ["invalid"]}`;
     * a policy violation is the field-coded 400 of 002 on `newPassword`
     * (checked BEFORE any mutation — the hash, the sessions and the journal
     * stay untouched, exactly like reset-confirm).
     */
    fun changePassword(
        userId: UUID,
        currentSessionId: UUID,
        currentPassword: String,
        newPassword: String,
        clientIp: String,
        userAgent: String? = null,
    ): IssuedTokenPair {
        val user = userRepository.findById(userId) ?: throw InvalidRefreshTokenException()

        verifyCurrentPassword(user, currentPassword, clientIp, userAgent)

        // step 4: the FR-005 policy of 002 BEFORE any mutation (research §5)
        newPasswordCode(newPassword, user)?.let { code ->
            throw RegistrationValidationException(mapOf(NEW_PASSWORD_FIELD to listOf(code)))
        }

        // steps 5–7: hash replacement + revoke-others + rotate-current +
        // completion journal — one atomic transaction; a concurrently dead
        // current session (InvalidRefreshTokenException of T017) rolls the
        // hash update back with everything else
        return requireNotNull(
            mutationTx.execute {
                userRepository.update(user.changePassword(passwordEncoder.encode(newPassword), clock.now()))
                val rotatedPair =
                    sessionService.revokeOthersAndRotateCurrent(userId, currentSessionId, clientIp, userAgent)
                authEventRecorder.record(
                    eventType = AuthEventType.PASSWORD_CHANGE_COMPLETED,
                    clientIp = clientIp,
                    userAgent = userAgent,
                    userId = user.id,
                    details = mapOf(DETAIL_REASON to REASON_PROFILE),
                )
                rotatedPair
            },
        )
    }

    /**
     * data-model 008b §3.3 steps 2–3: exactly ONE Argon2 evaluation on every
     * path — a JIT account verifies against the fictitious hash (timing
     * parity with LoginService) and fails the uniform 400 even if the
     * submitted value were the fictitious preimage itself. The failure
     * journal is written OUTSIDE any wrapping transaction: the entry
     * auto-commits and survives the thrown 400 (LoginService precedent).
     */
    private fun verifyCurrentPassword(
        user: User,
        currentPassword: String,
        clientIp: String,
        userAgent: String?,
    ) {
        val storedHash = user.passwordHash
        val currentVerified = passwordEncoder.matches(currentPassword, storedHash ?: fictitiousPasswordHash)
        if (storedHash != null && currentVerified) {
            return
        }
        authEventRecorder.record(
            eventType = AuthEventType.PASSWORD_CHANGE_FAILED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
            details = mapOf(DETAIL_REASON to REASON_INVALID_CURRENT_PASSWORD),
        )
        throw RegistrationValidationException(mapOf(CURRENT_PASSWORD_FIELD to listOf(CODE_INVALID)))
    }

    /** The 002 field code of the first violated policy rule or `null` when the candidate complies. */
    private fun newPasswordCode(
        password: String,
        user: User,
    ): String? =
        passwordPolicyService.validate(password, user.username, user.email)?.let { violation ->
            when (violation) {
                PasswordPolicyViolation.BLANK -> CODE_BLANK
                PasswordPolicyViolation.TOO_SHORT -> CODE_TOO_SHORT
                PasswordPolicyViolation.TOO_LONG -> CODE_TOO_LONG
                PasswordPolicyViolation.TOO_COMMON -> CODE_TOO_COMMON
                PasswordPolicyViolation.SAME_AS_USERNAME -> CODE_SAME_AS_USERNAME
                PasswordPolicyViolation.SAME_AS_EMAIL -> CODE_SAME_AS_EMAIL
            }
        }

    /**
     * The fictitious Argon2 hash for the JIT leg — same encoder, same work
     * factor as a stored hash (LoginService precedent).
     */
    private val fictitiousPasswordHash: String = passwordEncoder.encode(fictitiousSecret())

    private fun fictitiousSecret(): String {
        val bytes = ByteArray(FICTITIOUS_SECRET_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        val secureRandom = SecureRandom()

        /** Random opener for the fictitious hash — long enough to never be a real password. */
        const val FICTITIOUS_SECRET_BYTES = 32

        /** Journal detail keys and reason markers (data-model 008b §1.2 — no secrets). */
        const val DETAIL_REASON = "reason"
        const val REASON_PROFILE = "profile"
        const val REASON_INVALID_CURRENT_PASSWORD = "invalid_current_password"

        /** Field names and codes of contract 0.10.0 §1 (codes parity with 002). */
        const val CURRENT_PASSWORD_FIELD = "currentPassword"
        const val NEW_PASSWORD_FIELD = "newPassword"
        const val CODE_INVALID = "invalid"
        const val CODE_BLANK = "blank"
        const val CODE_TOO_SHORT = "too_short"
        const val CODE_TOO_LONG = "too_long"
        const val CODE_TOO_COMMON = "too_common"
        const val CODE_SAME_AS_USERNAME = "same_as_username"
        const val CODE_SAME_AS_EMAIL = "same_as_email"
    }
}
