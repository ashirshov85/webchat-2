package webchat.backend.contacts.domain.service

import org.springframework.stereotype.Service
import webchat.backend.contacts.domain.model.Contact
import webchat.backend.contacts.domain.model.ContactAlias
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.port.BlockRepository
import webchat.backend.contacts.domain.port.ContactAddResult
import webchat.backend.contacts.domain.port.ContactEntry
import webchat.backend.contacts.domain.port.ContactRepository
import webchat.backend.contacts.domain.port.UserLookupPort
import java.util.UUID

/**
 * 422 (api-contract.md №21/№23, FR-016/FR-020): an operation of the caller
 * on their own user id is refused — a contact entry and a block both
 * require two DISTINCT users. Decided BEFORE the repository is touched;
 * the V11 `CHECK` constraints are only the second line of defense. The
 * contract code is carried for the problem+json rendering of the api
 * layer (T053).
 */
class SelfForbiddenException : RuntimeException("a contact or block needs two distinct users (FR-016/FR-020)") {
    val code: String = "self_forbidden"
}

/**
 * 404 (api-contract.md №21/№23): the requested target user does not
 * exist — refused BEFORE any repository write, so no partial state is
 * ever left behind. The caller is always a known (authenticated) user,
 * so this refusal never competes with [SelfForbiddenException].
 */
class UserNotFoundException : RuntimeException("the requested target user does not exist") {
    val code: String = "user_not_found"
}

/**
 * The contact book of User Story 5 (T052): the idempotent one-sided
 * add/remove and the sorted list — contacts stay fully independent of
 * dialogs and blocks (FR-017/FR-020): no WRITE here ever touches
 * `user_blocks` or the V10 tables.
 *
 * Add (api-contract.md №21, FR-016): `422 self_forbidden` for oneself and
 * `404 user_not_found` for an unknown target are decided HERE, before the
 * repository is touched — the storage itself is idempotent (`ON CONFLICT`
 * on the V11 composite PK), so a repeat resolves to
 * [ContactAddResult.Existing] (the api layer maps it to `200` with the
 * SAME stored row — no duplicate is ever created, edge spec).
 *
 * Remove (№22): the unconditional idempotent delete — a missing entry is
 * NOT an error (`204` either way); the dialog and history of the pair are
 * NEVER touched (FR-017).
 *
 * List (№20, FR-015): the owner's entries with the joined public profiles
 * in the server-side case-insensitive order of [ContactSort]; the raw
 * `sort` parameter validation (`400 invalid_sort`) belongs to the api
 * layer (T053).
 *
 * Block READ model (T097, bug 16): the additive `ContactView.blockedByMe`
 * projection — [blockedTargetsOf] / [blockedByMe] expose the caller's OWN
 * `user_blocks` rows for the №20/№21 answers. Strictly read-only, the
 * same relation №12/№13 already project: with the dialog deleted (hidden)
 * №12 drops the item and №20 stays the ONLY place the block state is
 * visible from; the FR-017/FR-020 write independence is untouched.
 */
@Service
class ContactService(
    private val userLookup: UserLookupPort,
    private val contactRepository: ContactRepository,
    private val blockRepository: BlockRepository,
) {
    fun add(
        ownerId: UUID,
        contactUserId: UUID,
    ): ContactAddResult {
        if (ownerId == contactUserId) throw SelfForbiddenException()
        if (userLookup.findById(contactUserId) == null) throw UserNotFoundException()
        return contactRepository.add(ownerId, contactUserId)
    }

    fun remove(
        ownerId: UUID,
        contactUserId: UUID,
    ) {
        contactRepository.remove(ownerId, contactUserId)
    }

    fun list(
        ownerId: UUID,
        sort: ContactSort,
    ): List<ContactEntry> = contactRepository.listByOwner(ownerId, sort)

    /**
     * №40 set/reset (T014 routes here): stores the owner's [alias] —
     * already normalized by
     * [ContactAlias.normalize][webchat.backend.contacts.domain.model.ContactAlias]
     * (`null` = the reset leg) — and answers the stored row for the
     * `200 ContactView`; `null` when the `(owner, contact_user)` pair has
     * no row, the uniform `404 contact_not_found` gate of the API layer
     * (an existing user never added and an unknown userId read
     * identically). Last-write-wins idempotent, no side events by
     * contract (refetch semantics, api-contract.md §1 №40).
     */
    fun setAlias(
        ownerId: UUID,
        contactUserId: UUID,
        alias: ContactAlias?,
    ): Contact? = contactRepository.storeAlias(ownerId, contactUserId, alias)

    /** T097 №20: every user [ownerId] currently blocks — one set query for the whole list. */
    fun blockedTargetsOf(ownerId: UUID): Set<UUID> = blockRepository.blockedTargetsOf(ownerId)

    /** T097 №21: the point lookup for the single-entry answers. */
    fun blockedByMe(
        ownerId: UUID,
        targetId: UUID,
    ): Boolean = blockRepository.exists(ownerId, targetId)
}
