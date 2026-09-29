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
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.sync.SyncTestSupport
import java.time.Duration
import java.util.UUID

/**
 * T030 (tasks.md Phase 4, US2, constitution VI Test-First) — the MESSAGING
 * slice of group chats: contract №16/№15/№17/№25/№26 over a `kind='group'`
 * chat carry every 004/005 guarantee unchanged, with the group-specific
 * fanout rule of realtime-group-events.md §3.7. One method per rule:
 *
 *  * fanout: a №16 send publishes `message.created` to EVERY active member
 *    EXCEPT the sender (FR-011) — in a direct dialog both participants get
 *    the frame (004), in a group the sender's own stream stays silent;
 *  * dedup: a retry of the recorded `clientMessageId` answers `200` with
 *    the same row, adds nothing to №15 and publishes NO second frame
 *    (SC-002 — 0 duplicates in retries);
 *  * order: interleaved senders produce one server `seq` order — every
 *    member's SSE frames and №15 history replay it identically (SC-002);
 *  * reconnect: a member who drops the stream and reconnects recovers the
 *    whole offline gap through №26 sync — ascending, own outgoing
 *    included, no losses, no duplicates (SC-002);
 *  * flood: the 30-per-minute FR-011 allowance holds in groups — the burst
 *    past it is refused `429 flood_limit` + `Retry-After` and writes
 *    nothing (SC-001/SC-002);
 *  * blocking edge (spec.md Assumptions): a pair blocked in their DIRECT
 *    dialog (№23 active, №16 refused there) still exchanges group messages
 *    and each other's frames — block marks never apply to groups;
 *  * interim privacy floor (api-contract.md §3, until the full T054/US5
 *    matrix): №15–№17 and №25 answer `404 chat_not_found` for a unknown
 *    chatId and `403 not_participant` on an existing group without an
 *    active membership row (a never-member AND a `state='removed'` row),
 *    and the №25 batch is refused ATOMICALLY — no partial acks survive.
 *
 * The 4096-char text validation is NOT re-tested here: it is the shared
 * №16 path owned by the 004 suites (MessageValidationIT).
 *
 * NOTE (TDD, constitution VI): written BEFORE the US2 implementation tasks
 * (T034–T037) — until they land, №16 in a group is refused before any
 * record, so every method here fails (RED) by design. Every method stages
 * its own users/contacts/chats over the real 001 flows (Testcontainers
 * PG 17 + Redis 7, real HTTP, no mocks), so no accounts, buckets or rows
 * leak between methods; bulk backlogs ride the [seedChatBacklog] JDBC
 * fixture to stay under the 30/min send allowance.
 */
@Suppress("TooManyFunctions", "LargeClass") // T030: one method per US2 rule of tasks.md
class GroupRealtimeIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : SyncTestSupport() {
    /** First `message.created` frame must arrive well inside this CI-tolerant budget (SC-001 functional leg). */
    private val deliveryBudget: Duration = Duration.ofSeconds(DELIVERY_BUDGET_SECONDS)

    /**
     * FR-011 fanout rule (realtime-group-events.md §3.7): the sender's POST
     * publishes `message.created` to every OTHER active member — with the
     * payload repeating the №16 answer verbatim — while the sender's own
     * stream receives nothing (unlike a direct dialog, where the sender's
     * other devices converge).
     */
    @Test
    fun `message created fans out to every active member except the sender`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val carol = group.carol
        val chatId = group.chatId

