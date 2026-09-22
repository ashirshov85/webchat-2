package webchat.backend.contacts

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
import webchat.backend.chats.MessagingTestSupport
import java.time.Duration
import java.util.UUID

/**
 * T048 (tasks.md Phase 7, US5): the blocking semantics of contract №23/№24
 * on top of №11–№17 (FR-020, research.md §6):
 *
 *  * the block is one-sided and managed by the blocker: `PUT /block` is
 *    idempotent (`204` again on repeat), self-block is `422
 *    self_forbidden`, an unknown target is `404 user_not_found`;
 *  * while the block is active the №16 send is forbidden in BOTH
 *    directions and rejected BEFORE any record — the blocker gets `403
 *    chat_blocked_by_you`, the blocked user gets the explicit `403
 *    you_are_blocked` (his only way to learn about the block); the ONLY
 *    exemption is the FR-004 dedup first: a retry of an already recorded
 *    `clientMessageId` still returns the existing record and creates
 *    nothing (edge spec FR-020);
 *  * №17 reads are suppressed — the blocker's marks answer `204` but
 *    neither move his watermark nor publish `chat.read` (the statuses and
 *    the unread badge of the blocked dialog «freeze» at the blocker);
 *    the blocked user's marks answer `204` and DO advance his own
 *    watermark locally, but the event is not delivered to the blocker
 *    (his activity is not revealed);
 *  * the №12 unread badge of the blocker freezes naturally: it neither
 *    grows (sends into the pair are rejected) nor resets (his reads are
 *    suppressed) until the unblock resumes the regular behavior;
 *  * `DELETE /block` (idempotent `204`) restores the dialog completely:
 *    both directions send again, `chat.read` flows again in realtime,
 *    the history survived and the state converges (Edge Cases);
 *  * contacts are fully independent of blocks (FR-020): the blocked
 *    contact stays in the contact list, the blocked user may add the
 *    blocker, and deleting a contact does not lift the block;
 *  * `blockedByMe` is the ONLY block projection in the API: `ChatView`
 *    (№11/№13) and `ChatListItem` (№12) carry exactly their contract
 *    fields — no inverse «who blocked me» field exists anywhere;
 *  * the Edge Case of a block established while BOTH users hold live №18
 *    SSE subscriptions: nothing leaks into the blocked user's stream,
 *    the rejected send never reaches him implicitly, and after the
 *    unblock the stream converges to exactly the newly recorded message.
 *
 * NOTE (TDD, constitution VI): written BEFORE T050–T055 — until the
 * contacts/block endpoints and the FR-020 suppressions in `MessageService`
 * and `ReadService` land, every method here fails (RED) by design.
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №15 history, №16 send, №17 read, №18 SSE),
 * Testcontainers PG+Redis, real HTTP and the real Redis pub/sub fanout —
 * no mocks; every method registers its own pair, so the per-user Redis
 * buckets never leak between methods.
 */
@Suppress("TooManyFunctions") // one helper per contract leg of FR-020
class BlockingIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : MessagingTestSupport() {
    /** Realtime legs must arrive well inside this CI-tolerant budget (SC-005/SC-007). */
    private val deliveryBudget: Duration = Duration.ofSeconds(5)

    /**
     * FR-020 core: with an active block the №16 send is rejected in both
     * directions BEFORE any record — the blocker sees `403
     * chat_blocked_by_you`, the blocked user sees the explicit `403
     * you_are_blocked` — and the FR-004 dedup stays FIRST: a retry of an
     * already recorded `clientMessageId` returns the existing record (200)
     * without creating anything. The history written before the block
     * survives for both participants.
     */
    @Test
    fun `send is forbidden both ways with no records while the block is active`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val bobRecordedId = UUID.randomUUID()
        val bobText = "recorded before the block and retryable by its id"
        val aliceText = "also recorded before the block — the history must survive"
        val bobFirstSend = sendMessage(bob, chatId, bobText, clientMessageId = bobRecordedId)
        assertThat(bobFirstSend.statusCode.is2xxSuccessful)
            .overridingErrorMessage(
                "the pre-block fixture send must succeed, got <%s>: %s",
                bobFirstSend.statusCode,
                bobFirstSend.body,
            ).isTrue
        val bobRecorded = objectMapper.readTree(bobFirstSend.body)
        sendMessageOk(alice, chatId, aliceText)
        assertThat(messageCount(chatId)).isEqualTo(PRE_BLOCK_MESSAGES)

