package webchat.backend.chats

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
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
 * Anchored on the T002 fixtures over the real 001 flows, Testcontainers
 * PG+Redis, real HTTP, no mocks; every method registers its own users, so
 * nothing leaks between methods and the FR-011 flood bucket is never
 * crossed. The 005 №25 ack fixture rides the [SyncTestSupport] layer the
 * badge now depends on (T031).
 */
class ChatListIT(
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

        val CHAT_LIST_ITEM_FIELDS = listOf("chatId", "peer", "lastMessage", "unreadCount", "blockedByMe")
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
