package webchat.backend.realtime

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import webchat.backend.chats.MessagingTestSupport
import webchat.backend.config.ChatsProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T007 (tasks.md Phase 3, US1-3): the SSE user event stream of contract №18
 * `GET /api/v1/users/me/events` answers with the exact WHATWG framing of
 * realtime-channel.md §1–§3 — the opening `retry: 3000` frame, the `:ka`
 * heartbeat comment at the `chats.realtime.heartbeat` cadence, and the
 * `message.created` event delivered to BOTH participants after a №16 POST,
 * with a payload that conforms to `components.schemas.MessageCreatedEvent`
 * (openapi.yaml: exactly `chatId` + `message`, `additionalProperties: false`)
 * and equals the stored `Message` returned by №16.
 *
 * The raw frames are read through the T002 fixture ([MessagingTestSupport]
 * JDK-HttpClient reader) on purpose: the build carries no webflux dependency
 * (research.md §13 names WebTestClient, but plan.md VII adds no such
 * dependency), and a codec-based client would strip the `retry:`/`:ka`
 * framing that this IT must assert verbatim.
 *
 * NOTE (TDD, constitution VI): written BEFORE T009–T019 — until the chats
 * endpoints and the realtime channel land, every method here fails (RED) by
 * design. SC-005 realtime latency (p95 ≤ 2 s) is the k6 profile's budget
 * (T066, research.md §13); this IT asserts functional delivery within a
 * CI-tolerant deadline instead of a percentile.
 */
