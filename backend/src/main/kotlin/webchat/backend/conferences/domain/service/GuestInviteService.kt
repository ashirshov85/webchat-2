package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.api.dto.GuestInviteView
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.conferences.domain.port.GuestInviteNonces
import webchat.backend.conferences.domain.port.GuestInviteRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.config.ConferencesProperties
import java.util.UUID

/**
 * T045 (009, US4) — the moderator surface of the guest invite links:
 * №52 `POST /api/v1/conferences/{conferenceId}/guest-invite` (the
 * generation) and №53 `DELETE …/guest-invite` (the revocation), over
 * the two-layer link of research.md §6 / data-model 009 §Сущность 3:
 * the stateless ES256 nonce ([GuestInviteNonces], T044 — signature and
 * `exp` horizon) plus the minimal status row
 * ([GuestInviteRepository], T011 — revocation and the «one ACTIVE per
 * conference» invariant of `ux_guest_invites_active`).
 *
 * The CALLER ([webchat.backend.conferences.api.ConferenceController])
 * has ALREADY resolved `404 conference_not_found` → the №52 `409
 * conference_ended` of an ended row → the `403 moderator_required`
 * gate; this service re-checks ONLY the ended row of №52 as the
 * concurrent-finalize race backstop (the [ModerationService.end]
 * discipline: a №48/`room_finished` that lands between the caller's
 * resolution and here must never spawn a link of an ended conference).
 *
 * The Q13=B fast path of BOTH legs runs strictly POST-COMMIT
 * (livekit-integration.md §6.1): the status transaction (`revoke +
 * insert` of №52, the conditional revoke of №53) is durable FIRST,
 * then `ListParticipants` of `c:{conferenceId}` and
 * `RemoveParticipant` of EVERY live connection whose participant
 * metadata carries `inviteId` of the just-revoked link — the same
 * metadata key the №55 mint writes and every later
 * `UpdateParticipant` push preserves ([ModerationService], T036).
 * Members and guests of OTHER links never match and stay connected.
 * The disconnect is best-effort AFTER the durable fact (the №52/№53
 * contract tables define no media-failure code): the revoked row
 * already blocks every NEW entry at №54/№55, so a failing plane is
 * logged (ids only, constitution V) and the connected stragglers
 * converge by their own `conferences.token-ttl` horizon.
 *
 * The link is NEVER posted to the chat (FR-009): delivery rides the
 * moderator's own channel, the URL is the PUBLIC SPA route
 * `{app.public-base-url}/join/{nonce}` (the `app.public-base-url`
 * origin of the SSO/email links — dev default the Vite server, prod
 * value from env).
 */
