package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * T030 (tasks.md Phase 4, US2): the FR-011 send flood limit on contract №16
 * `POST /chats/{chatId}/messages` — 30 new messages per minute per user
 * (`rl:user:msgsend:{userId}`, research.md 004 §7):
 *
 *  * a burst of fresh ids past the 30-message allowance is refused with
 *    `429` problem+json (`errors.text=[flood_limit]`) + `Retry-After`
 *    (seconds to the next available token) — and NOTHING is written: the
 *    rejected draft has no row, №15 history holds exactly the accepted
 *    records;
 *  * a retry of an ALREADY RECORDED `clientMessageId` is never flood-penalized
 *    (FR-004/FR-011: the dedup lookup runs BEFORE the bucket) — with the
 *    bucket drained it still answers `200` with the same stored row, while a
 *    fresh id in the same drained instant is still `429`;
 *  * after the `Retry-After` window the very next fresh send is created with
 *    `201` and joins the dialog history as the last record.
 *
 * Test-first for T032 (the bucket itself) and T031 (the dedup fast-path
 * ordering): against the US1-only send path every method here fails — the
 * burst drains no bucket, so no `429` ever arrives. The greedy 30/60s drip
 * may re-grant a token while the burst is still running, so the first
 * rejection is asserted to land at message 31+ and within the deterministic
 * refill bound (`30 + elapsed/2`), never at a fixed attempt index.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №16 send, №15 history), Testcontainers PG+Redis, real
 * HTTP, no mocks; every method registers its own pair, so the per-user
 * Redis buckets never leak between methods.
 */
