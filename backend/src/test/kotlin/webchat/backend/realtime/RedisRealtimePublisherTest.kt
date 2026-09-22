package webchat.backend.realtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.ChatReadEvent
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.config.ChatsProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Unit-level verification of the T019 adapter mandates (research.md 004
 * §2, realtime-channel.md §2–§3) against a recording [RealtimePubSub]
 * transport: `publishMessageCreated`/`publishChatRead` write a compact
 * ONE-LINE envelope `{"event":…,"data":…}` to `rt:user:{userId}` whose
 * `data` is byte-shaped exactly like the contract payloads (the inner
 * `message` matching the `Message` schema, `text` a plain string), the
 * registry's first/last-connection callbacks drive the dynamic
 * subscribe/unsubscribe, and inbound frames are re-dispatched to EVERY
 * local session of the target user only. Malformed envelopes and foreign
 * channels are dropped without dispatch, and a dead transport never
 * fails the already-durable request (FR-009, constitution II).
 *
 * The REAL Lettuce-backed transport — the shared connection factory, the
 * `RedisMessageListenerContainer`, the wire `retry:`/`:ka` framing — is
 * exercised end-to-end by RealtimeSseIT (T007) against Testcontainers.
 */
class RedisRealtimePublisherTest {
    private val pubSub = RecordingPubSub()

    private val registry = SseConnectionRegistry(SLOW_HEARTBEAT_PROPERTIES)

    private val publisher =
        RedisRealtimePublisher(registry, MAPPER, pubSub).apply {
            attach()
        }

    @AfterEach
    fun tearDown() {
        registry.shutdown()
    }

    @Test
    fun `message created publishes a contract envelope to the user channel`() {
        publisher.publishMessageCreated(ALICE, MESSAGE_CREATED)

        assertThat(pubSub.published).hasSize(1)
        val (channel, json) = pubSub.published.single()
        assertThat(channel).isEqualTo("rt:user:$ALICE")
        assertThat(json)
            .overridingErrorMessage("the envelope must be one JSON line — one data: line per SSE frame (§2)")
            .doesNotContain("\n")

        val envelope = MAPPER.readTree(json)
        assertThat(fieldNames(envelope)).containsExactlyInAnyOrder("event", "data")
        assertThat(envelope["event"].asText()).isEqualTo("message.created")

        val payload = envelope["data"]
        assertThat(fieldNames(payload))
            .overridingErrorMessage("the payload must be exactly the MessageCreatedEvent schema")
            .containsExactlyInAnyOrder("chatId", "message")
        assertThat(payload["chatId"].asText()).isEqualTo(CHAT_ID.toString())
        val message = payload["message"]
        assertThat(fieldNames(message))
            .overridingErrorMessage("the inner message must be exactly the Message schema")
            .containsExactlyInAnyOrder("id", "chatId", "senderId", "text", "seq", "createdAt")
        assertThat(message["id"].asText()).isEqualTo(MESSAGE_ID.toString())
        assertThat(message["senderId"].asText()).isEqualTo(ALICE.toString())
        assertThat(message["text"].asText())
            .overridingErrorMessage("text must be the plain FR-003 string, never a nested MessageText object")
            .isEqualTo(TEXT)
        assertThat(message["seq"].asLong()).isEqualTo(SEQ)
        assertThat(message["createdAt"].asText()).isEqualTo(CREATED_AT.toString())
    }

    @Test
    fun `chat read publishes a contract envelope to the user channel`() {
        publisher.publishChatRead(ALICE, CHAT_READ)

        val (channel, json) = pubSub.published.single()
        assertThat(channel).isEqualTo("rt:user:$ALICE")
        val envelope = MAPPER.readTree(json)
        assertThat(envelope["event"].asText()).isEqualTo("chat.read")
        val payload = envelope["data"]
        assertThat(fieldNames(payload))
            .overridingErrorMessage("the payload must be exactly the ChatReadEvent schema")
            .containsExactlyInAnyOrder("chatId", "readUpToSeq", "byUserId")
        assertThat(payload["chatId"].asText()).isEqualTo(CHAT_ID.toString())
        assertThat(payload["readUpToSeq"].asLong()).isEqualTo(SEQ)
        assertThat(payload["byUserId"].asText()).isEqualTo(BOB.toString())
    }

    @Test
    fun `the first SSE connection subscribes the channel and the last close unsubscribes`() {
        val session = RecordingEmitter()
        registry.register(ALICE, session)
        assertThat(pubSub.subscribed)
            .overridingErrorMessage("the instance subscribes rt:user:{id} only while the user has live sessions")
            .containsExactly("rt:user:$ALICE")

        registry.unregister(ALICE, session)
        assertThat(pubSub.unsubscribed).containsExactly("rt:user:$ALICE")
    }

