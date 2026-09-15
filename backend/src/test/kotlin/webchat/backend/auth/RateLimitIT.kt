package webchat.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest

/**
 * US5-1, US5-4 (T045a): FR-009 flood limits on all 8 public auth endpoints
 * (research.md §11, data-model.md §7).
 *
 * Covers: 429 + `Retry-After` per source IP on the identifier-bearing routes
 * register/resend/login/password-reset; the email/identifier bucket on the
 * same routes (for login the key is sha256(identifier)) throttling
 * independently of the source IP — every attempt arrives from a fresh XFF
 * address, so only the shared email/account key can trip; the resend two
 * tiers (research.md §11): the `rl:email:resend` bucket fires FIRST in
 * RateLimitFilter while the 60 s PG cooldown (data-model.md §5, covered in
 * detail by T013c) fires second — both answers are the same uniform 429
 * problem+json, indistinguishable to the client; IP-only buckets (no
 * identifier key) on confirm/password/reset-confirm/refresh — completing the
 * coverage of all 8 public routes; bucket state lives in Redis under the
 * data-model.md §7 keys, not in process memory (FR-009); and valid traffic
 * under the limits is never throttled (US5-4: a single normal user completes
 * register → confirm → set-password → login → refresh → /users/me →
 * password-reset from ONE source without ever seeing a limit).
 *
 * NOTE (TDD, constitution VI): written BEFORE T046–T048 — until the
 * RateLimitFilter/ClientIpResolver/LoginThrottle land, the 429 assertions
 * fail (RED) by design. Test-method isolation: every method uses its own
 * 198.18.0.0/15 source addresses and its own emails/usernames, so the shared
 * static Redis carries no state between methods (windows are minutes/hours).
 *
 * T045b (progressive delay, lockout, restart survival) extends this file.
 */
