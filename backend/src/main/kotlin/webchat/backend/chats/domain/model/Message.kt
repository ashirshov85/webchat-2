package webchat.backend.chats.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A single dialog message (data-model 004 §3): [id] is the CLIENT-generated
 * UUID — the deduplication key and public identifier (FR-004, `ON CONFLICT`
 * PK path of T012); [seq] is the server-side per-chat order cursor
 * (`GENERATED ALWAYS AS IDENTITY`, unique within the chat only — FR-006,
 * the exclusive `before=<seq>` pagination cursor of FR-008). [text] is the
 * FR-003-normalized [MessageText]; [createdAt] is the server record moment
 * («доставлено», FR-005).
 *
 * The read state is NOT stored on the message: it is derived from the
 * peer's `last_read_seq` watermark (data-model 004 §3, [isReadBy]).
 */
data class Message(
    val id: UUID,
    val chatId: UUID,
    val senderId: UUID,
    val text: MessageText,
    val seq: Long,
    val createdAt: Instant,
) {
    init {
        require(seq >= 1) { "message seq must be a positive per-chat cursor (IDENTITY starts at 1)" }
    }

    /** ✓✓ derivation (FR-010): read ⟺ `seq ≤ peer.last_read_seq` (monotone watermark). */
    fun isReadBy(peerLastReadSeq: Long): Boolean = seq <= peerLastReadSeq
}
