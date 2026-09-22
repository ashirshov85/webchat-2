package webchat.backend.chats

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * T049 (tasks.md Phase 7, US5): the №14 per-user chat deletion of FR-021 —
 * `DELETE /chats/{id}` hides the whole history FROM THE DELETING PARTICIPANT
 * ONLY (the watermark `chat_participants.deleted_up_to_seq`, data-model 004
 * §2), never touching the peer's dialog:
 *
 *  * the deleter's №15 history is empty afterwards, the peer still reads
 *    the full dialog and its №13 view; the persisted end state is exactly
 *    the №14 UPDATE — `deleted_up_to_seq = chats.last_seq, hidden = true`
 *    for the deleter, `0/false` for the peer;
 *  * a repeated DELETE is an idempotent `204` that changes nothing;
 *  * the №11 ensure by the deleter CLEARS `hidden` but KEEPS the watermark
 *    (data-model §2: the deleted history is never restored);
 *  * a new incoming message after the deletion returns the chat to the
 *    deleter's visibility with ONLY the new messages (the old suffix stays
 *    hidden by the watermark, FR-021; `hidden` is reset by the T012 write
 *    path);
 *  * a first incoming from a stranger with NO prior chat/contact makes the
 *    dialog appear on the recipient side automatically (FR-019) — the
 *    implicit participant row is created readable, not hidden;
 *  * an empty chat (no messages, `last_seq = 0`) is hidden COMPLETELY after
 *    the deletion: the data-model §2 exclusion invariant
 *    `hidden AND deleted_up_to_seq >= chats.last_seq` holds for both a
 *    deleted dialog and a deleted empty chat.
 *
 * The `hidden` flag and the watermark are NOT exposed by the API
 * (data-model §5: deletion is expressed by the №12 exclusion and the empty
 * №15 history) — the invariant itself is asserted on the real
 * `chat_participants`/`chats` rows, like the T038 visibility fixtures.
 *
 * NOTE (TDD, constitution VI): written BEFORE the T056 endpoint — until
 * №14 `DELETE /chats/{chatId}` lands, every method here fails (RED) by
 * design (the 403/404 refusals of the same resource are locked by T008
 * ChatAccessIT, not duplicated here).
 *
 * Anchored on the T002 fixtures over the real 001 flows (registration,
 * login, №11 ensure, №16 send, №15 history, №13 view), Testcontainers
 * PG+Redis, real HTTP, no mocks; every method registers its own pair, so
 * nothing leaks between methods and the FR-011 flood bucket (30/minute
 * per user) is never crossed.
 */
