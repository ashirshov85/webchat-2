package webchat.backend.conferences.domain.port

import webchat.backend.conferences.domain.model.PublicationSource
import webchat.backend.conferences.domain.model.PublishGrants
import java.time.Duration

/**
 * A live connection of a LiveKit room as seen by the ADMIN surface —
 * the minimal projection of `ListParticipants` the backend consumers
 * need (livekit-integration.md §4): [identity] is the FULL connection
 * identity (`u:{userId}:{connId}` / `g:{connId}` — the
 * [webchat.backend.conferences.domain.model.ConferenceIdentity] tools
 * fold it), [metadata] is the raw participant-metadata JSON string
 * (`{"role","guest","inviteId"}`, §3) or `null` when unset. No tracks,
 * no PII beyond the opaque identities (research §4).
 */
data class LiveKitParticipant(
    val identity: String,
    val metadata: String?,
)

/**
 * Raised by [LiveKitAdminClient] when the media plane is UNREACHABLE
 * or keeps failing past the adapter's retries (T012): the caller maps
 * it to `503 conference_media_unavailable` (№44 pre-flight edge,
 * research §7) — the control plane and the rest of the API stay
 * untouched. Distinct from every idempotent no-op outcome: a room
 * already gone or an identity not connected is SUCCESS here, never
 * this exception.
 */
class ConferenceMediaUnavailableException(
    cause: Throwable,
) : RuntimeException("LiveKit media plane unavailable", cause)

/**
 * The admin port over the media plane — the server-side Twirp surface
 * of livekit-integration.md §4 (DIP: the LiveKit SDK adapter lives in
 * `webchat.backend.conferences.livekit.LiveKitServerAdminClient`, T012;
 * the admin token with `roomCreate`+`roomList`+`roomAdmin` grants is
 * minted in the ADAPTER's memory and never leaves the backend, §3).
 *
 * Every method is SET-semantics and safe to repeat (§4
 * «Идемпотентность»): the adapter retries transient failures with
 * backoff; a logically idempotent outcome (existing room, absent
 * room, absent identity) returns normally. Only a media plane that
 * stays unreachable surfaces as
 * [ConferenceMediaUnavailableException]. The room name of a
 * conference is always `c:{conferenceId}` (§2,
 * [webchat.backend.conferences.domain.model.Conference.roomName]).
 * Clients NEVER see this port — all moderation is server-side
 * (FR-008), client tokens carry no admin grants.
 */
interface LiveKitAdminClient {
    /**
     * `CreateRoom` (§2): the eager room of №44 — post-commit after the
     * conference row (T017), `name = c:{conferenceId}`,
     * [emptyTimeout]/[departureTimeout] from
     * `conferences.empty-room-timeout`/`departure-timeout` (60 s,
     * Q19=A) and [maxParticipants] — the creation-time snapshot also
     * enforced at the mint pre-check (FR-015). The sweeper's
     * self-healing re-creation of a lost `created` room (T056, SC-007)
     * rides the same call: an existing room is updated, never
     * duplicated.
     */
    fun createRoom(
        name: String,
        emptyTimeout: Duration,
        departureTimeout: Duration,
        maxParticipants: Int,
    )

    /**
     * `DeleteRoom` (§4): the controlled end — №48 moderator (T038)
     * and the max-duration limit (T056, Q16=B); every participant is
     * disconnected immediately and the `room_finished` webhook fires
     * the штатный finalize (T055). Called AFTER the finalize
     * pre-record when used by №48 (research §7). Deleting an absent
     * room is a success no-op (webhook/sweeper race) — only an
     * unreachable plane throws.
     */
    fun deleteRoom(name: String)

    /**
     * `ListRooms` (§4) as the bare room-name set: the №44 pre-flight
     * probe of media-plane availability (T017 — ANY successful call
     * proves the plane; failure → [ConferenceMediaUnavailableException]
     * → `503` BEFORE any DB write) and the sweeper's existence scan
     * (T056 — `created` rooms to re-create, `active` rooms past the
     * `room-lost-grace` to finalize, SC-007).
     */
    fun listRoomNames(): Set<String>

    /**
     * `ListParticipants` (§4): the live connection truth of a room —
     * the №47/№55 mint pre-check against the quota
     * (`Room.num_participants` vs `maxParticipants`, FR-015/Q23=A);
     * the guest disconnect of a revoked invite by
     * `metadata.inviteId` (T045, Q13=B); the chat-removal disconnect
     * by the `u:{userId}:` prefix (T020, FR-003 edge). Source of
     * truth for connections — the DB mirrors none of it (research
     * §4/§7).
     */
    fun listParticipants(room: String): List<LiveKitParticipant>

    /**
     * `RemoveParticipant` (§4): the immediate server-side disconnect
     * of ONE live connection — kick №51 (T037; кик ≠ бан, the
     * principal row is untouched), the revoked-invite disconnect
     * (T045), the chat-removal disconnect (T020) and the max-duration
     * break (T056). An identity not connected is a success no-op
     * (already gone / never joined).
     */
    fun removeParticipant(
        room: String,
        identity: String,
    )

    /**
     * `UpdateParticipant` (§4): the full SET of a live connection's
     * publish permission and metadata — the immediate application of
     * moderation (T036): [permission] carries the computed
     * [PublishGrants] (`speech_restricted ⟹ canPublish=false`, sources
     * minus restricted — `canSubscribe`/`canPublishData` are contract
     * constants the adapter adds), [metadata] the full participant
     * metadata JSON (`{"role","guest","inviteId"?}`) whose change
     * broadcasts `ParticipantMetadataChanged` to the UI (№50 role
     * broadcast, T037). LiveKit pushes the permission change to the
     * client (`ParticipantPermissionsChanged` — FR-007/FR-008); the
     * PERSISTENT truth stays in the DB and re-applies at every re-mint
     * (Q5=A). An identity not connected is a success no-op — the
     * row state alone answers the next mint.
     */
    fun updateParticipant(
        room: String,
        identity: String,
        permission: PublishGrants,
        metadata: String,
    )

    /**
     * `MutePublishedTrack` (§4): the immediate mute of the ALREADY
     * PUBLISHED tracks of one source kind of one connection
     * (T036/Q22=A — the adapter resolves the track sids via the
     * participant's live tracks; requires `enable_remote_unmute`,
     * §1). Already-muted or absent tracks are no-ops — SET-semantics
     * keeps repeated moderation idempotent.
     */
    fun mutePublishedTrack(
        room: String,
        identity: String,
        source: PublicationSource,
    )

    /**
     * `SendData` (§4): a server-originated data packet to EVERY
     * participant of the room (empty destination set) — the
     * max-duration warning (T056, Q16=B): topic `conference.warning`,
     * payload `{"type":"conference.warning","endsAt":…}`; reaches
     * connected GUESTS that have no SSE (FR-015). [payload] is the
     * JSON string; the adapter encodes it for the wire.
     */
    fun sendData(
        room: String,
        topic: String,
        payload: String,
    )
}
