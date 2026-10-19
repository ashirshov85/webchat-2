package webchat.backend.conferences.scheduler

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.service.ConferenceLifecycleService
import webchat.backend.config.ConferencesProperties
import java.time.Instant
import java.util.UUID

/**
 * T056 (tasks.md 009, US5; FR-015/Q16=B, SC-007, research.md §7) — the
 * TIME half of the conference lifecycle: the max-duration ladder and
 * the sweeper, one fixed-delay tick every `conferences.sweep-interval`
 * (60 s, the `PresenceTransitionScheduler` fixed-delay precedent —
 * sub-minute precision is beyond every budget the feature states):
 *
 *  * **max-duration (Q16=B)** — every `active` conference carries the
 *    horizon `started_at + conferences.max-duration` (4 h). Inside the
 *    last `conferences.warning-before-end` (5 min) the tick sends the
 *    ONE warning: an admin `SendData` packet, topic
 *    `conference.warning`, payload `{"type":"conference.warning",
 *    "endsAt":…}`, to EVERY participant of the room — members AND
 *    guests alike (livekit-integration.md §4; a separate SSE type is
 *    deliberately NOT introduced — the №18 contract stays exactly two
 *    events, and the guests without SSE are the packet's whole point).
 *    At the horizon the tick ends the conference the CONTROLLed way —
 *    the №48 order verbatim ([ModerationService.end]): the finalize
 *    pre-record (`max_duration`) BEFORE `DeleteRoom`, so the
 *    `room_finished` webhook the deletion fires re-enters
 *    [ConferenceLifecycleService.finalize] as a no-op REUSING the
 *    reason (invariant 5) instead of guessing `last_left`;
 *  * **sweeper (SC-007)** — the webhook-loss compensation the
 *    «no guarantees around delivery» of the media plane makes
 *    mandatory, one `ListRooms` scan per tick as the room truth the DB
 *    deliberately mirrors none of (research §4/§7):
 *     * a `created` conference whose room is absent WHILE its
 *       empty-timeout could not have eaten it yet — the crash orphan
 *       between the №44 commit and the post-commit `CreateRoom` — is
 *       re-created with the row's own quota/timers (the self-heal of
 *       research §7; an existing room is updated, never duplicated);
 *     * a `created` conference whose room is absent PAST that border —
 *       «никто не вошёл», the empty timeout ate the eager №44 room and
 *       the webhook was lost — finalizes `room_lost`;
 *     * an `active` conference whose room stays absent longer than
 *       `conferences.room-lost-grace` (120 s ≥ 2× the empty-room
 *       timeout — the grace never races the штатное LiveKit closure of
 *       a drained room) finalizes `room_lost` — a stopped plane, a
 *       lost `room_finished`, an orphaned call: every shape converges
 *       to the one idempotent finalize seat with the roster deletions
 *       and the `conference.ended` SSE riding the winner (T055).
 *
 * **Statelessness** (constitution II): the instance keeps ONLY the two
 * observation folds the ticks feed — the warned-conference set and the
 * first-seen-missing room stamps — both plain in-memory and both GC'd
 * by the live scan every tick; every STATE effect runs through the
 * conditional repository transitions, so racing replicas of the
 * stateless fleet resolve exactly one winner (invariant 5) and a
 * restarted instance simply re-observes (a `room_lost` finalize may
 * arrive one grace later — inside every SC-007 budget). The warning is
 * once-per-INSTANCE by the same folds: an N-replica fleet may deliver
 * the packet up to N times inside the window — at-least-once for a UI
 * toast, the deliberate trade against a schema change the task never
 * asks for; a FAILED packet un-marks the conference and the next tick
 * retries it.
 *
 * **Degradation**: an unreachable plane ([observeRooms] → `null`)
 * skips every observation leg — no re-creation, no `room_lost`, no
 * warning, the participants gauge keeps its last snapshot — but the
 * max-duration horizon is DB truth and its finalize STILL runs: a
 * `DeleteRoom` that cannot land is logged (ids only, constitution V)
 * and converges the №48 way (nobody can re-mint an ended conference;
 * the plane's own timeouts close the drained room). A leg that throws
 * isolates to its conference ([sweepSafely]) — one poisoned row must
 * not starve the rest of the tick, and every leg is idempotent for the
 * next one.
 *
 * Gated on `conferences.scheduler-enabled` (the presence T036
 * scheduler-ownership precedent): production always runs this bean
 * (the conditional defaults to TRUE); the IT profile disables it
 * JVM-wide against the ONE shared media plane (application-test.yml) —
 * a test context needing the real ticks re-enables it as the single
 * owner of the test JVM.
 */
