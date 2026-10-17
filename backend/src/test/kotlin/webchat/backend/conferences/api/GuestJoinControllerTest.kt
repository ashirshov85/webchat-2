package webchat.backend.conferences.api

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.api.dto.GuestJoinRequest
import webchat.backend.conferences.api.dto.InviteInfoView
import webchat.backend.conferences.api.dto.JoinTicket
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.conferences.domain.port.ConferenceRepository
import webchat.backend.conferences.domain.port.GuestInviteRepository
import webchat.backend.conferences.domain.service.ConferenceEndedException
import webchat.backend.conferences.domain.service.InviteExpiredException
import webchat.backend.conferences.domain.service.InviteInvalidException
import webchat.backend.conferences.domain.service.InviteNotFoundException
import webchat.backend.conferences.domain.service.InviteRevokedException
import webchat.backend.conferences.domain.service.JoinTokenService
import webchat.backend.conferences.livekit.GuestInviteNonce
import webchat.backend.conferences.livekit.GuestInviteTokens
import webchat.backend.conferences.livekit.InvalidGuestInviteTokenException
import webchat.backend.config.ConferencesProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T046 unit regression (tasks.md US4): the №54/№55 PUBLIC guest surface
 * pinned against api-contract.md 009 §3 — port fakes only, no Spring
 * context (the story IT T048 holds the SQL, the security `permitAll`
 * surface of T047 and the end-to-end legs):
 *
 *  * **№54** — the missing `inviteToken` 400; the FORGERY fold
 *    `{valid:false}` with NO reason and NO status read (the forgery
 *    modes are indistinguishable, api-contract §1 edge); the lifecycle
 *    answers `reason:"expired"|"revoked"|"ended"` and the valid answer
 *    carries `live` = the conference row's `active` status with NO
 *    media round-trip; the FINALIZE fold — the invite rows are deleted
 *    (Q18=A), the ended conference row is the only surviving witness —
 *    answers `reason:"ended"` (the T057 old-link shape);
 *  * **№55** — the ladder in the contract order: the name shape (`422`
 *    carrier, the server trim BEFORE any nonce work), the uniform
 *    `invite_invalid`, the row lifecycle `invite_expired|invite_revoked`,
 *    the `conference_ended` fold (row present OR deleted by finalize)
 *    and the `invite_not_found` no-witness refusal; the usable link
 *    delegates to [JoinTokenService.mintGuest] with the TRIMMED name;
 *    every link refusal counts
 *    `webchat_conference_joins_denied_total{reason=invite}` (FR-018,
 *    SC-003) while the `conference_ended` conference state deliberately
 *    does not.
 *
 * Arguments of the mocked Kotlin ports are stubbed through the
 * `*NotNull` wrappers (the ConferenceControllerTest convention).
 */
@Suppress("TooManyFunctions") // one leg per №54/№55 rule of the contract table
class GuestJoinControllerTest {
    private val tokens = Mockito.mock(GuestInviteTokens::class.java)
    private val invites = Mockito.mock(GuestInviteRepository::class.java)
    private val conferences = Mockito.mock(ConferenceRepository::class.java)
    private val joinTokenService = Mockito.mock(JoinTokenService::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val controller =
        GuestJoinController(
            tokens = tokens,
            invites = invites,
            conferences = conferences,
            joinTokenService = joinTokenService,
            properties = TEST_PROPERTIES,
            metrics = ConferenceMetrics(meterRegistry, Mockito.mock(JdbcTemplate::class.java)),
            clock = Clock { NOW },
        )

    private val conference: Conference = Conference.create(CONFERENCE_ID, CHAT_ID, MODERATOR_ID, MAX_PARTICIPANTS, T0)

    @BeforeEach
    fun stubTheNonceLayer() {
        Mockito
            .`when`(tokens.verify(NONCE))
            .thenReturn(GuestInviteNonce(INVITE_ID, CONFERENCE_ID, NOW + GUEST_INVITE_TTL))
        Mockito
            .`when`(tokens.verify(FORGED_NONCE))
            .thenThrow(InvalidGuestInviteTokenException("tampered"))
    }

    @Test
    fun `invite-info without the query parameter answers the 400 carrier`() {
        assertThatThrownBy { controller.inviteInfo(null) }.isInstanceOf(MissingInviteTokenException::class.java)
        assertThatThrownBy { controller.inviteInfo("  ") }.isInstanceOf(MissingInviteTokenException::class.java)
    }

    @Test
    fun `a forged nonce answers valid false with no reason and no status read`() {
        val view = controller.inviteInfo(FORGED_NONCE)

        assertThat(view).isEqualTo(InviteInfoView(valid = false))
        Mockito.verifyNoInteractions(invites, conferences)
    }

    @Test
    fun `a usable link of a created conference answers valid true with live false`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(activeInvite())
        Mockito.`when`(conferences.findById(CONFERENCE_ID)).thenReturn(conference)

        val view = controller.inviteInfo(NONCE)

        assertThat(view).isEqualTo(InviteInfoView(valid = true, live = false))
    }

