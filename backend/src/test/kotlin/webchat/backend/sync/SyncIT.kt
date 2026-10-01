package webchat.backend.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * T008 (tasks.md Phase 3, US1): the catch-up synchronization of contract
 * №26 `POST /users/me/sync` — the completeness/order core of SC-001
 * (sync-protocol.md §3, §5):
 *
 *  * completeness/order: the delta replays the undelivered backlog
 *    strictly ascending `(startAfterSeq, …]` by server `seq`, INCLUDING
 *    the user's own outgoing messages — no losses, no duplicates, and no
 *    client-clock say (the skewed-timestamps edge pins order/bounds to
 *    `seq` alone);
 *  * the effective cursor is `max(client, server)` in BOTH directions:
 *    a stale client cursor never rewinds the server position, and a
 *    cursor ahead of the position (but within the chat head) is adopted
 *    as the lower bound — still not a desync;
 *  * pagination: `messageLimit`/`hasMore` pages continue strictly after
 *    the cursor until exhaustion (the contract default page is 50),
 *    `chatLimit`/`moreChats` page the chats by last activity DESC — the
 *    newest dialog first;
 *  * truncation (§5): a cursor below the per-user visibility floor
 *    (`deleted_up_to_seq` — the exact №14 end state; the 12-month
 *    history-window leg waits for archival 012) surfaces
 *    `truncatedUpToSeq` with `startAfterSeq` pinned to the floor, and
 *    messages below never return (US1-5);
 *  * the future-cursor repair (§5): a client `upToSeq` beyond the chat
 *    head is NOT a request error — the affected delta answers
 *    `desynced: true` + `serverUpToSeq` while the other chats sync
 *    normally, and the server never adopts the bogus cursor;
 *  * №26 is per-user: foreign/unknown chat ids in `cursors` ride along
 *    silently ignored (unlike the atomic №25 batch of T007);
 *  * all-or-refusal: a read failure of ONE chat refuses the WHOLE
 *    request with 5xx (deterministically injected through a
 *    row-level-security predicate raising on the poisoned chat only) —
 *    a partial chat list may never parade as the full one;
 *  * the operation NEVER moves the delivery position (FR-001) — the
 *    scenarios probe `chat_participants.delivered_up_to_seq` around the
 *    syncs; only the explicit №25 ack writes it.
 *
 * NOTE (TDD, constitution VI): written BEFORE T013–T014 land — until
 * the №26 route exists every call here answers 404, so every method
 * fails (RED) by design; the [deliveredUpToSeq] probe already works
 * against the V12 column (T005), keeping the FR-001 legs anchored on
 * the authoritative table, not on response bodies.
 *
 * T032 (tasks.md Phase 4, US2): the №26 GROUP deltas join this suite at
 * the US2 close — the group element of the catch-up answer (006,
 * api-contract.md §3): `type:'group'` with `title`/`memberCount` of the
 * active roster and the FR-012 ✓✓ bound `othersReadUpToSeq`
 * (MIN of the OTHER actives' read watermarks — the caller's own mark
 * never folds in), while the direct-shaped peer projection renders as
 * EXPLICIT nulls (`peer`/`blockedByMe`/`peerReadUpToSeq`); the replay
 * rules themselves stay the 005 ones verbatim — ascending by `seq`,
 * seeded ids verbatim, the caller's own outgoing included,
 * delivery-bounded `unreadCount`, no repair fields, and the sweep never
 * writes the delivery position (FR-001). The DIRECT delta beside the
 * group in the SAME answer keeps the exact 0.5.0 field set — no group
 * field may leak into a direct delta. The group read watermarks are
 * staged straight on `chat_participants` (the №17 group path is owned by
 * T036 — the same end state its GREATEST-update produces), and the group
 * history rides [seedChatBacklog]: the real №16 group send lands with
 * T035. RED by design until the T037 group delta projection exists.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №25 ack, №14 end state), Testcontainers PG 17 +
 * Redis 7 and real HTTP — no mocks; every method registers its own
 * trio, so no account rows or rate-limit buckets leak between methods.
 */
