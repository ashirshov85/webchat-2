package webchat.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.RedisClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.ratelimit.ClientIpResolver
import webchat.backend.auth.ratelimit.LoginThrottle
import webchat.backend.auth.ratelimit.RateLimitFilter
import webchat.backend.auth.security.AuthEventRecorder

/**
 * The US5 guard of the public auth routes (FR-009): Bucket4j limits per
 * source and per request identifier (T047, research.md §2/§11) plus the
 * T048 `login:fail` brute-force lockout of research.md §8 — the counter
 * verdict runs FIRST on `/auth/login`, before the buckets, and answers
 * with the uniform 429 + `Retry-After` = the remaining counter TTL.
 * Since 009 T047 the same guard also carries the per-IP buckets of the
 * two PUBLIC guest routes №54/№55 (`conferences.rate-limit.*`,
 * api-contract.md 009 §3) — the specs arrive via [ConferencesProperties]
 * so the numbers stay owned by the conferences domain while the
 * enforcement rides the ONE shared filter.
 *
 * The guard's collaborators are the wiring itself (tasks.md T047–T048),
 * so they arrive as constructor injection of the configuration and the
 * [rateLimitFilter] factory stays parameter-less (kotlin:S107).
 */
@Suppress("LongParameterList") // the guard's collaborators are the wiring itself (tasks.md T047–T048)
@Configuration
class RateLimitFilterConfig(
    private val rateLimitRedisClient: RedisClient,
    private val properties: AuthRateLimitProperties,
    private val conferencesProperties: ConferencesProperties,
    private val clientIpResolver: ClientIpResolver,
    private val objectMapper: ObjectMapper,
    private val loginThrottle: LoginThrottle,
    private val authEventRecorder: AuthEventRecorder,
    private val userRepository: UserRepository,
) {
    @Bean
    fun rateLimitFilter(): RateLimitFilter =
        RateLimitFilter(
            rateLimitRedisClient,
            properties,
            conferencesProperties,
            clientIpResolver,
            objectMapper,
            loginThrottle,
            authEventRecorder,
            userRepository,
        )
}
