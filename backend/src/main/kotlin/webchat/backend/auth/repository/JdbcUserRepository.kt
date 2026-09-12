package webchat.backend.auth.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.model.UserStatus
import webchat.backend.auth.domain.port.UserRepository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * JDBC adapter for [UserRepository] (data-model.md §1): lookups go through
 * lower(username)/lower(email) to match the unique expression indexes.
 */
@Repository
class JdbcUserRepository(
    private val jdbcTemplate: JdbcTemplate,
) : UserRepository {
    override fun insert(user: User) {
        jdbcTemplate.update(
            INSERT_SQL,
            user.id,
            user.username,
            user.email,
            user.passwordHash,
            user.status.name.lowercase(),
            toDb(user.emailConfirmedAt),
            toDb(user.passwordSetAt),
            toDb(user.createdAt),
            toDb(user.updatedAt),
        )
    }

    override fun update(user: User) {
        jdbcTemplate.update(
            UPDATE_SQL,
            user.passwordHash,
            user.status.name.lowercase(),
            toDb(user.emailConfirmedAt),
            toDb(user.passwordSetAt),
            toDb(user.updatedAt),
            user.id,
        )
    }

    override fun findById(id: UUID): User? = jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id).firstOrNull()

    override fun findByUsername(username: String): User? = findOne(FIND_BY_USERNAME_SQL, username.lowercase())

    override fun findByEmail(email: String): User? = findOne(FIND_BY_EMAIL_SQL, email.lowercase())

    private fun findOne(
        sql: String,
        argument: String,
    ): User? = jdbcTemplate.query(sql, ROW_MAPPER, argument).firstOrNull()

    private fun toDb(instant: Instant?): Timestamp? = instant?.let(Timestamp::from)

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                User(
                    id = rs.getObject("id", UUID::class.java),
                    username = rs.getString("username"),
                    email = rs.getString("email"),
                    passwordHash = rs.getString("password_hash"),
                    status = UserStatus.valueOf(rs.getString("status").uppercase()),
                    emailConfirmedAt = rs.getTimestamp("email_confirmed_at")?.toInstant(),
                    passwordSetAt = rs.getTimestamp("password_set_at")?.toInstant(),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                )
            }

        val INSERT_SQL =
            """
            INSERT INTO users (id, username, email, password_hash, status, email_confirmed_at, password_set_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?::user_status, ?, ?, ?, ?)
            """.trimIndent()

        val UPDATE_SQL =
            """
            UPDATE users
            SET password_hash = ?, status = ?::user_status, email_confirmed_at = ?, password_set_at = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent()

        val FIND_BY_ID_SQL = findSql("id = ?")
        val FIND_BY_USERNAME_SQL = findSql("lower(username) = ?")
        val FIND_BY_EMAIL_SQL = findSql("lower(email) = ?")

        private fun findSql(condition: String): String =
            """
            SELECT id, username, email, password_hash, status, email_confirmed_at,
                   password_set_at, created_at, updated_at
            FROM users
            WHERE $condition
            """.trimIndent()
    }
}
