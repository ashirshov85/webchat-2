package webchat.backend.conferences.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Lifecycle status of a conference (data-model 009 §Сущность 1, the V18
 * `ck_conferences_status` CHECK): [CREATED] — the row and the eagerly
 * created LiveKit room exist, nobody has joined yet (`room_started`
 * arrives at the eager №44 room creation and is NOT a start); [ACTIVE] —
 * the first `participant_joined` webhook moved the row forward;
 * [ENDED] — finalize (any of the five paths of [ConferenceEndReason]).
 * Transitions move ONLY forward — the mirror of the conditional UPDATEs
 * that make `created→active` and finalize idempotent (research §7).
 */
enum class ConferenceStatus {
    CREATED,
    ACTIVE,
    ENDED,
}

/**
 * The deterministic finalize path of a conference (data-model 009
 * §Сущность 1, the V18 `ck_conferences_end_reason` CHECK; invariant 5):
 * [LAST_LEFT] — the `room_finished` webhook for an `active` conference
 * without a pre-recorded reason; [MODERATOR] — №48 pre-records the
 * reason before `DeleteRoom`, the later webhook finalize reuses it as a
 * no-op; [MAX_DURATION] — the scheduler's controlled end (Q16=B);
 * [ROOM_LOST] — the sweeper, and ANY end while still `created`
 * (empty-timeout, «никто не вошёл»); [CHAT_DELETED] — the chat-deletion
 * domain event handled in the deletion transaction BEFORE the
 * `ON DELETE CASCADE` (FR-014). Deterministic for the SSE `reason` and
 * the IT set.
 */
enum class ConferenceEndReason {
    LAST_LEFT,
    MODERATOR,
    MAX_DURATION,
    ROOM_LOST,
    CHAT_DELETED,
}

/**
 * A call bound to a chat (direct or group — `chats.kind`), the row
 * behind the LiveKit room `c:{id}` and the system invite message
 * (data-model 009 §Сущность 1). Exactly one non-[ENDED][ConferenceStatus.ENDED]
 * conference per chat is guaranteed by the partial unique index
 * `ux_conferences_chat_active` (FR-001 edge «двойное нажатие» — a
 * competing INSERT loses, the service returns the existing row);
 * [maxParticipants] is the creation-time snapshot of
 * `conferences.max-participants` applied both as the room's
 * `max_participants` and in the pre-mint quota check (FR-015).
 * Media state (connections, tracks, mutes) lives in LiveKit and is NOT
 * mirrored here (research §4/§7): the row keeps only what must survive
 * reconnect (roles, restrictions — [ConferenceParticipant]) and outlive
 * the call (the fact of it, Q18=A).
 *
 * The `init` block mirrors the directional CHECKs of V18, so a row read
 * back from storage always re-validates: `ended ⟺ ended_at/end_reason
 * set`, `started_at` is NULL while `created` and set once `active` (an
 * `ended` conference that never went active — `room_lost` — legitimately
 * keeps it NULL).
 */
