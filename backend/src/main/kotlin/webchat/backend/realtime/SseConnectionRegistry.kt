package webchat.backend.realtime

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import webchat.backend.config.ChatsProperties
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The per-user SSE connection table of the realtime channel (T018;
 * realtime-channel.md §1, research.md 004 §1–§2): every device/session of
 * a user opens its own stream, so the registry keeps a MULTI-SESSION
 * emitter set per user id. The table is deliberately instance-local —
 * pods stay stateless (constitution II), and the cross-instance fan-out
 * rides Redis Pub/Sub `rt:user:{userId}` (T019), whose local half calls
 * [dispatch].
 *
 * Two duties:
 *  - LIVENESS: a dedicated daemon scheduler writes the WHATWG comment
 *    frame `:ka` to every open emitter at the `chats.realtime.heartbeat`
 *    cadence (15 s, T001), so idle proxies and K8s load balancers do not
 *    cut silent streams (research.md 004 §1 keepalive); a failed write
 *    completes and unregisters only the dead emitter — reconnect is the
 *    client's normal mode (FR-009). The scheduler is NOT `@Scheduled` on
 *    purpose: the shared Boot task scheduler is single-threaded and also
 *    runs the SMTP-blocking email outbox poller, whose ticks would stall
 *    the heartbeat cadence.
 *  - DISPATCH: [dispatch] writes one `event:`/`data:` frame to every
 *    session of the target user. Payloads arrive pre-serialized as
 *    single-line JSON (realtime-channel.md §2 — one `data:` line per
 *    frame) and are written verbatim as text. Delivery is at-most-once
 *    and isolated per emitter: the triggering record is already durable,
 *    so a lost or failed frame never propagates — clients converge via
 *    the refetch on (re)connect (FR-009, constitution II).
 *
 * [ConnectionListener] observes the FIRST connection of a user and the
 * close of the LAST one — the subscribe/unsubscribe hooks of the T019
 * dynamic per-instance Pub/Sub subscription. Listeners are invoked under
 * the registry monitor: they must not call back into the registry.
 */