@Component
@Suppress("LongParameterList", "TooManyFunctions") // one port per leg (DIP), one function per §7 duty
@ConditionalOnProperty(
    prefix = "conferences",
    name = ["scheduler-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ConferenceLifecycleScheduler(
    private val conferences: ConferenceRepository,
    private val lifecycle: ConferenceLifecycleService,
    private val liveKit: LiveKitAdminClient,
    private val properties: ConferencesProperties,
    private val metrics: ConferenceMetrics,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ConferenceLifecycleScheduler::class.java)

    /**
     * The warned fold of the max-duration warning: the ids whose packet
     * this instance has ALREADY delivered — the once-per-instance anchor
     * that keeps a 5-minute window from toasting every tick. A failed
     * delivery un-marks the id (the retry leg of the at-least-once
     * trade); the GC of [retainLiveState] drops the ids the live scans
     * no longer return (finalized conferences).
     */
    private val warnedMaxDuration = HashSet<UUID>()

    /**
     * The observation fold of the `room_lost` grace: the FIRST tick this
     * instance saw each live conference's room absent — the grace clock
     * runs on the sweeper's OWN observations (the DB keeps no room
     * truth), so a restarted backend starts a fresh window and a fleet
     * races its windows to the same conditional finalize. Cleared the
     * tick the room is seen again; GC'd with the conference itself.
     */
    private val roomMissingSince = HashMap<UUID, Instant>()

    /**
     * One fixed-delay tick (research §7): scan the two live statuses,
     * observe the plane ONCE, run every leg idempotently, refresh the
     * participants gauge off the same scan (research §13 «снимок
     * sweeper'ом») and GC the observation folds. ENDED rows have no scan
     * consumer (Q18=A) and are never enumerated.
     */
    @Scheduled(fixedDelayString = "\${conferences.sweep-interval}")
    fun sweep() {
        val now = clock.now()
        val active = conferences.findByStatus(ConferenceStatus.ACTIVE)
        val created = conferences.findByStatus(ConferenceStatus.CREATED)
        val rooms = observeRooms()
        active.forEach { conference ->
            sweepSafely(conference) { sweepActive(conference, now, rooms) }
        }
        created.forEach { conference ->
            sweepSafely(conference) { sweepCreated(conference, now, rooms) }
        }
        if (rooms != null) {
            refreshParticipantsConnected(active + created, rooms)
        }
        retainLiveState(active + created)
    }

    /**
     * The `active` ladder of one tick: the max-duration horizon first
     * (DB truth — it runs even against a dead plane), then the
     * observation legs a reachable plane alone can answer: the warning
     * window of a LIVE room, and the `room_lost` grace of an absent
     * one. [rooms] is `null` while the plane is unreachable — only the
     * horizon leg stays armed.
     */
    private fun sweepActive(
        conference: Conference,
        now: Instant,
        rooms: Set<String>?,
    ) {
        val deadline = maxDurationDeadlineOf(conference)
        if (!now.isBefore(deadline)) {
            endAtMaxDuration(conference)
            return
        }
        if (rooms == null) return
        if (conference.roomName in rooms) {
            roomMissingSince.remove(conference.id)
            if (!now.isBefore(deadline.minus(properties.warningBeforeEnd))) {
                warnOnce(conference, deadline)
            }
        } else {
            finalizeRoomLostWhenPastGrace(conference, now)
        }
    }

    /**
     * The `created` ladder of one tick — the self-heal vs the
     * empty-timeout end over the same room absence: YOUNGER than
     * `conferences.empty-room-timeout` the eager №44 room cannot have
     * been eaten yet, so its absence is the crash orphan between the
     * commit and the post-commit `CreateRoom` (research §7) and the
     * tick re-creates it with the row's OWN quota/timers snapshot —
     * the creation values re-asserted verbatim, an existing room
     * updated, never duplicated. PAST that border the empty timeout
     * ate the room («никто не вошёл») and the webhook is the thing
     * that was lost: the compensating finalize is `room_lost`
     * (data-model 009 §Сущность 1). A live plane is the prerequisite
     * of both legs — `null` [rooms] skips them.
     */
    private fun sweepCreated(
        conference: Conference,
        now: Instant,
        rooms: Set<String>?,
    ) {
        if (rooms == null || conference.roomName in rooms) return
        if (now.isBefore(conference.createdAt.plus(properties.emptyRoomTimeout))) {
            liveKit.createRoom(
                name = conference.roomName,
                emptyTimeout = properties.emptyRoomTimeout,
                departureTimeout = properties.departureTimeout,
                maxParticipants = conference.maxParticipants,
            )
            return
        }
        lifecycle.finalize(conference, ConferenceEndReason.ROOM_LOST)
    }

    /**
     * The horizon leg (Q16=B): the №48 order verbatim — the finalize
     * pre-record (`max_duration`) BEFORE `DeleteRoom`, so the webhook
     * of the deletion re-enters [ConferenceLifecycleService.finalize]
     * as the invariant-5 no-op reusing THIS reason, never the
     * `last_left` guess of a reason-less `room_finished`. The race
     * loser (`null` — №48, a webhook, a competing replica won the
     * conditional UPDATE) hands the room legs to the winner and
     * returns; a `DeleteRoom` that cannot land is the committed-row-
     * outranks-the-plane rule: logged (ids only) and converged by the
     * plane's own timeouts — nobody can re-mint an ended conference.
     */
    @Suppress("TooGenericExceptionCaught") // the committed end outranks the room call — see the KDoc above
    private fun endAtMaxDuration(conference: Conference) {
        lifecycle.finalize(conference, ConferenceEndReason.MAX_DURATION) ?: return
        try {
            liveKit.deleteRoom(conference.roomName)
        } catch (failure: Exception) {
            log.warn(
                "DeleteRoom of conference <{}> failed after the max-duration finalize committed; " +
                    "no re-mint is possible and the drained room closes by its empty/departure timeout: {}",
                conference.id,
                failure.message,
            )
        }
    }

    /**
     * The warning leg (livekit-integration.md §4, Q16=B): the ONE
     * `SendData` packet of the window — topic `conference.warning`,
     * payload `{"type":"conference.warning","endsAt":…}` (the horizon
     * itself, ISO-8601 — the toast's «когда»), delivered to EVERY
     * participant of the live room: the connected GUESTS without SSE
     * are the packet's whole reason, the members get it beside their
     * in-call UI. Once per instance per conference; a failed delivery
     * un-marks the id so the next tick retries — at-least-once for a
     * UI toast, never a per-tick spam.
     */
    @Suppress("TooGenericExceptionCaught") // a failed packet un-marks for the next tick; at-least-once for a toast
    private fun warnOnce(
        conference: Conference,
        deadline: Instant,
    ) {
        if (!warnedMaxDuration.add(conference.id)) return
        val payload = """{"type":"$WARNING_TYPE","endsAt":"$deadline"}"""
        try {
            liveKit.sendData(conference.roomName, WARNING_TOPIC, payload)
        } catch (failure: Exception) {
            warnedMaxDuration.remove(conference.id)
            log.warn(
                "the max-duration warning of conference <{}> failed to leave; the next tick retries it: {}",
                conference.id,
                failure.message,
            )
        }
    }

    /**
     * The `room_lost` grace leg (SC-007): the room of an `active`
     * conference has now been absent for a whole
     * `conferences.room-lost-grace` window of THIS instance's
     * observations — the plane is not coming back for this call. The
     * compensating finalize runs through the ONE idempotent seat (the
     * roster deletions and the `conference.ended` SSE ride the
     * winner); the `null` loser is a racing webhook/replica whose end
     * simply stands (invariant 5).
     */
    private fun finalizeRoomLostWhenPastGrace(
        conference: Conference,
        now: Instant,
    ) {
        val missingSince = roomMissingSince.getOrPut(conference.id) { now }
        if (now.isBefore(missingSince.plus(properties.roomLostGrace))) return
        lifecycle.finalize(conference, ConferenceEndReason.ROOM_LOST)
    }

    /**
     * The plane observation of one tick — `null` while unreachable,
     * when every observation leg skips: the documented transient shape
     * ([ConferenceMediaUnavailableException] — retries already burned
     * inside the adapter) at debug, anything louder at warn so a plane
     * misconfiguration stays visible per tick without stalling the
     * DB-truth horizon leg above.
     */
    @Suppress("TooGenericExceptionCaught") // a loud plane failure must not stall the max-duration leg
    private fun observeRooms(): Set<String>? =
        try {
            liveKit.listRoomNames()
        } catch (failure: ConferenceMediaUnavailableException) {
            log.debug(
                "the media plane is unreachable; the sweeper observation legs skip this tick: {}",
                failure.message,
            )
            null
        } catch (failure: Exception) {
            log.warn("listing rooms failed; the sweeper observation legs skip this tick: {}", failure.message)
            null
        }

    /**
     * The participants gauge refresh (research §13 «снимок sweeper'ом»):
     * the room-by-room sum of live connections over the SAME scan —
     * the media truth the DB mirrors none of, sampled into
     * [ConferenceMetrics.updateParticipantsConnected]. A failure aborts
     * the refresh whole (the previous snapshot stands — a gauge, not an
     * invoice); a room that vanished mid-scan counts zero via the
     * adapter's `not_found` no-op.
     */
    @Suppress("TooGenericExceptionCaught") // a stale gauge must not fail the tick it rides
    private fun refreshParticipantsConnected(
        live: List<Conference>,
        rooms: Set<String>,
    ) {
        var connected = 0
        for (conference in live) {
            if (conference.roomName !in rooms) continue
            try {
                connected += liveKit.listParticipants(conference.roomName).size
            } catch (failure: Exception) {
                log.debug("the participants snapshot refresh aborted; the previous value stands: {}", failure.message)
                return
            }
        }
        metrics.updateParticipantsConnected(connected)
    }

    /**
     * The per-conference isolation of one tick leg: a leg that throws
     * (a repository hiccup, a plane gone mid-tick) warns with ids only
     * and leaves the rest of the batch to this very tick — every leg
     * is idempotent, so the next tick retries the poisoned one for
     * free.
     */
    @Suppress("TooGenericExceptionCaught") // one poisoned conference must not starve the tick's batch
    private fun sweepSafely(
        conference: Conference,
        leg: () -> Unit,
    ) {
        try {
            leg()
        } catch (failure: Exception) {
            log.warn(
                "the lifecycle tick leg of conference <{}> failed; the next tick retries it idempotently: {}",
                conference.id,
                failure.message,
            )
        }
    }

    /**
     * The GC of the observation folds: entries the live scans no
     * longer return (a finalized conference left both statuses) leave
     * the instance memory — the folds stay bounded by the live set,
     * never the history (Q18=A).
     */
    private fun retainLiveState(live: List<Conference>) {
        val liveIds = live.mapTo(HashSet()) { it.id }
        warnedMaxDuration.retainAll(liveIds)
        roomMissingSince.keys.retainAll(liveIds)
    }

    /**
     * The max-duration horizon of an `active` conference —
     * `started_at + conferences.max-duration`. A null `started_at` is
     * the broken V18 `ck_conferences_started_active` invariant and
     * fails LOUD (the [ConferenceLifecycleService.eventOf] precedent,
     * never a silent guess).
     */
    private fun maxDurationDeadlineOf(conference: Conference): Instant =
        conference.startedAt?.plus(properties.maxDuration)
            ?: error("an active conference ${conference.id} carries no started_at (the V18 invariant)")

    private companion object {
        /** livekit-integration.md §4: the data-packet topic of the max-duration warning. */
        const val WARNING_TOPIC = "conference.warning"

        /** The payload discriminator the frontend toast of T060 switches on. */
        const val WARNING_TYPE = "conference.warning"
    }
}
