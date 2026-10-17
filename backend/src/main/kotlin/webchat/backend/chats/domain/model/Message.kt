package webchat.backend.chats.domain.model

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * The record kind of the `messages` table (009, data-model §Сущность 4 /
 * V18 `ck_messages_kind`): [USER] — a participant's send (the only kind of
 * 004–008, `sender_id NOT NULL`, no payload); [SYSTEM] — a server-authored
 * widget row (nullable sender, the jsonb `payload` object dispatches the
 * widget; the first type is `conference_invite`, FR-002). Serializes as
 * the LOWER-CASE contract enum of the `Message.kind` schema field
 * (`"user"|"system"`, default `"user"` — the rows created before 009 are
 * semantically `user`), the same single-source convention as
 * [ChatKind]/`MemberRole` labels.
 */
enum class MessageKind {
    USER,
    SYSTEM,
}

/**
 * A single dialog message (data-model 004 §3): [id] is the CLIENT-generated
 * UUID — the deduplication key and public identifier (FR-004, `ON CONFLICT`
 * PK path of T012); [seq] is the server-side per-chat order cursor
 * (`GENERATED ALWAYS AS IDENTITY`, unique within the chat only — FR-006,
 * the exclusive `before=<seq>` pagination cursor of FR-008); [text] is the
 * FR-003-normalized [MessageText]; [createdAt] is the server record moment
 * («доставлено», FR-005).
 *
 * Since 009 (T016, data-model §Сущность 4) the same row family carries the
 * SYSTEM widgets: [senderId] is NULL for [MessageKind.SYSTEM] rows (the
 * server is the author — the FR-020 blocking pair, the flood bucket and
 * every sender-based gate never apply), [kind] discriminates the record and
 * [payload] holds the jsonb widget object (`payload.type` dispatches the
 * card; ≤ 4 KB is service-validated by the append port). The shape
 * invariant mirrors the V18 `ck_messages_kind_shape` CHECK: `kind='user'`
 * ⟺ `senderId != null` ⟺ `payload == null`.
 *
 * The read state is NOT stored on the message: it is derived from the
 * peer's `last_read_seq` watermark (data-model 004 §3, [isReadBy]).
 */
data class Message(
    val id: UUID,
    val chatId: UUID,
    val senderId: UUID?,
    val text: MessageText,
    val seq: Long,
    val createdAt: Instant,
    val kind: MessageKind = MessageKind.USER,
    val payload: ObjectNode? = null,
) {
    init {
        require(seq >= 1) { "message seq must be a positive per-chat cursor (IDENTITY starts at 1)" }
        when (kind) {
            MessageKind.USER ->
                require(senderId != null && payload == null) {
                    "a kind='user' record must carry a sender and no payload (V18 ck_messages_kind_shape)"
                }
            MessageKind.SYSTEM ->
                require(senderId == null && payload != null) {
                    "a kind='system' record must have no sender and a widget payload (V18 ck_messages_kind_shape)"
                }
        }
    }

    /** ✓✓ derivation (FR-010): read ⟺ `seq ≤ peer.last_read_seq` (monotone watermark). */
    fun isReadBy(peerLastReadSeq: Long): Boolean = seq <= peerLastReadSeq

    /** The contract `Message.kind` wire value (`"user"|"system"`, openapi.yaml 0.11.0). */
    fun kindLabel(): String = kind.name.lowercase()

    companion object {
        /**
         * T016 (data-model §Сущность 4): the size cap of the widget jsonb —
         * SERVICE validation on the append path (`payload` ≤ 4 KB); the
         * object SHAPE is the V18 `ck_messages_payload_object` CHECK.
         */
        const val MAX_PAYLOAD_BYTES: Int = 4096
    }
}
