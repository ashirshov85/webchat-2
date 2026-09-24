package webchat.backend.backpressure

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.chats.MessagingTestSupport
import webchat.backend.config.DeliveryProperties
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/**
 * T038 (tasks.md Phase 6, US4): the integration contract of the adaptive
 * admission limiter on the №16 send path (research.md 005 §6,
 * api-contract.md §3, data-model.md entity 6 — SC-005/006/007).
 *
 * The saturation is DETERMINISTIC, not statistical: an IT-held
 * `SELECT … FOR UPDATE` on the chat row stalls the `UPDATE chats SET
 * last_seq` leg of the exactly-once insert transaction, so every send
 * the limiter ADMITS parks in-flight on that row lock until the test
 * releases it — `in-flight == limit` for as long as the scenario needs,
 * while the shed decision itself stays a local (PG-free) refusal.
 * Readers remain unaffected by the stall (PG MVCC): the FR-004 dedup
 * lookup and the membership SELECT sail through — exactly the legs the
 * ordering probes rely on.
 *
 * Against the pre-T039/T040/T041 send path every enabled-profile method
 * of [BackpressureIT] fails (constitution VI Test-First): nothing sheds
 * (there is no admission gate on the path yet) and the
 * `webchat_backpressure_limit` gauge does not exist, while the send path
 * keeps its 004/005-US1 behaviour. The [BackpressureDisabledIT]
 * companion class guards the `delivery.backpressure.enabled=false`
 * profile — the send path must stay exactly the old one (no 503 ever,
 * FR-011 flood intact).
 *
 * Both classes extend the T002 fixtures over the real 001 flows
 * (Testcontainers PG 17 + Redis 7, real HTTP, no mocks) and register
 * their own users per method, so the per-user Redis flood buckets never
 * leak between scenarios.
 *
 * This abstract base carries the shared machinery of the two
 * backpressure IT contexts: the chat-row stall, the concurrent send
 * fan-out, the shed/flood shape assertions and the Micrometer reads of
 * the admission state.
 */
@Suppress("TooManyFunctions") // T038: fixture library — one helper per contract concern, mirroring MessagingTestSupport
abstract class BackpressureSendSupport : MessagingTestSupport() {
    @Autowired
    protected lateinit var bpRestTemplate: TestRestTemplate

    @Autowired
    protected lateinit var bpObjectMapper: ObjectMapper

    @Autowired
    protected lateinit var bpMeterRegistry: MeterRegistry

    @Autowired
    protected lateinit var bpDataSource: DataSource

    @Autowired
    protected lateinit var bpJdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var bpDeliveryProperties: DeliveryProperties

    /**
     * The fan-out pool of the saturation volleys. Daemon on purpose: a
     * failed scenario may leave a request parked on the chat-row stall,
     * and the JVM must still be able to exit after reporting.
     */
    private val sendPool =
        Executors.newFixedThreadPool(SEND_POOL_SIZE) { runnable ->
            Thread(runnable, "backpressure-it-sender").apply { isDaemon = true }
        }

