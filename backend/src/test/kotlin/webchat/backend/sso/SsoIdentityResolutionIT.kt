package webchat.backend.sso

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.AbstractIntegrationTest
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * T027 [US2] (tasks.md Phase 4, Test-First): the FIRST-LOGIN identity
 * resolution matrix (data-model.md §7 rows 3–8, research.md §8) over the real
 * HTTP port of the `AbstractIntegrationTest` context through the MockIdP
 * (T018) — written BEFORE the implementation (T028–T030) and RED until T032
 * brings it green (constitution VI). US1 (the existing-identity branch) is
 * covered by SsoFlowIT.
 *
 * Covered acceptance of US2 (three configured providers on one MockIdP —
 * `idp-trusted` and `idp-trusted-b` with `trusted-for-email-linking: true`,
 * `idp-open` without it):
 * - JIT provisioning (US2-1): no account, provider-verified email → an
 *   `active` account with a generated username, `password_hash = NULL`,
 *   `email_confirmed_at = now()` (data-model.md §2), the identity bound, the
 *   session opened `auth_method='sso'`, NO letter queued, `sso_account_created`
 *   + `sso_login_success {resolution: jit}` journaled (FR-011);
 * - JIT through a NON-trusted provider (US2-7): the allowlist gates only
 *   auto-linking, never JIT (FR-004);
 * - auto-linking (US2-2): an active 002 account with the same lower(email),
 *   trusted provider, verified email → login INTO that account, binding added,
 *   no new account, `resolution: auto_linked`, both login methods stay alive;
 * - the email gate (US2-3): `email_verified ≠ true` or no email claim at all →
 *   `sso_error=email_not_verified`, zero accounts/bindings/sessions;
 * - `email_conflict` (US2-3): an active account owns the email, provider
 *   outside the allowlist → refusal with no binding, password login intact;
 * - `registration_incomplete` (US2-4): pending and awaiting_password accounts
 *   are untouched — no activation bypass, no binding (data-model.md §7 row 6);
 * - the uniform 401 (US2-5): password login for a password-less JIT account is
 *   indistinguishable from a wrong password (FR-013, T030);
 * - username collisions (US2-6): the generated username derives from the email
 *   local part and gets a numeric suffix on collision (research.md §7);
 * - two trusted providers returning one confirmed email (spec Edge Cases,
 *   FR-003): both identities land on a single account, a repeated login
 *   creates no duplicate (FR-012);
 * - the password-reset path of a JIT account (FR-013 second part): the 002
 *   recovery letter works, and completing it revokes the LIVE SSO session with
 *   `revoked_reason=password_change` (FR-002) while enabling password login.
 *
 * Every rejection leg asserts the `sso_login_failed` journal entry with the
 * public reason marker and no secrets/tokens in `details` (FR-011).
 *
 * The IdP half of the flow runs on a dedicated loopback mount of [MockIdP] —
 * the SsoFlowIT pattern: provider endpoints must be absolute URIs known while
 * `@DynamicPropertySource` binds `sso.providers.*`, before the random
 * application port becomes observable. Test data follows the SessionIT
 * conventions (accounts grown through the real 002 registration API); the
 * 192.0.2.x documentation IPs keep the 002 IP rate-limit buckets out of the
 * picture.
 */
