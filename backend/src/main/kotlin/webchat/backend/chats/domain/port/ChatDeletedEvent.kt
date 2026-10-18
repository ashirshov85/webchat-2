package webchat.backend.chats.domain.port

import java.util.UUID

/**
 * T062 (009, US5, FR-014) — the CHAT-deletion domain event, the chats
 * half of the «чат удалён» edge (data-model 009 §Сущность 1): published
 * by the deleting owner's №30 leg
 * ([webchat.backend.groups.domain.service.GroupService.delete]) INSIDE
 * the still-open deletion transaction, strictly BEFORE the
 * `DELETE FROM chats` statement executes — the only point where the
 * chat's conference rows and membership roster still exist — and
 * consumed SYNCHRONOUSLY by the conferences domain's
 * [webchat.backend.conferences.domain.service.ChatDeletedListener],
 * which finalizes every non-ended conference of the chat with
 * `end_reason='chat_deleted'` in the SAME transaction (the reason
 * pre-record plus the membership snapshot ride it), so the
 * `ON DELETE CASCADE` that follows erases already-finished rows and the
 * post-commit legs (SSE `conference.ended` to the snapshot roster,
 * `DeleteRoom` of the still-live room) announce exactly the call that
 * was running at the moment the chat died.
 *
 * The deliberate contrast with [GroupMemberRemovedEvent] (T020): that
 * one rides `@TransactionalEventListener(AFTER_COMMIT)` — a removed
 * MEMBER disconnects off the durable fact — while THIS one must run
 * BEFORE the commit AND before the CASCADE, so an ordinary synchronous
 * `@EventListener` is the only correct seam: a `BEFORE_COMMIT`
 * transactional listener would already see a transaction whose DELETE
 * statement (and with it the CASCADE) has executed, i.e. a chat with
 * no conferences and no roster left to finalize or address.
 */
data class ChatDeletedEvent(
    val chatId: UUID,
)
