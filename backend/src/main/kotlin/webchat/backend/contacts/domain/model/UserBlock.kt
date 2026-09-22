package webchat.backend.contacts.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A one-sided block relation managed by the blocker (data-model 004 §5;
 * FR-020): one row per (blocker, blocked) — the composite PK of V11
 * `user_blocks`. The `blocker_id <> blocked_id` CHECK of V11 is the second
 * line of defense; the primary self-guard is the service layer
 * (`422 self_forbidden`, T052).
 *
 * Every observable block effect (FR-020) is derived from this single
 * relation via point lookups on the pair PK in both directions (T051):
 * send refusals with distinct codes (`403 chat_blocked_by_you` for the
 * blocker, `403 you_are_blocked` for the blocked side), read/watermark
 * suppressions (T054) and the `blockedByMe` projection (№12/№13) — the
 * only block projection in the API; a "who blocked me" field does NOT
 * exist (no activity leak to the blocked side). Contacts are fully
 * independent of blocks (Q, research.md §6).
 *
 * `PUT/DELETE /users/{userId}/block` (№23/№24) are idempotent — the
 * relation has no state beyond existence; unblocking resumes the ordinary
 * dialog behavior immediately.
 */
data class UserBlock(
    val blockerId: UUID,
    val blockedId: UUID,
    val createdAt: Instant,
) {
    init {
        require(blockerId != blockedId) { "a block requires two distinct users (FR-020)" }
    }

    /** `true` when [userId] is the blocking side — the only side that sees `blockedByMe`. */
    fun isBlocker(userId: UUID): Boolean = userId == blockerId

    /** `true` when [userId] is the blocked side (send → `403 you_are_blocked`). */
    fun isBlocked(userId: UUID): Boolean = userId == blockedId

    companion object {
        /**
         * `PUT /users/{userId}/block` (№23): the service (T052) rejects
         * `blockerId == blockedId` and an unknown target BEFORE the
         * repository call; the stored row is immutable afterwards.
         */
        fun by(
            blockerId: UUID,
            blockedId: UUID,
            at: Instant,
        ): UserBlock = UserBlock(blockerId = blockerId, blockedId = blockedId, createdAt = at)
    }
}
