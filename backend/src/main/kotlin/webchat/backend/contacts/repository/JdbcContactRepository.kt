package webchat.backend.contacts.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import webchat.backend.contacts.domain.model.Contact
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.model.UserProfile
import webchat.backend.contacts.domain.port.ContactAddResult
import webchat.backend.contacts.domain.port.ContactEntry
import webchat.backend.contacts.domain.port.ContactRepository
import java.sql.ResultSet
import java.util.UUID

/**
 * JDBC adapter for [ContactRepository] (data-model 004 §4; DIP: the adapter
 * lives outside the domain).
 *
 * The add is idempotent at the storage level: `INSERT … ON CONFLICT
 * (owner_id, contact_user_id) DO NOTHING` where the rowcount distinguishes
 * [ContactAddResult.Created] (`201`, api-contract.md №21) from
 * [ContactAddResult.Existing] (`200` with the SAME row — the committed
 * `created_at` survives, edge spec). The remove is an unconditional
 * single-row DELETE by the PK pair. Contacts never touch `user_blocks`
 * or the V10 tables (FR-017/FR-020).
 */
@Repository
class JdbcContactRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ContactRepository {
    @Transactional
    override fun add(
        ownerId: UUID,
        contactUserId: UUID,
    ): ContactAddResult {
        require(ownerId != contactUserId) { "a contact entry requires two distinct users (FR-016)" }
        val created = jdbcTemplate.update(INSERT_SQL, ownerId, contactUserId) == 1
        val contact =
            jdbcTemplate
                .query(FIND_SQL, CONTACT_ROW_MAPPER, ownerId, contactUserId)
                .firstOrNull()
                ?: error("contact add could not resolve the entry inside its transaction")
        return if (created) ContactAddResult.Created(contact) else ContactAddResult.Existing(contact)
    }

    override fun remove(
        ownerId: UUID,
        contactUserId: UUID,
    ) {
        jdbcTemplate.update(DELETE_SQL, ownerId, contactUserId)
    }

    override fun listByOwner(
        ownerId: UUID,
        sort: ContactSort,
    ): List<ContactEntry> = jdbcTemplate.query(listSql(sort), ENTRY_ROW_MAPPER, ownerId)

    private companion object {
        val CONTACT_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                Contact(
                    ownerId = rs.getObject("owner_id", UUID::class.java),
                    contactUserId = rs.getObject("contact_user_id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            }

        val ENTRY_ROW_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                ContactEntry(
                    contact =
                        Contact(
                            ownerId = rs.getObject("owner_id", UUID::class.java),
                            contactUserId = rs.getObject("contact_user_id", UUID::class.java),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                        ),
                    user = userProfile(rs),
                )
            }

        private fun userProfile(rs: ResultSet): UserProfile =
            UserProfile(
                id = rs.getObject("user_id", UUID::class.java),
                username = rs.getString("username"),
                email = rs.getString("email"),
                status = rs.getString("status"),
                createdAt = rs.getTimestamp("user_created_at").toInstant(),
            )

        val INSERT_SQL =
            """
            INSERT INTO user_contacts (owner_id, contact_user_id)
            VALUES (?, ?)
            ON CONFLICT (owner_id, contact_user_id) DO NOTHING
            """.trimIndent()

        val FIND_SQL =
            """
            SELECT owner_id, contact_user_id, created_at
            FROM user_contacts
            WHERE owner_id = ? AND contact_user_id = ?
            """.trimIndent()

        val DELETE_SQL =
            """
            DELETE FROM user_contacts
            WHERE owner_id = ? AND contact_user_id = ?
            """.trimIndent()

        private fun listSql(sort: ContactSort): String {
            val orderBy =
                when (sort) {
                    ContactSort.LOGIN -> "lower(u.username)"
                    ContactSort.EMAIL -> "lower(u.email)"
                }
            return """
                SELECT c.owner_id, c.contact_user_id, c.created_at,
                       u.id AS user_id, u.username, u.email, u.status, u.created_at AS user_created_at
                FROM user_contacts c
                JOIN users u ON u.id = c.contact_user_id
                WHERE c.owner_id = ?
                ORDER BY $orderBy
                """.trimIndent()
        }
    }
}
