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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * US4-1…US4-5 (T039): POST /api/v1/auth/password-reset, POST /api/v1/auth/
 * password-reset/confirm — contracts №8–9.
 *
 * Covers: the reset request answers 202 uniformly for existing and unknown
 * emails alike — no account disclosure, no letter for unknown recipients
 * (US4-4) — while an existing active account gets a password_reset outbox
 * email with a /reset-password?token=... link whose opaque value is stored
 * only as a SHA-256 hash with a TTL of 1 h (US4-1, data-model.md §2);
 * confirming by a valid link changes the password — the old one answers the
 * uniform 401 "Invalid credentials", the new one logs in — and revokes EVERY
 * session of the user with revoked_reason=password_change, kills all active
 * refresh generations, denylists each sid in Redis (TTL bounded by the
 * access lifetime) and journals password_reset_requested /
 * password_reset_completed + session_revoked{reason=password_change} without
 * secrets (US4-2, FR-010, FR-013); a consumed or expired link is rejected
 * with a uniform 400 suggesting a new link and no side effects (US4-3,
 * FR-012); policy violations (FR-004) and a confirmPassword mismatch answer
 * 400 while the reset link stays consumable — a retry with a valid password
 * over the SAME link succeeds exactly once and still revokes the sessions
 * (US4-5 flood limits are US5/T045a scope and are not exercised here).
 */
