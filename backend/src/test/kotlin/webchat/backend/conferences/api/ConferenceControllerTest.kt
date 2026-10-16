package webchat.backend.conferences.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import webchat.backend.conferences.api.dto.ConferenceMode
import webchat.backend.conferences.api.dto.CreateConferenceRequest
import webchat.backend.conferences.api.dto.JoinTicket
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ChatMembershipChecker
import webchat.backend.conferences.domain.port.ConferenceCreateResult
import webchat.backend.conferences.domain.port.ConferenceModeReader
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.conferences.domain.port.ParticipantRepository
import webchat.backend.conferences.domain.service.ConferenceEndedException
import webchat.backend.conferences.domain.service.ConferenceForbiddenException
import webchat.backend.conferences.domain.service.ConferenceNotFoundException
import webchat.backend.conferences.domain.service.ConferenceService
import webchat.backend.conferences.domain.service.JoinTokenService
import java.time.Instant
import java.util.UUID

/**
 * T019 unit regression (tasks.md US1): the №44–№47 HTTP adapter pinned
 * against the api-contract.md 009 §2 table — port fakes only, no Spring
 * context (the story IT T021 holds the SQL and the end-to-end legs):
 *
 *  * **№44** — the winning create answers `201` with the REQUEST's mode
 *    and a zero count WITHOUT a media round-trip (the T017 post-commit
 *    rule: a plane failure after the commit must not fail the durable
 *    create), the «double press» answers `200` of the EXISTING
 *    conference with ITS widget mode (the submitted mode may differ)
 *    and the LIVE count of `ListParticipants`;
 *  * **№45** — the uniform 404 (an unknown id and a non-member's id
 *    answer the same `conference_not_found`, api-contract §1), the
 *    member projection (status labels, widget mode, live count) and
 *    the ended leg (`endReason`, a zero count with NO plane call — the
 *    room is gone by finalize);
 *  * **№46** — the DEFAULT `MeView` of a not-yet-joined member (a read
 *    never creates rows) and the persisted principal projection;
 *  * **№47** — the ladder of the every-join membership gate
 *    (FR-003/SC-002): `404 conference_not_found` → `403
 *    conference_forbidden` → `409 conference_ended`, each BEFORE the
 *    mint ([JoinTokenService] untouched — a non-member never mints),
 *    then the delegated mint for an active member.
 *
 * Arguments of the mocked Kotlin ports are stubbed through the
 * `*NotNull` wrappers (the matcher factories return null/platform
 * values the compiler refuses on NON-NULL Kotlin parameters — the
 * ConferenceServiceTest seam, inlined for this file).
 */
@Suppress("TooManyFunctions") // one leg per №44–№47 rule of the contract table
class ConferenceControllerTest {
    private val conferenceService = Mockito.mock(ConferenceService::class.java)
    private val joinTokenService = Mockito.mock(JoinTokenService::class.java)
    private val conferenceRepository = Mockito.mock(ConferenceRepository::class.java)
    private val participantRepository = Mockito.mock(ParticipantRepository::class.java)
    private val membership = Mockito.mock(ChatMembershipChecker::class.java)
    private val inviteModes = Mockito.mock(ConferenceModeReader::class.java)
    private val liveKit = Mockito.mock(LiveKitAdminClient::class.java)

    private val controller =
        ConferenceController(
            conferenceService = conferenceService,
            joinTokenService = joinTokenService,
            conferenceRepository = conferenceRepository,
            participantRepository = participantRepository,
            membership = membership,
            inviteModes = inviteModes,
            liveKit = liveKit,
        )

    @Test
    fun `create answers 201 with the request mode and no media round-trip`() {
        Mockito
            .`when`(conferenceService.create(eqNotNull(CALLER_ID), eqNotNull(CHAT_ID), eqNotNull(ConferenceMode.VIDEO)))
            .thenReturn(ConferenceCreateResult.Created(activeConference()))

        val response = controller.create(CHAT_ID.toString(), CreateConferenceRequest("video"), tokenOf(CALLER_ID))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val view = response.body!!
        assertThat(view.id).isEqualTo(CONFERENCE_ID)
        assertThat(view.chatId).isEqualTo(CHAT_ID)
        assertThat(view.status).isEqualTo("created")
        assertThat(view.mode)
            .overridingErrorMessage("the fresh 201 answers the REQUEST's mode — the widget carries it (FR-001)")
            .isEqualTo("video")
        assertThat(view.createdBy).isEqualTo(CALLER_ID)
        assertThat(view.createdAt).isEqualTo(T1)
        assertThat(view.maxParticipants).isEqualTo(MAX_PARTICIPANTS)
        assertThat(view.activeParticipants).isZero()
        assertThat(view.startedAt).isNull()
        assertThat(view.endedAt).isNull()
        assertThat(view.endReason).isNull()
        Mockito.verifyNoInteractions(liveKit, inviteModes)
    }

