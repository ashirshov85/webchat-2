package webchat.backend.presence.repository

import org.springframework.stereotype.Component
import webchat.backend.presence.PresenceMetrics
import webchat.backend.presence.domain.port.PresenceEventPublisher
import webchat.backend.presence.domain.port.PresenceUpdatedEvent
import webchat.backend.realtime.RedisRealtimePublisher
import java.util.UUID

/**
 * T017 (tasks.md 007, US1; contracts/presence-events.md §2): the Redis
 * adapter of the [PresenceEventPublisher] port — the presence half of
 * the №18 frame family RIDES THE EXISTING
 * [RedisRealtimePublisher][webchat.backend.realtime.RedisRealtimePublisher]
 * and its per-user `rt:user:{observerId}` channel family: no new broker
 * topology, no change to the delivery core, the SAME at-most-once
 * post-transition discipline as the 004/006 frames (constitution VIII,
 * research.md 006 §4; the dependency direction presence → realtime is
 * the established T013 seam).
 *
 * The adapter owns exactly the two presence-specific concerns the
 * transport stays agnostic of:
 *  * the WIRE SERIALIZATION of the port's [PresenceUpdatedEvent] — the
 *    `userId`/`status`/`rev` field set with the LOWER-CASE
 *    `online|offline` values of the openapi `PresenceUpdatedEvent`
 *    schema (T016's №36 projection discipline) plus the OPTIONAL
 *    `lastSeenAt` of 008a — present only when the caller's T044
 *    disclosure verdict handed one, ABSENT otherwise (never an explicit
 *    `null`, SC-002);
 *  * the FR-009 observability leg of T010 — ONE
 *    `webchat_presence_events_published_total{status}` increment per
 *    PUBLISHED transition (per transition, never per observer: the
 *    counter counts publications, and the §Load audience-size
 *    estimator of plan.md divides by it). The
 *    `webchat_realtime_push_seconds{event=presence.updated}` samples
 *    of the SAME transition are recorded by the transport itself — one
 *    per observer envelope on the publish leg and one per local SSE
 *    dispatch on the dispatch leg (the T036 exporter discipline: the
 *    whole timer family shares the `event`+`stage` tag keys).
 *
 * Failure etiquette (the port contract): the store's CAS has ALREADY
 * made the transition durable when this runs, and the channel is
 * at-most-once — the underlying [RedisRealtimePublisher] isolates every
 * single envelope publish (a dead transport warns with ids only,
 * constitution V, and never re-sends), so this adapter never throws
 * either: clients converge via the №36 snapshot refetch by max(rev)
 * (FR-003, constitution III).
 */
@Component
class RedisPresenceEventPublisher(
    private val realtimePublisher: RedisRealtimePublisher,
    private val presenceMetrics: PresenceMetrics,
) : PresenceEventPublisher {
    /**
     * ONE `presence.updated {userId,status,rev}` envelope per [audience]
     * observer — set semantics make the once-per-observer invariant
     * structural (a direct- AND group-mate of the subject is one set
     * entry, T030); an EMPTY audience (the caller already guards, and a
     * subject nobody may observe has none) publishes and counts
     * NOTHING.
     */
    override fun fanoutPresenceUpdated(
        audience: Set<UUID>,
        event: PresenceUpdatedEvent,
    ) {
        if (audience.isEmpty()) return
        presenceMetrics.countEventPublished(event.status)
        realtimePublisher.fanoutPresenceUpdated(audience, payloadOf(event))
    }

    /**
     * The contract payload (presence-events.md §2 + 008a §2): exactly
     * `userId`+`status`+`rev` — plus the OPTIONAL `lastSeenAt` when the
     * caller's T044 disclosure verdict handed one — the status as the
     * LOWER-CASE wire value, never the Kotlin constant case, exactly like
     * the №36 items of T016. A `null` [PresenceUpdatedEvent.lastSeenAt]
     * stays an ABSENT field (never an explicit `null`): «скрыто» ≡ «нет
     * данных» on the wire (SC-002), and the Boot mapper renders the
     * carried `Instant` as the RFC 3339 date-time the contract declares.
     */
    private fun payloadOf(event: PresenceUpdatedEvent): Map<String, Any> =
        buildMap {
            put(FIELD_USER_ID, event.userId)
            put(FIELD_STATUS, event.status.name.lowercase())
            put(FIELD_REV, event.rev)
            event.lastSeenAt?.let { put(FIELD_LAST_SEEN_AT, it) }
        }

    private companion object {
        /** presence-events.md 007 §2 + 008a research §B2: the `PresenceUpdatedEvent` payload fields. */
        const val FIELD_USER_ID = "userId"
        const val FIELD_STATUS = "status"
        const val FIELD_REV = "rev"
        const val FIELD_LAST_SEEN_AT = "lastSeenAt"
    }
}
