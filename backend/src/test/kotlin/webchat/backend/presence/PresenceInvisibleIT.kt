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
import java.util.concurrent.atomic.AtomicLong

/**
 * T031 (tasks.md Phase 6, US4): the INCOGNITO contract of 007 over the real
 * stack (Testcontainers PG+Redis through [PresenceTestSupport], the tightened
 * test-profile windows of T003 — ttl 3 s, hysteresis 2 s, heartbeat 1 s,
 * poller 200 ms): the №38 settings port and the «невидимка» semantics of
 * FR-007/US4 AC2/AC5 (research §D1/§D2, data-model §1.2/§1.5) — every mode
 * change rides the REAL №38 route, never raw SQL (the V15 column is the
 * port's own storage — unlike the T030 staging, here the port IS the subject
 * under test):
 *
 *  * №38 contract (T032 acceptance cites this suite): GET answers the
 *    persisted `{incognito}` (false by default, V15 DEFAULT FALSE); a
 *    non-boolean and a missing field answer `400 malformed_request`
 *    (errors.incognito, problem+json); the IDEMPOTENT PUT — a repeat of
 *    the very same value — is a NO-OP without events AND without a rev
 *    advance; the per-user PUT flood bucket throttles a drain burst with
 *    `429 flood_limit` + `Retry-After` and recovers after the advertised
 *    wait;
 *  * включение при живых подключениях (US4 AC5, research §D1): the
 *    audience receives EXACTLY ONE `offline` — published IMMEDIATELY (the
 *    [immediateToggleBudget] is strictly smaller than one hysteresis
 *    window, proving the offq bypass of research §D2) with a strictly
 *    greater rev — and the mode FREEZES every further transition of the
 *    subject: the loss of the last registration resolves silently, a
 *    reconnect inside the mode publishes NOTHING, and the №36 snapshot of
 *    the target answers `offline` — INDISTINGUISHABLE from a real offline
 *    (FR-007, US4 AC2); each toggle costs ≤ ONE displayed switch (spec
 *    edge 108);
 *  * выключение (research §D1): an ONLINE subject's disable publishes
 *    `online` (the actual status, one switch, rev strictly greater); an
 *    OFFLINE subject's disable (edge, spec 109) publishes NOT A SINGLE
 *    event, the STATUS part of the №36 answer stays `offline` and the rev
 *    does not DECREASE (it may only grow — the client's max(rev) merge
 *    stays correct);
 *  * the «невидимка» reads №36 WITHOUT restrictions (FR-007): his own
 *    mode never filters HIS observer leg — the audience peer with a live
 *    connection answers `online`;
 *  * the mode ACTS ON THE WHOLE USER, not a device (US4 AC5): it
 *    survives a RELOGIN (a fresh 002 session of the same user still reads
 *    `incognito=true` — the V15 column is the only durable home, PG
 *    persists it across sessions) and the fresh session's connection
 *    stays FROZEN — no `online` ever reaches the audience while the mode
 *    holds.
 *
 * NOTE (TDD, constitution VI): written BEFORE T032/T033 — until the №38
 * `PresenceSettingsController` lands, every №38 call here fails (404, RED)
 * by design; with the port alone but without the T033 semantics the
 * freeze/snapshot legs stay RED (a connected subject still publishes
 * `online`, the snapshot still answers it). The budgets derive from
 * [PresenceProperties] exactly like PresenceIT/PresencePrivacyIT: windows
 * + poller ticks + CI slack, plus the logoutBudget-style immediate-window
 * proof of the offq bypass.
 */
