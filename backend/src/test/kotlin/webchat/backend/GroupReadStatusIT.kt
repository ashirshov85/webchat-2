package webchat.backend

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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import webchat.backend.sync.SyncTestSupport
import java.time.Duration
import java.util.UUID

/**
 * T031 (tasks.md Phase 4, US2, constitution VI Test-First) — the READ
 * STATUS slice of a group chat: the FR-012 ✓✓ rule as the №13
 * `othersReadUpToSeq` projection and the №17 `chat.read` fanout of
 * realtime-group-events.md §3.7. One method per rule:
 *
 *  * ✓✓ = MIN: `othersReadUpToSeq` of a member is the
 *    `MIN(last_read_seq)` of the OTHER active members — the projection
 *    tracks the slowest reader and flips to the head only once everyone
 *    else has read that far (each member sees his OWN projection through
 *    №13, never a chat-wide value);
 *  * a group of one never sets ✓✓: with no other active members the
 *    projection answers 0 — the double tick is unreachable
 *    (api-contract.md №13 «0 при одиночной группе»), even though the
 *    lone member's own `myReadUpToSeq` advances normally;
 *  * monotonicity (FR-012): a repeated or smaller `upToSeq` is a silent
 *    no-op — the GREATEST-watermark row keeps its value, the MIN
 *    projection does not roll back and no `chat.read` is published — and
 *    a member added later cannot reset ✓✓ either: his watermark starts
 *    at the group position (FR-013), so the achieved MIN survives the
 *    roster growth;
 *  * `chat.read` fanout: a №17 advance in a group publishes the 004
 *    `ChatReadEvent` payload verbatim (`chatId`, `readUpToSeq`,
 *    `byUserId`) to EVERY active member EXCEPT the reader — the reader's
 *    own stream stays silent (§3.7);
 *  * T061 re-add edges (FR-002/FR-012, clarify 2026-09-25): the member
 *    leaving WITHOUT reading drops out of the ✓✓ condition (the MIN is
 *    recalculated over the CURRENT roster — the projection jumps to the
 *    head), and his №31 re-add restores him WITH his PRESERVED
 *    watermark, repinning №13 to the old MIN — a contract-legal
 *    server-side drop the live client rides out holding its max
 *    (realtime-group-events.md §3.7/§5.3);
 *  * T061 badge/history edge (FR-002/FR-02): a re-add is NOT a first
 *    addition — BOTH watermarks survive the removal→re-add cycle, the
 *    WHOLE history stays readable from the preserved mark and the
 *    delivery-bounded №12 badge counts EVERYTHING sent after his last
 *    read once he acks №25 — the absence period included.
 *
 * NOTE (TDD, constitution VI): written BEFORE the US2 implementation
 * task T036 — until it lands, №17 in a group is refused before any
 * watermark moves, so every method here fails (RED) by design. Every
 * method stages its own users/contacts/chats over the real 001 flows
 * (Testcontainers PG 17 + Redis 7, real HTTP, no mocks); message
 * backlogs ride the [seedChatBacklog] JDBC fixture, so no method spends
 * a single №16 send of the 30/min FR-011 allowance. The T061 legs
 * additionally ride №33 `DELETE /membership` — written BEFORE the US6
 * implementation task T063, so until it lands the leave answers the
 * empty 404 of the missing mapping and both T061 methods stay RED by
 * design.
 */
@Suppress("TooManyFunctions") // T031: one helper per fixture/probe of the read-status rules
class GroupReadStatusIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : SyncTestSupport() {
    /** First `chat.read` frame must arrive well inside this CI-tolerant budget (SC-001 functional leg). */
    private val deliveryBudget: Duration = Duration.ofSeconds(DELIVERY_BUDGET_SECONDS)

    /**
     * FR-012 core (data-model.md «Правила видимости»): №13 answers each
     * member his OWN `othersReadUpToSeq` — the `MIN(last_read_seq)` of
     * the other ACTIVE members. The projection tracks the slowest
     * reader: it leaves 0 only after EVERY other member has read, it
     * climbs step by step as the minimum of the individual marks, and it
     * reaches the head exactly when the last laggard catches up (✓✓ of
     * the sender's messages).
     */
    @Test
    fun `others read up to seq is the min of the other active members watermarks`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val carol = group.carol
        val chatId = group.chatId
        val seqs = seedChatBacklog(chatId, MIN_RULE_MESSAGES, { owner }, MIN_TEXT_PREFIX).map { it.seq }

