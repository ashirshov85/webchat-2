package webchat.backend

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.chats.MessagingTestSupport
import java.time.Duration
import java.util.UUID

/**
 * T067 (tasks.md Phase 9, SC-008): the observability verification of the
 * 004 messaging feature — the six contract meters GROW in the quickstart
 * §3.2/§3.7/§3.8 scenarios and are exposed on `/actuator/prometheus`,
 * and the refusal warn logs carry ids/codes ONLY, never the message
 * text or the search query (constitution V: both are PII).
 *
 *  * §3.2 exactly-once: a fresh send + a same-`clientMessageId` retry +
 *    the recipient's read advance grow `webchat_message_ack_seconds`
 *    (both outcome tags), `webchat_message_dedup_total`,
 *    `webchat_realtime_push_seconds` (both stage tags — the send runs
 *    with both SSE streams open, so the dispatch leg fires too) and
 *    `webchat_read_advanced_total`;
 *  * §3.7 blocking: refusals of a blocked pair in both directions grow
 *    `webchat_send_rejected_total{reason=blocked_by_you|you_are_blocked}`;
 *  * §3.8 flood: the drained send and search buckets grow
 *    `webchat_send_rejected_total{reason=flood}` and
 *    `webchat_search_rejected_total{reason=flood}`.
 *
 * The meters are read the way an operator reads them — by scraping the
 * real `/actuator/prometheus` text exposition before/after each
 * scenario (never through the MeterRegistry), so the verification
 * covers the whole export path: registration, naming, tags, the
 * `application` common tag and the endpoint exposure itself. Deltas
 * (not absolute values) make the IT immune to the shared Spring test
 * context carrying counters from earlier IT classes.
 *
 * Every scenario drives the REAL 001–004 flows over Testcontainers
 * PG+Redis (registration, login, №11/№16/№17, №23 block, №19 search),
 * and the PII legs assert on the captured process output — the JSON
 * logback stream — filtering to WARN records only, exactly the level
 * the SC-008 requirement bounds.
 */
