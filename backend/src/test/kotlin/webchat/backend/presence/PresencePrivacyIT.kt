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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.config.PresenceProperties
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T030 (tasks.md Phase 6, US4): the PRIVACY/ADDRESSING contract of 007 over
 * the real stack (Testcontainers PG+Redis through [PresenceTestSupport], the
 * tightened test-profile windows of T003 — ttl 3 s, hysteresis 2 s,
 * heartbeat 1 s, poller 200 ms) — the HTTP/SSE twin of the adapter-level
 * [JdbcVisibilityAudienceReaderIT]: every membership change rides the REAL
 * 004/006 ports (№11 ensure, №21 contacts, №23 block, №27 group, №32 kick),
 * never raw SQL — the only direct write is the `users.presence_hidden`
 * column (V15) no port can reach until №38 lands with T032:
 *
 *  * SC-004: a STRANGER (no chat, no contact) and a BLOCK-PAIR (a direct
 *    chat neutered by №23) receive NOT A SINGLE `presence.updated` of the
 *    subject — not on `online`, not on `offline` — while the audience peer
 *    of the very same direct chat receives both; №36 answers both outsiders
 *    the indistinguishable `unknown` + rev 0;
 *  * «нет доступа» не различает «офлайн»/«скрыто» (FR-007, US4 AC4): the
 *    №36 answer of the stranger is ONE and THE SAME `unknown` for an
 *    offline subject, a HIDDEN subject (`presence_hidden=true` staged on
 *    the V15 column) and a nonexistent userId — existence is never
 *    disclosed;
 *  * the GROUP leg: the mates of a shared №27 group see each other's
 *    statuses (events and №36 alike), and an observer sharing a DIRECT
 *    AND a GROUP chat with the subject receives EXACTLY ONE
 *    `presence.updated` per transition — one `rt:user:{observer}` fanout
 *    regardless of the shared-chat count (edge «до 200 участников»);
 *  * the audience CONVERGENCE edges (≤ 5 s, as 006 FR-010): after a №32
 *    kick the removed member stops receiving the subject's transitions on
 *    his STILL-MOUNTED №18 stream and №36 collapses to `unknown`, while a
 *    remaining mate keeps receiving — the audience is computed live per
 *    transition (research §C1, no cache, no invalidation); after a №21
 *    contact addition the FIRST transition that follows reaches the new
 *    observer on his still-mounted stream — no client restart, no
 *    reconnect.
 *
 * NOTE (TDD, constitution VI): unlike the T031 invisible suite this file
 * is the VERIFICATION leg of the already-landed audience machinery — the
 * T009/T016/T017 implementations must keep it GREEN (their acceptance
 * criteria cite T030 verbatim); the `presence_hidden` staging pins the
 * no-access indistinguishability the T033 snapshot semantics must never
 * regress. Budgets derive from [PresenceProperties] exactly like
 * PresenceIT/PresenceExpiryIT: windows + poller ticks + CI slack, plus
 * the dedicated 5 s convergence window of the membership edges.
 */
@Suppress("LargeClass", "TooManyFunctions", "LongMethod") // T030: one scenario per US4 bullet + the T011 fixtures
class PresencePrivacyIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : PresenceTestSupport() {
    /** A delivered-then-consumed transition must stay silent for the window + poller slack. */
    private val windowWithNoEvent: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /** An explicit close publishes `offline` after hysteresis + a couple of poller ticks (+ CI slack). */
    private val offlinePublicationBudget: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /** T030: the membership-convergence edge window (≤ 5 s, as 006 FR-010). */
    private val convergenceWindow: Duration = Duration.ofSeconds(CONVERGENCE_WINDOW_SECONDS)

