package webchat.backend.auth.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import webchat.backend.auth.domain.port.Clock
import webchat.backend.config.AuthJwtProperties
import webchat.backend.config.AuthTokenProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * T028 contract for the ES256 access-token signer/verifier: kid header and
 * sub/sid/jti/iat/exp/typ:"access" claims with the configured TTL; uniform
 * verification failures (research.md §4) — unknown kid, tampered signature,
 * wrong token type, expiry with clock-skew tolerance; key rotation keeps a
 * non-active kid verifiable from its public part; malformed configuration
 * refuses startup.
 */
class JwtServiceTest {
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val sessionId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val initialNow: Instant = Instant.parse("2026-09-14T12:00:00Z")
    private var now: Instant = initialNow
    private val clock = Clock { now }
    private val tokenProperties =
        AuthTokenProperties(
            accessTtl = ACCESS_TTL,
            refreshTtl = Duration.ofDays(3),
            emailVerificationTtl = Duration.ofHours(24),
            passwordSetupTtl = Duration.ofHours(1),
            passwordResetTtl = Duration.ofHours(1),
        )

    @Test
    fun `created token carries kid header and access claims with configured ttl`() {
        val service = service(keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}")

        val token = service.createAccessToken(userId, sessionId)

        val jwt = SignedJWT.parse(token)
        assertThat(jwt.header.algorithm).isEqualTo(JWSAlgorithm.ES256)
        assertThat(jwt.header.keyID).isEqualTo(ACTIVE_KID)
        val claims = jwt.getJWTClaimsSet()
        assertThat(claims.subject).isEqualTo(userId.toString())
        assertThat(claims.getStringClaim(CLAIM_SID)).isEqualTo(sessionId.toString())
        assertThat(claims.getJWTID()).isNotEmpty
        assertThat(claims.issueTime.toInstant()).isEqualTo(initialNow)
        assertThat(claims.expirationTime.toInstant()).isEqualTo(initialNow.plus(ACCESS_TTL))
        assertThat(claims.getStringClaim(CLAIM_TYP)).isEqualTo("access")
    }

    @Test
    fun `verify returns validated claims for a freshly created token`() {
        val service = service(keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}")

        val verified = service.verifyAccessToken(service.createAccessToken(userId, sessionId))

        assertThat(verified.userId).isEqualTo(userId)
        assertThat(verified.sessionId).isEqualTo(sessionId)
        assertThat(verified.jti).isNotEmpty
        assertThat(verified.expiresAt).isEqualTo(initialNow.plus(ACCESS_TTL))
    }

    @Test
    fun `token signed by an unknown kid is rejected uniformly`() {
        val keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}"
        val foreignToken =
            service(keys = "foreign-1=${privatePemBase64(FOREIGN_KEY_PAIR)}", activeKid = "foreign-1")
                .createAccessToken(userId, sessionId)

