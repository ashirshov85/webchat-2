package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.model.PublicationSource
import webchat.backend.conferences.domain.model.PublishGrants
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.conferences.domain.port.ParticipantRepository
import java.time.Instant
import java.util.UUID

/**
 * T036/T037 unit regression (tasks.md US3): the №49/№50/№51
 * moderation legs pinned against research.md §5 — the durable SET
 * writes, the immediate media-plane application and the refusal
 * ladder, port fakes all the way down (the `ConferenceServiceTest`
 * convention):
 *
 *  * **the durable SET (№49/№50)** — the target folds through the
 *    principal prefix (a FULL member connection identity of the
 *    roster panel included), the repository receives the verbatim
 *    body (the four flags / the role) and the answer carries the
 *    updated row (the `ParticipantView` source of T039) plus the
 *    observed `online` flag;
 *  * **the immediate application (№49)** — every LIVE connection of
 *    the principal (two tabs of one member, edge Q10) gets
 *    `UpdateParticipant` with the grants recomputed from the row (the
 *    SAME single computation as the mint, Q5=A — `speech_restricted`
 *    gates the whole publish side, Q4=A) and the full metadata JSON
 *    (a guest keeps his `inviteId` — the №53 revocation key), then
 *    `MutePublishedTrack` for each point-restricted source (screen
 *    removes BOTH screen kinds, Q22=A); «право слова» and lifted
 *    restrictions mute nothing — the permission push alone carries
 *    them;
 *  * **the role broadcast (№50)** — the same `UpdateParticipant`
 *    push rides the metadata change (`ParticipantMetadataChanged`)
 *    with the grants UNTOUCHED and no track mutes: the role writes
 *    no restriction (Q9=A — persistence is the row, re-applied at
 *    every re-mint);
 *  * **the kick (№51)** — `RemoveParticipant` of every live
 *    connection with NO row write of any kind (кик ≠ бан: the
 *    principal row survives verbatim, re-entry by the active link
 *    stays possible), counted once disconnected;
 *  * **the refusals** — an unknown and a malformed target answer the
 *    SAME `404 participant_not_found` with nothing written; a
 *    media-plane failure never fails the durable №49/№50 answer (the
 *    T017 post-commit rule) — the SET stays, `online` degrades to
 *    `false` — but FAILS the kick №51: no durable fact outranks the
 *    disconnect (Q2=A edge semantics), and every applied action
 *    counts its `webchat_conference_moderation_actions_total{action}`.
 */
