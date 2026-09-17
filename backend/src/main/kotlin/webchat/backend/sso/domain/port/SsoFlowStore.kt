package webchat.backend.sso.domain.port

import java.time.Instant
import java.util.UUID

/**
 * Why the flow exists (data-model.md §5, §7): `login` starts anonymously from
 * the sign-in screen (US1/US2), `link` is started by an authenticated user
 * from the security settings (US3) and must remember the initiating user.
 */
enum class SsoFlowPurpose {
    LOGIN,
    LINK,
}

/**
 * Ephemeral OIDC flow context (data-model.md §5): stored under the Redis key
 * `sso:flow:<state>` as JSON
 * `{providerId, purpose, userId?, returnTo?, nonce, codeVerifier, createdAt}`
 * with the `sso.flow-ttl` (10 m) and consumed atomically on the callback.
 *
 * [nonce] and [codeVerifier] bind the flow to the browser that started it
 * (PKCE S256 + ID-token `nonce` check, spec Edge Cases); they are flow
 * secrets and never appear in logs or `auth_events.details` (SC-004, FR-011).
 * [returnTo] is kept for audit/debugging only — the SPA round-trips it via
 * `sessionStorage`, not through the handshake (contracts/sso-api.md §2).
 *
 * @param userId the initiating user; required for [SsoFlowPurpose.LINK],
 *   absent for [SsoFlowPurpose.LOGIN]
 */
data class SsoFlowContext(
    val providerId: String,
    val purpose: SsoFlowPurpose,
    val userId: UUID? = null,
    val returnTo: String? = null,
    val nonce: String,
    val codeVerifier: String,
    val createdAt: Instant,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(nonce.isNotBlank()) { "nonce must not be blank" }
        require(codeVerifier.isNotBlank()) { "codeVerifier must not be blank" }
        if (purpose == SsoFlowPurpose.LINK) {
            requireNotNull(userId) { "a link flow requires the initiating user" }
        }
    }
}

/**
 * Token-issuing context created by a successful callback (research.md §2,
 * data-model.md §5): stored under `sso:handshake:<sha256(code)>` — the raw
 * handshake code is never persisted — with the `sso.handshake-ttl` (2 m) and
 * exchanged atomically for a session on `POST /auth/sso/token`. Sessions and
 * tokens are created only after that exchange (FR-012).
 */
data class SsoHandshake(
    val userId: UUID,
    val identityId: UUID,
    val providerId: String,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
    }
}

/**
 * Port for the ephemeral single-use SSO state (data-model.md §5).
 *
 * Both structures live in Redis outside the process (constitution II —
 * stateless pods): `sso:flow:<state>` (TTL `sso.flow-ttl`, 10 m) and
 * `sso:handshake:<sha256(code)>` (TTL `sso.handshake-ttl`, 2 m). Writes
 * happen on authorize/callback; reads are atomic single-use take-outs
 * (`GETDEL`, research.md §2) so a replayed, forged or foreign-tab request
 * finds nothing and is rejected BEFORE any side effect — no session,
 * account or binding is ever created twice (SC-006, US5-2, FR-012).
 * Losing Redis never breaks PG durability: live flows simply expire and
 * the user retries the login (data-model.md §5).
 *
 * The Redis adapter is `webchat.backend.sso.repository.RedisSsoFlowStore`
 * (T013); TTLs come from [webchat.backend.config.SsoProperties].
 */
interface SsoFlowStore {
    /**
     * Persists the flow context under `sso:flow:<state>` (authorize step).
     * The state is opaque to the store; uniqueness/single-use is enforced by
     * the atomic [consumeFlow], not by this write.
     */
    fun saveFlow(
        state: String,
        context: SsoFlowContext,
    )

    /**
     * Atomically takes the flow context out (`GETDEL`, callback step):
     * `null` when the state is unknown, expired or already consumed —
     * the caller rejects the callback before any side effect (SC-006).
     */
    fun consumeFlow(state: String): SsoFlowContext?

    /**
     * Persists the handshake under `sso:handshake:<sha256(code)>` with the
     * raw code supplied by the caller (contracts/sso-api.md §3); only its
     * SHA-256 ever becomes a key.
     */
    fun saveHandshake(
        code: String,
        handshake: SsoHandshake,
    )

    /**
     * Atomically takes the handshake out (`GETDEL`, `POST /auth/sso/token`):
     * `null` for an unknown, expired or already exchanged code — the caller
     * answers 400 `invalid_code` (contracts/sso-api.md §4) without creating
     * a duplicate session (FR-012).
     */
    fun consumeHandshake(code: String): SsoHandshake?
}
