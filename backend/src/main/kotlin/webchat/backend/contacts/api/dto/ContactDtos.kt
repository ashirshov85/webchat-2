package webchat.backend.contacts.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import webchat.backend.contacts.domain.model.UserProfile
import java.time.Instant
import java.util.UUID

/**
 * The reused contract schema `PublicUser {id, username, email, status,
 * createdAt}` (openapi.yaml 0.4.0) — the projection carried by №19
 * `users.search` answers and by [ContactView.user]; no password material
 * or internal columns (SC-005).
 *
 * 008a (api-contract.md §2, T014): the OPTIONAL [displayName] — the
 * profile display name of FR-001 rides every `PublicUser` `$ref`
 * surface, here the `ContactView.user` of №20/№21/№40. NULL = «not
 * set» and renders the field ABSENT (backward compatibility: 008
 * clients fall back to `username`, SC-007); the column value already
 * re-validates against the `ck_users_display_name` CHECK of V16 on
 * every read.
 */
data class PublicUserView(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
    @JsonInclude(JsonInclude.Include.NON_NULL) val displayName: String? = null,
)

/** [UserProfile] projected as the contract `PublicUser` schema — verbatim, no reshaping. */
internal fun UserProfile.toPublicUserView(): PublicUserView =
    PublicUserView(
        id = id,
        username = username,
        email = email,
        status = status,
        createdAt = createdAt,
        displayName = displayName?.value,
    )

/**
 * Contract №21 request body — exactly `{userId: uuid}`.
 *
 * The id arrives as a raw string on purpose: a malformed or absent value
 * is rejected HERE as the contract 400 `errors: {userId: [invalid_uuid]}`
 * (problem+json), instead of dying in deserialization — the reply stays a
 * typed problem, never the default error page (the same convention as
 * `EnsureChatRequest.peerUserId`).
 */
data class AddContactRequest(
    val userId: String? = null,
)

/**
 * The №40 PUT body (openapi `AliasUpdateRequest`, T014): bound
 * leniently as a plain JSON value — the FR-003 shape gate is the
 * controller's `400 invalid_alias` (errors.alias), not a framework
 * parse failure (the [ProfileUpdateRequest][webchat.backend.users.api.ProfileUpdateRequest]
 * precedent of №39); `null`/omitted = the reset leg («not set»).
 */
data class AliasUpdateRequest(
    val alias: Any? = null,
)

/**
 * Contract №20/№21 success body — `ContactView` (openapi.yaml 0.8.0):
 * the contact user as `PublicUser`, the `createdAt` of the stored
 * `user_contacts` row (a repeat add returns the SAME row, edge spec) and
 * `blockedByMe` — the caller's OWN block mark of this contact (T097,
 * bug 16): the same `user_blocks` relation №12/№13 project, kept here so
 * the state survives a deleted (hidden) dialog; strictly one-directional
 * — no inverse «who blocked me» field exists (FR-020).
 *
 * 008a (api-contract.md §2, T014): the OPTIONAL [alias] — the caller's
 * personal name for the contact (FR-003), served on №20/№21/№40 of the
 * OWNER only; NULL = «not set» renders the field ABSENT (008 clients
 * fall back to displayName/`username`, SC-007) and the value is the
 * stored trimmed form of [ContactAlias][webchat.backend.contacts.domain.model.ContactAlias]
 * (the `ck_user_contacts_alias` CHECK of V16).
 */
data class ContactView(
    val user: PublicUserView,
    val createdAt: Instant,
    val blockedByMe: Boolean,
    @JsonInclude(JsonInclude.Include.NON_NULL) val alias: String? = null,
)

/** Contract №20 success body — `ContactsResponse {contacts: [ContactView]}`; an empty list is valid. */
data class ContactsResponse(
    val contacts: List<ContactView>,
)

/** Contract №19 success body — `UsersSearchResponse {users: [PublicUser]}`: 0..1 element, empty is valid (edge). */
data class UsersSearchResponse(
    val users: List<PublicUserView>,
)
