package webchat.backend.conferences.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.conferences.domain.port.MemberNameReader
import java.sql.ResultSet
import java.util.UUID

/**
 * T018 — the DIP adapter of [MemberNameReader]: one point read of
 * `users.username` (the §3 `name` claim of a №47 member token) over
 * [JdbcTemplate]. `users` is an entity of 002 — read directly by the
 * bare column, the established cross-domain monolith convention
 * ([ChatMembershipCheckerImpl] reads `chats`, [JdbcUserLookup] reads
 * `users` the same way); the auth aggregate's write surface and
 * password material are never touched.
 */
@Repository
class MemberNameReaderImpl(
    private val jdbcTemplate: JdbcTemplate,
) : MemberNameReader {
    override fun usernameOf(userId: UUID): String? =
        jdbcTemplate
            .query(FIND_USERNAME_SQL, ROW_MAPPER, userId)
            .firstOrNull()

    private companion object {
        const val USERNAME_COLUMN = "username"

        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int -> rs.getString(USERNAME_COLUMN) }

        val FIND_USERNAME_SQL =
            """
            SELECT username
            FROM users
            WHERE id = ?
            """.trimIndent()
    }
}
