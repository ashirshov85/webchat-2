package webchat.backend.conferences.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import webchat.backend.chats.repository.JdbcChatRepository
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ConferenceCreateResult
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T010 regression IT (tasks.md Phase 2, Foundational): the JDBC adapter
 * legs every later story asserts anchor on — [JdbcConferenceRepository]
 * (data-model 009 §Сущность 1, real Testcontainers PG 17, no mocks):
 *
 *  * №44 idempotence (invariant 1): the `ux_conferences_chat_active`
 *    partial index makes the FIRST `create` the winner and resolves
 *    the competing «double press» to the SAME row verbatim
 *    (`Existing`, no second row); a chat accepts a FRESH conference
 *    once the previous one is `ended`;
 *  * `created → active` advances EXACTLY once — the conditional UPDATE
 *    of the first `participant_joined` (US5-1), a duplicate is a no-op
 *    that keeps the original `started_at`;
 *  * finalize is the ONE idempotent end (invariant 5): the winning
 *    transaction ends the row AND deletes EVERY participant/invite row
 *    of the conference (Q18=A) while the conference FACT survives for
 *    the `409 conference_ended` answers; a duplicate finalize (another
 *    reason, later timestamp) changes NOTHING;
 *  * the `room_lost` shape: an end while still `created` keeps
 *    `started_at` NULL (V18 directional CHECKs);
 *  * the sweeper scans (`findByStatus`) and the chat-deletion list
 *    (`findUnendedByChatId`) follow the lifecycle exactly.
 *
 * The story-level ITs (T021+) replay these rules through the services;
 * this IT pins them at the adapter boundary before any user story.
 */
