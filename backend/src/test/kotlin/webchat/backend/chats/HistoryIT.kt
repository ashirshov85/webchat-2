package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * T038 (tasks.md Phase 5, US3): the №15 history read of a long dialog
 * (api-contract.md №15, FR-008, US3-1..US3-4, SC-004):
 *
 *  * a 120-message history pages 50-50-20 by the EXCLUSIVE `before`
 *    cursor down to the very first message — the default page (no
 *    `limit`) is the contract-fixed 50 (`chats.message.page-size`,
 *    FR-008), every page arrives `seq DESC`, `nextBefore` is the oldest
 *    seq of its page and is ABSENT on the short tail page;
 *  * a request past the beginning of the dialog is a CLEAN empty page —
 *    `200`, `messages: []`, no `nextBefore`, no error (US3-4 boundary,
 *    SC-004);
 *  * `limit=0` and `limit=51` are refused `400` problem+json with
 *    `errors: {limit: [limit_out_of_range]}` (FR-008: the 1..50 bound is
 *    contract-fixed), while the edges 1 and 50 stay valid;
 *  * the order is IDENTICAL between repeated loads and for both
 *    participants (FR-006/SC-003 at the 50-page scale);
 *  * a per-user deletion watermark (`deleted_up_to_seq`, FR-021) hides
 *    the old history from the deleting participant ONLY — their pages
 *    carry exactly the visible suffix — while the peer pages over the
 *    full dialog untouched (US3 reads after «удаление чата»);
 *  * an empty dialog reads as a clean empty first page (US3-3).
 *
 * Fixture data is seeded through the REAL tables with the write-path
 * end state (messages rows + the monotone `chats.last_seq` cache) on
 * purpose: 120 fresh №16 sends would cross the FR-011 flood limit
 * (30/minute per user — a 2+-minute paced wait per method), while the
 * send path itself is already locked by T029/T030; THIS IT locks the
 * №15 read contract. For the same reason the deletion watermark is set
 * directly on `chat_participants` (the exact end state of the №14
 * DELETE, whose endpoint lands with T056/US5 and is locked by T049
 * ChatDeletionIT): here the subject is the №15 visibility filter
 * `seq > deleted_up_to_seq` (data-model 004 §2).
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №15 history), Testcontainers PG+Redis, real HTTP,
 * no mocks; every method registers its own pair, so nothing leaks
 * between methods.
 */
