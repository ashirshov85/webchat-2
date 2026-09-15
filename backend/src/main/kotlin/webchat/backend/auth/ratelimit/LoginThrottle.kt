package webchat.backend.auth.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.ExpirationOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.types.Expiration
import org.springframework.stereotype.Component
import webchat.backend.config.AuthRateLimitProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Progressive slow-down and brute-force lockout counter on the login
 * identifier (T048, research.md §8, SC-004): a Redis INCR counter
 * `login:fail:<sha256(identifier)>` (data-model.md §7) whose window is
 * anchored at the FIRST failure of a run (EXPIRE NX) and resets on the one
 * event that proves knowledge of the secret — a successful login.
 *
 * Two effects by the failures n accumulated BEFORE the current attempt:
 *  * n = 4…threshold−1 — the server-side delay
 *    min(baseDelay × 2^(n−3), maxDelay), applied by
 *    [webchat.backend.auth.domain.service.LoginService] before the password
 *    check (FR-009, US5-2);
 *  * n ≥ threshold — the identifier is locked out: RateLimitFilter answers
 *    429 + `Retry-After` = the remaining counter TTL BEFORE the account/IP
 *    buckets and any password work (research.md §11 route priority), so at
 *    most `threshold` Argon2 evaluations happen per window.
 *
 * The counter is EXTERNAL state: shared by all replicas and surviving
 * restarts (SC-004, US5-3, constitution II — stateless pods, state outside
 * the process). Redis unavailability fails OPEN — losing the ephemeral
 * counter may only reset the protection, never 5xx the login endpoint
 * (data-model.md §7 invariant).
 */
@Component
class LoginThrottle(
    private val redisTemplate: StringRedisTemplate,
    properties: AuthRateLimitProperties,
) {
    private val limits: AuthRateLimitProperties.RouteLimits.ThrottleLimits =
        requireNotNull(properties.login.throttle) {
            "auth.ratelimit.login.throttle must be configured (research.md §8, SC-004)"
        }

    init {
        require(limits.baseDelay.isPositive) { "auth.ratelimit.login.throttle.base-delay must be positive" }
        require(limits.maxDelay >= limits.baseDelay) {
            "auth.ratelimit.login.throttle.max-delay must not be below base-delay"
        }
        require(limits.lockoutThreshold > 0) { "auth.ratelimit.login.throttle.lockout-threshold must be positive" }
        require(limits.window.isPositive) { "auth.ratelimit.login.throttle.window must be positive" }
    }

    /**
     * Failures accumulated for the identifier BEFORE the current attempt —
     * `null` when Redis is unavailable (the caller fails open: no delay).
     */
    fun failuresBefore(identifier: String): Long? {
        val key = keyOrNull(identifier) ?: return 0L
        return runCatching { redisTemplate.opsForValue().get(key)?.toLongOrNull() ?: 0L }
            .onFailure { warnUnavailable(READ_OPERATION, key, it) }
            .getOrNull()
    }

    /**
     * The delay ladder step for [failures] accumulated attempts:
     * ZERO below the 4th failure (research.md §8: n ≤ 3 — no delay),
     * min(baseDelay × 2^(n−3), maxDelay) at n = 4…threshold−1, and the
     * maxDelay cap from the lockout threshold on (an upstream miss of the
     * filter verdict still cannot run faster than the cap).
     */
    @Suppress("ReturnCount") // the rungs ARE the ladder formula of research.md §8
    fun delayFor(failures: Long): Duration {
        if (failures < FIRST_DELAY_FAILURES) return Duration.ZERO
        if (failures >= limits.lockoutThreshold) return limits.maxDelay
        val exponent = failures - SHIFT_BASE
        if (exponent >= MAX_SAFE_SHIFT) return limits.maxDelay
        val step = limits.baseDelay.toMillis() shl exponent.toInt()
        val delay = Duration.ofMillis(step)
        return if (delay > limits.maxDelay) limits.maxDelay else delay
    }

    /**
     * The remaining lockout when the identifier has [limits.lockoutThreshold]
     * or more accumulated failures — `Retry-After` for the uniform 429;
     * `null` when the identifier is not locked out or Redis is unavailable
     * (fail open). A counter without a TTL (a crashed write leg) degrades to
     * the full window rather than a lockout with no advisory wait.
     */
    @Suppress("ReturnCount") // the fail-open legs of the data-model.md §7 invariant
    fun lockoutRemaining(identifier: String): Duration? {
        val key = keyOrNull(identifier) ?: return null
        val failures =
            runCatching { redisTemplate.opsForValue().get(key)?.toLongOrNull() ?: 0L }
                .onFailure { warnUnavailable(READ_OPERATION, key, it) }
                .getOrNull() ?: return null
        if (failures < limits.lockoutThreshold) return null
        val ttlSeconds =
            runCatching { redisTemplate.getExpire(key, TimeUnit.SECONDS) }
                .onFailure { warnUnavailable(READ_OPERATION, key, it) }
                .getOrNull() ?: NO_TTL
        return if (ttlSeconds > 0) Duration.ofSeconds(ttlSeconds) else limits.window
    }

    /** A failed attempt: INCR + EXPIRE NX — the window never slides on retries. */
    fun onFailure(identifier: String) {
        val key = keyOrNull(identifier) ?: return
        runCatching {
            redisTemplate.opsForValue().increment(key)
            redisTemplate.expire(key, Expiration.from(limits.window), ExpirationOptions.builder().nx().build())
        }.onFailure { warnUnavailable(WRITE_OPERATION, key, it) }
    }

    /** A successful login resets the counter (research.md §8: reset on success only). */
    fun onSuccess(identifier: String) {
        val key = keyOrNull(identifier) ?: return
        runCatching { redisTemplate.delete(key) }
            .onFailure { warnUnavailable(WRITE_OPERATION, key, it) }
    }

    /**
     * The data-model.md §7 key of a non-blank identifier, normalized with
     * the same trim+lowercase rule as the lookup it guards — username and
     * email forms of one account share their buckets, spoofed case variants
     * cannot split the counter.
     */
    private fun keyOrNull(identifier: String): String? =
        identifier
            .trim()
            .lowercase()
            .takeIf { it.isNotEmpty() }
            ?.let { "$KEY_FAMILY:${sha256Hex(it)}" }

    private fun warnUnavailable(
        operation: String,
        key: String,
        error: Throwable,
    ) {
        log.warn("Login throttle counter {} on {} is unavailable, failing open: {}", operation, key, error.toString())
    }

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = EMPTY_STRING) { BYTE_TO_HEX_FORMAT.format(it) }

    private companion object {
        private val log = LoggerFactory.getLogger(LoginThrottle::class.java)

        /** data-model.md §7 key family: `login:fail:<sha256(identifier)>`. */
        const val KEY_FAMILY = "login:fail"

        /** research.md §8: n ≤ 3 — no delay, the ladder starts at the 4th accumulated failure. */
        const val FIRST_DELAY_FAILURES = 4L

        /** delay = baseDelay × 2^(n−3). */
        const val SHIFT_BASE = 3L

        /** Beyond this shift any base overruns the cap — clamp straight to maxDelay. */
        const val MAX_SAFE_SHIFT = 30L

        /** `getExpire` sentinel for a key without a TTL (or missing). */
        const val NO_TTL = -1L

        const val READ_OPERATION = "read"
        const val WRITE_OPERATION = "write"
        const val SHA_256 = "SHA-256"
        const val BYTE_TO_HEX_FORMAT = "%02x"
        const val EMPTY_STRING = ""
    }
}
