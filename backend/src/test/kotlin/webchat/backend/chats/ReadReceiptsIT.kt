package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID

/**
 * T041 (tasks.md Phase 6, US4): the read receipts of contract №17
 * `POST /chats/{id}/read` — the per-user watermark of FR-010:
 *
 *  * a valid `upToSeq` answers `204` and advances the CALLER's
 *    `chat_participants.last_read_seq` (the GREATEST-watermark of
 *    data-model.md §2) — the sender's own mark stays untouched;
 *  * a repeated or SMALLER `upToSeq` is idempotent and monotone (US4-5):
 *    still `204`, the watermark keeps its value and NO `chat.read` event
 *    is published — realtime-channel.md §3.2 sends the event only when
 *    the watermark ADVANCES, which a later larger read still does;
 *  * an `upToSeq` beyond the last recorded message of the dialog is the
 *    `400 invalid_up_to_seq` refusal (openapi №17, FR-010 bound) with the
 *    watermark unmoved — and the dialog still accepts a valid mark after
 *    the refused sweep;
 *  * the `chat.read` event reaches the PEER in realtime with exactly the
 *    `ChatReadEvent` payload (`chatId`, `readUpToSeq`, `byUserId` —
 *    `additionalProperties: false`);
 *  * the re-entry of US4-4: a recipient who was offline pages through the
 *    №15 history and marks the DISPLAYED messages read from the page data
 *    (no realtime involved) — every message of both the first and the
 *    paginated older page ends up covered by the mark (edge: «включая
 *    подгруженные части истории»), and a page-local smaller mark neither
 *    rolls the watermark back nor republishes.
 *
 * The blocking-pair suppression of FR-020 is NOT here: it is the US5
 * contract of T048/T054 (BlockingIT).
 *
 * NOTE (TDD, constitution VI): written BEFORE T042 — until `ReadService`
 * and endpoint №17 land, every method here fails (RED) by design: the
 * route does not answer `204`/`400` yet. SC-007 (double tick ≤ 2 s for
 * 95%) is the k6 profile budget (T066); this IT asserts functional
 * delivery within a CI-tolerant deadline instead of a percentile.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №15 history, №16 send), Testcontainers PG+Redis,
 * real HTTP and the real Redis pub/sub fanout — no mocks; every method
 * registers its own pair, so the per-user Redis buckets never leak
 * between methods.
 */
