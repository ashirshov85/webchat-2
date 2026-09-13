package webchat.backend.auth.domain.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import webchat.backend.auth.domain.model.OneTimeToken
import webchat.backend.auth.domain.model.TokenPurpose
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.TokenRepository
import webchat.backend.config.AuthTokenProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * A freshly issued one-time token: the open 256-bit base64url value exists only
 * in the email link / endpoint response, while persistence sees the SHA-256
 * hash alone (SC-005).
 */
data class IssuedOneTimeToken(
    val token: OneTimeToken,
    val openValue: String,
)

/**
 * One-time token lifecycle (data-model.md §2; FR-002, FR-010, FR-012).
 *
 * Generation: 256-bit opaque value from [SecureRandom], base64url-encoded
 * without padding (~43 chars). Storage: lowercase hex SHA-256 of the open
 * value — the open value itself is never persisted or logged. TTLs come solely
 * from `auth.token.*-ttl` via [AuthTokenProperties] (mandatory keys, fail-fast
 * startup; [TokenPurpose] stays TTL-free). Issuing annuls previous active
 * tokens of the same user+purpose (one live link invariant, data-model.md §2).
 */
@Service
class TokenService(
    private val tokenRepository: TokenRepository,
    private val clock: Clock,
    private val properties: AuthTokenProperties,
) {
    init {
        TokenPurpose.entries.forEach { purpose ->
            require(ttlFor(purpose).isPositive) {
                "auth.token TTL for ${purpose.name.lowercase()} must be positive"
            }
        }
    }

    /**
     * Annuls previous active tokens of the same user+purpose and persists a new
     * single-use token; runs inside the caller's transaction when one exists.
     */
    @Transactional
    fun issue(
        userId: UUID,
        purpose: TokenPurpose,
    ): IssuedOneTimeToken {
        tokenRepository.annulActiveFor(userId, purpose)
        val openValue = generateOpenValue()
        val token =
            OneTimeToken.issue(
                id = UUID.randomUUID(),
                userId = userId,
                purpose = purpose,
                tokenHash = hash(openValue),
                ttl = ttlFor(purpose),
                at = clock.now(),
            )
        tokenRepository.insert(token)
        return IssuedOneTimeToken(token, openValue)
    }

    /**
     * Resolves [openValue] to its stored token WITHOUT consuming it: only an
     * unused, unexpired token of [expectedPurpose] matches (FR-012 guards).
     * Lets callers run pre-checks (e.g. the password policy) before the atomic
     * consumption — a failed pre-check leaves the link usable.
     */
    fun findActive(
        openValue: String,
        expectedPurpose: TokenPurpose,
    ): OneTimeToken? {
        val token = tokenRepository.findByTokenHash(hash(openValue))
        return when {
            token == null || token.purpose != expectedPurpose -> null
            else -> token.takeIf { it.isActive(clock.now()) }
        }
    }

    /**
     * Resolves [openValue] to its stored token and conditionally consumes it
     * (FR-012): only an unused, unexpired token of [expectedPurpose] is
     * accepted; `null` is returned otherwise with no side effects.
     */
    fun consume(
        openValue: String,
        expectedPurpose: TokenPurpose,
    ): OneTimeToken? {
        val token = tokenRepository.findByTokenHash(hash(openValue))
        return when {
            token == null || token.purpose != expectedPurpose -> null
            tokenRepository.consume(token.id, token.tokenHash) -> token
            else -> null
        }
    }

    /** Single source of one-time link TTLs (research.md §11). */
    fun ttlFor(purpose: TokenPurpose): Duration =
        when (purpose) {
            TokenPurpose.EMAIL_VERIFICATION -> properties.emailVerificationTtl
            TokenPurpose.PASSWORD_SETUP -> properties.passwordSetupTtl
            TokenPurpose.PASSWORD_RESET -> properties.passwordResetTtl
        }

    private fun generateOpenValue(): String {
        val bytes = ByteArray(OPEN_VALUE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(openValue: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(openValue.toByteArray(StandardCharsets.UTF_8))
            .toHexString()

    private companion object {
        const val SHA_256 = "SHA-256"

        /** 256-bit opaque value (research.md §4). */
        const val OPEN_VALUE_BYTES = 32

        val secureRandom = SecureRandom()
    }
}
