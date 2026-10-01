package webchat.backend.presence.domain.port

import java.util.UUID

/**
 * The visibility-policy port (data-model 007 §1.4; DIP: the SQL adapter
 * lives outside the domain in
 * `webchat.backend.presence.repository.JdbcVisibilityAudienceReader`,
 * T009 — read-only over the CURRENT state of PG via the existing V10/
 * V11/V14 indexes, research §C1). The audience is COMPUTED, never
 * stored (FR-001 forbids presence side-tables):
 *
 * ```text
 * audience(X) = { active co-participants of X's chats (direct + group,
 *                 chat_participants.state='active')            -- V10/V14
 *               ∪ { owners having X in their contacts }         -- user_contacts V11
 *             } − { block-pairs of X, both directions }         -- user_blocks V11
 *               − { X }
 * ```
 *
 * Both presence legs — the `presence.updated` fan-out (T017) and the
 * №36 batch snapshot (T016) — resolve through THIS one policy, so an
 * event and a snapshot can never disagree about who is allowed to see
 * whom (FR-003/FR-007, SC-004). The reads are LIVE (no cache in v1 —
 * research §C1 rejected it): a contact added or a group kick converges
 * without a client restart (edges; the removal window ≤ 5 s as in 006
 * FR-010). The policy does NOT know about «невидимка»: membership in
 * the audience is unaffected by `users.presence_hidden` — the event
 * freeze and the indistinguishable `offline` answer are the
 * `PresenceService` business (FR-007, T033); a blocked pair is simply
 * OUT of the audience, so blocking already hides mutually (004
 * FR-020, research §D1).
 */
interface VisibilityAudienceReader {
    /**
     * The fan-out addressee set of a status transition of [subjectId]
     * (T017): every user allowed to observe the subject — an observer
     * per shared ACTIVE chat (direct or group) ∪ every owner having
     * the subject in their contacts, minus block-pairs in BOTH
     * directions, minus the subject themself. Returns a SET: exactly
     * ONE `rt:user:{observerId}` publication per observer regardless
     * of how many chats they share with the subject (edge «до 200
     * участников», T030 — a direct- AND group-mate still receives one
     * frame). Called only on an actual published transition
     * (~14/s mean, research §C1 load): median audience ~50, tail
     * ~2000; an unknown [subjectId] simply yields an empty set.
     */
    fun audienceOf(subjectId: UUID): Set<UUID>

    /**
     * The №36 per-pair policy batch (T016): returns the SUBSET of
     * [targetIds] whose published status [observerId] may read — the
     * same membership formula resolved pairwise
     * (`observerId ∈ audience(target)`); the complement gets the
     * single `unknown` answer. «No access» is INDISTINGUISHABLE: an
     * outsider, a block-pair, a nonexistent userId and the observer
     * themself all read `unknown` — the API never reveals existence,
     * offline-ness or «невидимка» (FR-007, US4 AC4). [targetIds]
     * arrives deduped and capped at 200 (№36 validation is the API
     * layer's business, contracts/presence-api.md §1); set semantics
     * make a duplicate or a self-entry harmless.
     */
    fun visibleTargets(
        observerId: UUID,
        targetIds: Collection<UUID>,
    ): Set<UUID>
}
