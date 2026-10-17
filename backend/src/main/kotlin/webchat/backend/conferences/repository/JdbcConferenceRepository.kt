package webchat.backend.conferences.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceStatus
import webchat.backend.conferences.domain.port.ConferenceCreateResult
import webchat.backend.conferences.domain.port.ConferenceRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC adapter for [ConferenceRepository] (data-model 009 §Сущность 1;
 * DIP: the adapter lives outside the domain, T010 — JdbcTemplate, the
 * domain convention without JPA).
 *
 * Every status move is the CONDITIONAL single-statement UPDATE of the
 * port contract resolved through RETURNING: [activate] touches only a
 * `created` row, [finalize] only a non-`ended` one — an empty result
 * is the idempotent no-op with NO side effects, so duplicate
 * `room_finished` webhooks, №48 racing the webhook and the sweeper
 * racing both converge on the first winner (research §7, invariant 5).
 * [finalize] is the ONLY writer that removes principal and invite rows
 * (Q18=A) and does so strictly inside the same transaction as the
 * winning UPDATE; [create] resolves the `ux_conferences_chat_active`
 * race the same READ COMMITTED way as the messages adapter of 004 —
 * the competing row is already committed, hence visible to the
 * follow-up SELECT of the SAME transaction.
 */
@Repository
class JdbcConferenceRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ConferenceRepository {
    @Transactional
    override fun create(conference: Conference): ConferenceCreateResult {
        val inserted =
            jdbcTemplate
                .query(
                    INSERT_ON_CONFLICT_SQL,
                    ROW_MAPPER,
                    conference.id,
                    conference.chatId,
                    conference.createdBy,
                    conference.status.pg(),
                    conference.maxParticipants,
                    Timestamp.from(conference.createdAt),
                ).firstOrNull()
        if (inserted != null) return ConferenceCreateResult.Created(inserted)
        val existing =
            jdbcTemplate
                .query(FIND_UNENDED_BY_CHAT_SQL, ROW_MAPPER, conference.chatId)
                .firstOrNull()
                ?: error("conference conflict could not resolve the existing row inside its transaction")
        return ConferenceCreateResult.Existing(existing)
    }

    override fun findById(id: UUID): Conference? = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id).firstOrNull()

    override fun findUnendedByChatId(chatId: UUID): List<Conference> =
        jdbcTemplate.query(FIND_UNENDED_BY_CHAT_SQL, ROW_MAPPER, chatId)

    override fun findByStatus(status: ConferenceStatus): List<Conference> =
        jdbcTemplate.query(FIND_BY_STATUS_SQL, ROW_MAPPER, status.pg())

    override fun activate(
        id: UUID,
        at: Instant,
    ): Conference? = jdbcTemplate.query(ACTIVATE_SQL, ROW_MAPPER, Timestamp.from(at), id).firstOrNull()

    @Transactional
    override fun finalize(
        id: UUID,
        reason: ConferenceEndReason,
        at: Instant,
    ): Conference? {
        val ended =
            jdbcTemplate
                .query(FINALIZE_SQL, ROW_MAPPER, Timestamp.from(at), reason.pg(), id)
                .firstOrNull()
                ?: return null
        // Q18=A: the roster of a finished conference is neither kept nor
        // shown — both deletions ride the SAME transaction as the winning
        // UPDATE, so a no-op finalize never deletes a live roster.
        jdbcTemplate.update(DELETE_PARTICIPANTS_SQL, id)
        jdbcTemplate.update(DELETE_GUEST_INVITES_SQL, id)
        return ended
    }

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Conference(
                    id = rs.getObject("id", UUID::class.java),
                    chatId = rs.getObject("chat_id", UUID::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    status = conferenceStatus(rs.getString("status")),
                    maxParticipants = rs.getInt("max_participants"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    startedAt = rs.getTimestamp("started_at")?.toInstant(),
                    endedAt = rs.getTimestamp("ended_at")?.toInstant(),
                    endReason = rs.getString("end_reason")?.let(::conferenceEndReason),
                )
            }

        /** Every projection selects the same column set as [ROW_MAPPER] maps. */
        const val COLUMNS =
            "id, chat_id, created_by, status, max_participants, " +
                "created_at, started_at, ended_at, end_reason"

        val INSERT_ON_CONFLICT_SQL =
            """
            INSERT INTO conferences (id, chat_id, created_by, status, max_participants, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (chat_id) WHERE status <> 'ended' DO NOTHING
            RETURNING $COLUMNS
            """.trimIndent()

        val FIND_BY_ID_SQL =
            """
            SELECT $COLUMNS
            FROM conferences
            WHERE id = ?
            """.trimIndent()

        val FIND_UNENDED_BY_CHAT_SQL =
            """
            SELECT $COLUMNS
            FROM conferences
            WHERE chat_id = ? AND status <> 'ended'
            """.trimIndent()

        val FIND_BY_STATUS_SQL =
            """
            SELECT $COLUMNS
            FROM conferences
            WHERE status = ?
            """.trimIndent()

        val ACTIVATE_SQL =
            """
            UPDATE conferences
            SET status = 'active', started_at = ?
            WHERE id = ? AND status = 'created'
            RETURNING $COLUMNS
            """.trimIndent()

        val FINALIZE_SQL =
            """
            UPDATE conferences
            SET status = 'ended', ended_at = ?, end_reason = ?
            WHERE id = ? AND status <> 'ended'
            RETURNING $COLUMNS
            """.trimIndent()

        val DELETE_PARTICIPANTS_SQL =
            """
            DELETE FROM conference_participants
            WHERE conference_id = ?
            """.trimIndent()

        val DELETE_GUEST_INVITES_SQL =
            """
            DELETE FROM conference_guest_invites
            WHERE conference_id = ?
            """.trimIndent()
    }
}

/** The V18 `ck_conferences_status` wire names are the lowercase enum constants. */
private fun ConferenceStatus.pg(): String = name.lowercase()

/** The V18 `ck_conferences_end_reason` wire names are the lowercase enum constants. */
private fun ConferenceEndReason.pg(): String = name.lowercase()

/** Stored rows are CHECK-constrained to the enum, so the read-back is an identity parse. */
private fun conferenceStatus(raw: String): ConferenceStatus = ConferenceStatus.valueOf(raw.uppercase())

private fun conferenceEndReason(raw: String): ConferenceEndReason = ConferenceEndReason.valueOf(raw.uppercase())
