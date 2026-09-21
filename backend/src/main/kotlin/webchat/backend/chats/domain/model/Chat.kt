package webchat.backend.chats.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Personal dialog of exactly two users (data-model 004 §1; FR-001): the
 * pair is stored in canonical order (`user_low_id = least(a, b)`,
 * `user_high_id = greatest(a, b)`, `low < high` — the UNIQUE pair of V10),
 * so exactly one dialog exists per pair and ensure is idempotent
 * (FR-018). The dialog is immutable after creation; per-user visibility
 * lives in [ChatParticipant], messages in [Message].
 *
 * [lastSeq] caches `MAX(messages.seq)` of the chat, maintained in the
 * message INSERT transaction (T012): list sorting (FR-014), the deletion
 * watermark (FR-021) and visibility reads avoid a full MAX scan.
 */
data class Chat(
    val id: UUID,
    val userLowId: UUID,
    val userHighId: UUID,
    val createdAt: Instant,
    val lastSeq: Long = 0,
) {
    init {
        require(userLowId < userHighId) { "chat pair must be in canonical order user_low_id < user_high_id" }
    }

    /** FR-002: membership check used by every chats resource. */
    fun involves(userId: UUID): Boolean = userId == userLowId || userId == userHighId

    /** The other side of the dialog for a participant, `null` for strangers. */
    fun peerOf(userId: UUID): UUID? =
        when (userId) {
            userLowId -> userHighId
            userHighId -> userLowId
            else -> null
        }

    /**
     * Monotone `last_seq` cache advance inside the message INSERT
     * transaction (data-model 004 §3 step 4): never moves backwards.
     */
    fun advanceLastSeq(seq: Long): Chat {
        require(seq >= lastSeq) { "chat last_seq must not move backwards" }
        return copy(lastSeq = seq)
    }

    companion object {
        /**
         * Opens (or re-resolves) the single dialog of a pair: canonical
         * least/greatest order regardless of the argument order; a dialog
         * with oneself is refused here in addition to the `422 self_forbidden`
         * of the service layer (FR-001/edge).
         */
        fun forPair(
            id: UUID,
            a: UUID,
            b: UUID,
            at: Instant,
        ): Chat {
            require(a != b) { "a dialog requires two distinct users (FR-001)" }
            val (low, high) = if (a < b) a to b else b to a
            return Chat(id = id, userLowId = low, userHighId = high, createdAt = at)
        }
    }
}
