package webchat.backend.auth.domain.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Single-use link token issued via email (data-model.md §2; FR-002, FR-010, FR-012).
 *
 * Only the SHA-256 hash of the 256-bit opaque value is persisted; the open value
 * exists solely in the email link. Consumption is conditional (used_at/expires_at
 * guards, FR-012); issuing a new token of the same purpose annuls previous active
 * ones for the same user (one live link per user+purpose).
 */
data class OneTimeToken(
    val id: UUID,
    val userId: UUID,
    val purpose: TokenPurpose,
    val tokenHash: String,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val createdAt: Instant,
) {
    init {
        require(tokenHash.length == SHA_256_HEX_LENGTH && tokenHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "tokenHash must be a lowercase hex-encoded SHA-256 value"
        }
    }

    val isConsumed: Boolean
        get() = usedAt != null

    fun isActive(at: Instant): Boolean = usedAt == null && expiresAt.isAfter(at)

    companion object {
        private const val SHA_256_HEX_LENGTH = 64

        fun issue(
            id: UUID,
            userId: UUID,
            purpose: TokenPurpose,
            tokenHash: String,
            ttl: Duration,
            at: Instant,
        ): OneTimeToken =
            OneTimeToken(
                id = id,
                userId = userId,
                purpose = purpose,
                tokenHash = tokenHash,
                expiresAt = at + ttl,
                usedAt = null,
                createdAt = at,
            )
    }
}
