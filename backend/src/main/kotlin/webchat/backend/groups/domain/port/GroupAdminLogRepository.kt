package webchat.backend.groups.domain.port

import java.time.Instant
import java.util.UUID

/**
 * One administrative fact about a group (data-model 006 §Сущность 4,
 * FR-017): exactly the ten actions admitted by the V14 CHECK
 * `ck_group_admin_log_action` — the wire/storage values are the
 * lower-case snake-case contract forms (`group_created`, …) mapped by
 * the JDBC adapter (T013). Message CONTENT is never journaled — only
 * the facts (clarify 2026-09-25, constitution V).
 */
enum class GroupAdminAction {
    /** №27: the group came into being (owner + initial roster). */
    GROUP_CREATED,

    /** №29: the title patch landed. */
    TITLE_CHANGED,

    /** №29: the description patch landed. */
    DESCRIPTION_CHANGED,

    /** №27/№31: a member joined the roster (first add or re-add). */
    MEMBER_ADDED,

    /** №32: an admin/owner removed a member. */
    MEMBER_REMOVED,

    /** №33: a member left on their own. */
    MEMBER_LEFT,

    /** №34: the owner elevated a member to admin. */
    ADMIN_GRANTED,

    /** №34: the owner revoked the admin role. */
    ADMIN_REVOKED,

    /** №35: ownership moved (old owner demoted to admin). */
    OWNERSHIP_TRANSFERRED,

    /** №30: the owner hard-deleted the group (the fact, no content). */
    GROUP_DELETED,
}

/**
 * One append-only journal record of [GroupAdminAction] (data-model 006
 * §Сущность 4): WHO ([actorId]) did WHAT ([action]) to WHOM
 * ([targetUserId] — the roles/members target; `null` for metadata and
 * delete facts). [createdAt] defaults to the EPOCH sentinel — the
 * server stamps `now()` on append, and only entries read BACK through
 * [GroupAdminLogRepository.chronology] carry their persisted timestamp.
 */
data class GroupAdminLogEntry(
    val groupId: UUID,
    val actorId: UUID,
    val targetUserId: UUID?,
    val action: GroupAdminAction,
    val createdAt: Instant = Instant.EPOCH,
)

/**
 * Persistence port for the `group_admin_log` audit trail (data-model
 * 006 §Сущность 4, FR-017; DIP: the JDBC adapter lives outside the
 * domain in `webchat.backend.groups.repository.JdbcGroupAdminLogRepository`,
 * T013).
 *
 * Append-only: there is no update and no delete — the journal records
 * history instead of carrying state, and the table deliberately has NO
 * FK on `chats`, so entries SURVIVE the №30 hard delete of their group
 * (FR-006: the deletion fact itself is journaled against the already
 * gone group id). No public read API exists (research.md 006 §7 —
 * observability, not a user-facing resource); [chronology] serves the
 * audit read model and the story ITs (T060).
 */
interface GroupAdminLogRepository {
    /**
     * Records one administrative fact in the SAME transaction as the
     * operation that caused it (T021+ service discipline): a lost
     * journal row must be impossible once the operation is durable.
     * The server stamps `created_at` — the [GroupAdminLogEntry.createdAt]
     * sentinel never persists.
     */
    fun append(entry: GroupAdminLogEntry)

    /**
     * The audit read: entries of ONE group ordered by the chronology
     * index `(group_id, created_at)` (V14 `ix_group_admin_log_group`),
     * oldest first — the timeline of T060. Scopes to [groupId] exactly:
     * entries of other groups never leak into the answer.
     */
    fun chronology(groupId: UUID): List<GroupAdminLogEntry>
}
