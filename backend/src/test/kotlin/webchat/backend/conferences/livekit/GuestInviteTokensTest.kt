package webchat.backend.conferences.livekit

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import webchat.backend.auth.security.JwtService
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.config.AuthJwtProperties
import webchat.backend.config.AuthTokenProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * T044 contract for the guest-invite nonce signer/verifier (009 research
 * §6): the ES256 nonce carries `sub=inviteId`, `cid=conferenceId`,
 * `typ:"guest-invite"`, `iat=createdAt`, `exp=expiresAt` (= created + TTL)
 * over the shared AUTH_JWT_KEYS kid ring; verification rejects every
 * forgery mode UNIFORMLY, without distinguishing causes (api-contract §1
 * edge) — including an access token of the same key ring replayed as a
 * nonce. A legitimately signed but expired nonce still resolves to its
 * claims: the expired/revoked/ended lifecycle reasons belong to the №54/№55
 * status layer (T046), which is why the row-check horizon and the JWT `exp`
 * are kept equal by minting from the [GuestInvite] row itself.
 */
class GuestInviteTokensTest {
    private val inviteId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val conferenceId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val createdBy = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val createdAt: Instant = Instant.parse("2026-10-17T12:00:00Z")

    private fun invite(ttl: Duration = GUEST_INVITE_TTL): GuestInvite =
        GuestInvite.issue(
            id = inviteId,
            conferenceId = conferenceId,
            createdBy = createdBy,
            at = createdAt,
            ttl = ttl,
        )

    private fun tokens(
        keys: String = "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}",
        activeKid: String = ACTIVE_KID,
    ): GuestInviteTokens = GuestInviteTokens(AuthJwtProperties(keys, activeKid))

    @Test
    fun `minted nonce carries kid header and guest-invite claims with row horizons`() {
        val nonce = tokens().mint(invite())

        val jwt = SignedJWT.parse(nonce)
        assertThat(jwt.header.algorithm).isEqualTo(JWSAlgorithm.ES256)
        assertThat(jwt.header.keyID).isEqualTo(ACTIVE_KID)
        val claims = jwt.getJWTClaimsSet()
        assertThat(claims.subject).isEqualTo(inviteId.toString())
        assertThat(claims.getStringClaim(CLAIM_CID)).isEqualTo(conferenceId.toString())
        assertThat(claims.getStringClaim(CLAIM_TYP)).isEqualTo("guest-invite")
        assertThat(claims.issueTime.toInstant()).isEqualTo(createdAt)
        assertThat(claims.expirationTime.toInstant()).isEqualTo(createdAt.plus(GUEST_INVITE_TTL))
    }

    @Test
    fun `verify round-trips a freshly minted nonce`() {
        val verified = tokens().verify(tokens().mint(invite()))

        assertThat(verified.inviteId).isEqualTo(inviteId)
        assertThat(verified.conferenceId).isEqualTo(conferenceId)
        assertThat(verified.expiresAt).isEqualTo(createdAt.plus(GUEST_INVITE_TTL))
    }

    @Test
    fun `nonce signed by an unknown kid is rejected uniformly`() {
        val foreignNonce =
            tokens(keys = "foreign-1=${privatePemBase64(FOREIGN_KEY_PAIR)}", activeKid = "foreign-1")
                .mint(invite())

        assertThatExceptionOfType(InvalidGuestInviteTokenException::class.java)
            .isThrownBy { tokens().verify(foreignNonce) }
    }

    @Test
    fun `tampered signature is rejected uniformly`() {
        val nonce = tokens().mint(invite())
        val foreignSignature =
            tokens(keys = "foreign-1=${privatePemBase64(FOREIGN_KEY_PAIR)}", activeKid = "foreign-1")
                .mint(invite())
                .substringAfterLast(".")
        val tampered = "${nonce.substringBeforeLast(".")}.$foreignSignature"

        assertThatExceptionOfType(InvalidGuestInviteTokenException::class.java)
            .isThrownBy { tokens().verify(tampered) }
    }

    @Test
    fun `garbage nonce is rejected uniformly`() {
        val service = tokens()

        listOf("", "not-a-jwt", "a.b.c").forEach { candidate ->
            assertThatExceptionOfType(InvalidGuestInviteTokenException::class.java)
                .isThrownBy { service.verify(candidate) }
        }
    }

