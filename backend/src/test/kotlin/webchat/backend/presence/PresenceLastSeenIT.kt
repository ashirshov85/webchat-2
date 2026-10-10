package webchat.backend.presence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import org.yaml.snakeyaml.Yaml
import webchat.backend.config.PresenceProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T042 (tasks.md Phase 5, US3): the lastSeen disclosure contract of 008a —
 * the optional `lastSeenAt` of №36 `PresenceStatusItem` and №18
 * `PresenceUpdatedEvent` — over the real stack (Testcontainers PG+Redis
 * through [PresenceTestSupport], the tightened test-profile windows of 007
 * T003 — ttl 3 s, hysteresis 2 s, heartbeat 1 s, poller 200 ms):
 *
 *  * the DISCLOSURE conjunction (FR-010, data-model §2.2): `lastSeenAt`
 *    rides №36 and the offline `presence.updated` ONLY when
 *    status=offline ∧ the observer sits in the 007 visibility audience
 *    (a direct-chat peer here) ∧ the subject is NOT incognito ∧ the
 *    `presence:lastseen:{userId}` key exists — a NEVER-connected subject
 *    answers the plain offline item WITHOUT the field (the «нет данных»
 *    fallback), and an ONLINE subject never carries it;
 *  * SC-005 accuracy: a graceful close stamps the very close moment
 *    (штатное закрытие — точное) and an ABANDONED registration (no stop,
 *    the TTL reap of 007) still discloses a stamp whose drift from the
 *    wall clock stays inside the TTL-model budget — ttl + hysteresis +
 *    poller ticks + CI slack (production ≈ 90 + 45 + 1 s);
 *  * SC-002 privacy (0 leaks): an INCOGNITO subject (№38, freeze) hides
 *    the field ENTIRELY — the freeze `offline` event carries NO
 *    `lastSeenAt` (the edge «не время включения инкогнито», just a bare
 *    offline) and his №36 item shrinks to the exact shape of a
 *    no-data subject — indistinguishable; a STRANGER and a BLOCK-PAIR
 *    observer keep answering the 007 `unknown` + rev 0 WITHOUT the
 *    field while the very same staging discloses it to the audience
 *    peer — «нет данных» ≡ «скрыто» ≡ «посторонний» never diverge;
 *  * schema conformance: every №36 item and `presence.updated` payload
 *    under assertion validates against the LIVE
 *    `components.schemas.PresenceStatusItem`/`PresenceUpdatedEvent` of
 *    contracts/openapi.yaml (0.9.0 declared the optional field — T005).
 *
 * NOTE (TDD, constitution VI): written BEFORE T043/T044 — until the
 * `presence:lastseen` stamp of RedisPresenceStore and the disclosure
 * filter of PresenceService land, every awaited `lastSeenAt` leg here
 * fails (RED) by design at the missing field of the offline snapshot or
 * event; the privacy pins (online/freeze/hidden/unknown carry NO field)
 * already hold and guard the 007 semantics the implementation must never
 * regress. Budgets derive from [PresenceProperties] exactly like the
 * other presence ITs: windows + poller ticks + CI slack.
 */