    @Test
    fun `create answers 200 of the existing conference with its widget mode`() {
        val existing = activeConference()
        Mockito
            .`when`(conferenceService.create(eqNotNull(CALLER_ID), eqNotNull(CHAT_ID), eqNotNull(ConferenceMode.AUDIO)))
            .thenReturn(ConferenceCreateResult.Existing(existing))
        Mockito.`when`(inviteModes.modeOf(CONFERENCE_ID, CHAT_ID)).thenReturn(ConferenceMode.VIDEO)
        Mockito
            .`when`(liveKit.listParticipants(existing.roomName))
            .thenReturn(listOf(participant("u:$CALLER_ID:1"), participant("u:$BOB:2"), participant("g:3")))

        // the «double press» submits audio while the running conference is video
        val response = controller.create(CHAT_ID.toString(), CreateConferenceRequest("audio"), tokenOf(CALLER_ID))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val view = response.body!!
        assertThat(view.mode)
            .overridingErrorMessage("the idempotent 200 answers the mode of the conference THAT RUNS, not the request")
            .isEqualTo("video")
        assertThat(view.activeParticipants)
            .overridingErrorMessage("activeParticipants is the live ListParticipants count (members AND guests)")
            .isEqualTo(3)
    }

    @Test
    fun `create rejects a non-UUID chatId before the service is touched`() {
        assertThrows<InvalidConferenceChatIdException> {
            controller.create("not-a-uuid", CreateConferenceRequest("video"), tokenOf(CALLER_ID))
        }

        Mockito.verifyNoInteractions(conferenceService)
    }

    @Test
    fun `create rejects an invalid mode before the service is touched`() {
        assertThrows<webchat.backend.conferences.api.dto.InvalidConferenceModeException> {
            controller.create(CHAT_ID.toString(), CreateConferenceRequest("voice"), tokenOf(CALLER_ID))
        }

        Mockito.verifyNoInteractions(conferenceService)
    }

    @Test
    fun `getConference answers the uniform 404 for an unknown id`() {
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(null)

        assertThrows<ConferenceNotFoundException> {
            controller.getConference(CONFERENCE_ID.toString(), tokenOf(CALLER_ID))
        }

        Mockito.verifyNoInteractions(membership, liveKit, inviteModes)
    }

    @Test
    fun `getConference answers the uniform 404 for a non-member`() {
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(activeConference())
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(false)

        assertThrows<ConferenceNotFoundException> {
            controller.getConference(CONFERENCE_ID.toString(), tokenOf(CALLER_ID))
        }

        Mockito.verifyNoInteractions(liveKit, inviteModes)
    }

    @Test
    fun `getConference projects the status, the widget mode and the live count`() {
        val conference = activeConference().activate(T2)!!
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(conference)
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(true)
        Mockito.`when`(inviteModes.modeOf(CONFERENCE_ID, CHAT_ID)).thenReturn(ConferenceMode.AUDIO)
        Mockito
            .`when`(liveKit.listParticipants(conference.roomName))
            .thenReturn(listOf(participant("u:$CALLER_ID:1"), participant("u:$BOB:2")))

        val view = controller.getConference(CONFERENCE_ID.toString(), tokenOf(CALLER_ID))

        assertThat(view.status).isEqualTo("active")
        assertThat(view.mode).isEqualTo("audio")
        assertThat(view.activeParticipants).isEqualTo(2)
        assertThat(view.startedAt).isEqualTo(T2)
    }

    @Test
    fun `getConference of an ended conference answers the end labels without a media round-trip`() {
        val conference =
            activeConference()
                .activate(T2)!!
                .end(ConferenceEndReason.LAST_LEFT, T3)!!
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(conference)
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(true)
        Mockito.`when`(inviteModes.modeOf(CONFERENCE_ID, CHAT_ID)).thenReturn(ConferenceMode.VIDEO)

        val view = controller.getConference(CONFERENCE_ID.toString(), tokenOf(CALLER_ID))

        assertThat(view.status).isEqualTo("ended")
        assertThat(view.endReason).isEqualTo("last_left")
        assertThat(view.endedAt).isEqualTo(T3)
        assertThat(view.activeParticipants)
            .overridingErrorMessage("an ended conference answers 0 — its room is gone by finalize (SC-007)")
            .isZero()
        Mockito.verifyNoInteractions(liveKit)
    }

    @Test
    fun `me answers the default view for a member who has not joined yet`() {
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(activeConference())
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(true)
        Mockito.`when`(participantRepository.findMember(CONFERENCE_ID, CALLER_ID)).thenReturn(null)

        val me = controller.me(CONFERENCE_ID.toString(), tokenOf(CALLER_ID))

        assertThat(me.role).isEqualTo("participant")
        assertThat(me.micRestricted).isFalse()
        assertThat(me.cameraRestricted).isFalse()
        assertThat(me.screenRestricted).isFalse()
        assertThat(me.speechRestricted).isFalse()
    }

