package webchat.backend.presence.domain

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import webchat.backend.presence.domain.port.PresenceEventPublisher
import webchat.backend.presence.domain.port.PresencePublishedStatus
import webchat.backend.presence.domain.port.PresenceStore
import webchat.backend.presence.domain.port.PresenceTransition
import webchat.backend.presence.domain.port.PresenceUpdatedEvent
import webchat.backend.presence.domain.port.PublishedPresence
import webchat.backend.presence.domain.port.VisibilityAudienceReader
import webchat.backend.realtime.PresenceConnectionLifecycle
import java.util.UUID

/**
 * T014 (tasks.md 007, US1): the §1.2 state-machine owner of the 007
 * presence — the domain service the T013 realtime seam late-binds as its
 * [PresenceConnectionLifecycle] bean. Every leg is a DELEGATION to one
 * atomic [PresenceStore] operation (the T008 Lua CAS legs — the service
 * itself stays stateless and holds no connection map: constitution II,
 * the SSE connections of one user may ride different pods and any
 * instance may serve any hook, FR-005).
 *
 *  * open ([onConnectionOpened]): [PresenceStore.register] persists the
 *    registration — carrying the `sid` session of the access token that
 *    opened the stream (data-model 007 §1.1, minted and passed by T013;
 *    the per-session logout of T029 clears exactly these later) — and
 *    the SEPARATE [PresenceStore.transitionOnlineIfDue] flips
 *    nil|offline→online on the actual first registration only: the
 *    immediate CAS + rev++ + the offq cancel (debounce-online = 0,
 *    data-model §1.2) — the second device of an already-online user
 *    resolves [PresenceTransition.Unchanged] and publishes NOTHING
 *    (SC-005 multi-device: exactly one `online` per transition). An
 *    in-window revival deliberately leaves a PENDING offq entry to the
 *    offq poller's due recount (T025) — that branch both cancels it and
 *    counts the suppression (US3 AC4).
 *    Splitting the two store calls (and not folding the transition into
 *    the register Lua) is what lets T033 skip the transition entirely
 *    while the «невидимка» freeze holds (FR-007).
 *  * close ([onConnectionClosed]): [PresenceStore.unregister] ONLY — the
 *    loss of the last live registration NEVER publishes here: at this
 *    moment a tab close is indistinguishable from a metro gap, so the
 *    same atomic leg merely ZADDs the `presence:offq` hysteresis entry
 *    (45 s, FR-004/T024) and the offq poller (T025) decides the CAS.
 *    A repeated or expired connectionId is a no-op by the port contract
 *    (T013 may fire the callbacks twice; FR-002/FR-003 idempotence).
 *  * №37 ([heartbeat]): [PresenceStore.renewRegistration] — the atomic
 *    alive+watch extend to `now + TTL`; `false` ⟺ the connectionId is
 *    not a LIVE registration of this user, which T015 answers with `404
 *    presence_connection_not_found` so the client reconnects the №18
 *    stream (a revival is the register leg of the reconnect, never the
 *    beat — presence-api.md §2). The renew never touches the published
 *    status (a kept-alive registration must not advance the rev).
 *
 * The publication leg: on the ACTUAL flip only, the visibility audience
 * is resolved LIVE through [VisibilityAudienceReader] (data-model §1.4 —
 * one SQL, no cache: a contact added or a group kick converges by the
 * very next transition) and handed with the [PresenceUpdatedEvent] to
 * the [PresenceEventPublisher] fan-out — ONE `rt:user:{observerId}`
 * frame per observer regardless of how many chats they share with the
 * subject (edge «до 200 участников», contracts/presence-events.md §2).
 * The publisher rides the ObjectProvider seam exactly like the
 * RealtimeController side of T013: presence and realtime stay loosely
 * coupled through the port (Spring wires whatever bean implements it —
 * since T017 the Redis adapter
 * `webchat.backend.presence.repository.RedisPresenceEventPublisher`),
 * and an absent/unresolvable publisher degrades gracefully — the
 * transition keeps converging in the store, only the frame is absent,
 * and clients heal through the №36 snapshot by max(rev) anyway
 * (at-most-once channel, constitution III).
 * A Switched leg with an EMPTY audience (a subject nobody may observe
 * yet) publishes nothing — set semantics make that structural.
 *
 * Failure etiquette (SC-001 ≤ 2 s): the open leg runs on the SSE
 * handshake thread, so every store call is ONE round trip and the
 * fan-out is a best-effort publish — the CAS has ALREADY made the new
 * status and rev durable when the frames go out, a lost frame is never
 * re-sent and must not fail the caller (the T013 hook guard converts a
 * degraded store into a warn with ids only — constitution V; the
 * registration self-heals via TTL and the №37-404 → reconnect path,
 * FR-002).
 */
