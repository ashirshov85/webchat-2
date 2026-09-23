package webchat.backend.contacts.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A one-sided contact list entry (data-model 004 §4; FR-016): one row per
 * (owner, contact user) — the composite PK of V11 `user_contacts` makes
 * re-adding idempotent at the storage level (edge spec: repeat → `200`
 * with the existing entry, no duplicate). The `contact_user_id <> owner_id`
 * CHECK of V11 is the second line of defense; the primary self-guard is
 * the service layer (`422 self_forbidden`, T052).
 *
 * Independence (FR-017/FR-020): removing a contact never touches the chat
 * or history of the pair; blocks never touch contacts and vice versa.
 * Clicking a contact opens the dialog via `POST /chats/ensure` (FR-018).
 */
data class Contact(
    val ownerId: UUID,
    val contactUserId: UUID,
    val createdAt: Instant,
) {
    init {
        require(ownerId != contactUserId) { "a contact entry requires two distinct users (FR-016)" }
    }

    companion object {
        /**
         * `POST /contacts` (№21): a new entry for the owner's list; the
         * service (T052) rejects `ownerId == contactUserId` and an unknown
         * contact user BEFORE the repository call.
         */
        fun between(
            ownerId: UUID,
            contactUserId: UUID,
            at: Instant,
        ): Contact = Contact(ownerId = ownerId, contactUserId = contactUserId, createdAt = at)
    }
}

/**
 * FR-015: the server-side case-insensitive sort field of the contact list
 * (№20 `GET /contacts?sort=`): `lower(username)` for [LOGIN] (default),
 * `lower(email)` for [EMAIL] — the same expressions as the 002 unique
 * indexes on `users`. Anything but `login`/`email` is refused by the API
 * layer with `400 invalid_sort` (T053).
 */
enum class ContactSort {
    LOGIN,
    EMAIL,
    ;

    companion object {
        /** Raw contract value of №20 → enum, `null` → `400 invalid_sort` (T053). */
        fun fromRaw(raw: String?): ContactSort? =
            when (raw) {
                null, "login" -> LOGIN
                "email" -> EMAIL
                else -> null
            }
    }
}
