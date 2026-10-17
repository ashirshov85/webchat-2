package webchat.backend.conferences.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import webchat.backend.chats.repository.JdbcChatRepository
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.conferences.domain.model.GuestInviteStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T011 regression IT (tasks.md Phase 2, Foundational): the JDBC adapter
 * legs the №52/№53/№54-№55 flows of US4 anchor on —
 * [JdbcGuestInviteRepository] (data-model 009 §Сущность 3, real
 * Testcontainers PG 17, no mocks):
 *
 *  * №52 (Q17=A): the FIRST issue of a conference stores the ACTIVE row
 *    with NO predecessor; every subsequent issue runs revoke+INSERT in
 *    the SAME transaction — the previous row comes back REVOKED with
 *    `revoked_at` stamped, the fresh one ACTIVE, and the partial unique
 *    index `ux_guest_invites_active` never observes two ACTIVE rows
 *    (invariant «одна активная ссылка»);
 *  * №53: revoke of the ACTIVE row answers the REVOKED row; a repeat
 *    keeps the ORIGINAL `revoked_at` and answers `null` — the uniform
 *    `404 guest_invite_not_found` of an unknown id AND an
 *    already-revoked one (api-contract.md §2);
 *  * the №54/№55 read is RAW by id: the row is returned regardless of
 *    status/expiry — the `isValid` classification (revoked/expired) and
 *    the forged-nonce refusal stay at the caller layers (T044/T046);
 *  * rows live scoped by their conference and die with the finalize
 *    transaction (Q18=A — pinned in JdbcConferenceRepositoryIT).
 */
