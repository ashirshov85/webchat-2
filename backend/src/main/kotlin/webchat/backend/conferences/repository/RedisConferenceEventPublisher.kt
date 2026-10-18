package webchat.backend.conferences.repository

import org.springframework.stereotype.Component
import webchat.backend.conferences.api.dto.conferenceEndReasonLabel
import webchat.backend.conferences.api.dto.conferenceModeLabel
import webchat.backend.conferences.domain.port.ConferenceEndedEvent
import webchat.backend.conferences.domain.port.ConferenceEventPublisher
import webchat.backend.conferences.domain.port.ConferenceStartedEvent
import webchat.backend.realtime.RedisRealtimePublisher
import java.util.UUID

/**
 * T054/T055 (tasks.md 009, US5; realtime-conference-events.md §1/§2):
 * the Redis adapter of the [ConferenceEventPublisher] port — the
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
 * [ConferenceStartedEvent]/[ConferenceEndedEvent] — the exact field
 * sets of the openapi `ConferenceStartedEvent`/`ConferenceEndedEvent`
 * schemas with the LOWER-CASE `video|audio` mode and
 * `last_left|…|chat_deleted` reason labels, never the Kotlin constant
 * case (the group-frame payload discipline of the transport's own
 * fan-out legs). Failure etiquette (the port contract): the
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
     * ONE `conference.ended` envelope per [ConferenceEventPublisher.fanoutConferenceEnded]
     * addressee — the T055 twin of the started leg, same empty-roster
     * rule (a chat whose roster is gone — the T062 deletion path —
     * publishes nothing: there is nobody left to address).
     */
    override fun fanoutConferenceEnded(
        toUserIds: List<UUID>,
        event: ConferenceEndedEvent,
    ) {
        if (toUserIds.isEmpty()) return
        realtimePublisher.fanoutConferenceEnded(toUserIds, payloadOf(event))
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

    /**
     * realtime-conference-events.md §2: exactly
     * `conferenceId`+`chatId`+`endedAt`+`reason` — the reason as the
     * LOWER-CASE wire value of the winning row (never the caller's
     * guess), the `Instant` rendered RFC 3339 by the shared mapper.
     */
    private fun payloadOf(event: ConferenceEndedEvent): Map<String, Any> =
        mapOf(
            FIELD_CONFERENCE_ID to event.conferenceId,
            FIELD_CHAT_ID to event.chatId,
            FIELD_ENDED_AT to event.endedAt,
            FIELD_REASON to conferenceEndReasonLabel(event.reason),
        )

    private companion object {
        /** realtime-conference-events.md 009 §1/§2: the frame payload fields. */
        const val FIELD_CONFERENCE_ID = "conferenceId"

        const val FIELD_CHAT_ID = "chatId"

        const val FIELD_STARTED_AT = "startedAt"

        const val FIELD_MODE = "mode"

        const val FIELD_ENDED_AT = "endedAt"

        const val FIELD_REASON = "reason"
    }
}
