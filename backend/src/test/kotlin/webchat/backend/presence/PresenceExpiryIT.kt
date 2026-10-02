package webchat.backend.presence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.redis.core.StringRedisTemplate
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
import java.util.concurrent.atomic.AtomicLong

/**
 * T026 (tasks.md Phase 5, US3): the self-expiry contract of 007 — silent
 * heartbeats, stateless instances and the logout bypass (FR-002/FR-005,
 * SC-003) — over the real stack (Testcontainers PG+Redis through
 * [MessagingTestSupport], the tightened test-profile windows of T003: ttl 3 s,
 * hysteresis 2 s, heartbeat 1 s, poller 200 ms):
 *
 *  * SC-003 «крэш»: heartbeat silence WITHOUT any explicit disconnect (the
 *    subject's №18 socket stays mounted — a hung tab / a killed pod) still
 *    converges the published status to `offline` for every observer within
 *    ttl + hysteresis + 2×poller-interval (+ CI slack) — the watch poller
 *    reaps the lapsed registration, the offq poller executes the deferred
 *    CAS, and the №36 snapshot flips with the very rev of the event;
 *  * FR-005 «один пользователь — два инстанса»: two connections of one user
 *    ride the SAME Redis state (one status, one rev for the audience); a
 *    crashed "instance" — one registration lapsing silently while the other
 *    keeps beating — never flickers the published `online`, and the loss of
 *    the second one converges inside the very SC-003 budget;
 *  * US3 AC4 «шторм»: ≥ 10 users expiring simultaneously (an instance crash
 *    with all its sockets), ≥ half RETURNING inside the hysteresis window —
 *    ZERO false `offline` for the returning half (their pending offq entries
 *    are cancelled), EXACTLY ONE `offline` per abandoned subject, and the
 *    `webchat_presence_hysteresis_suppressed_total` counter grows;
 *  * edge «осиротевшая регистрация реконнекта»: an orphaned registration of
 *    a superseded connection self-expires by its own score — its №37 turns
 *    404 `presence_connection_not_found` while the LIVE connection of the
 *    same user still answers 204, and the duplicate never distorts the
 *    published status (data-model 007 §1.1);
 *  * edge «мультидевайс-logout» (research §B2): the №7 logout of ONE session
 *    spares the registrations of the live second session (status stays
 *    `online`, no event), while the logout of the LAST session publishes
 *    `offline` IMMEDIATELY — within a budget strictly smaller than one
 *    hysteresis window, proving the offq bypass (a revoked session cannot
 *    return, the window is pointless);
 *  * «эпоха» rev (data-model 007 §3 / research §C2): after the rev key
 *    lapses (its 7 h TTL cannot be awaited — the documented §3 key is
 *    deleted deliberately), the next transition re-mints the revision from
 *    the now_ms epoch marker and the chain keeps STRICTLY increasing, so the
 *    client's strictly-greater max(rev) merge never breaks.
 *
 * NOTE (TDD, constitution VI): written BEFORE T025/T027/T028/T029 — until
 * the offq poller, the register/unregister watch branches, the watch poller
 * and the logout wiring land, every silent-expiry scenario here fails (RED)
 * exactly at the awaited `offline` publication (nobody reaps the lapsed
 * registrations and nobody drains `presence:offq`), and the last-logout leg
 * stays online for the same reason; the orphan-lapse scenario alone holds
 * invariants the implementation must never regress. Budgets derive from
 * [PresenceProperties] exactly like PresenceIT/PresenceHysteresisIT:
 * windows + poller ticks + CI slack.
 */
