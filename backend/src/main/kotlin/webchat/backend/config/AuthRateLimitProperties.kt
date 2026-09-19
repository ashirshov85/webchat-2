package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * US5 bucket limits per public auth route (FR-009, research.md §11), bound
 * from `auth.ratelimit.*` in `"<count>/<window>"` form, e.g. `5/1h`. The
 * [RouteLimits.email]/[RouteLimits.account] fields name the identifier-keyed
 * bucket of a route; both map onto the single Redis key family `rl:email`
 * (data-model.md §7 — for login the key is sha256(identifier)). Specs are
 * parsed fail-fast at startup by the RateLimitFilter (T047).
 *
 * The login route additionally carries the brute-force counter parameters
 * [RouteLimits.ThrottleLimits] (T048, research.md §8, SC-004) consumed by
 * `LoginThrottle`.
 *
 * [SsoLimits] (003 T047, research §9 of specs/003-sso-identity-providers,
 * FR-009) extends the table with the five SSO routes — IP-only buckets,
 * including the two GET routes (`providers`, `callback`) the filter could
 * not see before.
 */
@ConfigurationProperties(prefix = "auth.ratelimit")
data class AuthRateLimitProperties(
    val register: RouteLimits,
    val resend: RouteLimits,
    val login: RouteLimits,
    val passwordReset: RouteLimits,
    val confirm: RouteLimits,
    val password: RouteLimits,
    val resetConfirm: RouteLimits,
    val refresh: RouteLimits,
    val sso: SsoLimits,
) {
    /**
     * The five SSO routes of research §9 (normative source; mirrored by
     * contracts/sso-api.md §1–5 and quickstart S6.2): authorize is stricter
     * (10/1m — it CREATES flow contexts in Redis), the rest are 30/1m.
     * All are IP-only: state is a single-use flow secret, not a source
     * identifier, so no identifier buckets ride this group.
     */
    data class SsoLimits(
        val providers: RouteLimits,
        val authorize: RouteLimits,
        val callback: RouteLimits,
        val token: RouteLimits,
        val linkAuthorize: RouteLimits,
    )

    data class RouteLimits(
        val ip: String,
        val email: String? = null,
        val account: String? = null,
        val throttle: ThrottleLimits? = null,
    ) {
        /**
         * Brute-force counter of the login route (research.md §8, SC-004):
         * delay = min([baseDelay] × 2^(n−3), [maxDelay]) from the 4th
         * accumulated failure on; [lockoutThreshold] accumulated failures
         * lock the identifier out for [window] (EXPIRE NX — the window is
         * anchored at the first failure and never slides).
         */
        data class ThrottleLimits(
            val baseDelay: Duration,
            val maxDelay: Duration,
            val lockoutThreshold: Int,
            val window: Duration,
        )
    }
}
