package webchat.backend.auth.security

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.springframework.stereotype.Component
import webchat.backend.auth.domain.port.Clock
import webchat.backend.config.AuthJwtProperties
import webchat.backend.config.AuthTokenProperties
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
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
 * Keys come from `AUTH_JWT_KEYS` — a comma-separated `kid=PEM` mapping (see
 * [AuthJwtProperties]); every entry must be a PKCS#8 `PRIVATE KEY` (signing)
 * or an X.509 `PUBLIC KEY` (verification-only, e.g. a kid kept alive during
 * rotation). `AUTH_JWT_ACTIVE_KID` picks the signing kid and MUST reference a
 * private entry: malformed material, a missing active kid or a public-only
 * active key refuse the application startup — no tokens can ever be minted
 * with placeholder keys.
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
    private val activeKid: String
    private val keysByKid: Map<String, ECKey>
    private val keyFactory: KeyFactory = KeyFactory.getInstance("EC")

    init {
        val parsedKeys = parseKeys(jwtProperties.keys)
        check(parsedKeys.isNotEmpty()) { "auth.jwt.keys must contain at least one kid=PEM entry" }
        val activeKey =
            checkNotNull(parsedKeys[jwtProperties.activeKid]) {
                "auth.jwt.active-kid <${jwtProperties.activeKid}> is absent from auth.jwt.keys ${parsedKeys.keys}"
            }
        check(activeKey.isPrivate) {
            "Active JWT key <${jwtProperties.activeKid}> has no private part and cannot sign"
        }
        activeKid = jwtProperties.activeKid
        keysByKid = parsedKeys
    }

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
                    .keyID(activeKid)
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
        jwt.sign(ECDSASigner(keysByKid.getValue(activeKid)))
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
        val key = keysByKid[header.keyID] ?: throw InvalidAccessTokenException("Unknown access token kid")
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

    private fun parseKeys(rawKeys: String): Map<String, ECKey> =
        rawKeys
            .split(ENTRY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val separatorIndex = entry.indexOf(KEY_VALUE_SEPARATOR)
                check(separatorIndex > 0) { "Malformed auth.jwt.keys entry <$entry>: expected kid=PEM" }
                val kid = entry.substring(0, separatorIndex).trim()
                kid to parseKey(kid, entry.substring(separatorIndex + KEY_VALUE_SEPARATOR.length))
            }.toMap()

    private fun parseKey(
        kid: String,
        encodedKey: String,
    ): ECKey {
        val pem = decodePem(encodedKey)
        val publicKey: ECPublicKey
        val privateKey: ECPrivateKey?
        when {
            pem.contains(PKCS8_BEGIN) -> {
                val key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pemBody(pem, PKCS8_BEGIN, PKCS8_END)))
                check(key is ECPrivateKey) { "JWT key <$kid> is not an EC private key" }
                privateKey = key
                publicKey = derivePublicKey(kid, key)
            }
            pem.contains(PUBLIC_BEGIN) -> {
                val key = keyFactory.generatePublic(X509EncodedKeySpec(pemBody(pem, PUBLIC_BEGIN, PUBLIC_END)))
                check(key is ECPublicKey) { "JWT key <$kid> is not an EC public key" }
                checkIsP256(kid, key.params)
                privateKey = null
                publicKey = key
            }
            else ->
                error(
                    "JWT key <$kid> must be a PKCS#8 EC private key (BEGIN PRIVATE KEY) " +
                        "or an X.509 public key (BEGIN PUBLIC KEY) PEM",
                )
        }
        val builder = ECKey.Builder(Curve.P_256, publicKey).keyID(kid)
        privateKey?.let { builder.privateKey(it) }
        return builder.build()
    }

    /**
     * A PKCS#8 EC private key carries no public point, so the public key is
     * recovered as d*G over the (already P-256-verified) named curve.
     */
    private fun derivePublicKey(
        kid: String,
        privateKey: ECPrivateKey,
    ): ECPublicKey {
        checkIsP256(kid, privateKey.params)
        val publicPoint = P256_PARAMETERS.g.multiply(privateKey.s).normalize()
        val publicSpec =
            ECPublicKeySpec(
                java.security.spec.ECPoint(
                    publicPoint.affineXCoord.toBigInteger(),
                    publicPoint.affineYCoord.toBigInteger(),
                ),
                privateKey.params,
            )
        return keyFactory.generatePublic(publicSpec) as ECPublicKey
    }

    private fun checkIsP256(
        kid: String,
        params: ECParameterSpec,
    ) {
        check(EC5Util.convertCurve(params.curve) == P256_PARAMETERS.curve) {
            "JWT key <$kid> must be P-256 (ES256)"
        }
    }

    private fun decodePem(encodedKey: String): String {
        val value = encodedKey.trim().replace(ESCAPED_NEWLINE, NEWLINE)
        if (value.contains(PEM_BEGIN_MARKER)) {
            return value
        }
        return runCatching { String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8) }
            .getOrElse { e ->
                throw IllegalStateException("JWT key value is neither PEM nor Base64-encoded PEM", e)
            }
    }

    private fun pemBody(
        pem: String,
        beginMarker: String,
        endMarker: String,
    ): ByteArray {
        val base64 =
            pem
                .substringAfter(beginMarker)
                .substringBefore(endMarker)
                .replace(NEWLINE, "")
                .replace(CARRIAGE_RETURN, "")
                .trim()
        return runCatching { Base64.getMimeDecoder().decode(base64) }
            .getOrElse { throw IllegalStateException("JWT key PEM body is not valid Base64") }
    }

    private companion object {
        const val CLAIM_SESSION_ID = "sid"
        const val CLAIM_TOKEN_TYPE = "typ"
        const val TOKEN_TYPE_ACCESS = "access"
        const val ENTRY_SEPARATOR = ","
        const val KEY_VALUE_SEPARATOR = "="
        const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
        const val PKCS8_END = "-----END PRIVATE KEY-----"
        const val PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----"
        const val PUBLIC_END = "-----END PUBLIC KEY-----"
        const val PEM_BEGIN_MARKER = "-----BEGIN"
        const val ESCAPED_NEWLINE = "\\n"
        const val NEWLINE = "\n"
        const val CARRIAGE_RETURN = "\r"

        val P256_PARAMETERS: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec("secp256r1")

        // verification-side tolerance for minor clock offsets across replicas (research.md §5)
        val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }
}
