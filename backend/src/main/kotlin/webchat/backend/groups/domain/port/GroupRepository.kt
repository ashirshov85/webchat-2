package webchat.backend.groups.domain.port

import webchat.backend.groups.domain.model.GroupChat
import webchat.backend.groups.domain.model.GroupDescription
import webchat.backend.groups.domain.model.GroupTitle
import java.util.UUID

/**
 * Persistence port for the group projection of `chats` (data-model 006
 * §Сущность 1; DIP: the JDBC adapter lives outside the domain in
 * `webchat.backend.groups.repository.JdbcGroupRepository`, T012).
 *
 * The scope is the `chats` row of `kind='group'` ONLY: the roster rows
 * (roles, watermarks, `state`) persist through the extended
 * [webchat.backend.chats.domain.port.ParticipantRepository] (T008/T011),
 * the audit trail — through [GroupAdminLogRepository], and group event
 * publishing rides the EXISTING realtime fan-out port
 * [webchat.backend.chats.domain.port.RealtimeEventPublisher] (extended by
 * T009) — a separate group realtime port is deliberately NOT introduced
 * (YAGNI, constitution VII; research.md 006 §4: the same per-user
 * `rt:user:{userId}` channels, no new broker topology).
 *
 * Group rows resolve NEVER for direct dialogs and unknown ids: every
 * `null`/`false` outcome feeds the single privacy `404 group_not_found`
 * of the membership gate (FR-008/FR-009 — a foreign group must not be
 * distinguishable from a missing one).
 */
interface GroupRepository {
    /**
     * `POST /api/v1/groups` (№27, T021) — ONE PG transaction (data-model
     * 006 §Сущность 2 «№27 создание»): `INSERT chats (kind='group',
     * title, description)` with the NULL dialog pair (V14 shape-CHECK),
     * the creator row `role='owner', state='active'` and every initial
     * [memberIds] row `role='member', state='active'` — BOTH watermarks
     * of every roster row initialized at `chats.last_seq` (a fresh group
     * starts at 0 — the badge-from-0 semantics of FR-013). The roster
     * check (each member is the creator's contact, FR-002) and the
     * `self_forbidden` refusal stay with the service: a violating batch
     * surfaces here as a key violation rolling the WHOLE transaction
     * back (an empty group row never survives a rejected create).
     */
    fun create(
        title: GroupTitle,
        description: GroupDescription?,
        ownerId: UUID,
        memberIds: List<UUID>,
    ): GroupChat

    /**
     * №28 `GET /api/v1/groups/{chatId}` resolution: the `kind='group'`
     * row, or `null` for an unknown id AND for a direct dialog — a
     * direct chatId is not a group resource and answers through the same
     * `404 group_not_found` gate (api-contract.md №28; FR-008).
     */
    fun find(id: UUID): GroupChat?

    /**
     * №31 `POST /api/v1/groups/{chatId}/members` serialization
     * (data-model 006 §Сущность 2 «№31 добавление»): `SELECT … FOR
     * UPDATE` of the `chats` row INSIDE the ambient transaction —
     * concurrent additions queue on the row lock, so the
     * `countActive + batch ≤ 200` check and the subsequent inserts form
     * one serialized section (`409 group_full` can never be outrun by a
     * racing batch). Same resolution rule as [find]: `null` for unknown
     * ids and direct dialogs.
     */
    fun findForUpdate(id: UUID): GroupChat?

    /**
     * №29 `PATCH /api/v1/groups/{chatId}` (T051): the atomic
     * last-confirmed patch — a single `UPDATE chats SET title =
     * :title, description = :description WHERE id = :id AND
     * kind='group'`, so two concurrent renames converge on exactly one
     * confirmed result (data-model 006 §Сущность 1: «последняя
     * подтверждённая операция»). The service has already validated the
     * patch through [GroupTitle]/[GroupDescription] (FR-001) and the
     * owner/admin role gate (FR-007). Returns the updated projection,
     * or `null` for unknown ids and direct dialogs (no partial writes).
     */
    fun updateMetadata(
        id: UUID,
        title: GroupTitle,
        description: GroupDescription?,
    ): GroupChat?

    /**
     * The 200-limit check of №27/№31 (FR-002): `COUNT(*)` of the
     * `state='active'` roster rows over the partial index
     * `ix_chat_participants_chat_active` (V14). Meaningful only next to
     * the [findForUpdate] lock — the count and the inserts of one
     * addition serialize on the same `chats` row.
     */
    fun countActive(id: UUID): Int

    /**
     * №30 `DELETE /api/v1/groups/{chatId}` (T064): the single hard
     * delete — `DELETE FROM chats WHERE id = :id AND kind='group'`;
     * `chat_participants` and `messages` CASCADE away on the existing
     * FKs (FR-006: the only durable removal). The service snapshots the
     * active roster BEFORE the delete (the `group.deleted` addressees)
     * and appends the journal fact AFTER — `group_admin_log` carries no
     * FK and survives (§Сущность 4). Returns `true` on rowcount 1;
     * `false` for unknown ids, direct dialogs AND a repeated №30 — the
     * `404` convergence of api-contract.md §2.
     */
    fun deleteHard(id: UUID): Boolean
}
