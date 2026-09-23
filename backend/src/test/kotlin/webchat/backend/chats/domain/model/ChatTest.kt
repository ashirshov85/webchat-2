package webchat.backend.chats.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the Chat invariants mandated for T009 (data-model
 * 004 §1): canonical pair order `user_low_id < user_high_id` (FR-001 —
 * exactly one dialog per pair, UNIQUE pair), no self-dialogs, and the
 * monotone `last_seq` cache maintained by the message INSERT transaction.
 * Persistence/ensure behaviour is covered by the IT of T011/T012.
 */
class ChatTest {
    @Test
    fun `orders the pair canonically regardless of argument order`() {
        val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val high = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val direct = Chat.forPair(ID, low, high, CREATED_AT)
        val swapped = Chat.forPair(ID, high, low, CREATED_AT)

        assertThat(direct.userLowId).isEqualTo(low)
        assertThat(direct.userHighId).isEqualTo(high)
        assertThat(swapped.userLowId).isEqualTo(low)
        assertThat(swapped.userHighId).isEqualTo(high)
        assertThat(direct).isEqualTo(swapped)
    }

    @Test
    fun `rejects a dialog of a user with themselves`() {
        assertThrows<IllegalArgumentException> { Chat.forPair(ID, ALICE, ALICE, CREATED_AT) }
    }

    @Test
    fun `rejects a non-canonical pair order at construction`() {
        val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val high = UUID.fromString("00000000-0000-0000-0000-000000000002")

        assertThrows<IllegalArgumentException> { Chat(ID, high, low, CREATED_AT) }
    }

    @Test
    fun `starts with an empty last_seq cache`() {
        val chat = Chat.forPair(ID, ALICE, BOB, CREATED_AT)

        assertThat(chat.lastSeq).isZero
    }

    @Test
    fun `membership covers both participants only`() {
        val chat = Chat.forPair(ID, ALICE, BOB, CREATED_AT)

        assertThat(chat.involves(ALICE)).isTrue
        assertThat(chat.involves(BOB)).isTrue
        assertThat(chat.involves(CAROL)).isFalse
    }

    @Test
    fun `peerOf resolves each participant and rejects strangers`() {
        val chat = Chat.forPair(ID, ALICE, BOB, CREATED_AT)

        assertThat(chat.peerOf(ALICE)).isEqualTo(BOB)
        assertThat(chat.peerOf(BOB)).isEqualTo(ALICE)
        assertThat(chat.peerOf(CAROL)).isNull()
    }

    @Test
    fun `last_seq cache advances monotonically`() {
        val chat =
            Chat
                .forPair(ID, ALICE, BOB, CREATED_AT)
                .advanceLastSeq(7)
                .advanceLastSeq(7)
                .advanceLastSeq(8)

        assertThat(chat.lastSeq).isEqualTo(8)
    }

    @Test
    fun `last_seq cache never moves backwards`() {
        val chat = Chat.forPair(ID, ALICE, BOB, CREATED_AT).advanceLastSeq(5)

        assertThrows<IllegalArgumentException> { chat.advanceLastSeq(4) }
    }

    private companion object {
        val ID = UUID.fromString("00000000-0000-0000-0000-00000000aa01")

        val ALICE = UUID.fromString("00000000-0000-0000-0000-00000000aa11")

        val BOB = UUID.fromString("00000000-0000-0000-0000-00000000aa12")

        val CAROL = UUID.fromString("00000000-0000-0000-0000-00000000aa13")

        val CREATED_AT = Instant.parse("2026-09-20T12:00:00Z")
    }
}
