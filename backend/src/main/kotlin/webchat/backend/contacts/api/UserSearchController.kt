package webchat.backend.contacts.api

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import webchat.backend.config.ChatsProperties
import webchat.backend.config.RateLimitConfig
import webchat.backend.config.UserRateLimiter
import webchat.backend.contacts.api.dto.UsersSearchResponse
import webchat.backend.contacts.api.dto.toPublicUserView
import webchat.backend.contacts.domain.port.UserLookupPort
import java.util.UUID

/**
 * Contract №19 (api-contract.md §3, FR-016): `GET /api/v1/users/search`
 * — the EXACT match of a full email OR a full login, case-insensitively,
 * routed by the @-rule inside [UserLookupPort.searchExact]. A thin HTTP
 * adapter: this layer validates the `query` parameter
 * (`400 query_missing`), enforces the per-user flood gate (T053a) and
 * projects 0..1 results as `PublicUser` — an empty list is a correct
 * answer, never an error.
 *
 * The enumeration guard (FR-016): `chats.rate-limit.searches-per-minute`
 * requests per minute per user, the `rl:user:search:{userId}` bucket of
 * [UserRateLimiter] (Bucket4j + Redis via the shared
 * [RateLimitConfig.rateLimitProxyManager] infra, research.md 004 §7) —
 * checked AFTER the `query` gate and BEFORE the lookup, so a refused
 * search performs NO comparison (the contract: «поиск не выполняется»).
 * The refusal is the `429 flood_limit` problem+json + `Retry-After` of
 * №19, rendered by [ContactsExceptionHandler]; the warn log and the
 * `webchat_search_rejected_total{reason=flood}` counter (SC-008) carry
 * the user id and the wait ONLY — never the query itself (constitution
 * V: a search string is PII).
 *
 * The security chain has ALREADY authenticated the request (the same
 * Bearer gate as №11); the caller id is the token `sub` claim. Refusals
 * leave as typed exceptions rendered problem+json.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserSearchController(
    private val userLookup: UserLookupPort,
    private val rateLimiter: UserRateLimiter,
    private val chatsProperties: ChatsProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(UserSearchController::class.java)

    /**
     * №19: `query` is required — absent, empty and longer than 254
     * characters are refused `400 query_missing`; the 254-character bound
     * itself stays valid (a clean miss). No match → `200 {users: []}`.
     */
    @GetMapping("/search")
    fun search(
        @RequestParam query: String?,
        @AuthenticationPrincipal accessToken: Jwt,
    ): UsersSearchResponse {
        val normalized = requireQuery(query)
        enforceSearchFloodLimit(UUID.fromString(accessToken.subject))
        val match = userLookup.searchExact(normalized)
        return UsersSearchResponse(users = listOfNotNull(match).map { it.toPublicUserView() })
    }

    private fun requireQuery(raw: String?): String {
        if (raw.isNullOrEmpty() || raw.length > QUERY_MAX_LENGTH) throw QueryMissingException()
        return raw
    }

    /**
     * FR-016 (T053a): one token per search of the per-user bucket
     * `rl:user:search:{userId}` — every replica and every device of the
     * user draws from ONE bucket (constitution II). A miss query spends
     * the token the same as a hit: the guard counts SEARCHES, not
     * results. Redis unavailability fails open (the caller then searches
     * — losing the ephemeral store may only reset the allowance, never
     * 5xx the route).
     */
    private fun enforceSearchFloodLimit(callerId: UUID) {
        val verdict =
            rateLimiter.tryAcquire(
                keyFamily = SEARCH_KEY_FAMILY,
                userId = callerId,
                permitsPerMinute = chatsProperties.rateLimit.searchesPerMinute.toLong(),
            )
        if (verdict is UserRateLimiter.Verdict.Rejected) {
            log.warn(
                "search refused by the flood limit (FR-016): user <{}> exhausted {} searches/minute, " +
                    "retry after {}s",
                callerId,
                chatsProperties.rateLimit.searchesPerMinute,
                verdict.retryAfterSeconds,
            )
            rejectedTotal().increment()
            throw SearchFloodException(verdict.retryAfterSeconds)
        }
    }

    /** SC-008 (T053a): search-route refusals by reason — `flood` here. */
    private fun rejectedTotal(): Counter =
        Counter
            .builder(SEARCH_REJECTED_TOTAL)
            .description("Search-route refusals by reason: flood limit (FR-016, T053a)")
            .tag(TAG_REASON, REASON_FLOOD)
            .register(meterRegistry)

    private companion object {
        /** The contract-fixed length bound of №19 (openapi.yaml: `maxLength: 254`). */
        const val QUERY_MAX_LENGTH = 254

        /** research.md 004 §7: the Redis key family of the FR-016 search bucket. */
        const val SEARCH_KEY_FAMILY = "rl:user:search:"

        /** SC-008: search refusals by reason. */
        const val SEARCH_REJECTED_TOTAL = "webchat_search_rejected_total"
        const val TAG_REASON = "reason"
        const val REASON_FLOOD = "flood"
    }
}

/**
 * 400 (api-contract.md №19): `query` is absent, empty or longer than 254
 * characters — rendered by [ContactsExceptionHandler] as
 * `errors: {query: [query_missing]}`.
 */
class QueryMissingException : RuntimeException("query must be present, non-empty and at most 254 characters")

/**
 * 429 (api-contract.md №19, FR-016): the per-user search flood limit is
 * exhausted — [retryAfterSeconds] is the integral ceiling of the wait
 * for the next available token, rendered by the api layer as the
 * `Retry-After` header. The search is NOT performed.
 */
class SearchFloodException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user search flood limit is exhausted") {
    val code: String = "flood_limit"
}