@Service
@Suppress("LongParameterList") // the №52/№53 collaborators, one port per leg (DIP, plan.md VIII)
class GuestInviteService(
    private val invites: GuestInviteRepository,
    private val nonces: GuestInviteNonces,
    private val liveKit: LiveKitAdminClient,
    private val properties: ConferencesProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @param:Value("\${app.public-base-url}") private val publicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(GuestInviteService::class.java)

    /**
     * №52 (api-contract.md 009 §2): the fresh ACTIVE row + nonce pair
     * — `GuestInviteView {inviteUrl, expiresAt}`. ONE repository
     * transaction keeps `ux_guest_invites_active` unbroken (Q17=A):
     * the conference's previous ACTIVE link — if any — comes back as
     * [GuestInviteRepository.issue]'s `revokedPrevious` and its
     * connected guests disconnect post-commit (Q13=B) BEFORE the
     * answer leaves; a conference with NO previous link skips the
     * plane entirely (every earlier link was already revoked — and
     * disconnected — at its own №53 or №52, nothing can still hold its
     * `inviteId`).
     *
     * The row carries `expiresAt = now + conferences.guest-invite-ttl`
     * (24 h default, Q19=A) and the nonce is minted FROM THE STORED
     * row, so the JWT `exp` and the status-row horizon are one value
     * by construction (data-model 009 §Сущность 3).
     *
     * The re-check of the ended row is the race backstop of the class
     * KDoc — the caller's gate has already answered the contract's
     * `409 conference_ended` for the row he saw.
     */
    fun issue(
        conference: Conference,
        moderatorId: UUID,
    ): GuestInviteView {
        if (conference.isEnded) throw ConferenceEndedException()
        val invite =
            GuestInvite.issue(
                id = UUID.randomUUID(),
                conferenceId = conference.id,
                createdBy = moderatorId,
                at = clock.now(),
                ttl = properties.guestInviteTtl,
            )
        val result = invites.issue(invite)
        result.revokedPrevious?.let { previous -> disconnectGuestsOf(conference, previous.id) }
        return GuestInviteView(
            inviteUrl = joinUrlOf(nonces.mint(result.invite)),
            expiresAt = result.invite.expiresAt,
        )
    }

    /**
     * №53 (api-contract.md 009 §2): the revocation of the conference's
     * single ACTIVE link — the path carries no invite id, the
     * conditional [GuestInviteRepository.revokeActiveOf] resolves it.
     * `null` → [GuestInviteNotFoundException] (`404
     * guest_invite_not_found` «нет активной»): never issued, the №53
     * repeat of an already-revoked link and the finalized conference
     * whose invite rows are gone (Q18=A) answer identically — the fold
     * the №53 contract table prescribes INSTEAD of a 409 (the delete
     * lists no `conference_ended`). The win disconnects every guest
     * still connected under the link's `metadata.inviteId` post-commit
     * (Q13=B) and blocks every new №54/№55 entry by the revoked row.
     */
    fun revoke(conference: Conference) {
        val revoked =
            invites.revokeActiveOf(conference.id, clock.now())
                ?: throw GuestInviteNotFoundException()
        disconnectGuestsOf(conference, revoked.id)
    }

    /**
     * The Q13=B leg of livekit-integration.md §6.1: `ListParticipants`
     * of the conference room, then `RemoveParticipant` of EVERY live
     * connection whose participant metadata carries the [inviteId] —
     * the metadata is server-minted JSON (the №55 mint writes
     * `inviteId`, every later `UpdateParticipant` push preserves it,
     * T036), so a guest that does not match simply stays: members,
     * guests of other links and a malformed/unset metadata (an
     * invariant breach, not a refusal) never disconnect.
     *
     * Best-effort AFTER the commit (the class KDoc): the revoked row
     * already blocks new entries, the stragglers converge by their
     * token TTL.
     *
     * `TooGenericExceptionCaught` suppressed: the committed status
     * outranks the plane; convergence is documented above.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun disconnectGuestsOf(
        conference: Conference,
        inviteId: UUID,
    ) {
        try {
            val room = conference.roomName
            liveKit
                .listParticipants(room)
                .filter { inviteIdOf(it.metadata) == inviteId.toString() }
                .forEach { liveKit.removeParticipant(room, it.identity) }
        } catch (failure: Exception) {
            log.warn(
                "the post-commit disconnect of the guests of revoked invite <{}> from conference <{}> failed; " +
                    "new entries are already blocked by the revoked row and the stragglers converge by their " +
                    "token TTL: {}",
                inviteId,
                conference.id,
                failure.message,
            )
        }
    }

    /**
     * The `inviteId` of a live participant metadata string, or `null`
     * for unset/malformed input — the [ModerationService] twin: a
     * parse failure of a foreign string degrades to «no match», never
     * throws.
     *
     * `TooGenericExceptionCaught` suppressed: any parse failure
     * converges to `null`.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun inviteIdOf(rawMetadata: String?): String? {
        val node: JsonNode? =
            if (rawMetadata == null) {
                null
            } else {
                try {
                    objectMapper.readTree(rawMetadata)
                } catch (_: Exception) {
                    null
                }
            }
        return node?.path(METADATA_INVITE_ID)?.takeIf { it.isTextual }?.asText()
    }

    /** `{app.public-base-url}/join/{nonce}` — the PUBLIC SPA route of the link (FR-009/T050). */
    private fun joinUrlOf(nonce: String): String = "${publicBaseUrl.trimEnd('/')}/$JOIN_ROUTE/$nonce"

    private companion object {
        const val JOIN_ROUTE = "join"

        const val METADATA_INVITE_ID = "inviteId"
    }
}
