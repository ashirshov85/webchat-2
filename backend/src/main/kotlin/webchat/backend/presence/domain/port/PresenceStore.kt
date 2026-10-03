package webchat.backend.presence.domain.port

import java.time.Instant
import java.util.UUID

/**
 * One live SSE-connection record (data-model 007 §1.1): a registration
 * is the ONLY proof of life — «online» ⟺ ≥1 non-expired registration of
 * the user (FR-001, multi-device: any session counts). [expiresAt] is
 * the self-expiry horizon (the score of the `presence:alive:{userId}`
 * ZSET, §3): every №37 heartbeat pushes it to `now + TTL` (90 s,
 * research §G), silence lets it lapse — the watch poller reaps it and
 * the status converges to «offline» within the SC-003 budget. [sessionId]
 * ties the registration to the 002 session of its access token, so the
 * per-session logout clears exactly its own registrations (T029, edge
 * «мультидевайс-logout»). Nothing here is ever persisted to PG (FR-001
 * forbids presence history).
 */
data class PresenceRegistration(
    val connectionId: UUID,
    val userId: UUID,
    val sessionId: UUID,
    val expiresAt: Instant,
    val createdAt: Instant,
)

/**
 * The published presence value (data-model 007 §1.2/§3): the LAST status
 * the audience was allowed to observe — the CAS-guarded
 * `presence:pub:{userId}` key. A MISSING key reads as [OFFLINE] (the CAS
 * nil→online of the very first transition is valid), so a user unknown
 * to the store is simply «offline», never an error.
 */
enum class PresencePublishedStatus {
    ONLINE,
    OFFLINE,
}

/**
 * The №36 snapshot projection of one user (contracts/presence-api.md
 * §1): the published status — hysteresis-consistent with the events, so
 * inside the pending-offline window it still reads [ONLINE] (FR-004) —
 * plus the monotone [rev] of that publication. `unknown` is NOT a store
 * value: the per-pair visibility policy (`VisibilityAudienceReader`,
 * data-model §1.4) decides it in the service BEFORE this read is issued.
 */
data class PublishedPresence(
    val userId: UUID,
    val status: PresencePublishedStatus,
    val rev: Long,
)

/**
 * The outcome of a published-status transition (data-model 007 §1.2):
 * every leg of the state machine moves through an atomic compare-and-set
 * of `presence:pub:{userId}` (+ INCR `presence:rev`, the §3 online
 * counter and the offq mutations inside the SAME Lua script — T008), so
 * competing instances and pollers serialize safely and a repeat
 * execution is always a no-op (FR-003/FR-005).
 */
sealed interface PresenceTransition {
    val userId: UUID
    val publishedStatus: PresencePublishedStatus
    val rev: Long

    /**
     * The CAS flipped the published status — the caller MUST fan out
     * `presence.updated {userId,status,rev}` to the visibility audience
     * (contracts/presence-events.md §2; SC-001 «online» ≤ 2 s). This is
     * the ONLY branch that ever publishes.
     */
    data class Switched(
        override val userId: UUID,
        override val publishedStatus: PresencePublishedStatus,
        override val rev: Long,
    ) : PresenceTransition

    /**
     * The CAS did not apply — the published status already equalled the
     * target, so NO event may follow (echo suppression, research §B1).
     * [rev] is the CURRENT revision: status transitions advance it only
     * on a flip, the «невидимка» policy transitions advance it
     * unconditionally (T033) — the strictly-greater client merge stays
     * correct either way (research §C2).
     */
    data class Unchanged(
        override val userId: UUID,
        override val publishedStatus: PresencePublishedStatus,
        override val rev: Long,
    ) : PresenceTransition
}

/**
 * The outcome of executing ONE due `presence:offq` entry (T025): the
 * offq poller fetches candidates cheaply, then hands each over to this
 * atomic decision — multi-instance competition on the same candidate
 * resolves to exactly one of the three outcomes (FR-005).
 */
sealed interface OfflineDueOutcome {
    /**
     * A live registration returned inside the hysteresis window — the
     * entry is cancelled and NOTHING is published; the caller increments
     * `webchat_presence_hysteresis_suppressed_total` (FR-009, SC-002
     * «метро»: a series of gaps shorter than the window yields zero
     * observed switches).
     */
    data object Suppressed : OfflineDueOutcome

    /**
     * The CAS online→offline applied with the new [rev] — the caller
     * publishes `presence.updated {status=offline}` to the audience.
     */
    data class WentOffline(
        val rev: Long,
    ) : OfflineDueOutcome

