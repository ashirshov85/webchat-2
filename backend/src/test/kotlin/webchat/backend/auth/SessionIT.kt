package webchat.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * US2-1…US2-5 (T026): POST /api/v1/auth/login, /auth/refresh, /auth/logout —
 * contracts №5–7.
 *
 * Covers: login by username AND by email (lower() lookup) → 200 TokenPair with
 * an ES256 JWT access token (header `kid`, claims `sub`/`sid`/`jti`/`typ:"access"`)
 * and an opaque base64url refresh token of which only the SHA-256 hash is
 * persisted; uniform 401 "Invalid credentials" for a wrong password and for
 * unknown identifiers alike — timings aligned through the fictitious Argon2
 * hash (research.md §7) — while a sub-8-chars password is still 401, never 400
 * (LoginRequest carries no password policy, contract §4); 403 for incomplete
 * registrations with no tokens and no session created; refresh rotation
 * (CAS UPDATE, replaced_by chain) with the rotated token being single-use: its
 * reuse → uniform 401, session compromised, the whole refresh chain revoked and
 * the sid denylisted in Redis (`auth:denylist:sid:<sid>`, TTL ≤ access TTL);
 * logout → 204 revoking BOTH tokens of its session while a parallel session of
 * the same user stays fully functional (edge spec); auth_events record
 * login_success/login_failed, refresh_rotated, logout and refresh_reuse_detected
 * + session_revoked{reason=logout|refresh_reuse_detected} without secrets
 * (FR-013).
 */
@Suppress("LargeClass") // tasks.md T026 mandates the whole US2 flow in this single IT file
class SessionIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : AbstractIntegrationTest() {
    @Test
    fun `login with username returns 200 token pair with session and login_success event`() {
        createActiveUser("ursula", "ursula@example.com", registerIp = "198.51.100.20")

        val response = login("ursula", PASSWORD, xForwardedFor = "198.51.100.21")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        val accessToken = body["accessToken"].asText()
        val refreshToken = body["refreshToken"].asText()

        val segments = accessToken.split(".")
        assertThat(segments).hasSize(3)
        assertThat(objectMapper.readTree(base64UrlDecode(segments[0])).has("kid")).isTrue
        val claims = objectMapper.readTree(base64UrlDecode(segments[1]))
        assertThat(claims.path("typ").asText()).isEqualTo("access")
        assertThat(claims.path("sub").asText()).isEqualTo(userId("ursula").toString())
        val sid = sessionIdOf(refreshToken)!!
        assertThat(claims.path("sid").asText()).isEqualTo(sid.toString())
        assertThat(claims.path("jti").asText()).isNotEmpty
        assertThat(refreshToken).matches("[A-Za-z0-9_-]{43}")
        assertThat(body["tokenType"].asText()).isEqualTo("Bearer")
        assertThat(body["expiresInSec"].asInt()).isEqualTo(ACCESS_TTL_SECONDS)

        val user = body["user"]
        assertThat(user["id"].asText()).isEqualTo(userId("ursula").toString())
        assertThat(user["username"].asText()).isEqualTo("ursula")
        assertThat(user["email"].asText()).isEqualTo("ursula@example.com")

        assertThat(sessionRow(sid)!!["status"]).isEqualTo("active")
        assertThat(refreshGeneration(refreshToken)!!["status"]).isEqualTo("active")

        val event = authEvent("login_success", "ursula")!!
        assertThat(event["user_id"]).isEqualTo(userId("ursula"))
        assertThat(event["ip_hash"] as String).matches("[0-9a-f]{64}")
        assertThat(event["details"] as String)
            .doesNotContain(PASSWORD)
            .doesNotContain(refreshToken)
            .doesNotContain(accessToken)
    }

