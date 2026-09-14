package webchat.backend.auth.security

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.util.UUID

/**
 * Resource-server [JwtDecoder] for ES256 access tokens (T032, research.md
 * §4–§5). Registered as the `JwtDecoder` bean in SecurityConfig and wired
 * into `oauth2ResourceServer().jwt()`, it is the single validation gate for
 * every authenticated request.
 *
 * Cryptographic validation is delegated to [JwtService.verifyAccessToken]
 * (T028): key selection by the `kid` header against `AUTH_JWT_KEYS`, ES256
 * signature, the `typ:"access"` claim and expiry with clock-skew tolerance.
 * On top of the stateless checks this decoder enforces the session denylist
 * (FR-011, data-model.md §7): an access token whose `sid` has a live Redis
 * marker `auth:denylist:sid:<sid>` (logout / password change / refresh reuse,
 * written by SessionService with TTL = access TTL) is rejected on the spot,
 * so revocation takes effect immediately — not at token expiry.
 *
 * Security contract (US3-1/US3-2): EVERY failure — blank, unparseable,
 * unknown `kid`, tampered signature, wrong type, expired or denylisted —
 * throws the SAME [JwtException] with no distinguishing details; the security
 * chain maps it to the single 401 `Not authenticated` problem+json of the
 * API contract (UnifiedAuthenticationEntryPoint, T009a).
 *
 * Availability tradeoff (research.md §4 — chat availability must not be tied
 * to Redis): when Redis itself is unreachable the denylist lookup degrades
 * gracefully to "not denylisted" (with a warn — never a blanket 401 that
 * would reject ALL authenticated traffic); the residual revocation window is
 * bounded by the access-token TTL, which denylist markers never outlive.
 * Explicit denylist hits still reject.
 */
class AuthJwtDecoder(
    private val jwtService: JwtService,
    private val redisTemplate: StringRedisTemplate,
) : JwtDecoder {
    override fun decode(token: String?): Jwt {
        if (token.isNullOrBlank()) {
            throw JwtException(UNIFORM_FAILURE)
        }
        val verified =
            try {
                jwtService.verifyAccessToken(token)
            } catch (_: InvalidAccessTokenException) {
                throw JwtException(UNIFORM_FAILURE)
            }
        if (isDenylisted(verified.sessionId)) {
            throw JwtException(UNIFORM_FAILURE)
        }
        return toJwt(token, verified)
    }

    /**
     * `true` only when a denylist marker exists for the session. A Redis
     * outage degrades to `false` (see class KDoc); the warn carries no token
     * or claim values (SC-005).
     */
    private fun isDenylisted(sessionId: UUID): Boolean =
        try {
            redisTemplate.hasKey(DENYLIST_KEY_PREFIX + sessionId) == true
        } catch (e: DataAccessException) {
            log.warn("Redis denylist lookup failed, failing open (bounded by the access-token TTL)", e)
            false
        }

    /**
     * Rebuilds the validated token as the Spring Security [Jwt] view from the
     * verified claims — `sub`/`sid`/`jti`/`typ`/`exp` — the shape the T033
     * logout and T038 `/users/me` handlers read via the authentication
     * principal.
     */
    private fun toJwt(
        token: String,
        verified: VerifiedAccessToken,
    ): Jwt =
        Jwt
            .withTokenValue(token)
            .header(HEADER_ALGORITHM, ALGORITHM_ES256)
            .claim(JwtClaimNames.SUB, verified.userId.toString())
            .claim(CLAIM_SESSION_ID, verified.sessionId.toString())
            .claim(JwtClaimNames.JTI, verified.jti)
            .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
            .expiresAt(verified.expiresAt)
            .build()

    private companion object {
        val log = LoggerFactory.getLogger(AuthJwtDecoder::class.java)

        /** One message for every rejection mode — no detail leakage (US3-1/2). */
        const val UNIFORM_FAILURE = "Not authenticated"

        /** data-model.md §7 — the same key SessionService denylists with. */
        const val DENYLIST_KEY_PREFIX = "auth:denylist:sid:"

        const val HEADER_ALGORITHM = "alg"
        const val ALGORITHM_ES256 = "ES256"
        const val CLAIM_SESSION_ID = "sid"
        const val CLAIM_TOKEN_TYPE = "typ"
        const val TOKEN_TYPE_ACCESS = "access"
    }
}
