package webchat.backend.auth.domain.service

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.auth.domain.model.RefreshToken
import webchat.backend.auth.domain.model.RevokedReason
import webchat.backend.auth.domain.model.Session
import webchat.backend.auth.domain.model.SessionAuthMethod
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.RefreshTokenRepository
import webchat.backend.auth.domain.port.SessionRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.auth.security.JwtService
import webchat.backend.config.AuthTokenProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * A session's token pair (api-contract.md №5–№6): an ES256 access token plus
 * the OPEN value of the fresh refresh generation — the latter exists only in
 * the endpoint response, persistence keeps the SHA-256 hash (SC-005).
 */
data class IssuedTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
)

/**
 * 401: the presented refresh token is unknown, expired, already rotated
 * (reuse) or its session is revoked/compromised — a single uniform failure
 * that never distinguishes the cases (api-contract.md №6, US2-4).
 */
class InvalidRefreshTokenException : RuntimeException("refresh token is invalid or expired")

/**
 * Session lifecycle: login issuance, CAS refresh rotation with reuse
 * detection, logout and password-change revocation (T030; FR-006, FR-010,
 * FR-011, FR-013).
 *
 * Rotation (data-model.md §4) is the atomic conditional UPDATE guarded by
 * token_hash + active + expiry: rowcount 1 → a new generation is inserted (a
 * fresh sliding [AuthTokenProperties.refreshTtl]) and a new pair is returned;
 * rowcount 0 → REUSE: the session turns `compromised`, the whole chain is
 * revoked, the sid is denylisted and the uniform 401 is thrown. Subsequent
 * presentations of dead generations hit an already non-active session and
 * stay event-silent (one compromise = one journal entry of each kind).
 *
 * Every revocation (logout, password change, reuse) pairs the PG transition
 * with a Redis denylist marker `auth:denylist:sid:<sid>` (data-model.md §7)
 * so already issued access tokens die immediately: access tokens are minted
 * only at login/rotation, hence no live access token of the session can
 * outlive the marker TTL = [AuthTokenProperties.accessTtl].
 *
 * Security contract (FR-013, SC-005): events carry the user id, hashed IP
 * and a secrets-free reason marker only — never passwords or token values.
 */