    @Test
    fun `an access token of the same key ring is rejected as a nonce (token confusion)`() {
        val accessToken = jwtService().createAccessToken(createdBy, inviteId)

        assertThatExceptionOfType(InvalidGuestInviteTokenException::class.java)
            .isThrownBy { tokens().verify(accessToken) }
    }

    @Test
    fun `well-signed nonce with a malformed uuid claim is rejected uniformly`() {
        val malformed =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.ES256)
                    .type(JOSEObjectType.JWT)
                    .keyID(ACTIVE_KID)
                    .build(),
                JWTClaimsSet
                    .Builder()
                    .subject("definitely-not-a-uuid")
                    .claim(CLAIM_CID, conferenceId.toString())
                    .claim(CLAIM_TYP, "guest-invite")
                    .issueTime(java.util.Date.from(createdAt))
                    .expirationTime(java.util.Date.from(createdAt.plus(GUEST_INVITE_TTL)))
                    .build(),
            ).apply { sign(ECDSASigner(activeEcKey())) }
                .serialize()

        assertThatExceptionOfType(InvalidGuestInviteTokenException::class.java)
            .isThrownBy { tokens().verify(malformed) }
    }

    @Test
    fun `an authentic expired nonce still verifies - expiry is the status row's decision`() {
        val expired = invite(ttl = Duration.ofMinutes(1))
        val nonce = tokens().mint(expired)

        val verified = tokens().verify(nonce)

        // №54 must still answer {valid:false, reason:"expired"} and №55 -
        // 409 invite_expired from the row whose expires_at equals JWT exp,
        // so the nonce layer resolves claims instead of throwing (T046)
        assertThat(verified.inviteId).isEqualTo(inviteId)
        assertThat(verified.expiresAt).isEqualTo(createdAt.plus(Duration.ofMinutes(1)))
    }

    @Test
    fun `previous kid stays verifiable from its public part after rotation`() {
        val previousNonce =
            tokens(keys = "previous=${privatePemBase64(PREVIOUS_KEY_PAIR)}", activeKid = "previous")
                .mint(invite())

        val rotated =
            tokens(
                keys =
                    "$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}," +
                        "previous=${publicPemBase64(PREVIOUS_KEY_PAIR.public)}",
            )

        assertThat(rotated.verify(previousNonce).inviteId).isEqualTo(inviteId)
    }

    @Test
    fun `malformed key material refuses startup`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { tokens(keys = "$ACTIVE_KID=not-a-pem-at-all") }
    }

    @Test
    fun `active kid without private part refuses startup`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { tokens(keys = "$ACTIVE_KID=${publicPemBase64(ACTIVE_KEY_PAIR.public)}") }
    }

    private fun jwtService(): JwtService =
        JwtService(
            AuthJwtProperties("$ACTIVE_KID=${privatePemBase64(ACTIVE_KEY_PAIR)}", ACTIVE_KID),
            AuthTokenProperties(
                accessTtl = Duration.ofMinutes(5),
                refreshTtl = Duration.ofDays(3),
                emailVerificationTtl = Duration.ofHours(24),
                passwordSetupTtl = Duration.ofHours(1),
                passwordResetTtl = Duration.ofHours(1),
            ),
            webchat.backend.auth.domain.port
                .Clock { createdAt },
        )

    private fun activeEcKey(): ECKey =
        ECKey
            .Builder(Curve.P_256, ACTIVE_KEY_PAIR.public as ECPublicKey)
            .keyID(ACTIVE_KID)
            .privateKey(ACTIVE_KEY_PAIR.private as ECPrivateKey)
            .build()

    private companion object {
        const val ACTIVE_KID = "active-1"
        const val CLAIM_CID = "cid"
        const val CLAIM_TYP = "typ"
        val GUEST_INVITE_TTL: Duration = Duration.ofHours(24)

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

        fun privatePemBase64(keyPair: KeyPair): String =
            Base64.getEncoder().encodeToString(privatePem(keyPair.private).toByteArray())

        fun publicPemBase64(publicKey: PublicKey): String =
            Base64.getEncoder().encodeToString(publicPem(publicKey).toByteArray())

        val ACTIVE_KEY_PAIR: KeyPair by lazy { generateKeyPair("secp256r1") }
        val PREVIOUS_KEY_PAIR: KeyPair by lazy { generateKeyPair("secp256r1") }
        val FOREIGN_KEY_PAIR: KeyPair by lazy { generateKeyPair("secp256r1") }
    }
}
