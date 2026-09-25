package webchat.backend.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID

/**
 * T030 (tasks.md Phase 5, US3): the server-authoritative unread counter
 * of data-model.md сущность 3 — the SC-003 convergence core, asserted on
 * BOTH surfaces that carry it: №12 `ChatListItem.unreadCount` and the
 * №26 `SyncChatDelta.unreadCount`:
 *
 *  * the exact formula — `unread = COUNT(входящих, seq >
 *    GREATEST(last_read, deleted), seq ≤ LEAST(last_seq, delivered))`:
 *    the badge grows ONLY by the delivery ack №25 (FR-007) — a REAL
 *    awaited `message.created` frame (US3-1) and a №26 catch-up page
 *    (US3-2) both leave it at zero until the client acks, then both
 *    grow it identically to the actual missed count; the user's own
 *    outgoing messages never count;
 *  * the №17 read decreases the badge by exactly the actually read
 *    amount, including PAGE-WISE reading (US3-6 — the watermark grows
 *    with scrolling); idempotent repeats and smaller watermarks keep
 *    it at zero — never negative;
 *  * messages below the truncation point are inaccessible, NOT
 *    unread (US1-5/FR-007 — the №14 end state of T008's truncation
 *    branch, here on the counter);
 *  * the «чтение во время дозагрузки» edge (sync-protocol.md §6): the
 *    №17 watermark may run AHEAD of the delivery position (it is bound
 *    by `chats.last_seq`, not by `delivered`) — the GREATEST lower
 *    bound keeps the counter exact at zero (an empty range never goes
 *    negative) and the already-read tail is never resurrected as
 *    unread by the later acks (no doubling);
 *  * the US3-7 offline-read flush: the deferred pendingReads watermark
 *    lands through the IDEMPOTENT №17 (a repeat after a lost response
 *    is safe — server-side GREATEST), the server badge converges to
 *    zero and the SENDER's ✓✓ watermark (`peerReadUpToSeq` of his №26
 *    delta + the `last_read_seq` probe) converges to the flush point.
 *
 * NOTE (TDD, constitution VI): written BEFORE T031 lands — the №12 leg
 * still counts by the 004 formula (no `LEAST(…, delivered_up_to_seq)`
 * upper bound), so every №12 assertion of an UNACKED tail fails (RED)
 * by design; the №26 legs ride the reusable calculator of T013 and are
 * the pinned reference behaviour T031 must converge №12 onto.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №16 send, №18 SSE), Testcontainers PG 17 + Redis 7
 * and real HTTP — no mocks; every method registers its own trio, so no
 * account rows or rate-limit buckets leak between methods.
 */
