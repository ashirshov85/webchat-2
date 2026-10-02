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
import webchat.backend.chats.MessagingTestSupport
import webchat.backend.config.PresenceProperties
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T011 (tasks.md Phase 3, US1): the presence lifecycle of 007 over the real
 * stack (Testcontainers PG+Redis through [MessagingTestSupport], tightened
 * test-profile windows of T003 — ttl 3 s, hysteresis 2 s, heartbeat
 * interval 1 s, poller 200 ms):
 *
 *  * SSE-open answers `retry: 3000` and then the 007 opening frame
 *    `connected {connectionId}` (presence-events.md §1), and the subject's
 *    `offline→online` transition reaches the visibility audience within the
 *    SC-001 budget of 2 s;
 *  * multi-device (SC-005): two connections register two distinct
 *    connectionIds with EXACTLY ONE `online` fanout; closing ONE of them
 *    keeps the published `online` (snapshot included), and the close of the
 *    LAST connection publishes exactly one `offline` with a strictly
 *    greater rev (FR-003 rev monotonicity);
 *  * the №36 batch snapshot answers the PUBLISHED status and the very rev
 *    the audience observed (SC-006), stays `online` inside the
 *    pending-offline hysteresis window (FR-004 snapshot/event agreement)
 *    and is NOT re-pushed on (re)connect (presence-events.md §4 — the
 *    duplicate-frame idempotency of FR-003);
 *  * a frame lost while the observer is disconnected (at-most-once №18)
 *    converges through a №36 refetch by max(rev) — the delivery-loss path
 *    of constitution III;
 *  * №37 heartbeat keeps the registration alive across > 2×TTL of explicit
 *    1 s renewals; a `flood_limit` 429 throttles the beat but never tears
 *    the №18 stream and never expires the registration (presence-api.md
 *    §2), while a foreign/unknown connectionId answers 404
 *    `presence_connection_not_found` and a malformed one 400.
 *
 * Registrations outlive the 3 s TTL only through explicit №37 renewals at
 * the test-profile heartbeat interval (tasks.md T011) — driven by the
 * [HeartbeatPump] in every scenario longer than the TTL.
 *
 * NOTE (TDD, constitution VI): written BEFORE T013–T017 — until the
 * `connected` frame, the №36/№37 endpoints and the presence publication
 * land, every lifecycle method here fails (RED) by design; only the
 * T003 windows guard passes (the config landed with Phase 1). The 2 s
 * SC-001 budget is asserted from the subject's `connected` frame to the
 * observer's event — the connect-handshake jitter and the percentiles are
 * the k6 profile's business (T035), the precedent of RealtimeSseIT.
 */
