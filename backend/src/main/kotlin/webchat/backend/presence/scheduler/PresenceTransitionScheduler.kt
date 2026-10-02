package webchat.backend.presence.scheduler

import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import webchat.backend.presence.PresenceMetrics
import webchat.backend.presence.domain.port.OfflineDueOutcome
import webchat.backend.presence.domain.port.PresenceEventPublisher
import webchat.backend.presence.domain.port.PresencePublishedStatus
import webchat.backend.presence.domain.port.PresenceStore
import webchat.backend.presence.domain.port.PresenceUpdatedEvent
import webchat.backend.presence.domain.port.VisibilityAudienceReader
import java.util.UUID

/**
 * T025 (tasks.md 007, US2; FR-004, research.md §B1, data-model 007
 * §1.2/§3): the hysteresis `presence:offq` poller — the deferred-execution
 * half of the anti-flap contract. The T024 planning legs (the
 * unregister/reap loss of the LAST live registration) only ZADD the
 * deferred publish with score = loss + hysteresis (45 s, `presence.
 * hysteresis`); THIS component is the one that ever executes it.
 *
 * Every tick (`presence.poller-interval`, fixed delay: 1 s prod, 200 ms
 * test profile — T003; research.md §G) is a bounded, idempotent drain:
 *  * [PresenceStore.dueOfflinePublishBatch] FETCHES the due userIds
 *    oldest-first WITHOUT removing them (a cheap ZRANGEBYSCORE −inf..now
 *    with LIMIT, data-model §3 — no KEYS/SCAN, research.md §A2), so the
 *    pollers of every stateless replica may compete on the SAME
 *    candidates (constitution II, FR-005);
 *  * [PresenceStore.processDueOfflinePublish] is the ONE atomic Lua
 *    decision per entry — the CAS by `presence:pub` that serializes the
 *    competing instances: a live registration returned inside the window
 *    → cancel ([OfflineDueOutcome.Suppressed] — SC-002 «метро»: zero
 *    observed switches, one `webchat_presence_hysteresis_suppressed_total`
 *    increment per suppressed gap, never silent); the CAS online→offline
 *    applied (+rev, the §3 online counter) →
 *    [OfflineDueOutcome.WentOffline] with the new rev — the ONLY branch
 *    that fans out `presence.updated {status=offline}` to the visibility
 *    audience; the published status already `offline` (a frozen
 *    «невидимка» of T033 or a lost race) → [OfflineDueOutcome.
 *    AlreadyOffline] — a repeat is a structural no-op, which is what
 *    makes «a break longer than the window yields exactly ONE offline»
 *    an invariant rather than a hope (SC-002, data-model §1.2).
 *
 * The publication leg mirrors the T014 discipline of
 * `PresenceService.publishIfSwitched`: the audience is resolved LIVE
 * through [VisibilityAudienceReader] (data-model §1.4 — one SQL, so a
 * contact added or a group kick in the window is honored by the very
 * deferred publish), ONE `rt:user:{observerId}` frame per observer via
 * the [PresenceEventPublisher] seam (the T017 adapter also counts
 * `webchat_presence_events_published_total{status=offline}`), and the
 * ObjectProvider absence degrades gracefully — the CAS has ALREADY made
 * the transition durable, a lost frame is never re-sent (at-most-once,
 * constitution III) and clients converge via the №36 snapshot refetch by
 * max(rev) (FR-003). An empty audience (a subject nobody may observe)
 * publishes nothing — set semantics make that structural.
 *
 * T028 (US3) will add the sibling `presence:watch` silent-expiry poller
 * to this very scheduler file (plan.md Project Structure): the two
 * pollers share the tick cadence and the idempotent-CAS etiquette, but
 * drain their own trigger ZSET.
 */
@Component
class PresenceTransitionScheduler(
    private val presenceStore: PresenceStore,
    private val visibilityAudienceReader: VisibilityAudienceReader,
    private val presenceEventPublisher: ObjectProvider<PresenceEventPublisher>,
    private val presenceMetrics: PresenceMetrics,
) {
    /**
     * One fixed-delay offq drain (T025): a single bounded batch per tick
     * (data-model §3 «LIMIT за тик») — the entries beyond the cap stay
     * due and are claimed by the next tick or by a competing replica, so
     * an instance-crash storm (plan.md §Load: ~50k entries) merely
     * back-pressures into the fleet instead of spiking one tick. A
     * thrown store/publish leg fails the tick loudly and the next tick
     * retries the still-due candidates — the fetch is non-destructive
     * and the per-entry CAS is idempotent either way.
     */
    @Scheduled(fixedDelayString = "\${presence.poller-interval}")
    fun drainDueOfflinePublishes() {
        presenceStore.dueOfflinePublishBatch(DUE_BATCH_LIMIT).forEach { userId ->
            when (val outcome = presenceStore.processDueOfflinePublish(userId)) {
                OfflineDueOutcome.Suppressed -> presenceMetrics.countHysteresisSuppressed()
                is OfflineDueOutcome.WentOffline -> publishOfflineToAudience(userId, outcome.rev)
                OfflineDueOutcome.AlreadyOffline -> Unit
            }
        }
    }

    /**
     * The publication leg of the executed CAS (SC-001's deferred half):
     * resolve the audience LIVE and hand ONE `offline` frame per observer
     * to the publisher — the transition itself is already durable, so a
     * degraded publisher must not (and cannot) roll anything back.
     */
    private fun publishOfflineToAudience(
        userId: UUID,
        rev: Long,
    ) {
        val publisher = presenceEventPublisher.ifAvailable ?: return
        val audience = visibilityAudienceReader.audienceOf(userId)
        if (audience.isEmpty()) return
        publisher.fanoutPresenceUpdated(
            audience,
            PresenceUpdatedEvent(
                userId = userId,
                status = PresencePublishedStatus.OFFLINE,
                rev = rev,
            ),
        )
    }

    private companion object {
        /**
         * The per-tick ZRANGEBYSCORE cap (data-model 007 §3): 1k entries
         * per second per instance — the §Load storm drains across the
         * replica fleet in seconds while every candidate leg stays one
         * cheap atomic Lua call.
         */
        const val DUE_BATCH_LIMIT = 1_000
    }
}
