package webchat.backend.realtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import webchat.backend.config.ChatsProperties
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit-level verification of the T018 connection-table mandates
 * (realtime-channel.md §1, research.md 004 §1–§2): a MULTI-SESSION
 * emitter set per user with first/last-connection notification (the
 * subscribe hooks of the T019 dynamic Pub/Sub subscription),
 * [SseConnectionRegistry.dispatch] reaching every live session of the
 * target user only, dead-socket self-cleaning that spares siblings, the
 * `:ka` heartbeat at the configured `chats.realtime.heartbeat` cadence,
 * and graceful shutdown.
 *
 * [RecordingEmitter] captures the EXACT wire text of every frame — the
 * chunks Spring's [SseEmitter.SseEventBuilder] produces are concatenated
 * in order, which is precisely the WHATWG stream the client parses. The
 * end-to-end framing (the opening `retry: 3000`, wire headers, contract
 * payloads) is RealtimeSseIT (T007), which stays RED until the Redis
 * publisher lands (T019).
 */
class SseConnectionRegistryTest {
    private val listener = RecordingListener()

    private val registry =
        SseConnectionRegistry(TEST_PROPERTIES).apply {
            addListener(listener)
        }

    @AfterEach
    fun tearDown() {
        registry.shutdown()
    }

    @Test
    fun `dispatch reaches every session of the user and only the target user`() {
        val aliceFirst = RecordingEmitter()
        val aliceSecond = RecordingEmitter()
        val bob = RecordingEmitter()
        registry.register(ALICE, aliceFirst)
        registry.register(ALICE, aliceSecond)
        registry.register(BOB, bob)

        registry.dispatch(ALICE, MESSAGE_CREATED, PAYLOAD)

        assertThat(aliceFirst.recorded)
            .overridingErrorMessage("every session of the target user must receive the frame")
            .contains("event:$MESSAGE_CREATED\ndata:$PAYLOAD\n\n")
        assertThat(aliceSecond.recorded).contains("event:$MESSAGE_CREATED\ndata:$PAYLOAD\n\n")
        assertThat(bob.recorded)
            .overridingErrorMessage("other users' streams must stay untouched")
            .isEmpty()
        assertThat(listener.firstConnections).containsExactly(ALICE, BOB)
        assertThat(listener.lastClosed).isEmpty()
    }

    @Test
    fun `removing one session keeps the user live and the rest receiving`() {
        val first = RecordingEmitter()
        val second = RecordingEmitter()
        registry.register(ALICE, first)
        registry.register(ALICE, second)

        registry.unregister(ALICE, first)
        registry.dispatch(ALICE, MESSAGE_CREATED, PAYLOAD)

        assertThat(registry.connectionCount(ALICE)).isEqualTo(1)
        assertThat(second.recorded).contains("event:$MESSAGE_CREATED\ndata:$PAYLOAD\n\n")
        assertThat(first.recorded)
            .overridingErrorMessage("an unregistered session must not receive further frames")
            .isEmpty()
        assertThat(listener.lastClosed).isEmpty()
    }

    @Test
    fun `removing the last session closes the user entry and re-open notifies again`() {
        val only = RecordingEmitter()
        registry.register(ALICE, only)

        registry.unregister(ALICE, only)
        registry.dispatch(ALICE, MESSAGE_CREATED, PAYLOAD)

        assertThat(registry.connectionCount(ALICE)).isZero()
        assertThat(only.recorded).isEmpty()
        assertThat(listener.lastClosed).containsExactly(ALICE)

        registry.register(ALICE, RecordingEmitter())
        assertThat(listener.firstConnections).containsExactly(ALICE, ALICE)
    }

    @Test
    fun `unregistering an unknown pair is a silent no-op`() {
        registry.unregister(UNKNOWN_USER, RecordingEmitter())

        assertThat(listener.lastClosed).isEmpty()
        assertThat(registry.connectionCount(UNKNOWN_USER)).isZero()
    }

    @Test
    fun `a failed write drops only the dead session and completes it`() {
        val dead = FailingEmitter()
        val healthy = RecordingEmitter()
        registry.register(ALICE, dead)
        registry.register(ALICE, healthy)

        registry.dispatch(ALICE, MESSAGE_CREATED, PAYLOAD)

        assertThat(registry.connectionCount(ALICE)).isEqualTo(1)
        assertThat(dead.completed).isTrue
        assertThat(healthy.recorded).contains("event:$MESSAGE_CREATED\ndata:$PAYLOAD\n\n")
        assertThat(listener.lastClosed)
            .overridingErrorMessage("the user stays live while the sibling session remains")
            .isEmpty()
    }

