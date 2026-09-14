package webchat.backend.auth.domain.service

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * 401: the credentials do not verify — a SINGLE uniform failure for a wrong
 * password, an unknown identifier and any other mismatch alike (US2-3,
 * api-contract.md №5). The detail is always "Invalid credentials"; which leg
 * failed is never disclosed.
 */
class InvalidCredentialsException : RuntimeException("invalid identifier or password")

/**
 * 403: the identifier resolves to an account whose registration is not
 * completed (US1-5, api-contract.md №5 «завершите регистрацию») — no password
 * exists yet, so the reply guides the user to finish registration instead of
 * the uniform 401.
 */
class RegistrationIncompleteException : RuntimeException("registration is not completed")

/**
 * A successful login (api-contract.md №5): the authenticated account plus the
 * freshly issued session pair from [SessionService.startSession].
 */
data class LoginResult(
    val user: User,
    val tokenPair: IssuedTokenPair,
)

/**
 * Password login use case (T031; FR-005): identifier = username OR email.
 *
 * Identification (research.md §7): case-insensitive lookup by
 * lower(username), then lower(email) — the two forms cannot collide (the
 * username pattern disallows '@'), so a single account resolves at most.
 *
 * Timing alignment against account enumeration (US2-3, research.md §7): an
 * UNKNOWN identifier runs the very same Argon2 verification against a
 * fictitious hash minted with the SAME encoder — one Argon2 evaluation on
 * every code path, a fast reject nowhere. The outcome against the fictitious
 * hash is discarded; the reply stays the uniform 401.
 *
 * Only `active` accounts may open a session (US2-2): a pending or
 * awaiting-password account has no password yet, so ANY password yields 403
 * «завершите регистрацию» with no session and no token (checked before any
 * Argon2 work — the account's existence at this step is intentional guidance,
 * not a leak: the uniform 401 covers unknown identifiers only).
 *
 * Journal (FR-013, SC-005): login_success/login_failed carry the user id
 * (NULL when the identifier is unknown), the hashed IP and no secrets. The
 * failed event is written OUTSIDE any wrapping transaction and therefore
 * survives the thrown 401; the success path lets [SessionService.startSession]
 * commit the session atomically in its own transaction.
 */
@Service
class LoginService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val sessionService: SessionService,
    private val authEventRecorder: AuthEventRecorder,
) {
    /**
     * Verifies the credentials and opens a session for an `active` account
     * (FR-005). Every failure leg — unknown identifier, wrong password — is
     * the uniform [InvalidCredentialsException]; an incomplete registration
     * is the 403 [RegistrationIncompleteException].
     */
    @Suppress("ReturnCount") // the three exit legs ARE the contract: uniform 401 ×2 + 403 (tasks.md T031)
    fun login(
        identifier: String,
        password: String,
        clientIp: String,
        userAgent: String? = null,
    ): LoginResult {
        val user = userRepository.findByUsername(identifier) ?: userRepository.findByEmail(identifier)
        if (user == null) {
            // research.md §7: same code path, same Argon2 work, same message —
            // the discarded outcome keeps the timing indistinguishable
            passwordEncoder.matches(password, fictitiousPasswordHash)
            return failLogin(userId = null, clientIp = clientIp, userAgent = userAgent)
        }
        if (user.registrationIncomplete) {
            throw RegistrationIncompleteException()
        }

        val passwordHash = user.passwordHash ?: fictitiousPasswordHash
        if (!passwordEncoder.matches(password, passwordHash)) {
            return failLogin(userId = user.id, clientIp = clientIp, userAgent = userAgent)
        }

        val tokenPair = sessionService.startSession(user.id)
        authEventRecorder.record(
            eventType = AuthEventType.LOGIN_SUCCESS,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
        )
        return LoginResult(user = user, tokenPair = tokenPair)
    }

    /** The uniform 401 leg: journal the attempt, then the single [InvalidCredentialsException]. */
    private fun failLogin(
        userId: UUID?,
        clientIp: String,
        userAgent: String?,
    ): Nothing {
        authEventRecorder.record(
            eventType = AuthEventType.LOGIN_FAILED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = userId,
        )
        throw InvalidCredentialsException()
    }

    /** The fictitious Argon2 hash for unknown identifiers — same encoder, same work factor. */
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
    }
}
