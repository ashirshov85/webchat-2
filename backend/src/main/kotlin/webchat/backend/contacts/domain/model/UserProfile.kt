package webchat.backend.contacts.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Public read model of a `users` (002) row as seen by the contacts
 * feature (DIP: users are entities of 002, here only read through
 * [webchat.backend.contacts.domain.port.UserLookupPort]). Mirrors the
 * contract `PublicUser` projection `{id, username, email, status,
 * createdAt}` used by №19 `users.search` and `ContactView.user` (№20/№21)
 * — no password material or internal timestamps (SC-005).
 *
 * [status] carries the lowercase `user_status` enum of 002/contract:
 * `pending_email_confirmation` / `awaiting_password` / `active`.
 */
data class UserProfile(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
)
