package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
import org.yaml.snakeyaml.Yaml
import webchat.backend.config.ChatsProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * T027 (tasks.md Phase 4, US2): the ephemeral typing indicator of 008a —
 * contract №41 `POST /chats/{chatId}/typing` and the new №18 frames —
 * over the real stack (Testcontainers PG+Redis through
 * [MessagingTestSupport], the tightened typing windows of T008 read by
 * this context via [TypingTestSupport]: state-ttl 3 s, poll-interval
 * 200 ms, the poller re-enabled as the ONE owner of the test JVM):
 *
 *  * SC-001 (server proxy leg): a member's `start` answers `204` and the
 *    peer's stream receives `typing.started {chatId, userId}` — payload
 *    conforming to the LIVE `components.schemas.TypingStartedEvent` of
 *    contracts/openapi.yaml — within the 2 s budget measured from the
 *    №41 POST (the p95 percentile and the client render are the manual
 *    T062 check, the RealtimeSseIT precedent);
 *  * the sender's own stream stays silent (self-exclusion — own devices
 *    never render their own typing, US2 AC9 solved by addressing);
 *  * a `stop` signal and a successful №16 message send of the typer both
 *    publish `typing.stopped` (TypingStoppedEvent) within the same 2 s
 *    budget, and a REPEATED `stop` without an active state publishes
 *    nothing (idempotence against the double extinguish of the client
 *    stop + the server-side extinguish at INSERT);
 *  * SC-003 self-expiry: an abandoned state (no stop, no message) is
 *    reaped by the poller — `typing.stopped` within state-ttl + a couple
 *    of poller ticks (+ CI slack) — and leaves ZERO trace in PG (no
 *    typing table exists at all, no message row appears);
 *  * ephemerality: a subscriber connecting AFTER the `start` receives no
 *    replay of the active state (AC5 — there is no read surface);
 *  * a DIRECT block pair (№23, both directions): signals are still
 *    accepted `204` (valid membership) but NOTHING is published either
 *    way — observers never see the block chatter (AC7/AC8);
 *  * the same blocked pair inside a shared group DOES trade
 *    `typing.started` both ways — block marks never filter group frames
 *    (FR-008, the 006 chat.read/message.created parity);
 *  * the FR-009 flood limit of №41 (60 signals/min per user): the burst
 *    past the allowance is refused `429 flood_limit` +
 *    `Retry-After` with `errors.action=[flood_limit]`, and the observer
 *    receives EXACTLY one frame per ACCEPTED signal — the refused ones
 *    publish nothing (no chatter at the observers);
 *  * FR-017: after the start signal the shared MeterRegistry carries a
 *    `webchat_realtime_push_seconds` observation tagged
 *    `event=typing.started`, `stage=dispatch` (the SSE dispatch leg);
 *  * the №16 access matrix keeps holding on №41: a stranger gets
 *    `403 not_participant`, an unknown chat id `404 chat_not_found`;
 *    `action` outside `start|stop` answers `400 invalid_action` and a
 *    non-UUID path `400 invalid_uuid`.
 *
 * NOTE (TDD, constitution VI): written BEFORE T028–T033 — until the
 * TypingStore, the fanout leg, the TypingService, the №41 controller and
 * the TypingTransitionScheduler land, every scenario here fails (RED) by
 * design at the awaited `204`/frame; only the T008 windows guard passes
 * (the config landed with Phase 2). Budgets derive from
 * [ChatsProperties.typing] exactly like the presence ITs do: windows +
 * poller ticks + CI slack.
 */
