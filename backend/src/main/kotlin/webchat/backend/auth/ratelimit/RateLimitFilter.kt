package webchat.backend.auth.ratelimit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.convert.DurationStyle
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.config.AuthRateLimitProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

/**
 * FR-009 flood guard over the 8 public auth routes (research.md §2, §11;
 * data-model.md §7): a Bucket4j token bucket per source
 * (`rl:ip:<route>:<ip>` — every route) and per request identifier
 * (`rl:email:<route>:<sha256(email | identifier)>` — register/resend/
 * password-reset by email, login by identifier), stored in Redis through
 * [LettuceBasedProxyManager] so the state is shared by all replicas and
 * survives restarts (SC-004, constitution II: state outside the process).
 *
 * Consumption order (research.md §11): on `/auth/login` the research.md §8
 * brute-force counter is consulted FIRST — an identifier with ≥ lockout
 * threshold accumulated failures receives the uniform 429 + `Retry-After` =
 * the remaining counter TTL before any bucket consumption and any password
 * work (the account/IP buckets would otherwise answer with a ≤ 60 s wait
 * and betray the ~15-min lockout expectation); then the identifier bucket;
 * then the IP bucket. A drained bucket answers with the uniform 429
 * problem+json + `Retry-After` and the chain never sees the request, so a
 * throttled flood has no side effects.
 *
 * The identifier is read from the JSON body only on the routes that carry
 * one; the body is replayed downstream via [CachedBodyRequestWrapper]. A
 * missing or unparseable identifier (empty/malformed body) degrades to the
 * IP bucket alone — MVC validation then answers the client.
 *
 * Redis unavailability fails OPEN (data-model.md §7 invariant): losing the
 * ephemeral store may only reset the limits, never 5xx the public endpoints.
 */
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
class RateLimitFilter(
    redisClient: RedisClient,
    properties: AuthRateLimitProperties,
    private val clientIpResolver: ClientIpResolver,
    private val objectMapper: ObjectMapper,
    private val loginThrottle: LoginThrottle,
    private val authEventRecorder: AuthEventRecorder,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {
    private val proxyManager: ProxyManager<ByteArray> =
        Bucket4jLettuce
            .casBasedBuilder(redisClient)
            .expirationAfterWrite(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofSeconds(BUCKET_TTL_MARGIN_SECONDS),
                ),
            ).build()

    private val routes: Map<String, LimitedRoute> =
        mapOf(
            "/api/v1/auth/register" to limitedRoute("register", properties.register),
            "/api/v1/auth/register/resend" to limitedRoute("resend", properties.resend),
            "/api/v1/auth/login" to limitedRoute("login", properties.login),
            "/api/v1/auth/password-reset" to limitedRoute("password-reset", properties.passwordReset),
            "/api/v1/auth/register/confirm" to limitedRoute("confirm", properties.confirm),
            "/api/v1/auth/register/password" to limitedRoute("password", properties.password),
            "/api/v1/auth/password-reset/confirm" to limitedRoute("reset-confirm", properties.resetConfirm),
            "/api/v1/auth/refresh" to limitedRoute("refresh", properties.refresh),
        )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val route = routes[request.requestURI]?.takeIf { request.method == POST_METHOD }
        val admittedRequest = if (route == null) request else gate(route, request, response) ?: return
        filterChain.doFilter(admittedRequest, response)
    }

    /**
     * Consumes the buckets of the route and returns the request to forward
     * (the body-replaying wrapper on identifier routes) — `null` when the
     * login brute-force counter or a drained bucket already answered with
     * the uniform 429.
     */
    @Suppress("ReturnCount") // every return IS a rejection leg of the research.md §11 priority chain
    private fun gate(
        route: LimitedRoute,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): HttpServletRequest? {
        var forwardedRequest = request
        route.identifierBucket?.let { identifierBucket ->
            val body = request.inputStream.readBytes()
            forwardedRequest = CachedBodyRequestWrapper(request, body)
            extractIdentifier(body, identifierBucket.field)?.let { identifier ->
                // research.md §8/§11 route priority: the login:fail counter
                // verdict comes FIRST — before the account/IP buckets and any
                // password work
                if (route.name == LOGIN_ROUTE_NAME && rejectLockedOutIdentifier(identifier, request, response)) {
                    return null
                }
                val key = "$EMAIL_KEY_FAMILY:${route.name}:${sha256Hex(identifier)}"
                if (!consume(identifierBucket.limit, key, response)) return null
            }
        }

        val clientIp = clientIpResolver.resolve(request)
        val ipKey = "$IP_KEY_FAMILY:${route.name}:$clientIp"
        return if (consume(route.ipLimit, ipKey, response)) forwardedRequest else null
    }

    /**
     * The lockout leg for `/auth/login` (research.md §8, SC-004): ≥ lockout
     * threshold accumulated failures — the uniform 429 with `Retry-After` =
     * the remaining counter TTL and a `login_throttled` journal record
     * (FR-013, no secrets: only the resolved user id, the hashed IP and the
     * advisory wait). Always false when the counter is not armed or Redis is
     * unavailable (fail open, data-model.md §7).
     */
    private fun rejectLockedOutIdentifier(
        identifier: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Boolean {
        val lockoutRemaining = loginThrottle.lockoutRemaining(identifier) ?: return false
        journalLockout(identifier, request, lockoutRemaining)
        writeTooManyRequests(response, lockoutRemaining.toNanos())
        return true
    }

    /** The lockout journal record — a failure to write must never break the guard itself. */
    private fun journalLockout(
        identifier: String,
        request: HttpServletRequest,
        lockoutRemaining: Duration,
    ) {
        runCatching {
            val user = userRepository.findByUsername(identifier) ?: userRepository.findByEmail(identifier)
            authEventRecorder.record(
                eventType = AuthEventType.LOGIN_THROTTLED,
                clientIp = clientIpResolver.resolve(request),
                userAgent = request.getHeader(HttpHeaders.USER_AGENT),
                userId = user?.id,
                details =
                    mapOf(
                        REASON_DETAIL to LOCKOUT_REASON,
                        RETRY_AFTER_DETAIL to lockoutRemaining.toSeconds(),
                    ),
            )
        }.onFailure { log.warn("login_throttled journal write for a locked-out identifier failed: {}", it.toString()) }
    }

    /** One token per request; a rejection is rendered immediately as the uniform 429. */
    private fun consume(
        limit: RateLimit,
        key: String,
        response: HttpServletResponse,
    ): Boolean =
        runCatching { newBucket(limit, key).tryConsumeAndReturnRemaining(1) }
            .onFailure { log.warn("Rate limit bucket {} is unavailable, failing open: {}", key, it.toString()) }
            .getOrNull()
            ?.let { probe ->
                if (probe.isConsumed) {
                    true
                } else {
                    writeTooManyRequests(response, probe.nanosToWaitForRefill)
                    false
                }
            } ?: true

    private fun newBucket(
        limit: RateLimit,
        key: String,
    ): BucketProxy {
        val bandwidth =
            Bandwidth
                .builder()
                .capacity(limit.count)
                .refillGreedy(limit.count, limit.window)
                .build()
        val configuration = BucketConfiguration.builder().addLimit(bandwidth).build()
        return proxyManager.getProxy(key.toByteArray(StandardCharsets.UTF_8)) { configuration }
    }

    /** The exact 429 problem+json of api-contract.md §3 with the bucket refill wait as `Retry-After`. */
    private fun writeTooManyRequests(
        response: HttpServletResponse,
        nanosToWaitForRefill: Long,
    ) {
        val retryAfterSeconds =
            ((nanosToWaitForRefill + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
                .coerceIn(RETRY_AFTER_FLOOR_SECONDS, RETRY_AFTER_CEILING_SECONDS)
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        response.setIntHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toInt())
        response.outputStream.write(TOO_MANY_REQUESTS_BODY.toByteArray(StandardCharsets.UTF_8))
    }

    /** Trims and lowercases the field value so the bucket key is case-insensitive like the lookup it guards. */
    private fun extractIdentifier(
        body: ByteArray,
        field: String,
    ): String? =
        runCatching {
            val root: JsonNode? = objectMapper.readTree(body)
            root
                ?.get(field)
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = EMPTY_STRING) { BYTE_TO_HEX_FORMAT.format(it) }

    private fun limitedRoute(
        name: String,
        limits: AuthRateLimitProperties.RouteLimits,
    ): LimitedRoute {
        require(limits.email == null || limits.account == null) {
            "Route '$name' must define at most one identifier bucket (email or account)"
        }
        val identifierSpec = limits.email ?: limits.account
        val identifierBucket =
            identifierSpec?.let {
                val field = if (limits.email != null) EMAIL_FIELD else IDENTIFIER_FIELD
                IdentifierBucket(field, parseRateLimit(it))
            }
        if (name == LOGIN_ROUTE_NAME) {
            // the login:fail counter check rides the identifier extraction —
            // a login route without the account bucket would silently lose it
            requireNotNull(identifierBucket) {
                "Route 'login' must define the account bucket (research.md §8, §11)"
            }
        }
        return LimitedRoute(name, parseRateLimit(limits.ip), identifierBucket)
    }

    private fun parseRateLimit(spec: String): RateLimit {
        val parts = spec.split(LIMIT_DELIMITER)
        require(parts.size == LIMIT_SPEC_PARTS) { "Malformed rate limit '$spec', expected '<count>/<window>'" }
        val count = parts.first().trim().toLong()
        require(count > 0) { "Rate limit count must be positive: '$spec'" }
        return RateLimit(count, DurationStyle.detectAndParse(parts.last().trim()))
    }

    private data class RateLimit(
        val count: Long,
        val window: Duration,
    )

    private data class IdentifierBucket(
        val field: String,
        val limit: RateLimit,
    )

    private data class LimitedRoute(
        val name: String,
        val ipLimit: RateLimit,
        val identifierBucket: IdentifierBucket?,
    )

    private companion object {
        private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

        const val POST_METHOD = "POST"
        const val IP_KEY_FAMILY = "rl:ip"
        const val EMAIL_KEY_FAMILY = "rl:email"
        const val EMAIL_FIELD = "email"
        const val IDENTIFIER_FIELD = "identifier"
        const val LOGIN_ROUTE_NAME = "login"
        const val REASON_DETAIL = "reason"
        const val LOCKOUT_REASON = "lockout"
        const val RETRY_AFTER_DETAIL = "retryAfterSec"
        const val SHA_256 = "SHA-256"
        const val LIMIT_DELIMITER = "/"
        const val LIMIT_SPEC_PARTS = 2
        const val BYTE_TO_HEX_FORMAT = "%02x"
        const val EMPTY_STRING = ""
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val RETRY_AFTER_FLOOR_SECONDS = 1L
        const val RETRY_AFTER_CEILING_SECONDS = Int.MAX_VALUE.toLong()
        const val BUCKET_TTL_MARGIN_SECONDS = 60L
        const val TOO_MANY_REQUESTS_BODY =
            """{"title":"Too Many Requests","status":429,"detail":"Rate limit exceeded"}"""
    }
}