@Suppress("TooManyFunctions") // T030: one test method per US3 rule of tasks.md
class UnreadConvergenceIT(
    @Autowired private val objectMapper: ObjectMapper,
) : SyncTestSupport() {
    /** The realtime frame of the US3-1 leg must arrive well inside this CI-tolerant budget. */
    private val realtimeBudget: Duration = Duration.ofSeconds(REALTIME_BUDGET_SECONDS)

    /**
     * US3-1/US3-2: the badge grows ONLY by the delivery ack. An applied
     * realtime frame (awaited on the wire) and a handed-out №26 catch-up
     * page both leave the counter at zero (the current delta page is not
     * unread until acked — api-contract.md №26); after each ack the SAME
     * growth lands — realtime (№16 live path) and catch-up (seeded
     * backlog) increase the badge identically to the actual missed
     * incoming count, own outgoing never counted. The fully acked chat
     * leaves the next №26 answer empty — «доставлено всё» closes the
     * cycle.
     */
    @Test
    fun `unread grows only by the delivery ack, realtime and catch-up sync alike`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)

        openUserEvents(alice).use { aliceStream ->
            val liveSeq = sendOkSeq(bob, chatId, REALTIME_TEXT)
            val frame = aliceStream.awaitEvent(MESSAGE_CREATED_EVENT, realtimeBudget)
            assertThat(frame["message"]["seq"].asLong())
                .overridingErrorMessage("the awaited frame must be the very №16 message sent")
                .isEqualTo(liveSeq)
            assertThat(unreadOf(alice, chatId))
                .overridingErrorMessage(
                    "an applied realtime frame must NOT grow the badge — only the ack №25 does (US3-1, FR-007)",
                ).isZero()

            // the offline gap: a seeded backlog (missed while disconnected), incoming mixed with own outgoing
            val seeded =
                seedChatBacklog(
                    chatId,
                    SYNC_BACKLOG_MESSAGES,
                    { index -> if (index % OWN_OUTGOING_EVERY == 0) alice else bob },
                    SYNC_TEXT_PREFIX,
                )
            val missedIncoming = seeded.count { it.senderId == bob.id }
            assertThat(missedIncoming)
                .overridingErrorMessage("the fixture must mix the missed backlog with own outgoing messages")
                .isGreaterThan(ZERO_UNREAD)

            // still frozen while offline: nothing was delivered (US3-2 — «заморожен на время оффлайна»)
            assertThat(unreadOf(alice, chatId))
                .overridingErrorMessage("the offline backlog must stay invisible to the badge until the ack")
                .isZero()

            // reconnect: №26 hands the whole page, yet its unreadCount stays 0 — the page is not unread yet
            val delta = syncDeltaOf(alice, chatId)
            assertThat(delta["unreadCount"].asLong())
                .overridingErrorMessage("the current №26 page must not count as unread before the ack (№26)")
                .isZero()
            assertThat(unreadOf(alice, chatId))
                .overridingErrorMessage("the №26 answer itself must NOT grow the badge (US3-2, FR-007)")
                .isZero()

            // the realtime ack lands first (the debounced batcher path) — the live message becomes unread
            assertThat(deliveryAck(alice, listOf(chatId to liveSeq)).statusCode)
                .isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(unreadOf(alice, chatId))
                .overridingErrorMessage("after the realtime ack exactly the live incoming message must count (US3-1)")
                .isEqualTo(LIVE_INCOMING)

            // the catch-up pages get applied and acked to the head — the badge converges to the missed count
            assertThat(deliveryAck(alice, listOf(chatId to seeded.last().seq)).statusCode)
                .isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(unreadOf(alice, chatId))
                .overridingErrorMessage(
                    "after the catch-up ack the badge must equal the missed incoming count — own outgoing never count",
                ).isEqualTo(LIVE_INCOMING + missedIncoming)
        }

        val closed = requestSync(alice, listOf(chatId to ZERO_CURSOR))
        assertThat(closed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(closed.body)["chats"].size())
            .overridingErrorMessage("the fully acked chat leaves the next №26 answer empty")
            .isZero()
    }

    /**
     * US3-6: the №17 read decreases the badge by exactly the ACTUALLY
     * read amount — the watermark grows page-wise with scrolling; the
     * final read zeroes the badge, and the idempotent repeat / a smaller
     * watermark (the №17 GREATEST no-op) keep it at zero — never
     * negative, never doubled.
     */
    @Test
    fun `read receipts decrease the badge by the actually read amount, page-wise`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, READ_MESSAGES, READ_TEXT_PREFIX)
        assertThat(deliveryAck(alice, listOf(chatId to seqs.last())).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage("after the full ack the badge must carry the whole delivered incoming tail")
            .isEqualTo(READ_MESSAGES.toLong())

        markReadOk(alice, chatId, seqs[READ_PAGE_ONE_INDEX])
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage("the first scrolled page must decrease the badge by exactly its incoming count")
            .isEqualTo((READ_MESSAGES - READ_PAGE_ONE_INDEX - PAGE_STEP_ONE).toLong())

        markReadOk(alice, chatId, seqs[READ_PAGE_TWO_INDEX])
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage("the second page must keep decreasing the badge — the watermark grows (US3-6)")
            .isEqualTo((READ_MESSAGES - READ_PAGE_TWO_INDEX - PAGE_STEP_ONE).toLong())

        markReadOk(alice, chatId, seqs.last())
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage("reading to the head must zero the badge")
            .isZero()

        markReadOk(alice, chatId, seqs.last())
        markReadOk(alice, chatId, seqs[READ_PAGE_ONE_INDEX])
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage(
                "idempotent repeats and smaller watermarks must keep the badge at zero — never negative",
            ).isZero()
    }

    /**
     * US1-5/FR-007 on the counter: messages below the truncation point
     * (the exact №14 end state of the T008 truncation branch) are
     * INACCESSIBLE, not unread — the truncation delta surfaces
     * `truncatedUpToSeq`, its own `unreadCount` stays 0 before the ack,
     * and after the client pins the position the badge counts only the
     * incoming strictly above the floor.
     */
    @Test
    fun `messages below the truncation point never count as unread`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, TRUNCATION_MESSAGES, TRUNCATION_TEXT_PREFIX)
        val floor = seqs[TRUNCATION_FLOOR_INDEX]
        setDeletionWatermark(alice.id, chatId, floor)

        val delta = syncDeltaOf(alice, chatId)
        assertThat(delta["truncatedUpToSeq"].asLong())
            .overridingErrorMessage("the cursor below the visibility floor must surface the truncation point")
            .isEqualTo(floor)
        assertThat(delta["unreadCount"].asLong())
            .overridingErrorMessage("nothing is delivered yet — the truncation delta carries unreadCount 0")
            .isZero()

        assertThat(deliveryAck(alice, listOf(chatId to seqs.last())).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage(
                "only the incoming ABOVE the floor may count — the buried tail is inaccessible, not unread",
            ).isEqualTo((TRUNCATION_MESSAGES - TRUNCATION_FLOOR_INDEX - PAGE_STEP_ONE).toLong())
    }

    /**
     * The «чтение во время дозагрузки» edge (sync-protocol.md §6): the
     * №17 watermark may run AHEAD of the delivery position (its bound is
     * `chats.last_seq`) — the formula's GREATEST lower bound keeps the
     * counter at exactly zero (an empty range — never negative), a
     * repeated №26 sweep reports the same converged value (no doubling),
     * and the completing ack never resurrects the already-read tail as
     * unread: only the incoming strictly above the read watermark count.
     */
    @Test
    fun `reading during catch-up keeps the counter exact, never negative nor doubled`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, RACE_MESSAGES, RACE_TEXT_PREFIX)

        assertThat(deliveryAck(alice, listOf(chatId to seqs[RACE_DELIVERED_INDEX])).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage(
                "the badge must expose exactly the delivered unread tail (seq ≤ delivered), got the undelivered one",
            ).isEqualTo((RACE_DELIVERED_INDEX + PAGE_STEP_ONE).toLong())

        markReadOk(alice, chatId, seqs[RACE_READ_AHEAD_INDEX])
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage(
                "a read watermark ahead of the delivery position empties the range — zero, never negative",
            ).isZero()
        assertThat(syncDeltaOf(alice, chatId)["unreadCount"].asLong())
            .overridingErrorMessage("the repeated №26 sweep must report the same converged zero — no doubling")
            .isZero()

        assertThat(deliveryAck(alice, listOf(chatId to seqs.last())).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage(
                "the completing ack must count only the incoming above the read watermark — no resurrection",
            ).isEqualTo((RACE_MESSAGES - RACE_READ_AHEAD_INDEX - PAGE_STEP_ONE).toLong())
    }

    /**
     * US3-7: the offline read (a pendingReads watermark the client kept
     * locally) flushes on reconnect through the IDEMPOTENT №17 — a
     * duplicate flush after a lost response is safe (server-side
     * GREATEST). The server badge converges to zero and the SENDER's
     * ✓✓ watermark converges to the flush point: his №26 delta carries
     * `peerReadUpToSeq` at the head (the accumulated offline ✓✓).
     */
    @Test
    fun `an offline read flushes on reconnect and converges the badge and the sender checkmarks`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, FLUSH_MESSAGES, FLUSH_TEXT_PREFIX)

        // Alice received everything (acked to the head) and read OFFLINE — the server is not told yet
        assertThat(deliveryAck(alice, listOf(chatId to seqs.last())).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage("the delivered-but-unread tail must carry the full badge until the read")
            .isEqualTo(FLUSH_MESSAGES.toLong())
        assertThat(readUpToSeq(alice.id, chatId))
            .overridingErrorMessage("an offline read leaves the server watermark behind (the pendingReads flush)")
            .isZero()
        assertThat(syncDeltaOf(bob, chatId)["peerReadUpToSeq"].asLong())
            .overridingErrorMessage("the sender's ✓✓ watermark must still be behind before the flush")
            .isZero()

        // reconnect: the deferred watermark flushes through №17 — and a duplicate flush is safe (US3-7)
        markReadOk(alice, chatId, seqs.last())
        markReadOk(alice, chatId, seqs.last())

        assertThat(readUpToSeq(alice.id, chatId))
            .overridingErrorMessage("the flushed watermark must land on the read point")
            .isEqualTo(seqs.last())
        assertThat(unreadOf(alice, chatId))
            .overridingErrorMessage("the server badge must converge to zero after the flush")
            .isZero()
        assertThat(syncDeltaOf(bob, chatId)["peerReadUpToSeq"].asLong())
            .overridingErrorMessage("the sender's catch-up must show the accumulated offline ✓✓ at the head")
            .isEqualTo(seqs.last())
    }

    /**
     * Seeds [count] incoming messages (the peer is the sender) and
     * returns their `seq`s ascending — the controlled coordinates of the
     * сущность 3 formula bounds, without crossing the 30/min flood
     * limit of the real №16 path (T002 fixture).
     */
    private fun seedIncoming(
        sender: MessagingUser,
        chatId: UUID,
        count: Int,
        textPrefix: String,
    ): List<Long> = seedChatBacklog(chatId, count, { sender }, textPrefix).map { it.seq }

    /** №12 happy path → the `unreadCount` of the one dialog item (asserted present). */
    private fun unreadOf(
        user: MessagingUser,
        chatId: UUID,
    ): Long {
        val response = listChats(user)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the №12 list of <%s> must answer 200, got <%s>: %s",
                user.username,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val item =
            objectMapper
                .readTree(response.body)["chats"]
                .firstOrNull { it["chatId"].asText() == chatId.toString() }
        assertThat(item)
            .overridingErrorMessage("the №12 list of <%s> must carry the item of chat <%s>", user.username, chatId)
            .isNotNull
        return item!!["unreadCount"].asLong()
    }

    /** №17 happy path → `204` asserted verbatim (the FR-010 bound failures are not under test here). */
    private fun markReadOk(
        user: MessagingUser,
        chatId: UUID,
        upToSeq: Long,
    ) {
        val response = markRead(user, chatId, upToSeq)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№17 read of chat <%s> up to <%s> must answer 204, got <%s>: %s",
                chatId,
                upToSeq,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /**
     * №26 for [user] with a zero client cursor of [chatId] → the delta
     * asserted present (the chat carries undelivered content in every
     * call site: either the user never acked, or only partially).
     */
    private fun syncDeltaOf(
        user: MessagingUser,
        chatId: UUID,
    ): JsonNode {
        val response = requestSync(user, listOf(chatId to ZERO_CURSOR))
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the №26 sync of <%s> must answer 200, got <%s>: %s",
                user.username,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val delta =
            objectMapper
                .readTree(response.body)["chats"]
                .firstOrNull { it["chatId"].asText() == chatId.toString() }
        assertThat(delta)
            .overridingErrorMessage(
                "the №26 answer must carry the delta of chat <%s> for <%s>",
                chatId,
                user.username,
            ).isNotNull
        return delta!!
    }

    private companion object {
        const val MESSAGE_CREATED_EVENT = "message.created"
        const val ZERO_CURSOR = 0L
        const val ZERO_UNREAD = 0
        const val LIVE_INCOMING = 1L
        const val OWN_OUTGOING_EVERY = 3
        const val PAGE_STEP_ONE = 1
        const val REALTIME_BUDGET_SECONDS = 5L

        const val SYNC_BACKLOG_MESSAGES = 7
        const val READ_MESSAGES = 8
        const val READ_PAGE_ONE_INDEX = 2
        const val READ_PAGE_TWO_INDEX = 5
        const val TRUNCATION_MESSAGES = 9
        const val TRUNCATION_FLOOR_INDEX = 3
        const val RACE_MESSAGES = 10
        const val RACE_DELIVERED_INDEX = 3
        const val RACE_READ_AHEAD_INDEX = 6
        const val FLUSH_MESSAGES = 6

        const val REALTIME_TEXT = "непрочитанное в реальном времени — US3-1"
        const val SYNC_TEXT_PREFIX = "unread-sync"
        const val READ_TEXT_PREFIX = "unread-read"
        const val TRUNCATION_TEXT_PREFIX = "unread-truncated"
        const val RACE_TEXT_PREFIX = "unread-race"
        const val FLUSH_TEXT_PREFIX = "unread-flush"
    }
}