class HistoryIT(
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : MessagingTestSupport() {
    /**
     * US3-1/US3-4: the 120-message dialog pages 50 (default) - 50 - 20
     * through the exclusive `before` cursor down to message #1, and the
     * walk past the beginning is a clean empty page — no error, no
     * cursor. The full reconstruction is exactly the seeded order.
     */
    @Test
    fun `a 120-message history pages 50-50-20 down to the start and ends with a clean empty boundary page`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seededIds = seedDialogHistory(alice, bob, chatId, LONG_HISTORY_SIZE)

        val first = loadPage(bob, chatId)
        assertDefaultFirstPage(first)

        val second = loadPage(bob, chatId, before = first.nextBefore)
        assertThat(second.messages.map { it["text"].asText() })
            .overridingErrorMessage("the second page must continue with the next older %d messages", CONTRACT_PAGE_SIZE)
            .containsExactlyElementsOf(
                (LONG_HISTORY_SIZE - 2 * CONTRACT_PAGE_SIZE until LONG_HISTORY_SIZE - CONTRACT_PAGE_SIZE)
                    .map(::seededText)
                    .reversed(),
            )
        assertThat(second.nextBefore).isEqualTo(second.messages.last()["seq"].asLong())

        val tail = loadPage(bob, chatId, before = second.nextBefore)
        assertThat(tail.messages.map { it["text"].asText() })
            .overridingErrorMessage(
                "the third page must be the remaining %d oldest messages, got %d: %s",
                LONG_TAIL_SIZE,
                tail.messages.size,
                tail.messages.map { it["text"].asText() },
            ).containsExactlyElementsOf((0 until LONG_TAIL_SIZE).map(::seededText).reversed())
        assertThat(tail.nextBefore)
            .overridingErrorMessage(
                "a page SHORTER than the limit is the boundary — nextBefore must be absent, got <%s>",
                tail.nextBefore,
            ).isNull()

        val boundary = loadPage(bob, chatId, before = tail.messages.last()["seq"].asLong())
        assertThat(boundary.messages)
            .overridingErrorMessage(
                "a request past the beginning must be a CLEAN empty page (US3-4), got: %s",
                boundary.messages.map { it["text"].asText() },
            ).isEmpty()
        assertThat(boundary.nextBefore)
            .overridingErrorMessage("the empty boundary page must carry no nextBefore (US3-4)")
            .isNull()

        assertWholeDialogRebuilt(listOf(first, second, tail), seededIds)
    }

    /**
     * FR-008: the `limit` bounds 1..50 are contract-fixed — 0 and 51 are
     * the `400 limit_out_of_range` problem+json refusal, the edges 1 and
     * 50 are valid page sizes.
     */
    @Test
    fun `limit outside the contract bounds 1 to 50 is refused with 400 limit_out_of_range`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        seedDialogHistory(alice, bob, chatId, SMALL_HISTORY_SIZE)

        assertLimitOutOfRange(listMessages(bob, chatId, limit = LIMIT_TOO_LOW))
        assertLimitOutOfRange(listMessages(bob, chatId, limit = LIMIT_TOO_HIGH))

        val single = loadPage(bob, chatId, limit = LIMIT_FLOOR)
        assertThat(single.messages)
            .overridingErrorMessage("limit=1 is the contract floor and must be accepted (FR-008)")
            .hasSize(LIMIT_FLOOR)
        assertThat(single.nextBefore)
            .overridingErrorMessage("a full limit=1 page must expose the exclusive cursor to the next older row")
            .isEqualTo(single.messages.single()["seq"].asLong())

        val whole = loadPage(bob, chatId, limit = LIMIT_CEILING)
        assertThat(whole.messages)
            .overridingErrorMessage("limit=50 is the contract ceiling and must be accepted (FR-008)")
            .hasSize(SMALL_HISTORY_SIZE)
        assertThat(whole.nextBefore)
            .overridingErrorMessage("a page shorter than limit=50 is the boundary — no cursor")
            .isNull()
    }

    /**
     * SC-003 (US3-2): repeated №15 loads — the default page twice and
     * two full cursor walks — repeat the IDENTICAL order for both
     * participants.
     */
    @Test
    fun `order is stable between repeated loads for both participants`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        seedDialogHistory(alice, bob, chatId, LONG_HISTORY_SIZE)

        val defaultPage = loadPage(alice, chatId)
        val defaultPageReload = loadPage(alice, chatId)
        assertThat(defaultPageReload.messages.map { it["id"].asText() })
            .overridingErrorMessage("a repeated load must repeat the identical default page (SC-003)")
            .containsExactlyElementsOf(defaultPage.messages.map { it["id"].asText() })

        val firstWalk = walkAllPages(alice, chatId)
        val reload = walkAllPages(alice, chatId)
        val forBob = walkAllPages(bob, chatId)

        assertThat(reload.map { it["id"].asText() })
            .overridingErrorMessage("a repeated paginated load must repeat the identical order (SC-003)")
            .containsExactlyElementsOf(firstWalk.map { it["id"].asText() })
        assertThat(forBob.map { it["id"].asText() })
            .overridingErrorMessage("both participants must observe the SAME order (FR-006/SC-003)")
            .containsExactlyElementsOf(firstWalk.map { it["id"].asText() })
        assertThat(forBob.map { it["seq"].asLong() })
            .overridingErrorMessage("the seq values themselves must be identical for both participants (SC-003)")
            .containsExactlyElementsOf(firstWalk.map { it["seq"].asLong() })
        assertThat(firstWalk.map { it["text"].asText() })
            .containsExactlyElementsOf((0 until LONG_HISTORY_SIZE).map(::seededText))
    }

    /**
     * FR-021 read side (US3): the deletion watermark hides the old
     * history from the deleting participant ONLY — their №15 pages
     * carry exactly the visible suffix `seq > deleted_up_to_seq` with a
     * clean boundary — while the peer pages over the full dialog
     * untouched.
     */
    @Test
    fun `deletion watermark hides old history only from the deleting participant`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seededIds = seedDialogHistory(alice, bob, chatId, LONG_HISTORY_SIZE)

        val watermarkSeq = seqOf(seededIds[DELETION_HIDDEN_EDGE_INDEX])
        applyDeletionWatermark(alice, chatId, watermarkSeq)

        val alicePages = walkAllPages(alice, chatId)
        assertThat(alicePages)
            .overridingErrorMessage(
                "the deleting participant must page over exactly the visible suffix of %d messages, got %d",
                DELETION_VISIBLE_TAIL,
                alicePages.size,
            ).hasSize(DELETION_VISIBLE_TAIL)
        assertThat(alicePages.map { it["text"].asText() })
            .overridingErrorMessage(
                "the visible suffix behind the watermark must be the LAST %d messages in order (FR-021), got: %s",
                DELETION_VISIBLE_TAIL,
                alicePages.map { it["text"].asText() },
            ).containsExactlyElementsOf(
                (LONG_HISTORY_SIZE - DELETION_VISIBLE_TAIL until LONG_HISTORY_SIZE).map(::seededText),
            )

        val boundary = loadPage(alice, chatId, before = seqOf(seededIds[DELETION_VISIBLE_START_INDEX]))
        assertThat(boundary.messages)
            .overridingErrorMessage("the page behind the deletion watermark boundary must be cleanly empty")
            .isEmpty()
        assertThat(boundary.nextBefore).isNull()

        val forBob = walkAllPages(bob, chatId)
        assertThat(forBob.map { it["text"].asText() })
            .overridingErrorMessage(
                "the deletion is per-user: the peer must still page over the WHOLE dialog (FR-021), got %d",
                forBob.size,
            ).containsExactlyElementsOf((0 until LONG_HISTORY_SIZE).map(::seededText))
    }

    /** US3-3: a dialog without messages reads as a clean empty first page — no error, no cursor. */
    @Test
    fun `an empty dialog reads as a clean empty first page`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val page = loadPage(bob, chatId)

        assertThat(page.messages)
            .overridingErrorMessage("an empty dialog must answer an empty page without errors (US3-3)")
            .isEmpty()
        assertThat(page.nextBefore)
            .overridingErrorMessage("an empty dialog must expose no cursor (US3-3)")
            .isNull()

        val explicitLimit = loadPage(bob, chatId, limit = LIMIT_CEILING)
        assertThat(explicitLimit.messages).isEmpty()
        assertThat(explicitLimit.nextBefore).isNull()
    }

    /** One №15 answer projected for assertions: the rows plus the parsed exclusive cursor. */
    private data class HistoryPage(
        val messages: List<JsonNode>,
        val nextBefore: Long?,
    )

    /** №15 read with the built-in 200 gate — every caller page of this IT must succeed. */
    private fun loadPage(
        user: MessagingUser,
        chatId: UUID,
        before: Long? = null,
        limit: Int? = null,
    ): HistoryPage {
        val response = listMessages(user, chatId, before, limit)
        assertThat(response.statusCode)
            .overridingErrorMessage("history №15 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        val messages = body["messages"].toList()
        val seqs = messages.map { it["seq"].asLong() }
        assertThat(seqs)
            .overridingErrorMessage("every №15 page must be strictly seq DESC (FR-008), got: %s", seqs)
            .isSortedAccordingTo(SEQ_DESCENDING)
            .doesNotHaveDuplicates()
        val cursor = body.get(NEXT_BEFORE_FIELD)
        return HistoryPage(
            messages = messages,
            nextBefore = if (cursor == null || cursor.isNull) null else cursor.asLong(),
        )
    }

    /**
     * US3-1 head of the walk: the default №15 page (no `limit`) is the
     * contract-fixed `chats.message.page-size` — the LAST 50 messages,
     * strictly `seq DESC`, with `nextBefore` = the oldest seq of the
     * page (the exclusive cursor of the next older page).
     */
    private fun assertDefaultFirstPage(page: HistoryPage) {
        assertThat(page.messages)
            .overridingErrorMessage(
                "the default №15 page must be the contract page size %d (chats.message.page-size, FR-008), got %d",
                CONTRACT_PAGE_SIZE,
                page.messages.size,
            ).hasSize(CONTRACT_PAGE_SIZE)
        assertThat(page.messages.map { it["text"].asText() })
            .overridingErrorMessage(
                "the first page must be the LAST %d messages of the dialog, got: %s",
                CONTRACT_PAGE_SIZE,
                page.messages.map { it["text"].asText() },
            ).containsExactlyElementsOf(
                (LONG_HISTORY_SIZE - CONTRACT_PAGE_SIZE until LONG_HISTORY_SIZE).map(::seededText).reversed(),
            )
        assertThat(page.nextBefore)
            .overridingErrorMessage(
                "nextBefore must be the seq of the OLDEST row of the page (exclusive cursor), got <%s>",
                page.nextBefore,
            ).isEqualTo(page.messages.last()["seq"].asLong())
    }

    /**
     * FR-008/SC-002 tail of the pagination walk: the collected pages,
     * reversed into the reading direction, must rebuild the WHOLE dialog
     * exactly once — every seeded id in the send order, texts verbatim.
     */
    private fun assertWholeDialogRebuilt(
        pages: List<HistoryPage>,
        seededIds: List<UUID>,
    ) {
        val reconstructed = pages.flatMap { it.messages }.reversed()
        assertThat(reconstructed.map { it["id"].asText() })
            .overridingErrorMessage(
                "the paged walk must rebuild the WHOLE dialog exactly once, in order (FR-008/SC-002)",
            ).containsExactlyElementsOf(seededIds.map { it.toString() })
        assertThat(reconstructed.map { it["text"].asText() })
            .containsExactlyElementsOf((0 until LONG_HISTORY_SIZE).map(::seededText))
    }

    /**
     * Walks the whole №15 history through the exclusive cursor
     * (default 50-sized pages until `nextBefore` disappears) and returns
     * the rows in the dialog reading direction — ascending `seq`.
     */
    private fun walkAllPages(
        user: MessagingUser,
        chatId: UUID,
    ): List<JsonNode> {
        val descending = mutableListOf<JsonNode>()
        var before: Long? = null
        repeat(MAX_PAGES) {
            val page = loadPage(user, chatId, before = before)
            if (page.messages.isEmpty()) return descending.reversed()
            descending.addAll(page.messages)
            val next = page.nextBefore ?: return descending.reversed()
            before = next
        }
        throw AssertionError("history pagination did not exhaust within $MAX_PAGES pages")
    }

    /**
     * Seeds [count] alternating messages straight into the real tables
     * — the exact end state of [count] №16 sends (data-model 004 §3):
     * rows with the client UUID as PK and the IDENTITY `seq`, plus the
     * monotone `chats.last_seq` cache. Texts are `history-<i>` in send
     * order, so every assertion maps rows to indices verbatim.
     */
    private fun seedDialogHistory(
        senderA: MessagingUser,
        senderB: MessagingUser,
        chatId: UUID,
        count: Int,
    ): List<UUID> {
        val ids =
            (0 until count).map { index ->
                val sender = if (index % 2 == 0) senderA else senderB
                val id = UUID.randomUUID()
                jdbcTemplate.update(
                    "INSERT INTO messages (id, chat_id, sender_id, text) VALUES (?, ?, ?, ?)",
                    id,
                    chatId,
                    sender.id,
                    seededText(index),
                )
                id
            }
        jdbcTemplate.update(
            """
            UPDATE chats
            SET last_seq = (SELECT COALESCE(MAX(seq), 0) FROM messages WHERE chat_id = ?)
            WHERE id = ?
            """.trimIndent(),
            chatId,
            chatId,
        )
        return ids
    }

    /** The №14 DELETE end state for the deleting participant (T056/US5 lands the endpoint; T049 locks it). */
    private fun applyDeletionWatermark(
        user: MessagingUser,
        chatId: UUID,
        upToSeq: Long,
    ) {
        val updated =
            jdbcTemplate.update(
                """
                UPDATE chat_participants
                SET deleted_up_to_seq = ?, hidden = true
                WHERE chat_id = ? AND user_id = ?
                """.trimIndent(),
                upToSeq,
                chatId,
                user.id,
            )
        assertThat(updated)
            .overridingErrorMessage("the deletion watermark must land on the participant row")
            .isEqualTo(1)
    }

    private fun seqOf(messageId: UUID): Long =
        jdbcTemplate.queryForObject("SELECT seq FROM messages WHERE id = ?", Long::class.java, messageId)
            ?: error("seeded message $messageId must resolve its seq")

    /**
     * The exact №15 400 of api-contract.md: problem+json with
     * `errors.limit=[limit_out_of_range]` (FR-008: the 1..50 bound is
     * fixed by the public contract, never a free choice of the caller).
     */
    private fun assertLimitOutOfRange(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "limit outside 1..50 must be refused with 400 (FR-008), got <%s>: %s",
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

    private fun seededText(index: Int): String = "$SEED_TEXT_PREFIX-$index"

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val LIMIT_FIELD = "limit"
        const val LIMIT_OUT_OF_RANGE = "limit_out_of_range"
        const val NEXT_BEFORE_FIELD = "nextBefore"

        const val SEED_TEXT_PREFIX = "history"

        /** US3 Independent Test scale: «чат со 120 сообщениями». */
        const val LONG_HISTORY_SIZE = 120

        /** FR-008/api-contract.md №15: the contract-fixed page and limit ceiling. */
        const val CONTRACT_PAGE_SIZE = 50
        const val LONG_TAIL_SIZE = LONG_HISTORY_SIZE - 2 * CONTRACT_PAGE_SIZE
        const val LIMIT_FLOOR = 1
        const val LIMIT_CEILING = 50
        const val LIMIT_TOO_LOW = 0
        const val LIMIT_TOO_HIGH = 51

        /** A light dialog for the limit-bound checks — the bounds, not the scale, are the subject. */
        const val SMALL_HISTORY_SIZE = 5

        /**
         * FR-021 fixture: the watermark hides messages 0..74 — the
         * visible suffix is the last 45 rows (a NON-multiple of 50, so
         * the first page is short and the cursor is absent).
         */
        const val DELETION_VISIBLE_TAIL = 45
        const val DELETION_HIDDEN_EDGE_INDEX = LONG_HISTORY_SIZE - DELETION_VISIBLE_TAIL - 1
        const val DELETION_VISIBLE_START_INDEX = LONG_HISTORY_SIZE - DELETION_VISIBLE_TAIL

        const val MAX_PAGES = 10

        /** FR-008 page order: strictly descending unique seqs (UNIQUE (chat_id, seq) makes repeats impossible). */
        val SEQ_DESCENDING: Comparator<Long> = Comparator.reverseOrder()
    }
}
