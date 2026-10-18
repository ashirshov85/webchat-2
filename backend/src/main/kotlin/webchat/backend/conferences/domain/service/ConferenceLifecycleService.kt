package webchat.backend.conferences.domain.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.ConferenceMetrics.JoinKind
import webchat.backend.conferences.ConferenceMetrics.WebhookEvent
import webchat.backend.conferences.ConferenceMetrics.WebhookResult
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.port.ChatMembershipChecker
import webchat.backend.conferences.domain.port.ConferenceEventPublisher
import webchat.backend.conferences.domain.port.ConferenceModeReader
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.ConferenceStartedEvent
import webchat.backend.conferences.domain.port.ParticipantRepository
import java.util.UUID

/**
 * The media-plane lifecycle events of ONE conference, folded to the
 * domain before they get here (livekit-integration.md §5; DIP: the
 * `livekit.LivekitWebhook.WebhookEvent` protobuf stays behind the
 * T053 intake adapter — this hierarchy is what it maps onto). The
 * handlers are exhaustive over the sealed set, so a future contract
 * event fails compilation here until mapped (the sealed-hierarchy
 * discipline the realtime frames use):
 *
 *  * [RoomStarted] — the eager №44 room creation ack. NOT a start:
 *    the room exists before any participant (research §7), the
 *    handler is the metric-tagged 200/no-op;
 *  * [ParticipantJoined] — the FIRST join of a `created` conference
 *    wins the conditional `created→active` and with it the ONE
 *    `conference.started` SSE of US5-1; every join (reconnects,
 *    second tabs — edge Q10) marks the principal's `last_joined_at`;
 *  * [ParticipantLeft] — the `last_left_at` twin (the empty-room
 *    close itself is the plane's `departure_timeout` →
 *    [RoomFinished], never a leg of its own);
 *  * [RoomFinished] — the T055 finalize seat: until it lands, the
 *    documented no-op with its own metric tag;
 *  * [Unhandled] — everything else (track publications, egress…):
 *    the 200/no-op rest of §5.
 */
sealed interface ConferenceWebhookEvent {
    /** `room_started` — the room of [roomName] came into existence. */
    data class RoomStarted(
        val roomName: String,
    ) : ConferenceWebhookEvent

    /**
     * `participant_joined` — [identity] is the FULL connection
     * identity (`u:{userId}:{connId}` / `g:{connId}`) exactly as the
     * plane reports it; the row lookup folds it to the principal
     * prefix ([ConferenceIdentity.parsePrincipal]).
     */
    data class ParticipantJoined(
        val roomName: String,
        val identity: String,
    ) : ConferenceWebhookEvent

    /** `participant_left` — the [ParticipantJoined.identity] twin. */
    data class ParticipantLeft(
        val roomName: String,
        val identity: String,
    ) : ConferenceWebhookEvent

    /**
     * `room_finished` — the room closed (empty timeouts or
     * `DeleteRoom`); the штатный finalize path belongs to T055.
     */
    data class RoomFinished(
        val roomName: String,
    ) : ConferenceWebhookEvent

    /** The wire type of an event this backend does not handle — the metric `other` fold. */
    data class Unhandled(
        val eventType: String,
    ) : ConferenceWebhookEvent
}

/**
 * T054 (009, US5) — the webhook PROCESSING half of the lifecycle (the
 * T053 intake stays the boundary: signature, dedup, 200-always; this
 * service owns every state effect, realtime publication and metric of
 * an accepted event — livekit-integration.md §5, research §7):
 *
 *  * **`room_started` → 200/no-op** — the room is created EAGERLY at
 *    №44 (post-commit `CreateRoom`, T017), so the webhook arrives
 *    before any participant: a start it is NOT (research §7; US5-1:
 *    the conference starts at the FIRST actual join);
 *  * **the first `participant_joined` of a `created` conference** —
 *    the conditional `created→active`
 *    ([ConferenceRepository.activate]): the WINNING transition is the
 *    once-anchor of the post-commit SSE `conference.started` to the
 *    chat's ACTIVE members (the snapshot rides the SAME transaction —
 *    realtime-conference-events.md §1) and of the
 *    `webchat_conference_started_total` counter; duplicates, retries
 *    and the out-of-order `participant_joined` after `room_finished`
 *    (status `ended`) resolve to the repository's `null` no-op — no
 *    transition, no second event, no counter (invariant: the frame
 *    fires exactly once per conference, constitution III);
 *  * **every `participant_joined`/`participant_left`** — the
 *    `last_joined_at`/`last_left_at` marks on the principal row
 *    resolved by the identity PREFIX (`u:{userId}:{connId}` folds to
 *    `u:{userId}`, research §4) plus the
 *    `webchat_conference_joins_total{kind}` observability of an
 *    ACTUAL join (member/guest by the identity prefix — the mint
 *    counter of T018/T046 observes tokens, this one observes people);
 *    a rowcount-0 mark (a connection the backend never minted for,
 *    the finalize race already deleted the roster) is a silent no-op
 *    — the webhook still answers 200;
 *  * **the rest** — 200/no-op with the `ignored` metric.
 *
 * ONE transaction per participant event ([TransactionTemplate], the
 * T017 convention): the conditional transition, the membership
 * snapshot and the mark commit together, and the SSE fires strictly
 * AFTER it (the `SystemMessageService` post-commit rule — a rolled
 * back transition never announces anything). The mode of the SSE
 * payload is the invite-widget projection ([ConferenceModeReader]) —
 * a `null` there is the broken T017 create-transaction invariant and
 * fails LOUD, rolling the transition back (the resolveMode precedent
 * of the №44/№45 controller); the T053 intake still answers 200 and
 * the sweeper (T056) converges the orphan.
 */
