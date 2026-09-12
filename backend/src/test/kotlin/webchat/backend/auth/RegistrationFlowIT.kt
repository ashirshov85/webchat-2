package webchat.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import java.util.UUID

/**
 * US1-1, US1-4 (T013a): POST /api/v1/auth/register — contract №1.
 *
 * Covers: 202 + pending_email_confirmation account + queued verification email;
 * immediate 400 on invalid username/email format (RegisterRequest patterns, contract §4)
 * with no side effects; 409 on username/email taken by an account of ANY status
 * (active and incomplete, incl. partial overlap) with the taken field indicated and
 * without disclosing the holder's status; resumption only on a full (username+email)
 * match with an incomplete registration and without a duplicate account;
 * `register_requested` auth event with user_id and hashed IP, no secrets (FR-013).
 *
 * US1-2, US1-3 (T013b): POST /auth/register/confirm, POST /auth/register/password —
 * contracts №3–4.
 *
 * Covers: confirmation before expiry → setupToken (password_setup, TTL 1 h) with the
 * account moving to awaiting_password; setting a policy-compliant password → active
 * with an Argon2 hash; password policy FR-004 rejections (<8 / >128 chars, empty or
 * whitespace-only, top-list `password123` from the bundled blocklist, equal to
 * username/email) and a confirmPassword mismatch → 400 while the account remains
 * awaiting_password and the setupToken is NOT consumed (retry with a valid password
 * → 204 active); link idempotency FR-012 (reuse of a consumed verification token or
 * setupToken → uniform 400 without new side effects); `email_confirmed` and
 * `password_set` auth events after successful steps, no secrets (FR-013).
 *
 * US1-6 (T013c): POST /auth/register/resend — contract №2.
 *
 * Covers: expired password_setup link → resend → password_setup email with a
 * /set-password link that completes registration (account → active) while the
 * stale setupToken is uniformly rejected; resend cooldown 60 s → 429 with
 * `Retry-After` (60 − elapsed) enforced per account in PG (data-model.md §5) —
 * a source IP change must not bypass it (PG cooldown is the second tier,
 * independent of Redis buckets); the cooldown treats the family
 * `email_verification` ∪ `email_verification_repeat` as a single step: a
 * resend within 60 s of the original registration letter → 429, and a fresh
 * repeat letter restarts the family cooldown.
 */
@Suppress("LargeClass") // tasks.md T013a-T013c mandate the whole US1 flow in this single IT file
class RegistrationFlowIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : AbstractIntegrationTest() {
    @Test
    fun `register returns 202, creates pending account and queues verification email`() {
        val response = register("alice", "alice@example.com")

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val user = userRow("alice")!!
        assertThat(user["status"]).isEqualTo("pending_email_confirmation")
        assertThat(user["password_hash"]).isNull()
        assertThat(user["email_confirmed_at"]).isNull()

        assertThat(outboxCount("alice@example.com", "email_verification")).isEqualTo(1)
        val payload = outboxPayload("alice@example.com", "email_verification")
        assertThat(payload).contains("/confirm-registration")
        assertThat(payload).contains("token=")
    }

    @Test
    fun `register records register_requested event with user id and hashed ip only`() {
        register("eventuser", "eventuser@example.com")

        val event =
            jdbcTemplate.queryForMap(
                """
                SELECT user_id, ip_hash, user_agent, details::text AS details
                FROM auth_events
                WHERE event_type = 'register_requested'
                  AND user_id = (SELECT id FROM users WHERE lower(username) = 'eventuser')
                """.trimIndent(),
            )

        assertThat(event["user_id"]).isEqualTo(userId("eventuser"))
        val ipHash = event["ip_hash"] as String
        assertThat(ipHash).matches("[0-9a-f]{64}")
        assertThat(ipHash).isNotEqualTo("127.0.0.1")
        val details = event["details"] as String
        assertThat(details).doesNotContain("password").doesNotContain("token")
    }