        openUserEvents(owner).use { ownerStream ->
            openUserEvents(bob).use { bobStream ->
                openUserEvents(carol).use { carolStream ->
                    val ownerStored = sendMessageOk(owner, chatId, OWNER_FANOUT_TEXT)
                    assertFanoutFrame(bobStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget), chatId, ownerStored)
                    assertFanoutFrame(
                        carolStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget),
                        chatId,
                        ownerStored,
                    )
                    assertNoEventFollows(ownerStream, MESSAGE_CREATED_EVENT, NO_FRAME_WINDOW)

                    val carolStored = sendMessageOk(carol, chatId, CAROL_FANOUT_TEXT)
                    assertFanoutFrame(
                        ownerStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget),
                        chatId,
                        carolStored,
                    )
                    assertFanoutFrame(bobStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget), chatId, carolStored)
                    assertNoEventFollows(carolStream, MESSAGE_CREATED_EVENT, NO_FRAME_WINDOW)
                }
            }
        }
    }

    /**
     * SC-002 dedup: a sequential retry of the recorded `clientMessageId`
     * answers `200` with the SAME row, leaves exactly ONE record in №15
     * (for every member) and publishes NO second `message.created` frame —
     * the group channel never turns a client retry into a duplicate.
     */
    @Test
    fun `retried client message id leaves one record one frame and no duplicates`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val chatId = group.chatId
        val clientMessageId = UUID.randomUUID()

        openUserEvents(bob).use { stream ->
            val first = sendMessage(owner, chatId, DEDUP_FIRST_TEXT, clientMessageId)
            assertThat(first.statusCode)
                .overridingErrorMessage(
                    "the first group send of a fresh id must be created with 201, got <%s>: %s",
                    first.statusCode,
                    first.body,
                ).isEqualTo(HttpStatus.CREATED)
            val retry = sendMessage(owner, chatId, DEDUP_RETRY_TEXT, clientMessageId)
            assertThat(retry.statusCode)
                .overridingErrorMessage(
                    "a retry of a recorded id in a group must dedup to 200, got <%s>: %s",
                    retry.statusCode,
                    retry.body,
                ).isEqualTo(HttpStatus.OK)
            assertThat(objectMapper.readTree(retry.body))
                .overridingErrorMessage("the dedup answer must be the SAME stored record (FR-004)")
                .isEqualTo(objectMapper.readTree(first.body))

            val frame = stream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)
            assertThat(frame["message"]["id"].asText())
                .overridingErrorMessage("the frame must carry the retried message exactly once — by its id")
                .isEqualTo(clientMessageId.toString())
            assertNoEventFollows(stream, MESSAGE_CREATED_EVENT, NO_FRAME_WINDOW)
        }

        for ((member, label) in listOf(owner to "owner", bob to "bob")) {
            val history = historyAscending(member, chatId)
            assertThat(history.map { it["id"].asText() })
                .overridingErrorMessage(
                    "the retry must leave exactly ONE record in №15 for <%s> (SC-002), got: %s",
                    label,
                    history.map { it["id"].asText() },
                ).containsExactly(clientMessageId.toString())
            assertThat(history.single()["text"].asText())
                .overridingErrorMessage("the stored text must be the FIRST send's text for <%s>", label)
                .isEqualTo(DEDUP_FIRST_TEXT)
        }
        assertThat(countMessageRows(chatId))
            .overridingErrorMessage("the retry must not add a row")
            .isEqualTo(1)
    }

    /**
     * SC-002/SC-003 order: interleaved group senders produce one server
     * `seq` order — every member's `message.created` frames arrive in that
     * order (each seeing the messages of the OTHERS, §3.7), and the №15
     * history of all three members replays the identical full order.
     */
    @Test
    fun `every member observes the identical seq order in frames and history`() {
        val group = trioGroup()
        val senders = listOf(group.owner, group.bob, group.carol)
        val chatId = group.chatId

        openUserEvents(group.owner).use { ownerStream ->
            openUserEvents(group.bob).use { bobStream ->
                openUserEvents(group.carol).use { carolStream ->
                    val acked = sendInterleaved(senders, chatId)
                    val globalOrder = acked.map { it["id"].asText() }

                    assertFramesInOrder(group.owner, ownerStream, acked)
                    assertFramesInOrder(group.bob, bobStream, acked)
                    assertFramesInOrder(group.carol, carolStream, acked)
                    assertHistoriesIdentical(senders, chatId, globalOrder)
                }
            }
        }
    }

    /**
     * The interleaved №16 sweep of the order fixture: [senders] write in
     * strict rotation and every answer must be `201` — the server `seq`
     * mirrors the send order without duplicates.
     */
    private fun sendInterleaved(
        senders: List<MessagingUser>,
        chatId: UUID,
    ): List<JsonNode> {
        val acked =
            (0 until ORDER_MESSAGE_COUNT).map { index ->
                val sender = senders[index % senders.size]
                val response = sendMessage(sender, chatId, "$ORDER_TEXT_PREFIX-$index")
                assertThat(response.statusCode)
                    .overridingErrorMessage(
                        "the interleaved group sends must all be created with 201, got <%s>: %s",
                        response.statusCode,
                        response.body,
                    ).isEqualTo(HttpStatus.CREATED)
                objectMapper.readTree(response.body)
            }
        assertThat(acked.map { it["seq"].asLong() })
            .overridingErrorMessage("the server seq must mirror the strict send order")
            .isSorted()
            .doesNotHaveDuplicates()
        return acked
    }

    /**
     * §3.7/SC-003, wire leg: [member] receives the messages of the OTHERS —
     * exactly the subsequence of [acked] he did not author — in the server
     * `seq` order.
     */
    private fun assertFramesInOrder(
        member: MessagingUser,
        stream: UserEventsStream,
        acked: List<JsonNode>,
    ) {
        val expected =
            acked
                .filter { it["senderId"].asText() != member.id.toString() }
                .map { it["id"].asText() }
        val frames = awaitCreatedFrames(stream, expected.size)
        assertThat(frames.map { it["message"]["id"].asText() })
            .overridingErrorMessage(
                "<%s> must receive the OTHERS' messages in the server seq order (§3.7/SC-003), got: %s",
                member.username,
                frames.map { it["message"]["id"].asText() },
            ).containsExactlyElementsOf(expected)
        assertThat(frames.map { it["message"]["seq"].asLong() })
            .overridingErrorMessage("the frames of <%s> must arrive in ascending seq", member.username)
            .isSorted()
    }

    /** SC-003, №15 leg: every member's history replays the identical full order, ascending, no duplicates. */
    private fun assertHistoriesIdentical(
        members: List<MessagingUser>,
        chatId: UUID,
        globalOrder: List<String>,
    ) {
        val histories = members.associateWith { member -> historyAscending(member, chatId) }
        histories.forEach { (member, history) ->
            assertThat(history.map { it["id"].asText() })
                .overridingErrorMessage(
                    "№15 of <%s> must replay the identical full order (SC-003), got: %s",
                    member.username,
                    history.map { it["id"].asText() },
                ).containsExactlyElementsOf(globalOrder)
            assertThat(history.map { it["seq"].asLong() })
                .overridingErrorMessage("№15 of <%s> must be ascending without duplicates", member.username)
                .isSorted()
                .doesNotHaveDuplicates()
        }
        val reference = histories.values.first().map { it["id"].asText() }
        histories.forEach { (member, history) ->
            assertThat(history.map { it["id"].asText() })
                .overridingErrorMessage("№15 of <%s> must equal the other members' views", member.username)
                .containsExactlyElementsOf(reference)
        }
    }

    /**
     * SC-002 reconnect leg: the member receives live frames up to a cursor,
     * DROPS the stream (offline), the chat keeps moving — a JDBC-seeded
     * backlog (mixed senders, his own outgoing included) plus a real №16
     * send — then reconnects and runs №26 with the last seen cursor: the
     * delta replays the WHOLE gap ascending, exactly once, own outgoing
     * included, and never writes the delivery position (FR-001 of 005).
     */
    @Test
    fun `reconnect sync replays the offline gap without losses or duplicates`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val chatId = group.chatId

        var cursor = 0L
        openUserEvents(bob).use { bobStream ->
            val liveSeqs =
                (0 until LIVE_MESSAGES).map { index ->
                    sendOkSeq(owner, chatId, "$LIVE_TEXT_PREFIX-$index")
                }
            val frames = awaitCreatedFrames(bobStream, LIVE_MESSAGES)
            assertThat(frames.map { it["message"]["seq"].asLong() })
                .containsExactlyElementsOf(liveSeqs)
            cursor = liveSeqs.last()
        }

        val seeded =
            seedChatBacklog(chatId, OFFLINE_SEEDED_MESSAGES, { index ->
                if (index % OWN_OUTGOING_EVERY == 0) bob else owner
            }, OFFLINE_TEXT_PREFIX)
        val offlineLiveSeq = sendOkSeq(owner, chatId, OFFLINE_LIVE_TEXT)
        assertThat(offlineLiveSeq)
            .overridingErrorMessage("the offline-phase live send must land above the seeded backlog")
            .isGreaterThan(seeded.last().seq)
        val offlineLiveId = offlineLiveIdOf(chatId, offlineLiveSeq)

        openUserEvents(bob).use { /* reconnect: a fresh №18 subscription */ }

        val response = requestSync(bob, listOf(chatId to cursor))
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№26 of a reconnected group member must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val delta = groupDeltaOf(objectMapper.readTree(response.body), chatId)
        assertThat(delta["startAfterSeq"].asLong())
            .overridingErrorMessage("the №26 delta must resume exactly at the client cursor")
            .isEqualTo(cursor)
        assertThat(delta["hasMore"].asBoolean())
            .overridingErrorMessage("the gap within one page must leave no tail")
            .isFalse
        val replayed = delta["messages"].toList()
        val expectedIds = seeded.map { it.id.toString() } + offlineLiveId
        assertThat(replayed.map { it["id"].asText() })
            .overridingErrorMessage(
                "№26 must replay the WHOLE offline gap exactly once — no losses, no duplicates (SC-002), got: %s",
                replayed.map { it["id"].asText() },
            ).containsExactlyElementsOf(expectedIds)
        assertThat(replayed.map { it["senderId"].asText() })
            .overridingErrorMessage(
                "the replay must include the member's OWN outgoing messages in order (005 semantics)",
            ).containsExactlyElementsOf(seeded.map { it.senderId.toString() } + owner.id.toString())
        assertThat(replayed.map { it["seq"].asLong() })
            .overridingErrorMessage("the replay must be strictly ascending by seq")
            .isSorted()
        assertThat(deliveredUpToSeq(bob.id, chatId))
            .overridingErrorMessage("№26 must NOT write the delivery position — only ack №25 does (FR-001)")
            .isZero()
    }

    /**
     * FR-011 in groups (SC-001/SC-002 leg): the 30-per-minute send
     * allowance of 004 holds unchanged — a fresh-id burst past it is
     * refused `429 flood_limit` + integral `Retry-After`, and NOTHING is
     * written: the group holds exactly the accepted records in №15 and in
     * the table.
     */
    @Test
    fun `flood limit holds in groups past thirty per minute with nothing written`() {
        val group = trioGroup()
        val owner = group.owner
        val bob = group.bob
        val chatId = group.chatId

        val burst = sendUntilFlood(owner, chatId, FLOOD_TEXT_PREFIX)

        assertThat(burst.accepted)
            .overridingErrorMessage(
                "the full 30-message allowance of FR-011 must be spendable in a group before any 429, got %d",
                burst.accepted.size,
            ).hasSizeGreaterThanOrEqualTo(WINDOW_MESSAGES)
        assertFloodRejection(burst.rejection)

        assertThat(countMessageRows(chatId))
            .overridingErrorMessage(
                "the 429 refusal must write no row: messages count <%d> must equal the accepted sends <%d>",
                countMessageRows(chatId),
                burst.accepted.size,
            ).isEqualTo(burst.accepted.size)
        val history = historyAscending(bob, chatId)
        assertThat(history.map { it["id"].asText() })
            .overridingErrorMessage(
                "the refused burst must leave the group untouched for the members — exactly the accepted records",
            ).containsExactlyElementsOf(burst.accepted.map { it.toString() })
        assertThat(history.map { it["text"].asText() })
            .overridingErrorMessage("the refused draft text must appear nowhere in the group history")
            .doesNotContain(refusedText(FLOOD_TEXT_PREFIX, burst.accepted.size + 1))
    }

    /**
     * The blocking edge of spec.md Assumptions: Alice blocks Bob (№23) —
     * their DIRECT dialog is verifiably dead (Bob's №16 there is `403
     * you_are_blocked`) — yet inside the shared group both keep writing
     * (201) and receiving each other's `message.created` frames: block
     * marks apply to the direct pair only, never to groups (FR-011).
     */
    @Test
    fun `a pair blocked in their dialog still exchanges group messages`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id.toString())))
        val directChatId = ensureChatOk(owner, bob.id)

        val block = exchangeAuthorized(owner, HttpMethod.PUT, "$USERS_PATH/${bob.id}/block")
        assertThat(block.statusCode)
            .overridingErrorMessage(
                "fixture №23 block of the direct pair must answer 204, got <%s>: %s",
                block.statusCode,
                block.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        val directRefusal = sendMessage(bob, directChatId, BLOCKED_DIRECT_TEXT)
        assertThat(directRefusal.statusCode)
            .overridingErrorMessage("the fixture block must be verifiably active in the direct dialog")
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(directRefusal, HttpStatus.FORBIDDEN, CHAT_FIELD, YOU_ARE_BLOCKED)

        var fromBlocked: JsonNode
        var fromBlocker: JsonNode
        openUserEvents(owner).use { ownerStream ->
            openUserEvents(bob).use { bobStream ->
                fromBlocked = sendMessageOk(bob, chatId, BLOCKED_GROUP_TEXT_BOB)
                assertThat(fromBlocked["senderId"].asText()).isEqualTo(bob.id.toString())
                fromBlocker = sendMessageOk(owner, chatId, BLOCKED_GROUP_TEXT_OWNER)
                assertThat(fromBlocker["senderId"].asText()).isEqualTo(owner.id.toString())

                assertFanoutFrame(ownerStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget), chatId, fromBlocked)
                assertFanoutFrame(bobStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget), chatId, fromBlocker)
            }
        }

        val history = historyAscending(bob, chatId)
        assertThat(history.map { it["id"].asText() })
            .overridingErrorMessage("both group messages of the blocked pair must survive in the group history")
            .containsExactly(fromBlocked["id"].asText(), fromBlocker["id"].asText())
    }

    /**
     * The interim privacy floor of api-contract.md §3 (the FULL matrix is
     * T054/US5): №15–№17 and №25 keep the 004 semantics on group ids — an
     * unknown chatId answers `404 chat_not_found`; an EXISTING group
     * without an active membership row (a never-member and a
     * `state='removed'` row) answers `403 not_participant`; and the №25
     * batch is refused ATOMICALLY — the caller's own valid item of the
     * same batch is not applied.
     */
    @Test
    fun `chat paths and ack batches keep the interim group privacy floor`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val dave = messagingUser("dave")
        addContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, memberUserIds = listOf(bob.id.toString())))
        seedChatBacklog(chatId, PRIVACY_SEEDED_MESSAGES, { owner }, PRIVACY_TEXT_PREFIX)
        val head = lastSeqOf(chatId)
        assertThat(head)
            .overridingErrorMessage("the fixture must seed the group above the zero head (valid №17/№25 bodies)")
            .isPositive
        val ownChat = ensureChatOk(carol, dave.id)
        val ownSeq = seedChatBacklog(ownChat, PRIVACY_SEEDED_MESSAGES, { dave }, OWN_TEXT_PREFIX).last().seq
        val unknownChat = UUID.randomUUID()

        assertProblem(listMessages(owner, unknownChat), HttpStatus.NOT_FOUND, CHAT_FIELD, CHAT_NOT_FOUND)
        assertProblem(
            sendMessage(owner, unknownChat, PRIVACY_STRANGER_TEXT),
            HttpStatus.NOT_FOUND,
            CHAT_FIELD,
            CHAT_NOT_FOUND,
        )
        assertProblem(markRead(owner, unknownChat, head), HttpStatus.NOT_FOUND, CHAT_FIELD, CHAT_NOT_FOUND)
        assertProblem(deliveryAck(owner, listOf(unknownChat to head)), HttpStatus.NOT_FOUND, CHAT_FIELD, CHAT_NOT_FOUND)

        assertProblem(listMessages(carol, chatId), HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
        assertProblem(
            sendMessage(carol, chatId, PRIVACY_STRANGER_TEXT),
            HttpStatus.FORBIDDEN,
            CHAT_FIELD,
            NOT_PARTICIPANT,
        )
        assertProblem(markRead(carol, chatId, head), HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)

        val batch = deliveryAck(carol, listOf(ownChat to ownSeq, chatId to head))
        assertProblem(batch, HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
        assertThat(deliveredUpToSeq(carol.id, ownChat))
            .overridingErrorMessage("a refused №25 batch must leave NO partial effects — it is all-or-refusal")
            .isZero()
        assertThat(deliveryAck(carol, listOf(ownChat to ownSeq)).statusCode)
            .overridingErrorMessage("after the refused batch a valid single ack must still land")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deliveredUpToSeq(carol.id, ownChat)).isEqualTo(ownSeq)

        flipToRemoved(bob.id, chatId)
        assertProblem(listMessages(bob, chatId), HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
        assertProblem(sendMessage(bob, chatId, PRIVACY_REMOVED_TEXT), HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
        assertProblem(markRead(bob, chatId, head), HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
        assertProblem(deliveryAck(bob, listOf(chatId to head)), HttpStatus.FORBIDDEN, CHAT_FIELD, NOT_PARTICIPANT)
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

    /** Contract №27 `POST /api/v1/groups` — raw response for status/error assertions. */
    private fun createGroup(
        owner: MessagingUser,
        title: String,
        memberUserIds: List<String> = emptyList(),
    ): ResponseEntity<String> =
        postJsonAuthorized(
            GROUPS_PATH,
            buildMap {
                put(TITLE_FIELD, title)
                if (memberUserIds.isNotEmpty()) put(MEMBER_USER_IDS_FIELD, memberUserIds)
            },
            owner,
        )

    /** №27 happy path → the stored `GroupView` body. */
    private fun createGroupOk(
        owner: MessagingUser,
        title: String = GROUP_TITLE,
        memberUserIds: List<String> = emptyList(),
    ): JsonNode {
        val response = createGroup(owner, title, memberUserIds)
        assertThat(response.statusCode)
            .overridingErrorMessage("№27 create must answer 201, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.CREATED)
        return objectMapper.readTree(response.body)
    }

    /** Contract №21 `POST /api/v1/contacts` — fixture contact for the №27 roster gate. */
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

    private fun exchangeAuthorized(
        user: MessagingUser,
        method: HttpMethod,
        path: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Unit>(Unit, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    private fun chatIdOf(view: JsonNode): UUID = UUID.fromString(view["chatId"].asText())

    /**
     * The exact №32/№33 participant end state (T008's `removeMember`
     * semantics) staged straight on the table: the US3/US6 operations are
     * not implemented yet, but the interim gate of api-contract.md §3 must
     * already treat the row as a non-participant.
     */
    private fun flipToRemoved(
        userId: UUID,
        chatId: UUID,
    ) {
        val updated =
            jdbcTemplate.update(
                """
                UPDATE chat_participants
                SET state = 'removed', role = 'member'
                WHERE chat_id = ? AND user_id = ?
                """.trimIndent(),
                chatId,
                userId,
            )
        assertThat(updated)
            .overridingErrorMessage("the removed-membership fixture must land on the participant row")
            .isEqualTo(1)
    }

    private fun countMessageRows(chatId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages WHERE chat_id = ?", Int::class.java, chatId) ?: 0

    // ------------------------------------------------------------------
    // probes
    // ------------------------------------------------------------------

    /** №15 read projected to ascending `seq` (the group reading direction). */
    private fun historyAscending(
        user: MessagingUser,
        chatId: UUID,
    ): List<JsonNode> {
        val response = listMessages(user, chatId)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "№15 of a group member must answer 200, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["messages"].toList().reversed()
    }

    /** The caller's №26 delta of the group — the reconnect replay source. */
    private fun groupDeltaOf(
        syncBody: JsonNode,
        chatId: UUID,
    ): JsonNode {
        val delta =
            syncBody["chats"]
                .toList()
                .singleOrNull { it["chatId"].asText() == chatId.toString() }
        assertThat(delta)
            .overridingErrorMessage(
                "the №26 answer must carry exactly one delta of the group <%s>, got: %s",
                chatId,
                syncBody,
            ).isNotNull
        return delta!!
    }

    /** Resolves the id of the live offline-phase send by its known seq — the replay tail expectation. */
    private fun offlineLiveIdOf(
        chatId: UUID,
        seq: Long,
    ): String =
        jdbcTemplate.queryForObject(
            "SELECT id::text FROM messages WHERE chat_id = ? AND seq = ?",
            String::class.java,
            chatId,
            seq,
        ) ?: error("the offline live message seq=$seq of chat $chatId must resolve its id")

    /** RFC 9457 refusal probe: [expected] status with `errors.[field] = [code]` verbatim. */
    private fun assertProblem(
        response: ResponseEntity<String>,
        expected: HttpStatus,
        field: String,
        code: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "expected %s with errors.%s=[%s], got <%s>: %s",
                expected,
                field,
                code,
                response.statusCode,
                response.body,
            ).isEqualTo(expected)
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("refusals must be RFC 9457 application/problem+json")
            .contains(PROBLEM_JSON_MEDIA_TYPE)
        val codes =
            objectMapper
                .readTree(response.body)["errors"]
                ?.get(field)
                ?.map { it.asText() }
                .orEmpty()
        assertThat(codes)
            .overridingErrorMessage(
                "the problem must carry errors.%s=[%s], got <%s> in %s",
                field,
                code,
                codes,
                response.body,
            ).containsExactly(code)
    }

    /** A `message.created` frame must repeat the group id and the №16 answer verbatim (§3.7). */
    private fun assertFanoutFrame(
        event: JsonNode,
        chatId: UUID,
        stored: JsonNode,
    ) {
        assertThat(event["chatId"].asText())
            .overridingErrorMessage("message.created.chatId must be the group chatId")
            .isEqualTo(chatId.toString())
        assertThat(event["message"])
            .overridingErrorMessage("the frame payload must repeat the №16 answer verbatim")
            .isEqualTo(stored)
    }

    /**
     * Collects [count] consecutive `message.created` frames — the wire-order
     * observation of the order assertions (unknown events are skipped,
     * forward compatibility of 004 §2).
     */
    private fun awaitCreatedFrames(
        stream: UserEventsStream,
        count: Int,
    ): List<JsonNode> = (0 until count).map { stream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget) }

    /**
     * Consumes frames for [window] and fails the moment an `event:` frame of
     * [eventType] shows up — the §3.7 "except the sender" and the SC-002
     * "no duplicate frame" legs. A per-frame timeout inside the window is
     * the expected clean exit (nothing was published).
     */
    private fun assertNoEventFollows(
        stream: UserEventsStream,
        eventType: String,
        window: Duration,
    ) {
        val deadline = System.nanoTime() + window.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            val frame =
                try {
                    stream.nextFrame(Duration.ofNanos(remaining))
                        ?: throw AssertionError("stream ended while asserting that no <$eventType> frame follows")
                } catch (failure: AssertionError) {
                    if (TIMEOUT_MARKER !in failure.message.orEmpty()) throw failure
                    return
                }
            assertThat(frame.event)
                .overridingErrorMessage("no further <%s> frame may follow", eventType)
                .isNotEqualTo(eventType)
        }
    }

    /** One accepted №16 send of the flood burst: the fresh `clientMessageId`. */
    private data class FloodedBurst(
        val accepted: List<UUID>,
        val rejection: ResponseEntity<String>,
    )

    /**
     * Sends fresh-id messages back to back until the first non-`201` answer
     * (FR-011 in groups): the accepted ids plus the first refusal. The
     * greedy 30/60s drip may re-grant tokens mid-burst, so the accepted
     * count is bounded by `30 + elapsedSeconds/2 + 1` — never a fixed index.
     */
    private fun sendUntilFlood(
        user: MessagingUser,
        chatId: UUID,
        textPrefix: String,
    ): FloodedBurst {
        val accepted = mutableListOf<UUID>()
        var rejection: ResponseEntity<String>? = null
        val startedAt = System.nanoTime()
        var attempt = 0
        while (rejection == null && attempt < BURST_ATTEMPT_CAP) {
            attempt += 1
            val response = sendMessage(user, chatId, refusedText(textPrefix, attempt))
            if (response.statusCode == HttpStatus.CREATED) {
                accepted += UUID.fromString(objectMapper.readTree(response.body)["id"].asText())
            } else {
                rejection = response
            }
        }
        val elapsedSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        assertThat(rejection)
            .overridingErrorMessage(
                "the 30-messages-per-minute bucket (FR-011) must refuse a group burst of %d fresh sends, " +
                    "but every attempt answered 201 — the flood limit is not enforced in groups",
                BURST_ATTEMPT_CAP,
            ).isNotNull
        val dripBound = WINDOW_MESSAGES.toLong() + elapsedSeconds / 2 + DRIP_SLACK
        assertThat(accepted.size.toLong())
            .overridingErrorMessage(
                "the first group refusal must land within the greedy-drip bound 30+elapsed/2+1 " +
                    "(accepted=<%d>, elapsed=<%ds>, bound=<%d>)",
                accepted.size,
                elapsedSeconds,
                dripBound,
            ).isBetween(WINDOW_MESSAGES.toLong(), dripBound)
        return FloodedBurst(accepted, rejection!!)
    }

    /**
     * The exact №16 429 of api-contract.md (unchanged by 006): `429` +
     * problem+json `errors.text=[flood_limit]` + an integral `Retry-After`
     * of at least 1 second.
     */
    private fun assertFloodRejection(rejection: ResponseEntity<String>) {
        assertThat(rejection.statusCode)
            .overridingErrorMessage(
                "the group flood refusal must be 429 (FR-011 unchanged in groups), got <%s>: %s",
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
    }

    private fun refusedText(
        prefix: String,
        attempt: Int,
    ): String = "$prefix-$attempt"

    private companion object {
        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"
        const val USERS_PATH = "/api/v1/users"

        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val MESSAGE_CREATED_EVENT = "message.created"

        const val TITLE_FIELD = "title"
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"
        const val USER_ID_FIELD = "userId"
        const val CHAT_FIELD = "chat"
        const val TEXT_FIELD = "text"

        const val NOT_PARTICIPANT = "not_participant"
        const val CHAT_NOT_FOUND = "chat_not_found"
        const val YOU_ARE_BLOCKED = "you_are_blocked"
        const val FLOOD_LIMIT = "flood_limit"

        const val GROUP_TITLE = "US2 realtime IT group"

        const val OWNER_FANOUT_TEXT = "fanout — everyone but the sender ✓"
        const val CAROL_FANOUT_TEXT = "carol's turn — the sender stays silent ✓"
        const val DEDUP_FIRST_TEXT = "the only copy of this group message"
        const val DEDUP_RETRY_TEXT = "an edited draft that must not replace the stored row"
        const val ORDER_TEXT_PREFIX = "order"
        const val LIVE_TEXT_PREFIX = "live"
        const val OFFLINE_TEXT_PREFIX = "offline"
        const val OFFLINE_LIVE_TEXT = "the live send of the offline window"
        const val FLOOD_TEXT_PREFIX = "flood-group"
        const val BLOCKED_DIRECT_TEXT = "dead in the direct dialog"
        const val BLOCKED_GROUP_TEXT_BOB = "bob writes to the group despite the block"
        const val BLOCKED_GROUP_TEXT_OWNER = "the blocker answers in the group"
        const val PRIVACY_TEXT_PREFIX = "privacy"
        const val OWN_TEXT_PREFIX = "privacy-own"
        const val PRIVACY_STRANGER_TEXT = "a valid body — membership is the only refusal reason"
        const val PRIVACY_REMOVED_TEXT = "a removed row must read as a non-participant"

        /** FR-011/api-contract.md №16: 30 new messages per minute per user. */
        const val WINDOW_MESSAGES = 30
        const val BURST_ATTEMPT_CAP = 40
        const val DRIP_SLACK = 1L
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L

        const val ORDER_MESSAGE_COUNT = 6
        const val LIVE_MESSAGES = 2
        const val OFFLINE_SEEDED_MESSAGES = 6
        const val OWN_OUTGOING_EVERY = 2
        const val PRIVACY_SEEDED_MESSAGES = 2
        const val DELIVERY_BUDGET_SECONDS = 5L

        /** §3.7 «except the sender» / SC-002 «no duplicate»: any push would land well inside the ≤2 s budget. */
        val NO_FRAME_WINDOW: Duration = Duration.ofSeconds(3)
        const val TIMEOUT_MARKER = "timed out"
    }
}