class RealtimeSseIT(
    @Autowired private val chatsProperties: ChatsProperties,
) : MessagingTestSupport() {
    /** First `message.created` frame must arrive well inside this CI-tolerant budget. */
    private val deliveryBudget: Duration = Duration.ofSeconds(5)

    /** realtime-channel.md §1: the stream opens with the reconnect hint `retry: 3000`. */
    @Test
    fun `stream opens with retry 3000 frame`() {
        val user = messagingUser("retry")

        openUserEvents(user).use { stream ->
            val first = stream.nextFrame(FIRST_FRAME_BUDGET)

            assertThat(first)
                .overridingErrorMessage("the first frame of the user event stream must be the retry frame")
                .isNotNull
            assertThat(first!!.retryMillis)
                .overridingErrorMessage("the opening frame must carry SSE field retry: 3000")
                .isEqualTo(RETRY_MILLIS)
            assertThat(first.event)
                .overridingErrorMessage("the retry frame must not carry an event: field")
                .isNull()
            assertThat(first.data)
                .overridingErrorMessage("the retry frame must not carry a data: field")
                .isNull()
        }
    }

    /**
     * realtime-channel.md §1/§2: the server keeps the stream alive with the
     * `:ka` comment frame at the `chats.realtime.heartbeat` cadence (15 s from
     * application.yml / T001) — the first heartbeat arrives within one period
     * (plus slack) of the connect, and consecutive heartbeats keep the period,
     * so idle proxies do not cut the stream (research.md §2 keepalive).
     */
    @Test
    fun `heartbeat ka arrives at the configured cadence`() {
        val heartbeat = chatsProperties.realtime.heartbeat
        assertThat(heartbeat)
            .overridingErrorMessage("chats.realtime.heartbeat must stay at the contract value 15 s (T001)")
            .isEqualTo(Duration.ofSeconds(15))
        val user = messagingUser("ka")

        openUserEvents(user).use { stream ->
            val openedAt = System.nanoTime()
            val firstKaAt = awaitHeartbeat(stream, heartbeat.plusMillis(HEARTBEAT_SLACK_MILLIS))
            val firstGap = Duration.ofNanos(firstKaAt - openedAt)
            assertThat(firstGap)
                .overridingErrorMessage(
                    "the first :ka heartbeat must arrive within one heartbeat period (+%d ms slack), took %s",
                    HEARTBEAT_SLACK_MILLIS,
                    firstGap,
                ).isLessThanOrEqualTo(heartbeat.plusMillis(HEARTBEAT_SLACK_MILLIS))

            val secondKaAt = awaitHeartbeat(stream, heartbeat.plusMillis(HEARTBEAT_SLACK_MILLIS))
            val cadence = Duration.ofNanos(secondKaAt - firstKaAt)
            assertThat(cadence.toMillis())
                .overridingErrorMessage(
                    "consecutive :ka heartbeats must keep the %s cadence (got %s)",
                    heartbeat,
                    cadence,
                ).isBetween((heartbeat.toMillis() / 2), heartbeat.multipliedBy(2).toMillis())
        }
    }

    /**
     * realtime-channel.md §3.1 (FR-007): after a №16 POST both participants
     * receive `message.created` on their own streams — the recipient renders
     * it, the sender's other devices converge (US2-6) — and the payload is the
     * contract `MessageCreatedEvent`: only `chatId` + `message`, the inner
     * `message` matching the `Message` schema and byte-equal to the №16
     * response of the very POST that triggered it.
     */
    @Test
    fun `message created frame reaches both participants with contract payload`() {
        val (alice, bob) = messagingPair()
        val text = "Привет из RealtimeSseIT — US1-3 ✓"

        openUserEvents(alice).use { aliceStream ->
            openUserEvents(bob).use { bobStream ->
                val chatId = ensureChatOk(alice, bob.id)
                val stored = sendMessageOk(alice, chatId, text)

                for ((participant, stream) in listOf(alice to aliceStream, bob to bobStream)) {
                    val event = stream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)

                    assertThat(fieldNames(event))
                        .overridingErrorMessage(
                            "message.created payload for <%s> must have exactly the MessageCreatedEvent fields",
                            participant.username,
                        ).containsExactlyInAnyOrder("chatId", "message")
                    assertThat(event["chatId"].asText())
                        .overridingErrorMessage("event.chatId must be the dialog UUID")
                        .isEqualTo(chatId.toString())

                    val message = event["message"]
                    assertThat(fieldNames(message))
                        .overridingErrorMessage(
                            "event.message for <%s> must have exactly the Message schema fields",
                            participant.username,
                        ).containsExactlyInAnyOrder("id", "chatId", "senderId", "text", "seq", "createdAt")
                    assertThat(UUID.fromString(message["id"].asText()))
                        .overridingErrorMessage("message.id must be the clientMessageId UUID (FR-004)")
                        .isEqualTo(UUID.fromString(stored["id"].asText()))
                    assertThat(UUID.fromString(message["chatId"].asText()))
                        .overridingErrorMessage("message.chatId must match event.chatId")
                        .isEqualTo(chatId)
                    assertThat(UUID.fromString(message["senderId"].asText()))
                        .overridingErrorMessage("message.senderId must be the sending participant")
                        .isEqualTo(alice.id)
                    assertThat(message["text"].asText())
                        .overridingErrorMessage("message.text must carry the sent text verbatim (FR-003)")
                        .isEqualTo(text)
                    assertThat(message["seq"].asLong())
                        .overridingErrorMessage("message.seq must be a positive server sequence")
                        .isPositive()
                    val createdAtIsInstant =
                        runCatching { Instant.parse(message["createdAt"].asText()).isAfter(Instant.EPOCH) }
                            .getOrDefault(false)
                    assertThat(createdAtIsInstant)
                        .overridingErrorMessage("message.createdAt must be an ISO-8601 instant")
                        .isTrue()
                    assertThat(message)
                        .overridingErrorMessage(
                            "event.message for <%s> must equal the Message returned by POST /chats/{id}/messages",
                            participant.username,
                        ).isEqualTo(stored)
                }
            }
        }
    }

    /**
     * Waits for the next pure `:ka` comment frame (realtime-channel.md §2) and
     * returns its arrival time in nanos — skipping `retry:` and event frames,
     * which may interleave legally.
     */
    private fun awaitHeartbeat(
        stream: UserEventsStream,
        timeout: Duration,
    ): Long {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw AssertionError("timed out after $timeout waiting for a :ka heartbeat")
            }
            val frame =
                stream.nextFrame(Duration.ofNanos(remaining))
                    ?: throw AssertionError("stream ended before a :ka heartbeat arrived")
            if (frame.isHeartbeat) {
                assertThat(frame.comment)
                    .overridingErrorMessage("the heartbeat frame must be the :ka comment")
                    .isEqualTo(HEARTBEAT_COMMENT)
                assertThat(frame.event)
                    .overridingErrorMessage("the heartbeat frame must not carry an event: field")
                    .isNull()
                assertThat(frame.data)
                    .overridingErrorMessage("the heartbeat frame must not carry a data: field")
                    .isNull()
                return System.nanoTime()
            }
        }
    }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    private companion object {
        const val RETRY_MILLIS = 3_000L
        const val HEARTBEAT_COMMENT = "ka"
        const val HEARTBEAT_SLACK_MILLIS = 5_000L
        const val MESSAGE_CREATED_EVENT = "message.created"
        val FIRST_FRAME_BUDGET: Duration = Duration.ofSeconds(5)
    }
}
