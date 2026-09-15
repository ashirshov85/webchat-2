package webchat.backend.config

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
        if (properties.ssl.isEnabled) builder.withSsl(true)
        val uri = builder.build()
        connectionDetails.username?.let(uri::setUsername)
        connectionDetails.password?.let { uri.setPassword(it) }
        return uri
    }

    private companion object {
        const val SHUTDOWN = "shutdown"
    }
}
