package webchat.backend.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import webchat.backend.config.ChatsProperties
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit-level verification of the T018 HTTP-adapter mandates
 * (api-contract.md №18, realtime-channel.md §1): the stream answers
 * `200` with the proxy switch `X-Accel-Buffering: no` (no ingress
 * aggregation — the ingress route itself is T020), never times out
 * server-side (liveness belongs to the `:ka` heartbeat; the client
 * reconnect is the normal mode, FR-009), and lands in
 * [SseConnectionRegistry] under the token `sub` — one entry per
 * device/session (US2-6).
 *
 * 007 (T013) extends the same adapter with the presence registration
 * seam: every open hands the minted connectionId to the
 * [PresenceConnectionLifecycle] hooks together with the token `sub`/`sid`
 * pair (data-model 007 §1.1 — the per-session logout of T029 rides the
 * sid), every device mints its OWN connectionId (multi-device, SC-005),
 * and a MISSING or THROWING hook never fails the №18 stream itself
 * (presence is best-effort here: №37 404 → reconnect heals a lost
 * registration, presence-api.md §2).
 *
 * The wire framing — the opening `retry: 3000` frame followed by
 * `event: connected` with the `ConnectedEvent` payload, `text/event-stream`
 * content type, Bearer gate — and the close/error → unregister leg are
 * asserted end-to-end by RealtimeSseIT (T007) and PresenceIT (T011):
 * the emitter runs its completion callbacks only inside a real async
 * request, which a bare unit context cannot start.
 */
class RealtimeControllerTest {
    private val registry = SseConnectionRegistry(TEST_PROPERTIES)

    private val presenceHooks = RecordingPresenceLifecycle()

    private val controller = RealtimeController(registry, providerOf(presenceHooks))

    @AfterEach
    fun tearDown() {
        registry.shutdown()
    }

    @Test
    fun `stream answers 200 with X-Accel-Buffering no and no server timeout`() {
        val response = controller.streamUserEvents(tokenOf(ALICE))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst(PROXY_BUFFERING_HEADER))
            .overridingErrorMessage("the SSE route must disable proxy buffering (research.md 004 §1)")
            .isEqualTo("no")
        assertThat(response.body!!.timeout)
            .overridingErrorMessage("the emitter must never time out server-side — heartbeats own liveness")
            .isZero()
    }

    @Test
    fun `each call registers one session of the token owner only`() {
        controller.streamUserEvents(tokenOf(ALICE))
        controller.streamUserEvents(tokenOf(ALICE))

        assertThat(registry.connectionCount(ALICE))
            .overridingErrorMessage("every device/session opens its own stream (US2-6)")
            .isEqualTo(2)
        assertThat(registry.connectionCount(BOB)).isZero()
    }

    /** T013: the open hook gets one registration per stream — sub, sid and a per-connection minted id. */
    @Test
    fun `every open registers its own presence connection carrying sub and sid`() {
        controller.streamUserEvents(tokenOf(ALICE))
        controller.streamUserEvents(tokenOf(ALICE))

        assertThat(presenceHooks.opened)
            .overridingErrorMessage("every SSE open must hand its registration to presence (data-model 007 §1.1)")
            .hasSize(2)
        assertThat(presenceHooks.opened.map { it.userId })
            .overridingErrorMessage("the presence registration belongs to the token owner (sub)")
            .containsOnly(ALICE)
        assertThat(presenceHooks.opened.map { it.sessionId })
            .overridingErrorMessage("the presence registration must carry the token session sid (T029 logout)")
            .containsOnly(SESSION_ID)
        assertThat(presenceHooks.opened.map { it.connectionId }.toSet())
            .overridingErrorMessage("every device/session connection mints its OWN connectionId (SC-005 multi-device)")
            .hasSize(2)
    }

    /** T013: until presence lands (T014) its absence must not change the №18 channel at all. */
    @Test
    fun `stream opens unchanged when no presence lifecycle bean exists`() {
        val bareController = RealtimeController(registry, providerOf(null))

        val response = bareController.streamUserEvents(tokenOf(ALICE))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!.timeout).isZero()
    }

    /** T013: a degraded presence store must never fail the stream open itself. */
    @Test
    fun `a throwing presence hook never fails the stream`() {
        val failingController = RealtimeController(registry, providerOf(THROWING_PRESENCE))

        val response = failingController.streamUserEvents(tokenOf(ALICE))

        assertThat(response.statusCode)
            .overridingErrorMessage("the №18 stream must open even while the presence store is degraded")
            .isEqualTo(HttpStatus.OK)
    }

    private fun tokenOf(userId: UUID): Jwt =
        Jwt
            .withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .claim(SESSION_ID_CLAIM, SESSION_ID.toString())
            .build()

    /** Captures the T013 open-hook calls; the close leg needs a real async request (see the class KDoc). */
    private class RecordingPresenceLifecycle : PresenceConnectionLifecycle {
        val opened = CopyOnWriteArrayList<OpenedRegistration>()

        override fun onConnectionOpened(
            userId: UUID,
            sessionId: UUID,
            connectionId: UUID,
        ) {
            opened += OpenedRegistration(userId, sessionId, connectionId)
        }

        override fun onConnectionClosed(
            userId: UUID,
            connectionId: UUID,
        ) = Unit // asserted end-to-end by PresenceIT (T011), not reachable in a bare unit context
    }

    private data class OpenedRegistration(
        val userId: UUID,
        val sessionId: UUID,
        val connectionId: UUID,
    )

    private fun providerOf(hook: PresenceConnectionLifecycle?): ObjectProvider<PresenceConnectionLifecycle> =
        object : ObjectProvider<PresenceConnectionLifecycle> {
            override fun getObject(): PresenceConnectionLifecycle =
                hook ?: throw NoSuchBeanDefinitionException(PresenceConnectionLifecycle::class.java)
        }

    private companion object {
        const val PROXY_BUFFERING_HEADER = "X-Accel-Buffering"
        const val SESSION_ID_CLAIM = "sid"

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val SESSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

        /** The degraded-store stand-in: every hook throws, the channel must not care. */
        val THROWING_PRESENCE =
            object : PresenceConnectionLifecycle {
                override fun onConnectionOpened(
                    userId: UUID,
                    sessionId: UUID,
                    connectionId: UUID,
                ): Unit = error("presence store is down")

                override fun onConnectionClosed(
                    userId: UUID,
                    connectionId: UUID,
                ) = Unit
            }

        /** Slow enough that no heartbeat tick interferes with these unit checks. */
        val TEST_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = 4096, pageSize = 50),
                rateLimit = ChatsProperties.RateLimit(messagesPerMinute = 30, searchesPerMinute = 30),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofMinutes(10)),
            )
    }
}