    @Test
    fun `register rejects invalid username format immediately without side effects`() {
        val invalidUsernames =
            listOf(
                "ab",
                "_alice",
                "alice_",
                "ali ce",
                "alice!",
                "a".repeat(33),
            )

        invalidUsernames.forEachIndexed { index, username ->
            val email = "invalid-user-$index@example.com"
            val response = register(username, email)

            assertThat(response.statusCode)
                .overridingErrorMessage("username <%s> must be rejected with 400", username)
                .isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(errorFields(response.body)).contains("username")

            assertThat(userRow(username)).isNull()
            assertThat(outboxCount(email)).isZero()
        }
    }

    @Test
    fun `register rejects invalid email format immediately without side effects`() {
        val invalidEmails =
            listOf(
                "not-an-email",
                "alice@@example.com",
                "alice@example..com",
            )

        invalidEmails.forEachIndexed { index, email ->
            val username = "invalidemail$index"
            val response = register(username, email)

            assertThat(response.statusCode)
                .overridingErrorMessage("email <%s> must be rejected with 400", email)
                .isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(errorFields(response.body)).contains("email")

            assertThat(userRow(username)).isNull()
            assertThat(outboxCount(email)).isZero()
        }
    }

    @Test
    fun `register rejects missing fields without side effects`() {
        val noUsername = register(null, "missing-username@example.com")
        assertThat(noUsername.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorFields(noUsername.body)).contains("username")
        assertThat(outboxCount("missing-username@example.com")).isZero()

        val noEmail = register("missingemail", null)
        assertThat(noEmail.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorFields(noEmail.body)).contains("email")
        assertThat(userRow("missingemail")).isNull()
    }

