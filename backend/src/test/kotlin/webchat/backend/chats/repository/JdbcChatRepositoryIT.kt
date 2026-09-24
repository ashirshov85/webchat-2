package webchat.backend.chats.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Regression IT for the canonical pair resolve of T011: the low/high order
 * MUST follow PostgreSQL's UNSIGNED byte-wise uuid comparison (the V10
 * `ck_chats_low_lt_high` CHECK and the `ux_chats_user_pair` key), while
 * Java's `UUID.compareTo` is SIGNED on the two 64-bit halves. The two
 * orders disagree exactly when the most significant byte crosses 0x80 —
 * the deterministic pair below pins that boundary, which the random pairs
 * of JdbcMessageRepositoryIT hit statistically (~50% of dialogs → CHECK
 * violation, a latent 500 on `/chats/ensure`).
 */
class JdbcChatRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `ensure canonicalizes the pair by PG byte order, not Java signed uuid order`() {
        // Signed msb: 0xf0.. is NEGATIVE, 0x10.. is positive → Java orders
        // f0.. BEFORE 10..; PG memcmp orders 0x10 < 0xf0 — only the latter
        // satisfies the CHECK.
        val highMsb = UUID.fromString("f0000000-0000-4000-8000-00000000000a")
        val lowMsb = UUID.fromString("10000000-0000-4000-8000-00000000000b")
        newUser(highMsb)
        newUser(lowMsb)

        val forward = chatRepository.ensure(highMsb, lowMsb).chat
        val reverse = chatRepository.ensure(lowMsb, highMsb).chat

