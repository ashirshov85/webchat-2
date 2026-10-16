package webchat.backend.conferences.domain.port

import webchat.backend.conferences.domain.model.PublishGrants
import java.time.Duration

/**
 * The mint request of one client admission token — the variable part of
 * the livekit-integration.md §3 claim table: the full per-connection
 * [identity] (`u:{userId}:{connId}` / `g:{connId}`, one fresh server
 * UUID per mint), the display [name] (the member's username / the
 * guest's entered name), the participant [metadata] JSON
 * (`{"role","guest"[,"inviteId"]}`), the publish [grants] computed by
 * [webchat.backend.conferences.domain.model.ParticipantRestrictions.grants]
 * and the short [ttl] (`conferences.token-ttl` — self-hosted LiveKit
 * never revokes tokens). The constants of the table — `roomJoin=true`,
 * `canSubscribe=true`, `canPublishData=false` — belong to the ADAPTER,
 * exactly like the contract constants around
 * [webchat.backend.conferences.domain.model.PublishGrants] in
 * [LiveKitAdminClient.updateParticipant]; admin grants are NEVER part
 * of a client token (FR-008).
 */
data class ClientTokenRequest(
    val roomName: String,
    val identity: String,
    val name: String,
    val metadata: String,
    val grants: PublishGrants,
    val ttl: Duration,
)

/**
 * The CLIENT token mint port over the media plane (livekit-integration.md
 * §3; DIP: the LiveKit SDK adapter lives in
 * `webchat.backend.conferences.livekit.LiveKitServerTokenMinter`, T018 —
 * the domain stays technology-free, constitution VIII). The mint is a
 * pure local HS256 signature over the [ClientTokenRequest] claims: no
 * media-plane round-trip, no state — the quota `ListParticipants`
 * pre-check of FR-015 is the caller's
 * ([webchat.backend.conferences.domain.service.JoinTokenService]) leg
 * over [LiveKitAdminClient], not this port's.
 *
 * Mirrors the server-side pair of the same table: the admin token of
 * the Twirp adapter (T012) is minted in the ADAPTER's memory with
 * `roomCreate`+`roomList`+`roomAdmin` and never leaves the backend —
 * a CLIENT token minted here carries `roomJoin` only and NEVER any
 * admin grant (FR-008 — all moderation is server-side).
 */
interface LiveKitTokenMinter {
    /**
     * Signs one short-lived client JWT (HS256, `iss = LIVEKIT_API_KEY`)
     * for [request.roomName]: `sub`/`name`/`metadata` + the `video`
     * grant object of §3. Returns the compact JWS string — the
     * `JoinTicket.token` of №47/№55; connected LiveKit participants get
     * a silent token refresh, so the TTL never cuts a live call.
     */
    fun mint(request: ClientTokenRequest): String
}