class FloodLimitIT(
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : MessagingTestSupport() {
    /**
     * FR-011 head: the allowance of 30 is fully spendable in one burst; the
     * next fresh message is the `429 flood_limit` + `Retry-After` refusal
     * with no DB record for the refused draft.
     */
    @Test
    fun `message past the thirty-per-minute allowance is refused with 429 Retry-After and writes nothing`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val burst = sendUntilFlood(alice, chatId, WINDOW_TEXT_PREFIX)

        assertFloodRejection(burst.rejection)

        assertThat(burst.accepted.map { it.id.toString() })
            .overridingErrorMessage(
                "the full 30-message allowance of FR-011 must be spendable in one burst before any 429, " +
                    "got %d accepted: %s",
                burst.accepted.size,
                burst.accepted.map { it.id },
            ).hasSizeGreaterThanOrEqualTo(WINDOW_MESSAGES)

        val history = historyAscending(bob, chatId)
        assertThat(history.map { it["id"].asText() })
            .overridingErrorMessage(
                "the refused send must leave the dialog untouched — exactly the accepted records, in seq order",
            ).containsExactlyElementsOf(burst.accepted.map { it.id.toString() })
        assertThat(history.map { it["text"].asText() })
            .overridingErrorMessage("the refused draft text must appear nowhere in the history")
            .doesNotContain(refusedText(WINDOW_TEXT_PREFIX, burst.accepted.size + 1))
        assertThat(countMessageRows(chatId))
            .overridingErrorMessage(
                "the 429 refusal must write no row: messages count <%d> must equal the accepted sends <%d>",
                countMessageRows(chatId),
                burst.accepted.size,
            ).isEqualTo(burst.accepted.size)
    }

    /**
     * FR-011 dedup carve-out (research.md 004 §7 ordering «дедуп → флуд»):
     * with the bucket drained, a retry of a recorded `clientMessageId` still
     * answers `200` with the SAME row — while a fresh id in the same drained
     * instant is still refused, proving the `200` was the dedup path and not
     * a refilled token.
     */
    @Test
    fun `retry of an already recorded message is not flood-penalized while fresh sends still are`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val burst = sendUntilFlood(alice, chatId, DEDUP_TEXT_PREFIX)
        assertFloodRejection(burst.rejection)
        val recorded = burst.accepted.first()

        val retry = sendMessage(alice, chatId, DEDUP_RETRY_TEXT, recorded.id)
        assertThat(retry.statusCode)
            .overridingErrorMessage(
                "a retry of a recorded clientMessageId must NOT be flood-penalized (FR-011: dedup before the " +
                    "bucket) — expected 200 with the drained bucket, got <%s>: %s",
                retry.statusCode,
                retry.body,
            ).isEqualTo(HttpStatus.OK)
        assertSameRecord(retry, recorded.response)

        val fresh = sendMessage(alice, chatId, DEDUP_FRESH_TEXT)
        assertThat(fresh.statusCode)
            .overridingErrorMessage(
                "a FRESH id in the same drained instant must still be refused 429 — the retry above went through " +
                    "the dedup path, not a refilled token, got <%s>: %s",
                fresh.statusCode,
                fresh.body,
            ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertFloodRejection(fresh)

        assertThat(countMessageRows(chatId))
            .overridingErrorMessage("neither the dedup retry nor the fresh refusal may add a row")
            .isEqualTo(burst.accepted.size)
        val history = historyAscending(bob, chatId)
        assertThat(history.map { it["id"].asText() })
            .containsExactlyElementsOf(burst.accepted.map { it.id.toString() })
        assertThat(history.map { it["text"].asText() })
            .overridingErrorMessage("the stored text must stay the FIRST send's text, never the retry draft")
            .doesNotContain(DEDUP_RETRY_TEXT, DEDUP_FRESH_TEXT)
    }

    /**
     * FR-011 recovery: `Retry-After` is the ceiling of the refill wait —
     * after it elapses the very next fresh send is created with `201` and
     * becomes the last record of the dialog for both participants.
     */
    @Test
    fun `fresh send after the limit window recovers with 201`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val burst = sendUntilFlood(alice, chatId, RECOVERY_TEXT_PREFIX)
        val retryAfterSeconds = assertFloodRejection(burst.rejection)

        Thread.sleep(retryAfterSeconds * MILLIS_PER_SECOND + RECOVERY_MARGIN_MILLIS)

        val afterWindowId = UUID.randomUUID()
        val afterWindow = sendMessage(alice, chatId, AFTER_WINDOW_TEXT, afterWindowId)
        assertThat(afterWindow.statusCode)
            .overridingErrorMessage(
                "a fresh send after the Retry-After window must be created with 201 (FR-011), got <%s>: %s",
                afterWindow.statusCode,
                afterWindow.body,
            ).isEqualTo(HttpStatus.CREATED)
        assertThat(objectMapper.readTree(afterWindow.body)["id"].asText())
            .isEqualTo(afterWindowId.toString())

        val history = historyAscending(bob, chatId)
        assertThat(history)
            .overridingErrorMessage(
                "the recovered send must be exactly one new record on top of the burst, got %d vs %d+1",
                history.size,
                burst.accepted.size,
            ).hasSize(burst.accepted.size + 1)
        assertThat(history.last()["id"].asText())
            .overridingErrorMessage("the recovered send must be the LAST record in seq order")
            .isEqualTo(afterWindowId.toString())
    }

    /**
     * One accepted №16 send of the burst: the fresh `clientMessageId`, its
     * text and the raw `201` answer (the row the FR-004 dedup assertions
     * compare against).
     */
    private data class AcceptedSend(
        val id: UUID,
        val response: ResponseEntity<String>,
    )

    /** A drain-to-429 burst: every accepted send plus the first refusal. */
    private data class FloodedBurst(
        val accepted: List<AcceptedSend>,
        val rejection: ResponseEntity<String>,
    )

    /**
     * Sends fresh-id messages back to back until the first non-`201` answer
     * and returns everything accepted before it. The first non-`201` IS the
     * expected `429` (asserted by the callers through
     * [assertFloodRejection]); the greedy 30/60s drip may re-grant tokens
     * while the burst runs, so the accepted count is bounded by
     * `30 + elapsedSeconds/2 + 1` — never a fixed index — and must stay at
     * or above the full 30-message allowance (FR-011 is not stricter than
     * the contract).
     */
    private fun sendUntilFlood(
        user: MessagingUser,
        chatId: UUID,
        textPrefix: String,
    ): FloodedBurst {
        val accepted = mutableListOf<AcceptedSend>()
        var rejection: ResponseEntity<String>? = null
        val startedAt = System.nanoTime()
        var attempt = 0
        while (rejection == null && attempt < BURST_ATTEMPT_CAP) {
            attempt += 1
            val response = sendMessage(user, chatId, refusedText(textPrefix, attempt))
            if (response.statusCode == HttpStatus.CREATED) {
                accepted += AcceptedSend(UUID.fromString(objectMapper.readTree(response.body)["id"].asText()), response)
            } else {
                rejection = response
            }
        }
        val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        assertThat(rejection)
            .overridingErrorMessage(
                "the 30-messages-per-minute bucket (FR-011) must refuse the burst of %d fresh sends, " +
                    "but every attempt answered 201 — the flood limit is not enforced on №16",
                BURST_ATTEMPT_CAP,
            ).isNotNull
        val dripBound = WINDOW_MESSAGES.toLong() + elapsedSeconds / 2 + DRIP_SLACK
        assertThat(accepted.size.toLong())
            .overridingErrorMessage(
                "the first refusal must land at message 31+ of the window and within the greedy-drip bound " +
                    "30+elapsed/2+1 (accepted=<%d>, elapsed=<%ds>, bound=<%d>)",
                accepted.size,
                elapsedSeconds,
                dripBound,
            ).isBetween(WINDOW_MESSAGES.toLong(), dripBound)
        return FloodedBurst(accepted, rejection!!)
    }

    /**
     * The exact №16 429 of api-contract.md: `429` + problem+json with
     * `errors.text=[flood_limit]` and an integral `Retry-After` of at least
     * 1 second (openapi №16 `Retry-After` minimum: 1). Returns the
     * advertised wait in seconds for the window-recovery assertions.
     */
    private fun assertFloodRejection(rejection: ResponseEntity<String>): Long {
        assertThat(rejection.statusCode)
            .overridingErrorMessage(
                "the flood refusal must be 429 (FR-011), got <%s>: %s",
                rejection.statusCode,
                rejection.body,
            ).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejection.headers.contentType?.toString())
            .overridingErrorMessage("the flood refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val retryAfterHeader = rejection.headers.getFirst(HttpHeaders.RETRY_AFTER)
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()
        assertThat(retryAfterSeconds)
            .overridingErrorMessage(
                "Retry-After must be integral seconds ≥ 1 (openapi №16), got <%s>",
                retryAfterHeader,
            ).isNotNull
        assertThat(retryAfterSeconds!!)
            .overridingErrorMessage(
                "Retry-After must be within one refill window (≥1s, ≤60s), got <%s>",
                retryAfterHeader,
            ).isBetween(RETRY_AFTER_FLOOR_SECONDS, RETRY_AFTER_CEILING_SECONDS)
        val problem = objectMapper.readTree(rejection.body)
        val codes = problem["errors"]?.get(TEXT_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                TEXT_FIELD,
                FLOOD_LIMIT,
                codes,
                rejection.body,
            ).containsExactly(FLOOD_LIMIT)
        return retryAfterSeconds
    }

    /** №15 read projected to ascending `seq` (the dialog reading direction). */
    private fun historyAscending(
        user: MessagingUser,
        chatId: UUID,
    ): List<JsonNode> {
        val response = listMessages(user, chatId)
        assertThat(response.statusCode)
            .overridingErrorMessage("history №15 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["messages"].toList().reversed()
    }

    /** Two №16 answers must be the very same stored row — field by field. */
    private fun assertSameRecord(
        candidate: ResponseEntity<String>,
        original: ResponseEntity<String>,
    ) {
        assertThat(objectMapper.readTree(candidate.body))
            .overridingErrorMessage(
                "the deduplicated answer must be the SAME stored record, got <%s> vs the original <%s>",
                candidate.body,
                original.body,
            ).isEqualTo(objectMapper.readTree(original.body))
    }

    /** The «записи в БД нет» leg: the raw row count behind the dialog. */
    private fun countMessageRows(chatId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages WHERE chat_id = ?", Int::class.java, chatId) ?: 0

    private fun refusedText(
        prefix: String,
        attempt: Int,
    ): String = "$prefix-$attempt"

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val TEXT_FIELD = "text"
        const val FLOOD_LIMIT = "flood_limit"

        const val WINDOW_TEXT_PREFIX = "flood-window"
        const val DEDUP_TEXT_PREFIX = "flood-dedup"
        const val DEDUP_RETRY_TEXT = "a drained bucket must still return the recorded row"
        const val DEDUP_FRESH_TEXT = "a fresh id in the same drained instant"
        const val RECOVERY_TEXT_PREFIX = "flood-recovery"
        const val AFTER_WINDOW_TEXT = "sent after the Retry-After window"

        /** FR-011/api-contract.md №16: 30 new messages per minute per user. */
        const val WINDOW_MESSAGES = 30

        /** Burst headroom for the greedy-drip tokens re-granted mid-run. */
        const val BURST_ATTEMPT_CAP = 40
        const val DRIP_SLACK = 1L

        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1000L

        /** Test-execution margin over the ceiling-style Retry-After wait. */
        const val RECOVERY_MARGIN_MILLIS = 750L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