    /**
     * SC-004 (US4 AC3): the stranger and the block-pair receive NOT A
     * SINGLE `presence.updated` of the subject — neither `online` nor the
     * hysteresis-deferred `offline` — while the direct-chat peer of the
     * very same staging receives both transitions with a strictly growing
     * rev; №36 mirrors the same policy: `unknown` + rev 0 for the
     * outsiders, the published status/rev for the peer. The «нет доступа»
     * bullet rides the same stranger BEFORE anything connects: an offline
     * subject, a HIDDEN subject (the V15 column staged directly — №38
     * arrives with T032) and a nonexistent userId share ONE
     * indistinguishable №36 answer (FR-007, US4 AC4).
     */
    @Test
    fun `stranger and block pair receive zero events and indistinguishable unknown snapshots`() {
        val (alice, bob) = messagingPair()
        val stranger = messagingUser("carol")
        val blocker = messagingUser("dave")
        ensureChatOk(alice, bob.id) // the audience peer — the delivery control
        ensureChatOk(alice, blocker.id) // a would-be observer neutered by the block below

        // FR-007 before any connection: offline, hidden and nonexistent share ONE answer
        assertSnapshotUnknown(stranger, alice.id)
        setPresenceHidden(alice.id, hidden = true)
        assertSnapshotUnknown(stranger, alice.id)
        assertSnapshotUnknown(stranger, UUID.randomUUID())
        // T033: the V15 mode now carries runtime meaning — an incognito
        // subject's open hook never publishes `online` — so restore the
        // NORMAL subject the delivery control below observes
        setPresenceHidden(alice.id, hidden = false)

        // №23 (004): the block removes the pair from each other's audience BOTH ways
        assertThat(blockUser(blocker, alice.id).statusCode)
            .overridingErrorMessage("staging: the №23 block of the pair must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)

        val bobStream = openUserEvents(bob)
        val strangerStream = openUserEvents(stranger)
        val blockerStream = openUserEvents(blocker)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        var onlineRev = 0L
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(strangerStream)
            awaitConnectedFrame(blockerStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(onlineEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage("the audience peer must receive the online transition (delivery control)")
                    .isEqualTo(STATUS_ONLINE)
                onlineRev = onlineEvent[REV_FIELD].asLong()

                // SC-004: zero frames reach the stranger and the block-pair
                assertNoPresenceEventFor(strangerStream, alice.id, convergenceWindow)
                assertNoPresenceEventFor(blockerStream, alice.id, convergenceWindow)

                // №36 resolves through the very same policy (SC-004: event/snapshot agreement)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
                assertSnapshotUnknown(stranger, alice.id)
                assertSnapshotUnknown(blocker, alice.id)

                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            aliceStream.close()

            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, offlinePublicationBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertThat(offlineEvent[REV_FIELD].asLong())
                .overridingErrorMessage("rev must strictly increase across the offline transition")
                .isGreaterThan(onlineRev)

            // the offline transition is equally invisible to the outsiders
            assertNoPresenceEventFor(strangerStream, alice.id, convergenceWindow)
            assertNoPresenceEventFor(blockerStream, alice.id, convergenceWindow)
            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, offlineEvent[REV_FIELD].asLong())
            assertSnapshotUnknown(stranger, alice.id)
            assertSnapshotUnknown(blocker, alice.id)

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online through the scenario: %s",
                    pumpFailures,
                )
                .isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { strangerStream.close() }
            runCatching { blockerStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * The GROUP leg of §1.4 (US4 AC1): the mates of a shared №27 group see
     * each other's statuses — the group-only mate receives the very
     * `online`/`offline` transitions and №36 answers him the published
     * status/rev; the edge «один фанаут на наблюдателя» (US4 AC4 /
     * presence-events.md §2): an observer sharing a DIRECT AND a GROUP
     * chat with the subject receives EXACTLY ONE `presence.updated` per
     * transition — the audience set emits him once, never once per chat.
     */
    @Test
    fun `group mates see each other and a direct plus group observer gets exactly one event per transition`() {
        val host = messagingUser("host")
        val alice = messagingUser("alice")
        val bob = messagingUser("bob")
        val mate = messagingUser("mate")
        val groupId = stageGroupWithMembers(host, "T030 Группа", listOf(alice, bob, mate))
        ensureChatOk(alice, bob.id) // bob: direct AND group — the one-fanout edge

        val bobStream = openUserEvents(bob)
        val mateStream = openUserEvents(mate)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        var onlineRev = 0L
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(mateStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // the group-only mate: the group leg of §1.4 delivers and №36 agrees
                val mateEvent = awaitPresenceEvent(mateStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(mateEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                onlineRev = mateEvent[REV_FIELD].asLong()
                assertNoPresenceEventFor(mateStream, alice.id, windowWithNoEvent)
                assertSnapshotStatus(mate, alice.id, STATUS_ONLINE, onlineRev)

                // the direct-AND-group observer: EXACTLY ONE frame of the very transition
                val bobEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(bobEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                assertThat(bobEvent[REV_FIELD].asLong())
                    .overridingErrorMessage("one transition = one rev for every observer of it")
                    .isEqualTo(onlineRev)
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            aliceStream.close()

            val bobOffline = awaitPresenceEvent(bobStream, alice.id, offlinePublicationBudget)
            assertThat(bobOffline[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertThat(bobOffline[REV_FIELD].asLong()).isGreaterThan(onlineRev)
            assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)

            val mateOffline = awaitPresenceEvent(mateStream, alice.id, offlinePublicationBudget)
            assertThat(mateOffline[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertThat(mateOffline[REV_FIELD].asLong())
                .overridingErrorMessage("the group mate must observe the very offline rev of the transition")
                .isEqualTo(bobOffline[REV_FIELD].asLong())
            assertNoPresenceEventFor(mateStream, alice.id, windowWithNoEvent)

            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, bobOffline[REV_FIELD].asLong())
            assertSnapshotStatus(mate, alice.id, STATUS_OFFLINE, mateOffline[REV_FIELD].asLong())

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online through the scenario: %s",
                    pumpFailures,
                )
                .isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { mateStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * The convergence edge «исключение из группы» (US4 AC4, ≤ 5 s as 006
     * FR-010): a №32 kick of the observer removes him from the subject's
     * audience — the very NEXT transition stops reaching his STILL-MOUNTED
     * №18 stream (the frame is simply not addressed to `rt:user:{him}`
     * anymore) while a remaining mate keeps receiving it, proving the
     * transition itself fired; №36 collapses to the indistinguishable
     * `unknown` immediately — the audience is recomputed live from PG per
     * transition and per snapshot, no cache to invalidate (research §C1).
     */
    @Test
    fun `group kick converges the audience and the events stop reaching the removed member`() {
        val host = messagingUser("host")
        val alice = messagingUser("alice")
        val bob = messagingUser("bob")
        val groupId = stageGroupWithMembers(host, "T030 Сходимость", listOf(alice, bob))

        val bobStream = openUserEvents(bob)
        val hostStream = openUserEvents(host)
        var aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        var onlineRev = 0L
        try {
            awaitConnectedFrame(bobStream)
            awaitConnectedFrame(hostStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // pre-kick control: the group leg delivers to bob, №36 is visible
                val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(onlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                onlineRev = onlineEvent[REV_FIELD].asLong()
                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)
                // consume the same transition on the host side too — the post-kick await must start clean
                assertThat(awaitPresenceEvent(hostStream, alice.id, ONLINE_PUBLICATION_BUDGET)[REV_FIELD].asLong())
                    .isEqualTo(onlineRev)

                // №32 by the owner — the membership edge under test
                assertThat(kickMember(host, groupId, bob.id).statusCode)
                    .overridingErrorMessage("staging: the №32 kick of the observer must answer 204")
                    .isEqualTo(HttpStatus.NO_CONTENT)

                // the per-pair policy has converged already: bob is an outsider now
                assertSnapshotUnknown(bob, alice.id)

                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            aliceStream.close()

            // the offline transition FIRES (the remaining mate observes it)…
            val offlineEvent = awaitPresenceEvent(hostStream, alice.id, offlinePublicationBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev).isGreaterThan(onlineRev)

            // …but the removed member's STILL-MOUNTED stream stays silent (≤ 5 s window)
            assertNoPresenceEventFor(bobStream, alice.id, convergenceWindow)
            assertSnapshotStatus(host, alice.id, STATUS_OFFLINE, offlineRev)
            assertSnapshotUnknown(bob, alice.id)

            // and the return transition keeps bypassing him the very same way
            aliceStream = openUserEvents(alice)
            val returnPumpConnections = CopyOnWriteArraySet<UUID>()
            returnPumpConnections += awaitConnectedFrame(aliceStream)
            val returnPump = startHeartbeatPump(alice, returnPumpConnections, pumpFailures)
            try {
                val returnEvent = awaitPresenceEvent(hostStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(returnEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                assertThat(returnEvent[REV_FIELD].asLong()).isGreaterThan(offlineRev)
                assertNoPresenceEventFor(bobStream, alice.id, convergenceWindow)
                assertSnapshotStatus(host, alice.id, STATUS_ONLINE, returnEvent[REV_FIELD].asLong())
                assertSnapshotUnknown(bob, alice.id)
            } finally {
                returnPump.close()
            }

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online through the scenario: %s",
                    pumpFailures,
                )
                .isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { hostStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * The convergence edge «добавление в контакты» (US4 AC4): a №21 contact
     * addition pulls the observer INTO the subject's audience — the very
     * NEXT transition reaches his stream that has stayed mounted since
     * BEFORE the contact existed (no client restart, no reconnect — the
     * deliverability changed server-side alone), №36 flips from the
     * indistinguishable `unknown` to the published status, and the
     * following return transition arrives within the SC-001 budget.
     */
    @Test
    fun `contact addition starts delivering events to the mounted stream without a client restart`() {
        val alice = messagingUser("alice")
        val observer = messagingUser("carol")

        // the observer's №18 stream opens FIRST and stays mounted to the very end
        val observerStream = openUserEvents(observer)
        var aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        var onlineRev = 0L
        try {
            awaitConnectedFrame(observerStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)

            // pre-contact: no chat, no contact — the subject is indistinguishable from nonexistent
            assertSnapshotUnknown(observer, alice.id)

            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                // №21: the observer adds the subject — the contact leg of §1.4
                addContactOk(observer, alice.id)

                // the per-pair policy converged immediately: №36 answers the PUBLISHED status
                val visible = snapshotOf(observer, alice.id)
                assertThat(visible[STATUS_FIELD].asText())
                    .overridingErrorMessage(
                        "№36 must answer the published status right after the №21 contact addition, got <%s>",
                        visible,
                    ).isEqualTo(STATUS_ONLINE)
                onlineRev = visible[REV_FIELD].asLong()
                assertThat(onlineRev)
                    .overridingErrorMessage("a published online status carries a positive rev, got <%s>", visible)
                    .isPositive

                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            aliceStream.close()

            // the FIRST frame ever addressed to the observer lands on the STILL-MOUNTED stream
            val offlineEvent = awaitPresenceEvent(observerStream, alice.id, offlinePublicationBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev).isGreaterThan(onlineRev)
            assertSnapshotStatus(observer, alice.id, STATUS_OFFLINE, offlineRev)

            // and the return transition arrives within SC-001 — still no reconnect of the observer
            aliceStream = openUserEvents(alice)
            val returnPumpConnections = CopyOnWriteArraySet<UUID>()
            returnPumpConnections += awaitConnectedFrame(aliceStream)
            val returnPump = startHeartbeatPump(alice, returnPumpConnections, pumpFailures)
            try {
                val returnEvent = awaitPresenceEvent(observerStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(returnEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                assertThat(returnEvent[REV_FIELD].asLong()).isGreaterThan(offlineRev)
                assertSnapshotStatus(observer, alice.id, STATUS_ONLINE, returnEvent[REV_FIELD].asLong())
                assertNoPresenceEventFor(observerStream, alice.id, windowWithNoEvent)
            } finally {
                returnPump.close()
            }

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online through the scenario: %s",
                    pumpFailures,
                )
                .isEmpty()
        } finally {
            runCatching { observerStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    // --- the №18/№36/№37 fixtures of T011, reused verbatim across the 007 ITs ---

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

    /** №36 must answer the INDISTINGUISHABLE «no access»: `unknown` + rev 0 (FR-007, US4 AC4). */
    private fun assertSnapshotUnknown(
        observer: MessagingUser,
        target: UUID,
    ) {
        val item = snapshotOf(observer, target)
        assertThat(item[STATUS_FIELD].asText())
            .overridingErrorMessage(
                "№36 must answer the indistinguishable <%s> for <%s> (no access never reveals " +
                    "offline-ness, «невидимку» or existence), got <%s> in %s",
                STATUS_UNKNOWN,
                target,
                item[STATUS_FIELD].asText(),
                item,
            ).isEqualTo(STATUS_UNKNOWN)
        assertThat(item[REV_FIELD].asLong())
            .overridingErrorMessage(
                "an unknown №36 item must carry rev 0 (a structural no-op for the max(rev) merge), got <%s>",
                item,
            ).isZero
    }

    // --- the 004/006 membership staging (real ports, the JdbcVisibilityAudienceReaderIT discipline) ---

    /** Contract №21 `POST /api/v1/contacts` — the observer's contact of the target (the §1.4 contact leg). */
    private fun addContactOk(
        owner: MessagingUser,
        userId: UUID,
    ) {
        val response = postJsonAuthorized(CONTACTS_PATH, mapOf(USER_ID_FIELD to userId.toString()), owner)
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "staging: the №21 contact of <%s> → <%s> must succeed, got <%s>: %s",
                owner.username,
                userId,
                response.statusCode,
                response.body,
            ).isTrue
    }

    /** Contract №23 `PUT /api/v1/users/{userId}/block` — raw response (the §1.4 block anti-join). */
    private fun blockUser(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchangeWithAuth(HttpMethod.PUT, "$USERS_PATH/$userId/block", user)

    /**
     * Contract №27 `POST /api/v1/groups` staging: the №21 contacts FIRST
     * (the whole №27 batch dies on `not_in_contacts` otherwise — FR-002 of
     * 006), then the group with the initial roster; returns its chatId.
     */
    private fun stageGroupWithMembers(
        owner: MessagingUser,
        title: String,
        members: List<MessagingUser>,
    ): UUID {
        members.forEach { addContactOk(owner, it.id) }
        val response =
            postJsonAuthorized(
                GROUPS_PATH,
                mapOf(
                    TITLE_FIELD to title,
                    MEMBER_USER_IDS_FIELD to members.map { it.id.toString() },
                ),
                owner,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "staging: the №27 group with %d initial members must answer 201, got <%s>: %s",
                members.size,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        return UUID.fromString(objectMapper.readTree(response.body)[CHAT_ID_FIELD].asText())
    }

    /** Contract №32 `DELETE /api/v1/groups/{chatId}/members/{userId}` — raw response (the kick edge). */
    private fun kickMember(
        user: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ): ResponseEntity<String> = exchangeWithAuth(HttpMethod.DELETE, "$GROUPS_PATH/$chatId/members/$userId", user)

    /**
     * The V15 `users.presence_hidden` staging (data-model §1.5/§2): the
     * writable port arrives with №38 (T032), and «нет доступа» must stay
     * indistinguishable regardless — raw SQL only for the state no port
     * can write yet, the T009 discipline.
     */
    private fun setPresenceHidden(
        userId: UUID,
        hidden: Boolean,
    ) {
        assertThat(jdbcTemplate.update(SET_PRESENCE_HIDDEN_SQL, hidden, userId))
            .overridingErrorMessage("staging: the V15 presence_hidden flip must update exactly the subject row")
            .isEqualTo(1)
    }

    // --- the raw HTTP legs ---

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

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    private fun bearerHeaders(user: MessagingUser) = HttpHeaders().apply { setBearerAuth(user.accessToken) }

    private fun bearerJsonHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    private fun postJsonAuthorized(
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            path,
            HttpEntity(payload, bearerJsonHeaders(user)),
            String::class.java,
        )

    private fun exchangeWithAuth(
        method: HttpMethod,
        path: String,
        user: MessagingUser,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, bearerHeaders(user)),
            String::class.java,
        )

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
        const val CHAT_ID_FIELD = "chatId"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val STATUS_UNKNOWN = "unknown"
        const val TIMEOUT_MARKER = "timed out"
        const val SNAPSHOT_PATH = "/api/v1/users/me/presence"
        const val HEARTBEAT_PATH = "/api/v1/users/me/presence/heartbeat"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val GROUPS_PATH = "/api/v1/groups"
        const val USERS_PATH = "/api/v1/users"
        const val TITLE_FIELD = "title"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val PUMP_THREAD_NAME = "presence-privacy-it-heartbeat"

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L

        /** T030: the membership-convergence edge window (≤ 5 s, as 006 FR-010). */
        const val CONVERGENCE_WINDOW_SECONDS = 5L

        /** SC-001: from the subject's registration to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)

        /** The V15 column no port can write until №38 (T032) lands. */
        const val SET_PRESENCE_HIDDEN_SQL = "UPDATE users SET presence_hidden = ? WHERE id = ?"
    }
}
