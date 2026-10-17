package webchat.backend.conferences.livekit

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.port.ClientTokenRequest
import webchat.backend.config.ConferencesProperties
import java.time.Duration
import java.time.Instant

/**
 * T018 adapter contract — the №47/№55 client token of
 * livekit-integration.md §3, minted by the official SDK `AccessToken`:
 * the HS256 signature over the configured API pair and the EXACT claim
 * table (`sub`/`name`/`metadata` + the `video` grant object with the
 * §3 constants), the wire-name mapping of
 * [webchat.backend.conferences.domain.model.PublicationSource], and
 * the FR-008 rule that a client token NEVER carries an admin grant.
 * The mint is a pure local signature — no media plane needed (the IT
 * set T021 holds the end-to-end legs, livekit-integration.md §7).
 */
class LiveKitServerTokenMinterTest {
    private val minter = LiveKitServerTokenMinter(TEST_PROPERTIES)

    @Test
    fun `mint signs the full claim table with HS256 and the configured pair`() {
        val token =
            minter.mint(
                ClientTokenRequest(
                    roomName = ROOM_NAME,
                    identity = IDENTITY,
                    name = NAME,
                    metadata = METADATA,
                    grants = ParticipantRestrictions().grants(),
                    ttl = TTL,
                ),
            )

        val jwt = SignedJWT.parse(token)
        assertThat(jwt.header.algorithm).isEqualTo(JWSAlgorithm.HS256)
        assertThat(jwt.verify(MACVerifier(TEST_PROPERTIES.livekit.apiSecret)))
            .overridingErrorMessage("the client token must verify against the configured LIVEKIT_API_SECRET")
            .isTrue

        val claims = jwt.jwtClaimsSet
        assertThat(claims.issuer).isEqualTo(TEST_PROPERTIES.livekit.apiKey)
        assertThat(claims.subject).isEqualTo(IDENTITY)
        assertThat(claims.getStringClaim("name")).isEqualTo(NAME)
        assertThat(claims.getStringClaim("metadata")).isEqualTo(METADATA)
        assertThat(claims.expirationTime.toInstant())
            .isCloseTo(Instant.now() + TTL, within(Duration.ofSeconds(EXPIRY_TOLERANCE_SECONDS)))

        @Suppress("UNCHECKED_CAST")
        val video = claims.getClaim("video") as Map<String, Any>
        assertThat(video).containsOnly(
            java.util.Map.entry("roomJoin", true),
            java.util.Map.entry("room", ROOM_NAME),
            java.util.Map.entry("canSubscribe", true),
            java.util.Map.entry("canPublish", true),
            java.util.Map.entry("canPublishData", false),
            java.util.Map.entry("canPublishSources", ALL_WIRE_SOURCES),
        )
    }

    @Test
    fun `restricted sources drop from canPublishSources and speech blocks the whole publish side`() {
        val micAndScreenRestricted =
            minter.mint(
                request(
                    grants = ParticipantRestrictions(micRestricted = true, screenRestricted = true).grants(),
                ),
            )
        val speechRestricted =
            minter.mint(
                request(grants = ParticipantRestrictions(speechRestricted = true).grants()),
            )

        @Suppress("UNCHECKED_CAST")
        val micAndScreenVideo =
            SignedJWT.parse(micAndScreenRestricted).jwtClaimsSet.getClaim("video") as Map<String, Any>
        assertThat(micAndScreenVideo["canPublish"]).isEqualTo(true)
        assertThat(micAndScreenVideo["canPublishSources"]).isEqualTo(listOf("camera"))

        @Suppress("UNCHECKED_CAST")
        val speechVideo = SignedJWT.parse(speechRestricted).jwtClaimsSet.getClaim("video") as Map<String, Any>
        // Q4=A: «право слова» gates ANY publication — the sources are
        // then not consulted (data-model 009 §Сущность 2)
        assertThat(speechVideo["canPublish"]).isEqualTo(false)
        assertThat(speechVideo["canPublishSources"]).isEqualTo(emptyList<String>())
    }

    @Test
    fun `the video grant object never carries an admin grant`() {
        val token = minter.mint(request(grants = ParticipantRestrictions().grants()))

        @Suppress("UNCHECKED_CAST")
        val video = SignedJWT.parse(token).jwtClaimsSet.getClaim("video") as Map<String, Any>
        assertThat(video.keys).doesNotContain("roomAdmin", "roomCreate", "roomList", "roomRecord", "ingressAdmin")
    }

    @Test
    fun `a sub-second ttl never yields a born-expired token`() {
        val mintedAt = Instant.now()
        val token = minter.mint(request(grants = ParticipantRestrictions().grants(), ttl = Duration.ofMillis(500)))

        // the 1 s floor of the adapter: the auth0 signer floors exp
        // to the whole-second grid, so a bare 500 ms ttl could expire
        // within the mint itself
        val expiry =
            SignedJWT
                .parse(token)
                .jwtClaimsSet.expirationTime
                .toInstant()
        assertThat(expiry.isAfter(mintedAt)).isTrue
    }

    private fun request(
        grants: webchat.backend.conferences.domain.model.PublishGrants,
        ttl: Duration = TTL,
    ): ClientTokenRequest =
        ClientTokenRequest(
            roomName = ROOM_NAME,
            identity = IDENTITY,
            name = NAME,
            metadata = METADATA,
            grants = grants,
            ttl = ttl,
        )

    private companion object {
        const val API_KEY = "testkey"

        const val API_SECRET = "0123456789abcdef0123456789abcdef"

        const val ROOM_NAME = "c:00000000-0000-4000-8000-00000000000c"

        const val IDENTITY = "u:00000000-0000-4000-8000-00000000000a:00000000-0000-4000-8000-00000000000b"

        const val NAME = "alice"

        const val METADATA = """{"role":"participant","guest":false}"""

        val TTL: Duration = Duration.ofSeconds(900)

        val ALL_WIRE_SOURCES =
            listOf("microphone", "camera", "screen_share", "screen_share_audio")

        /** the SDK signs with wall-clock now; two seconds absorb the test's own latency */
        const val EXPIRY_TOLERANCE_SECONDS = 2L

        val TEST_PROPERTIES =
            ConferencesProperties(
                maxParticipants = 16,
                tokenTtl = Duration.ofSeconds(900),
                guestInviteTtl = Duration.ofHours(24),
                emptyRoomTimeout = Duration.ofSeconds(60),
                departureTimeout = Duration.ofSeconds(60),
                maxDuration = Duration.ofHours(4),
                warningBeforeEnd = Duration.ofMinutes(5),
                reconnectWindow = Duration.ofSeconds(120),
                guestNameMaxLength = 50,
                sweepInterval = Duration.ofSeconds(60),
                roomLostGrace = Duration.ofSeconds(120),
                livekit = ConferencesProperties.LiveKit("http://localhost:7880", API_KEY, API_SECRET),
            )
    }
}
