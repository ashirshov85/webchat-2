package webchat.backend.contacts.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import webchat.backend.contacts.api.dto.AddContactRequest
import webchat.backend.contacts.api.dto.ContactView
import webchat.backend.contacts.api.dto.ContactsResponse
import webchat.backend.contacts.api.dto.toPublicUserView
import webchat.backend.contacts.domain.model.Contact
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.port.ContactAddResult
import webchat.backend.contacts.domain.port.ContactEntry
import webchat.backend.contacts.domain.port.UserLookupPort
import webchat.backend.contacts.domain.service.ContactService
import java.util.UUID

/**
 * The contact book endpoints of User Story 5 (api-contract.md №20–№22,
 * FR-015..FR-017) — thin HTTP adapters over [ContactService]: every
 * business rule (the `422 self_forbidden` pair guard and `404
 * user_not_found` of №21, the idempotent remove of №22) stays in the
 * service; this layer only resolves the token owner, validates the raw
 * `sort` parameter (`400 invalid_sort`), parses the request into typed
 * 400 carriers and projects rows as the contract `ContactView`.
 *
 * Contacts are fully independent of dialogs and blocks (FR-017/FR-020):
 * nothing here touches the V10 tables or `user_blocks`. The security
 * chain has ALREADY authenticated the request; the owner id is the token
 * `sub` claim. All failures leave as typed exceptions rendered
 * problem+json by [ContactsExceptionHandler].
 */
@RestController
@RequestMapping("/api/v1/contacts")
class ContactController(
    private val contactService: ContactService,
    private val userLookup: UserLookupPort,
) {
    /**
     * Contract №20: the caller's list with the server-side
     * case-insensitive alphabetical sorting of [ContactSort] (FR-015) —
     * default `login`; only `login`/`email` are valid, anything else is
     * the contract 400 `errors: {sort: [invalid_sort]}` before the
     * service is touched. An empty list is a valid answer.
     */
    @GetMapping
    fun list(
        @RequestParam sort: String?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ContactsResponse {
        val resolvedSort = ContactSort.fromRaw(sort) ?: throw InvalidSortException()
        val ownerId = callerId(accessToken)
        return ContactsResponse(
            contacts = contactService.list(ownerId, resolvedSort).map(::view),
        )
    }

    /**
     * Contract №21: the idempotent one-sided add — `201 ContactView` when
     * the entry is created, `200 ContactView` for the already-added pair
     * (the SAME stored row, no duplicate — edge spec).
     */
    @PostMapping
    fun add(
        @RequestBody request: AddContactRequest,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<ContactView> {
        val ownerId = callerId(accessToken)
        val result = contactService.add(ownerId, parseUserId(request.userId))
        return when (result) {
            is ContactAddResult.Created -> ResponseEntity.status(HttpStatus.CREATED).body(view(result.contact))
            is ContactAddResult.Existing -> ResponseEntity.ok(view(result.contact))
        }
    }

    /**
     * Contract №22: the unconditional idempotent remove — `204` either
     * way; the dialog and history of the pair are NEVER touched
     * (FR-017).
     */
    @DeleteMapping("/{userId}")
    fun remove(
        @PathVariable userId: UUID,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ResponseEntity<Unit> {
        contactService.remove(callerId(accessToken), userId)
        return ResponseEntity.noContent().build()
    }

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * The №21 id gate: an absent or malformed `userId` becomes the
     * contract 400 `errors: {userId: [invalid_uuid]}` BEFORE the service
     * is touched.
     */
    private fun parseUserId(raw: String?): UUID {
        val value = raw ?: throw InvalidContactUserIdException()
        return try {
            UUID.fromString(value)
        } catch (failure: IllegalArgumentException) {
            throw InvalidContactUserIdException(failure)
        }
    }

    private fun view(entry: ContactEntry): ContactView =
        ContactView(
            user = entry.user.toPublicUserView(),
            createdAt = entry.contact.createdAt,
        )

    /**
     * The №21 answer projection: the public profile of the added user —
     * resolved via [UserLookupPort] by the stored `contactUserId` of the
     * result. The row always exists ([ContactService] verified the target
     * BEFORE the insert and the V11 FK keeps it): a miss is a broken
     * invariant, not a client answer.
     */
    private fun view(contact: Contact): ContactView {
        val user =
            userLookup.findById(contact.contactUserId)
                ?: error("contact user ${contact.contactUserId} of owner ${contact.ownerId} does not resolve")
        return ContactView(
            user = user.toPublicUserView(),
            createdAt = contact.createdAt,
        )
    }
}

/**
 * 400 (api-contract.md №20): the `sort` parameter is neither `login` nor
 * `email` — rendered by [ContactsExceptionHandler] as
 * `errors: {sort: [invalid_sort]}`.
 */
class InvalidSortException : RuntimeException("sort must be one of: login, email")

/**
 * 400 (api-contract.md №21): the request `userId` is absent or not a
 * UUID — rendered by [ContactsExceptionHandler] as
 * `errors: {userId: [invalid_uuid]}`.
 */
class InvalidContactUserIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("userId must be a UUID", cause)
