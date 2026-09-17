package webchat.backend.sso.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.sso.domain.model.ExternalIdentity
import webchat.backend.sso.domain.port.ExternalIdentityRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * JDBC adapter for [ExternalIdentityRepository] (data-model.md §1).
 *
 * A duplicate `(provider_id, subject)` insert surfaces as a unique violation
 * (ux_external_identities_provider_subject) — the caller maps it to
 * `identity_taken` (data-model.md §7 Link). Deleting the last login method of a
 * passwordless account is rejected by the `keep_at_least_one_login_method`
 * trigger (data-model.md §6) — the caller maps it to 409 `last_login_method`.
 */
@Repository
class JdbcExternalIdentityRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ExternalIdentityRepository {
    override fun insert(identity: ExternalIdentity) {
        jdbcTemplate.update(
            INSERT_SQL,
            identity.id,
            identity.userId,
            identity.providerId,
            identity.subject,
            identity.providerEmail,
            identity.providerEmailVerified,
            Timestamp.from(identity.linkedAt),
        )
    }

    override fun findByProviderAndSubject(
        providerId: String,
        subject: String,
    ): ExternalIdentity? =
        jdbcTemplate
            .query(
                FIND_BY_PROVIDER_AND_SUBJECT_SQL,
                ROW_MAPPER,
                providerId,
                subject,
            ).firstOrNull()

    override fun findByUserId(userId: UUID): List<ExternalIdentity> =
        jdbcTemplate.query(
            FIND_BY_USER_ID_SQL,
            ROW_MAPPER,
            userId,
        )

    override fun delete(id: UUID): Boolean = jdbcTemplate.update(DELETE_SQL, id) > 0

    override fun updateProviderEmail(
        id: UUID,
        providerEmail: String?,
        providerEmailVerified: Boolean,
    ) {
        jdbcTemplate.update(
            UPDATE_PROVIDER_EMAIL_SQL,
            providerEmail?.trim()?.takeIf { it.isNotEmpty() },
            providerEmailVerified,
            id,
        )
    }

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ExternalIdentity(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    providerId = rs.getString("provider_id"),
                    subject = rs.getString("subject"),
                    providerEmail = rs.getString("provider_email"),
                    providerEmailVerified = rs.getBoolean("provider_email_verified"),
                    linkedAt = rs.getTimestamp("linked_at").toInstant(),
                )
            }

        val INSERT_SQL =
            """
            INSERT INTO external_identities (id, user_id, provider_id, subject, provider_email, provider_email_verified, linked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        val FIND_BY_PROVIDER_AND_SUBJECT_SQL = findSql("provider_id = ? AND subject = ?")

        val FIND_BY_USER_ID_SQL = findSql("user_id = ? ORDER BY linked_at")

        const val DELETE_SQL = "DELETE FROM external_identities WHERE id = ?"

        val UPDATE_PROVIDER_EMAIL_SQL =
            """
            UPDATE external_identities
            SET provider_email = ?, provider_email_verified = ?
            WHERE id = ?
            """.trimIndent()

        private fun findSql(condition: String): String =
            """
            SELECT id, user_id, provider_id, subject, provider_email, provider_email_verified, linked_at
            FROM external_identities
            WHERE $condition
            """.trimIndent()
    }
}
