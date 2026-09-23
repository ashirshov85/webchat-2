package webchat.backend.chats.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the Message invariants mandated for T009
 * (data-model 004 §3): the id is the client-generated dedup key (FR-004),
 * the server `seq` is a positive per-chat order cursor (FR-006) and the
 * read state is NOT stored — it is derived from the peer's
 * `last_read_seq` watermark. SQL/persistence behaviour is covered by the
 * IT of T012/T029.
 */
class MessageTest {
    @Test
    fun `holds the normalized text verbatim`() {
        val message = message(text = "  привет мир  ")

        assertThat(message.text.value).isEqualTo("привет мир")
    }

    @Test
    fun `rejects a non-positive seq`() {
        assertThrows<IllegalArgumentException> { message(seq = 0) }
        assertThrows<IllegalArgumentException> { message(seq = -1) }
    }

    @Test
    fun `read_by_peer is derived from the peer read watermark`() {
        val message = message(seq = 5)

        assertThat(message.isReadBy(peerLastReadSeq = 4)).isFalse
        assertThat(message.isReadBy(peerLastReadSeq = 5)).isTrue
        assertThat(message.isReadBy(peerLastReadSeq = 6)).isTrue
    }

    @Test
    fun `identity is the client message id`() {
        val clientMessageId = UUID.randomUUID()

        assertThat(message(id = clientMessageId).id).isEqualTo(clientMessageId)
    }

    private fun message(
        id: UUID = UUID.randomUUID(),
        text: String = "текст",
        seq: Long = 1,
    ): Message =
        Message(
            id = id,
            chatId = CHAT_ID,
            senderId = ALICE,
            text = MessageText.normalize(text),
            seq = seq,
            createdAt = CREATED_AT,
        )

    private companion object {
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-00000000cc01")

        val ALICE = UUID.fromString("00000000-0000-0000-0000-00000000cc11")

        val CREATED_AT = Instant.parse("2026-09-20T12:00:00Z")
    }
}
