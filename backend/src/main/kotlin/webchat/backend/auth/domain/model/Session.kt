package webchat.backend.auth.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Session = chain of refresh generations of one login/device (data-model.md §3;
 * FR-006, FR-010, FR-011). Its [id] is the `sid` claim of the access tokens
 * issued within it; parallel sessions per user are allowed (edge spec).
 *
 * Lifecycle: active → revoked(reason) | compromised, guarded by
 * [SessionStatus.canTransitionTo]. Every revocation is paired by the domain
 * service with a Redis denylist marker `auth:denylist:sid:<sid>` (TTL ≤ the
 * remaining access-token lifetime) so already issued access tokens die
 * immediately (FR-006, FR-011).
 */
data class Session(
    val id: UUID,
    val userId: UUID,
    val status: SessionStatus,
    val createdAt: Instant,
    val lastRefreshedAt: Instant,
    val revokedAt: Instant?,
    val revokedReason: RevokedReason?,
) {
    val isActive: Boolean
        get() = status == SessionStatus.ACTIVE

    fun revoke(
        reason: RevokedReason,
        at: Instant,
    ): Session {
        require(status.canTransitionTo(SessionStatus.REVOKED)) {
            "only an active session can be revoked (current: $status)"
        }
        return copy(status = SessionStatus.REVOKED, revokedAt = at, revokedReason = reason)
    }

    /** Terminal state for refresh reuse: the whole chain is compromised (FR-006). */
    fun markCompromised(at: Instant): Session {
        require(status.canTransitionTo(SessionStatus.COMPROMISED)) {
            "only an active session can be marked compromised (current: $status)"
        }
        return copy(
            status = SessionStatus.COMPROMISED,
            revokedAt = at,
            revokedReason = RevokedReason.REFRESH_REUSE_DETECTED,
        )
    }

    companion object {
        /** Opens a fresh session at login: the `sid` root of the refresh chain. */
        fun start(
            id: UUID,
            userId: UUID,
            at: Instant,
        ): Session =
            Session(
                id = id,
                userId = userId,
                status = SessionStatus.ACTIVE,
                createdAt = at,
                lastRefreshedAt = at,
                revokedAt = null,
                revokedReason = null,
            )
    }
}
