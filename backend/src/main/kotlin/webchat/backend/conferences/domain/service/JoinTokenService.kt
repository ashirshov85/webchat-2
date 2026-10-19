package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Service
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.api.dto.JoinTicket
import webchat.backend.conferences.api.dto.participantRoleLabel
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ClientTokenRequest
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitTokenMinter
import webchat.backend.conferences.domain.port.MemberNameReader
import webchat.backend.conferences.domain.port.ParticipantRepository
import webchat.backend.config.ConferencesProperties
import java.util.UUID

/**
 * T018 (009, US1) — the №47 `POST /api/v1/conferences/{conferenceId}/join`
 * mint: the short-lived LiveKit admission ticket of a chat member.
 *
 * The CALLER ([webchat.backend.conferences.api.ConferenceController],
 * T019) has ALREADY resolved the contract ladder `404
 * conference_not_found` → `403 conference_forbidden` (the every-join
 * membership gate, FR-003/SC-002) → `409 conference_ended` on a
 * NON-ended [Conference] row — this service owns exactly the two
 * mint-time legs of livekit-integration.md §3/§4, in this order:
 *
 * 1. **the FR-015 quota pre-check** — `ListParticipants` of
 *    `c:{conferenceId}` (the live-connection truth of the media plane;
 *    members and guests share the [Conference.maxParticipants]
 *    snapshot, Q23=A, and two tabs of one member are two connections,
 *    the edge of spec.md) against the quota: an exhausted room answers
 *    [ConferenceFullException] (`409 conference_full`) with the
 *    `webchat_conference_joins_denied_total{reason=quota}` count of
 *    T014; an unreachable plane surfaces as
 *    [webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException]
 *    (`503 conference_media_unavailable`) BEFORE anything is written;
 * 2. **the mint** — `ensureMember` first (the FIRST join creates the
 *    principal row; every re-join — reconnect, second tab — returns
 *    the EXISTING row verbatim, so role and moderation restrictions
 *    reproduce in the fresh grants, Q5/Q9=A, invariant 3), then the
 *    per-connection identity `u:{userId}:{connId}` (a FRESH server
 *    UUID per mint — LiveKit de-duplicates by identity, so a second
 *    tab keeps its own slot), the `name` claim from `users.username`
 *    ([MemberNameReader]), the metadata `{"role","guest":false}` and
 *    the grants of [ConferenceParticipant.grants] through
 *    [LiveKitTokenMinter] (HS256, TTL `conferences.token-ttl`, NO
 *    admin grants — all moderation is server-side, FR-008).
 *
 * The refusal of leg 1 writes NOTHING — no principal row, no token, no
 * metric but the denial counter; a successful mint counts
 * `webchat_conference_tokens_minted_total{kind=member}` (FR-018). The
 * guest twin of №55 is [mintGuest] (T046): the same two mint-time legs
 * with the `g:{connId}` identity and `guest:true` metadata.
 */
