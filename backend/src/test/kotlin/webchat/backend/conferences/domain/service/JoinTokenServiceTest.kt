package webchat.backend.conferences.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.auth.domain.port.Clock
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.LiveKitAdminClient
import webchat.backend.conferences.domain.port.LiveKitParticipant
import webchat.backend.conferences.domain.port.MemberNameReader
import webchat.backend.conferences.domain.port.ParticipantRepository
import webchat.backend.conferences.livekit.LiveKitServerTokenMinter
import webchat.backend.config.ConferencesProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T018 unit regression (tasks.md US1): the №47 member mint pinned
 * against the FR-015 quota pre-check and the livekit-integration.md
 * §3 ticket shape — port fakes + the REAL [LiveKitServerTokenMinter]
 * (a pure local HS256 signature, no media plane), so the ticket
 * claims are asserted end-to-end through the service:
 *
 *  * **the happy path** — the ticket carries the ws ingress, the
 *    member's username, `expiresAt = now + conferences.token-ttl` and
 *    a token whose `sub` is a FRESH per-mint
 *    `u:{userId}:{connId}` (a second mint of the SAME principal
 *    answers a distinct connection identity — the two-tabs edge,
 *    research §4), the metadata is `{"role","guest":false}` of the
 *    PERSISTED row (a moderator rides `role=moderator`, Q9=A) and the
 *    grants re-compute from its restrictions verbatim (Q5=A,
 *    invariant 3);
 *  * **the FR-015 quota pre-check** — a room at its
 *    `max_participants` answers [ConferenceFullException] (`409
 *    conference_full`) BEFORE the principal row and the mint (a
 *    refused join leaves no trace) and counts
 *    `webchat_conference_joins_denied_total{reason=quota}`; the LAST
 *    free slot still admits;
 *  * **the 503 edge** — a failing `ListParticipants` propagates
 *    [ConferenceMediaUnavailableException] with nothing written and
 *    nothing counted but nothing denied either;
 *  * **№55 `mintGuest`** (T046) — the guest twin: the ticket shape
 *    `g:{connId}` + the `{"role":"participant","guest":true,
 *    "inviteId"}` metadata of livekit-integration.md §3, the FULL
 *    source set of a fresh row (Q1=C), the row created exactly at the
 *    ticket issuance (Q7=A — a second entry of the SAME link is a new
 *    row/identity, Q2=A), the SHARED quota refusal before any write
 *    (Q23=A) and the same 503 edge.
 *
 * Arguments of the mocked Kotlin ports are stubbed through the
 * `*NotNull` wrappers of the ConferenceServiceTest convention.
 */
