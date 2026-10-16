package webchat.backend.conferences.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.conferences.domain.model.ConferenceIdentity
import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.model.ParticipantRole
import webchat.backend.conferences.domain.port.ParticipantRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC adapter for [ParticipantRepository] (data-model 009 §Сущность 2;
 * DIP: the adapter lives outside the domain, T010 — JdbcTemplate, the
 * domain convention without JPA).
 *
 * The bean name is explicit: the chats domain of 004/005 already has its
 * own `JdbcParticipantRepository` (a different port — chat rosters), and
 * the annotation default would decapitalize both simple names into one
 * conflicting bean id; injection stays by port type and never collides.
 *
 * The row is the ONLY carrier of the role and moderation restrictions
 * that must survive reconnect (Q5/Q9/Q22=A), so every read projects
 * the full principal and every moderation write is a SET-semantics
 * UPDATE…RETURNING resolved by rowcount: unknown identities converge
 * to `null`/`false` with NO side effects — the `404
 * participant_not_found` of №49/№50 and the silent 200 of a webhook
 * for a connection the backend never minted for. There is NO delete
 * leg of its own: the finalize transaction of
 * [JdbcConferenceRepository.finalize] removes every row of the
 * conference (Q18=A). [ensureMember] resolves the
 * `ux_conference_participants_member` race the same READ COMMITTED
 * way as the messages adapter of 004 — the competing row is already
 * committed, hence visible to the follow-up SELECT of the SAME
 * transaction.
 */
@Repository("conferencesParticipantRepository")
class JdbcParticipantRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ParticipantRepository {
    override fun insert(participant: ConferenceParticipant): ConferenceParticipant =
        jdbcTemplate
            .query(
                INSERT_SQL,
                ROW_MAPPER,
                participant.id,
                participant.conferenceId,
                participant.identity.value,
                participant.userId,
                participant.displayName,
                participant.role.pg(),
                participant.restrictions.micRestricted,
                participant.restrictions.cameraRestricted,
                participant.restrictions.screenRestricted,
                participant.restrictions.speechRestricted,
                Timestamp.from(participant.firstSeenAt),
            ).firstOrNull()
            ?: error("INSERT … RETURNING must answer")

    @Transactional
    override fun ensureMember(
        conferenceId: UUID,
        userId: UUID,
        at: Instant,
    ): ConferenceParticipant {
        val inserted =
            jdbcTemplate
                .query(
                    INSERT_MEMBER_ON_CONFLICT_SQL,
                    ROW_MAPPER,
                    UUID.randomUUID(),
                    conferenceId,
                    ConferenceIdentity.member(userId).value,
                    userId,
                    Timestamp.from(at),
                ).firstOrNull()
        if (inserted != null) return inserted
        return jdbcTemplate
            .query(FIND_MEMBER_SQL, ROW_MAPPER, conferenceId, userId)
            .firstOrNull()
            ?: error("participant conflict could not resolve the existing row inside its transaction")
    }

    override fun findByIdentity(
        conferenceId: UUID,
        identity: String,
    ): ConferenceParticipant? =
        jdbcTemplate
            .query(FIND_BY_IDENTITY_SQL, ROW_MAPPER, conferenceId, identity)
            .firstOrNull()

    override fun findMember(
        conferenceId: UUID,
        userId: UUID,
    ): ConferenceParticipant? =
        jdbcTemplate
            .query(FIND_MEMBER_SQL, ROW_MAPPER, conferenceId, userId)
            .firstOrNull()

    override fun markJoined(
        conferenceId: UUID,
        identity: String,
        at: Instant,
    ): Boolean = jdbcTemplate.update(MARK_JOINED_SQL, Timestamp.from(at), conferenceId, identity) > 0

    override fun markLeft(
        conferenceId: UUID,
        identity: String,
        at: Instant,
    ): Boolean = jdbcTemplate.update(MARK_LEFT_SQL, Timestamp.from(at), conferenceId, identity) > 0

    override fun updateRestrictions(
        conferenceId: UUID,
        identity: String,
        restrictions: ParticipantRestrictions,
    ): ConferenceParticipant? =
        jdbcTemplate
            .query(
                UPDATE_RESTRICTIONS_SQL,
                ROW_MAPPER,
                restrictions.micRestricted,
                restrictions.cameraRestricted,
                restrictions.screenRestricted,
                restrictions.speechRestricted,
                conferenceId,
                identity,
            ).firstOrNull()

