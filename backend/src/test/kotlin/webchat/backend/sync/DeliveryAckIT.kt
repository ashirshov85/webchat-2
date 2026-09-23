package webchat.backend.sync

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Duration
import java.util.UUID

/**
 * T007 (tasks.md Phase 3, US1): the delivery position of contract №25
 * `POST /users/me/delivery-ack` — the GREATEST-monotone watermark of
 * data-model.md entity 1 (FR-001: ack is the ONLY writer):
 *
 *  * monotonicity: a repeated or SMALLER `upToSeq` answers `204`
 *    idempotently and never moves the position back — and a later
 *    larger ack still advances it;
 *  * the position moves ONLY by №25: an APPLIED realtime frame
 *    (№18 `message.created` really awaited on the wire) and a real
 *    №26 sync answer both leave `delivered_up_to_seq` untouched until
 *    the client acks (neither the SSE frame nor the sync response
 *    writes it);
 *  * batch atomicity: a foreign chat refuses the WHOLE batch with
 *    `403 not_participant`, an unknown chat with `404 chat_not_found`,
 *    `upToSeq` past the chat head with `400 invalid_up_to_seq` — no
 *    partial effects survive any refusal (the membership precheck of
 *    every item wins over the seq bound, pinning the T012 order), and
 *    a valid ack still lands after a refused batch;
 *  * the contract bound of the batch itself: more than 100 items is
 *    the `400` refusal (atomic — positions stay put).
 *
 * NOTE (TDD, constitution VI): written BEFORE T012–T014 land — until
 * the №25 route exists every call here answers 404, so every method
 * fails (RED) by design. The DB probe [deliveredUpToSeq] works against
 * the V12 column (T005) already, keeping the assertions anchored on
 * the authoritative table, not on response bodies.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №16 send, №18 SSE), Testcontainers PG 17 + Redis 7
 * and real HTTP — no mocks; every method registers its own trio, so no
 * account rows or rate-limit buckets leak between methods.
 */
