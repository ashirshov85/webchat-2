package webchat.backend.contacts.domain.port

import webchat.backend.contacts.domain.model.Contact
import webchat.backend.contacts.domain.model.ContactAlias
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
     * 008a: each entry carries the owner's optional
     * [alias][Contact.alias] of the contact (V16, absent = «not set»).
     */
    fun listByOwner(
        ownerId: UUID,
        sort: ContactSort,
    ): List<ContactEntry>

    /**
     * №40 set/reset leg (api-contract.md §1, T014 routes here): stores
     * [alias] — already normalized by [ContactAlias.normalize], i.e. the
     * server-side trim happens BEFORE this call (data-model 008a §1.2);
     * `null` resets to «not set». Last-write-wins idempotent: a repeat
     * of the same value is a plain overwrite and by contract there is
     * NO name-change realtime event (refetch semantics, api-contract.md
     * §1 №40). ONE `UPDATE … RETURNING` statement over the PK pair, so
     * the answer cannot race a concurrent write; `null` when the
     * `(owner, contact_user)` row does not exist — the uniform
     * `404 contact_not_found` gate of №40 (T014: an existing user never
     * added and an unknown userId read identically). The alias lives on
     * the contact row itself: it survives chat deletion (№14) and is
     * removed together with the contact (№22 — the row goes as a whole).
     * Validation (`400 invalid_alias`) and the flood bucket
     * (`rl:user:alias:{userId}`, 30/min) belong to the API layer (T014).
     */
    fun storeAlias(
        ownerId: UUID,
        contactUserId: UUID,
        alias: ContactAlias?,
    ): Contact?

    /**
     * FR-003 projection helper for the owner's peer surfaces (T015
     * №11/№12/№13 peer fragments and T016 №28 group members): the
     * caller's aliases toward [userIds] — ONE `SELECT` over
     * `user_contacts` by the PK prefix, keyed by contact user id and
     * holding ONLY non-null values (an absent key = «no alias», the
     * neutral fallback of the display chain). Strictly personal: the
     * answer is the caller's own row material and must never be
     * projected into anyone else's surfaces; an empty [userIds] costs
     * no database round-trip.
     */
    fun aliasesOf(
        ownerId: UUID,
        userIds: Collection<UUID>,
    ): Map<UUID, String>
}
