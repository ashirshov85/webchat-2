package webchat.backend.auth.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import webchat.backend.auth.domain.port.Clock
import webchat.backend.config.AuthJwtProperties
import webchat.backend.config.AuthTokenProperties
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/** Validated access-token claims extracted by [JwtService.verifyAccessToken]. */
data class VerifiedAccessToken(
    val userId: UUID,
    val sessionId: UUID,
    val jti: String,
    val expiresAt: Instant,
)

/**
 * Uniform verification failure carrying no distinguishing details, so the
 * resource-server layer (T032) can map every failure mode to the single 401
 * `Not authenticated` of the API contract (US3-1/US3-2).
 */
class InvalidAccessTokenException(
    message: String,
) : RuntimeException(message)

/**
 * ES256 (P-256) signer and verifier for access tokens (T028, research.md §4).
 *
 * Keys come from `AUTH_JWT_KEYS` via the shared [Es256KeyRing] (kid→PEM
 * rotation, constitution V) — the same ring signs the guest-invite nonces of
 * 009-video-conferences (T044); the `typ` claim below keeps the two token
 * classes from being replayed against each other.
 *
 * Token shape: JWS header `alg:ES256`, `kid`, `typ:JWT`; claims `sub` (user
 * UUID), `sid` (session UUID), `jti`, `iat`, `exp` (= `iat` +
 * `auth.token.access-ttl`) and `typ:"access"`.
 *
 * Security contract: verification never distinguishes error causes — every
 * failure is an [InvalidAccessTokenException]; the sid denylist check stays
 * with the caller (T032, FR-007).
 */
@Component
class JwtService(
    jwtProperties: AuthJwtProperties,
    private val authTokenProperties: AuthTokenProperties,
    private val clock: Clock,
) {
    private val keyRing = Es256KeyRing(jwtProperties)

    /** Signs a fresh access token for [userId] and [sessionId]. */
    fun createAccessToken(
        userId: UUID,
        sessionId: UUID,
    ): String {
        val issuedAt = clock.now()
        val expiresAt = issuedAt.plus(authTokenProperties.accessTtl)
        val jwt =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.ES256)
                    .type(JOSEObjectType.JWT)
                    .keyID(keyRing.activeKid)
                    .build(),
                JWTClaimsSet
                    .Builder()
                    .subject(userId.toString())
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .claim(CLAIM_SESSION_ID, sessionId.toString())
                    .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                    .build(),
            )
        jwt.sign(ECDSASigner(keyRing.signingKey()))
        return jwt.serialize()
    }

    /**
     * Verifies the ES256 signature (kid lookup against the configured keys),
     * the `typ:"access"` claim and the expiry (with [CLOCK_SKEW] tolerance)
     * and returns the validated claims. The sid denylist check is owned by
     * the caller (T032).
     *
     * @throws InvalidAccessTokenException for any unparseable, foreign-key,
     * tampered, mistyped or expired token — uniformly, without details.
     */
    @Suppress("ThrowsCount") // straight-line validator: every failure throws the SAME uniform exception (US3-1/2)
    fun verifyAccessToken(token: String): VerifiedAccessToken {
        val jwt =
            runCatching { SignedJWT.parse(token) }
                .getOrElse { throw InvalidAccessTokenException("Unparseable access token") }
        val header = jwt.header
        if (header.algorithm != JWSAlgorithm.ES256) {
            throw InvalidAccessTokenException("Unexpected access token algorithm")
        }
        val key = keyRing.verificationKey(header.keyID) ?: throw InvalidAccessTokenException("Unknown access token kid")
        val signatureValid =
            runCatching { jwt.verify(ECDSAVerifier(key.toPublicJWK())) }.getOrDefault(false)
        if (!signatureValid) {
            throw InvalidAccessTokenException("Access token signature verification failed")
        }
        val claims = jwt.getJWTClaimsSet()
        if (claims.getStringClaim(CLAIM_TOKEN_TYPE) != TOKEN_TYPE_ACCESS) {
            throw InvalidAccessTokenException("Unexpected access token type")
        }
        val expiresAt = claims.expirationTime?.toInstant() ?: throw InvalidAccessTokenException("Missing exp claim")
        if (!clock.now().isBefore(expiresAt.plus(CLOCK_SKEW))) {
            throw InvalidAccessTokenException("Access token expired")
        }
        return VerifiedAccessToken(
            userId = parseUuidClaim(claims.subject),
            sessionId = parseUuidClaim(claims.getStringClaim(CLAIM_SESSION_ID)),
            jti = claims.getJWTID() ?: throw InvalidAccessTokenException("Missing jti claim"),
            expiresAt = expiresAt,
        )
    }

    private fun parseUuidClaim(value: String?): UUID =
        value?.let { candidate -> runCatching { UUID.fromString(candidate) }.getOrNull() }
            ?: throw InvalidAccessTokenException("Malformed UUID claim")

    private companion object {
        const val CLAIM_SESSION_ID = "sid"
        const val CLAIM_TOKEN_TYPE = "typ"
        const val TOKEN_TYPE_ACCESS = "access"

        // verification-side tolerance for minor clock offsets across replicas (research.md §5)
        val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }
}