        assertThat(blockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val fromBlocker = sendMessage(alice, chatId, "the blocker must be refused before the insert")
        assertSendRefusal(fromBlocker, CHAT_BLOCKED_BY_YOU)

        val fromBlocked = sendMessage(bob, chatId, "the blocked user must learn he is blocked")
        assertSendRefusal(fromBlocked, YOU_ARE_BLOCKED)

        assertThat(messageCount(chatId))
            .overridingErrorMessage("a rejected send must not create any message row (FR-020)")
            .isEqualTo(PRE_BLOCK_MESSAGES)
        assertThat(historyTexts(alice, chatId))
            .overridingErrorMessage("the pre-block history must survive the block for the blocker")
            .containsExactly(aliceText, bobText)
        assertThat(historyTexts(bob, chatId))
            .overridingErrorMessage("the pre-block history must survive the block for the blocked user")
            .containsExactly(aliceText, bobText)

        val dedupRetry = sendMessage(bob, chatId, bobText, clientMessageId = bobRecordedId)
        assertThat(dedupRetry.statusCode)
            .overridingErrorMessage(
                "a retry of an already recorded id must be answered by the FR-004 dedup BEFORE the block check, " +
                    "got <%s>: %s",
                dedupRetry.statusCode,
                dedupRetry.body,
            ).isEqualTo(HttpStatus.OK)
        val retryBody = objectMapper.readTree(dedupRetry.body)
        assertThat(UUID.fromString(retryBody["id"].asText()))
            .overridingErrorMessage("the dedup retry must return the SAME record id")
            .isEqualTo(bobRecordedId)
        assertThat(retryBody["seq"].asLong())
            .overridingErrorMessage("the dedup retry must return the existing seq, not a new one")
            .isEqualTo(bobRecorded["seq"].asLong())
        assertThat(messageCount(chatId))
            .overridingErrorMessage("the dedup retry must create nothing new (FR-020 edge)")
            .isEqualTo(PRE_BLOCK_MESSAGES)
    }

    /**
     * FR-020 read suppression over №17: the blocker's mark answers `204`
     * but neither moves his watermark nor publishes `chat.read` (his view
     * of the blocked dialog «freezes»); the blocked user's mark answers
     * `204` and DOES advance his own watermark locally, but the event is
     * not delivered to the blocker — the blocked side's activity is never
     * revealed. Both streams are live while the marks land.
     */
    @Test
    fun `blocker reads freeze silently and blocked reads advance without publication`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val lastSeq = sendFrom(bob, chatId, SUPPRESSED_TEXT_PREFIX, SUPPRESSED_MESSAGES).last()
        assertThat(blockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        openUserEvents(bob).use { bobStream ->
            openUserEvents(alice).use { aliceStream ->
                assertThat(markRead(alice, chatId, upToSeq = lastSeq).statusCode)
                    .overridingErrorMessage("the blocker's read must still answer 204 (a silent no-op)")
                    .isEqualTo(HttpStatus.NO_CONTENT)
                assertThat(lastReadSeq(chatId, alice.id))
                    .overridingErrorMessage("the blocker's watermark must NOT move while he blocks the peer")
                    .isZero()
                assertNoEvents(bobStream, quietPeriod, CHAT_READ_EVENT)

                assertThat(markRead(bob, chatId, upToSeq = lastSeq).statusCode)
                    .overridingErrorMessage("the blocked user's read must answer 204 and advance locally")
                    .isEqualTo(HttpStatus.NO_CONTENT)
                assertThat(lastReadSeq(chatId, bob.id))
                    .overridingErrorMessage(
                        "the blocked user's watermark must advance locally despite the block (FR-020)",
                    ).isEqualTo(lastSeq)
                assertNoEvents(aliceStream, quietPeriod, CHAT_READ_EVENT)
            }
        }
    }

    /**
     * FR-020 badge: the №12 `unreadCount` of the blocked dialog at the
     * blocker FREEZES — it keeps the value it had at the block moment:
     * it cannot grow (sends into the pair are rejected before the record)
     * and cannot reset (the blocker's reads are suppressed). After the
     * unblock the regular behavior resumes and the badge converges.
     */
    @Test
    fun `the unread badge of the blocker freezes during the block`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val lastSeq = sendFrom(bob, chatId, BADGE_TEXT_PREFIX, BADGE_MESSAGES).last()

