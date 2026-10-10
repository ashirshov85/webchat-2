package webchat.backend.contacts.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.contacts.domain.model.InvalidAliasException
import webchat.backend.contacts.domain.service.SelfForbiddenException
import webchat.backend.contacts.domain.service.UserNotFoundException

/**
 * RFC 9457 rendering for the contacts/users API errors (api-contract.md
 * §3, the same conventions as the chats
 * [webchat.backend.chats.api.ChatsExceptionHandler]): every failure
 * leaves the controller as a typed exception and reaches the client as
 * `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only, never
 * contact details or search queries (constitution V).
 *
 * The contacts [SelfForbiddenException]/[UserNotFoundException] of T052
 * are distinct from their chats namesakes; each advice claims exactly
 * its own carrier types, so the global advices never collide.
 */
@RestControllerAdvice
class ContactsExceptionHandler {
    /** 422 (api-contract.md №21/№23): the caller targets themselves (FR-016/FR-020). */
    @ExceptionHandler(SelfForbiddenException::class)
    fun onSelfForbidden(): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, SELF_FORBIDDEN_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_ID_FIELD to listOf(SELF_FORBIDDEN_CODE))) }

    /** 404 (api-contract.md №21/№23): the requested target user does not exist. */
    @ExceptionHandler(UserNotFoundException::class)
    fun onUserNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, USER_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_FIELD to listOf(USER_NOT_FOUND_CODE))) }

    /** 400 (api-contract.md №19): `query` is absent, empty or longer than 254 characters. */
    @ExceptionHandler(QueryMissingException::class)
    fun onQueryMissing(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, QUERY_MISSING_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(QUERY_FIELD to listOf(QUERY_MISSING_CODE))) }

    /** 400 (api-contract.md №20): `sort` is neither `login` nor `email`. */
    @ExceptionHandler(InvalidSortException::class)
    fun onInvalidSort(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_SORT_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(SORT_FIELD to listOf(INVALID_SORT_CODE))) }

    /**
     * 429 (api-contract.md №19, FR-016, T053a): the per-user search
     * flood limit — the allowance is exhausted, `Retry-After` carries the
     * integral seconds to the next available token and the search is NOT
     * performed. The query itself is never echoed (constitution V).
     */
    @ExceptionHandler(SearchFloodException::class)
    fun onSearchFlood(failure: SearchFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, FLOOD_LIMIT_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(QUERY_FIELD to listOf(FLOOD_LIMIT_CODE))) },
            )

    /** 400 (api-contract.md №21): the request `userId` is absent or not a UUID. */
    @ExceptionHandler(InvalidContactUserIdException::class)
    fun onInvalidContactUserId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_CONTACT_USER_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    /**
     * 400 (api-contract.md №40, T014): the submitted `alias` is
     * empty/whitespace after trim, longer than 64 characters or not a
     * JSON string — `errors: {alias: [invalid_alias]}`; the submitted
     * value is never echoed (constitution V) and the stored alias
     * SURVIVES (US1 AC5 — the FR-003 gate ran before any SQL leg).
     */
    @ExceptionHandler(InvalidAliasException::class)
    fun onInvalidAlias(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_ALIAS_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(ALIAS_FIELD to listOf(INVALID_ALIAS_CODE))) }

    /**
     * 404 (api-contract.md №40, T014): the path target is not a contact
     * of the caller — an existing user never added and an unknown
     * userId read identically — `errors: {userId: [contact_not_found]}`;
     * the refusal leaves no rows behind.
     */
    @ExceptionHandler(ContactNotFoundException::class)
    fun onContactNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, CONTACT_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(USER_ID_FIELD to listOf(CONTACT_NOT_FOUND_CODE))) }

    /**
     * 429 (api-contract.md №40, T014): the per-user alias flood bucket
     * is exhausted — `Retry-After` carries the integral seconds to the
     * next token; the stored alias is NOT touched and the client
     * repeats the save after the wait. The submitted value is never
     * echoed (constitution V).
     */
    @ExceptionHandler(ContactAliasFloodException::class)
    fun onAliasFlood(failure: ContactAliasFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, ALIAS_FLOOD_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(ALIAS_FIELD to listOf(FLOOD_LIMIT_CODE))) },
            )

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private companion object {
        const val ERRORS_PROPERTY = "errors"
        const val QUERY_FIELD = "query"
        const val SORT_FIELD = "sort"
        const val USER_FIELD = "user"
        const val USER_ID_FIELD = "userId"
        const val ALIAS_FIELD = "alias"
        const val QUERY_MISSING_CODE = "query_missing"
        const val INVALID_SORT_CODE = "invalid_sort"
        const val USER_NOT_FOUND_CODE = "user_not_found"
        const val SELF_FORBIDDEN_CODE = "self_forbidden"
        const val INVALID_UUID_CODE = "invalid_uuid"
        const val INVALID_ALIAS_CODE = "invalid_alias"
        const val CONTACT_NOT_FOUND_CODE = "contact_not_found"
        const val FLOOD_LIMIT_CODE = "flood_limit"
        const val QUERY_MISSING_DETAIL = "query must be present, non-empty and at most 254 characters"
        const val INVALID_SORT_DETAIL = "sort must be one of: login, email"
        const val USER_NOT_FOUND_DETAIL = "The requested target user does not exist"
        const val SELF_FORBIDDEN_DETAIL = "A contact or block needs two distinct users"
        const val INVALID_CONTACT_USER_ID_DETAIL = "userId must be a UUID"
        const val INVALID_ALIAS_DETAIL = "alias must be absent/null (reset) or 1..64 characters after trim"
        const val CONTACT_NOT_FOUND_DETAIL = "The requested target user is not a contact of the caller"
        const val FLOOD_LIMIT_DETAIL =
            "The per-user search flood limit is exhausted (30 searches/minute); the search was not performed"
        const val ALIAS_FLOOD_DETAIL =
            "The per-user contact-alias flood limit is exhausted; repeat the save after the advertised wait"
    }
}