data class Conference(
    val id: UUID,
    val chatId: UUID,
    val createdBy: UUID,
    val status: ConferenceStatus,
    val maxParticipants: Int,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val endReason: ConferenceEndReason? = null,
) {
    init {
        require(maxParticipants > 0) {
            "conference max_participants must be positive (V18 ck_conferences_max_participants)"
        }
        // finalize idempotency anchor (invariant 5)
        require((status == ConferenceStatus.ENDED) == (endedAt != null)) {
            "ended_at is set iff the conference is ended (V18 ck_conferences_ended_at)"
        }
        require((status == ConferenceStatus.ENDED) == (endReason != null)) {
            "end_reason is set iff the conference is ended (V18 ck_conferences_end_reason_set)"
        }
        // started_at: first participant_joined; NULL while 'created'; a
        // created→ended (room_lost) transition legitimately keeps it NULL
        require(status != ConferenceStatus.CREATED || startedAt == null) {
            "started_at must be NULL while the conference is created (V18 ck_conferences_started_created)"
        }
        require(status != ConferenceStatus.ACTIVE || startedAt != null) {
            "started_at must be set once the conference is active (V18 ck_conferences_started_active)"
        }
    }

    /**
     * The LiveKit room name of this conference (livekit-integration.md
     * §2): `c:{conferenceId}` — opaque, no PII. Derived, not stored.
     */
    val roomName: String
        get() = "$ROOM_NAME_PREFIX$id"

    /** FR-001 idempotence surface: a non-ended conference still accepts joins/token mints. */
    val isEnded: Boolean
        get() = status == ConferenceStatus.ENDED

    /**
     * `created → active` on the FIRST `participant_joined` webhook
     * (data-model 009 §Сущность 1): sets [startedAt] and returns the
     * advanced row, or `null` for a no-op — a duplicate
     * `participant_joined` (competing conditional UPDATEs, retries) and
     * any call on an already advanced/ended conference change nothing.
     * The repository (T010) applies this via a conditional UPDATE
     * `WHERE status='created'`; the post-commit SSE `conference.started`
     * fires exactly once (T054, US5-1).
     */
    fun activate(at: Instant): Conference? =
        when (status) {
            ConferenceStatus.CREATED -> copy(status = ConferenceStatus.ACTIVE, startedAt = at)
            ConferenceStatus.ACTIVE, ConferenceStatus.ENDED -> null
        }

    /**
     * `created|active → ended` — the domain transition behind every
     * finalize path (webhook `room_finished` / №48 moderator /
     * max-duration scheduler / sweeper / chat deletion — T055). Returns
     * the finalized row, or `null` when already ended: a duplicate
     * `room_finished` (or a webhook racing №48's pre-recorded
     * [ConferenceEndReason.MODERATOR]) is an idempotent no-op that
     * leaves `ended_at`/`end_reason` untouched (invariant 5). An end
     * while still [CREATED][ConferenceStatus.CREATED] keeps
     * [startedAt] NULL — the `room_lost` shape of the V18 CHECKs.
     */
    fun end(
        reason: ConferenceEndReason,
        at: Instant,
    ): Conference? =
        when (status) {
            ConferenceStatus.ENDED -> null
            ConferenceStatus.CREATED, ConferenceStatus.ACTIVE ->
                copy(status = ConferenceStatus.ENDED, endedAt = at, endReason = reason)
        }

    companion object {
        /** The room-name prefix of livekit-integration.md §2: `c:{conferenceId}` — opaque, no PII. */
        const val ROOM_NAME_PREFIX = "c:"

        /**
         * The inbound twin of [roomName] (T054): folds a webhook room
         * name back to the conference id — [ROOM_NAME_PREFIX] + a valid
         * UUID. Malformed input (foreign prefix, non-UUID tail) yields
         * `null`, never throws: webhook bodies are external input, and
         * a room this backend never created is the metric-tagged
         * no-op of livekit-integration.md §5 (the
         * [ConferenceIdentity.parsePrincipal] discipline).
         */
        fun conferenceIdOfRoomName(roomName: String): UUID? {
            if (!roomName.startsWith(ROOM_NAME_PREFIX)) return null
            return try {
                UUID.fromString(roomName.substring(ROOM_NAME_PREFIX.length))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * №44 `POST /chats/{chatId}/conferences` (T017): the new row
         * starts [CREATED][ConferenceStatus.CREATED] — the room is
         * created eagerly, but the conference only becomes `active` on
         * the first actual participant join (data-model 009 §Сущность 1,
         * research §7). The creator's moderator row is inserted
         * separately (T017, FR-008).
         */
        fun create(
            id: UUID,
            chatId: UUID,
            createdBy: UUID,
            maxParticipants: Int,
            at: Instant,
        ): Conference =
            Conference(
                id = id,
                chatId = chatId,
                createdBy = createdBy,
                status = ConferenceStatus.CREATED,
                maxParticipants = maxParticipants,
                createdAt = at,
            )
    }
}
