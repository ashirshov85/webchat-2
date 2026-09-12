package webchat.backend.auth.domain.port

import webchat.backend.auth.domain.model.OneTimeToken
import webchat.backend.auth.domain.model.TokenPurpose
import java.util.UUID

/**
 * Persistence port for [OneTimeToken] (data-model.md §2).
 *
 * Consumption is a conditional single-use UPDATE (FR-012): `false` means the link
 * was already used, expired or does not exist — no side effects either way.
 */
interface TokenRepository {
    fun insert(token: OneTimeToken)

    fun findByTokenHash(tokenHash: String): OneTimeToken?

    /**
     * Atomically consumes the token:
     * `UPDATE ... SET used_at = now() WHERE id = ? AND token_hash = ?
     *   AND used_at IS NULL AND expires_at > now()`; rowcount 0 → rejection.
     */
    fun consume(
        id: UUID,
        tokenHash: String,
    ): Boolean

    /** Annuls all still-active tokens of the given user+purpose (one live link invariant). */
    fun annulActiveFor(
        userId: UUID,
        purpose: TokenPurpose,
    )
}