    @Test
    fun `heartbeat writes the ka comment frame at the configured cadence`() {
        val fastRegistry = SseConnectionRegistry(FAST_PROPERTIES)
        try {
            val session = RecordingEmitter()
            fastRegistry.register(ALICE, session)

            val deadline = System.nanoTime() + HEARTBEAT_WAIT.toNanos()
            while (session.heartbeatCount() < HEARTBEAT_TICKS_TO_OBSERVE && System.nanoTime() < deadline) {
                Thread.sleep(HEARTBEAT_POLL_MILLIS)
            }

            assertThat(session.heartbeatCount())
                .overridingErrorMessage(
                    "the keepalive comment must repeat at the heartbeat cadence; " +
                        "recorded wire: <${session.recorded.take(WIRE_SNAPSHOT_LENGTH)}>",
                ).isGreaterThanOrEqualTo(HEARTBEAT_TICKS_TO_OBSERVE)
        } finally {
            fastRegistry.shutdown()
        }
    }

    @Test
    fun `shutdown completes every open session`() {
        val first = RecordingEmitter()
        val second = RecordingEmitter()
        registry.register(ALICE, first)
        registry.register(ALICE, second)

        registry.shutdown()

        assertThat(first.completed).isTrue
        assertThat(second.completed).isTrue
        assertThat(registry.connectionCount(ALICE)).isZero()
    }

    /**
     * Captures the wire text: the registry sends every frame as ONE
     * pre-rendered WHATWG string through the verbatim `Set` overload of
     * [ResponseBodyEmitter.send], so `recorded` is exactly the stream a
     * client would parse; [completed] mirrors the complete() cleanup calls.
     */
    private open class RecordingEmitter : SseEmitter(0L) {
        private val wire = StringBuffer()

        val recorded: String
            get() = wire.toString()

        @Volatile
        var completed = false
            private set

        fun heartbeatCount(): Int = wire.toString().split(HEARTBEAT_FRAME).size - 1

        override fun send(items: Set<ResponseBodyEmitter.DataWithMediaType>) {
            items.forEach { wire.append(it.data.toString()) }
        }

        override fun complete() {
            completed = true
        }

        private companion object {
            const val HEARTBEAT_FRAME = ":ka\n\n"
        }
    }

    /** A socket that is already gone: every write throws (IOException, the transport's signal). */
    private class FailingEmitter : SseEmitter(0L) {
        @Volatile
        var completed = false
            private set

        override fun send(items: Set<ResponseBodyEmitter.DataWithMediaType>): Unit = throw IOException("broken pipe")

        override fun complete() {
            completed = true
        }
    }

    private class RecordingListener : SseConnectionRegistry.ConnectionListener {
        val firstConnections = CopyOnWriteArrayList<UUID>()
        val lastClosed = CopyOnWriteArrayList<UUID>()

        override fun onFirstConnection(userId: UUID) {
            firstConnections += userId
        }

        override fun onLastConnectionClosed(userId: UUID) {
            lastClosed += userId
        }
    }

    private companion object {
        const val MESSAGE_CREATED = "message.created"
        const val PAYLOAD = """{"chatId":"7dc5f5c0-0000-4a10-8b00-000000000001","message":{"seq":128}}"""
        const val HEARTBEAT_TICKS_TO_OBSERVE = 2
        const val HEARTBEAT_POLL_MILLIS = 5L
        const val WIRE_SNAPSHOT_LENGTH = 400
        val HEARTBEAT_WAIT: Duration = Duration.ofSeconds(5)

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val UNKNOWN_USER = UUID.fromString("00000000-0000-0000-0000-0000000000ee")

        /** Slow enough that no heartbeat tick interferes with the dispatch tests. */
        val TEST_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = 4096, pageSize = 50),
                rateLimit = ChatsProperties.RateLimit(messagesPerMinute = 30, searchesPerMinute = 30),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofMinutes(10)),
            )

        /** Fast ticks so the heartbeat test observes real scheduler periods in seconds, not minutes. */
        val FAST_PROPERTIES =
            TEST_PROPERTIES.copy(realtime = ChatsProperties.Realtime(heartbeat = Duration.ofMillis(25)))
    }
}
