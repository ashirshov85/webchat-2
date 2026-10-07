package webchat.backend.presence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.config.PresenceProperties
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T077 (tasks.md 008, Phase 9 bug 1): the full presence LIFECYCLE
 * regression of the reported «асимметрия видимости online-статуса» —
 * ONE scenario walking the subject through every leg the bug was
 * traced across (T074 backend trace), over the real stack:
 *
 *  1. CONNECT → FANOUT ONLINE: the subject's №18 stream opens and the
 *     audience observer receives EXACTLY ONE `presence.updated`
 *     `online` (№18, at-most-once channel) within the SC-001 budget —
 *     the durable CAS + rev++ of `PresenceService.onConnectionOpened`
 *     publishes before any heartbeat is due;
 *  2. HEARTBEAT-ПРОДЛЕНИЕ: explicit №37 renewals at the test-profile
 *     1 s interval carry the registration across a window of 2×TTL —
 *     a live connection must NOT expire (the T076 half of the bug:
 *     «статус сбрасывается при живом соединении»): no offline flap
 *     reaches the audience, №36 keeps answering `online` with the
 *     UNCHANGED rev (a renewal is not a transition) and the next beat
 *     still answers 204;
 *  3. EXPIRY → FANOUT OFFLINE: the beats fall silent with the socket
 *     still mounted (the subject walked away — no close, no logout);
 *     the registration self-expires by its own score, the deferred
 *     offq publish executes and the observer converges to EXACTLY ONE
 *     `offline` with a STRICTLY greater rev inside the SC-003 budget —
 *     the very «A ушёл → B видит офлайн» acceptance of the bug report.
 *
 * After EVERY leg the №36 snapshot must agree with the last published
 * event (status + the very rev) — the backend's designated heal the
 * frontend refetch of T075 (variant б) relies on: an observer that
 * lost the frame converges through №36 without any refresh.
 *
 * Window budgets derive from [PresenceProperties] exactly like
 * PresenceIT/PresenceExpiryIT: windows + poller ticks + CI slack.
 */