        assertOthersReadUpToSeq(owner, chatId, FRESH_PROJECTION)
        assertOthersReadUpToSeq(bob, chatId, FRESH_PROJECTION)

        markReadOk(bob, chatId, seqs[1])
        assertOthersReadUpToSeq(owner, chatId, FRESH_PROJECTION)
        assertOthersReadUpToSeq(carol, chatId, FRESH_PROJECTION)

        markReadOk(carol, chatId, seqs[3])
        assertOthersReadUpToSeq(owner, chatId, seqs[1])
        assertOthersReadUpToSeq(bob, chatId, FRESH_PROJECTION)
        assertOthersReadUpToSeq(carol, chatId, FRESH_PROJECTION)

        markReadOk(bob, chatId, seqs.last())
        assertOthersReadUpToSeq(owner, chatId, seqs[3])

        markReadOk(carol, chatId, seqs.last())
        assertOthersReadUpToSeq(owner, chatId, seqs.last())

        val bobView = chatViewOk(bob, chatId)
        assertThat(bobView[MY_READ_UP_TO_SEQ_FIELD].asLong())
            .overridingErrorMessage("№13 must mirror the reader's own watermark in myReadUpToSeq")
            .isEqualTo(seqs.last())
        assertThat(readUpToSeq(owner.id, chatId))
            .overridingErrorMessage("the sender's own watermark must stay at 0 — the mark is per-user")
            .isEqualTo(FRESH_PROJECTION)
    }

    /**
     * FR-012 edge (api-contract.md №13): a group of a single member
     * never exposes ✓✓ — with no other active members the projection
     * answers 0 while `myReadUpToSeq` still advances normally, and the
     * lone member's own read publishes nothing (there is nobody to fan
     * out to — and his own stream never carries his own `chat.read`).
     */
    @Test
    fun `a single member group never sets the double tick`() {
        val owner = messagingUser("owner")
        val chatId = chatIdOf(createGroupOk(owner))
        val head = seedChatBacklog(chatId, SOLO_MESSAGES, { owner }, SOLO_TEXT_PREFIX).last().seq

        openUserEvents(owner).use { ownerStream ->
            markReadOk(owner, chatId, head)
            assertNoChatReadEvent(ownerStream, quietPeriod)
        }

        val view = chatViewOk(owner, chatId)
        assertThat(view[MY_READ_UP_TO_SEQ_FIELD].asLong())
            .overridingErrorMessage("the lone member's own mark must advance normally (myReadUpToSeq)")
            .isEqualTo(head)
        assertThat(view[OTHERS_READ_UP_TO_SEQ_FIELD].asLong())
            .overridingErrorMessage(
                "a group of one must answer othersReadUpToSeq=0 — ✓✓ is unreachable (FR-012 edge)",
            ).isEqualTo(FRESH_PROJECTION)
    }

    /**
     * FR-012 monotonicity: the per-member watermark is the 004 GREATEST
     * row — a repeated or smaller `upToSeq` answers 204 but moves
     * nothing and publishes NO `chat.read` (the advance frames of the
     * same live stream are consumed first, proving the rule and not a
     * dead stream); and ✓✓ is never RESET by roster growth: a member
     * added AFTER the head was read starts at the group position
     * (FR-013, badge 0), so the achieved MIN projection survives him
     * joining (a naive zero-initialized member would drop it to 0).
     */
    @Test
    fun `read marks and the min projection never roll back`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val carol = group.carol
        val chatId = group.chatId
        val seqs = seedChatBacklog(chatId, MONOTONE_MESSAGES, { owner }, MONOTONE_TEXT_PREFIX).map { it.seq }
        val mid = seqs[MONOTONE_MID_INDEX]
        val head = seqs.last()

        openUserEvents(owner).use { ownerStream ->
            markReadOk(bob, chatId, mid)
            markReadOk(carol, chatId, mid)
            assertChatReadFrame(ownerStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget), chatId, mid, bob)
            assertChatReadFrame(ownerStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget), chatId, mid, carol)
            assertOthersReadUpToSeq(owner, chatId, mid)

            markReadOk(bob, chatId, seqs.first())
            markReadOk(bob, chatId, mid)
            assertThat(readUpToSeq(bob.id, chatId))
                .overridingErrorMessage("a repeated or smaller upToSeq must not roll the watermark back (US4-5)")
                .isEqualTo(mid)
            assertOthersReadUpToSeq(owner, chatId, mid)
            assertNoChatReadEvent(ownerStream, quietPeriod)

            markReadOk(bob, chatId, head)
            markReadOk(carol, chatId, head)
            assertOthersReadUpToSeq(owner, chatId, head)

            val dave = messagingUser("dave")
            addContact(owner, dave.id)
            addMembersOk(owner, chatId, dave.id)
            assertThat(readUpToSeq(dave.id, chatId))
                .overridingErrorMessage(
                    "a first add must initialize the watermark at the group position — badge 0 (FR-013)",
                ).isEqualTo(head)
            assertOthersReadUpToSeq(owner, chatId, head)
        }
    }

    /**
     * realtime-group-events.md §3.7: a №17 advance in a group publishes
     * `chat.read` with the UNCHANGED 004 payload (`chatId`,
     * `readUpToSeq`, `byUserId` — `additionalProperties: false`) to
     * every active member EXCEPT the reader; the reader's own stream
     * receives nothing (his devices already hold the watermark locally).
     */
    @Test
    fun `chat read fans out to every active member except the reader`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val carol = group.carol
        val chatId = group.chatId
        val seqs = seedChatBacklog(chatId, FANOUT_MESSAGES, { owner }, FANOUT_TEXT_PREFIX).map { it.seq }
        val mid = seqs.first()
        val head = seqs.last()

        openUserEvents(owner).use { ownerStream ->
            openUserEvents(bob).use { bobStream ->
                openUserEvents(carol).use { carolStream ->
                    markReadOk(bob, chatId, mid)
                    assertChatReadFrame(ownerStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget), chatId, mid, bob)
                    assertChatReadFrame(carolStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget), chatId, mid, bob)
                    assertNoChatReadEvent(bobStream, quietPeriod)

                    markReadOk(carol, chatId, head)
                    assertChatReadFrame(ownerStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget), chatId, head, carol)
                    assertChatReadFrame(bobStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget), chatId, head, carol)
                    assertNoChatReadEvent(carolStream, quietPeriod)
                }
            }
        }
    }

    /**
     * FR-012 edge №2 (T061, clarify 2026-09-25): the ✓✓ condition is
     * recalculated over the CURRENT roster — the member leaving without
     * reading DROPS OUT of the MIN, so the sender's double tick becomes
     * reachable the moment he is gone. His №31 re-add restores him WITH
     * his PRESERVED watermark (FR-002), repinning №13 to the old MIN —
     * a contract-legal server-side drop the live client must ride out
     * holding its max (realtime-group-events.md §3.7/§5.3) — and the
     * per-member GREATEST marks never roll back: once everyone has read
     * to the new head, №13 answers the head again.
     */
    @Test
    fun `the unread member leaving frees the double tick and his re-add repins the min to the preserved mark`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val carol = group.carol
        val chatId = group.chatId
        val seqs = seedChatBacklog(chatId, READD_TICK_MESSAGES, { owner }, READD_TICK_TEXT_PREFIX).map { it.seq }
        val mark = seqs[READD_MARK_INDEX]
        val head = seqs.last()

        markReadOk(bob, chatId, mark)
        markReadOk(carol, chatId, head)
        assertOthersReadUpToSeq(owner, chatId, mark)

        leaveGroupOk(bob, chatId)

        assertOthersReadUpToSeq(owner, chatId, head)

        val absence = seedChatBacklog(chatId, READD_TICK_ABSENCE_MESSAGES, { owner }, READD_TICK_ABSENCE_TEXT_PREFIX)
        val newHead = absence.last().seq

        addMembersOk(owner, chatId, bob.id)

        assertOthersReadUpToSeq(owner, chatId, mark)

        markReadOk(bob, chatId, newHead)
        markReadOk(carol, chatId, newHead)
        assertOthersReadUpToSeq(owner, chatId, newHead)
    }

    /**
     * FR-002/FR-02 badge edge (T061, data-model.md §Сущность 2 «№31
     * добавление»): a re-added member is NOT a first addition — BOTH
     * watermarks survive the removal→re-add cycle (no re-initialization
     * at the head), the WHOLE history stays readable from his preserved
     * mark (№15 `?after=`) and the delivery-bounded №12 badge (the 005
     * formula) counts EVERYTHING sent after his last read once he acks
     * №25 — the absence period included; reading to the new head
     * converges the badge back to 0.
     */
    @Test
    fun `re-adding preserves both watermarks and the badge counts the absence backlog`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val chatId = group.chatId
        val seeded = seedChatBacklog(chatId, READD_BADGE_MESSAGES, { owner }, READD_BADGE_TEXT_PREFIX)
        val mark = seeded[READD_MARK_INDEX].seq
        val head = seeded.last().seq
        val ackedUnread = seeded.count { it.seq > mark }.toLong()

        deliveryAckOk(bob, chatId, head)
        markReadOk(bob, chatId, mark)
        assertThat(readUpToSeq(bob.id, chatId))
            .overridingErrorMessage("fixture: bob's read watermark must sit at his mark")
            .isEqualTo(mark)
        assertThat(deliveredUpToSeq(bob.id, chatId))
            .overridingErrorMessage("fixture: bob's delivery position must sit at the head")
            .isEqualTo(head)
        assertThat(unreadBadgeOf(bob, chatId))
            .overridingErrorMessage("baseline: the badge counts the acked-but-unread messages above bob's mark")
            .isEqualTo(ackedUnread)

        leaveGroupOk(bob, chatId)
        val absence = seedChatBacklog(chatId, READD_BADGE_ABSENCE_MESSAGES, { owner }, READD_BADGE_ABSENCE_TEXT_PREFIX)
        val newHead = absence.last().seq
        val unreadBacklog = seeded.filter { it.seq > mark } + absence

        addMembersOk(owner, chatId, bob.id)
        assertThat(readUpToSeq(bob.id, chatId))
            .overridingErrorMessage(
                "a re-add must PRESERVE the read watermark — no re-initialization at the head (FR-002)",
            ).isEqualTo(mark)
        assertThat(deliveredUpToSeq(bob.id, chatId))
            .overridingErrorMessage("a re-add must PRESERVE the delivery position (FR-002)")
            .isEqualTo(head)
        assertThat(unreadBadgeOf(bob, chatId))
            .overridingErrorMessage(
                "the badge must cross the removal→re-add cycle unchanged — the acked-but-unread backlog survives",
            ).isEqualTo(ackedUnread)

        val page = listMessagesAfterOk(bob, chatId, mark)
        assertThat(page.map { UUID.fromString(it["id"].asText()) })
            .overridingErrorMessage(
                "the re-added member must read the WHOLE history after his preserved mark (FR-02)",
            ).containsExactlyElementsOf(unreadBacklog.map { it.id })

        assertThat(requestSync(bob, listOf(chatId to mark)).statusCode)
            .overridingErrorMessage("№26 catch-up from the preserved mark must answer 200")
            .isEqualTo(HttpStatus.OK)
        deliveryAckOk(bob, chatId, newHead)
        assertThat(unreadBadgeOf(bob, chatId))
            .overridingErrorMessage(
                "after the №25 ack the badge must count EVERYTHING after the preserved mark — " +
                    "the absence period included (FR-002)",
            ).isEqualTo(unreadBacklog.size.toLong())

        markReadOk(bob, chatId, newHead)
        assertThat(unreadBadgeOf(bob, chatId))
            .overridingErrorMessage("reading to the new head must converge the badge back to 0")
            .isZero
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** One fully staged US2 fixture: a three-member group (owner + two added contacts). */
    private fun trioGroup(): GroupFixture {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        addContact(owner, bob.id)
        addContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id.toString(), carol.id.toString())))
        return GroupFixture(owner, bob, carol, chatId)
    }

    private data class GroupFixture(
        val owner: MessagingUser,
        val bob: MessagingUser,
        val carol: MessagingUser,
        val chatId: UUID,
    )

    /** Contract №27 `POST /api/v1/groups` → the stored `GroupView` body of the happy path. */
    private fun createGroupOk(
        owner: MessagingUser,
        title: String = GROUP_TITLE,
        memberUserIds: List<String> = emptyList(),
    ): JsonNode {
        val response =
            postJsonAuthorized(
                GROUPS_PATH,
                buildMap {
                    put(TITLE_FIELD, title)
                    if (memberUserIds.isNotEmpty()) put(MEMBER_USER_IDS_FIELD, memberUserIds)
                },
                owner,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    /** Contract №31 `POST /api/v1/groups/{chatId}/members` — the roster-growth fixture of the monotonicity leg. */
    private fun addMembersOk(
        adder: MessagingUser,
        chatId: UUID,
        userId: UUID,
    ) {
        val response =
            postJsonAuthorized(
                "$GROUPS_PATH/$chatId/members",
                mapOf(USER_IDS_FIELD to listOf(userId.toString())),
                adder,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№31 add of a contact must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
    }

    /**
     * Contract №33 `DELETE /api/v1/groups/{chatId}/membership` — the US6
     * voluntary leave of a plain member (T061 writes it BEFORE the T063
     * implementation — RED by design until the mapping exists).
     */
    private fun leaveGroupOk(
        user: MessagingUser,
        chatId: UUID,
    ) {
        val response =
            restTemplate.exchange(
                "$GROUPS_PATH/$chatId/membership",
                HttpMethod.DELETE,
                HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
                String::class.java,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№33 leave of a plain member must answer 204 (api-contract.md №33), got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /** Contract №25 happy path: the single-chat delivery ack must answer 204 (005). */
    private fun deliveryAckOk(
        user: MessagingUser,
        chatId: UUID,
        upToSeq: Long,
    ) {
        val response = deliveryAck(user, listOf(chatId to upToSeq))
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№25 ack of <%s> in <%s> up to <%d> must answer 204, got <%s>: %s",
                user.username,
                chatId,
                upToSeq,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /** The caller's №12 `unreadCount` of the group — the FR-013/FR-002 badge probe. */
    private fun unreadBadgeOf(
        user: MessagingUser,
        chatId: UUID,
    ): Long {
        val response = listChats(user)
        assertThat(response.statusCode)
            .overridingErrorMessage("№12 must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val item =
            objectMapper
                .readTree(response.body)["chats"]
                .toList()
                .singleOrNull { it["chatId"].asText() == chatId.toString() }
        assertThat(item)
            .overridingErrorMessage("the №12 list of <%s> must contain the group <%s>", user.username, chatId)
            .isNotNull
        return item!!["unreadCount"].asLong()
    }

    /** Contract №15 ascending page `?after=` — the re-added member's history probe. */
    private fun listMessagesAfterOk(
        user: MessagingUser,
        chatId: UUID,
        after: Long,
    ): List<JsonNode> {
        val response = listMessagesAfter(user, chatId, after)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№15 ?after= of a re-added member must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["messages"].toList()
    }

    /** Contract №21 `POST /api/v1/contacts` — fixture contact for the №27/№31 gates. */
    private fun addContact(
        user: MessagingUser,
        userId: UUID,
    ) {
        val response = postJsonAuthorized(CONTACTS_PATH, mapOf(USER_ID_FIELD to userId.toString()), user)
        assertThat(response.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "fixture contact №21 of <%s> → <%s> must succeed, got <%s>: %s",
                user.username,
                userId,
                response.statusCode,
                response.body,
            ).isTrue
    }

    private fun postJsonAuthorized(
        path: String,
        payload: Map<String, Any>,
        user: MessagingUser,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view["chatId"].asText())

    // ------------------------------------------------------------------
    // probes
    // ------------------------------------------------------------------

    /** Contract №17 happy path: a valid `upToSeq` must answer 204. */
    private fun markReadOk(
        user: MessagingUser,
        chatId: UUID,
        upToSeq: Long,
    ) {
        val response = markRead(user, chatId, upToSeq)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№17 of a group member at upToSeq=<%d> must answer 204, got <%s>: %s",
                upToSeq,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /** Contract №13 `GET /chats/{chatId}` of a group → the parsed `ChatView` body. */
    private fun chatViewOk(
        user: MessagingUser,
        chatId: UUID,
    ): JsonNode {
        val response =
            restTemplate.exchange(
                "/api/v1/chats/$chatId",
                HttpMethod.GET,
                HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
                String::class.java,
            )
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№13 of a group member must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)
    }

    /**
     * The FR-012 projection probe: the caller's №13 `othersReadUpToSeq`.
     * The field PRESENCE is asserted first (`isNumber`) — a missing field
     * would otherwise read as the Jackson default 0 and silently pass.
     */
    private fun assertOthersReadUpToSeq(
        user: MessagingUser,
        chatId: UUID,
        expected: Long,
    ) {
        val view = chatViewOk(user, chatId)
        assertThat(view.path(OTHERS_READ_UP_TO_SEQ_FIELD).isNumber)
            .overridingErrorMessage(
                "the group ChatView of <%s> must carry the numeric <%s>, view: %s",
                user.username,
                OTHERS_READ_UP_TO_SEQ_FIELD,
                view,
            ).isTrue
        assertThat(view.path(OTHERS_READ_UP_TO_SEQ_FIELD).asLong())
            .overridingErrorMessage(
                "№13 othersReadUpToSeq of <%s> must be the MIN of the other active watermarks <%d>, view: %s",
                user.username,
                expected,
                view,
            ).isEqualTo(expected)
    }

    /**
     * The §3.7 wire payload: exactly the 004 `ChatReadEvent` fields —
     * `chatId`/`readUpToSeq`/`byUserId` — with [reader] as `byUserId`,
     * never the receiving member.
     */
    private fun assertChatReadFrame(
        event: JsonNode,
        chatId: UUID,
        readUpToSeq: Long,
        reader: MessagingUser,
    ) {
        assertThat(event.fieldNames().asSequence().toList())
            .overridingErrorMessage("chat.read payload must have exactly the ChatReadEvent fields")
            .containsExactlyInAnyOrderElementsOf(CHAT_READ_EVENT_FIELDS)
        assertThat(UUID.fromString(event[CHAT_ID_FIELD].asText()))
            .overridingErrorMessage("chat.read.chatId must be the group chatId")
            .isEqualTo(chatId)
        assertThat(event[READ_UP_TO_SEQ_FIELD].asLong())
            .overridingErrorMessage("chat.read.readUpToSeq must be the newly advanced watermark")
            .isEqualTo(readUpToSeq)
        assertThat(UUID.fromString(event[BY_USER_ID_FIELD].asText()))
            .overridingErrorMessage("chat.read.byUserId must be the READER, not the receiving member")
            .isEqualTo(reader.id)
    }

    /**
     * The «no event» legs: no `chat.read` may arrive for the reader's own
     * mark (§3.7) or while no watermark advances (US4-5) — a bounded
     * quiet window after the 204.
     */
    private fun assertNoChatReadEvent(
        stream: UserEventsStream,
        quietPeriod: Duration,
    ) {
        val unexpected = runCatching { stream.awaitEvent(CHAT_READ_EVENT, quietPeriod) }
        assertThat(unexpected.exceptionOrNull())
            .overridingErrorMessage(
                "no chat.read may arrive for the reader's own mark or a non-advancing read, got <%s>",
                unexpected.getOrNull(),
            ).isNotNull
    }

    private companion object {
        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"

        const val CHAT_READ_EVENT = "chat.read"
        val CHAT_READ_EVENT_FIELDS = listOf(CHAT_ID_FIELD, READ_UP_TO_SEQ_FIELD, BY_USER_ID_FIELD)

        const val TITLE_FIELD = "title"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val USER_IDS_FIELD = "userIds"
        const val USER_ID_FIELD = "userId"
        const val CHAT_ID_FIELD = "chatId"
        const val READ_UP_TO_SEQ_FIELD = "readUpToSeq"
        const val BY_USER_ID_FIELD = "byUserId"
        const val MY_READ_UP_TO_SEQ_FIELD = "myReadUpToSeq"
        const val OTHERS_READ_UP_TO_SEQ_FIELD = "othersReadUpToSeq"

        const val GROUP_TITLE = "US2 read status IT group"

        const val MIN_TEXT_PREFIX = "min-rule"
        const val SOLO_TEXT_PREFIX = "solo"
        const val MONOTONE_TEXT_PREFIX = "monotone"
        const val FANOUT_TEXT_PREFIX = "fanout-read"

        /** T061 re-add fixtures: bob's preserved mark sits at index 1 of the pre-absence backlog. */
        const val READD_MARK_INDEX = 1
        const val READD_TICK_MESSAGES = 4
        const val READD_TICK_ABSENCE_MESSAGES = 2
        const val READD_BADGE_MESSAGES = 4
        const val READD_BADGE_ABSENCE_MESSAGES = 3
        const val READD_TICK_TEXT_PREFIX = "readmin-tick"
        const val READD_TICK_ABSENCE_TEXT_PREFIX = "readmin-tick-absence"
        const val READD_BADGE_TEXT_PREFIX = "readmin-badge"
        const val READD_BADGE_ABSENCE_TEXT_PREFIX = "readmin-badge-absence"

        /** Openapi 0.6.0 №13: 0 — nobody else has read yet. */
        const val FRESH_PROJECTION = 0L

        const val MIN_RULE_MESSAGES = 5
        const val SOLO_MESSAGES = 3
        const val MONOTONE_MESSAGES = 6
        const val MONOTONE_MID_INDEX = 3
        const val FANOUT_MESSAGES = 2
        const val DELIVERY_BUDGET_SECONDS = 5L

        /** Bounded quiet window proving the no-event legs publish nothing. */
        val quietPeriod: Duration = Duration.ofMillis(QUIET_PERIOD_MILLIS)
        const val QUIET_PERIOD_MILLIS = 1_500L
    }
}