@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureObservability
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("TooManyFunctions") // T067: one helper per scrape/tag/PII concern, mirroring the T002 fixture style
class ObservabilityIT(
    @Autowired private val apiRestTemplate: TestRestTemplate,
    @Autowired private val observabilityObjectMapper: ObjectMapper,
) : MessagingTestSupport() {
    /**
     * Quickstart §3.2 (steps 1–3): the exactly-once dialog exchange — a
     * fresh send, its same-id retry, realtime delivery to the ONLINE
     * peer (both streams open) and the peer's read advance. All five
     * delivery-side SC-008 meters must answer with at least the exact
     * scenario minimum, proving they hook the code paths the success
     * criteria are judged by.
     */
    @Test
    @Order(1)
    fun `quickstart 3_2 exactly-once exchange grows ack dedup push and read meters`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val ackCreatedBefore = current(ACK_COUNT, TAG_OUTCOME_CREATED)
        val ackExistingBefore = current(ACK_COUNT, TAG_OUTCOME_EXISTING)
        val dedupBefore = current(DEDUP_TOTAL)
        val pushPublishBefore = current(PUSH_COUNT, TAG_STAGE_PUBLISH)
        val pushDispatchBefore = current(PUSH_COUNT, TAG_STAGE_DISPATCH)
        val readAdvancedBefore = current(READ_ADVANCED)

        openUserEvents(alice).use { aliceEvents ->
            openUserEvents(bob).use { bobEvents ->
                val clientMessageId = UUID.randomUUID()
                val created = sendMessage(alice, chatId, FRESH_SEND_TEXT, clientMessageId)
                assertThat(created.statusCode)
                    .overridingErrorMessage(
                        "the fresh §3.2 send must be created 201, got <%s>: %s",
                        created.statusCode,
                        created.body,
                    ).isEqualTo(HttpStatus.CREATED)
                val seq = observabilityObjectMapper.readTree(created.body)["seq"].asLong()

                bobEvents.awaitEvent(EVENT_MESSAGE_CREATED, SSE_EVENT_TIMEOUT)
                aliceEvents.awaitEvent(EVENT_MESSAGE_CREATED, SSE_EVENT_TIMEOUT)

                val retry = sendMessage(alice, chatId, RETRY_DRAFT_TEXT, clientMessageId)
                assertThat(retry.statusCode)
                    .overridingErrorMessage(
                        "the same-clientMessageId retry must dedup to 200 (§3.2 step 1), got <%s>: %s",
                        retry.statusCode,
                        retry.body,
                    ).isEqualTo(HttpStatus.OK)

                val mark = markRead(bob, chatId, seq)
                assertThat(mark.statusCode)
                    .overridingErrorMessage(
                        "the peer's read advance (§3.2 step 3) must answer 204, got <%s>",
                        mark.statusCode,
                    ).isEqualTo(HttpStatus.NO_CONTENT)
            }
        }

        assertGrows(ackCreatedBefore, MINIMUM_ONE, ACK_COUNT, TAG_OUTCOME_CREATED)
        assertGrows(ackExistingBefore, MINIMUM_ONE, ACK_COUNT, TAG_OUTCOME_EXISTING)
        assertGrows(dedupBefore, MINIMUM_ONE, DEDUP_TOTAL)
        assertGrows(pushPublishBefore, MINIMUM_TWO, PUSH_COUNT, TAG_STAGE_PUBLISH)
        assertGrows(pushDispatchBefore, MINIMUM_ONE, PUSH_COUNT, TAG_STAGE_DISPATCH)
        assertGrows(readAdvancedBefore, MINIMUM_ONE, READ_ADVANCED)
    }

    /**
     * Quickstart §3.7 (step 2): a blocked pair refuses sends in BOTH
     * directions — the refusal counters grow BY REASON, and every WARN
     * record of the refusal path stays PII-free (ids and codes only,
     * never the refused draft texts).
     */
    @Test
    @Order(2)
    fun `quickstart 3_7 blocking refusals counted by reason without PII`(capturedOutput: CapturedOutput) {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        assertThat(blockUser(alice, bob.id).statusCode)
            .overridingErrorMessage("№23 block must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)

        val blockedByYouBefore = current(REJECTED_TOTAL, TAG_REASON_BLOCKED_BY_YOU)
        val youAreBlockedBefore = current(REJECTED_TOTAL, TAG_REASON_YOU_ARE_BLOCKED)

        val bobDraft = uniquePii("blocked-bob")
        val bobRefusal = sendMessage(bob, chatId, bobDraft)
        assertRefusal(bobRefusal, HttpStatus.FORBIDDEN, REASON_YOU_ARE_BLOCKED)
        val aliceDraft = uniquePii("blocked-alice")
        val aliceRefusal = sendMessage(alice, chatId, aliceDraft)
        assertRefusal(aliceRefusal, HttpStatus.FORBIDDEN, REASON_BLOCKED_BY_YOU)

        assertGrows(blockedByYouBefore, MINIMUM_ONE, REJECTED_TOTAL, TAG_REASON_BLOCKED_BY_YOU)
        assertGrows(youAreBlockedBefore, MINIMUM_ONE, REJECTED_TOTAL, TAG_REASON_YOU_ARE_BLOCKED)

        await().atMost(LOG_WAIT).untilAsserted {
            val warns = warnLines(capturedOutput)
            assertThat(warns.any { it.contains(MARKER_YOU_ARE_BLOCKED) })
                .overridingErrorMessage("the §3.7 you_are_blocked refusal must produce a WARN record")
                .isTrue
            assertThat(warns.any { it.contains(MARKER_CHAT_BLOCKED_BY_YOU) })
                .overridingErrorMessage("the §3.7 chat_blocked_by_you refusal must produce a WARN record")
                .isTrue
            assertThat(warns.none { it.contains(bobDraft) || it.contains(aliceDraft) })
                .overridingErrorMessage(
                    "no WARN record may leak the refused draft texts (constitution V, SC-008): <%s> / <%s>",
                    bobDraft,
                    aliceDraft,
                ).isTrue
        }
    }

    /**
     * Quickstart §3.8 (step 1 + the search flood of FR-016): the drained
     * per-user buckets refuse the 31st send and the 31st search — both
     * flood counters grow, and the WARN records of both refusals stay
     * PII-free (the flooded message texts and the search query never
     * appear).
     */
    @Test
    @Order(3)
    fun `quickstart 3_8 flood refusals counted for send and search without PII`(capturedOutput: CapturedOutput) {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val sendFloodBefore = current(REJECTED_TOTAL, TAG_REASON_FLOOD)
        val searchFloodBefore = current(SEARCH_REJECTED_TOTAL, TAG_REASON_FLOOD)

        val sendPiiPrefix = uniquePii("flood-send")
        var sendRefusals = 0
        var attempt = 0
        while (sendRefusals == 0 && attempt < BURST_CAP) {
            attempt += 1
            val response = sendMessage(alice, chatId, "$sendPiiPrefix-$attempt")
            if (response.statusCode != HttpStatus.CREATED) {
                assertRefusal(response, HttpStatus.TOO_MANY_REQUESTS, REASON_FLOOD)
                sendRefusals += 1
            }
        }
        assertThat(sendRefusals)
            .overridingErrorMessage("the §3.8 send burst must be refused 429 by the drained bucket")
            .isEqualTo(1)

        val queryPii = "${uniquePii("flood-query")}@example.com"
        var searchRefusals = 0
        attempt = 0
        while (searchRefusals == 0 && attempt < BURST_CAP) {
            attempt += 1
            val response = searchUsers(alice, queryPii)
            if (response.statusCode != HttpStatus.OK) {
                assertThat(response.statusCode)
                    .overridingErrorMessage(
                        "the search flood refusal must be 429, got <%s>: %s",
                        response.statusCode,
                        response.body,
                    ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                searchRefusals += 1
            }
        }
        assertThat(searchRefusals)
            .overridingErrorMessage("the §3.8 search burst must be refused 429 by the drained bucket")
            .isEqualTo(1)

        assertGrows(sendFloodBefore, MINIMUM_ONE, REJECTED_TOTAL, TAG_REASON_FLOOD)
        assertGrows(searchFloodBefore, MINIMUM_ONE, SEARCH_REJECTED_TOTAL, TAG_REASON_FLOOD)

        await().atMost(LOG_WAIT).untilAsserted {
            val warns = warnLines(capturedOutput)
            assertThat(warns.any { it.contains(MARKER_SEND_FLOOD) })
                .overridingErrorMessage("the §3.8 send flood refusal must produce a WARN record")
                .isTrue
            assertThat(warns.any { it.contains(MARKER_SEARCH_FLOOD) })
                .overridingErrorMessage("the §3.8 search flood refusal must produce a WARN record")
                .isTrue
            assertThat(warns.none { it.contains(sendPiiPrefix) || it.contains(queryPii) })
                .overridingErrorMessage(
                    "no WARN record may leak the flooded texts or the search query (constitution V, SC-008): " +
                        "<%s> / <%s>",
                    sendPiiPrefix,
                    queryPii,
                ).isTrue
        }
    }

    /**
     * The exposure head of SC-008: after the scenarios above, every one
     * of the six 004 meter families is present in the real exposition
     * with its HELP header — the exact surface an operator scrapes.
     */
    @Test
    @Order(4)
    fun `all six SC-008 meter families are exposed on actuator prometheus`() {
        val exposition = scrape()

        SC008_METERS.forEach { meter ->
            assertThat(exposition)
                .overridingErrorMessage(
                    "SC-008 requires <%s> on /actuator/prometheus, got:%n%s",
                    meter,
                    excerpt(exposition, meter),
                ).contains("# HELP $meter")
        }
    }

    // --- scrape helpers: the operator's view of the meters, not the registry's ---

    /** GET /actuator/prometheus — the real text exposition. */
    private fun scrape(): String {
        val response = apiRestTemplate.getForEntity(PROMETHEUS_PATH, String::class.java)
        assertThat(response.statusCode)
            .overridingErrorMessage("the prometheus exposition must answer 200, got <%s>", response.statusCode)
            .isEqualTo(HttpStatus.OK)
        return response.body.orEmpty()
    }

    /**
     * One sample value of [sampleName] whose label set contains every
     * [tags] entry (e.g. `reason="flood"`); `0.0` when the meter has no
     * sample yet — the lawful «before» value of a delta.
     */
    private fun current(
        sampleName: String,
        vararg tags: String,
    ): Double {
        val wanted = tags.joinToString(separator = "")
        val sample =
            scrape()
                .lineSequence()
                .mapNotNull { line -> SAMPLE_PATTERN.matchEntire(line.trim()) }
                .firstOrNull { match ->
                    match.groupValues[SAMPLE_NAME_GROUP] == sampleName &&
                        (wanted.isEmpty() || match.groupValues[SAMPLE_LABELS_GROUP].contains(wanted))
                }
        return sample?.let { match -> match.groupValues[SAMPLE_VALUE_GROUP].toDouble() } ?: 0.0
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

    // --- flow helpers (№23 block, №19 search) over the same real HTTP ---

    /** Contract №23 `PUT /api/v1/users/{userId}/block` — raw response. */
    private fun blockUser(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchangeAuthorized(user, HttpMethod.PUT, "$USERS_PATH/$userId/block")

    /** Contract №19 `GET /api/v1/users/search?query=` — raw response. */
    private fun searchUsers(
        user: MessagingUser,
        query: String,
    ): ResponseEntity<String> =
        exchangeAuthorized(
            user,
            HttpMethod.GET,
            UriComponentsBuilder
                .fromPath(SEARCH_PATH)
                .queryParam(QUERY_FIELD, query)
                .build()
                .toUriString(),
        )

    private fun exchangeAuthorized(
        user: MessagingUser,
        method: HttpMethod,
        path: String,
    ): ResponseEntity<String> =
        apiRestTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    // --- PII helpers: the WARN records of the JSON log stream ---

    private fun warnLines(capturedOutput: CapturedOutput): List<String> =
        capturedOutput.all
            .lineSequence()
            .filter { WARN_LEVEL_MARKER in it }
            .toList()

    /** A per-run unique PII marker (never log-safe by accident). */
    private fun uniquePii(label: String): String = "$label-${UUID.randomUUID()}"

    /** The №16/№19 refusal shape: the HTTP status and the contract code in the problem body. */
    private fun assertRefusal(
        response: ResponseEntity<String>,
        status: HttpStatus,
        code: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the refusal with code <%s> must answer <%s>, got <%s>: %s",
                code,
                status,
                response.statusCode,
                response.body,
            ).isEqualTo(status)
        assertThat(response.body.orEmpty())
            .overridingErrorMessage(
                "the refusal problem must carry the contract code <%s>, got <%s>",
                code,
                response.body,
            ).contains(code)
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
        /** research.md 004 §11 / quickstart §4: the SC-008 meter names. */
        const val ACK_SECONDS = "webchat_message_ack_seconds"
        const val PUSH_SECONDS = "webchat_realtime_push_seconds"
        const val DEDUP_TOTAL = "webchat_message_dedup_total"
        const val REJECTED_TOTAL = "webchat_send_rejected_total"
        const val SEARCH_REJECTED_TOTAL = "webchat_search_rejected_total"
        const val READ_ADVANCED = "webchat_read_advanced_total"

        /** The exposition sample names of the two timers (`_count` series). */
        const val ACK_COUNT = "webchat_message_ack_seconds_count"
        const val PUSH_COUNT = "webchat_realtime_push_seconds_count"

        val SC008_METERS =
            listOf(ACK_SECONDS, PUSH_SECONDS, DEDUP_TOTAL, REJECTED_TOTAL, SEARCH_REJECTED_TOTAL, READ_ADVANCED)

        /** Label selectors as they appear inside the exposition braces. */
        const val TAG_OUTCOME_CREATED = "outcome=\"created\""
        const val TAG_OUTCOME_EXISTING = "outcome=\"existing\""
        const val TAG_STAGE_PUBLISH = "stage=\"publish\""
        const val TAG_STAGE_DISPATCH = "stage=\"dispatch\""
        const val TAG_REASON_FLOOD = "reason=\"flood\""
        const val TAG_REASON_BLOCKED_BY_YOU = "reason=\"blocked_by_you\""
        const val TAG_REASON_YOU_ARE_BLOCKED = "reason=\"you_are_blocked\""

        const val REASON_FLOOD = "flood"
        const val REASON_BLOCKED_BY_YOU = "blocked_by_you"
        const val REASON_YOU_ARE_BLOCKED = "you_are_blocked"

        /** Distinctive fragments of the warn records (MessageService/UserSearchController). */
        const val MARKER_SEND_FLOOD = "send refused by the flood limit (FR-011)"
        const val MARKER_SEARCH_FLOOD = "search refused by the flood limit (FR-016)"
        const val MARKER_CHAT_BLOCKED_BY_YOU = "(FR-020 chat_blocked_by_you)"
        const val MARKER_YOU_ARE_BLOCKED = "(FR-020 you_are_blocked)"

        /** The logstash-encoded level field of a WARN record. */
        const val WARN_LEVEL_MARKER = "\"level\":\"WARN\""

        const val EVENT_MESSAGE_CREATED = "message.created"

        const val PROMETHEUS_PATH = "/actuator/prometheus"
        const val USERS_PATH = "/api/v1/users"
        const val SEARCH_PATH = "/api/v1/users/search"
        const val QUERY_FIELD = "query"

        const val FRESH_SEND_TEXT = "the fresh exactly-once send of the observability scenario"
        const val RETRY_DRAFT_TEXT = "the same-id retry draft - never stored, never logged"

        const val MINIMUM_ONE = 1.0
        const val MINIMUM_TWO = 2.0
        const val BURST_CAP = 40

        val SSE_EVENT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val METER_WAIT: Duration = Duration.ofSeconds(10)
        val LOG_WAIT: Duration = Duration.ofSeconds(10)

        /**
         * One exposition sample line: `name{labels} value` (the labels
         * braces may be absent for untagged series; the 004 meters all
         * carry at least the `application` common tag).
         */
        val SAMPLE_PATTERN = Regex("""([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([0-9.eE+-]+)""")
        const val SAMPLE_NAME_GROUP = 1
        const val SAMPLE_LABELS_GROUP = 2
        const val SAMPLE_VALUE_GROUP = 3
    }
}
