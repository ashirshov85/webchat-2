package webchat.backend.conferences.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import webchat.backend.chats.repository.JdbcChatRepository
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.model.ParticipantRole
import java.time.Instant
import java.util.UUID

/**
 * T010 regression IT (tasks.md Phase 2, Foundational): the JDBC adapter
 * legs every moderation/webhook path asserts anchor on —
 * [JdbcParticipantRepository] (data-model 009 §Сущность 2, real
 * Testcontainers PG 17, no mocks):
 *
 *  * `insert` stores the creator moderator row and the named guest row
 *    through the V18 shape-CHECK (member `u:{userId}` nameless, guest
 *    `g:{connId}` named — Q7=A);
 *  * `ensureMember` is the №47 join leg (invariant 3): the FIRST join
 *    creates the plain row, every re-join returns the EXISTING row
 *    VERBATIM — the role elevation of №50 and the restrictions of №49
 *    survive reconnect (Q5/Q9=A), and the join leg never stamps the
 *    connection marks of the webhooks;
 *  * `markJoined`/`markLeft` stamp the principal by its stored identity
 *    prefix and answer `false` for an unknown identity — the silent 200
 *    of a webhook for a connection the backend never minted for;
 *  * №49/№50 are SET-semantics UPDATE…RETURNING: repeating the same
 *    body rewrites the same values, flipping moves the state, unknown
 *    identities converge to `null` (`404 participant_not_found`);
 *  * every lookup is scoped by conference — the same member principal
 *    exists once per conference, a guest row never leaks across.
 */
