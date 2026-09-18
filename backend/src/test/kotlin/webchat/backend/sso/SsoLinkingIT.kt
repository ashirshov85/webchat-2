package webchat.backend.sso

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.redis.core.StringRedisTemplate
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T033 [US3] (tasks.md Phase 5, Test-First): the manual identity-linking
 * management over the real HTTP port of the `AbstractIntegrationTest` context
 * through the MockIdP (T018) — written BEFORE the implementation (T034–T035)
 * and RED until T039 brings it green (constitution VI).
 *
 * Covered acceptance of US3 (two providers on one MockIdP — `idp-trusted`
 * with `trusted-for-email-linking: true`, `idp-open` without it; manual
 * linking needs neither trust nor an email match, spec Assumptions):
 * - the link flow (US3-1): a Bearer-authenticated user starts
 *   `POST /auth/sso/link/authorize` (a purpose=link flow context carries the
 *   userId, data-model.md §5), passes the full IdP choreography and lands on
 *   `302 /settings/security?linked=<providerId>`; the binding shows up in
 *   `GET /users/me/identities` (provider email, display name from the
 *   config, linkedAt) and immediately works as a login method;
 * - the repeated link flow of the user's OWN identity (FR-012,
 *   data-model.md §7 Link): a no-op success — no duplicate binding, no
 *   second `sso_identity_linked` event;
 * - `identity_taken` (US3-2): an identity owned by another account is
 *   refused with the public code and NO owner details (FR-006);
 * - unlink with a password present (US3-3): 204, then re-entry follows the
 *   first-login rules (FR-003/FR-004) — outside the allowlist →
 *   `email_conflict` with password login intact; a trusted provider with a
 *   matching verified email → auto-linking straight back into the account;
 * - the last-login-method guard (US3-4): unlinking the only binding of a
 *   password-less JIT account answers 409 `last_login_method`;
 * - a provider email different from the account email (US3-5): linking is
 *   allowed, the account email never changes;
 * - sessions survive the unlink (US3-6, clarify 2026-09-16): access and
 *   refresh keep working, `sessions.identity_id` is SET NULL, only NEW logins
 *   through the unlinked provider are blocked;
 * - the concurrent double unlink of a password-less JIT account: exactly one
 *   204 and one 409 `last_login_method` — the race is closed by the DB
 *   trigger of V9, not by app-level checks (data-model.md §6/§8);
 * - parameterized reachability of the NEW Bearer-protected endpoints of
 *   0.3.0 (`GET /users/me/identities`, `POST /auth/sso/link/authorize`) with
 *   an SSO session — on par with password sessions (SC-002).
 *
 * Every linking/unlinking leg asserts the `sso_identity_linked` /
 * `sso_identity_unlinked` journal entries with non-secret markers only
 * (FR-011).
 *
 * The IdP half of the flow runs on a dedicated loopback mount of [MockIdP] —
 * the SsoFlowIT pattern: provider endpoints must be absolute URIs known while
 * `@DynamicPropertySource` binds `sso.providers.*`, before the random
 * application port becomes observable. Test data follows the SessionIT
 * conventions (accounts grown through the real 002 registration API); the
 * 198.51.100.x documentation IPs keep the 002 IP rate-limit buckets out of
 * the picture.
 */