@Suppress("LargeClass", "TooManyFunctions", "LongMethod") // T042: one scenario per US3 bullet + the 007 fixture reuse
class PresenceLastSeenIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
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

    /**
     * SC-005 in test-profile windows: the TTL-model drift allowance —
     * ttl + hysteresis + poller ticks + CI slack (production ≈ 137 s);
     * doubles as the reap-publication budget of the abandoned leg (the
     * 007 expiry machine publishes within the very same windows).
     */
    private val sc005Budget: Duration =
        presenceProperties.ttl
            .plus(presenceProperties.hysteresis)
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /**
     * Research §D2 (007): a mode toggle publishes IMMEDIATELY (the offq
     * bypass) — the budget is STRICTLY SMALLER than one hysteresis
     * window, so a freeze transition accidentally deferred fails.
     */
    private val immediateToggleBudget: Duration = presenceProperties.hysteresis.minus(presenceProperties.pollerInterval)

    /**
     * The happy-path conjunction (FR-010/US3 AC1+AC5): a NEVER-connected
     * subject answers the plain offline item WITHOUT the field (нет
     * данных), the ONLINE item and event of the very same subject never
     * carry it, and the graceful close — the штатное закрытие of
     * research B1, stamped by the UNREGISTER leg — discloses an ACCURATE
     * `lastSeenAt` to the audience peer BOTH in the offline №18 frame and
     * the №36 snapshot: the stamp parses as RFC 3339, lands on the close
     * moment (± clock slack) and its drift from the wall clock stays
     * inside the SC-005 budget; the rev keeps strictly growing.
     */
    @Test
    fun `online carries no field and a graceful close discloses accurate lastSeenAt to the audience`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        // нет данных (the key never existed): offline WITHOUT the field — the neutral fallback
        val noData = snapshotOf(bob, alice.id)
        assertThat(noData[STATUS_FIELD].asText())
            .overridingErrorMessage("a never-connected subject must answer offline, got <%s>", noData)
            .isEqualTo(STATUS_OFFLINE)
        assertNoLastSeenAt(noData, "the no-data item (never online since the feature)")
        assertItemConformsToSchema(noData)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            var onlineRev = 0L
            try {
                val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(onlineEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage("the control: the connected subject must publish online first")
                    .isEqualTo(STATUS_ONLINE)
                onlineRev = onlineEvent[REV_FIELD].asLong()
                assertNoLastSeenAt(onlineEvent, "the ONLINE presence.updated (при online поля нет)")
                assertEventConformsToSchema(onlineEvent)

                // the №36 twin of the online rule: status/rev agree, the field stays away
                val onlineItem = snapshotOf(bob, alice.id)
                assertThat(onlineItem[STATUS_FIELD].asText()).isEqualTo(STATUS_ONLINE)
                assertThat(onlineItem[REV_FIELD].asLong()).isEqualTo(onlineRev)
                assertNoLastSeenAt(onlineItem, "the ONLINE №36 item (при online поля нет)")
                assertItemConformsToSchema(onlineItem)

                pumpedConnections.clear()
            } finally {
                pump.close()
            }

            val closedAt = Instant.now()
            aliceStream.close()

            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, offlinePublicationBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            val offlineRev = offlineEvent[REV_FIELD].asLong()
            assertThat(offlineRev)
                .overridingErrorMessage("rev must strictly increase across the offline transition")
                .isGreaterThan(onlineRev)
            val lastSeen = assertDisclosesAccurateLastSeen(offlineEvent, floor = closedAt)
            assertEventConformsToSchema(offlineEvent)

            val offlineItem = snapshotOf(bob, alice.id)
            assertThat(offlineItem[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertThat(offlineItem[REV_FIELD].asLong()).isEqualTo(offlineRev)
            val snapshotLastSeen = assertDisclosesAccurateLastSeen(offlineItem, floor = closedAt)
            assertThat(snapshotLastSeen)
                .overridingErrorMessage(
                    "№36 and the offline event must disclose the very same lastSeen stamp, got <%s> vs <%s>",
                    snapshotLastSeen,
                    lastSeen,
                ).isEqualTo(lastSeen)
            assertItemConformsToSchema(offlineItem)

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online through the scenario: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * SC-005 «обрыв»: the subject falls silent WITHOUT any close (a hung
     * tab / a dead network — no UNREGISTER ever fires) — the 007 TTL reap
     * publishes `offline` within ttl + hysteresis + poller ticks (+ CI
     * slack) and the disclosed `lastSeenAt` is the stamp of the LAST
     * heartbeat (research B1: the reap writes no stamp of its own): the
     * drift from the wall clock stays inside the very same budget, the
     * №36 snapshot agrees with the event.
     */
    @Test
    fun `an abandoned registration self-expires and still discloses lastSeenAt within the sc005 budget`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(onlineEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage("the control: the connected subject must publish online first")
                    .isEqualTo(STATUS_ONLINE)

                // обрыв: the renewals stop, the socket STAYS OPEN — only the TTL reap remains
                pumpedConnections.clear()
            } finally {
                pump.close()
            }

            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, sc005Budget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertDisclosesAccurateLastSeen(offlineEvent, floor = Instant.now().minus(sc005Budget))
            assertEventConformsToSchema(offlineEvent)

            val offlineItem = snapshotOf(bob, alice.id)
            assertThat(offlineItem[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertThat(offlineItem[REV_FIELD].asLong()).isEqualTo(offlineEvent[REV_FIELD].asLong())
            assertDisclosesAccurateLastSeen(offlineItem, floor = Instant.now().minus(sc005Budget))
            assertItemConformsToSchema(offlineItem)

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online until the abandonment: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * SC-002 (US3 AC2, the freeze edge): enabling №38 with a LIVE
     * connection publishes the freeze `offline` IMMEDIATELY (the offq
     * bypass, [immediateToggleBudget]) — WITHOUT `lastSeenAt` (не «время
     * включения инкогнито» — целиком скрыт, even though the stamp exists
     * from the just-lived registration); the №36 item of the hidden
     * subject carries NO field and its shape is EXACTLY the shape of a
     * no-data subject (a never-connected peer of the very same observer)
     * — «скрыто» ≡ «нет данных» on the wire; the stranger keeps the
     * indistinguishable `unknown` of 007, and ≤ ONE displayed switch
     * costs the toggle (nothing further follows).
     */
    @Test
    fun `incognito hides lastSeenAt entirely and the freeze offline event carries no field`() {
        val (alice, bob) = messagingPair()
        val ghost = messagingUser("ghost")
        val stranger = messagingUser("carol")
        ensureChatOk(alice, bob.id)
        ensureChatOk(bob, ghost.id) // the no-data twin of the SAME observer

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(onlineEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage("the control: the connected subject must publish online first")
                    .isEqualTo(STATUS_ONLINE)

                // включение невидимки: the freeze offline lands IMMEDIATELY — bare, no stamp
                assertThat(putSettings(alice, incognito = true).statusCode)
                    .overridingErrorMessage("staging: the №38 enable of a live subject must answer 200")
                    .isEqualTo(HttpStatus.OK)
                val frozenEvent = awaitPresenceEvent(bobStream, alice.id, immediateToggleBudget)
                assertThat(frozenEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage("the freeze of an online subject must publish offline")
                    .isEqualTo(STATUS_OFFLINE)
                assertNoLastSeenAt(
                    frozenEvent,
                    "the FREEZE presence.updated (инкогнито скрывает lastSeen целиком — не время включения)",
                )
                assertEventConformsToSchema(frozenEvent)

                // №36 of the hidden subject: offline WITHOUT the field…
                val hiddenItem = snapshotOf(bob, alice.id)
                assertThat(hiddenItem[STATUS_FIELD].asText())
                    .overridingErrorMessage("the frozen subject must stay offline-indistinguishable (007)")
                    .isEqualTo(STATUS_OFFLINE)
                assertNoLastSeenAt(hiddenItem, "the №36 item of an incognito subject (ключ есть — скрыто)")
                assertItemConformsToSchema(hiddenItem)

                // …and EXACTLY the shape of a no-data subject of the same observer
                val noDataItem = snapshotOf(bob, ghost.id)
                assertThat(noDataItem[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
                assertNoLastSeenAt(noDataItem, "the no-data item (ghost never connected)")
                assertThat(fieldNames(hiddenItem))
                    .overridingErrorMessage(
                        "«скрыто» ≡ «нет данных»: the hidden item must be wire-identical in shape " +
                            "(same fields, the stamp simply absent), got <%s> vs <%s>",
                        fieldNames(hiddenItem),
                        fieldNames(noDataItem),
                    ).containsExactlyElementsOf(fieldNames(noDataItem))

                // the stranger: the indistinguishable 007 answer, never a field
                assertSnapshotUnknownWithoutLastSeen(stranger, alice.id)

                // ≤ one switch per toggle: nothing further may follow
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)

                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            aliceStream.close()
            // the registration loss of a FROZEN subject publishes NOTHING at all (007,
            // the PresenceInvisibleIT freeze semantics) — a fortiori no lastSeenAt frame
            assertNoPresenceEventFor(bobStream, alice.id, offlinePublicationBudget)
            assertThat(snapshotOf(bob, alice.id)[STATUS_FIELD].asText())
                .overridingErrorMessage("№36 keeps answering the frozen subject offline-indistinguishable")
                .isEqualTo(STATUS_OFFLINE)

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online through the scenario: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    /**
     * SC-002 (US3 AC4, 0 leaks): the STRANGER (no chat, no contact) and
     * the BLOCK-PAIR observer (a direct chat neutered by №23) answer the
     * indistinguishable 007 `unknown` + rev 0 WITHOUT the field — while
     * the audience peer of the very same staging reads the disclosed
     * `lastSeenAt` in his №36 snapshot: the stamp exists, the disclosure
     * just never leaves the audience.
     */
    @Test
    fun `stranger and block pair never receive lastSeenAt while the audience peer does`() {
        val (alice, bob) = messagingPair()
        val stranger = messagingUser("carol")
        val blocker = messagingUser("dave")
        ensureChatOk(alice, bob.id) // the audience peer — the disclosure control
        ensureChatOk(alice, blocker.id) // a would-be observer neutered by the block below

        assertThat(blockUser(blocker, alice.id).statusCode)
            .overridingErrorMessage("staging: the №23 block of the pair must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(alice)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            try {
                val onlineEvent = awaitPresenceEvent(bobStream, alice.id, ONLINE_PUBLICATION_BUDGET)
                assertThat(onlineEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage("the control: the connected subject must publish online first")
                    .isEqualTo(STATUS_ONLINE)

                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            val closedAt = Instant.now()
            aliceStream.close()

            // the audience leg: the offline frame and №36 DO disclose the stamp
            val offlineEvent = awaitPresenceEvent(bobStream, alice.id, offlinePublicationBudget)
            assertThat(offlineEvent[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertDisclosesAccurateLastSeen(offlineEvent, floor = closedAt)
            val audienceItem = snapshotOf(bob, alice.id)
            assertThat(audienceItem[STATUS_FIELD].asText()).isEqualTo(STATUS_OFFLINE)
            assertDisclosesAccurateLastSeen(audienceItem, floor = closedAt)

            // 0 leaks: the outsider legs never see the field — unknown, rev 0, bare
            assertSnapshotUnknownWithoutLastSeen(stranger, alice.id)
            assertSnapshotUnknownWithoutLastSeen(blocker, alice.id)

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals must keep the subject online through the scenario: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
            runCatching { aliceStream.close() }
        }
    }

    // --- the lastSeen assertions (the T042 core) ---

    /**
     * The disclosed stamp: parses as RFC 3339, never precedes [floor]
     * (minus the pump/clock slack), never runs ahead of the wall clock
     * beyond slack, and its drift stays inside the SC-005 budget; returns
     * the parsed instant for cross-surface agreement checks.
     */
    private fun assertDisclosesAccurateLastSeen(
        node: JsonNode,
        floor: Instant,
    ): Instant {
        val field = node[LAST_SEEN_AT_FIELD]
        assertThat(field)
            .overridingErrorMessage(
                "the offline surface must DISCLOSE lastSeenAt to the audience peer (FR-010), got: %s",
                node,
            ).isNotNull
        val lastSeen =
            runCatching { OffsetDateTime.parse(field.asText()).toInstant() }
                .getOrElse {
                    throw AssertionError(
                        "lastSeenAt must be an RFC 3339 date-time (the contract format), got <${field.asText()}>",
                    )
                }
        val now = Instant.now()
        assertThat(lastSeen)
            .overridingErrorMessage(
                "the stamp must reflect the subject's live activity window (not older than the floor " +
                    "<%s> minus slack <%ss>), got <%s>",
                floor,
                CLOCK_SLACK_SECONDS,
                lastSeen,
            ).isAfter(floor.minusSeconds(ACTIVITY_SLACK_SECONDS))
        assertThat(lastSeen)
            .overridingErrorMessage(
                "the stamp must not run ahead of the wall clock beyond slack <%ss>, got <%s> vs now <%s>",
                CLOCK_SLACK_SECONDS,
                lastSeen,
                now,
            ).isBefore(now.plusSeconds(CLOCK_SLACK_SECONDS))
        val drift = Duration.between(lastSeen, now)
        assertThat(drift)
            .overridingErrorMessage(
                "SC-005: the disclosed time may diverge from the actual last activity by at most " +
                    "the TTL-model budget <%s>, got <%s>",
                sc005Budget,
                drift,
            ).isLessThanOrEqualTo(sc005Budget)
        return lastSeen
    }

    /** The field must be ENTIRELY absent — a `null` would leak the reason and break the indistinguishability. */
    private fun assertNoLastSeenAt(
        node: JsonNode,
        what: String,
    ) {
        assertThat(node.has(LAST_SEEN_AT_FIELD))
            .overridingErrorMessage(
                "%s must carry NO lastSeenAt (absence is the neutral, indistinguishable form — never null), got <%s>",
                what,
                node,
            ).isFalse
    }

    /** The №36 outsider answer of 007 — `unknown` + rev 0 — now also pinned WITHOUT the field. */
    private fun assertSnapshotUnknownWithoutLastSeen(
        observer: MessagingUser,
        target: UUID,
    ) {
        val item = snapshotOf(observer, target)
        assertThat(item[STATUS_FIELD].asText())
            .overridingErrorMessage(
                "№36 must answer the indistinguishable <%s> for <%s>, got <%s> in %s",
                STATUS_UNKNOWN,
                target,
                item[STATUS_FIELD].asText(),
                item,
            ).isEqualTo(STATUS_UNKNOWN)
        assertThat(item[REV_FIELD].asLong())
            .overridingErrorMessage("an unknown №36 item must carry rev 0, got <%s>", item)
            .isZero
        assertNoLastSeenAt(item, "the №36 item of an outsider (посторонний/блок-пара)")
        assertItemConformsToSchema(item)
    }

    // --- the №18/№36/№37/№38/№23 fixtures of T011/T031, reused verbatim across the 007 ITs ---

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
                HttpEntity<Unit>(Unit, bearerHeaders(observer)),
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

    /** Contract №38 `PUT /users/me/presence/settings` — the freeze toggle of the incognito legs. */
    private fun putSettings(
        user: MessagingUser,
        incognito: Boolean,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            SETTINGS_PATH,
            HttpMethod.PUT,
            HttpEntity("{\"$INCOGNITO_FIELD\":$incognito}", bearerJsonHeaders(user)),
            String::class.java,
        )

    /** Contract №23 `PUT /api/v1/users/{userId}/block` — the block-pair anti-join of the outsider leg. */
    private fun blockUser(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "$USERS_PATH/$userId/block",
            HttpMethod.PUT,
            HttpEntity<Unit>(Unit, bearerHeaders(user)),
            String::class.java,
        )

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

    // --- the live-contract conformance of T027 (the RealtimeSseIT T064 subset) ---

    /** The №36 item contract: validates against the LIVE `PresenceStatusItem` schema. */
    private fun assertItemConformsToSchema(item: JsonNode) = assertConformsToSchema(item, PRESENCE_STATUS_ITEM_SCHEMA)

    /** The №18 frame contract: validates against the LIVE `PresenceUpdatedEvent` schema. */
    private fun assertEventConformsToSchema(payload: JsonNode) =
        assertConformsToSchema(payload, PRESENCE_UPDATED_EVENT_SCHEMA)

    /**
     * T042 (constitution IV): asserts a live №36/№18 payload against the
     * very schema of the public contract — `contracts/openapi.yaml`
     * `components.schemas.<schemaName>` — instead of a hand-copied field
     * list, so a schema change that breaks the wire payload fails here
     * first (the 0.9.0 optional `lastSeenAt` of T005 among them). The
     * validator covers exactly the JSON Schema subset the event schemas
     * use (the RealtimeSseIT T064 subset): `type`, `required`,
     * `properties`, `additionalProperties: false`, `$ref`, `format`
     * (`uuid`/`date-time`), `maxLength`, `minimum`.
     */
    private fun assertConformsToSchema(
        payload: JsonNode,
        schemaName: String,
    ) {
        val schema = openApiContract["components"]["schemas"][schemaName]
        if (schema !is ObjectNode) {
            throw AssertionError(
                "contracts/openapi.yaml must keep components.schemas.$schemaName — " +
                    "the №36/№18 presence payloads are declared there, got: ${schema.nodeType}",
            )
        }
        val violations = mutableListOf<String>()
        validateAgainst(payload, schema, "#", violations)
        assertThat(violations)
            .overridingErrorMessage(
                "presence payload violates components.schemas.%s of contracts/openapi.yaml: %s",
                schemaName,
                violations,
            ).isEmpty()
    }

    private fun validateAgainst(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        val ref = schema["\$ref"]?.takeIf { it.isTextual }
        if (ref != null) {
            val target = resolveLocalRef(ref.asText(), path, violations)
            if (target != null) validateAgainst(node, target, path, violations)
            return
        }
        when (schema["type"]?.takeIf { it.isTextual }?.asText()) {
            "object" -> validateObject(node, schema, path, violations)
            "string" -> validateString(node, schema, path, violations)
            "integer" -> validateInteger(node, schema, path, violations)
            // a schema without a type imposes no structural constraint here
            else -> Unit
        }
    }

    private fun resolveLocalRef(
        ref: String,
        path: String,
        violations: MutableList<String>,
    ): JsonNode? {
        val target = openApiContract.at(ref.removePrefix(LOCAL_REF_PREFIX)).takeUnless { it.isMissingNode }
        return when {
            !ref.startsWith(LOCAL_REF_PREFIX) -> {
                violations.add("$path: only local schema refs are supported, got <$ref>")
                null
            }
            target == null -> {
                violations.add("$path: unresolvable schema ref <$ref>")
                null
            }
            else -> target
        }
    }

    private fun validateObject(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isObject) {
            violations.add("$path: expected an object, got <${node.nodeType}>")
            return
        }
        schema["required"]?.takeIf { it.isArray }?.forEach { requiredField ->
            if (!node.has(requiredField.asText())) {
                violations.add("$path: required field <${requiredField.asText()}> is missing")
            }
        }
        val additionalForbidden =
            schema["additionalProperties"]?.takeIf { it.isBoolean }?.asBoolean() == false
        val properties = schema["properties"]?.takeIf { it.isObject }
        node.fieldNames().asSequence().forEach { field ->
            val propertySchema = properties?.get(field)
            when {
                propertySchema != null && !propertySchema.isMissingNode ->
                    validateAgainst(node[field], propertySchema, "$path/$field", violations)
                additionalForbidden ->
                    violations.add("$path/$field: not declared by the schema and additionalProperties is false")
                else -> Unit
            }
        }
    }

    private fun validateString(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isTextual) {
            violations.add("$path: expected a string, got <${node.nodeType}>")
            return
        }
        val text = node.asText()
        val maxLength = schema["maxLength"]?.takeIf { it.isInt }?.asInt()
        if (maxLength != null && text.length > maxLength) {
            violations.add("$path: <${text.length}> chars exceeds maxLength <$maxLength>")
        }
        when (schema["format"]?.takeIf { it.isTextual }?.asText()) {
            "uuid" ->
                if (runCatching { UUID.fromString(text) }.isFailure) {
                    violations.add("$path: <$text> is not a uuid")
                }
            "date-time" ->
                if (runCatching { OffsetDateTime.parse(text) }.isFailure) {
                    violations.add("$path: <$text> is not an RFC 3339 date-time")
                }
        }
    }

    private fun validateInteger(
        node: JsonNode,
        schema: JsonNode,
        path: String,
        violations: MutableList<String>,
    ) {
        if (!node.isIntegralNumber || !node.canConvertToLong()) {
            violations.add("$path: expected an int64 integer, got <${node.asText()}>")
            return
        }
        val minimum = schema["minimum"]?.takeIf { it.isIntegralNumber }?.asLong()
        if (minimum != null && node.asLong() < minimum) {
            violations.add("$path: <${node.asLong()}> is below the schema minimum <$minimum>")
        }
    }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    private fun bearerHeaders(user: MessagingUser) = HttpHeaders().apply { setBearerAuth(user.accessToken) }

    private fun bearerJsonHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    private companion object {
        const val RETRY_MILLIS = 3_000L
        const val CONNECTED_EVENT = "connected"
        const val PRESENCE_UPDATED_EVENT = "presence.updated"
        const val CONNECTION_ID_FIELD = "connectionId"
        const val USER_ID_FIELD = "userId"
        const val STATUS_FIELD = "status"
        const val REV_FIELD = "rev"
        const val LAST_SEEN_AT_FIELD = "lastSeenAt"
        const val USER_IDS_PARAM = "userIds"
        const val ITEMS_FIELD = "items"
        const val INCOGNITO_FIELD = "incognito"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val STATUS_UNKNOWN = "unknown"
        const val TIMEOUT_MARKER = "timed out"
        const val SNAPSHOT_PATH = "/api/v1/users/me/presence"
        const val HEARTBEAT_PATH = "/api/v1/users/me/presence/heartbeat"

        /** contracts/presence-api.md §3: the №38 settings route (the freeze toggle). */
        const val SETTINGS_PATH = "/api/v1/users/me/presence/settings"
        const val USERS_PATH = "/api/v1/users"
        const val PUMP_THREAD_NAME = "presence-lastseen-it-heartbeat"

        /** The live-contract schemas of the two disclosed surfaces (0.9.0, T005). */
        const val PRESENCE_STATUS_ITEM_SCHEMA = "PresenceStatusItem"
        const val PRESENCE_UPDATED_EVENT_SCHEMA = "PresenceUpdatedEvent"

        /** The local `$ref` prefix of the contract validator (the T064 subset). */
        const val LOCAL_REF_PREFIX = "#/"

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L

        /** Wall-clock slack of the stamp assertions (Redis TIME vs the test clock, CI jitter). */
        const val CLOCK_SLACK_SECONDS = 2L

        /** The last №37 renewal may trail the floor by one pump interval + slack (the stamp window). */
        const val ACTIVITY_SLACK_SECONDS = 2L

        /** SC-001: from the subject's registration to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)

        /** `backend/` (Gradle) and the repo root (IDE runs) in order. */
        val CONTRACT_LOCATIONS: List<Path> =
            listOf(Path.of("../contracts/openapi.yaml"), Path.of("contracts/openapi.yaml"))

        val contractJson: ObjectMapper = ObjectMapper()

        /** contracts/openapi.yaml parsed once per JVM (the T064 loader). */
        val openApiContract: JsonNode by lazy { loadOpenApiContract() }

        private fun loadOpenApiContract(): JsonNode {
            val contract =
                CONTRACT_LOCATIONS.firstOrNull { Files.isRegularFile(it) }
                    ?: error(
                        "contracts/openapi.yaml not found at ${CONTRACT_LOCATIONS.joinToString()} — " +
                            "the T042 conformance check must read the live public contract",
                    )
            return contract.toFile().inputStream().use { input ->
                val yamlRoot: Any? = Yaml().load(input)
                contractJson.valueToTree<JsonNode>(yamlRoot)
            }
        }
    }
}
