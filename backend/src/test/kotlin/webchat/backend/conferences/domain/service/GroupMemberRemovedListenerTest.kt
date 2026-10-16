package webchat.backend.conferences.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import webchat.backend.chats.domain.port.GroupMemberRemovedEvent
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import java.time.Instant
import java.util.UUID

/**
 * T020 unit regression (tasks.md US1, FR-003 edge): the in-process
 * `GroupMemberRemovedEvent` consumer pinned against the 006 removal
 * causes and the media-plane outcomes — port mocks only, no Spring
 * context (the AFTER_COMMIT/@Async wiring and the end-to-end №32/№33
 * legs during a live call hold in the story IT T021):
 *
 *  * **both causes** — the №32 exclusion (`actorId` set) and the №33
 *    voluntary exit (`actorId = null`) disconnect alike: the listener
 *    never reads the actor, only the removed [GroupMemberRemovedEvent.userId];
 *  * **the prefix discipline** — `ListParticipants(c:{id})` of the
 *    chat's ONE non-ended conference, `RemoveParticipant` for EVERY
 *    `u:{userId}:` connection (two tabs — two connections, edge Q10),
 *    while OTHER members's connections and guest `g:{connId}`
 *    identities stay untouched;
 *  * **the no-call legs** — a chat without a running conference costs
 *    NO media-plane round-trip at all;
 *  * **the best-effort rule** — a media-plane failure AFTER the
 *    durable removal is swallowed (logged), never re-thrown: the
 *    re-mint membership gate is already closed and the removed
 *    member's token dies at its TTL.
 *
 * Arguments of the mocked Kotlin ports are recorded through stub
 * answers (not ArgumentCaptor) — the matcher factories of plain
 * Mockito return null/platform values the compiler refuses on NON-NULL
 * Kotlin parameters; the two `*NotNull` wrappers below keep the
 * unavoidable `any`/`eq` stubbings legal.
 */
@Suppress("TooManyFunctions") // one leg per T020 rule of the task table
class GroupMemberRemovedListenerTest {
    private val conferences = Mockito.mock(ConferenceRepository::class.java)
    private val liveKit = Mockito.mock(LiveKitAdminClient::class.java)

    private val listener =
        GroupMemberRemovedListener(
            conferences = conferences,
            liveKit = liveKit,
        )

    private val removedConnections = mutableListOf<String>()

    @BeforeEach
    fun stubConferenceAndRecorders() {
        Mockito
            .`when`(conferences.findUnendedByChatId(eqNotNull(CHAT_ID)))
            .thenReturn(listOf(CONFERENCE))
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(CONFERENCE.roomName)))
            .thenAnswer { ROOM_CONNECTIONS }
        Mockito
            .`when`(liveKit.removeParticipant(eqNotNull(CONFERENCE.roomName), anyNotNull()))
            .thenAnswer { invocation -> removedConnections += invocation.getArgument<String>(1) }
    }

    @Test
    fun `disconnects every live connection of the member excluded via №32`() {
        listener.onGroupMemberRemoved(GroupMemberRemovedEvent(CHAT_ID, REMOVED_USER_ID, ACTOR_ID))

        Mockito
            .verify(liveKit)
            .listParticipants(CONFERENCE.roomName)
        assertThat(removedConnections)
            .containsExactly(REMOVED_TAB_ONE_IDENTITY, REMOVED_TAB_TWO_IDENTITY)
    }

    @Test
    fun `disconnects the same connections on the voluntary №33 exit with actorId null`() {
        listener.onGroupMemberRemoved(GroupMemberRemovedEvent(CHAT_ID, REMOVED_USER_ID, actorId = null))

        assertThat(removedConnections)
            .containsExactly(REMOVED_TAB_ONE_IDENTITY, REMOVED_TAB_TWO_IDENTITY)
    }

    @Test
    fun `a chat without a running conference spends no media-plane round-trip`() {
        Mockito
            .`when`(conferences.findUnendedByChatId(eqNotNull(CHAT_ID)))
            .thenReturn(emptyList())

        listener.onGroupMemberRemoved(GroupMemberRemovedEvent(CHAT_ID, REMOVED_USER_ID, ACTOR_ID))

        verifyNoInteractions(liveKit)
        assertThat(removedConnections).isEmpty()
    }

    @Test
    fun `a member with no live connection in the room is a plain no-op`() {
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(CONFERENCE.roomName)))
            .thenReturn(listOf(OTHER_MEMBER_PARTICIPANT, GUEST_PARTICIPANT))

        listener.onGroupMemberRemoved(GroupMemberRemovedEvent(CHAT_ID, REMOVED_USER_ID, ACTOR_ID))

        Mockito
            .verify(liveKit)
            .listParticipants(CONFERENCE.roomName)
        Mockito
            .verifyNoMoreInteractions(liveKit)
        assertThat(removedConnections).isEmpty()
    }

    @Test
    fun `a media-plane failure after the durable removal is swallowed`() {
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(CONFERENCE.roomName)))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("twirp down")))

        assertThatCode {
            listener.onGroupMemberRemoved(GroupMemberRemovedEvent(CHAT_ID, REMOVED_USER_ID, ACTOR_ID))
        }.doesNotThrowAnyException()

        assertThat(removedConnections).isEmpty()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T09:00:00Z")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0a7")

        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000ac70")

        val REMOVED_USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000006675")

        val OTHER_MEMBER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000de13")

        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c411")

        val CONFERENCE =
            Conference.create(
                id = CONFERENCE_ID,
                chatId = CHAT_ID,
                createdBy = ACTOR_ID,
                maxParticipants = 16,
                at = NOW,
            )

        val REMOVED_TAB_ONE_IDENTITY = "u:$REMOVED_USER_ID:00000000-0000-4000-8000-000000000001"

        val REMOVED_TAB_TWO_IDENTITY = "u:$REMOVED_USER_ID:00000000-0000-4000-8000-000000000002"

        val OTHER_MEMBER_IDENTITY = "u:$OTHER_MEMBER_ID:00000000-0000-4000-8000-000000000003"

        const val GUEST_IDENTITY = "g:00000000-0000-4000-8000-000000000004"

        val OTHER_MEMBER_PARTICIPANT = LiveKitParticipant(OTHER_MEMBER_IDENTITY, MEMBER_METADATA)

        val GUEST_PARTICIPANT = LiveKitParticipant(GUEST_IDENTITY, GUEST_METADATA)

        val ROOM_CONNECTIONS =
            listOf(
                LiveKitParticipant(REMOVED_TAB_ONE_IDENTITY, MEMBER_METADATA),
                OTHER_MEMBER_PARTICIPANT,
                LiveKitParticipant(REMOVED_TAB_TWO_IDENTITY, MEMBER_METADATA),
                GUEST_PARTICIPANT,
            )

        const val MEMBER_METADATA = """{"role":"participant","guest":false}"""

        const val GUEST_METADATA = """{"role":"participant","guest":true}"""
    }
}

/**
 * The Kotlin null-safety seam of Mockito on the conferences Kotlin
 * ports: the matcher factories return null/platform values the
 * compiler refuses on NON-NULL Kotlin parameters — Kotlin-typed
 * wrappers keep the stubbing expressions legal (the mockito-kotlin
 * trick, inlined for this file; the unbounded `T` keeps the casts
 * unchecked at runtime).
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

/** [anyNotNull] for [ArgumentMatchers.eq]. */
@Suppress("UNCHECKED_CAST")
private fun <T> eqNotNull(value: T): T = ArgumentMatchers.eq(value) as T
