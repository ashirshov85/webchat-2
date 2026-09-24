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
 * T010 regression IT (tasks.md Phase 3, US1): the JDBC adapter legs the
 * №25/№26 acceptance asserts anchor on — `advanceDelivered` and
 * `loadForSync` of [JdbcParticipantRepository] (data-model 005 сущности
 * 1–2):
 *
 *  * the ack batch advances the CALLER's `delivered_up_to_seq` in every
 *    chat of the batch in ONE transaction (GREATEST per element) while
 *    the peers' rows stay at 0 — the position is per-user state;
 *  * rowcount 0 is a no-op: a repeated or smaller `upToSeq` leaves the
 *    watermark untouched (idempotent), and a later larger ack still
 *    advances it;
 *  * the №26 candidate read returns ONLY chats with a visible
 *    undelivered tail (`chats.last_seq > GREATEST(delivered_up_to_seq,
 *    deleted_up_to_seq)` — `ChatParticipant.hasUndeliveredVisible`): a
 *    fully acked chat and the exact №14 end state are excluded;
 *  * the page order is the latest activity first — `chats.last_seq
 *    DESC`, tie-break `created_at DESC, chat_id` — with `moreChats` by
 *    the remainder in the same read snapshot, and an ack that reaches
 *    the chat head drops the chat from the next page.
 *
 * The HTTP-level T007/T008 stay RED until the №25/№26 routes of
 * T012–T014 land (their TDD note); this IT pins the same repository
 * rules at the adapter boundary — real Testcontainers PG 17, no mocks,
 * every method staging its own users.
 */
@Suppress("TooManyFunctions") // T010: one method per adapter rule of tasks.md
class JdbcParticipantRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var participantRepository: JdbcParticipantRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `advanceDelivered advances every caller position in one batch and leaves the peers at zero`() {
        val me = newUser()
        val bobPeer = newUser()
        val carolPeer = newUser()
        val withBob = chatRepository.ensure(me, bobPeer).chat
        val withCarol = chatRepository.ensure(me, carolPeer).chat
        val fromBob = insertMessages(withBob.id, bobPeer, BATCH_MESSAGES, T1)
        val fromCarol = insertMessages(withCarol.id, carolPeer, BATCH_MESSAGES, T1)
        stageHeads(mapOf(withBob.id to fromBob.last(), withCarol.id to fromCarol.last()))

        participantRepository.advanceDelivered(me, mapOf(withBob.id to fromBob[1], withCarol.id to fromCarol.last()))

