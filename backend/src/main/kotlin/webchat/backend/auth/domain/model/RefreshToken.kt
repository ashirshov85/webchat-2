package webchat.backend.auth.domain.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Lifecycle of a refresh-token generation: active → rotated | revoked (data-model.md §4). */
enum class RefreshTokenStatus {
    ACTIVE,
    ROTATED,
    REVOKED,
    ;

    fun canTransitionTo(next: RefreshTokenStatus): Boolean =
        when (this) {
            ACTIVE -> next == ROTATED || next == REVOKED
            ROTATED, REVOKED -> false
        }
}

/**
 * One generation of a session's refresh chain (data-model.md §4; FR-006).
 *
 * Only the SHA-256 hash of the 256-bit opaque value is persisted — the open
 * value exists solely on the client side (SC-005). Rotation is an atomic
 * conditional CAS UPDATE guarded by `status='active' AND expires_at > now()`
 * (rowcount 0 → reuse), linking the consumed generation to its replacement
 * via [replacedBy]; every new generation starts a fresh sliding TTL (3 days
 * by default, research.md §11).
 */
data class RefreshToken(
    val id: UUID,
    val sessionId: UUID,
    val tokenHash: String,
    val status: RefreshTokenStatus,
    val expiresAt: Instant,
    val replacedBy: UUID?,
    val createdAt: Instant,
) {
    init {
        require(tokenHash.length == SHA_256_HEX_LENGTH && tokenHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "tokenHash must be a lowercase hex-encoded SHA-256 value"
        }
    }

    fun isActive(at: Instant): Boolean = status == RefreshTokenStatus.ACTIVE && expiresAt.isAfter(at)

    fun rotateTo(replacementId: UUID): RefreshToken {
        require(status.canTransitionTo(RefreshTokenStatus.ROTATED)) {
            "only an active generation can be rotated (current: $status)"
        }
        return copy(status = RefreshTokenStatus.ROTATED, replacedBy = replacementId)
    }

    companion object {
        private const val SHA_256_HEX_LENGTH = 64

        fun issue(
            id: UUID,
            sessionId: UUID,
            tokenHash: String,
            ttl: Duration,
            at: Instant,
        ): RefreshToken =
            RefreshToken(
                id = id,
                sessionId = sessionId,
                tokenHash = tokenHash,
                status = RefreshTokenStatus.ACTIVE,
                expiresAt = at + ttl,
                replacedBy = null,
                createdAt = at,
            )
    }
}