@Suppress("TooManyFunctions") // one method per adapter rule of tasks.md
class JdbcConferenceRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var conferenceRepository: JdbcConferenceRepository

    @Autowired
    private lateinit var participantRepository: JdbcParticipantRepository

    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `create resolves the chat-active race into Created then the existing row`() {
        val alice = newUser()
        val bob = newUser()
        val chat = chatRepository.ensure(alice, bob).chat
        val conference = Conference.create(UUID.randomUUID(), chat.id, alice, MAX_PARTICIPANTS, T1)

        val winner = conferenceRepository.create(conference)

        assertThat(winner)
            .overridingErrorMessage("the first create of a chat must win the insert")
            .isInstanceOf(ConferenceCreateResult.Created::class.java)
        assertThat(winner.conference.id).isEqualTo(conference.id)
        assertThat(winner.conference.status).isEqualTo(ConferenceStatus.CREATED)
        assertThat(winner.conference.startedAt).isNull()
        assertThat(winner.conference.endedAt).isNull()
        assertThat(winner.conference.maxParticipants).isEqualTo(MAX_PARTICIPANTS)

        val doublePress =
            conferenceRepository.create(
                Conference.create(UUID.randomUUID(), chat.id, bob, MAX_PARTICIPANTS, T2),
            )

        assertThat(doublePress)
            .overridingErrorMessage("the competing «double press» must resolve to Existing, not a second row")
            .isInstanceOf(ConferenceCreateResult.Existing::class.java)
        assertThat(doublePress.conference.id).isEqualTo(conference.id)
        assertThat(doublePress.conference.createdBy)
            .overridingErrorMessage("the existing row is returned verbatim — the original creator")
            .isEqualTo(alice)
    }

    @Test
    fun `activate advances created exactly once and keeps the timestamp of the first win`() {
        val conference = newConference()

        val advanced = conferenceRepository.activate(conference.id, T2)

        assertThat(advanced!!.status).isEqualTo(ConferenceStatus.ACTIVE)
        assertThat(advanced.startedAt).isEqualTo(T2)
        assertThat(conferenceRepository.activate(conference.id, T3))
            .overridingErrorMessage("a duplicate participant_joined is rowcount 0 — no second advance")
            .isNull()
        assertThat(conferenceRepository.findById(conference.id)!!.startedAt)
            .overridingErrorMessage("the duplicate must not move started_at")
            .isEqualTo(T2)
    }

    @Test
    fun `activate after finalize is a no-op`() {
        val conference = newConference()
        conferenceRepository.finalize(conference.id, ConferenceEndReason.ROOM_LOST, T2)

        assertThat(conferenceRepository.activate(conference.id, T3))
            .overridingErrorMessage("an out-of-order participant_joined after room_finished must not resurrect the row")
            .isNull()
        assertThat(conferenceRepository.findById(conference.id)!!.status).isEqualTo(ConferenceStatus.ENDED)
    }

    @Test
    fun `finalize ends once, removes every principal and invite row and keeps the fact`() {
        val alice = newUser()
        val bob = newUser()
        val chat = chatRepository.ensure(alice, bob).chat
        val conference = newConference(chat.id, alice)
        participantRepository.insert(
            ConferenceParticipant.member(UUID.randomUUID(), conference.id, alice, T1, ParticipantRole.MODERATOR),
        )
        participantRepository.insert(
            ConferenceParticipant.guest(UUID.randomUUID(), conference.id, UUID.randomUUID(), GUEST_NAME, T1),
        )
        stageGuestInvite(UUID.randomUUID(), conference.id, alice)

        val ended = conferenceRepository.finalize(conference.id, ConferenceEndReason.LAST_LEFT, T2)

        assertThat(ended!!.status).isEqualTo(ConferenceStatus.ENDED)
        assertThat(ended.endedAt).isEqualTo(T2)
        assertThat(ended.endReason).isEqualTo(ConferenceEndReason.LAST_LEFT)
        assertThat(rowCount(PARTICIPANTS_OF_SQL, conference.id))
            .overridingErrorMessage("finalize must delete every participant row (Q18=A)")
            .isZero
        assertThat(rowCount(INVITES_OF_SQL, conference.id))
            .overridingErrorMessage("finalize must delete every guest invite row (Q18=A)")
            .isZero
        assertThat(conferenceRepository.findById(conference.id))
            .overridingErrorMessage("the conference fact survives finalize for the «завершена» answers")
            .isNotNull

        val duplicate = conferenceRepository.finalize(conference.id, ConferenceEndReason.MODERATOR, T3)

        assertThat(duplicate)
            .overridingErrorMessage("a duplicate room_finished is rowcount 0 (invariant 5)")
            .isNull()
        val reread = conferenceRepository.findById(conference.id)!!
        assertThat(reread.endedAt)
            .overridingErrorMessage("the duplicate must leave ended_at untouched")
            .isEqualTo(T2)
        assertThat(reread.endReason)
            .overridingErrorMessage("the duplicate must leave end_reason untouched")
            .isEqualTo(ConferenceEndReason.LAST_LEFT)
    }

    @Test
    fun `finalize from created keeps startedAt null - the room_lost shape`() {
        val conference = newConference()

        val ended = conferenceRepository.finalize(conference.id, ConferenceEndReason.ROOM_LOST, T2)

        assertThat(ended!!.startedAt)
            .overridingErrorMessage("an end while still created (empty-timeout) keeps started_at NULL")
            .isNull()
        assertThat(ended.endReason).isEqualTo(ConferenceEndReason.ROOM_LOST)
    }

    @Test
    fun `a chat accepts a fresh conference once the previous one ended`() {
        val alice = newUser()
        val bob = newUser()
        val chat = chatRepository.ensure(alice, bob).chat
        val first = newConference(chat.id, alice)
        conferenceRepository.finalize(first.id, ConferenceEndReason.LAST_LEFT, T2)

        val reborn =
            conferenceRepository.create(
                Conference.create(UUID.randomUUID(), chat.id, alice, MAX_PARTICIPANTS, T3),
            )

        assertThat(reborn)
            .overridingErrorMessage("an ended conference must not block the partial index")
            .isInstanceOf(ConferenceCreateResult.Created::class.java)
        assertThat(reborn.conference.id).isNotEqualTo(first.id)
    }

    @Test
    fun `findByStatus and findUnendedByChatId follow the lifecycle`() {
        val alice = newUser()
        val bob = newUser()
        val chat = chatRepository.ensure(alice, bob).chat
        val otherChat = chatRepository.ensure(bob, newUser()).chat
        val conference = newConference(chat.id, alice)

        assertThat(conferenceRepository.findByStatus(ConferenceStatus.CREATED).map { it.id })
            .contains(conference.id)
        assertThat(conferenceRepository.findUnendedByChatId(chat.id).single().id).isEqualTo(conference.id)
        assertThat(conferenceRepository.findUnendedByChatId(otherChat.id)).isEmpty()

        conferenceRepository.activate(conference.id, T2)
        assertThat(conferenceRepository.findByStatus(ConferenceStatus.CREATED).map { it.id })
            .doesNotContain(conference.id)
        assertThat(conferenceRepository.findByStatus(ConferenceStatus.ACTIVE).map { it.id })
            .contains(conference.id)

        conferenceRepository.finalize(conference.id, ConferenceEndReason.MODERATOR, T3)
        assertThat(conferenceRepository.findUnendedByChatId(chat.id))
            .overridingErrorMessage("a finalized conference leaves the chat free of un-ended rows")
            .isEmpty()
    }

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t010c-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(
            INSERT_USER_SQL,
            id,
            login,
            "$login@example.com",
        )
        return id
    }

    private fun newConference(
        chatId: UUID,
        createdBy: UUID,
    ): Conference =
        conferenceRepository
            .create(Conference.create(UUID.randomUUID(), chatId, createdBy, MAX_PARTICIPANTS, T1))
            .conference

    private fun newConference(): Conference {
        val alice = newUser()
        val bob = newUser()
        return newConference(chatRepository.ensure(alice, bob).chat.id, alice)
    }

    /** A staged ACTIVE invite row (the T011 adapter is not in scope of T010). */
    private fun stageGuestInvite(
        id: UUID,
        conferenceId: UUID,
        createdBy: UUID,
    ) {
        jdbcTemplate.update(
            STAGE_GUEST_INVITE_SQL,
            id,
            conferenceId,
            createdBy,
            Timestamp.from(T1.plus(GUEST_INVITE_TTL)),
            Timestamp.from(T1),
        )
    }

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

        val STAGE_GUEST_INVITE_SQL =
            """
            INSERT INTO conference_guest_invites (id, conference_id, created_by, status, expires_at, created_at)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent()

        val PARTICIPANTS_OF_SQL =
            """
            SELECT count(*) FROM conference_participants WHERE conference_id = ?
            """.trimIndent()

        val INVITES_OF_SQL =
            """
            SELECT count(*) FROM conference_guest_invites WHERE conference_id = ?
            """.trimIndent()

        /** Deterministic instants: T1 < T2 < T3. */
        val T1 = Instant.parse("2026-01-01T00:00:00Z")
        val T2 = Instant.parse("2026-01-01T00:00:01Z")
        val T3 = Instant.parse("2026-01-01T00:00:02Z")

        const val MAX_PARTICIPANTS = 16
        const val GUEST_NAME = "guest-bob"

        /** data-model 009 §Сущность 3: the default 24 h nonce horizon (Q19=A). */
        val GUEST_INVITE_TTL = Duration.ofHours(HOURS_PER_DAY)

        const val HOURS_PER_DAY = 24L
    }
}
