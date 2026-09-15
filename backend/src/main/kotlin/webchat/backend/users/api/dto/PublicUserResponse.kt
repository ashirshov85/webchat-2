package webchat.backend.users.api.dto

import java.time.Instant
import java.util.UUID

/**
 * Contract №10 success body — `PublicUser` (api-contract.md §4): exactly
 * `{id, username, email, status, createdAt}` (`additionalProperties: false`)
 * — no password material, hashes or internal timestamps leave the service
 * (SC-005). `status` carries the lowercase `user_status` enum of the
 * contract (`pending_email_confirmation` / `awaiting_password` / `active`).
 */
data class PublicUserResponse(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
)
