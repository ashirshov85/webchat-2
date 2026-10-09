package webchat.backend.users.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.users.domain.model.DisplayName
import webchat.backend.users.domain.model.Profile
import webchat.backend.users.domain.port.ProfileStore
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [ProfileStore] (data-model 008a §1.1; DIP: the adapter
 * lives outside the domain): ONE `UPDATE … RETURNING` statement writes
 * `display_name` and reads back the updated public projection atomically —
 * the №39 answer never races a concurrent write. The value arrives
 * already trimmed by [DisplayName.normalize], satisfying the
 * `ck_users_display_name` CHECK of V16 on every write; the read leg
 * rebuilds the value object from the CHECK-guaranteed column (the
 * [MessageText][webchat.backend.chats.domain.model.MessageText]
 * discipline). Only the profile column (and the row's `updated_at`) is
 * touched: password/status material of the 002 aggregate is out of scope.
 */
@Repository
class JdbcProfileStore(
    private val jdbcTemplate: JdbcTemplate,
) : ProfileStore {
    override fun updateDisplayName(
        userId: UUID,
        displayName: DisplayName?,
    ): Profile =
        jdbcTemplate
            .query(UPDATE_SQL, ROW_MAPPER, displayName?.value, userId)
            .firstOrNull()
            ?: error("a display-name write must target an existing user — the №39 API gates own that check")

    private companion object {
        val ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Profile(
                    id = rs.getObject("id", UUID::class.java),
                    username = rs.getString("username"),
                    email = rs.getString("email"),
                    status = rs.getString("status"),
                    displayName = rs.getString("display_name")?.let(DisplayName::normalize),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val UPDATE_SQL =
            """
            UPDATE users
            SET display_name = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            RETURNING id, username, email, status, display_name, created_at
            """.trimIndent()
    }
}