        assertThat(forward.userLowId)
            .overridingErrorMessage("user_low_id must be the PG-byte-order least of the pair")
            .isEqualTo(lowMsb)
        assertThat(forward.userHighId).isEqualTo(highMsb)
        assertThat(reverse.id)
            .overridingErrorMessage("both call orders resolve the SAME dialog (FR-001/FR-018)")
            .isEqualTo(forward.id)
    }

    /**
     * T055 (№12, FR-014): the sort key is the `createdAt` of the last
     * VISIBLE message DESC — never `seq` (a per-chat cursor, plan.md).
     * Dialogs whose last messages share ONE instant tie-break by `chat_id`
     * (PG byte order), and chats without visible messages keep their
     * place BELOW the correspondence (NULLS LAST, FR-018). The rows are
     * staged with EXPLICIT `created_at` — the deterministic twin of the
     * HTTP-level ChatListIT.
     */
    @Test
    fun `listForUser sorts by the last visible createdAt with the chat_id tie-break and empty dialogs last`() {
        val me = newUser()
        val olderPeer = newUser()
        val tiedPeer = newUser()
        val newestPeer = newUser()
        val emptyPeer = newUser()
        val olderChat = chatRepository.ensure(me, olderPeer).chat
        val tiedChat = chatRepository.ensure(me, tiedPeer).chat
        val newestChat = chatRepository.ensure(me, newestPeer).chat
        chatRepository.ensure(me, emptyPeer) // no messages ever

        insertMessage(olderChat.id, olderPeer, OLDER_TEXT, T1)
        insertMessage(tiedChat.id, tiedPeer, TIED_TEXT, T1) // the SAME instant as OLDER
        insertMessage(newestChat.id, newestPeer, NEWEST_TEXT, T2)

        val ids = chatRepository.listForUser(me).map { it.chatId }

        // The tie-break follows PG byte-order uuid comparison (memcmp),
        // NOT Java's signed UUID.compareTo — the hex form compares exactly
        // (the same rule as Chat.canonicalPair).
        val tiedPair = listOf(olderChat.id, tiedChat.id).sortedBy { it.toString() }
        assertThat(ids)
            .overridingErrorMessage(
                "№12 must sort by last visible createdAt DESC, tie-break chat_id, empty dialogs last, got <%s>",
                ids,
            ).containsExactly(newestChat.id, tiedPair[0], tiedPair[1], emptyChatId(me, emptyPeer))
    }

    /**
     * T055 (№12, data-model 004 §2): the ONLY exclusion is the FULLY
     * deleted dialog `hidden AND deleted_up_to_seq >= chats.last_seq` —
     * a `hidden` dialog WITH a visible tail stays in the list (the
     * FR-021 return until T012 resets the flag); `lastMessage` is the
     * newest message above the watermark; `unreadCount` counts exactly
     * the incoming messages of the DELIVERED visible tail (005 T031,
     * data-model сущность 3: `GREATEST(last_read, deleted) < seq ≤
     * LEAST(chats.last_seq, delivered)` — the staged rows carry the
     * delivery position the ack №25 would have written); `blockedByMe`
     * is the caller's own mark.
     */
    @Test
    fun `listForUser excludes only fully deleted dialogs and aggregates per user`() {
        val me = newUser()
        val deletedPeer = newUser()
        val alivePeer = newUser()
        val blockedPeer = newUser()
        val deletedChat = chatRepository.ensure(me, deletedPeer).chat
        val aliveChat = chatRepository.ensure(me, alivePeer).chat
        val blockedChat = chatRepository.ensure(me, blockedPeer).chat

        insertMessage(aliveChat.id, me, MINE_TEXT, T1)
        val theirsSeq = insertMessage(aliveChat.id, alivePeer, THEIRS_TEXT, T2)
        insertMessage(deletedChat.id, deletedPeer, DELETED_TEXT, T1)
        insertMessage(blockedChat.id, blockedPeer, BLOCKED_TEXT, T1)
        advanceLastSeq(aliveChat.id, theirsSeq)
        advanceLastSeq(deletedChat.id, lastInsertedSeq(deletedChat.id))
        advanceLastSeq(blockedChat.id, lastInsertedSeq(blockedChat.id))

        stagePerUserState(
            deletedChat.id,
            me,
            deletedUpToSeq = lastInsertedSeq(deletedChat.id),
            hidden = true,
            // the V12 backfill discipline: delivered = GREATEST(read, deleted)
            deliveredUpToSeq = lastInsertedSeq(deletedChat.id),
        )
        stagePerUserState(
            aliveChat.id,
            me,
            deletedUpToSeq = 0,
            hidden = true, // hidden WITH a visible tail
            deliveredUpToSeq = theirsSeq, // the ack №25 end state — the incoming tail counts (T031)
        )
        jdbcTemplate.update(INSERT_BLOCK_SQL, me, blockedPeer)

        val entries = chatRepository.listForUser(me).associateBy { it.chatId }

        assertThat(entries)
            .overridingErrorMessage("the fully deleted dialog must be the ONLY exclusion (data-model §2)")
            .doesNotContainKey(deletedChat.id)

        val alive = entries.getValue(aliveChat.id)
        assertThat(alive.lastMessage!!.seq)
            .overridingErrorMessage("lastMessage must be the newest message ABOVE the deletion watermark")
            .isEqualTo(theirsSeq)
        assertThat(alive.lastMessage.senderId)
            .overridingErrorMessage(
                "lastMessage must be the peer's message — the newest visible, not the caller's own older one",
            ).isEqualTo(alivePeer)
        assertThat(alive.unreadCount)
            .overridingErrorMessage(
                "unreadCount must count the incoming messages of the delivered visible tail only " +
                    "(T031: seq ≤ delivered)",
            ).isEqualTo(1L)

        assertThat(entries.getValue(blockedChat.id).blockedByMe)
            .overridingErrorMessage("blockedByMe must carry the caller's own block mark (FR-020)")
            .isTrue
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        newUser(id)
        return id
    }

    private fun newUser(id: UUID) {
        val login = "t011r-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(
            INSERT_USER_SQL,
            id,
            login,
            "$login@example.com",
        )
    }

    /** Inserts a message with an EXPLICIT `created_at` and returns the assigned `seq`. */
    private fun insertMessage(
        chatId: UUID,
        senderId: UUID,
        text: String,
        at: Instant,
    ): Long =
        jdbcTemplate.queryForObject(
            INSERT_MESSAGE_SQL,
            Long::class.java,
            UUID.randomUUID(),
            chatId,
            senderId,
            text,
            Timestamp.from(at),
        ) ?: error("INSERT … RETURNING seq must answer")

    /** Keeps the `chats.last_seq` cache honest for directly staged rows (the T012 invariant). */
    private fun advanceLastSeq(
        chatId: UUID,
        seq: Long,
    ) {
        jdbcTemplate.update("UPDATE chats SET last_seq = ? WHERE id = ?", seq, chatId)
    }

    private fun lastInsertedSeq(chatId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT coalesce(max(seq), 0) FROM messages WHERE chat_id = ?",
            Long::class.java,
            chatId,
        ) ?: error("chat <$chatId> must carry messages")

    /**
     * Stages the exact №14 end state (or a partial one) on ONE participant
     * row, including the delivery position the ack №25 would have written
     * (005 T031 — the badge is delivery-bounded down in the list query).
     */
    private fun stagePerUserState(
        chatId: UUID,
        userId: UUID,
        deletedUpToSeq: Long,
        hidden: Boolean,
        deliveredUpToSeq: Long,
    ) {
        jdbcTemplate.update(
            STAGE_PARTICIPANT_SQL,
            deletedUpToSeq,
            deliveredUpToSeq,
            hidden,
            chatId,
            userId,
        )
    }

    private fun emptyChatId(
        me: UUID,
        peer: UUID,
    ): UUID =
        jdbcTemplate.queryForObject(
            EMPTY_CHAT_ID_SQL,
            UUID::class.java,
            me,
            peer,
            peer,
            me,
        ) ?: error("the empty dialog of <$me> and <$peer> must exist")

    private companion object {
        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()

        val INSERT_MESSAGE_SQL =
            """
            INSERT INTO messages (id, chat_id, sender_id, text, created_at)
            VALUES (?, ?, ?, ?, ?)
            RETURNING seq
            """.trimIndent()

        val STAGE_PARTICIPANT_SQL =
            """
            UPDATE chat_participants
            SET deleted_up_to_seq = ?, delivered_up_to_seq = ?, hidden = ?
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        val INSERT_BLOCK_SQL =
            """
            INSERT INTO user_blocks (blocker_id, blocked_id)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent()

        val EMPTY_CHAT_ID_SQL =
            """
            SELECT id FROM chats
             WHERE (user_low_id = ? AND user_high_id = ?)
                OR (user_low_id = ? AND user_high_id = ?)
            """.trimIndent()

        /** Deterministic message instants: T1 < T2, with an exact TIE inside T1. */
        val T1 = Instant.parse("2026-01-01T00:00:00Z")
        val T2 = Instant.parse("2026-01-01T00:00:01Z")

        const val OLDER_TEXT = "older"
        const val TIED_TEXT = "tied on the same instant"
        const val NEWEST_TEXT = "newest"
        const val MINE_TEXT = "my own message never counts"
        const val THEIRS_TEXT = "their newest visible message"
        const val DELETED_TEXT = "fully deleted suffix"
        const val BLOCKED_TEXT = "blocked dialog fixture"
    }
}