    @Test
    fun `login with email equals login with username`() {
        createActiveUser("victor", "victor@example.com", registerIp = "198.51.100.22")

        val byEmail = login("victor@example.com", PASSWORD, xForwardedFor = "198.51.100.23")
        val byUsername = login("victor", PASSWORD, xForwardedFor = "198.51.100.24")
        val byUpperEmail = login("VICTOR@EXAMPLE.COM", PASSWORD, xForwardedFor = "198.51.100.25")
        val byUpperUsername = login("Victor", PASSWORD, xForwardedFor = "198.51.100.26")

        listOf(byEmail, byUsername, byUpperEmail, byUpperUsername).forEach { response ->
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = objectMapper.readTree(response.body)
            assertThat(body["tokenType"].asText()).isEqualTo("Bearer")
            assertThat(body["expiresInSec"].asInt()).isEqualTo(ACCESS_TTL_SECONDS)
            assertThat(body["user"]["username"].asText()).isEqualTo("victor")
            assertThat(body["user"]["email"].asText()).isEqualTo("victor@example.com")
        }
    }

    @Test
    fun `login failures return uniform 401 without any password policy check`() {
        createActiveUser("walter", "walter@example.com", registerIp = "203.0.113.20")
        val since = OffsetDateTime.now().minusSeconds(5)

        val wrongPassword = login("walter", "Totally-Wrong-Pass!", xForwardedFor = "203.0.113.21")
        val wrongPasswordByEmail = login("walter@example.com", "Totally-Wrong-Pass!", xForwardedFor = "203.0.113.22")
        val unknownUsername = login("no-such-walter-xyz", PASSWORD, xForwardedFor = "203.0.113.23")
        val unknownEmail = login("no-such-walter@example.net", PASSWORD, xForwardedFor = "203.0.113.24")

        listOf(wrongPassword, wrongPasswordByEmail, unknownUsername, unknownEmail).forEach { response ->
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            val problem = objectMapper.readTree(response.body)
            assertThat(problem["title"].asText()).isEqualTo("Unauthorized")
            assertThat(problem["detail"].asText()).isEqualTo("Invalid credentials")
        }
        assertThat(wrongPassword.headers.contentType).isNotNull
        assertThat(wrongPassword.headers.contentType!!.toString()).contains("application/problem+json")

        // LoginRequest.password is string(1..128) with NO policy (contract §4):
        // a too-short password is a credentials mismatch → 401, never 400
        val shortPassword = login("walter", "short", xForwardedFor = "203.0.113.25")
        assertThat(shortPassword.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(shortPassword.body)["detail"].asText()).isEqualTo("Invalid credentials")

        assertThat(sessionCount("walter")).isZero

        assertThat(authEventCount("login_failed", "walter")).isEqualTo(3)
        assertThat(nullUserEventCountSince("login_failed", since)).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `login timing for unknown identifier is aligned with wrong password timing`() {
        createActiveUser("tinatur", "tinatur@example.com", registerIp = "198.51.100.30")

        // sanity: the timing comparison is only meaningful against a working login
        assertThat(login("tinatur", PASSWORD, xForwardedFor = "198.51.100.33").statusCode)
            .isEqualTo(HttpStatus.OK)

        login("tinatur", "Not-The-Right-Pass!", xForwardedFor = "198.51.100.31")
        login("no-such-timing-user", "Not-The-Right-Pass!", xForwardedFor = "198.51.100.32")

        val wrongPasswordMs =
            averageMillis(TIMING_SAMPLES) {
                login("tinatur", "Not-The-Right-Pass!", xForwardedFor = "192.0.2.10")
            }
        val unknownIdentifierMs =
            averageMillis(TIMING_SAMPLES) {
                login("no-such-timing-user", "Not-The-Right-Pass!", xForwardedFor = "192.0.2.11")
            }

        // research.md §7: an unknown identifier must run the same Argon2 work against the
        // fictitious hash — never a fast reject path
        assertThat(unknownIdentifierMs).isGreaterThanOrEqualTo(wrongPasswordMs / TIMING_TOLERANCE)
    }

    @Test
    fun `login is rejected with 403 until registration is completed`() {
        register("xavier", "xavier@example.com", xForwardedFor = "203.0.113.30")

        val pending = login("xavier", PASSWORD, xForwardedFor = "203.0.113.31")
        assertThat(pending.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        val problem = objectMapper.readTree(pending.body)
        assertThat(problem["title"]).isNotNull
        assertThat(problem["detail"].asText()).isNotEmpty.isNotEqualTo("Invalid credentials")
        assertThat(problem.has("accessToken")).isFalse
        assertThat(problem.has("refreshToken")).isFalse
        assertThat(sessionCount("xavier")).isZero

        registerAndConfirm("yvonne", "yvonne@example.com", xForwardedFor = "203.0.113.32")

        val awaiting = login("yvonne@example.com", PASSWORD, xForwardedFor = "203.0.113.33")
        assertThat(awaiting.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(objectMapper.readTree(awaiting.body).has("accessToken")).isFalse
        assertThat(sessionCount("yvonne")).isZero
    }

    @Test
    fun `refresh returns a new pair and marks the old generation rotated`() {
        createActiveUser("zuri", "zuri@example.com", registerIp = "198.51.100.40")

        val first = loginOkPair("zuri", xForwardedFor = "198.51.100.41")
        val response = refresh(first.refreshToken, xForwardedFor = "198.51.100.42")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        val newAccessToken = body["accessToken"].asText()
        val newRefreshToken = body["refreshToken"].asText()
        assertThat(newAccessToken.split(".")).hasSize(3)
        assertThat(newRefreshToken).matches("[A-Za-z0-9_-]{43}")
        assertThat(newRefreshToken).isNotEqualTo(first.refreshToken)
        assertThat(body["tokenType"].asText()).isEqualTo("Bearer")
        assertThat(body["expiresInSec"].asInt()).isEqualTo(ACCESS_TTL_SECONDS)

        // data-model.md §4: CAS rotation links the old generation to its replacement
        val oldGeneration = refreshGeneration(first.refreshToken)!!
        val newGeneration = refreshGeneration(newRefreshToken)!!
        assertThat(oldGeneration["status"]).isEqualTo("rotated")
        assertThat(oldGeneration["replaced_by"]).isEqualTo(newGeneration["id"])
        assertThat(newGeneration["status"]).isEqualTo("active")
        assertThat(sessionRow(sessionIdOf(first.refreshToken)!!)!!["status"]).isEqualTo("active")

        assertThat(authEventCount("refresh_rotated", "zuri")).isEqualTo(1)
    }

    @Test
    fun `reuse of rotated refresh token revokes the chain, compromises the session and denylists sid`() {
        createActiveUser("rietta", "rietta@example.com", registerIp = "198.51.100.50")

        val first = loginOkPair("rietta", xForwardedFor = "198.51.100.51")
        val second = refreshOk(first.refreshToken, xForwardedFor = "198.51.100.52")
        val sid = sessionIdOf(first.refreshToken)!!

        val reuse = refresh(first.refreshToken, xForwardedFor = "198.51.100.53")
        assertThat(reuse.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        // contract №6: uniform 401 — reuse is indistinguishable from an unknown token
        val unknown = refresh("g".repeat(43), xForwardedFor = "198.51.100.54")
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        val reuseProblem = objectMapper.readTree(reuse.body)
        val unknownProblem = objectMapper.readTree(unknown.body)
        assertThat(reuseProblem["title"].asText()).isEqualTo(unknownProblem["title"].asText())
        assertThat(reuseProblem["detail"].asText()).isEqualTo(unknownProblem["detail"].asText())

        // the whole chain is revoked: the current generation dies with the session
        val currentAfterReuse = refresh(second.refreshToken, xForwardedFor = "198.51.100.55")
        assertThat(currentAfterReuse.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        assertThat(sessionRow(sid)!!["status"]).isEqualTo("compromised")
        assertThat(sessionRow(sid)!!["revoked_reason"]).isEqualTo("refresh_reuse_detected")
        assertThat(activeRefreshCount(sid)).isZero

        // data-model.md §7: denylist marker for the sid with TTL bounded by the access lifetime
        val denylistTtl = redisTemplate.getExpire("$DENYLIST_KEY_PREFIX$sid", TimeUnit.SECONDS)
        assertThat(denylistTtl ?: -1L).isGreaterThan(0).isLessThanOrEqualTo(ACCESS_TTL_SECONDS.toLong())

        assertThat(authEventCount("refresh_reuse_detected", "rietta")).isEqualTo(1)
        val reuseEvent = authEvent("refresh_reuse_detected", "rietta")!!
        assertThat(reuseEvent["details"] as String)
            .doesNotContain(first.refreshToken)
            .doesNotContain(second.refreshToken)
        val revokedEvent = authEvent("session_revoked", "rietta")!!
        assertThat(revokedEvent["details"] as String).contains("refresh_reuse_detected")
    }

    @Test
    fun `logout revokes both tokens of its session and leaves parallel sessions independent`() {
        createActiveUser("patreece", "patreece@example.com", registerIp = "198.51.100.60")

        val deviceA = loginOkPair("patreece", xForwardedFor = "198.51.100.61")
        val deviceB = loginOkPair("patreece", xForwardedFor = "198.51.100.62")
        val sidA = sessionIdOf(deviceA.refreshToken)!!
        val sidB = sessionIdOf(deviceB.refreshToken)!!
        assertThat(sidA).isNotEqualTo(sidB)

        val withoutToken = logout(null, deviceA.refreshToken, xForwardedFor = "198.51.100.63")
        assertThat(withoutToken.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(withoutToken.body)["title"].asText()).isEqualTo("Unauthorized")

        val response = logout(deviceA.accessToken, deviceA.refreshToken, xForwardedFor = "198.51.100.64")
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // both tokens of the logged-out session are dead
        assertThat(refresh(deviceA.refreshToken, xForwardedFor = "198.51.100.65").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(logout(deviceA.accessToken, deviceA.refreshToken, xForwardedFor = "198.51.100.66").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        assertThat(sessionRow(sidA)!!["status"]).isEqualTo("revoked")
        assertThat(sessionRow(sidA)!!["revoked_reason"]).isEqualTo("logout")
        assertThat(sessionRow(sidB)!!["status"]).isEqualTo("active")
        val denylistTtl = redisTemplate.getExpire("$DENYLIST_KEY_PREFIX$sidA", TimeUnit.SECONDS)
        assertThat(denylistTtl ?: -1L).isGreaterThan(0).isLessThanOrEqualTo(ACCESS_TTL_SECONDS.toLong())
        assertThat(redisTemplate.hasKey("$DENYLIST_KEY_PREFIX$sidB") ?: false).isFalse()

        assertThat(authEventCount("logout", "patreece")).isEqualTo(1)
        val revokedEvent = authEvent("session_revoked", "patreece")!!
        assertThat(revokedEvent["details"] as String)
            .contains("logout")
            .doesNotContain(deviceA.refreshToken)
            .doesNotContain(deviceB.refreshToken)

        // the parallel session is fully functional and ends with its own logout
        assertThat(logout(deviceB.accessToken, deviceB.refreshToken, xForwardedFor = "198.51.100.67").statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(authEventCount("logout", "patreece")).isEqualTo(2)
    }

    private fun register(
        username: String,
        email: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> = postJson(REGISTER_PATH, mapOf("username" to username, "email" to email), xForwardedFor)

    private fun confirm(token: String): ResponseEntity<String> = postJson(CONFIRM_PATH, mapOf("token" to token))

    private fun setPassword(
        setupToken: String,
        password: String,
        confirmPassword: String,
    ): ResponseEntity<String> =
        postJson(
            SET_PASSWORD_PATH,
            mapOf(
                "setupToken" to setupToken,
                "password" to password,
                "confirmPassword" to confirmPassword,
            ),
        )

    private fun createActiveUser(
        username: String,
        email: String,
        registerIp: String,
    ) {
        val setupToken = registerAndConfirm(username, email, registerIp)
        val response = setPassword(setupToken, PASSWORD, PASSWORD)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    private fun registerAndConfirm(
        username: String,
        email: String,
        xForwardedFor: String? = null,
    ): String {
        register(username, email, xForwardedFor)
        val response = confirm(extractVerificationToken(email))
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["setupToken"].asText()
    }

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

    private fun refresh(
        refreshToken: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> = postJson(REFRESH_PATH, mapOf("refreshToken" to refreshToken), xForwardedFor)

    private fun logout(
        accessToken: String?,
        refreshToken: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                accessToken?.let { set(HttpHeaders.AUTHORIZATION, "Bearer $it") }
                xForwardedFor?.let { set("X-Forwarded-For", it) }
            }
        return restTemplate.postForEntity(
            LOGOUT_PATH,
            HttpEntity(mapOf("refreshToken" to refreshToken), headers),
            String::class.java,
        )
    }

    private fun loginOkPair(
        identifier: String,
        xForwardedFor: String? = null,
    ): Tokens {
        val response = login(identifier, PASSWORD, xForwardedFor)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return Tokens(body["accessToken"].asText(), body["refreshToken"].asText())
    }

    private fun refreshOk(
        refreshToken: String,
        xForwardedFor: String? = null,
    ): Tokens {
        val response = refresh(refreshToken, xForwardedFor)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return Tokens(body["accessToken"].asText(), body["refreshToken"].asText())
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

    private fun averageMillis(
        samples: Int,
        attempt: () -> Unit,
    ): Double {
        val durationsMillis =
            (1..samples).map {
                val start = System.nanoTime()
                attempt()
                (System.nanoTime() - start) / NANOS_PER_MILLI
            }
        return durationsMillis.average()
    }

    private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun userId(username: String): UUID? {
        val ids =
            jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE lower(username) = ?",
                UUID::class.java,
                username.lowercase(),
            )
        return ids.firstOrNull()
    }

    private fun sessionCount(username: String): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM sessions
            WHERE user_id = (SELECT id FROM users WHERE lower(username) = ?)
            """.trimIndent(),
            Int::class.java,
            username.lowercase(),
        )

    private fun sessionRow(sid: UUID): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, user_id, status::text AS status, revoked_reason::text AS revoked_reason, revoked_at
                FROM sessions WHERE id = ?
                """.trimIndent(),
                sid,
            ).firstOrNull()

    private fun sessionIdOf(refreshToken: String): UUID? {
        val ids =
            jdbcTemplate.queryForList(
                "SELECT session_id FROM refresh_tokens WHERE token_hash = ?",
                UUID::class.java,
                sha256Hex(refreshToken),
            )
        return ids.firstOrNull()
    }

    private fun refreshGeneration(refreshToken: String): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, session_id, status::text AS status, token_hash, replaced_by, expires_at
                FROM refresh_tokens WHERE token_hash = ?
                """.trimIndent(),
                sha256Hex(refreshToken),
            ).firstOrNull()

    private fun activeRefreshCount(sid: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE session_id = ? AND status = 'active'",
            Int::class.java,
            sid,
        )

    private fun authEventCount(
        eventType: String,
        username: String,
    ): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM auth_events
            WHERE event_type = ?::auth_event_type
              AND user_id = (SELECT id FROM users WHERE lower(username) = ?)
            """.trimIndent(),
            Int::class.java,
            eventType,
            username.lowercase(),
        )

    private fun authEvent(
        eventType: String,
        username: String,
    ): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT user_id, ip_hash, details::text AS details
                FROM auth_events
                WHERE event_type = ?::auth_event_type
                  AND user_id = (SELECT id FROM users WHERE lower(username) = ?)
                """.trimIndent(),
                eventType,
                username.lowercase(),
            ).firstOrNull()

    private fun nullUserEventCountSince(
        eventType: String,
        since: OffsetDateTime,
    ): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM auth_events
            WHERE event_type = ?::auth_event_type AND user_id IS NULL AND occurred_at >= ?
            """.trimIndent(),
            Int::class.java,
            eventType,
            since,
        )

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private data class Tokens(
        val accessToken: String,
        val refreshToken: String,
    )

    private companion object {
        const val PASSWORD = "Str0ng-Session-IT-Pass!"
        const val ACCESS_TTL_SECONDS = 300
        const val TIMING_SAMPLES = 5
        const val TIMING_TOLERANCE = 3.0
        const val NANOS_PER_MILLI = 1_000_000.0
        const val DENYLIST_KEY_PREFIX = "auth:denylist:sid:"
        const val REGISTER_PATH = "/api/v1/auth/register"
        const val CONFIRM_PATH = "/api/v1/auth/register/confirm"
        const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REFRESH_PATH = "/api/v1/auth/refresh"
        const val LOGOUT_PATH = "/api/v1/auth/logout"
    }
}
