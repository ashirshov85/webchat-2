package webchat.backend.conferences.domain.port

import webchat.backend.conferences.api.dto.ConferenceMode
import java.time.Instant
import java.util.UUID

/**
 * The `conference.started` frame payload (realtime-conference-events.md
 * 009 §1, `components.schemas.ConferenceStartedEvent`; US5-1/FR-013):
 * a conference ACTUALLY started — the first `participant_joined`
 * webhook won the conditional `created→active` (the eager №44
 * `room_started` is NOT a start, research §7). [startedAt] is the
 * winning transition's own stamp; [mode] is the pre-join default the
 * invite widget of the chat history carries (the row stores none —
 * the [ConferenceModeReader] projection, data-model 009 §Сущность 1).
 */
data class ConferenceStartedEvent(
    val conferenceId: UUID,
    val chatId: UUID,
    val startedAt: Instant,
    val mode: ConferenceMode,
)

/**
 * The lifecycle fan-out port of the conferences domain
 * (realtime-conference-events.md 009; DIP: the adapter lives outside
 * the domain in
 * `webchat.backend.conferences.repository.RedisConferenceEventPublisher`,
 * T054 — it rides the EXISTING
 * [RedisRealtimePublisher][webchat.backend.realtime.RedisRealtimePublisher]
 * and its per-user `rt:user:{userId}` channel family over the SAME
 * at-most-once post-commit discipline as the 004/006/007 frames: no
 * new broker topology, no change to the delivery core, the presence
 * T017 seam precedent of a non-chats domain reusing the №18 channel).
 *
 * Publish-only: the addressee resolution is the caller's leg — the
 * ACTIVE chat-membership snapshot taken INSIDE the transition
 * transaction (the [ChatMembershipChecker.activeMemberIds] contract),
 * so the frame reaches exactly the roster the conference belonged to
 * («carol, не входя в звонок», US5-1/SC-006). Guests receive NOTHING
 * (no userId/SSE channel — realtime-conference-events.md §4); the
 * wire shape (the exact `conferenceId`/`chatId`/`startedAt`/`mode`
 * field set with the lower-case `video|audio` label) is the ADAPTER's
 * contract serialization.
 *
 * A single `started` leg (T054); the `ended` twin (T055) joins this
 * port with its own payload when the finalize lands.
 */
fun interface ConferenceEventPublisher {
    /**
     * T054 (realtime-conference-events.md §1): ONE
     * `conference.started {conferenceId,chatId,startedAt,mode}`
     * envelope per [toUserIds] addressee on their own
     * `rt:user:{userId}` channel — published strictly POST-COMMIT of
     * the winning `created→active` transition, EXACTLY ONCE per
     * conference (the conditional UPDATE is the once-anchor: a
     * duplicate/retried `participant_joined` never reaches this leg).
     * At-most-once like every №18 frame: a lost or failed delivery
     * must NOT fail the caller and is NEVER re-sent — clients converge
     * via the №45 `status` refetch (SC-006 completeness, FR-013);
     * duplicates stay safe because consumers apply the frame as a
     * status function (constitution III).
     */
    fun fanoutConferenceStarted(
        toUserIds: List<UUID>,
        event: ConferenceStartedEvent,
    )
}
