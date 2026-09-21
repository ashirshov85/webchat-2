package webchat.backend.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import webchat.backend.config.ChatsProperties
import java.time.Duration
import java.util.UUID

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
 * The wire framing — the opening `retry: 3000` frame, `text/event-stream`
 * content type, Bearer gate — is asserted end-to-end by RealtimeSseIT
 * (T007), which stays RED until the Redis publisher lands (T019).
 */
class RealtimeControllerTest {
    private val registry = SseConnectionRegistry(TEST_PROPERTIES)

    private val controller = RealtimeController(registry)

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

    private fun tokenOf(userId: UUID): Jwt =
        Jwt
            .withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .build()

    private companion object {
        const val PROXY_BUFFERING_HEADER = "X-Accel-Buffering"

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")

        /** Slow enough that no heartbeat tick interferes with these unit checks. */
        val TEST_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = 4096, pageSize = 50),
                rateLimit = ChatsProperties.RateLimit(messagesPerMinute = 30),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofMinutes(10)),
            )
    }
}
