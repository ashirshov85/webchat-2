package webchat.backend.conferences.repository

import org.springframework.stereotype.Component
import webchat.backend.conferences.api.dto.conferenceModeLabel
import webchat.backend.conferences.domain.port.ConferenceEventPublisher
import webchat.backend.conferences.domain.port.ConferenceStartedEvent
import webchat.backend.realtime.RedisRealtimePublisher
import java.util.UUID

/**
 * T054 (tasks.md 009, US5; realtime-conference-events.md §1): the
 * Redis adapter of the [ConferenceEventPublisher] port — the
 * conference lifecycle half of the №18 frame family RIDES THE EXISTING
 * [RedisRealtimePublisher][webchat.backend.realtime.RedisRealtimePublisher]
 * and its per-user `rt:user:{userId}` channel family: no new broker
 * topology, no change to the delivery core, the SAME at-most-once
 * post-commit discipline as the 004/006/007 frames (constitution VIII,
 * the presence T017 seam precedent of a non-chats domain reusing the
 * channel; research.md 006 §4).
 *
 * The adapter owns exactly the conference-specific concern the
 * transport stays agnostic of: the WIRE SERIALIZATION of the port's
 * [ConferenceStartedEvent] — the exact
 * `conferenceId`/`chatId`/`startedAt`/`mode` field set of the openapi
 * `ConferenceStartedEvent` schema with the LOWER-CASE `video|audio`
 * label, never the Kotlin constant case (the group-frame payload
 * discipline of the transport's own fan-out legs). Failure etiquette
 * (the port contract): the
 * transition is ALREADY durable when this runs and the channel is
 * at-most-once — the underlying [RedisRealtimePublisher] isolates
 * every single envelope publish (a dead transport warns with ids
 * only, constitution V, and never re-sends), so this adapter never
 * throws either: clients converge via the №45 `status` refetch
 * (SC-006 completeness, FR-013).
 */
@Component
class RedisConferenceEventPublisher(
    private val realtimePublisher: RedisRealtimePublisher,
) : ConferenceEventPublisher {
    /**
     * ONE `conference.started` envelope per [ConferenceEventPublisher.fanoutConferenceStarted]
     * addressee; an EMPTY roster (the caller already guards) publishes
     * NOTHING.
     */
    override fun fanoutConferenceStarted(
        toUserIds: List<UUID>,
        event: ConferenceStartedEvent,
    ) {
        if (toUserIds.isEmpty()) return
        realtimePublisher.fanoutConferenceStarted(toUserIds, payloadOf(event))
    }

    /**
     * realtime-conference-events.md §1: exactly
     * `conferenceId`+`chatId`+`startedAt`+`mode` — the mode as the
     * LOWER-CASE wire value, and the Boot mapper renders the carried
     * `Instant` as the RFC 3339 date-time the contract declares (the
     * [RedisRealtimePublisher] shares the app mapper).
     */
    private fun payloadOf(event: ConferenceStartedEvent): Map<String, Any> =
        mapOf(
            FIELD_CONFERENCE_ID to event.conferenceId,
            FIELD_CHAT_ID to event.chatId,
            FIELD_STARTED_AT to event.startedAt,
            FIELD_MODE to conferenceModeLabel(event.mode),
        )

    private companion object {
        /** realtime-conference-events.md 009 §1: the `ConferenceStartedEvent` payload fields. */
        const val FIELD_CONFERENCE_ID = "conferenceId"

        const val FIELD_CHAT_ID = "chatId"

        const val FIELD_STARTED_AT = "startedAt"

        const val FIELD_MODE = "mode"
    }
}
