package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T029 (tasks.md Phase 4, US2): the exactly-once delivery core on contract
 * №16 `POST /chats/{chatId}/messages` — SC-002 (0 duplicates in retries,
 * double sends and offline reads) and SC-003 (identical `seq` order for both
 * participants and between loads):
 *
 *  * a sequential retry of a recorded `clientMessageId` answers `200` with
 *    the SAME stored row (FR-004) — the id, not the draft text, is the
 *    deduplication key;
 *  * parallel retries of one id (double click / retry storm) converge to a
 *    single durable record: exactly one `201`, every other attempt `200`
 *    with the identical row, nothing duplicated in №15 history;
 *  * a `clientMessageId` owned by ANOTHER stored record — another sender in
 *    the same chat, or the same sender in a different chat — is refused
 *    with `409 message_id_conflict` and writes nothing;
 *  * the server `seq` mirrors the interleaved send order identically for
 *    both participants and stays stable between loads, including the full
 *    `before`-cursor pagination reconstruction (FR-006/FR-008);
 *  * a fully offline recipient (SSE №18 never opened) later reads every
 *    acked message exactly once — no losses, no duplicates.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №16 send, №15 history), Testcontainers PG+Redis, no
 * mocks. The US1 send path (T012/T014) already carries the functional
 * dedup semantics this IT locks in; T031 (the fast-path lookup before the
 * flood/blocking gates of T032/T054) must keep every method here green.
 */
