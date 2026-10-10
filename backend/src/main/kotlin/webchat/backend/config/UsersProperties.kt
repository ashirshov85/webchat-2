package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 008a-social-signals user-surface flood limits (T008, research.md C2):
 * [RateLimit.profileWritesPerMinute] is the №39 `PUT /users/me/profile`
 * per-user bucket `rl:user:profile:{userId}` — the conservative 30/min
 * №38 parity for a rare manual write over the 60 s [UserRateLimiter]
 * window (contracts/api-contract.md §1); a refusal answers 429
 * `flood_limit` + Retry-After and performs no write. Bound from
 * `users.rate-limit.*` (application.yml); the test profile keeps the
 * production capacity — the ProfileDisplayNameIT flood leg drains the
 * bucket on purpose.
 */
@ConfigurationProperties(prefix = "users")
data class UsersProperties(
    val rateLimit: RateLimit,
) {
    /** contracts/api-contract.md §1 (№39): profile PUT writes per minute per user. */
    data class RateLimit(
        val profileWritesPerMinute: Int,
    )

    init {
        require(rateLimit.profileWritesPerMinute > 0) { "users.rate-limit.profile-writes-per-minute must be positive" }
    }
}
