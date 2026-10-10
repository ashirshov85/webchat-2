package webchat.backend.presence.domain

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import webchat.backend.auth.domain.port.PresenceLogoutListener
import webchat.backend.presence.domain.port.PresenceEventPublisher
import webchat.backend.presence.domain.port.PresencePublishedStatus
import webchat.backend.presence.domain.port.PresenceSettingsStore
import webchat.backend.presence.domain.port.PresenceStore
import webchat.backend.presence.domain.port.PresenceTransition
import webchat.backend.presence.domain.port.PresenceUpdatedEvent
import webchat.backend.presence.domain.port.PublishedPresence
import webchat.backend.presence.domain.port.VisibilityAudienceReader
import webchat.backend.realtime.PresenceConnectionLifecycle
import java.time.Instant
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
 *    the register Lua) is what lets this service skip the transition
 *    entirely while the «невидимка» freeze holds (T033/FR-007): an
 *    incognito subject still REGISTERS — the №37 leg keeps answering
 *    204 and the later reveal knows the actual status — but the open
 *    hook never arms the online publication.
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
 *  * №7 logout ([onSessionLoggedOut], T029): [PresenceStore.
 *    clearSessionRegistrations] removes EXACTLY the revoked session's
 *    registrations (the `sid` every registration carries since T013) —
 *    the one leg allowed to bypass the hysteresis queue: a surviving
 *    parallel session resolves [PresenceTransition.Unchanged] (no event,
 *    edge «мультидевайс-logout»), the removal of the LAST registration
 *    flips offline IMMEDIATELY inside the very logout request (FR-004's
 *    single logout exception — a revoked session cannot return, the
 *    window is pointless; research §B2). The still-mounted №18 sockets
 *    of the logged-out session converge naturally: their №37 turns 404
 *    and the reconnect hits the 002-denylisted token — no revival path
 *    exists.
 *  * №38 settings ([presenceSettings]/[updatePresenceSettings], T032):
 *    the durable «невидимка» mode of data-model §1.5 — a pure V15
 *    `users.presence_hidden` round trip through [PresenceSettingsStore]
 *    whose conditional write resolves the IDEMPOTENT PUT of
 *    presence-api.md §3 (a repeat of the same value is a no-op without
 *    events and without a rev advance). The SWITCH semantics layered
 *    onto the change verdict are the T033 legs: a real flip to `true`
 *    runs the store's freeze transition — the immediate CAS→offline
 *    (rev++ unconditionally, the offq bypass, EXACTLY one observable
 *    switch) plus the LAST `offline` event the audience receives while
 *    the mode holds; a real flip to `false` runs the reveal — the
 *    published status moves to the ACTUAL one (rev++; a live
 *    registration publishes `online`, no registration keeps `offline`
 *    silently — edge «выключение без живых подключений»). The №36
 *    indistinguishability needs NO extra leg: the freeze already
 *    forced `presence:pub` to `offline`, so the snapshot answers the
 *    frozen target exactly like a real offline (FR-007, research
 *    §D1/§D2).
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
 * T044 (tasks.md 008a, US3; research 008a §B2): the lastSeen DISCLOSURE
 * filter — the conjunction `offline ∧ audience ∧ not «невидимка» ∧ the
 * stamp exists` — lives HERE, never in the store: every offline surface
 * (the №36 item of [snapshot] and the offline `presence.updated` of
 * [publishIfSwitched]) resolves through the same verdict so the two can
 * never disagree (SC-004 discipline), and the deferred offq-drain
 * publication of the T025 scheduler reuses [discloseOfflineLastSeen]
 * verbatim. Every rejection is the ABSENT field — «скрыто» ≡ «нет
 * данных» on the wire (SC-002, FR-010).
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
    private val presenceSettingsStore: PresenceSettingsStore,
    private val presenceEventPublisher: ObjectProvider<PresenceEventPublisher>,
) : PresenceConnectionLifecycle,
    PresenceLogoutListener {
    /**
     * SSE №18 open (T013 → data-model 007 §1.1/§1.2): register the
     * connection — [sessionId] is the `sid` claim of the access token
     * that opened the stream — then the immediate offline→online CAS of
     * the FIRST live registration with the audience fan-out on the
     * actual flip (debounce-online = 0, SC-001; a repeat is a silent
     * no-op, FR-003).
     *
     * T033 freeze (FR-007): while the «невидимка» mode holds, the
     * registration STILL lands — the №37 leg of that connection keeps
     * answering 204 and a later №38 disable must find the actual
     * «online» to reveal — but the transition is skipped ENTIRELY: no
     * CAS, no rev advance, no `online` ever reaches the audience
     * (research §D1 «заморозка»). The mode consults the durable V15
     * verdict per open: the value is per-USER and survives sessions, so
     * a fresh login of an incognito account stays frozen from its very
     * first frame (US4 AC5, the T031 relogin leg).
     */
    override fun onConnectionOpened(
        userId: UUID,
        sessionId: UUID,
        connectionId: UUID,
    ) {
        presenceStore.register(userId, sessionId, connectionId)
        if (presenceSettingsStore.incognitoOf(userId)) return
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
     * №7 logout (T029, research §B2; data-model §1.2 logout leg): clear the
     * revoked session's registrations — [sessionId] is the `sid` of the
     * 002 session every registration of that login carries. The atomic
     * store leg decides: a live registration of ANOTHER session →
     * [PresenceTransition.Unchanged] — nothing is published and the
     * audience keeps the untouched `online`/rev (edge
     * «мультидевайс-logout»); the removal of the LAST one → the immediate
     * CAS online→offline + rev++ + the offq cancel (the BYPASS — no
     * hysteresis window) → the audience fan-out happens right here,
     * inside the logout request (FR-004's single logout exception: the
     * tokens are revoked, a return is impossible, waiting is pointless).
     */
    override fun onSessionLoggedOut(
        userId: UUID,
        sessionId: UUID,
    ) {
        publishIfSwitched(presenceStore.clearSessionRegistrations(userId, sessionId))
    }

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
     * T044 (008a US3, research 008a §B2): the visible OFFLINE entries
     * additionally run through the lastSeen DISCLOSURE conjunction —
     * `lastSeenAt` rides the item ONLY when status=offline ∧ the target
     * is NOT «невидимка» (ONE batch `presence_hidden` read over the
     * candidates that actually have a stamp — an ONLINE item or a
     * no-stamp «нет данных» item needs no SQL at all) ∧ the
     * `presence:lastseen:{userId}` stamp exists. Every rejection keeps
     * the field ABSENT, never `null`: the №36 shape of a hidden subject
     * stays byte-identical to a no-data subject («скрыто» ≡ «нет
     * данных», SC-002), and a missing key is the client's neutral «давно»
     * fallback (FR-010/FR-011).
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
        val hidden = hiddenSubjectsOf(published.values)
        return distinct.associateWith { target ->
            published[target]?.let { entry ->
                if (entry.status == PresencePublishedStatus.OFFLINE &&
                    entry.lastSeenAt != null &&
                    target !in hidden
                ) {
                    PresenceSnapshotEntry.of(entry, entry.lastSeenAt)
                } else {
                    PresenceSnapshotEntry.of(entry)
                }
            } ?: PresenceSnapshotEntry.unknown(target)
        }
    }

    /**
     * №38 GET (T032, contracts/presence-api.md §3): the persisted
     * «невидимка» mode — the V15 `users.presence_hidden` round trip of
     * [PresenceSettingsStore.incognitoOf]. The value is per-USER and
     * survives sessions (US4 AC5): a fresh login of the same account
     * answers the very mode the previous session left.
     */
    fun presenceSettings(userId: UUID): Boolean = presenceSettingsStore.incognitoOf(userId)

    /**
     * №38 PUT (T032, presence-api.md §3): persist the mode
     * atomically-if-changed and answer the STORED value. The
     * conditional V15 write IS the idempotence verdict: a repeat of the
     * very same value writes nothing, publishes no event and advances
     * no rev — the «no-op без событий» of the contract; only an actual
     * flip returns `changed = true`.
     *
     * T033 switch semantics (FR-007, research §D1/§D2) — layered onto
     * the `changed` verdict ALONE, so the idempotent repeat stays
     * completely unobservable (no Lua leg runs at all):
     *  * enable → [PresenceStore.freezePublishedOffline]: the rev
     *    advances UNCONDITIONALLY, the published status CASes
     *    online→offline IMMEDIATELY (the offq bypass — the §B2 logout
     *    discipline: the toggle must not wait out a 45 s window it
     *    already decided) and any pending offq entry is cancelled; a
     *    [PresenceTransition.Switched] verdict fans out the ONE — and
     *    until the disable, the LAST — `offline` the audience observes
     *    (≤ one displayed switch per toggle, spec edge 108); an already
     *    `offline` subject resolves [PresenceTransition.Unchanged] —
     *    zero events, the rev may only grow (spec edge 109).
     *  * disable → [PresenceStore.revealPublishedStatus]: the rev
     *    advances UNCONDITIONALLY and the published status moves to the
     *    ACTUAL one — a live registration flips offline→online
     *    (Switched → the `online` fan-out), no registration keeps
     *    `offline` (Unchanged — NO event; edge «выключение без живых
     *    подключений»: the №36 status part stays untouched and the rev
     *    does not decrease, so the client's max(rev) merge stays
     *    correct while the display stays still).
     * The №36 indistinguishability of the frozen target needs no extra
     * leg: the freeze already forced `presence:pub` to `offline`, so
     * the snapshot reads a value indistinguishable from a real offline.
     */
    fun updatePresenceSettings(
        userId: UUID,
        incognito: Boolean,
    ): PresenceSettingsUpdate {
        val changed = presenceSettingsStore.storeIfChanged(userId, incognito)
        if (changed) {
            publishIfSwitched(
                if (incognito) {
                    presenceStore.freezePublishedOffline(userId)
                } else {
                    presenceStore.revealPublishedStatus(userId)
                },
            )
        }
        val stored = if (changed) incognito else presenceSettingsStore.incognitoOf(userId)
        return PresenceSettingsUpdate(
            incognito = stored,
            changed = changed,
        )
    }

    /**
     * The №38 PUT outcome (T032): the STORED mode (the 200 body of the
     * route) plus the atomic change verdict the T033 switch legs branch
     * on — `changed = false` is the idempotent no-op that must stay
     * COMPLETELY unobservable (no event, no rev advance).
     */
    data class PresenceSettingsUpdate(
        val incognito: Boolean,
        val changed: Boolean,
    )

    /**
     * The §1.2 publication rule: ONLY a [PresenceTransition.Switched]
     * outcome ever fans out — [PresenceTransition.Unchanged] is the echo
     * the CAS suppressed (the second device, a repeat hook), and an
     * Unchanged leg must stay unobservable (FR-003). The T017 adapter
     * absence (pre-US1-interim) and an empty audience both stop BEFORE
     * the SQL/publish legs: nothing to observe means nothing to resolve.
     *
     * T044 (008a US3): an OFFLINE frame additionally carries the
     * disclosed `lastSeenAt` — [discloseOfflineLastSeen] resolves the
     * §B2 conjunction (the audience half is already structural: the
     * frame addresses exactly [VisibilityAudienceReader.audienceOf]).
     * An ONLINE frame never carries the field, and the FREEZE offline
     * resolves it away by the incognito gate («не время включения
     * инкогнито» — the №38 enable persists `presence_hidden` BEFORE the
     * freeze transition runs, so the very filter that protects №36
     * protects the frame).
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
                        lastSeenAt =
                            if (transition.publishedStatus == PresencePublishedStatus.OFFLINE) {
                                discloseOfflineLastSeen(transition.userId)
                            } else {
                                null
                            },
                    ),
                )
            }
        }
    }

    /**
     * The T044 offline disclosure filter (research 008a §B2) — the ONE
     * verdict every offline `presence.updated` leg resolves through,
     * whether it rides THIS service's [publishIfSwitched] (the logout
     * bypass, the №38 freeze/reveal switch) or the deferred offq-drain
     * publication of the T025 scheduler (the graceful close and the
     * TTL-reap «обрыв»). The conjunction, applied in short-circuit
     * order:
     *
     *  1. the subject is NOT «невидимка» — ONE durable V15
     *     [PresenceSettingsStore.incognitoOf] read; the FREEZE leg has
     *     already persisted `presence_hidden = true` before its own
     *     transition runs, so the freeze offline answers `null` here and
     *     goes out BARE (SC-002: the mode hides the time entirely);
     *  2. the `presence:lastseen:{userId}` stamp EXISTS — the very
     *     [PresenceStore.readPublishedBatch] round trip T043 taught to
     *     carry the raw stamp; `null` ⟺ no key, the neutral «нет
     *     данных» of a subject never seen since the feature deployment.
     *
     * The audience and the offline-status halves of the §B2 conjunction
     * are the CALLER's structural facts — every caller of this leg
     * publishes an offline frame addressed to the resolved audience — so
     * the answer is the disclosed stamp or nothing, and `null` keeps the
     * wire field ABSENT (never an explicit `null`): «скрыто» ≡ «нет
     * данных» (SC-002, FR-010).
     */
    fun discloseOfflineLastSeen(subjectId: UUID): Instant? {
        if (presenceSettingsStore.incognitoOf(subjectId)) return null
        return presenceStore.readPublishedBatch(listOf(subjectId))[subjectId]?.lastSeenAt
    }

    /**
     * The №36 incognito half of the T044 conjunction: ONE batch V15 read
     * over the candidates that would otherwise disclose — the visible
     * OFFLINE targets that actually HAVE a stamp (an ONLINE item and a
     * no-stamp item reject before any SQL runs). Returns the subject ids
     * whose mode is `presence_hidden = true`.
     */
    private fun hiddenSubjectsOf(published: Collection<PublishedPresence>): Set<UUID> {
        val candidates =
            published
                .filter { it.status == PresencePublishedStatus.OFFLINE && it.lastSeenAt != null }
                .map { it.userId }
        if (candidates.isEmpty()) return emptySet()
        return presenceSettingsStore.incognitoBatch(candidates).filterValues { it }.keys
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
 *
 * [lastSeenAt] (008a T044, US3): the DISCLOSED stamp — present ONLY on
 * a visible OFFLINE entry of a non-incognito subject that has one; the
 * API layer projects it as the optional wire field, ABSENT whenever
 * `null` (never an explicit `null` — «скрыто» ≡ «нет данных», SC-002).
 */
data class PresenceSnapshotEntry(
    val userId: UUID,
    val status: PresenceSnapshotStatus,
    val rev: Long,
    val lastSeenAt: Instant? = null,
) {
    internal companion object {
        /**
         * The visible projection: the published status + its very rev
         * (SC-006) + the T044-disclosed stamp when the §B2 conjunction
         * admitted one (default `null` — absent).
         */
        fun of(
            published: PublishedPresence,
            lastSeenAt: Instant? = null,
        ): PresenceSnapshotEntry =
            PresenceSnapshotEntry(
                userId = published.userId,
                status =
                    when (published.status) {
                        PresencePublishedStatus.ONLINE -> PresenceSnapshotStatus.ONLINE
                        PresencePublishedStatus.OFFLINE -> PresenceSnapshotStatus.OFFLINE
                    },
                rev = published.rev,
                lastSeenAt = lastSeenAt,
            )

        /** The «no access» projection: unknown + rev 0 (a structural merge no-op). */
        fun unknown(userId: UUID): PresenceSnapshotEntry =
            PresenceSnapshotEntry(userId, PresenceSnapshotStatus.UNKNOWN, 0L)
    }
}