@Service
@Suppress("LongParameterList") // the №47 collaborators, one port per leg (DIP, plan.md VIII)
class JoinTokenService(
    private val liveKit: LiveKitAdminClient,
    private val participants: ParticipantRepository,
    private val memberNames: MemberNameReader,
    private val tokenMinter: LiveKitTokenMinter,
    private val properties: ConferencesProperties,
    private val metrics: ConferenceMetrics,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /**
     * №47 (api-contract.md 009 §2): the [JoinTicket] — the signed
     * token, the WS ingress [JoinTicket.wsUrl] of
     * `conferences.livekit.url`, the full connection [JoinTicket.identity]
     * and the [JoinTicket.expiresAt] horizon for the client's mid-call
     * re-mint (self-hosted LiveKit never revokes tokens — connected
     * clients get the silent refresh, research §4).
     *
     * `ThrowsCount` suppressed: [ConferenceFullException] and the
     * propagated [webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException]
     * ARE the two №47 mint-time refusals of the contract table.
     */
    @Suppress("ThrowsCount")
    fun mintMember(
        callerId: UUID,
        conference: Conference,
    ): JoinTicket {
        // leg 1 — the FR-015 quota pre-check over the media-plane
        // truth: an absent room reads as an empty roster (the adapter
        // folds `not_found`), an unreachable plane throws the 503
        // shape before any write
        val connected = liveKit.listParticipants(conference.roomName)
        if (connected.size >= conference.maxParticipants) {
            metrics.countJoinDenied(ConferenceMetrics.JoinDenialReason.QUOTA)
            throw ConferenceFullException()
        }

        // leg 2 — the principal row BEFORE the mint: the first join
        // inserts `role='participant'`, a re-join returns the existing
        // row verbatim, so the persisted restrictions land in the
        // fresh grants (Q5/Q9=A)
        val principal = participants.ensureMember(conference.id, callerId, clock.now())
        val username =
            memberNames.usernameOf(callerId)
                ?: error("an authenticated chat member must resolve to a users row (the 002/006 invariant)")

        // a fresh connId per mint: LiveKit de-duplicates by identity,
        // so reconnects and second tabs keep distinct slots (edge Q10)
        val identity = ConferenceIdentity.member(callerId).connectionIdentity(UUID.randomUUID())
        val token =
            tokenMinter.mint(
                ClientTokenRequest(
                    roomName = conference.roomName,
                    identity = identity,
                    name = username,
                    metadata = memberMetadata(principal).toString(),
                    grants = principal.grants(),
                    ttl = properties.tokenTtl,
                ),
            )
        metrics.countTokensMinted(ConferenceMetrics.JoinKind.MEMBER)
        return JoinTicket(
            token = token,
            wsUrl = properties.livekit.clientUrl(),
            identity = identity,
            name = username,
            expiresAt = clock.now() + properties.tokenTtl,
        )
    }

    /**
     * The member participant metadata of livekit-integration.md §3:
     * `{"role":"moderator"|"participant","guest":false}` — the UI
     * broadcast surface of the role (№50 re-mints recompute it from
     * the persisted row, Q9=A).
     */
    private fun memberMetadata(principal: ConferenceParticipant): ObjectNode =
        objectMapper
            .createObjectNode()
            .put(METADATA_ROLE, participantRoleLabel(principal.role))
            .put(METADATA_GUEST, false)

    /**
     * №55 (api-contract.md 009 §3, T046): the GUEST twin of the mint.
     * The CALLER ([webchat.backend.conferences.api.GuestJoinController])
     * has ALREADY resolved the name shape (`422 name_invalid`) and the
     * nonce ladder (`404 invite_invalid|invite_not_found`, `409
     * invite_expired|invite_revoked|conference_ended`); this service
     * owns exactly the two mint-time legs of the member twin, in the
     * same order:
     *
     * 1. **the FR-015 quota pre-check** — `ListParticipants` against
     *    the SHARED [Conference.maxParticipants] snapshot (members and
     *    guests divide one quota, Q23=A): an exhausted room answers
     *    [ConferenceFullException] (`409 conference_full`) counting
     *    `webchat_conference_joins_denied_total{reason=quota}`; an
     *    unreachable plane surfaces as the 503 shape BEFORE anything is
     *    written;
     * 2. **the mint** — the guest principal row is created HERE, at the
     *    ticket issuance (Q7=A): a FRESH `connId` per entry — the link
     *    is multi-use (Q2=A) and a kick is no ban, so every №55 of a
     *    still-active link is a NEW row/identity `g:{connId}`; the
     *    metadata carries `{"role":"participant","guest":true,
     *    "inviteId"}` (the №53/№52 disconnect key of T045, livekit-
     *    integration.md §3) and the grants come from the just-inserted
     *    row — a fresh guest has NO restrictions, so the sources are
     *    FULL (Q1=C: the guest start state «only microphone» is a
     *    client default, never a narrowed grant); TTL as for members
     *    (`conferences.token-ttl`), NO admin grants (FR-008).
     *
     * `ThrowsCount` suppressed: [ConferenceFullException] and the
     * propagated [webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException]
     * ARE the two №55 mint-time refusals of the contract table.
     */
    @Suppress("ThrowsCount")
    fun mintGuest(
        conference: Conference,
        inviteId: UUID,
        name: String,
    ): JoinTicket {
        // leg 1 — the SHARED quota pre-check over the media-plane truth
        // (Q23=A), verbatim the member twin: the refused join leaves no
        // row and no token
        val connected = liveKit.listParticipants(conference.roomName)
        if (connected.size >= conference.maxParticipants) {
            metrics.countJoinDenied(ConferenceMetrics.JoinDenialReason.QUOTA)
            throw ConferenceFullException()
        }

        // leg 2 — the guest principal row AT the ticket issuance (Q7=A):
        // a fresh connId per entry, the row and the connection identity
        // are one `g:{connId}`
        val connId = UUID.randomUUID()
        val principal =
            participants.insert(
                ConferenceParticipant.guest(
                    id = UUID.randomUUID(),
                    conferenceId = conference.id,
                    connId = connId,
                    displayName = name,
                    firstSeenAt = clock.now(),
                ),
            )
        val identity = principal.identity.value
        val token =
            tokenMinter.mint(
                ClientTokenRequest(
                    roomName = conference.roomName,
                    identity = identity,
                    name = name,
                    metadata = guestMetadata(inviteId).toString(),
                    grants = principal.grants(),
                    ttl = properties.tokenTtl,
                ),
            )
        metrics.countTokensMinted(ConferenceMetrics.JoinKind.GUEST)
        return JoinTicket(
            token = token,
            wsUrl = properties.livekit.clientUrl(),
            identity = identity,
            name = name,
            expiresAt = clock.now() + properties.tokenTtl,
        )
    }

    /**
     * The guest participant metadata of livekit-integration.md §3:
     * `{"role":"participant","guest":true,"inviteId"}` — the guest is
     * never a moderator (FR-008/FR-010) and `inviteId` is the
     * `metadata.inviteId` disconnect key of the №52/№53 revocation legs
     * (T045, Q13=B).
     */
    private fun guestMetadata(inviteId: UUID): ObjectNode =
        objectMapper
            .createObjectNode()
            .put(METADATA_ROLE, participantRoleLabel(ParticipantRole.PARTICIPANT))
            .put(METADATA_GUEST, true)
            .put(METADATA_INVITE_ID, inviteId.toString())

    private companion object {
        const val METADATA_ROLE = "role"

        const val METADATA_GUEST = "guest"

        const val METADATA_INVITE_ID = "inviteId"
    }
}