@Suppress("TooManyFunctions") // one leg per №47 rule of the task table
class JoinTokenServiceTest {
    private val liveKit = Mockito.mock(LiveKitAdminClient::class.java)
    private val participants = Mockito.mock(ParticipantRepository::class.java)
    private val memberNames = Mockito.mock(MemberNameReader::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val service =
        JoinTokenService(
            liveKit = liveKit,
            participants = participants,
            memberNames = memberNames,
            tokenMinter = LiveKitServerTokenMinter(TEST_PROPERTIES),
            properties = TEST_PROPERTIES,
            metrics = ConferenceMetrics(meterRegistry, Mockito.mock(JdbcTemplate::class.java)),
            objectMapper = ObjectMapper(),
            clock = Clock { NOW },
        )

    @BeforeEach
    fun stubTheCommonLegs() {
        Mockito.`when`(liveKit.listParticipants(ArgumentMatchers.anyString())).thenReturn(emptyList())
        Mockito
            .`when`(memberNames.usernameOf(eqNotNull(CALLER_ID)))
            .thenReturn(USERNAME)
        stubPrincipal(
            ConferenceParticipant.member(
                id = UUID.randomUUID(),
                conferenceId = CONFERENCE_ID,
                userId = CALLER_ID,
                firstSeenAt = NOW,
            ),
        )
    }

    @Test
    fun `mint hands out the public client ingress when the environment splits it`() {
        // the dev-stand split: the admin Twirp calls stay in-cluster
        // (conferences.livekit.url), while the JoinTicket wsUrl points
        // browsers at the public ingress route (livekit.public-url)
        stubGuestRow()
        val splitProperties =
            TEST_PROPERTIES.copy(
                livekit = TEST_PROPERTIES.livekit.copy(publicUrl = "https://dev.webchat.lkshr.ru/livekit"),
            )
        val splitService =
            JoinTokenService(
                liveKit = liveKit,
                participants = participants,
                memberNames = memberNames,
                tokenMinter = LiveKitServerTokenMinter(splitProperties),
                properties = splitProperties,
                metrics = ConferenceMetrics(SimpleMeterRegistry(), Mockito.mock(JdbcTemplate::class.java)),
                objectMapper = ObjectMapper(),
                clock = Clock { NOW },
            )

        val memberTicket = splitService.mintMember(CALLER_ID, conference())
        val guestTicket = splitService.mintGuest(activeConference(), INVITE_ID, GUEST_NAME)

        assertThat(memberTicket.wsUrl).isEqualTo("https://dev.webchat.lkshr.ru/livekit")
        assertThat(guestTicket.wsUrl).isEqualTo("https://dev.webchat.lkshr.ru/livekit")
    }

    @Test
    fun `mint issues the admission ticket of the member shape`() {
        val ticket = service.mintMember(CALLER_ID, conference())

        assertThat(ticket.wsUrl).isEqualTo(TEST_PROPERTIES.livekit.url)
        assertThat(ticket.name).isEqualTo(USERNAME)
        assertThat(ticket.expiresAt)
            .overridingErrorMessage("expiresAt is the mint-time TTL horizon (conferences.token-ttl)")
            .isEqualTo(NOW + TEST_PROPERTIES.tokenTtl)
        assertThat(ticket.identity).matches("u:$CALLER_ID:$UUID_PATTERN")

        val jwt = SignedJWT.parse(ticket.token)
        assertThat(jwt.header.algorithm).isEqualTo(JWSAlgorithm.HS256)
        assertThat(jwt.verify(MACVerifier(TEST_PROPERTIES.livekit.apiSecret))).isTrue
        val claims = jwt.jwtClaimsSet
        assertThat(claims.issuer).isEqualTo(TEST_PROPERTIES.livekit.apiKey)
        assertThat(claims.subject).isEqualTo(ticket.identity)
        assertThat(claims.getStringClaim("name")).isEqualTo(USERNAME)
        assertThat(claims.getStringClaim("metadata")).isEqualTo("""{"role":"participant","guest":false}""")

        @Suppress("UNCHECKED_CAST")
        val video = claims.getClaim("video") as Map<String, Any>
        assertThat(video["roomJoin"]).isEqualTo(true)
        assertThat(video["room"]).isEqualTo(conference().roomName)
        assertThat(video["canSubscribe"]).isEqualTo(true)
        assertThat(video["canPublish"]).isEqualTo(true)
        assertThat(video["canPublishData"]).isEqualTo(false)
        assertThat(video["canPublishSources"])
            .isEqualTo(listOf("microphone", "camera", "screen_share", "screen_share_audio"))

        assertThat(mintedCount("member")).isEqualTo(1.0)
    }

    @Test
    fun `a second mint answers a fresh connection identity on the same principal`() {
        val first = service.mintMember(CALLER_ID, conference())
        val second = service.mintMember(CALLER_ID, conference())

        assertThat(first.identity).isNotEqualTo(second.identity)
        Mockito
            .verify(participants, Mockito.times(2))
            .ensureMember(eqNotNull(CONFERENCE_ID), eqNotNull(CALLER_ID), eqNotNull(NOW))
        assertThat(mintedCount("member")).isEqualTo(2.0)
    }

    @Test
    fun `an exhausted quota answers conference_full before any write`() {
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenReturn(connected(quota = 2))

        assertThatThrownBy { service.mintMember(CALLER_ID, conference(maxParticipants = 2)) }
            .isInstanceOfSatisfying(ConferenceFullException::class.java) {
                assertThat(it.code).isEqualTo("conference_full")
            }

        Mockito
            .verify(participants, Mockito.never())
            .ensureMember(anyNotNull(), anyNotNull(), anyNotNull())
        Mockito.verifyNoInteractions(memberNames)
        assertThat(
            meterRegistry
                .find(JOINS_DENIED)
                .tag("reason", "quota")
                .counter()
                ?.count(),
        ).overridingErrorMessage(
            "the quota application counts webchat_conference_joins_denied_total{reason=quota} (FR-015)",
        ).isEqualTo(1.0)
        assertThat(mintedCount("member")).isZero()
    }

    @Test
    fun `the last free slot still admits`() {
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenReturn(connected(quota = 2))

        val ticket = service.mintMember(CALLER_ID, conference(maxParticipants = 3))

        assertThat(ticket.identity).isNotBlank()
        assertThat(mintedCount("member")).isEqualTo(1.0)
    }

    @Test
    fun `a media-plane failure propagates as the 503 shape with nothing written`() {
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        assertThatThrownBy { service.mintMember(CALLER_ID, conference()) }
            .isInstanceOf(ConferenceMediaUnavailableException::class.java)

        Mockito.verifyNoInteractions(participants, memberNames)
        assertThat(meterRegistry.find(JOINS_DENIED).counters()).isEmpty()
        assertThat(mintedCount("member")).isZero()
    }

    @Test
    fun `persisted restrictions reproduce in the fresh grants`() {
        stubPrincipal(
            principal(
                restrictions = ParticipantRestrictions(micRestricted = true, screenRestricted = true),
            ),
        )

        val ticket = service.mintMember(CALLER_ID, conference())

        @Suppress("UNCHECKED_CAST")
        val video = SignedJWT.parse(ticket.token).jwtClaimsSet.getClaim("video") as Map<String, Any>
        assertThat(video["canPublish"]).isEqualTo(true)
        assertThat(video["canPublishSources"]).isEqualTo(listOf("camera"))
    }

    @Test
    fun `the speech restriction blocks the whole publish side`() {
        stubPrincipal(principal(restrictions = ParticipantRestrictions(speechRestricted = true)))

        val ticket = service.mintMember(CALLER_ID, conference())

        @Suppress("UNCHECKED_CAST")
        val video = SignedJWT.parse(ticket.token).jwtClaimsSet.getClaim("video") as Map<String, Any>
        // Q4=A: «право слова» gates ANY publication
        assertThat(video["canPublish"]).isEqualTo(false)
        assertThat(video["canPublishSources"]).isEqualTo(emptyList<String>())
    }

    @Test
    fun `the persisted moderator role rides the metadata`() {
        stubPrincipal(
            ConferenceParticipant.member(
                id = UUID.randomUUID(),
                conferenceId = CONFERENCE_ID,
                userId = CALLER_ID,
                firstSeenAt = NOW,
                role = ParticipantRole.MODERATOR,
            ),
        )

        val ticket = service.mintMember(CALLER_ID, conference())

        assertThat(SignedJWT.parse(ticket.token).jwtClaimsSet.getStringClaim("metadata"))
            .isEqualTo("""{"role":"moderator","guest":false}""")
    }

    @Test
    fun `mintGuest issues the guest ticket shape with full sources and the inviteId metadata`() {
        stubGuestRow()

        val ticket = service.mintGuest(activeConference(), INVITE_ID, GUEST_NAME)

        assertThat(ticket.wsUrl).isEqualTo(TEST_PROPERTIES.livekit.url)
        assertThat(ticket.name).isEqualTo(GUEST_NAME)
        assertThat(ticket.expiresAt)
            .overridingErrorMessage("the guest TTL equals the member's (conferences.token-ttl)")
            .isEqualTo(NOW + TEST_PROPERTIES.tokenTtl)
        assertThat(ticket.identity).matches("g:$UUID_PATTERN")

        val jwt = SignedJWT.parse(ticket.token)
        assertThat(jwt.verify(MACVerifier(TEST_PROPERTIES.livekit.apiSecret))).isTrue()
        val claims = jwt.jwtClaimsSet
        assertThat(claims.subject).isEqualTo(ticket.identity)
        assertThat(claims.getStringClaim("name")).isEqualTo(GUEST_NAME)
        // livekit-integration.md §3: {"role":"participant","guest":true,"inviteId"} —
        // the №52/№53 disconnect key of T045 rides every guest connection
        assertThat(claims.getStringClaim("metadata"))
            .isEqualTo("""{"role":"participant","guest":true,"inviteId":"$INVITE_ID"}""")

        @Suppress("UNCHECKED_CAST")
        val video = claims.getClaim("video") as Map<String, Any>
        assertThat(video["roomJoin"]).isEqualTo(true)
        assertThat(video["room"]).isEqualTo(activeConference().roomName)
        // Q1=C: a fresh guest has NO restrictions — the sources are FULL; the
        // «only microphone» start state is a client default, never a grant
        assertThat(video["canPublish"]).isEqualTo(true)
        assertThat(video["canPublishSources"])
            .isEqualTo(listOf("microphone", "camera", "screen_share", "screen_share_audio"))

        assertThat(mintedCount("guest")).isEqualTo(1.0)
    }

    @Test
    fun `mintGuest creates the guest principal row at the ticket issuance`() {
        stubGuestRow()

        val ticket = service.mintGuest(activeConference(), INVITE_ID, GUEST_NAME)

        val stored = guestCaptor()
        Mockito.verify(participants).insert(stored.capture() ?: dummyGuest)
        assertThat(stored.value.conferenceId).isEqualTo(CONFERENCE_ID)
        assertThat(stored.value.identity.value).isEqualTo(ticket.identity)
        assertThat(stored.value.displayName).isEqualTo(GUEST_NAME)
        assertThat(stored.value.role).isEqualTo(ParticipantRole.PARTICIPANT)
    }

    @Test
    fun `a second mintGuest of the same link answers a fresh identity and a fresh row`() {
        stubGuestRow()

        val first = service.mintGuest(activeConference(), INVITE_ID, GUEST_NAME)
        val second = service.mintGuest(activeConference(), INVITE_ID, GUEST_NAME)

        // Q2=A/Q7=A: the link is multi-use and every entry is a NEW row/identity
        assertThat(first.identity).isNotEqualTo(second.identity)
        Mockito.verify(participants, Mockito.times(2)).insert(anyNotNull())
        assertThat(mintedCount("guest")).isEqualTo(2.0)
    }

    @Test
    fun `mintGuest answers conference_full of the shared quota before the row`() {
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenReturn(connected(quota = 2))

        assertThatThrownBy { service.mintGuest(activeConference(maxParticipants = 2), INVITE_ID, GUEST_NAME) }
            .isInstanceOfSatisfying(ConferenceFullException::class.java) {
                assertThat(it.code).isEqualTo("conference_full")
            }

        Mockito.verify(participants, Mockito.never()).insert(anyNotNull())
        assertThat(
            meterRegistry
                .find(JOINS_DENIED)
                .tag("reason", "quota")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(mintedCount("guest")).isZero()
    }

    @Test
    fun `mintGuest propagates a media-plane failure as the 503 shape with nothing written`() {
        Mockito
            .`when`(liveKit.listParticipants(ArgumentMatchers.anyString()))
            .thenThrow(ConferenceMediaUnavailableException(IllegalStateException("plane down")))

        assertThatThrownBy { service.mintGuest(activeConference(), INVITE_ID, GUEST_NAME) }
            .isInstanceOf(ConferenceMediaUnavailableException::class.java)

        Mockito.verify(participants, Mockito.never()).insert(anyNotNull())
        assertThat(meterRegistry.find(JOINS_DENIED).counters()).isEmpty()
        assertThat(mintedCount("guest")).isZero()
    }

    private fun conference(maxParticipants: Int = TEST_PROPERTIES.maxParticipants): Conference =
        Conference.create(CONFERENCE_ID, CHAT_ID, CREATOR_ID, maxParticipants, NOW)

    /** The №55 target — a live `active` conference row. */
    private fun activeConference(maxParticipants: Int = TEST_PROPERTIES.maxParticipants): Conference =
        conference(maxParticipants).activate(NOW)!!

    private fun stubPrincipal(row: ConferenceParticipant) {
        Mockito
            .`when`(participants.ensureMember(eqNotNull(CONFERENCE_ID), eqNotNull(CALLER_ID), eqNotNull(NOW)))
            .thenReturn(row)
    }

    /** The №55 row leg (Q7=A): the insert answers the stored guest row verbatim. */
    private fun stubGuestRow() {
        Mockito
            .`when`(participants.insert(anyNotNull()))
            .thenAnswer { it.arguments[0] as ConferenceParticipant }
    }

    private fun guestCaptor(): ArgumentCaptor<ConferenceParticipant> =
        ArgumentCaptor.forClass(ConferenceParticipant::class.java)

    /** The non-null placeholder of the capture seam — never asserted on. */
    private val dummyGuest =
        ConferenceParticipant.guest(
            id = UUID.randomUUID(),
            conferenceId = CONFERENCE_ID,
            connId = UUID.randomUUID(),
            displayName = GUEST_NAME,
            firstSeenAt = NOW,
        )

    private fun principal(restrictions: ParticipantRestrictions): ConferenceParticipant =
        ConferenceParticipant(
            id = UUID.randomUUID(),
            conferenceId = CONFERENCE_ID,
            identity = ConferenceIdentity.member(CALLER_ID),
            displayName = null,
            role = ParticipantRole.PARTICIPANT,
            restrictions = restrictions,
            firstSeenAt = NOW,
        )

    /** `quota` live connections — members and guests alike share the room slots (Q23=A). */
    private fun connected(quota: Int): List<LiveKitParticipant> =
        (1..quota).map { LiveKitParticipant("u:${UUID.randomUUID()}:${UUID.randomUUID()}", null) }

    private fun mintedCount(kind: String): Double =
        meterRegistry
            .find(TOKENS_MINTED)
            .tag("kind", kind)
            .counter()
            ?.count() ?: 0.0

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T09:00:00Z")

        val CONFERENCE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000c")

        val CHAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0a7")

        val CREATOR_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000d")

        val CALLER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000a11c")

        val INVITE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000004a")

        const val USERNAME = "alice"

        const val GUEST_NAME = "carol-guest"

        const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        const val TOKENS_MINTED = "webchat_conference_tokens_minted_total"

        const val JOINS_DENIED = "webchat_conference_joins_denied_total"

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
                rateLimit = ConferencesProperties.RateLimit("30/1m", "10/1m", "60/1m"),
            )
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