@Suppress("TooManyFunctions") // one method per adapter rule of tasks.md
class JdbcParticipantRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var participantRepository: JdbcParticipantRepository

    @Autowired
    private lateinit var conferenceRepository: JdbcConferenceRepository

    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `insert stores the moderator creator and the named guest through the shape CHECK`() {
        val conference = newConference()
        val alice = conference.createdBy

        val creator =
            participantRepository.insert(
                ConferenceParticipant.member(UUID.randomUUID(), conference.id, alice, T1, ParticipantRole.MODERATOR),
            )

        assertThat(creator.role).isEqualTo(ParticipantRole.MODERATOR)
        assertThat(creator.displayName)
            .overridingErrorMessage("a member principal carries no display name")
            .isNull()
        assertThat(creator.identity.value).isEqualTo("u:$alice")
        assertThat(creator.firstSeenAt).isEqualTo(T1)

        val connId = UUID.randomUUID()
        val guest =
            participantRepository.insert(
                ConferenceParticipant.guest(UUID.randomUUID(), conference.id, connId, GUEST_NAME, T1),
            )

        assertThat(guest.displayName).isEqualTo(GUEST_NAME)
        assertThat(guest.identity.value).isEqualTo("g:$connId")
        assertThat(guest.userId).isNull()
        assertThat(guest.role).isEqualTo(ParticipantRole.PARTICIPANT)
    }

    @Test
    fun `ensureMember creates the row once and returns the existing verbatim with role and restrictions`() {
        val conference = newConference()
        val alice = conference.createdBy

        val first = participantRepository.ensureMember(conference.id, alice, T1)

        assertThat(first.role).isEqualTo(ParticipantRole.PARTICIPANT)
        assertThat(first.restrictions).isEqualTo(ParticipantRestrictions())
        assertThat(first.firstSeenAt).isEqualTo(T1)

        participantRepository.updateRole(conference.id, first.identity.value, ParticipantRole.MODERATOR)
        participantRepository.updateRestrictions(
            conference.id,
            first.identity.value,
            ParticipantRestrictions(micRestricted = true, speechRestricted = true),
        )

        val rejoin = participantRepository.ensureMember(conference.id, alice, T2)

        assertThat(rejoin.id)
            .overridingErrorMessage("a re-join reuses the ONE member principal (Q10=A edge)")
            .isEqualTo(first.id)
        assertThat(rejoin.role)
            .overridingErrorMessage("the №50 elevation must survive reconnect (Q9=A)")
            .isEqualTo(ParticipantRole.MODERATOR)
        assertThat(rejoin.restrictions.micRestricted)
            .overridingErrorMessage("the №49 restrictions must survive reconnect (Q5=A)")
            .isTrue
        assertThat(rejoin.restrictions.speechRestricted).isTrue
        assertThat(rejoin.firstSeenAt)
            .overridingErrorMessage("the existing row is returned verbatim — first_seen_at stays")
            .isEqualTo(T1)
        assertThat(rejoin.lastJoinedAt)
            .overridingErrorMessage("the join leg never stamps the webhook marks")
            .isNull()
    }

    @Test
    fun `markJoined and markLeft stamp the principal prefix and ignore unknown identities`() {
        val conference = newConference()
        val alice = conference.createdBy
        val member = participantRepository.ensureMember(conference.id, alice, T1)
        val guestConn = UUID.randomUUID()
        participantRepository.insert(
            ConferenceParticipant.guest(UUID.randomUUID(), conference.id, guestConn, GUEST_NAME, T1),
        )

        assertThat(participantRepository.markJoined(conference.id, member.identity.value, T2)).isTrue
        assertThat(participantRepository.markLeft(conference.id, "g:$guestConn", T3)).isTrue

        val memberRow = participantRepository.findByIdentity(conference.id, member.identity.value)!!
        assertThat(memberRow.lastJoinedAt).isEqualTo(T2)
        assertThat(memberRow.lastLeftAt).isNull()
        val guestRow = participantRepository.findByIdentity(conference.id, "g:$guestConn")!!
        assertThat(guestRow.lastLeftAt).isEqualTo(T3)
        assertThat(guestRow.lastJoinedAt).isNull()

        assertThat(participantRepository.markJoined(conference.id, UNKNOWN_IDENTITY, T2))
            .overridingErrorMessage("an unknown principal is rowcount 0 — the webhook still answers 200")
            .isFalse
        assertThat(participantRepository.markLeft(conference.id, UNKNOWN_IDENTITY, T3)).isFalse
    }

    @Test
    fun `updateRestrictions rewrites the SET state and converges unknown identities to null`() {
        val conference = newConference()
        val member = participantRepository.ensureMember(conference.id, conference.createdBy, T1)
        val identity = member.identity.value

        val restricted =
            participantRepository.updateRestrictions(
                conference.id,
                identity,
                ParticipantRestrictions(micRestricted = true, screenRestricted = true),
            )!!

        assertThat(restricted.restrictions.micRestricted).isTrue
        assertThat(restricted.restrictions.screenRestricted).isTrue
        assertThat(restricted.restrictions.cameraRestricted).isFalse

        val repeated =
            participantRepository.updateRestrictions(
                conference.id,
                identity,
                ParticipantRestrictions(micRestricted = true, screenRestricted = true),
            )

        assertThat(repeated)
            .overridingErrorMessage("repeating the same №49 body is an idempotent PUT, not a 404")
            .isNotNull

        val cleared =
            participantRepository.updateRestrictions(conference.id, identity, ParticipantRestrictions())!!

        assertThat(cleared.restrictions)
            .overridingErrorMessage("flipping the flags back must rewrite the row (SET semantics)")
            .isEqualTo(ParticipantRestrictions())

        assertThat(participantRepository.updateRestrictions(conference.id, UNKNOWN_IDENTITY, ParticipantRestrictions()))
            .overridingErrorMessage("an unknown identity converges to the 404 of №49")
            .isNull()
    }

    @Test
    fun `updateRole persists the elevation and converges unknown identities to null`() {
        val conference = newConference()
        val member = participantRepository.ensureMember(conference.id, conference.createdBy, T1)
        val identity = member.identity.value

        val elevated =
            participantRepository.updateRole(conference.id, identity, ParticipantRole.MODERATOR)!!

        assertThat(elevated.role).isEqualTo(ParticipantRole.MODERATOR)
        assertThat(participantRepository.findMember(conference.id, conference.createdBy)!!.role)
            .overridingErrorMessage("the №50 role write must persist for the re-mint (Q9=A)")
            .isEqualTo(ParticipantRole.MODERATOR)

        val demoted = participantRepository.updateRole(conference.id, identity, ParticipantRole.PARTICIPANT)!!

        assertThat(demoted.role).isEqualTo(ParticipantRole.PARTICIPANT)
        assertThat(participantRepository.updateRole(conference.id, UNKNOWN_IDENTITY, ParticipantRole.MODERATOR))
            .overridingErrorMessage("an unknown identity converges to the 404 of №50")
            .isNull()
    }

    @Test
    fun `lookups are scoped by conference`() {
        val alice = newUser()
        val bob = newUser()
        val firstChat = chatRepository.ensure(alice, bob).chat
        val secondChat = chatRepository.ensure(alice, newUser()).chat
        val first = newConference(firstChat.id, alice)
        val second = newConference(secondChat.id, alice)

        val firstPrincipal = participantRepository.ensureMember(first.id, alice, T1)
        val secondPrincipal = participantRepository.ensureMember(second.id, alice, T1)

        assertThat(secondPrincipal.id).isNotEqualTo(firstPrincipal.id)
        assertThat(participantRepository.findMember(first.id, alice)!!.id).isEqualTo(firstPrincipal.id)
        assertThat(participantRepository.findMember(second.id, alice)!!.id).isEqualTo(secondPrincipal.id)
        assertThat(participantRepository.findMember(first.id, bob))
            .overridingErrorMessage("a member that never joined has no principal row — №46 answers the default MeView")
            .isNull()

        val guestConn = UUID.randomUUID()
        participantRepository.insert(
            ConferenceParticipant.guest(UUID.randomUUID(), first.id, guestConn, GUEST_NAME, T1),
        )

        assertThat(participantRepository.findByIdentity(second.id, "g:$guestConn"))
            .overridingErrorMessage("a guest row must never leak across conferences")
            .isNull()
        assertThat(participantRepository.findByIdentity(first.id, firstPrincipal.identity.value)!!.id)
            .isEqualTo(firstPrincipal.id)
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t010p-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(
            INSERT_USER_SQL,
            id,
            login,
            "$login@example.com",
        )
        return id
    }

    private fun newConference(): Conference {
        val alice = newUser()
        val bob = newUser()
        val chat = chatRepository.ensure(alice, bob).chat
        return newConference(chat.id, alice)
    }

    private fun newConference(
        chatId: UUID,
        createdBy: UUID,
    ): Conference =
        conferenceRepository
            .create(Conference.create(UUID.randomUUID(), chatId, createdBy, MAX_PARTICIPANTS, T1))
            .conference

    private companion object {
        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()

        /** Deterministic instants: T1 < T2 < T3. */
        val T1 = Instant.parse("2026-01-01T00:00:00Z")
        val T2 = Instant.parse("2026-01-01T00:00:01Z")
        val T3 = Instant.parse("2026-01-01T00:00:02Z")

        const val MAX_PARTICIPANTS = 16
        const val GUEST_NAME = "guest-bob"

        /** A user UUID that never mints a principal in any test conference. */
        const val UNKNOWN_IDENTITY = "u:00000000-0000-0000-0000-000000000000"
    }
}
