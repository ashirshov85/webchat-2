package webchat.backend.contacts.domain.port

import webchat.backend.contacts.domain.model.UserBlock
import java.util.UUID

/**
 * Persistence port for the [UserBlock] relation (data-model 004 §5; DIP:
 * the JDBC adapter lives outside the domain in
 * `webchat.backend.contacts.repository.JdbcBlockRepository`, T051).
 *
 * All block semantics are POINT lookups on the `(blocker_id, blocked_id)`
 * PK pair — in both directions where the caller needs them (data-model
 * 004 §5): the send path (T054) checks `exists(sender, recipient)` →
 * `403 chat_blocked_by_you` and `exists(recipient, sender)` →
 * `403 you_are_blocked` before the INSERT; the read path suppresses
 * publications; the chat projections read `exists(me, peer)` for
 * `blockedByMe`. Blocks are fully independent of contacts and chats
 * (FR-020): no method of this port touches `user_contacts` or the V10
 * tables.
 */
interface BlockRepository {
    /**
     * `PUT /users/{userId}/block` (№23): the idempotent block —
     * `INSERT … ON CONFLICT (blocker_id, blocked_id) DO NOTHING` + resolve;
     * returns the stored (created OR pre-existing) relation, so a repeat
     * is observationally a no-op (both map to `204`, edge spec). The
     * service (T052) rejects `blockerId == blockedId` (`422
     * self_forbidden`) and an unknown target (`404 user_not_found`)
     * BEFORE calling this method.
     */
    fun block(
        blockerId: UUID,
        blockedId: UUID,
    ): UserBlock

    /**
     * `DELETE /users/{userId}/block` (№24): the unconditional single-row
     * DELETE by the PK pair — idempotent, a missing entry is NOT an error
     * (the endpoint answers `204` either way). Ordinary dialog behavior
     * (send, reads, badge) resumes immediately for both sides.
     */
    fun unblock(
        blockerId: UUID,
        blockedId: UUID,
    )

    /**
     * Point existence lookup on the PK pair, ONE direction: `true` ⟺
     * [blockerId] currently blocks [blockedId]. Used for `blockedByMe`
     * (№12/№13 — the only API projection, T054/T055) and for the send/read
     * suppressions; the opposite direction is a second lookup with the
     * arguments swapped — never a "who blocked me" API field (FR-020, Q).
     */
    fun exists(
        blockerId: UUID,
        blockedId: UUID,
    ): Boolean
}
