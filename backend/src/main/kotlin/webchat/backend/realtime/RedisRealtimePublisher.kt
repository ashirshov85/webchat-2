package webchat.backend.realtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import webchat.backend.chats.api.dto.MessageView
import webchat.backend.chats.domain.port.ChatReadEvent
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The Redis Pub/Sub transport seam of the realtime fan-out (T019;
 * research.md 004 §2): PUBLISH to a channel, the DYNAMIC per-channel
 * SUBSCRIBE/UNSUBSCRIBE of the instance, and inbound frame delivery.
 *
 * The narrow interface exists so the publisher's envelope/dispatch logic
 * is unit-testable without a broker (constitution VI); the production
 * implementation [SpringRedisPubSub] rides the SAME shared Lettuce
 * connection factory as the rest of the app (constitution II — pods stay
 * stateless, Redis carries the cross-instance fan-out).
 */
interface RealtimePubSub {
    fun publish(
        channel: String,
        json: String,
    )

    fun subscribe(channel: String)

    fun unsubscribe(channel: String)

    fun onFrame(handler: (channel: String, json: String) -> Unit)
}

/**
 * The production [RealtimePubSub] over Spring Data Redis (Lettuce-backed
 * by the shared Boot auto-configuration): PUBLISH goes through the
 * [StringRedisTemplate] connection, the dynamic subscription through a
 * [RedisMessageListenerContainer] that this class feeds exactly the
 * channels of users with LIVE local sessions — the container maintains
 * one dedicated pub/sub connection and re-subscribes internally, so the
 * publisher only adds/removes [ChannelTopic]s.
 *
 * Subscribe/unsubscribe are idempotent per channel (a channel set); the
 * [SseConnectionRegistry] already serializes its first/last-connection
 * callbacks, so races between concurrent sessions of one user cannot
 * double-subscribe or unsubscribe a still-live user.
 */
internal class SpringRedisPubSub(
    private val listenerContainer: RedisMessageListenerContainer,
    private val redisTemplate: StringRedisTemplate,
) : RealtimePubSub {
    @Volatile
    private var frameHandler: ((channel: String, json: String) -> Unit)? = null

    private val activeChannels: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val listener =
        MessageListener { message, _ ->
            val handler = frameHandler ?: return@MessageListener
            handler.invoke(
                String(message.channel, Charsets.UTF_8),
                String(message.body, Charsets.UTF_8),
            )
        }

    override fun publish(
        channel: String,
        json: String,
    ) {
        redisTemplate.convertAndSend(channel, json)
    }

    override fun subscribe(channel: String) {
        if (channel in activeChannels) return
        listenerContainer.addMessageListener(listener, ChannelTopic(channel))
        activeChannels += channel
    }

    override fun unsubscribe(channel: String) {
        if (channel !in activeChannels) return
        listenerContainer.removeMessageListener(listener, ChannelTopic(channel))
        activeChannels -= channel
    }

    override fun onFrame(handler: (channel: String, json: String) -> Unit) {
        frameHandler = handler
    }
}

/**
 * The Spring wiring of the realtime transport: the app declares no
 * [RedisMessageListenerContainer] of its own (Boot does not auto-configure
 * one), so the realtime feature provides it — dedicated to dynamic
 * per-user channels, started/stopped with the context (SmartLifecycle).
 */
@Configuration
internal class RedisRealtimePubSubConfig {
    @Bean(destroyMethod = DESTROY_METHOD)
    fun realtimeMessageListenerContainer(connectionFactory: RedisConnectionFactory): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
        }

    @Bean
    fun realtimePubSub(
        listenerContainer: RedisMessageListenerContainer,
        redisTemplate: StringRedisTemplate,
    ): RealtimePubSub = SpringRedisPubSub(listenerContainer, redisTemplate)

    private companion object {
        const val DESTROY_METHOD = "destroy"
    }
}

