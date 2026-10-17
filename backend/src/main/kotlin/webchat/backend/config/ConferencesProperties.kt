package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Conference quotas and timers of 009-video-conferences, bound from
 * `conferences.*` (application.yml). Values mirror the fixed v1 constants
 * of the feature (api-contract.md §5, Q19=A — every value overridable
 * per-env via env / SPRING_APPLICATION_JSON, research.md §9): the
 * participant quota [maxParticipants] (dev stand ≤ 16, SC-008) is applied
 * at room creation and at every token mint (FR-015); [tokenTtl] keeps
 * client LiveKit JWTs short-lived (self-hosted LiveKit never revokes
 * tokens — livekit-integration.md §3, connected clients get silent token
 * refresh); [guestInviteTtl] is the nonce horizon of an invite link
 * (FR-012); [emptyRoomTimeout]/[departureTimeout] become the
 * `empty_timeout`/`departure_timeout` of the LiveKit room created by №44
 * (livekit-integration.md §2, SC-007); [maxDuration] +
 * [warningBeforeEnd] drive the controlled end of long conferences
 * (FR-015, Q16=B); [reconnectWindow] is the client re-mint window after
 * an expired mid-call token (plan.md Constraints); [guestNameMaxLength]
 * caps the guest display name after trim (Q7/Q19=A); [sweepInterval] and
 * [roomLostGrace] drive the lifecycle sweeper compensating lost webhooks
 * (SC-007; the default grace ≥ 2× the empty-room timeout, so it never
 * races the regular LiveKit closure of an empty room). [livekit] is the
 * media-plane connection of the admin adapter (livekit-integration.md
 * §1). Consumed by ConferenceService/JoinTokenService/
 * ConferenceLifecycleScheduler/T012 adapter; asserted by the binding
 * unit test and the IT set T021+.
 */
@ConfigurationProperties(prefix = "conferences")
data class ConferencesProperties(
    /** FR-015/Q23: simultaneous-connection quota — LiveKit `max_participants` of the room + pre-check at every mint. */
    val maxParticipants: Int,
    /** livekit-integration.md §3: TTL of every client token (member №47 and guest №55 alike). */
    val tokenTtl: Duration,
    /** FR-012 (Q19=A): signed-nonce TTL of a guest invite link (`exp = created + ttl`). */
    val guestInviteTtl: Duration,
    /** SC-007: `empty_timeout` of the LiveKit room — «никто не вошёл» border. */
    val emptyRoomTimeout: Duration,
    /** SC-007: `departure_timeout` of the LiveKit room — last-participant-left border. */
    val departureTimeout: Duration,
    /** FR-015 (Q16=B): hard cap of an active conference's lifetime. */
    val maxDuration: Duration,
    /** FR-015 (Q16=B): the warning head start before the max-duration end. */
    val warningBeforeEnd: Duration,
    /** plan.md Constraints: client window for re-minting an expired token via №47/№55 before giving up (Q15=B). */
    val reconnectWindow: Duration,
    /** FR-012 (Q7/Q19=A): guest display-name cap after trim. */
    val guestNameMaxLength: Int,
    /** SC-007: fixed-delay tick of the lifecycle sweeper (internal, not a contract value). */
    val sweepInterval: Duration,
    /** SC-007: an `active` conference without a LiveKit room longer than this finalizes as `room_lost`. */
    val roomLostGrace: Duration,
    /** livekit-integration.md §1: media-plane ingress and the API pair of the server config. */
    val livekit: LiveKit,
) {
    /**
     * LiveKit connection of the backend: [url] is the signaling/Twirp
     * ingress, [apiKey]/[apiSecret] the server pair that signs client
     * tokens, admin tokens of the Twirp adapter (T012) and webhook
     * bodies. Dev defaults mirror deploy/local/docker-compose.yml (the
     * public `--dev` placeholder pair); production values come from env /
     * K8s Secrets only (constitution V, SC-005).
     */
    data class LiveKit(
        val url: String,
        val apiKey: String,
        val apiSecret: String,
    )

    init {
        require(maxParticipants > 0) { "conferences.max-participants must be positive" }
        require(tokenTtl.isPositive) { "conferences.token-ttl must be positive" }
        require(guestInviteTtl.isPositive) { "conferences.guest-invite-ttl must be positive" }
        require(emptyRoomTimeout.isPositive) { "conferences.empty-room-timeout must be positive" }
        require(departureTimeout.isPositive) { "conferences.departure-timeout must be positive" }
        require(maxDuration.isPositive) { "conferences.max-duration must be positive" }
        require(warningBeforeEnd.isPositive) { "conferences.warning-before-end must be positive" }
        require(reconnectWindow.isPositive) { "conferences.reconnect-window must be positive" }
        require(guestNameMaxLength > 0) { "conferences.guest-name-max-length must be positive" }
        require(sweepInterval.isPositive) { "conferences.sweep-interval must be positive" }
        require(roomLostGrace.isPositive) { "conferences.room-lost-grace must be positive" }
        require(livekit.url.isNotBlank()) { "conferences.livekit.url must not be blank" }
        require(livekit.apiKey.isNotBlank()) { "conferences.livekit.api-key must not be blank" }
        require(livekit.apiSecret.isNotBlank()) { "conferences.livekit.api-secret must not be blank" }
    }
}
