package webchat.backend.chats

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
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.sync.SyncTestSupport
import java.util.UUID

/**
 * T009 (tasks.md Phase 3, US1): the №15 ascending extension of
 * api-contract.md §2 — the exclusive `after` cursor of the catch-up
 * pagination (sync-protocol.md §4, the cycle-B fetch of T019):
 *
 *  * pages `(after, after+limit]` by `seq ASC` — the 120-message backlog
 *    walks 50 (default) - 50 - 20 from `after=0` up to the head, and the
 *    walk past the head is a CLEAN empty page — `200`, `messages: []`,
 *    no `nextAfter`, no error (the sync loop stops on the missing
 *    cursor, never on an error);
 *  * `nextAfter` is the seq of the LAST row of its page (the exclusive
 *    cursor of the next newer page) and is ABSENT on the short tail
 *    page — while the after-mode answer never carries `nextBefore`
 *    (the DESC cursor of 004);
 *  * `limit` keeps the contract bounds 1..50 inside the after mode too
 *    (`400 limit_out_of_range`, FR-008) and windows the rows exactly;
 *  * `after` together with `before` is the `400 mixed_cursors`
 *    problem+json refusal (`errors: {after: [mixed_cursors]}`) — one
 *    page may never mix the two walk directions;
 *  * visibility stays per-user: rows at or below the caller's
 *    `deleted_up_to_seq` (the №14 end state of 004) never return in the
 *    after mode — the deleting participant pages over exactly the
 *    visible suffix from ANY cursor below the watermark, while the peer
 *    still pages over the WHOLE dialog (deletion never resurrects);
 *  * the DESC `before` regime of 004 does not regress beside the new
 *    mode: the default page and the `before` walk answer the same
 *    `seq DESC` rows with `nextBefore` and WITHOUT `nextAfter`.
 *
 * NOTE (TDD, constitution VI): written BEFORE T015 lands — until the
 * `after` parameter exists in [webchat.backend.chats.api.MessageController],
 * Spring ignores it, so every after-mode method below fails against the
 * default DESC page (RED) by design; only the 004 guard method is green
 * now. The backlog is seeded straight into the real tables (the T002
 * [seedChatBacklog] fixture — the exact №16 end state) because 120 real
 * sends would cross the FR-011 flood limit while the send path itself is
 * already locked by the 004 ITs.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №15 reads), Testcontainers PG 17 + Redis 7 and real
 * HTTP — no mocks; every method registers its own pair, so no account
 * rows or rate-limit buckets leak between methods.
 */
