package webchat.backend.conferences.domain.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import webchat.backend.chats.domain.port.GroupMemberRemovedEvent
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient

/**
 * T020 (009, US1) — the FR-003 edge disconnect: the IN-PROCESS
 * AFTER_COMMIT consumer of the 006 [GroupMemberRemovedEvent], published
 * inside the removal transaction by
 * [webchat.backend.groups.domain.service.GroupService] on BOTH of its
 * causes — the №32 exclusion and the №33 voluntary exit
 * (`actorId = null`) — which this listener deliberately never reads:
 * whatever removed the member, his live conference connections must go.
 *
 * Once the removal transaction has COMMITTED (a rolled-back kick/leave
 * disconnects nobody), the leg is: resolve the chat's non-ended
 * conference (`ux_conferences_chat_active` — at most one, the
 * `findUnendedByChatId` defensive list), `ListParticipants` its room
 * `c:{id}` and `RemoveParticipant` EVERY live connection of the member
 * by the `u:{userId}:` connection prefix
 * ([ConferenceIdentity.isMemberConnectionOf] — two tabs of one user are
 * two connections, edge Q10). The re-mint protection needs no work
 * here: the already-committed removal drops the user out of the
 * every-join membership check, so his next №47 answers the same
 * `403 conference_forbidden` as any stranger's (SC-002).
 *
 * `@Async` moves the Twirp round-trips off the request thread onto the
 * Boot auto-configured executor (the `StarterContactEventListener`
 * precedent): the №32/№33 response latency never pays for the media
 * plane, and an async void listener can never break the publisher.
 *
 * The whole body is best-effort AFTER the durable fact: a failing media
 * plane is logged (ids only, constitution V) and left to converge — the
 * removed member's token dies at `conferences.token-ttl` anyway, and
 * every later mint is already blocked by the membership gate.
 */
@Component
class GroupMemberRemovedListener(
    private val conferences: ConferenceRepository,
    private val liveKit: LiveKitAdminClient,
) {
    private val log = LoggerFactory.getLogger(GroupMemberRemovedListener::class.java)

    /**
     * The one entry point of T020: disconnect every live connection of
     * [GroupMemberRemovedEvent.userId] from the chat's running call.
     * Idempotent by construction — a redelivery finds the connections
     * already gone and repeats into the port's no-op outcomes
     * (SET-semantics of livekit-integration.md §4).
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onGroupMemberRemoved(event: GroupMemberRemovedEvent) {
        try {
            disconnectMemberConnections(event)
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            // best-effort AFTER the commit: the removal is durable, the
            // re-mint gate is already closed; the loss must not pass
            // silently, but nothing may be re-queued either (the token
            // TTL and the membership check converge it)
            log.warn(
                "disconnect of a removed member (chat <{}>, user <{}>) from the live conference failed; " +
                    "the re-mint membership gate stays closed and the token dies at its TTL: {}",
                event.groupId,
                event.userId,
                failure.message,
            )
        }
    }

    /**
     * The T020 pipeline: the chat's non-ended conference (at most one
     * by the partial unique index; the list shape is the defensive
     * loop of the [ConferenceRepository.findUnendedByChatId] port) →
     * its live connections → `RemoveParticipant` for each match of the
     * `u:{userId}:` prefix. Guests (`g:{connId}`) and OTHER members's
     * connections never match the prefix and stay.
     */
    private fun disconnectMemberConnections(event: GroupMemberRemovedEvent) {
        conferences.findUnendedByChatId(event.groupId).forEach { conference ->
            val memberConnections =
                liveKit
                    .listParticipants(conference.roomName)
                    .filter { ConferenceIdentity.isMemberConnectionOf(it.identity, event.userId) }
            if (memberConnections.isEmpty()) return@forEach
            log.debug(
                "disconnecting {} live connection(s) of a removed member from conference <{}>",
                memberConnections.size,
                conference.id,
            )
            memberConnections.forEach { liveKit.removeParticipant(conference.roomName, it.identity) }
        }
    }
}