class ChatDeletionIT(
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) : MessagingTestSupport() {
    /**
     * FR-021 core: the deletion hides the dialog from the DELETER only.
     * Alice deletes a 3-message dialog — her №15 history is a clean empty
     * page, while Bob still reads every message and his №13 view answers
     * 200; the persisted rows carry the exact №14 end state per side.
     */
    @Test
    fun `delete hides the history from the deleting participant only and the peer dialog stays intact`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val sentSeqs = sendFrom(bob, chatId, DELETED_DIALOG_PREFIX, DELETED_DIALOG_SIZE)

        val deletion = deleteChat(alice, chatId)
        assertThat(deletion.statusCode)
            .overridingErrorMessage("№14 DELETE must answer 204, got <%s>: %s", deletion.statusCode, deletion.body)
            .isEqualTo(HttpStatus.NO_CONTENT)

        assertHistoryEmptyFor(alice, chatId)
        assertHistoryIs(bob, chatId, sentSeqs)
        assertThat(getChat(bob, chatId).statusCode)
            .overridingErrorMessage("the peer's №13 view must survive the other side's deletion (FR-021)")
            .isEqualTo(HttpStatus.OK)

        assertDeletionEndState(alice, chatId, deletedUpTo = sentSeqs.max(), hidden = true)
        assertDeletionEndState(bob, chatId, deletedUpTo = NOT_DELETED, hidden = false)
    }

    /** Contract №14: a repeated DELETE is an idempotent `204` — nothing changes the second time. */
    @Test
    fun `repeated delete is an idempotent 204 that changes nothing`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(bob, chatId, IDEMPOTENT_PREFIX, IDEMPOTENT_SIZE)

        assertThat(deleteChat(alice, chatId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        val watermark = deletedUpToSeq(chatId, alice.id)

        val repeat = deleteChat(alice, chatId)
        assertThat(repeat.statusCode)
            .overridingErrorMessage(
                "a repeated №14 DELETE must be 204 again (idempotent), got <%s>: %s",
                repeat.statusCode,
                repeat.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(deletedUpToSeq(chatId, alice.id))
            .overridingErrorMessage("the repeated DELETE must not move the watermark again")
            .isEqualTo(watermark)

        assertHistoryEmptyFor(alice, chatId)
        assertHistoryIs(bob, chatId, seqs)
    }

    /**
     * data-model §2: the №11 ensure by the deleter clears `hidden` (the chat
     * returns to the list) but KEEPS the watermark — the deleted history is
     * NOT restored, the №15 history stays empty for the deleter.
     */
    @Test
    fun `ensure by the deleter clears hidden but keeps the deletion watermark`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val seqs = sendFrom(bob, chatId, ENSURE_PREFIX, ENSURE_SIZE)
        assertThat(deleteChat(alice, chatId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val reEnsured = ensureChatOk(alice, bob.id)
        assertThat(reEnsured)
            .overridingErrorMessage("№11 ensure must return the SAME dialog after the deletion (FR-021)")
            .isEqualTo(chatId)

        assertThat(hiddenFlag(chatId, alice.id))
            .overridingErrorMessage("№11 ensure must clear the deleter's hidden flag (data-model §2)")
            .isFalse
        assertThat(deletedUpToSeq(chatId, alice.id))
            .overridingErrorMessage("№11 ensure must KEEP the deletion watermark (the history stays hidden)")
            .isEqualTo(seqs.max())
        assertHistoryEmptyFor(alice, chatId)
        assertHistoryIs(bob, chatId, seqs)
    }

    /**
     * FR-021 tail: after the deletion, a NEW incoming message returns the
     * chat to the deleter's visibility with ONLY the new messages — the old
     * suffix stays hidden by the watermark, `hidden` is reset by the write
     * path (T012).
     */
    @Test
    fun `a new incoming message after deletion returns the chat without the old history`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)
        val oldSeqs = sendFrom(bob, chatId, OLD_PREFIX, OLD_SIZE)
        assertThat(deleteChat(alice, chatId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertHistoryEmptyFor(alice, chatId)

        val newSeq = sendMessageOk(bob, chatId, NEW_INCOMING_TEXT)["seq"].asLong()
        assertThat(newSeq).isGreaterThan(oldSeqs.max())

        assertThat(hiddenFlag(chatId, alice.id))
            .overridingErrorMessage("a new incoming must reset the deleter's hidden flag (T012/FR-021)")
            .isFalse
        assertThat(deletedUpToSeq(chatId, alice.id))
            .overridingErrorMessage("the new incoming must NOT restore the deleted history (watermark stays)")
            .isEqualTo(oldSeqs.max())
        assertHistoryIs(alice, chatId, listOf(newSeq))
        assertHistoryIs(bob, chatId, oldSeqs + newSeq)

        assertThat(getChat(alice, chatId).statusCode)
            .overridingErrorMessage("the returned chat must be openable by the former deleter (FR-021)")
            .isEqualTo(HttpStatus.OK)
    }

    /**
     * FR-019: a first incoming from a stranger — no prior chat, no contact —
     * makes the dialog appear on the recipient side AUTOMATICALLY: the
     * implicitly created participant row is readable (`hidden = false`) and
     * both the №13 view and the №15 history answer with the stranger's
     * message right away.
     */
    @Test
    fun `a chat from a stranger appears automatically for the recipient`() {
        val alice = messagingUser("alice")
        val stranger = strangerUser()
        val chatId = ensureChatOk(stranger, alice.id)
        val seq = sendMessageOk(stranger, chatId, STRANGER_TEXT)["seq"].asLong()

        val view = getChat(alice, chatId)
        assertThat(view.statusCode)
            .overridingErrorMessage(
                "a chat started by a stranger must be openable by the recipient with no contact added (FR-019), " +
                    "got <%s>: %s",
                view.statusCode,
                view.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(view.body)["peer"]["id"].asText())
            .overridingErrorMessage("the implicitly created dialog must present the stranger as the peer")
            .isEqualTo(stranger.id.toString())

        assertHistoryIs(alice, chatId, listOf(seq))
        assertThat(hiddenFlag(chatId, alice.id))
            .overridingErrorMessage("the recipient's implicit participant row must not be hidden (FR-019)")
            .isFalse
    }

    /**
     * FR-021/data-model §2 exclusion invariant: a deleted EMPTY chat
     * (`last_seq = 0`) is hidden COMPLETELY — `hidden = true` and the
     * watermark `0 >= last_seq` — the same invariant that excludes a
     * deleted dialog with messages from the №12 list.
     */
    @Test
    fun `an empty chat deleted is hidden completely`() {
        val (alice, bob) = messagingPair()
        val chatId = ensureChatOk(alice, bob.id)

        val deletion = deleteChat(alice, chatId)
        assertThat(deletion.statusCode)
            .overridingErrorMessage(
                "№14 DELETE of an empty chat must answer 204, got <%s>: %s",
                deletion.statusCode,
                deletion.body,
            ).isEqualTo(HttpStatus.NO_CONTENT)

        assertHistoryEmptyFor(alice, chatId)
        assertThat(chatLastSeq(chatId))
            .overridingErrorMessage("a chat without messages must carry last_seq = 0")
            .isZero
        assertThat(hiddenFlag(chatId, alice.id))
            .overridingErrorMessage("an empty deleted chat must be hidden (data-model §2 invariant)")
            .isTrue
        assertThat(deletedUpToSeq(chatId, alice.id))
            .overridingErrorMessage("an empty deleted chat must satisfy deleted_up_to_seq >= last_seq")
            .isZero
        assertThat(hiddenFlag(chatId, bob.id))
            .overridingErrorMessage("the peer's empty chat must stay visible to the peer (FR-021)")
            .isFalse
    }

    /** The №15 history of [user] must be a clean empty page — `messages: []`, no `nextBefore`, no error. */
    private fun assertHistoryEmptyFor(
        user: MessagingUser,
        chatId: UUID,
    ) {
        val response = listMessages(user, chatId)
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the hidden history must read as a CLEAN empty page, got <%s>: %s",
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val page = objectMapper.readTree(response.body)
        assertThat(page["messages"])
            .overridingErrorMessage("the deleter's history must be empty, got: %s", response.body)
            .isEmpty()
        val nextBefore = page["nextBefore"]
        assertThat(nextBefore == null || nextBefore.isNull || nextBefore.isMissingNode)
            .overridingErrorMessage("an empty history page must carry no nextBefore, got: <%s>", nextBefore)
            .isTrue
    }

    /** The №15 history of [user] must be exactly [seqs] — nothing hidden, nothing extra, in the dialog order. */
    private fun assertHistoryIs(
        user: MessagingUser,
        chatId: UUID,
        seqs: List<Long>,
    ) {
        val response = listMessages(user, chatId, limit = seqs.size.coerceAtLeast(1))
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "the history read must answer 200 for <%s>, got <%s>: %s",
                user.username,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        val page = objectMapper.readTree(response.body)
        val actual = page["messages"].map { it["seq"].asLong() }.reversed()
        assertThat(actual)
            .overridingErrorMessage(
                "the history of <%s> must be exactly seqs %s (ascending dialog order), got: %s",
                user.username,
                seqs,
                actual,
            ).containsExactlyElementsOf(seqs)
    }

    /**
     * The exact №14 DELETE end state on the real `chat_participants` row
     * (data-model §2 §«Операции») plus the §2 exclusion invariant
     * `hidden AND deleted_up_to_seq >= chats.last_seq` when hidden.
     */
    private fun assertDeletionEndState(
        user: MessagingUser,
        chatId: UUID,
        deletedUpTo: Long,
        hidden: Boolean,
    ) {
        assertThat(deletedUpToSeq(chatId, user.id))
            .overridingErrorMessage(
                "the №14 DELETE must leave deleted_up_to_seq = <%d> for <%s>",
                deletedUpTo,
                user.username,
            ).isEqualTo(deletedUpTo)
        assertThat(hiddenFlag(chatId, user.id))
            .overridingErrorMessage("the №14 DELETE must leave hidden = <%s> for <%s>", hidden, user.username)
            .isEqualTo(hidden)
        if (hidden) {
            assertThat(deletedUpToSeq(chatId, user.id))
                .overridingErrorMessage(
                    "the deleted dialog must satisfy the №12 exclusion invariant deleted_up_to_seq >= last_seq",
                ).isGreaterThanOrEqualTo(chatLastSeq(chatId))
        }
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

    private fun deletedUpToSeq(
        chatId: UUID,
        userId: UUID,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT deleted_up_to_seq FROM chat_participants WHERE chat_id = ? AND user_id = ?",
            Long::class.java,
            chatId,
            userId,
        ) ?: error("participant row of <$userId> in chat <$chatId> must exist")

    private fun hiddenFlag(
        chatId: UUID,
        userId: UUID,
    ): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT hidden FROM chat_participants WHERE chat_id = ? AND user_id = ?",
            Boolean::class.java,
            chatId,
            userId,
        ) ?: error("participant row of <$userId> in chat <$chatId> must exist")

    private fun chatLastSeq(chatId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT last_seq FROM chats WHERE id = ?",
            Long::class.java,
            chatId,
        ) ?: error("chat <$chatId> must exist")

    private companion object {
        /** `chat_participants` defaults of a side that never deleted anything. */
        const val NOT_DELETED = 0L

        const val DELETED_DIALOG_PREFIX = "deleted-dialog"
        const val DELETED_DIALOG_SIZE = 3

        const val IDEMPOTENT_PREFIX = "idempotent-delete"
        const val IDEMPOTENT_SIZE = 2

        const val ENSURE_PREFIX = "ensure-keeps-watermark"
        const val ENSURE_SIZE = 2

        const val OLD_PREFIX = "old-before-deletion"
        const val OLD_SIZE = 2
        const val NEW_INCOMING_TEXT = "the only message visible after the deletion"

        const val STRANGER_TEXT = "a dialog from a complete stranger (FR-019)"
    }
}
