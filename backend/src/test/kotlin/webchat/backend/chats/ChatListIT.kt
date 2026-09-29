package webchat.backend.chats

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
import webchat.backend.sync.SyncTestSupport
import java.util.UUID

/**
 * T055 (tasks.md Phase 7, US5): the №12 `GET /api/v1/chats` dialog list —
 * ONE request per panel load (research.md 004 §8), each item a contract
 * `ChatListItem` shaped strictly by openapi.yaml:
 *
 *  * server-side sorting: by `createdAt` of the LAST VISIBLE message
 *    descending (incoming OR outgoing — either lifts the dialog, FR-014),
 *    chats without visible messages keep their place BELOW the
 *    correspondence (NULLS LAST, FR-014/FR-018); `chats.last_seq` is never
 *    an inter-chat key (`seq` is per-chat only — plan.md);
 *  * `lastMessage` is the newest message with `seq > deleted_up_to_seq`
 *    (the per-user visibility watermark, data-model 004 §2) with its FULL
 *    text (the ≤64-char cut is a client render); `null` for an empty chat;
 *  * `unreadCount` counts exactly the incoming messages of the DELIVERED
 *    visible tail — `sender_id <> me AND seq > GREATEST(last_read_seq,
 *    deleted_up_to_seq) AND seq <= LEAST(chats.last_seq,
 *    delivered_up_to_seq)` (data-model 005 сущность 3, T031/FR-007): the
 *    badge grows ONLY by the delivery ack №25, so the fixtures ack the
 *    received tail before asserting it;
 *  * the ONLY exclusion is the fully deleted dialog:
 *    `hidden AND deleted_up_to_seq >= chats.last_seq` — the peer's row is
 *    never touched (FR-021); a NEW incoming resets `hidden` on the write
 *    path (T012) and the dialog returns WITHOUT the deleted history;
 *  * `blockedByMe` is the caller's OWN block mark only (FR-020, T054) —
 *    no inverse field may exist.
 *
 * NOTE (TDD, constitution VI): written BEFORE the №12 endpoint — until it
 * lands, every method here fails (RED) by design. The №14 DELETE endpoint
 * of T056 is NOT assumed: the per-user deletion state is staged directly
 * on `chat_participants` (the same rows №14 would write), exactly like the
 * ChatDeletionIT fixtures.
 *
 * T032 (tasks.md Phase 4, US2): the №12 GROUP elements join this suite at
 * the US2 close — the full projection check of the unified list (006,
 * api-contract.md §3, FR-014): the group item carries the roster
 * projection (`type:'group'`/`title`/`memberCount`/`myRole` per caller)
 * with the peer fields as EXPLICIT nulls (`peer`/`blockedByMe` — blocks
 * never apply to groups, Assumptions 006), groups and direct dialogs sort
 * together by the last visible message (any member's send lifts the
 * group), the badge stays delivery-bounded (fellow members' messages
 * count only after the №25 ack, own sends never) — while the DIRECT
 * neighbors in the very same list keep the exact 0.4.0 field set (no
 * group field may leak into a direct item). The group history is staged
 * with the [SyncTestSupport.seedChatBacklog] fixture: the real №16 group
 * send is owned by T035 (US2 implementation) and stays out of the №12
 * projection fixtures on purpose.
 *
 * Anchored on the T002 fixtures over the real 001 flows, Testcontainers
 * PG+Redis, real HTTP, no mocks; every method registers its own users, so
 * nothing leaks between methods and the FR-011 flood bucket is never
 * crossed. The 005 №25 ack fixture rides the [SyncTestSupport] layer the
 * badge now depends on (T031).
 */
class ChatListIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : SyncTestSupport() {
    /**
     * FR-014 sorting: the dialog with the NEWEST visible message comes
     * first regardless of which side sent it; the chat that never carried
     * a message stays in the list BELOW the correspondence with
     * `lastMessage = null`. The item shape is exactly the contract
     * `ChatListItem` — no extra field, `peer` reuses `PublicUser`.
     */
    @Test
    fun `the list is sorted by the last visible message with empty dialogs below`() {
        val alice = messagingUser("alice")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val dave = messagingUser("dave")
        val olderChat = ensureChatOk(alice, bob.id)
        val newerChat = ensureChatOk(alice, carol.id)
        val emptyChat = ensureChatOk(alice, dave.id)

        sendMessageOk(bob, olderChat, OLDER_TEXT)
        distinctMoment()
        sendMessageOk(carol, newerChat, NEWER_TEXT)

        val items = chatListOk(alice)
        assertThat(items.map { chatIdOf(it) })
            .overridingErrorMessage("the dialogs must sort by the last visible message createdAt DESC, empty last")
            .containsExactly(newerChat, olderChat, emptyChat)

        val newer = items[0]
        assertThat(lastMessageTextOf(newer))
            .overridingErrorMessage("lastMessage must be the newest VISIBLE message with its full text")
            .isEqualTo(NEWER_TEXT)
        assertThat(newer["peer"]["id"].asText())
            .overridingErrorMessage("the peer of the item must be the OTHER side of the dialog")
            .isEqualTo(carol.id.toString())

        val empty = items[2]
        val emptyLast = empty["lastMessage"]
        assertThat(emptyLast == null || emptyLast.isNull || emptyLast.isMissingNode)
            .overridingErrorMessage("a chat without messages must carry lastMessage = null, got <%s>", emptyLast)
            .isTrue

        items.forEach { item ->
            assertThat(fieldNames(item))
                .overridingErrorMessage(
                    "ChatListItem must carry exactly the contract fields, got <%s>",
                    fieldNames(item),
                ).containsExactlyInAnyOrderElementsOf(CHAT_LIST_ITEM_FIELDS)
            assertThat(fieldNames(item["peer"]))
                .overridingErrorMessage("the peer fragment must reuse the PublicUser schema")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_USER_FIELDS)
            val last = item["lastMessage"]
            if (last != null && !last.isNull) {
                assertThat(fieldNames(last))
                    .overridingErrorMessage("the lastMessage fragment must reuse the Message schema")
                    .containsExactlyInAnyOrderElementsOf(MESSAGE_FIELDS)
            }
        }
    }

    /**
     * FR-014/Q40: the caller's OWN outgoing message lifts the dialog too —
     * the sort key is the last visible message, never "the last incoming".
     */
    @Test
    fun `an outgoing message lifts the dialog as well`() {
        val alice = messagingUser("alice")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val incomingChat = ensureChatOk(alice, bob.id)
        val outgoingChat = ensureChatOk(alice, carol.id)

        sendMessageOk(bob, incomingChat, INCOMING_TEXT)
        distinctMoment()
        sendMessageOk(alice, outgoingChat, OUTGOING_TEXT)

        val items = chatListOk(alice)
        assertThat(items.map { chatIdOf(it) })
            .overridingErrorMessage("the caller's own send must lift the dialog to the top (FR-014)")
            .containsExactly(outgoingChat, incomingChat)
        assertThat(lastMessageTextOf(items[0])).isEqualTo(OUTGOING_TEXT)
        assertThat(items[0]["lastMessage"]["senderId"].asText())
            .isEqualTo(alice.id.toString())
    }

    /**
     * FR-014 badge (005 T031 recut): `unreadCount` counts exactly the
     * incoming messages of the DELIVERED visible tail — a received but
     * UNACKED message does not count (the badge grows only by the ack
     * №25, FR-007), own messages never count, and the №17 advance
     * zeroes the badge (US4 watermark).
     */
    @Test
    fun `unreadCount counts only visible incoming messages above the read watermark`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(bob, chatId, UNREAD_PREFIX, UNREAD_MESSAGES)

        assertThat(unreadCountOf(chatListItemOf(alice, chatId)))
            .overridingErrorMessage(
                "an unacked incoming message is not unread yet — only the ack №25 grows the badge (FR-007)",
            ).isZero()

        assertThat(deliveryAck(alice, listOf(chatId to seqs.max())).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadCountOf(chatListItemOf(alice, chatId)))
            .overridingErrorMessage("every delivered unread incoming message must count")
            .isEqualTo(UNREAD_MESSAGES.toLong())

        sendMessageOk(alice, chatId, OWN_TEXT)
        assertThat(unreadCountOf(chatListItemOf(alice, chatId)))
            .overridingErrorMessage("the caller's own message must not add to the badge")
            .isEqualTo(UNREAD_MESSAGES.toLong())

        assertThat(markRead(alice, chatId, upToSeq = seqs.max()).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadCountOf(chatListItemOf(alice, chatId)))
            .overridingErrorMessage("reading up to the last seq must zero the badge")
            .isZero()
    }

    /**
     * FR-021/data-model §2: the ONLY exclusion is the FULLY deleted dialog
     * (`hidden AND deleted_up_to_seq >= chats.last_seq`) and it is per-user
     * — the peer keeps the dialog in his list untouched. A NEW incoming
     * message returns the dialog to the former deleter WITHOUT the deleted
     * history: `lastMessage` is the new message only, the badge counts only
     * the new incoming (T012 resets `hidden` on the write path).
     */
    @Test
    fun `a fully deleted dialog disappears for the deleter only and a new incoming returns it`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val oldSeqs = sendFrom(bob, chatId, DELETED_PREFIX, DELETED_MESSAGES)
        stagePerUserDeletion(chatId, alice.id)

        assertThat(chatIdsOf(chatListOk(alice)))
            .overridingErrorMessage("the fully deleted dialog must be excluded from the deleter's №12 list")
            .doesNotContain(chatId)
        assertThat(chatIdsOf(chatListOk(bob)))
            .overridingErrorMessage("the peer's №12 list keeps the dialog (FR-021 per-user deletion)")
            .contains(chatId)

        val newText = "the only message after the deletion (FR-021)"
        val newSeq = sendMessageOk(bob, chatId, newText)["seq"].asLong()
        assertThat(newSeq).isGreaterThan(oldSeqs.max())

        assertThat(deliveryAck(alice, listOf(chatId to newSeq)).statusCode)
            .overridingErrorMessage(
                "the returned dialog's new incoming must ack-deliver before the badge may count it (T031)",
            ).isEqualTo(HttpStatus.NO_CONTENT)
        val returned = chatListItemOf(alice, chatId)
        assertThat(lastMessageTextOf(returned))
            .overridingErrorMessage("the returned dialog shows ONLY the new message as lastMessage")
            .isEqualTo(newText)
        assertThat(unreadCountOf(returned))
            .overridingErrorMessage("the deleted suffix must not count towards the badge")
            .isEqualTo(1L)
    }

    /**
     * FR-020/T054: `blockedByMe` is the ONLY block projection of the list —
     * the blocker's own item carries the mark, the blocked user's item (his
     * own list) carries none; the dialog itself stays fully visible to both.
     */
    @Test
    fun `blockedByMe marks only the caller's own block`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        sendFrom(bob, chatId, BLOCK_FIXTURE_PREFIX, BLOCK_FIXTURE_MESSAGES)

        stageBlock(alice.id, bob.id)

        assertThat(chatListItemOf(alice, chatId)["blockedByMe"].asBoolean())
            .overridingErrorMessage("the blocker's №12 item must carry blockedByMe = true")
            .isTrue
        assertThat(chatListItemOf(bob, chatId)["blockedByMe"].asBoolean())
            .overridingErrorMessage("the blocked user's №12 item must carry NO block mark (FR-020 leakage ban)")
            .isFalse
    }

    /** A user without dialogs gets a CLEAN empty list — `{chats: []}` (contract: an empty array is valid). */
    @Test
    fun `a user without dialogs gets an empty list`() {
        val loner = messagingUser("loner")

        val items = chatListOk(loner)
        assertThat(items)
            .overridingErrorMessage("a fresh user must see an empty (not absent) chats array")
            .isEmpty()
    }

    /**
     * T032 (api-contract.md 006 §3, FR-014): the GROUP item of the unified
     * list — the exact contract field set with the roster projection:
     * `type:'group'`, `title`, `memberCount` of the active roster and the
     * caller's OWN `myRole` (owner for the creator, member for the added),
     * while the peer projection renders as EXPLICIT nulls: `peer` never
     * resolves in a group and `blockedByMe` stays null EVEN with a real
     * user_blocks row to a fellow member — blocks never apply to groups
     * (Assumptions 006). `lastMessage` is the newest message with the FULL
     * text and the attribution of the fellow member who sent it. The
     * DIRECT neighbor in the same list keeps the exact 0.4.0 field set —
     * no group field may leak into a direct item.
     */
    @Test
    fun `the group item carries the roster projection with the peer fields null`() {
        val owner = messagingUser("owner")
        val (bob, carol) = messagingUser("bob") to messagingUser("carol")
        val dave = messagingUser("dave")
        addGroupContact(owner, bob.id)
        addGroupContact(owner, carol.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, listOf(bob.id, carol.id)))
        seedChatBacklog(chatId, GROUP_HISTORY_MESSAGES, { bob }, GROUP_TEXT_PREFIX)
        val directChat = ensureChatOk(owner, dave.id)

        val items = chatListOk(owner)
        val groupItem = items.single { chatIdOf(it) == chatId }
        assertThat(fieldNames(groupItem))
            .overridingErrorMessage(
                "the group ChatListItem must carry exactly the contract fields, got <%s>",
                fieldNames(groupItem),
            ).containsExactlyInAnyOrderElementsOf(GROUP_CHAT_LIST_ITEM_FIELDS)
        assertThat(groupItem["type"].asText()).isEqualTo("group")
        assertThat(groupItem["title"].asText()).isEqualTo(GROUP_TITLE)
        assertThat(groupItem["memberCount"].asLong())
            .overridingErrorMessage("memberCount must count the active roster — the creator plus both added")
            .isEqualTo(3)
        assertThat(groupItem["myRole"].asText()).isEqualTo("owner")
        assertNullField(groupItem, "peer")
        assertNullField(groupItem, "blockedByMe")
        assertThat(lastMessageTextOf(groupItem))
            .overridingErrorMessage("the group lastMessage must be the newest message with its full text")
            .isEqualTo("$GROUP_TEXT_PREFIX-${GROUP_HISTORY_MESSAGES - INDEX_ONE}")
        assertThat(groupItem["lastMessage"]["senderId"].asText())
            .overridingErrorMessage("the group lastMessage carries the attribution of the fellow member who sent it")
            .isEqualTo(bob.id.toString())
        assertThat(fieldNames(groupItem["lastMessage"]))
            .overridingErrorMessage("the group lastMessage fragment must reuse the Message schema")
            .containsExactlyInAnyOrderElementsOf(MESSAGE_FIELDS)
        assertThat(unreadCountOf(groupItem))
            .overridingErrorMessage("a group the caller has not acked yet carries the delivery-bounded zero badge")
            .isZero()

        assertThat(chatListItemOf(bob, chatId)["myRole"].asText())
            .overridingErrorMessage("myRole is the projection of the CALLER's own row — member for the added")
            .isEqualTo("member")

        stageBlock(owner.id, bob.id)
        assertThat(chatListItemOf(owner, chatId)["blockedByMe"].isNull)
            .overridingErrorMessage(
                "blocks never apply to groups (Assumptions 006) — blockedByMe must stay an explicit null " +
                    "even over a real block row",
            ).isTrue

        items
            .filterNot { chatIdOf(it) == chatId }
            .forEach { direct ->
                assertThat(fieldNames(direct))
                    .overridingErrorMessage(
                        "the direct neighbors of a group must keep the exact 0.4.0 field set, got <%s>",
                        fieldNames(direct),
                    ).containsExactlyInAnyOrderElementsOf(CHAT_LIST_ITEM_FIELDS)
            }
        assertThat(chatIdsOf(items))
            .overridingErrorMessage("the unified list carries the group AND the direct dialog of the caller")
            .contains(chatId, directChat)
    }

    /**
     * T032 (FR-014): the unified sort — groups and direct dialogs ride ONE
     * list ordered by the last visible message createdAt DESC: ANY member's
     * send lifts the group (a fellow member's message above), the caller's
     * newer direct dialog takes the top back, and a group without messages
     * keeps its place BELOW the correspondence (NULLS LAST) — exactly the
     * 004 rules, now over both kinds.
     */
    @Test
    fun `groups and direct dialogs sort together by the last visible message`() {
        val owner = messagingUser("owner")
        val (alice, bob) = messagingUser("alice") to messagingUser("bob")
        val dave = messagingUser("dave")
        addGroupContact(owner, alice.id)
        addGroupContact(owner, bob.id)
        val quietGroup = chatIdOf(createGroupOk(owner, QUIET_GROUP_TITLE, listOf(alice.id)))
        val activeGroup = chatIdOf(createGroupOk(owner, GROUP_TITLE, listOf(alice.id, bob.id)))
        val directChat = ensureChatOk(alice, dave.id)

        seedChatBacklog(directChat, DIRECT_LIFT_MESSAGES, { dave }, DIRECT_TEXT_PREFIX)
        distinctMoment()
        seedChatBacklog(activeGroup, GROUP_LIFT_MESSAGES, { bob }, GROUP_TEXT_PREFIX)

        assertThat(chatIdsOf(chatListOk(alice)))
            .overridingErrorMessage(
                "a fellow member's message must lift the group above the older direct dialog, empty group last",
            ).containsExactly(activeGroup, directChat, quietGroup)

        distinctMoment()
        seedChatBacklog(directChat, DIRECT_LIFT_MESSAGES, { dave }, NEWER_DIRECT_TEXT_PREFIX)

        assertThat(chatIdsOf(chatListOk(alice)))
            .overridingErrorMessage(
                "the caller's newer direct message must take the top back (one sort over both kinds)",
            ).containsExactly(directChat, activeGroup, quietGroup)
    }

    /**
     * T032 (FR-013 + data-model 005 сущность 3): the group badge is the
     * SAME delivery-bounded counter as the dialogs — a fellow member's
     * messages count ONLY after the №25 ack grows the position (before it
     * the badge stays 0), and the caller's OWN group message never counts.
     */
    @Test
    fun `the group badge counts only ack-delivered incoming messages of fellow members`() {
        val owner = messagingUser("owner")
        val (alice, bob) = messagingUser("alice") to messagingUser("bob")
        addGroupContact(owner, alice.id)
        addGroupContact(owner, bob.id)
        val chatId = chatIdOf(createGroupOk(owner, GROUP_TITLE, listOf(alice.id, bob.id)))
        val incoming =
            seedChatBacklog(chatId, GROUP_UNREAD_MESSAGES, { owner }, GROUP_TEXT_PREFIX)
                .map { it.seq }

        assertThat(unreadCountOf(chatListItemOf(alice, chatId)))
            .overridingErrorMessage(
                "an unacked group message is not unread yet — only the ack №25 grows the badge (FR-007)",
            ).isZero()

        assertThat(deliveryAck(alice, listOf(chatId to incoming.max())).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadCountOf(chatListItemOf(alice, chatId)))
            .overridingErrorMessage("every delivered unread message of a fellow member must count in the group badge")
            .isEqualTo(GROUP_UNREAD_MESSAGES.toLong())

        distinctMoment()
        val withOwn = seedChatBacklog(chatId, GROUP_OWN_MESSAGES, { alice }, OWN_GROUP_TEXT_PREFIX)
        assertThat(deliveryAck(alice, listOf(chatId to withOwn.maxOf { it.seq })).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(unreadCountOf(chatListItemOf(alice, chatId)))
            .overridingErrorMessage("the caller's own group message must not add to the badge")
            .isEqualTo(GROUP_UNREAD_MESSAGES.toLong())
    }

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

    /**
     * PG `now()` carries microsecond precision, so two HTTP sends within
     * the same microsecond would tie on `createdAt` — a sleep keeps the
     * DESC order deterministic without relying on the chat_id tie-break.
     */
    private fun distinctMoment() {
        Thread.sleep(ORDER_SLEEP_MILLIS)
    }

    /** №12 happy path → the parsed `chats` array of the caller. */
    private fun chatListOk(user: MessagingUser): List<JsonNode> {
        val response = listChats(user)
        assertThat(response.statusCode)
            .overridingErrorMessage("№12 GET /chats must answer 200, got <%s>: %s", response.statusCode, response.body)
            .isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["chats"].toList()
    }

    private fun chatIdsOf(items: List<JsonNode>): List<UUID> = items.map { chatIdOf(it) }

    /** The caller's №12 item of [chatId] — exactly one dialog per fresh fixture pair. */
    private fun chatListItemOf(
        user: MessagingUser,
        chatId: UUID,
    ): JsonNode {
        val item = chatListOk(user).singleOrNull { chatIdOf(it) == chatId }
        assertThat(item)
            .overridingErrorMessage("the №12 list of the fixture pair must contain chat <%s>", chatId)
            .isNotNull
        return item!!
    }

    private fun chatIdOf(item: JsonNode): UUID = UUID.fromString(item["chatId"].asText())

    private fun lastMessageTextOf(item: JsonNode): String = item["lastMessage"]["text"].asText()

    private fun unreadCountOf(item: JsonNode): Long = item["unreadCount"].asLong()

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /** A contract-REQUIRED nullable field must be PRESENT as JSON null (not omitted). */
    private fun assertNullField(
        node: JsonNode,
        field: String,
    ) {
        assertThat(node.has(field) && node[field].isNull)
            .overridingErrorMessage(
                "field <%s> must be present as JSON null on the group item, got <%s>",
                field,
                node[field],
            ).isTrue
    }

    /**
     * Stages the exact №14 end state on the real rows (T056 will drive it
     * through DELETE): `deleted_up_to_seq = chats.last_seq, hidden = true`
     * for ONE side only (data-model 004 §2).
     */
    private fun stagePerUserDeletion(
        chatId: UUID,
        userId: UUID,
    ) {
        val updated =
            jdbcTemplate.update(
                STAGE_DELETION_SQL,
                chatId,
                chatId,
                userId,
            )
        assertThat(updated)
            .overridingErrorMessage("the deletion fixture must update the participant row")
            .isEqualTo(1)
    }

    /**
     * Stages the №23 block state directly (the PUT endpoint semantics are
     * owned by BlockingIT of T048): the `(blocker_id, blocked_id)` row the
     * `blockedByMe` projection reads.
     */
    private fun stageBlock(
        blockerId: UUID,
        blockedId: UUID,
    ) {
        jdbcTemplate.update(STAGE_BLOCK_SQL, blockerId, blockedId)
    }

    /**
     * Contract №27 `POST /api/v1/groups` — the T032 group fixture: a group
     * of the creator plus [memberUserIds] from HIS contacts (№21 rows are
     * staged by [addGroupContact] first) → the stored `GroupView` chatId.
     */
    private fun createGroupOk(
        owner: MessagingUser,
        title: String,
        memberUserIds: List<UUID> = emptyList(),
    ): JsonNode {
        val response =
            restTemplate.postForEntity(
                GROUPS_PATH,
                HttpEntity(
                    buildMap<String, Any> {
                        put("title", title)
                        if (memberUserIds.isNotEmpty()) {
                            put("memberUserIds", memberUserIds.map(UUID::toString))
                        }
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

    private companion object {
        const val ORDER_SLEEP_MILLIS = 50L

        const val OLDER_TEXT = "the older last message"
        const val NEWER_TEXT = "the newer last message — full text, no server-side cut"
        const val INCOMING_TEXT = "an incoming message"
        const val OUTGOING_TEXT = "the caller's own outgoing message"

        const val UNREAD_PREFIX = "unread"
        const val UNREAD_MESSAGES = 3
        const val OWN_TEXT = "own messages never count"

        const val DELETED_PREFIX = "deleted-suffix"
        const val DELETED_MESSAGES = 2

        const val BLOCK_FIXTURE_PREFIX = "block-fixture"
        const val BLOCK_FIXTURE_MESSAGES = 1

        const val GROUPS_PATH = "/api/v1/groups"
        const val CONTACTS_PATH = "/api/v1/contacts"

        const val GROUP_TITLE = "ChatList unified roster IT"
        const val QUIET_GROUP_TITLE = "ChatList quiet group IT"
        const val GROUP_TEXT_PREFIX = "chat-list-group"
        const val OWN_GROUP_TEXT_PREFIX = "chat-list-group-own"
        const val DIRECT_TEXT_PREFIX = "chat-list-direct"
        const val NEWER_DIRECT_TEXT_PREFIX = "chat-list-direct-newer"

        const val GROUP_HISTORY_MESSAGES = 2
        const val GROUP_LIFT_MESSAGES = 1
        const val DIRECT_LIFT_MESSAGES = 1
        const val GROUP_UNREAD_MESSAGES = 3
        const val GROUP_OWN_MESSAGES = 1
        const val INDEX_ONE = 1

        val CHAT_LIST_ITEM_FIELDS = listOf("chatId", "peer", "lastMessage", "unreadCount", "blockedByMe")
        val GROUP_CHAT_LIST_ITEM_FIELDS =
            listOf(
                "chatId",
                "type",
                "title",
                "memberCount",
                "myRole",
                "peer",
                "lastMessage",
                "unreadCount",
                "blockedByMe",
            )
        val PUBLIC_USER_FIELDS = listOf("id", "username", "email", "status", "createdAt")
        val MESSAGE_FIELDS = listOf("id", "chatId", "senderId", "text", "seq", "createdAt")

        val STAGE_DELETION_SQL =
            """
            UPDATE chat_participants
            SET deleted_up_to_seq = (SELECT last_seq FROM chats WHERE id = ?),
                hidden = true
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        val STAGE_BLOCK_SQL =
            """
            INSERT INTO user_blocks (blocker_id, blocked_id)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()
    }
}
