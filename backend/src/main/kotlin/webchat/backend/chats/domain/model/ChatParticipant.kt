package webchat.backend.chats.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Per-user state of a dialog (data-model 004 §2): one row per
 * (participant, chat) — the read watermark `last_read_seq` (monotone,
 * GREATEST-update, US4-5) and the deletion watermark `deleted_up_to_seq`
 * plus `hidden` of the per-user chat deletion (FR-021). Mirrors the
 * `chat_participants` table of V10 (non-negative CHECKs).
 *
 * Derived visibility rules (data-model 004 §2):
 * - a message is visible to the participant ⟺ `seq > deletedUpToSeq`;
 * - it is unread ⟺ additionally `seq > lastReadSeq` and it is incoming;
 * - the chat is excluded from the list ⟺ `hidden` with no visible messages.
 */
data class ChatParticipant(
    val chatId: UUID,
    val userId: UUID,
    val lastReadSeq: Long = 0,
    val deletedUpToSeq: Long = 0,
    val hidden: Boolean = false,
    val createdAt: Instant,
) {
    init {
        require(lastReadSeq >= 0) { "last_read_seq must not be negative" }
        require(deletedUpToSeq >= 0) { "deleted_up_to_seq must not be negative" }
    }

    /** `DELETE /chats/{id}` (№14): hides the history behind the watermark, only for this user. */
    fun deleteUpTo(chatLastSeq: Long): ChatParticipant = copy(deletedUpToSeq = chatLastSeq, hidden = true)

    /**
     * `POST /chats/ensure` (№11) / a new incoming message (T012): returns
     * the chat to the list WITHOUT restoring the deleted history (the
     * watermark survives, FR-021).
     */
    fun unhide(): ChatParticipant = if (hidden) copy(hidden = false) else this

    /**
     * `POST /chats/{id}/read` (№17): the monotone GREATEST-update — returns
     * the advanced state, or `null` when nothing changes (rowcount 0 → no
     * event, US4-5 idempotence).
     */
    fun advanceReadUpTo(seq: Long): ChatParticipant? = if (seq > lastReadSeq) copy(lastReadSeq = seq) else null

    /** Message visibility for this participant (FR-021 watermark). */
    fun isVisible(seq: Long): Boolean = seq > deletedUpToSeq

    /** Unread badge contribution (FR-014): incoming, visible, above the read watermark. */
    fun isUnread(message: Message): Boolean {
        val fromPeer = message.senderId != userId
        return fromPeer && isVisible(message.seq) && message.seq > lastReadSeq
    }

    /** List exclusion invariant (data-model 004 §2): deleted with no new incoming. */
    fun excludedFromList(chatLastSeq: Long): Boolean = hidden && chatLastSeq <= deletedUpToSeq
}