@Component
class SseConnectionRegistry(
    chatsProperties: ChatsProperties,
) {
    private val log = LoggerFactory.getLogger(SseConnectionRegistry::class.java)

    private val heartbeat: Duration = chatsProperties.realtime.heartbeat

    private val emittersByUser = ConcurrentHashMap<UUID, CopyOnWriteArraySet<SseEmitter>>()

    private val listeners = CopyOnWriteArraySet<ConnectionListener>()

    private val heartbeatScheduler: ScheduledExecutorService =
        Executors
            .newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, HEARTBEAT_THREAD_NAME).apply { isDaemon = true }
            }.also { scheduler ->
                scheduler.scheduleAtFixedRate(
                    ::sendHeartbeatToAllUsers,
                    heartbeat.toMillis(),
                    heartbeat.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
            }

    /**
     * Adds one session of [userId]. The first live connection of the
     * user notifies [ConnectionListener.onFirstConnection] (the T019
     * subscribe hook); re-registrations of a known emitter are no-ops.
     */
    @Synchronized
    fun register(
        userId: UUID,
        emitter: SseEmitter,
    ) {
        val userEmitters = emittersByUser.computeIfAbsent(userId) { CopyOnWriteArraySet() }
        val firstConnection = userEmitters.isEmpty()
        userEmitters.add(emitter)
        if (firstConnection) {
            listeners.forEach { it.onFirstConnection(userId) }
        }
    }

    /**
     * Removes one session (client disconnect, transport error, stream
     * completion — the emitter lifecycle callbacks of RealtimeController).
     * Removing the LAST session of the user drops the whole entry and
     * notifies [ConnectionListener.onLastConnectionClosed] (the T019
     * unsubscribe hook); unknown pairs are silent no-ops.
     */
    @Synchronized
    fun unregister(
        userId: UUID,
        emitter: SseEmitter,
    ) {
        val userEmitters = emittersByUser[userId] ?: return
        userEmitters.remove(emitter)
        if (userEmitters.isEmpty()) {
            emittersByUser.remove(userId, userEmitters)
            listeners.forEach { it.onLastConnectionClosed(userId) }
        }
    }

    fun addListener(listener: ConnectionListener) {
        listeners.add(listener)
    }

    /**
     * Writes one event frame (`event: [eventName]` + one `data:` line,
     * realtime-channel.md §2) to EVERY session of [userId] — the local
     * half of the T019 Pub/Sub fan-out. Never throws: a dead emitter is
     * completed and dropped here, its siblings keep receiving.
     */
    fun dispatch(
        userId: UUID,
        eventName: String,
        jsonPayload: String,
    ) {
        emittersByUser[userId]?.forEach { emitter ->
            sendDroppingDeadConnections(userId, emitter, eventFrame(eventName, jsonPayload))
        }
    }

    /** Live session count of [userId] — tests and observability. */
    fun connectionCount(userId: UUID): Int = emittersByUser[userId]?.size ?: 0

    /** Snapshot of the live sessions of [userId] — tests and observability. */
    fun emittersOf(userId: UUID): Set<SseEmitter> = emittersByUser[userId]?.toSet() ?: emptySet()

    /**
     * Graceful shutdown: stops the heartbeat and completes every open
     * stream — clients take their standard reconnect path (backoff) to
     * another pod; «реконнект — норма» (research.md 004 §1).
     */
    @PreDestroy
    fun shutdown() {
        heartbeatScheduler.shutdownNow()
        emittersByUser.values.forEach { userEmitters ->
            userEmitters.forEach { emitter -> runCatching { emitter.complete() } }
        }
        emittersByUser.clear()
    }

    private fun sendHeartbeatToAllUsers() {
        emittersByUser.forEach { (userId, userEmitters) ->
            userEmitters.forEach { emitter ->
                sendDroppingDeadConnections(userId, emitter, heartbeatFrame())
            }
        }
    }

    /**
     * A write to a broken socket (closed tab, half-open TCP) may surface
     * as any transport failure — the emitter is dead weight from now on:
     * unregister it and mark it complete so the container releases the
     * async request; the failure is routine, so it logs at debug with
     * ids only (never payloads, constitution V).
     */
    @Suppress("TooGenericExceptionCaught") // a dead socket signals itself by throwing
    private fun sendDroppingDeadConnections(
        userId: UUID,
        emitter: SseEmitter,
        frameText: String,
    ) {
        try {
            emitter.send(frameText, FRAME_MEDIA_TYPE)
        } catch (failure: Exception) {
            unregister(userId, emitter)
            runCatching { emitter.complete() }
            log.debug(
                "SSE session of user <{}> dropped after a failed frame write; the client reconnects (FR-009): {}",
                userId,
                failure.message,
            )
        }
    }

    /**
     * Per-instance subscribe/unsubscribe hooks of the T019 dynamic
     * Lettuce subscription on `rt:user:{userId}`.
     */
    interface ConnectionListener {
        fun onFirstConnection(userId: UUID)

        fun onLastConnectionClosed(userId: UUID)
    }

    private companion object {
        /** realtime-channel.md §2: the keepalive comment of the heartbeat frame. */
        const val HEARTBEAT_COMMENT = "ka"
        const val HEARTBEAT_THREAD_NAME = "sse-heartbeat"

        /**
         * Frames are pre-rendered WHATWG text (one SSE frame per send) and
         * must reach the wire as UTF-8: `text/plain` alone would fall back
         * to StringHttpMessageConverter's ISO-8859-1 default and mojibake
         * the Cyrillic payloads of the contract (realtime-channel.md §2).
         */
        val FRAME_MEDIA_TYPE: MediaType = MediaType("text", "plain", StandardCharsets.UTF_8)

        /** realtime-channel.md §2: the comment-only keepalive frame. */
        fun heartbeatFrame(): String = ":$HEARTBEAT_COMMENT\n\n"

        /** realtime-channel.md §2: one `event:` line + ONE `data:` line with single-line JSON. */
        fun eventFrame(
            eventName: String,
            jsonPayload: String,
        ): String = "event:$eventName\ndata:$jsonPayload\n\n"
    }
}