    override fun updateRole(
        conferenceId: UUID,
        identity: String,
        role: ParticipantRole,
    ): ConferenceParticipant? =
        jdbcTemplate
            .query(UPDATE_ROLE_SQL, ROW_MAPPER, role.pg(), conferenceId, identity)
            .firstOrNull()

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ConferenceParticipant(
                    id = rs.getObject("id", UUID::class.java),
                    conferenceId = rs.getObject("conference_id", UUID::class.java),
                    identity = principal(rs.getString("identity")),
                    displayName = rs.getString("display_name"),
                    role = participantRole(rs.getString("role")),
                    restrictions =
                        ParticipantRestrictions(
                            micRestricted = rs.getBoolean("mic_restricted"),
                            cameraRestricted = rs.getBoolean("camera_restricted"),
                            screenRestricted = rs.getBoolean("screen_restricted"),
                            speechRestricted = rs.getBoolean("speech_restricted"),
                        ),
                    firstSeenAt = rs.getTimestamp("first_seen_at").toInstant(),
                    lastJoinedAt = rs.getTimestamp("last_joined_at")?.toInstant(),
                    lastLeftAt = rs.getTimestamp("last_left_at")?.toInstant(),
                )
            }

        /** Every projection selects the same column set as [ROW_MAPPER] maps. */
        const val COLUMNS =
            "id, conference_id, identity, user_id, display_name, role, mic_restricted, " +
                "camera_restricted, screen_restricted, speech_restricted, first_seen_at, " +
                "last_joined_at, last_left_at"

        val INSERT_SQL =
            """
            INSERT INTO conference_participants
                (id, conference_id, identity, user_id, display_name, role,
                 mic_restricted, camera_restricted, screen_restricted, speech_restricted, first_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING $COLUMNS
            """.trimIndent()

        val INSERT_MEMBER_ON_CONFLICT_SQL =
            """
            INSERT INTO conference_participants (id, conference_id, identity, user_id, role, first_seen_at)
            VALUES (?, ?, ?, ?, 'participant', ?)
            ON CONFLICT (conference_id, user_id) WHERE user_id IS NOT NULL DO NOTHING
            RETURNING $COLUMNS
            """.trimIndent()

        val FIND_MEMBER_SQL =
            """
            SELECT $COLUMNS
            FROM conference_participants
            WHERE conference_id = ? AND user_id = ?
            """.trimIndent()

        val FIND_BY_IDENTITY_SQL =
            """
            SELECT $COLUMNS
            FROM conference_participants
            WHERE conference_id = ? AND identity = ?
            """.trimIndent()

        val MARK_JOINED_SQL =
            """
            UPDATE conference_participants
            SET last_joined_at = ?
            WHERE conference_id = ? AND identity = ?
            """.trimIndent()

        val MARK_LEFT_SQL =
            """
            UPDATE conference_participants
            SET last_left_at = ?
            WHERE conference_id = ? AND identity = ?
            """.trimIndent()

        val UPDATE_RESTRICTIONS_SQL =
            """
            UPDATE conference_participants
            SET mic_restricted = ?, camera_restricted = ?, screen_restricted = ?, speech_restricted = ?
            WHERE conference_id = ? AND identity = ?
            RETURNING $COLUMNS
            """.trimIndent()

        val UPDATE_ROLE_SQL =
            """
            UPDATE conference_participants
            SET role = ?
            WHERE conference_id = ? AND identity = ?
            RETURNING $COLUMNS
            """.trimIndent()

        /**
         * Stored identities are the principal prefixes this adapter itself
         * writes (CHECK-constrained shape around them), so the read-back is
         * an identity parse; opaque UUIDs only — nothing PII to log.
         */
        fun principal(raw: String): ConferenceIdentity =
            ConferenceIdentity.parsePrincipal(raw)
                ?: error("stored conference_participants.identity is not a principal prefix")
    }
}

/** The V18 `ck_conference_participants_role` wire names are the lowercase enum constants. */
private fun ParticipantRole.pg(): String = name.lowercase()

/** Stored rows are CHECK-constrained to the enum, so the read-back is an identity parse. */
private fun participantRole(raw: String): ParticipantRole = ParticipantRole.valueOf(raw.uppercase())
