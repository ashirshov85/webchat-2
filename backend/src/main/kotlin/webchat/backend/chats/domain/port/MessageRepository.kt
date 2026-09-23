package webchat.backend.chats.domain.port

import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import java.util.UUID

/**
 * A send candidate (data-model 004 §3): everything the CLIENT supplies —
 * the FR-004 deduplication key [id] plus the addressing; the FR-003
 * normalized [text]. The server-side [Message.seq] (IDENTITY) and
 * [Message.createdAt] (`now()`) are assigned by the INSERT and are never
 * present here.
 */
data class NewMessage(
    val id: UUID,
    val chatId: UUID,
    val senderId: UUID,
    val text: MessageText,
)

/**
 * The outcome of the idempotent message write (research.md 004 §3 step 4):
 * the caller (T014/T031) maps [Inserted] to `201 Message` and [Duplicate]
 * to `200 Message` — a retry never produces a second record (FR-004,
 * constitution III).
 */
sealed interface MessageInsertResult {
    /** A new row was stored (a fresh `seq` allocated) → `201`. */
    data class Inserted(
        val message: Message,
    ) : MessageInsertResult

    /**
     * The client UUID already existed — either the dedup lookup matched, or
     * a parallel-retry race made `RETURNING` empty and the follow-up SELECT
     * resolved the same record. The service compares [existing] chat/sender
     * with the request: a mismatch is the `409 message_id_conflict` of №16
     * (a foreign id), a match is the idempotent `200`.
     */
    data class Duplicate(
        val existing: Message,
    ) : MessageInsertResult
}

/**
 * Persistence port for [Message] (data-model 004 §3; DIP: the JDBC adapter
 * lives outside the domain in
 * `webchat.backend.chats.repository.JdbcMessageRepository`, T012).
 *
 * The write path is the exactly-once transaction of research.md 004 §3:
 * dedup first (step 0, [findById] — BEFORE limits and blocks), then the
 * `INSERT … ON CONFLICT (id) DO NOTHING RETURNING *` with the
 * `chats.last_seq` advance and the `hidden` reset folded into the SAME
 * transaction.
 */
interface MessageRepository {
    /**
     * Dedup fast-path lookup by the client UUID PK (research.md 004 §3
     * step 0): a retry of an already-recorded message resolves here — no
     * flood penalty, no second row (US2-1/2).
     */
    fun findById(id: UUID): Message?

    /**
     * The exactly-once write, ONE PG transaction (data-model 004 §3 step 4;
     * T012):
     * `INSERT … ON CONFLICT (id) DO NOTHING RETURNING *`; when a row is
     * returned — `UPDATE chats SET last_seq = :seq` and the `hidden = false`
     * reset for BOTH participants happen in the same transaction (FR-021:
     * a new incoming returns the chat to the list without restoring the
     * deleted history); an empty `RETURNING` (a race of parallel retries)
     * falls back to a SELECT by id → [MessageInsertResult.Duplicate].
     */
    fun insert(message: NewMessage): MessageInsertResult

    /**
     * History page (№15, T015): chat messages VISIBLE to the viewer —
     * `seq > viewer.deleted_up_to_seq` (FR-021) — with the exclusive
     * `before` cursor (`seq < before`, FR-008) when [before] is given,
     * ordered `seq DESC`, at most [limit] rows (the service validates
     * `1..50` → `400 limit_out_of_range`). An empty result at the boundary
     * is a correct page (US3-4); `nextBefore` derivation is the caller's.
     */
    fun findVisiblePage(
        chatId: UUID,
        viewerId: UUID,
        before: Long?,
        limit: Int,
    ): List<Message>
}