    @Test
    fun `me projects the persisted principal`() {
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(activeConference())
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(true)
        Mockito
            .`when`(participantRepository.findMember(CONFERENCE_ID, CALLER_ID))
            .thenReturn(
                ConferenceParticipant
                    .member(CONFERENCE_ID, CALLER_ID, PARTICIPANT_ID, T2, ParticipantRole.MODERATOR)
                    .copy(
                        restrictions =
                            ParticipantRestrictions(
                                micRestricted = true,
                                cameraRestricted = false,
                                screenRestricted = true,
                                speechRestricted = false,
                            ),
                    ),
            )

        val me = controller.me(CONFERENCE_ID.toString(), tokenOf(CALLER_ID))

        assertThat(me.role).isEqualTo("moderator")
        assertThat(me.micRestricted).isTrue()
        assertThat(me.cameraRestricted).isFalse()
        assertThat(me.screenRestricted).isTrue()
        assertThat(me.speechRestricted).isFalse()
    }

    @Test
    fun `join of an unknown conference answers 404 before the membership gate`() {
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(null)

        assertThrows<ConferenceNotFoundException> { controller.join(CONFERENCE_ID.toString(), tokenOf(CALLER_ID)) }

        Mockito.verifyNoInteractions(membership, joinTokenService)
    }

    @Test
    fun `join of a non-member answers conference_forbidden before the mint`() {
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(activeConference())
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(false)

        assertThrows<ConferenceForbiddenException> { controller.join(CONFERENCE_ID.toString(), tokenOf(CALLER_ID)) }

        Mockito.verifyNoInteractions(joinTokenService)
        Mockito
            .verify(participantRepository, Mockito.never())
            .ensureMember(anyNotNull(), anyNotNull(), anyNotNull())
    }

    @Test
    fun `join of an ended conference answers conference_ended before the mint`() {
        val conference = activeConference().end(ConferenceEndReason.MODERATOR, T2)!!
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(conference)
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(true)

        assertThrows<ConferenceEndedException> { controller.join(CONFERENCE_ID.toString(), tokenOf(CALLER_ID)) }

        Mockito.verifyNoInteractions(joinTokenService)
    }

    @Test
    fun `join mints through JoinTokenService for an active member`() {
        val conference = activeConference()
        Mockito.`when`(conferenceRepository.findById(CONFERENCE_ID)).thenReturn(conference)
        Mockito.`when`(membership.isMember(CHAT_ID, CALLER_ID)).thenReturn(true)
        Mockito
            .`when`(joinTokenService.mintMember(CALLER_ID, conference))
            .thenReturn(TICKET)

        val ticket = controller.join(CONFERENCE_ID.toString(), tokenOf(CALLER_ID))

        assertThat(ticket).isSameAs(TICKET)
    }

    @Test
    fun `join rejects a non-UUID conferenceId before any collaborator`() {
        assertThrows<InvalidConferenceIdException> { controller.join("not-a-uuid", tokenOf(CALLER_ID)) }

        Mockito.verifyNoInteractions(conferenceRepository, membership, joinTokenService)
    }

    private fun activeConference(): Conference =
        Conference.create(CONFERENCE_ID, CHAT_ID, CALLER_ID, MAX_PARTICIPANTS, T1)

    private fun participant(identity: String): LiveKitParticipant = LiveKitParticipant(identity, null)

    private fun tokenOf(userId: UUID): Jwt =
        Jwt
            .withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .build()

    private companion object {
        // the explicit `: UUID`/`: Instant` annotations keep the fixture
        // values KOTLIN-non-null: `UUID.fromString` returns a platform
        // type, and a platform-typed value would flow into the generic
        // eqNotNull helper whose null return the compiler then
        // null-checks at the stubbing call sites (the ConferenceServiceTest seam)
        val T1: Instant = Instant.parse("2026-10-16T09:00:00Z")
        val T2: Instant = Instant.parse("2026-10-16T09:00:01Z")
        val T3: Instant = Instant.parse("2026-10-16T09:00:02Z")
        val CHAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0a7")
        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000044c")
        val CALLER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a11c")
        val BOB: UUID = UUID.fromString("00000000-0000-4000-8000-000000000b0b")
        val PARTICIPANT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000e7ab")
        const val MAX_PARTICIPANTS = 16

        val TICKET =
            JoinTicket(
                token = "ticket-jwt",
                wsUrl = "wss://media.example/rtc",
                identity = "u:$CALLER_ID:conn",
                name = "alice",
                expiresAt = T1.plusSeconds(900),
            )
    }
}

/**
 * The Kotlin null-safety seam of Mockito on the conferences Kotlin
 * ports (the ConferenceServiceTest trick, inlined for this file).
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

/** [anyNotNull] for [ArgumentMatchers.eq]. */
@Suppress("UNCHECKED_CAST")
private fun <T> eqNotNull(value: T): T = ArgumentMatchers.eq(value) as T
