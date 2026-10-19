package webchat.backend.conferences

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import webchat.backend.conferences.domain.model.PublicationSource
import webchat.backend.conferences.domain.model.PublishGrants
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.livekit.LiveKitServerAdminClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one deliberate «fake-порт» of the T040 story IT: a RECORDING
 * decorator over the PRODUCTION [LiveKitServerAdminClient], installed
 * `@Primary` by the IT configuration below — the real plane still
 * serves every delegated call (rooms, roster truth and disconnects
 * stay LIVE), but the immediate-application legs become observable:
 * the IT asserts the ADAPTER-visible `UpdateParticipant` /
 * `MutePublishedTrack` / `RemoveParticipant` traffic with the exact
 * grants and metadata the service computed, without mocking the media
 * plane away (the task table leg «UpdateParticipant/MutePublishedTrack
 * вызываются адаптеру»).
 */
class RecordingLiveKitAdminClient(
    private val delegate: LiveKitAdminClient,
) : LiveKitAdminClient by delegate {
    /** One adapter-visible `UpdateParticipant` call — the pushed permission+metadata pair. */
    data class ParticipantUpdate(
        val room: String,
        val identity: String,
        val permission: PublishGrants,
        val metadata: String,
    )

    /** One adapter-visible `MutePublishedTrack` call — the muted source kind. */
    data class TrackMute(
        val room: String,
        val identity: String,
        val source: PublicationSource,
    )

    /** One adapter-visible `RemoveParticipant` call. */
    data class Removal(
        val room: String,
        val identity: String,
    )

    private val participantUpdates = CopyOnWriteArrayList<ParticipantUpdate>()

    private val trackMutes = CopyOnWriteArrayList<TrackMute>()

    private val removals = CopyOnWriteArrayList<Removal>()

    override fun updateParticipant(
        room: String,
        identity: String,
        permission: PublishGrants,
        metadata: String,
    ) {
        participantUpdates += ParticipantUpdate(room, identity, permission, metadata)
        delegate.updateParticipant(room, identity, permission, metadata)
    }

    override fun mutePublishedTrack(
        room: String,
        identity: String,
        source: PublicationSource,
    ) {
        trackMutes += TrackMute(room, identity, source)
        delegate.mutePublishedTrack(room, identity, source)
    }

    override fun removeParticipant(
        room: String,
        identity: String,
    ) {
        removals += Removal(room, identity)
        delegate.removeParticipant(room, identity)
    }

    /** The recorded `UpdateParticipant` calls of one room — every test owns its own room. */
    fun updatesOf(room: String): List<ParticipantUpdate> = participantUpdates.filter { it.room == room }

    /** The recorded `MutePublishedTrack` calls of one room. */
    fun mutesOf(room: String): List<TrackMute> = trackMutes.filter { it.room == room }

    /** The recorded `RemoveParticipant` calls of one room. */
    fun removalsOf(room: String): List<Removal> = removals.filter { it.room == room }
}

/**
 * T040 (tasks.md Phase 5, US3) — the story IT of the №48–№51
 * moderation surface against the REAL stack (the
 * [ConferenceCreateJoinIT] shape: Testcontainers PG+Redis via
 * [ConferencesTestSupport], the shared LiveKit plane, №47-minted
 * signal-only room connections) plus the one «fake-порт» above — a
 * recording decorator over the production adapter, so the immediate
 * application legs assert the ADAPTER-visible calls while the plane
 * itself stays real. One leg per rule of the T040 task table:
 *
 *  * **the role gate** — a plain participant WITH a row and a chat
 *    member WITHOUT any row both answer `403 moderator_required` on
 *    every №48–№51 endpoint, with nothing written, nothing counted
 *    and nothing pushed to the plane;
 *  * **Q4=A / invariant 3 (Q5=A)** — the №49 «право слова» SET blocks
 *    the whole publish side of the NEXT mint (`canPublish=false`, no
 *    sources), and the №49 source SET (mic+screen) reproduces
 *    verbatim in the re-mint grants (`camera` the only survivor) —
 *    the ROW drives every mint, and unlike T021 it is the REAL
 *    endpoint writing it now;
 *  * **Q9=A** — the №50 moderator role survives the reconnect: the
 *    fresh mint carries `{"role":"moderator"}` metadata, and the
 *    RECONNECTED moderator acts (his №49 succeeds);
 *  * **Q21=A** — equal moderators: one demotes ANOTHER moderator (who
 *    loses the gate immediately), re-promotes him and KICKS him;
 *  * **кик ≠ бан** — the №51 kick breaks the live connection through
 *    `RemoveParticipant`, touches NO principal row (the same row id,
 *    role and the four flags verbatim), and the kicked member
 *    re-enters by the still-active link (№47) with a FRESH connection
 *    identity;
 *  * **the fake-порт** — the №49 immediate application reaches the
 *    ADAPTER for EVERY live connection of the target (two tabs, edge
 *    Q10): the recomputed grants plus the member metadata through
 *    `UpdateParticipant`, and the point-restricted sources through
 *    `MutePublishedTrack` (screen removes BOTH screen kinds, Q22=A;
 *    the camera is never muted).
 */
