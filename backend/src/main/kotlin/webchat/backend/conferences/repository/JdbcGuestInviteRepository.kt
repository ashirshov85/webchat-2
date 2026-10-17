package webchat.backend.conferences.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.conferences.domain.model.GuestInviteStatus
import webchat.backend.conferences.domain.port.GuestInviteIssueResult
import webchat.backend.conferences.domain.port.GuestInviteRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC adapter for [GuestInviteRepository] (data-model 009 §Сущность 3;
 * DIP: the adapter lives outside the domain, T011 — JdbcTemplate, the
 * domain convention without JPA).
 *
 * [issue] is the ONE №52 transaction that keeps the partial unique index
 * `ux_guest_invites_active` unbroken (Q17=A): the conditional
 * revoke of the conference's current ACTIVE row runs BEFORE the INSERT
 * of the fresh one, so no observer — not even the constraint itself —
 * can ever see two ACTIVE rows; the pre-revoked row is handed back for
 * the post-commit disconnect of its connected guests (Q13=B). [revoke]
 * is the №53 conditional UPDATE…RETURNING resolved by rowcount: an
 * unknown id AND an already-revoked invite answer identically (`null`
 * → the caller's `404 guest_invite_not_found`), and a revoke keeps the
 * ORIGINAL `revoked_at` of the first win. There is NO delete leg of
 * its own: the finalize transaction of
 * [JdbcConferenceRepository.finalize] removes every row of the
 * conference (Q18=A).
 */
@Repository
class JdbcGuestInviteRepository(
    private val jdbcTemplate: JdbcTemplate,
) : GuestInviteRepository {
    @Transactional
    override fun issue(invite: GuestInvite): GuestInviteIssueResult {
        val revokedPrevious =
            jdbcTemplate
                .query(
                    REVOKE_ACTIVE_OF_CONFERENCE_SQL,
                    ROW_MAPPER,
                    Timestamp.from(invite.createdAt),
                    invite.conferenceId,
                ).firstOrNull()
        val inserted =
            jdbcTemplate
                .query(
                    INSERT_SQL,
                    ROW_MAPPER,
                    invite.id,
                    invite.conferenceId,
                    invite.createdBy,
                    invite.status.pg(),
                    Timestamp.from(invite.expiresAt),
                    Timestamp.from(invite.createdAt),
                ).firstOrNull()
                ?: error("INSERT … RETURNING must answer")
        return GuestInviteIssueResult(invite = inserted, revokedPrevious = revokedPrevious)
    }

    override fun revoke(
        id: UUID,
        at: Instant,
    ): GuestInvite? =
        jdbcTemplate
            .query(REVOKE_BY_ID_SQL, ROW_MAPPER, Timestamp.from(at), id)
            .firstOrNull()

    override fun findById(id: UUID): GuestInvite? =
        jdbcTemplate
            .query(FIND_BY_ID_SQL, ROW_MAPPER, id)
            .firstOrNull()

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                GuestInvite(
                    id = rs.getObject("id", UUID::class.java),
                    conferenceId = rs.getObject("conference_id", UUID::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    status = guestInviteStatus(rs.getString("status")),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at").toInstant(),
                    revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                )
            }

        /** Every projection selects the same column set as [ROW_MAPPER] maps. */
        const val COLUMNS = "id, conference_id, created_by, status, expires_at, created_at, revoked_at"

        val REVOKE_ACTIVE_OF_CONFERENCE_SQL =
            """
            UPDATE conference_guest_invites
            SET status = 'revoked', revoked_at = ?
            WHERE conference_id = ? AND status = 'active'
            RETURNING $COLUMNS
            """.trimIndent()

        val INSERT_SQL =
            """
            INSERT INTO conference_guest_invites (id, conference_id, created_by, status, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING $COLUMNS
            """.trimIndent()

        val REVOKE_BY_ID_SQL =
            """
            UPDATE conference_guest_invites
            SET status = 'revoked', revoked_at = ?
            WHERE id = ? AND status = 'active'
            RETURNING $COLUMNS
            """.trimIndent()

        val FIND_BY_ID_SQL =
            """
            SELECT $COLUMNS
            FROM conference_guest_invites
            WHERE id = ?
            """.trimIndent()
    }
}

/** The V18 `ck_conference_guest_invites_status` wire names are the lowercase enum constants. */
private fun GuestInviteStatus.pg(): String = name.lowercase()

/** Stored rows are CHECK-constrained to the enum, so the read-back is an identity parse. */
private fun guestInviteStatus(raw: String): GuestInviteStatus = GuestInviteStatus.valueOf(raw.uppercase())
