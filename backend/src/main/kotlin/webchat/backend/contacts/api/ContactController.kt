package webchat.backend.contacts.api

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import webchat.backend.config.ContactsProperties
import webchat.backend.config.UserRateLimiter
import webchat.backend.contacts.api.dto.AddContactRequest
import webchat.backend.contacts.api.dto.AliasUpdateRequest
import webchat.backend.contacts.api.dto.ContactView
import webchat.backend.contacts.api.dto.ContactsResponse
import webchat.backend.contacts.api.dto.toPublicUserView
import webchat.backend.contacts.domain.model.Contact
import webchat.backend.contacts.domain.model.ContactAlias
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.model.InvalidAliasException
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
    private val rateLimiter: UserRateLimiter,
    private val contactsProperties: ContactsProperties,
) {
    private val log = LoggerFactory.getLogger(ContactController::class.java)

    /**
     * Contract №20: the caller's list with the server-side
     * case-insensitive alphabetical sorting of [ContactSort] (FR-015) —
     * default `login`; only `login`/`email` are valid, anything else is
     * the contract 400 `errors: {sort: [invalid_sort]}` before the
     * service is touched. An empty list is a valid answer. Every
     * `ContactView` carries `blockedByMe` — the caller's own block mark
     * (T097, additive 0.8.0): one set read of `user_blocks` serves the
     * whole page, and the projection stays visible after the dialog is
     * deleted (№12 drops the hidden chat — bug 16).
     */
    @GetMapping
    fun list(
        @RequestParam sort: String?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ContactsResponse {
        val resolvedSort = ContactSort.fromRaw(sort) ?: throw InvalidSortException()
        val ownerId = callerId(accessToken)
        val blockedTargets = contactService.blockedTargetsOf(ownerId)
        return ContactsResponse(
            contacts =
                contactService.list(ownerId, resolvedSort).map { entry ->
                    view(entry, blockedTargets.contains(entry.user.id))
                },
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
            is ContactAddResult.Created -> ResponseEntity.status(HttpStatus.CREATED).body(view(result.contact, ownerId))
            is ContactAddResult.Existing -> ResponseEntity.ok(view(result.contact, ownerId))
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

    /**
     * Contract №40 `PUT /contacts/{userId}/alias` (T014, FR-003) → `200
     * ContactView` with the STORED row: the FR-003 shape gate FIRST
     * (the server trim of [ContactAlias.normalize]; `null`/an omitted
     * field is the RESET leg — «not set», never a validation case;
     * `""`/whitespace-only/oversized or a non-string value is the one
     * refusal `400 invalid_alias`), the per-user flood bucket
     * `rl:user:alias:{userId}` (30/min,
     * [ContactsProperties.RateLimit.aliasWritesPerMinute]) NEXT and the
     * idempotent last-write-wins store write LAST — a repeat of the
     * same value is a plain 200 with NO side events (there is no
     * name-change realtime event by contract — refetch semantics,
     * api-contract.md §1 №40). A missing `(owner, contact_user)` row is
     * the uniform `404 contact_not_found`: an existing user never added
     * and an unknown userId read identically, and the refusal leaves no
     * rows behind.
     *
     * The answer carries the caller's stored `alias` and the aliased
     * user as `PublicUser` with his optional `displayName` — the alias
     * is strictly personal material and rides ONLY the caller's own
     * surfaces (FR-003).
     */
    @PutMapping("/{userId}/alias")
    fun setAlias(
        @PathVariable userId: UUID,
        @RequestBody(required = false) request: AliasUpdateRequest?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): ContactView {
        val alias = normalizeOrNull(request?.alias)
        val ownerId = callerId(accessToken)
        enforceAliasFloodLimit(ownerId)
        val contact =
            contactService.setAlias(ownerId, userId, alias)
                ?: throw ContactNotFoundException()
        return view(contact, ownerId)
    }

    private fun callerId(accessToken: Jwt): UUID = UUID.fromString(accessToken.subject)

    /**
     * The FR-003 single gate of №40: a NON-NULL value must be a JSON
     * string that survives [ContactAlias.normalize] (server trim, 1–64
     * chars) — everything else is the one contract refusal
     * `400 invalid_alias` ([InvalidAliasException] is rendered by
     * [ContactsExceptionHandler]); `null` passes as the reset leg.
     */
    private fun normalizeOrNull(raw: Any?): ContactAlias? =
        when (raw) {
            null -> null
            !is String -> throw InvalidAliasException()
            else -> ContactAlias.normalize(raw)
        }

    /**
     * api-contract.md §1 №40: one token per PUT of the per-user bucket
     * `rl:user:alias:{userId}` (capacity
     * [ContactsProperties.RateLimit.aliasWritesPerMinute] over the 60 s
     * window — the №38/№39 parity): a refused PUT performs NO write and
     * the client repeats after the advertised `Retry-After` wait (the
     * stored alias is untouched — a lost toggle is recoverable via №20).
     * The warn log carries ids and waits only — never the submitted
     * alias (constitution V).
     */
    private fun enforceAliasFloodLimit(ownerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = ALIAS_KEY_FAMILY,
                userId = ownerId,
                permitsPerMinute = contactsProperties.rateLimit.aliasWritesPerMinute.toLong(),
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "alias PUT refused by the flood limit (№40, api-contract.md §1): user <{}> exhausted " +
                    "{} writes/minute, retry after {}s",
                ownerId,
                contactsProperties.rateLimit.aliasWritesPerMinute,
                verdict.retryAfterSeconds,
            )
            throw ContactAliasFloodException(verdict.retryAfterSeconds)
        }
    }

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

    private fun view(
        entry: ContactEntry,
        blockedByMe: Boolean,
    ): ContactView =
        ContactView(
            user = entry.user.toPublicUserView(),
            createdAt = entry.contact.createdAt,
            blockedByMe = blockedByMe,
            alias = entry.contact.alias?.value,
        )

    /**
     * The №21 answer projection: the public profile of the added user —
     * resolved via [UserLookupPort] by the stored `contactUserId` of the
     * result. The row always exists ([ContactService] verified the target
     * BEFORE the insert and the V11 FK keeps it): a miss is a broken
     * invariant, not a client answer. `blockedByMe` is the same T097
     * projection as №20 — the block and the contact book stay
     * independent, so the answer reflects the CURRENT mark verbatim.
     */
    private fun view(
        contact: Contact,
        ownerId: UUID,
    ): ContactView {
        val user =
            userLookup.findById(contact.contactUserId)
                ?: error("contact user ${contact.contactUserId} of owner ${contact.ownerId} does not resolve")
        return ContactView(
            user = user.toPublicUserView(),
            createdAt = contact.createdAt,
            blockedByMe = contactService.blockedByMe(ownerId, contact.contactUserId),
            alias = contact.alias?.value,
        )
    }

    private companion object {
        /** research.md 008a C2: the Redis key family of the №40 PUT bucket. */
        const val ALIAS_KEY_FAMILY = "rl:user:alias:"
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

/**
 * 404 (api-contract.md №40, T014): the path target is not a contact of
 * the caller — no `(owner, contact_user)` row exists for the pair (an
 * existing user never added and an unknown userId read identically) —
 * rendered by [ContactsExceptionHandler] as
 * `errors: {userId: [contact_not_found]}`.
 */
class ContactNotFoundException : RuntimeException("the requested target is not a contact of the caller")

/**
 * 429 (api-contract.md №40, T014): the per-user alias flood bucket is
 * exhausted — [retryAfterSeconds] is the integral ceiling of the wait
 * for the next available token, rendered as the `Retry-After` header
 * by [ContactsExceptionHandler].
 */
class ContactAliasFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user contact-alias flood limit is exhausted")
