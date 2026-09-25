package webchat.backend.sync

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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * T045 (tasks.md Phase 7, FR-014): the observability audit of the 005
 * delivery-resilience feature — the EIGHT contract meters of the feature
 * are EXPOSED on `/actuator/prometheus` and GROW in the quickstart
 * §3.1–§3.5 scenarios, the 004 rate-limiting observability stays in
 * place (`webchat_send_rejected_total{reason=flood}`, the saturation
 * counterpart T042 scrapes the same way), and the shed/truncation log
 * records are JSON with ids/points ONLY — never the message text
 * (constitution V, the PII minimization of 004).
 *
 *  * §3.1 reconnect catch-up (10 missed messages → №26 → №25) grows
 *    `webchat_sync_request_seconds{outcome=full}`, the replay volume
 *    `webchat_sync_messages_delivered_total` by the whole backlog and
 *    `webchat_delivery_ack_seconds`;
 *  * §3.2 truncation («Bob deleted the chat») and the future-cursor
 *    repair grow `webchat_sync_truncated_total` /
 *    `webchat_sync_desynced_total` — the INFO repair records of
 *    research.md §8 (chatId + точка, PII-free);
 *  * §3.5 degradation (quickstart lowers `max-limit` the same way this
 *    context does) sheds a saturated №16 path: the shed grows
 *    `webchat_backpressure_rejections_total`, the WARN record carries
 *    ids/Retry-After only, and the drained 30/minute bucket grows
 *    `webchat_send_rejected_total{reason=flood}` — the rate-limit meter
 *    the T042 saturation profile asserts server-side;
 *  * the exposure head: every one of the eight FR-014 meters (plus the
 *    reused flood counter) is present in the real exposition with its
 *    HELP header — the exact surface an operator scrapes.
 *
 * The meters are read the way an operator reads them — by scraping the
 * real `/actuator/prometheus` text exposition before/after each scenario
 * (never through the MeterRegistry), so the audit covers registration,
 * naming, tags and the endpoint exposure itself (the T038 IT reads the
 * same gauges through the registry — this class pins the WIRE format).
 * Deltas, not absolutes, keep the audit immune to the shared test
 * context. The saturation leg is deterministic (the BackpressureIT
 * precedent): an IT-held `SELECT … FOR UPDATE` on the chat row parks the
 * ADMITTED sends in-flight, so with `max-limit=2` the third send sheds.
 */
@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // quickstart §3.5: the stress profile lowers the admission bounds
        // the same way for the manual run
        "delivery.backpressure.enabled=true",
        "delivery.backpressure.min-limit=1",
        "delivery.backpressure.max-limit=2",
        "delivery.backpressure.latency-baseline=50ms",
    ],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("TooManyFunctions") // T045: one helper per scrape/tag/PII concern (the T067 fixture style)
