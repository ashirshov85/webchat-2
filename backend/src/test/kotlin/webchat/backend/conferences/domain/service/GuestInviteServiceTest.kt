package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.api.dto.GuestInviteView
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.conferences.domain.model.GuestInviteStatus
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.GuestInviteIssueResult
import webchat.backend.conferences.domain.port.GuestInviteNonces
import webchat.backend.conferences.domain.port.GuestInviteRepository
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.config.ConferencesProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T045 unit regression (tasks.md US4): the №52/№53 guest-link legs
 * pinned against research.md §6 / livekit-integration.md §6.1 — port
 * fakes all the way down (the `ModerationServiceTest` convention; the
 * story IT T048 holds the SQL and the end-to-end legs):
 *
 *  * **№52** — the ended re-check answers the SAME `409
 *    conference_ended` the caller's gate already owns (the
 *    concurrent-finalize backstop) with nothing written; the happy
 *    path stores the row with `now + conferences.guest-invite-ttl`
 *    (24 h default, Q19=A), mints the nonce FROM THE STORED row (the
 *    JWT `exp` and the status horizon are one value) and answers the
 *    PUBLIC join URL `{app.public-base-url}/join/{nonce}`; the link is
 *    never posted to the chat (FR-009 — the service has no message
 *    port at all);
 *  * **the Q17=A replacement** — the previous ACTIVE link comes back
 *    from the issue transaction and its connected guests disconnect
 *    post-commit by the `metadata.inviteId` match (Q13=B): members,
 *    guests of OTHER links and unset/malformed metadata never match;
 *    a conference with NO previous link never touches the plane
 *    (every earlier link was already revoked — and disconnected — at
 *    its own №53/№52);
 *  * **№53** — the path carries no invite id: the conditional
 *    revoke-of-the-conference's-active resolves it; `null` — never
 *    issued, the repeat of an already-revoked link — answers the
 *    uniform `404 guest_invite_not_found` with no plane round-trip;
 *    the win disconnects the guests of the revoked link exactly as
 *    №52's replacement does;
 *  * **the post-commit rule** — a media-plane failure NEVER fails the
 *    committed answer of either leg (the №52/№53 contract tables
 *    define no media-failure code): the revoked row already blocks
 *    every new entry, the stragglers converge by their token TTL.
 */
@Suppress("TooManyFunctions") // one leg per №52/№53 rule of livekit-integration.md §6.1
class GuestInviteServiceTest {
    private val invites = Mockito.mock(GuestInviteRepository::class.java)
    private val nonces = Mockito.mock(GuestInviteNonces::class.java)
    private val liveKit = Mockito.mock(LiveKitAdminClient::class.java)

    private val service =
        GuestInviteService(
            invites = invites,
            nonces = nonces,
            liveKit = liveKit,
            properties = PROPERTIES,
            objectMapper = ObjectMapper(),
            clock = Clock { NOW },
            publicBaseUrl = PUBLIC_BASE_URL,
        )

    private val conference: Conference =
        Conference.create(CONFERENCE_ID, CHAT_ID, MODERATOR_ID, MAX_PARTICIPANTS, NOW).activate(NOW)!!

    @Test
    fun `issue of an ended conference answers conference_ended with nothing written`() {
        val ended = conference.end(ConferenceEndReason.MODERATOR, NOW)!!

        assertThatThrownBy { service.issue(ended, MODERATOR_ID) }
            .isInstanceOfSatisfying(ConferenceEndedException::class.java) {
                assertThat(it.code).isEqualTo("conference_ended")
            }

        Mockito.verifyNoInteractions(invites, nonces, liveKit)
    }

    @Test
    fun `issue stores the row with the configured ttl and mints the nonce of the stored row`() {
        Mockito
            .`when`(invites.issue(anyNotNull()))
            .thenAnswer { GuestInviteIssueResult(invite = it.arguments[0] as GuestInvite, revokedPrevious = null) }
        Mockito.`when`(nonces.mint(anyNotNull())).thenReturn(NONCE)

        val view = service.issue(conference, MODERATOR_ID)

        val stored = inviteCaptor()
        Mockito.verify(invites).issue(stored.capture() ?: DUMMY_INVITE)
        assertThat(stored.value.conferenceId).isEqualTo(CONFERENCE_ID)
        assertThat(stored.value.createdBy).isEqualTo(MODERATOR_ID)
        assertThat(stored.value.status).isEqualTo(GuestInviteStatus.ACTIVE)
        assertThat(stored.value.createdAt).isEqualTo(NOW)
        assertThat(stored.value.expiresAt)
            .overridingErrorMessage("the horizon is now + conferences.guest-invite-ttl (24 h default, Q19=A)")
            .isEqualTo(NOW.plus(PROPERTIES.guestInviteTtl))
        // the nonce is minted FROM THE STORED row — one horizon for the JWT exp and the status row
        Mockito.verify(nonces).mint(stored.value)
        val expected =
            GuestInviteView(
                inviteUrl = "$PUBLIC_BASE_URL/join/$NONCE",
                expiresAt = NOW.plus(GUEST_INVITE_TTL),
            )
        assertThat(view).isEqualTo(expected)
    }