@Service
// the T054 collaborators, one port per leg (DIP, plan.md VIII)
@Suppress("LongParameterList")
class ConferenceLifecycleService(
    private val conferences: ConferenceRepository,
    private val participants: ParticipantRepository,
    private val membership: ChatMembershipChecker,
    private val inviteModes: ConferenceModeReader,
    private val events: ConferenceEventPublisher,
    private val metrics: ConferenceMetrics,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ConferenceLifecycleService::class.java)

    /**
     * The ONE transaction of a participant event: the conditional
     * `created→active` (with its SSE payload legs) and the principal
     * mark commit or roll back TOGETHER (the JdbcTemplate convention
     * without JPA; the conference row is always written first, so the
     * finalize ordering cannot deadlock).
     */
    private val participantEvent = TransactionTemplate(transactionManager)

    /**
     * The T053 intake's single processing hand-off. NEVER throws into
     * the transport contract: a handled event runs its legs, every
     * event closes its `webchat_conference_webhooks_processed_total`
     * sample — the caller answers 200 regardless (§5 «Доступность»).
     */
    fun onWebhook(event: ConferenceWebhookEvent) {
        when (event) {
            is ConferenceWebhookEvent.RoomStarted -> {
                // the eager №44 room ack — a start it is NOT (research §7)
                log.debug("room_started of room <{}> acknowledged as a no-op", event.roomName)
                count(event, WebhookResult.IGNORED)
            }
            is ConferenceWebhookEvent.ParticipantJoined -> onParticipantJoined(event)
            is ConferenceWebhookEvent.ParticipantLeft -> onParticipantLeft(event)
            is ConferenceWebhookEvent.RoomFinished -> {
                // the T055 finalize seat: an accepted, deliberately
                // unprocessed delivery until the finalize lands
                log.debug("room_finished of room <{}> parked for the finalize (T055)", event.roomName)
                count(event, WebhookResult.IGNORED)
            }
            is ConferenceWebhookEvent.Unhandled -> {
                log.debug("an unhandled webhook event of type <{}> was ignored", event.eventType)
                count(event, WebhookResult.IGNORED)
            }
        }
    }

    /**
     * `participant_joined` (livekit-integration.md §5): the
     * conditional `created→active` — the WINNER is the once-anchor of
     * the SSE `conference.started` — plus the `last_joined_at` mark
     * and the actual-join counter. A room this backend never created
     * (a foreign or malformed name) is the tagged no-op.
     */
    private fun onParticipantJoined(event: ConferenceWebhookEvent.ParticipantJoined) {
        val conferenceId = Conference.conferenceIdOfRoomName(event.roomName)
        if (conferenceId == null) {
            log.debug("participant_joined of a non-conference room <{}> was ignored", event.roomName)
            count(event, WebhookResult.IGNORED)
            return
        }
        val at = clock.now()
        val principal = ConferenceIdentity.parsePrincipal(event.identity)
        val outcome =
            participantEvent.execute {
                // the conditional UPDATE is the once-anchor: exactly
                // one winner per conference, everyone else reads null
                val started =
                    conferences.activate(conferenceId, at)?.let { active ->
                        StartedAnnouncement(
                            addressees = membership.activeMemberIds(active.chatId),
                            event =
                                ConferenceStartedEvent(
                                    conferenceId = active.id,
                                    chatId = active.chatId,
                                    startedAt = active.startedAt ?: at,
                                    mode = modeOf(active),
                                ),
                        )
                    }
                val joined = principal != null && participants.markJoined(conferenceId, principal.value, at)
                ParticipantEventOutcome(startedAnnouncement = started, joinCounted = joined)
            } ?: error("the participant_joined transaction must return its outcome")

        // strictly post-commit of the winning transition (the template
        // has returned): the frame fires EXACTLY once — the losing
        // duplicates and the post-`room_finished` stragglers carry a
        // null announcement (constitution III, invariant 5)
        outcome.startedAnnouncement?.let { announceStarted(it) }
        // an ACTUAL join of a known principal — member or guest by the
        // identity prefix; a rowcount-0 mark never counts
        if (outcome.joinCounted && principal != null) {
            metrics.countJoin(joinKindOf(principal))
        }
        count(event, WebhookResult.PROCESSED)
    }

    /**
     * `participant_left`: the `last_left_at` mark on the principal
     * row — nothing else. The empty-room close is the plane's
     * `departure_timeout` business and surfaces as `room_finished`
     * (T055), never here.
     */
    private fun onParticipantLeft(event: ConferenceWebhookEvent.ParticipantLeft) {
        val conferenceId = Conference.conferenceIdOfRoomName(event.roomName)
        val principal = ConferenceIdentity.parsePrincipal(event.identity)
        if (conferenceId == null || principal == null) {
            log.debug("participant_left of room <{}> with an unresolvable target was ignored", event.roomName)
            count(event, WebhookResult.IGNORED)
            return
        }
        participants.markLeft(conferenceId, principal.value, clock.now())
        count(event, WebhookResult.PROCESSED)
    }

    /**
     * The post-commit `conference.started` leg (US5-1/SC-006): the
     * once-anchored frame to the snapshot roster plus the winning
     * `created→active` counter. At-most-once isolation — the
     * transition is already durable, a lost frame converges via the
     * №45 `status` refetch (FR-013); the warn log carries ids only
     * (constitution V).
     */
    @Suppress("TooGenericExceptionCaught") // at-most-once isolation, the SystemMessageService post-commit defense
    private fun announceStarted(announcement: StartedAnnouncement) {
        metrics.countConferenceStarted()
        try {
            events.fanoutConferenceStarted(announcement.addressees, announcement.event)
        } catch (failure: Exception) {
            log.warn(
                "realtime fan-out of conference.started of conference <{}> to <{}> addressees failed; " +
                    "the transition is durable and clients converge on the №45 refetch (FR-013): {}",
                announcement.event.conferenceId,
                announcement.addressees.size,
                failure.message,
            )
        }
    }

    /**
     * The SSE payload mode of the started conference — the invite
     * widget projection ([ConferenceModeReader]); `null` is the broken
     * T017 create-transaction invariant (the widget rides the create
     * transaction) and fails LOUD inside the transition, rolling it
     * back — the [ConferenceController.resolveMode] precedent, never
     * a silent guess.
     */
    private fun modeOf(active: Conference): ConferenceMode =
        inviteModes.modeOf(active.id, active.chatId)
            ?: error(
                "conference ${active.id} has no invite widget in chat ${active.chatId} " +
                    "(the T017 create-transaction invariant)",
            )

    private fun joinKindOf(principal: ConferenceIdentity): JoinKind =
        when (principal) {
            is ConferenceIdentity.Member -> JoinKind.MEMBER
            is ConferenceIdentity.Guest -> JoinKind.GUEST
        }

    private fun count(
        event: ConferenceWebhookEvent,
        result: WebhookResult,
    ) {
        metrics.countWebhookProcessed(tagOf(event), result)
    }

    /** The bounded `event` tag of the intake vocabulary (T053/T054), exhaustive over the sealed set. */
    private fun tagOf(event: ConferenceWebhookEvent): WebhookEvent =
        when (event) {
            is ConferenceWebhookEvent.RoomStarted -> WebhookEvent.ROOM_STARTED
            is ConferenceWebhookEvent.ParticipantJoined -> WebhookEvent.PARTICIPANT_JOINED
            is ConferenceWebhookEvent.ParticipantLeft -> WebhookEvent.PARTICIPANT_LEFT
            is ConferenceWebhookEvent.RoomFinished -> WebhookEvent.ROOM_FINISHED
            is ConferenceWebhookEvent.Unhandled -> WebhookEvent.OTHER
        }

    /** The post-commit legs of a WON `created→active`: the snapshot roster and the ready frame. */
    private data class StartedAnnouncement(
        val addressees: List<UUID>,
        val event: ConferenceStartedEvent,
    )

    /** The committed outcome of one participant webhook transaction. */
    private data class ParticipantEventOutcome(
        val startedAnnouncement: StartedAnnouncement?,
        val joinCounted: Boolean,
    )
}