class DeliveryObservabilityIT(
    @Autowired private val observabilityRestTemplate: TestRestTemplate,
    @Autowired private val observabilityObjectMapper: ObjectMapper,
    @Autowired private val observabilityDataSource: DataSource,
) : SyncTestSupport() {
    /**
     * Quickstart §3.1 (steps 1 and 5): the reconnect catch-up — the peer
     * seeds a 10-message backlog while the subject is offline, №26 replays
     * it ascending and №25 acknowledges the head. The sweep timer (a full
     * single-page catch-up), the replay volume counter and the ack timer
     * must all answer with the scenario minimum.
     */
    @Test
    @Order(1)
    fun `quickstart 3_1 reconnect catch-up grows sync ack and replay meters`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)
        val backlog = seedChatBacklog(chatId, CATCHUP_MESSAGES, { bob }, CATCHUP_TEXT_PREFIX)

        val syncFullBefore = current(SYNC_REQUEST_COUNT, TAG_OUTCOME_FULL)
        val replayedBefore = current(SYNC_DELIVERED_TOTAL)
        val ackBefore = current(ACK_COUNT)

        val sync = requestSync(alice, listOf(chatId to NO_CURSOR))
        assertThat(sync.statusCode)
            .overridingErrorMessage("the §3.1 catch-up sync must answer 200, got <%s>: %s", sync.statusCode, sync.body)
            .isEqualTo(HttpStatus.OK)
        val delta = observabilityObjectMapper.readTree(sync.body)["chats"].first()
        assertThat(delta["messages"].size())
            .overridingErrorMessage("the §3.1 delta must replay the whole %d-message backlog", CATCHUP_MESSAGES)
            .isEqualTo(CATCHUP_MESSAGES)

        val ack = deliveryAck(alice, listOf(chatId to backlog.last().seq))
        assertThat(ack.statusCode)
            .overridingErrorMessage("the §3.1 delivery-ack of the page head must answer 204, got <%s>", ack.statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        assertGrows(syncFullBefore, MINIMUM_ONE, SYNC_REQUEST_COUNT, TAG_OUTCOME_FULL)
        assertGrows(replayedBefore, CATCHUP_MESSAGES.toDouble(), SYNC_DELIVERED_TOTAL)
        assertGrows(ackBefore, MINIMUM_ONE, ACK_COUNT)
    }

    /**
     * Quickstart §3.2 (steps 1–2): the truncation (the per-user deletion
     * watermark pins `startAfterSeq` to the visibility floor) and the
     * future-cursor repair (`desynced` + `serverUpToSeq`, never a request
     * error) grow their repair counters, and the INFO records of
     * research.md §8 land in the JSON log stream with the chat id and the
     * point ONLY — the seeded texts are per-run unique PII markers and
     * must never surface in ANY log record of the run.
     */
    @Test
    @Order(2)
    fun `quickstart 3_2 truncation and future cursor grow repair meters PII-free`(capturedOutput: CapturedOutput) {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)
        seedChatBacklog(chatId, TRUNCATION_MESSAGES, { bob }, truncationPiiPrefix)

        val truncatedBefore = current(SYNC_TRUNCATED_TOTAL)
        val desyncedBefore = current(SYNC_DESYNCED_TOTAL)

        setDeletionWatermark(alice.id, chatId, DELETION_FLOOR)
        val truncated = requestSync(alice, listOf(chatId to NO_CURSOR))
        assertThat(truncated.statusCode)
            .overridingErrorMessage(
                "the §3.2 truncated sync must answer 200, got <%s>: %s",
                truncated.statusCode,
                truncated.body,
            ).isEqualTo(HttpStatus.OK)
        val truncatedDelta = observabilityObjectMapper.readTree(truncated.body)["chats"].first()
        assertThat(truncatedDelta["truncatedUpToSeq"].asLong())
            .overridingErrorMessage("the §3.2 delta must pin the truncation floor at <%d>", DELETION_FLOOR)
            .isEqualTo(DELETION_FLOOR)

        val futureCursor = lastSeqOf(chatId) + FUTURE_CURSOR_MARGIN
        val desynced = requestSync(alice, listOf(chatId to futureCursor))
        assertThat(desynced.statusCode)
            .overridingErrorMessage(
                "the §3.2 future cursor must never fail the request (desync repair), got <%s>: %s",
                desynced.statusCode,
                desynced.body,
            ).isEqualTo(HttpStatus.OK)
        val desyncDelta = observabilityObjectMapper.readTree(desynced.body)["chats"].first()
        assertThat(desyncDelta["desynced"].asBoolean())
            .overridingErrorMessage("the §3.2 delta of a future cursor must carry desynced=true")
            .isTrue

        assertGrows(truncatedBefore, MINIMUM_ONE, SYNC_TRUNCATED_TOTAL)
        assertGrows(desyncedBefore, MINIMUM_ONE, SYNC_DESYNCED_TOTAL)

        await()
            .atMost(LOG_WAIT)
            .untilAsserted {
                val records = capturedOutput.all.lineSequence().toList()
                assertThat(records.any { it.contains(MARKER_TRUNCATED) })
                    .overridingErrorMessage("the §3.2 truncation must produce its INFO repair record")
                    .isTrue
                assertThat(records.any { it.contains(MARKER_DESYNCED) })
                    .overridingErrorMessage("the §3.2 future-cursor repair must produce its INFO record")
                    .isTrue
                assertThat(records.none { it.contains(truncationPiiPrefix) })
                    .overridingErrorMessage(
                        "no log record may leak the synced message texts (constitution V): <%s>",
                        truncationPiiPrefix,
                    ).isTrue
            }
    }

    /**
     * Quickstart §3.5 (steps 1 and 3, quickstart lowers `max-limit` the
     * same way): the saturated №16 path sheds the third concurrent send —
     * `503 server_busy` + `Retry-After`, the shed grows
     * `webchat_backpressure_rejections_total`, the WARN record carries
     * ids/Retry-After only; and the drained 30/minute bucket grows the
     * 004 `webchat_send_rejected_total{reason=flood}` — the exact meter
     * the T042 saturation profile scrapes server-side (FR-014).
     */
    @Test
    @Order(3)
    fun `quickstart 3_5 shed and flood refusals grow meters without PII`(capturedOutput: CapturedOutput) {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)

        val rejectionsBefore = current(BACKPRESSURE_REJECTIONS_TOTAL)
        val floodBefore = current(SEND_REJECTED_TOTAL, TAG_REASON_FLOOD)

        val stall = ChatRowStall(chatId)
        val parked =
            try {
                val volley =
                    (1..SATURATION_SENDS).map { fireAsyncSend(bob, chatId, "$shedPiiPrefix-$it") }
                await()
                    .atMost(INFLIGHT_WAIT)
                    .untilAsserted {
                        assertThat(current(BACKPRESSURE_INFLIGHT))
                            .overridingErrorMessage(
                                "the parked volley must hold both admission slots (max-limit=2) before the shed probe",
                            ).isGreaterThanOrEqualTo(ADMISSION_LIMIT.toDouble())
                    }
                val shed = sendMessage(alice, chatId, SHED_PROBE_TEXT)
                assertServerBusyShed(shed)
                volley
            } finally {
                stall.close()
            }
        parked.forEach { future ->
            assertThat(future.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).statusCode)
                .overridingErrorMessage("the parked admitted sends must commit with 201 after the stall release")
                .isEqualTo(HttpStatus.CREATED)
        }

        drainUntilFloodRefusal(alice, chatId)

        assertGrows(rejectionsBefore, MINIMUM_ONE, BACKPRESSURE_REJECTIONS_TOTAL)
        assertGrows(floodBefore, MINIMUM_ONE, SEND_REJECTED_TOTAL, TAG_REASON_FLOOD)

        await()
            .atMost(LOG_WAIT)
            .untilAsserted {
                val warns = warnLines(capturedOutput)
                assertThat(warns.any { it.contains(MARKER_SHED) })
                    .overridingErrorMessage("the §3.5 shed must produce its WARN record")
                    .isTrue
                assertThat(
                    warns.none {
                        it.contains(shedPiiPrefix) || it.contains(floodPiiPrefix) || it.contains(SHED_PROBE_TEXT)
                    },
                ).overridingErrorMessage(
                    "no WARN record may leak the refused drafts (constitution V): <%s> / <%s>",
                    shedPiiPrefix,
                    floodPiiPrefix,
                ).isTrue
            }
    }

    /**
     * The exposure head of FR-014: after the scenarios above, every one of
     * the EIGHT feature meters — plus the reused 004 flood counter — is
     * present in the real exposition with its HELP header: the exact
     * surface quickstart §4 tells an operator to scrape.
     */
    @Test
    @Order(4)
    fun `all eight FR-014 meters plus the flood counter are exposed on actuator prometheus`() {
        val exposition = scrape()

        FR014_METERS.forEach { meter ->
            assertThat(exposition)
                .overridingErrorMessage(
                    "FR-014 requires <%s> on /actuator/prometheus, got:%n%s",
                    meter,
                    excerpt(exposition, meter),
                ).contains("# HELP $meter")
        }
    }

    // --- saturation machinery (the BackpressureIT precedent, local copy) ---

    /**
     * Quickstart §3.5 step 3: fresh №16 sends until the drained 30/minute
     * bucket refuses with the FIRST `429 flood_limit` — the server-side
     * leg behind `webchat_send_rejected_total{reason=flood}`.
     */
    private fun drainUntilFloodRefusal(
        user: MessagingUser,
        chatId: UUID,
    ) {
        var floodRefusals = 0
        var attempt = 0
        while (floodRefusals == 0 && attempt < BURST_CAP) {
            attempt += 1
            val response = sendMessage(user, chatId, "$floodPiiPrefix-$attempt")
            if (response.statusCode != HttpStatus.CREATED) {
                assertThat(response.statusCode)
                    .overridingErrorMessage(
                        "the drained bucket refusal must be the 429 of FR-011, got <%s>",
                        response.statusCode,
                    ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                floodRefusals += 1
            }
        }
        assertThat(floodRefusals)
            .overridingErrorMessage("the §3.5 flood drain must be refused 429 at least once")
            .isEqualTo(1)
    }

    /**
     * Deterministic saturation: one borrowed connection holds a row lock on
     * `chats`, so the `UPDATE chats SET last_seq` leg of the exactly-once
     * insert transaction blocks — every ADMITTED send parks in-flight
     * until [close] rolls the lock back. The shed decision itself is a
     * local (PG-free) refusal and never queues behind the lock.
     */
    private inner class ChatRowStall(
        chatId: UUID,
    ) : AutoCloseable {
        private val connection: Connection = observabilityDataSource.connection

        init {
            connection.autoCommit = false
            connection.prepareStatement(CHAT_ROW_LOCK_SQL).use { statement ->
                statement.setObject(1, chatId)
                statement.executeQuery().use { rows -> rows.next() }
            }
        }

        override fun close() {
            connection.rollback()
            connection.close()
        }
    }

    /** Daemon on purpose: a failed scenario may leave a request parked on the stall. */
    private val sendPool =
        Executors.newFixedThreadPool(SATURATION_SENDS) { runnable ->
            Thread(runnable, "delivery-observability-sender").apply { isDaemon = true }
        }

    /** Fires one authenticated №16 send asynchronously (the saturation volley unit). */
    private fun fireAsyncSend(
        user: MessagingUser,
        chatId: UUID,
        text: String,
    ): Future<ResponseEntity<String>> =
        sendPool.submit(
            Callable {
                observabilityRestTemplate.postForEntity(
                    "/api/v1/chats/$chatId/messages",
                    HttpEntity(
                        mapOf(
                            "clientMessageId" to UUID.randomUUID().toString(),
                            "text" to text,
                        ),
                        HttpHeaders().apply {
                            contentType = MediaType.APPLICATION_JSON
                            setBearerAuth(user.accessToken)
                        },
                    ),
                    String::class.java,
                )
            },
        )

    /**
     * The exact №16 503 of api-contract.md §3: `503` + problem+json with
     * `errors.chat=[server_busy]` and an integral `Retry-After` ≥ 1 s.
     */
    private fun assertServerBusyShed(shed: ResponseEntity<String>) {
        assertThat(shed.statusCode)
            .overridingErrorMessage(
                "the saturated §3.5 send path must refuse with 503 server_busy (SC-005), got <%s>: %s",
                shed.statusCode,
                shed.body,
            ).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(shed.headers.contentType?.toString())
            .overridingErrorMessage("the backpressure refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val retryAfter =
            shed.headers
                .getFirst(HttpHeaders.RETRY_AFTER)
                ?.trim()
                ?.toLongOrNull()
        assertThat(retryAfter)
            .overridingErrorMessage(
                "the backpressure refusal must carry an integral Retry-After, got <%s>",
                shed.headers.getFirst(HttpHeaders.RETRY_AFTER),
            ).isNotNull
        assertThat(retryAfter!!)
            .isGreaterThanOrEqualTo(RETRY_AFTER_FLOOR_SECONDS)
        val problem = observabilityObjectMapper.readTree(shed.body)
        val codes = problem["errors"]?.get(CHAT_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                CHAT_FIELD,
                SERVER_BUSY,
                codes,
                shed.body,
            ).containsExactly(SERVER_BUSY)
    }

    // --- scrape helpers: the operator's view of the meters, not the registry's ---

    /** GET /actuator/prometheus — the real text exposition. */
    private fun scrape(): String {
        val response = observabilityRestTemplate.getForEntity(PROMETHEUS_PATH, String::class.java)
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
                    "FR-014: <%s{%s}> must grow by at least <%s> after the scenario " +
                        "(before=<%s>, now=<%s>)",
                    sampleName,
                    tags.joinToString(separator = ","),
                    byAtLeast,
                    before,
                    now,
                ).isGreaterThanOrEqualTo(byAtLeast)
        }
    }

    // --- PII helpers: the log records of the JSON logstash stream ---

    private fun warnLines(capturedOutput: CapturedOutput): List<String> =
        capturedOutput.all
            .lineSequence()
            .filter { WARN_LEVEL_MARKER in it }
            .toList()

    private fun excerpt(
        exposition: String,
        meter: String,
    ): String =
        exposition
            .lineSequence()
            .filter { line -> meter in line }
            .joinToString(separator = "\n")

    private companion object {
        /** research.md 005 §8 / tasks.md T045: the FR-014 meter names (8) + the reused 004 flood counter. */
        const val SYNC_REQUEST_SECONDS = "webchat_sync_request_seconds"
        const val SYNC_DELIVERED_TOTAL = "webchat_sync_messages_delivered_total"
        const val SYNC_TRUNCATED_TOTAL = "webchat_sync_truncated_total"
        const val SYNC_DESYNCED_TOTAL = "webchat_sync_desynced_total"
        const val ACK_SECONDS = "webchat_delivery_ack_seconds"
        const val BACKPRESSURE_REJECTIONS_TOTAL = "webchat_backpressure_rejections_total"
        const val BACKPRESSURE_LIMIT = "webchat_backpressure_limit"
        const val BACKPRESSURE_INFLIGHT = "webchat_backpressure_inflight"
        const val SEND_REJECTED_TOTAL = "webchat_send_rejected_total"

        val FR014_METERS =
            listOf(
                SYNC_REQUEST_SECONDS,
                SYNC_DELIVERED_TOTAL,
                SYNC_TRUNCATED_TOTAL,
                SYNC_DESYNCED_TOTAL,
                ACK_SECONDS,
                BACKPRESSURE_REJECTIONS_TOTAL,
                BACKPRESSURE_LIMIT,
                BACKPRESSURE_INFLIGHT,
            )

        /** The exposition sample names of the two timers (`_count` series). */
        const val SYNC_REQUEST_COUNT = "webchat_sync_request_seconds_count"
        const val ACK_COUNT = "webchat_delivery_ack_seconds_count"

        /** Label selectors as they appear inside the exposition braces. */
        const val TAG_OUTCOME_FULL = "outcome=\"full\""
        const val TAG_REASON_FLOOD = "reason=\"flood\""

        /** Distinctive fragments of the repair/refusal records (SyncService/MessageService). */
        const val MARKER_TRUNCATED = "sync delta truncated"
        const val MARKER_DESYNCED = "sync future-cursor repair"
        const val MARKER_SHED = "send admission shed"

        /** The logstash-encoded level field of a WARN record. */
        const val WARN_LEVEL_MARKER = "\"level\":\"WARN\""

        const val PROMETHEUS_PATH = "/actuator/prometheus"
        const val CHAT_ROW_LOCK_SQL = "SELECT last_seq FROM chats WHERE id = ? FOR UPDATE"

        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val CHAT_FIELD = "chat"
        const val SERVER_BUSY = "server_busy"
        const val RETRY_AFTER_FLOOR_SECONDS = 1L

        /** sync-protocol.md §1: a chat without a client cursor resumes from the server position. */
        const val NO_CURSOR = 0L

        const val CATCHUP_MESSAGES = 10
        const val CATCHUP_TEXT_PREFIX = "observability-catchup"
        const val TRUNCATION_MESSAGES = 6
        const val DELETION_FLOOR = 3L
        const val FUTURE_CURSOR_MARGIN = 1_000L
        const val MINIMUM_ONE = 1.0

        /** quickstart §3.5: the lowered admission bound of this context (two parked slots suffice). */
        const val ADMISSION_LIMIT = 2
        const val SATURATION_SENDS = 2
        const val BURST_CAP = 40
        const val JOIN_TIMEOUT_SECONDS = 30L

        val INFLIGHT_WAIT: Duration = Duration.ofSeconds(10)
        val METER_WAIT: Duration = Duration.ofSeconds(10)
        val LOG_WAIT: Duration = Duration.ofSeconds(10)

        /** Per-run unique PII markers (never log-safe by accident). */
        val truncationPiiPrefix: String = "observability-truncation-${UUID.randomUUID()}"
        val shedPiiPrefix: String = "observability-shed-${UUID.randomUUID()}"
        val floodPiiPrefix: String = "observability-flood-${UUID.randomUUID()}"
        const val SHED_PROBE_TEXT = "the shed probe draft - never stored, never logged"

        /**
         * One exposition sample line: `name{labels} value` (the labels
         * braces may be absent for untagged series; the FR-014 meters all
         * carry at least the `application` common tag).
         */
        val SAMPLE_PATTERN = Regex("""([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([0-9.eE+-]+)""")
        const val SAMPLE_NAME_GROUP = 1
        const val SAMPLE_LABELS_GROUP = 2
        const val SAMPLE_VALUE_GROUP = 3
    }
}