    /**
     * The published status was already `offline` (a frozen «невидимка»
     * or a concurrent instance won the CAS) — a repeat is a no-op
     * without an event (data-model §1.2 invariant: a break longer than
     * the window yields exactly ONE «offline»).
     */
    data object AlreadyOffline : OfflineDueOutcome
}

/**
 * The ephemeral-presence storage port (data-model 007 §3; DIP: the Redis
 * adapter lives outside the domain in
 * `webchat.backend.presence.repository.RedisPresenceStore`, T008) — the
 * whole live state of the feature: registration ZSETs with expiry
 * scores, the watch/offq trigger queues, the published status, the
 * monotone revision and the online counter. Instances stay stateless
 * (constitution II): every mutation is ONE atomic Redis operation (a Lua
 * CAS — T008), so SSE connections of the same user may live on different
 * pods and the pollers of all instances may compete idempotently
 * (FR-005). Reads lazily evict expired members (ZREMRANGEBYSCORE −inf..now,
 * §3) — an expired registration never counts as alive (FR-002).
 */
@Suppress("TooManyFunctions") // the §1.2 state machine legs + the §3 key families: one atomic leg per operation
interface PresenceStore {
    /**
     * SSE №18 open (T013/T014): atomically adds the registration
     * (ZADD `presence:alive` + the watch score = max(expiresAt)). It
     * NEVER touches the published status itself: the offline→online
     * transition is a SEPARATE [transitionOnlineIfDue] call, so the
     * service can suppress it while the «невидимка» freeze holds
     * (FR-007, T033). A PENDING offq entry is deliberately left in
     * place — the offq poller's due recount (T025) is the branch that
     * cancels it AND counts the suppression (`suppressed++`,
     * data-model §1.2), so an in-window returning device stays
     * observable in `webchat_presence_hysteresis_suppressed_total`
     * (US3 AC4). A reconnect before the old registration expires simply
     * adds a second member — the duplicate never distorts the status
     * (edge «reconnect», FR-003); the orphaned old member lapses by its
     * own score (FR-002).
     */
    fun register(
        userId: UUID,
        sessionId: UUID,
        connectionId: UUID,
    ): PresenceRegistration

    /**
     * №37 heartbeat (T015): atomically extends THIS registration and its
     * watch score to `now + TTL` and renews the pub/rev key TTLs (§3).
     * Returns `false` ⟺ the connectionId is not a LIVE registration of
     * this user (unknown, expired or someone else's) → `404
     * presence_connection_not_found` — the client then reconnects the
     * SSE channel (research §A3/§E2). Never changes the published
     * status.
     */
    fun renewRegistration(
        userId: UUID,
        connectionId: UUID,
    ): Boolean

    /**
     * SSE close/error (T013): atomically removes the registration,
     * recomputes the watch score and — when this was the LAST live
     * registration — schedules the hysteresis publish (`presence:offq`,
     * score = now + 45 s, FR-004, T024). It NEVER publishes «offline»
     * directly: at this moment a tab close is indistinguishable from a
     * metro gap (research §B1/B2). Removing an unknown/expired
     * connectionId is a no-op.
     */
    fun unregister(
        userId: UUID,
        connectionId: UUID,
    )

    /**
     * Logout of ONE session (002 per-session revocation, T029/research
     * §B2): atomically removes every registration carrying the
     * [sessionId]. Live registrations of OTHER sessions remain →
     * [PresenceTransition.Unchanged] (edge «мультидевайс-logout»: the
     * status stays «online»); the removal of the LAST one flips the
     * published status offline IMMEDIATELY, bypassing the hysteresis
     * queue (the session is revoked — a reconnect is impossible, the
     * window is pointless) → [PresenceTransition.Switched].
     */
    fun clearSessionRegistrations(
        userId: UUID,
        sessionId: UUID,
    ): PresenceTransition

    /**
     * FR-001: `true` ⟺ the user has ≥1 non-expired registration (any
     * session/device); expired members are lazily evicted first (§3).
     */
    fun hasAliveRegistration(userId: UUID): Boolean

    /**
     * The first live registration / a revival (T014, state machine §1.2):
     * the immediate CAS nil|offline→online — rev++ and the online-counter
     * bump happen ONLY on the actual flip (debounce-online = 0, SC-001;
     * a repeat — the second device — is a no-op, FR-003 idempotence).
     * [PresenceTransition.Switched] ⟹ the caller publishes
     * `presence.updated {status=online}` to the audience; while the
     * «невидимка» freeze holds, the caller skips this call entirely
     * (T033).
     */
    fun transitionOnlineIfDue(userId: UUID): PresenceTransition

