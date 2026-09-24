package webchat.backend.chats.domain.port

import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.UndeliveredChatPage
import java.util.UUID

/**
 * Persistence port for the per-user dialog state [ChatParticipant]
 * (data-model 004 §2; DIP: the JDBC adapter lives outside the domain in
 * `webchat.backend.chats.repository.JdbcParticipantRepository`, T011).
 *
 * Every transition is a conditional single-row UPDATE against the
 * `(chat_id, user_id)` PK: a zero rowcount means the transition does not
 * apply and no side effects occur — the same conditional-UPDATE discipline
 * as the auth feature ports.
 */
interface ParticipantRepository {
    /**
     * Per-user state read: the read watermark, the deletion watermark and
     * the `hidden` flag. `null` → no participant row (not a member); used
     * for history visibility (FR-021) and the read fields of `ChatView`
     * (T043).
     */
    fun find(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant?

    /**
     * T043: BOTH participant rows of the dialog in one read — the service
     * projects [ChatParticipant.lastReadSeq] per side into the read fields
     * of `ChatView` (`myReadUpToSeq`/`peerReadUpToSeq`, openapi 0.4.0
     * №11/№13). Ensure creates both rows lazily in its transaction, so
     * both exist for a resolved dialog; a missing row reads as the
     * watermark 0 («0 — ничего не прочитано»).
     */
    fun findForChat(chatId: UUID): List<ChatParticipant>

    /**
     * `POST /chats/{chatId}/read` (№17, T042) — the monotone
     * GREATEST-update (data-model 004 §2):
     * `UPDATE … SET last_read_seq = GREATEST(last_read_seq, :upToSeq)
     *  WHERE chat_id = :chatId AND user_id = :userId AND last_read_seq < :upToSeq`.
     * Returns the advanced state, or `null` when rowcount is 0 (a repeated
     * or smaller `upToSeq`, or no row) — nothing changes and NO event is
     * published (US4-5 idempotence/monotonicity).
     */
    fun advanceReadUpTo(
        chatId: UUID,
        userId: UUID,
        upToSeq: Long,
    ): ChatParticipant?

    /**
     * `DELETE /chats/{chatId}` (№14, T056) — the per-user deletion, atomic
     * and O(1) (data-model 004 §2):
     * `UPDATE … SET deleted_up_to_seq = :chatLastSeq, hidden = true
     *  WHERE chat_id = :chatId AND user_id = :userId`.
     * The peer's row is NEVER touched (FR-021); the call is idempotent —
     * a repeated DELETE maps to the same `204`. Returns the updated state,
     * or `null` when no participant row exists.
     */
    fun deleteUpTo(
        chatId: UUID,
        userId: UUID,
        chatLastSeq: Long,
    ): ChatParticipant?

    /**
     * Ack №25 `POST /users/me/delivery-ack` (005, api-contract.md §2,
     * data-model сущность 1, T012): the batch monotone GREATEST-advance
     * of the CALLER's delivery position, one transaction over the whole
     * batch — per entry, by the `(chat_id, user_id)` PK:
     * `UPDATE … SET delivered_up_to_seq = GREATEST(delivered_up_to_seq, :upToSeq)
     *  WHERE chat_id = :chatId AND user_id = :userId AND delivered_up_to_seq < :upToSeq`;
     * rowcount 0 — no effect (a repeated or smaller value, idempotent —
     * see [ChatParticipant.advanceDeliveredUpTo]). The caller has already
     * validated the WHOLE batch (membership: чужой → `403`, несуществующий
     * → `404`; `upToSeq ≤ chats.last_seq` → `400 invalid_up_to_seq`), so
     * the port performs no partial effects; an empty batch performs no
     * statements (the contract's `minItems 1` is the service's check).
     * The delivery position moves ONLY through this operation (FR-001) —
     * a №26 sync answer and an SSE frame never write it.
     */
    fun advanceDelivered(
        userId: UUID,
        acks: Map<UUID, Long>,
    )

    /**
     * №26 `POST /users/me/sync` candidate read (005, sync-protocol.md §3,
     * data-model сущность 2, T013): [userId]'s chats WITH a visible
     * undelivered tail — `chats.last_seq > GREATEST(delivered_up_to_seq,
     * deleted_up_to_seq)` (see [ChatParticipant.hasUndeliveredVisible]) —
     * latest activity first: `chats.last_seq DESC`, tie-break
     * `created_at DESC, chat_id`; up to [chatLimit] entries (already
     * validated 1–50 by the caller) with [UndeliveredChatPage.moreChats]
     * by the remainder, computed in the same read snapshot
     * («частичный список как полный» исключён). The client cursors are
     * folded in by the service (эффективный курсор =
     * `max(клиентский, серверный)`); the read never moves the delivery
     * position.
     */
    fun loadForSync(
        userId: UUID,
        chatLimit: Int,
    ): UndeliveredChatPage
}