        assertThat(deliveredOf(me, withBob.id))
            .overridingErrorMessage("the batch must advance the caller's position in the first chat exactly")
            .isEqualTo(fromBob[1])
        assertThat(deliveredOf(me, withCarol.id))
            .overridingErrorMessage("the batch must advance the caller's position in the second chat exactly")
            .isEqualTo(fromCarol.last())
        assertThat(deliveredOf(bobPeer, withBob.id))
            .overridingErrorMessage("the peer's own position must stay at 0 — the ack is per-user")
            .isZero
        assertThat(deliveredOf(carolPeer, withCarol.id)).isZero
        assertThat(participantRepository.find(withBob.id, me)!!.deliveredUpToSeq)
            .overridingErrorMessage("find must project the advanced delivery position of the participant row")
            .isEqualTo(fromBob[1])
    }

    @Test
    fun `repeated and smaller upToSeq keep the watermark and a later larger ack still advances`() {
        val me = newUser()
        val peer = newUser()
        val chat = chatRepository.ensure(me, peer).chat
        val seqs = insertMessages(chat.id, peer, MONOTONE_MESSAGES, T1)
        stageHeads(mapOf(chat.id to seqs.last()))

        participantRepository.advanceDelivered(me, mapOf(chat.id to seqs[MONOTONE_ACK_INDEX]))
        assertThat(deliveredOf(me, chat.id)).isEqualTo(seqs[MONOTONE_ACK_INDEX])

        participantRepository.advanceDelivered(me, mapOf(chat.id to seqs[MONOTONE_ACK_INDEX]))
        assertThat(deliveredOf(me, chat.id))
            .overridingErrorMessage("a repeated upToSeq is rowcount 0 — no effect (idempotent)")
            .isEqualTo(seqs[MONOTONE_ACK_INDEX])

        participantRepository.advanceDelivered(me, mapOf(chat.id to seqs[0]))
        assertThat(deliveredOf(me, chat.id))
            .overridingErrorMessage("a smaller upToSeq is rowcount 0 — the watermark never rewinds")
            .isEqualTo(seqs[MONOTONE_ACK_INDEX])

        participantRepository.advanceDelivered(me, mapOf(chat.id to seqs.last()))
        assertThat(deliveredOf(me, chat.id))
            .overridingErrorMessage("after the no-ops a larger ack must still advance the position")
            .isEqualTo(seqs.last())
    }

    @Test
    fun `loadForSync returns only chats with a visible undelivered tail by last activity`() {
        val me = newUser()
        val ackedPeer = newUser()
        val deletedPeer = newUser()
        val freshPeer = newUser()
        val midPeer = newUser()
        val tiedOlderPeer = newUser()
        val tiedNewerPeer = newUser()
        val ackedChat = chatRepository.ensure(me, ackedPeer).chat
        val deletedChat = chatRepository.ensure(me, deletedPeer).chat
        val freshChat = chatRepository.ensure(me, freshPeer).chat
        val midChat = chatRepository.ensure(me, midPeer).chat
        val tiedOlderChat = chatRepository.ensure(me, tiedOlderPeer).chat
        val tiedNewerChat = chatRepository.ensure(me, tiedNewerPeer).chat

        val freshSeqs = insertMessages(freshChat.id, freshPeer, FRESH_MESSAGES, T1)
        // The natural identity seqs are GLOBAL — the staged heads below pin the
        // inter-chat order deterministically regardless of the test history.
        stageHeads(
            mapOf(
                // the fully acked dialog: delivered = head → nothing undelivered.
                ackedChat.id to ACKED_HEAD,
                // the exact №14 end state: the whole tail below deleted_up_to_seq.
                deletedChat.id to DELETED_HEAD,
                freshChat.id to FRESH_HEAD,
                midChat.id to MID_HEAD,
                tiedOlderChat.id to TIED_HEAD,
                tiedNewerChat.id to TIED_HEAD,
            ),
        )
        participantRepository.advanceDelivered(me, mapOf(ackedChat.id to ACKED_HEAD))
        jdbcTemplate.update(STAGE_DELETED_SQL, DELETED_HEAD, deletedChat.id, me)
        jdbcTemplate.update(STAGE_DELIVERED_SQL, MID_DELIVERED, midChat.id, me)
        jdbcTemplate.update(STAGE_CREATED_AT_SQL, Timestamp.from(T1), tiedOlderChat.id)
        jdbcTemplate.update(STAGE_CREATED_AT_SQL, Timestamp.from(T2), tiedNewerChat.id)

        val page = participantRepository.loadForSync(me, FULL_CHAT_LIMIT)

        assertThat(page.chats.map { it.chatId })
            .overridingErrorMessage(
                "№26 must page only the visible-undelivered chats, latest activity first " +
                    "(last_seq DESC, tie-break created_at DESC), got <%s>",
                page.chats,
            ).containsExactly(tiedNewerChat.id, tiedOlderChat.id, midChat.id, freshChat.id)
        assertThat(page.moreChats)
            .overridingErrorMessage("no undelivered chat may stay beyond the full page")
            .isFalse

        val mid = page.chats.single { it.chatId == midChat.id }
        assertThat(mid.deliveredUpToSeq)
            .overridingErrorMessage("the candidate projection must carry the server delivery position")
            .isEqualTo(MID_DELIVERED)
        assertThat(mid.deletedUpToSeq).isZero
        assertThat(mid.chatLastSeq)
            .overridingErrorMessage("the candidate projection must carry chats.last_seq (the lastSeq field)")
            .isEqualTo(MID_HEAD)
        val fresh = page.chats.single { it.chatId == freshChat.id }
        assertThat(fresh.chatLastSeq).isEqualTo(FRESH_HEAD)
        assertThat(freshSeqs.size)
            .overridingErrorMessage("the realistic dialog must really carry its seeded messages")
            .isEqualTo(FRESH_MESSAGES)
        assertThat(fresh.deliveredUpToSeq)
            .overridingErrorMessage("the read must never move the delivery position (FR-001)")
            .isZero
    }

    @Test
    fun `loadForSync slices by chatLimit with moreChats until the remainder is gone`() {
        val me = newUser()
        val newestPeer = newUser()
        val middlePeer = newUser()
        val oldestPeer = newUser()
        val newestChat = chatRepository.ensure(me, newestPeer).chat
        val middleChat = chatRepository.ensure(me, middlePeer).chat
        val oldestChat = chatRepository.ensure(me, oldestPeer).chat
        stageHeads(
            mapOf(
                newestChat.id to NEWEST_HEAD,
                middleChat.id to MIDDLE_HEAD,
                oldestChat.id to OLDEST_HEAD,
            ),
        )

        val firstPage = participantRepository.loadForSync(me, TWO_CHAT_LIMIT)
        assertThat(firstPage.chats.map { it.chatId })
            .overridingErrorMessage("chatLimit=2 must keep the two newest undelivered dialogs")
            .containsExactly(newestChat.id, middleChat.id)
        assertThat(firstPage.moreChats)
            .overridingErrorMessage("a left-behind undelivered chat must surface as moreChats=true")
            .isTrue

        val single = participantRepository.loadForSync(me, ONE_CHAT_LIMIT)
        assertThat(single.chats.map { it.chatId }).containsExactly(newestChat.id)
        assertThat(single.moreChats).isTrue

        participantRepository.advanceDelivered(me, mapOf(newestChat.id to NEWEST_HEAD))
        val secondPage = participantRepository.loadForSync(me, TWO_CHAT_LIMIT)
        assertThat(secondPage.chats.map { it.chatId })
            .overridingErrorMessage(
                "an ack reaching the chat head drops it from the next page — the remainder pages on",
            ).containsExactly(middleChat.id, oldestChat.id)
        assertThat(secondPage.moreChats).isFalse
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t010r-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(
            INSERT_USER_SQL,
            id,
            login,
            "$login@example.com",
        )
        return id
    }

    /** Inserts [count] messages with an EXPLICIT `created_at`, returning their `seq`s ascending. */
    private fun insertMessages(
        chatId: UUID,
        senderId: UUID,
        count: Int,
        at: Instant,
    ): List<Long> =
        (0 until count).map { index ->
            jdbcTemplate.queryForObject(
                INSERT_MESSAGE_SQL,
                Long::class.java,
                UUID.randomUUID(),
                chatId,
                senderId,
                "t010 message $index",
                Timestamp.from(at),
            ) ?: error("INSERT … RETURNING seq must answer")
        }

    /** Keeps the `chats.last_seq` cache honest for directly staged rows (the T012 invariant). */
    private fun stageHeads(heads: Map<UUID, Long>) {
        heads.forEach { (chatId, head) -> jdbcTemplate.update(STAGE_HEAD_SQL, head, chatId) }
    }

    private fun deliveredOf(
        userId: UUID,
        chatId: UUID,
    ): Long =
        jdbcTemplate.queryForObject(
            DELIVERED_OF_SQL,
            Long::class.java,
            chatId,
            userId,
        ) ?: error("the participant row of chat <$chatId> and user <$userId> must exist")

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

        const val STAGE_HEAD_SQL = "UPDATE chats SET last_seq = ? WHERE id = ?"

        val STAGE_DELETED_SQL =
            """
            UPDATE chat_participants
            SET deleted_up_to_seq = ?, hidden = true
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        val STAGE_DELIVERED_SQL =
            """
            UPDATE chat_participants
            SET delivered_up_to_seq = ?
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        const val STAGE_CREATED_AT_SQL = "UPDATE chats SET created_at = ? WHERE id = ?"

        val DELIVERED_OF_SQL =
            """
            SELECT delivered_up_to_seq FROM chat_participants
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        /** Deterministic instants: T1 < T2 (the №26 tie-break of equal last_seq). */
        val T1 = Instant.parse("2026-01-01T00:00:00Z")
        val T2 = Instant.parse("2026-01-01T00:00:01Z")

        const val BATCH_MESSAGES = 3
        const val MONOTONE_MESSAGES = 5
        const val MONOTONE_ACK_INDEX = 2
        const val FRESH_MESSAGES = 2

        const val FULL_CHAT_LIMIT = 50
        const val TWO_CHAT_LIMIT = 2
        const val ONE_CHAT_LIMIT = 1

        /** Staged heads: the tied pair above MID above FRESH. */
        const val ACKED_HEAD = 10L
        const val DELETED_HEAD = 10L
        const val FRESH_HEAD = 10L
        const val MID_HEAD = 20L
        const val MID_DELIVERED = 10L
        const val TIED_HEAD = 30L
        const val NEWEST_HEAD = 30L
        const val MIDDLE_HEAD = 20L
        const val OLDEST_HEAD = 10L
    }
}