    @Test
    fun `register returns 409 with field name when username taken by active account`() {
        seedUser("takenactive", "takenactive@example.com", status = "active")

        val response = register("takenactive", "fresh-email@example.com")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(response.body)).containsExactly("username")
        assertNoStatusDisclosure(response)
        assertThat(userRow("fresh-email")).isNull()
        assertThat(outboxCount("fresh-email@example.com")).isZero()
    }

    @Test
    fun `register returns 409 with field name when email taken by active account`() {
        seedUser("takenactive2", "takenactive2@example.com", status = "active")

        val response = register("freshname", "takenactive2@example.com")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(response.body)).containsExactly("email")
        assertNoStatusDisclosure(response)
        assertThat(userRow("freshname")).isNull()
    }

    @Test
    fun `register returns 409 when username taken by incomplete account`() {
        seedUser("pendingbob", "pendingbob@example.com", status = "pending_email_confirmation")

        val response = register("pendingbob", "other-email@example.com")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(response.body)).containsExactly("username")
        assertNoStatusDisclosure(response)
        assertThat(outboxCount("other-email@example.com")).isZero()
    }

    @Test
    fun `register returns 409 when email taken by incomplete account`() {
        seedUser("pendingbob2", "pendingbob2@example.com", status = "pending_email_confirmation")

        val response = register("othername", "pendingbob2@example.com")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(response.body)).containsExactly("email")
        assertNoStatusDisclosure(response)
        assertThat(userRow("othername")).isNull()
    }

    @Test
    fun `register conflict detection is case-insensitive`() {
        seedUser("casetest", "casetest@example.com", status = "active")

        val byUsername = register("CaseTest", "case-insensitive@example.com")
        assertThat(byUsername.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(byUsername.body)).containsExactly("username")

        val byEmail = register("casedifferent", "CaseTest@example.com")
        assertThat(byEmail.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(byEmail.body)).containsExactly("email")
    }

    @Test
    fun `register rejects partial overlap with incomplete registration`() {
        seedUser("partial", "partial@example.com", status = "pending_email_confirmation")

        val sameUsername = register("partial", "fresh-partial@example.com")
        assertThat(sameUsername.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(sameUsername.body)).containsExactly("username")
        assertThat(outboxCount("fresh-partial@example.com")).isZero()

        val sameEmail = register("partialfresh", "partial@example.com")
        assertThat(sameEmail.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(errorFields(sameEmail.body)).containsExactly("email")
        assertThat(userRow("partialfresh")).isNull()
    }

    @Test
    fun `register full match with incomplete registration resumes without duplicate`() {
        val userId = seedUser("resume", "resume@example.com", status = "pending_email_confirmation")
        val oldTokenHash = "ab".repeat(32)
        jdbcTemplate.update(
            """
            INSERT INTO one_time_tokens (id, user_id, purpose, token_hash, expires_at)
            VALUES (?, ?, 'email_verification', ?, now() + interval '24 hours')
            """.trimIndent(),
            UUID.randomUUID(),
            userId,
            oldTokenHash,
        )

        val response = register("resume", "resume@example.com")

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        assertThat(userCount("resume")).isEqualTo(1)
        assertThat(userRow("resume")!!["status"]).isEqualTo("pending_email_confirmation")

        assertThat(outboxCount("resume@example.com", "email_verification")).isEqualTo(2)

        val oldTokenStillUsable =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM one_time_tokens
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > now()
                """.trimIndent(),
                Int::class.java,
                oldTokenHash,
            )
        assertThat(oldTokenStillUsable).isZero()
    }

    @Test
    fun `confirm verification token before expiry returns setup token and moves account to awaiting_password`() {
        register("carol", "carol@example.com")
        val verificationToken = extractVerificationToken("carol@example.com")

        val response = confirm(verificationToken)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val body = objectMapper.readTree(response.body)
        val setupToken = body["setupToken"].asText()
        assertThat(setupToken).matches("[A-Za-z0-9_-]{43}")
        assertThat(body["setupTokenType"].asText()).isEqualTo("password_setup")
        assertThat(body["expiresInSec"].asInt()).isEqualTo(3600)

        val user = userRow("carol")!!
        assertThat(user["status"]).isEqualTo("awaiting_password")
        assertThat(user["email_confirmed_at"]).isNotNull
        assertThat(user["password_hash"]).isNull()

        assertThat(activeTokenCount(userId("carol")!!, "email_verification")).isZero()
        assertThat(tokenHashes(userId("carol")!!, "password_setup"))
            .containsExactly(sha256Hex(setupToken))

        val event = authEvent("email_confirmed", "carol")!!
        assertThat(event["user_id"]).isEqualTo(userId("carol"))
        assertThat(event["details"] as String)
            .doesNotContain(setupToken)
            .doesNotContain(verificationToken)
    }

    @Test
    fun `set password with valid setup token activates account and records password_set event`() {
        val setupToken = registerAndConfirm("dave", "dave@example.com")
        val password = "C0rrect-Horse!42"

        val response = setPassword(setupToken, password, password)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val user = userRow("dave")!!
        assertThat(user["status"]).isEqualTo("active")
        assertThat(user["password_set_at"]).isNotNull
        val passwordHash = user["password_hash"] as String
        assertThat(passwordHash).startsWith("{argon2}")
        assertThat(passwordHash).doesNotContain(password)

        assertThat(activeTokenCount(userId("dave")!!, "password_setup")).isZero()

        val event = authEvent("password_set", "dave")!!
        assertThat(event["user_id"]).isEqualTo(userId("dave"))
        assertThat(event["details"] as String)
            .doesNotContain(setupToken)
            .doesNotContain(password)
    }

    @Test
    fun `set password rejects policy violations without consuming setup token`() {
        val setupToken = registerAndConfirm("edward", "edward@example.com")

        val invalidPasswords =
            listOf(
                "Sh0rt!1",
                "L".repeat(129),
                "",
                "        ",
                "password123",
                "edward",
                "edward@example.com",
            )

        invalidPasswords.forEach { password ->
            val response = setPassword(setupToken, password, password)

            assertThat(response.statusCode)
                .overridingErrorMessage("password <%s> must be rejected with 400", password)
                .isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(errorFields(response.body)).contains("password")

            val user = userRow("edward")!!
            assertThat(user["status"]).isEqualTo("awaiting_password")
            assertThat(user["password_hash"]).isNull()
        }

        assertThat(activeTokenCount(userId("edward")!!, "password_setup")).isEqualTo(1)

        val validPassword = "Edwards-V4lid-Pass!"
        val retry = setPassword(setupToken, validPassword, validPassword)
        assertThat(retry.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(userRow("edward")!!["status"]).isEqualTo("active")
    }

    @Test
    fun `set password rejects mismatched confirmation without consuming setup token`() {
        val setupToken = registerAndConfirm("frank", "frank@example.com")

        val response = setPassword(setupToken, "Franks-V4lid-Pass!", "Franks-D1fferent-Pass!")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorFields(response.body)).contains("confirmPassword")

        val user = userRow("frank")!!
        assertThat(user["status"]).isEqualTo("awaiting_password")
        assertThat(user["password_hash"]).isNull()
        assertThat(activeTokenCount(userId("frank")!!, "password_setup")).isEqualTo(1)

        val validPassword = "Franks-V4lid-Pass!"
        val retry = setPassword(setupToken, validPassword, validPassword)
        assertThat(retry.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(userRow("frank")!!["status"]).isEqualTo("active")
    }

    @Test
    fun `confirm link is single-use and reuse is rejected uniformly`() {
        register("grace", "grace@example.com")
        val verificationToken = extractVerificationToken("grace@example.com")

        assertThat(confirm(verificationToken).statusCode).isEqualTo(HttpStatus.OK)

        val reuse = confirm(verificationToken)

        assertThat(reuse.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(reuse.body)["status"].asInt()).isEqualTo(400)

        assertThat(tokenCount(userId("grace")!!, "password_setup")).isEqualTo(1)
        assertThat(authEventCount("email_confirmed", "grace")).isEqualTo(1)
    }

    @Test
    fun `confirm rejects unknown token with uniform bad request`() {
        val response = confirm("g".repeat(43))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(response.body)["title"]).isNotNull
    }

    @Test
    fun `set password rejects unknown and consumed setup tokens`() {
        val unknown = setPassword("u".repeat(43), "Unknown-T0ken-Pass!", "Unknown-T0ken-Pass!")
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val setupToken = registerAndConfirm("heidi", "heidi@example.com")
        val firstPassword = "Heidis-F1rst-Pass!"
        assertThat(setPassword(setupToken, firstPassword, firstPassword).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)
        val hashAfterFirst = userRow("heidi")!!["password_hash"]

        val reuse = setPassword(setupToken, "Heidis-S3cond-Pass!", "Heidis-S3cond-Pass!")

        assertThat(reuse.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val user = userRow("heidi")!!
        assertThat(user["status"]).isEqualTo("active")
        assertThat(user["password_hash"]).isEqualTo(hashAfterFirst)
        assertThat(authEventCount("password_set", "heidi")).isEqualTo(1)
    }

    @Test
    fun `resend after setup token expiry issues password_setup email and completes registration`() {
        val staleSetupToken = registerAndConfirm("stalelink", "stalelink@example.com")

        // US1-6 trigger: the 1 h password_setup lifetime elapsed before the password was set
        jdbcTemplate.update(
            """
            UPDATE one_time_tokens SET expires_at = now() - interval '1 second'
            WHERE user_id = ? AND purpose = 'password_setup'
            """.trimIndent(),
            userId("stalelink"),
        )

        // password_setup is a cooldown family of its own: the registration letter sent
        // moments ago belongs to the email_verification family and must not throttle this
        val response = resend("stalelink@example.com", xForwardedFor = "198.51.100.10")

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        assertThat(outboxCount("stalelink@example.com", "password_setup")).isEqualTo(1)
        val payload = outboxPayload("stalelink@example.com", "password_setup")!!
        assertThat(payload).contains("/set-password")
        assertThat(payload).contains("token=")

        // only the fresh link stays active; the stale one is dead
        assertThat(activeTokenCount(userId("stalelink")!!, "password_setup")).isEqualTo(1)
        val freshSetupToken = extractSetupToken("stalelink@example.com")
        assertThat(sha256Hex(freshSetupToken)).isIn(tokenHashes(userId("stalelink")!!, "password_setup"))

        val stalePassword = "Stale-L1nk-Pass!"
        assertThat(setPassword(staleSetupToken, stalePassword, stalePassword).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(userRow("stalelink")!!["status"]).isEqualTo("awaiting_password")
        assertThat(userRow("stalelink")!!["password_hash"]).isNull()

        val freshPassword = "Fresh-L1nk-Pass!"
        assertThat(setPassword(freshSetupToken, freshPassword, freshPassword).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        val user = userRow("stalelink")!!
        assertThat(user["status"]).isEqualTo("active")
        assertThat(user["password_hash"] as String).doesNotContain(freshPassword)
        assertThat(activeTokenCount(userId("stalelink")!!, "password_setup")).isZero()
    }

    @Test
    fun `resend within cooldown is rejected with 429 even when source ip changes`() {
        register("cooldownkate", "cooldownkate@example.com")

        // the registration letter itself starts the account cooldown (second tier, PG)
        val response = resend("cooldownkate@example.com", xForwardedFor = "198.51.100.11")

        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        val retryAfter = response.headers.getFirst(HttpHeaders.RETRY_AFTER)
        assertThat(retryAfter).isNotNull
        assertThat(retryAfter!!.toInt()).isBetween(1, 60)
        val problem = objectMapper.readTree(response.body)
        assertThat(problem["status"].asInt()).isEqualTo(429)
        assertThat(problem["title"]).isNotNull

        // rejected without side effects: no repeat letter queued
        assertThat(outboxCount("cooldownkate@example.com")).isEqualTo(1)

        // the cooldown is per account in PG, not per source: a different IP must not bypass it
        val fromOtherIp = resend("cooldownkate@example.com", xForwardedFor = "203.0.113.7")

        assertThat(fromOtherIp.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(fromOtherIp.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNotNull
        assertThat(outboxCount("cooldownkate@example.com")).isEqualTo(1)
    }

    @Test
    fun `resend cooldown treats verification and repeat letters as one family`() {
        register("familyfred", "familyfred@example.com")

        // email_verification ∪ email_verification_repeat is a single cooldown step:
        // a resend within 60 s of the original registration letter is throttled
        assertThat(resend("familyfred@example.com", xForwardedFor = "198.51.100.12").statusCode)
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)

        // cooldown elapsed → resend accepted and the repeat letter queued
        backdateOutbox("familyfred@example.com", "email_verification", seconds = 61)

        val response = resend("familyfred@example.com", xForwardedFor = "198.51.100.12")

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(outboxCount("familyfred@example.com", "email_verification_repeat")).isEqualTo(1)
        val payload = outboxPayload("familyfred@example.com", "email_verification_repeat")!!
        assertThat(payload).contains("/confirm-registration")
        assertThat(payload).contains("token=")

        // the fresh repeat letter restarts the same family cooldown
        assertThat(resend("familyfred@example.com", xForwardedFor = "198.51.100.12").statusCode)
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(outboxCount("familyfred@example.com", "email_verification_repeat")).isEqualTo(1)
    }

    private fun register(
        username: String?,
        email: String?,
    ): ResponseEntity<String> {
        val payload =
            buildMap<String, String> {
                username?.let { put("username", it) }
                email?.let { put("email", it) }
            }
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        return restTemplate.postForEntity(REGISTER_PATH, HttpEntity(payload, headers), String::class.java)
    }

    private fun errorFields(body: String?): Set<String> {
        val problem = objectMapper.readTree(body)
        assertThat(problem.has("title")).isTrue
        assertThat(problem["status"].asInt()).isGreaterThan(0)
        return problem
            .path("errors")
            .fieldNames()
            .asSequence()
            .toSet()
    }

    private fun assertNoStatusDisclosure(response: ResponseEntity<String>) {
        assertThat(response.body)
            .doesNotContain("pending_email_confirmation")
            .doesNotContain("awaiting_password")
    }

    private fun seedUser(
        username: String,
        email: String,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        val active = status == "active"
        jdbcTemplate.update(
            """
            INSERT INTO users (id, username, email, password_hash, status, email_confirmed_at, password_set_at)
            VALUES (?, ?, ?, ?, ?::user_status,
                    CASE WHEN ? THEN now() ELSE NULL END,
                    CASE WHEN ? THEN now() ELSE NULL END)
            """.trimIndent(),
            id,
            username,
            email,
            "{argon2}seed-hash-not-used-for-login".takeIf { active },
            status,
            active,
            active,
        )
        return id
    }

    private fun userRow(username: String): Map<String, Any>? {
        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT id, username, email, password_hash, status::text AS status, email_confirmed_at
                FROM users WHERE lower(username) = ?
                """.trimIndent(),
                username.lowercase(),
            )
        return rows.firstOrNull()
    }

    private fun userCount(username: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE lower(username) = ?",
            Int::class.java,
            username.lowercase(),
        )

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

    private fun confirm(token: String): ResponseEntity<String> = postJson(CONFIRM_PATH, mapOf("token" to token))

    private fun resend(
        email: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                xForwardedFor?.let { set("X-Forwarded-For", it) }
            }
        return restTemplate.postForEntity(
            RESEND_PATH,
            HttpEntity(mapOf("email" to email), headers),
            String::class.java,
        )
    }

    private fun setPassword(
        setupToken: String,
        password: String,
        confirmPassword: String,
    ): ResponseEntity<String> =
        postJson(
            PASSWORD_PATH,
            mapOf(
                "setupToken" to setupToken,
                "password" to password,
                "confirmPassword" to confirmPassword,
            ),
        )

    private fun postJson(
        path: String,
        payload: Map<String, String>,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
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

    private fun extractSetupToken(email: String): String {
        val payload = outboxPayload(email, "password_setup")
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload ?: "")
        assertThat(match)
            .overridingErrorMessage(
                "password_setup email to <%s> must contain a /set-password?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    private fun backdateOutbox(
        recipientEmail: String,
        emailType: String,
        seconds: Long,
    ) {
        jdbcTemplate.update(
            """
            UPDATE email_outbox SET created_at = now() - make_interval(secs => ?::int)
            WHERE lower(recipient_email) = ? AND email_type = ?::email_type
            """.trimIndent(),
            seconds,
            recipientEmail.lowercase(),
            emailType,
        )
    }

    private fun registerAndConfirm(
        username: String,
        email: String,
    ): String {
        register(username, email)
        val response = confirm(extractVerificationToken(email))
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["setupToken"].asText()
    }

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

    private fun tokenCount(
        userId: UUID,
        purpose: String,
    ): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM one_time_tokens
            WHERE user_id = ? AND purpose = ?::one_time_token_purpose
            """.trimIndent(),
            Int::class.java,
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
    ): Map<String, Any>? {
        val events =
            jdbcTemplate.queryForList(
                """
                SELECT user_id, ip_hash, details::text AS details
                FROM auth_events
                WHERE event_type = ?::auth_event_type
                  AND user_id = (SELECT id FROM users WHERE lower(username) = ?)
                """.trimIndent(),
                eventType,
                username.lowercase(),
            )
        return events.firstOrNull()
    }

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val REGISTER_PATH = "/api/v1/auth/register"
        const val RESEND_PATH = "/api/v1/auth/register/resend"
        const val CONFIRM_PATH = "/api/v1/auth/register/confirm"
        const val PASSWORD_PATH = "/api/v1/auth/register/password"
    }
}
