package webchat.backend.presence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import webchat.backend.config.ChatsProperties
import webchat.backend.presence.domain.port.OfflineDueOutcome
import webchat.backend.presence.domain.port.PresencePublishedStatus
import webchat.backend.presence.domain.port.PresenceRegistration
import webchat.backend.presence.domain.port.PresenceStore
import webchat.backend.presence.domain.port.PresenceTransition
import webchat.backend.presence.domain.port.PresenceUpdatedEvent
import webchat.backend.presence.domain.port.PublishedPresence
import webchat.backend.presence.repository.RedisPresenceEventPublisher
import webchat.backend.realtime.RealtimePubSub
import webchat.backend.realtime.RedisRealtimePublisher
import webchat.backend.realtime.SseConnectionRegistry
import java.time.Duration
import java.util.UUID

/**
 * Unit-level verification of the T017 adapter mandates
 * (contracts/presence-events.md §2, tasks.md 007) against a recording
 * [RealtimePubSub] transport — the adapter rides the EXISTING
 * [RedisRealtimePublisher], so these tests build the real publisher the
 * same way RedisRealtimePublisherTest does and assert the presence
 * specifics on top:
 *
 *  * ONE `presence.updated` envelope per audience observer on their OWN
 *    `rt:user:{observerId}` channel — exactly one frame per observer per
 *    transition regardless of the audience size (edge «до 200
 *    участников»: a direct- AND group-mate receives ONE frame; the T030
 *    end-to-end counterpart exercises the SQL audience);
 *  * the `data` payload is byte-shaped exactly like the contract
 *    `PresenceUpdatedEvent` — `userId` (the SUBJECT, never the
 *    observer), the lower-case `status` wire value and the `rev` — one
 *    JSON line per frame;
 *  * `webchat_presence_events_published_total{status}` counts ONE per
 *    PUBLISHED transition, NOT per observer (FR-009);
 *  * the publish leg records
 *    `webchat_realtime_push_seconds{event=presence.updated}` — the
 *    presence sample separable from the messaging frames (SC-001);
 *  * an EMPTY audience publishes and counts nothing; a dead transport
 *    never fails the already-durable transition (at-most-once, FR-009,
 *    constitution II).
 *
 * The full stack — the audience SQL, the Lua CAS, the №18 SSE wire — is
 * exercised end-to-end by PresenceIT (T011) against Testcontainers.
 */
class RedisPresenceEventPublisherTest {
    private val pubSub = RecordingPubSub()

    private val registry = SseConnectionRegistry(SLOW_HEARTBEAT_PROPERTIES)

    private val meterRegistry = SimpleMeterRegistry()

    private val realtimePublisher =
        RedisRealtimePublisher(registry, MAPPER, pubSub, meterRegistry).apply {
            attach()
        }

    private val presenceMetrics = PresenceMetrics(meterRegistry, NoopPresenceStore)

    private val publisher = RedisPresenceEventPublisher(realtimePublisher, presenceMetrics)

    @AfterEach
    fun tearDown() {
        registry.shutdown()
    }

    @Test
    fun `one presence updated envelope per audience observer with the contract payload`() {
        publisher.fanoutPresenceUpdated(setOf(OBSERVER_ONE, OBSERVER_TWO, OBSERVER_THREE), ONLINE_EVENT)

        assertThat(pubSub.published)
            .overridingErrorMessage(
                "every audience observer must receive exactly ONE envelope on their own rt:user channel",
            ).hasSize(3)
        assertThat(pubSub.published.map { it.first })
            .containsExactlyInAnyOrder(
                "rt:user:$OBSERVER_ONE",
                "rt:user:$OBSERVER_TWO",
                "rt:user:$OBSERVER_THREE",
            )
        pubSub.published.forEach { (_, json) ->
            assertThat(json)
                .overridingErrorMessage("the envelope must be one JSON line — one data: line per SSE frame (§2)")
                .doesNotContain("\n")
            val envelope = MAPPER.readTree(json)
            assertThat(fieldNames(envelope)).containsExactlyInAnyOrder(FIELD_EVENT, FIELD_DATA)
            assertThat(envelope[FIELD_EVENT].asText()).isEqualTo(PRESENCE_UPDATED_EVENT)
            val payload = envelope[FIELD_DATA]
            assertThat(fieldNames(payload))
                .overridingErrorMessage("the payload must be exactly the PresenceUpdatedEvent schema")
                .containsExactlyInAnyOrder(FIELD_USER_ID, FIELD_STATUS, FIELD_REV)
            assertThat(payload[FIELD_USER_ID].asText())
                .overridingErrorMessage("userId must be the SUBJECT of the transition, never the observer")
                .isEqualTo(SUBJECT.toString())
            assertThat(payload[FIELD_STATUS].asText())
                .overridingErrorMessage("status must be the lower-case contract value")
                .isEqualTo(STATUS_ONLINE)
            assertThat(payload[FIELD_REV].asLong()).isEqualTo(REV)
        }
    }

