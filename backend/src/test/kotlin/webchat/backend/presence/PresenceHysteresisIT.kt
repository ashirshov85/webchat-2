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
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.chats.MessagingTestSupport
import webchat.backend.config.PresenceProperties
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T023 (tasks.md Phase 4, US2): the anti-flap contract of 007 — debounce and
 * hysteresis (FR-004) — over the real stack (Testcontainers PG+Redis through
 * [MessagingTestSupport], the tightened test-profile windows of T003: ttl 3 s,
 * hysteresis 2 s, heartbeat 1 s, poller 200 ms):
 *
 *  * SC-002 «метро»: a series of ≥ 10 breaks/reconnects, EVERY gap shorter
 *    than the hysteresis window, publishes ZERO `presence.updated` switches —
 *    the reconnect cancels the pending offline before it is ever due, and the
 *    №36 snapshot keeps the very rev of the initial `online` (a suppressed
 *    series must not even advance the revision);
 *  * US2 AC3, a single break LONGER than the window: exactly ONE `offline`
 *    (the CAS of `presence:pub` rules a second one out) — never published
 *    before the window expires — followed by exactly ONE `online` on the
 *    reconnect, with a strictly increasing rev chain across all three frames;
 *  * edge «разрыв на границе окна»: a break of exactly the hysteresis window
 *    races the deferred CAS against the returning registration — BOTH honest
 *    outcomes are accepted (zero switches, or one offline followed by one
 *    online), but NEVER a flap (≥ 2 switches per series is a violation), and
 *    the published state always converges back to `online`;
 *  * US2 AC4, «чередование устройств»: devices of one user alternating inside
 *    the window (one drops, the other returns within the gap) never surface
 *    an intermediate `offline` — the audience keeps seeing the untouched
 *    `online`/rev.
 *
 * Registrations outlive the 3 s TTL only through explicit №37 renewals at the
 * test-profile heartbeat interval (the [HeartbeatPump] of T011) whenever a
 * scenario's tail is longer than the TTL — every break below is an EXPLICIT
 * client close (US2's metro gap), so the silent-expiry path stays US3's
 * business (T026).
 *
 * NOTE (TDD, constitution VI): written BEFORE T024/T025 — until the offq
 * poller (`PresenceTransitionScheduler`) drains `presence:offq`, the
 * long-break scenario fails (RED) exactly at the awaited `offline`
 * publication (the store schedules, nobody executes), while the three
 * no-flap scenarios hold the suppression invariants the implementation must
 * never regress. Budgets derive from [PresenceProperties] exactly like
 * PresenceIT: hysteresis + poller ticks + CI slack.
 */
@Suppress("TooManyFunctions") // T023: one helper per contract surface №36 + the SSE lifecycle/pump fixtures
class PresenceHysteresisIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
) : MessagingTestSupport() {
    /** A suppressed transition (re-register, covered break) must stay silent for the window + poller slack. */
    private val windowWithNoEvent: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /** An uncovered break publishes `offline` after hysteresis + a couple of poller ticks (+ CI slack). */
    private val offlinePublicationBudget: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /** US2 AC3 «не раньше истечения окна»: the publication is due at detection+hysteresis, never before. */
    private val preWindowQuiet: Duration = presenceProperties.hysteresis.minusMillis(WINDOW_MARGIN_MILLIS)

    /** A metro/alternation gap: half the window, so the re-register beats the due time with a wide margin. */
    private val shortGapMillis: Long = presenceProperties.hysteresis.toMillis() / 2

    /**
     * SC-002 (metro): ≥ 10 break/reconnect cycles, every gap half the
     * hysteresis window — the audience observes ZERO `presence.updated`
     * frames for the whole series, and the №36 snapshot still answers the
     * very `online`/rev of the initial transition (a suppressed series does
     * not advance the revision — only the CAS legs ever do).
     */
    @Test
    fun `metro series of short breaks publishes zero switches and keeps the online rev`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        var subjectStream = openUserEvents(alice)
        var metroConnection: UUID? = null
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(subjectStream)
            val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
            val onlineRev = onlineEvent[REV_FIELD].asLong()
            assertThat(onlineEvent[USER_ID_FIELD].asText()).isEqualTo(alice.id.toString())
            assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)

            repeat(METRO_CYCLES) {
                subjectStream.close()
                Thread.sleep(shortGapMillis)
                subjectStream = openUserEvents(alice)
                metroConnection = awaitConnectedFrame(subjectStream)
            }

            // the tail is longer than the 3 s TTL — keep the last registration explicitly alive
            val pumpedConnections = CopyOnWriteArraySet<UUID>()
            pumpedConnections += metroConnection!!
            val pumpFailures = CopyOnWriteArrayList<String>()
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // SC-002: nothing buffered from the series and nothing new for the window + slack
                assertNoPresenceEventFollows(bobStream, windowWithNoEvent)
            } finally {
                pump.close()
            }
            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the explicit №37 renewals at the 1 s interval must keep the last registration known: %s",
                    pumpFailures,
                ).isEmpty()

            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
        } finally {
            runCatching { bobStream.close() }
            runCatching { subjectStream.close() }
        }
    }

    /**
     * US2 AC3 / SC-002 second half: a single break LONGER than the hysteresis
     * window publishes exactly ONE `offline` — never before the window
     * expires — and the reconnect publishes exactly ONE `online`; the rev
     * chain strictly increases across online→offline→online, the №36
     * snapshot flips with each frame, and the CAS suppresses every echo
     * (two `offline` frames for one break would be a violation).
     */
    @Test
    fun `break longer than the window publishes exactly one offline and one return`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(aliceStream)
            val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
            val onlineRev = onlineEvent[REV_FIELD].asLong()

            // the long break: an explicit client close, then silence past the whole window
            aliceStream.close()

            // «не раньше истечения окна»: inside hysteresis-margin nothing may be published
            assertNoPresenceEventFollows(bobStream, preWindowQuiet)

            val offlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, offlinePublicationBudget)
            assertThat(offlineEvent[USER_ID_FIELD].asText()).isEqualTo(alice.id.toString())
            assertThat(offlineEvent[STATUS_FIELD].asText())
                .overridingErrorMessage("an uncovered break must publish offline, got <%s>", offlineEvent)
                .isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev)
                .overridingErrorMessage("rev must strictly increase across the online→offline transition")
                .isGreaterThan(onlineRev)

            // the CAS rules a second offline out — exactly one publication per break
            assertNoPresenceEventFollows(bobStream, windowWithNoEvent)
            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlineRev)

            // the one return: the reconnect flips the published status back immediately (debounce-online = 0)
            openUserEvents(alice).use { reconnected ->
                awaitConnectedFrame(reconnected)
                val returnEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
                assertThat(returnEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                val returnRev = returnEvent[REV_FIELD].asLong()
                assertThat(returnRev)
                    .overridingErrorMessage("rev must strictly increase across the offline→online return")
                    .isGreaterThan(offlineRev)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, returnRev)
                assertNoPresenceEventFollows(bobStream, windowWithNoEvent)
            }
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * Edge «разрыв на границе окна» (spec edge cases): a break of exactly the
     * hysteresis window races the deferred offline CAS against the returning
     * registration — the observer sees EITHER zero switches (the re-register
     * cancelled the pending publication) OR exactly one offline followed by
     * one online; ≥ 2 switches would be flapping and must fail. Whatever the
     * race outcome, the published state converges back to `online`.
     */
    @Test
    fun `break at the window boundary settles in at most one switch and converges back online`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(aliceStream)
            val initialRev =
                bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)[REV_FIELD].asLong()

            // the boundary break: exactly the hysteresis window, then the device returns
            aliceStream.close()
            Thread.sleep(presenceProperties.hysteresis.toMillis())

            openUserEvents(alice).use { reconnected ->
                awaitBoundarySettling(bobStream, reconnected, observer = bob, subject = alice, initialRev = initialRev)
            }
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * The boundary-drain leg of the edge above: the returned device gets the
     * explicit №37 keep-alive (the drain window outlives the 3 s TTL), the
     * observer's whole window is drained, and the race outcome must be one
     * of the two honest settle paths.
     */
    private fun awaitBoundarySettling(
        observerStream: UserEventsStream,
        reconnected: UserEventsStream,
        observer: MessagingUser,
        subject: MessagingUser,
        initialRev: Long,
    ) {
        val reconnection = awaitConnectedFrame(reconnected)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        pumpedConnections += reconnection
        val pumpFailures = CopyOnWriteArrayList<String>()
        val pump = startHeartbeatPump(subject, pumpedConnections, pumpFailures)
        val switches: List<JsonNode>
        try {
            switches =
                drainPresenceEvents(observerStream, offlinePublicationBudget)
                    .filter { it[USER_ID_FIELD].asText() == subject.id.toString() }
        } finally {
            pump.close()
        }
        assertThat(pumpFailures)
            .overridingErrorMessage("the №37 renewals must hold the returned registration: %s", pumpFailures)
            .isEmpty()

        val offlines = switches.filter { it[STATUS_FIELD].asText() == STATUS_OFFLINE }
        val onlines = switches.filter { it[STATUS_FIELD].asText() == STATUS_ONLINE }
        assertThat(offlines.size.toLong())
            .overridingErrorMessage(
                "a boundary break may surface AT MOST one offline (no flapping), got %d in %s",
                offlines.size,
                switches,
            ).isLessThanOrEqualTo(1L)

        if (offlines.isEmpty()) {
            // suppressed: the re-register landed first — an already-online CAS stays silent
            assertThat(onlines)
                .overridingErrorMessage("a suppressed boundary break must publish NOTHING at all, got %s", switches)
                .isEmpty()
            assertSnapshotStatus(observer, subject.id, STATUS_ONLINE, initialRev)
        } else {
            // executed: exactly one offline, then exactly one online return, rev strictly climbing
            assertThat(onlines)
                .overridingErrorMessage(
                    "an executed boundary break must be followed by exactly one online return, got %s",
                    switches,
                ).hasSize(1)
            val offlineRev = offlines.single()[REV_FIELD].asLong()
            val returnRev = onlines.single()[REV_FIELD].asLong()
            assertThat(offlineRev).isGreaterThan(initialRev)
            assertThat(returnRev).isGreaterThan(offlineRev)
            assertSnapshotStatus(observer, subject.id, STATUS_ONLINE, returnRev)
        }
    }

    /**
     * US2 AC4 «чередование устройств»: one device drops, the other returns
     * within the window, again and again — the alternation never surfaces an
     * intermediate `offline`: the pending publication of each drop is
     * cancelled by the next device's registration, and the snapshot keeps
     * the untouched `online`/rev through the whole series.
     */
    @Test
    fun `alternating devices inside the window never surface an intermediate offline`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        var deviceStream = openUserEvents(alice)
        var deviceConnection: UUID? = null
        try {
            awaitConnectedFrame(bobStream)
            deviceConnection = awaitConnectedFrame(deviceStream)
            val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
            val onlineRev = onlineEvent[REV_FIELD].asLong()

            repeat(ALTERNATION_CYCLES) {
                deviceStream.close()
                Thread.sleep(shortGapMillis)
                deviceStream = openUserEvents(alice)
                deviceConnection = awaitConnectedFrame(deviceStream)
            }

            val pumpedConnections = CopyOnWriteArraySet<UUID>()
            pumpedConnections += deviceConnection!!
            val pumpFailures = CopyOnWriteArrayList<String>()
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // any intermediate offline of the series would surface here — none may exist
                assertNoPresenceEventFollows(bobStream, windowWithNoEvent)
            } finally {
                pump.close()
            }
            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the explicit №37 renewals at the 1 s interval must keep the alternating registration: %s",
                    pumpFailures,
                ).isEmpty()

            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
        } finally {
            runCatching { bobStream.close() }
            runCatching { deviceStream.close() }
        }
    }

    /**
     * Consumes the opening `retry: 3000` frame and the №18 `connected
     * {connectionId}` opening frame right after it (presence-events.md §1),
     * asserting the exact frame order and payload shape; returns the
     * registered connectionId.
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

    /** Contract №36 `GET /users/me/presence?userIds=` for one target: 200 + the per-request item. */
    private fun snapshotOf(
        observer: MessagingUser,
        target: UUID,
    ): JsonNode {
        val response =
            restTemplate.exchange(
                UriComponentsBuilder
                    .fromPath(SNAPSHOT_PATH)
                    .queryParam(USER_IDS_PARAM, target.toString())
                    .build()
                    .toUriString(),
                HttpMethod.GET,
                HttpEntity<Void>(null, bearerHeaders(observer)),
                String::class.java,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№36 GET /users/me/presence must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val item = objectMapper.readTree(response.body)[ITEMS_FIELD].single()
        assertThat(item[USER_ID_FIELD].asText())
            .overridingErrorMessage("№36 must answer the requested userId, got %s", item)
            .isEqualTo(target.toString())
        return item
    }

    /** №36 must answer the published status with exactly the expected rev (SC-006). */
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

    /**
     * Consumes every `presence.updated` payload delivered within [window]
     * (other frames are skipped, a per-frame timeout is the expected clean
     * exit) — the boundary race needs the FULL stream of the window, not a
     * first-match wait, to assert «at most one switch» honestly.
     */
    private fun drainPresenceEvents(
        stream: UserEventsStream,
        window: Duration,
    ): List<JsonNode> {
        val events = mutableListOf<JsonNode>()
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return events
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError(
                            "stream ended while draining <$PRESENCE_UPDATED_EVENT> frames",
                        )
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return events
                }
            if (frame.event == PRESENCE_UPDATED_EVENT) events.add(objectMapper.readTree(frame.data))
        }
    }

    /**
     * Consumes frames for [window] and fails the moment a `presence.updated`
     * frame shows up — the «zero switches» half of every scenario. A
     * per-frame timeout (no COMPLETE frame within the window) is the
     * expected clean exit.
     */
    private fun assertNoPresenceEventFollows(
        stream: UserEventsStream,
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
                            "stream ended while asserting that no <$PRESENCE_UPDATED_EVENT> frame follows",
                        )
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            assertThat(frame.event)
                .overridingErrorMessage(
                    "no further <%s> frame may follow within %s",
                    PRESENCE_UPDATED_EVENT,
                    window,
                ).isNotEqualTo(PRESENCE_UPDATED_EVENT)
        }
    }

    /**
     * Starts the explicit keep-alive of T011: a daemon thread posting №37 for
     * every live [connections] entry at the test-profile heartbeat interval,
     * recording any answer other than 204/429 into [failures] (a 404 means a
     * registration was lost — exactly what the renewals must prevent).
     */
    private fun startHeartbeatPump(
        user: MessagingUser,
        connections: Set<UUID>,
        failures: MutableList<String>,
    ): HeartbeatPump {
        val pump =
            HeartbeatPump(presenceProperties.heartbeatInterval) {
                connections.forEach { connectionId ->
                    val response =
                        restTemplate.postForEntity(
                            HEARTBEAT_PATH,
                            HttpEntity(mapOf(CONNECTION_ID_FIELD to connectionId.toString()), bearerJsonHeaders(user)),
                            String::class.java,
                        )
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

    /** Fixed-delay №37 keep-alive loop of T011 (the explicit 1 s test-profile renewals). */
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

        /** SC-002: ≥ 10 breaks/recoveries shorter than the window — zero switches. */
        const val METRO_CYCLES = 10

        /** US2 AC4: the alternating-device series of the same covered-gap shape. */
        const val ALTERNATION_CYCLES = 6

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L

        /** The «not before the window» guard keeps a margin inside the hysteresis. */
        const val WINDOW_MARGIN_MILLIS = 500L
        const val PUMP_THREAD_NAME = "presence-hysteresis-it-heartbeat"

        /** SC-001: from the subject's `connected` frame to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)
    }
}
