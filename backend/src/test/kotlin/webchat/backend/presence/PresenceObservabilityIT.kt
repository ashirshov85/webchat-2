package webchat.backend.presence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import webchat.backend.config.PresenceProperties
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T036 (tasks.md 007 Phase 7, FR-009/SC-008, presence-events.md §5):
 * the observability validation of the 007 presence feature — the FOUR
 * meter families of [PresenceMetrics] (T010) must answer on
 * `/actuator/prometheus` and react in the two scenarios they were built
 * to observe, по образцу GroupObservabilityIT (006 T068):
 *
 *  * the TOGGLE scenario (SC-001 signal): one subject going online,
 *    then breaking LONGER than the hysteresis window (exactly one
 *    `offline` publication), then returning — every PUBLISHED
 *    transition grows `webchat_presence_events_published_total` BY ITS
 *    `status` TAG (`online` twice, `offline` once), every publication
 *    adds `webchat_realtime_push_seconds{event=presence.updated}`
 *    samples (the reused 004 push pipeline timer, separable from the
 *    messaging frames), and the gauge `webchat_presence_online_users`
 *    follows the subject's own −1/+1 steps of the Redis
 *    `presence:online:count` counter (STRICT inequalities around the
 *    awaited transition events, never absolutes — the static IT Redis
 *    is shared by every IT of the run, and the users of earlier
 *    classes may legally settle offline for up to their contexts' :ka
 *    detection lag while this scenario runs);
 *  * the SUPPRESSED break (SC-002 «метро»): break/reconnect cycles
 *    shorter than the hysteresis window publish NOTHING and grow
 *    `webchat_presence_hysteresis_suppressed_total` — every metro gap
 *    the window swallowed is counted, never silent.
 *
 * The meters are read the way an operator reads them — by scraping the
 * real `/actuator/prometheus` text exposition (never through the
 * MeterRegistry), so the verification covers the whole export path:
 * registration, naming, the `status`/`event` tags and the endpoint
 * exposure itself. Deltas between awaited snapshots (not absolute
 * values) make the IT immune to the shared Spring test context
 * carrying counters from earlier IT classes.
 *
 * CONTEXT DISCIPLINE: unlike GroupObservabilityIT this class does NOT
 * add `@AutoConfigureObservability` — the prometheus endpoint is
 * exposed by the application configuration itself, and staying in the
 * SHARED presence context keeps exactly ONE `PresenceTransitionScheduler`
 * alive in the JVM (application-test.yml disables the pollers for every
 * OTHER cached test context; PresenceTestSupport re-enables them here),
 * so the offq poller that counts the suppressions and deferred `offline`
 * publications of this scenario IS this context's — no due-entry race,
 * and the registry-based asserts of the sibling presence ITs stay
 * deterministic symmetrically. The observer's №18 registration outlives
 * the 3 s TTL through explicit №37 renewals (the [HeartbeatPump] of
 * T011), so the scenario judges the SUBJECT's transitions, never the
 * observer's own silent expiry.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("TooManyFunctions") // T036: one helper per scrape/gauge/SSE-lifecycle concern, mirroring GroupObservabilityIT
class PresenceObservabilityIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
) : PresenceTestSupport() {
    /** An uncovered break publishes `offline` after hysteresis + a couple of poller ticks (+ CI slack). */
    private val offlinePublicationBudget: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /** A metro gap: half the window, so the re-register beats the due time with a wide margin. */
    private val shortGapMillis: Long = presenceProperties.hysteresis.toMillis() / 2

    /**
     * SC-008 / FR-009: the toggle scenario (online → suppressed metro
     * series → long break with exactly one `offline` → one `online`
     * return) — the three counters answer with at least the exact
     * scenario minimums BY THEIR TAGS, the reused push timer gains
     * `event=presence.updated` samples per publication, and the
     * online-users gauge follows the subject's own steps DOWN and UP
     * (strict inequalities: the counter is exact by construction —
     * every Lua transition maintains it — but the earlier classes'
     * users may still be settling offline through their :ka detection
     * lag, so only the subject's own −1/+1 steps are pinned, never
     * absolutes).
     */
    @Test
    @Order(1)
    fun `toggle scenario and suppressed break grow the presence meters by their tags`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        var subjectStream: UserEventsStream? = null
        val pumpFailures = CopyOnWriteArrayList<String>()
        var pump: HeartbeatPump? = null
        try {
            val observerConnection = awaitConnectedFrame(bobStream)

            // the observer's registration outlives the 3 s TTL through explicit №37 renewals
            pump = startHeartbeatPump(bob, setOf(observerConnection), pumpFailures)

            // the counters' baseline: BEFORE the subject's first registration — only the (pumped)
            // observer and the settled remainder of the run contribute; deltas pin the scenario
            val eventsOnlineBefore = current(EVENTS_PUBLISHED_TOTAL, TAG_STATUS_ONLINE)
            val eventsOfflineBefore = current(EVENTS_PUBLISHED_TOTAL, TAG_STATUS_OFFLINE)
            val suppressedBefore = current(HYSTERESIS_SUPPRESSED_TOTAL)
            val pushCountBefore = current(PUSH_SECONDS_COUNT, TAG_EVENT_PRESENCE_UPDATED)

            // SC-001 toggle leg one: the subject connects — ONE online publication, counter +1
            subjectStream = openUserEvents(alice)
            awaitConnectedFrame(subjectStream)
            val onlineRev = awaitSubjectOnline(bobStream, alice)
            val gaugeWithSubject = current(ONLINE_USERS)
            assertThat(gaugeWithSubject)
                .overridingErrorMessage(
                    "SC-008: <%s> must count the subject once she is online-published, got <%s>",
                    ONLINE_USERS,
                    gaugeWithSubject,
                ).isGreaterThanOrEqualTo(SUBJECT_STEP)

            // SC-002 metro: short covered breaks — suppressed, never published
            subjectStream = runSuppressedMetroCycles(alice, subjectStream)
            assertGrows(suppressedBefore, MINIMUM_ONE, HYSTERESIS_SUPPRESSED_TOTAL)

            // SC-001 toggle leg two: the uncovered break — exactly ONE offline publication, counter −1
            subjectStream?.close()
            subjectStream = null
            val offline = awaitSubjectOffline(bobStream, alice, onlineRev, gaugeWithSubject)

            // SC-001 toggle leg three: the return — ONE online publication (debounce-online = 0), counter +1
            subjectStream = openUserEvents(alice)
            awaitConnectedFrame(subjectStream)
            val returnRev = awaitSubjectOnline(bobStream, alice)
            assertThat(returnRev)
                .overridingErrorMessage("rev must strictly increase across the offline→online return")
                .isGreaterThan(offline.rev)
            val gaugeAfterReturn = current(ONLINE_USERS)
            assertThat(gaugeAfterReturn)
                .overridingErrorMessage(
                    "SC-008: <%s> must RISE by the subject's own +1 after the return publication " +
                        "(was <%s> without her, got <%s>)",
                    ONLINE_USERS,
                    offline.gauge,
                    gaugeAfterReturn,
                ).isGreaterThan(offline.gauge)

            // FR-009: every publication counted BY ITS STATUS TAG (initial online + the return),
            // every metro suppression counted, one push-pipeline sample per publication
            assertGrows(eventsOnlineBefore, MINIMUM_TWO, EVENTS_PUBLISHED_TOTAL, TAG_STATUS_ONLINE)
            assertGrows(eventsOfflineBefore, MINIMUM_ONE, EVENTS_PUBLISHED_TOTAL, TAG_STATUS_OFFLINE)
            assertGrows(pushCountBefore, MINIMUM_THREE, PUSH_SECONDS_COUNT, TAG_EVENT_PRESENCE_UPDATED)
            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the explicit №37 renewals at the 1 s interval must keep the observer's " +
                        "registration known: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { subjectStream?.close() }
            pump?.close()
        }
    }

    /**
     * The exposure head of the acceptance (quickstart §3 step 4): after
     * the scenario above, every one of the FOUR presence-events.md §5
     * meter families is present in the real exposition with its HELP
     * header AND a live sample series — `status` legs `online` and
     * `offline`, the hysteresis counter, the online-users gauge and the
     * reused push timer tagged `event=presence.updated`.
     */
    @Test
    @Order(2)
    fun `all four FR-009 meter families are exposed on actuator prometheus`() {
        val exposition = scrape()

        FR009_METERS.forEach { meter ->
            assertThat(exposition)
                .overridingErrorMessage(
                    "SC-008 requires <%s> on /actuator/prometheus, got:%n%s",
                    meter,
                    excerpt(exposition, meter),
                ).contains("# HELP $meter")
        }
        assertThat(sampleValue(exposition, ONLINE_USERS))
            .overridingErrorMessage(
                "SC-008 requires a live <%s> sample series, got:%n%s",
                ONLINE_USERS,
                excerpt(exposition, ONLINE_USERS),
            ).isNotNull
        assertThat(sampleValue(exposition, EVENTS_PUBLISHED_TOTAL, TAG_STATUS_ONLINE))
            .overridingErrorMessage(
                "SC-008 requires a <%s{status=\"online\"}> sample series, got:%n%s",
                EVENTS_PUBLISHED_TOTAL,
                excerpt(exposition, EVENTS_PUBLISHED_TOTAL),
            ).isNotNull
        assertThat(sampleValue(exposition, EVENTS_PUBLISHED_TOTAL, TAG_STATUS_OFFLINE))
            .overridingErrorMessage(
                "SC-008 requires a <%s{status=\"offline\"}> sample series, got:%n%s",
                EVENTS_PUBLISHED_TOTAL,
                excerpt(exposition, EVENTS_PUBLISHED_TOTAL),
            ).isNotNull
        assertThat(sampleValue(exposition, HYSTERESIS_SUPPRESSED_TOTAL))
            .overridingErrorMessage(
                "SC-008 requires a live <%s> sample series, got:%n%s",
                HYSTERESIS_SUPPRESSED_TOTAL,
                excerpt(exposition, HYSTERESIS_SUPPRESSED_TOTAL),
            ).isNotNull
        assertThat(sampleValue(exposition, PUSH_SECONDS_COUNT, TAG_EVENT_PRESENCE_UPDATED))
            .overridingErrorMessage(
                "SC-008 requires a <%s{event=\"presence.updated\"}> sample series, got:%n%s",
                PUSH_SECONDS_COUNT,
                excerpt(exposition, PUSH_SECONDS_COUNT),
            ).isNotNull
    }

    // --- scrape helpers: the operator's view of the meters, not the registry's ---

    /** GET /actuator/prometheus — the real text exposition. */
    private fun scrape(): String {
        val response = restTemplate.getForEntity(PROMETHEUS_PATH, String::class.java)
        assertThat(response.statusCode)
            .overridingErrorMessage("the prometheus exposition must answer 200, got <%s>", response.statusCode)
            .isEqualTo(HttpStatus.OK)
        return response.body.orEmpty()
    }

    /**
     * One sample value of [sampleName] whose label set contains every
     * [tags] entry (e.g. `status="online"`); `0.0` when the meter has no
     * sample yet — the lawful «before» value of a delta.
     */
    private fun current(
        sampleName: String,
        vararg tags: String,
    ): Double = sampleValue(scrape(), sampleName, *tags) ?: ZERO_SAMPLES

    /** The exposition-matched sample value, or `null` when the series is absent. */
    private fun sampleValue(
        exposition: String,
        sampleName: String,
        vararg tags: String,
    ): Double? {
        val wanted = tags.joinToString(separator = "")
        val sample =
            exposition
                .lineSequence()
                .mapNotNull { line -> SAMPLE_PATTERN.matchEntire(line.trim()) }
                .firstOrNull { match ->
                    match.groupValues[SAMPLE_NAME_GROUP] == sampleName &&
                        (wanted.isEmpty() || match.groupValues[SAMPLE_LABELS_GROUP].contains(wanted))
                }
        return sample?.let { match -> match.groupValues[SAMPLE_VALUE_GROUP].toDouble() }
    }

    /** Awaits the scenario minimum growth of a sample (deltas, not absolutes). */
    private fun assertGrows(
        before: Double,
        byAtLeast: Double,
        sampleName: String,
        vararg tags: String,
    ) {
        await().atMost(METER_WAIT).untilAsserted {
            val now = current(sampleName, *tags)
            assertThat(now - before)
                .overridingErrorMessage(
                    "SC-008: <%s{%s}> must grow by at least <%s> after the scenario " +
                        "(before=<%s>, now=<%s>)",
                    sampleName,
                    tags.joinToString(separator = ","),
                    byAtLeast,
                    before,
                    now,
                ).isGreaterThanOrEqualTo(byAtLeast)
        }
    }

    // --- SSE lifecycle helpers of the 007 scenarios (T011/T023 idioms) ---

    /** One awaited `online` publication of the subject on the observer's stream — the published rev. */
    private fun awaitSubjectOnline(
        observerStream: UserEventsStream,
        subject: MessagingUser,
    ): Long {
        val event = observerStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
        assertThat(event[USER_ID_FIELD].asText()).isEqualTo(subject.id.toString())
        assertThat(event[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
        return event[REV_FIELD].asLong()
    }

    /**
     * The awaited `offline` publication of the uncovered break —
     * exactly one, rev strictly above [onlineRev] — plus the post-drop
     * gauge reading (the subject's own −1 makes the drop STRICT even
     * while earlier classes' users keep settling offline).
     */
    private fun awaitSubjectOffline(
        observerStream: UserEventsStream,
        subject: MessagingUser,
        onlineRev: Long,
        gaugeWithSubject: Double,
    ): TransitionReading {
        val event = observerStream.awaitEvent(PRESENCE_UPDATED_EVENT, offlinePublicationBudget)
        assertThat(event[USER_ID_FIELD].asText()).isEqualTo(subject.id.toString())
        assertThat(event[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
        val offlineRev = event[REV_FIELD].asLong()
        assertThat(offlineRev)
            .overridingErrorMessage("rev must strictly increase across the online→offline transition")
            .isGreaterThan(onlineRev)
        val gaugeWithoutSubject = current(ONLINE_USERS)
        assertThat(gaugeWithoutSubject)
            .overridingErrorMessage(
                "SC-008: <%s> must DROP by the subject's own −1 after the offline publication " +
                    "(was <%s> with her, got <%s>)",
                ONLINE_USERS,
                gaugeWithSubject,
                gaugeWithoutSubject,
            ).isLessThan(gaugeWithSubject)
        return TransitionReading(offlineRev, gaugeWithoutSubject)
    }

    /**
     * SC-002 «метро»: [SUPPRESSED_CYCLES] break/reconnect gaps, every
     * one half the hysteresis window — the re-register beats the due
     * time with a wide margin, so the whole series is suppressed;
     * returns the LAST live stream of the subject.
     */
    private fun runSuppressedMetroCycles(
        subject: MessagingUser,
        initial: UserEventsStream,
    ): UserEventsStream {
        var stream = initial
        repeat(SUPPRESSED_CYCLES) {
            stream.close()
            Thread.sleep(shortGapMillis)
            stream = openUserEvents(subject)
            awaitConnectedFrame(stream)
        }
        return stream
    }

    /** One awaited transition's published rev plus the gauge reading taken right after it. */
    private data class TransitionReading(
        val rev: Long,
        val gauge: Double,
    )

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
        val payload: JsonNode = objectMapper.readTree(connected.data)
        assertThat(payload.fieldNames().asSequence().toList())
            .overridingErrorMessage("the connected payload must have exactly the ConnectedEvent fields")
            .containsExactly(CONNECTION_ID_FIELD)
        return UUID.fromString(payload[CONNECTION_ID_FIELD].asText())
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

    private fun excerpt(
        exposition: String,
        meter: String,
    ): String =
        exposition
            .lineSequence()
            .filter { line -> meter in line }
            .joinToString(separator = "\n")

    private companion object {
        /** presence-events.md §5 / research.md §F names (FR-009, SC-008) — the T010 vocabulary. */
        const val EVENTS_PUBLISHED_TOTAL = "webchat_presence_events_published_total"
        const val HYSTERESIS_SUPPRESSED_TOTAL = "webchat_presence_hysteresis_suppressed_total"
        const val ONLINE_USERS = "webchat_presence_online_users"

        /** research.md §F: the REUSED 004 push-pipeline timer (no new pipe) — the timer's `_count` leg. */
        const val PUSH_SECONDS_COUNT = "webchat_realtime_push_seconds_count"
        const val PUSH_SECONDS = "webchat_realtime_push_seconds"

        val FR009_METERS = listOf(EVENTS_PUBLISHED_TOTAL, HYSTERESIS_SUPPRESSED_TOTAL, ONLINE_USERS, PUSH_SECONDS)

        /** Label selectors as they appear inside the exposition braces (presence-events.md §2/§5). */
        const val TAG_STATUS_ONLINE = "status=\"online\""
        const val TAG_STATUS_OFFLINE = "status=\"offline\""
        const val TAG_EVENT_PRESENCE_UPDATED = "event=\"presence.updated\""

        const val CONNECTED_EVENT = "connected"
        const val PRESENCE_UPDATED_EVENT = "presence.updated"
        const val CONNECTION_ID_FIELD = "connectionId"
        const val USER_ID_FIELD = "userId"
        const val STATUS_FIELD = "status"
        const val REV_FIELD = "rev"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val HEARTBEAT_PATH = "/api/v1/users/me/presence/heartbeat"
        const val PROMETHEUS_PATH = "/actuator/prometheus"

        /** SC-002: the metro series of short covered breaks — every one counted as suppressed. */
        const val SUPPRESSED_CYCLES = 3

        /** The subject's own contribution to `presence:online:count` — the only step the IT pins. */
        const val SUBJECT_STEP = 1.0

        const val MINIMUM_ONE = 1.0
        const val MINIMUM_TWO = 2.0
        const val MINIMUM_THREE = 3.0
        const val ZERO_SAMPLES = 0.0
        const val RETRY_MILLIS = 3_000L
        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L
        const val PUMP_THREAD_NAME = "presence-observability-it-heartbeat"

        /** SC-001: from the subject's `connected` frame to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val METER_WAIT: Duration = Duration.ofSeconds(10)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)

        /**
         * One exposition sample line: `name{labels} value` (the labels
         * braces may be absent for untagged series; the 007 meters all
         * carry at least the `application` common tag).
         */
        val SAMPLE_PATTERN = Regex("""([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([0-9.eE+-]+)""")
        const val SAMPLE_NAME_GROUP = 1
        const val SAMPLE_LABELS_GROUP = 2
        const val SAMPLE_VALUE_GROUP = 3
    }
}