    @Test
    fun `the published counter counts the transition once regardless of audience size`() {
        publisher.fanoutPresenceUpdated(setOf(OBSERVER_ONE, OBSERVER_TWO, OBSERVER_THREE), ONLINE_EVENT)
        publisher.fanoutPresenceUpdated(setOf(OBSERVER_ONE), OFFLINE_EVENT)

        assertThat(publishedCountOf(STATUS_ONLINE))
            .overridingErrorMessage(
                "the counter counts ONE per published transition, not per observer (FR-009)",
            ).isEqualTo(1.0)
        assertThat(publishedCountOf(STATUS_OFFLINE)).isEqualTo(1.0)
    }

    /**
     * T036 (presence-events.md §5): the transport itself records the
     * presence push-pipeline samples — one
     * `webchat_realtime_push_seconds{event=presence.updated,stage=publish}`
     * per OBSERVER ENVELOPE of the fan-out (the same per-envelope
     * discipline the messaging frames ride), so the series is separable
     * from the messaging frames by its `event` tag alone.
     */
    @Test
    fun `the publish leg records the presence push timer separably from messaging`() {
        publisher.fanoutPresenceUpdated(setOf(OBSERVER_ONE, OBSERVER_TWO), ONLINE_EVENT)

        val presencePublish =
            meterRegistry.find(METRIC_PUSH_SECONDS).timers().filter { timer ->
                timer.id.getTag(TAG_EVENT) == PRESENCE_UPDATED_EVENT && timer.id.getTag(TAG_STAGE) == STAGE_PUBLISH
            }
        assertThat(presencePublish.map { it.count() })
            .overridingErrorMessage(
                "one $METRIC_PUSH_SECONDS{event=presence.updated,stage=publish} sample per observer " +
                    "envelope of the fan-out (SC-001)",
            ).containsExactly(2L)
    }

    @Test
    fun `an empty audience publishes and counts nothing`() {
        publisher.fanoutPresenceUpdated(emptySet(), ONLINE_EVENT)

        assertThat(pubSub.published).isEmpty()
        assertThat(publishedCountOf(STATUS_ONLINE)).isZero
        assertThat(
            meterRegistry.find(METRIC_PUSH_SECONDS).timers().filter { timer ->
                timer.id.getTag(TAG_EVENT) == PRESENCE_UPDATED_EVENT
            },
        ).isEmpty()
    }

    @Test
    fun `a dead transport never fails the published transition`() {
        pubSub.failPublish = true

        assertThatCode { publisher.fanoutPresenceUpdated(setOf(OBSERVER_ONE), ONLINE_EVENT) }
            .overridingErrorMessage(
                "a failed at-most-once delivery must not fail the already-durable transition (FR-009)",
            ).doesNotThrowAnyException()
        assertThat(publishedCountOf(STATUS_ONLINE))
            .overridingErrorMessage("the transition itself was published — the counter still counts it")
            .isEqualTo(1.0)
    }

    @Test
    fun `an inbound presence frame dispatches to the local sessions as a contract sse frame`() {
        val observer = RecordingEmitter()
        registry.register(OBSERVER_ONE, observer)

        publisher.fanoutPresenceUpdated(setOf(OBSERVER_ONE), ONLINE_EVENT)
        val (channel, json) = pubSub.published.single()
        pubSub.deliver(channel, json)

        val frame = "event:$PRESENCE_UPDATED_EVENT\ndata:${MAPPER.readTree(json)[FIELD_DATA]}\n\n"
        assertThat(observer.recorded)
            .overridingErrorMessage("the dispatched №18 frame must be the contract presence.updated SSE frame")
            .contains(frame)
    }

    private fun publishedCountOf(status: String): Double =
        meterRegistry
            .find(METRIC_EVENTS_PUBLISHED_TOTAL)
            .tag(TAG_STATUS, status)
            .counter()
            ?.count() ?: 0.0

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /** The [PresenceStore] the T010 gauge construction needs — no store leg runs in these tests. */
    private object NoopPresenceStore : PresenceStore {
        override fun readOnlineUsersCount(): Long = 0L