@Suppress("TooManyFunctions") // T007: one test method per №25 contract rule of tasks.md
class DeliveryAckIT(
    @Autowired private val objectMapper: ObjectMapper,
) : SyncTestSupport() {
    /** The realtime frame of the only-ack-moves leg must arrive well inside this CI-tolerant budget. */
    private val deliveryBudget: Duration = Duration.ofSeconds(REALTIME_BUDGET_SECONDS)

    /**
     * FR-001 core: a valid batch answers `204` and advances the
     * CALLER's `delivered_up_to_seq` to exactly `upToSeq` — while the
     * peer's own row stays at zero: the delivery position is per-user
     * state of the acknowledging participant, never chat-wide.
     */
    @Test
    fun `ack advances only the caller delivery position and answers 204`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(alice, chatId, ADVANCE_MESSAGES, ADVANCE_TEXT_PREFIX)
        assertThat(deliveredUpToSeq(bob.id, chatId))
            .overridingErrorMessage("a fresh dialog must start with the delivery position at 0")
            .isZero()

        val response = deliveryAck(bob, listOf(chatId to seqs[ADVANCE_ACK_INDEX]))

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a valid ack batch must answer 204, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(bob.id, chatId))
            .overridingErrorMessage("the caller's delivery position must advance to upToSeq exactly")
            .isEqualTo(seqs[ADVANCE_ACK_INDEX])
        assertThat(deliveredUpToSeq(alice.id, chatId))
            .overridingErrorMessage("the peer's delivery position must stay at 0 — the ack is per-user")
            .isZero()
    }

    /**
     * Entity 1 monotonicity: a repeated or smaller `upToSeq` is NOT an
     * error — still `204`, the GREATEST-watermark keeps its value; only
     * a later larger ack moves it again (the closing advance proves the
     * no-ops were the rule, not a stuck position).
     */
    @Test
    fun `repeated and smaller upToSeq keep the position and answer 204 idempotently`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(alice, chatId, IDEMPOTENT_MESSAGES, IDEMPOTENT_TEXT_PREFIX)
        val head = seqs.last()

        assertThat(deliveryAck(bob, listOf(chatId to seqs[IDEMPOTENT_ACK_INDEX])).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(bob.id, chatId)).isEqualTo(seqs[IDEMPOTENT_ACK_INDEX])

        assertThat(deliveryAck(bob, listOf(chatId to seqs[IDEMPOTENT_ACK_INDEX])).statusCode)
            .overridingErrorMessage("a repeated upToSeq must still answer 204 (idempotent)")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveryAck(bob, listOf(chatId to seqs[1])).statusCode)
            .overridingErrorMessage("a smaller upToSeq must also answer 204 — a monotone no-op, not an error")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(bob.id, chatId))
            .overridingErrorMessage("neither the repeat nor the smaller value may move the position")
            .isEqualTo(seqs[IDEMPOTENT_ACK_INDEX])

        assertThat(deliveryAck(bob, listOf(chatId to head)).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(bob.id, chatId))
            .overridingErrorMessage("after the idempotent no-ops a larger ack must still advance the position")
            .isEqualTo(head)
    }

    /**
     * The happy batch of api-contract.md №25: one atomic 204 advances
     * the caller's position in EVERY chat of the batch (GREATEST per
     * element, one transaction), while the peers' own rows stay at 0.
     */
    @Test
    fun `a multi chat batch advances every caller position in one atomic 204`() {
        val (alice, bob, carol) = messagingTrio()
        val withBob = ensureDialog(alice, bob)
        val withCarol = ensureDialog(alice, carol)
        val fromBob = seedIncoming(bob, withBob, BATCH_MESSAGES, BATCH_TEXT_PREFIX)
        val fromCarol = seedIncoming(carol, withCarol, BATCH_MESSAGES, OTHER_TEXT_PREFIX)

        val response = deliveryAck(alice, listOf(withBob to fromBob[1], withCarol to fromCarol[1]))

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "a batch of the caller's own chats must answer 204, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(alice.id, withBob)).isEqualTo(fromBob[1])
        assertThat(deliveredUpToSeq(alice.id, withCarol)).isEqualTo(fromCarol[1])
        assertThat(deliveredUpToSeq(bob.id, withBob))
            .overridingErrorMessage("Bob's own position must stay at 0 — only Alice acked")
            .isZero()
        assertThat(deliveredUpToSeq(carol.id, withCarol)).isZero()
    }

    /**
     * FR-001 uniqueness of the writer: the recipient REALLY receives
     * the `message.created` frame (awaited on the wire) and a REAL №26
     * sync answer delivers the message again — neither moves
     * `delivered_up_to_seq`; only the explicit №25 ack does.
     */
    @Test
    fun `neither the sse frame nor the sync answer moves the delivery position`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)

        openUserEvents(bob).use { bobStream ->
            val seq = sendOkSeq(alice, chatId, REALTIME_TEXT)
            val frame = bobStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)
            assertThat(frame["message"]["seq"].asLong())
                .overridingErrorMessage("the awaited frame must be the very №16 message sent")
                .isEqualTo(seq)
            assertThat(deliveredUpToSeq(bob.id, chatId))
                .overridingErrorMessage("an applied SSE frame must NOT write the delivery position (FR-001)")
                .isZero()

            val sync = requestSync(bob, listOf(chatId to ZERO_CURSOR))
            assertThat(sync.statusCode)
                .overridingErrorMessage(
                    "the №26 answer is the scenario precondition (the message really delivered again), got <%s>: %s",
                    sync.statusCode,
                    sync.body,
                ).isEqualTo(HttpStatus.OK)
            assertThat(deliveredUpToSeq(bob.id, chatId))
                .overridingErrorMessage(
                    "the №26 sync answer must NOT write the delivery position — only ack №25 does (FR-001)",
                ).isZero()

            assertThat(deliveryAck(bob, listOf(chatId to seq)).statusCode)
                .isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(deliveredUpToSeq(bob.id, chatId))
                .overridingErrorMessage("the №25 ack must be the ONLY writer of the position")
                .isEqualTo(seq)
        }
    }

    /**
     * Batch atomicity, membership leg: a chat the caller never joined
     * (an otherwise-VALID item — seeded head and in-range upToSeq)
     * refuses the WHOLE batch with `403 not_participant` — no partial
     * effects on the caller's own chats, and a valid single ack still
     * lands after the refusal.
     */
    @Test
    fun `a foreign chat rejects the whole batch with 403 not_participant`() {
        val (alice, bob, carol) = messagingTrio()
        val ownChat = ensureDialog(alice, bob)
        val foreignChat = ensureDialog(bob, carol)
        val own = seedIncoming(alice, ownChat, FOREIGN_MESSAGES, FOREIGN_TEXT_PREFIX)
        val foreign = seedIncoming(bob, foreignChat, FOREIGN_MESSAGES, OTHER_TEXT_PREFIX)

        val response = deliveryAck(alice, listOf(ownChat to own[1], foreignChat to foreign[1]))

        assertProblem(response, HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
        assertThat(deliveredUpToSeq(alice.id, ownChat))
            .overridingErrorMessage("a refused batch must leave NO partial effects — the whole batch is atomic")
            .isZero()

        assertThat(deliveryAck(alice, listOf(ownChat to own[1])).statusCode)
            .overridingErrorMessage("after the refused batch a valid single ack must still land")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(alice.id, ownChat)).isEqualTo(own[1])
    }

    /**
     * Batch atomicity, existence leg: a random unknown chatId refuses
     * the whole batch with `404 chat_not_found` — the caller's own chat
     * of the same batch stays untouched, a valid ack lands after.
     */
    @Test
    fun `an unknown chat rejects the whole batch with 404 chat_not_found`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(alice, chatId, UNKNOWN_MESSAGES, UNKNOWN_TEXT_PREFIX)
        val unknownChat = UUID.randomUUID()

        val response = deliveryAck(alice, listOf(chatId to seqs[1], unknownChat to seqs[1]))

        assertProblem(response, HttpStatus.NOT_FOUND, CHAT_FIELD, CHAT_NOT_FOUND)
        assertThat(deliveredUpToSeq(alice.id, chatId))
            .overridingErrorMessage("a refused batch must leave NO partial effects")
            .isZero()

        assertThat(deliveryAck(alice, listOf(chatId to seqs[1])).statusCode)
            .overridingErrorMessage("after the refused batch a valid single ack must still land")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(alice.id, chatId)).isEqualTo(seqs[1])
    }

    /**
     * Batch atomicity, seq bound: `upToSeq` past the chat head (and
     * anything below 1 — the №25 `minimum: 1`) refuses the whole batch
     * with `400 invalid_up_to_seq`; an EMPTY dialog has head 0, so any
     * ack there is the same refusal (nothing is delivered yet). The
     * position never moves on a refusal.
     */
    @Test
    fun `upToSeq beyond the chat head rejects the whole batch with 400 invalid_up_to_seq`() {
        val (alice, bob, carol) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(alice, chatId, BOUNDARY_MESSAGES, BOUNDARY_TEXT_PREFIX)
        val head = seqs.last()
        assertThat(lastSeqOf(chatId))
            .overridingErrorMessage("the seeded backlog must define the chat head (the invalid_up_to_seq bound)")
            .isEqualTo(head)

        assertProblem(
            deliveryAck(alice, listOf(chatId to seqs[1], chatId to head + 1)),
            HttpStatus.BAD_REQUEST,
            UP_TO_SEQ_FIELD,
            INVALID_UP_TO_SEQ,
        )
        assertProblem(
            deliveryAck(alice, listOf(chatId to BELOW_MINIMUM_UP_TO_SEQ)),
            HttpStatus.BAD_REQUEST,
            UP_TO_SEQ_FIELD,
            INVALID_UP_TO_SEQ,
        )
        assertThat(deliveredUpToSeq(alice.id, chatId))
            .overridingErrorMessage("the 400 refusals must not move the delivery position")
            .isZero()

        val emptyChat = ensureDialog(alice, carol)
        assertThat(lastSeqOf(emptyChat)).isZero()
        assertProblem(
            deliveryAck(alice, listOf(emptyChat to FIRST_UP_TO_SEQ)),
            HttpStatus.BAD_REQUEST,
            UP_TO_SEQ_FIELD,
            INVALID_UP_TO_SEQ,
        )

        assertThat(deliveryAck(alice, listOf(chatId to seqs[1])).statusCode)
            .overridingErrorMessage("after the refused sweep the chat must still accept a valid ack")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(alice.id, chatId)).isEqualTo(seqs[1])
    }

    /**
     * The T012 check ORDER pinned from the contract text: the
     * membership precheck covers EVERY item before any seq bound — a
     * batch whose foreign item ALSO carries an out-of-range upToSeq
     * answers `403 not_participant` (not 400), with no partial effects.
     */
    @Test
    fun `membership refusal wins over the seq bound of another item`() {
        val (alice, bob, carol) = messagingTrio()
        val ownChat = ensureDialog(alice, bob)
        val foreignChat = ensureDialog(bob, carol)
        val own = seedIncoming(alice, ownChat, ORDER_MESSAGES, ORDER_TEXT_PREFIX)
        seedIncoming(bob, foreignChat, ORDER_MESSAGES, OTHER_TEXT_PREFIX)

        val response =
            deliveryAck(
                alice,
                listOf(foreignChat to lastSeqOf(foreignChat) + 1, ownChat to own[1]),
            )

        assertProblem(response, HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
        assertThat(deliveredUpToSeq(alice.id, ownChat))
            .overridingErrorMessage("the refused batch must leave the caller's own chats untouched")
            .isZero()
    }

    /**
     * The batch-size bound of api-contract.md №25 (`acks: 1..100`): a
     * batch of 101 otherwise-valid items answers `400` — refused
     * atomically, both chats' positions stay at 0.
     */
    @Test
    fun `a batch of more than 100 items is refused with 400`() {
        val (alice, bob, carol) = messagingTrio()
        val withBob = ensureDialog(alice, bob)
        val withCarol = ensureDialog(alice, carol)
        val fromBob = seedIncoming(bob, withBob, OVERFLOW_MESSAGES, OVERFLOW_TEXT_PREFIX)
        val fromCarol = seedIncoming(carol, withCarol, OVERFLOW_MESSAGES, OTHER_TEXT_PREFIX)

        val overflow =
            (0..ACK_BATCH_LIMIT).map { index ->
                if (index % PAIR_COUNT == 0) withBob to fromBob.last() else withCarol to fromCarol.last()
            }
        assertThat(overflow).hasSize(ACK_BATCH_LIMIT + 1)

        val response = deliveryAck(alice, overflow)

        assertThat(response.statusCode)
            .overridingErrorMessage(
                "an oversized batch must be refused with 400, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("the refusal must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        assertThat(deliveredUpToSeq(alice.id, withBob))
            .overridingErrorMessage("the oversized batch must be refused atomically — no partial effects")
            .isZero()
        assertThat(deliveredUpToSeq(alice.id, withCarol)).isZero()
    }

    /**
     * Seeds [count] incoming messages for the acking user ([sender] is
     * the peer) and returns their `seq`s ascending — the controlled
     * coordinates of the entity 1 transitions, without crossing the
     * 30/min flood limit of the real №16 path (T002 fixture).
     */
    private fun seedIncoming(
        sender: MessagingUser,
        chatId: UUID,
        count: Int,
        textPrefix: String,
    ): List<Long> = seedChatBacklog(chatId, count, { sender }, textPrefix).map { it.seq }

    /** The exact problem+json refusal of №25: [expected] status with `errors.[field] = [code]` verbatim. */
    private fun assertProblem(
        response: ResponseEntity<String>,
        expected: HttpStatus,
        field: String,
        code: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the ack batch must be refused with %s, got <%s>: %s",
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
        const val MESSAGE_CREATED_EVENT = "message.created"

        const val CHAT_FIELD = "chat"
        const val UP_TO_SEQ_FIELD = "upToSeq"
        const val NOT_PARTICIPANT = "not_participant"
        const val CHAT_NOT_FOUND = "chat_not_found"
        const val INVALID_UP_TO_SEQ = "invalid_up_to_seq"

        /** api-contract.md №25: the ack batch cap — 1..100 elements. */
        const val ACK_BATCH_LIMIT = 100

        const val ADVANCE_MESSAGES = 5
        const val IDEMPOTENT_MESSAGES = 6
        const val BATCH_MESSAGES = 3
        const val FOREIGN_MESSAGES = 2
        const val UNKNOWN_MESSAGES = 2
        const val BOUNDARY_MESSAGES = 3
        const val ORDER_MESSAGES = 2
        const val OVERFLOW_MESSAGES = 2

        const val ADVANCE_ACK_INDEX = 3
        const val IDEMPOTENT_ACK_INDEX = 4

        const val BELOW_MINIMUM_UP_TO_SEQ = 0L
        const val FIRST_UP_TO_SEQ = 1L
        const val ZERO_CURSOR = 0L
        const val PAIR_COUNT = 2
        const val REALTIME_BUDGET_SECONDS = 5L

        const val ADVANCE_TEXT_PREFIX = "ack-advance"
        const val IDEMPOTENT_TEXT_PREFIX = "ack-idempotent"
        const val BATCH_TEXT_PREFIX = "ack-batch"
        const val OTHER_TEXT_PREFIX = "ack-other"
        const val FOREIGN_TEXT_PREFIX = "ack-foreign"
        const val UNKNOWN_TEXT_PREFIX = "ack-unknown"
        const val BOUNDARY_TEXT_PREFIX = "ack-boundary"
        const val ORDER_TEXT_PREFIX = "ack-order"
        const val OVERFLOW_TEXT_PREFIX = "ack-overflow"
        const val REALTIME_TEXT = "доставлено в реальном времени — US1, ack не нужен ✓"
    }
}