    /**
     * Deterministic saturation of the admission limiter: one borrowed
     * pool connection holds a row lock on `chats`, so the
     * `UPDATE chats SET last_seq` of the exactly-once insert transaction
     * blocks — every ADMITTED send parks in-flight (its admission slot
     * held) until [close] rolls the lock back. The shed decision, the
     * dedup fast-path and the membership gate are local checks/reads and
     * never queue behind the lock — which is exactly what the ordering
     * probes assert.
     */
    protected inner class ChatRowStall(
        chatId: UUID,
    ) : AutoCloseable {
        private val connection: Connection = bpDataSource.connection

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

    /** The outcome of a deadline split: [completed] answered, [pending] still parked. */
    protected data class DeadlineSplit(
        val completed: List<ResponseEntity<String>>,
        val pending: List<Future<ResponseEntity<String>>>,
    )

    /** One №16 drain leg: the accepted fresh sends plus the first flood refusal. */
    protected data class FloodDrain(
        val accepted: Int,
        val rejection: ResponseEntity<String>,
    )

    /** Fires one authenticated №16 send asynchronously (the saturation volley unit). */
    protected fun fireAsyncSend(
        user: MessagingUser,
        chatId: UUID,
        text: String,
    ): Future<ResponseEntity<String>> =
        sendPool.submit(
            Callable {
                val headers =
                    HttpHeaders().apply {
                        contentType = MediaType.APPLICATION_JSON
                        setBearerAuth(user.accessToken)
                    }
                bpRestTemplate.postForEntity(
                    "/api/v1/chats/$chatId/messages",
                    HttpEntity(
                        mapOf(
                            "clientMessageId" to UUID.randomUUID().toString(),
                            "text" to text,
                        ),
                        headers,
                    ),
                    String::class.java,
                )
            },
        )

    /**
     * Collects the volley answers that arrive within [window] (the sheds —
     * a shed never touches PG) and reports the rest as [DeadlineSplit.pending]:
     * those are the admitted sends parked on the chat-row stall, and they
     * cannot complete before the lock goes away.
     */
    @Suppress("SwallowedException") // the timeout IS the signal: the future is still parked on the stall
    protected fun splitAtDeadline(
        futures: List<Future<ResponseEntity<String>>>,
        window: Duration,
    ): DeadlineSplit {
        val deadline = System.nanoTime() + window.toNanos()
        val completed = mutableListOf<ResponseEntity<String>>()
        val pending = mutableListOf<Future<ResponseEntity<String>>>()
        for (future in futures) {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
            try {
                completed += future.get(remaining, TimeUnit.NANOSECONDS)
            } catch (timeout: TimeoutException) {
                pending += future
            }
        }
        return DeadlineSplit(completed, pending)
    }

    /**
     * The exact №16 503 of api-contract.md §3: `503` + problem+json with
     * `errors.chat=[server_busy]` and an integral `Retry-After` of at
     * least 1 second (research.md §6: the drain estimate
     * `ceil(in-flight × EWMA / max(limit, 1))`, floored at 1 — SC-005's
     * «0 silent refusals»).
     */
    protected fun assertServerBusyShed(shed: ResponseEntity<String>) {
        assertThat(shed.statusCode)
            .overridingErrorMessage(
                "an overloaded send path must refuse with 503 server_busy (SC-005), got <%s>: %s",
                shed.statusCode,
                shed.body,
            ).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(shed.headers.contentType?.toString())
            .overridingErrorMessage("the backpressure refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val retryAfterHeader = shed.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()
        assertThat(retryAfterSeconds)
            .overridingErrorMessage(
                "the backpressure refusal must carry an integral Retry-After, got <%s>",
                retryAfterHeader,
            ).isNotNull
        assertThat(retryAfterSeconds!!)
            .overridingErrorMessage("Retry-After must be ≥ 1 second (api-contract.md §3), got <%s>", retryAfterHeader)
            .isGreaterThanOrEqualTo(RETRY_AFTER_FLOOR_SECONDS)
        val problem = bpObjectMapper.readTree(shed.body)
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

    /**
     * Drains a №16 flood bucket: fresh-id sends until the first
     * `429 flood_limit` (asserted verbatim — FR-011 stays the per-user
     * limiter it was in 004) and returns how many were accepted. The
     * greedy 30/60s drip may re-grant tokens mid-drain;
     * [availableFloor] is the caller's guaranteed-remaining allowance.
     */
    protected fun drainFloodBucket(
        user: MessagingUser,
        chatId: UUID,
        textPrefix: String,
        availableFloor: Int,
    ): FloodDrain {
        var accepted = 0
        var rejection: ResponseEntity<String>? = null
        while (rejection == null && accepted < DRAIN_ATTEMPT_CAP) {
            val response = sendMessage(user, chatId, "$textPrefix-${UUID.randomUUID()}")
            if (response.statusCode == HttpStatus.CREATED) {
                accepted += 1
            } else {
                rejection = response
            }
        }
        assertThat(rejection)
            .overridingErrorMessage(
                "the FR-011 flood bucket (30/minute) must refuse the drain of <%s>, but every send answered 201",
                user.username,
            ).isNotNull
        val refusal = rejection!!
        assertThat(refusal.statusCode)
            .overridingErrorMessage(
                "the drain refusal must be the 429 of FR-011, got <%s>: %s",
                refusal.statusCode,
                refusal.body,
            ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(
            refusal.headers
                .getFirst(HttpHeaders.RETRY_AFTER)
                ?.trim()
                ?.toLongOrNull(),
        ).overridingErrorMessage("the flood refusal must carry an integral Retry-After ≥ 1")
            .isGreaterThanOrEqualTo(RETRY_AFTER_FLOOR_SECONDS)
        val codes =
            bpObjectMapper
                .readTree(refusal.body)["errors"]
                ?.get(TEXT_FIELD)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage("the flood refusal must carry errors.%s=[%s]", TEXT_FIELD, FLOOD_LIMIT)
            .containsExactly(FLOOD_LIMIT)
        assertThat(accepted)
            .overridingErrorMessage(
                "the flood allowance must be intact (≥ <%d> accepted before the 429), got <%d>",
                availableFloor,
                accepted,
            ).isGreaterThanOrEqualTo(availableFloor)
        return FloodDrain(accepted, refusal)
    }

    /** The T039 gauge `webchat_backpressure_limit` — null until the limiter registers it. */
    protected fun admissionLimitGauge(): Double? = bpMeterRegistry.find(BACKPRESSURE_LIMIT_GAUGE).gauge()?.value()

    /** The T039 counter `webchat_backpressure_rejections_total` — 0.0 until registered. */
    protected fun admissionRejectionsTotal(): Double {
        val counter = bpMeterRegistry.find(BACKPRESSURE_REJECTIONS_TOTAL).counter()
        return counter?.count() ?: 0.0
    }

    /** The «nothing was written» leg: the raw row count behind the dialog. */
    protected fun countMessageRows(chatId: UUID): Int =
        bpJdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages WHERE chat_id = ?", Int::class.java, chatId) ?: 0

    private companion object {
        const val CHAT_ROW_LOCK_SQL = "SELECT last_seq FROM chats WHERE id = ? FOR UPDATE"

        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val CHAT_FIELD = "chat"
        const val TEXT_FIELD = "text"
        const val SERVER_BUSY = "server_busy"
        const val FLOOD_LIMIT = "flood_limit"

        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val SEND_POOL_SIZE = 8
        const val DRAIN_ATTEMPT_CAP = 80

        const val BACKPRESSURE_LIMIT_GAUGE = "webchat_backpressure_limit"
        const val BACKPRESSURE_REJECTIONS_TOTAL = "webchat_backpressure_rejections_total"
    }
}

/**
 * T038: the enabled admission limiter under the low-limit test profile
 * (research.md §11 «шедулимый gate — тестовый профиль: низкие лимиты»;
 * quickstart §3.5 lowers the bounds the same way for the manual run).
 *
 * Ordered: the AIMD scenario runs FIRST on the virgin limiter state of
 * this context (the Spring context caches the singleton limiter across
 * the class methods — the ordering scenario below tolerates any starting
 * limit, the AIMD shrink/recovery legs do not).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "delivery.backpressure.enabled=true",
        "delivery.backpressure.min-limit=1",
        "delivery.backpressure.max-limit=4",
        "delivery.backpressure.latency-baseline=50ms",
    ],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BackpressureIT : BackpressureSendSupport() {
    /**
     * SC-006/FR-013 (data-model entity 6 «Деградация поэтапно»): a burst
     * of pathologically slow PG commits (the chat-row stall ≈ seconds vs
     * the 50ms baseline — a ≥ 60× gradient) squeezes the AIMD limit
     * below its steady base, and once the load subsides the healthy
     * trickle of fast sends grows it back to base — the self-healing an
     * operator watches on `webchat_backpressure_limit`.
     */
    @Test
    @Order(1)
    fun `admission limit shrinks under a latency spike and recovers to base after the load subsides`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val users = listOf(alice, bob)
        val base = bpDeliveryProperties.backpressure.maxLimit.toDouble()

        pumpLimitToBase(users, chatId, base, WARMUP_TIMEOUT_MILLIS)

        repeat(SPIKE_ROUNDS) { round ->
            val stall = ChatRowStall(chatId)
            val volley = (1..SATURATION_SENDS).map { fireAsyncSend(bob, chatId, "$SPIKE_BLOCKER_TEXT-$round-$it") }
            // T041 fixup: only the PARKED (admitted) futures can commit with
            // 201 after the release — a shed future has already answered its
            // one-shot 503 above and can never become a 201.
            val parked: List<Future<ResponseEntity<String>>>
            try {
                val split = splitAtDeadline(volley, shedWindow())
                assertThat(split.completed.size)
                    .overridingErrorMessage(
                        "with in-flight parked at the limit (%d concurrent sends against max-limit=%d) the limiter " +
                            "must shed (SC-005), but every send was admitted",
                        SATURATION_SENDS,
                        bpDeliveryProperties.backpressure.maxLimit,
                    ).isGreaterThanOrEqualTo(MIN_SHEDS_PER_ROUND)
                split.completed.forEach(::assertServerBusyShed)
                parked = split.pending
                Thread.sleep(SPIKE_HOLD_MILLIS)
            } finally {
                stall.close()
            }
            parked.forEach { future ->
                assertThat(future.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).statusCode)
                    .overridingErrorMessage(
                        "an admitted send of the spike round must commit with 201 after the release",
                    ).isEqualTo(HttpStatus.CREATED)
            }
        }

        awaitLimitBelowBase(base, SHRINK_TIMEOUT_MILLIS)
        pumpLimitToBase(users, chatId, base, RECOVERY_TIMEOUT_MILLIS)
    }

    /**
     * api-contract.md §3 / data-model entity 6 «Порядок в №16» — the
     * check order dedup → admission → membership → validation → flood,
     * asserted leg by leg while `in-flight == limit` (the chat-row
     * stall): the dedup retry of an already recorded id answers `200`
     * with the SAME row (never 503/429, FR-009); a fresh send with a
     * drained bucket sheds 503 (not 429 — the system signal, not the
     * personal limit); a stranger's send sheds 503 (not 403); a member's
     * blank text sheds 503 (not 400). Flood tokens do NOT burn on sheds
     * (FR-010): after the spike Bob's full 30/minute allowance is intact
     * and the very next fresh sends still hit the 429 — the limit is not
     * bypassed by retrying through the shed. A shed writes NOTHING and
     * grows `webchat_backpressure_rejections_total` (T039).
     */
    @Test
    @Order(2)
    fun `send path checks run dedup then admission then membership validation and flood`() {
        val (alice, bob) = messagingPair()
        val carol = strangerUser()
        val chatId = ensureChatOk(alice, bob.id)

        val recordedId = UUID.randomUUID()
        assertThat(sendMessage(alice, chatId, ORDER_RECORDED_TEXT, recordedId).statusCode)
            .isEqualTo(HttpStatus.CREATED)
        val aliceDrain = drainFloodBucket(alice, chatId, ORDER_ALICE_DRAIN_PREFIX, FLOOD_WINDOW - 1)

        val rejectionsBefore = admissionRejectionsTotal()
        val outcome = saturatedOrderingProbes(chatId, alice, bob, carol, recordedId)

        // T041 fixup: only the PARKED (admitted) futures can commit with 201
        // once the stall releases — the shed futures have already answered
        // their one-shot 503 inside the probe leg.
        outcome.parked.forEach { future ->
            val accepted = future.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertThat(accepted.statusCode)
                .overridingErrorMessage(
                    "the parked admitted sends must commit with 201 once the stall releases, got <%s>: %s",
                    accepted.statusCode,
                    accepted.body,
                ).isEqualTo(HttpStatus.CREATED)
        }

        val bobDrain = drainFloodBucket(bob, chatId, ORDER_BOB_DRAIN_PREFIX, FLOOD_WINDOW - outcome.admitted)
        assertThat(outcome.admitted + bobDrain.accepted)
            .overridingErrorMessage(
                "sheds must not burn flood tokens (FR-010): Bob's full 30/minute allowance must survive " +
                    "<%d> sheds + <%d> admitted sends, but only <%d> fresh sends were accepted before the 429",
                outcome.sheds,
                outcome.admitted,
                bobDrain.accepted,
            ).isGreaterThanOrEqualTo(FLOOD_WINDOW)

        assertThat(countMessageRows(chatId))
            .overridingErrorMessage(
                "the 503 sheds and the ordering probes must write NOTHING: rows must equal recorded+drains+admitted",
            ).isEqualTo(1 + aliceDrain.accepted + outcome.admitted + bobDrain.accepted)

        assertThat(admissionRejectionsTotal() - rejectionsBefore)
            .overridingErrorMessage(
                "every 503 must grow webchat_backpressure_rejections_total (T039) by at least the observed sheds",
            ).isGreaterThanOrEqualTo((outcome.sheds + ORDER_PROBES).toDouble())
    }

    /** What the saturated probe leg observed: shed count and parked (admitted) count. */
    private data class OrderingOutcome(
        val sheds: Int,
        val admitted: Int,
        val parked: List<Future<ResponseEntity<String>>>,
    )

    /**
     * Holds the chat-row stall, saturates the limiter with a concurrent
     * volley of Bob sends (the admitted ones park in-flight) and runs
     * the four ordering probes against the saturated path. The stall is
     * released in `finally`; the parked volley futures are returned for
     * the caller's post-release assertions.
     */
    private fun saturatedOrderingProbes(
        chatId: UUID,
        alice: MessagingUser,
        bob: MessagingUser,
        carol: MessagingUser,
        recordedId: UUID,
    ): OrderingOutcome {
        val stall = ChatRowStall(chatId)
        val volley = (1..SATURATION_SENDS).map { fireAsyncSend(bob, chatId, "$ORDER_BLOCKER_TEXT-$it") }
        try {
            val split = splitAtDeadline(volley, shedWindow())
            val minSheds = SATURATION_SENDS - bpDeliveryProperties.backpressure.maxLimit
            assertThat(split.completed.size)
                .overridingErrorMessage(
                    "%d concurrent sends against max-limit=%d must leave at least <%d> shed at 503, got %s",
                    SATURATION_SENDS,
                    bpDeliveryProperties.backpressure.maxLimit,
                    minSheds,
                    split.completed.map { it.statusCode },
                ).isGreaterThanOrEqualTo(minSheds)
            split.completed.forEach(::assertServerBusyShed)
            assertThat(split.pending.size)
                .overridingErrorMessage(
                    "the admitted (parked in-flight) sends must number between min-limit and max-limit, got <%d>",
                    split.pending.size,
                ).isBetween(bpDeliveryProperties.backpressure.minLimit, bpDeliveryProperties.backpressure.maxLimit)

            val dedupRetry = sendMessage(alice, chatId, ORDER_RETRY_DRAFT_TEXT, recordedId)
            assertThat(dedupRetry.statusCode)
                .overridingErrorMessage(
                    "a retry of an already recorded clientMessageId must NEVER be refused by admission (FR-009 " +
                        "«ретрай принятого — 200, никогда 503/429»), got <%s>: %s",
                    dedupRetry.statusCode,
                    dedupRetry.body,
                ).isEqualTo(HttpStatus.OK)
            val dedupRow = bpObjectMapper.readTree(dedupRetry.body)
            assertThat(dedupRow["id"].asText())
                .overridingErrorMessage("the dedup retry must return the SAME stored record")
                .isEqualTo(recordedId.toString())
            assertThat(dedupRow["text"].asText())
                .overridingErrorMessage("the stored text must stay the FIRST send's text, never the retry draft")
                .isEqualTo(ORDER_RECORDED_TEXT)

            assertServerBusyShed(sendMessage(alice, chatId, ORDER_FRESH_MEMBER_TEXT))
            assertServerBusyShed(sendMessage(carol, chatId, ORDER_STRANGER_TEXT))
            assertServerBusyShed(sendMessage(bob, chatId, ORDER_BLANK_TEXT))
            return OrderingOutcome(sheds = split.completed.size, admitted = split.pending.size, parked = split.pending)
        } finally {
            stall.close()
        }
    }

    /**
     * AIMD warm-up/recovery pump: healthy fast sends until the gauge
     * reads the configured base (`delivery.backpressure.max-limit`) or
     * the deadline elapses. A tolerated 429 (the FR-011 drip math of the
     * two alternating users) does not fail the pump — only the gauge
     * convergence is asserted.
     */
    private fun pumpLimitToBase(
        users: List<MessagingUser>,
        chatId: UUID,
        base: Double,
        timeoutMillis: Long,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        var pump = 0
        while (true) {
            val current =
                admissionLimitGauge()
                    ?: throw AssertionError(
                        "the gauge webchat_backpressure_limit must be registered by the admission limiter " +
                            "(T039/T040) and converge to the base <${base.toInt()}> under a healthy send load",
                    )
            if (current > base - LIMIT_EPSILON) return
            if (System.nanoTime() > deadline || pump >= PUMP_CAP) {
                assertThat(current)
                    .overridingErrorMessage(
                        "the AIMD limit must converge to the base <%d> under a healthy send load, gauge reads <%s>",
                        base.toInt(),
                        current,
                    ).isGreaterThan(base - LIMIT_EPSILON)
                return
            }
            sendMessage(users[pump % users.size], chatId, "$PUMP_TEXT-${UUID.randomUUID()}")
            pump += 1
            Thread.sleep(PUMP_INTERVAL_MILLIS)
        }
    }

    /** Polls until the gauge falls strictly below [base] (the AIMD shrink after the spike). */
    private fun awaitLimitBelowBase(
        base: Double,
        timeoutMillis: Long,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (true) {
            val current =
                admissionLimitGauge()
                    ?: throw AssertionError(
                        "the gauge webchat_backpressure_limit must be registered by the admission limiter " +
                            "(T039/T040) and shrink below the base <${base.toInt()}> after the latency spike",
                    )
            if (current < base - LIMIT_EPSILON) return
            if (System.nanoTime() > deadline) {
                assertThat(current)
                    .overridingErrorMessage(
                        "AIMD must shrink the limit below the base <%d> after a ≥%d× latency spike (FR-013/SC-006), " +
                            "gauge still reads <%s>",
                        base.toInt(),
                        SPIKE_GRADIENT,
                        current,
                    ).isLessThan(base - LIMIT_EPSILON)
                return
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private fun shedWindow(): Duration = Duration.ofSeconds(SHED_WINDOW_SECONDS)

    private companion object {
        const val ORDER_RECORDED_TEXT = "the recorded row of the ordering scenario"
        const val ORDER_RETRY_DRAFT_TEXT = "a draft text the dedup retry must never overwrite with"
        const val ORDER_ALICE_DRAIN_PREFIX = "bp-order-alice-drain"
        const val ORDER_BOB_DRAIN_PREFIX = "bp-order-bob-drain"
        const val ORDER_BLOCKER_TEXT = "bp-order-blocker"
        const val ORDER_FRESH_MEMBER_TEXT = "fresh member send under saturation - must shed, not 429"
        const val ORDER_STRANGER_TEXT = "stranger send under saturation - must shed, not 403"
        const val ORDER_BLANK_TEXT = ""
        const val SPIKE_BLOCKER_TEXT = "bp-aimd-blocker"
        const val PUMP_TEXT = "bp-aimd-pump"

        /** FR-011: the per-user allowance the sheds must not touch. */
        const val FLOOD_WINDOW = 30

        const val SATURATION_SENDS = 6
        const val MIN_SHEDS_PER_ROUND = 1
        const val ORDER_PROBES = 3
        const val SPIKE_ROUNDS = 3

        /** 50ms baseline vs ~3s parked commits: the spike gradient magnitude. */
        const val SPIKE_GRADIENT = 60

        const val SHED_WINDOW_SECONDS = 2L
        const val SPIKE_HOLD_MILLIS = 1_000L
        const val PUMP_INTERVAL_MILLIS = 150L
        const val POLL_INTERVAL_MILLIS = 100L
        const val JOIN_TIMEOUT_SECONDS = 30L
        const val WARMUP_TIMEOUT_MILLIS = 25_000L
        const val SHRINK_TIMEOUT_MILLIS = 15_000L
        const val RECOVERY_TIMEOUT_MILLIS = 30_000L
        const val PUMP_CAP = 120
        const val NANOS_PER_MILLI = 1_000_000L

        /** Integral gauge values — half-a-step tolerance for the double reads. */
        const val LIMIT_EPSILON = 0.5
    }
}

/**
 * T038: the `delivery.backpressure.enabled=false` profile — the send
 * path must behave EXACTLY as before the feature (tasks.md T038 «при
 * delivery.backpressure.enabled=false путь отправки не меняется»):
 * nothing ever answers 503 (not even under the same stall+volley
 * saturation that sheds the enabled profile), the FR-004 dedup retry
 * still answers `200`, and FR-011 flood limiting stays the one and only
 * per-user send limiter.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["delivery.backpressure.enabled=false"],
)
class BackpressureDisabledIT : BackpressureSendSupport() {
    @Test
    fun `disabled admission leaves the send path unchanged`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val recordedId = UUID.randomUUID()
        assertThat(sendMessage(alice, chatId, DISABLED_RECORDED_TEXT, recordedId).statusCode)
            .isEqualTo(HttpStatus.CREATED)
        val retry = sendMessage(alice, chatId, DISABLED_RETRY_DRAFT_TEXT, recordedId)
        assertThat(retry.statusCode)
            .overridingErrorMessage(
                "with admission disabled the FR-004 dedup retry must still answer 200, got <%s>: %s",
                retry.statusCode,
                retry.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(bpObjectMapper.readTree(retry.body)["text"].asText())
            .isEqualTo(DISABLED_RECORDED_TEXT)

        val stall = ChatRowStall(chatId)
        val volley = (1..DISABLED_VOLLEY).map { fireAsyncSend(bob, chatId, "$DISABLED_VOLLEY_TEXT-$it") }
        val earlyAnswers =
            try {
                splitAtDeadline(volley, Duration.ofSeconds(SHED_WINDOW_SECONDS)).completed
            } finally {
                stall.close()
            }
        assertThat(earlyAnswers)
            .overridingErrorMessage(
                "with delivery.backpressure.enabled=false no send may be refused early (nothing sheds), got %s",
                earlyAnswers.map { it.statusCode },
            ).isEmpty()
        val statuses = volley.map { future -> future.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).statusCode }
        assertThat(statuses)
            .overridingErrorMessage(
                "with admission disabled the whole volley must commit with 201 - never 503/429, got %s",
                statuses,
            ).containsOnly(HttpStatus.CREATED)

        val drain = drainFloodBucket(alice, chatId, DISABLED_DRAIN_PREFIX, FLOOD_WINDOW - 1)
        assertThat(drain.rejection.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)

        assertThat(countMessageRows(chatId))
            .isEqualTo(1 + DISABLED_VOLLEY + drain.accepted)
    }

    private companion object {
        const val DISABLED_RECORDED_TEXT = "the recorded row of the disabled-profile scenario"
        const val DISABLED_RETRY_DRAFT_TEXT = "a draft the dedup retry must never overwrite with"
        const val DISABLED_VOLLEY_TEXT = "bp-disabled-volley"
        const val DISABLED_DRAIN_PREFIX = "bp-disabled-drain"

        const val DISABLED_VOLLEY = 6
        const val FLOOD_WINDOW = 30

        const val SHED_WINDOW_SECONDS = 2L
        const val JOIN_TIMEOUT_SECONDS = 30L
    }
}
