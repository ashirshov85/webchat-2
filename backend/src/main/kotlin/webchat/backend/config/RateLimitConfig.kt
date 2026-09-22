package webchat.backend.config

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

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
     * `rl:*` Redis — the per-user send flood bucket
     * `rl:user:msgsend:{userId}` of the 004 send path draws its state from
     * here, so every replica and every device of a user shares ONE bucket
     * (constitution II: state outside the process). The auth
     * [webchat.backend.auth.ratelimit.RateLimitFilter] of 002 keeps its
     * dedicated manager unchanged; T053a generalizes this bean further for
     * the search-route limit. Idle buckets expire after the refill-to-max
     * time plus the margin below — losing them only resets an allowance.
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