    @Test
    fun `an inbound frame is dispatched to every local session of the user only`() {
        val aliceFirst = RecordingEmitter()
        val aliceSecond = RecordingEmitter()
        val bob = RecordingEmitter()
        registry.register(ALICE, aliceFirst)
        registry.register(ALICE, aliceSecond)
        registry.register(BOB, bob)

        publisher.publishMessageCreated(ALICE, MESSAGE_CREATED)
        val (channel, json) = pubSub.published.single()
        pubSub.deliver(channel, json)

        val frame = "event:message.created\ndata:${MAPPER.readTree(json)["data"]}\n\n"
        assertThat(aliceFirst.recorded)
            .overridingErrorMessage("every local session of the target user must receive the frame")
            .contains(frame)
        assertThat(aliceSecond.recorded).contains(frame)
        assertThat(bob.recorded)
            .overridingErrorMessage("other users' streams must stay untouched")
            .isEmpty()
    }

    @Test
    fun `frames of foreign or malformed channels are dropped without dispatch`() {
        val alice = RecordingEmitter()
        registry.register(ALICE, alice)

        pubSub.deliver("rt:user:not-an-uuid", ENVELOPE)
        pubSub.deliver("sso:flow:123", ENVELOPE)

        assertThat(alice.recorded).isEmpty()
    }

    @Test
    fun `malformed envelopes are dropped without dispatch`() {
        val alice = RecordingEmitter()
        registry.register(ALICE, alice)

        pubSub.deliver("rt:user:$ALICE", "{not-json")
        pubSub.deliver("rt:user:$ALICE", """{"event":"message.created"}""")
        pubSub.deliver("rt:user:$ALICE", """{"data":{"seq":128}}""")
        pubSub.deliver("rt:user:$ALICE", """{"event":"x","data":"scalar"}""")

        assertThat(alice.recorded).isEmpty()
    }

    @Test
    fun `a dead transport never fails the durable send path`() {
        pubSub.failPublish = true

        assertThatCode { publisher.publishMessageCreated(ALICE, MESSAGE_CREATED) }
            .overridingErrorMessage("a failed at-most-once delivery must not fail the committed record (FR-009)")
            .doesNotThrowAnyException()
    }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /** Records every transport call; [deliver] simulates Redis handing a frame to this instance's subscription. */
    private class RecordingPubSub : RealtimePubSub {
        val published = mutableListOf<Pair<String, String>>()
        val subscribed = mutableListOf<String>()
        val unsubscribed = mutableListOf<String>()

        @Volatile
        var failPublish = false

        private var handler: ((channel: String, json: String) -> Unit)? = null

        override fun publish(
            channel: String,
            json: String,
        ) {
            if (failPublish) error("channel down")
            published += channel to json
        }

        override fun subscribe(channel: String) {
            subscribed += channel
        }

        override fun unsubscribe(channel: String) {
            unsubscribed += channel
        }

        override fun onFrame(handler: (channel: String, json: String) -> Unit) {
            this.handler = handler
        }

        fun deliver(
            channel: String,
            json: String,
        ) {
            handler?.invoke(channel, json)
        }
    }

    /** Captures the exact wire text of the frames the registry writes (the SseConnectionRegistryTest pattern). */
    private class RecordingEmitter : SseEmitter(0L) {
        private val wire = StringBuffer()

        val recorded: String
            get() = wire.toString()

        override fun send(items: Set<ResponseBodyEmitter.DataWithMediaType>) {
            items.forEach { wire.append(it.data.toString()) }
        }
    }

    private companion object {
        /** Boot parity: ISO-8601 instants, Kotlin module — the production mapper is the Boot one. */
        val MAPPER: ObjectMapper =
            ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CHAT_ID = UUID.fromString("7dc5f5c0-0000-4a10-8b00-0000000000c1")
        val MESSAGE_ID = UUID.fromString("0b0f0000-0000-4000-8000-0000000000aa")
        const val TEXT = "Привет из RedisRealtimePublisherTest — внутренние  пробелы ✓"
        const val SEQ = 128L
        val CREATED_AT: Instant = Instant.parse("2026-09-20T12:00:00.123Z")

        val MESSAGE =
            Message(
                id = MESSAGE_ID,
                chatId = CHAT_ID,
                senderId = ALICE,
                text = MessageText.normalize(TEXT),
                seq = SEQ,
                createdAt = CREATED_AT,
            )
        val MESSAGE_CREATED = MessageCreatedEvent(chatId = CHAT_ID, message = MESSAGE)
        val CHAT_READ = ChatReadEvent(chatId = CHAT_ID, readUpToSeq = SEQ, byUserId = BOB)

        const val ENVELOPE = """{"event":"message.created","data":{"seq":128}}"""

        /** Slow enough that no heartbeat tick interferes with the dispatch assertions. */
        val SLOW_HEARTBEAT_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = 4096, pageSize = 50),
                rateLimit = ChatsProperties.RateLimit(messagesPerMinute = 30),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofMinutes(10)),
            )
    }
}