@Suppress("TooManyFunctions") // one leg per №49/№50/№51 rule of research.md §5
class ModerationServiceTest {
    private val participants = Mockito.mock(ParticipantRepository::class.java)
    private val liveKit = Mockito.mock(LiveKitAdminClient::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val service =
        ModerationService(
            participants = participants,
            liveKit = liveKit,
            metrics = ConferenceMetrics(meterRegistry, Mockito.mock(JdbcTemplate::class.java)),
            objectMapper = ObjectMapper(),
        )

    private val conference: Conference =
        Conference.create(CONFERENCE_ID, CHAT_ID, CREATOR_ID, MAX_PARTICIPANTS, NOW)

    @BeforeEach
    fun stubTheCommonLegs() {
        Mockito.`when`(liveKit.listParticipants(ArgumentMatchers.anyString())).thenReturn(emptyList())
    }

    @Test
    fun `the SET persists the four flags and answers the updated row`() {
        val connection = memberConnection()
        stubTargetOnline(connection)
        Mockito
            .`when`(
                participants.updateRestrictions(
                    eqNotNull(CONFERENCE_ID),
                    eqNotNull(TARGET_PRINCIPAL),
                    eqNotNull(MIC_ONLY),
                ),
            ).thenReturn(updatedMemberRow(MIC_ONLY))

        val update = service.setRestrictions(conference, TARGET_PRINCIPAL, MIC_ONLY)

        assertThat(update.participant.restrictions).isEqualTo(MIC_ONLY)
        assertThat(update.online).isTrue
        assertThat(actionCount()).isEqualTo(1.0)
    }

    @Test
    fun `a full member connection identity folds to its principal`() {
        stubTargetOnline(memberConnection())
        Mockito
            .`when`(participants.updateRestrictions(anyNotNull(), anyNotNull(), anyNotNull()))
            .thenReturn(updatedMemberRow(MIC_ONLY))

        service.setRestrictions(conference, "u:$TARGET_USER_ID:$CONN_ID", MIC_ONLY)

        Mockito
            .verify(participants)
            .updateRestrictions(eqNotNull(CONFERENCE_ID), eqNotNull(TARGET_PRINCIPAL), eqNotNull(MIC_ONLY))
    }

    @Test
    fun `the live connection gets the recomputed grants and the member metadata`() {
        val connection = memberConnection()
        stubTargetOnline(connection)
        stubMemberRowWrite(MIC_ONLY)

        service.setRestrictions(conference, TARGET_PRINCIPAL, MIC_ONLY)

        val permission = grantsCaptor()
        val metadata = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(liveKit).updateParticipant(
            eqNotNull(conference.roomName),
            eqNotNull(connection.identity),
            permission.capture() ?: DUMMY_GRANTS,
            metadata.capture() ?: "",
        )
        assertThat(permission.value.canPublish).isTrue
        assertThat(permission.value.canPublishSources)
            .isEqualTo(
                setOf(
                    PublicationSource.CAMERA,
                    PublicationSource.SCREEN_SHARE,
                    PublicationSource.SCREEN_SHARE_AUDIO,
                ),
            )
        assertThat(metadata.value).isEqualTo("""{"role":"participant","guest":false}""")
        Mockito
            .verify(liveKit)
            .mutePublishedTrack(conference.roomName, connection.identity, PublicationSource.MICROPHONE)
        Mockito.verify(liveKit).listParticipants(conference.roomName)
        Mockito.verifyNoMoreInteractions(liveKit)
    }

    @Test
    fun `both tabs of one member get the application`() {
        val first = memberConnection()
        val second = memberConnection()
        stubTargetOnline(first, second)
        stubMemberRowWrite(MIC_ONLY)

        val update = service.setRestrictions(conference, TARGET_PRINCIPAL, MIC_ONLY)

        assertThat(update.online).isTrue
        listOf(first, second).forEach { connection ->
            Mockito.verify(liveKit).updateParticipant(
                eqNotNull(conference.roomName),
                eqNotNull(connection.identity),
                anyNotNull(),
                anyNotNull(),
            )
            Mockito
                .verify(liveKit)
                .mutePublishedTrack(conference.roomName, connection.identity, PublicationSource.MICROPHONE)
        }
    }

    @Test
    fun `the speech restriction gates the whole publish side without track mutes`() {
        stubTargetOnline(memberConnection())
        stubMemberRowWrite(SPEECH_ONLY)

        service.setRestrictions(conference, TARGET_PRINCIPAL, SPEECH_ONLY)

        val permission = grantsCaptor()
        Mockito
            .verify(liveKit)
            .updateParticipant(anyNotNull(), anyNotNull(), permission.capture() ?: DUMMY_GRANTS, anyNotNull())
        // Q4=A: «право слова» blocks ANY publication
        assertThat(permission.value.canPublish).isFalse
        assertThat(permission.value.canPublishSources).isEmpty()
        Mockito.verify(liveKit, Mockito.never()).mutePublishedTrack(anyNotNull(), anyNotNull(), anyNotNull())
    }

    @Test
    fun `the screen restriction mutes both screen source kinds`() {
        val connection = memberConnection()
        stubTargetOnline(connection)
        stubMemberRowWrite(SCREEN_ONLY)

        service.setRestrictions(conference, TARGET_PRINCIPAL, SCREEN_ONLY)

        // Q22=A: screen removes screen_share AND screen_share_audio
        Mockito
            .verify(liveKit)
            .mutePublishedTrack(conference.roomName, connection.identity, PublicationSource.SCREEN_SHARE)
        Mockito
            .verify(liveKit)
            .mutePublishedTrack(conference.roomName, connection.identity, PublicationSource.SCREEN_SHARE_AUDIO)
        Mockito
            .verify(liveKit, Mockito.never())
            .mutePublishedTrack(anyNotNull(), anyNotNull(), eqNotNull(PublicationSource.MICROPHONE))
    }

    @Test
    fun `lifting every restriction restores the grants with no track mutes`() {
        stubTargetOnline(memberConnection())
        stubMemberRowWrite(ParticipantRestrictions())

        service.setRestrictions(conference, TARGET_PRINCIPAL, ParticipantRestrictions())

        val permission = grantsCaptor()
        Mockito
            .verify(liveKit)
            .updateParticipant(anyNotNull(), anyNotNull(), permission.capture() ?: DUMMY_GRANTS, anyNotNull())
        assertThat(permission.value.canPublish).isTrue
        assertThat(permission.value.canPublishSources).isEqualTo(PublicationSource.entries.toSet())
        Mockito.verify(liveKit, Mockito.never()).mutePublishedTrack(anyNotNull(), anyNotNull(), anyNotNull())
    }

    @Test
    fun `an offline target persists the row and answers online false`() {
        stubMemberRowWrite(MIC_ONLY)

        val update = service.setRestrictions(conference, TARGET_PRINCIPAL, MIC_ONLY)

        assertThat(update.online).isFalse
        assertThat(update.participant.restrictions).isEqualTo(MIC_ONLY)
        assertThat(actionCount()).isEqualTo(1.0)
        verifyNoLiveApplication()
    }

    @Test
    fun `a guest connection keeps its inviteId in the broadcast metadata`() {
        val guest = guestConnection()
        stubTargetOnline(guest)
        Mockito
            .`when`(
                participants.updateRestrictions(
                    eqNotNull(CONFERENCE_ID),
                    eqNotNull(TARGET_GUEST_PRINCIPAL),
                    eqNotNull(MIC_ONLY),
                ),
            ).thenReturn(updatedGuestRow(MIC_ONLY))

        service.setRestrictions(conference, TARGET_GUEST_PRINCIPAL, MIC_ONLY)

        val metadata = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(liveKit).updateParticipant(
            eqNotNull(conference.roomName),
            eqNotNull(guest.identity),
            anyNotNull(),
            metadata.capture() ?: "",
        )
        assertThat(metadata.value)
            .isEqualTo("""{"role":"participant","guest":true,"inviteId":"$INVITE_ID"}""")
    }

    @Test
    fun `an unknown target answers participant_not_found with nothing counted`() {
        Mockito
            .`when`(participants.updateRestrictions(anyNotNull(), anyNotNull(), anyNotNull()))
            .thenReturn(null)

        assertThatThrownBy { service.setRestrictions(conference, TARGET_PRINCIPAL, MIC_ONLY) }
            .isInstanceOfSatisfying(ParticipantNotFoundException::class.java) {
                assertThat(it.code).isEqualTo("participant_not_found")
            }

        verifyNoLiveApplication()
        assertThat(actionCount()).isZero()
    }

    @Test
    fun `a malformed target identity answers the same participant_not_found`() {
        assertThatThrownBy { service.setRestrictions(conference, "not-an-identity", MIC_ONLY) }
            .isInstanceOf(ParticipantNotFoundException::class.java)

        Mockito.verifyNoInteractions(participants)
        Mockito.verifyNoInteractions(liveKit)
        assertThat(actionCount()).isZero()
    }

    @Test
    fun `a media-plane failure never fails the durable SET answer`() {
        stubMemberRowWrite(MIC_ONLY)
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        val update = service.setRestrictions(conference, TARGET_PRINCIPAL, MIC_ONLY)

        // the committed row outranks the plane: the №49 answer stays,
        // `online` degrades to false, the action is counted
        assertThat(update.participant.restrictions).isEqualTo(MIC_ONLY)
        assertThat(update.online).isFalse
        assertThat(actionCount()).isEqualTo(1.0)
        verifyNoLiveApplication()
    }

    @Test
    fun `the role write persists and answers the updated row`() {
        stubTargetOnline(memberConnection())
        stubRoleWrite(ParticipantRole.MODERATOR)

        val update = service.changeRole(conference, TARGET_PRINCIPAL, ParticipantRole.MODERATOR)

        assertThat(update.participant.role).isEqualTo(ParticipantRole.MODERATOR)
        assertThat(update.online).isTrue
        assertThat(actionCount(SET_ROLE)).isEqualTo(1.0)
    }

    @Test
    fun `a full member connection identity folds to its principal for the role write`() {
        stubTargetOnline(memberConnection())
        stubRoleWrite(ParticipantRole.MODERATOR)

        service.changeRole(conference, "u:$TARGET_USER_ID:$CONN_ID", ParticipantRole.MODERATOR)

        Mockito
            .verify(participants)
            .updateRole(eqNotNull(CONFERENCE_ID), eqNotNull(TARGET_PRINCIPAL), eqNotNull(ParticipantRole.MODERATOR))
    }

    @Test
    fun `the live connection gets the new role broadcast with the grants untouched`() {
        val connection = memberConnection()
        stubTargetOnline(connection)
        stubRoleWrite(ParticipantRole.MODERATOR)

        service.changeRole(conference, TARGET_PRINCIPAL, ParticipantRole.MODERATOR)

        val permission = grantsCaptor()
        val metadata = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(liveKit).updateParticipant(
            eqNotNull(conference.roomName),
            eqNotNull(connection.identity),
            permission.capture() ?: DUMMY_GRANTS,
            metadata.capture() ?: "",
        )
        // the broadcast rides ParticipantMetadataChanged (№50, Q9=A)
        assertThat(metadata.value).isEqualTo("""{"role":"moderator","guest":false}""")
        // the role writes no restriction: the permission recomputes verbatim
        assertThat(permission.value.canPublish).isTrue
        assertThat(permission.value.canPublishSources).isEqualTo(PublicationSource.entries.toSet())
        Mockito.verify(liveKit, Mockito.never()).mutePublishedTrack(anyNotNull(), anyNotNull(), anyNotNull())
        Mockito.verify(liveKit).listParticipants(conference.roomName)
        Mockito.verifyNoMoreInteractions(liveKit)
    }

    @Test
    fun `both tabs of one member get the role broadcast`() {
        val first = memberConnection()
        val second = memberConnection()
        stubTargetOnline(first, second)
        stubRoleWrite(ParticipantRole.MODERATOR)

        val update = service.changeRole(conference, TARGET_PRINCIPAL, ParticipantRole.MODERATOR)

        assertThat(update.online).isTrue
        listOf(first, second).forEach { connection ->
            Mockito.verify(liveKit).updateParticipant(
                eqNotNull(conference.roomName),
                eqNotNull(connection.identity),
                anyNotNull(),
                anyNotNull(),
            )
        }
    }

    @Test
    fun `an offline role target persists the row and answers online false`() {
        stubRoleWrite(ParticipantRole.MODERATOR)

        val update = service.changeRole(conference, TARGET_PRINCIPAL, ParticipantRole.MODERATOR)

        assertThat(update.participant.role).isEqualTo(ParticipantRole.MODERATOR)
        assertThat(update.online).isFalse
        assertThat(actionCount(SET_ROLE)).isEqualTo(1.0)
        verifyNoLiveApplication()
    }

    @Test
    fun `an unknown role target answers participant_not_found with nothing counted`() {
        Mockito
            .`when`(participants.updateRole(anyNotNull(), anyNotNull(), anyNotNull()))
            .thenReturn(null)

        assertThatThrownBy { service.changeRole(conference, TARGET_PRINCIPAL, ParticipantRole.MODERATOR) }
            .isInstanceOfSatisfying(ParticipantNotFoundException::class.java) {
                assertThat(it.code).isEqualTo("participant_not_found")
            }

        verifyNoLiveApplication()
        assertThat(actionCount(SET_ROLE)).isZero()
    }

    @Test
    fun `a malformed role target identity answers the same participant_not_found`() {
        assertThatThrownBy { service.changeRole(conference, "not-an-identity", ParticipantRole.MODERATOR) }
            .isInstanceOf(ParticipantNotFoundException::class.java)

        Mockito.verifyNoInteractions(participants)
        Mockito.verifyNoInteractions(liveKit)
        assertThat(actionCount(SET_ROLE)).isZero()
    }

    @Test
    fun `a media-plane failure never fails the durable role answer`() {
        stubRoleWrite(ParticipantRole.MODERATOR)
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        val update = service.changeRole(conference, TARGET_PRINCIPAL, ParticipantRole.MODERATOR)

        // the committed row outranks the plane (Q9=A: every re-mint
        // re-applies the role), `online` degrades to false
        assertThat(update.participant.role).isEqualTo(ParticipantRole.MODERATOR)
        assertThat(update.online).isFalse
        assertThat(actionCount(SET_ROLE)).isEqualTo(1.0)
        verifyNoLiveApplication()
    }

    @Test
    fun `the kick removes every live connection of the member target`() {
        val first = memberConnection()
        val second = memberConnection()
        stubTargetOnline(first, second)
        stubTargetRow()

        service.kick(conference, TARGET_PRINCIPAL)

        Mockito.verify(liveKit).removeParticipant(conference.roomName, first.identity)
        Mockito.verify(liveKit).removeParticipant(conference.roomName, second.identity)
        assertThat(actionCount(KICK)).isEqualTo(1.0)
    }

    @Test
    fun `the kick touches no principal row — re-entry by the active link stays possible`() {
        stubTargetOnline(memberConnection())
        stubTargetRow()

        service.kick(conference, TARGET_PRINCIPAL)

        // кик ≠ бан: the row is resolved and NOTHING is written — the
        // role and restrictions survive verbatim for the re-mint
        Mockito.verify(participants).findByIdentity(eqNotNull(CONFERENCE_ID), eqNotNull(TARGET_PRINCIPAL))
        Mockito.verifyNoMoreInteractions(participants)
    }

    @Test
    fun `a guest kick removes the exact guest connection only`() {
        val guest = guestConnection()
        val unrelatedMember = LiveKitParticipant("u:${UUID.randomUUID()}:${UUID.randomUUID()}", null)
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(conference.roomName)))
            .thenReturn(listOf(guest, unrelatedMember))
        Mockito
            .`when`(participants.findByIdentity(eqNotNull(CONFERENCE_ID), eqNotNull(TARGET_GUEST_PRINCIPAL)))
            .thenReturn(updatedGuestRow(ParticipantRestrictions()))

