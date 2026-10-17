package webchat.backend.conferences.domain.port

import webchat.backend.conferences.domain.model.GuestInvite
import java.time.Instant
import java.util.UUID

/**
 * The №52 issue outcome (T045, Q17=A): [invite] is the stored ACTIVE
 * row behind the freshly minted nonce link; [replacedPrevious] is the
 * row that held the conference's single ACTIVE slot until this call —
 * `null` when there was none. The caller needs the previous row's id
 * for the post-commit disconnect of its connected guests
 * (`ListParticipants` → `metadata.inviteId` → `RemoveParticipant`,
 * Q13=B, livekit-integration.md §6.1).
 */
data class GuestInviteIssueResult(
    val invite: GuestInvite,
    val revokedPrevious: GuestInvite?,
)

/**
 * Persistence port for the [GuestInvite] status row (data-model 009
 * §Сущность 3; DIP: the JDBC adapter lives outside the domain in
 * `webchat.backend.conferences.repository.JdbcGuestInviteRepository`,
 * T011 — JdbcTemplate, the domain convention without JPA).
 *
 * The cryptographic validity of the link lives in the ES256 nonce
 * (T044 — signature/`exp`, stateless); this row adds only REVOCATION
 * and the «exactly one ACTIVE per conference» invariant held by the
 * partial unique index `ux_guest_invites_active` (research §6). Rows
 * are deleted by the finalize transaction of
 * [ConferenceRepository.finalize] (Q18=A) — this port has no delete
 * leg of its own.
 */
interface GuestInviteRepository {
    /**
     * №52 `POST …/guest-invite` (T045, Q17=A): ONE transaction that
     * keeps `ux_guest_invites_active` unbroken — the conditional
     * `UPDATE … SET status='revoked', revoked_at=:at WHERE
     * conference_id = :conferenceId AND status='active'` (if any)
     * followed by the INSERT of [invite] (already ACTIVE,
     * `GuestInvite.issue`), so the revoke+insert pair can never observe
     * two ACTIVE rows. Never posted to the chat (FR-009). The caller
     * has ALREADY passed the moderator gate and the
     * `conference_ended`/live-room checks.
     */
    fun issue(invite: GuestInvite): GuestInviteIssueResult

    /**
     * №53 `DELETE …/guest-invite` (T045): the single conditional
     * `UPDATE … SET status='revoked', revoked_at=:at WHERE id = :id
     * AND status='active' RETURNING …`. Returns the revoked row, or
     * `null` when there was nothing to revoke — an unknown id AND an
     * already-revoked invite answer identically (the caller's `404
     * guest_invite_not_found` «нет активной», api-contract.md §2); a
     * revoke of an already-revoked row keeps the ORIGINAL
     * `revoked_at` ([GuestInvite.revoke] discipline). The post-commit
     * disconnect of guests still connected under `metadata.inviteId`
     * rides [LiveKitAdminClient] (Q13=B).
     */
    fun revoke(
        id: UUID,
        at: Instant,
    ): GuestInvite?

    /**
     * The №54/№55 validation read (T046, api-contract.md §3): the row
     * behind a nonce's `sub = inviteId` after the SIGNATURE/`exp` layer
     * (T044) accepted the token. `null` → `404 invite_not_found`; a
     * row present but REVOKED/expired → the caller's `409
     * invite_revoked`/`invite_expired` (the [GuestInvite.isValid]
     * predicate; the refusal of a forged/unparsable nonce stays
     * uniform at the token layer — no reason disclosure).
     */
    fun findById(id: UUID): GuestInvite?
}