@Suppress("LargeClass") // T077: one lifecycle scenario per leg + the №18/№36/№37 fixtures
class PresenceLifecycleIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
) : PresenceTestSupport() {
    /** FR-002 keep-alive leg: > 2× ttl of renewals without an expiry (PresenceIT's window). */
    private val keepAliveWindow: Duration = presenceProperties.ttl.multipliedBy(2).plusSeconds(1)

    /** SC-003 «уход»: last beat + ttl + hysteresis + poller ticks + CI slack (crashQuietWindow of T026). */
    private val silentExpiryBudget: Duration =
        presenceProperties.heartbeatInterval
            .plus(presenceProperties.ttl)
            .plus(presenceProperties.hysteresis)
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /** A kept-alive (or already-offline) subject must stay silent for window + ticks + slack. */
    private val quietWindow: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /**
     * The bug-1 lifecycle end to end: connect → fanout online → №37
     * renewals keep the registration alive across 2×TTL → silent expiry
     * (no disconnect) → exactly one offline fanout; №36 agrees with the
     * published event after every leg.
     */
    @Test
    fun `bug 1 lifecycle - connect fans out online, heartbeats keep alive, silent expiry fans out offline`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            val aliceConnection = awaitConnectedFrame(aliceStream)

            // the pump starts BEFORE the first ttl can lapse — the
            // keep-alive leg must never race the registration's score
            pumpedConnections += aliceConnection
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)

            // LEG 1 — connect → fanout online (exactly one frame, SC-001 budget)
            val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
            assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
            val onlineRev = onlineEvent[REV_FIELD].asLong()
            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

            try {
                // LEG 2 — heartbeat-продление: 2×ttl of №37 renewals, no expiry
                assertNoPresenceEventFor(bobStream, alice.id, keepAliveWindow)
            } finally {
                pump.close()
            }
            assertThat(pumpFailures)
                .overridingErrorMessage("№37 renewals across 2×ttl must all stay 204: %s", pumpFailures)
                .isEmpty()
            val renewal = heartbeatUntilSuccess(alice, aliceConnection, RECOVERY_DEADLINE)
            assertThat(renewal.statusCode)
                .overridingErrorMessage("after 2×ttl of renewals the next №37 beat must still find the registration")
                .isEqualTo(HttpStatus.NO_CONTENT)
            // a kept-alive registration is NOT a transition: same status, same rev
            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

            // LEG 3 — silent expiry → fanout offline (the socket stays mounted)
            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, silentExpiryBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText())
                .overridingErrorMessage(
                    "heartbeat silence must converge the subject to offline, got <%s>",
                    offlineEvent,
                ).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev)
                .overridingErrorMessage("rev must strictly increase across the expiry offline transition")
                .isGreaterThan(onlineRev)
            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlineRev)

            // exactly one offline — the CAS rules echoes out
            assertNoPresenceEventFor(bobStream, alice.id, quietWindow)
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * Consumes the opening `retry: 3000` frame and the №18 `connected
     * {connectionId}` opening frame right after it (presence-events.md
     * §1), asserting the exact frame order and payload shape; returns
     * the registered connectionId.
     */
    private fun awaitConnectedFrame(stream: UserEventsStream): UUID {
        val opening = stream.nextFrame(FRAME_BUDGET)
        assertThat(opening)
            .overridingErrorMessage("the user event stream must open with a frame")
            .isNotNull
        assertThat(opening!!.retryMillis)
            .overridingErrorMessage("the opening frame must carry SSE field retry: 3000")
            .isEqualTo(RETRY_MILLIS)
        assertThat(opening.event)
            .overridingErrorMessage("the retry frame must not carry an event: field")
            .isNull()

        val connected = stream.nextFrame(FRAME_BUDGET)
        assertThat(connected)
            .overridingErrorMessage(
                "the frame right after retry must be the connected opening frame (presence-events.md §1)",
            ).isNotNull
        assertThat(connected!!.event)
            .overridingErrorMessage("the opening presence frame must be event: connected")
            .isEqualTo(CONNECTED_EVENT)
        val payload = objectMapper.readTree(connected.data)
        assertThat(fieldNames(payload))
            .overridingErrorMessage("the connected payload must have exactly the ConnectedEvent fields")
            .containsExactly(CONNECTION_ID_FIELD)
        return UUID.fromString(payload[CONNECTION_ID_FIELD].asText())
    }

    /** Waits for the next `presence.updated` of [subjectId] and returns its payload (other subjects are skipped). */
    private fun awaitPresenceEvent(
        stream: UserEventsStream,
        subjectId: UUID,
        timeout: Duration,
    ): JsonNode {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw AssertionError(
                    "timed out after $timeout waiting for <$PRESENCE_UPDATED_EVENT> of subject <$subjectId>",
                )
            }
            val frame =
                stream.nextFrame(Duration.ofNanos(remaining))
                    ?: throw AssertionError("stream ended before <$PRESENCE_UPDATED_EVENT> of <$subjectId> arrived")
            if (frame.event == PRESENCE_UPDATED_EVENT) {
                val payload = objectMapper.readTree(frame.data)
                if (payload[USER_ID_FIELD].asText() == subjectId.toString()) return payload
            }
        }
    }

    /** Fails the moment a `presence.updated` of [subjectId] shows up within [window] (frames of others are skipped). */
    private fun assertNoPresenceEventFor(
        stream: UserEventsStream,
        subjectId: UUID,
        window: Duration,
    ) {
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError(
                            "stream ended while asserting that no <$PRESENCE_UPDATED_EVENT> of <$subjectId> follows",
                        )
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            if (frame.event == PRESENCE_UPDATED_EVENT) {
                val payload = objectMapper.readTree(frame.data)
                assertThat(payload[USER_ID_FIELD].asText())
                    .overridingErrorMessage(
                        "no further <%s> frame of <%s> may follow within %s",
                        PRESENCE_UPDATED_EVENT,
                        subjectId,
                        window,
                    ).isNotEqualTo(subjectId.toString())
            }
        }
    }

    /** Contract №36 `GET /users/me/presence?userIds=` — one item per target of the batch. */
    private fun snapshotBatch(
        observer: MessagingUser,
        targets: List<UUID>,
    ): Map<UUID, JsonNode> {
        val response =
            restTemplate.exchange(
                UriComponentsBuilder
                    .fromPath(SNAPSHOT_PATH)
                    .queryParam(USER_IDS_PARAM, targets.joinToString(",") { it.toString() })
                    .build()
                    .toUriString(),
                HttpMethod.GET,
                HttpEntity<Void>(null, bearerHeaders(observer)),
                String::class.java,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№36 GET /users/me/presence must answer 200 for a batch of %d, got <%s>: %s",
                targets.size,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val items = objectMapper.readTree(response.body)[ITEMS_FIELD]
        return targets.associateWith { target ->
            items.firstOrNull { it[USER_ID_FIELD].asText() == target.toString() }
                ?: throw AssertionError(
                    "№36 must answer one item per requested userId, missing <$target> in ${response.body}",
                )
        }
    }

    /** №36 for one target: asserts 200 + the per-request item and returns it. */
    private fun snapshotOf(
        observer: MessagingUser,
        target: UUID,
    ): JsonNode = snapshotBatch(observer, listOf(target)).getValue(target)

    /** №36 must answer the published status with exactly the expected rev (the T075 heal contract). */
    private fun assertSnapshotStatus(
        observer: MessagingUser,
        target: UUID,
        expectedStatus: String,
        expectedRev: Long,
    ) {
        val item = snapshotOf(observer, target)
        assertThat(item[STATUS_FIELD].asText())
            .overridingErrorMessage(
                "№36 must answer the published <%s> for <%s>, got <%s> in %s",
                expectedStatus,
                target,
                item[STATUS_FIELD].asText(),
                item,
            ).isEqualTo(expectedStatus)
        assertThat(item[REV_FIELD].asLong())
            .overridingErrorMessage(
                "№36 must expose the very published rev <%d> for <%s>, got <%s> in %s",
                expectedRev,
                target,
                item[REV_FIELD].asLong(),
                item,
            ).isEqualTo(expectedRev)
    }

    /** Contract №37 `POST /users/me/presence/heartbeat` — raw response. */
    private fun postHeartbeat(
        user: MessagingUser,
        connectionId: UUID,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            HEARTBEAT_PATH,
            HttpEntity(mapOf(CONNECTION_ID_FIELD to connectionId.toString()), bearerJsonHeaders(user)),
            String::class.java,
        )

    /** Retries №37 across the 429 throttle until 204; any other status fails on the spot. */
    private fun heartbeatUntilSuccess(
        user: MessagingUser,
        connectionId: UUID,
        deadline: Duration,
    ): ResponseEntity<String> {
        var last: ResponseEntity<String>? = null
        val endAt = System.nanoTime() + deadline.toNanos()
        while (System.nanoTime() < endAt) {
            last = postHeartbeat(user, connectionId)
            when (last.statusCode) {
                HttpStatus.NO_CONTENT -> return last
                HttpStatus.TOO_MANY_REQUESTS -> Thread.sleep(RECOVERY_POLL_MILLIS)
                else ->
                    assertThat(last.statusCode)
                        .overridingErrorMessage(
                            "a №37 retry must only ever be throttled or renewed, got <%s>: %s",
                            last.statusCode,
                            last.body,
                        ).isEqualTo(HttpStatus.NO_CONTENT)
            }
        }
        throw AssertionError(
            "№37 for <$connectionId> must recover with 204 within $deadline, last: <${last?.statusCode}> ${last?.body}",
        )
    }

    /**
     * Starts the explicit keep-alive: a daemon thread posting №37 for
     * every live [connections] entry at the test-profile heartbeat
     * interval, recording any answer other than 204/429 into [failures].
     */
    private fun startHeartbeatPump(
        user: MessagingUser,
        connections: Set<UUID>,
        failures: MutableList<String>,
    ): HeartbeatPump {
        val pump =
            HeartbeatPump(presenceProperties.heartbeatInterval) {
                connections.forEach { connectionId ->
                    val response = postHeartbeat(user, connectionId)
                    if (response.statusCode != HttpStatus.NO_CONTENT &&
                        response.statusCode != HttpStatus.TOO_MANY_REQUESTS
                    ) {
                        failures += "№37 for <$connectionId> answered <${response.statusCode}>: ${response.body}"
                    }
                }
            }
        pump.start()
        return pump
    }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    private fun bearerHeaders(user: MessagingUser) = HttpHeaders().apply { setBearerAuth(user.accessToken) }

    private fun bearerJsonHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    /** Fixed-delay №37 keep-alive loop (the explicit test-profile renewals). */
    private class HeartbeatPump(
        private val interval: Duration,
        private val beat: () -> Unit,
    ) {
        private val stopped = AtomicBoolean(false)

        private val thread =
            Thread(
                {
                    while (!stopped.get()) {
                        beat()
                        try {
                            Thread.sleep(interval.toMillis())
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                },
                PUMP_THREAD_NAME,
            ).apply { isDaemon = true }

        fun start() {
            thread.start()
        }

        fun close() {
            stopped.set(true)
            thread.interrupt()
        }
    }

    private companion object {
        const val RETRY_MILLIS = 3_000L
        const val CONNECTED_EVENT = "connected"
        const val PRESENCE_UPDATED_EVENT = "presence.updated"
        const val CONNECTION_ID_FIELD = "connectionId"
        const val USER_ID_FIELD = "userId"
        const val STATUS_FIELD = "status"
        const val REV_FIELD = "rev"
        const val USER_IDS_PARAM = "userIds"
        const val ITEMS_FIELD = "items"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val TIMEOUT_MARKER = "timed out"
        const val SNAPSHOT_PATH = "/api/v1/users/me/presence"
        const val HEARTBEAT_PATH = "/api/v1/users/me/presence/heartbeat"

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L

        const val RECOVERY_POLL_MILLIS = 250L
        const val PUMP_THREAD_NAME = "presence-lifecycle-it-heartbeat"

        /** SC-001: from the subject's `connected` frame to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)
        val RECOVERY_DEADLINE: Duration = Duration.ofSeconds(5)
    }
}
