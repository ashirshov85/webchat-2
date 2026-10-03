package webchat.backend.presence.domain.port

import java.util.UUID

/**
 * The `presence.updated` frame payload (contracts/presence-events.md §2,
 * `components.schemas.PresenceUpdatedEvent`; data-model 007 §1.3):
 * [userId] is the SUBJECT of the transition — never the observer — plus
 * the PUBLISHED status (hysteresis-delayed for `offline`, immediate for
 * `online`) and the monotone [rev] the client merge converges on (FR-003:
 * apply only a strictly greater revision per user; a duplicate or stale
 * frame is a no-op). Nothing here is ever stored anywhere (FR-001 forbids
 * presence history): the frame is the only artifact of a transition.
 */
data class PresenceUpdatedEvent(
    val userId: UUID,
    val status: PresencePublishedStatus,
    val rev: Long,
)

/**
 * The presence fan-out port (contracts/presence-events.md §2; DIP: the
 * adapter lives outside the domain — T017 rides the EXISTING
 * `webchat.backend.realtime.RedisRealtimePublisher` and its per-user
 * `rt:user:{observerId}` channel family, so `presence.updated` EXTENDS
 * the №18 frame set of the realtime publisher over the same at-most-once
 * transport, with no new broker topology and no change to the delivery
 * core itself — constitution VIII, research.md 006 §4 discipline).
 *
 * Publish-only: the audience resolution is the caller's leg through
 * [VisibilityAudienceReader] (T017), the subscriptions and SSE emitters
 * stay transport concerns of the realtime adapters — never of the
 * domain. The wire shape (the lower-case `online|offline` values, the
 * exact `userId`/`status`/`rev` field set) is the ADAPTER's contract
 * serialization, exactly like the 004/006 frames of
 * `webchat.backend.chats.domain.port.RealtimeEventPublisher`. A single
 * leg — the `fun interface` of the codebase's one-method ports (Clock,
 * EmailGateway): the T017 adapter is a named class, tests stub the port
 * with a SAM lambda.
 */
fun interface PresenceEventPublisher {
    /**
     * T014/T017: ONE `presence.updated {userId,status,rev}` envelope per
     * [audience] observer on their own `rt:user:{observerId}` channel —
     * exactly one publication per observer per transition regardless of
     * how many chats they share with the subject (edge «до 200
     * участников», T030: a direct- AND group-mate still receives ONE
     * frame). [audience] is the DEDUPED visibility addressee set the
     * caller resolved via [VisibilityAudienceReader.audienceOf] — set
     * semantics make the once-per-observer invariant structural; an
     * empty set (an unknown subject simply has no audience) publishes
     * nothing.
     *
     * Called ONLY on an actual published transition
     * (`PresenceTransition.Switched`): the store's CAS has ALREADY made
     * the new status and revision atomically durable, and the channel is
     * at-most-once — a lost or failed delivery must NOT fail the caller
     * and is NEVER re-sent; clients converge via the №36 snapshot
     * refetch by max(rev) (FR-003, constitution II; the adapter only
     * warns with ids, never payload — constitution V). The «невидимка»
     * outgoing-event freeze (FR-007) is the caller's (T033) gate — this
     * port publishes exactly what it is handed.
     */
    fun fanoutPresenceUpdated(
        audience: Set<UUID>,
        event: PresenceUpdatedEvent,
    )
}
