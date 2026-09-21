package webchat.backend.chats.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.NewMessage
import java.util.UUID

/**
 * T012 (tasks.md Phase 3): repository-level IT of the exactly-once message
 * write against real PostgreSQL 17 (Testcontainers) — the SQL/persistence
 * half that the Message unit test of T009 defers to "the IT of T012/T029".
 *
 * Asserts the single-transaction invariants of data-model 004 §3 step 4
 * (research.md 004 §3):
 * - `INSERT … ON CONFLICT (id) DO NOTHING RETURNING *` → [MessageInsertResult.Inserted]
 *   with the IDENTITY `seq` allocated and `chats.last_seq` advanced IN THE
 *   SAME transaction (monotone — an out-of-order commit never regresses it);
 * - a retried client UUID → [MessageInsertResult.Duplicate] of the SAME row
 *   with NO side effects (no `last_seq` bump, no `hidden` reset — the dedup
 *   path must not resurrect a deleted chat);
 * - an ACTUAL new record resets `hidden = false` for BOTH participants
 *   (FR-021: a new incoming returns the chat to the list while the deleted
 *   history stays behind the watermark);
 * - the history page is `ORDER BY seq DESC`, visibility-filtered per viewer
 *   (`seq > deleted_up_to_seq`), capped by `limit`, with the EXCLUSIVE
 *   `before` cursor (FR-008) — an empty boundary page is a correct result.
 */
class JdbcMessageRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var messageRepository: JdbcMessageRepository

    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `insert stores the record and advances chats last_seq in the same transaction`() {
        val (chat, alice, bob) = dialog()

        val result = messageRepository.insert(newMessage(chat, alice, "привет"))

        assertThat(result).isInstanceOf(MessageInsertResult.Inserted::class.java)
        val stored = (result as MessageInsertResult.Inserted).message
        assertThat(stored.seq).isGreaterThanOrEqualTo(1)
        assertThat(stored.id).isNotNull()
        assertThat(stored.chatId).isEqualTo(chat)
        assertThat(stored.senderId).isEqualTo(alice)
        assertThat(stored.text.value).isEqualTo("привет")
        assertThat(stored.createdAt).isNotNull()
        assertThat(chatLastSeq(chat)).isEqualTo(stored.seq)
        assertThat(messageRepository.findById(stored.id)).isEqualTo(stored)
        assertThat(participantIdsOf(chat)).containsExactlyInAnyOrder(alice, bob)
    }

    @Test
    fun `retrying the same client uuid resolves the same record without any side effects`() {
        val (chat, alice, _) = dialog()
        val candidate = newMessage(chat, alice, "дедуп")

        val first = messageRepository.insert(candidate) as MessageInsertResult.Inserted

        val retry = messageRepository.insert(candidate)

        assertThat(retry).isInstanceOf(MessageInsertResult.Duplicate::class.java)
        assertThat((retry as MessageInsertResult.Duplicate).existing).isEqualTo(first.message)
        assertThat(chatLastSeq(chat)).isEqualTo(first.message.seq)
        assertThat(messageCount(chat)).isEqualTo(1)

        val next = messageRepository.insert(newMessage(chat, alice, "следующее")) as MessageInsertResult.Inserted

        assertThat(next.message.seq)
            .overridingErrorMessage("a fresh record gets a NEW per-chat seq (FR-006)")
            .isGreaterThan(first.message.seq)
        assertThat(messageCount(chat)).isEqualTo(2)
    }

    @Test
    fun `an actual new record un-hides both participants while the dedup path does not`() {
        val (chat, alice, bob) = dialog()
        val recorded = (messageRepository.insert(newMessage(chat, alice, "первое")) as MessageInsertResult.Inserted).message
        jdbcTemplate.update(HIDE_ALL_SQL, chat)
        assertThat(hidden(chat, alice)).isTrue()
        assertThat(hidden(chat, bob)).isTrue()

        val dupRetry =
            messageRepository.insert(
                NewMessage(recorded.id, chat, alice, recorded.text),
            )

        assertThat(dupRetry).isInstanceOf(MessageInsertResult.Duplicate::class.java)
        assertThat(hidden(chat, alice)).overridingErrorMessage("dedup must not resurrect a deleted chat").isTrue()
        assertThat(hidden(chat, bob)).isTrue()

        messageRepository.insert(newMessage(chat, alice, "новое"))

        assertThat(hidden(chat, alice))
            .overridingErrorMessage("a new record resets hidden for BOTH participants (FR-021)")
            .isFalse()
        assertThat(hidden(chat, bob)).isFalse()
    }

    @Test
    fun `last_seq never regresses on an out-of-order seq`() {
        val (chat, alice, _) = dialog()
        val first = (messageRepository.insert(newMessage(chat, alice, "первое")) as MessageInsertResult.Inserted).message

        jdbcTemplate.update(SET_LAST_SEQ_SQL, first.seq + 100, chat)

        val second = (messageRepository.insert(newMessage(chat, alice, "второе")) as MessageInsertResult.Inserted).message

        assertThat(second.seq).isEqualTo(first.seq + 1)
        assertThat(chatLastSeq(chat))
            .overridingErrorMessage("last_seq is a monotone MAX(seq) cache, not a blind overwrite")
            .isEqualTo(first.seq + 100)
    }

    @Test
    fun `findVisiblePage orders descending, caps by limit and honors the exclusive before cursor`() {
        val (chat, alice, _) = dialog()
        // The IDENTITY is GLOBAL (research.md 004 §3): only the per-chat
        // ORDER is contractual, so the assertions are relative to the
        // actually allocated seqs.
        val sent = sentSeqs(chat, alice, PAGE_MESSAGES)

        val newestPage = messageRepository.findVisiblePage(chat, alice, before = null, limit = 2)

        assertThat(newestPage.map { it.seq }).containsExactly(sent[4], sent[3])

        val olderPage = messageRepository.findVisiblePage(chat, alice, before = sent[3], limit = 2)

        assertThat(olderPage.map { it.seq })
            .overridingErrorMessage("the before cursor is EXCLUSIVE (FR-008)")
            .containsExactly(sent[2], sent[1])

        val boundaryPage = messageRepository.findVisiblePage(chat, alice, before = sent[0], limit = 2)

        assertThat(boundaryPage).isEmpty()

        val wholeHistory = messageRepository.findVisiblePage(chat, alice, before = null, limit = 50)

        assertThat(wholeHistory.map { it.seq }).containsExactly(sent[4], sent[3], sent[2], sent[1], sent[0])
    }

    @Test
    fun `findVisiblePage filters each viewer by their own deletion watermark`() {
        val (chat, alice, bob) = dialog()
        repeat(DELETED_MESSAGES) {
            messageRepository.insert(newMessage(chat, if (it % 2 == 0) alice else bob, "msg-$it"))
        }
        val deletedSeqs = messageRepository.findVisiblePage(chat, alice, before = null, limit = 50).map { it.seq }.reversed()
        jdbcTemplate.update(DELETE_HISTORY_SQL, chat, alice)

        val afterDeletion = messageRepository.findVisiblePage(chat, alice, before = null, limit = 50)

        assertThat(afterDeletion).isEmpty()

        val revived = (messageRepository.insert(newMessage(chat, bob, "после удаления")) as MessageInsertResult.Inserted).message

        val alicePage = messageRepository.findVisiblePage(chat, alice, before = null, limit = 50)

        assertThat(alicePage.map { it.seq })
            .overridingErrorMessage("deleted history stays behind the watermark, the new incoming is visible (FR-021)")
            .containsExactly(revived.seq)

        val bobPage = messageRepository.findVisiblePage(chat, bob, before = null, limit = 50)

        assertThat(bobPage.map { it.seq }).containsExactly(revived.seq, deletedSeqs[2], deletedSeqs[1], deletedSeqs[0])
    }

    @Test
    fun `findVisiblePage of an empty chat is a correct empty page`() {
        val (chat, alice, _) = dialog()

        assertThat(messageRepository.findVisiblePage(chat, alice, before = null, limit = 50)).isEmpty()
    }

    private fun dialog(): Triple<UUID, UUID, UUID> {
        val alice = newUser()
        val bob = newUser()
        val chat = chatRepository.ensure(alice, bob).chat.id
        return Triple(chat, alice, bob)
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t012-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(
            INSERT_USER_SQL,
            id,
            login,
            "$login@example.com",
        )
        return id
    }

    private fun newMessage(
        chatId: UUID,
        senderId: UUID,
        text: String,
    ): NewMessage = NewMessage(id = UUID.randomUUID(), chatId = chatId, senderId = senderId, text = MessageText.normalize(text))

    /** Sends [count] fresh messages and returns their seqs ASCENDING as actually allocated. */
    private fun sentSeqs(
        chatId: UUID,
        senderId: UUID,
        count: Int,
    ): List<Long> =
        (1..count)
            .map {
                (messageRepository.insert(newMessage(chatId, senderId, "msg-$it")) as MessageInsertResult.Inserted).message.seq
            }.sorted()

    private fun chatLastSeq(chatId: UUID): Long =
        jdbcTemplate.queryForObject("SELECT last_seq FROM chats WHERE id = ?", Long::class.java, chatId)!!

    private fun messageCount(chatId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM messages WHERE chat_id = ?", Int::class.java, chatId)!!

    private fun hidden(
        chatId: UUID,
        userId: UUID,
    ): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT hidden FROM chat_participants WHERE chat_id = ? AND user_id = ?",
            Boolean::class.java,
            chatId,
            userId,
        )!!

    private fun participantIdsOf(chatId: UUID): List<UUID> =
        jdbcTemplate.queryForList("SELECT user_id FROM chat_participants WHERE chat_id = ?", UUID::class.java, chatId)

    private companion object {
        const val PAGE_MESSAGES = 5
        const val DELETED_MESSAGES = 3

        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()

        val HIDE_ALL_SQL =
            """
            UPDATE chat_participants SET hidden = true WHERE chat_id = ?
            """.trimIndent()

        val SET_LAST_SEQ_SQL =
            """
            UPDATE chats SET last_seq = ? WHERE id = ?
            """.trimIndent()

        val DELETE_HISTORY_SQL =
            """
            UPDATE chat_participants
            SET deleted_up_to_seq = chats.last_seq, hidden = true
            FROM chats
            WHERE chats.id = chat_participants.chat_id
              AND chat_participants.chat_id = ? AND chat_participants.user_id = ?
            """.trimIndent()
    }
}
