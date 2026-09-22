package webchat.backend.contacts.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import webchat.backend.contacts.api.dto.UsersSearchResponse
import webchat.backend.contacts.api.dto.toPublicUserView
import webchat.backend.contacts.domain.port.UserLookupPort

/**
 * Contract №19 (api-contract.md §3, FR-016): `GET /api/v1/users/search`
 * — the EXACT match of a full email OR a full login, case-insensitively,
 * routed by the @-rule inside [UserLookupPort.searchExact]. A thin HTTP
 * adapter: this layer only validates the `query` parameter
 * (`400 query_missing`) and projects 0..1 results as `PublicUser` — an
 * empty list is a correct answer, never an error. The per-user flood
 * gate (30 req/min, `429 flood_limit` + `Retry-After`) joins this path in
 * T053a without changing the map.
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as №11); refusals leave as typed exceptions rendered
 * problem+json by [ContactsExceptionHandler].
 */
@RestController
@RequestMapping("/api/v1/users")
class UserSearchController(
    private val userLookup: UserLookupPort,
) {
    /**
     * №19: `query` is required — absent, empty and longer than 254
     * characters are refused `400 query_missing`; the 254-character bound
     * itself stays valid (a clean miss). No match → `200 {users: []}`.
     */
    @GetMapping("/search")
    fun search(
        @RequestParam query: String?,
    ): UsersSearchResponse {
        val normalized = requireQuery(query)
        val match = userLookup.searchExact(normalized)
        return UsersSearchResponse(users = listOfNotNull(match).map { it.toPublicUserView() })
    }

    private fun requireQuery(raw: String?): String {
        if (raw.isNullOrEmpty() || raw.length > QUERY_MAX_LENGTH) throw QueryMissingException()
        return raw
    }

    private companion object {
        /** The contract-fixed length bound of №19 (openapi.yaml: `maxLength: 254`). */
        const val QUERY_MAX_LENGTH = 254
    }
}

/**
 * 400 (api-contract.md №19): `query` is absent, empty or longer than 254
 * characters — rendered by [ContactsExceptionHandler] as
 * `errors: {query: [query_missing]}`.
 */
class QueryMissingException : RuntimeException("query must be present, non-empty and at most 254 characters")
