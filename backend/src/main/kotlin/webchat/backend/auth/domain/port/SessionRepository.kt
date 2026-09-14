package webchat.backend.auth.domain.port

import webchat.backend.auth.domain.model.RefreshToken
import webchat.backend.auth.domain.model.RevokedReason
import webchat.backend.auth.domain.model.Session
import java.util.UUID

/**
 * Persistence port for [Session] (data-model.md §3; FR-006, FR-010, FR-011).
 *
 * Every status transition is a conditional single-row UPDATE evaluated against
 * the database clock: rowcount 0 → the transition does not apply and no side
 * effects occur.
 */
interface SessionRepository {
    fun insert(session: Session)

    fun findById(id: UUID): Session?

    /** Touches `last_refreshed_at` on refresh rotation (kept by the DB clock). */
    fun markRefreshed(id: UUID)

    /** Conditional revocation of a single active session — logout (FR-011). */
    fun revoke(
        id: UUID,
        reason: RevokedReason,
    ): Boolean

    /** Marks the session compromised on refresh reuse (FR-006); no-op for a non-active session. */
    fun markCompromised(id: UUID): Boolean

    /**
     * Revokes ALL active sessions of the user — password change (FR-010);
     * returns the ids of actually revoked sessions (each sid must be denylisted).
     */
    fun revokeAllForUser(
        userId: UUID,
        reason: RevokedReason,
    ): List<UUID>
}

/**
 * Persistence port for [RefreshToken] generations (data-model.md §4; FR-006).
 *
 * Rotation is the atomic CAS UPDATE guarding `status='active' AND
 * expires_at > now()`; a zero rowcount means the presented generation was
 * already rotated, revoked or expired — i.e. reuse (data-model.md §4).
 */
interface RefreshTokenRepository {
    fun insert(token: RefreshToken)

    fun findByTokenHash(tokenHash: String): RefreshToken?

    /**
     * Atomically rotates the generation:
     * `UPDATE ... SET status='rotated', replaced_by=:replacementId
     *   WHERE id = ? AND token_hash = ? AND status='active' AND expires_at > now()`;
     * rowcount 0 → REUSE of a consumed/dead generation.
     */
    fun rotate(
        id: UUID,
        tokenHash: String,
        replacementId: UUID,
    ): Boolean

    /** Revokes all still-active generations of the session (reuse/logout chain revocation). */
    fun revokeActiveForSession(sessionId: UUID)

    /** Revokes all still-active generations across all sessions of the user (password change, FR-010). */
    fun revokeActiveForUser(userId: UUID)
}