@Suppress("LargeClass", "TooManyFunctions", "LongMethod") // T031: one scenario per US4 incognito bullet
class PresenceInvisibleIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val presenceProperties: PresenceProperties,
) : PresenceTestSupport() {
    /** A frozen/silent transition must stay silent for the window + poller slack. */
    private val windowWithNoEvent: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /**
     * Research §D2: a mode toggle publishes IMMEDIATELY (the offq bypass of
     * logout, §B2) — the budget is STRICTLY SMALLER than one hysteresis
     * window, so a toggle accidentally routed through the offq fails.
     */
    private val immediateToggleBudget: Duration = presenceProperties.hysteresis.minus(presenceProperties.pollerInterval)

    /** The deferred offq drain of the freeze-close leg: hysteresis + ticks (+ CI slack). */
    private val offlinePublicationBudget: Duration =
        presenceProperties.hysteresis
            .plus(presenceProperties.pollerInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS + 1)

    /**
     * №38 (contracts/presence-api.md §3, the T032 acceptance): the settings
     * round-trip — the default V15 `false`, the `malformed_request` shape
     * gate, the IDEMPOTENT PUT (a repeat of the same value is a no-op
     * WITHOUT events and WITHOUT a rev advance) and the per-user PUT flood
     * bucket (`429 flood_limit` + `Retry-After`, recovery after the wait).
     * The subject stays OFFLINE the whole scenario (no connection): every
     * legitimate toggle of his is silent by the §1.2 machine, so the
     * mounted witness stream doubles as the zero-events probe.
     */
    @Test
    fun `settings endpoint defaults false, repeats idempotently and refuses malformed and flood puts`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        try {
            awaitConnectedFrame(bobStream)

            // GET: the V15 default answers {incognito:false} — exactly the contract fields
            val initial = getSettingsRaw(alice)
            assertThat(initial.statusCode)
                .overridingErrorMessage(
                    "№38 GET /users/me/presence/settings must answer 200, got <%s>: %s",
                    initial.statusCode,
                    initial.body,
                ).isEqualTo(HttpStatus.OK)
            val initialBody = objectMapper.readTree(initial.body)
            assertThat(fieldNames(initialBody))
                .overridingErrorMessage("the №38 GET body must have exactly the PresenceSettingsResponse fields")
                .containsExactly(INCOGNITO_FIELD)
            assertThat(initialBody[INCOGNITO_FIELD].asBoolean())
                .overridingErrorMessage("the №38 default is the V15 DEFAULT FALSE, got <%s>", initial.body)
                .isFalse

            // 400: a non-boolean value and a missing field are malformed (errors.incognito)
            assertSettingsProblem(
                putSettingsBody(alice, "{\"$INCOGNITO_FIELD\":\"$MALFORMED_INCOGNITO\"}"),
                HttpStatus.BAD_REQUEST,
                MALFORMED_REQUEST,
            )
            assertSettingsProblem(putSettingsBody(alice, EMPTY_BODY_OBJECT), HttpStatus.BAD_REQUEST, MALFORMED_REQUEST)

            // PUT true → 200 with the stored value; №36 pins the post-toggle rev
            val enabled = putSettings(alice, incognito = true)
            assertThat(enabled.statusCode)
                .overridingErrorMessage(
                    "№38 PUT {incognito:true} must answer 200, got <%s>: %s",
                    enabled.statusCode,
                    enabled.body,
                ).isEqualTo(HttpStatus.OK)
            assertThat(objectMapper.readTree(enabled.body)[INCOGNITO_FIELD].asBoolean()).isTrue
            val toggledRev = snapshotOf(bob, alice.id)[REV_FIELD].asLong()

            // the IDEMPOTENT repeat: a no-op without events AND without a rev advance
            val repeat = putSettings(alice, incognito = true)
            assertThat(repeat.statusCode)
                .overridingErrorMessage(
                    "№38 PUT of the very same value is an idempotent no-op and must answer 200, got <%s>: %s",
                    repeat.statusCode,
                    repeat.body,
                ).isEqualTo(HttpStatus.OK)
            assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, toggledRev)

            // PUT false → the stored value flips back; GET mirrors it
            val disabled = putSettings(alice, incognito = false)
            assertThat(disabled.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(objectMapper.readTree(disabled.body)[INCOGNITO_FIELD].asBoolean()).isFalse
            assertThat(objectMapper.readTree(getSettingsRaw(alice).body)[INCOGNITO_FIELD].asBoolean()).isFalse

            // the flood gate: drain the per-user PUT bucket with idempotent repeats — 429 + Retry-After
            var rejection: ResponseEntity<String>? = null
            var attempt = 0
            while (rejection == null && attempt < SETTINGS_BURST_CAP) {
                attempt += 1
                val probe = putSettings(alice, incognito = true)
                if (probe.statusCode == HttpStatus.TOO_MANY_REQUESTS) rejection = probe
            }
            assertThat(rejection)
                .overridingErrorMessage(
                    "№38 must enforce the per-user PUT flood bucket (presence-api.md §3) — " +
                        "%d back-to-back idempotent puts were all admitted",
                    SETTINGS_BURST_CAP,
                ).isNotNull
            assertSettingsProblem(rejection!!, HttpStatus.TOO_MANY_REQUESTS, FLOOD_LIMIT)
            val retryAfterSeconds = retryAfterSecondsOf(rejection!!)

            // recovery after the advertised wait — the first allowed PUT answers 200 again
            Thread.sleep(retryAfterSeconds * MILLIS_PER_SECOND + RECOVERY_MARGIN_MILLIS)
            val recovery = putSettings(alice, incognito = true)
            assertThat(recovery.statusCode)
                .overridingErrorMessage(
                    "the first №38 PUT after the Retry-After window must recover with 200, got <%s>: %s",
                    recovery.statusCode,
                    recovery.body,
                ).isEqualTo(HttpStatus.OK)
            assertThat(objectMapper.readTree(getSettingsRaw(alice).body)[INCOGNITO_FIELD].asBoolean()).isTrue
        } finally {
            runCatching { bobStream.close() }
        }
    }

    /**
     * The CORE §D1 semantics: enabling with LIVE connections publishes to
     * the audience EXACTLY ONE `offline` — immediately (the offq bypass,
     * [immediateToggleBudget]) with a strictly greater rev — and FREEZES
     * everything else: the №36 snapshot answers the indistinguishable
     * `offline`, the loss of the last registration publishes nothing (the
     * offq entry of a frozen subject resolves to a no-op), a reconnect
     * inside the mode publishes nothing, while the «невидимка» HIMSELF
     * keeps reading №36 without restrictions (the online audience peer
     * answers `online` — his own mode never filters his observer leg).
     * The disable of the still-online subject publishes `online` — every
     * toggle costs ≤ ONE displayed switch.
     */
    @Test
    fun `enabling with live connections publishes one offline immediately and freezes the audience view`() {
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
            assertThat(onlineEvent[STATUS_FIELD].asText())
                .overridingErrorMessage("the control: the connected subject must publish online first")
                .isEqualTo(STATUS_ONLINE)
            val onlineRev = onlineEvent[REV_FIELD].asLong()
            assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, onlineRev)

            val pump = startHeartbeatPump(alice, pumpedConnections, pumpFailures)
            var freezeRev = 0L
            try {
                // включение: EXACTLY ONE offline, IMMEDIATE (no offq window), rev strictly greater
                assertThat(putSettings(alice, incognito = true).statusCode)
                    .overridingErrorMessage("staging: the №38 enable of a live subject must answer 200")
                    .isEqualTo(HttpStatus.OK)
                val frozenEvent = awaitPresenceEvent(bobStream, alice.id, immediateToggleBudget)
                assertThat(frozenEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage(
                        "the enable of an online subject must publish offline, got <%s>",
                        frozenEvent,
                    ).isEqualTo(STATUS_OFFLINE)
                freezeRev = frozenEvent[REV_FIELD].asLong()
                assertThat(freezeRev)
                    .overridingErrorMessage("the freeze transition must advance the rev strictly")
                    .isGreaterThan(onlineRev)

                // неотличимость: №36 answers the frozen target offline with the very freeze rev
                assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, freezeRev)
                // ≤ one switch per toggle: nothing further may follow
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)

                // FR-007: the «невидимка» reads №36 WITHOUT restrictions —
                // his own mode never filters his observer leg (bob IS online)
                val bobSeenByAlice = snapshotOf(alice, bob.id)
                assertThat(bobSeenByAlice[STATUS_FIELD].asText())
                    .overridingErrorMessage(
                        "the incognito subject must still observe the online audience peer, got <%s>",
                        bobSeenByAlice,
                    ).isEqualTo(STATUS_ONLINE)

                // заморозка: the loss of the last registration publishes NOTHING —
                // the pending offq entry of a frozen subject resolves to a silent no-op
                pumpedConnections.clear()
            } finally {
                pump.close()
            }
            aliceStream.close()
            assertNoPresenceEventFor(bobStream, alice.id, offlinePublicationBudget)
            assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, freezeRev)

            // a reconnect INSIDE the mode publishes NOTHING (the freeze skips the transition)
            aliceStream = openUserEvents(alice)
            val returnPumpConnections = CopyOnWriteArraySet<UUID>()
            returnPumpConnections += awaitConnectedFrame(aliceStream)
            val returnPump = startHeartbeatPump(alice, returnPumpConnections, pumpFailures)
            try {
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
                assertSnapshotStatus(bob, alice.id, STATUS_OFFLINE, freezeRev)

                // выключение онлайн-пользователя: the actual status publishes — ONE online switch
                assertThat(putSettings(alice, incognito = false).statusCode)
                    .overridingErrorMessage("staging: the №38 disable of a live subject must answer 200")
                    .isEqualTo(HttpStatus.OK)
                val revealEvent = awaitPresenceEvent(bobStream, alice.id, immediateToggleBudget)
                assertThat(revealEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage(
                        "the disable of an online subject must publish online, got <%s>",
                        revealEvent,
                    ).isEqualTo(STATUS_ONLINE)
                val revealRev = revealEvent[REV_FIELD].asLong()
                assertThat(revealRev)
                    .overridingErrorMessage("rev must strictly increase across the reveal transition")
                    .isGreaterThan(freezeRev)

                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, revealRev)
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
            } finally {
                returnPump.close()
            }

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
     * The edge «выключение офлайн-пользователя» (spec 109, research §D1):
     * a subject WITHOUT live connections toggles the mode both ways — NOT
     * A SINGLE event reaches the audience (the published value was and
     * stays `offline`), the STATUS part of the №36 answer is unchanged
     * (`offline` throughout — indistinguishable) and the rev only grows
     * (или остаётся — «не убывает»): the max(rev) client merge stays
     * correct while the display stays perfectly still.
     */
    @Test
    fun `disabling an offline subject stays silent with the status unchanged and rev non-decreasing`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        val bobStream = openUserEvents(bob)
        try {
            awaitConnectedFrame(bobStream)

            val baseline = snapshotOf(bob, alice.id)
            assertThat(baseline[STATUS_FIELD].asText())
                .overridingErrorMessage("a never-connected subject must read offline, got <%s>", baseline)
                .isEqualTo(STATUS_OFFLINE)
            val baselineRev = baseline[REV_FIELD].asLong()

            // включение офлайн-пользователя: already offline — one rev bump at most, ZERO events
            assertThat(putSettings(alice, incognito = true).statusCode)
                .overridingErrorMessage("staging: the №38 enable of an offline subject must answer 200")
                .isEqualTo(HttpStatus.OK)
            assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
            val frozen = snapshotOf(bob, alice.id)
            assertThat(frozen[STATUS_FIELD].asText())
                .overridingErrorMessage("the frozen offline subject must keep answering offline, got <%s>", frozen)
                .isEqualTo(STATUS_OFFLINE)
            assertThat(frozen[REV_FIELD].asLong())
                .overridingErrorMessage("the №36 rev must never decrease across a mode toggle")
                .isGreaterThanOrEqualTo(baselineRev)

            // выключение офлайн-пользователя: the actual status EQUALS the published one —
            // NO event, the status part stays untouched, the rev does not decrease
            assertThat(putSettings(alice, incognito = false).statusCode)
                .overridingErrorMessage("staging: the №38 disable of an offline subject must answer 200")
                .isEqualTo(HttpStatus.OK)
            assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
            val revealed = snapshotOf(bob, alice.id)
            assertThat(revealed[STATUS_FIELD].asText())
                .overridingErrorMessage(
                    "the disable of an offline subject must not move the №36 status part, got <%s>",
                    revealed,
                ).isEqualTo(STATUS_OFFLINE)
            assertThat(revealed[REV_FIELD].asLong())
                .overridingErrorMessage("the №36 rev must never decrease across the silent disable edge")
                .isGreaterThanOrEqualTo(frozen[REV_FIELD].asLong())
        } finally {
            runCatching { bobStream.close() }
        }
    }

    /**
     * US4 AC5 / data-model §1.5: the mode acts ON THE WHOLE USER and
     * PERSISTS between sessions (FR-007) — a fresh 002 login of the same
     * user still reads `incognito=true` (the V15 `users.presence_hidden`
     * column is the only durable home), and the fresh session's №18
     * connection stays FROZEN: no `online` ever reaches the audience
     * while the mode holds; its disable finally publishes the actual
     * `online` with a rev strictly greater than everything observed.
     */
    @Test
    fun `incognito mode survives a relogin and keeps the fresh session frozen`() {
        val (alice, bob) = messagingPair()
        ensureChatOk(alice, bob.id)

        // включение офлайн-пользователя (молчит) — затем ПЕРЕЛОГИН той же учётки
        assertThat(putSettings(alice, incognito = true).statusCode)
            .overridingErrorMessage("staging: the №38 enable before the relogin must answer 200")
            .isEqualTo(HttpStatus.OK)
        val relogged = loginAgain(alice)
        assertThat(relogged.id)
            .overridingErrorMessage("a relogin must be the SAME user with a fresh session")
            .isEqualTo(alice.id)
        assertThat(objectMapper.readTree(getSettingsRaw(relogged).body)[INCOGNITO_FIELD].asBoolean())
            .overridingErrorMessage(
                "the incognito mode must survive the relogin (PG V15 persistence, FR-007)",
            ).isTrue

        val bobStream = openUserEvents(bob)
        val aliceStream = openUserEvents(relogged)
        val pumpedConnections = CopyOnWriteArraySet<UUID>()
        val pumpFailures = CopyOnWriteArrayList<String>()
        try {
            awaitConnectedFrame(bobStream)
            pumpedConnections += awaitConnectedFrame(aliceStream)
            val pump = startHeartbeatPump(relogged, pumpedConnections, pumpFailures)
            try {
                // the fresh session's connection stays FROZEN — per-user, not per-device
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
                val frozenRev = snapshotOf(bob, alice.id)[REV_FIELD].asLong()
                assertThat(snapshotOf(bob, alice.id)[STATUS_FIELD].asText())
                    .overridingErrorMessage("№36 must answer the frozen target offline — indistinguishable")
                    .isEqualTo(STATUS_OFFLINE)

                // the disable finally publishes the ACTUAL status of the live registration
                assertThat(putSettings(relogged, incognito = false).statusCode)
                    .overridingErrorMessage("staging: the №38 disable after the relogin must answer 200")
                    .isEqualTo(HttpStatus.OK)
                val revealEvent = awaitPresenceEvent(bobStream, alice.id, immediateToggleBudget)
                assertThat(revealEvent[STATUS_FIELD].asText())
                    .overridingErrorMessage("the disable of the live relogged subject must publish online")
                    .isEqualTo(STATUS_ONLINE)
                val revealRev = revealEvent[REV_FIELD].asLong()
                assertThat(revealRev)
                    .overridingErrorMessage("rev must strictly increase across the post-relogin reveal")
                    .isGreaterThan(frozenRev)

                assertSnapshotStatus(bob, alice.id, STATUS_ONLINE, revealRev)
                assertNoPresenceEventFor(bobStream, alice.id, windowWithNoEvent)
            } finally {
                pump.close()
            }

            assertThat(pumpFailures)
                .overridingErrorMessage(
                    "the №37 renewals of the relogged session must stay 204: %s",
                    pumpFailures,
                ).isEmpty()
        } finally {
            runCatching { bobStream.close() }
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

    // --- the №38 settings fixtures (presence-api.md §3) ---

    /** Contract №38 `GET /users/me/presence/settings` — raw response. */
    private fun getSettingsRaw(user: MessagingUser): ResponseEntity<String> =
        restTemplate.exchange(
            SETTINGS_PATH,
            HttpMethod.GET,
            HttpEntity<Void>(null, bearerHeaders(user)),
            String::class.java,
        )

    /** Contract №38 `PUT` with a typed boolean body — raw response. */
    private fun putSettings(
        user: MessagingUser,
        incognito: Boolean,
    ): ResponseEntity<String> = putSettingsBody(user, "{\"$INCOGNITO_FIELD\":$incognito}")

    /** Contract №38 `PUT` with a VERBATIM JSON body — the malformed probes of the shape gate. */
    private fun putSettingsBody(
        user: MessagingUser,
        rawBody: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            SETTINGS_PATH,
            HttpMethod.PUT,
            HttpEntity(rawBody, bearerJsonHeaders(user)),
            String::class.java,
        )

    /** The №38 error contract: the status, RFC 9457 body and `errors.incognito=[code]`. */
    private fun assertSettingsProblem(
        response: ResponseEntity<String>,
        expectedStatus: HttpStatus,
        expectedCode: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№38 PUT must answer <%s>, got <%s>: %s",
                expectedStatus,
                response.statusCode,
                response.body,
            ).isEqualTo(expectedStatus)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("№38 errors must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(INCOGNITO_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                INCOGNITO_FIELD,
                expectedCode,
                codes,
                response.body,
            ).containsExactly(expectedCode)
    }

    /** The integral `Retry-After` (seconds, ≥ 1) of a №38 429 — the recovery wait of the flood leg. */
    private fun retryAfterSecondsOf(rejection: ResponseEntity<String>): Long {
        val header = rejection.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val seconds = header?.trim()?.toLongOrNull()
        assertThat(seconds)
            .overridingErrorMessage(
                "the №38 flood refusal must carry an integral Retry-After ≥ 1, got <%s>",
                header,
            ).isNotNull
        assertThat(seconds!!)
            .overridingErrorMessage("Retry-After must be within one refill window (≥1s, ≤60s), got <%s>", header)
            .isBetween(RETRY_AFTER_FLOOR_SECONDS, RETRY_AFTER_CEILING_SECONDS)
        return seconds
    }

    // --- the №36 batch-snapshot fixtures of T011 ---

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

    // --- the №37/relogin fixtures of T026 ---

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

    /**
     * A fresh login of an EXISTING fixture user (002 parallel-session edge,
     * T026 fixture): the same user id, a brand-new sid — the relogin leg of
     * the V15 persistence scenario. Uses the IT's own 203.0.116.0/24 login-IP
     * block so the shared Redis login buckets of the full run never collide
     * (messaging 004/.114, expiry IT .115, SSO 003/.113).
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
            .overridingErrorMessage("the relogin of <%s> must succeed", user.username)
            .isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return user.copy(
            accessToken = body["accessToken"].asText(),
            refreshToken = body["refreshToken"].asText(),
        )
    }

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    private fun bearerHeaders(user: MessagingUser) = HttpHeaders().apply { setBearerAuth(user.accessToken) }

    private fun bearerJsonHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    private fun uniqueLoginIp(): String {
        val lastOctet = 1L + loginIpCounter.incrementAndGet() % LOGIN_IP_LAST_OCTET_SPAN
        return LOGIN_IP_PREFIX + lastOctet
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
        const val INCOGNITO_FIELD = "incognito"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val FLOOD_LIMIT = "flood_limit"
        const val MALFORMED_REQUEST = "malformed_request"
        const val MALFORMED_INCOGNITO = "yes"
        const val EMPTY_BODY_OBJECT = "{}"
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val TIMEOUT_MARKER = "timed out"
        const val SNAPSHOT_PATH = "/api/v1/users/me/presence"
        const val HEARTBEAT_PATH = "/api/v1/users/me/presence/heartbeat"

        /** contracts/presence-api.md §3: the №38 settings route under test. */
        const val SETTINGS_PATH = "/api/v1/users/me/presence/settings"
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"

        /** Mirrors MessagingTestSupport's fixture secret — loginAgain needs the raw password. */
        const val FIXTURE_PASSWORD = "Msg-IT-004-Fixture-Pass!"

        /** Dedicated 203.0.116.0/24 block for this IT's own logins (004/.114, T026 .115, SSO .113). */
        const val LOGIN_IP_PREFIX = "203.0.116."
        const val LOGIN_IP_LAST_OCTET_SPAN = 254L
        val loginIpCounter = AtomicLong()

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L

        /** presence-api.md §3: the drain burst cap of the №38 PUT flood leg (the T016 precedent). */
        const val SETTINGS_BURST_CAP = 150

        const val MILLIS_PER_SECOND = 1000L
        const val RECOVERY_MARGIN_MILLIS = 750L
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val PUMP_THREAD_NAME = "presence-invisible-it-heartbeat"

        /** SC-001: from the subject's registration to the observer's `presence.updated`. */
        val ONLINE_PUBLICATION_BUDGET: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)
    }
}