        assertThatExceptionOfType(InvalidAccessTokenException::class.java)
            .isThrownBy { service(keys).verifyAccessToken(foreignToken) }
    }

    @Test
    fun `tampered signature is rejected`() {
        val service = service(keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}")
        val token = service.createAccessToken(userId, sessionId)
        val foreignSignature =
            service(keys = "$ACTIVE_KID=${privatePemBase64(FOREIGN_KEY_PAIR)}")
                .createAccessToken(userId, sessionId)
                .substringAfterLast(".")
        val tampered = "${token.substringBeforeLast(".")}.$foreignSignature"

        assertThatExceptionOfType(InvalidAccessTokenException::class.java)
            .isThrownBy { service.verifyAccessToken(tampered) }
    }

    @Test
    fun `garbage token is rejected uniformly`() {
        val service = service(keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}")

        listOf("", "not-a-jwt", "a.b.c").forEach { candidate ->
            assertThatExceptionOfType(InvalidAccessTokenException::class.java)
                .isThrownBy { service.verifyAccessToken(candidate) }
        }
    }

    @Test
    fun `token stays verifiable within clock skew and expires past it`() {
        val keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}"
        val service = service(keys)
        val token = service.createAccessToken(userId, sessionId)

        now = initialNow.plus(ACCESS_TTL).plus(Duration.ofSeconds(30))
        assertThat(service.verifyAccessToken(token).userId).isEqualTo(userId)

        now = initialNow.plus(ACCESS_TTL).plus(Duration.ofSeconds(61))
        assertThatExceptionOfType(InvalidAccessTokenException::class.java)
            .isThrownBy { service.verifyAccessToken(token) }
    }

    @Test
    fun `non-active kid stays verifiable from its public part after rotation`() {
        val previousService = service(keys = "previous=${privatePemBase64(PREVIOUS_KEY_PAIR)}", activeKid = "previous")
        val token = previousService.createAccessToken(userId, sessionId)

        val rotationKeys =
            "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}," +
                "previous=${publicPemBase64(PREVIOUS_KEY_PAIR.public)}"
        val rotated = service(keys = rotationKeys)

        assertThat(rotated.verifyAccessToken(token).sessionId).isEqualTo(sessionId)
    }

    @Test
    fun `raw PEM with escaped newlines and Base64 PEM forms are interchangeable`() {
        val rawPemService =
            service(keys = "$ACTIVE_KID=${privatePem(ACTIVE_KEY_PAIR.private).replace("\n", "\\n")}")
        val base64PemService = service(keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}")

        val token = rawPemService.createAccessToken(userId, sessionId)
        assertThat(base64PemService.verifyAccessToken(token).userId).isEqualTo(userId)
    }

    @Test
    fun `empty keys refuse startup`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { service(keys = " ") }
    }

    @Test
    fun `malformed key material refuses startup`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { service(keys = "$ACTIVE_KID=not-a-pem-at-all") }
    }

    @Test
    fun `active kid missing from keys refuses startup`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { service(keys = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}", activeKid = "missing") }
    }

    @Test
    fun `active kid without private part refuses startup`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { service(keys = "$ACTIVE_KID=${publicPemBase64(ACTIVE_KEY_PAIR.public)}") }
    }

    @Test
    fun `non P-256 key refuses startup`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { service(keys = "$ACTIVE_KID=${privatePemBase64(P384_KEY_PAIR)}") }
    }

    private fun service(
        keys: String,
        activeKid: String = ACTIVE_KID,
    ): JwtService = JwtService(AuthJwtProperties(keys, activeKid), tokenProperties, clock)

    private companion object {
        const val ACTIVE_KID = "active-1"
        const val CLAIM_SID = "sid"
        const val CLAIM_TYP = "typ"
        val ACCESS_TTL: Duration = Duration.ofMinutes(5)

        fun generateKeyPair(curve: String): KeyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec(curve)) }
                .generateKeyPair()

        fun pem(
            type: String,
            encoded: ByteArray,
        ): String {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded)
            return "-----BEGIN $type-----\n$body\n-----END $type-----"
        }

        fun privatePem(privateKey: PrivateKey): String = pem("PRIVATE KEY", privateKey.encoded)

        fun publicPem(publicKey: PublicKey): String = pem("PUBLIC KEY", publicKey.encoded)

        fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun privatePemBase64(keyPair: KeyPair): String = encodeBase64(privatePem(keyPair.private).toByteArray())

        fun publicPemBase64(publicKey: PublicKey): String = encodeBase64(publicPem(publicKey).toByteArray())

        val ACTIVE_KEY_PAIR: KeyPair by lazy { generateKeyPair("secp256r1") }
        val PREVIOUS_KEY_PAIR: KeyPair by lazy { generateKeyPair("secp256r1") }
        val FOREIGN_KEY_PAIR: KeyPair by lazy { generateKeyPair("secp256r1") }
        val P384_KEY_PAIR: KeyPair by lazy { generateKeyPair("secp384r1") }
    }
}
