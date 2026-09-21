package webchat.backend.chats.domain.port

import webchat.backend.chats.domain.model.Chat
import java.util.UUID

/**
 * The outcome of the idempotent pair resolve (api-contract.md №11): the
 * caller (T013) maps [Created] to `201 ChatView` and [Existing] to
 * `200 ChatView` — both carry the resolved dialog.
 */
sealed interface ChatEnsureResult {
    val chat: Chat

    /**
     * The pair had no dialog: the chat row and the two lazy
     * `chat_participants` rows were inserted in one transaction.
     */
    data class Created(
        override val chat: Chat,
    ) : ChatEnsureResult

    /**
     * FR-018: the UNIQUE canonical pair already existed — the same dialog is
     * returned; the CALLER's `hidden` flag has been cleared (the deletion
     * watermark survives — FR-021), the peer row untouched.
     */
    data class Existing(
        override val chat: Chat,
    ) : ChatEnsureResult
}

/**
 * Persistence port for the [Chat] aggregate (data-model 004 §1; DIP: the
 * JDBC adapter lives outside the domain in
 * `webchat.backend.chats.repository.JdbcChatRepository`, T011).
 *
 * The aggregate is immutable after creation; the only write is the
 * idempotent ensure — exactly one dialog per canonical pair (FR-001).
 * `chats.last_seq` is NOT advanced through this port: it is maintained
 * inside the message INSERT transaction of [MessageRepository] (data-model
 * 004 §3 step 4, T012).
 */
interface ChatRepository {
    /**
     * Membership resolution for every chats resource (FR-002): `null` →
     * `404 chat_not_found`; the caller then checks [Chat.involves] for the
     * `403 not_participant` refusal.
     */
    fun findById(chatId: UUID): Chat?

    /**
     * `POST /chats/ensure` (№11), one PG transaction (data-model 004 §1):
     * `INSERT … ON CONFLICT (user_low_id, user_high_id) DO NOTHING` with the
     * canonical least/greatest pair of [callerId]/[peerId], lazy creation of
     * the two `chat_participants` (`ON CONFLICT DO NOTHING`), then `SELECT`
     * of the resolved row; on the existing chat also
     * `UPDATE chat_participants SET hidden = false` FOR THE CALLER only —
     * the pair dialog is never duplicated (FR-001/FR-018). The service
     * (T013) rejects `callerId == peerId` (`422 self_forbidden`) and an
     * unknown peer (`404 peer_not_found`) BEFORE calling this method.
     */
    fun ensure(
        callerId: UUID,
        peerId: UUID,
    ): ChatEnsureResult
}
