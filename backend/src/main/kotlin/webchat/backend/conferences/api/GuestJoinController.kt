package webchat.backend.conferences.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.api.dto.GuestJoinRequest
import webchat.backend.conferences.api.dto.InviteInfoReason
import webchat.backend.conferences.api.dto.InviteInfoView
import webchat.backend.conferences.api.dto.JoinTicket
import webchat.backend.conferences.api.dto.inviteInfoReasonLabel
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.conferences.domain.model.GuestInviteStatus
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.GuestInviteRepository
import webchat.backend.conferences.domain.service.ConferenceEndedException
import webchat.backend.conferences.domain.service.InviteExpiredException
import webchat.backend.conferences.domain.service.InviteInvalidException
import webchat.backend.conferences.domain.service.InviteNotFoundException
import webchat.backend.conferences.domain.service.InviteRevokedException
import webchat.backend.conferences.domain.service.JoinTokenService
import webchat.backend.conferences.livekit.GuestInviteNonce
import webchat.backend.conferences.livekit.GuestInviteTokens
import webchat.backend.conferences.livekit.InvalidGuestInviteTokenException
import webchat.backend.config.ConferencesProperties
import java.util.UUID

/**
 * T046 (009, US4) — the PUBLIC guest surface of the `conferences` tag:
 * №54 `GET /api/v1/conferences/invite-info?inviteToken={nonce}` and №55
 * `POST /api/v1/conferences/join-by-invite` (api-contract.md 009 §3) —
 * the ONLY endpoints of the feature with no `Authorization` at all
 * (FR-009: the controlled exception whose `permitAll` + per-IP buckets
 * open in T047). The same thin-adapter discipline as
 * [ConferenceController]: every business refusal leaves as a typed
 * exception rendered problem+json by [ConferencesExceptionHandler], and
 * the guest NEVER learns more than the contract's minimum — no chat
 * details, no roster, no causes of forgery (api-contract.md 009 §1).
 *
 * Both endpoints share ONE ladder over the two-layer link of research
 * §6 (the ES256 nonce of T044 proves AUTHENTICITY — every forgery mode
 * folds uniformly; the `conference_guest_invites` status row of T011
 * owns the disclosable LIFECYCLE states expired/revoked, the conference
 * row owns ended/live):
 *
 * 1. the name shape of №55 — the server trim, 1..
 *    `conferences.guest-name-max-length` → `422 name_invalid`;
 * 2. the nonce — [GuestInviteTokens.verify] → the uniform `404
 *    invite_invalid` (№54 answers the same fold as `{valid:false}`
 *    WITHOUT a reason);
 * 3. the status row — `409 invite_revoked` / `409 invite_expired`
 *    (№54: `reason:"revoked"|"expired"`); a row gone WITHOUT an ended
 *    conference → `404 invite_not_found` (№54: bare `{valid:false}`);
 * 4. the live row — `409 conference_ended` (№54:
 *    `reason:"ended"`) — INCLUDING the finalize fold: the deleted rows
 *    of Q18=A are answered from the surviving ended conference row,
 *    the T057 shape of an old link after the call;
 * 5. the mint legs of [JoinTokenService.mintGuest] — the SHARED quota
 *    (`409 conference_full`, Q23=A) and the `g:{connId}` ticket whose
 *    row is created exactly at the issuance (Q7=A).
 *
 * №55 of a refused link counts
 * `webchat_conference_joins_denied_total{reason=invite}` (FR-018,
 * SC-003); the quota denial is counted by the service, verbatim №47.
 * №54 is a bare status probe — nothing is counted and nothing is
 * written by either endpoint before its refusal.
 */
