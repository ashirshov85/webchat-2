package webchat.backend.chats.domain.port

import webchat.backend.chats.domain.model.ChatListEntry
import java.util.UUID

/**
 * Persistence port for the №12 panel read (T055, api-contract.md №12,
 * research.md 004 §8; DIP: the JDBC adapter lives outside the domain in
 * `webchat.backend.chats.repository.JdbcChatRepository`).
 *
 * The list is ONE aggregate query by contract design: JOIN of the
 * caller's `chat_participants` state + the dialog + the peer `users` row +
 * the caller's `user_blocks` mark, with the per-chat aggregates
 * (`lastMessage` by the max VISIBLE `seq`, `unreadCount`) computed
 * in-query — N+1 panel reads are the rejected alternative (research.md
 * 004 §8).
 */
fun interface ChatListRepository {
    /**
     * All dialogs of [callerId] as [ChatListEntry] rows, sorted by the
     * `createdAt` of the last VISIBLE message DESC (incoming or outgoing —
     * either lifts the dialog, FR-014), chats without visible messages
     * last (NULLS LAST, FR-014/FR-018), tie-break `chat_id` — `seq` is
     * per-chat and is NEVER an inter-chat key (plan.md). Excluded is ONLY
     * the fully deleted dialog `hidden AND deleted_up_to_seq >=
     * chats.last_seq` (data-model 004 §2; FR-021 — a new incoming resets
     * `hidden` on the write path of T012 and the dialog returns).
     */
    fun listForUser(callerId: UUID): List<ChatListEntry>
}
