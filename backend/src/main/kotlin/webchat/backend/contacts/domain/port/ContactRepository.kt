package webchat.backend.contacts.domain.port

import webchat.backend.contacts.domain.model.Contact
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.model.UserProfile
import java.util.UUID

/**
 * The outcome of the idempotent contact add (api-contract.md №21): the
 * caller (T052) maps [Created] to `201 ContactView` and [Existing] to
 * `200 ContactView` — a repeat never creates a duplicate (edge spec).
 */
sealed interface ContactAddResult {
    val contact: Contact

    /** The pair had no entry: the row was inserted. */
    data class Created(
        override val contact: Contact,
    ) : ContactAddResult

    /** The composite PK `(owner, contact_user)` already existed — the same entry. */
    data class Existing(
        override val contact: Contact,
    ) : ContactAddResult
}

/**
 * A contact list entry joined with the public profile of the contact user
 * (№20): [ContactEntry.contact].createdAt is the `createdAt` of
 * `ContactView`, [ContactEntry.user] projects the joined `users` row.
 */
data class ContactEntry(
    val contact: Contact,
    val user: UserProfile,
)

/**
 * Persistence port for the [Contact] aggregate (data-model 004 §4; DIP:
 * the JDBC adapter lives outside the domain in
 * `webchat.backend.contacts.repository.JdbcContactRepository`, T051).
 *
 * The aggregate is immutable after creation; the only writes are the
 * idempotent add (`INSERT … ON CONFLICT (owner_id, contact_user_id) DO
 * NOTHING` + `SELECT`) and the unconditional remove. Contacts are fully
 * independent of `chats`/`chat_participants` and `user_blocks`
 * (FR-017/FR-020): no method of this port touches those tables.
 */
interface ContactRepository {
    /**
     * `POST /contacts` (№21): resolves to [ContactAddResult.Created] on
     * the first add and to [ContactAddResult.Existing] on a repeat — no
     * duplicate rows (V11 composite PK). The service (T052) rejects
     * `ownerId == contactUserId` (`422 self_forbidden`) and an unknown
     * contact user (`404 user_not_found`) BEFORE calling this method.
     */
    fun add(
        ownerId: UUID,
        contactUserId: UUID,
    ): ContactAddResult

    /**
     * `DELETE /contacts/{userId}` (№22): unconditional single-row DELETE
     * by the PK pair — idempotent, a missing entry is NOT an error (the
     * endpoint answers `204` either way). The chat and history of the
     * pair are NEVER touched (FR-017).
     */
    fun remove(
        ownerId: UUID,
        contactUserId: UUID,
    )

    /**
     * `GET /contacts?sort=` (№20): the owner's list with the joined public
     * profiles of the contact users, sorted server-side case-insensitively
     * by [ContactSort] (`ORDER BY lower(username)` / `lower(email)`,
     * FR-015 — the expressions of the 002 unique indexes). An empty list
     * is a valid result. Blocks are not filtered or exposed here.
     */
    fun listByOwner(
        ownerId: UUID,
        sort: ContactSort,
    ): List<ContactEntry>
}