@Suppress("LargeClass") // tasks.md T027 mandates the whole US2 matrix in this single IT file (SessionIT precedent)
class SsoIdentityResolutionIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun resetIdp() {
        mockIdP.reset()
    }

    @Test
    fun `first login with a verified email and no account creates an active jit account`() {
        mockIdP.setClaims(idpClaims(SUBJECT_BRION, EMAIL_BRION))
        val usersBefore = usersCount()
        val identitiesBefore = identitiesCount()

        val sso = performSsoLogin(TRUSTED_PROVIDER_ID)

        // US2-1: no manual registration — straight into the chat with a session
        assertThat(sso.body["user"]["email"].asText()).isEqualTo(EMAIL_BRION)
        assertThat(sso.body["user"]["username"].asText()).isEqualTo(USERNAME_BRION)

        // data-model.md §2 JIT shape: active at once, no password, provider-confirmed email
        val user = userRowByEmail(EMAIL_BRION)!!
        assertThat(user["status"]).isEqualTo("active")
        assertThat(user["password_hash"]).isNull()
        assertThat(user["email_confirmed_at"]).isNotNull
        assertThat(usersCount()).isEqualTo(usersBefore + 1)

        val identity = identityRow(TRUSTED_PROVIDER_ID, SUBJECT_BRION)!!
        assertThat(identity["user_id"]).isEqualTo(user["id"])
        assertThat(identity["provider_email"]).isEqualTo(EMAIL_BRION)
        assertThat(identity["provider_email_verified"]).isEqualTo(true)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore + 1)

        // T016 anchor: the session records the SSO origin and the binding
        val session = sessionRow(sessionIdOf(sso.refreshToken)!!)!!
        assertThat(session["auth_method"]).isEqualTo("sso")
        assertThat(session["identity_id"]).isEqualTo(identity["id"])

        // spec Assumptions: the confirmation email is never sent — the provider confirmed it
        assertThat(outboxCount(EMAIL_BRION)).isZero

        // FR-011: sso_account_created + sso_login_success {resolution: jit}, no secrets
        val createdDetails = authEventDetails(user["id"] as UUID, "sso_account_created").single()
        val loginDetails = authEventDetails(user["id"] as UUID, "sso_login_success").single()
        assertThat(createdDetails).contains(TRUSTED_PROVIDER_ID)
        assertThat(loginDetails).contains("jit").contains(TRUSTED_PROVIDER_ID)
        listOf(createdDetails, loginDetails).forEach { details ->
            assertThat(details)
                .doesNotContain(sso.accessToken)
                .doesNotContain(sso.refreshToken)
                .doesNotContain(sso.handshakeCode)
                .doesNotContain(MockIdP.DEFAULT_CLIENT_SECRET)
        }
    }

    @Test
    fun `jit provisioning works through a non-trusted provider because the allowlist gates only auto-linking`() {
        mockIdP.setClaims(idpClaims(SUBJECT_NADIA, EMAIL_NADIA))

        val sso = performSsoLogin(UNTRUSTED_PROVIDER_ID)

        // US2-7 / FR-004: trust is required for auto-linking only, never for JIT
        assertThat(sso.body["user"]["email"].asText()).isEqualTo(EMAIL_NADIA)
        val user = userRowByEmail(EMAIL_NADIA)!!
        assertThat(user["status"]).isEqualTo("active")
        assertThat(identityRow(UNTRUSTED_PROVIDER_ID, SUBJECT_NADIA)!!["user_id"]).isEqualTo(user["id"])
        assertThat(authEventDetails(user["id"] as UUID, "sso_account_created")).hasSize(1)
    }

    @Test
    fun `trusted provider with a verified email auto-links the existing active account`() {
        val seededUserId = seedActivePasswordUser(USERNAME_CARLA, EMAIL_CARLA)
        mockIdP.setClaims(idpClaims(SUBJECT_CARLA, EMAIL_CARLA))

        val sso = performSsoLogin(TRUSTED_PROVIDER_ID)

        // US2-2 / FR-003: login into the EXISTING account, a new one is not created
        assertThat(sso.body["user"]["id"].asText()).isEqualTo(seededUserId.toString())
        assertThat(sso.body["user"]["username"].asText()).isEqualTo(USERNAME_CARLA)
        assertThat(usersCountByEmail(EMAIL_CARLA)).isEqualTo(1)
        val user = userRowByEmail(EMAIL_CARLA)!!
        assertThat(user["password_hash"]).isNotNull() // the password remains a login method
        assertThat(identityRow(TRUSTED_PROVIDER_ID, SUBJECT_CARLA)!!["user_id"]).isEqualTo(seededUserId)

        // FR-011: auto_linked resolution; no JIT event for an existing account
        assertThat(authEventDetails(seededUserId, "sso_login_success").single()).contains("auto_linked")
        assertThat(authEventDetails(seededUserId, "sso_account_created")).isEmpty()

        // both login methods of the linked account keep working
        assertThat(passwordLogin(EMAIL_CARLA, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `unverified or missing provider email rejects the flow with email_not_verified and no side effects`() {
        val usersBefore = usersCount()
        val identitiesBefore = identitiesCount()

        // US2-3 / data-model.md §7 row 3: the provider did not confirm the email
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = SUBJECT_MYRA, email = EMAIL_MYRA, emailVerified = false))
        assertThat(rejectedSsoError(TRUSTED_PROVIDER_ID)).isEqualTo(EMAIL_NOT_VERIFIED_CODE)

        // ... and no email claim at all — the same gate (MockIdP omits the claims for null)
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = SUBJECT_NONAME, email = null, emailVerified = true))
        assertThat(rejectedSsoError(TRUSTED_PROVIDER_ID)).isEqualTo(EMAIL_NOT_VERIFIED_CODE)

        // FR-004: no binding, no JIT, no account — row 8 leaves nothing behind
        assertThat(usersCount()).isEqualTo(usersBefore)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(userRowByEmail(EMAIL_MYRA)).isNull()
        assertThat(identityRow(TRUSTED_PROVIDER_ID, SUBJECT_MYRA)).isNull()

        // FR-011: both refusals journaled with the public reason marker and no secrets
        val reasons = authEventReasons("sso_login_failed", EMAIL_NOT_VERIFIED_CODE)
        assertThat(reasons).hasSize(2)
        reasons.forEach { details ->
            assertThat(details)
                .contains(TRUSTED_PROVIDER_ID)
                .doesNotContain(MockIdP.DEFAULT_CLIENT_SECRET)
        }
    }

    @Test
    fun `non-trusted provider with a taken verified email answers email_conflict`() {
        seedActivePasswordUser(USERNAME_DARYL, EMAIL_DARYL)
        val identitiesBefore = identitiesCount()
        mockIdP.setClaims(idpClaims(SUBJECT_DARYL, EMAIL_DARYL))

        // US2-3 / data-model.md §7 row 5: log in by password and link manually instead
        assertThat(rejectedSsoError(UNTRUSTED_PROVIDER_ID)).isEqualTo(EMAIL_CONFLICT_CODE)

        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(identityRow(UNTRUSTED_PROVIDER_ID, SUBJECT_DARYL)).isNull()
        assertThat(passwordLogin(EMAIL_DARYL, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(authEventReasons("sso_login_failed", EMAIL_CONFLICT_CODE)).isNotEmpty
    }

    @Test
    fun `pending and awaiting_password accounts answer registration_incomplete without side effects`() {
        register(USERNAME_PETRA, EMAIL_PETRA, nextClientIp()) // -> pending_email_confirmation
        registerAndConfirm(USERNAME_QUENN, EMAIL_QUENN) // -> awaiting_password

        mockIdP.setClaims(idpClaims(SUBJECT_PETRA, EMAIL_PETRA))
        assertThat(rejectedSsoError(TRUSTED_PROVIDER_ID)).isEqualTo(REGISTRATION_INCOMPLETE_CODE)

        mockIdP.setClaims(idpClaims(SUBJECT_QUENN, EMAIL_QUENN))
        assertThat(rejectedSsoError(TRUSTED_PROVIDER_ID)).isEqualTo(REGISTRATION_INCOMPLETE_CODE)

        // US2-4: the registration states are untouched — no activation bypass, no binding
        val pending = userRowByEmail(EMAIL_PETRA)!!
        assertThat(pending["status"]).isEqualTo("pending_email_confirmation")
        assertThat(pending["email_confirmed_at"]).isNull()
        val awaiting = userRowByEmail(EMAIL_QUENN)!!
        assertThat(awaiting["status"]).isEqualTo("awaiting_password")
        assertThat(identityRow(TRUSTED_PROVIDER_ID, SUBJECT_PETRA)).isNull()
        assertThat(identityRow(TRUSTED_PROVIDER_ID, SUBJECT_QUENN)).isNull()
        assertThat(authEventReasons("sso_login_failed", REGISTRATION_INCOMPLETE_CODE)).hasSize(2)
    }

    @Test
    fun `password login for a jit account answers the uniform 401`() {
        mockIdP.setClaims(idpClaims(SUBJECT_OLPH, EMAIL_OLPH))
        val sso = performSsoLogin(TRUSTED_PROVIDER_ID)
        val jitUsername = sso.body["user"]["username"].asText()

        // US2-5 / FR-013 (T030): no password exists — the uniform wrong-password
        // answer for the email AND the username form, never a 403 and never a
        // hint that this account has no password
        listOf(EMAIL_OLPH, jitUsername).forEach { identifier ->
            val response = passwordLogin(identifier, PASSWORD)
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(objectMapper.readTree(response.body)["detail"].asText()).isEqualTo(INVALID_CREDENTIALS_DETAIL)
        }
    }

    @Test
    fun `a username collision is resolved transparently by the generator`() {
        seedActivePasswordUser(USERNAME_SABLE, EMAIL_SABLE_OWNER) // owns the "sable" username
        mockIdP.setClaims(idpClaims(SUBJECT_SABLE, EMAIL_SABLE))

        val sso = performSsoLogin(TRUSTED_PROVIDER_ID)

        // US2-6 / research.md §7: derived from the email local part, numeric
        // suffix on collision, still inside the 002 username pattern — the
        // user never notices
        val jitUsername = sso.body["user"]["username"].asText()
        assertThat(jitUsername).isNotEqualTo(USERNAME_SABLE)
        assertThat(jitUsername).matches("^$USERNAME_SABLE-[0-9]{1,2}$")
        assertThat(jitUsername).matches(USERNAME_PATTERN)
        assertThat(userRowByEmail(EMAIL_SABLE)!!["username"]).isEqualTo(jitUsername)
    }

    @Test
    fun `two trusted providers with one verified email share a single account without duplicates`() {
        mockIdP.setClaims(idpClaims(SUBJECT_TESSA_A, EMAIL_TESSA))
        val first = performSsoLogin(TRUSTED_PROVIDER_ID)

        // spec Edge Cases / FR-003: the same confirmed email from a second
        // trusted provider lands as another identity on the SAME account
        mockIdP.setClaims(idpClaims(SUBJECT_TESSA_B, EMAIL_TESSA))
        val second = performSsoLogin(TRUSTED_B_PROVIDER_ID)
        assertThat(second.body["user"]["id"].asText()).isEqualTo(first.body["user"]["id"].asText())
        assertThat(usersCountByEmail(EMAIL_TESSA)).isEqualTo(1)

        val accountId = userRowByEmail(EMAIL_TESSA)!!["id"] as UUID
        val bindings = identitiesOfUser(accountId)
        assertThat(bindings.map { it["provider_id"].toString() to it["subject"].toString() })
            .containsExactlyInAnyOrder(
                TRUSTED_PROVIDER_ID to SUBJECT_TESSA_A,
                TRUSTED_B_PROVIDER_ID to SUBJECT_TESSA_B,
            )

        // FR-012: the repeated login through the first provider resolves as the
        // existing identity — no duplicate account, no duplicate binding
        mockIdP.setClaims(idpClaims(SUBJECT_TESSA_A, EMAIL_TESSA))
        val again = performSsoLogin(TRUSTED_PROVIDER_ID)
        assertThat(again.body["user"]["id"].asText()).isEqualTo(first.body["user"]["id"].asText())
        assertThat(usersCountByEmail(EMAIL_TESSA)).isEqualTo(1)
        assertThat(identitiesOfUser(accountId)).hasSize(2)

        // exactly one JIT provisioning; the follow-up logins resolve auto_linked/existing_identity
        assertThat(authEventDetails(accountId, "sso_account_created")).hasSize(1)
        assertThat(authEventDetails(accountId, "sso_login_success")).hasSize(3)
    }

    @Test
    fun `password reset of a jit account revokes its live sso session and enables password login`() {
        mockIdP.setClaims(idpClaims(SUBJECT_VERNE, EMAIL_VERNE))
        val sso = performSsoLogin(TRUSTED_PROVIDER_ID)
        val sid = sessionIdOf(sso.refreshToken)!!

        // second part of FR-013: the 002 recovery flow works for a JIT account — the letter lands in the outbox
        assertThat(requestPasswordReset(EMAIL_VERNE).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(outboxCount(EMAIL_VERNE, PASSWORD_RESET_EMAIL_TYPE)).isEqualTo(1)
        val resetToken = extractResetToken(EMAIL_VERNE)
        assertThat(resetConfirm(resetToken, NEW_PASSWORD).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // FR-002: a password change revokes the whole session — the SSO pair dies with it
        val session = sessionRow(sid)!!
        assertThat(session["status"]).isEqualTo("revoked")
        assertThat(session["revoked_reason"]).isEqualTo("password_change")
        assertThat(refresh(sso.refreshToken).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(usersMe(sso.accessToken).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        // ... and the password becomes a working login method of the same account (FR-013)
        val login = passwordLogin(EMAIL_VERNE, NEW_PASSWORD)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(login.body)["user"]["email"].asText()).isEqualTo(EMAIL_VERNE)
    }

    // --- SSO flow driving ---------------------------------------------------

    /** Runs authorize → IdP → callback; the SPA redirect parameters (code on success, sso_error otherwise). */
    private fun driveCallback(providerId: String): Map<String, String> {
        val authorizeResponse = postJson(AUTHORIZE_PATH, mapOf(PROVIDER_ID_FIELD to providerId), nextClientIp())
        assertThat(authorizeResponse.statusCode).isEqualTo(HttpStatus.OK)
        val authorizationUrl = objectMapper.readTree(authorizeResponse.body)["authorizationUrl"].asText()

        val idpRedirect = noRedirectClient.getForEntity(URI.create(authorizationUrl), String::class.java)
        assertThat(idpRedirect.statusCode).isEqualTo(HttpStatus.FOUND)
        val idpParameters = queryParametersOf(idpRedirect.headers.location.toString())
        assertThat(idpParameters).doesNotContainKey(ERROR_PARAMETER)

        val callbackUrl =
            UriComponentsBuilder
                .fromUriString(rootUri() + CALLBACK_PATH)
                .queryParam(STATE, idpParameters.getValue(STATE))
                .queryParam(CODE, idpParameters.getValue(CODE))
                .build()
                .toUriString()
        // the browser leg carries its own documentation IP — the shared 127.0.0.1
        // callback bucket of other suites never enters the picture
        val callbackResponse =
            noRedirectClient.exchange(
                URI.create(callbackUrl),
                HttpMethod.GET,
                HttpEntity<String>(HttpHeaders().apply { set(X_FORWARDED_FOR_HEADER, nextClientIp()) }),
                String::class.java,
            )
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaLocation = callbackResponse.headers.location.toString()
        assertThat(spaLocation).startsWith(SPA_CALLBACK_PREFIX)
        return queryParametersOf(spaLocation)
    }

    /** The whole US1 choreography including the token exchange; the caller arms [MockIdP.setClaims] first. */
    private fun performSsoLogin(providerId: String): SsoLoginResult {
        val spaParameters = driveCallback(providerId)
        assertThat(spaParameters).doesNotContainKey(SSO_ERROR_PARAMETER)
        val handshakeCode = spaParameters.getValue(CODE)
        assertThat(handshakeCode).matches(TOKEN_43)

        val tokenResponse = postJson(TOKEN_PATH, mapOf(CODE to handshakeCode), nextClientIp())
        assertThat(tokenResponse.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(tokenResponse.body)
        return SsoLoginResult(
            accessToken = body["accessToken"].asText(),
            refreshToken = body["refreshToken"].asText(),
            handshakeCode = handshakeCode,
            body = body,
        )
    }

    /** Drives the flow expecting a rejection and returns the public `sso_error` code. */
    private fun rejectedSsoError(providerId: String): String {
        val spaParameters = driveCallback(providerId)
        assertThat(spaParameters).doesNotContainKey(CODE)
        return spaParameters.getValue(SSO_ERROR_PARAMETER)
    }

    // --- 002 password-side helpers (SessionIT conventions) -----------------

    private fun register(
        username: String,
        email: String,
        xForwardedFor: String,
    ): ResponseEntity<String> = postJson(REGISTER_PATH, mapOf("username" to username, "email" to email), xForwardedFor)

    private fun confirm(
        token: String,
        xForwardedFor: String,
    ): ResponseEntity<String> = postJson(CONFIRM_PATH, mapOf("token" to token), xForwardedFor)

    private fun setPassword(
        setupToken: String,
        password: String,
        xForwardedFor: String,
    ): ResponseEntity<String> =
        postJson(
            SET_PASSWORD_PATH,
            mapOf("password" to password, "confirmPassword" to password, "setupToken" to setupToken),
            xForwardedFor,
        )

    /** Grows an active password user through the real 002 API and returns its id. */
    private fun seedActivePasswordUser(
        username: String,
        email: String,
    ): UUID {
        registerAndConfirm(username, email)
        val setPasswordResponse = setPassword(setupTokenOf(email), PASSWORD, nextClientIp())
        assertThat(setPasswordResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        return userIdByUsername(username)!!
    }

    /** register → confirm: an `awaiting_password` account (002). */
    private fun registerAndConfirm(
        username: String,
        email: String,
    ) {
        register(username, email, nextClientIp())
        val response = confirm(confirmTokenFromOutbox(email), nextClientIp())
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        setupTokens[email] = objectMapper.readTree(response.body)["setupToken"].asText()
    }

    private fun passwordLogin(
        identifier: String,
        password: String,
    ): ResponseEntity<String> =
        postJson(
            LOGIN_PATH,
            mapOf("identifier" to identifier, "password" to password),
            nextClientIp(),
        )

    private fun requestPasswordReset(email: String): ResponseEntity<String> =
        postJson(PASSWORD_RESET_PATH, mapOf("email" to email), nextClientIp())

    private fun resetConfirm(
        token: String,
        newPassword: String,
    ): ResponseEntity<String> =
        postJson(
            PASSWORD_RESET_CONFIRM_PATH,
            mapOf("token" to token, "password" to newPassword, "confirmPassword" to newPassword),
            nextClientIp(),
        )

    private fun refresh(refreshToken: String): ResponseEntity<String> =
        postJson(REFRESH_PATH, mapOf("refreshToken" to refreshToken), nextClientIp())

    private fun usersMe(accessToken: String): ResponseEntity<String> =
        restTemplate.exchange(
            USERS_ME_PATH,
            HttpMethod.GET,
            HttpEntity(null, HttpHeaders().apply { set(HttpHeaders.AUTHORIZATION, "Bearer $accessToken") }),
            String::class.java,
        )

    private fun postJson(
        path: String,
        payload: Map<String, String>,
        xForwardedFor: String,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set(X_FORWARDED_FOR_HEADER, xForwardedFor)
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    /** Verification token of the registration letter (SessionIT convention). */
    private fun confirmTokenFromOutbox(email: String): String {
        val payload = outboxPayload(email, EMAIL_VERIFICATION_TYPE)
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload.orEmpty())
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
                email,
            ).isNotNull()
        return match!!.groupValues[1]
    }

    /** Reset token of the password_reset letter (PasswordResetIT convention). */
    private fun extractResetToken(email: String): String {
        val payload = outboxPayload(email, PASSWORD_RESET_EMAIL_TYPE)
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload.orEmpty())
        assertThat(match)
            .overridingErrorMessage(
                "password_reset email to <%s> must contain a /reset-password?token=... link",
                email,
            ).isNotNull()
        return match!!.groupValues[1]
    }

    /**
     * TestRestTemplate's JDK client follows redirects, which would chase the
     * 302 Locations straight into unreachable redirect targets; the authorize
     * and callback assertions need the raw FOUND responses (SsoFlowIT
     * precedent).
     */
    private val noRedirectClient: RestTemplate =
        RestTemplate(
            object : SimpleClientHttpRequestFactory() {
                override fun prepareConnection(
                    connection: HttpURLConnection,
                    httpMethod: String,
                ) {
                    super.prepareConnection(connection, httpMethod)
                    connection.instanceFollowRedirects = false
                }
            },
        )

    private fun rootUri(): String = restTemplate.rootUri.removeSuffix("/")

    // --- inspection helpers (SessionIT conventions) -------------------------

    private fun queryParametersOf(url: String): Map<String, String> =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .map { (name, values) -> name to URLDecoder.decode(values.first(), StandardCharsets.UTF_8) }
            .toMap()

    private fun usersCount(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Int::class.java)!!

    private fun identitiesCount(): Int {
        val sql = "SELECT COUNT(*) FROM external_identities"
        return jdbcTemplate.queryForObject(sql, Int::class.java)!!
    }

    private fun usersCountByEmail(email: String): Int =
        jdbcTemplate
            .queryForObject(
                "SELECT COUNT(*) FROM users WHERE lower(email) = ?",
                Int::class.java,
                email.lowercase(),
            )!!

    private fun userRowByEmail(email: String): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, username, email, password_hash, status::text AS status, email_confirmed_at
                FROM users WHERE lower(email) = ?
                """.trimIndent(),
                email.lowercase(),
            ).firstOrNull()

    private fun userIdByUsername(username: String): UUID? =
        jdbcTemplate
            .queryForList(
                "SELECT id FROM users WHERE lower(username) = ?",
                UUID::class.java,
                username.lowercase(),
            ).firstOrNull()

    private fun identityRow(
        providerId: String,
        subject: String,
    ): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, user_id, provider_id, subject, provider_email, provider_email_verified
                FROM external_identities WHERE provider_id = ? AND subject = ?
                """.trimIndent(),
                providerId,
                subject,
            ).firstOrNull()

    private fun identitiesOfUser(userId: UUID): List<Map<String, Any>> =
        jdbcTemplate.queryForList(
            """
            SELECT id, provider_id, subject, provider_email, provider_email_verified
            FROM external_identities WHERE user_id = ?
            """.trimIndent(),
            userId,
        )

    private fun sessionRow(sid: UUID): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, user_id, status::text AS status, revoked_reason::text AS revoked_reason, revoked_at,
                       auth_method, identity_id
                FROM sessions WHERE id = ?
                """.trimIndent(),
                sid,
            ).firstOrNull()

    private fun sessionIdOf(refreshToken: String): UUID? =
        jdbcTemplate
            .queryForList(
                "SELECT session_id FROM refresh_tokens WHERE token_hash = ?",
                UUID::class.java,
                sha256Hex(refreshToken),
            ).firstOrNull()

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
    ): String? =
        jdbcTemplate
            .queryForList(
                """
                SELECT payload::text FROM email_outbox
                WHERE lower(recipient_email) = ? AND email_type = ?::email_type
                ORDER BY created_at DESC
                """.trimIndent(),
                String::class.java,
                recipientEmail.lowercase(),
                emailType,
            ).firstOrNull()

    /** `details` payloads of the user's events of the given type (FR-011 inspection). */
    private fun authEventDetails(
        userId: UUID,
        eventType: String,
    ): List<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT details::text AS details FROM auth_events
                WHERE event_type = ?::auth_event_type AND user_id = ?
                ORDER BY occurred_at
                """.trimIndent(),
                String::class.java,
                eventType,
                userId,
            )

    /** `details` payloads of the events carrying the public reason marker, regardless of the user. */
    private fun authEventReasons(
        eventType: String,
        reason: String,
    ): List<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT details::text AS details FROM auth_events
                WHERE event_type = ?::auth_event_type AND details->>'reason' = ?
                ORDER BY occurred_at
                """.trimIndent(),
                String::class.java,
                eventType,
                reason,
            )

    private fun idpClaims(
        subject: String,
        email: String,
    ): MockIdP.ControlledClaims = MockIdP.ControlledClaims(subject = subject, email = email, emailVerified = true)

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun nextClientIp(): String = "$CLIENT_IP_PREFIX${ipCounter.incrementAndGet()}"

    private fun setupTokenOf(email: String): String = setupTokens.getValue(email)

    private data class SsoLoginResult(
        val accessToken: String,
        val refreshToken: String,
        val handshakeCode: String,
        val body: JsonNode,
    )

    companion object {
        /** The MockIdP controller class of T018, driven directly by this suite. */
        private val mockIdP =
            MockIdP(ObjectMapper()).apply {
                registerClient(TRUSTED_B_CLIENT_ID, TRUSTED_B_CLIENT_SECRET)
                registerClient(UNTRUSTED_CLIENT_ID, UNTRUSTED_CLIENT_SECRET)
            }

        /** setupTokens handed out by register/confirm, keyed by email (002 flow memory). */
        private val setupTokens = LinkedHashMap<String, String>()

        /**
         * Loopback mount of the MockIdP whose base URI is known BEFORE the
         * Spring context starts — the precondition for binding
         * `sso.providers.*` to absolute IdP endpoints (SsoFlowIT pattern,
         * research.md §12).
         */
        private val idpServer = SsoTestIdpLoopbackServer(mockIdP)

        @DynamicPropertySource
        @JvmStatic
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            // trusted pair — the allowlist for auto-linking (US2-2, spec Edge Cases)
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.display-name") { "Trusted IdP" }
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.trusted-for-email-linking") { "true" }
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.client-id") { MockIdP.DEFAULT_CLIENT_ID }
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.client-secret") { MockIdP.DEFAULT_CLIENT_SECRET }
            registerIdpEndpoints(registry, TRUSTED_PROVIDER_ID)
            registry.add("sso.providers.$TRUSTED_B_PROVIDER_ID.display-name") { "Trusted IdP B" }
            registry.add("sso.providers.$TRUSTED_B_PROVIDER_ID.trusted-for-email-linking") { "true" }
            registry.add("sso.providers.$TRUSTED_B_PROVIDER_ID.client-id") { TRUSTED_B_CLIENT_ID }
            registry.add("sso.providers.$TRUSTED_B_PROVIDER_ID.client-secret") { TRUSTED_B_CLIENT_SECRET }
            registerIdpEndpoints(registry, TRUSTED_B_PROVIDER_ID)
            // outside the allowlist — JIT still allowed (US2-7), auto-linking is not (US2-3)
            registry.add("sso.providers.$UNTRUSTED_PROVIDER_ID.display-name") { "Open IdP" }
            registry.add("sso.providers.$UNTRUSTED_PROVIDER_ID.client-id") { UNTRUSTED_CLIENT_ID }
            registry.add("sso.providers.$UNTRUSTED_PROVIDER_ID.client-secret") { UNTRUSTED_CLIENT_SECRET }
            registerIdpEndpoints(registry, UNTRUSTED_PROVIDER_ID)
        }

        /** Explicit MockIdP endpoints (research.md §12: tests never use discovery). */
        private fun registerIdpEndpoints(
            registry: DynamicPropertyRegistry,
            providerId: String,
        ) {
            registry.add("sso.providers.$providerId.authorization-uri") { "${idpServer.baseUri}/mock-idp/authorize" }
            registry.add("sso.providers.$providerId.token-uri") { "${idpServer.baseUri}/mock-idp/token" }
            registry.add("sso.providers.$providerId.jwks-uri") { "${idpServer.baseUri}/mock-idp/jwks" }
        }

        @AfterAll
        @JvmStatic
        fun stopIdpServer() {
            idpServer.stop()
        }

        private val ipCounter = AtomicInteger()

        private const val TRUSTED_PROVIDER_ID = "idp-trusted"

        private const val TRUSTED_B_PROVIDER_ID = "idp-trusted-b"

        private const val UNTRUSTED_PROVIDER_ID = "idp-open"

        private const val TRUSTED_B_CLIENT_ID = "webchat-it-b"

        private const val TRUSTED_B_CLIENT_SECRET = "mock-secret-b"

        private const val UNTRUSTED_CLIENT_ID = "webchat-it-open"

        private const val UNTRUSTED_CLIENT_SECRET = "mock-secret-open"

        private const val PASSWORD = "Str0ng-Identity-IT-Pass!"

        private const val NEW_PASSWORD = "Vernes-N3w-Pass!"

        private const val CLIENT_IP_PREFIX = "192.0.2."

        private const val USERNAME_PATTERN = "^[a-zA-Z0-9]([a-zA-Z0-9_.-]{1,30}[a-zA-Z0-9])$"

        private const val TOKEN_43 = "[A-Za-z0-9_-]{43}"

        private const val INVALID_CREDENTIALS_DETAIL = "Invalid credentials"

        private const val EMAIL_NOT_VERIFIED_CODE = "email_not_verified"

        private const val EMAIL_CONFLICT_CODE = "email_conflict"

        private const val REGISTRATION_INCOMPLETE_CODE = "registration_incomplete"

        private const val EMAIL_VERIFICATION_TYPE = "email_verification"

        private const val PASSWORD_RESET_EMAIL_TYPE = "password_reset"

        private const val USERNAME_BRION = "brion"

        private const val USERNAME_CARLA = "carla"

        private const val USERNAME_DARYL = "daryl"

        private const val USERNAME_PETRA = "petra"

        private const val USERNAME_QUENN = "quenn"

        private const val USERNAME_SABLE = "sable"

        private const val EMAIL_BRION = "brion@example.com"

        private const val EMAIL_NADIA = "nadia@example.com"

        private const val EMAIL_CARLA = "carla@example.com"

        private const val EMAIL_MYRA = "myra@example.com"

        private const val EMAIL_DARYL = "daryl@example.com"

        private const val EMAIL_PETRA = "petra@example.com"

        private const val EMAIL_QUENN = "quenn@example.com"

        private const val EMAIL_OLPH = "olph@example.com"

        private const val EMAIL_SABLE = "sable@example.com"

        private const val EMAIL_SABLE_OWNER = "sable.owner@example.net"

        private const val EMAIL_TESSA = "tessa@example.com"

        private const val EMAIL_VERNE = "verne@example.com"

        private const val SUBJECT_BRION = "subject-brion-8c1"

        private const val SUBJECT_NADIA = "subject-nadia-3v7"

        private const val SUBJECT_CARLA = "subject-carla-6k2"

        private const val SUBJECT_MYRA = "subject-myra-9d4"

        private const val SUBJECT_NONAME = "subject-noname-2f8"

        private const val SUBJECT_DARYL = "subject-daryl-5h1"

        private const val SUBJECT_PETRA = "subject-petra-7j9"

        private const val SUBJECT_QUENN = "subject-quenn-4m3"

        private const val SUBJECT_OLPH = "subject-olph-1n6"

        private const val SUBJECT_SABLE = "subject-sable-0p5"

        private const val SUBJECT_TESSA_A = "subject-tessa-a51"

        private const val SUBJECT_TESSA_B = "subject-tessa-b72"

        private const val SUBJECT_VERNE = "subject-verne-3x8"

        private const val AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"

        private const val CALLBACK_PATH = "/api/v1/auth/sso/callback"

        private const val TOKEN_PATH = "/api/v1/auth/sso/token"

        private const val REGISTER_PATH = "/api/v1/auth/register"

        private const val CONFIRM_PATH = "/api/v1/auth/register/confirm"

        private const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"

        private const val LOGIN_PATH = "/api/v1/auth/login"

        private const val REFRESH_PATH = "/api/v1/auth/refresh"

        private const val PASSWORD_RESET_PATH = "/api/v1/auth/password-reset"

        private const val PASSWORD_RESET_CONFIRM_PATH = "/api/v1/auth/password-reset/confirm"

        private const val USERS_ME_PATH = "/api/v1/users/me"

        private const val PROVIDER_ID_FIELD = "providerId"

        private const val STATE = "state"

        private const val CODE = "code"

        private const val ERROR_PARAMETER = "error"

        private const val SSO_ERROR_PARAMETER = "sso_error"

        private const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"

        private const val SPA_CALLBACK_PREFIX = "http://localhost:5173/sso/callback"
    }
}

/**
 * Loopback HTTP mount of the very same [MockIdP] controller class that also
 * runs as the context bean (T018) — the SsoFlowIT pattern verbatim: the
 * production `OidcClient` (T015) needs the IdP at absolute URLs while
 * `AbstractIntegrationTest` boots the application on a RANDOM port that only
 * becomes observable after the context is fully built, too late for the
 * `@DynamicPropertySource` binding of `sso.providers.*`. Requests are
 * translated into [MockHttpServletRequest] and dispatched to the controller
 * methods, responses are translated back — byte-for-byte the same
 * authorize/token/jwks behavior as the in-context bean, without a second
 * Spring context.
 */
private class SsoTestIdpLoopbackServer(
    private val idp: MockIdP,
) {
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(POOL_SIZE) { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), BACKLOG)

    /** Absolute base URI (e.g. `http://127.0.0.1:39291`) known before the Spring context starts. */
    val baseUri: String

    init {
        server.createContext(ROOT_PATH) { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
        baseUri = "http://$LOOPBACK_HOST:${server.address.port}"
    }

    fun stop() {
        server.stop(STOP_DELAY_SECONDS)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val response =
            try {
                route(exchange)
            } catch (cause: Exception) {
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(cause.toString())
            }
        respond(exchange, response)
        exchange.close()
    }

    @Suppress("ReturnCount") // each branch is one routed IdP endpoint
    private fun route(exchange: HttpExchange): ResponseEntity<String> {
        val path = exchange.requestURI.path
        return when {
            exchange.requestMethod == GET_METHOD && path == AUTHORIZE_PATH -> idp.authorize(toServletRequest(exchange))
            exchange.requestMethod == POST_METHOD && path == TOKEN_PATH -> idp.token(toServletRequest(exchange))
            exchange.requestMethod == GET_METHOD && path == JWKS_PATH -> jwksResponse()
            else -> ResponseEntity.notFound().build()
        }
    }

    private fun jwksResponse(): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(idp.jwks())

    private fun toServletRequest(exchange: HttpExchange): MockHttpServletRequest {
        val request = MockHttpServletRequest(exchange.requestMethod, exchange.requestURI.toString())
        exchange.requestHeaders.forEach { (name, values) -> values.forEach { request.addHeader(name, it) } }
        addParametersFrom(request, exchange.requestURI.rawQuery)
        addParametersFrom(request, exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        return request
    }

    /** Splits a raw query/form string into decoded parameters — the IdP reads every input via getParameter. */
    private fun addParametersFrom(
        request: MockHttpServletRequest,
        raw: String?,
    ) {
        if (raw.isNullOrEmpty()) return
        raw.split(AMPERSAND).forEach { pair ->
            val separator = pair.indexOf(EQUALS_SIGN)
            if (separator <= 0) return@forEach
            val name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
            request.addParameter(name, value)
        }
    }

    private fun respond(
        exchange: HttpExchange,
        response: ResponseEntity<String>,
    ) {
        response.headers.location?.let { exchange.responseHeaders.set(HttpHeaders.LOCATION, it.toString()) }
        val body = response.body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        exchange.responseHeaders.set(
            HttpHeaders.CONTENT_TYPE,
            response.headers.contentType?.toString() ?: MediaType.APPLICATION_JSON_VALUE,
        )
        exchange.sendResponseHeaders(
            response.statusCode.value(),
            if (body.isEmpty()) NO_BODY else body.size.toLong(),
        )
        if (body.isNotEmpty()) exchange.responseBody.use { stream -> stream.write(body) }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        const val ROOT_PATH = "/"

        const val AUTHORIZE_PATH = "/mock-idp/authorize"

        const val TOKEN_PATH = "/mock-idp/token"

        const val JWKS_PATH = "/mock-idp/jwks"

        const val THREAD_NAME = "mock-idp-loopback"

        const val POOL_SIZE = 4

        const val BACKLOG = 0

        const val STOP_DELAY_SECONDS = 0

        const val NO_BODY = -1L

        const val GET_METHOD = "GET"

        const val POST_METHOD = "POST"

        const val AMPERSAND = "&"

        const val EQUALS_SIGN = '='
    }
}
