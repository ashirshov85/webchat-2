package webchat.backend.chats.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Per-user state of a dialog (data-model 004 §2, extended by 005): one
 * row per (participant, chat) — the read watermark `last_read_seq`
 * (monotone, GREATEST-update, US4-5), the deletion watermark
 * `deleted_up_to_seq` plus `hidden` of the per-user chat deletion
 * (FR-021) and, since V12 (005), the delivery position
 * `delivered_up_to_seq` (FR-001): the server-side sync cursor that is
 * moved ONLY by the ack operation №25 — neither a №26 sync answer nor
 * an SSE frame writes it. Mirrors the `chat_participants` table
 * (non-negative CHECKs).
 *
 * Derived visibility rules (data-model 004 §2 + 005 сущности 1–3):
 * - a message is visible to the participant ⟺ `seq > deletedUpToSeq`;
 * - it is unread ⟺ additionally `seq > lastReadSeq` and it is incoming;
 * - it is undelivered ⟺ additionally `seq > deliveredUpToSeq` — such a
 *   visible tail is exactly what the №26 catch-up sync delivers;
 * - the chat is excluded from the list ⟺ `hidden` with no visible messages.
 */
data class ChatParticipant(
    val chatId: UUID,
    val userId: UUID,
    val lastReadSeq: Long = 0,
    val deletedUpToSeq: Long = 0,
    val deliveredUpToSeq: Long = 0,
    val hidden: Boolean = false,
    val createdAt: Instant,
) {
    init {
        require(lastReadSeq >= 0) { "last_read_seq must not be negative" }
        require(deletedUpToSeq >= 0) { "deleted_up_to_seq must not be negative" }
        require(deliveredUpToSeq >= 0) { "delivered_up_to_seq must not be negative" }
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

    /**
     * Ack №25 `POST /users/me/delivery-ack` (005, data-model сущность 1):
     * the monotone GREATEST-update of the delivery position — returns the
     * advanced state, or `null` when nothing changes (a repeated or
     * smaller `upToSeq` → rowcount 0 → no effect, idempotent). The ONLY
     * transition that moves [deliveredUpToSeq] (FR-001): a №26 sync
     * answer and an SSE frame never advance it — only the client's ack
     * of an APPLIED page/frame does.
     */
    @Suppress("MaxLineLength") // ktlint function-signature requires the single-line body; 126 chars > detekt's 120
    fun advanceDeliveredUpTo(seq: Long): ChatParticipant? = if (seq > deliveredUpToSeq) copy(deliveredUpToSeq = seq) else null

    /** Message visibility for this participant (FR-021 watermark). */
    fun isVisible(seq: Long): Boolean = seq > deletedUpToSeq

    /** Unread badge contribution (FR-014): incoming, visible, above the read watermark. */
    fun isUnread(message: Message): Boolean {
        val fromPeer = message.senderId != userId
        return fromPeer && isVisible(message.seq) && message.seq > lastReadSeq
    }

    /** List exclusion invariant (data-model 004 §2): deleted with no new incoming. */
    fun excludedFromList(chatLastSeq: Long): Boolean = hidden && chatLastSeq <= deletedUpToSeq

    /**
     * №26 selection predicate (005, data-model сущность 2): the chat has a
     * visible undelivered tail for this participant — some message with
     * `deletedUpToSeq < seq ≤ chatLastSeq` AND `seq > deliveredUpToSeq`,
     * i.e. `chatLastSeq > GREATEST(deliveredUpToSeq, deletedUpToSeq)`.
     * Such chats are the catch-up candidates of `loadForSync`.
     */
    fun hasUndeliveredVisible(chatLastSeq: Long): Boolean = chatLastSeq > maxOf(deliveredUpToSeq, deletedUpToSeq)
}

/**
 * One №26 catch-up candidate (005, T013): the projection of the caller's
 * `chat_participants` row JOIN `chats` — the server delivery position
 * [deliveredUpToSeq] (the client cursor is folded in by the service as
 * the effective cursor `max(client, server)`), the per-user deletion
 * watermark (the lower bound / truncation point of catch-up) and
 * `chats.last_seq` (the `lastSeq` answer field plus `hasMore`/desync
 * detection). A READ model: derived in the single `loadForSync` query;
 * nothing here mutates dialog state.
 */
data class UndeliveredChat(
    val chatId: UUID,
    val deliveredUpToSeq: Long,
    val deletedUpToSeq: Long,
    val chatLastSeq: Long,
)

/**
 * The `loadForSync` answer (№26): up to `chatLimit` chats with a visible
 * undelivered tail — latest activity first (`chats.last_seq DESC`,
 * tie-break `created_at DESC, chat_id`) — and [moreChats]: undelivered
 * chats remain beyond the limit, computed in the same read snapshot
 * («частичный список как полный» исключён); the caller repeats №26 with
 * refreshed cursors.
 */
data class UndeliveredChatPage(
    val chats: List<UndeliveredChat>,
    val moreChats: Boolean,
)