@Suppress("TooManyFunctions") // T009: one helper per §2 rule of api-contract.md
class AscendingHistoryIT(
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val mixedRestTemplate: TestRestTemplate,
) : SyncTestSupport() {
    /**
     * US1-2/US1-3: the 120-message backlog pages 50-50-20 ASCENDING from
     * `after=0` through the exclusive `nextAfter` cursor up to the very
     * head — every page strictly `seq ASC`, no duplicates, `nextAfter` =
     * the last row's seq — and the walk past the head is a clean empty
     * page. The full walk rebuilds the dialog exactly once in the send
     * order.
     */
    @Test
    fun `a 120-message backlog pages 50-50-20 ascending from after 0 and ends with a clean empty boundary page`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)
        val seeded = seedAlternatingDialog(alice, bob, chatId, LONG_HISTORY_SIZE)

        val first = loadAfterPage(bob, chatId, ZERO_CURSOR)
        assertThat(first.messages)
            .overridingErrorMessage(
                "the first ascending page must be the contract page size %d (default limit), got %d",
                CONTRACT_PAGE_SIZE,
                first.messages.size,
            ).hasSize(CONTRACT_PAGE_SIZE)
        assertThat(idsOf(first.messages))
            .overridingErrorMessage(
                "the first after-page must be the OLDEST %d messages in send order (seq ASC), got: %s",
                CONTRACT_PAGE_SIZE,
                idsOf(first.messages),
            ).containsExactlyElementsOf(seededIds(seeded, 0 until CONTRACT_PAGE_SIZE))
        assertThat(first.nextAfter)
            .overridingErrorMessage(
                "nextAfter must be the seq of the LAST row of the page (the exclusive cursor), got <%s>",
                first.nextAfter,
            ).isEqualTo(first.messages.last()["seq"].asLong())
        assertThat(first.nextAfter).isEqualTo(seeded[CONTRACT_PAGE_SIZE - 1].seq)
        assertThat(first.nextBefore)
            .overridingErrorMessage("an after-mode page must never carry the DESC cursor nextBefore")
            .isNull()

        val second = loadAfterPage(bob, chatId, first.nextAfter!!)
        val secondPageIds = seededIds(seeded, CONTRACT_PAGE_SIZE until 2 * CONTRACT_PAGE_SIZE)
        assertThat(idsOf(second.messages))
            .overridingErrorMessage(
                "the second after-page must continue with the next newer %d messages",
                CONTRACT_PAGE_SIZE,
            ).containsExactlyElementsOf(secondPageIds)
        assertThat(second.nextAfter).isEqualTo(seeded[2 * CONTRACT_PAGE_SIZE - 1].seq)

        val tail = loadAfterPage(bob, chatId, second.nextAfter!!)
        assertThat(idsOf(tail.messages))
            .overridingErrorMessage(
                "the third page must be the remaining %d newest messages ascending, got %d: %s",
                LONG_TAIL_SIZE,
                tail.messages.size,
                idsOf(tail.messages),
            ).containsExactlyElementsOf(seededIds(seeded, 2 * CONTRACT_PAGE_SIZE until LONG_HISTORY_SIZE))
        assertThat(tail.nextAfter)
            .overridingErrorMessage(
                "a page SHORTER than the limit is the boundary — nextAfter must be absent, got <%s>",
                tail.nextAfter,
            ).isNull()

        assertCleanEmptyBoundaryPage(bob, chatId, seeded.last().seq)

        val walked = listOf(first, second, tail).flatMap { it.messages }
        assertThat(walked.map { it["id"].asText() })
            .overridingErrorMessage(
                "the ascending walk must rebuild the WHOLE dialog exactly once, in send order",
            ).containsExactlyElementsOf(seeded.map { it.id.toString() })
    }

    /**
     * FR-008 inside the after mode: the window is exactly the next
     * `limit` visible rows STRICTLY ABOVE the cursor — `(after,
     * after+limit]` — with `nextAfter` at its last row, `limit=1` is the
     * contract floor; the bounds 0 and 51 stay the `400
     * limit_out_of_range` refusal of 004.
     */
    @Test
    fun `after with limit serves the exact exclusive window and the limit bounds still apply`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)
        val seeded = seedAlternatingDialog(alice, bob, chatId, SMALL_HISTORY_SIZE)

        val window = loadAfterPage(bob, chatId, seeded[WINDOW_AFTER_INDEX].seq, WINDOW_LIMIT)
        assertThat(idsOf(window.messages))
            .overridingErrorMessage(
                "the after-window must be exactly the %d rows with seq > after (strictly exclusive), got: %s",
                WINDOW_LIMIT,
                idsOf(window.messages),
            ).containsExactlyElementsOf(seededIds(seeded, WINDOW_AFTER_INDEX + 1..WINDOW_AFTER_INDEX + WINDOW_LIMIT))
        assertThat(window.nextAfter).isEqualTo(seeded[WINDOW_AFTER_INDEX + WINDOW_LIMIT].seq)

        val single = loadAfterPage(bob, chatId, seeded[0].seq, SINGLE_LIMIT)
        assertThat(single.messages)
            .overridingErrorMessage("limit=1 is the contract floor and must be accepted in the after mode")
            .hasSize(SINGLE_LIMIT)
        assertThat(single.nextAfter)
            .overridingErrorMessage("a full limit=1 page must expose the cursor to the next newer row")
            .isEqualTo(seeded[1].seq)

        assertLimitOutOfRange(listMessagesAfter(bob, chatId, ZERO_CURSOR, LIMIT_TOO_LOW))
        assertLimitOutOfRange(listMessagesAfter(bob, chatId, ZERO_CURSOR, LIMIT_TOO_HIGH))
    }

    /** US1-3 boundary: a dialog without messages answers a clean empty page in the after mode — no error, no cursor. */
    @Test
    fun `an empty dialog reads as a clean empty page in after mode`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)

        val page = loadAfterPage(bob, chatId, ZERO_CURSOR)

        assertThat(page.messages)
            .overridingErrorMessage("an empty dialog must answer an empty after-page without errors")
            .isEmpty()
        assertThat(page.nextAfter)
            .overridingErrorMessage("an empty dialog must expose no nextAfter")
            .isNull()

        val explicitLimit = loadAfterPage(bob, chatId, ZERO_CURSOR, LIMIT_CEILING)
        assertThat(explicitLimit.messages).isEmpty()
        assertThat(explicitLimit.nextAfter).isNull()
    }

    /**
     * §2 of api-contract.md: the two cursors are mutually exclusive —
     * `after` together with `before` is the `400` problem+json refusal
     * `errors: {after: [mixed_cursors]}`, never a silently-prioritized
     * direction (an ambiguous walk could skip or duplicate rows).
     */
    @Test
    fun `after together with before is refused with 400 mixed_cursors`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)
        val seeded = seedAlternatingDialog(alice, bob, chatId, MIX_HISTORY_SIZE)

        val response = listMessagesMixed(bob, chatId, after = seeded[0].seq, before = seeded[1].seq)

        assertProblem(response, HttpStatus.BAD_REQUEST, AFTER_FIELD, MIXED_CURSORS)
    }

    /**
     * The 004 visibility rule carried into the after mode (FR-021/FR-007):
     * rows at or below the caller's `deleted_up_to_seq` are inaccessible —
     * the deleting participant's ascending walk from ANY cursor below the
     * watermark starts exactly at the visible suffix and ends cleanly,
     * while the peer still walks over the WHOLE dialog (a per-user
     * deletion never resurrects through the catch-up fetch).
     */
    @Test
    fun `deletion watermark keeps the ascending pages above it only for the deleting participant`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)
        val seeded = seedAlternatingDialog(alice, bob, chatId, LONG_HISTORY_SIZE)
        val watermarkSeq = seeded[DELETION_HIDDEN_EDGE_INDEX].seq
        setDeletionWatermark(alice.id, chatId, watermarkSeq)

        val fromZero = walkAllAfterPages(alice, chatId, ZERO_CURSOR)
        assertThat(fromZero.map { it["id"].asText() })
            .overridingErrorMessage(
                "the deleting participant must walk over exactly the visible suffix of %d messages, got %d",
                DELETION_VISIBLE_TAIL,
                fromZero.size,
            ).containsExactlyElementsOf(seededIds(seeded, DELETION_VISIBLE_START_INDEX until LONG_HISTORY_SIZE))

        val fromWatermark = loadAfterPage(alice, chatId, watermarkSeq)
        assertThat(idsOf(fromWatermark.messages))
            .overridingErrorMessage(
                "a cursor AT the watermark must start at the same visible suffix — rows at or below it never return",
                idsOf(fromWatermark.messages),
            ).containsExactlyElementsOf(seededIds(seeded, DELETION_VISIBLE_START_INDEX until LONG_HISTORY_SIZE))
        assertThat(fromWatermark.nextAfter)
            .overridingErrorMessage("the %d-row suffix is one short page — no cursor", DELETION_VISIBLE_TAIL)
            .isNull()

        val forBob = walkAllAfterPages(bob, chatId, ZERO_CURSOR)
        assertThat(forBob.map { it["id"].asText() })
            .overridingErrorMessage(
                "the deletion is per-user: the peer must still walk over the WHOLE dialog, got %d",
                forBob.size,
            ).containsExactlyElementsOf(seeded.map { it.id.toString() })
    }

    /**
     * The 004 guard (backward compatibility of the additive 0.5.0
     * change): beside the new mode the DESC regime is untouched — the
     * default page and the `before` walk answer the same `seq DESC` rows
     * with `nextBefore` and WITHOUT `nextAfter` (the contract keeps the
     * cursors mode-specific).
     */
    @Test
    fun `the desc before mode of 004 does not regress beside the new after mode`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureDialog(alice, bob)
        val seeded = seedAlternatingDialog(alice, bob, chatId, LONG_HISTORY_SIZE)

        val first = loadDescPage(bob, chatId)
        val firstPageIds =
            seededIds(seeded, LONG_HISTORY_SIZE - CONTRACT_PAGE_SIZE until LONG_HISTORY_SIZE)
                .asReversed()
        assertThat(first.messages)
            .overridingErrorMessage("the default №15 page must stay the contract size %d", CONTRACT_PAGE_SIZE)
            .hasSize(CONTRACT_PAGE_SIZE)
        assertThat(idsOf(first.messages))
            .overridingErrorMessage("the default page must stay the LAST %d messages seq DESC", CONTRACT_PAGE_SIZE)
            .containsExactlyElementsOf(firstPageIds)
        assertThat(first.nextBefore).isEqualTo(seeded[LONG_HISTORY_SIZE - CONTRACT_PAGE_SIZE].seq)
        assertThat(first.nextAfter)
            .overridingErrorMessage(
                "the DESC regime must not expose the ascending cursor nextAfter (0.5.0 keeps it mode-specific)",
            ).isNull()

        val second = loadDescPage(bob, chatId, before = first.nextBefore)
        val secondPageIds =
            seededIds(seeded, LONG_HISTORY_SIZE - 2 * CONTRACT_PAGE_SIZE until LONG_HISTORY_SIZE - CONTRACT_PAGE_SIZE)
                .asReversed()
        assertThat(idsOf(second.messages))
            .overridingErrorMessage("the before-walk must continue with the next older %d messages", CONTRACT_PAGE_SIZE)
            .containsExactlyElementsOf(secondPageIds)
        assertThat(second.nextBefore).isEqualTo(seeded[LONG_HISTORY_SIZE - 2 * CONTRACT_PAGE_SIZE].seq)
        assertThat(second.nextAfter).isNull()
    }

    /** One №15 after-mode answer projected for assertions: the rows plus the parsed exclusive cursor. */
    private data class AscendingPage(
        val messages: List<JsonNode>,
        val nextAfter: Long?,
        val nextBefore: Long?,
    )

    /** One №15 DESC answer of 004 projected the same way. */
    private data class DescPage(
        val messages: List<JsonNode>,
        val nextBefore: Long?,
        val nextAfter: Long?,
    )

    /**
     * №15 read with `after` and the built-in 200 gate — every after-page
     * of this IT must succeed and be strictly seq ASC.
     */
    private fun loadAfterPage(
        user: MessagingUser,
        chatId: UUID,
        after: Long,
        limit: Int? = null,
    ): AscendingPage {
        val response = listMessagesAfter(user, chatId, after, limit)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "history №15 after-mode must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        val messages = body["messages"].toList()
        val seqs = messages.map { it["seq"].asLong() }
        assertThat(seqs)
            .overridingErrorMessage("every after-page must be strictly seq ASC (0.5.0 §2), got: %s", seqs)
            .isSorted()
            .doesNotHaveDuplicates()
        return AscendingPage(
            messages = messages,
            nextAfter = cursorOf(body, NEXT_AFTER_FIELD),
            nextBefore = cursorOf(body, NEXT_BEFORE_FIELD),
        )
    }

    /** №15 DESC read (004 regime) with the same gates — strictly seq DESC and the mode-specific cursor set. */
    private fun loadDescPage(
        user: MessagingUser,
        chatId: UUID,
        before: Long? = null,
    ): DescPage {
        val response = listMessages(user, chatId, before)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "history №15 DESC must answer 200 (the 004 regime must not regress), got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        val messages = body["messages"].toList()
        val seqs = messages.map { it["seq"].asLong() }
        assertThat(seqs)
            .overridingErrorMessage("every DESC page must stay strictly seq DESC (FR-008), got: %s", seqs)
            .isSortedAccordingTo(SEQ_DESCENDING)
            .doesNotHaveDuplicates()
        return DescPage(
            messages = messages,
            nextBefore = cursorOf(body, NEXT_BEFORE_FIELD),
            nextAfter = cursorOf(body, NEXT_AFTER_FIELD),
        )
    }

    /**
     * The exhaustion boundary of §2: a cursor AT the head must answer a
     * CLEAN empty page — `200`, no rows, no `nextAfter`, no error (the
     * sync loop stops on the missing cursor, never on an error).
     */
    private fun assertCleanEmptyBoundaryPage(
        user: MessagingUser,
        chatId: UUID,
        headSeq: Long,
    ) {
        val boundary = loadAfterPage(user, chatId, headSeq)
        assertThat(boundary.messages)
            .overridingErrorMessage(
                "a request past the head must be a CLEAN empty page (no error), got: %s",
                idsOf(boundary.messages),
            ).isEmpty()
        assertThat(boundary.nextAfter)
            .overridingErrorMessage("the empty boundary page must carry no nextAfter")
            .isNull()
    }

    /**
     * Walks the whole ascending history from [after] through the
     * exclusive `nextAfter` cursor (default 50-sized pages until the
     * cursor disappears) and returns the rows in walk direction —
     * ascending `seq`.
     */
    private fun walkAllAfterPages(
        user: MessagingUser,
        chatId: UUID,
        after: Long,
    ): List<JsonNode> {
        val rows = mutableListOf<JsonNode>()
        var cursor = after
        repeat(MAX_PAGES) {
            val page = loadAfterPage(user, chatId, cursor)
            if (page.messages.isEmpty()) return rows
            rows.addAll(page.messages)
            val next = page.nextAfter ?: return rows
            cursor = next
        }
        throw AssertionError("ascending history pagination did not exhaust within $MAX_PAGES pages")
    }

    /**
     * The raw №15 request carrying BOTH cursors — the `mixed_cursors`
     * refusal needs the two params side by side (neither fixture helper
     * of 004/005 builds this combination on purpose).
     */
    private fun listMessagesMixed(
        user: MessagingUser,
        chatId: UUID,
        after: Long,
        before: Long,
    ): ResponseEntity<String> {
        val path =
            UriComponentsBuilder
                .fromPath("/api/v1/chats/$chatId/messages")
                .queryParam("after", after)
                .queryParam("before", before)
                .build()
                .toUriString()
        return mixedRestTemplate.exchange(
            path,
            HttpMethod.GET,
            HttpEntity<Unit>(null, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )
    }

    /**
     * Seeds [count] alternating messages through the T002 fixture (the
     * exact №16 end state) — ids, seqs and texts map to indices verbatim.
     */
    private fun seedAlternatingDialog(
        senderA: MessagingUser,
        senderB: MessagingUser,
        chatId: UUID,
        count: Int,
    ): List<SeededMessage> =
        seedChatBacklog(
            chatId,
            count,
            { index -> if (index % EVEN == 0) senderA else senderB },
            SEED_TEXT_PREFIX,
        )

    /** The contract cursor may be omitted or rendered null — both mean "absent" (the NON_NULL convention of 004). */
    private fun cursorOf(
        body: JsonNode,
        field: String,
    ): Long? {
        val node = body.get(field) ?: return null
        return if (node.isNull) null else node.asLong()
    }

    private fun idsOf(messages: List<JsonNode>): List<String> = messages.map { it["id"].asText() }

    private fun seededIds(
        seeded: List<SeededMessage>,
        range: IntRange,
    ): List<String> = seeded.slice(range).map { it.id.toString() }

    /**
     * The exact №15 400 of FR-008 — problem+json with
     * `errors.limit=[limit_out_of_range]`, unchanged inside the after mode.
     */
    private fun assertLimitOutOfRange(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "limit outside 1..50 must be refused with 400 in the after mode too (FR-008), got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the limit refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(LIMIT_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                LIMIT_FIELD,
                LIMIT_OUT_OF_RANGE,
                codes,
                response.body,
            ).containsExactly(LIMIT_OUT_OF_RANGE)
    }

    /** The exact §2 refusal: problem+json with `errors.[field] = [code]` verbatim. */
    private fun assertProblem(
        response: ResponseEntity<String>,
        expected: HttpStatus,
        field: String,
        code: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the mixed-cursor request must be refused with %s, got <%s>: %s",
                expected,
                response.statusCode,
                response.body,
            ).isEqualTo(expected)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the refusal must be RFC 9457 application/problem+json")
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

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"

        const val LIMIT_FIELD = "limit"
        const val AFTER_FIELD = "after"
        const val NEXT_BEFORE_FIELD = "nextBefore"
        const val NEXT_AFTER_FIELD = "nextAfter"

        const val LIMIT_OUT_OF_RANGE = "limit_out_of_range"
        const val MIXED_CURSORS = "mixed_cursors"

        const val SEED_TEXT_PREFIX = "ascending"

        /** The US1 catch-up scale: a 120-message offline backlog (HistoryIT precedent). */
        const val LONG_HISTORY_SIZE = 120

        /** FR-008/api-contract.md №15: the contract-fixed page and limit ceiling. */
        const val CONTRACT_PAGE_SIZE = 50
        const val LONG_TAIL_SIZE = LONG_HISTORY_SIZE - 2 * CONTRACT_PAGE_SIZE

        /** A light dialog for the window checks — the window, not the scale, is the subject. */
        const val SMALL_HISTORY_SIZE = 10
        const val MIX_HISTORY_SIZE = 2

        const val WINDOW_AFTER_INDEX = 1
        const val WINDOW_LIMIT = 3
        const val SINGLE_LIMIT = 1
        const val LIMIT_CEILING = 50
        const val LIMIT_TOO_LOW = 0
        const val LIMIT_TOO_HIGH = 51

        /** The ascending cursor floor of the 0.5.0 contract (`after: int64 ≥ 0`) — a full history walk. */
        const val ZERO_CURSOR = 0L
        const val MAX_PAGES = 10
        const val EVEN = 2

        /**
         * FR-021 fixture (HistoryIT precedent): the watermark hides
         * messages 0..74 — the visible suffix is the last 45 rows (a
         * NON-multiple of 50, so the first page is short and the cursor
         * is absent).
         */
        const val DELETION_VISIBLE_TAIL = 45
        const val DELETION_HIDDEN_EDGE_INDEX = LONG_HISTORY_SIZE - DELETION_VISIBLE_TAIL - 1
        const val DELETION_VISIBLE_START_INDEX = LONG_HISTORY_SIZE - DELETION_VISIBLE_TAIL

        /** FR-008 page order of the 004 regime: strictly descending unique seqs. */
        val SEQ_DESCENDING: Comparator<Long> = Comparator.reverseOrder()
    }
}