class MessagingDeliveryIT(
    @Autowired private val objectMapper: ObjectMapper,
) : MessagingTestSupport() {
    /** FR-004: a retry of the recorded id answers 200 with the same row — never a duplicate. */
    @Test
    fun `sequential retry of the same clientMessageId returns the same record with 200`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val clientMessageId = UUID.randomUUID()

        val first = sendMessage(alice, chatId, FIRST_TEXT, clientMessageId)
        assertThat(first.statusCode)
            .overridingErrorMessage(
                "the first send of a fresh id must be created with 201, got <%s>: %s",
                first.statusCode,
                first.body,
            ).isEqualTo(HttpStatus.CREATED)

        val retry = sendMessage(alice, chatId, FIRST_TEXT, clientMessageId)
        assertThat(retry.statusCode)
            .overridingErrorMessage(
                "a retry of a recorded clientMessageId must answer 200 (FR-004), got <%s>: %s",
                retry.statusCode,
                retry.body,
            ).isEqualTo(HttpStatus.OK)
        assertSameRecord(retry, first)

        // A double click re-sending the id with a DIFFERENT draft text still
        // converges to the stored row: only chat_id/sender_id are compared
        // (research.md 004 §3), the payload is never rewritten.
        val doubleClick = sendMessage(alice, chatId, RETRY_REPLACEMENT_TEXT, clientMessageId)
        assertThat(doubleClick.statusCode)
            .overridingErrorMessage(
                "a re-send with an edited draft must dedup to 200, got <%s>: %s",
                doubleClick.statusCode,
                doubleClick.body,
            ).isEqualTo(HttpStatus.OK)
        assertSameRecord(doubleClick, first)

        val history = historyAscending(bob, chatId)
        assertThat(history.map { it["id"].asText() })
            .overridingErrorMessage(
                "retries must leave exactly ONE record in the dialog (SC-002), got: %s",
                history.map { it["id"].asText() },
            ).containsExactly(clientMessageId.toString())
        assertThat(history.single()["text"].asText())
            .overridingErrorMessage("the stored text must be the FIRST send's text, not the edited draft")
            .isEqualTo(FIRST_TEXT)
    }

    /**
     * SC-002 race shape: parallel POSTs of one `clientMessageId` — the PG
     * `ON CONFLICT` write gives exactly one `201`; every racing attempt
     * observes the committed row and answers `200` with the identical body.
     */
    @Test
    fun `parallel retries of one clientMessageId store exactly one record`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val clientMessageId = UUID.randomUUID()

        val responses = sendConcurrently(alice, chatId, clientMessageId, PARALLEL_ATTEMPTS)

        val statuses = responses.map { it.statusCode }
        assertThat(statuses)
            .overridingErrorMessage(
                "parallel retries of one id must answer only 200/201 (exactly-once, SC-002), got: %s",
                statuses,
            ).containsOnly(HttpStatus.CREATED, HttpStatus.OK)
        val created = responses.filter { it.statusCode == HttpStatus.CREATED }
        assertThat(created)
            .overridingErrorMessage(
                "exactly ONE of %d parallel attempts may insert (a single 201), got statuses: %s",
                PARALLEL_ATTEMPTS,
                statuses,
            ).hasSize(1)

        val inserted = objectMapper.readTree(created.single().body)
        responses.forEach { response ->
            assertThat(objectMapper.readTree(response.body))
                .overridingErrorMessage(
                    "every parallel retry must observe the SAME stored record, got <%s> vs the inserted <%s>",
                    response.body,
                    created.single().body,
                ).isEqualTo(inserted)
        }

        val history = historyAscending(bob, chatId)
        assertThat(history.map { it["id"].asText() })
            .overridingErrorMessage(
                "parallel retries must store exactly one record (SC-002), got: %s",
                history.map { it["id"].asText() },
            ).containsExactly(clientMessageId.toString())
        assertThat(history.single()["seq"].asLong())
            .overridingErrorMessage("the surviving record must be the one every retry observed")
            .isEqualTo(inserted["seq"].asLong())
    }

    /**
     * FR-004 foreign-id edge: a recorded `clientMessageId` belongs to its
     * stored row alone — another sender in the same chat and the same
     * sender in a DIFFERENT chat both get `409 message_id_conflict`, and
     * neither refusal writes anything anywhere.
     */
    @Test
    fun `clientMessageId owned by another record is refused with 409 message_id_conflict`() {
        val (alice, bob) = messagingPair()
        val carol = strangerUser()
        val chatWithBob = ensureChatOk(alice, bob.id)
        val chatWithCarol = ensureChatOk(alice, carol.id)
        val stolenId = UUID.randomUUID()

        val original = sendMessage(alice, chatWithBob, ORIGINAL_TEXT, stolenId)
        assertThat(original.statusCode)
            .overridingErrorMessage(
                "the id owner's send must be created with 201, got <%s>: %s",
                original.statusCode,
                original.body,
            ).isEqualTo(HttpStatus.CREATED)

        assertConflict(sendMessage(bob, chatWithBob, PEER_STOLEN_TEXT, stolenId))
        assertConflict(sendMessage(alice, chatWithCarol, CROSS_CHAT_TEXT, stolenId))

        val historyForBob = historyAscending(bob, chatWithBob)
        assertThat(historyForBob.map { it["id"].asText() })
            .overridingErrorMessage(
                "the refused foreign-id sends must not touch the original dialog, got: %s",
                historyForBob.map { it["id"].asText() },
            ).containsExactly(stolenId.toString())
        assertThat(historyAscending(alice, chatWithCarol))
            .overridingErrorMessage("a cross-chat id retry must write nothing into the second dialog")
            .isEmpty()
    }

    /**
     * SC-003: interleaved senders produce one server `seq` order — both
     * participants read the identical sequence, a repeated load repeats it,
     * and the exclusive `before`-cursor pagination rebuilds it page by page.
     */
    @Test
    fun `seq order is identical for both participants and stable across paginated loads`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val acked =
            (0 until SEQ_MESSAGE_COUNT).map { index ->
                val sender = if (index % 2 == 0) alice else bob
                val response = sendMessage(sender, chatId, "delivery-$index")
                assertThat(response.statusCode)
                    .overridingErrorMessage(
                        "the interleaved sends must all be created with 201, got <%s>: %s",
                        response.statusCode,
                        response.body,
                    ).isEqualTo(HttpStatus.CREATED)
                objectMapper.readTree(response.body)
            }
        assertThat(acked.map { it["seq"].asLong() })
            .overridingErrorMessage(
                "the server seq must mirror the strict send order (FR-006), got: %s",
                acked.map { it["seq"].asLong() },
            ).isSorted()
            .doesNotHaveDuplicates()

        val forAlice = historyAscending(alice, chatId)
        val forBob = historyAscending(bob, chatId)

        assertThat(forAlice.map { it["id"].asText() })
            .overridingErrorMessage("alice must observe the messages in the server seq order (SC-003)")
            .containsExactlyElementsOf(acked.map { it["id"].asText() })
        assertThat(forBob.map { it["id"].asText() })
            .overridingErrorMessage("bob must observe the SAME order as alice (SC-003)")
            .containsExactlyElementsOf(forAlice.map { it["id"].asText() })
        assertThat(forBob.map { it["seq"].asLong() })
            .overridingErrorMessage("the seq values themselves must be identical for both participants (SC-003)")
            .containsExactlyElementsOf(forAlice.map { it["seq"].asLong() })

        val reconstructed = paginateAscending(alice, chatId, PAGE_LIMIT)
        assertThat(reconstructed.map { it["id"].asText() })
            .overridingErrorMessage(
                "the before-cursor pagination must rebuild the identical order (FR-008), paginated=<%s> full=<%s>",
                reconstructed.map { it["id"].asText() },
                forAlice.map { it["id"].asText() },
            ).containsExactlyElementsOf(forAlice.map { it["id"].asText() })

        val reloaded = historyAscending(alice, chatId)
        assertThat(reloaded.map { it["id"].asText() })
            .overridingErrorMessage("a repeated load must repeat the identical order (SC-003)")
            .containsExactlyElementsOf(forAlice.map { it["id"].asText() })
    }

    /**
     * SC-002 offline shape: the recipient never opens the SSE stream; the
     * sender's flaky client retries two of its sends by the same id; the
     * later plain №15 read shows every acked message exactly once.
     */
    @Test
    fun `offline recipient later reads every message exactly once`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val clientIds = (0 until OFFLINE_MESSAGE_COUNT).map { UUID.randomUUID() }
        clientIds.forEachIndexed { index, clientMessageId ->
            val text = "offline-${index + 1}"
            val response = sendMessage(alice, chatId, text, clientMessageId)
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "the offline-dialog send must be created with 201, got <%s>: %s",
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.CREATED)
            if (index == RETRY_INDEX_A || index == RETRY_INDEX_B) {
                val retry = sendMessage(alice, chatId, text, clientMessageId)
                assertThat(retry.statusCode)
                    .overridingErrorMessage(
                        "a flaky-client retry must dedup to 200, got <%s>: %s",
                        retry.statusCode,
                        retry.body,
                    ).isEqualTo(HttpStatus.OK)
            }
        }

        val history = historyAscending(bob, chatId)
        assertThat(history.map { it["id"].asText() })
            .overridingErrorMessage(
                "an offline recipient must see every acked message exactly once — no losses, no duplicates (SC-002), " +
                    "got: %s",
                history.map { it["id"].asText() },
            ).containsExactlyElementsOf(clientIds.map { it.toString() })
        assertThat(history.map { it["seq"].asLong() })
            .overridingErrorMessage("the offline read must be one strictly ordered pass (SC-003)")
            .isSorted()
            .doesNotHaveDuplicates()
        assertThat(history.map { it["text"].asText() })
            .overridingErrorMessage("every message text must arrive verbatim in order")
            .containsExactlyElementsOf((0 until OFFLINE_MESSAGE_COUNT).map { "offline-${it + 1}" })
    }

    /**
     * Fires [attempts] sends of ONE `clientMessageId` as simultaneously as a
     * [CyclicBarrier] lines them up — the double-click/retry-storm shape of
     * SC-002 — and returns the responses in submission order so the
     * per-response assertions stay deterministic.
     */
    private fun sendConcurrently(
        user: MessagingUser,
        chatId: UUID,
        clientMessageId: UUID,
        attempts: Int,
    ): List<ResponseEntity<String>> {
        val barrier = CyclicBarrier(attempts)
        val executor = Executors.newFixedThreadPool(attempts)
        try {
            val futures =
                (0 until attempts).map {
                    executor.submit(
                        Callable {
                            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            sendMessage(user, chatId, PARALLEL_RETRY_TEXT, clientMessageId)
                        },
                    )
                }
            return futures.map { future -> future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
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

    /**
     * Walks the whole №15 history through the exclusive `before` cursor
     * ([limit]-sized pages until `nextBefore` disappears) and returns the
     * concatenation in ascending `seq` order — pages arrive newest-first,
     * so the walk accumulates a global `seq DESC` list that is reversed
     * exactly once at the end.
     */
    private fun paginateAscending(
        user: MessagingUser,
        chatId: UUID,
        limit: Int,
    ): List<JsonNode> {
        val descending = mutableListOf<JsonNode>()
        var before: Long? = null
        repeat(MAX_PAGES) {
            val response = listMessages(user, chatId, before = before, limit = limit)
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "a №15 page must answer 200, got <%s>: %s",
                    response.statusCode,
                    response.body,
                ).isEqualTo(HttpStatus.OK)
            val body = objectMapper.readTree(response.body)
            val page = body["messages"].toList()
            if (page.isEmpty()) return descending.reversed()
            descending.addAll(page)
            val next = body["nextBefore"]
            if (next == null || next.isNull) return descending.reversed()
            before = next.asLong()
        }
        throw AssertionError("history pagination did not exhaust within $MAX_PAGES pages")
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

    /** 409 (api-contract.md №16): problem+json with `errors.clientMessageId=[message_id_conflict]`. */
    private fun assertConflict(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a foreign clientMessageId must be refused with 409, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the 409 refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(CLIENT_MESSAGE_ID_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                CLIENT_MESSAGE_ID_FIELD,
                MESSAGE_ID_CONFLICT,
                codes,
                response.body,
            ).containsExactly(MESSAGE_ID_CONFLICT)
    }

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val CLIENT_MESSAGE_ID_FIELD = "clientMessageId"
        const val MESSAGE_ID_CONFLICT = "message_id_conflict"

        const val FIRST_TEXT = "the only copy of this message"
        const val RETRY_REPLACEMENT_TEXT = "a draft edited after the send — must NOT replace the stored row"
        const val ORIGINAL_TEXT = "recorded under a client-chosen id"
        const val PEER_STOLEN_TEXT = "bob must not hijack alice's clientMessageId"
        const val CROSS_CHAT_TEXT = "the same id cannot live in two dialogs"
        const val PARALLEL_RETRY_TEXT = "eight clicks, one message"

        const val PARALLEL_ATTEMPTS = 8
        const val SEQ_MESSAGE_COUNT = 12
        const val PAGE_LIMIT = 5
        const val MAX_PAGES = 20
        const val OFFLINE_MESSAGE_COUNT = 5
        const val RETRY_INDEX_A = 1
        const val RETRY_INDEX_B = 3
        const val BARRIER_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}
