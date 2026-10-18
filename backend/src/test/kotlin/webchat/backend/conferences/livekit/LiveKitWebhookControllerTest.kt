package webchat.backend.conferences.livekit

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.config.ConferencesProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Date

/**
 * T053 unit regression (tasks.md US5): the LiveKit webhook intake of
 * livekit-integration.md §5 pinned against the REAL
 * [io.livekit.server.WebhookReceiver] of the SDK — no Spring context,
 * only the dedup store is faked (the story IT T057 holds the
 * security `permitAll` surface, the per-IP bucket and the
 * end-to-end legs):
 *
 *  * **authenticity** — the three rejection modes of the receiver
 *    (missing header, wrong signing key, sha256 body mismatch) fold
 *    into the single uniform 401, and a rejected delivery touches
 *    NEITHER the dedup store NOR the metrics;
 *  * **200-always** — a VALID signature answers 200 in EVERY other
 *    outcome: the accepted event, the `event.id` duplicate, the
 *    Redis-unavailable fail-open, even a signed but unparseable body
 *    (an authentic delivery the plane must not retry);
 *  * **dedup** — the marker is `SET NX EX` under the `lkw:dedup:`
 *    family with the 5-minute window, shared-state semantics a
 *    per-process cache could not give (constitution II); a duplicate
 *    counts `webchat_conference_webhooks_processed_total{result=
 *    duplicate}` with the wire event type folded to the bounded tag
 *    space (`other` for the unhandled rest);
 *  * **identity-less no-op** — a signature-verified event without an
 *    `id` is the 200/no-op of the 200-always rule: nothing to dedup,
 *    nothing to count.
 *
 * The webhook JWTs are minted exactly like the plane does (Nimbus
 * HS256, `iss` = the API key, the `sha256` claim over the exact body
 * bytes — the same pair [LiveKitWebhookController] is built from),
 * and the Authorization header carries the RAW serialized token the
 * receiver expects.
 */
@Suppress("LargeClass") // one leg per §5 rule, the GuestJoinControllerTest convention
class LiveKitWebhookControllerTest {
    private val redisTemplate = Mockito.mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST") // the opsForValue() face of the mocked template, one shared fake
    private val valueOperations = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>

    private val meterRegistry = SimpleMeterRegistry()

    private val controller =
        LiveKitWebhookController(
            properties = TEST_PROPERTIES,
            metrics = ConferenceMetrics(meterRegistry, Mockito.mock(JdbcTemplate::class.java)),
            redisTemplate = redisTemplate,
        )

    @BeforeEach
    fun stubTheDedupStore() {
        Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
        Mockito
            .`when`(
                valueOperations.setIfAbsent(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any(Duration::class.java),
                ),
            ).thenReturn(true)
    }