@Suppress("TooManyFunctions", "LargeClass") // T008/T032: one test method per №26 contract rule of tasks.md
class SyncIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val itJdbcTemplate: JdbcTemplate,
) : SyncTestSupport() {
    /**
     * SC-001 happy path: a zero cursor of a fresh dialog must replay the
     * WHOLE backlog — ascending strictly by `seq`, seeded ids verbatim
     * (no loss, no duplicates), own outgoing included — with the full
     * delta shape ([peer], [blockedByMe], [lastSeq], [hasMore],
     * [peerReadUpToSeq], the delivery-bounded [unreadCount]) and NO
     * repair fields. The sync answer itself leaves the delivery
     * position at 0 (FR-001).
     */
    @Test
    fun `sync replays the whole backlog ascending with own outgoing and never moves the position`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seeded =
            seedChatBacklog(
                chatId,
                FULL_MESSAGES,
                { index -> if (index % OWN_OUTGOING_EVERY == 0) alice else bob },
                FULL_TEXT_PREFIX,
            )

        val response = syncOk(alice, listOf(chatId to ZERO_CURSOR))

        assertThat(response["chats"].size())
            .overridingErrorMessage(
                "a chat with an undelivered backlog must produce exactly one delta, got <%s>",
                response,
            ).isEqualTo(ONE_DELTA)
        val delta = deltaOf(response, chatId)
        assertThat(delta["startAfterSeq"].asLong())
            .overridingErrorMessage("a zero cursor of a fresh dialog must resume from 0")
            .isZero()
        assertThat(delta["peer"]["id"].asText())
            .overridingErrorMessage("the delta peer must be the dialog counterpart")
            .isEqualTo(bob.id.toString())
        assertThat(delta["blockedByMe"].asBoolean())
            .overridingErrorMessage("an unblocked dialog must project blockedByMe=false")
            .isFalse()
        assertThat(delta["lastSeq"].asLong())
            .overridingErrorMessage("lastSeq must be the chat head")
            .isEqualTo(seeded.last().seq)
        assertThat(delta["hasMore"].asBoolean())
            .overridingErrorMessage("a backlog within one default page must leave no tail")
            .isFalse()
        assertThat(delta["peerReadUpToSeq"].asLong())
            .overridingErrorMessage("a peer who read nothing must project the ✓✓ watermark at 0")
            .isZero()
        assertThat(delta["unreadCount"].asLong())
            .overridingErrorMessage(
                "unread is delivery-bounded (data-model entity 3): before any ack nothing may count, got <%s>",
                delta["unreadCount"],
            ).isZero()
        assertNoRepairFields(delta)

        val messages = delta["messages"]
        assertThat(messages.size()).isEqualTo(FULL_MESSAGES)
        assertThat(messages.map { it["seq"].asLong() })
            .overridingErrorMessage("the delta must replay the backlog strictly ascending by seq")
            .containsExactlyElementsOf(seeded.map { it.seq })
        assertThat(messages.map { it["id"].asText() })
            .overridingErrorMessage("the delta must replay the seeded ids verbatim — no loss, no duplicates")
            .containsExactlyElementsOf(seeded.map { it.id.toString() })
        assertThat(messages.map { it["senderId"].asText() })
            .overridingErrorMessage("the delta must include the user's own outgoing messages in order")
            .containsExactlyElementsOf(seeded.map { it.senderId.toString() })
        assertThat(deliveredUpToSeq(alice.id, chatId))
            .overridingErrorMessage("the №26 answer must NOT write the delivery position — only ack №25 does (FR-001)")
            .isZero()
    }

    /**
     * The canonical close of the cycle (sync-protocol.md §3.1): once the
     * client acks the chat head, the next №26 returns the EMPTY chat
     * list — «доставлено всё» is a correct full answer, `moreChats`
     * false.
     */
    @Test
    fun `a fully acked chat leaves the empty list as the correct full answer`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seeded =
            seedChatBacklog(chatId, FULL_MESSAGES, { bob }, CLOSED_TEXT_PREFIX)

        assertThat(deliveryAck(alice, listOf(chatId to seeded.last().seq)).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        val closed = syncOk(alice, listOf(chatId to ZERO_CURSOR))
        assertThat(closed["chats"].size())
            .overridingErrorMessage(
                "a fully acked chat has nothing undelivered — the empty chat list is the correct answer",
            ).isZero()
        assertThat(closed["moreChats"].asBoolean()).isFalse()
    }

    /**
     * sync-protocol.md §1 effective cursor: the server applies the client
     * map as a LOWER BOUND of its own position — a stale client cursor
     * never rewinds the resume point (server wins), a cursor ahead of
     * the confirmed position but within the chat head is adopted (client
     * wins, and it is still NOT a desync). Neither sync moves the
     * position itself (FR-001).
     */
    @Test
    fun `effective cursor is the max of the client and server positions`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, CURSOR_MESSAGES, CURSOR_TEXT_PREFIX)
        val acked = seqs[SERVER_CURSOR_INDEX]
        assertThat(deliveryAck(alice, listOf(chatId to acked)).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        val serverWins = syncOk(alice, listOf(chatId to seqs[BEHIND_CURSOR_INDEX]))
        val serverDelta = deltaOf(serverWins, chatId)
        assertThat(serverDelta["startAfterSeq"].asLong())
            .overridingErrorMessage(
                "a stale client cursor must not rewind the effective one — the server position wins",
            ).isEqualTo(acked)
        assertThat(serverDelta["messages"].map { it["seq"].asLong() })
            .overridingErrorMessage("the delta must resume strictly after the effective cursor")
            .containsExactlyElementsOf(seqs.subList(SERVER_CURSOR_INDEX + 1, seqs.size))
        assertNoRepairFields(serverDelta)

        val clientWins = syncOk(alice, listOf(chatId to seqs[AHEAD_CURSOR_INDEX]))
        val clientDelta = deltaOf(clientWins, chatId)
        assertThat(clientDelta["startAfterSeq"].asLong())
            .overridingErrorMessage(
                "a client cursor ahead of the position (within the chat head) is the adopted lower bound",
            ).isEqualTo(seqs[AHEAD_CURSOR_INDEX])
        assertThat(clientDelta["messages"].map { it["seq"].asLong() })
            .containsExactlyElementsOf(seqs.subList(AHEAD_CURSOR_INDEX + 1, seqs.size))
        assertNoRepairFields(clientDelta)

        assertThat(deliveredUpToSeq(alice.id, chatId))
            .overridingErrorMessage("neither sync answer may move the delivery position (FR-001)")
            .isEqualTo(acked)
    }

    /**
     * The №26 chat page: only chats with undelivered content, by last
     * activity DESC (the newest dialog first — FR-003), up to
     * `chatLimit` with `moreChats` for the remainder; a client cursor at
     * a chat head excludes that chat from the page without any ack.
     */
    @Test
    fun `chat deltas page by last activity with chatLimit and moreChats`() {
        val (alice, bob, carol) = messagingTrio()
        val withBob = ensureDialog(alice, bob)
        val withCarol = ensureDialog(alice, carol)
        seedIncoming(bob, withBob, ORDER_OLDER_MESSAGES, ORDER_OLDER_TEXT_PREFIX)
        val fromCarol = seedIncoming(carol, withCarol, ORDER_NEWER_MESSAGES, ORDER_NEWER_TEXT_PREFIX)

        val both = syncOk(alice, listOf(withBob to ZERO_CURSOR, withCarol to ZERO_CURSOR))
        assertThat(both["chats"].map { it["chatId"].asText() })
            .overridingErrorMessage("chats must arrive by last activity DESC — the newest dialog first")
            .containsExactly(withCarol.toString(), withBob.toString())
        assertThat(both["moreChats"].asBoolean()).isFalse()
        assertThat(deltaOf(both, withCarol)["messages"].size()).isEqualTo(ORDER_NEWER_MESSAGES)
        assertThat(deltaOf(both, withBob)["messages"].size()).isEqualTo(ORDER_OLDER_MESSAGES)

        val firstPage =
            syncOk(
                alice,
                listOf(withBob to ZERO_CURSOR, withCarol to ZERO_CURSOR),
                chatLimit = ONE_CHAT_PAGE,
            )
        assertThat(firstPage["chats"].map { it["chatId"].asText() })
            .overridingErrorMessage("chatLimit=1 must keep only the newest dialog in the page")
            .containsExactly(withCarol.toString())
        assertThat(firstPage["moreChats"].asBoolean())
            .overridingErrorMessage("a left-behind undelivered chat must surface as moreChats=true")
            .isTrue()

        val secondPage =
            syncOk(
                alice,
                listOf(withBob to ZERO_CURSOR, withCarol to fromCarol.last()),
                chatLimit = ONE_CHAT_PAGE,
            )
        assertThat(secondPage["chats"].map { it["chatId"].asText() })
            .overridingErrorMessage("once the newest chat is caught up, the next page carries the older one")
            .containsExactly(withBob.toString())
        assertThat(secondPage["moreChats"].asBoolean()).isFalse()
    }

    /**
     * The №26 message page: `messageLimit` slices the delta strictly
     * after the cursor — pages never overlap, never gap (the union
     * replays the backlog exactly), `hasMore` closes only at the chat
     * head, and a cursor at the head leaves the chat out of the answer
     * entirely (the empty page at the boundary is correct).
     */
    @Test
    fun `message deltas page strictly after the cursor until exhaustion`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, PAGE_MESSAGES, PAGE_TEXT_PREFIX)

        val pageOne = deltaOf(syncOk(alice, listOf(chatId to ZERO_CURSOR), messageLimit = PAGE_LIMIT), chatId)
        val pageOneSeqs = pageOne["messages"].map { it["seq"].asLong() }
        assertThat(pageOne["startAfterSeq"].asLong()).isEqualTo(ZERO_CURSOR)
        assertThat(pageOneSeqs).containsExactlyElementsOf(seqs.subList(0, PAGE_LIMIT))
        assertThat(pageOne["hasMore"].asBoolean()).isTrue()

        val pageTwo =
            deltaOf(
                syncOk(alice, listOf(chatId to pageOneSeqs.last()), messageLimit = PAGE_LIMIT),
                chatId,
            )
        val pageTwoSeqs = pageTwo["messages"].map { it["seq"].asLong() }
        assertThat(pageTwo["startAfterSeq"].asLong()).isEqualTo(pageOneSeqs.last())
        assertThat(pageTwoSeqs)
            .overridingErrorMessage("page two must continue strictly after page one — no overlap")
            .containsExactlyElementsOf(seqs.subList(PAGE_LIMIT, PAGE_LIMIT * PAGE_STEP))
        assertThat(pageTwo["hasMore"].asBoolean()).isTrue()

        val pageThree = deltaOf(syncOk(alice, listOf(chatId to pageTwoSeqs.last()), messageLimit = PAGE_LIMIT), chatId)
        val pageThreeSeqs = pageThree["messages"].map { it["seq"].asLong() }
        assertThat(pageThreeSeqs).containsExactlyElementsOf(listOf(seqs.last()))
        assertThat(pageThree["hasMore"].asBoolean())
            .overridingErrorMessage("the page that reaches the chat head closes the tail")
            .isFalse()
        assertThat(pageOneSeqs + pageTwoSeqs + pageThreeSeqs)
            .overridingErrorMessage("the pages must cover the backlog completely — no gaps")
            .containsExactlyElementsOf(seqs)

        val boundary = syncOk(alice, listOf(chatId to seqs.last()))
        assertThat(boundary["chats"].size())
            .overridingErrorMessage(
                "a cursor at the chat head leaves nothing undelivered — the empty answer is correct",
            ).isZero()
        assertThat(boundary["moreChats"].asBoolean()).isFalse()
    }

    /**
     * The contract default page of api-contract.md №26: without an
     * explicit `messageLimit` the delta carries at most 50 messages and
     * `hasMore` flags the tail (T001 `sync.message-page-size=50`); the
     * follow-up page finishes the backlog.
     */
    @Test
    fun `the default message page carries fifty entries with a hasMore tail`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, DEFAULT_PAGE_MESSAGES, DEFAULT_PAGE_TEXT_PREFIX)

        val first = deltaOf(syncOk(alice, listOf(chatId to ZERO_CURSOR)), chatId)
        assertThat(first["messages"].size())
            .overridingErrorMessage("the default messageLimit is 50 — the limit-free page must stop there")
            .isEqualTo(DEFAULT_MESSAGE_LIMIT)
        assertThat(first["messages"].map { it["seq"].asLong() })
            .containsExactlyElementsOf(seqs.subList(0, DEFAULT_MESSAGE_LIMIT))
        assertThat(first["hasMore"].asBoolean()).isTrue()

        val tail = deltaOf(syncOk(alice, listOf(chatId to seqs[DEFAULT_MESSAGE_LIMIT - INDEX_ONE])), chatId)
        assertThat(tail["messages"].map { it["seq"].asLong() })
            .containsExactlyElementsOf(listOf(seqs.last()))
        assertThat(tail["hasMore"].asBoolean()).isFalse()
    }

    /**
     * sync-protocol.md §5 truncation, the `deleted_up_to_seq` branch
     * (the 12-month window leg is untestable until archival 012): a
     * cursor below the per-user visibility floor — the exact №14 end
     * state — makes the delta carry `truncatedUpToSeq` with
     * `startAfterSeq` pinned to the floor; messages below are
     * inaccessible and never return (US1-5). The truncation delta
     * itself does NOT pin the delivery position (only the client's №25
     * ack does), and a cursor already above the floor is a plain resume
     * without the marker.
     */
    @Test
    fun `truncation pins the delta to the deletion watermark`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, TRUNCATION_MESSAGES, TRUNCATION_TEXT_PREFIX)
        val watermark = seqs[TRUNCATION_WATERMARK_INDEX]
        setDeletionWatermark(alice.id, chatId, watermark)

        val truncated = deltaOf(syncOk(alice, listOf(chatId to ZERO_CURSOR)), chatId)
        assertThat(truncated["truncatedUpToSeq"].asLong())
            .overridingErrorMessage("a cursor below the visibility floor must surface the truncation point")
            .isEqualTo(watermark)
        assertThat(truncated["startAfterSeq"].asLong())
            .overridingErrorMessage("the truncated delta must resume from the effective floor")
            .isEqualTo(watermark)
        assertThat(truncated["messages"].map { it["seq"].asLong() })
            .overridingErrorMessage(
                "messages below the deletion watermark are inaccessible and must not return (US1-5)",
            ).containsExactlyElementsOf(seqs.subList(TRUNCATION_WATERMARK_INDEX + INDEX_ONE, seqs.size))
        assertThat(truncated["hasMore"].asBoolean()).isFalse()
        assertThat(truncated["lastSeq"].asLong()).isEqualTo(seqs.last())
        assertThat(truncated.has("desynced"))
            .overridingErrorMessage("a truncation is not a desync — the repair marker must stay absent")
            .isFalse()
        assertThat(deliveredUpToSeq(alice.id, chatId))
            .overridingErrorMessage(
                "the truncation delta must not pin the position — the client's №25 ack does (FR-001)",
            ).isZero()

        val aboveFloor = deltaOf(syncOk(alice, listOf(chatId to seqs[ABOVE_WATERMARK_INDEX])), chatId)
        assertThat(aboveFloor.has("truncatedUpToSeq"))
            .overridingErrorMessage("a cursor already above the floor is a plain resume — no truncation marker")
            .isFalse()
        assertThat(aboveFloor["messages"].map { it["seq"].asLong() })
            .containsExactlyElementsOf(seqs.subList(ABOVE_WATERMARK_INDEX + INDEX_ONE, seqs.size))
    }

    /**
     * sync-protocol.md §5 future-cursor repair (a backup-restore
     * client): a client `upToSeq` beyond the chat head is NOT a request
     * error — the affected delta answers `desynced: true` +
     * `serverUpToSeq` (the actual server position, which the server
     * neither adopts nor rewinds) and resumes from it, while the other
     * chats sync normally; the identical repeat request stays safe.
     */
    @Test
    fun `a future cursor repairs per chat without failing the request`() {
        val (alice, bob, carol) = messagingTrio()
        val withBob = ensureDialog(alice, bob)
        val withCarol = ensureDialog(alice, carol)
        val fromBob = seedIncoming(bob, withBob, DESYNC_MESSAGES, DESYNC_TEXT_PREFIX)
        seedIncoming(carol, withCarol, DESYNC_PEER_MESSAGES, DESYNC_PEER_TEXT_PREFIX)
        val futureCursor = fromBob.last() + FUTURE_CURSOR_STEP

        repeat(SYNC_ROUNDS) { round ->
            val response = syncOk(alice, listOf(withBob to futureCursor, withCarol to ZERO_CURSOR))

            val repaired = deltaOf(response, withBob)
            assertThat(repaired["desynced"].asBoolean())
                .overridingErrorMessage("a cursor beyond the chat head must mark the chat desynced (round <%s>)", round)
                .isTrue()
            assertThat(repaired["serverUpToSeq"].asLong())
                .overridingErrorMessage("the repair must carry the actual server position (round <%s>)", round)
                .isEqualTo(deliveredUpToSeq(alice.id, withBob))
            assertThat(repaired["startAfterSeq"].asLong())
                .overridingErrorMessage(
                    "after the repair the delta resumes from the server position (round <%s>)",
                    round,
                ).isEqualTo(ZERO_CURSOR)
            assertThat(repaired["messages"].map { it["seq"].asLong() })
                .overridingErrorMessage("the repaired chat must still deliver its whole backlog (round <%s>)", round)
                .containsExactlyElementsOf(fromBob)
            assertThat(repaired.has("truncatedUpToSeq"))
                .overridingErrorMessage("a future cursor is a repair, not a truncation (round <%s>)", round)
                .isFalse()

            val healthy = deltaOf(response, withCarol)
            assertThat(healthy.has("desynced"))
                .overridingErrorMessage(
                    "the repair is per chat — the healthy dialog carries no marker (round <%s>)",
                    round,
                ).isFalse()
            assertThat(healthy["startAfterSeq"].asLong()).isZero()
            assertThat(healthy["messages"].size()).isEqualTo(DESYNC_PEER_MESSAGES)
        }

        assertThat(deliveredUpToSeq(alice.id, withBob))
            .overridingErrorMessage("the server never adopts a future client cursor as its position")
            .isZero()
        assertThat(deliveredUpToSeq(alice.id, withCarol)).isZero()
    }

    /**
     * api-contract.md №26: the operation is per-user — foreign (a real
     * dialog the caller never joined) and unknown chat ids in `cursors`
     * are silently ignored: 200 with ONLY the caller's own chats, no
     * 403/404 (the sharp contrast with the atomic №25 batch of T007).
     */
    @Test
    fun `foreign and unknown cursors are silently ignored`() {
        val (alice, bob, carol) = messagingTrio()
        val ownChat = ensureDialog(alice, bob)
        seedIncoming(bob, ownChat, IGNORE_MESSAGES, IGNORE_TEXT_PREFIX)
        val foreignChat = ensureDialog(bob, carol)
        seedIncoming(carol, foreignChat, IGNORE_MESSAGES, IGNORE_PEER_TEXT_PREFIX)

        val response =
            syncOk(
                alice,
                listOf(
                    ownChat to ZERO_CURSOR,
                    foreignChat to IGNORE_FOREIGN_CURSOR,
                    UUID.randomUUID() to IGNORE_UNKNOWN_CURSOR,
                ),
            )

        assertThat(response["chats"].map { it["chatId"].asText() })
            .overridingErrorMessage(
                "№26 is per-user: only the caller's dialogs may appear — stranger/unknown cursors ride along ignored",
            ).containsExactly(ownChat.toString())
        assertThat(response["moreChats"].asBoolean()).isFalse()
    }

    /**
     * The all-or-refusal edge of sync-protocol.md §3: the answer is one
     * read snapshot — a transient read failure of a SINGLE chat must
     * refuse the WHOLE request with 5xx instead of answering 200 with
     * the surviving chats («частичный список как полный» is forbidden).
     * The per-chat failure is injected deterministically at the storage
     * layer: a row-level-security predicate on `messages` raises for
     * the poisoned chat only (real PostgreSQL, no mocks, no application
     * invariant broken) and is fully reverted afterwards — proving the
     * refusal is temporary: the identical request answers 200 with both
     * chats again, and no delivery position moved.
     */
    @Test
    fun `a read failure of one chat refuses the whole sync with 5xx`() {
        val (alice, bob, carol) = messagingTrio()
        val withBob = ensureDialog(alice, bob)
        val withCarol = ensureDialog(alice, carol)
        seedIncoming(bob, withBob, REFUSAL_MESSAGES, REFUSAL_TEXT_PREFIX)
        seedIncoming(carol, withCarol, REFUSAL_MESSAGES, REFUSAL_PEER_TEXT_PREFIX)
        val cursors = listOf(withBob to ZERO_CURSOR, withCarol to ZERO_CURSOR)

        val healthy = syncOk(alice, cursors)
        assertThat(healthy["chats"].size())
            .overridingErrorMessage("the control read must see both undelivered chats")
            .isEqualTo(REFUSAL_CHATS)

        withPoisonedChatReads(withCarol) {
            val response = requestSync(alice, cursors)
            assertThat(response.statusCode.is5xxServerError)
                .overridingErrorMessage(
                    "a failed read of ONE chat must refuse the WHOLE request — a partial list may never parade " +
                        "as full, got <%s>: %s",
                    response.statusCode,
                    response.body,
                ).isTrue()
        }

        val recovered = syncOk(alice, cursors)
        assertThat(recovered["chats"].size())
            .overridingErrorMessage(
                "the refusal is temporary — the identical request must answer fully once the read recovers",
            ).isEqualTo(REFUSAL_CHATS)
        assertThat(deliveredUpToSeq(alice.id, withBob))
            .overridingErrorMessage("the refused sweep must leave the delivery position untouched")
            .isZero()
        assertThat(deliveredUpToSeq(alice.id, withCarol)).isZero()
    }

    /**
     * The skewed-clock edge of spec.md: order and bounds are defined by
     * the SERVER `seq` alone — with `created_at` deliberately inverted
     * (each later seq an hour EARLIER), the delta still replays strictly
     * ascending by `seq` while the timestamps run strictly backwards:
     * client times never influence the replay.
     */
    @Test
    fun `order and bounds follow server seq, not message timestamps`() {
        val (alice, bob, _) = messagingTrio()
        val chatId = ensureDialog(alice, bob)
        val seqs = seedIncoming(bob, chatId, CLOCK_MESSAGES, CLOCK_TEXT_PREFIX)
        val inverted =
            itJdbcTemplate.update(
                "UPDATE messages SET created_at = now() - (seq * interval '1 hour') WHERE chat_id = ?",
                chatId,
            )
        assertThat(inverted)
            .overridingErrorMessage("the timestamp inversion must reach every seeded row")
            .isEqualTo(CLOCK_MESSAGES)

        val messages = deltaOf(syncOk(alice, listOf(chatId to ZERO_CURSOR)), chatId)["messages"]
        assertThat(messages.map { it["seq"].asLong() })
            .overridingErrorMessage("the replay order must follow server seq, whatever the timestamps say")
            .containsExactlyElementsOf(seqs)
        val createdAt = messages.map { OffsetDateTime.parse(it["createdAt"].asText()).toInstant() }
        createdAt.zipWithNext().forEach { (current, next) ->
            assertThat(next.isBefore(current))
                .overridingErrorMessage("createdAt must run strictly backwards while seq runs forwards")
                .isTrue()
        }
    }

    /**
     * T032 (api-contract.md 006 §3, FR-012): the GROUP delta of №26 — the
     * exact contract field set with the group projection: `type:'group'`,
     * `title`, `memberCount` of the active roster and
     * `othersReadUpToSeq` = MAX of the OTHER actives' read watermarks
     * (✓✓ once any one of them has read; staged straight on
     * `chat_participants` — the end state the №17 GREATEST-update of
     * T036 produces; the caller's own mark never folds in), while the
     * peer projection renders as EXPLICIT nulls:
     * `peer`/`blockedByMe`/`peerReadUpToSeq`. The 005 replay rules stay
     * verbatim over the group: ascending by `seq`, seeded ids verbatim,
     * the caller's own outgoing included, delivery-bounded `unreadCount`,
     * no repair fields, and the sweep never writes the delivery position
     * (FR-001).
     */
    @Test
    fun `the group delta carries the roster projection with the peer fields null`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        addGroupContact(owner, bob.id)
        addGroupContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, listOf(bob.id, carol.id)))
        val senderCycle = listOf(owner, bob, carol)
        val seeded = seedGroupBacklog(chatId, senderCycle)
        stageReadWatermark(bob.id, chatId, seeded[GROUP_OTHER_READ_INDEX].seq)
        stageReadWatermark(owner.id, chatId, seeded[GROUP_HIGHER_READ_INDEX].seq)

        val response = syncOk(carol, listOf(chatId to ZERO_CURSOR))
        assertThat(response["chats"].size())
            .overridingErrorMessage(
                "a group with an undelivered backlog must produce exactly one delta, got <%s>",
                response,
            ).isEqualTo(ONE_DELTA)
        val delta = deltaOf(response, chatId)

        assertThat(fieldNames(delta))
            .overridingErrorMessage(
                "the group SyncChatDelta must carry exactly the contract fields, got <%s>",
                fieldNames(delta),
            ).containsExactlyInAnyOrderElementsOf(GROUP_DELTA_FIELDS)
        assertThat(delta["type"].asText()).isEqualTo("group")
        assertThat(delta["title"].asText()).isEqualTo(GROUP_TITLE)
        assertThat(delta["memberCount"].asLong())
            .overridingErrorMessage("memberCount must count the active roster — the creator plus both added")
            .isEqualTo(3)
        assertThat(delta["othersReadUpToSeq"].asLong())
            .overridingErrorMessage(
                "othersReadUpToSeq is the MAX of the OTHER actives' watermarks (FR-012, «read by at least " +
                    "one») — the caller's own mark never folds in",
            ).isEqualTo(seeded[GROUP_HIGHER_READ_INDEX].seq)
        assertNullField(delta, "peer")
        assertNullField(delta, "blockedByMe")
        assertNullField(delta, "peerReadUpToSeq")

        assertThat(delta["startAfterSeq"].asLong())
            .overridingErrorMessage("a zero cursor of a fresh group must resume from 0")
            .isZero()
        assertThat(delta["lastSeq"].asLong()).isEqualTo(seeded.last().seq)
        assertThat(delta["hasMore"].asBoolean())
            .overridingErrorMessage("a backlog within one default page must leave no tail")
            .isFalse()
        assertThat(delta["unreadCount"].asLong())
            .overridingErrorMessage(
                "unread is delivery-bounded (data-model entity 3): before any ack nothing may count, got <%s>",
                delta["unreadCount"],
            ).isZero()
        assertNoRepairFields(delta)
        assertReplayVerbatim(delta["messages"], seeded)

        assertThat(deliveredUpToSeq(carol.id, chatId))
            .overridingErrorMessage(
                "the №26 group sweep must NOT write the delivery position — only ack №25 does (FR-001)",
            ).isZero()
    }

    /**
     * T032 (api-contract.md 006 §3): the unified №26 answer — the group
     * delta and the caller's DIRECT delta ride ONE response ordered by the
     * last activity DESC, and the direct delta keeps the exact 0.5.0 field
     * set (`peer` resolves, `peerReadUpToSeq` stays the direct ✓✓ bound)
     * — no group field may leak into a direct delta.
     */
    @Test
    fun `the direct delta keeps its exact shape beside the group delta in one answer`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val dave = messagingUser("dave")
        addGroupContact(owner, bob.id)
        addGroupContact(owner, carol.id)
        val groupChat = chatIdOf(createGroupOk(owner, listOf(bob.id, carol.id)))
        seedChatBacklog(groupChat, UNIFIED_GROUP_MESSAGES, { owner }, UNIFIED_GROUP_TEXT_PREFIX)
        distinctMoment()
        val directChat = ensureDialog(carol, dave)
        val fromDave =
            seedChatBacklog(directChat, UNIFIED_DIRECT_MESSAGES, { dave }, UNIFIED_DIRECT_TEXT_PREFIX)
                .map { it.seq }

        val response = syncOk(carol, listOf(groupChat to ZERO_CURSOR, directChat to ZERO_CURSOR))

        assertThat(response["chats"].map { it["chatId"].asText() })
            .overridingErrorMessage("the unified №26 answer orders the deltas by last activity DESC — the direct first")
            .containsExactly(directChat.toString(), groupChat.toString())
        assertThat(response["moreChats"].asBoolean()).isFalse()

        val directDelta = deltaOf(response, directChat)
        assertThat(fieldNames(directDelta))
            .overridingErrorMessage(
                "the direct delta beside a group must keep the exact 0.5.0 field set, got <%s>",
                fieldNames(directDelta),
            ).containsExactlyInAnyOrderElementsOf(DIRECT_DELTA_FIELDS)
        assertThat(directDelta["peer"]["id"].asText())
            .overridingErrorMessage("the direct delta peer must resolve as before the groups")
            .isEqualTo(dave.id.toString())
        assertThat(directDelta["peerReadUpToSeq"].asLong())
            .overridingErrorMessage("the direct delta keeps the peer ✓✓ watermark (0 — the peer read nothing)")
            .isZero()
        assertThat(directDelta["messages"].map { it["seq"].asLong() })
            .containsExactlyElementsOf(fromDave)

        val groupDelta = deltaOf(response, groupChat)
        assertThat(groupDelta["type"].asText())
            .overridingErrorMessage("the group delta of the SAME answer must carry the type discrimination")
            .isEqualTo("group")
    }

    /**
     * Seeds [count] incoming messages (the peer is the sender) and
     * returns their `seq`s ascending — the controlled coordinates of
     * the №26 rules, without crossing the 30/min flood limit of the
     * real №16 path (T002 fixture).
     */
    private fun seedIncoming(
        sender: MessagingUser,
        chatId: UUID,
        count: Int,
        textPrefix: String,
    ): List<Long> = seedChatBacklog(chatId, count, { sender }, textPrefix).map { it.seq }

    /**
     * PG `now()` carries microsecond precision, so two seeded backlogs
     * within the same microsecond would tie on `created_at` — a sleep
     * keeps the last-activity DESC order of the unified answer
     * deterministic (the ChatListIT precedent).
     */
    private fun distinctMoment() {
        Thread.sleep(ORDER_SLEEP_MILLIS)
    }

    /**
     * Seeds the [GROUP_MESSAGES]-row group backlog with the MIXED member
     * attribution — the [senderCycle] repeats owner, bob and the caller
     * herself, so the delta replay proves the member attribution verbatim
     * and the caller's own outgoing included.
     */
    private fun seedGroupBacklog(
        chatId: UUID,
        senderCycle: List<MessagingUser>,
    ): List<SeededMessage> {
        val senderFor: (Int) -> MessagingUser = { index -> senderCycle[index % senderCycle.size] }
        return seedChatBacklog(chatId, GROUP_MESSAGES, senderFor, GROUP_TEXT_PREFIX)
    }

    /**
     * The 005 replay rules verbatim over a seeded group backlog:
     * ascending by `seq`, ids verbatim (no loss, no duplicates) and the
     * mixed sender attribution with the caller's own outgoing included.
     */
    private fun assertReplayVerbatim(
        messages: JsonNode,
        seeded: List<SeededMessage>,
    ) {
        assertThat(messages.size()).isEqualTo(seeded.size)
        assertThat(messages.map { it["seq"].asLong() })
            .overridingErrorMessage("the group delta must replay the backlog strictly ascending by seq")
            .containsExactlyElementsOf(seeded.map { it.seq })
        assertThat(messages.map { it["id"].asText() })
            .overridingErrorMessage("the group delta must replay the seeded ids verbatim — no loss, no duplicates")
            .containsExactlyElementsOf(seeded.map { it.id.toString() })
        assertThat(messages.map { it["senderId"].asText() })
            .overridingErrorMessage(
                "the group delta must replay the mixed member attribution and the caller's own outgoing verbatim",
            ).containsExactlyElementsOf(seeded.map { it.senderId.toString() })
    }

    /**
     * Contract №27 `POST /api/v1/groups` — the T032 group fixture: a
     * group of the creator plus [memberUserIds] from HIS contacts (№21
     * rows staged by [addGroupContact] first) → the stored `GroupView`.
     */
    private fun createGroupOk(
        owner: MessagingUser,
        memberUserIds: List<UUID>,
    ): JsonNode {
        val response =
            restTemplate.postForEntity(
                GROUPS_PATH,
                HttpEntity(
                    buildMap<String, Any> {
                        put("title", GROUP_TITLE)
                        put("memberUserIds", memberUserIds.map(UUID::toString))
                    },
                    jsonHeaders(owner),
                ),
                String::class.java,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№27 group fixture must answer 201, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    /** Contract №21 `POST /api/v1/contacts` — the №27 roster gate fixture. */
    private fun addGroupContact(
        user: MessagingUser,
        userId: UUID,
    ) {
        val response =
            restTemplate.postForEntity(
                CONTACTS_PATH,
                HttpEntity(mapOf("userId" to userId.toString()), jsonHeaders(user)),
                String::class.java,
            )
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "fixture contact №21 of <%s> → <%s> must succeed, got <%s>: %s",
                user.username,
                userId,
                response.statusCode,
                response.body,
            ).isTrue
    }

    private fun jsonHeaders(user: MessagingUser): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(user.accessToken)
        }

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view["chatId"].asText())

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /** A contract-REQUIRED nullable field must be PRESENT as JSON null (not omitted). */
    private fun assertNullField(
        node: JsonNode,
        field: String,
    ) {
        assertThat(node.has(field) && node[field].isNull)
            .overridingErrorMessage(
                "field <%s> must be present as JSON null on the group delta, got <%s>",
                field,
                node[field],
            ).isTrue
    }

    /**
     * Stages the №17 end state of a fellow member straight on the real
     * row (`last_read_seq` — the GREATEST-update T036 drives through the
     * HTTP path; the setDeletionWatermark precedent): the ✓✓ coordinates
     * of the FR-012 MIN-fold the group delta projects.
     */
    private fun stageReadWatermark(
        userId: UUID,
        chatId: UUID,
        upToSeq: Long,
    ) {
        val updated =
            itJdbcTemplate.update(
                "UPDATE chat_participants SET last_read_seq = ? WHERE chat_id = ? AND user_id = ?",
                upToSeq,
                chatId,
                userId,
            )
        assertThat(updated)
            .overridingErrorMessage("the read watermark fixture must land on the participant row")
            .isEqualTo(1)
    }

    /** №26 happy path → the parsed `SyncResponse` tree (status asserted verbatim). */
    private fun syncOk(
        user: MessagingUser,
        cursors: List<Pair<UUID, Long>>,
        chatLimit: Int? = null,
        messageLimit: Int? = null,
    ): JsonNode {
        val response = requestSync(user, cursors, chatLimit, messageLimit)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the №26 sync of <%s> must answer 200, got <%s>: %s",
                user.username,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)
    }

    /** The delta of [chatId] from a `SyncResponse` — asserted present. */
    private fun deltaOf(
        response: JsonNode,
        chatId: UUID,
    ): JsonNode {
        val delta = response["chats"].firstOrNull { it["chatId"].asText() == chatId.toString() }
        assertThat(delta)
            .overridingErrorMessage("the sync answer must carry the delta of chat <%s> in %s", chatId, response)
            .isNotNull
        return delta!!
    }

    /** The contract rule «пропущенные поля отсутствуют»: no truncation/repair markers when nothing happened. */
    private fun assertNoRepairFields(delta: JsonNode) {
        assertThat(delta.has("truncatedUpToSeq"))
            .overridingErrorMessage("truncatedUpToSeq must be absent when no truncation happened")
            .isFalse()
        assertThat(delta.has("desynced"))
            .overridingErrorMessage("desynced must be absent when the client cursor is sane")
            .isFalse()
        assertThat(delta.has("serverUpToSeq"))
            .overridingErrorMessage("serverUpToSeq must be absent outside the repair")
            .isFalse()
    }

    /**
     * Deterministically fails the SELECTs of ONE chat at the storage
     * layer: a plpgsql predicate raises for the poisoned [chatId] and
     * passes every other row, wired as a FOR SELECT row-level-security
     * policy (FORCEd so the table owner is covered too). Everything is
     * reverted in `finally` — the surrounding scenario data stays intact.
     *
     * The app pool connects as the NON-superuser `webchat_app` (created
     * by webchat_it_init.sql through AbstractIntegrationTest) and OWNS
     * the schema objects, so the FORCEd policy covers its sessions —
     * the injection would silently no-op against the Testcontainers
     * bootstrap superuser, who bypasses RLS unconditionally.
     */
    private fun withPoisonedChatReads(
        chatId: UUID,
        poisonedReads: () -> Unit,
    ) {
        itJdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION ${POISON_FUNCTION}(candidate uuid)
            RETURNS boolean LANGUAGE plpgsql AS '
            BEGIN
                IF candidate = ''$chatId''::uuid THEN
                    RAISE EXCEPTION ''sync-it: simulated read failure of chat %'', candidate;
                END IF;
                RETURN true;
            END
            '
            """.trimIndent(),
        )
        itJdbcTemplate.execute("DROP POLICY IF EXISTS ${POISON_POLICY} ON messages")
        try {
            itJdbcTemplate.execute(
                "CREATE POLICY ${POISON_POLICY} ON messages FOR SELECT USING (${POISON_FUNCTION}(chat_id))",
            )
            itJdbcTemplate.execute("ALTER TABLE messages ENABLE ROW LEVEL SECURITY")
            itJdbcTemplate.execute("ALTER TABLE messages FORCE ROW LEVEL SECURITY")
            poisonedReads()
        } finally {
            itJdbcTemplate.execute("DROP POLICY IF EXISTS ${POISON_POLICY} ON messages")
            itJdbcTemplate.execute("ALTER TABLE messages NO FORCE ROW LEVEL SECURITY")
            itJdbcTemplate.execute("ALTER TABLE messages DISABLE ROW LEVEL SECURITY")
            itJdbcTemplate.execute("DROP FUNCTION IF EXISTS ${POISON_FUNCTION}(uuid)")
        }
    }

    private companion object {
        const val ZERO_CURSOR = 0L
        const val ONE_DELTA = 1
        const val ONE_CHAT_PAGE = 1
        const val INDEX_ONE = 1
        const val OWN_OUTGOING_EVERY = 2
        const val PAGE_STEP = 2
        const val SYNC_ROUNDS = 2

        const val FULL_MESSAGES = 12
        const val CURSOR_MESSAGES = 10
        const val SERVER_CURSOR_INDEX = 4
        const val BEHIND_CURSOR_INDEX = 2
        const val AHEAD_CURSOR_INDEX = 7
        const val ORDER_OLDER_MESSAGES = 5
        const val ORDER_NEWER_MESSAGES = 3
        const val PAGE_MESSAGES = 7
        const val PAGE_LIMIT = 3
        const val DEFAULT_MESSAGE_LIMIT = 50
        const val DEFAULT_PAGE_MESSAGES = 51
        const val TRUNCATION_MESSAGES = 10
        const val TRUNCATION_WATERMARK_INDEX = 4
        const val ABOVE_WATERMARK_INDEX = 7
        const val DESYNC_MESSAGES = 5
        const val DESYNC_PEER_MESSAGES = 3
        const val FUTURE_CURSOR_STEP = 10L
        const val IGNORE_MESSAGES = 3
        const val IGNORE_FOREIGN_CURSOR = 5L
        const val IGNORE_UNKNOWN_CURSOR = 9L
        const val REFUSAL_MESSAGES = 2
        const val REFUSAL_CHATS = 2
        const val CLOCK_MESSAGES = 6

        const val POISON_FUNCTION = "webchat_sync_it_poison_read"
        const val POISON_POLICY = "webchat_sync_it_poison"

        const val FULL_TEXT_PREFIX = "sync-full"
        const val CLOSED_TEXT_PREFIX = "sync-closed"
        const val CURSOR_TEXT_PREFIX = "sync-cursor"
        const val ORDER_OLDER_TEXT_PREFIX = "sync-older"
        const val ORDER_NEWER_TEXT_PREFIX = "sync-newer"
        const val PAGE_TEXT_PREFIX = "sync-page"
        const val DEFAULT_PAGE_TEXT_PREFIX = "sync-default-page"
        const val TRUNCATION_TEXT_PREFIX = "sync-truncated"
        const val DESYNC_TEXT_PREFIX = "sync-desynced"
        const val DESYNC_PEER_TEXT_PREFIX = "sync-desync-peer"
        const val IGNORE_TEXT_PREFIX = "sync-ignore"
        const val IGNORE_PEER_TEXT_PREFIX = "sync-ignore-peer"
        const val REFUSAL_TEXT_PREFIX = "sync-refusal"
        const val REFUSAL_PEER_TEXT_PREFIX = "sync-refusal-peer"
        const val CLOCK_TEXT_PREFIX = "sync-clock"

        const val ORDER_SLEEP_MILLIS = 50L

        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val GROUP_TITLE = "Sync group delta IT"
        const val GROUP_TEXT_PREFIX = "sync-group"
        const val GROUP_MESSAGES = 6
        const val GROUP_OTHER_READ_INDEX = 1
        const val GROUP_HIGHER_READ_INDEX = 3
        const val UNIFIED_GROUP_MESSAGES = 2
        const val UNIFIED_GROUP_TEXT_PREFIX = "sync-unified-group"
        const val UNIFIED_DIRECT_MESSAGES = 2
        const val UNIFIED_DIRECT_TEXT_PREFIX = "sync-unified-direct"

        /** openapi.yaml 0.6.0 №26 shapes — every delta is pinned field-by-field. */
        val GROUP_DELTA_FIELDS =
            listOf(
                "chatId",
                "type",
                "title",
                "memberCount",
                "othersReadUpToSeq",
                "peer",
                "blockedByMe",
                "startAfterSeq",
                "messages",
                "hasMore",
                "peerReadUpToSeq",
                "unreadCount",
                "lastSeq",
            )
        val DIRECT_DELTA_FIELDS =
            listOf(
                "chatId",
                "peer",
                "blockedByMe",
                "startAfterSeq",
                "messages",
                "hasMore",
                "peerReadUpToSeq",
                "unreadCount",
                "lastSeq",
            )
    }
}