    /**
     * «Невидимка» ON (T033/research §D1): the policy transition — the
     * rev advances UNCONDITIONALLY, the status flips online→offline if
     * needed (EXACTLY one observable switch) and any pending offq entry
     * is cancelled (a bypass like logout: the state is guaranteed,
     * §D2). [PresenceTransition.Switched] ⟹ one final `offline` event;
     * from here on the caller freezes every outgoing transition event
     * and answers snapshots with `offline` (FR-007 неотличимость).
     */
    fun freezePublishedOffline(userId: UUID): PresenceTransition

    /**
     * «Невидимка» OFF (T033/research §D1): the policy transition — the
     * rev advances UNCONDITIONALLY and the status moves to the ACTUAL
     * one: a live registration flips offline→online
     * ([PresenceTransition.Switched] → publish `online`), no live
     * registration keeps `offline` ([PresenceTransition.Unchanged] — no
     * event; edge «выключение без живых подключений»: the snapshot part
     * is unchanged, rev does not decrease).
     */
    fun revealPublishedStatus(userId: UUID): PresenceTransition

    /**
     * Schedules the deferred «offline» publish (T024): ZADD
     * `presence:offq` with score = now + hysteresis (45 s, FR-004) — the
     * loss of the last registration NEVER publishes immediately. Used by
     * the unregister/reap legs of the store itself and exposed for the
     * watch poller's recompute (T028); a repeat ZADD overwrites the
     * score (the LATEST loss wins).
     */
    fun scheduleOfflinePublish(userId: UUID)

    /**
     * Cancels the deferred «offline» publish (ZREM `presence:offq`) —
     * the explicit cancel leg of the §3 queue («отменяется
     * восстановлением»); the register Lua cancels internally, so this is
     * the manual/service-level counterpart.
     */
    fun cancelOfflinePublish(userId: UUID)

    /**
     * The offq poller batch (T025): userIds with a due publishAt, at
     * most [limit], oldest first — a FETCH ONLY (no removal): competing
     * instances may return the same candidates, and only
     * [processDueOfflinePublish] decides atomically.
     */
    fun dueOfflinePublishBatch(limit: Int): List<UUID>

    /**
     * The atomic execution of ONE due offq entry (T025): a live
     * registration → cancel ([OfflineDueOutcome.Suppressed]); otherwise
     * the CAS online→offline (+rev, online counter) →
     * [OfflineDueOutcome.WentOffline], and an already-offline CAS (a
     * frozen «невидимка» or a lost race) →
     * [OfflineDueOutcome.AlreadyOffline]. Idempotent under multi-instance
     * poller competition (FR-005).
     */
    fun processDueOfflinePublish(userId: UUID): OfflineDueOutcome

    /**
     * The watch poller batch (T028/research §A2): userIds whose maximal
     * registration expiry has lapsed, at most [limit] — a FETCH ONLY;
     * the entries stay until [reapExpiredRegistrations] (or a
     * register/renew) moves them.
     */
    fun dueWatchBatch(limit: Int): List<UUID>

    /**
     * The silent-expiry reap (T028, FR-002 active cleanup beside the
     * lazy eviction of every read): atomically removes the EXPIRED
     * members from `presence:alive:{userId}` and, when NO live
     * registration remains, schedules the hysteresis publish
     * ([scheduleOfflinePublish]). Returns `true` ⟺ the reap emptied the
     * user into the offq.
     */
    fun reapExpiredRegistrations(userId: UUID): Boolean

    /**
     * The №36 batch-snapshot read leg (T016): the published status+rev
     * of every DISTINCT requested user in one round trip (MGET-shaped;
     * the ≤200 cap and the dedup are the API layer's contract
     * validation, contracts/presence-api.md §1). A never-published user
     * reads [PresencePublishedStatus.OFFLINE] with rev = 0 — the
     * epoch-initialized rev (§3: nil → now_ms) keeps the client's
     * strictly-greater merge correct across «epochs» (research §C2).
     */
    fun readPublishedBatch(userIds: Collection<UUID>): Map<UUID, PublishedPresence>

    /**
     * FR-009/SC-008: the atomically maintained `presence:online:count`
     * (§3) — the source of the `webchat_presence_online_users` gauge;
     * counts PUBLISHED online users, not raw registrations (research §F).
     */
    fun readOnlineUsersCount(): Long
}