/**
 * The Redis adapter of the domain's [RealtimeEventPublisher] port (T019;
 * research.md 004 §2, data-model 004 §3 step 5): strictly AFTER the PG
 * commit the caller hands over the event, and this adapter PUBLISHES a
 * compact one-line JSON envelope `{"event":…,"data":…}` to the per-user
 * channel `rt:user:{userId}` of every addressee.
 *
 * At the same time the adapter is the LOCAL half of the fan-out: it
 * listens to the [SseConnectionRegistry] connection lifecycle and keeps
 * the instance subscribed to exactly the channels of users with live SSE
 * sessions here (dynamic Lettuce subscription on the first connection,
 * unsubscribe on the close of the last one), re-dispatching inbound
 * envelopes to the local emitters as contract SSE frames.
 *
 * Delivery semantics (research.md 004 §2): the channel is at-most-once —
 * a lost or failed frame is NEVER re-sent and NEVER fails the already
 * durable request; clients converge via the refetch on (re)connect
 * (FR-009, constitution II). Warn logs carry ids only, never payload
 * text (constitution V, SC-008). The inter-instance envelope is an
 * internal transport detail — the PUBLIC contract shape is the SSE frame
 * (realtime-channel.md §2–§3), so `data` is serialized exactly like the
 * contract payloads ([MessageView] for `message.created`).
 */
