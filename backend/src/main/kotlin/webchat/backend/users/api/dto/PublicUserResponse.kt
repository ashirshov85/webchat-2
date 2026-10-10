package webchat.backend.users.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * Contract №10 success body — `PublicUser` (api-contract.md §4): exactly
 * `{id, username, email, status, createdAt}` (`additionalProperties: false`)
 * — no password material, hashes or internal timestamps leave the service
 * (SC-005). `status` carries the lowercase `user_status` enum of the
 * contract (`pending_email_confirmation` / `awaiting_password` / `active`).
 *
 * 008a (api-contract.md §2): the OPTIONAL [displayName] — the profile
 * display name of FR-001, served by every `PublicUser` `$ref` surface
 * (№10/№39 here; the peer/search/member projections join in T015–T017).
 * NULL = «not set» and renders the field ABSENT (backward compatibility:
 * 008 clients fall back to `username`, SC-007); the stored value is
 * already the trimmed form of [DisplayName][webchat.backend.users.domain.model.DisplayName]
 * (1–64 chars, the `ck_users_display_name` CHECK of V16).
 */
data class PublicUserResponse(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
    @JsonInclude(JsonInclude.Include.NON_NULL) val displayName: String? = null,
)