        override fun register(
            userId: UUID,
            sessionId: UUID,
            connectionId: UUID,
        ): PresenceRegistration = error("not expected in RedisPresenceEventPublisherTest")

        override fun renewRegistration(
            userId: UUID,
            connectionId: UUID,
        ): Boolean = error("not expected in RedisPresenceEventPublisherTest")

        override fun unregister(
            userId: UUID,
            connectionId: UUID,
        ) = error("not expected in RedisPresenceEventPublisherTest")

        override fun clearSessionRegistrations(
            userId: UUID,
            sessionId: UUID,
        ): PresenceTransition = error("not expected in RedisPresenceEventPublisherTest")

        override fun hasAliveRegistration(userId: UUID): Boolean =
            error("not expected in RedisPresenceEventPublisherTest")

        override fun transitionOnlineIfDue(userId: UUID): PresenceTransition =
            error("not expected in RedisPresenceEventPublisherTest")

        override fun freezePublishedOffline(userId: UUID): PresenceTransition =
            error("not expected in RedisPresenceEventPublisherTest")

        override fun revealPublishedStatus(userId: UUID): PresenceTransition =
            error("not expected in RedisPresenceEventPublisherTest")

        override fun scheduleOfflinePublish(userId: UUID) = error("not expected in RedisPresenceEventPublisherTest")

        override fun cancelOfflinePublish(userId: UUID) = error("not expected in RedisPresenceEventPublisherTest")

        override fun dueOfflinePublishBatch(limit: Int): List<UUID> =
            error("not expected in RedisPresenceEventPublisherTest")

        override fun processDueOfflinePublish(userId: UUID): OfflineDueOutcome =
            error("not expected in RedisPresenceEventPublisherTest")

        override fun dueWatchBatch(limit: Int): List<UUID> = error("not expected in RedisPresenceEventPublisherTest")

        override fun reapExpiredRegistrations(userId: UUID): Boolean =
            error("not expected in RedisPresenceEventPublisherTest")

        override fun readPublishedBatch(userIds: Collection<UUID>): Map<UUID, PublishedPresence> =
            error("not expected in RedisPresenceEventPublisherTest")
    }

    /** Records every transport call; [deliver] simulates Redis handing a frame to this instance's subscription. */
    private class RecordingPubSub : RealtimePubSub {
        val published = mutableListOf<Pair<String, String>>()

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

        override fun subscribe(channel: String) = Unit

        override fun unsubscribe(channel: String) = Unit

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

        val SUBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val OBSERVER_ONE = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        val OBSERVER_TWO = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        val OBSERVER_THREE = UUID.fromString("00000000-0000-0000-0000-0000000000b3")
        const val REV = 42L

        val ONLINE_EVENT = PresenceUpdatedEvent(SUBJECT, PresencePublishedStatus.ONLINE, REV)
        val OFFLINE_EVENT = PresenceUpdatedEvent(SUBJECT, PresencePublishedStatus.OFFLINE, REV + 1)

        /** presence-events.md 007 §2: the №18 `event:` value and the payload field names. */
        const val PRESENCE_UPDATED_EVENT = "presence.updated"
        const val FIELD_EVENT = "event"
        const val FIELD_DATA = "data"
        const val FIELD_USER_ID = "userId"
        const val FIELD_STATUS = "status"
        const val FIELD_REV = "rev"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"

        /** PresenceMetrics (T010): the FR-009 counter and the reused 004 push timer. */
        const val METRIC_EVENTS_PUBLISHED_TOTAL = "webchat_presence_events_published_total"
        const val METRIC_PUSH_SECONDS = "webchat_realtime_push_seconds"
        const val TAG_EVENT = "event"
        const val TAG_STAGE = "stage"
        const val TAG_STATUS = "status"
        const val STAGE_PUBLISH = "publish"

        /** Slow enough that no heartbeat tick interferes with the dispatch assertion. */
        val SLOW_HEARTBEAT_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = 4096, pageSize = 50),
                rateLimit =
                    ChatsProperties.RateLimit(
                        messagesPerMinute = 30,
                        searchesPerMinute = 30,
                        typingSignalsPerMinute = 60,
                        soundWritesPerMinute = 30,
                    ),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofMinutes(10)),
                typing =
                    ChatsProperties.Typing(
                        stateTtl = Duration.ofSeconds(8),
                        pollerEnabled = false,
                        pollInterval = Duration.ofSeconds(1),
                        pollBatch = 1000,
                    ),
            )
    }
}
