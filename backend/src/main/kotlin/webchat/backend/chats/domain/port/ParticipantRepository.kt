package webchat.backend.chats.domain.port

import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.groups.domain.model.MemberRole
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
 *
 * Since V14 (006) the same rows carry the GROUP membership projection
 * `role`/`state` (data-model 006 §Сущность 2): membership in a group ⟺
 * an ACTIVE row (FR-008) checked on every request, while the 004/005
 * watermarks survive a removal for the re-add semantics (FR-002) — the
 * group-roster legs below (T008, adapter T011) evolve this port instead
 * of introducing a separate membership table (constitution VII).
 */
@Suppress("TooManyFunctions") // one member per port rule: 004/005 dialog legs + the 006 roster extension (T008)
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
     * data-model сущность 2, T013): [userId]'s dialogs WITH a visible
     * undelivered tail BEYOND THE EFFECTIVE CURSOR — the caller's
     * [clientCursors] fold into the candidacy bound as `эффективный
     * курсор = max(клиентский, серверный)`: `chats.last_seq >
     * GREATEST(delivered_up_to_seq, deleted_up_to_seq, клиентский
     * курсор)`, so a chat already caught up by its client cursor leaves
     * the page ENTIRELY (a foreign/unknown chatId of the map never
     * matches the caller's rows and rides along silently ignored —
     * per-user operation). A cursor BEYOND the chat head (the «курсор из
     * будущего» of sync-protocol.md §5) contributes NOTHING to the bound
     * — the candidacy falls back to the server position instead of
     * excluding the chat, and the service flags the desync repair.
     * Latest activity first: `chats.last_seq DESC`, tie-break
     * `created_at DESC, chat_id`; up to [chatLimit] entries (already
     * validated 1–50 by the caller) with [UndeliveredChatPage.moreChats]
     * by the remainder — because the cursors fold INTO the query,
     * pagination and `moreChats` stay cursor-aware in the same read
     * snapshot («частичный список как полный» исключён, and a page of
     * cursor-caught-up chats can never fake a complete answer). A pure
     * READ: never moves the delivery position (FR-001).
     */
    fun loadForSync(
        userId: UUID,
        clientCursors: Map<UUID, Long>,
        chatLimit: Int,
    ): UndeliveredChatPage

    /**
     * The server-authoritative unread counter — data-model 005 сущность 3
     * (FR-007, T013 — the ONE reusable calculator): `COUNT(messages WHERE
     * chat_id = chat AND sender_id != user AND seq >
     * GREATEST(last_read_seq, deleted_up_to_seq) AND seq <=
     * LEAST(chats.last_seq, delivered_up_to_seq))`. Delivery-bounded:
     * the badge counts only the CONFIRMED delivered tail (a №26 delta
     * page is not yet unread until the client acks it — the sync answer
     * never moves the position it is bounded by, FR-001) and
     * truncation-bounded: below the deletion watermark messages are
     * inaccessible, not unread (FR-003/US1-5). Derived per read — never
     * stored; a user without a participant row of the chat reads 0.
     * T031: the №12 chat list mirrors the SAME bounds inside its ONE
     * aggregate query (an N+1 of per-chat calls is the rejected
     * alternative, research.md 004 §8) — one formula, no drift.
     */
    fun countUnread(
        userId: UUID,
        chatId: UUID,
    ): Long

    /**
     * The FR-008 membership projection of 006: [userId]'s row of the
     * chat ONLY WHILE it is ACTIVE — `WHERE chat_id = ? AND user_id = ?
     * AND state='active'`. `null` covers every non-member ALIKE: a
     * stranger who never was a member, a REMOVED former member (№32
     * kick/№33 leave) and a foreign or unknown chatId — the single read
     * of the groups membership gate (T015 `GroupMembershipGate`),
     * rendering all three identically as the privacy `404
     * group_not_found` (FR-008/FR-009: existence is never disclosed).
     */
    fun findActive(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant?

    /**
     * The ACTIVE roster snapshot of a group (006, FR-011): one
     * index-only scan over the V14 partial index
     * `ix_chat_participants_chat_active` — ≤ 200 rows by the FR-002
     * limit, each with its [ChatParticipant.role] for the fan-out
     * addressees, the №28 `GroupView` roster and the FR-003/FR-004
     * role checks. Deterministic `created_at, user_id` order (the
     * `joined_at, user_id` member order of the №28 projection,
     * data-model 006 §Выводные представления). The message path calls
     * it INSIDE the send transaction — the addressee snapshot of the
     * post-commit fan-out is exactly the roster that saw the message
     * admitted.
     */
    fun activeMembers(chatId: UUID): List<ChatParticipant>

    /**
     * №27/№31 roster INSERT — the FIRST add of [userId] to a group
     * (data-model 006 §Сущность 2): BOTH watermarks initialize at the
     * chat head read in the SAME statement — `last_read_seq =
     * delivered_up_to_seq = chats.last_seq` — so the unread badge
     * starts at 0 (FR-013) and the history before the add is never
     * re-delivered (the 005 FR-001 discipline holds: only №25 acks move
     * the delivery position afterwards). [role] carries the joining
     * role — `owner` ONLY for the №27 creator (the
     * `ux_chat_participants_owner` partial unique index backs the
     * single-owner invariant, FR-003), everyone else joins as `member`.
     * The chat row must already exist (the service has resolved the
     * group and, for №31, holds the `chats` row lock of
     * `GroupRepository.findForUpdate` in the ambient transaction); a
     * pre-existing row of the user surfaces as a PK violation — the
     * addMembers scenario skips an ACTIVE row (idempotence, the №31
     * `200` without a duplicate) and routes a REMOVED one to
     * [reactivate] (api-contract.md 006 §2).
     */
    fun addMember(
        chatId: UUID,
        userId: UUID,
        role: MemberRole,
    ): ChatParticipant

    /**
     * №31 re-adding a REMOVED member (data-model 006 §Сущность 2): the
     * single conditional `UPDATE … SET state='active', role='member',
     * hidden=false WHERE … AND state='removed'` — BOTH 004/005
     * watermarks SURVIVE the absence verbatim (FR-002: no re-anchoring,
     * the returning member's badge counts the away-period tail), the
     * role always resets to plain `member` and a stale №14 `hidden`
     * flag clears. `null` on rowcount 0 — no row at all or the
     * membership is already ACTIVE (the caller has just skipped it as
     * the idempotent case); no side effects either way.
     */
    fun reactivate(
        chatId: UUID,
        userId: UUID,
    ): ChatParticipant?

    /**
     * №32 kick / №33 leave (data-model 006 §Сущность 2): the single
     * conditional `UPDATE … SET state='removed', role='member' WHERE
     * chat_id = ? AND user_id = ? AND state='active'`, resolved by
     * rowcount — `true` on exactly 1; `false` when the removal has
     * already happened (the kick×leave race converges without
     * duplicates, edge of api-contract.md 006 §2) or the user was never
     * a member. The watermarks stay in the row for the re-add semantics
     * (FR-002/FR-005) and the removed member's messages keep their
     * attribution — `messages` rows are never touched here.
     */
    fun removeMember(
        chatId: UUID,
        userId: UUID,
    ): Boolean

    /**
     * №34 role change / №35 ownership transfer (data-model 006
     * §Сущность 2): the single conditional `UPDATE … SET role = :role
     * WHERE chat_id = ? AND user_id = ? AND state='active' RETURNING …`
     * — the ONLY writer of `role` on an ACTIVE row. `null` on rowcount
     * 0 (never a member, or a concurrent №32/№33 removed him between
     * the caller's ACTIVE read and this statement — the rowcount-race
     * convergence of the kick×setRole edge); no side effects either
     * way. The №35 discipline rides this leg: demote THEN promote
     * inside ONE service transaction, so the partial unique index
     * `ux_chat_participants_owner` never observes two owners — nor
     * zero — at any statement boundary (FR-003); №34 never reaches here
     * with `owner` (the DTO refuses the label, api-contract.md 006 №34).
     */
    fun updateRole(
        chatId: UUID,
        userId: UUID,
        role: MemberRole,
    ): ChatParticipant?

    /**
     * The ✓✓ rule of a group (FR-012): `MIN(last_read_seq)` over the
     * ACTIVE members EXCEPT [userId] — an index-only fold over the same
     * V14 partial index (`INCLUDE (user_id, role, last_read_seq)`); a
     * REMOVED reader drops out of the condition naturally and a group
     * of one folds to 0 (✓✓ unreachable, data-model 006 §Правила
     * видимости). Monotone per reader by the GREATEST-discipline of
     * [advanceReadUpTo] — the MIN over monotone legs never regresses,
     * and a re-added member's PRESERVED watermark (FR-002) keeps it so.
     */
    fun minOtherReadUpToSeq(
        chatId: UUID,
        userId: UUID,
    ): Long
}