@Suppress("LargeClass") // tasks.md T039 mandates the whole US4 flow in this single IT file
class PasswordResetIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : AbstractIntegrationTest() {
    @Test
    fun `reset request answers uniform 202 for existing and unknown emails without disclosure`() {
        createActiveUser("quintella", "quintella@example.com", registerIp = "198.51.100.80")

        val existing = requestPasswordReset("quintella@example.com", xForwardedFor = "198.51.100.81")
        val unknown = requestPasswordReset("no-such-quintella@example.net", xForwardedFor = "198.51.100.82")

        // US4-4 (contract №8): 202 ALWAYS, indistinguishable for both cases
        assertThat(existing.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(existing.body).isEqualTo(unknown.body)
        assertThat(existing.headers.contentType).isEqualTo(unknown.headers.contentType)
        assertThat(existing.body ?: "")
            .doesNotContain("quintella")
            .doesNotContain("active")
            .doesNotContain("pending_email_confirmation")
            .doesNotContain("awaiting_password")

        // the letter is queued only for the real account; the link targets the SPA route
        assertThat(outboxCount("quintella@example.com", "password_reset")).isEqualTo(1)
        assertThat(outboxCount("no-such-quintella@example.net")).isZero
        val payload = outboxPayload("quintella@example.com", "password_reset")!!
        assertThat(payload).contains("/reset-password")
        assertThat(payload).contains("token=")

        // contract №8 400: malformed email is a plain validation error
        val malformed = requestPasswordReset("not-an-email", xForwardedFor = "198.51.100.83")
        assertThat(malformed.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorFields(malformed.body)).contains("email")

        // FR-013: the request is journaled with the user id and hashed IP, no secrets
        val event = authEvent("password_reset_requested", "quintella")!!
        assertThat(event["user_id"]).isEqualTo(userId("quintella"))
        assertThat(event["ip_hash"] as String).matches("[0-9a-f]{64}")
        assertThat(event["details"] as String)
            .doesNotContain(PASSWORD)
            .doesNotContain("quintella@example.com")
    }

    @Test
    fun `reset by valid link changes password and revokes every session with denylist`() {
        createActiveUser("rosalind", "rosalind@example.com", registerIp = "198.51.100.90")

        val deviceA = loginOkPair("rosalind", xForwardedFor = "198.51.100.91")
        val deviceB = loginOkPair("rosalind", xForwardedFor = "198.51.100.92")
        val sidA = sessionIdOf(deviceA.refreshToken)!!
        val sidB = sessionIdOf(deviceB.refreshToken)!!
        assertThat(sidA).isNotEqualTo(sidB)

        val request = requestPasswordReset("rosalind@example.com", xForwardedFor = "198.51.100.93")
        assertThat(request.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val resetToken = extractResetToken("rosalind@example.com")
        val uid = userId("rosalind")!!

        // data-model.md §2: only the SHA-256 hash is persisted, TTL 1 h (FR-010)
        assertThat(sha256Hex(resetToken)).isIn(tokenHashes(uid, "password_reset"))
        assertThat(resetTokenWithinHour(resetToken)).isTrue

        val newPassword = "Rosalinds-N3w-Pass!"
        val confirm = resetConfirm(resetToken, newPassword, newPassword)
        assertThat(confirm.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // US4-2: the old password is dead with the uniform message, the new one works
        val oldPasswordLogin = login("rosalind", PASSWORD, xForwardedFor = "198.51.100.94")
        assertThat(oldPasswordLogin.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(oldPasswordLogin.body)["detail"].asText())
            .isEqualTo("Invalid credentials")
        val renewed = loginOkPair("rosalind", newPassword, xForwardedFor = "198.51.100.95")
        assertThat(sessionIdOf(renewed.refreshToken)).isNotIn(setOf(sidA, sidB))

        // FR-010: EVERY session of the user is revoked with reason password_change
        assertThat(sessionRow(sidA)!!["status"]).isEqualTo("revoked")
        assertThat(sessionRow(sidA)!!["revoked_reason"]).isEqualTo("password_change")
        assertThat(sessionRow(sidB)!!["status"]).isEqualTo("revoked")
        assertThat(sessionRow(sidB)!!["revoked_reason"]).isEqualTo("password_change")
        assertThat(sessionRow(sessionIdOf(renewed.refreshToken)!!)!!["status"]).isEqualTo("active")
        listOf(sidA, sidB).forEach { sid ->
            // FR-010: the pre-reset sessions keep no live refresh generation
            assertThat(activeRefreshCountForSession(sid))
                .overridingErrorMessage("session <%s> must have no active refresh token left", sid)
                .isZero
        }

        // both pre-reset tokens are dead: refresh → uniform 401, access → 401 via sid denylist
        assertThat(refresh(deviceA.refreshToken, xForwardedFor = "198.51.100.96").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(usersMe(deviceB.accessToken).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        listOf(sidA, sidB).forEach { sid ->
            val denylistTtl = redisTemplate.getExpire("$DENYLIST_KEY_PREFIX$sid", TimeUnit.SECONDS)
            assertThat(denylistTtl ?: -1L).isGreaterThan(0).isLessThanOrEqualTo(ACCESS_TTL_SECONDS.toLong())
        }

        // FR-013: completion and one session_revoked{reason=password_change} per session
        assertThat(authEventCount("password_reset_completed", "rosalind")).isEqualTo(1)
        assertThat(authEventCount("session_revoked", "rosalind")).isEqualTo(2)
        val revokedEvent = authEvent("session_revoked", "rosalind")!!
        assertThat(revokedEvent["details"] as String)
            .contains("password_change")
            .doesNotContain(resetToken)
            .doesNotContain(deviceA.refreshToken)
    }

    @Test
    fun `consumed and expired reset links are rejected uniformly with new-link suggestion`() {
        createActiveUser("sylvestra", "sylvestra@example.com", registerIp = "203.0.113.40")

        requestPasswordReset("sylvestra@example.com", xForwardedFor = "203.0.113.41")
        val resetToken = extractResetToken("sylvestra@example.com")
        val newPassword = "Sylvestras-N3w-Pass!"
        assertThat(resetConfirm(resetToken, newPassword, newPassword).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        // US4-3 / FR-012: the consumed link never works again, no side effects
        val reuse = resetConfirm(resetToken, "Sylvestras-0ther-Pass!", "Sylvestras-0ther-Pass!")
        assertThat(reuse.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(reuse.headers.contentType!!.toString()).contains("application/problem+json")
        assertThat(objectMapper.readTree(reuse.body)["detail"].asText()).containsIgnoringCase("new link")
        assertThat(login("sylvestra", newPassword, xForwardedFor = "203.0.113.42").statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(authEventCount("password_reset_completed", "sylvestra")).isEqualTo(1)

        // unknown token value — the exact same uniform 400 (no case distinction, FR-012)
        val unknown = resetConfirm("s".repeat(43), "Sylvestras-Another-Pass!", "Sylvestras-Another-Pass!")
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(reuse.body)["title"].asText())
            .isEqualTo(objectMapper.readTree(unknown.body)["title"].asText())
        assertThat(objectMapper.readTree(reuse.body)["detail"].asText())
            .isEqualTo(objectMapper.readTree(unknown.body)["detail"].asText())

        // US4-3: an expired link is rejected the same way and changes nothing
        createActiveUser("tabitha", "tabitha@example.com", registerIp = "203.0.113.43")
        requestPasswordReset("tabitha@example.com", xForwardedFor = "203.0.113.44")
        val expiredToken = extractResetToken("tabitha@example.com")
        jdbcTemplate.update(
            """
            UPDATE one_time_tokens SET expires_at = now() - interval '1 second'
            WHERE token_hash = ? AND purpose = 'password_reset'
            """.trimIndent(),
            sha256Hex(expiredToken),
        )

        val expired = resetConfirm(expiredToken, "Tabithas-N3w-Pass!", "Tabithas-N3w-Pass!")
        assertThat(expired.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(expired.body)["detail"].asText())
            .isEqualTo(objectMapper.readTree(unknown.body)["detail"].asText())
        assertThat(login("tabitha", PASSWORD, xForwardedFor = "203.0.113.45").statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(authEventCount("password_reset_completed", "tabitha")).isZero
    }

    @Test
    fun `policy violations and confirmation mismatch leave the reset link consumable`() {
        createActiveUser("ulrica", "ulrica@example.com", registerIp = "203.0.113.50")
        val uid = userId("ulrica")!!
        val hashBefore = userRow("ulrica")!!["password_hash"]

        requestPasswordReset("ulrica@example.com", xForwardedFor = "203.0.113.51")
        val resetToken = extractResetToken("ulrica@example.com")

        // FR-004 policy rejections — the same rules as registration (T022a)
        val invalidPasswords =
            listOf(
                "Sh0rt!1",
                "U".repeat(129),
                "",
                "        ",
                "password123",
                "ulrica",
                "ulrica@example.com",
            )
        invalidPasswords.forEach { password ->
            val response = resetConfirm(resetToken, password, password)
            assertThat(response.statusCode)
                .overridingErrorMessage("password <%s> must be rejected with 400", password)
                .isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(errorFields(response.body)).contains("password")
        }

        val mismatch =
            resetConfirm(resetToken, "Ulricas-V4lid-Pass!", "Ulricas-D1fferent-Pass!")
        assertThat(mismatch.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorFields(mismatch.body)).contains("confirmPassword")

        // the link is NOT consumed by failures: password unchanged, one active token left
        assertThat(userRow("ulrica")!!["password_hash"]).isEqualTo(hashBefore)
        assertThat(activeTokenCount(uid, "password_reset")).isEqualTo(1)

        // a retry over the SAME link completes the reset and revokes the sessions
        val sessionBefore = loginOkPair("ulrica", xForwardedFor = "203.0.113.52")
        val sidBefore = sessionIdOf(sessionBefore.refreshToken)!!
        val newPassword = "Ulricas-V4lid-Pass!"
        val retry = resetConfirm(resetToken, newPassword, newPassword)
        assertThat(retry.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(login("ulrica", PASSWORD, xForwardedFor = "203.0.113.53").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(login("ulrica", newPassword, xForwardedFor = "203.0.113.54").statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(sessionRow(sidBefore)!!["revoked_reason"]).isEqualTo("password_change")
        assertThat(activeTokenCount(uid, "password_reset")).isZero
        assertThat(authEventCount("password_reset_completed", "ulrica")).isEqualTo(1)
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

    /** Successful login pair; [password] defaults to the seeded [PASSWORD] and is overridden after a reset. */
    private fun loginOkPair(
        identifier: String,
        password: String = PASSWORD,
        xForwardedFor: String? = null,
    ): Tokens {
        val response = login(identifier, password, xForwardedFor)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return Tokens(body["accessToken"].asText(), body["refreshToken"].asText())
    }

    private fun requestPasswordReset(
        email: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> = postJson(PASSWORD_RESET_PATH, mapOf("email" to email), xForwardedFor)

    private fun resetConfirm(
        token: String,
        password: String,
        confirmPassword: String,
    ): ResponseEntity<String> =
        postJson(
            PASSWORD_RESET_CONFIRM_PATH,
            mapOf(
                "token" to token,
                "password" to password,
                "confirmPassword" to confirmPassword,
            ),
        )

    private fun refresh(
        refreshToken: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> = postJson(REFRESH_PATH, mapOf("refreshToken" to refreshToken), xForwardedFor)

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

    private fun extractVerificationToken(email: String): String {
        val payload = outboxPayload(email, "email_verification")
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload ?: "")
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    private fun extractResetToken(email: String): String {
        val payload = outboxPayload(email, "password_reset")
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload ?: "")
        assertThat(match)
            .overridingErrorMessage(
                "password_reset email to <%s> must contain a /reset-password?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    private fun errorFields(body: String?): Set<String> =
        body
            ?.let(objectMapper::readTree)
            ?.get("errors")
            ?.fieldNames()
            ?.asSequence()
            ?.toSet()
            ?: emptySet()

    private fun userRow(username: String): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, username, email, password_hash, status::text AS status
                FROM users WHERE lower(username) = ?
                """.trimIndent(),
                username.lowercase(),
            ).firstOrNull()

    private fun userId(username: String): UUID? {
        val ids =
            jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE lower(username) = ?",
                UUID::class.java,
                username.lowercase(),
            )
        return ids.firstOrNull()
    }

    private fun outboxCount(
        recipientEmail: String,
        emailType: String? = null,
    ): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM email_outbox
            WHERE lower(recipient_email) = ? AND (?::email_type IS NULL OR email_type = ?::email_type)
            """.trimIndent(),
            Int::class.java,
            recipientEmail.lowercase(),
            emailType,
            emailType,
        )

    private fun outboxPayload(
        recipientEmail: String,
        emailType: String,
    ): String? {
        val payloads =
            jdbcTemplate.queryForList(
                """
                SELECT payload::text FROM email_outbox
                WHERE lower(recipient_email) = ? AND email_type = ?::email_type
                ORDER BY created_at DESC
                """.trimIndent(),
                String::class.java,
                recipientEmail.lowercase(),
                emailType,
            )
        return payloads.firstOrNull()
    }

    private fun sessionIdOf(refreshToken: String): UUID? {
        val ids =
            jdbcTemplate.queryForList(
                "SELECT session_id FROM refresh_tokens WHERE token_hash = ?",
                UUID::class.java,
                sha256Hex(refreshToken),
            )
        return ids.firstOrNull()
    }

    private fun sessionRow(sid: UUID): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, user_id, status::text AS status, revoked_reason::text AS revoked_reason, revoked_at
                FROM sessions WHERE id = ?
                """.trimIndent(),
                sid,
            ).firstOrNull()

    private fun activeRefreshCountForSession(sessionId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM refresh_tokens WHERE session_id = ? AND status = 'active'",
            Int::class.java,
            sessionId,
        )

    private fun tokenHashes(
        userId: UUID,
        purpose: String,
    ): List<String> =
        jdbcTemplate.queryForList(
            """
            SELECT token_hash FROM one_time_tokens
            WHERE user_id = ? AND purpose = ?::one_time_token_purpose
            """.trimIndent(),
            String::class.java,
            userId,
            purpose,
        )

    private fun activeTokenCount(
        userId: UUID,
        purpose: String,
    ): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM one_time_tokens
            WHERE user_id = ? AND purpose = ?::one_time_token_purpose
              AND used_at IS NULL AND expires_at > now()
            """.trimIndent(),
            Int::class.java,
            userId,
            purpose,
        )

    /** The password_reset lifetime is bounded by auth.token.password-reset-ttl = 1 h (research.md §11). */
    private fun resetTokenWithinHour(token: String): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM one_time_tokens
            WHERE token_hash = ? AND purpose = 'password_reset'
              AND expires_at > created_at AND expires_at <= created_at + interval '1 hour'
            """.trimIndent(),
            Int::class.java,
            sha256Hex(token),
        ) == 1

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
                ORDER BY occurred_at DESC
                """.trimIndent(),
                eventType,
                username.lowercase(),
            ).firstOrNull()

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
        const val PASSWORD = "Str0ng-Reset-IT-Pass!"
        const val ACCESS_TTL_SECONDS = 300
        const val DENYLIST_KEY_PREFIX = "auth:denylist:sid:"
        const val REGISTER_PATH = "/api/v1/auth/register"
        const val CONFIRM_PATH = "/api/v1/auth/register/confirm"
        const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REFRESH_PATH = "/api/v1/auth/refresh"
        const val PASSWORD_RESET_PATH = "/api/v1/auth/password-reset"
        const val PASSWORD_RESET_CONFIRM_PATH = "/api/v1/auth/password-reset/confirm"
        const val USERS_ME_PATH = "/api/v1/users/me"
    }
}