    @Test
    fun `an active conference rides the live ux hint`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(activeInvite())
        Mockito.`when`(conferences.findById(CONFERENCE_ID)).thenReturn(conference.activate(NOW))

        val view = controller.inviteInfo(NONCE)

        assertThat(view).isEqualTo(InviteInfoView(valid = true, live = true))
    }

    @Test
    fun `a revoked row answers reason revoked`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(activeInvite().revoke(NOW))

        assertThat(controller.inviteInfo(NONCE)).isEqualTo(InviteInfoView(valid = false, reason = "revoked"))
    }

    @Test
    fun `an expired row answers reason expired`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(expiredInvite())

        assertThat(controller.inviteInfo(NONCE)).isEqualTo(InviteInfoView(valid = false, reason = "expired"))
    }

    @Test
    fun `an ended conference answers reason ended`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(activeInvite())
        Mockito
            .`when`(conferences.findById(CONFERENCE_ID))
            .thenReturn(conference.end(ConferenceEndReason.MODERATOR, NOW))

        assertThat(controller.inviteInfo(NONCE)).isEqualTo(InviteInfoView(valid = false, reason = "ended"))
    }

    @Test
    fun `the finalize fold answers reason ended from the surviving conference row`() {
        // Q18=A: the finalize transaction deleted the invite rows — the
        // ended conference row is the only witness of the old link (T057)
        Mockito
            .`when`(conferences.findById(CONFERENCE_ID))
            .thenReturn(conference.end(ConferenceEndReason.LAST_LEFT, NOW))

        assertThat(controller.inviteInfo(NONCE)).isEqualTo(InviteInfoView(valid = false, reason = "ended"))
    }

    @Test
    fun `a row gone without an ended conference answers the bare valid false`() {
        val view = controller.inviteInfo(NONCE)

        assertThat(view).isEqualTo(InviteInfoView(valid = false))
    }

    @Test
    fun `join rejects the name shape before any nonce work`() {
        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(NONCE, "   ")) }
            .isInstanceOf(webchat.backend.conferences.api.dto.InvalidGuestNameException::class.java)

        Mockito.verifyNoInteractions(tokens, invites, conferences, joinTokenService)
    }

    @Test
    fun `join of a forged nonce answers the uniform invite_invalid and counts the denial`() {
        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(FORGED_NONCE, GUEST_NAME)) }
            .isInstanceOfSatisfying(InviteInvalidException::class.java) {
                assertThat(it.code).isEqualTo("invite_invalid")
            }

        Mockito.verifyNoInteractions(invites, conferences, joinTokenService)
        assertThat(inviteDenials()).isEqualTo(1.0)
    }

    @Test
    fun `join of a blank token folds into the same uniform invite_invalid`() {
        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(null, GUEST_NAME)) }
            .isInstanceOf(InviteInvalidException::class.java)

        Mockito.verifyNoInteractions(tokens, invites)
        assertThat(inviteDenials()).isEqualTo(1.0)
    }

    @Test
    fun `join of a revoked link answers invite_revoked and counts the denial`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(activeInvite().revoke(NOW))

        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(NONCE, GUEST_NAME)) }
            .isInstanceOfSatisfying(InviteRevokedException::class.java) {
                assertThat(it.code).isEqualTo("invite_revoked")
            }

        Mockito.verifyNoInteractions(joinTokenService)
        assertThat(inviteDenials()).isEqualTo(1.0)
    }

    @Test
    fun `join of an expired link answers invite_expired`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(expiredInvite())

        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(NONCE, GUEST_NAME)) }
            .isInstanceOfSatisfying(InviteExpiredException::class.java) {
                assertThat(it.code).isEqualTo("invite_expired")
            }

        Mockito.verifyNoInteractions(joinTokenService)
        assertThat(inviteDenials()).isEqualTo(1.0)
    }

    @Test
    fun `join of a link without any witness answers invite_not_found`() {
        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(NONCE, GUEST_NAME)) }
            .isInstanceOfSatisfying(InviteNotFoundException::class.java) {
                assertThat(it.code).isEqualTo("invite_not_found")
            }

        Mockito.verifyNoInteractions(joinTokenService)
        assertThat(inviteDenials()).isEqualTo(1.0)
    }

    @Test
    fun `join of an ended conference answers conference_ended without an invite denial`() {
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(activeInvite())
        Mockito
            .`when`(conferences.findById(CONFERENCE_ID))
            .thenReturn(conference.end(ConferenceEndReason.MODERATOR, NOW))

        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(NONCE, GUEST_NAME)) }
            .isInstanceOfSatisfying(ConferenceEndedException::class.java) {
                assertThat(it.code).isEqualTo("conference_ended")
            }

        Mockito.verifyNoInteractions(joinTokenService)
        assertThat(inviteDenials()).isZero()
    }

    @Test
    fun `join of an old link after finalize answers conference_ended from the surviving row`() {
        Mockito
            .`when`(conferences.findById(CONFERENCE_ID))
            .thenReturn(conference.end(ConferenceEndReason.LAST_LEFT, NOW))

        assertThatThrownBy { controller.joinByInvite(GuestJoinRequest(NONCE, GUEST_NAME)) }
            .isInstanceOf(ConferenceEndedException::class.java)

        Mockito.verifyNoInteractions(joinTokenService)
        assertThat(inviteDenials()).isZero()
    }

    @Test
    fun `join of a usable link delegates the mint with the trimmed name`() {
        val active = conference.activate(NOW)!!
        Mockito.`when`(invites.findById(INVITE_ID)).thenReturn(activeInvite())
        Mockito.`when`(conferences.findById(CONFERENCE_ID)).thenReturn(active)
        Mockito
            .`when`(joinTokenService.mintGuest(eqNotNull(active), eqNotNull(INVITE_ID), eqNotNull(GUEST_NAME)))
            .thenReturn(TICKET)

        val ticket = controller.joinByInvite(GuestJoinRequest(NONCE, "  $GUEST_NAME  "))

        assertThat(ticket).isSameAs(TICKET)
        Mockito.verify(joinTokenService).mintGuest(active, INVITE_ID, GUEST_NAME)
        Mockito
            .verify(joinTokenService, Mockito.never())
            .mintGuest(anyNotNull(), anyNotNull(), eqNotNull("  $GUEST_NAME  "))
    }

    /** An ACTIVE, unexpired invite row of the live conference. */
    private fun activeInvite(): GuestInvite =
        GuestInvite.issue(
            id = INVITE_ID,
            conferenceId = CONFERENCE_ID,
            createdBy = MODERATOR_ID,
            at = NOW - GUEST_INVITE_TTL,
            ttl = GUEST_INVITE_TTL + GUEST_INVITE_TTL,
        )

    /** A row whose horizon has passed at [NOW] — signed, expired, disclosable. */
    private fun expiredInvite(): GuestInvite =
        GuestInvite.issue(
            id = INVITE_ID,
            conferenceId = CONFERENCE_ID,
            createdBy = MODERATOR_ID,
            at = NOW - GUEST_INVITE_TTL,
            ttl = GUEST_INVITE_TTL - Duration.ofSeconds(1),
        )

    private fun inviteDenials(): Double =
        meterRegistry
            .find(JOINS_DENIED)
            .tag("reason", "invite")
            .counter()
            ?.count() ?: 0.0

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-17T12:00:00Z")

        val T0: Instant = Instant.parse("2026-10-17T11:00:00Z")

        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000c")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0a7")

        val MODERATOR_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000d")

        val INVITE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000004a")

        const val NONCE = "a.guest.invite.nonce"

        const val FORGED_NONCE = "forged.nonce.value"

        const val GUEST_NAME = "carol-guest"

        const val MAX_PARTICIPANTS = 16

        val GUEST_INVITE_TTL: Duration = Duration.ofHours(24)

        const val JOINS_DENIED = "webchat_conference_joins_denied_total"

        val TICKET =
            JoinTicket(
                token = "ticket-token",
                wsUrl = "ws://localhost:7880",
                identity = "g:00000000-0000-4000-8000-00000000009e",
                name = GUEST_NAME,
                expiresAt = NOW.plusSeconds(900),
            )

        val TEST_PROPERTIES =
            ConferencesProperties(
                maxParticipants = 16,
                tokenTtl = Duration.ofSeconds(900),
                guestInviteTtl = Duration.ofHours(24),
                emptyRoomTimeout = Duration.ofSeconds(60),
                departureTimeout = Duration.ofSeconds(60),
                maxDuration = Duration.ofHours(4),
                warningBeforeEnd = Duration.ofMinutes(5),
                reconnectWindow = Duration.ofSeconds(120),
                guestNameMaxLength = 50,
                sweepInterval = Duration.ofSeconds(60),
                roomLostGrace = Duration.ofSeconds(120),
                livekit =
                    ConferencesProperties.LiveKit(
                        "http://localhost:7880",
                        "testkey",
                        "0123456789abcdef0123456789abcdef",
                    ),
            )
    }
}

/**
 * The Kotlin null-safety seam of Mockito on the conferences Kotlin
 * ports — the ConferenceControllerTest convention verbatim.
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyNotNull(): T = ArgumentMatchers.any() as T

/** [anyNotNull] for [ArgumentMatchers.eq]. */
@Suppress("UNCHECKED_CAST")
private fun <T> eqNotNull(value: T): T = ArgumentMatchers.eq(value) as T
