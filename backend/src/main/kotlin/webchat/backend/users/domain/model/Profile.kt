package webchat.backend.users.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Read model of a `users` row for the profile feature (data-model 008a
 * §1.1): what №39 answers after a write — the `PublicUser` projection
 * `{id, username, email, status, createdAt}` plus the optional
 * [displayName] (NULL = «not set», rendered ABSENT by the API layer —
 * backward compatibility, SC-007). [status] carries the lowercase
 * `user_status` enum of 002/contract. No password material or internal
 * timestamps (SC-005).
 */
data class Profile(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val displayName: DisplayName?,
    val createdAt: Instant,
)