    @Test
    fun `a webhook without the Authorization header answers the uniform 401`() {
        val response = controller.receive(ROOM_FINISHED_BODY.toByteArray(StandardCharsets.UTF_8), null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        Mockito.verifyNoInteractions(valueOperations)
        assertThat(webhookCounters()).isEmpty()
    }

    @Test
    fun `a webhook signed with a foreign key answers the uniform 401`() {
        val response =
            controller.receive(
                ROOM_FINISHED_BODY.toByteArray(StandardCharsets.UTF_8),
                signedAuthHeader(ROOM_FINISHED_BODY, secret = "another-planes-secret-0123456789abcdef"),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        Mockito.verifyNoInteractions(valueOperations)
        assertThat(webhookCounters()).isEmpty()
    }

    @Test
    fun `a tampered body under a valid token answers the uniform 401`() {
        val response =
            controller.receive(
                ROOM_FINISHED_BODY
                    .replace(EVENT_ID, "ffffffff-0000-0000-0000-000000000000")
                    .toByteArray(StandardCharsets.UTF_8),
                signedAuthHeader(ROOM_FINISHED_BODY),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        Mockito.verifyNoInteractions(valueOperations)
        assertThat(webhookCounters()).isEmpty()
    }

    @Test
    fun `a validly signed event answers 200 and marks its id seen for the dedup window`() {
        val response =
            controller.receive(
                ROOM_FINISHED_BODY.toByteArray(StandardCharsets.UTF_8),
                signedAuthHeader(ROOM_FINISHED_BODY),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        Mockito
            .verify(valueOperations)
            .setIfAbsent(
                Mockito.eq("${LiveKitWebhookController.DEDUP_KEY_PREFIX}$EVENT_ID"),
                Mockito.eq("1"),
                Mockito.eq(LiveKitWebhookController.DEDUP_WINDOW),
            )
        assertThat(webhookCounters()).isEmpty()
    }

    @Test
    fun `a duplicate delivery answers 200 and counts the duplicate metric of the event type`() {
        Mockito
            .`when`(
                valueOperations.setIfAbsent(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any(Duration::class.java),
                ),
            ).thenReturn(false)

        val response =
            controller.receive(
                ROOM_FINISHED_BODY.toByteArray(StandardCharsets.UTF_8),
                signedAuthHeader(ROOM_FINISHED_BODY),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(duplicateCount(event = "room_finished")).isEqualTo(1.0)
    }

    @Test
    fun `a duplicate of an unhandled event type folds to the other tag`() {
        Mockito
            .`when`(
                valueOperations.setIfAbsent(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any(Duration::class.java),
                ),
            ).thenReturn(false)
        val body = """{"event":"track_published","id":"$EVENT_ID"}"""

        val response = controller.receive(body.toByteArray(StandardCharsets.UTF_8), signedAuthHeader(body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(duplicateCount(event = "other")).isEqualTo(1.0)
    }

    @Test
    fun `an unavailable dedup store fails open with 200`() {
        Mockito
            .`when`(
                valueOperations.setIfAbsent(
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.any(Duration::class.java),
                ),
            ).thenThrow(IllegalStateException("redis is down"))

        val response =
            controller.receive(
                ROOM_FINISHED_BODY.toByteArray(StandardCharsets.UTF_8),
                signedAuthHeader(ROOM_FINISHED_BODY),
            )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(webhookCounters()).isEmpty()
    }

    @Test
    fun `a validly signed but unparseable body answers 200 without processing`() {
        val body = "this-is-not-a-webhook-payload"

        val response = controller.receive(body.toByteArray(StandardCharsets.UTF_8), signedAuthHeader(body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        Mockito.verifyNoInteractions(valueOperations)
        assertThat(webhookCounters()).isEmpty()
    }

    @Test
    fun `an event without an id answers 200 without touching the dedup store`() {
        val body = """{"event":"room_started"}"""

        val response = controller.receive(body.toByteArray(StandardCharsets.UTF_8), signedAuthHeader(body))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        Mockito.verifyNoInteractions(valueOperations)
        assertThat(webhookCounters()).isEmpty()
    }

    /** The minted webhook Authorization: the RAW serialized JWT the receiver verifies (no Bearer scheme). */
    private fun signedAuthHeader(
        body: String,
        secret: String = API_SECRET,
    ): String {
        val sha256 =
            Base64
                .getEncoder()
                .encodeToString(
                    MessageDigest
                        .getInstance(SHA_256)
                        .digest(body.toByteArray(StandardCharsets.UTF_8)),
                )
        val jwt =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                JWTClaimsSet
                    .Builder()
                    .issuer(API_KEY)
                    .issueTime(Date())
                    .claim(SHA_256_CLAIM, sha256)
                    .build(),
            )
        jwt.sign(MACSigner(secret))
        return jwt.serialize()
    }

    private fun webhookCounters(): List<io.micrometer.core.instrument.Counter> =
        meterRegistry
            .find(WEBHOOKS_PROCESSED_METRIC)
            .counters()
            .toList()

    private fun duplicateCount(event: String): Double =
        meterRegistry
            .find(WEBHOOKS_PROCESSED_METRIC)
            .tag(EVENT_TAG, event)
            .tag(RESULT_TAG, "duplicate")
            .counter()
            ?.count()
            ?: 0.0

    private companion object {
        /** ≥ 32 bytes: the Nimbus MACSigner floor (the receiver itself accepts any length). */
        const val API_SECRET = "t053-livekit-webhook-signing-secret-0123456789"

        const val API_KEY = "devkey"

        const val EVENT_ID = "11111111-2222-3333-4444-555555555555"

        val ROOM_FINISHED_BODY =
            """{"event":"room_finished","id":"$EVENT_ID","room":{"name":"c:00000000-0000-0000-0000-0000000000aa"}}"""

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
                rateLimit = ConferencesProperties.RateLimit("30/1m", "10/1m", "60/1m"),
            )

        const val SHA_256 = "SHA-256"

        /** the claim name of the receiver's body-hash check (WebhookReceiver.receive) */
        const val SHA_256_CLAIM = "sha256"

        const val WEBHOOKS_PROCESSED_METRIC = "webchat_conference_webhooks_processed_total"

        const val EVENT_TAG = "event"

        const val RESULT_TAG = "result"
    }
}
