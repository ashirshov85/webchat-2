package webchat.backend.config

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * Infra for the US5 rate limiting (T047, research.md §2): a dedicated
 * [RedisClient] backing Bucket4j's LettuceBasedProxyManager. It targets the
 * SAME Redis as the application's shared LettuceConnectionFactory: the
 * [RedisConnectionDetails] contribution already unifies env properties and
 * Testcontainers `@ServiceConnection` wiring (Bucket4j drives its own
 * connections on top of the client, the container shuts it down).
 */
@Configuration
class RateLimitConfig {
    @Bean(destroyMethod = SHUTDOWN)
    fun rateLimitRedisClient(
        properties: RedisProperties,
        connectionDetails: RedisConnectionDetails,
    ): RedisClient = RedisClient.create(redisUri(properties, connectionDetails))

    /**
     * T032 (research.md 004 §7): the shared [ProxyManager] over the SAME
     * `rl:*` Redis — the per-user buckets of 004 draw their state from
     * here: the send flood bucket `rl:user:msgsend:{userId}` (T032) and,
     * since T053a, the search bucket `rl:user:search:{userId}` of №19
     * (FR-016) — so every replica and every device of a user shares ONE
     * bucket (constitution II: state outside the process). The auth
     * [webchat.backend.auth.ratelimit.RateLimitFilter] of 002 keeps its
     * dedicated manager unchanged. Idle buckets expire after the
     * refill-to-max time plus the margin below — losing them only resets
     * an allowance.
     */
    @Bean
    fun rateLimitProxyManager(rateLimitRedisClient: RedisClient): ProxyManager<ByteArray> =
        Bucket4jLettuce
            .casBasedBuilder(rateLimitRedisClient)
            .expirationAfterWrite(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofSeconds(BUCKET_TTL_MARGIN_SECONDS),
                ),
            ).build()

    private fun redisUri(
        properties: RedisProperties,
        connectionDetails: RedisConnectionDetails,
    ): RedisURI {
        properties.url?.let { return RedisURI.create(it) }
        val standalone = connectionDetails.standalone
        val builder =
            RedisURI
                .builder()
                .withHost(standalone.host)
                .withPort(standalone.port)
                .withDatabase(standalone.database)
        val username = connectionDetails.username
        val password: CharSequence? = connectionDetails.password
        if (username != null && password != null) {
            builder.withAuthentication(username, password)
        } else if (password != null) {
            builder.withPassword(password)
        }
        if (properties.ssl.isEnabled) builder.withSsl(true)
        return builder.build()
    }

    private companion object {
        const val SHUTDOWN = "shutdown"

        /** Idle-bucket TTL margin on top of the refill-to-max time (002 invariant). */
        const val BUCKET_TTL_MARGIN_SECONDS = 60L
    }
}

/**
 * T053a (research.md 004 §7, generalization of the T032 infra): one
 * reusable per-user token-bucket gate over the shared `rl:*`
 * [ProxyManager]. Both 004 flood limits draw from it — the send path
 * `rl:user:msgsend:{userId}` (30 messages/min, FR-011) and the №19
 * search route `rl:user:search:{userId}` (30 req/min, FR-016) — with the
 * SAME semantics (capacity N, greedy N/60s drip: a burst ≤ N, then one
 * token per 2 s).
 *
 * The verdict is decided by [tryAcquire]: [Verdict.Consumed] lets the
 * request through, [Verdict.Rejected] carries the integral ceiling of
 * the refill wait (≥1s — the `Retry-After` of the contract). Redis
 * unavailability fails OPEN (the 002 §7 invariant): losing the ephemeral
 * store may only reset an allowance, never 5xx the gated route.
 *
 * Callers own the refusal SIDE EFFECTS (warn log without payload, the
 * rejected-total metric, the typed 429 exception) — this component stays
 * payload-agnostic and logs only the bucket key on an outage.
 */
@Component
class UserRateLimiter(
    private val proxyManager: ProxyManager<ByteArray>,
) {
    private val log = LoggerFactory.getLogger(UserRateLimiter::class.java)

    /** The outcome of one permit request. */
    sealed interface Verdict {
        /** A token was consumed — the request may proceed. */
        data object Consumed : Verdict

        /**
         * The bucket is empty — the request is refused;
         * [retryAfterSeconds] is the integral wait for the next token.
         */
        data class Rejected(
            val retryAfterSeconds: Long,
        ) : Verdict
    }

    /**
     * Consumes one token of the per-user bucket `keyFamily + userId`
     * (capacity [permitsPerMinute], greedy refill over 60 s). The key
     * family is the caller's Redis prefix WITH the trailing colon (e.g.
     * `rl:user:search:`), so the bucket id never collides across routes.
     */
    @Suppress("TooGenericExceptionCaught") // the driver signals any outage by throwing
    fun tryAcquire(
        keyFamily: String,
        userId: UUID,
        permitsPerMinute: Long,
    ): Verdict {
        val key = "$keyFamily$userId"
        val probe =
            runCatching {
                proxyManager
                    .getProxy(key.toByteArray(StandardCharsets.UTF_8)) { bucketConfiguration(permitsPerMinute) }
                    .tryConsumeAndReturnRemaining(1)
            }.onFailure { outage ->
                log.warn("rate-limit bucket <{}> is unavailable, failing open: {}", key, outage.toString())
            }.getOrNull()
        return when {
            // an outage fails open — the route proceeds, the allowance just resets
            probe == null || probe.isConsumed -> Verdict.Consumed
            else -> Verdict.Rejected(retryAfterCeiling(probe.nanosToWaitForRefill))
        }
    }

    /** openapi №16/№19: the integral refill-wait ceiling, never below 1 second. */
    private fun retryAfterCeiling(nanosToWaitForRefill: Long): Long =
        ((nanosToWaitForRefill + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
            .coerceAtLeast(RETRY_AFTER_FLOOR_SECONDS)

    /** research.md 004 §7: capacity N, greedy N/60s — the uniform drip, burst ≤ N then 1 per 2s. */
    private fun bucketConfiguration(permitsPerMinute: Long): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(permitsPerMinute)
                    .refillGreedy(permitsPerMinute, Duration.ofSeconds(REFILL_WINDOW_SECONDS))
                    .build(),
            ).build()

    private companion object {
        /** research.md 004 §7: one window of the N-tokens-per-minute drip. */
        const val REFILL_WINDOW_SECONDS = 60L

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** openapi №16/№19: `Retry-After` minimum 1. */
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
    }
}
