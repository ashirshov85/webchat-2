package webchat.backend.presence.api

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import webchat.backend.config.PresenceProperties
import webchat.backend.config.UserRateLimiter
import webchat.backend.presence.domain.PresenceService
import webchat.backend.presence.domain.PresenceSnapshotEntry
import webchat.backend.presence.domain.PresenceSnapshotStatus
import java.util.UUID

/**
 * The №36/№37 HTTP surface of 007 (contracts/presence-api.md §1–2):
 *
 *  * `GET /api/v1/users/me/presence?userIds=` (№36, T016) — the
 *    batch-snapshot read of the DISPLAYED surfaces (FR-006): the client
 *    supplies comma-separated target UUIDs (≤ 200 after dedup); the
 *    per-pair visibility policy of [PresenceService.snapshot] answers
 *    every target with the PUBLISHED status + rev (hysteresis-consistent
 *    with `presence.updated`, FR-004/SC-006) or the indistinguishable
 *    `unknown` of «no access» (FR-007);
 *  * `POST /api/v1/users/me/presence/heartbeat` (№37, T015) — the
 *    APPLICATION-level presence heartbeat that atomically (one Lua leg of
 *    [webchat.backend.presence.repository.RedisPresenceStore]) extends the
 *    alive AND watch horizons of the registration to `now + 90 s`. It is
 *    the counterpart of the №18 opening frame `connected {connectionId}`
 *    (T013): the client beats with the connectionId it received, keeping
 *    the registration proof-of-life across silence of the SSE stream.
 *
 * Both are thin HTTP adapters (the state machine legs live in
 * [PresenceService], T014): this layer validates the input shape,
 * enforces the per-user flood buckets and projects the contract
 * outcomes — №36 the `200 {items}` projection, №37 `204` (renewed; the
 * published status and rev stay EXACTLY as they were: a kept-alive
 * registration must not advance the rev) and `404
 * presence_connection_not_found` (the connectionId is unknown, expired
 * or someone else's — the client's cue to reconnect the №18 stream
 * immediately; a revival is the register leg of the reconnect, never
 * the beat, presence-api.md §2).
 *
 * The flood gates (№37: the per-user `rl:user:presence-heartbeat:{userId}`
 * bucket of [PresenceProperties.RateLimit.heartbeatsPerWindow]; №36: the
 * `rl:user:presence-snapshot:{userId}` bucket of
 * [PresenceProperties.RateLimit.snapshotsPerMinute], conservative like
 * №26): checked AFTER the shape validation and BEFORE any store/SQL leg
 * — a refused request performs NO read or renewal, exactly like the №19
 * precedent. The №36 `429 flood_limit` + `Retry-After` simply tells the
 * client to refetch later (the snapshot is a pure read; the №18 stream
 * keeps delivering events and the max(rev) merge heals the gap); the №37
 * refusal never tears the №18 stream either (the transport `:ka`
 * keepalive of the channel is orthogonal to №37, and the 90 s TTL gives
 * the recommended 30 s cadence a 3× headroom). The buckets count
 * REQUESTS, not outcomes: every device and replica of the user draws
 * from one bucket (constitution II). Redis unavailability fails open
 * ([UserRateLimiter]).
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as every /users/me route); the caller id is the token
 * `sub` claim. Failures leave as typed exceptions rendered problem+json
 * by [PresenceExceptionHandler]; the warn log carries ids and waits
 * only — never a payload (constitution V).
 */