        service.kick(conference, TARGET_GUEST_PRINCIPAL)

        Mockito.verify(liveKit).removeParticipant(conference.roomName, guest.identity)
        Mockito.verify(liveKit, Mockito.never()).removeParticipant(anyNotNull(), eqNotNull(unrelatedMember.identity))
    }

    @Test
    fun `an offline kick target is a counted success`() {
        stubTargetRow()

        service.kick(conference, TARGET_PRINCIPAL)

        Mockito.verify(liveKit, Mockito.never()).removeParticipant(anyNotNull(), anyNotNull())
        assertThat(actionCount(KICK)).isEqualTo(1.0)
    }

    @Test
    fun `an unknown kick target answers participant_not_found with nothing counted`() {
        Mockito
            .`when`(participants.findByIdentity(anyNotNull(), anyNotNull()))
            .thenReturn(null)

        assertThatThrownBy { service.kick(conference, TARGET_PRINCIPAL) }
            .isInstanceOfSatisfying(ParticipantNotFoundException::class.java) {
                assertThat(it.code).isEqualTo("participant_not_found")
            }

        Mockito.verifyNoInteractions(liveKit)
        assertThat(actionCount(KICK)).isZero()
    }

    @Test
    fun `a malformed kick target identity answers the same participant_not_found`() {
        assertThatThrownBy { service.kick(conference, "not-an-identity") }
            .isInstanceOf(ParticipantNotFoundException::class.java)

        Mockito.verifyNoInteractions(participants)
        Mockito.verifyNoInteractions(liveKit)
        assertThat(actionCount(KICK)).isZero()
    }

    @Test
    fun `a media-plane failure fails the kick — no durable fact outranks the disconnect`() {
        stubTargetRow()
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        assertThatThrownBy { service.kick(conference, TARGET_PRINCIPAL) }
            .isInstanceOf(ConferenceMediaUnavailableException::class.java)

        // nothing happened yet — the idempotent №51 retry IS the convergence
        assertThat(actionCount(KICK)).isZero()
    }

    private fun stubMemberRowWrite(restrictions: ParticipantRestrictions) {
        Mockito
            .`when`(participants.updateRestrictions(anyNotNull(), eqNotNull(TARGET_PRINCIPAL), anyNotNull()))
            .thenReturn(updatedMemberRow(restrictions))
    }

    private fun stubRoleWrite(role: ParticipantRole) {
        Mockito
            .`when`(participants.updateRole(anyNotNull(), eqNotNull(TARGET_PRINCIPAL), eqNotNull(role)))
            .thenReturn(updatedMemberRow(ParticipantRestrictions(), role))
    }

    private fun stubTargetRow() {
        Mockito
            .`when`(participants.findByIdentity(eqNotNull(CONFERENCE_ID), eqNotNull(TARGET_PRINCIPAL)))
            .thenReturn(updatedMemberRow(ParticipantRestrictions()))
    }

    private fun stubTargetOnline(vararg connections: LiveKitParticipant) {
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(conference.roomName)))
            .thenReturn(connections.toList())
    }

    private fun updatedMemberRow(
        restrictions: ParticipantRestrictions,
        role: ParticipantRole = ParticipantRole.PARTICIPANT,
    ): ConferenceParticipant =
        ConferenceParticipant(
            id = UUID.randomUUID(),
            conferenceId = CONFERENCE_ID,
            identity = ConferenceIdentity.member(TARGET_USER_ID),
            displayName = null,
            role = role,
            restrictions = restrictions,
            firstSeenAt = NOW,
        )

    private fun updatedGuestRow(restrictions: ParticipantRestrictions): ConferenceParticipant =
        ConferenceParticipant(
            id = UUID.randomUUID(),
            conferenceId = CONFERENCE_ID,
            identity = ConferenceIdentity.guest(CONN_ID),
            displayName = GUEST_NAME,
            role = ParticipantRole.PARTICIPANT,
            restrictions = restrictions,
            firstSeenAt = NOW,
        )

    /** A live member connection of the target — a FRESH connId per call (edge Q10). */
    private fun memberConnection(): LiveKitParticipant =
        LiveKitParticipant("u:$TARGET_USER_ID:${UUID.randomUUID()}", null)

    private fun guestConnection(): LiveKitParticipant =
        LiveKitParticipant(TARGET_GUEST_PRINCIPAL, """{"role":"participant","guest":true,"inviteId":"$INVITE_ID"}""")

    private fun grantsCaptor(): ArgumentCaptor<PublishGrants> = ArgumentCaptor.forClass(PublishGrants::class.java)

    private fun actionCount(action: String = SET_RESTRICTIONS): Double =
        meterRegistry
            .find(MODERATION_ACTIONS)
            .tag("action", action)
            .counter()
            ?.count() ?: 0.0

    /** Nothing was pushed to the plane: no permission update, no track mute. */
    private fun verifyNoLiveApplication() {
        Mockito
            .verify(liveKit, Mockito.never())
            .updateParticipant(anyNotNull(), anyNotNull(), anyNotNull(), anyNotNull())
        Mockito.verify(liveKit, Mockito.never()).mutePublishedTrack(anyNotNull(), anyNotNull(), anyNotNull())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T09:00:00Z")

        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000c")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0a7")

        val CREATOR_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000d")

        val TARGET_USER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b0")

        val CONN_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000e0")

        val TARGET_PRINCIPAL: String = "u:$TARGET_USER_ID"

        val TARGET_GUEST_PRINCIPAL: String = "g:$CONN_ID"

        const val GUEST_NAME = "bob-the-guest"

        const val INVITE_ID = "00000000-0000-4000-8000-000000000099"

        const val MAX_PARTICIPANTS = 16

        const val MODERATION_ACTIONS = "webchat_conference_moderation_actions_total"

        const val SET_RESTRICTIONS = "set_restrictions"

        const val SET_ROLE = "set_role"

        const val KICK = "kick"

        /** The capture() null-fallback of the non-null Kotlin parameters (the SessionServiceTest elvis seam). */
        val DUMMY_GRANTS = PublishGrants(canPublish = true, canPublishSources = emptySet())

        val MIC_ONLY = ParticipantRestrictions(micRestricted = true)

        val SPEECH_ONLY = ParticipantRestrictions(speechRestricted = true)

        val SCREEN_ONLY = ParticipantRestrictions(screenRestricted = true)
    }
}

/**
 * The Kotlin null-safety seam of Mockito on the conferences Kotlin
 * ports — the ConferenceServiceTest convention verbatim.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

/** [anyNotNull] for [ArgumentMatchers.eq]. */
@Suppress("UNCHECKED_CAST")
private fun <T> eqNotNull(value: T): T = ArgumentMatchers.eq(value) as T