@Component
class RedisRealtimePublisher(
    private val connectionRegistry: SseConnectionRegistry,
    private val objectMapper: ObjectMapper,
    private val pubSub: RealtimePubSub,
) : RealtimeEventPublisher,
    SseConnectionRegistry.ConnectionListener {
    /**
     * FR-007: the event goes to BOTH participants' channels by the
     * caller's decision (T014) — the recipient renders it, the sender's
     * other devices/sessions render it already «доставлено» (US2-6).
     */
    override fun publishMessageCreated(
        toUserId: UUID,
        event: MessageCreatedEvent,
    ) {
        publishEnvelope(toUserId, EVENT_MESSAGE_CREATED, messageCreatedPayload(event))
    }

    /** FR-010 (T042): to the PEER's channel only; suppression is the caller's gate. */
    override fun publishChatRead(
        toUserId: UUID,
        event: ChatReadEvent,
    ) {
        publishEnvelope(toUserId, EVENT_CHAT_READ, event)
    }

    /** The T019 dynamic subscription hook: subscribe `rt:user:{id}` on the first live session. */
    override fun onFirstConnection(userId: UUID) {
        pubSub.subscribe(userChannel(userId))
    }

    /** The T019 dynamic subscription hook: unsubscribe when the user's last local session closes. */
    override fun onLastConnectionClosed(userId: UUID) {
        pubSub.unsubscribe(userChannel(userId))
    }

    /**
     * Wires the adapter into both halves of the channel once the bean is
     * ready (Spring calls this via [PostConstruct]): the registry's
     * first/last-connection callbacks start driving the subscription,
     * and inbound Pub/Sub frames flow into the local dispatch.
     */
    @PostConstruct
    fun attach() {
        connectionRegistry.addListener(this)
        pubSub.onFrame(::handleFrame)
    }

    /**
     * The payload is the contract `MessageCreatedEvent`
     * (realtime-channel.md §3.1): exactly `chatId` + `message`, the inner
     * `message` re-shaped through the SAME [MessageView] projection as
     * the №16/№15 REST bodies (one schema, US6) — including the plain
     * FR-003 text string, never the domain `MessageText` wrapper.
     */
    private fun messageCreatedPayload(event: MessageCreatedEvent): Map<String, Any> =
        mapOf(
            FIELD_CHAT_ID to event.chatId,
            FIELD_MESSAGE to
                MessageView(
                    id = event.message.id,
                    chatId = event.message.chatId,
                    senderId = event.message.senderId,
                    text = event.message.text.value,
                    seq = event.message.seq,
                    createdAt = event.message.createdAt,
                ),
        )

    /**
     * The outbound half: one compact JSON envelope to the user channel.
     * Isolation (the port contract): a lost or failed at-most-once
     * delivery must NOT fail the already-durable request — the failure
     * is a warn with ids only, never the message text (constitution V).
     */
    @Suppress("TooGenericExceptionCaught") // a dead transport signals itself by throwing
    private fun publishEnvelope(
        toUserId: UUID,
        eventName: String,
        payload: Any,
    ) {
        try {
            val envelope = objectMapper.writeValueAsString(RealtimeEnvelope(event = eventName, data = payload))
            pubSub.publish(userChannel(toUserId), envelope)
        } catch (failure: Exception) {
            log.warn(
                "realtime publish of a <{}> event to user <{}> failed; " +
                    "the record is durable and clients converge on refetch (FR-009): {}",
                eventName,
                toUserId,
                failure.message,
            )
        }
    }

    /**
     * The inbound half: `rt:user:{userId}` → the local emitters of that
     * user, as one contract frame (`event:` + one `data:` line). A frame
     * of a foreign/malformed channel or a malformed envelope is dropped
     * with a warn — the storage is exactly-once in PG, so nothing is lost
     * visibly: clients converge on the refetch (FR-009).
     */
    private fun handleFrame(
        channel: String,
        json: String,
    ) {
        val userId = userIdOf(channel) ?: return
        val frame = incomingFrame(json) ?: return
        connectionRegistry.dispatch(userId, frame.eventName, frame.payload.toString())
    }

    private fun userIdOf(channel: String): UUID? {
        if (!channel.startsWith(CHANNEL_PREFIX)) {
            log.warn("a realtime frame arrived on a non-user channel <{}> and was dropped", channel)
            return null
        }
        return runCatching { UUID.fromString(channel.substring(CHANNEL_PREFIX.length)) }
            .onFailure {
                log.warn("a realtime frame arrived on a malformed user channel <{}> and was dropped", channel)
            }.getOrNull()
    }

    /**
     * Parses the wire envelope: a textual `event` + an object `data`
     * payload — anything else is dropped with a warn.
     */
    @Suppress("TooGenericExceptionCaught") // an unreadable envelope signals itself by throwing
    private fun incomingFrame(json: String): RealtimeFrame? {
        val root =
            try {
                objectMapper.readTree(json)
            } catch (failure: Exception) {
                log.warn("an unreadable realtime envelope was dropped: {}", failure.message)
                null
            }
        val eventName = root?.get(FIELD_EVENT)?.takeIf(JsonNode::isTextual)?.asText()
        val payload = root?.get(FIELD_DATA)?.takeIf(JsonNode::isObject)
        if (eventName == null || payload == null) {
            log.warn("a realtime envelope without a textual event or an object payload was dropped")
            return null
        }
        return RealtimeFrame(eventName, payload)
    }

    /** A parsed inbound envelope: the `event:` name and the payload node (compact single-line `data:`). */
    private data class RealtimeFrame(
        val eventName: String,
        val payload: JsonNode,
    )

    private companion object {
        private val log = LoggerFactory.getLogger(RedisRealtimePublisher::class.java)

        /** realtime-channel.md §3: the contract `event:` values. */
        const val EVENT_MESSAGE_CREATED = "message.created"
        const val EVENT_CHAT_READ = "chat.read"

        /** The internal wire envelope fields (transport detail, NOT the public SSE framing). */
        const val FIELD_EVENT = "event"
        const val FIELD_DATA = "data"

        /** realtime-channel.md §3.1: the `MessageCreatedEvent` payload fields. */
        const val FIELD_CHAT_ID = "chatId"
        const val FIELD_MESSAGE = "message"
    }
}

/**
 * The inter-instance wire envelope of `rt:user:{userId}` — an internal
 * transport detail (the public contract is the SSE frame of
 * realtime-channel.md §2): [event] names the contract `event:` value,
 * [data] is the contract-shaped payload rendered to ONE JSON line.
 */
private data class RealtimeEnvelope(
    val event: String,
    val data: Any,
)

/** research.md 004 §2 / data-model 004 §6: the per-user fan-out channel family. */
private const val CHANNEL_PREFIX = "rt:user:"

/** The Pub/Sub channel of one user's event stream. */
private fun userChannel(userId: UUID): String = "$CHANNEL_PREFIX$userId"