class ReadReceiptsIT(
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : MessagingTestSupport() {
    /** First `chat.read` frame must arrive well inside this CI-tolerant budget (SC-007). */
    private val deliveryBudget: Duration = Duration.ofSeconds(5)

    /**
     * FR-010 core: `POST /read {upToSeq}` answers `204` and moves the
     * caller's watermark to exactly `upToSeq` — while the peer's own row
     * stays at zero: the mark is per-user state, never chat-wide.
     */
    @Test
    fun `post read advances the caller watermark and answers 204`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(alice, chatId, ADVANCE_TEXT_PREFIX, count = ADVANCE_MESSAGES)
        assertThat(lastReadSeq(chatId, bob.id))
            .overridingErrorMessage("a fresh dialog must start with the recipient watermark at 0")
            .isZero()

        val response = markRead(bob, chatId, upToSeq = seqs[1])

        assertThat(response.statusCode)
            .overridingErrorMessage("a valid upToSeq must answer 204, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(lastReadSeq(chatId, bob.id))
            .overridingErrorMessage("the recipient watermark must advance to upToSeq exactly")
            .isEqualTo(seqs[1])
        assertThat(lastReadSeq(chatId, alice.id))
            .overridingErrorMessage("the sender's own watermark must stay at 0 — the mark is per-user")
            .isZero()
    }

    /**
     * US4-5 idempotency/monotonicity: a repeated or smaller `upToSeq`
     * still answers `204` but changes nothing — the watermark keeps its
     * value and realtime-channel.md §3.2 publishes NO `chat.read` (the
     * event fires only on ADVANCE, which the closing larger read of the
     * same live stream still proves).
     */
    @Test
    fun `repeated and smaller upToSeq keep the watermark and publish no event`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(alice, chatId, IDEMPOTENT_TEXT_PREFIX, count = IDEMPOTENT_MESSAGES)

        openUserEvents(alice).use { aliceStream ->
            assertThat(markRead(bob, chatId, upToSeq = seqs[1]).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
            val first = aliceStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget)
            assertThat(first["readUpToSeq"].asLong())
                .overridingErrorMessage("the first advance must publish its new watermark")
                .isEqualTo(seqs[1])

            assertThat(markRead(bob, chatId, upToSeq = seqs[1]).statusCode)
                .overridingErrorMessage("a repeated upToSeq must still answer 204 (idempotent)")
                .isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(markRead(bob, chatId, upToSeq = seqs[0]).statusCode)
                .overridingErrorMessage("a smaller upToSeq must also answer 204 — it is not an error")
                .isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(lastReadSeq(chatId, bob.id))
                .overridingErrorMessage("neither the repeat nor the smaller value may move the watermark")
                .isEqualTo(seqs[1])
            assertNoChatReadEvent(aliceStream, quietPeriod)

            assertThat(markRead(bob, chatId, upToSeq = seqs[3]).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
            val advanced = aliceStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget)
            assertThat(advanced["readUpToSeq"].asLong())
                .overridingErrorMessage(
                    "a later ADVANCE must publish chat.read again — the quiet window above was the rule, " +
                        "not a dead stream",
                ).isEqualTo(seqs[3])
        }
    }

    /**
     * FR-010 bound: `upToSeq` past the last recorded message of the
     * dialog (and anything below 1) is the `400 invalid_up_to_seq`
     * problem+json refusal of openapi №17 — the watermark does not move,
     * and the dialog still accepts a valid mark after the refused sweep.
     */
    @Test
    fun `upToSeq beyond the last message seq is refused with 400 invalid_up_to_seq`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(alice, chatId, BOUNDARY_TEXT_PREFIX, count = BOUNDARY_MESSAGES)
        val lastSeq = seqs.last()
        assertThat(lastReadSeq(chatId, bob.id)).isZero()

        assertInvalidUpToSeq(markRead(bob, chatId, upToSeq = lastSeq + 1))
        assertInvalidUpToSeq(markRead(bob, chatId, upToSeq = BELOW_MINIMUM_UP_TO_SEQ))

        assertThat(lastReadSeq(chatId, bob.id))
            .overridingErrorMessage("the 400 refusals must not move the watermark")
            .isZero()
        assertThat(markRead(bob, chatId, upToSeq = lastSeq).statusCode)
            .overridingErrorMessage("after the refused sweep the dialog must still accept a valid mark")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(lastReadSeq(chatId, bob.id)).isEqualTo(lastSeq)
    }

    /**
     * FR-010 realtime leg: when the recipient marks the dialog read, the
     * PEER (the sender whose ticks must flip to ✓✓) receives `chat.read`
     * with exactly the `ChatReadEvent` contract payload — only `chatId`,
     * `readUpToSeq` and `byUserId`, no extra fields
     * (additionalProperties: false).
     */
    @Test
    fun `chat read event reaches the peer in realtime with contract payload`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val stored = sendMessageOk(alice, chatId, REALTIME_TEXT)
        val seq = stored["seq"].asLong()

        openUserEvents(alice).use { aliceStream ->
            assertThat(markRead(bob, chatId, upToSeq = seq).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

            val event = aliceStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget)
            assertThat(fieldNames(event))
                .overridingErrorMessage("chat.read payload must have exactly the ChatReadEvent fields")
                .containsExactlyInAnyOrder("chatId", "readUpToSeq", "byUserId")
            assertThat(UUID.fromString(event["chatId"].asText()))
                .overridingErrorMessage("event.chatId must be the dialog UUID")
                .isEqualTo(chatId)
            assertThat(event["readUpToSeq"].asLong())
                .overridingErrorMessage("event.readUpToSeq must be the newly advanced watermark")
                .isEqualTo(seq)
            assertThat(UUID.fromString(event["byUserId"].asText()))
                .overridingErrorMessage("event.byUserId must be the READER, not the peer receiving the event")
                .isEqualTo(bob.id)
        }
    }

    /**
     * US4-4 re-entry + the US3 pagination: the recipient was offline for
     * the whole burst, then enters and pages through the №15 history —
     * marking the DISPLAYED messages read advances the watermark from the
     * page data alone (no realtime involved), every message of BOTH the
     * first page and the older page loaded by the `before` cursor ends up
     * covered by the mark («включая подгруженные части истории»), and the
     * page-local smaller mark of the older page neither rolls the
     * watermark back nor republishes the event.
     */
    @Test
    fun `re-entry over paginated history marks every displayed message read`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(alice, chatId, REENTRY_TEXT_PREFIX, count = REENTRY_MESSAGES)
        val newest = seqs.last()

        openUserEvents(alice).use { aliceStream ->
            val firstPage = pageThrough(bob, chatId, before = null)
            assertThat(firstPage.seqs)
                .overridingErrorMessage("the №15 page must come back by seq DESC — the newest first")
                .containsExactly(seqs[4], seqs[3], seqs[2])

            assertThat(markRead(bob, chatId, upToSeq = newest).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(lastReadSeq(chatId, bob.id))
                .overridingErrorMessage("the mark sent from the PAGE data must advance the watermark")
                .isEqualTo(newest)
            val readEvent = aliceStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget)
            assertThat(readEvent["readUpToSeq"].asLong()).isEqualTo(newest)

            val olderPage = pageThrough(bob, chatId, before = firstPage.nextBefore)
            assertThat(olderPage.seqs).containsExactly(seqs[1], seqs[0])

            assertThat(markRead(bob, chatId, upToSeq = olderPage.seqs.first()).statusCode)
                .overridingErrorMessage("the page-local mark of an OLDER page must answer 204 — monotone no-op")
                .isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(lastReadSeq(chatId, bob.id))
                .overridingErrorMessage("the older page's local mark must not roll the watermark back")
                .isEqualTo(newest)
            assertNoChatReadEvent(aliceStream, quietPeriod)

            assertThat(lastReadSeq(chatId, bob.id))
                .overridingErrorMessage(
                    "every message displayed across BOTH pages must be covered by the watermark",
                ).isGreaterThanOrEqualTo((firstPage.seqs + olderPage.seqs).max())
        }
    }

    /**
     * T043 (US4): the №13 `GET /chats/{id}` and №11 `POST /chats/ensure`
     * `ChatView` carries BOTH read watermarks of the dialog — the caller's
     * own `myReadUpToSeq` and the peer's `peerReadUpToSeq` the sender
     * renders ✓✓ from (research.md §5). A fresh dialog answers 0/0 on
     * both sides; after the recipient's №17 advance each side sees exactly
     * its own projection — the reader its mark, the sender the peer's
     * mark — the watermark is per-user state, never a chat-wide value.
     */
    @Test
    fun `chat view carries both read watermarks for get and ensure`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(alice, chatId, VIEW_TEXT_PREFIX, count = VIEW_MESSAGES)

        assertReadWatermarks(ensureChat(bob, alice.id), myReadUpToSeq = 0, peerReadUpToSeq = 0)
        assertReadWatermarks(getChat(alice, chatId), myReadUpToSeq = 0, peerReadUpToSeq = 0)

        assertThat(markRead(bob, chatId, upToSeq = seqs.last()).statusCode)
            .overridingErrorMessage("the recipient's mark must answer 204 before the view is re-read")
            .isEqualTo(HttpStatus.NO_CONTENT)

        assertReadWatermarks(getChat(bob, chatId), myReadUpToSeq = seqs.last(), peerReadUpToSeq = 0)
        assertReadWatermarks(getChat(alice, chatId), myReadUpToSeq = 0, peerReadUpToSeq = seqs.last())
        assertReadWatermarks(ensureChat(alice, bob.id), myReadUpToSeq = 0, peerReadUpToSeq = seqs.last())
    }

    /** One №15 page projected to ascending helper order: descending `seq`s plus the cursor. */
    private data class HistoryPage(
        val seqs: List<Long>,
        val nextBefore: Long?,
    )

    /** №15 call → the page's `seq` list (DESC, as framed) and its `nextBefore` cursor. */
    private fun pageThrough(
        user: MessagingUser,
        chatId: UUID,
        before: Long?,
    ): HistoryPage {
        val response = listMessages(user, chatId, before = before, limit = PAGE_LIMIT)
        assertThat(response.statusCode)
            .overridingErrorMessage("history №15 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val page = objectMapper.readTree(response.body)
        val seqs = page["messages"].map { it["seq"].asLong() }
        assertThat(seqs)
            .overridingErrorMessage(
                "the page must be non-empty and never longer than the requested limit " +
                    "(the trailing partial page of a shorter history is the №15 boundary answer)",
            ).isNotEmpty()
            .hasSizeLessThanOrEqualTo(PAGE_LIMIT)
        val nextBefore = page["nextBefore"]?.asLong()
        return HistoryPage(seqs, nextBefore)
    }

    /**
     * The «без события» leg: no `chat.read` may arrive while the
     * watermark does not advance — a bounded quiet period after the 204
     * (the publish runs after the PG commit inside the request path, so
     * by the time the 204 returned anything to publish has long landed).
     */
    private fun assertNoChatReadEvent(
        stream: UserEventsStream,
        quietPeriod: Duration,
    ) {
        val unexpected = runCatching { stream.awaitEvent(CHAT_READ_EVENT, quietPeriod) }
        assertThat(unexpected.exceptionOrNull())
            .overridingErrorMessage(
                "no chat.read may be published while the watermark does not advance (US4-5), got <%s>",
                unexpected.getOrNull(),
            ).isNotNull
    }

    /** The exact №17 400 of openapi: problem+json with `errors.upToSeq=[invalid_up_to_seq]`. */
    private fun assertInvalidUpToSeq(response: ResponseEntity<String>) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "an out-of-range upToSeq must be refused with 400, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val problem = objectMapper.readTree(response.body)
        val codes = problem["errors"]?.get(UP_TO_SEQ_FIELD)?.map { it.asText() }.orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry the contract code errors.%s=[%s], got <%s> in %s",
                UP_TO_SEQ_FIELD,
                INVALID_UP_TO_SEQ,
                codes,
                response.body,
            ).containsExactly(INVALID_UP_TO_SEQ)
    }

    /**
     * The T043 watermark projection of №11/№13: an OK `ChatView` body with
     * exactly the expected `myReadUpToSeq`/`peerReadUpToSeq`. The field
     * PRESENCE is asserted first (`isNumber`) — a missing field would
     * otherwise read as the Jackson default 0 and silently pass.
     */
    private fun assertReadWatermarks(
        response: ResponseEntity<String>,
        myReadUpToSeq: Long,
        peerReadUpToSeq: Long,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage("the chat view must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val view = objectMapper.readTree(response.body)
        assertThat(view.path(MY_READ_UP_TO_SEQ_FIELD).isNumber)
            .overridingErrorMessage(
                "ChatView must carry the numeric <%s> (openapi 0.4.0), view: %s",
                MY_READ_UP_TO_SEQ_FIELD,
                response.body,
            ).isTrue
        assertThat(view.path(PEER_READ_UP_TO_SEQ_FIELD).isNumber)
            .overridingErrorMessage(
                "ChatView must carry the numeric <%s> (openapi 0.4.0), view: %s",
                PEER_READ_UP_TO_SEQ_FIELD,
                response.body,
            ).isTrue
        assertThat(view.path(MY_READ_UP_TO_SEQ_FIELD).asLong())
            .overridingErrorMessage("myReadUpToSeq must be <%d>, view: %s", myReadUpToSeq, response.body)
            .isEqualTo(myReadUpToSeq)
        assertThat(view.path(PEER_READ_UP_TO_SEQ_FIELD).asLong())
            .overridingErrorMessage("peerReadUpToSeq must be <%d>, view: %s", peerReadUpToSeq, response.body)
            .isEqualTo(peerReadUpToSeq)
    }

    /** The durable watermark row behind the dialog — `chat_participants.last_read_seq`. */
    private fun lastReadSeq(
        chatId: UUID,
        userId: UUID,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT last_read_seq FROM chat_participants WHERE chat_id = ? AND user_id = ?",
            Long::class.java,
            chatId,
            userId,
        ) ?: 0L

    /** Sends [count] messages and returns their server `seq`s ascending — the dialog reading order. */
    private fun sendFrom(
        sender: MessagingUser,
        chatId: UUID,
        textPrefix: String,
        count: Int,
    ): List<Long> =
        (1..count)
            .map { index -> sendMessageOk(sender, chatId, "$textPrefix-$index")["seq"].asLong() }
            .sorted()

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    private companion object {
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val CHAT_READ_EVENT = "chat.read"
        const val UP_TO_SEQ_FIELD = "upToSeq"
        const val INVALID_UP_TO_SEQ = "invalid_up_to_seq"

        const val ADVANCE_TEXT_PREFIX = "read-advance"
        const val IDEMPOTENT_TEXT_PREFIX = "read-idempotent"
        const val BOUNDARY_TEXT_PREFIX = "read-boundary"
        const val REENTRY_TEXT_PREFIX = "read-reentry"
        const val REALTIME_TEXT = "прочитано в реальном времени — US4 ✓✓"
        const val VIEW_TEXT_PREFIX = "read-view"

        const val MY_READ_UP_TO_SEQ_FIELD = "myReadUpToSeq"
        const val PEER_READ_UP_TO_SEQ_FIELD = "peerReadUpToSeq"

        const val ADVANCE_MESSAGES = 3
        const val IDEMPOTENT_MESSAGES = 4
        const val BOUNDARY_MESSAGES = 2
        const val REENTRY_MESSAGES = 5
        const val VIEW_MESSAGES = 2
        const val PAGE_LIMIT = 3

        const val BELOW_MINIMUM_UP_TO_SEQ = 0L

        /** Bounded quiet window proving the idempotent no-ops publish nothing. */
        val quietPeriod: Duration = Duration.ofMillis(1_500)
    }
}
