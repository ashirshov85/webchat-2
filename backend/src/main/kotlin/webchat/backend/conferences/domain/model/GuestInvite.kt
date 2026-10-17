package webchat.backend.conferences.domain.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Status of a guest invite link (data-model 009 §Сущность 3, the V18
 * `ck_conference_guest_invites_status` CHECK): [ACTIVE] — the link
 * validates (within its TTL); [REVOKED] — refused at №54/№55 and, once
 * revoked, every guest connected under it is disconnected post-commit
 * (Q13=B). Exactly one ACTIVE row per conference is guaranteed by the
 * partial unique index `ux_guest_invites_active` — generating a new
 * link revokes the previous one in the same transaction (Q17=A).
 */
enum class GuestInviteStatus {
    ACTIVE,
    REVOKED,
}

/**
 * The minimal status row behind a stateless signed guest link
 * (data-model 009 §Сущность 3, research §6): the JWT-nonce carries the
 * cryptographic validity (ES256 signature, `sub = id`, `exp =
 * expiresAt` — T044); this row adds REVOCATION and the «one active»
 * invariant. NOT posted to the chat (FR-009). Rows are deleted on
 * finalize (Q18=A). The `init` block mirrors the V18 CHECKs:
 * `expires_at > created_at` and `revoked ⟺ revoked_at set`.
 */
data class GuestInvite(
    val id: UUID,
    val conferenceId: UUID,
    val createdBy: UUID,
    val status: GuestInviteStatus = GuestInviteStatus.ACTIVE,
    val createdAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
) {
    init {
        require(expiresAt > createdAt) {
            "guest invite must expire after creation (V18 ck_conference_guest_invites_expires)"
        }
        require((status == GuestInviteStatus.REVOKED) == (revokedAt != null)) {
            "revoked_at is set iff the invite is revoked (V18 ck_conference_guest_invites_revoked)"
        }
    }

    /**
     * The №54/№55 validation predicate (api-contract §3, T046): the
     * row is still ACTIVE and the TTL horizon has not passed (the
     * signature itself is checked by the nonce layer, T044 — JWT `exp`
     * equals [expiresAt], so both agree).
     */
    fun isValid(at: Instant): Boolean = status == GuestInviteStatus.ACTIVE && at < expiresAt

    /**
     * №53 revocation / the Q17=A pre-revocation of №52 (both run in the
     * `revoke + insert` transaction of T045): moves to [REVOKED][GuestInviteStatus.REVOKED]
     * with the timestamp, idempotent — revoking an already revoked row
     * keeps the original `revoked_at`. The post-commit disconnect of
     * guests connected under `metadata.inviteId = id` is T045's job.
     */
    fun revoke(at: Instant): GuestInvite =
        if (status == GuestInviteStatus.REVOKED) this else copy(status = GuestInviteStatus.REVOKED, revokedAt = at)

    companion object {
        /**
         * №52 `POST …/guest-invites` (T045): a fresh ACTIVE row with
         * `expiresAt = createdAt + conferences.guest-invite-ttl`
         * (24 h default, Q19=A) — the `exp` claim of the minted nonce
         * (T044) uses the same horizon.
         */
        fun issue(
            id: UUID,
            conferenceId: UUID,
            createdBy: UUID,
            at: Instant,
            ttl: Duration,
        ): GuestInvite =
            GuestInvite(
                id = id,
                conferenceId = conferenceId,
                createdBy = createdBy,
                status = GuestInviteStatus.ACTIVE,
                createdAt = at,
                expiresAt = at + ttl,
            )
    }
}