@Suppress("TooManyFunctions") // one leg per rule of the T040 task table
class ConferenceModerationIT : ConferencesTestSupport() {
    @Autowired
    private lateinit var recordingLiveKit: RecordingLiveKitAdminClient

    /**
     * Wraps the production Twirp adapter with the recording port —
     * `@Primary` so every `LiveKitAdminClient` consumer (controller,
     * services, the test support itself) observes the adapter-visible
     * calls while the plane keeps serving them.
     */
    @TestConfiguration
    class RecordingLiveKitConfig {
        @Bean
        @Primary
        fun recordingLiveKitAdminClient(delegate: LiveKitServerAdminClient): RecordingLiveKitAdminClient =
            RecordingLiveKitAdminClient(delegate)
    }

    /** The role gate: a participant and a row-less member are both refused with nothing written. */
    @Test
    fun `a non-moderator is refused 403 on every moderation endpoint`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val chatId = groupChatOf(owner, bob, carol)
        val conferenceId = createConferenceOk(owner, chatId)
        joinConferenceOk(bob, conferenceId)
        val room = roomNameOf(conferenceId)
        val carolPrincipal = memberPrincipalOf(carol.id)

        val refusals =
            listOf(
                endConference(bob, conferenceId),
                setParticipantRestrictions(bob, conferenceId, carolPrincipal, micRestricted = true),
                setParticipantRole(bob, conferenceId, carolPrincipal, MODERATOR_ROLE),
                kickConferenceParticipant(bob, conferenceId, carolPrincipal),
                endConference(carol, conferenceId),
            )

        refusals.forEach { refusal ->
            assertThat(refusal.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
            assertThat(problemCode(refusal)).isEqualTo(MODERATOR_REQUIRED_CODE)
        }

        assertThat(recordingLiveKit.updatesOf(room))
            .overridingErrorMessage("a refused gate must push nothing to the plane")
            .isEmpty()
        assertThat(recordingLiveKit.mutesOf(room)).isEmpty()
        val bobRow = memberRowOf(conferenceId, bob.id)
        assertThat(bobRow)
            .overridingErrorMessage("the refused gate leaves the joined participant's row in place")
            .isNotNull
        assertThat(bobRow!![ROLE_COLUMN]).isEqualTo(PARTICIPANT_ROLE)
        assertThat(conferenceRowsOf(chatId).single()[STATUS_FIELD])
            .overridingErrorMessage("the refused №48 legs end nothing")
            .isEqualTo(CREATED_STATUS)
    }

    /** Q4=A + invariant 3 (Q5=A): the №49 SET drives the grants of every following mint. */
    @Test
    fun `the speech restriction blocks every publication and the SET reproduces in the re-mint`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        connectMember(bob, conferenceId)

        val silenced =
            setParticipantRestrictions(owner, conferenceId, memberPrincipalOf(bob.id), speechRestricted = true)
        assertThat(silenced.statusCode).isEqualTo(HttpStatus.OK)
        val silencedView = objectMapper.readTree(silenced.body)
        assertThat(silencedView[ROLE_FIELD].asText()).isEqualTo(PARTICIPANT_ROLE)
        assertThat(silencedView[SPEECH_RESTRICTED_FIELD].asBoolean()).isTrue
        assertThat(silencedView[ONLINE_FIELD].asBoolean())
            .overridingErrorMessage(
                "the online flag rides the live connection observed during the application (SC-005)",
            ).isTrue

        val silencedGrants = videoClaimsOf(joinConferenceOk(bob, conferenceId))
        assertThat(silencedGrants[CAN_PUBLISH_FIELD])
            .overridingErrorMessage("«право слова» blocks ANY publication of the next mint (Q4=A)")
            .isEqualTo(false)
        assertThat(silencedGrants[CAN_PUBLISH_SOURCES_FIELD]).isEqualTo(emptyList<String>())