@Suppress("LargeClass", "TooManyFunctions") // T027: one scenario per US2 acceptance + the №41/№18/PG fixtures
class TypingIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val chatsProperties: ChatsProperties,
    @Autowired private val meterRegistry: MeterRegistry,
) : TypingTestSupport() {
    /** SC-003: state-ttl + a couple of poller ticks + CI slack (production ≈ 8 s + 1 s + slack). */
    private val sc003Budget: Duration =
        chatsProperties.typing.stateTtl
            .plus(chatsProperties.typing.pollInterval.multipliedBy(POLLER_SLACK_TICKS + 1))
            .plusSeconds(WINDOW_SLACK_SECONDS)

    /** T008 guard: the budgets above derive from the tightened IT windows — a profile drift must fail loudly. */
    @Test
    fun `it profile tightens the typing windows the scenarios rely on`() {
        assertThat(chatsProperties.typing.stateTtl)
            .overridingErrorMessage(
                "the IT profile must tighten chats.typing.state-ttl to 3 s (T008), got <%s>",
                chatsProperties.typing.stateTtl,
            ).isEqualTo(Duration.ofSeconds(PROFILE_TTL_SECONDS))
        assertThat(chatsProperties.typing.pollInterval)
            .overridingErrorMessage(
                "the IT profile must tighten chats.typing.poll-interval to 200 ms (T008), got <%s>",
                chatsProperties.typing.pollInterval,
            ).isEqualTo(Duration.ofMillis(PROFILE_POLLER_MILLIS))
        assertThat(chatsProperties.typing.pollerEnabled)
            .overridingErrorMessage(
                "TypingTestSupport must re-enable the typing poller — this context is the ONE poller owner of the run",
            ).isTrue
        assertThat(chatsProperties.rateLimit.typingSignalsPerMinute)
            .overridingErrorMessage(
                "the typing flood capacity must keep the production 60/min " +
                    "— the flood leg drains the real bucket (T008)",
                chatsProperties.rateLimit.typingSignalsPerMinute,
            ).isEqualTo(PROFILE_TYPING_SIGNALS_PER_MINUTE)
    }

    /**
     * SC-001 (server proxy leg) + self-exclusion + FR-017: the member's
     * `start` answers `204`, the PEER's stream carries
     * `typing.started {chatId, userId}` (the typer, not the observer)
     * within the 2 s budget measured from the very POST — payload
     * validated against the live `TypingStartedEvent` schema — while the
     * SENDER's own stream stays silent, and the push pipeline observes
     * the dispatch leg under `webchat_realtime_push_seconds`.
     */
    @Test
    fun `start reaches the peer within two seconds and never the sender`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        openUserEvents(bob).use { bobStream ->
            openUserEvents(alice).use { aliceStream ->
                val sentAt = System.nanoTime()
                val accepted = sendTypingSignal(alice, chatId, ACTION_START)
                assertThat(accepted.statusCode)
                    .overridingErrorMessage(
                        "№41 start of a chat member must answer 204, got <%s>: %s",
                        accepted.statusCode,
                        accepted.body,
                    ).isEqualTo(HttpStatus.NO_CONTENT)

                val started = bobStream.awaitEvent(TYPING_STARTED_EVENT, SC001_BUDGET)
                val deliveryTook = Duration.ofNanos(System.nanoTime() - sentAt)
                assertThat(deliveryTook)
                    .overridingErrorMessage(
                        "SC-001: typing.started must reach the peer within 2 s of the №41 POST, took <%s>",
                        deliveryTook,
                    ).isLessThanOrEqualTo(SC001_BUDGET)

                assertTypingPayload(started, TYPING_STARTED_EVENT_SCHEMA, chatId, alice.id)

                assertNoFrameOfFollows(aliceStream, NO_FRAME_WINDOW, TYPING_STARTED_EVENT, TYPING_STOPPED_EVENT)
                assertDispatchLegObserved()
            }
        }
    }

    /**
     * The `stop` signal publishes `typing.stopped` once (TypingStopped
     * Event, the typer named) inside the same 2 s budget; a REPEATED
     * `stop` without an active state still answers `204` but publishes
     * nothing — idempotence against the double extinguish (the client
     * stop of T036 racing the server extinguish of T033).
     */
    @Test
    fun `stop publishes typing stopped once and a repeat stop stays silent`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        openUserEvents(bob).use { stream ->
            assertThat(sendTypingSignal(alice, chatId, ACTION_START).statusCode)
                .isEqualTo(HttpStatus.NO_CONTENT)
            stream.awaitEvent(TYPING_STARTED_EVENT, FRAME_BUDGET)

            assertThat(sendTypingSignal(alice, chatId, ACTION_STOP).statusCode)
                .isEqualTo(HttpStatus.NO_CONTENT)
            val stopped = stream.awaitEvent(TYPING_STOPPED_EVENT, SC001_BUDGET)
            assertTypingPayload(stopped, TYPING_STOPPED_EVENT_SCHEMA, chatId, alice.id)

            val repeat = sendTypingSignal(alice, chatId, ACTION_STOP)
            assertThat(repeat.statusCode)
                .overridingErrorMessage(
                    "a repeated stop must stay ACCEPTED (204, idempotent) — it just publishes nothing, got <%s>: %s",
                    repeat.statusCode,
                    repeat.body,
                ).isEqualTo(HttpStatus.NO_CONTENT)
            assertNoFrameOfFollows(stream, NO_FRAME_WINDOW, TYPING_STOPPED_EVENT)
        }
    }

    /**
     * The server extinguishes the state at the successful №16 INSERT of
     * the typer (data-model.md §2.1 transition 2): the peer's stream
     * gets `typing.stopped` inside the 2 s budget — the interleaved
     * `message.created` frame of the very send is legally skipped by the
     * event wait.
     */
    @Test
    fun `sending a message extinguishes the typing state with typing stopped`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        openUserEvents(bob).use { stream ->
            assertThat(sendTypingSignal(alice, chatId, ACTION_START).statusCode)
                .isEqualTo(HttpStatus.NO_CONTENT)
            stream.awaitEvent(TYPING_STARTED_EVENT, FRAME_BUDGET)

            sendMessageOk(alice, chatId, EXTINGUISHING_MESSAGE)

            val stopped = stream.awaitEvent(TYPING_STOPPED_EVENT, SC001_BUDGET)
            assertTypingPayload(stopped, TYPING_STOPPED_EVENT_SCHEMA, chatId, alice.id)
        }
    }

    /**
     * SC-003 «обрыв»: the typer falls silent WITHOUT any stop (a hung
     * tab / a killed pod) — the poller reaps the lapsed state and the
     * peer's `typing.stopped` lands within state-ttl + a couple of poller
     * ticks (+ CI slack) measured from the very `start` POST; the
     * ephemeral state leaves ZERO trace in PG — no typing table exists
     * and no message row appeared for the signals.
     */
    @Test
    fun `an abandoned state self-expires within the sc003 budget leaving no pg trace`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        openUserEvents(bob).use { stream ->
            val startedAt = System.nanoTime()
            assertThat(sendTypingSignal(alice, chatId, ACTION_START).statusCode)
                .isEqualTo(HttpStatus.NO_CONTENT)
            stream.awaitEvent(TYPING_STARTED_EVENT, FRAME_BUDGET)

            val stopped = stream.awaitEvent(TYPING_STOPPED_EVENT, sc003Budget)
            val expiryTook = Duration.ofNanos(System.nanoTime() - startedAt)
            assertThat(expiryTook)
                .overridingErrorMessage(
                    "SC-003: the abandoned state must publish typing.stopped within ttl + poller ticks " +
                        "(+slack), took <%s>",
                    expiryTook,
                ).isLessThanOrEqualTo(sc003Budget)
            assertTypingPayload(stopped, TYPING_STOPPED_EVENT_SCHEMA, chatId, alice.id)

            assertThat(typingTablesInPostgres())
                .overridingErrorMessage(
                    "SC-003: the typing state is Redis-ephemeral — no typing table may exist in PG, got <%s>",
                    typingTablesInPostgres(),
                ).isEmpty()
            assertThat(countMessageRows(chatId))
                .overridingErrorMessage("SC-003: typing signals must never write message rows")
                .isZero()
        }
    }

    /**
     * Ephemerality (AC5): a subscriber connecting AFTER the `start`
     * receives NO replay of the active state — there is no read surface,
     * the frame exists only for the live connections of the moment.
     */
    @Test
    fun `a subscriber connecting after the start never receives a replay`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        assertThat(sendTypingSignal(alice, chatId, ACTION_START).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        openUserEvents(bob).use { lateStream ->
            assertNoFrameOfFollows(lateStream, NO_REPLAY_WINDOW, TYPING_STARTED_EVENT)
        }
    }

    /**
     * AC7 (DIRECT block pair, 004 FR-020 parity): with the block active
     * (verifiably — the №16 send of the blocked side is `403
     * you_are_blocked`) BOTH sides' №41 signals are still accepted `204`
     * (membership is valid), yet NOTHING is published in either
     * direction — the observers never see the block chatter (AC8).
     */
    @Test
    fun `a blocked direct pair trades 204 signals but no frames either way`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        assertThat(blockUser(alice, bob.id).statusCode)
            .overridingErrorMessage("fixture №23 block of the direct pair must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)
        val refusal = sendMessage(bob, chatId, BLOCKED_DIRECT_TEXT)
        assertProblemCode(refusal, HttpStatus.FORBIDDEN, CHAT_FIELD, YOU_ARE_BLOCKED)

        openUserEvents(alice).use { aliceStream ->
            openUserEvents(bob).use { bobStream ->
                assertThat(sendTypingSignal(alice, chatId, ACTION_START).statusCode)
                    .isEqualTo(HttpStatus.NO_CONTENT)
                assertThat(sendTypingSignal(bob, chatId, ACTION_START).statusCode)
                    .isEqualTo(HttpStatus.NO_CONTENT)

                assertNoFrameOfFollows(aliceStream, NO_FRAME_WINDOW, TYPING_STARTED_EVENT, TYPING_STOPPED_EVENT)
                assertNoFrameOfFollows(bobStream, NO_FRAME_WINDOW, TYPING_STARTED_EVENT, TYPING_STOPPED_EVENT)
            }
        }
    }

    /**
     * FR-008 (the 006 chat.read/message.created parity): the same blocked
     * pair inside a SHARED GROUP still trades `typing.started` both ways
     * — block marks filter the direct pair only, never group frames.
     */
    @Test
    fun `a blocked pair inside a shared group still trades typing started both ways`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        addContact(owner, bob.id)
        val groupChatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id.toString())))
        val directChatId = ensureChatOk(owner, bob.id)

        assertThat(blockUser(owner, bob.id).statusCode)
            .overridingErrorMessage("fixture №23 block of the direct pair must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)
        val refusal = sendMessage(bob, directChatId, BLOCKED_DIRECT_TEXT)
        assertProblemCode(refusal, HttpStatus.FORBIDDEN, CHAT_FIELD, YOU_ARE_BLOCKED)

        openUserEvents(owner).use { ownerStream ->
            openUserEvents(bob).use { bobStream ->
                assertThat(sendTypingSignal(bob, groupChatId, ACTION_START).statusCode)
                    .isEqualTo(HttpStatus.NO_CONTENT)
                val fromBlocked = ownerStream.awaitEvent(TYPING_STARTED_EVENT, SC001_BUDGET)
                assertTypingPayload(fromBlocked, TYPING_STARTED_EVENT_SCHEMA, groupChatId, bob.id)

                assertThat(sendTypingSignal(owner, groupChatId, ACTION_START).statusCode)
                    .isEqualTo(HttpStatus.NO_CONTENT)
                val fromBlocker = bobStream.awaitEvent(TYPING_STARTED_EVENT, SC001_BUDGET)
                assertTypingPayload(fromBlocker, TYPING_STARTED_EVENT_SCHEMA, groupChatId, owner.id)
            }
        }
    }

    /**
     * FR-009 (№41 flood, 60 signals/min per user across ALL his chats):
     * the burst past the allowance is refused `429 flood_limit` +
     * `Retry-After` (integral seconds ≥ 1) with
     * `errors.action=[flood_limit]`, a fresh signal in the same drained
     * instant is still refused — and the observer receives EXACTLY one
     * `typing.started` per ACCEPTED signal: the refused ones publish
     * nothing, no chatter at the observers (AC8). The greedy 60/60s drip
     * may re-grant a token while the burst runs, so the first rejection
     * lands at signal 61+ within `60 + elapsedSeconds + 1`, never at a
     * fixed attempt index (the FloodLimitIT precedent).
     */
    @Test
    fun `signals past the sixty per minute bucket are refused with 429 and publish nothing new`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        openUserEvents(bob).use { stream ->
            val burst = sendUntilFlood(alice, chatId)
            assertFloodRejection(burst.rejection)

            val stillRefused = sendTypingSignal(alice, chatId, ACTION_START)
            assertThat(stillRefused.statusCode)
                .overridingErrorMessage(
                    "a fresh signal in the same drained instant must still be refused 429, got <%s>: %s",
                    stillRefused.statusCode,
                    stillRefused.body,
                ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)

            val deliveredStarted = countFramesOf(stream, NO_FRAME_WINDOW, TYPING_STARTED_EVENT)
            assertThat(deliveredStarted)
                .overridingErrorMessage(
                    "the observer must receive EXACTLY one typing.started per ACCEPTED signal " +
                        "(accepted=<%d>, refused ones publish nothing — no chatter), got <%d>",
                    burst.accepted,
                    deliveredStarted,
                ).isEqualTo(burst.accepted)
        }
    }

    /**
     * The №16 access matrix holds on №41: a registered stranger gets
     * `403 not_participant`, an unknown chat id `404 chat_not_found`;
     * `action` outside `start|stop` answers `400 invalid_action` and a
     * non-UUID chatId in the path `400 invalid_uuid` — every refusal is
     * an RFC 9457 problem carrying the contract code.
     */
    @Test
    fun `strangers unknown chats and invalid bodies get the contract refusals`() {
        val (alice, bob) = messagingPair()
        val carol = strangerUser()
        val chatId = ensureChatOk(alice, bob.id)

        assertProblemCode(
            sendTypingSignal(carol, chatId, ACTION_START),
            HttpStatus.FORBIDDEN,
            CHAT_FIELD,
            NOT_PARTICIPANT,
        )
        assertProblemCode(
            sendTypingSignal(alice, UUID.randomUUID(), ACTION_START),
            HttpStatus.NOT_FOUND,
            CHAT_FIELD,
            CHAT_NOT_FOUND,
        )
        assertProblemCode(
            sendTypingSignal(alice, chatId, ACTION_PAUSE),
            HttpStatus.BAD_REQUEST,
            ACTION_FIELD,
            INVALID_ACTION,
        )
        assertProblemCode(
            sendTypingSignalRawChatId(alice, NOT_A_UUID, ACTION_START),
            HttpStatus.BAD_REQUEST,
            CHAT_ID_FIELD,
            INVALID_UUID,
        )
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Contract №41 `POST /api/v1/chats/{chatId}/typing` — raw response. */
    private fun sendTypingSignal(
        user: MessagingUser,
        chatId: UUID,
        action: String,
    ): ResponseEntity<String> = sendTypingSignalRawChatId(user, chatId.toString(), action)

    /** №41 with an unvalidated chat id string — the `invalid_uuid` path leg. */
    private fun sendTypingSignalRawChatId(
        user: MessagingUser,
        chatId: String,
        action: String,
    ): ResponseEntity<String> =
        postJsonAuthorized(
            TYPING_PATH_FORMAT.format(chatId),
            mapOf(ACTION_FIELD to action),
            user,
        )

    /**
     * FR-017 (realtime-events.md §4): after a delivered start the shared
     * registry carries the SSE dispatch observation of the push timer —
     * the leg SC-008 measures; the registry view (not the /prometheus
     * scrape) is enough here, PresenceExpiryIT precedent.
     */
    private fun assertDispatchLegObserved() {
        await().atMost(METER_WAIT).untilAsserted {
            assertThat(
                meterRegistry
                    .find(PUSH_SECONDS)
                    .tag(TAG_EVENT, TYPING_STARTED_EVENT)
                    .tag(TAG_STAGE, STAGE_DISPATCH)
                    .timer(),
            ).overridingErrorMessage(
                "FR-017: <%s{event=\"%s\", stage=\"%s\"}> must be observed after a delivered start signal",
                PUSH_SECONDS,
                TYPING_STARTED_EVENT,
                STAGE_DISPATCH,
            ).isNotNull
        }
    }

    /**
     * The typing payload contract: validates against the LIVE schema,
     * carries EXACTLY `chatId`+`userId`, names the right dialog and the
     * TYPER (the sender of the signal, never the observer).
     */
    private fun assertTypingPayload(
        payload: JsonNode,
        schemaName: String,
        expectedChatId: UUID,
        expectedTyperId: UUID,
    ) {
        assertConformsToSchema(payload, schemaName)
        assertThat(fieldNames(payload))
            .overridingErrorMessage("the <%s> payload must have exactly the contract fields", schemaName)
            .containsExactlyInAnyOrder(CHAT_ID_FIELD, USER_ID_FIELD)
        assertThat(payload[CHAT_ID_FIELD].asText())
            .overridingErrorMessage("the typing payload must name the dialog the signal belongs to")
            .isEqualTo(expectedChatId.toString())
        assertThat(payload[USER_ID_FIELD].asText())
            .overridingErrorMessage("the typing payload must name the TYPER, not the observer")
            .isEqualTo(expectedTyperId.toString())
    }

    /** A drain-to-429 burst of №41 starts: every accepted signal plus the first refusal. */
    private data class TypingBurst(
        val accepted: Int,
        val rejection: ResponseEntity<String>,
    )

    /** Sends №41 starts back to back until the first non-204 answer (the FloodLimitIT idiom, 60/min). */
    private fun sendUntilFlood(
        user: MessagingUser,
        chatId: UUID,
    ): TypingBurst {
        var accepted = 0
        var rejection: ResponseEntity<String>? = null
        val startedAt = System.nanoTime()
        var attempt = 0
        while (rejection == null && attempt < BURST_ATTEMPT_CAP) {
            attempt += 1
            val response = sendTypingSignal(user, chatId, ACTION_START)
            if (response.statusCode == HttpStatus.NO_CONTENT) {
                accepted += 1
            } else {
                rejection = response
            }
        }
        val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        assertThat(rejection)
            .overridingErrorMessage(
                "the 60-signals-per-minute bucket (FR-009) must refuse the burst of %d №41 starts, " +
                    "but every attempt answered 204 — the flood limit is not enforced on №41",
                BURST_ATTEMPT_CAP,
            ).isNotNull
        val dripBound = WINDOW_SIGNALS.toLong() + elapsedSeconds + DRIP_SLACK
        assertThat(accepted.toLong())
            .overridingErrorMessage(
                "the first refusal must land at signal 61+ of the window and within the greedy-drip bound " +
                    "60+elapsed+1 (accepted=<%d>, elapsed=<%ds>, bound=<%d>)",
                accepted,
                elapsedSeconds,
                dripBound,
            ).isBetween(WINDOW_SIGNALS.toLong(), dripBound)
        return TypingBurst(accepted, rejection!!)
    }

    /**
     * The exact №41 429 of api-contract.md: `429` + problem+json with
     * `errors.action=[flood_limit]` and an integral `Retry-After` of at
     * least 1 second.
     */
    private fun assertFloodRejection(rejection: ResponseEntity<String>) {
        assertThat(rejection.statusCode)
            .overridingErrorMessage(
                "the typing flood refusal must be 429 (FR-009), got <%s>: %s",
                rejection.statusCode,
                rejection.body,
            ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejection.headers.contentType?.toString())
            .overridingErrorMessage("the typing flood refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val retryAfterHeader = rejection.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()
        assertThat(retryAfterSeconds)
            .overridingErrorMessage(
                "Retry-After must be integral seconds ≥ 1 (openapi №41), got <%s>",
                retryAfterHeader,
            ).isNotNull
        assertThat(retryAfterSeconds!!)
            .overridingErrorMessage(
                "Retry-After must be within one refill window (≥1s, ≤60s), got <%s>",
                retryAfterHeader,
            ).isBetween(RETRY_AFTER_FLOOR_SECONDS, RETRY_AFTER_CEILING_SECONDS)
        val problem = objectMapper.readTree(rejection.body)
        val codes = problem["errors"]?.get(ACTION_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                ACTION_FIELD,
                FLOOD_LIMIT,
                codes,
                rejection.body,
            ).containsExactly(FLOOD_LIMIT)
    }

    /** The RFC 9457 refusal shape shared by every №41 error leg: status + `errors.{field}=[code]`. */
    private fun assertProblemCode(
        response: ResponseEntity<String>,
        expectedStatus: HttpStatus,
        field: String,
        code: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№41 must answer <%s>, got <%s>: %s",
                expectedStatus,
                response.statusCode,
                response.body,
            ).isEqualTo(expectedStatus)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("№41 errors must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(field)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                field,
                code,
                codes,
                response.body,
            ).containsExactly(code)
    }

    /**
     * Consumes frames for [window] and fails the moment an `event:`
     * frame of any [eventTypes] shows up — every lawful push lands well
     * inside the ≤2 s SC-001 budget, so nothing may follow after it. A
     * per-frame timeout (no COMPLETE frame within the window) is the
     * expected clean exit (the RealtimeSseIT idiom).
     */
    private fun assertNoFrameOfFollows(
        stream: UserEventsStream,
        window: Duration,
        vararg eventTypes: String,
    ) {
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError(
                            "stream ended while asserting that no ${eventTypes.toList()} frame follows",
                        )
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            assertThat(frame.event)
                .overridingErrorMessage(
                    "no further <%s> frame may follow within %s",
                    eventTypes.toList(),
                    window,
                ).isNotIn(eventTypes.toList())
        }
    }

    /** Counts the `event:` frames of [eventType] delivered within [window] (the flood «no chatter» leg). */
    private fun countFramesOf(
        stream: UserEventsStream,
        window: Duration,
        eventType: String,
    ): Int {
        var seen = 0
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return seen
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError("stream ended while counting <$eventType> frames")
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return seen
                }
            if (frame.event == eventType) seen += 1
        }
    }

    /** SC-003: the typing state is Redis-ephemeral — no `typing%` table may exist in the public schema. */
    private fun typingTablesInPostgres(): List<String> =
        jdbcTemplate.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name LIKE 'typing%'
            """.trimIndent(),
            String::class.java,
        )

    /** The raw row count behind the dialog — typing signals must never write message rows. */
    private fun countMessageRows(chatId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages WHERE chat_id = ?", Int::class.java, chatId) ?: 0

    /** Contract №23 `PUT /api/v1/users/{userId}/block` — fixture block of a direct pair. */
    private fun blockUser(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchangeAuthorized(user, HttpMethod.PUT, "$USERS_PATH/$userId/block")

    /** Contract №21 `POST /api/v1/contacts` — fixture contact for the №27 roster gate. */
    private fun addContact(
        user: MessagingUser,
        userId: UUID,
    ) {
        val response = postJsonAuthorized(CONTACTS_PATH, mapOf(USER_ID_FIELD to userId.toString()), user)
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "fixture contact №21 of <%s> → <%s> must succeed, got <%s>: %s",
                user.username,
                userId,
                response.statusCode,
                response.body,
            ).isTrue
    }

    /** Contract №27 `POST /api/v1/groups` — raw response for status/error assertions. */
    private fun createGroup(
        owner: MessagingUser,
        title: String,
        memberUserIds: List<String>,
    ): ResponseEntity<String> =
        postJsonAuthorized(
            GROUPS_PATH,
            buildMap {
                put(TITLE_FIELD, title)
                if (memberUserIds.isNotEmpty()) put(MEMBER_USER_IDS_FIELD, memberUserIds)
            },
            owner,
        )

    /** №27 happy path → the stored `GroupView` body. */
    private fun createGroupOk(
        owner: MessagingUser,
        memberUserIds: List<String>,
    ): JsonNode {
        val response = createGroup(owner, GROUP_TITLE, memberUserIds)
        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view[CHAT_ID_FIELD].asText())

    private fun postJsonAuthorized(
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    private fun exchangeAuthorized(
        user: MessagingUser,
        method: HttpMethod,
        path: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /**
     * T027 (constitution IV): asserts a live №18 payload against the very
     * schema of the public contract — `contracts/openapi.yaml`
     * `components.schemas.<schemaName>` — instead of a hand-copied field
     * list, so a schema change that breaks the wire payload fails here
     * first. The validator covers exactly the JSON Schema subset the
     * event schemas use (the RealtimeSseIT T064 subset): `type`,
     * `required`, `properties`, `additionalProperties: false`, `$ref`,
     * `format` (`uuid`/`date-time`), `maxLength`, `minimum`.
     */
    private fun assertConformsToSchema(
        payload: JsonNode,
        schemaName: String,
    ) {
        val schema = openApiContract["components"]["schemas"][schemaName]
        if (schema !is ObjectNode) {
            throw AssertionError(
                "contracts/openapi.yaml must keep components.schemas.$schemaName — " +
                    "the №18 typing payloads are declared there (realtime-events.md §1), got: ${schema.nodeType}",
            )
        }
        val violations = mutableListOf<String>()
        validateAgainst(payload, schema, "#", violations)
        assertThat(violations)
            .overridingErrorMessage(
                "SSE payload violates components.schemas.%s of contracts/openapi.yaml: %s",
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
                if (runCatching { java.time.OffsetDateTime.parse(text) }.isFailure) {
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

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val TIMEOUT_MARKER = "timed out"

        /** realtime-events.md §1: the two new №18 frames of 008a. */
        const val TYPING_STARTED_EVENT = "typing.started"
        const val TYPING_STOPPED_EVENT = "typing.stopped"
        const val TYPING_STARTED_EVENT_SCHEMA = "TypingStartedEvent"
        const val TYPING_STOPPED_EVENT_SCHEMA = "TypingStoppedEvent"

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        /** A deliberately invalid `action` value for the 400 leg. */
        const val ACTION_PAUSE = "pause"

        const val CHAT_ID_FIELD = "chatId"
        const val USER_ID_FIELD = "userId"
        const val ACTION_FIELD = "action"
        const val CHAT_FIELD = "chat"

        /** №27 create-group body fields (the GroupRealtimeIT fixture naming). */
        const val TITLE_FIELD = "title"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"

        /** The local `$ref` prefix of the contract validator (the T064 subset). */
        const val LOCAL_REF_PREFIX = "#/"

        const val NOT_PARTICIPANT = "not_participant"
        const val CHAT_NOT_FOUND = "chat_not_found"
        const val YOU_ARE_BLOCKED = "you_are_blocked"
        const val INVALID_ACTION = "invalid_action"
        const val INVALID_UUID = "invalid_uuid"
        const val FLOOD_LIMIT = "flood_limit"

        /** FR-017: the push-timer observation of the SSE dispatch leg (RedisRealtimePublisher naming). */
        const val PUSH_SECONDS = "webchat_realtime_push_seconds"
        const val TAG_EVENT = "event"
        const val TAG_STAGE = "stage"
        const val STAGE_DISPATCH = "dispatch"

        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val USERS_PATH = "/api/v1/users"
        const val TYPING_PATH_FORMAT = "/api/v1/chats/%s/typing"

        const val GROUP_TITLE = "US2 typing IT group"
        const val BLOCKED_DIRECT_TEXT = "dead in the direct dialog"
        const val EXTINGUISHING_MESSAGE = "отправлено — набор погашен ✓"
        const val NOT_A_UUID = "definitely-not-a-uuid"

        /** T008: the tightened IT windows this IT's budgets derive from (application-test.yml). */
        const val PROFILE_TTL_SECONDS = 3L
        const val PROFILE_POLLER_MILLIS = 200L

        /** T008: the flood capacity keeps the production value — the flood leg drains the real bucket. */
        const val PROFILE_TYPING_SIGNALS_PER_MINUTE = 60

        const val POLLER_SLACK_TICKS = 2L
        const val WINDOW_SLACK_SECONDS = 1L

        /** FR-009/api-contract.md №41: 60 signals per minute per user. */
        const val WINDOW_SIGNALS = 60

        /** Burst headroom for the greedy-drip tokens re-granted mid-run (1 token/s of the 60 s window). */
        const val BURST_ATTEMPT_CAP = 70
        const val DRIP_SLACK = 1L

        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** SC-001: from the №41 POST to the observer's frame (the PresenceIT ONLINE_PUBLICATION_BUDGET precedent). */
        val SC001_BUDGET: Duration = Duration.ofSeconds(2)

        /**
         * Any lawful push lands well inside the ≤2 s SC-001 budget — the
         * «nothing may follow» windows (the 004/006 NO_FRAME_WINDOW
         * convention).
         */
        val NO_FRAME_WINDOW: Duration = Duration.ofSeconds(3)

        /**
         * The no-replay window stays below the earliest LAWFUL expiry
         * publication (~ttl + a poller tick after the start) so only a
         * genuine replay of `typing.started` can fail it.
         */
        val NO_REPLAY_WINDOW: Duration = Duration.ofSeconds(2)

        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)
        val METER_WAIT: Duration = Duration.ofSeconds(5)

        /** `backend/` (Gradle) and the repo root (IDE runs) in order. */
        val CONTRACT_LOCATIONS: List<Path> =
            listOf(Path.of("../contracts/openapi.yaml"), Path.of("contracts/openapi.yaml"))

        val contractJson: ObjectMapper = ObjectMapper()

        /** contracts/openapi.yaml parsed once per JVM (the T064 loader). */
        val openApiContract: JsonNode by lazy { loadOpenApiContract() }

        private fun loadOpenApiContract(): JsonNode {
            val contract =
                CONTRACT_LOCATIONS.firstOrNull { Files.isRegularFile(it) }
                    ?: throw IllegalStateException(
                        "contracts/openapi.yaml not found at ${CONTRACT_LOCATIONS.joinToString()} — " +
                            "the T027 conformance check must read the live public contract",
                    )
            return contract.toFile().inputStream().use { input ->
                val yamlRoot: Any? = Yaml().load(input)
                contractJson.valueToTree<JsonNode>(yamlRoot)
            }
        }
    }
}