        assertThat(unreadCount(alice, chatId))
            .overridingErrorMessage("before the block the badge must count the unread incoming messages")
            .isEqualTo(BADGE_MESSAGES.toLong())

        assertThat(blockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(markRead(alice, chatId, upToSeq = lastSeq).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(lastReadSeq(chatId, alice.id))
            .overridingErrorMessage("the suppressed read must not move the watermark behind the badge")
            .isZero()
        assertThat(unreadCount(alice, chatId))
            .overridingErrorMessage("the badge must NOT reset on the blocker's suppressed read (FR-020 freeze)")
            .isEqualTo(BADGE_MESSAGES.toLong())

        assertThat(sendMessage(alice, chatId, "no incoming may appear to grow the badge").statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(sendMessage(bob, chatId, "the blocked side cannot grow the badge either").statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(unreadCount(alice, chatId))
            .overridingErrorMessage("the badge must NOT grow while the block rejects every send (FR-020 freeze)")
            .isEqualTo(BADGE_MESSAGES.toLong())

        assertThat(unblockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(markRead(alice, chatId, upToSeq = lastSeq).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadCount(alice, chatId))
            .overridingErrorMessage("after the unblock the badge must resume and drop to zero")
            .isZero()
    }

    /**
     * №23/№24 idempotency + the FR-020 resumption: a repeated `PUT` block
     * and a repeated `DELETE` block both stay `204`; after the unblock the
     * dialog behaves regularly again — both directions send (201), the
     * `chat.read` event flows to the peer in realtime, the whole history
     * (before, during and after the block) survived and the watermarks
     * converge (Edge Cases).
     */
    @Test
    fun `block and unblock are idempotent and unblocking resumes the dialog`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val beforeText = "written before the block"
        val lastSeqBefore = sendMessageOk(alice, chatId, beforeText)["seq"].asLong()

        assertThat(blockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(blockUser(alice, bob.id).statusCode)
            .overridingErrorMessage("a repeated PUT block must stay idempotent (204, no changes)")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(sendMessage(bob, chatId, "still blocked until the DELETE").statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(unblockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unblockUser(alice, bob.id).statusCode)
            .overridingErrorMessage("a repeated DELETE block must stay idempotent (204)")
            .isEqualTo(HttpStatus.NO_CONTENT)

        val afterBlockText = "alice writes again after the unblock"
        val fromBlockedText = "bob answers — both directions are open again"
        val afterSeq = sendMessageOk(alice, chatId, afterBlockText)["seq"].asLong()
        val answerSeq = sendMessageOk(bob, chatId, fromBlockedText)["seq"].asLong()
        assertThat(answerSeq).isGreaterThan(afterSeq)

        // The reader is BOB, so realtime-channel.md §3.2 delivers
        // `chat.read` to the PEER (ALICE) — her stream proves the event
        // flows again after the unblock.
        openUserEvents(alice).use { aliceStream ->
            assertThat(markRead(bob, chatId, upToSeq = answerSeq).statusCode)
                .overridingErrorMessage("a regular read must answer 204 after the unblock")
                .isEqualTo(HttpStatus.NO_CONTENT)
            val readEvent = aliceStream.awaitEvent(CHAT_READ_EVENT, deliveryBudget)
            assertThat(readEvent["readUpToSeq"].asLong())
                .overridingErrorMessage("chat.read must flow to the peer again after the unblock (FR-010)")
                .isEqualTo(answerSeq)
            assertThat(UUID.fromString(readEvent["byUserId"].asText())).isEqualTo(bob.id)
        }
        assertThat(lastReadSeq(chatId, bob.id)).isEqualTo(answerSeq)
        assertThat(historyTexts(alice, chatId))
            .overridingErrorMessage("the full history across the block window must survive (FR-020)")
            .containsExactly(fromBlockedText, afterBlockText, beforeText)
        assertThat(lastSeqBefore).isLessThan(afterSeq)
    }

    /**
     * FR-020 independence: blocks never touch the contact lists — the
     * blocked contact STAYS in the blocker's №20 list, the blocked user
     * may still add the blocker as his own contact (№21), and deleting a
     * contact (№22) does not lift the block: the send stays refused.
     */
    @Test
    fun `contacts stay independent of blocks`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        assertThat(addContact(alice, bob.id).statusCode).isEqualTo(HttpStatus.CREATED)

        assertThat(blockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(contactIds(listContacts(alice)))
            .overridingErrorMessage("the blocked peer must STAY in the blocker's contact list (FR-020)")
            .containsExactly(bob.id.toString())

        assertThat(addContact(bob, alice.id).statusCode)
            .overridingErrorMessage("the blocked user must be able to add the blocker as a contact (FR-020)")
            .isEqualTo(HttpStatus.CREATED)

        assertThat(deleteContact(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(sendMessage(alice, chatId, "deleting a contact must not lift the block").statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertSendRefusal(sendMessage(bob, chatId, "the block outlives the contact deletion"), YOU_ARE_BLOCKED)
    }

    /**
     * `blockedByMe` is the ONLY block projection in the API (FR-020):
     * №13/№11 `ChatView` and №12 `ChatListItem` answer with exactly their
     * contract fields — the blocker sees `blockedByMe=true`, the blocked
     * user sees `blockedByMe=false` and NO inverse «who blocked me» field
     * exists anywhere: he learns about the block only from the 403 of his
     * own send. The dialog itself stays visible to both.
     */
    @Test
    fun `blockedByMe is the only block projection in the chat views`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        assertThat(blockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val blockerView = getChat(alice, chatId)
        assertThat(blockerView.statusCode)
            .overridingErrorMessage("the dialog must stay visible to the blocker (FR-020), got <%s>", blockerView.statusCode)
            .isEqualTo(HttpStatus.OK)
        assertChatViewProjection(blockerView, blockedByMe = true)

        val blockedView = getChat(bob, chatId)
        assertThat(blockedView.statusCode)
            .overridingErrorMessage("the dialog must stay visible to the blocked user without any mark (FR-020)")
            .isEqualTo(HttpStatus.OK)
        assertChatViewProjection(blockedView, blockedByMe = false)

        val blockedEnsure = ensureChat(bob, alice.id)
        assertThat(blockedEnsure.statusCode.is2xxSuccessful)
            .overridingErrorMessage("№11 ensure must keep working for the blocked pair (the dialog stays)")
            .isTrue
        assertChatViewProjection(blockedEnsure, blockedByMe = false)

        assertThat(chatListItem(alice, chatId)["blockedByMe"].asBoolean())
            .overridingErrorMessage("the blocker's №12 item must carry the «заблокирован» mark")
            .isTrue
        assertThat(chatListItem(bob, chatId)["blockedByMe"].asBoolean())
            .overridingErrorMessage("the blocked user's №12 item must NOT carry any block mark")
            .isFalse
    }

    /**
     * The Edge Case of a block established while both users hold live №18
     * SSE subscriptions: the rejected sends are NEVER published — nothing
     * (no `message.created`, no `chat.read`, no implicit delivery) leaks
     * into the blocked user's stream — and after the unblock the very
     * same stream converges to exactly the newly recorded message.
     */
    @Test
    fun `a live block leaks nothing into the streams and converges after the unblock`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val rejectedText = "this text must never exist nor be delivered"

        openUserEvents(bob).use { bobStream ->
            assertThat(blockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

            assertSendRefusal(sendMessage(alice, chatId, rejectedText), CHAT_BLOCKED_BY_YOU)
            assertSendRefusal(sendMessage(bob, chatId, "the blocked side is refused too"), YOU_ARE_BLOCKED)
            assertThat(messageCount(chatId))
                .overridingErrorMessage("both refusals must happen BEFORE any record (FR-020)")
                .isZero()
            assertNoEvents(bobStream, quietPeriod, MESSAGE_CREATED_EVENT, CHAT_READ_EVENT)

            assertThat(unblockUser(alice, bob.id).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
            val convergingText = "delivered only after the unblock"
            val sent = sendMessageOk(alice, chatId, convergingText)

            val event = bobStream.awaitEvent(MESSAGE_CREATED_EVENT, deliveryBudget)
            assertThat(UUID.fromString(event["chatId"].asText())).isEqualTo(chatId)
            assertThat(event["message"]["text"].asText())
                .overridingErrorMessage(
                    "the blocked stream must converge to exactly the post-unblock message — " +
                        "the rejected text may not appear implicitly",
                ).isEqualTo(convergingText)
            assertThat(UUID.fromString(event["message"]["id"].asText()))
                .isEqualTo(UUID.fromString(sent["id"].asText()))
        }
    }

    /** №23 error legs: self-block is `422 self_forbidden`, an unknown target is `404 user_not_found`. */
    @Test
    fun `block refuses self and unknown targets with the contract codes`() {
        val alice = messagingUser("blocker")

        val self = blockUser(alice, alice.id)
        assertThat(self.statusCode)
            .overridingErrorMessage("self-block must be refused 422, got <%s>: %s", self.statusCode, self.body)
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertProblem(self, USER_ID_FIELD, SELF_FORBIDDEN)

        val missing = blockUser(alice, UUID.randomUUID())
        assertThat(missing.statusCode)
            .overridingErrorMessage("blocking an unknown user must be refused 404, got <%s>: %s", missing.statusCode, missing.body)
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertProblem(missing, USER_FIELD, USER_NOT_FOUND)

        assertThat(unblockUser(alice, UUID.randomUUID()).statusCode)
            .overridingErrorMessage("№24 must stay idempotent for an unknown target too (204)")
            .isEqualTo(HttpStatus.NO_CONTENT)
    }

    /** Contract №23 `PUT /api/v1/users/{userId}/block` — raw response. */
    private fun blockUser(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchange(user, HttpMethod.PUT, "$USERS_PATH/$userId/block")

    /** Contract №24 `DELETE /api/v1/users/{userId}/block` — raw response. */
    private fun unblockUser(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchange(user, HttpMethod.DELETE, "$USERS_PATH/$userId/block")

    /** Contract №12 `GET /api/v1/chats` — raw response. */
    private fun listChats(user: MessagingUser): ResponseEntity<String> = exchange(user, HttpMethod.GET, CHATS_PATH)

    /** Contract №21 `POST /api/v1/contacts` — raw response. */
    private fun addContact(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(user.accessToken)
            }
        return restTemplate.postForEntity(CONTACTS_PATH, HttpEntity(mapOf(USER_ID_FIELD to userId.toString()), headers), String::class.java)
    }

    /** Contract №22 `DELETE /api/v1/contacts/{userId}` — raw response. */
    private fun deleteContact(
        user: MessagingUser,
        userId: UUID,
    ): ResponseEntity<String> = exchange(user, HttpMethod.DELETE, "$CONTACTS_PATH/$userId")

    /** Contract №20 `GET /api/v1/contacts` — raw response. */
    private fun listContacts(user: MessagingUser): ResponseEntity<String> = exchange(user, HttpMethod.GET, CONTACTS_PATH)

    private fun exchange(
        user: MessagingUser,
        method: HttpMethod,
        path: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            path,
            method,
            HttpEntity<Void>(null, HttpHeaders().apply { setBearerAuth(user.accessToken) }),
            String::class.java,
        )

    /** The №16 refusal of FR-020: 403 problem+json with `errors.chat=[code]`. */
    private fun assertSendRefusal(
        response: ResponseEntity<String>,
        code: String,
    ) {
        assertThat(response.statusCode)
            .overridingErrorMessage("a blocked-pair send must be refused 403, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertProblem(response, CHAT_FIELD, code)
    }

    /** RFC 9457 problem+json with exactly the contract `errors.<field>=[code]` entry. */
    private fun assertProblem(
        response: ResponseEntity<String>,
        field: String,
        code: String,
    ) {
        assertThat(response.headers.contentType?.toString())
            .overridingErrorMessage("refusals must be RFC 9457 application/problem+json, got <%s>", response.headers.contentType)
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

    /**
     * The №11/№13 `ChatView` block projection: the body carries exactly
     * the contract field set (additionalProperties: false — no inverse
     * «who blocked me» field may exist) and `blockedByMe` must be the
     * expected projection of the caller.
     */
    private fun assertChatViewProjection(
        response: ResponseEntity<String>,
        blockedByMe: Boolean,
    ) {
        val view = objectMapper.readTree(response.body)
        assertThat(fieldNames(view))
            .overridingErrorMessage(
                "ChatView must carry exactly the contract fields — blockedByMe is the ONLY block projection, " +
                    "no inverse field may exist, got <%s> in %s",
                fieldNames(view),
                response.body,
            ).containsExactlyInAnyOrderElementsOf(CHAT_VIEW_FIELDS)
        assertThat(view[BLOCKED_BY_ME_FIELD].asBoolean())
            .overridingErrorMessage("ChatView.blockedByMe must be <%b> for this caller", blockedByMe)
            .isEqualTo(blockedByMe)
    }

    /** The caller's №12 `ChatListItem` of [chatId] — the list holds exactly one dialog per fresh fixture pair. */
    private fun chatListItem(
        user: MessagingUser,
        chatId: UUID,
    ): JsonNode {
        val response = listChats(user)
        assertThat(response.statusCode)
            .overridingErrorMessage("№12 GET /chats must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        val items = objectMapper.readTree(response.body)["chats"]
        assertThat(items.size())
            .overridingErrorMessage("a fresh fixture pair must see exactly one dialog in №12, got <%s> in %s", items.size(), response.body)
            .isEqualTo(SINGLE_CHAT)
        val item = items[0]
        assertThat(UUID.fromString(item["chatId"].asText())).isEqualTo(chatId)
        return item
    }

    /** The №12 badge of [chatId] for the caller — `unreadCount` of the blocked dialog. */
    private fun unreadCount(
        user: MessagingUser,
        chatId: UUID,
    ): Long = chatListItem(user, chatId)[UNREAD_COUNT_FIELD].asLong()

    /** No frame of [forbidden] event types may arrive within [quietPeriod] — heartbeats/`retry:` are fine. */
    private fun assertNoEvents(
        stream: UserEventsStream,
        quietPeriod: Duration,
        vararg forbidden: String,
    ) {
        val deadline = System.nanoTime() + quietPeriod.toNanos()
        val leaked = mutableListOf<String>()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val frame = runCatching { stream.nextFrame(Duration.ofNanos(remaining)) }.getOrNull() ?: break
            if (frame.event != null && frame.event in forbidden) leaked += frame.event
        }
        assertThat(leaked)
            .overridingErrorMessage(
                "no <%s> frames may be delivered while the block is active (FR-020 suppression), got <%s>",
                forbidden.toList(),
                leaked,
            ).isEmpty()
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

    /** The stored message rows of the dialog — the «записей нет» leg of the send refusals. */
    private fun messageCount(chatId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM messages WHERE chat_id = ?",
            Long::class.java,
            chatId,
        ) ?: 0L

    /** The №20 contact list as peer user ids. */
    private fun contactIds(response: ResponseEntity<String>): List<String> =
        objectMapper.readTree(response.body)["contacts"].map { it["user"]["id"].asText() }

    /** №15 history of the dialog as text list in the framed DESC order. */
    private fun historyTexts(
        user: MessagingUser,
        chatId: UUID,
    ): List<String> = objectMapper.readTree(listMessages(user, chatId).body)["messages"].map { it["text"].asText() }

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
        const val USERS_PATH = "/api/v1/users"
        const val CHATS_PATH = "/api/v1/chats"
        const val CONTACTS_PATH = "/api/v1/contacts"

        const val CHAT_FIELD = "chat"
        const val USER_FIELD = "user"
        const val USER_ID_FIELD = "userId"
        const val BLOCKED_BY_ME_FIELD = "blockedByMe"
        const val UNREAD_COUNT_FIELD = "unreadCount"

        const val CHAT_BLOCKED_BY_YOU = "chat_blocked_by_you"
        const val YOU_ARE_BLOCKED = "you_are_blocked"
        const val SELF_FORBIDDEN = "self_forbidden"
        const val USER_NOT_FOUND = "user_not_found"

        const val MESSAGE_CREATED_EVENT = "message.created"
        const val CHAT_READ_EVENT = "chat.read"

        const val SUPPRESSED_TEXT_PREFIX = "block-read-suppressed"
        const val BADGE_TEXT_PREFIX = "block-badge-frozen"

        const val PRE_BLOCK_MESSAGES = 2L
        const val SUPPRESSED_MESSAGES = 2
        const val BADGE_MESSAGES = 3
        const val SINGLE_CHAT = 1

        /** Bounded quiet window proving the suppressed publishes deliver nothing. */
        val quietPeriod: Duration = Duration.ofMillis(1_500)

        /** ChatView (openapi 0.4.0): additionalProperties false, blockedByMe is the only block field. */
        val CHAT_VIEW_FIELDS: List<String> =
            listOf("chatId", "peer", BLOCKED_BY_ME_FIELD, "peerReadUpToSeq", "myReadUpToSeq")
    }
}