@Suppress("LargeClass", "TooManyFunctions") // T026: one scenario per US3 acceptance + the №36/№37/SSE fixtures of T011
class PresenceExpiryIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
    @Autowired private val meterRegistry: MeterRegistry,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : PresenceTestSupport() {
    /** SC-003: ttl + hysteresis + 2 poller ticks + CI slack (production ≈ 137 s + slack, research §G). */
    private val sc003Budget: Duration =
        presenceProperties.ttl
            .plus(presenceProperties.hysteresis)
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /** A suppressed transition (a covered crash, an orphan lapse) must stay silent for expiry + window + slack. */
    private val windowWithNoEvent: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /** A silent crash of ONE of two registrations: the last in-flight beat + ttl + window + ticks + CI slack. */
    private val crashQuietWindow: Duration =
        presenceProperties.heartbeatInterval
            .plus(presenceProperties.ttl)
            .plus(presenceProperties.hysteresis)
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /** An explicit-close offline (the epoch scenario): hysteresis + ticks + slack (the T011/T023 budget). */
    private val offlinePublicationBudget: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /** US3 AC4: the return lands after the scores lapse (ttl) yet well before the earliest offq due time. */
    private val stormReturnDelay: Duration = Duration.ofSeconds(PROFILE_TTL_SECONDS + STORM_RETURN_MARGIN_SECONDS)

    /** The storm's offline window: from the return point to the latest possible offq execution (+ slack). */
    private val stormDrainWindow: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(2 * POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /** The orphan lapses by its own score: ttl + a couple of poller ticks (+ margin) before its №37 turns 404. */
    private val orphanExpiryWait: Duration =
        presenceProperties.ttl
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(1)

    /**
     * Research §B2: the logout bypass must publish INSIDE a budget strictly
     * smaller than the earliest possible offq publication (hysteresis + one
     * poller tick) — a logout routed through the hysteresis queue would time
     * out here and fail the scenario.
     */
    private val logoutBudget: Duration = presenceProperties.hysteresis.minus(presenceProperties.pollerInterval)

    /**
     * SC-003 «крэш» (QS-3): the subject's №18 socket stays MOUNTED while its
     * heartbeats fall silent — no close, no logout, nothing but score lapse:
     * the registration self-expires (FR-002), the deferred publish executes,
     * and every observer sees EXACTLY ONE `offline` with a strictly greater
     * rev within the SC-003 budget; the №36 snapshot agrees with the event,
     * and no second switch may follow (the CAS rules echoes out).
     */
    @Test
    fun `heartbeat silence without a disconnect publishes exactly one offline within the sc003 budget`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val silentStream = openUserEvents(alice)
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(silentStream)
            val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
            assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
            val onlineRev = onlineEvent[REV_FIELD].asLong()

            // the clock starts at the observed online publication (open + ε):
            // the score lapses at open + ttl, the offq is due after that
            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, sc003Budget)
            assertThat(offlineEvent[STATUS_FIELD].asText())
                .overridingErrorMessage(
                    "heartbeat silence must converge the subject to offline, got <%s>",
                    offlineEvent,
                ).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev)
                .overridingErrorMessage("rev must strictly increase across the silent-expiry offline transition")
                .isGreaterThan(onlineRev)

            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlineRev)
            assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
        } finally {
            runCatching { bobStream.close() }
            runCatching { silentStream.close() }
        }
    }

    /**
     * FR-005 «stateless»: two connections of one user — two "instances" —
     * share ONE published status and ONE rev (a single `online` for both);
     * the silent crash of the first "instance" (its registration lapses
     * while the second keeps beating) never flickers the audience's
     * `online`, and only the loss of the LAST live registration converges
     * the user offline inside the very SC-003 budget — measured from an
     * explicit final №37 renewal, so the silence origin is exact.
     */
    @Test
    fun `two instance registrations share one status and a crashed instance never flickers it`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val crashedInstance = openUserEvents(alice)
        val survivingInstance = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            val crashedConnection = awaitConnectedFrame(crashedInstance)
            val survivingConnection = awaitConnectedFrame(survivingInstance)
            pumpedConnections += crashedConnection
            pumpedConnections += survivingConnection
            val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
            assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
            val onlineRev = onlineEvent[REV_FIELD].asLong()
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // ONE status for both instances: the second registration publishes NOTHING
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

                // the crash: the first instance's beats stop with the socket still
                // mounted — its registration must lapse silently, unnoticed by the audience
                pumpedConnections -= crashedConnection
                assertNoPresenceEventFor(bobStream, alice.id, crashQuietWindow)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
                assertThat(pumpFailures)
                    .overridingErrorMessage("the surviving instance's №37 renewals must stay 204: %s", pumpFailures)
                    .isEmpty()
            } finally {
                pump.close()
            }
            pumpedConnections -= survivingConnection

            // pin the silence origin: one last 204, then nothing — the SC-003 clock is exact
            val lastRenewal = heartbeatUntilSuccess(alice, survivingConnection, RECOVERY_DEADLINE)
            assertThat(lastRenewal.statusCode)
                .overridingErrorMessage("the surviving registration must still be renewable right before the crash")
                .isEqualTo(HttpStatus.NO_CONTENT)

            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, sc003Budget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev)
                .overridingErrorMessage("rev must strictly increase across the double-crash offline transition")
                .isGreaterThan(onlineRev)

            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlineRev)
            assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
        } finally {
            runCatching { bobStream.close() }
            runCatching { crashedInstance.close() }
            runCatching { survivingInstance.close() }
        }
    }

    /**
     * US3 AC4 «шторм»: [STORM_SIZE] subjects lapse simultaneously (a crash of
     * the instance holding all their sockets — no closes, no beats), then
     * [RETURNING_HALF] of them return INSIDE the hysteresis window (after
     * the scores lapse, well before the earliest offq due time):
     *  * the returning half surfaces ZERO switches — their pending offline
     *    publishes are cancelled, the №36 snapshot keeps the very online/rev
     *    of the initial publication;
     *  * every ABANDONED subject gets EXACTLY ONE `offline` with a strictly
     *    greater rev — and nothing else for the whole drain window;
     *  * the suppression does not pass silently: the
     *    `webchat_presence_hysteresis_suppressed_total` counter grows.
     */
    @Test
    fun `crash storm with in-window returns goes offline only for the abandoned half`() {
        val (observer, firstSubject) = messagingPair()
        val subjects = listOf(firstSubject) + (2..STORM_SIZE).map { messagingUser("storm") }
        subjects.forEach { ensureChatOk(it, observer.id) }

        val observerStream = openUserEvents(observer)
        val crashedStreams = mutableListOf<UserEventsStream>()
        val returnedStreams = mutableListOf<UserEventsStream>()
        val pumps = mutableListOf<HeartbeatPump>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(observerStream)
            val stormAnchor = System.nanoTime()
            subjects.forEach { subject ->
                val stream = openUserEvents(subject)
                crashedStreams += stream
                awaitConnectedFrame(stream)
            }
            val onlineRevs =
                awaitOnlineForAll(
                    observerStream,
                    subjects.map { it.id },
                    ONLINE_PUBLICATION_BUDGET.plusSeconds(1),
                )

            // one shared status before the crash: №36 answers online with the very published revs
            assertBeforeStormBaseline(observer, subjects, onlineRevs)

            val suppressedBefore = hysteresisSuppressedTotal()

            // THE CRASH: every socket stays mounted, every heartbeat stops —
            // then ≥ half return after the scores lapse, inside the window
            sleepUntilNanos(stormAnchor + stormReturnDelay.toNanos())
            val returning = subjects.take(RETURNING_HALF)
            val abandoned = subjects.drop(RETURNING_HALF)
            returning.forEach { subject ->
                val stream = openUserEvents(subject)
                returnedStreams += stream
                val connection = awaitConnectedFrame(stream)
                pumps += startHeartbeatPump(subject, setOf(connection), pumpFailures)
            }

            val frames =
                drainSubjectPresenceEvents(observerStream, subjects.map { it.id }.toSet(), stormDrainWindow)
            val offlinesBySubject = assertStormFrames(frames, abandoned, onlineRevs)

            assertThat(hysteresisSuppressedTotal())
                .overridingErrorMessage(
                    "US3 AC4: the in-window returns must grow <%s> — a suppression may never be silent",
                    HYSTERESIS_SUPPRESSED_COUNTER,
                ).isGreaterThan(suppressedBefore)

            assertAfterStormSnapshot(observer, returning, abandoned, onlineRevs, offlinesBySubject)

            assertNoPresenceEventForAny(observerStream, subjects.map { it.id }, windowWithNoEvent)
            assertThat(pumpFailures)
                .overridingErrorMessage("the returned subjects' №37 renewals must stay 204: %s", pumpFailures)
                .isEmpty()
        } finally {
            pumps.forEach { it.close() }
            runCatching { observerStream.close() }
            crashedStreams.forEach { runCatching { it.close() } }
            returnedStreams.forEach { runCatching { it.close() } }
        }
    }

    /** One shared status before the crash: №36 answers online with the very published revs. */
    private fun assertBeforeStormBaseline(
        observer: MessagingUser,
        subjects: List<MessagingUser>,
        onlineRevs: Map<UUID, Long>,
    ) {
        val beforeCrash = snapshotBatch(observer, subjects.map { it.id })
        subjects.forEach { subject ->
            val item = beforeCrash.getValue(subject.id)
            assertThat(item[STATUS_FIELD].asText())
                .overridingErrorMessage("every subject must be online before the storm, got <%s>", item)
                .isEqualTo(STATUS_ONLINE)
            assertThat(item[REV_FIELD].asLong())
                .overridingErrorMessage("№36 must expose the very published rev before the storm, got <%s>", item)
                .isEqualTo(onlineRevs.getValue(subject.id))
        }
    }

    /**
     * US3 AC4 storm frames: ZERO switches for the returning half (their
     * pending offline publishes are cancelled) and EXACTLY ONE `offline`
     * with a strictly greater rev per abandoned subject; returns the
     * per-subject offline payloads keyed by the subject id string.
     */
    private fun assertStormFrames(
        frames: List<JsonNode>,
        abandoned: List<MessagingUser>,
        onlineRevs: Map<UUID, Long>,
    ): Map<String, List<JsonNode>> {
        val offlines = frames.filter { it[STATUS_FIELD].asText() == STATUS_OFFLINE }
        val onlines = frames.filter { it[STATUS_FIELD].asText() == STATUS_ONLINE }

        assertThat(onlines)
            .overridingErrorMessage(
                "a returning subject must surface NO switch at all (the pending offline is cancelled), got %s",
                onlines,
            ).isEmpty()
        val offlinesBySubject = offlines.groupBy { it[USER_ID_FIELD].asText() }
        assertThat(offlinesBySubject.keys)
            .overridingErrorMessage(
                "offline publications must land for EXACTLY the abandoned half, got %s",
                offlinesBySubject.keys,
            ).containsExactlyInAnyOrderElementsOf(abandoned.map { it.id.toString() })
        offlinesBySubject.forEach { (subjectId, events) ->
            assertThat(events)
                .overridingErrorMessage(
                    "the abandoned subject <%s> must get EXACTLY ONE offline, got %d: %s",
                    subjectId,
                    events.size,
                    events,
                ).hasSize(1)
            assertThat(events.single()[REV_FIELD].asLong())
                .overridingErrorMessage(
                    "the storm offline of <%s> must advance the rev beyond the initial online",
                    subjectId,
                ).isGreaterThan(onlineRevs.getValue(UUID.fromString(subjectId)))
        }
        return offlinesBySubject
    }

    /**
     * US3 AC4 aftermath: the returning half is online at the untouched
     * rev, the abandoned half offline at the very storm rev.
     */
    private fun assertAfterStormSnapshot(
        observer: MessagingUser,
        returning: List<MessagingUser>,
        abandoned: List<MessagingUser>,
        onlineRevs: Map<UUID, Long>,
        offlinesBySubject: Map<String, List<JsonNode>>,
    ) {
        val afterStorm = snapshotBatch(observer, (returning + abandoned).map { it.id })
        returning.forEach { subject ->
            val item = afterStorm.getValue(subject.id)
            assertThat(item[STATUS_FIELD].asText())
                .overridingErrorMessage("a returned subject must be online again, got <%s>", item)
                .isEqualTo(STATUS_ONLINE)
            assertThat(item[REV_FIELD].asLong())
                .overridingErrorMessage(
                    "a suppressed storm leg must not even advance the revision, got <%s>",
                    item,
                ).isEqualTo(onlineRevs.getValue(subject.id))
        }
        abandoned.forEach { subject ->
            val item = afterStorm.getValue(subject.id)
            assertThat(item[STATUS_FIELD].asText())
                .overridingErrorMessage("an abandoned subject must be offline after the storm, got <%s>", item)
                .isEqualTo(STATUS_OFFLINE)
            assertThat(item[REV_FIELD].asLong())
                .overridingErrorMessage(
                    "№36 must expose the very rev of the storm offline event, got <%s>",
                    item,
                ).isEqualTo(offlinesBySubject.getValue(subject.id.toString()).single()[REV_FIELD].asLong())
        }
    }

    /**
     * Edge «осиротевшая регистрация реконнекта» (data-model 007 §1.1): a
     * reconnect mints a NEW connectionId while the superseded registration
     * keeps its own score — the orphan self-expires (its №37 answers 404
     * `presence_connection_not_found`, FR-002), the LIVE connection of the
     * same user still renews with 204, and the duplicate never distorts the
     * published status: no event, the snapshot keeps the untouched rev.
     */
    @Test
    fun `orphaned reconnect registration self-expires without distorting the live status`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val orphanStream = openUserEvents(alice)
        val liveStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            val orphanConnection = awaitConnectedFrame(orphanStream)
            val liveConnection = awaitConnectedFrame(liveStream)
            pumpedConnections += liveConnection
            val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
            assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
            val onlineRev = onlineEvent[REV_FIELD].asLong()
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // the orphan is never renewed: its score lapses by itself
                Thread.sleep(orphanExpiryWait.toMillis())

                assertHeartbeatProblem(
                    postHeartbeat(alice, orphanConnection),
                    HttpStatus.NOT_FOUND,
                    CONNECTION_NOT_FOUND,
                )
                val renewal = heartbeatUntilSuccess(alice, liveConnection, RECOVERY_DEADLINE)
                assertThat(renewal.statusCode)
                    .overridingErrorMessage("the LIVE connection must still renew after the orphan lapsed")
                    .isEqualTo(HttpStatus.NO_CONTENT)

                // the duplicate never distorts the status: no offline may ever surface
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
            } finally {
                pump.close()
            }
            assertThat(pumpFailures)
                .overridingErrorMessage("the live connection's №37 renewals must stay 204: %s", pumpFailures)
                .isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { orphanStream.close() }
            runCatching { liveStream.close() }
        }
    }

    /**
     * Edge «мультидевайс-logout» (research §B2, T029): the №7 logout of ONE
     * session (a second login of the SAME user holds the other socket)
     * clears only that session's registrations — the audience keeps the
     * untouched `online`/rev while the second session is alive; the logout
     * of the LAST session publishes `offline` IMMEDIATELY, bypassing the
     * hysteresis queue: the [logoutBudget] is strictly smaller than one
     * hysteresis window, so a logout routed through the offq would fail.
     */
    @Test
    fun `per-session logout spares the live session and the last logout goes offline immediately`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)
        val secondSession = loginAgain(alice)
        assertThat(secondSession.id)
            .overridingErrorMessage("a second login must be the SAME user with a fresh session")
            .isEqualTo(alice.id)

        val bobStream = openUserEvents(bob)
        val firstSessionStream = openUserEvents(alice)
        val secondSessionStream = openUserEvents(secondSession)
        val firstPumpConnections = CopyOnWriteArraySet<UUID>()
        val secondPumpConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            firstPumpConnections += awaitConnectedFrame(firstSessionStream)
            secondPumpConnections += awaitConnectedFrame(secondSessionStream)
            val firstPump = startHeartbeatPump(alice, firstPumpConnections, pumpFailures)
            val secondPump = startHeartbeatPump(secondSession, secondPumpConnections, pumpFailures)
            try {
                val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                val onlineRev = onlineEvent[REV_FIELD].asLong()

                // logout of session №1: the second session's registration keeps the user online
                firstPump.close()
                firstPumpConnections.clear()
                assertThat(postLogout(alice).statusCode)
                    .overridingErrorMessage("the №7 logout of the first session must answer 204")
                    .isEqualTo(HttpStatus.NO_CONTENT)

                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

                // logout of the LAST session: immediate offline — no hysteresis window (research §B2)
                secondPump.close()
                secondPumpConnections.clear()
                assertThat(postLogout(secondSession).statusCode)
                    .overridingErrorMessage("the №7 logout of the last session must answer 204")
                    .isEqualTo(HttpStatus.NO_CONTENT)

                assertImmediateOfflineAfterLastLogout(bobStream, bob, alice.id, onlineRev)
            } finally {
                firstPump.close()
                secondPump.close()
            }
            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "both sessions' №37 renewals must stay 204 until their logouts: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { firstSessionStream.close() }
            runCatching { secondSessionStream.close() }
        }
    }

    /**
     * research §B2: the logout of the LAST session publishes `offline`
     * IMMEDIATELY (the [logoutBudget] is strictly smaller than one
     * hysteresis window — a logout routed through the offq would time
     * out), the snapshot flips with the very rev and nothing follows.
     */
    private fun assertImmediateOfflineAfterLastLogout(
        observerStream: UserEventsStream,
        observer: MessagingUser,
        subjectId: UUID,
        onlineRev: Long,
    ) {
        val offlineEvent = awaitPresenceEvent(observerStream, subjectId, logoutBudget)
        assertThat(offlineEvent[STATUS_FIELD].asText())
            .overridingErrorMessage(
                "the last-session logout must publish offline immediately, got <%s>",
                offlineEvent,
            ).isEqualTo(STATUS_OFFLINE)
        val offlineRev = offlineEvent[REV_FIELD].asLong()
        assertThat(offlineRev)
            .overridingErrorMessage("rev must strictly increase across the logout offline transition")
            .isGreaterThan(onlineRev)

        assertSnapshotStatus(observer, subjectId, STATUS_OFFLINE, offlineRev)
        assertNoPresenceEventFor(observerStream, subjectId, windowWithNoEvent)
    }

    /**
     * «Эпоха» rev (data-model 007 §3, research §C2): the revision key
     * outlives every scenario but its 7 h TTL — simulated here by deleting
     * the documented `presence:rev:{userId}` key directly (the only honest
     * way to cross an epoch inside an IT). The next transition after the
     * lapse re-mints the revision from the now_ms epoch marker — strictly
     * greater than every pre-lapse rev (the elapsed wall clock dwarfs the
     * small per-epoch increments) — and the following INCR keeps the chain
     * climbing, so the client's strictly-greater max(rev) merge survives
     * epoch boundaries.
     */
    @Test
    fun `rev epoch after the rev key lapses keeps the merge chain strictly increasing`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        var aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
            assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
            val onlineRev = onlineEvent[REV_FIELD].asLong()
            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // the epoch boundary: the §3 rev key lapses (7 h TTL cannot be awaited)
                assertThat(redisTemplate.delete("$REV_KEY_PREFIX${alice.id}"))
                    .overridingErrorMessage("the epoch simulation must actually delete the documented rev key")
                    .isTrue

                // the first transition after the lapse mints the epoch rev (now_ms)
                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            aliceStream.close()

            // the deferred offline must be OBSERVED before any reconnect — a
            // returning registration would legitimately cancel the offq entry
            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, offlinePublicationBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText())
                .overridingErrorMessage("the post-epoch offline must still publish, got <%s>", offlineEvent)
                .isEqualTo(STATUS_OFFLINE)
            val epochRev = offlineEvent[REV_FIELD].asLong()
            assertThat(epochRev)
                .overridingErrorMessage(
                    "the post-epoch rev (now_ms marker) must be strictly greater than the pre-lapse rev",
                ).isGreaterThan(onlineRev)
            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, epochRev)

            // and the chain keeps climbing across the very next transition
            aliceStream = openUserEvents(alice)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val returnPump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                val returnEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(returnEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                val returnRev = returnEvent[REV_FIELD].asLong()
                assertThat(returnRev)
                    .overridingErrorMessage("rev must keep strictly increasing across the epoch boundary")
                    .isGreaterThan(epochRev)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, returnRev)
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
            } finally {
                returnPump.close()
            }
            assertThat(pumpFailures)
                .overridingErrorMessage("the №37 renewals around the epoch boundary must stay 204: %s", pumpFailures)
                .isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
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

    /** Collects the initial `online` rev of every [subjects] member — the storm's pre-crash baseline. */
    private fun awaitOnlineForAll(
        stream: UserEventsStream,
        subjects: List<UUID>,
        timeout: Duration,
    ): Map<UUID, Long> {
        val expected = subjects.toSet()
        val revs = mutableMapOf<UUID, Long>()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (revs.size < expected.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw AssertionError(
                    "timed out after $timeout waiting for the online frames of ${expected - revs.keys}",
                )
            }
            val frame =
                stream.nextFrame(Duration.ofNanos(remaining))
                    ?: throw AssertionError("stream ended before every subject published its online")
            if (frame.event == PRESENCE_UPDATED_EVENT) {
                val payload = objectMapper.readTree(frame.data)
                val subjectId = UUID.fromString(payload[USER_ID_FIELD].asText())
                if (subjectId in expected && payload[STATUS_FIELD].asText() == STATUS_ONLINE && subjectId !in revs) {
                    revs[subjectId] = payload[REV_FIELD].asLong()
                }
            }
        }
        return revs
    }

    /** Consumes every `presence.updated` of [subjects] delivered within [window] (the storm's full event stream). */
    private fun drainSubjectPresenceEvents(
        stream: UserEventsStream,
        subjects: Set<UUID>,
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
                        ?: throw AssertionError("stream ended while draining the storm frames")
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return events
                }
            if (frame.event == PRESENCE_UPDATED_EVENT) {
                val payload = objectMapper.readTree(frame.data)
                if (UUID.fromString(payload[USER_ID_FIELD].asText()) in subjects) events.add(payload)
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

    /** The storm tail: no `presence.updated` of ANY [subjects] member may follow within [window]. */
    private fun assertNoPresenceEventForAny(
        stream: UserEventsStream,
        subjects: Collection<UUID>,
        window: Duration,
    ) {
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError("stream ended while asserting the storm tail stays silent")
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            if (frame.event == PRESENCE_UPDATED_EVENT) {
                val payload = objectMapper.readTree(frame.data)
                assertThat(payload[USER_ID_FIELD].asText())
                    .overridingErrorMessage(
                        "no further <%s> frame of the storm subjects may follow within %s, got %s",
                        PRESENCE_UPDATED_EVENT,
                        window,
                        payload,
                    ).isNotIn(subjects.map { it.toString() })
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

    /** The №37 error contract: the status, RFC 9457 body and `errors.connectionId=[code]`. */
    private fun assertHeartbeatProblem(
        response: ResponseEntity<String>,
        expectedStatus: HttpStatus,
        expectedCode: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№37 must answer <%s>, got <%s>: %s",
                expectedStatus,
                response.statusCode,
                response.body,
            ).isEqualTo(expectedStatus)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("№37 errors must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(CONNECTION_ID_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                CONNECTION_ID_FIELD,
                expectedCode,
                codes,
                response.body,
            ).containsExactly(expectedCode)
    }

    /**
     * A fresh login of an EXISTING fixture user (002 parallel-session edge):
     * the same user id, a brand-new sid — the logout scenario's second
     * device. Uses the IT's own 203.0.115.0/24 login-IP block so the shared
     * Redis login buckets of the full run never collide.
     */
    private fun loginAgain(user: MessagingUser): MessagingUser {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set(X_FORWARDED_FOR_HEADER, uniqueLoginIp())
            }
        val response =
            restTemplate.postForEntity(
                LOGIN_PATH,
                HttpEntity(mapOf("identifier" to user.username, "password" to FIXTURE_PASSWORD), headers),
                String::class.java,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage("the second login of <%s> must succeed", user.username)
            .isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return user.copy(
            accessToken = body["accessToken"].asText(),
            refreshToken = body["refreshToken"].asText(),
        )
    }

    /** Contract №7 `POST /auth/logout` (Bearer + the session's refresh token) — raw response. */
    private fun postLogout(user: MessagingUser): ResponseEntity<String> =
        restTemplate.postForEntity(
            LOGOUT_PATH,
            HttpEntity(mapOf(REFRESH_TOKEN_FIELD to user.refreshToken), bearerJsonHeaders(user)),
            String::class.java,
        )

    /** research.md §F: the suppression counter read straight off the shared MeterRegistry. */
    private fun hysteresisSuppressedTotal(): Double = meterRegistry.counter(HYSTERESIS_SUPPRESSED_COUNTER).count()

    /** Sleeps until the absolute [nanos] deadline (the storm's anchored return point). */
    private fun sleepUntilNanos(nanos: Long) {
        while (true) {
            val remainingMillis = (nanos - System.nanoTime()) / NANOS_PER_MILLI
            if (remainingMillis <= 0) return
            Thread.sleep(remainingMillis)
        }
    }

    /**
     * Starts the explicit keep-alive of T011: a daemon thread posting №37 for
     * every live [connections] entry at the test-profile heartbeat interval,
     * recording any answer other than 204/429 into [failures].
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

    private fun uniqueLoginIp(): String =
        LOGIN_IP_PREFIX + (1L + loginIpCounter.incrementAndGet() % LOGIN_IP_LAST_OCTET_SPAN)

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
        const val CONNECTION_NOT_FOUND = "presence_connection_not_found"
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val TIMEOUT_MARKER = "timed out"
        const val SNAPSHOT_PATH = "/api/v1/users/me/presence"
        const val HEARTBEAT_PATH = "/api/v1/users/me/presence/heartbeat"
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val LOGOUT_PATH = "/api/v1/auth/logout"
        const val REFRESH_TOKEN_FIELD = "refreshToken"
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"

        /** Mirrors MessagingTestSupport's fixture secret — loginAgain needs the raw password. */
        const val FIXTURE_PASSWORD = "Msg-IT-004-Fixture-Pass!"

        /** data-model 007 §3: the rev key family the epoch scenario lapses deliberately. */
        const val REV_KEY_PREFIX = "presence:rev:"

        /** contracts/presence-events.md §5: the suppression counter (T010 names it verbatim). */
        const val HYSTERESIS_SUPPRESSED_COUNTER = "webchat_presence_hysteresis_suppressed_total"

        /** US3 AC4: ≥ 10 simultaneous lapses, ≥ half returning inside the window. */
        const val STORM_SIZE = 10
        const val RETURNING_HALF = 5

        /**
         * T003 (asserted by the PresenceIT guard): the tightened ttl anchors
         * the storm's return — after the scores lapse, inside the window.
         */
        const val PROFILE_TTL_SECONDS = 3L
        const val STORM_RETURN_MARGIN_SECONDS = 1L

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L

        /** Dedicated 203.0.115.0/24 block for this IT's own logins (messaging 004/.114, SSO 003/.113). */
        const val LOGIN_IP_PREFIX = "203.0.115."
        const val LOGIN_IP_LAST_OCTET_SPAN = 254L
        val loginIpCounter = AtomicLong()

        const val NANOS_PER_MILLI = 1_000_000L
        const val RECOVERY_POLL_MILLIS = 250L
        const val PUMP_THREAD_NAME = "presence-expiry-it-heartbeat"

        /** SC-001: from the subject's `connected` frame to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)
        val RECOVERY_DEADLINE: Duration = Duration.ofSeconds(5)
    }
}
