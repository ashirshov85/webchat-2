package webchat.backend.contacts.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.contacts.domain.model.UserProfile
import webchat.backend.contacts.domain.port.UserLookupPort
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [UserLookupPort] (data-model 004 §6; DIP: `users` is an
 * entity of 002 — here its rows are only read through the public
 * `PublicUser` projection, no password material).
 *
 * Search goes through `lower(email)` / `lower(username)` — the exact
 * expressions of the 002 unique indexes — routed by the @-rule
 * (api-contract.md №19, FR-016): a query containing `@` compares the email
 * only, everything else the login; the branches are disjoint because a
 * username cannot contain `@` (002 rule).
 */
@Repository
class JdbcUserLookup(
    private val jdbcTemplate: JdbcTemplate,
) : UserLookupPort {
    override fun findById(userId: UUID): UserProfile? =
        jdbcTemplate
            .query(FIND_BY_ID_SQL, ROW_MAPPER, userId)
            .firstOrNull()

    override fun searchExact(query: String): UserProfile? {
        val normalized = query.lowercase()
        val sql = if ('@' in query) FIND_BY_EMAIL_SQL else FIND_BY_USERNAME_SQL
        return jdbcTemplate.query(sql, ROW_MAPPER, normalized).firstOrNull()
    }

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                UserProfile(
                    id = rs.getObject("id", UUID::class.java),
                    username = rs.getString("username"),
                    email = rs.getString("email"),
                    status = rs.getString("status"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val FIND_BY_ID_SQL = findSql("id = ?")
        val FIND_BY_EMAIL_SQL = findSql("lower(email) = ?")
        val FIND_BY_USERNAME_SQL = findSql("lower(username) = ?")

        private fun findSql(condition: String): String =
            """
            SELECT id, username, email, status, created_at
            FROM users
            WHERE $condition
            """.trimIndent()
    }
}