@RestController
@RequestMapping("/api/v1/users/me/presence")
class PresenceController(
    private val presenceService: PresenceService,
    private val rateLimiter: UserRateLimiter,
    private val presenceProperties: PresenceProperties,
) {
    private val log = LoggerFactory.getLogger(PresenceController::class.java)

    /**
     * №36: `GET ?userIds=uuid1,uuid2,…` → `200 {items}` — one item per
     * DISTINCT requested userId in request order. The `userIds` shape
     * gate comes FIRST (an absent/empty parameter or any non-UUID
     * segment is `400 malformed_request`, then the > 200-after-dedup
     * batch is `400 presence_ids_too_many`, errors.userIds — the openapi
     * №36 400 contract verbatim), the flood bucket NEXT and the
     * visibility+store legs of [PresenceService.snapshot] LAST.
     */
    @GetMapping
    fun snapshot(
        @RequestParam(USER_IDS_PARAM) userIds: String?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): PresenceSnapshotResponse {
        val targets = requireUserIds(userIds)
        val callerId = UUID.fromString(accessToken.subject)
        enforceSnapshotFloodLimit(callerId)
        val entries = presenceService.snapshot(callerId, targets)
        return PresenceSnapshotResponse(items = targets.map { itemOf(it, entries) })
    }

    /**
     * №37: `{connectionId}` → `204`. A missing body, a missing/null
     * [PresenceHeartbeatRequest.connectionId] or a non-UUID value is
     * `400 malformed_request` (errors.connectionId) BEFORE any bucket or
     * store leg — the openapi №37 400 contract verbatim.
     */
    @PostMapping("/heartbeat")
    fun heartbeat(
        @RequestBody(required = false) request: PresenceHeartbeatRequest?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Void> {
        val connectionId = requireConnectionId(request)
        val callerId = UUID.fromString(accessToken.subject)
        enforceHeartbeatFloodLimit(callerId)
        if (!presenceService.heartbeat(callerId, connectionId)) throw HeartbeatConnectionNotFoundException()
        return ResponseEntity.noContent().build()
    }

    /** openapi №36: parse the comma-separated UUID list, dedup preserving request order. */
    private fun requireUserIds(raw: String?): List<UUID> {
        if (raw.isNullOrEmpty()) throw SnapshotMalformedException("userIds must be present and a non-empty UUID list")
        val parsed =
            raw.split(COMMA).map { segment ->
                try {
                    UUID.fromString(segment)
                } catch (_: IllegalArgumentException) {
                    throw SnapshotMalformedException("every userIds segment must be a UUID")
                }
            }
        return requireWithinBatchLimit(parsed.distinct())
    }

    /** openapi №36 400: the batch over [PresenceProperties.snapshotBatchLimit] after dedup is refused whole. */
    private fun requireWithinBatchLimit(distinct: List<UUID>): List<UUID> {
        if (distinct.size > presenceProperties.snapshotBatchLimit) {
            throw PresenceIdsTooManyException(distinct.size)
        }
        return distinct
    }

    /** openapi №36 200: the wire projection of one snapshot entry (the lowercase contract enum). */
    private fun itemOf(
        target: UUID,
        entries: Map<UUID, PresenceSnapshotEntry>,
    ): PresenceStatusItem =
        entries[target]?.let { entry ->
            PresenceStatusItem(
                userId = target,
                status =
                    when (entry.status) {
                        PresenceSnapshotStatus.ONLINE -> STATUS_ONLINE
                        PresenceSnapshotStatus.OFFLINE -> STATUS_OFFLINE
                        PresenceSnapshotStatus.UNKNOWN -> STATUS_UNKNOWN
                    },
                rev = entry.rev,
            )
        } ?: PresenceStatusItem(userId = target, status = STATUS_UNKNOWN, rev = 0L)

    /** openapi №37 400: the body, the field and the UUID shape — anything less is malformed. */
    private fun requireConnectionId(request: PresenceHeartbeatRequest?): UUID {
        if (request?.connectionId == null) throw HeartbeatMalformedException("connectionId is required")
        return try {
            UUID.fromString(request.connectionId)
        } catch (_: IllegalArgumentException) {
            throw HeartbeatMalformedException("connectionId must be a UUID")
        }
    }

    /**
     * presence-api.md §1: one token per snapshot of the per-user bucket
     * `rl:user:presence-snapshot:{userId}` (capacity
     * [PresenceProperties.RateLimit.snapshotsPerMinute] over the 60 s
     * window — conservative like №26/№16/№19) — a refused snapshot
     * performs NO visibility SQL and NO store read. The refusal is the
     * `429 flood_limit` problem+json + `Retry-After` of №36: the client
     * repeats the batch after the advertised wait and the max(rev) merge
     * of FR-003 absorbs the delay.
     */
    private fun enforceSnapshotFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = SNAPSHOT_KEY_FAMILY,
                userId = callerId,
                permitsPerMinute = presenceProperties.rateLimit.snapshotsPerMinute.toLong(),
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "snapshot refused by the flood limit (№36, presence-api.md §1): " +
                    "user <{}> exhausted {} snapshots/minute, retry after {}s",
                callerId,
                presenceProperties.rateLimit.snapshotsPerMinute,
                verdict.retryAfterSeconds,
            )
            throw PresenceSnapshotFloodException(verdict.retryAfterSeconds)
        }
    }

    /**
     * presence-api.md §2: one token per beat of the per-user contract
     * bucket (capacity [PresenceProperties.RateLimit.heartbeatsPerWindow]
     * over the 30 s [PresenceProperties.RateLimit.heartbeatWindow]) —
     * refused beats renew nothing. The refusal is the `429 flood_limit`
     * problem+json + `Retry-After` of №37 with the client contract
     * «keep the stream, retry in the next interval» (the 3× TTL
     * headroom makes the wait safe).
     */
    private fun enforceHeartbeatFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = HEARTBEAT_KEY_FAMILY,
                userId = callerId,
                permits = presenceProperties.rateLimit.heartbeatsPerWindow.toLong(),
                window = presenceProperties.rateLimit.heartbeatWindow,
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "heartbeat refused by the flood limit (№37, presence-api.md §2): user <{}> exhausted {} beats/{}s, " +
                    "retry after {}s",
                callerId,
                presenceProperties.rateLimit.heartbeatsPerWindow,
                presenceProperties.rateLimit.heartbeatWindow.toSeconds(),
                verdict.retryAfterSeconds,
            )
            throw PresenceHeartbeatFloodException(verdict.retryAfterSeconds)
        }
    }

    private companion object {
        /** The openapi №36 query parameter carrying the comma-separated target UUIDs. */
        const val USER_IDS_PARAM = "userIds"

        /** The openapi №36 wire enum — lowercase, never the Kotlin constant case. */
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val STATUS_UNKNOWN = "unknown"
        const val COMMA = ","

        /** research.md 004 §7 parity: the Redis key family of the №36 snapshot bucket. */
        const val SNAPSHOT_KEY_FAMILY = "rl:user:presence-snapshot:"

        /** research.md 004 §7 parity: the Redis key family of the №37 heartbeat bucket. */
        const val HEARTBEAT_KEY_FAMILY = "rl:user:presence-heartbeat:"
    }
}

