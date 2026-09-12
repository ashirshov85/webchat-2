package webchat.backend.auth.domain.port

import webchat.backend.auth.domain.model.User
import java.util.UUID

/**
 * Persistence port for the User aggregate (data-model.md §1).
 *
 * Username and email lookups are case-insensitive, backed by the unique
 * lower(username)/lower(email) indexes (FR-001).
 */
interface UserRepository {
    fun insert(user: User)

    fun update(user: User)

    fun findById(id: UUID): User?

    fun findByUsername(username: String): User?

    fun findByEmail(email: String): User?
}