@Service
// one cohesive domain service over the session ports (tasks.md T030); the 003
// startSession overloads (research 003 §11) push it past the function threshold
@Suppress("LongParameterList", "TooManyFunctions")
class SessionService(
    private val sessionRepository: SessionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtService: JwtService,
    private val redisTemplate: StringRedisTemplate,
    private val authEventRecorder: AuthEventRecorder,
    private val authTokenProperties: AuthTokenProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    /**
     * Independent REQUIRES_NEW template for the reuse revocation: the
     * surrounding rotation transaction rolls back when the uniform 401 is
     * thrown, but the compromise verdict must SURVIVE that rollback (the
     * Redis marker and the journal entries are written after this
     * transaction commits).
     */
    private val reuseRevocationTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /**
     * Opens a fresh session at login (FR-006): session row + first refresh
     * generation + access token, atomically. The login_success event itself
     * belongs to the login use case (T031), not to the session lifecycle.
     *
     * Legacy single-argument entry point of 002: every login of 002 is a
     * password login, so it delegates with [SessionAuthMethod.PASSWORD]
     * (research 003 §11 — NULL auth_method stays reserved to legacy rows).
     */
    @Transactional
    fun startSession(userId: UUID): IssuedTokenPair = startSession(userId, SessionAuthMethod.PASSWORD)

    /**
     * Opens a fresh session marking HOW it was opened (research 003 §11):
     * password login or an SSO flow; [identityId] points at the external
     * identity the SSO session was created through (NULL for password).
     */
    @Transactional
    fun startSession(
        userId: UUID,
        authMethod: SessionAuthMethod,
        identityId: UUID? = null,
    ): IssuedTokenPair {
        val sessionId = UUID.randomUUID()
        sessionRepository.insert(Session.start(sessionId, userId, clock.now(), authMethod, identityId))
        return issueGeneration(sessionId, userId)
    }

    /**
     * Rotates the presented generation (FR-006): rowcount 0 of the CAS UPDATE
     * means the value was already consumed or is dead → reuse → the session
     * is compromised, the chain revoked, the sid denylisted, and the caller
     * sees the uniform [InvalidRefreshTokenException].
     */
    @Transactional
    fun refresh(
        refreshToken: String,
        clientIp: String,
        userAgent: String? = null,
    ): IssuedTokenPair {
        val (generation, session) = requireSession(refreshToken)
        val replacementId = UUID.randomUUID()
        val (openValue, replacementHash) = mintRefreshValue()

        if (!refreshTokenRepository.rotate(generation.id, generation.tokenHash, replacementId)) {
            onReuseDetected(session, clientIp, userAgent)
            throw InvalidRefreshTokenException()
        }

        refreshTokenRepository.insert(
            RefreshToken.issue(
                id = replacementId,
                sessionId = session.id,
                tokenHash = replacementHash,
                ttl = authTokenProperties.refreshTtl,
                at = clock.now(),
            ),
        )
        sessionRepository.markRefreshed(session.id)
        authEventRecorder.record(
            eventType = AuthEventType.REFRESH_ROTATED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = session.userId,
        )
        return IssuedTokenPair(
            accessToken = jwtService.createAccessToken(session.userId, session.id),
            refreshToken = openValue,
            expiresInSec = authTokenProperties.accessTtl.seconds,
        )
    }

    /**
     * Logout (FR-011): revokes the session the presented refresh generation
     * belongs to, kills its whole chain and denylists the sid. A parallel
     * session of the same user is a different sid and stays untouched (edge
     * spec); an already dead session is a no-op (idempotence).
     */
    @Transactional
    fun logout(
        refreshToken: String,
        clientIp: String,
        userAgent: String? = null,
    ) {
        val (_, session) = requireSession(refreshToken)

        if (session.isActive && sessionRepository.revoke(session.id, RevokedReason.LOGOUT)) {
            refreshTokenRepository.revokeActiveForSession(session.id)
            denylistSid(session.id)
            authEventRecorder.record(
                eventType = AuthEventType.LOGOUT,
                clientIp = clientIp,
                userAgent = userAgent,
                userId = session.userId,
            )
            authEventRecorder.record(
                eventType = AuthEventType.SESSION_REVOKED,
                clientIp = clientIp,
                userAgent = userAgent,
                userId = session.userId,
                details = mapOf(DETAIL_REASON to RevokedReason.LOGOUT.name.lowercase()),
            )
        }
    }

    /**
     * Password change (FR-010, US4-2): revokes EVERY active session of the
     * user, kills all remaining refresh generations and denylists each sid.
     */
    @Transactional
    fun revokeAllForUser(
        userId: UUID,
        clientIp: String,
        userAgent: String? = null,
    ) {
        val revokedSessionIds = sessionRepository.revokeAllForUser(userId, RevokedReason.PASSWORD_CHANGE)
        if (revokedSessionIds.isEmpty()) {
            return
        }
        refreshTokenRepository.revokeActiveForUser(userId)
        revokedSessionIds.forEach { sessionId ->
            denylistSid(sessionId)
            authEventRecorder.record(
                eventType = AuthEventType.SESSION_REVOKED,
                clientIp = clientIp,
                userAgent = userAgent,
                userId = userId,
                details = mapOf(DETAIL_REASON to RevokedReason.PASSWORD_CHANGE.name.lowercase()),
            )
        }
    }

    /** Resolves the generation + session pair of the presented value — any miss is the uniform 401. */
    private fun requireSession(refreshToken: String): Pair<RefreshToken, Session> {
        val generation =
            refreshTokenRepository.findByTokenHash(sha256Hex(refreshToken))
                ?: throw InvalidRefreshTokenException()
        val session =
            sessionRepository.findById(generation.sessionId)
                ?: throw InvalidRefreshTokenException()
        return generation to session
    }

    /**
     * Reuse handling (data-model.md §4): only a still-active session is a NEW
     * compromise — repeat presentations of dead generations stay silent, so
     * one incident yields exactly one journal entry of each kind. The PG
     * transition runs committed in its own transaction because the caller
     * subsequently throws the uniform 401 and rolls the rotation back.
     */
    private fun onReuseDetected(
        session: Session,
        clientIp: String,
        userAgent: String?,
    ) {
        if (!session.isActive) {
            return
        }
        val newlyCompromised =
            reuseRevocationTx.execute {
                if (!sessionRepository.markCompromised(session.id)) {
                    return@execute false
                }
                refreshTokenRepository.revokeActiveForSession(session.id)
                // The journal entries join THIS transaction: written through
                // JdbcTemplate they would otherwise participate in the outer
                // rotation transaction and be rolled back with the 401
                authEventRecorder.record(
                    eventType = AuthEventType.REFRESH_REUSE_DETECTED,
                    clientIp = clientIp,
                    userAgent = userAgent,
                    userId = session.userId,
                )
                authEventRecorder.record(
                    eventType = AuthEventType.SESSION_REVOKED,
                    clientIp = clientIp,
                    userAgent = userAgent,
                    userId = session.userId,
                    details = mapOf(DETAIL_REASON to RevokedReason.REFRESH_REUSE_DETECTED.name.lowercase()),
                )
                true
            } ?: false
        if (!newlyCompromised) {
            return
        }
        denylistSid(session.id)
    }

    /** Mints the first generation of a fresh session plus its access token. */
    private fun issueGeneration(
        sessionId: UUID,
        userId: UUID,
    ): IssuedTokenPair {
        val (openValue, tokenHash) = mintRefreshValue()
        refreshTokenRepository.insert(
            RefreshToken.issue(
                id = UUID.randomUUID(),
                sessionId = sessionId,
                tokenHash = tokenHash,
                ttl = authTokenProperties.refreshTtl,
                at = clock.now(),
            ),
        )
        return IssuedTokenPair(
            accessToken = jwtService.createAccessToken(userId, sessionId),
            refreshToken = openValue,
            expiresInSec = authTokenProperties.accessTtl.seconds,
        )
    }

    /** Generates a 256-bit opaque value and its lowercase-hex SHA-256 — the pair the client/DB see. */
    private fun mintRefreshValue(): Pair<String, String> {
        val bytes = ByteArray(OPEN_VALUE_BYTES)
        secureRandom.nextBytes(bytes)
        val openValue = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return openValue to sha256Hex(openValue)
    }

    /**
     * Redis denylist marker `auth:denylist:sid:<sid>` (data-model.md §7):
     * access tokens of this session are minted only at login/rotation, so no
     * live one can outlive the marker TTL = the access TTL.
     */
    private fun denylistSid(sessionId: UUID) {
        redisTemplate
            .opsForValue()
            .set(DENYLIST_KEY_PREFIX + sessionId, DENYLIST_MARKER, authTokenProperties.accessTtl)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .toHexString()

    private companion object {
        const val DENYLIST_KEY_PREFIX = "auth:denylist:sid:"
        const val DENYLIST_MARKER = "revoked"
        const val DETAIL_REASON = "reason"
        const val SHA_256 = "SHA-256"

        /** 256-bit opaque refresh value (research.md §4). */
        const val OPEN_VALUE_BYTES = 32

        val secureRandom = SecureRandom()
    }
}
