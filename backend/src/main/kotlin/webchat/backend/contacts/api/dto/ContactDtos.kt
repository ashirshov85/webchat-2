package webchat.backend.contacts.api.dto

import webchat.backend.contacts.domain.model.UserProfile
import java.time.Instant
import java.util.UUID

/**
 * The reused contract schema `PublicUser {id, username, email, status,
 * createdAt}` (openapi.yaml 0.4.0) — the projection carried by №19
 * `users.search` answers and by [ContactView.user]; no password material
 * or internal columns (SC-005).
 */
data class PublicUserView(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
)

/** [UserProfile] projected as the contract `PublicUser` schema — verbatim, no reshaping. */
internal fun UserProfile.toPublicUserView(): PublicUserView =
    PublicUserView(
        id = id,
        username = username,
        email = email,
        status = status,
        createdAt = createdAt,
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
 * Contract №20/№21 success body — `ContactView` (openapi.yaml 0.4.0):
 * the contact user as `PublicUser` plus the `createdAt` of the stored
 * `user_contacts` row (a repeat add returns the SAME row, edge spec).
 */
data class ContactView(
    val user: PublicUserView,
    val createdAt: Instant,
)

/** Contract №20 success body — `ContactsResponse {contacts: [ContactView]}`; an empty list is valid. */
data class ContactsResponse(
    val contacts: List<ContactView>,
)

/** Contract №19 success body — `UsersSearchResponse {users: [PublicUser]}`: 0..1 element, empty is valid (edge). */
data class UsersSearchResponse(
    val users: List<PublicUserView>,
)