    @Test
    fun `the replaced link disconnects its connected guests post-commit by metadata inviteId`() {
        val previous = activeInvite(PREVIOUS_INVITE_ID)
        val replacedGuest = guestConnection(PREVIOUS_INVITE_ID)
        val otherLinkGuest = guestConnection(OTHER_INVITE_ID)
        val member = LiveKitParticipant("u:$MODERATOR_ID:${UUID.randomUUID()}", null)
        val noMetadataGuest = LiveKitParticipant("g:${UUID.randomUUID()}", null)
        val malformedMetadataGuest = LiveKitParticipant("g:${UUID.randomUUID()}", "not-json")
        Mockito
            .`when`(invites.issue(anyNotNull()))
            .thenReturn(GuestInviteIssueResult(invite = activeInvite(UUID.randomUUID()), revokedPrevious = previous))
        Mockito.`when`(nonces.mint(anyNotNull())).thenReturn(NONCE)
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(conference.roomName)))
            .thenReturn(listOf(replacedGuest, otherLinkGuest, member, noMetadataGuest, malformedMetadataGuest))

        service.issue(conference, MODERATOR_ID)

        // Q13=B: ONLY the connection of the replaced link goes — a
        // member, a guest of another link, unset and malformed metadata
        // never match
        Mockito.verify(liveKit).removeParticipant(conference.roomName, replacedGuest.identity)
        Mockito.verify(liveKit, Mockito.times(1)).removeParticipant(anyNotNull(), anyNotNull())
        listOf(otherLinkGuest, member, noMetadataGuest, malformedMetadataGuest).forEach { untouched ->
            Mockito.verify(liveKit, Mockito.never()).removeParticipant(conference.roomName, untouched.identity)
        }
    }

    @Test
    fun `the first link of a conference never touches the media plane`() {
        Mockito
            .`when`(invites.issue(anyNotNull()))
            .thenAnswer { GuestInviteIssueResult(invite = it.arguments[0] as GuestInvite, revokedPrevious = null) }
        Mockito.`when`(nonces.mint(anyNotNull())).thenReturn(NONCE)

        service.issue(conference, MODERATOR_ID)

        // nothing was replaced — no guest can still hold a live inviteId
        Mockito.verifyNoInteractions(liveKit)
    }

    @Test
    fun `a media-plane failure never fails the committed issue`() {
        Mockito
            .`when`(invites.issue(anyNotNull()))
            .thenReturn(
                GuestInviteIssueResult(
                    invite = activeInvite(UUID.randomUUID()),
                    revokedPrevious = activeInvite(PREVIOUS_INVITE_ID),
                ),
            )
        Mockito.`when`(nonces.mint(anyNotNull())).thenReturn(NONCE)
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        val view = service.issue(conference, MODERATOR_ID)

        // the committed revoke+insert outranks the plane: the №52 answer stays
        assertThat(view.inviteUrl).isEqualTo("$PUBLIC_BASE_URL/join/$NONCE")
    }

    @Test
    fun `revoke of no active link answers guest_invite_not_found with no plane round-trip`() {
        Mockito.`when`(invites.revokeActiveOf(CONFERENCE_ID, NOW)).thenReturn(null)

        assertThatThrownBy { service.revoke(conference) }
            .isInstanceOfSatisfying(GuestInviteNotFoundException::class.java) {
                assertThat(it.code).isEqualTo("guest_invite_not_found")
            }

        Mockito.verifyNoInteractions(liveKit)
    }

    @Test
    fun `revoke resolves the conference's active link and disconnects its guests`() {
        val revoked = activeInvite(PREVIOUS_INVITE_ID).revoke(NOW)
        Mockito.`when`(invites.revokeActiveOf(CONFERENCE_ID, NOW)).thenReturn(revoked)
        val connected = guestConnection(PREVIOUS_INVITE_ID)
        val otherLink = guestConnection(OTHER_INVITE_ID)
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(conference.roomName)))
            .thenReturn(listOf(connected, otherLink))

        service.revoke(conference)

        Mockito.verify(invites).revokeActiveOf(CONFERENCE_ID, NOW)
        Mockito.verify(liveKit).removeParticipant(conference.roomName, connected.identity)
        Mockito.verify(liveKit, Mockito.never()).removeParticipant(conference.roomName, otherLink.identity)
    }

    @Test
    fun `revoke of a link with no connected guests is a plane-read-only success`() {
        Mockito.`when`(invites.revokeActiveOf(CONFERENCE_ID, NOW)).thenReturn(activeInvite(PREVIOUS_INVITE_ID))
        Mockito
            .`when`(liveKit.listParticipants(eqNotNull(conference.roomName)))
            .thenReturn(listOf(LiveKitParticipant("u:$MODERATOR_ID:${UUID.randomUUID()}", null)))

        service.revoke(conference)

        Mockito.verify(liveKit, Mockito.never()).removeParticipant(anyNotNull(), anyNotNull())
    }

    @Test
    fun `a media-plane failure never fails the committed revoke`() {
        Mockito
            .`when`(invites.revokeActiveOf(CONFERENCE_ID, NOW))
            .thenReturn(activeInvite(PREVIOUS_INVITE_ID))
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        // the revoked row already blocks every new entry — the №53
        // answer stays, the stragglers converge by their token TTL
        service.revoke(conference)
    }

    private fun activeInvite(id: UUID): GuestInvite =
        GuestInvite.issue(
            id = id,
            conferenceId = CONFERENCE_ID,
            createdBy = MODERATOR_ID,
            at = NOW,
            ttl = GUEST_INVITE_TTL,
        )

    /** A live guest connection carrying [inviteId] in its participant metadata — the №55 mint shape. */
    private fun guestConnection(inviteId: UUID): LiveKitParticipant =
        LiveKitParticipant(
            identity = "g:${UUID.randomUUID()}",
            metadata = """{"role":"participant","guest":true,"inviteId":"$inviteId"}""",
        )

    private fun inviteCaptor(): ArgumentCaptor<GuestInvite> = ArgumentCaptor.forClass(GuestInvite::class.java)

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T09:00:00Z")

        val GUEST_INVITE_TTL: Duration = Duration.ofHours(24)

        val PROPERTIES =
            ConferencesProperties(
                maxParticipants = MAX_PARTICIPANTS,
                tokenTtl = Duration.ofSeconds(900),
                guestInviteTtl = GUEST_INVITE_TTL,
                emptyRoomTimeout = Duration.ofSeconds(60),
                departureTimeout = Duration.ofSeconds(60),
                maxDuration = Duration.ofHours(4),
                warningBeforeEnd = Duration.ofMinutes(5),
                reconnectWindow = Duration.ofSeconds(120),
                guestNameMaxLength = 50,
                sweepInterval = Duration.ofSeconds(60),
                roomLostGrace = Duration.ofSeconds(120),
                livekit = ConferencesProperties.LiveKit("http://livekit:7880", "devkey", "secret"),
            )

        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000044c")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0a7")

        val MODERATOR_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a11c")

        val PREVIOUS_INVITE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000f1")

        val OTHER_INVITE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000f2")

        const val MAX_PARTICIPANTS = 16

        const val PUBLIC_BASE_URL = "http://localhost:5173"

        const val NONCE = "es256-nonce-jwt"

        /** The capture() null-fallback of the non-null Kotlin parameter (the ModerationServiceTest elvis seam). */
        val DUMMY_INVITE =
            GuestInvite(
                id = UUID.randomUUID(),
                conferenceId = CONFERENCE_ID,
                createdBy = MODERATOR_ID,
                createdAt = NOW,
                expiresAt = NOW.plus(GUEST_INVITE_TTL),
            )
    }
}

/**
 * The Kotlin null-safety seam of Mockito on the conferences Kotlin
 * ports — the ModerationServiceTest convention verbatim.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

/** [anyNotNull] for [ArgumentMatchers.eq]. */
@Suppress("UNCHECKED_CAST")
private fun <T> eqNotNull(value: T): T = ArgumentMatchers.eq(value) as T