        val restricted =
            setParticipantRestrictions(
                owner,
                conferenceId,
                memberPrincipalOf(bob.id),
                micRestricted = true,
                screenRestricted = true,
            )
        assertThat(restricted.statusCode).isEqualTo(HttpStatus.OK)
        val restrictedGrants = videoClaimsOf(joinConferenceOk(bob, conferenceId))
        assertThat(restrictedGrants[CAN_PUBLISH_FIELD]).isEqualTo(true)
        assertThat(restrictedGrants[CAN_PUBLISH_SOURCES_FIELD])
            .overridingErrorMessage("mic+screen leave the camera the only source of the re-mint (Q5/Q22=A)")
            .isEqualTo(listOf(CAMERA_SOURCE))
    }

    /** Q9=A: the promoted moderator keeps the role across the reconnect — metadata and the gate. */
    @Test
    fun `the moderator role survives the reconnect`() {
        val owner = messagingUser("owner")
        val bob = messagingUser("bob")
        val carol = messagingUser("carol")
        val chatId = groupChatOf(owner, bob, carol)
        val conferenceId = createConferenceOk(owner, chatId)
        connectMember(bob, conferenceId)
        joinConferenceOk(carol, conferenceId)

        val promoted = setParticipantRole(owner, conferenceId, memberPrincipalOf(bob.id), MODERATOR_ROLE)
        assertThat(promoted.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(promoted.body)[ROLE_FIELD].asText()).isEqualTo(MODERATOR_ROLE)

        val reconnected = joinConferenceOk(bob, conferenceId)
        assertThat(ticketMetadataOf(reconnected))
            .overridingErrorMessage("the re-mint recomputes the metadata from the persisted row (Q9=A)")
            .isEqualTo(MODERATOR_METADATA)

        val bobRestricts =
            setParticipantRestrictions(
                bob,
                conferenceId,
                memberPrincipalOf(carol.id),
                micRestricted = true,
            )
        assertThat(bobRestricts.statusCode)
            .overridingErrorMessage("the RECONNECTED moderator acts — his №49 succeeds")
            .isEqualTo(HttpStatus.OK)
        assertThat(memberRowOf(conferenceId, carol.id)!![MIC_COLUMN]).isEqualTo(true)
    }

    /** Q21=A: equal moderators — one demotes and kicks ANOTHER moderator. */
    @Test
    fun `a moderator demotes and kicks another moderator`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        joinConferenceOk(bob, conferenceId)

        val promoted = setParticipantRole(owner, conferenceId, memberPrincipalOf(bob.id), MODERATOR_ROLE)
        assertThat(promoted.statusCode).isEqualTo(HttpStatus.OK)

        val demoted = setParticipantRole(owner, conferenceId, memberPrincipalOf(bob.id), PARTICIPANT_ROLE)
        assertThat(demoted.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(demoted.body)[ROLE_FIELD].asText()).isEqualTo(PARTICIPANT_ROLE)

        val refused =
            setParticipantRestrictions(bob, conferenceId, memberPrincipalOf(owner.id), micRestricted = true)
        assertThat(refused.statusCode)
            .overridingErrorMessage("the demoted moderator loses the gate IMMEDIATELY")
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(problemCode(refused)).isEqualTo(MODERATOR_REQUIRED_CODE)

        assertThat(
            setParticipantRole(owner, conferenceId, memberPrincipalOf(bob.id), MODERATOR_ROLE).statusCode,
        ).isEqualTo(HttpStatus.OK)
        val bobConnection = connectMember(bob, conferenceId)

        val kicked = kickConferenceParticipant(owner, conferenceId, memberPrincipalOf(bob.id))
        assertThat(kicked.statusCode)
            .overridingErrorMessage("a moderator may kick a fellow moderator — equal rights (Q21=A)")
            .isEqualTo(HttpStatus.NO_CONTENT)
        bobConnection.awaitClosedByServer()
    }

    /** кик ≠ бан: the row survives verbatim and the still-active link re-admits the kicked member. */
    @Test
    fun `a kicked member re-enters by the still-active link with the principal row untouched`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val firstTicket = ticketOf(joinConferenceOk(bob, conferenceId))
        val firstIdentity = firstTicket[IDENTITY_FIELD].asText()
        val bobConnection = connectTicket(firstTicket)
        val rowBefore = memberRowOf(conferenceId, bob.id)!!

        val kicked = kickConferenceParticipant(owner, conferenceId, memberPrincipalOf(bob.id))
        assertThat(kicked.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        bobConnection.awaitClosedByServer()
        assertThat(recordingLiveKit.removalsOf(roomNameOf(conferenceId)).map { it.identity })
            .overridingErrorMessage("the disconnect rode RemoveParticipant of the LIVE connection identity")
            .contains(firstIdentity)

        val secondTicket = ticketOf(joinConferenceOk(bob, conferenceId))
        assertThat(secondTicket[IDENTITY_FIELD].asText())
            .overridingErrorMessage("the re-entry is a FRESH connection identity (a new slot, edge Q10)")
            .isNotEqualTo(firstIdentity)

        val rowAfter = memberRowOf(conferenceId, bob.id)!!
        assertThat(rowAfter[ID_COLUMN])
            .overridingErrorMessage("the kick wrote NOTHING — the SAME principal row (кик ≠ бан)")
            .isEqualTo(rowBefore[ID_COLUMN])
        assertThat(rowAfter[ROLE_COLUMN]).isEqualTo(rowBefore[ROLE_COLUMN])
        assertThat(rowAfter[MIC_COLUMN]).isEqualTo(rowBefore[MIC_COLUMN])
        assertThat(rowAfter[CAMERA_COLUMN]).isEqualTo(rowBefore[CAMERA_COLUMN])
        assertThat(rowAfter[SCREEN_COLUMN]).isEqualTo(rowBefore[SCREEN_COLUMN])
        assertThat(rowAfter[SPEECH_COLUMN]).isEqualTo(rowBefore[SPEECH_COLUMN])
    }

    /**
     * The fake-порт leg: the immediate №49 application reaches the
     * ADAPTER for EVERY live connection of the target (two tabs, edge
     * Q10) — the recomputed grants and the member metadata through
     * `UpdateParticipant`, the point-restricted sources through
     * `MutePublishedTrack` (screen removes BOTH kinds, Q22=A; the
     * camera is never muted).
     */
    @Test
    fun `the adapter receives the update and the track mutes for every live connection`() {
        val (owner, bob) = messagingPair()
        val chatId = ensureChatOk(owner, bob.id)
        val conferenceId = createConferenceOk(owner, chatId)
        val room = roomNameOf(conferenceId)
        val firstTicket = ticketOf(joinConferenceOk(bob, conferenceId))
        val secondTicket = ticketOf(joinConferenceOk(bob, conferenceId))
        val firstIdentity = firstTicket[IDENTITY_FIELD].asText()
        val secondIdentity = secondTicket[IDENTITY_FIELD].asText()
        connectTicket(firstTicket)
        connectTicket(secondTicket)
        awaitRoomParticipants(room) { it.size == CONNECTED_TWO }

        val restricted =
            setParticipantRestrictions(
                owner,
                conferenceId,
                memberPrincipalOf(bob.id),
                micRestricted = true,
                screenRestricted = true,
            )
        assertThat(restricted.statusCode).isEqualTo(HttpStatus.OK)

        val updates = recordingLiveKit.updatesOf(room)
        assertThat(updates.map { it.identity })
            .overridingErrorMessage("every LIVE connection of the principal is applied to (the two-tabs edge Q10)")
            .containsExactlyInAnyOrder(firstIdentity, secondIdentity)
        updates.forEach { update ->
            assertThat(update.permission.canPublish).isTrue
            assertThat(update.permission.canPublishSources)
                .overridingErrorMessage("the pushed permission is the SAME grants computation as the mint (Q5=A)")
                .isEqualTo(setOf(PublicationSource.CAMERA))
            assertThat(update.metadata).isEqualTo(MEMBER_METADATA)
        }

        val mutes = recordingLiveKit.mutesOf(room)
        assertThat(mutes.map { it.source }.toSet())
            .overridingErrorMessage("mic+screen mute the microphone and BOTH screen kinds, never the camera (Q22=A)")
            .isEqualTo(
                setOf(
                    PublicationSource.MICROPHONE,
                    PublicationSource.SCREEN_SHARE,
                    PublicationSource.SCREEN_SHARE_AUDIO,
                ),
            )
        listOf(firstIdentity, secondIdentity).forEach { identity ->
            assertThat(mutes.filter { it.identity == identity }.map { it.source })
                .overridingErrorMessage("each live connection gets its own track mutes")
                .containsExactlyInAnyOrder(
                    PublicationSource.MICROPHONE,
                    PublicationSource.SCREEN_SHARE,
                    PublicationSource.SCREEN_SHARE_AUDIO,
                )
        }
    }

    private companion object {
        const val CREATED_STATUS = "created"

        const val STATUS_FIELD = "status"

        const val ID_COLUMN = "id"

        const val MIC_COLUMN = "mic_restricted"

        const val CAMERA_COLUMN = "camera_restricted"

        const val SCREEN_COLUMN = "screen_restricted"

        const val SPEECH_COLUMN = "speech_restricted"

        const val CONNECTED_TWO = 2

        const val MEMBER_METADATA = """{"role":"participant","guest":false}"""

        const val MODERATOR_METADATA = """{"role":"moderator","guest":false}"""
    }
}