/**
 * The №36 success body (openapi `PresenceSnapshotResponse`): one item
 * per DISTINCT requested userId, in request order.
 */
data class PresenceSnapshotResponse(
    val items: List<PresenceStatusItem>,
)

/**
 * One №36 item (openapi `PresenceStatusItem`): the target, the wire
 * status (`online`/`offline` — the published value; `unknown` — the
 * indistinguishable «no access») and the revision the client merges by
 * max(rev) per user (FR-003).
 */
data class PresenceStatusItem(
    val userId: UUID,
    val status: String,
    val rev: Long,
)

/**
 * The №37 request body (openapi `PresenceHeartbeatRequest`): the
 * connectionId of the №18 opening frame `connected`. Bound leniently —
 * the shape errors are the controller's `400 malformed_request`
 * (errors.connectionId), not a framework parse failure.
 */
data class PresenceHeartbeatRequest(
    val connectionId: String? = null,
)

/**
 * 400 (openapi №36): `userIds` is absent/empty or any segment is not a
 * UUID — rendered by [PresenceExceptionHandler] as
 * `errors: {userIds: [malformed_request]}`.
 */
class SnapshotMalformedException(
    detail: String,
) : RuntimeException(detail)

/**
 * 400 (openapi №36): the batch exceeds the 200-after-dedup cap
 * (contracts/presence-api.md §1; the >200 chunking is the CLIENT's
 * contract business) — rendered as
 * `errors: {userIds: [presence_ids_too_many]}`. [requestedCount] is the
 * deduped size the server actually counted (diagnostics only — the
 * problem body never echoes the ids themselves, constitution V).
 */
class PresenceIdsTooManyException(
    val requestedCount: Int,
) : RuntimeException("the userIds batch exceeds the per-request cap after dedup")

/**
 * 429 (openapi №36): the per-user snapshot flood bucket is exhausted —
 * [retryAfterSeconds] is the integral ceiling of the wait for the next
 * available token, rendered as the `Retry-After` header. The snapshot
 * is NOT performed; the client repeats the batch after the wait (the
 * №18 stream and the max(rev) merge absorb the delay).
 */
class PresenceSnapshotFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user snapshot flood limit is exhausted")

/**
 * 400 (openapi №37): the body is absent, connectionId is not set or is
 * not a UUID — rendered by [PresenceExceptionHandler] as
 * `errors: {connectionId: [malformed_request]}`.
 */
class HeartbeatMalformedException(
    detail: String,
) : RuntimeException(detail)

/**
 * 404 (openapi №37, data-model 007 §1.1): the connectionId is not a
 * LIVE registration of the caller — unknown, expired or someone else's.
 * The client's contract reaction is an immediate №18 reconnect (the
 * beat never revives anything).
 */
class HeartbeatConnectionNotFoundException : RuntimeException("the connectionId is not a live registration")

/**
 * 429 (openapi №37): the per-user heartbeat flood bucket is exhausted —
 * [retryAfterSeconds] is the integral ceiling of the wait for the next
 * available token, rendered as the `Retry-After` header. The №18 stream
 * stays up; the registration survives the throttle on its TTL
 * headroom.
 */
class PresenceHeartbeatFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user heartbeat flood limit is exhausted")
