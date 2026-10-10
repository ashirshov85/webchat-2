package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 008a-social-signals contact-surface flood limits (T008, research.md C2):
 * [RateLimit.aliasWritesPerMinute] is the №40 `PUT /contacts/{userId}/alias`
 * per-user bucket `rl:user:alias:{userId}` — the conservative 30/min №38
 * parity for a rare manual write over the 60 s [UserRateLimiter] window
 * (contracts/api-contract.md §1); a refusal answers 429 `flood_limit` +
 * Retry-After and performs no write. Bound from `contacts.rate-limit.*`
 * (application.yml); the test profile keeps the production capacity —
 * the ContactAliasIT flood leg drains the bucket on purpose.
 */
@ConfigurationProperties(prefix = "contacts")
data class ContactsProperties(
    val rateLimit: RateLimit,
) {
    /** contracts/api-contract.md §1 (№40): contact-alias PUT writes per minute per user. */
    data class RateLimit(
        val aliasWritesPerMinute: Int,
    )

    init {
        require(rateLimit.aliasWritesPerMinute > 0) { "contacts.rate-limit.alias-writes-per-minute must be positive" }
    }
}
