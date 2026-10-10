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
 * 008a (data-model §1.2, FR-003): the row now carries the owner's
 * optional personal [alias] of the contact (V16 `user_contacts.alias`,
 * NULL = «not set» → the display chain falls back to displayName and
 * `username`). Strictly private material: it is served ONLY on the
 * owner's own surfaces and never reaches the contact himself or third
 * parties; it survives chat deletion (№14 — it lives here, not on
 * chats) and dies with the row on №22 (a re-add starts from a clean
 * slate).
 *
 * Independence (FR-017/FR-020): removing a contact never touches the chat
 * or history of the pair; blocks never touch contacts and vice versa.
 * Clicking a contact opens the dialog via `POST /chats/ensure` (FR-018).
 */
data class Contact(
    val ownerId: UUID,
    val contactUserId: UUID,
    val createdAt: Instant,
    val alias: ContactAlias? = null,
) {
    init {
        require(ownerId != contactUserId) { "a contact entry requires two distinct users (FR-016)" }
    }

    companion object {
        /**
         * `POST /contacts` (№21): a new entry for the owner's list; the
         * service (T052) rejects `ownerId == contactUserId` and an unknown
         * contact user BEFORE the repository call. A fresh entry never
         * carries an [alias] — only №40 (T013/T014) sets one, and a re-add
         * after №22 starts from that same clean slate (edge spec).
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