@Suppress("TooManyFunctions") // T011: one helper per contract surface №36/№37 + the SSE lifecycle
class PresenceIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
) : MessagingTestSupport() {
    /** A suppressed transition (re-register, covered break) must stay silent for the window + poller slack. */
    private val windowWithNoEvent: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /** An explicit close publishes `offline` after hysteresis + a couple of poller ticks (+ CI slack). */
    private val offlinePublicationBudget: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /** Without №37 renewals the 3 s TTL would flip the user offline inside this window. */
    private val keepAliveWindow: Duration = presenceProperties.ttl.multipliedBy(2).plusSeconds(1)

    /** T003 guard: the budgets above derive from the tightened IT windows — a profile drift must fail loudly. */
    @Test
    fun `it profile tightens the presence windows the scenarios rely on`() {
        assertThat(presenceProperties.ttl)
            .overridingErrorMessage(
                "the IT profile must tighten presence.ttl to 3 s (T003), got <%s>",
                presenceProperties.ttl,
            ).isEqualTo(Duration.ofSeconds(PROFILE_TTL_SECONDS))
        assertThat(presenceProperties.hysteresis)
            .overridingErrorMessage(
                "the IT profile must tighten presence.hysteresis to 2 s (T003), got <%s>",
                presenceProperties.hysteresis,
            ).isEqualTo(Duration.ofSeconds(PROFILE_HYSTERESIS_SECONDS))
        assertThat(presenceProperties.heartbeatInterval)
            .overridingErrorMessage(
                "the IT profile must tighten presence.heartbeat-interval to 1 s (T003), got <%s>",
                presenceProperties.heartbeatInterval,
            ).isEqualTo(Duration.ofSeconds(PROFILE_HEARTBEAT_SECONDS))
        assertThat(presenceProperties.pollerInterval)
            .overridingErrorMessage(
                "the IT profile must tighten presence.poller-interval to 200 ms (T003), got <%s>",
                presenceProperties.pollerInterval,
            ).isEqualTo(Duration.ofMillis(PROFILE_POLLER_MILLIS))
    }

    /**
     * SC-001 / presence-events.md §1: the №18 stream opens with the retry
     * frame and the `connected {connectionId}` opening frame right after it,
     * and the `offline→online` transition of the connecting subject reaches
     * the visibility audience (the direct-chat peer) within the 2 s budget —
     * measured from the subject's `connected` frame to the observer's
     * `presence.updated`.
     */
    @Test
    fun `stream opens with retry then connected and online reaches the audience within two seconds`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        openUserEvents(bob).use { bobStream ->
            awaitConnectedFrame(bobStream)

            openUserEvents(alice).use { aliceStream ->
                awaitConnectedFrame(aliceStream)

                val event = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)

                assertThat(fieldNames(event))
                    .overridingErrorMessage(
                        "presence.updated payload must have exactly the PresenceUpdatedEvent fields",
                    ).containsExactlyInAnyOrder(USER_ID_FIELD, STATUS_FIELD, REV_FIELD)
                assertThat(UUID.fromString(event[USER_ID_FIELD].asText()))
                    .overridingErrorMessage(
                        "presence.updated.userId must be the subject who just connected, not the observer",
                    ).isEqualTo(alice.id)
                assertThat(event[STATUS_FIELD].asText())
                    .overridingErrorMessage("the first transition of a connecting subject must publish online")
                    .isEqualTo(STATUS_ONLINE)
                assertThat(event[REV_FIELD].asLong())
                    .overridingErrorMessage("presence.updated.rev must be a positive revision")
                    .isPositive
            }
        }
    }

    /**
     * SC-005 (multi-device): two connections of one user register two
     * distinct connectionIds with EXACTLY ONE `online` fanout (FR-003: one
     * frame per transition per observer); closing one of them keeps the
     * published `online` — snapshot №36 included — for the whole hysteresis
     * window (no transition at all while a registration is alive); closing
     * the LAST connection publishes exactly one `offline` whose rev is
     * strictly greater than the online rev (rev monotonicity), and the
     * snapshot flips with it.
     */
    @Test
    fun `closing one of two connections keeps online and the last close publishes offline once`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val firstDevice = openUserEvents(alice)
        val secondDevice = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            val firstConnection = awaitConnectedFrame(firstDevice)

            val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
            val onlineRev = onlineEvent[REV_FIELD].asLong()
            assertThat(onlineEvent[USER_ID_FIELD].asText()).isEqualTo(alice.id.toString())
            assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)

            val secondConnection = awaitConnectedFrame(secondDevice)
            assertThat(secondConnection)
                .overridingErrorMessage("every device connection must register its own connectionId")
                .isNotEqualTo(firstConnection)
            pumpedConnections += firstConnection
            pumpedConnections += secondConnection
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // FR-003: the second device of an already-online user publishes NOTHING
                assertNoPresenceEventFollows(bobStream, windowWithNoEvent)

                pumpedConnections -= firstConnection
                firstDevice.close()

                // SC-005: one alive registration of the set keeps the published status
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
                assertNoPresenceEventFollows(bobStream, windowWithNoEvent)

                pumpedConnections -= secondConnection
            } finally {
                pump.close()
            }
            secondDevice.close()

            val offlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, offlinePublicationBudget)
            assertThat(offlineEvent[USER_ID_FIELD].asText()).isEqualTo(alice.id.toString())
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev)
                .overridingErrorMessage(
                    "rev must strictly increase across the online→offline transition (FR-003 monotonicity)",
                ).isGreaterThan(onlineRev)

            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlineRev)

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the explicit №37 renewals at the 1 s interval must keep both registrations known: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { firstDevice.close() }
            runCatching { secondDevice.close() }
        }
    }

    /**
     * SC-006 / presence-events.md §4: the №36 snapshot on (re)connect
     * answers the PUBLISHED status with the very rev the observer saw in
     * `presence.updated`; the server never re-pushes the snapshot or a
     * duplicate `presence.updated` on reconnect (FR-003 idempotency — a
     * redelivered frame must not distort state), and a reconnect mints a
     * fresh connectionId.
     */
    @Test
    fun `reconnect snapshot reflects the published status and rev without re-pushing events`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            val firstBobConnection = awaitConnectedFrame(bobStream)
            val aliceConnection = awaitConnectedFrame(aliceStream)
            pumpedConnections += aliceConnection
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
                val publishedRev = onlineEvent[REV_FIELD].asLong()

                // SC-006: №36 exposes the very rev the audience observed
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, publishedRev)

                bobStream.close()
                openUserEvents(bob).use { reconnected ->
                    val secondBobConnection = awaitConnectedFrame(reconnected)
                    assertThat(secondBobConnection)
                        .overridingErrorMessage("a reconnect must mint a fresh connectionId")
                        .isNotEqualTo(firstBobConnection)

                    // at-most-once №18 + §4: nothing was lost, so nothing may be re-pushed
                    assertNoPresenceEventFollows(reconnected, windowWithNoEvent)

                    // a duplicate-free refetch repeats the published rev
                    assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, publishedRev)
                }
                assertThat(pumpFailures)
                    .overridingErrorMessage("the №37 renewals must keep the subject online through the reconnect")
                    .isEmpty()
            } finally {
                pump.close()
            }
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * FR-004 (hysteresis agreement): inside the pending-offline window the
     * №36 snapshot still answers `online` — the snapshot exposes the
     * PUBLISHED status, never the pending one, so snapshot and events can
     * never disagree; after the window the exactly-one `offline` lands with
     * rev+1 and the snapshot flips with it.
     */
    @Test
    fun `snapshot stays online inside the pending-offline window and flips after it`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(aliceStream)
            val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
            val onlineRev = onlineEvent[REV_FIELD].asLong()

            aliceStream.close()

            // FR-004 agreement: inside the window the published status is still online
            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

            val offlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, offlinePublicationBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev).isGreaterThan(onlineRev)

            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlineRev)
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * Constitution III (delivery-loss path): the observer is disconnected
     * while the subject goes offline — the at-most-once №18 publishes the
     * frame into the void and never re-pushes it on reconnect; the stale
     * client state converges through a №36 refetch whose rev is strictly
     * greater than the last applied one (the max(rev) merge of FR-003).
     */
    @Test
    fun `frame missed while the observer is disconnected converges through a snapshot refetch`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(aliceStream)
            val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
            val appliedRev = onlineEvent[REV_FIELD].asLong()

            // the observer drops BEFORE the transition: the offline frame is lost
            aliceStream.close()
            bobStream.close()

            openUserEvents(bob).use { reconnected ->
                awaitConnectedFrame(reconnected)

                // the missed frame is NOT re-pushed (at-most-once) — healing is №36's job
                assertNoPresenceEventFollows(reconnected, windowWithNoEvent)

                val healed = awaitSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlinePublicationBudget.plusSeconds(1))
                assertThat(healed[REV_FIELD].asLong())
                    .overridingErrorMessage(
                        "the missed transition must surface through №36 with a strictly greater rev (max(rev) merge)",
                    ).isGreaterThan(appliedRev)
            }
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * FR-002 (keep-alive): explicit №37 renewals at the test-profile 1 s
     * interval carry the registration across a window of 2×TTL — no offline
     * flap reaches the audience, the next beat still answers 204 (the
     * registration never expired), the snapshot stays online with the
     * unchanged rev, and the №18 stream itself survives the whole episode.
     */
    @Test
    fun `explicit heartbeat renewals keep the registration alive beyond the ttl`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            val aliceConnection = awaitConnectedFrame(aliceStream)
            pumpedConnections += aliceConnection
            val onlineEvent = bobStream.awaitEvent(PRESENCE_UPDATED_EVENT, ONLINE_PUBLICATION_BUDGET)
            val onlineRev = onlineEvent[REV_FIELD].asLong()

            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // > 2× ttl without renewals would expire the registration mid-scenario
                assertNoPresenceEventFollows(bobStream, keepAliveWindow)
            } finally {
                pump.close()
            }
            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the explicit №37 renewals at the 1 s interval must keep the registration known: %s",
                    pumpFailures,
                ).isEmpty()

            val renewal = heartbeatUntilSuccess(alice, aliceConnection, RECOVERY_DEADLINE)
            assertThat(renewal.statusCode)
                .overridingErrorMessage("after 2×ttl of renewals the next №37 beat must still find the registration")
                .isEqualTo(HttpStatus.NO_CONTENT)

            // a kept-alive registration must not advance the rev
            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

            sendMessageOk(bob, chatId, "stream alive after a ttl-wide window of renewals ✓")
            aliceStream.awaitEvent(MESSAGE_CREATED_EVENT, DELIVERY_BUDGET)
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * Presence-api.md §2 (№37 error contract): a FOREIGN and an UNKNOWN
     * connectionId answer 404 `presence_connection_not_found`, a malformed
     * one 400 `malformed_request` — all RFC 9457 problem+json with
     * `errors.connectionId`; the per-user `flood_limit` bucket throttles a
     * burst with 429 + `Retry-After` WITHOUT tearing the №18 stream (a
     * message still arrives on it) and WITHOUT expiring the registration —
     * after the advertised window the beat recovers with 204 and №36 still
     * answers online.
     */
    @Test
    fun `heartbeat flood rejection spares the stream and the registration survives the throttle`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val aliceStream = openUserEvents(alice)
        try {
            val aliceConnection = awaitConnectedFrame(aliceStream)

            assertHeartbeatProblem(postHeartbeat(bob, aliceConnection), HttpStatus.NOT_FOUND, CONNECTION_NOT_FOUND)
            assertHeartbeatProblem(
                postHeartbeat(alice, UUID.randomUUID()),
                HttpStatus.NOT_FOUND,
                CONNECTION_NOT_FOUND,
            )
            assertHeartbeatProblem(
                postHeartbeat(alice, MALFORMED_CONNECTION_ID),
                HttpStatus.BAD_REQUEST,
                MALFORMED_REQUEST,
            )

            var rejection: ResponseEntity<String>? = null
            var attempt = 0
            while (rejection == null && attempt < HEARTBEAT_BURST_CAP) {
                attempt += 1
                val beat = postHeartbeat(alice, aliceConnection)
                if (beat.statusCode == HttpStatus.TOO_MANY_REQUESTS) rejection = beat
            }
            assertThat(rejection)
                .overridingErrorMessage(
                    "№37 must enforce the per-user heartbeat flood bucket (presence-api.md §2) — " +
                        "%d back-to-back beats were all admitted",
                    HEARTBEAT_BURST_CAP,
                ).isNotNull
            val flooded = rejection!!
            assertHeartbeatProblem(flooded, HttpStatus.TOO_MANY_REQUESTS, FLOOD_LIMIT)
            val retryAfterSeconds = retryAfterSecondsOf(flooded)

            // the 429 must NOT tear the №18 stream: a №16 message still arrives on it
            sendMessageOk(bob, chatId, "delivered while the heartbeat bucket is drained ✓")
            aliceStream.awaitEvent(MESSAGE_CREATED_EVENT, DELIVERY_BUDGET)

            // retry in the next interval: the registration survived the throttle
            Thread.sleep(retryAfterSeconds * MILLIS_PER_SECOND + RECOVERY_MARGIN_MILLIS)
            val recovery = heartbeatUntilSuccess(alice, aliceConnection, RECOVERY_DEADLINE)
            assertThat(recovery.statusCode)
                .overridingErrorMessage("the first beat after the Retry-After window must recover with 204")
                .isEqualTo(HttpStatus.NO_CONTENT)

            val snapshot = snapshotOf(bob, alice.id)
            assertThat(snapshot[STATUS_FIELD].asText())
                .overridingErrorMessage(
                    "the registration must survive a 429 throttle episode (TTL headroom, presence-api.md §2)",
                ).isEqualTo(STATUS_ONLINE)
        } finally {
            runCatching { aliceStream.close() }
        }
    }

    /**
     * Presence-api.md §1 (№36 batch gate, T016): the SHAPE comes first —
     * an absent/empty `userIds` parameter and a non-UUID segment answer
     * `400 malformed_request` (errors.userIds), an oversized batch (> 200
     * AFTER DEDUP) `400 presence_ids_too_many`, while 201 raw ids with
     * ONE duplicate stay legal (200 distinct → one item per DISTINCT
     * target, request order preserved); the per-pair policy slice: the
     * chat peer answers its published status while the observer's OWN id
     * answers `unknown` (the audience never contains the subject). The
     * per-user `flood_limit` bucket throttles a drain burst with 429 +
     * `Retry-After` and recovers after the advertised wait — a refused
     * snapshot is a pure refusal (no visibility/store leg), so the next
     * allowed call answers 200 again.
     */
    @Test
    fun `snapshot batch gate answers one item per distinct target and enforces malformed too-many and flood refusals`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        // one item per DISTINCT target, request order; exactly the №36 item fields
        val shaped = snapshotRaw(alice, listOf(bob.id, bob.id, alice.id))
        assertThat(shaped.statusCode)
            .overridingErrorMessage(
                "№36 must answer 200 for a well-formed batch, got <%s>: %s",
                shaped.statusCode,
                shaped.body,
            ).isEqualTo(HttpStatus.OK)
        val items = objectMapper.readTree(shaped.body)[ITEMS_FIELD]
        assertThat(items.map { it[USER_ID_FIELD].asText() })
            .overridingErrorMessage(
                "№36 must answer ONE item per DISTINCT target in request order (duplicates collapse), got %s",
                shaped.body,
            ).containsExactly(bob.id.toString(), alice.id.toString())
        items.forEach { item ->
            assertThat(fieldNames(item))
                .overridingErrorMessage("a №36 item must have exactly the PresenceStatusItem fields")
                .containsExactlyInAnyOrder(USER_ID_FIELD, STATUS_FIELD, REV_FIELD)
        }

        // the pair-policy slice: the peer is visible (published offline until first
        // connect), the observer's own id is the indistinguishable no-access unknown
        assertThat(items[0][STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
        assertThat(items[1][STATUS_FIELD].asText()).isEqualTo(STATUS_UNKNOWN)

        // shape gate: absent/empty parameter and a non-UUID segment are malformed
        assertSnapshotProblem(snapshotRawParam(alice, null), HttpStatus.BAD_REQUEST, MALFORMED_REQUEST)
        assertSnapshotProblem(snapshotRawParam(alice, EMPTY_PARAM), HttpStatus.BAD_REQUEST, MALFORMED_REQUEST)
        assertSnapshotProblem(snapshotRawParam(alice, MALFORMED_CONNECTION_ID), HttpStatus.BAD_REQUEST, MALFORMED_REQUEST)
        assertSnapshotProblem(
            snapshotRawParam(alice, "${bob.id},$MALFORMED_CONNECTION_ID"),
            HttpStatus.BAD_REQUEST,
            MALFORMED_REQUEST,
        )

        // the batch cap counts DISTINCT ids: 201 → refused whole, 201 raw with a dup → 200 legal
        val oversized = (1..SNAPSHOT_BATCH_LIMIT + 1).map { UUID.randomUUID() }
        assertSnapshotProblem(snapshotRaw(alice, oversized), HttpStatus.BAD_REQUEST, IDS_TOO_MANY)
        val dedupedLegal = snapshotRaw(alice, oversized.dropLast(1) + oversized.first())
        assertThat(dedupedLegal.statusCode)
            .overridingErrorMessage(
                "the №36 cap applies AFTER dedup — %d raw ids with one duplicate are %d distinct and must pass, got <%s>",
                SNAPSHOT_BATCH_LIMIT + 1,
                SNAPSHOT_BATCH_LIMIT,
                dedupedLegal.statusCode,
            ).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(dedupedLegal.body)[ITEMS_FIELD].size())
            .isEqualTo(SNAPSHOT_BATCH_LIMIT)

        // flood gate: drain the per-user bucket — the refusal carries Retry-After and recovers
        var rejection: ResponseEntity<String>? = null
        var attempt = 0
        while (rejection == null && attempt < SNAPSHOT_BURST_CAP) {
            attempt += 1
            val probe = snapshotRaw(alice, listOf(bob.id))
            if (probe.statusCode == HttpStatus.TOO_MANY_REQUESTS) rejection = probe
        }
        assertThat(rejection)
            .overridingErrorMessage(
                "№36 must enforce the per-user snapshot flood bucket (presence-api.md §1) — " +
                    "%d back-to-back snapshots were all admitted",
                SNAPSHOT_BURST_CAP,
            ).isNotNull
        assertSnapshotProblem(rejection!!, HttpStatus.TOO_MANY_REQUESTS, FLOOD_LIMIT)
        val retryAfterSeconds = retryAfterSecondsOf(rejection!!)

        Thread.sleep(retryAfterSeconds * MILLIS_PER_SECOND + RECOVERY_MARGIN_MILLIS)
        val recovery = snapshotRaw(alice, listOf(bob.id))
        assertThat(recovery.statusCode)
            .overridingErrorMessage(
                "the first №36 batch after the Retry-After window must recover with 200, got <%s>: %s",
                recovery.statusCode,
                recovery.body,
            ).isEqualTo(HttpStatus.OK)
    }

    /**
     * Consumes the opening `retry: 3000` frame (realtime-channel.md §1) and
     * the №18 `connected {connectionId}` opening frame that follows it
     * (presence-events.md §1), asserting the exact frame order and payload
     * shape; returns the registered connectionId.
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
        assertThat(opening.data)
            .overridingErrorMessage("the retry frame must not carry a data: field")
            .isNull()

        val connected = stream.nextFrame(FRAME_BUDGET)
        assertThat(connected)
            .overridingErrorMessage(
                "the frame right after retry must be the connected opening frame (presence-events.md §1)",
            ).isNotNull
        assertThat(connected!!.event)
            .overridingErrorMessage("the opening presence frame must be event: connected")
            .isEqualTo(CONNECTED_EVENT)
        assertThat(connected.data)
            .overridingErrorMessage("the connected frame must carry a JSON payload")
            .isNotNull
        val payload = objectMapper.readTree(connected.data)
        assertThat(fieldNames(payload))
            .overridingErrorMessage("the connected payload must have exactly the ConnectedEvent fields")
            .containsExactly(CONNECTION_ID_FIELD)
        return UUID.fromString(payload[CONNECTION_ID_FIELD].asText())
    }

    /** Contract №36 `GET /users/me/presence?userIds=` — raw response. */
    private fun snapshotRaw(
        observer: MessagingUser,
        targets: List<UUID>,
    ): ResponseEntity<String> = snapshotRawParam(observer, targets.joinToString(",") { it.toString() })

    /** Contract №36 with a RAW `userIds` parameter — the malformed/oversized probes of the batch gate. */
    private fun snapshotRawParam(
        observer: MessagingUser,
        rawUserIds: String?,
    ): ResponseEntity<String> {
        val builder = UriComponentsBuilder.fromPath(SNAPSHOT_PATH)
        if (rawUserIds != null) builder.queryParam(USER_IDS_PARAM, rawUserIds)
        return restTemplate.exchange(
            builder.build().toUriString(),
            HttpMethod.GET,
            HttpEntity<Void>(null, bearerHeaders(observer)),
            String::class.java,
        )
    }

    /** The №36 error contract: the status, RFC 9457 body and `errors.userIds=[code]`. */
    private fun assertSnapshotProblem(
        response: ResponseEntity<String>,
        expectedStatus: HttpStatus,
        expectedCode: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№36 must answer <%s>, got <%s>: %s",
                expectedStatus,
                response.statusCode,
                response.body,
            ).isEqualTo(expectedStatus)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("№36 errors must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(USER_IDS_PARAM)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                USER_IDS_PARAM,
                expectedCode,
                codes,
                response.body,
            ).containsExactly(expectedCode)
    }

    /** №36 for one target: asserts 200 + the per-request item and returns it. */
    private fun snapshotOf(
        observer: MessagingUser,
        target: UUID,
    ): JsonNode {
        val response = snapshotRaw(observer, listOf(target))
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№36 GET /users/me/presence must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val items = objectMapper.readTree(response.body)["items"]
        val item = items.firstOrNull { it[USER_ID_FIELD].asText() == target.toString() }
        assertThat(item)
            .overridingErrorMessage(
                "№36 must answer one item per requested userId, missing <%s> in %s",
                target,
                response.body,
            ).isNotNull
        return item!!
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

    /** Polls №36 until the target converges to [expectedStatus] within [deadline]. */
    private fun awaitSnapshotStatus(
        observer: MessagingUser,
        target: UUID,
        expectedStatus: String,
        deadline: Duration,
    ): JsonNode {
        val endAt = System.nanoTime() + deadline.toNanos()
        var latest: JsonNode? = null
        while (System.nanoTime() < endAt) {
            latest = snapshotOf(observer, target)
            if (latest[STATUS_FIELD].asText() == expectedStatus) return latest
            Thread.sleep(SNAPSHOT_POLL_MILLIS)
        }
        val item =
            latest
                ?: throw AssertionError("the №36 convergence window must allow at least one call for <$target>")
        assertThat(item[STATUS_FIELD].asText())
            .overridingErrorMessage(
                "№36 must converge to <%s> for <%s> within %s, latest: %s",
                expectedStatus,
                target,
                deadline,
                item,
            ).isEqualTo(expectedStatus)
        throw AssertionError("unreachable — the assert above must have thrown")
    }

    /** Contract №37 `POST /users/me/presence/heartbeat` — raw response. */
    private fun postHeartbeat(
        user: MessagingUser,
        connectionId: UUID,
    ): ResponseEntity<String> = postHeartbeat(user, connectionId.toString())

    private fun postHeartbeat(
        user: MessagingUser,
        connectionId: String,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            HEARTBEAT_PATH,
            HttpEntity(mapOf(CONNECTION_ID_FIELD to connectionId), bearerJsonHeaders(user)),
            String::class.java,
        )

    /** Retries №37 across the 429 throttle until 204; any other status fails on the spot. */
    private fun heartbeatUntilSuccess(
        user: MessagingUser,
        connectionId: UUID,
        deadline: Duration,
    ): ResponseEntity<String> {
        val endAt = System.nanoTime() + deadline.toNanos()
        var last: ResponseEntity<String>? = null
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
            "№37 for <$connectionId> must recover with 204 within $deadline, " +
                "last: <${last?.statusCode}> ${last?.body}",
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

    /** The integral `Retry-After` (seconds, ≥ 1) of a №37 429 — the client's next-interval cue. */
    private fun retryAfterSecondsOf(rejection: ResponseEntity<String>): Long {
        val header = rejection.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val seconds = header?.trim()?.toLongOrNull()
        assertThat(seconds)
            .overridingErrorMessage(
                "the №37 flood refusal must carry an integral Retry-After ≥ 1, got <%s>",
                header,
            ).isNotNull
        assertThat(seconds!!)
            .overridingErrorMessage("Retry-After must be within one refill window (≥1s, ≤60s), got <%s>", header)
            .isBetween(RETRY_AFTER_FLOOR_SECONDS, RETRY_AFTER_CEILING_SECONDS)
        return seconds
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

    /**
     * Consumes frames for [window] and fails the moment a `presence.updated`
     * frame shows up — the "exactly one fanout / nothing re-pushed" half of
     * every scenario. A per-frame timeout (no COMPLETE frame within the
     * window) is the expected clean exit.
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
        const val MESSAGE_CREATED_EVENT = "message.created"
        const val CONNECTION_ID_FIELD = "connectionId"
        const val USER_ID_FIELD = "userId"
        const val STATUS_FIELD = "status"
        const val REV_FIELD = "rev"
        const val USER_IDS_PARAM = "userIds"
        const val ITEMS_FIELD = "items"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val STATUS_UNKNOWN = "unknown"
        const val FLOOD_LIMIT = "flood_limit"
        const val MALFORMED_REQUEST = "malformed_request"
        const val CONNECTION_NOT_FOUND = "presence_connection_not_found"
        const val IDS_TOO_MANY = "presence_ids_too_many"
        const val MALFORMED_CONNECTION_ID = "not-a-uuid"
        const val EMPTY_PARAM = ""
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val TIMEOUT_MARKER = "timed out"
        const val SNAPSHOT_PATH = "/api/v1/users/me/presence"
        const val HEARTBEAT_PATH = "/api/v1/users/me/presence/heartbeat"

        /** T003: the tightened IT windows this IT's budgets derive from. */
        const val PROFILE_TTL_SECONDS = 3L
        const val PROFILE_HYSTERESIS_SECONDS = 2L
        const val PROFILE_HEARTBEAT_SECONDS = 1L
        const val PROFILE_POLLER_MILLIS = 200L

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L
        const val HEARTBEAT_BURST_CAP = 60

        /** openapi №36 (presence-api.md §1): the batch cap after dedup. */
        const val SNAPSHOT_BATCH_LIMIT = 200

        /** The test-profile №36 bucket (T016): capacity 120 over 60 s refills 2 tokens/s. */
        const val SNAPSHOT_BURST_CAP = 150
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1000L
        const val RECOVERY_MARGIN_MILLIS = 750L
        const val RECOVERY_POLL_MILLIS = 250L
        const val SNAPSHOT_POLL_MILLIS = 200L
        const val PUMP_THREAD_NAME = "presence-it-heartbeat"

        /** SC-001: from the subject's `connected` frame to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)
        val DELIVERY_BUDGET: Duration = Duration.ofSeconds(5)
        val RECOVERY_DEADLINE: Duration = Duration.ofSeconds(5)
    }
}