@Suppress("LargeClass") // tasks.md T033 mandates the whole US3 acceptance in this single IT file (SessionIT precedent)
class SsoLinkingIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun resetIdp() {
        mockIdP.reset()
    }

    @Test
    fun `link flow binds the identity and it becomes a working login method`() {
        val userId = seedActivePasswordUser(USERNAME_HANA, EMAIL_HANA)
        val login = passwordLogin(EMAIL_HANA, PASSWORD)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val accessToken = objectMapper.readTree(login.body)["accessToken"].asText()
        mockIdP.setClaims(idpClaims(SUBJECT_HANA, EMAIL_HANA))
        val identitiesBefore = identitiesCount()

        val link = driveLinkFlow(accessToken, TRUSTED_PROVIDER_ID)

        // US3-1 / contracts §3 link branch: 302 to the settings screen with linked=<providerId>
        assertThat(link.spaParameters.getValue(LINKED_PARAMETER)).isEqualTo(TRUSTED_PROVIDER_ID)
        assertThat(link.spaParameters).doesNotContainKey(SSO_ERROR_PARAMETER)

        // the authorization URL of the link flow is a full PKCE/OIDC URL (contract §5 = §2)
        val authorizationParameters = queryParametersOf(link.authorizationUrl)
        assertThat(authorizationParameters.getValue(STATE)).matches(TOKEN_43)
        assertThat(authorizationParameters.getValue(NONCE_CLAIM)).matches(TOKEN_43)
        assertThat(authorizationParameters.getValue(CODE_CHALLENGE)).matches(TOKEN_43)
        assertThat(authorizationParameters.getValue(CODE_CHALLENGE_METHOD)).isEqualTo(S256_METHOD)
        assertThat(link.authorizationUrl).doesNotContain(CODE_VERIFIER)

        // data-model.md §5: purpose=link flow context carries the authenticated user
        val flowJson = redisTemplate.opsForValue().get("$FLOW_KEY_PREFIX${authorizationParameters.getValue(STATE)}")
        assertThat(flowJson)
            .contains("\"providerId\":\"$TRUSTED_PROVIDER_ID\"")
            .contains("\"purpose\":\"LINK\"")
            .contains("\"userId\":\"$userId\"")

        // US3-1: the binding appears in the list with the config display name and the provider email
        val identities = listIdentitiesOk(accessToken)
        assertThat(identities).hasSize(1)
        assertThat(identities[0]["providerId"].asText()).isEqualTo(TRUSTED_PROVIDER_ID)
        assertThat(identities[0]["providerDisplayName"].asText()).isEqualTo(TRUSTED_DISPLAY_NAME)
        assertThat(identities[0]["email"].asText()).isEqualTo(EMAIL_HANA)
        assertThat(identities[0]["linkedAt"].asText()).isNotEmpty

        val identity = identityRow(TRUSTED_PROVIDER_ID, SUBJECT_HANA)!!
        assertThat(identity["user_id"]).isEqualTo(userId)
        assertThat(identity["provider_email"]).isEqualTo(EMAIL_HANA)
        assertThat(identity["provider_email_verified"]).isEqualTo(true)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore + 1)

        // ...and the account email is untouched (spec Assumptions: linking never rewrites it)
        assertThat(userRowByEmail(EMAIL_HANA)!!["email"]).isEqualTo(EMAIL_HANA)

        // US3-1: the freshly linked identity is immediately a login method into the SAME account
        val sso = performSsoLogin(TRUSTED_PROVIDER_ID)
        assertThat(sso.body["user"]["id"].asText()).isEqualTo(userId.toString())
        assertThat(authEventDetails(userId, "sso_identity_linked")).hasSize(1)
        assertThat(authEventDetails(userId, "sso_identity_unlinked")).isEmpty()
    }

    @Test
    fun `repeated link flow of the own identity is a no-op success without a duplicate`() {
        val userId = seedActivePasswordUser(USERNAME_ISA, EMAIL_ISA)
        val accessToken = accessTokenOfPasswordLogin(EMAIL_ISA)
        mockIdP.setClaims(idpClaims(SUBJECT_ISA, EMAIL_ISA))
        driveLinkFlow(accessToken, TRUSTED_PROVIDER_ID)
        assertThat(identitiesOfUser(userId)).hasSize(1)

        // FR-012 / data-model.md §7 Link: re-linking one's OWN identity is a no-op success
        val repeat = driveLinkFlow(accessToken, TRUSTED_PROVIDER_ID)

        assertThat(repeat.spaParameters.getValue(LINKED_PARAMETER)).isEqualTo(TRUSTED_PROVIDER_ID)
        assertThat(repeat.spaParameters).doesNotContainKey(SSO_ERROR_PARAMETER)
        assertThat(identitiesOfUser(userId)).hasSize(1)
        // exactly one journal entry — the no-op leg stays silent (FR-011/FR-012)
        assertThat(authEventDetails(userId, "sso_identity_linked")).hasSize(1)
    }

    @Test
    fun `linking an identity owned by another account answers identity_taken without owner details`() {
        val jackId = seedActivePasswordUser(USERNAME_JACK, EMAIL_JACK)
        val jackToken = accessTokenOfPasswordLogin(EMAIL_JACK)
        mockIdP.setClaims(idpClaims(SUBJECT_JACK, EMAIL_JACK))
        driveLinkFlow(jackToken, TRUSTED_PROVIDER_ID)

        // US3-2 / FR-006: the same (provider, subject) offered by a different account
        seedActivePasswordUser(USERNAME_KATE, EMAIL_KATE)
        val kateToken = accessTokenOfPasswordLogin(EMAIL_KATE)
        val identitiesBefore = identitiesCount()
        val rejected = driveLinkFlow(kateToken, TRUSTED_PROVIDER_ID)

        assertThat(rejected.spaParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(IDENTITY_TAKEN_CODE)
        // no owner disclosure in the redirect: the public code is the whole answer
        assertThat(rejected.spaParameters.keys).containsExactly(SSO_ERROR_PARAMETER)
        assertThat(rejected.spaParameters.values.none { it.contains(USERNAME_JACK) || it.contains(EMAIL_JACK) }).isTrue

        // the identity stays with its owner; Kate got nothing
        assertThat(identityRow(TRUSTED_PROVIDER_ID, SUBJECT_JACK)!!["user_id"]).isEqualTo(jackId)
        assertThat(identitiesOfUser(userIdByEmail(EMAIL_KATE)!!)).isEmpty()
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(authEventDetails(userIdByEmail(EMAIL_KATE)!!, "sso_identity_linked")).isEmpty()
    }

    @Test
    fun `unlink with a password present blocks re-entry outside the allowlist with email_conflict`() {
        val userId = seedActivePasswordUser(USERNAME_LARS, EMAIL_LARS)
        val accessToken = accessTokenOfPasswordLogin(EMAIL_LARS)
        mockIdP.setClaims(idpClaims(SUBJECT_LARS, EMAIL_LARS))
        driveLinkFlow(accessToken, UNTRUSTED_PROVIDER_ID) // manual linking needs no allowlist
        val identityId = identityRow(UNTRUSTED_PROVIDER_ID, SUBJECT_LARS)!!["id"]

        // US3-3 / contract §7: with a password left, the unlink is a plain 204
        val unlink = deleteIdentity(accessToken, identityId.toString())
        assertThat(unlink.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(identityRow(UNTRUSTED_PROVIDER_ID, SUBJECT_LARS)).isNull()
        assertThat(listIdentitiesOk(accessToken)).isEmpty()
        assertThat(authEventDetails(userId, "sso_identity_unlinked")).hasSize(1)

        // FR-004: re-entry is handled by the FIRST-login rules — outside the allowlist → email_conflict
        assertThat(rejectedSsoError(UNTRUSTED_PROVIDER_ID)).isEqualTo(EMAIL_CONFLICT_CODE)
        assertThat(identityRow(UNTRUSTED_PROVIDER_ID, SUBJECT_LARS)).isNull()

        // ...and the password login of the account is untouched
        assertThat(passwordLogin(EMAIL_LARS, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `unlink then trusted provider login auto-links the identity back and logs in`() {
        val userId = seedActivePasswordUser(USERNAME_MIRA, EMAIL_MIRA)
        val accessToken = accessTokenOfPasswordLogin(EMAIL_MIRA)
        mockIdP.setClaims(idpClaims(SUBJECT_MIRA, EMAIL_MIRA))
        driveLinkFlow(accessToken, TRUSTED_PROVIDER_ID)
        val identityId = identityRow(TRUSTED_PROVIDER_ID, SUBJECT_MIRA)!!["id"]
        assertThat(deleteIdentity(accessToken, identityId.toString()).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // US3-3 / FR-003: trusted provider + matching verified email → straight back into the SAME account
        val sso = performSsoLogin(TRUSTED_PROVIDER_ID)

        assertThat(sso.body["user"]["id"].asText()).isEqualTo(userId.toString())
        assertThat(usersCountByEmail(EMAIL_MIRA)).isEqualTo(1)
        assertThat(identityRow(TRUSTED_PROVIDER_ID, SUBJECT_MIRA)!!["user_id"]).isEqualTo(userId)
        assertThat(authEventDetails(userId, "sso_login_success").last()).contains("auto_linked")
        assertThat(authEventDetails(userId, "sso_identity_linked")).hasSize(1) // only the manual leg journaled linking
    }

    @Test
    fun `unlink of the last login method of a jit account answers 409 last_login_method`() {
        mockIdP.setClaims(idpClaims(SUBJECT_NOLL, EMAIL_NOLL))
        val sso = performSsoLogin(TRUSTED_PROVIDER_ID) // JIT: no password, exactly one binding
        val jitUserId = UUID.fromString(sso.body["user"]["id"].asText())

        val identities = listIdentitiesOk(sso.accessToken)
        assertThat(identities).hasSize(1)
        val identityId = identities[0]["id"].asText()

        // US3-4 / contract §7: the guard fires before the delete
        val unlink = deleteIdentity(sso.accessToken, identityId)

        assertThat(unlink.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(unlink.headers.contentType.toString()).contains("application/problem+json")
        val problem = objectMapper.readTree(unlink.body)
        assertThat(problem["errors"]["identity"][0].asText()).isEqualTo(LAST_LOGIN_METHOD_CODE)
        assertThat(problem["detail"].asText()).isNotEmpty

        // the binding is still there — the account keeps its only login method
        assertThat(identityRow(TRUSTED_PROVIDER_ID, SUBJECT_NOLL)!!["user_id"]).isEqualTo(jitUserId)
        assertThat(authEventDetails(jitUserId, "sso_identity_unlinked")).isEmpty()
    }

    @Test
    fun `provider email different from the account email links fine and keeps the account email`() {
        val userId = seedActivePasswordUser(USERNAME_ODA, EMAIL_ODA)
        val accessToken = accessTokenOfPasswordLogin(EMAIL_ODA)
        mockIdP.setClaims(idpClaims(SUBJECT_ODA, PROVIDER_EMAIL_ODA)) // verified, but NOT the account email

        val link = driveLinkFlow(accessToken, UNTRUSTED_PROVIDER_ID)

        // US3-5 (spec Assumptions): control over both sides is proven by the flows themselves
        assertThat(link.spaParameters.getValue(LINKED_PARAMETER)).isEqualTo(UNTRUSTED_PROVIDER_ID)

        val identity = identityRow(UNTRUSTED_PROVIDER_ID, SUBJECT_ODA)!!
        assertThat(identity["user_id"]).isEqualTo(userId)
        assertThat(identity["provider_email"]).isEqualTo(PROVIDER_EMAIL_ODA)
        assertThat(identity["provider_email_verified"]).isEqualTo(true)

        // the account email is never rewritten and no second account appears
        assertThat(userRowByEmail(EMAIL_ODA)!!["email"]).isEqualTo(EMAIL_ODA)
        assertThat(userRowByEmail(PROVIDER_EMAIL_ODA)).isNull()
        assertThat(listIdentitiesOk(accessToken)[0]["email"].asText()).isEqualTo(PROVIDER_EMAIL_ODA)
    }

    @Test
    fun `sessions survive the unlink and only new logins through the provider are blocked`() {
        val userId = seedActivePasswordUser(USERNAME_PERC, EMAIL_PERC)
        val accessToken = accessTokenOfPasswordLogin(EMAIL_PERC)
        mockIdP.setClaims(idpClaims(SUBJECT_PERC, EMAIL_PERC))
        driveLinkFlow(accessToken, UNTRUSTED_PROVIDER_ID)

        // a session opened THROUGH the binding under unlink (US3-6's worst case)
        val sso = performSsoLogin(UNTRUSTED_PROVIDER_ID)
        val sid = sessionIdOf(sso.refreshToken)!!
        val percIdentityId = identityRow(UNTRUSTED_PROVIDER_ID, SUBJECT_PERC)!!["id"].toString()
        assertThat(deleteIdentity(accessToken, percIdentityId).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        // US3-6 / clarify 2026-09-16: the session lives on — access works, refresh rotates
        assertThat(usersMe(sso.accessToken).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(refresh(sso.refreshToken).statusCode).isEqualTo(HttpStatus.OK)
        val session = sessionRow(sid)!!
        assertThat(session["status"]).isEqualTo("active")
        assertThat(session["identity_id"]).isNull() // ON DELETE SET NULL (data-model.md §3)

        // ...while NEW logins through the unlinked provider are blocked by the first-login rules
        assertThat(rejectedSsoError(UNTRUSTED_PROVIDER_ID)).isEqualTo(EMAIL_CONFLICT_CODE)
        assertThat(authEventDetails(userId, "sso_identity_unlinked")).hasSize(1)
    }

    @Test
    fun `parallel unlink of two bindings of a passwordless jit account leaves one 204 and one 409`() {
        mockIdP.setClaims(idpClaims(SUBJECT_QUIN_A, EMAIL_QUIN))
        val sso = performSsoLogin(TRUSTED_PROVIDER_ID) // JIT account: no password
        val jitUserId = UUID.fromString(sso.body["user"]["id"].asText())

        // a second binding of the same password-less account via the link flow
        mockIdP.setClaims(idpClaims(SUBJECT_QUIN_B, EMAIL_QUIN))
        driveLinkFlow(sso.accessToken, UNTRUSTED_PROVIDER_ID)

        val identityIds = identitiesOfUser(jitUserId).map { it["id"].toString() }
        assertThat(identityIds).hasSize(PARALLEL_UNLINK_THREADS)

        // two clients unlink BOTH bindings at the same moment: the app check
        // alone would let both through — the V9 trigger must close the race
        val barrier = CyclicBarrier(PARALLEL_UNLINK_THREADS)
        val futures =
            identityIds.map { identityId ->
                unlinkPool.submit<ResponseEntity<String>> {
                    barrier.await(AWAIT_SECONDS, TimeUnit.SECONDS)
                    deleteIdentity(sso.accessToken, identityId)
                }
            }
        val statuses = futures.map { it.get(AWAIT_SECONDS, TimeUnit.SECONDS).statusCode }

        // exactly one 204 and one 409 last_login_method (US3-4 race, data-model.md §6)
        assertThat(statuses.count { it == HttpStatus.NO_CONTENT }).isEqualTo(1)
        val responses = futures.map { it.get(AWAIT_SECONDS, TimeUnit.SECONDS) }
        val conflict = responses.first { it.statusCode == HttpStatus.CONFLICT }
        val conflictProblem = objectMapper.readTree(conflict.body)["errors"]["identity"][0].asText()
        assertThat(conflictProblem).isEqualTo(LAST_LOGIN_METHOD_CODE)
        assertThat(identitiesOfUser(jitUserId)).hasSize(1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zeroThreeBearerEndpoints")
    fun `sso session reaches every new 0_3_0 bearer-protected endpoint`(endpoint: ProtectedEndpoint) {
        val username =
            endpoint.name
                .filter(Char::isLetterOrDigit)
                .lowercase()
                .take(USERNAME_MAX_LENGTH)
        mockIdP.setClaims(idpClaims("subject-$username", "$username@example.com"))
        val sso = performSsoLogin(TRUSTED_PROVIDER_ID) // a pure SSO session must reach them (SC-002)

        val response =
            when (endpoint.method) {
                HttpMethod.GET ->
                    restTemplate.exchange(
                        endpoint.path,
                        HttpMethod.GET,
                        HttpEntity(null, authHeaders(sso.accessToken)),
                        String::class.java,
                    )
                else ->
                    restTemplate.exchange(
                        endpoint.path,
                        HttpMethod.POST,
                        HttpEntity(mapOf(PROVIDER_ID_FIELD to TRUSTED_PROVIDER_ID), authHeaders(sso.accessToken)),
                        String::class.java,
                    )
            }

        // SC-002: the 0.3.0 Bearer surface is on par for SSO sessions too
        assertThat(response.statusCode).isEqualTo(endpoint.expectedStatus)
    }

    // --- link/login flow driving --------------------------------------------

    /**
     * Runs the whole US3 link choreography: `POST /auth/sso/link/authorize`
     * (Bearer) → IdP authorize redirect → `GET /auth/sso/callback` → the
     * settings-screen redirect; the caller arms [MockIdP.setClaims] first.
     */
    private fun driveLinkFlow(
        accessToken: String,
        providerId: String,
    ): LinkFlowResult {
        val authorizeResponse =
            restTemplate.postForEntity(
                LINK_AUTHORIZE_PATH,
                HttpEntity(mapOf(PROVIDER_ID_FIELD to providerId), authHeaders(accessToken, nextClientIp())),
                String::class.java,
            )
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
        val callbackResponse =
            noRedirectClient.exchange(
                URI.create(callbackUrl),
                HttpMethod.GET,
                HttpEntity<String>(HttpHeaders().apply { set(X_FORWARDED_FOR_HEADER, nextClientIp()) }),
                String::class.java,
            )
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaLocation = callbackResponse.headers.location.toString()
        assertThat(spaLocation).startsWith(SPA_SETTINGS_PREFIX)
        return LinkFlowResult(
            authorizationUrl = authorizationUrl,
            spaParameters = queryParametersOf(spaLocation),
        )
    }

    /** Runs authorize → IdP → callback → token exchange; the caller arms [MockIdP.setClaims] first. */
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
            body = body,
        )
    }

    /** Runs the login-flow callback leg and returns the SPA redirect parameters. */
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

    /** Drives the login flow expecting a rejection and returns the public `sso_error` code. */
    private fun rejectedSsoError(providerId: String): String {
        val spaParameters = driveCallback(providerId)
        assertThat(spaParameters).doesNotContainKey(CODE)
        return spaParameters.getValue(SSO_ERROR_PARAMETER)
    }

    // --- identities CRUD -----------------------------------------------------

    /** `GET /users/me/identities` (contract §6) — parsed entries of a 200 answer. */
    private fun listIdentitiesOk(accessToken: String): List<JsonNode> {
        val response =
            restTemplate.exchange(
                IDENTITIES_PATH,
                HttpMethod.GET,
                HttpEntity(null, authHeaders(accessToken)),
                String::class.java,
            )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val identities = objectMapper.readTree(response.body)["identities"]
        assertThat(identities.isArray).isTrue
        return identities.map { it }
    }

    private fun deleteIdentity(
        accessToken: String,
        identityId: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "$IDENTITIES_PATH/$identityId",
            HttpMethod.DELETE,
            HttpEntity(null, authHeaders(accessToken, nextClientIp())),
            String::class.java,
        )

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
        val clientIp = nextClientIp()
        register(username, email, clientIp)
        val confirmResponse = confirm(confirmTokenFromOutbox(email), clientIp)
        assertThat(confirmResponse.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirmResponse.body)["setupToken"].asText()
        assertThat(setPassword(setupToken, PASSWORD, clientIp).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        return userIdByUsername(username)!!
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

    private fun accessTokenOfPasswordLogin(email: String): String {
        val response = passwordLogin(email, PASSWORD)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["accessToken"].asText()
    }

    private fun refresh(refreshToken: String): ResponseEntity<String> =
        postJson(
            REFRESH_PATH,
            mapOf("refreshToken" to refreshToken),
            nextClientIp(),
        )

    private fun usersMe(accessToken: String): ResponseEntity<String> =
        restTemplate.exchange(
            USERS_ME_PATH,
            HttpMethod.GET,
            HttpEntity(null, authHeaders(accessToken)),
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

    private fun authHeaders(
        accessToken: String? = null,
        xForwardedFor: String? = null,
    ): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accessToken?.let { set(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            xForwardedFor?.let { set(X_FORWARDED_FOR_HEADER, it) }
        }

    /** Verification token of the registration letter (SessionIT convention). */
    private fun confirmTokenFromOutbox(email: String): String {
        val payload = outboxPayload(email)
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payload.orEmpty())
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
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

    private fun identitiesCount(): Int =
        jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM external_identities", Int::class.java)!!

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

    private fun userIdByEmail(email: String): UUID? = userRowByEmail(email)?.get("id") as UUID?

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
            SELECT id, provider_id, subject, provider_email, provider_email_verified, linked_at
            FROM external_identities WHERE user_id = ?
            ORDER BY linked_at
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

    private fun outboxPayload(recipientEmail: String): String? =
        jdbcTemplate
            .queryForList(
                """
                SELECT payload::text FROM email_outbox
                WHERE lower(recipient_email) = ? AND email_type = 'email_verification'::email_type
                ORDER BY created_at DESC
                """.trimIndent(),
                String::class.java,
                recipientEmail.lowercase(),
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

    private data class SsoLoginResult(
        val accessToken: String,
        val refreshToken: String,
        val body: JsonNode,
    )

    private data class LinkFlowResult(
        val authorizationUrl: String,
        val spaParameters: Map<String, String>,
    )

    /**
     * One Bearer-protected operation ADDED by the 0.3.0 contract: the two
     * `bearerAuth` operations of contracts/sso-api.md §5–7 — the SSO/password
     * parity surface of this feature (SC-002).
     */
    data class ProtectedEndpoint(
        val name: String,
        val method: HttpMethod,
        val path: String,
        val expectedStatus: HttpStatus,
    ) {
        override fun toString(): String = name
    }

    companion object {
        /** The MockIdP controller class of T018, driven directly by this suite. */
        private val mockIdP =
            MockIdP(ObjectMapper()).apply {
                registerClient(UNTRUSTED_CLIENT_ID, UNTRUSTED_CLIENT_SECRET)
            }

        /** Fixed pool for the concurrent-unlink race (one client per binding). */
        private val unlinkPool: ExecutorService =
            Executors.newFixedThreadPool(PARALLEL_UNLINK_THREADS) { runnable ->
                Thread(runnable, UNLINK_THREAD_NAME).apply { isDaemon = true }
            }

        /**
         * Loopback mount of the MockIdP whose base URI is known BEFORE the
         * Spring context starts — the precondition for binding
         * `sso.providers.*` to absolute IdP endpoints (SsoFlowIT pattern,
         * research.md §12).
         */
        private val idpServer = LinkingTestIdpLoopbackServer(mockIdP)

        @JvmStatic
        fun zeroThreeBearerEndpoints(): List<ProtectedEndpoint> =
            listOf(
                ProtectedEndpoint("GET /api/v1/users/me/identities", HttpMethod.GET, IDENTITIES_PATH, HttpStatus.OK),
                ProtectedEndpoint(
                    "POST /api/v1/auth/sso/link/authorize",
                    HttpMethod.POST,
                    LINK_AUTHORIZE_PATH,
                    HttpStatus.OK,
                ),
            )

        @DynamicPropertySource
        @JvmStatic
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            // trusted pair — auto-linking allowlist for the US3-3 re-entry legs
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.display-name") { TRUSTED_DISPLAY_NAME }
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.trusted-for-email-linking") { "true" }
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.client-id") { MockIdP.DEFAULT_CLIENT_ID }
            registry.add("sso.providers.$TRUSTED_PROVIDER_ID.client-secret") { MockIdP.DEFAULT_CLIENT_SECRET }
            registerIdpEndpoints(registry, TRUSTED_PROVIDER_ID)
            // outside the allowlist — manual linking still allowed (spec Assumptions)
            registry.add("sso.providers.$UNTRUSTED_PROVIDER_ID.display-name") { UNTRUSTED_DISPLAY_NAME }
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
            unlinkPool.shutdownNow()
        }

        private val ipCounter = AtomicInteger()

        private const val TRUSTED_PROVIDER_ID = "idp-trusted"

        private const val UNTRUSTED_PROVIDER_ID = "idp-open"

        private const val TRUSTED_DISPLAY_NAME = "Trusted IdP"

        private const val UNTRUSTED_DISPLAY_NAME = "Open IdP"

        private const val UNTRUSTED_CLIENT_ID = "webchat-it-link-open"

        private const val UNTRUSTED_CLIENT_SECRET = "mock-secret-link-open"

        private const val PASSWORD = "Str0ng-Linking-IT-Pass!"

        private const val CLIENT_IP_PREFIX = "198.51.100."

        private const val USERNAME_MAX_LENGTH = 32

        private const val TOKEN_43 = "[A-Za-z0-9_-]{43}"

        private const val FLOW_KEY_PREFIX = "sso:flow:"

        private const val EMAIL_CONFLICT_CODE = "email_conflict"

        private const val IDENTITY_TAKEN_CODE = "identity_taken"

        private const val LAST_LOGIN_METHOD_CODE = "last_login_method"

        private const val USERNAME_HANA = "hana"

        private const val USERNAME_ISA = "isa"

        private const val USERNAME_JACK = "jack"

        private const val USERNAME_KATE = "kate"

        private const val USERNAME_LARS = "lars"

        private const val USERNAME_MIRA = "mira"

        private const val USERNAME_ODA = "oda"

        private const val USERNAME_PERC = "perc"

        private const val EMAIL_HANA = "hana@example.com"

        private const val EMAIL_ISA = "isa@example.com"

        private const val EMAIL_JACK = "jack@example.com"

        private const val EMAIL_KATE = "kate@example.com"

        private const val EMAIL_LARS = "lars@example.com"

        private const val EMAIL_MIRA = "mira@example.com"

        private const val EMAIL_NOLL = "noll@example.com"

        private const val EMAIL_ODA = "oda@example.com"

        private const val EMAIL_PERC = "perc@example.com"

        private const val EMAIL_QUIN = "quin@example.com"

        private const val PROVIDER_EMAIL_ODA = "oda.idp@example.net"

        private const val SUBJECT_HANA = "subject-hana-3r7"

        private const val SUBJECT_ISA = "subject-isa-8t2"

        private const val SUBJECT_JACK = "subject-jack-1w4"

        private const val SUBJECT_LARS = "subject-lars-6y9"

        private const val SUBJECT_MIRA = "subject-mira-2q8"

        private const val SUBJECT_NOLL = "subject-noll-5e3"

        private const val SUBJECT_ODA = "subject-oda-9u6"

        private const val SUBJECT_PERC = "subject-perc-4i1"

        private const val SUBJECT_QUIN_A = "subject-quin-a31"

        private const val SUBJECT_QUIN_B = "subject-quin-b75"

        private const val LINK_AUTHORIZE_PATH = "/api/v1/auth/sso/link/authorize"

        private const val AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"

        private const val CALLBACK_PATH = "/api/v1/auth/sso/callback"

        private const val TOKEN_PATH = "/api/v1/auth/sso/token"

        private const val IDENTITIES_PATH = "/api/v1/users/me/identities"

        private const val REGISTER_PATH = "/api/v1/auth/register"

        private const val CONFIRM_PATH = "/api/v1/auth/register/confirm"

        private const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"

        private const val LOGIN_PATH = "/api/v1/auth/login"

        private const val REFRESH_PATH = "/api/v1/auth/refresh"

        private const val USERS_ME_PATH = "/api/v1/users/me"

        private const val PROVIDER_ID_FIELD = "providerId"

        private const val STATE = "state"

        private const val CODE = "code"

        private const val ERROR_PARAMETER = "error"

        private const val SSO_ERROR_PARAMETER = "sso_error"

        private const val LINKED_PARAMETER = "linked"

        private const val NONCE_CLAIM = "nonce"

        private const val CODE_CHALLENGE = "code_challenge"

        private const val CODE_CHALLENGE_METHOD = "code_challenge_method"

        private const val CODE_VERIFIER = "code_verifier"

        private const val S256_METHOD = "S256"

        private const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"

        private const val SPA_CALLBACK_PREFIX = "http://localhost:5173/sso/callback"

        private const val SPA_SETTINGS_PREFIX = "http://localhost:5173/settings/security"

        private const val PARALLEL_UNLINK_THREADS = 2

        private const val UNLINK_THREAD_NAME = "linking-it-unlink"

        private const val AWAIT_SECONDS = 30L
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
private class LinkingTestIdpLoopbackServer(
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

        const val THREAD_NAME = "mock-idp-loopback-linking"

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