@Service
class PresenceService(
    private val presenceStore: PresenceStore,
    private val visibilityAudienceReader: VisibilityAudienceReader,
    private val presenceEventPublisher: ObjectProvider<PresenceEventPublisher>,
) : PresenceConnectionLifecycle {
    /**
     * SSE №18 open (T013 → data-model 007 §1.1/§1.2): register the
     * connection — [sessionId] is the `sid` claim of the access token
     * that opened the stream — then the immediate offline→online CAS of
     * the FIRST live registration with the audience fan-out on the
     * actual flip (debounce-online = 0, SC-001; a repeat is a silent
     * no-op, FR-003).
     */
    override fun onConnectionOpened(
        userId: UUID,
        sessionId: UUID,
        connectionId: UUID,
    ) {
        presenceStore.register(userId, sessionId, connectionId)
        publishIfSwitched(presenceStore.transitionOnlineIfDue(userId))
    }

    /**
     * SSE №18 close/error/completion (T013): the unregister leg — NEVER a
     * publish; the hysteresis entry of a last-registration loss is
     * scheduled atomically inside the store (FR-004/T024) and the offq
     * poller (T025) owns the deferred CAS. Idempotent by the port
     * contract for a repeat or an already-expired connectionId.
     */
    override fun onConnectionClosed(
        userId: UUID,
        connectionId: UUID,
    ) {
        presenceStore.unregister(userId, connectionId)
    }

    /**
     * №37 heartbeat (T015): the atomic alive+watch renewal to
     * `now + TTL`. Returns `false` ⟺ the connectionId is unknown,
     * expired or someone else's → the caller answers `404
     * presence_connection_not_found`; `true` is the 204 — the published
     * status and rev stay exactly as they were.
     */
    fun heartbeat(
        userId: UUID,
        connectionId: UUID,
    ): Boolean = presenceStore.renewRegistration(userId, connectionId)

    /**
     * №36 batch snapshot (T016, contracts/presence-api.md §1): the
     * per-pair visibility policy FIRST — [VisibilityAudienceReader.
     * visibleTargets] decides which of the DEDUPED [observerId]-supplied
     * [targetIds] may be answered at all — and only THEN one
     * [PresenceStore.readPublishedBatch] round trip over the visible
     * subset (data-model §1.4: an event and a snapshot resolve through
     * the SAME policy, so they can never disagree about who observes
     * whom — SC-004). Every requested target gets an entry:
     *  * visible → the PUBLISHED status + rev (`presence:pub` — the
     *    hysteresis-consistent value, so inside the pending-offline
     *    window the snapshot still reads [ONLINE] exactly like the last
     *    event did, FR-004; a never-published user reads
     *    [OFFLINE]/rev 0 — the store contract);
     *  * the complement (an outsider, a block-pair, a nonexistent
     *    userId, the observer themself) → [UNKNOWN] with rev 0 — the
     *    INDISTINGUISHABLE «no access» answer that never reveals
     *    existence, offline-ness or «невидимка» (FR-007, US4 AC4); rev 0
     *    makes the client's strictly-greater max(rev) merge a structural
     *    no-op, so an `unknown` item can never overwrite a live state.
     *
     * [targetIds] arrives deduped and ≤ 200 — the №36 contract
     * validation is the API layer's business (presence-api.md §1);
     * duplicates/self-entries stay harmless anyway (set semantics).
     */
    fun snapshot(
        observerId: UUID,
        targetIds: Collection<UUID>,
    ): Map<UUID, PresenceSnapshotEntry> {
        val distinct = targetIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val visible = visibilityAudienceReader.visibleTargets(observerId, distinct)
        val published: Map<UUID, PublishedPresence> =
            if (visible.isEmpty()) {
                emptyMap()
            } else {
                presenceStore.readPublishedBatch(visible)
            }
        return distinct.associateWith { target ->
            published[target]?.let { PresenceSnapshotEntry.of(it) } ?: PresenceSnapshotEntry.unknown(target)
        }
    }

    /**
     * The §1.2 publication rule: ONLY a [PresenceTransition.Switched]
     * outcome ever fans out — [PresenceTransition.Unchanged] is the echo
     * the CAS suppressed (the second device, a repeat hook), and an
     * Unchanged leg must stay unobservable (FR-003). The T017 adapter
     * absence (pre-US1-interim) and an empty audience both stop BEFORE
     * the SQL/publish legs: nothing to observe means nothing to resolve.
     */
    private fun publishIfSwitched(transition: PresenceTransition) {
        val publisher = presenceEventPublisher.ifAvailable
        if (transition is PresenceTransition.Switched && publisher != null) {
            val audience = visibilityAudienceReader.audienceOf(transition.userId)
            if (audience.isNotEmpty()) {
                publisher.fanoutPresenceUpdated(
                    audience,
                    PresenceUpdatedEvent(
                        userId = transition.userId,
                        status = transition.publishedStatus,
                        rev = transition.rev,
                    ),
                )
            }
        }
    }
}

/**
 * The №36 answer for one target (T016): the API-level status triple
 * online/offline/unknown of contracts/presence-api.md §1 — [UNKNOWN] is
 * NOT a store value, it is the visibility policy's «no access» answer
 * decided in [PresenceService.snapshot] BEFORE any store read (FR-007:
 * indistinguishable for an outsider, a block-pair and a nonexistent
 * userId alike).
 */
enum class PresenceSnapshotStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN,
}

/**
 * One №36 item (contracts/presence-api.md §1): the status the observer
 * may see plus the revision of that publication — the client merges it
 * with №18 `presence.updated` frames by strictly-greater `rev` per user
 * (FR-003); an [PresenceSnapshotStatus.UNKNOWN] entry carries rev 0, so
 * it never overwrites a previously observed state.
 */
data class PresenceSnapshotEntry(
    val userId: UUID,
    val status: PresenceSnapshotStatus,
    val rev: Long,
) {
    internal companion object {
        /** The visible projection: the published status + its very rev (SC-006). */
        fun of(published: PublishedPresence): PresenceSnapshotEntry =
            PresenceSnapshotEntry(
                userId = published.userId,
                status =
                    when (published.status) {
                        PresencePublishedStatus.ONLINE -> PresenceSnapshotStatus.ONLINE
                        PresencePublishedStatus.OFFLINE -> PresenceSnapshotStatus.OFFLINE
                    },
                rev = published.rev,
            )

        /** The «no access» projection: unknown + rev 0 (a structural merge no-op). */
        fun unknown(userId: UUID): PresenceSnapshotEntry = PresenceSnapshotEntry(userId, PresenceSnapshotStatus.UNKNOWN, 0L)
    }
}
