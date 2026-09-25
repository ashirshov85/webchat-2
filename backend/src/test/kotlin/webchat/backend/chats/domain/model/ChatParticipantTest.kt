package webchat.backend.chats.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the ChatParticipant invariants mandated for T009
 * (data-model 004 §2): non-negative read/deletion watermarks (V10 CHECKs),
 * monotone GREATEST-update of `last_read_seq` (US4-5: smaller/repeat does
 * nothing — rowcount 0 → no event), per-user deletion `deleted_up_to_seq`
 * + `hidden` (FR-021) and the derived per-user visibility rules. Since
 * 005 (T006): the delivery position `delivered_up_to_seq` (V12 CHECK) —
 * non-negative, monotone GREATEST-advance by ack №25 only (FR-001) — and
 * the №26 undelivered-tail selection predicate. SQL-level behaviour is
 * covered by the IT of T011/T042/T049 and, for the sync port, T010.
 */
class ChatParticipantTest {
    @Test
    fun `starts with zero watermarks and a visible chat`() {
        val participant = ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)

        assertThat(participant.lastReadSeq).isZero
        assertThat(participant.deletedUpToSeq).isZero
        assertThat(participant.deliveredUpToSeq).isZero
        assertThat(participant.hidden).isFalse
    }

    @Test
    fun `rejects negative watermarks`() {
        assertThrows<IllegalArgumentException> {
            ChatParticipant(CHAT_ID, ALICE, lastReadSeq = -1, createdAt = CREATED_AT)
        }
        assertThrows<IllegalArgumentException> {
            ChatParticipant(CHAT_ID, ALICE, deletedUpToSeq = -1, createdAt = CREATED_AT)
        }
        assertThrows<IllegalArgumentException> {
            ChatParticipant(CHAT_ID, ALICE, deliveredUpToSeq = -1, createdAt = CREATED_AT)
        }
    }

    @Test
    fun `advanceReadUpTo moves the watermark only forward`() {
        val advanced =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .advanceReadUpTo(5)!!

        assertThat(advanced.lastReadSeq).isEqualTo(5)
        assertThat(advanced.advanceReadUpTo(5)).isNull()
        assertThat(advanced.advanceReadUpTo(3)).isNull()
        assertThat(advanced.advanceReadUpTo(6)!!.lastReadSeq).isEqualTo(6)
    }

    @Test
    fun `advanceDeliveredUpTo moves the delivery position only forward`() {
        val advanced =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .advanceDeliveredUpTo(5)!!

        assertThat(advanced.deliveredUpToSeq).isEqualTo(5)
        assertThat(advanced.advanceDeliveredUpTo(5)).isNull()
        assertThat(advanced.advanceDeliveredUpTo(3)).isNull()
        assertThat(advanced.advanceDeliveredUpTo(7)!!.deliveredUpToSeq).isEqualTo(7)
    }

    @Test
    fun `delivery position survives read and deletion transitions untouched`() {
        val participant =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .advanceDeliveredUpTo(6)!!
                .advanceReadUpTo(4)!!
                .deleteUpTo(chatLastSeq = 9)

        assertThat(participant.deliveredUpToSeq).isEqualTo(6)
    }

    @Test
    fun `deleteUpTo hides the dialog at the deletion watermark`() {
        val deleted =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .advanceReadUpTo(4)!!
                .deleteUpTo(chatLastSeq = 9)

        assertThat(deleted.deletedUpToSeq).isEqualTo(9)
        assertThat(deleted.hidden).isTrue
        assertThat(deleted.lastReadSeq).isEqualTo(4)
    }

    @Test
    fun `unhide keeps the deletion watermark so old history stays hidden`() {
        val restored =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .deleteUpTo(chatLastSeq = 9)
                .unhide()

        assertThat(restored.hidden).isFalse
        assertThat(restored.deletedUpToSeq).isEqualTo(9)
    }

    @Test
    fun `unhide of an already visible chat is a no-op`() {
        val participant = ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)

        assertThat(participant.unhide()).isEqualTo(participant)
    }

    @Test
    fun `message is visible strictly above the deletion watermark`() {
        val participant =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .deleteUpTo(chatLastSeq = 9)

        assertThat(participant.isVisible(9)).isFalse
        assertThat(participant.isVisible(10)).isTrue
    }

    @Test
    fun `unread is an incoming visible message above the read watermark`() {
        val participant =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .advanceReadUpTo(2)!!

        assertThat(participant.isUnread(incoming(seq = 1))).isFalse
        assertThat(participant.isUnread(incoming(seq = 2))).isFalse
        assertThat(participant.isUnread(incoming(seq = 3))).isTrue
        assertThat(participant.isUnread(own(seq = 3))).isFalse
    }

    @Test
    fun `deleted history is never unread`() {
        val participant =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .deleteUpTo(chatLastSeq = 9)

        assertThat(participant.isUnread(incoming(seq = 9))).isFalse
        assertThat(participant.isUnread(incoming(seq = 10))).isTrue
    }

    @Test
    fun `undelivered tail exists strictly above delivery and deletion watermarks`() {
        val participant =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .advanceDeliveredUpTo(4)!!
                .copy(deletedUpToSeq = 2)

        assertThat(participant.hasUndeliveredVisible(chatLastSeq = 9)).isTrue
        assertThat(participant.hasUndeliveredVisible(chatLastSeq = 5)).isTrue
        assertThat(participant.hasUndeliveredVisible(chatLastSeq = 4)).isFalse
        assertThat(participant.copy(deletedUpToSeq = 9).hasUndeliveredVisible(chatLastSeq = 9)).isFalse
        assertThat(
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .hasUndeliveredVisible(chatLastSeq = 0),
        ).isFalse
    }

    @Test
    fun `list exclusion holds only for deleted chats without new incoming`() {
        val deleted =
            ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
                .deleteUpTo(chatLastSeq = 9)

        assertThat(deleted.excludedFromList(chatLastSeq = 9)).isTrue
        assertThat(deleted.excludedFromList(chatLastSeq = 10)).isFalse

        val visible = ChatParticipant(chatId = CHAT_ID, userId = ALICE, createdAt = CREATED_AT)
        assertThat(visible.excludedFromList(chatLastSeq = 0)).isFalse
    }

    private fun incoming(seq: Long) = message(senderId = BOB, seq = seq)

    private fun own(seq: Long) = message(senderId = ALICE, seq = seq)

    private fun message(
        senderId: UUID,
        seq: Long,
    ): Message =
        Message(
            id = UUID.randomUUID(),
            chatId = CHAT_ID,
            senderId = senderId,
            text = MessageText.normalize("текст $seq"),
            seq = seq,
            createdAt = CREATED_AT,
        )

    private companion object {
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-00000000bb01")

        val ALICE = UUID.fromString("00000000-0000-0000-0000-00000000bb11")

        val BOB = UUID.fromString("00000000-0000-0000-0000-00000000bb12")

        val CREATED_AT = Instant.parse("2026-09-20T12:00:00Z")
    }
}