@RestController
// the №54/№55 collaborators, one port per leg (DIP, plan.md VIII): the
// verifier is consumed through its T044 adapter face because the
// domain needs only the mint half (GuestInviteNonces) — verification
// is an API-surface concern and both are outer-ring adapters
@Suppress("LongParameterList")
class GuestJoinController(
    private val tokens: GuestInviteTokens,
    private val invites: GuestInviteRepository,
    private val conferences: ConferenceRepository,
    private val joinTokenService: JoinTokenService,
    private val properties: ConferencesProperties,
    private val metrics: ConferenceMetrics,
    private val clock: Clock,
) {
    /**
     * №54 (api-contract.md 009 §3): the minimum-disclosure status answer
     * for the join page BEFORE the name entry (SC-003). A forged nonce
     * answers the bare `{valid:false}` — no reason, no `live`, and no
     * status read at all (the forgery modes are indistinguishable,
     * api-contract §1 edge). A LIFECYCLE refusal carries its
     * [InviteInfoView.reason]; a valid link carries `live` — the
     * conference row's `status='active'` (the first join happened,
     * finalize has not): the public probe deliberately makes NO
     * media-plane round-trip.
     */
    @GetMapping("/api/v1/conferences/invite-info")
    fun inviteInfo(
        @RequestParam(required = false) inviteToken: String?,
    ): InviteInfoView {
        if (inviteToken.isNullOrBlank()) {
            throw MissingInviteTokenException()
        }
        val nonce = verify(inviteToken) ?: return InviteInfoView(valid = false)
        return when (val resolution = resolve(nonce)) {
            is InviteResolution.Usable ->
                InviteInfoView(
                    valid = true,
                    live = resolution.conference.status == ConferenceStatus.ACTIVE,
                )
            is InviteResolution.Refused ->
                InviteInfoView(valid = false, reason = resolution.reason?.let(::inviteInfoReasonLabel))
        }
    }

    /**
     * №55 (api-contract.md 009 §3): the guest [JoinTicket] — the ladder
     * of the class KDoc, in the contract order (name → nonce → status →
     * live → the delegated mint legs). The link is multi-use (Q2=A) and
     * a kick is no ban: every №55 of a still-active link mints a FRESH
     * `g:{connId}` row (Q7=A) through [JoinTokenService.mintGuest]. A
     * refused link leg counts the `invite` denial of FR-018 before its
     * typed exception; the `conference_ended` fold is a conference
     * state, not a link denial, and deliberately is not counted here.
     *
     * `ThrowsCount` suppressed: the throw statements ARE the №55
     * refusal ladder of api-contract.md 009 §3, in the contract order.
     */
    @Suppress("ThrowsCount")
    @PostMapping("/api/v1/conferences/join-by-invite")
    fun joinByInvite(
        @RequestBody request: GuestJoinRequest,
    ): JoinTicket {
        val name = request.validatedName(properties.guestNameMaxLength)
        val nonce =
            verify(request.inviteToken)
                ?: run {
                    metrics.countJoinDenied(ConferenceMetrics.JoinDenialReason.INVITE)
                    throw InviteInvalidException()
                }
        when (val resolution = resolve(nonce)) {
            is InviteResolution.Usable ->
                return joinTokenService.mintGuest(resolution.conference, resolution.invite.id, name)
            is InviteResolution.Refused ->
                when (resolution.reason) {
                    InviteInfoReason.REVOKED -> {
                        metrics.countJoinDenied(ConferenceMetrics.JoinDenialReason.INVITE)
                        throw InviteRevokedException()
                    }
                    InviteInfoReason.EXPIRED -> {
                        metrics.countJoinDenied(ConferenceMetrics.JoinDenialReason.INVITE)
                        throw InviteExpiredException()
                    }
                    InviteInfoReason.ENDED -> throw ConferenceEndedException()
                    null -> {
                        metrics.countJoinDenied(ConferenceMetrics.JoinDenialReason.INVITE)
                        throw InviteNotFoundException()
                    }
                }
        }
    }

    /**
     * The signature layer of the ladder (T044): AUTHENTICITY only —
     * `null` for a blank/unparseable/foreign/tampered/mistyped nonce,
     * the validated claims otherwise. Expiry is deliberately NOT a
     * failure here: the signed-but-expired link still resolves to its
     * row so the status layer can answer the DISCLOSABLE `expired`
     * lifecycle state ([GuestInviteNonce] carries `expiresAt` for
     * exactly that).
     */
    private fun verify(raw: String?): GuestInviteNonce? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            tokens.verify(raw)
        } catch (_: InvalidGuestInviteTokenException) {
            null
        }
    }

    /**
     * The shared status/live core of №54/№55 — every outcome both
     * endpoints need, graded:
     * * [InviteResolution.Usable] — the ACTIVE, unexpired row of a
     *   NON-ended conference: №54 answers `valid` (+`live`), №55 mints;
     * * [InviteResolution.Refused] with a [InviteInfoReason] — the
     *   disclosable lifecycle refusals `revoked`/`expired` (the row)
     *   and `ended` (the conference row, INCLUDING the finalize fold:
     *   the invite rows are deleted, Q18=A, so the ended conference
     *   row is the only surviving witness — the T057 old-link shape);
     * * [InviteResolution.Refused] with `null` — the anomalous
     *   no-witness state (a row gone without an ended conference, a
     *   conference row gone under a live invite): №54's uniform bare
     *   `{valid:false}` and №55's `404 invite_not_found` — the least
     *   disclosure the contract allows.
     */
    private fun resolve(nonce: GuestInviteNonce): InviteResolution {
        val invite = invites.findById(nonce.inviteId)
        return when {
            invite == null -> resolveOfMissingRow(nonce.conferenceId)
            invite.status == GuestInviteStatus.REVOKED -> InviteResolution.Refused(InviteInfoReason.REVOKED)
            !invite.isValid(clock.now()) -> InviteResolution.Refused(InviteInfoReason.EXPIRED)
            else ->
                when (val conference = conferences.findById(invite.conferenceId)) {
                    null -> InviteResolution.Refused(null)
                    else ->
                        if (conference.isEnded) {
                            InviteResolution.Refused(InviteInfoReason.ENDED)
                        } else {
                            InviteResolution.Usable(invite, conference)
                        }
                }
        }
    }

    /**
     * The finalize fold of [resolve]: the invite rows of a finalized
     * conference are deleted (Q18=A), so the ENDED conference row is
     * the only witness of the old link — `ended` when it says so, the
     * uniform no-witness refusal otherwise.
     */
    private fun resolveOfMissingRow(conferenceId: UUID): InviteResolution =
        if (conferences.findById(conferenceId)?.isEnded == true) {
            InviteResolution.Refused(InviteInfoReason.ENDED)
        } else {
            InviteResolution.Refused(null)
        }

    /** The graded outcome of the shared №54/№55 ladder — see [resolve]. */
    private sealed interface InviteResolution {
        /** The ACTIVE, unexpired row of a non-ended conference. */
        data class Usable(
            val invite: GuestInvite,
            val conference: Conference,
        ) : InviteResolution

        /** A refusal: the disclosable [reason], or `null` for the uniform no-witness fold. */
        data class Refused(
            val reason: InviteInfoReason?,
        ) : InviteResolution
    }
}

/**
 * 400 (api-contract.md 009 №54): the `inviteToken` query parameter is
 * absent or blank — `errors: {inviteToken: [malformed_request]}`. The
 * submitted value is never echoed (constitution V); rendered by
 * [ConferencesExceptionHandler] as a typed problem. №55 has no such
 * 400: its blank `inviteToken` folds into the uniform `404
 * invite_invalid` with the rest of the forgery modes.
 */
class MissingInviteTokenException : RuntimeException("inviteToken must not be blank")