@Suppress("LargeClass") // tasks.md T045a-T045b mandate the whole US5 rate-limit scope in this single IT file
class RateLimitIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : AbstractIntegrationTest() {
    @Test
    fun `register beyond ip limit is rejected with 429 and retry-after`() {
        val sourceIp = "198.18.0.1"

        repeat(REGISTER_IP_LIMIT) { index ->
            val response = register("rlregip$index", "rlregip$index@example.com", xForwardedFor = sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }

        val flooded = register("rlregipflood", "rlregipflood@example.com", xForwardedFor = sourceIp)

        assertTooManyRequests(flooded, HOUR_SECONDS)
        // FR-009: the bucket state is external (Redis key of data-model.md §7), not in-process
        assertThat(redisTemplate.hasKey("rl:ip:register:$sourceIp") ?: false).isTrue
        // a throttled flood request has no side effects
        assertThat(userCount("rlregipflood")).isZero
        assertThat(outboxCount("rlregipflood@example.com")).isZero
    }

    @Test
    fun `register email bucket throttles independently of source ip`() {
        val email = "rlregemail@example.com"

        val first = register("rlregemail1", email, xForwardedFor = "198.18.0.11")
        assertThat(first.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        // same email from FRESH sources: 409 (taken by the pending account of the
        // first request) — each attempt still consumes the SAME email bucket
        assertThat(register("rlregemail2", email, xForwardedFor = "198.18.0.12").statusCode)
            .isEqualTo(HttpStatus.CONFLICT)
        assertThat(register("rlregemail3", email, xForwardedFor = "198.18.0.13").statusCode)
            .isEqualTo(HttpStatus.CONFLICT)

        val flooded = register("rlregemail4", email, xForwardedFor = "198.18.0.14")

        assertTooManyRequests(flooded, HOUR_SECONDS)
        assertThat(redisTemplate.hasKey("rl:email:register:${sha256Hex(email)}") ?: false).isTrue
        assertThat(userCount("rlregemail4")).isZero
    }

    @Test
    fun `resend beyond ip limit is rejected with 429 and retry-after`() {
        val sourceIp = "198.18.0.21"

        repeat(RESEND_IP_LIMIT) { index ->
            // unknown emails → uniform 202 without letters: pure bucket behaviour,
            // no PG cooldown can interfere (data-model.md §5 is per existing account)
            val response = resend("rlresendip$index@example.com", xForwardedFor = sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }

        val flooded = resend("rlresendipflood@example.com", xForwardedFor = sourceIp)

        assertTooManyRequests(flooded, HOUR_SECONDS)
        assertThat(outboxCount("rlresendipflood@example.com")).isZero
    }

    @Test
    fun `resend email bucket throttles independently of source ip`() {
        val email = "rlresendemail@example.com"

        repeat(RESEND_EMAIL_LIMIT) { index ->
            assertThat(resend(email, xForwardedFor = "198.18.0.${31 + index}").statusCode)
                .isEqualTo(HttpStatus.ACCEPTED)
        }

        val flooded = resend(email, xForwardedFor = "198.18.0.34")

        assertTooManyRequests(flooded, HOUR_SECONDS)
        assertThat(redisTemplate.hasKey("rl:email:resend:${sha256Hex(email)}") ?: false).isTrue
    }

    @Test
    fun `resend bucket tier and pg cooldown tier answer with the same uniform 429`() {
        // tier 1 (research.md §11): the `rl:email:resend` bucket in RateLimitFilter —
        // for an unknown email the PG cooldown is never reached, so after the bucket
        // is drained the 429 comes from the FIRST tier alone
        val unknownEmail = "rltier1@example.com"
        repeat(RESEND_EMAIL_LIMIT) { index ->
            assertThat(resend(unknownEmail, xForwardedFor = "198.18.0.4${index + 1}").statusCode)
                .isEqualTo(HttpStatus.ACCEPTED)
        }
        val bucketTier = resend(unknownEmail, xForwardedFor = "198.18.0.45")
        assertTooManyRequests(bucketTier, HOUR_SECONDS)

        // tier 2: the 60 s PG cooldown (data-model.md §5) — a fresh account email
        // passes its untouched bucket and is throttled by the cooldown instead
        // (the registration letter started it moments ago, T013c covers the details)
        assertThat(register("rltier2", "rltier2@example.com", xForwardedFor = "198.18.0.46").statusCode)
            .isEqualTo(HttpStatus.ACCEPTED)
        val cooldownTier = resend("rltier2@example.com", xForwardedFor = "198.18.0.47")
        assertTooManyRequests(cooldownTier, MINUTE_SECONDS)

        // the two tiers are indistinguishable to the client: same uniform problem+json
        val bucketProblem = objectMapper.readTree(bucketTier.body)
        val cooldownProblem = objectMapper.readTree(cooldownTier.body)
        assertThat(bucketProblem["title"].asText()).isEqualTo(cooldownProblem["title"].asText())
        assertThat(bucketProblem["status"].asInt()).isEqualTo(cooldownProblem["status"].asInt())
        assertThat(bucketTier.headers.contentType!!.toString())
            .isEqualTo(cooldownTier.headers.contentType!!.toString())
    }

    @Test
    fun `login beyond ip limit is rejected with 429 and retry-after`() {
        val sourceIp = "198.18.0.51"

        // distinct identifiers: each accumulates a single failure, so the
        // per-identifier throttle/lockout (T045b) never interferes — only the
        // shared IP bucket can trip here
        repeat(LOGIN_IP_LIMIT) { index ->
            val response = login("rl-login-unknown-$index", "Wrong-Pass-$index!", xForwardedFor = sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        }

        val flooded = login("rl-login-unknown-flood", "Wrong-Flood-Pass!", xForwardedFor = sourceIp)

        assertTooManyRequests(flooded, MINUTE_SECONDS)
        assertThat(redisTemplate.hasKey("rl:ip:login:$sourceIp") ?: false).isTrue
    }

    @Test
    fun `login account bucket throttles successful logins across source ips`() {
        createActiveUser("rlacct", "rlacct@example.com", registerIp = "198.18.0.60")

        // 10 SUCCESSFUL logins keep the brute-force failure counter (T045b) at
        // zero — every attempt comes from a FRESH source, so only the account
        // bucket keyed by sha256(identifier) (data-model.md §7) can trip
        repeat(LOGIN_ACCOUNT_LIMIT) { index ->
            val response = login("rlacct", PASSWORD, xForwardedFor = "198.18.0.${61 + index}")
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        }

        val flooded = login("rlacct", PASSWORD, xForwardedFor = "198.18.0.72")

        assertTooManyRequests(flooded, MINUTE_SECONDS)
        assertThat(redisTemplate.hasKey("rl:email:login:${sha256Hex("rlacct")}") ?: false).isTrue
    }

    @Test
    fun `password reset beyond ip limit is rejected with 429 and retry-after`() {
        val sourceIp = "198.18.0.71"

        repeat(RESET_IP_LIMIT) { index ->
            val response = passwordReset("rlresetip$index@example.com", xForwardedFor = sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }

        val flooded = passwordReset("rlresetipflood@example.com", xForwardedFor = sourceIp)

        assertTooManyRequests(flooded, HOUR_SECONDS)
        assertThat(outboxCount("rlresetipflood@example.com")).isZero
    }

    @Test
    fun `password reset email bucket throttles independently of source ip`() {
        val email = "rlresetemail@example.com"

        repeat(RESET_EMAIL_LIMIT) { index ->
            assertThat(passwordReset(email, xForwardedFor = "198.18.0.${81 + index}").statusCode)
                .isEqualTo(HttpStatus.ACCEPTED)
        }

        val flooded = passwordReset(email, xForwardedFor = "198.18.0.84")

        assertTooManyRequests(flooded, HOUR_SECONDS)
        assertThat(redisTemplate.hasKey("rl:email:password-reset:${sha256Hex(email)}") ?: false).isTrue
    }

    @Test
    fun `token routes are ip limited without identifier keys`() {
        val garbageToken = "g".repeat(43)

        val underLimitPassword = "Some-Val1d-Pass!"
        val routes =
            listOf(
                Route(
                    name = "confirm",
                    path = CONFIRM_PATH,
                    payload = mapOf("token" to garbageToken),
                    underLimitStatus = HttpStatus.BAD_REQUEST,
                    sourceIp = "198.18.0.91",
                ),
                Route(
                    name = "password",
                    path = SET_PASSWORD_PATH,
                    payload =
                        mapOf(
                            "setupToken" to garbageToken,
                            "password" to underLimitPassword,
                            "confirmPassword" to underLimitPassword,
                        ),
                    underLimitStatus = HttpStatus.BAD_REQUEST,
                    sourceIp = "198.18.0.92",
                ),
                Route(
                    name = "reset-confirm",
                    path = PASSWORD_RESET_CONFIRM_PATH,
                    payload =
                        mapOf(
                            "token" to garbageToken,
                            "password" to underLimitPassword,
                            "confirmPassword" to underLimitPassword,
                        ),
                    underLimitStatus = HttpStatus.BAD_REQUEST,
                    sourceIp = "198.18.0.93",
                ),
                Route(
                    name = "refresh",
                    path = REFRESH_PATH,
                    payload = mapOf("refreshToken" to garbageToken),
                    underLimitStatus = HttpStatus.UNAUTHORIZED,
                    sourceIp = "198.18.0.94",
                ),
            )

        routes.forEach { assertRouteIpLimited(it) }
    }

    private fun assertRouteIpLimited(route: Route) {
        repeat(TOKEN_ROUTE_IP_LIMIT) {
            val response = postJson(route.path, route.payload, route.sourceIp)
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "%s under the limit must answer %s, not be throttled",
                    route.name,
                    route.underLimitStatus,
                ).isEqualTo(route.underLimitStatus)
        }

        val flooded = postJson(route.path, route.payload, route.sourceIp)

        assertThat(flooded.statusCode)
            .overridingErrorMessage("%s beyond the ip limit must be throttled", route.name)
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertTooManyRequests(flooded, MINUTE_SECONDS)
        assertThat(redisTemplate.hasKey("rl:ip:${route.name}:${route.sourceIp}") ?: false).isTrue
    }

    @Test
    fun `normal user flow under the limits never observes throttling`() {
        val sourceIp = "198.18.1.1"

        assertThat(register("rlnormal", "rlnormal@example.com", xForwardedFor = sourceIp).statusCode)
            .isEqualTo(HttpStatus.ACCEPTED)

        val confirm =
            postJson(
                CONFIRM_PATH,
                mapOf("token" to extractVerificationToken("rlnormal@example.com")),
                sourceIp,
            )
        assertThat(confirm.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirm.body)["setupToken"].asText()

        val password = "Rlnormal-Val1d-Pass!"
        val setPassword =
            postJson(
                SET_PASSWORD_PATH,
                mapOf(
                    "setupToken" to setupToken,
                    "password" to password,
                    "confirmPassword" to password,
                ),
                sourceIp,
            )
        assertThat(setPassword.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val login = postJson(LOGIN_PATH, mapOf("identifier" to "rlnormal", "password" to password), sourceIp)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val tokens = objectMapper.readTree(login.body)

        val refresh = postJson(REFRESH_PATH, mapOf("refreshToken" to tokens["refreshToken"].asText()), sourceIp)
        assertThat(refresh.statusCode).isEqualTo(HttpStatus.OK)

        val usersMe = usersMe(tokens["accessToken"].asText())
        assertThat(usersMe.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(usersMe.body)["username"].asText()).isEqualTo("rlnormal")

        assertThat(passwordReset("rlnormal@example.com", xForwardedFor = sourceIp).statusCode)
            .isEqualTo(HttpStatus.ACCEPTED)
    }

    private fun assertTooManyRequests(
        response: ResponseEntity<String>,
        maxRetryAfterSeconds: Int,
    ) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        val retryAfter = response.headers.getFirst(HttpHeaders.RETRY_AFTER)
        assertThat(retryAfter).isNotNull
        assertThat(retryAfter!!.toInt()).isBetween(1, maxRetryAfterSeconds)
        assertThat(response.headers.contentType).isNotNull
        assertThat(response.headers.contentType!!.toString()).contains("application/problem+json")
        val problem = objectMapper.readTree(response.body)
        assertThat(problem["status"].asInt()).isEqualTo(429)
        assertThat(problem["title"].asText()).isEqualTo("Too Many Requests")
    }

    private fun register(
        username: String,
        email: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> = postJson(REGISTER_PATH, mapOf("username" to username, "email" to email), xForwardedFor)

    private fun resend(
        email: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> = postJson(RESEND_PATH, mapOf("email" to email), xForwardedFor)

    private fun login(
        identifier: String,
        password: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> =
        postJson(
            LOGIN_PATH,
            mapOf("identifier" to identifier, "password" to password),
            xForwardedFor,
        )

    private fun passwordReset(
        email: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> = postJson(PASSWORD_RESET_PATH, mapOf("email" to email), xForwardedFor)

    private fun usersMe(accessToken: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }
        return restTemplate.exchange(USERS_ME_PATH, HttpMethod.GET, HttpEntity<String>(headers), String::class.java)
    }

    private fun postJson(
        path: String,
        payload: Map<String, String>,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                xForwardedFor?.let { set("X-Forwarded-For", it) }
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    private fun createActiveUser(
        username: String,
        email: String,
        registerIp: String,
    ) {
        assertThat(register(username, email, xForwardedFor = registerIp).statusCode)
            .isEqualTo(HttpStatus.ACCEPTED)
        val confirm =
            postJson(CONFIRM_PATH, mapOf("token" to extractVerificationToken(email)), registerIp)
        assertThat(confirm.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirm.body)["setupToken"].asText()
        val setPassword =
            postJson(
                SET_PASSWORD_PATH,
                mapOf(
                    "setupToken" to setupToken,
                    "password" to PASSWORD,
                    "confirmPassword" to PASSWORD,
                ),
                registerIp,
            )
        assertThat(setPassword.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    private fun extractVerificationToken(email: String): String {
        val payloads =
            jdbcTemplate.queryForList(
                """
                SELECT payload::text FROM email_outbox
                WHERE lower(recipient_email) = ? AND email_type = 'email_verification'
                ORDER BY created_at DESC
                """.trimIndent(),
                String::class.java,
                email.lowercase(),
            )
        val payload = payloads.firstOrNull() ?: ""
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload)
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    private fun userCount(username: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE lower(username) = ?",
            Int::class.java,
            username.lowercase(),
        )

    private fun outboxCount(recipientEmail: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM email_outbox WHERE lower(recipient_email) = ?",
            Int::class.java,
            recipientEmail.lowercase(),
        )

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private data class Route(
        val name: String,
        val path: String,
        val payload: Map<String, String>,
        val underLimitStatus: HttpStatus,
        val sourceIp: String,
    )

    private companion object {
        const val PASSWORD = "Str0ng-RateLimit-IT-Pass!"
        const val HOUR_SECONDS = 3600
        const val MINUTE_SECONDS = 60

        // research.md §11 defaults, bound to auth.ratelimit.* in application.yml
        const val REGISTER_IP_LIMIT = 5
        const val REGISTER_EMAIL_LIMIT = 3
        const val RESEND_IP_LIMIT = 3
        const val RESEND_EMAIL_LIMIT = 3
        const val LOGIN_IP_LIMIT = 10
        const val LOGIN_ACCOUNT_LIMIT = 10
        const val RESET_IP_LIMIT = 5
        const val RESET_EMAIL_LIMIT = 3
        const val TOKEN_ROUTE_IP_LIMIT = 30

        const val REGISTER_PATH = "/api/v1/auth/register"
        const val RESEND_PATH = "/api/v1/auth/register/resend"
        const val CONFIRM_PATH = "/api/v1/auth/register/confirm"
        const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REFRESH_PATH = "/api/v1/auth/refresh"
        const val PASSWORD_RESET_PATH = "/api/v1/auth/password-reset"
        const val PASSWORD_RESET_CONFIRM_PATH = "/api/v1/auth/password-reset/confirm"
        const val USERS_ME_PATH = "/api/v1/users/me"
    }
}
