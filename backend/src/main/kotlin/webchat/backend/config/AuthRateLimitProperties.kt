package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * US5 bucket limits per public auth route (FR-009, research.md §11), bound
 * from `auth.ratelimit.*` in `"<count>/<window>"` form, e.g. `5/1h`. The
 * [RouteLimits.email]/[RouteLimits.account] fields name the identifier-keyed
 * bucket of a route; both map onto the single Redis key family `rl:email`
 * (data-model.md §7 — for login the key is sha256(identifier)). Specs are
 * parsed fail-fast at startup by the RateLimitFilter (T047).
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
) {
    data class RouteLimits(
        val ip: String,
        val email: String? = null,
        val account: String? = null,
    )
}
