package webchat.backend.chats.domain.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
 *
 * T016 (009, data-model §Сущность 4): the system-widget shape — the kind
 * discriminates the record family, the V18 `ck_messages_kind_shape`
 * invariant (`kind='user'` ⟺ sender ⟺ no payload; `kind='system'` ⟺ NULL
 * sender + a payload object) holds at the model boundary, and the wire
 * label is the lower-case contract enum.
 */
class MessageTest {
    private val objectMapper = ObjectMapper()

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
    fun `read_by-peer is derived from the peer read watermark`() {
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

    @Test
    fun `a system record carries a null sender and its widget payload`() {
        val payload = widgetPayload()
        val message = systemMessage(payload = payload)

        assertThat(message.kind).isEqualTo(MessageKind.SYSTEM)
        assertThat(message.senderId).isNull()
        assertThat(message.payload).isEqualTo(payload)
        assertThat(message.kindLabel()).isEqualTo("system")
    }

    @Test
    fun `the kind shape invariant mirrors the V18 check`() {
        // kind='user' without a sender — impossible shape.
        assertThrows<IllegalArgumentException> { message(senderId = null) }
        // kind='user' with a payload — impossible shape.
        assertThrows<IllegalArgumentException> { message(payload = widgetPayload()) }
        // kind='system' with a sender — impossible shape.
        assertThrows<IllegalArgumentException> {
            systemMessage(senderId = ALICE, payload = widgetPayload())
        }
        // kind='system' without a payload — impossible shape.
        assertThrows<IllegalArgumentException> { systemMessage(payload = null) }
    }

    @Test
    fun `a user record defaults to kind user and no payload`() {
        val message = message()

        assertThat(message.kind).isEqualTo(MessageKind.USER)
        assertThat(message.payload).isNull()
        assertThat(message.kindLabel()).isEqualTo("user")
    }

    private fun widgetPayload(): ObjectNode = objectMapper.createObjectNode().put("type", "conference_invite")

    private fun message(
        id: UUID = UUID.randomUUID(),
        senderId: UUID? = ALICE,
        text: String = "текст",
        seq: Long = 1,
        payload: ObjectNode? = null,
    ): Message =
        Message(
            id = id,
            chatId = CHAT_ID,
            senderId = senderId,
            text = MessageText.normalize(text),
            seq = seq,
            createdAt = CREATED_AT,
            payload = payload,
        )

    private fun systemMessage(
        senderId: UUID? = null,
        payload: ObjectNode?,
    ): Message =
        Message(
            id = UUID.randomUUID(),
            chatId = CHAT_ID,
            senderId = senderId,
            text = MessageText.normalize("Видеозвонок"),
            seq = 1,
            createdAt = CREATED_AT,
            kind = MessageKind.SYSTEM,
            payload = payload,
        )

    private companion object {
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-00000000cc01")

        val ALICE = UUID.fromString("00000000-0000-0000-0000-00000000cc11")

        val CREATED_AT = Instant.parse("2026-09-20T12:00:00Z")
    }
}