@Suppress("TooManyFunctions") // one method per adapter rule of tasks.md
class JdbcGuestInviteRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var guestInviteRepository: JdbcGuestInviteRepository

    @Autowired
    private lateinit var conferenceRepository: JdbcConferenceRepository

    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `issue stores the first active row with no predecessor`() {
        val conference = newConference()
        val invite = newInvite(conference.id, conference.createdBy)

        val result = guestInviteRepository.issue(invite)

        assertThat(result.revokedPrevious)
            .overridingErrorMessage("the first link of a conference replaces nothing")
            .isNull()
        assertThat(result.invite.id).isEqualTo(invite.id)
        assertThat(result.invite.conferenceId).isEqualTo(conference.id)
        assertThat(result.invite.status).isEqualTo(GuestInviteStatus.ACTIVE)
        assertThat(result.invite.createdAt).isEqualTo(T1)
        assertThat(result.invite.expiresAt).isEqualTo(T1.plus(GUEST_INVITE_TTL))
        assertThat(result.invite.revokedAt).isNull()
    }

    @Test
    fun `issue revokes the previous link in the same transaction - one active stays`() {
        val conference = newConference()
        val first = guestInviteRepository.issue(newInvite(conference.id, conference.createdBy)).invite

        val second = guestInviteRepository.issue(secondInvite(conference.id, conference.createdBy))

        assertThat(second.revokedPrevious!!.id)
            .overridingErrorMessage("the replaced row is handed back for the post-commit guest disconnect (Q13=B)")
            .isEqualTo(first.id)
        assertThat(second.revokedPrevious.status).isEqualTo(GuestInviteStatus.REVOKED)
        assertThat(second.revokedPrevious.revokedAt)
            .overridingErrorMessage("the pre-revoke stamps the moment of the new issue")
            .isEqualTo(T2)
        assertThat(second.invite.id).isNotEqualTo(first.id)
        assertThat(second.invite.status).isEqualTo(GuestInviteStatus.ACTIVE)

        assertThat(rowCount(ACTIVE_OF_SQL, conference.id))
            .overridingErrorMessage("ux_guest_invites_active must observe exactly ONE active row (Q17=A)")
            .isEqualTo(1)
        assertThat(guestInviteRepository.findById(first.id)!!.status)
            .overridingErrorMessage("the previous link must answer REVOKED at №54/№55")
            .isEqualTo(GuestInviteStatus.REVOKED)
        assertThat(guestInviteRepository.findById(second.invite.id)!!.status)
            .isEqualTo(GuestInviteStatus.ACTIVE)
    }

    @Test
    fun `each conference carries its own single active link`() {
        val alice = newUser()
        val firstChat = chatRepository.ensure(alice, newUser()).chat
        val secondChat = chatRepository.ensure(alice, newUser()).chat
        val first = newConference(firstChat.id, alice)
        val second = newConference(secondChat.id, alice)
        val firstInvite = guestInviteRepository.issue(newInvite(first.id, alice)).invite

        val secondResult = guestInviteRepository.issue(newInvite(second.id, alice))

        assertThat(secondResult.revokedPrevious)
            .overridingErrorMessage("a link of another conference is never revoked by this issue")
            .isNull()
        assertThat(rowCount(ACTIVE_OF_SQL, first.id)).isEqualTo(1)
        assertThat(rowCount(ACTIVE_OF_SQL, second.id)).isEqualTo(1)
        assertThat(guestInviteRepository.findById(firstInvite.id)!!.conferenceId).isEqualTo(first.id)
    }

    @Test
    fun `revoke answers the revoked row once and keeps the original revoked_at`() {
        val conference = newConference()
        val invite = guestInviteRepository.issue(newInvite(conference.id, conference.createdBy)).invite

        val revoked = guestInviteRepository.revoke(invite.id, T2)

        assertThat(revoked!!.id).isEqualTo(invite.id)
        assertThat(revoked.status).isEqualTo(GuestInviteStatus.REVOKED)
        assertThat(revoked.revokedAt).isEqualTo(T2)
        assertThat(rowCount(ACTIVE_OF_SQL, conference.id)).isZero

        val repeated = guestInviteRepository.revoke(invite.id, T3)

        assertThat(repeated)
            .overridingErrorMessage("a repeat revoke converges to the uniform 404 of №53")
            .isNull()
        assertThat(guestInviteRepository.findById(invite.id)!!.revokedAt)
            .overridingErrorMessage("the repeat must keep the ORIGINAL revoked_at")
            .isEqualTo(T2)
    }

    @Test
    fun `revoke of an unknown id answers null`() {
        val conference = newConference()

        assertThat(guestInviteRepository.revoke(UUID.randomUUID(), T1))
            .overridingErrorMessage("an unknown id answers identically to an already-revoked one")
            .isNull()
        assertThat(guestInviteRepository.findById(UUID.randomUUID()))
            .overridingErrorMessage("an unknown id reads back as nothing — 404 invite_not_found")
            .isNull()
        assertThat(rowCount(ACTIVE_OF_SQL, conference.id)).isZero
    }

    @Test
    fun `revokeActiveOf resolves the conference's single active link - the №53 path`() {
        val conference = newConference()
        val invite = guestInviteRepository.issue(newInvite(conference.id, conference.createdBy)).invite

        val revoked = guestInviteRepository.revokeActiveOf(conference.id, T2)

        assertThat(revoked!!.id)
            .overridingErrorMessage("the DELETE path carries no invite id — the ONE active row resolves")
            .isEqualTo(invite.id)
        assertThat(revoked.status).isEqualTo(GuestInviteStatus.REVOKED)
        assertThat(revoked.revokedAt).isEqualTo(T2)
        assertThat(rowCount(ACTIVE_OF_SQL, conference.id)).isZero

        val repeated = guestInviteRepository.revokeActiveOf(conference.id, T3)

        assertThat(repeated)
            .overridingErrorMessage("the №53 repeat converges to the uniform 404 — the first revoked_at stays")
            .isNull()
        assertThat(guestInviteRepository.findById(invite.id)!!.revokedAt).isEqualTo(T2)
    }

    @Test
    fun `revokeActiveOf of a conference without a link and of another conference's link answers null`() {
        val conference = newConference()
        val other = newConference()
        val otherInvite = guestInviteRepository.issue(newInvite(other.id, other.createdBy)).invite

        assertThat(guestInviteRepository.revokeActiveOf(conference.id, T1))
            .overridingErrorMessage("never issued — the uniform 404 of №53")
            .isNull()
        assertThat(guestInviteRepository.revokeActiveOf(UUID.randomUUID(), T1))
            .overridingErrorMessage("an unknown conference answers the same null")
            .isNull()
        assertThat(guestInviteRepository.findById(otherInvite.id)!!.status)
            .overridingErrorMessage("a link of ANOTHER conference is never touched")
            .isEqualTo(GuestInviteStatus.ACTIVE)
        assertThat(rowCount(ACTIVE_OF_SQL, other.id)).isEqualTo(1)
    }

    @Test
    fun `findById returns the raw row regardless of status and expiry`() {
        val conference = newConference()
        val invite = guestInviteRepository.issue(newInvite(conference.id, conference.createdBy)).invite
        guestInviteRepository.revoke(invite.id, T2)
        val expired =
            GuestInvite.issue(
                id = UUID.randomUUID(),
                conferenceId = conference.id,
                createdBy = conference.createdBy,
                at = T1.minusSeconds(EXPIRED_AGE_SECONDS),
                ttl = GUEST_INVITE_TTL,
            )

        assertThat(guestInviteRepository.findById(invite.id))
            .overridingErrorMessage("a REVOKED row is still the №54 read — the caller classifies")
            .isNotNull
        assertThat(guestInviteRepository.findById(invite.id)!!.isValid(T3))
            .overridingErrorMessage("a revoked link never validates")
            .isFalse

        guestInviteRepository.issue(expired)
        val stored = guestInviteRepository.findById(expired.id)!!
        assertThat(stored.status).isEqualTo(GuestInviteStatus.ACTIVE)
        assertThat(stored.isValid(LATE))
            .overridingErrorMessage("an ACTIVE row past its horizon is the caller's invite_expired, not a hidden row")
            .isFalse
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t011-${id.toString().substring(0, 8)}"
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
        return newConference(chatRepository.ensure(alice, bob).chat.id, alice)
    }

    private fun newConference(
        chatId: UUID,
        createdBy: UUID,
    ): Conference =
        conferenceRepository
            .create(Conference.create(UUID.randomUUID(), chatId, createdBy, MAX_PARTICIPANTS, T1))
            .conference

    /** A fresh ACTIVE №52 row issued at [T1] — the first link of a conference. */
    private fun newInvite(
        conferenceId: UUID,
        createdBy: UUID,
    ): GuestInvite = GuestInvite.issue(UUID.randomUUID(), conferenceId, createdBy, T1, GUEST_INVITE_TTL)

    /** The second link of the same conference — issued later at [T2] (Q17=A revoke+insert). */
    private fun secondInvite(
        conferenceId: UUID,
        createdBy: UUID,
    ): GuestInvite = GuestInvite.issue(UUID.randomUUID(), conferenceId, createdBy, T2, GUEST_INVITE_TTL)

    private fun rowCount(
        sql: String,
        conferenceId: UUID,
    ): Int = jdbcTemplate.queryForObject(sql, Int::class.java, conferenceId) ?: 0

    private companion object {
        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()

        val ACTIVE_OF_SQL =
            """
            SELECT count(*) FROM conference_guest_invites WHERE conference_id = ? AND status = 'active'
            """.trimIndent()

        /** Deterministic instants: T1 < T2 < T3 < LATE. */
        val T1 = Instant.parse("2026-01-01T00:00:00Z")
        val T2 = Instant.parse("2026-01-01T00:00:01Z")
        val T3 = Instant.parse("2026-01-01T00:00:02Z")

        /** Far past the 24 h horizon of a T1-issued link. */
        val LATE = Instant.parse("2026-01-05T00:00:00Z")

        const val MAX_PARTICIPANTS = 16

        /** data-model 009 §Сущность 3: the default 24 h nonce horizon (Q19=A). */
        val GUEST_INVITE_TTL = Duration.ofHours(HOURS_PER_DAY)

        const val HOURS_PER_DAY = 24L
        const val SECONDS_PER_HOUR = 3600L
        const val EXPIRED_AGE_SECONDS = 48L * HOURS_PER_DAY * SECONDS_PER_HOUR
    }
}
