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
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T058 [RED→GREEN] [US6] (tasks.md Phase 9): the `oauth2-userinfo` protocol
 * branch of the SSO login flow over the real HTTP port of the
 * `AbstractIntegrationTest` context through the oauth2 mode of the MockIdP
 * (T055, research.md §21) — the protocol legs themselves (T056/T057) are in
 * place, so this suite pins them GREEN end-to-end (constitution VI: the story
 * is done when its IT is green, stably).
 *
 * Providers under test (one MockIdP instance serves BOTH modes side by side,
 * T055 — exactly the "Yandex next to dex/google" production shape):
 * - `yandexish` — `protocol: oauth2-userinfo`, `pkce: false`,
 *   `email-verified-mode: provider-guaranteed`, Yandex-shaped claim names
 *   `psuid`/`default_email` (research.md §20), `trusted-for-email-linking`:
 *   the full login, the JIT and the auto-linking legs;
 * - `claimish` — `protocol: oauth2-userinfo`, `pkce: true` (default), the
 *   default `claim` verified-mode and default claim names: the PKCE-on
 *   authorization URL and the email gate on `email_verified`;
 * - `oidc-mate` — a regular OIDC provider on the same IdP instance: the
 *   parallel-protocol isolation leg (an oauth2 outage/degradation must never
 *   touch the OIDC provider, and vice versa);
 * - `bad-secret` — an oauth2 provider whose client secret the IdP rejects
 *   (`invalid_client`), the secret-rotation leg of US6;
 * - `vkish` — the VK shape: nested dot-path claims (`user.user_id`),
 *   `client-auth: post`, `token-device-id: true` (the authorize-issued
 *   `device_id` must return at the exchange), claim-mode verified fact.
 *
 * Covered acceptance (research.md §21, tasks.md T058):
 * - the full oauth2 login — authorize → IdP redirect → callback →
 *   `POST /auth/sso/token` — lands in the SAME unified session as US1
 *   (`auth_method='sso'`, `identity_id` bound, `sso_login_success`);
 * - JIT by a `provider-guaranteed` email (no `email_verified` field in the
 *   profile at all — the Yandex shape): an active account, no letter;
 * - the email gate of the `claim` mode: `email_verified: false` → the public
 *   `sso_error=email_not_verified`, zero side effects (FR-004);
 * - the failure matrix — userinfo unavailable (slower than the read cap),
 *   non-2xx, no subject claim, wrong client secret — every leg answers the
 *   single `provider_error` within the 5 s callback deadline (SC-005) and is
 *   journaled as `sso_flow_error` with non-secret markers (FR-011), while the
 *   parallel OIDC provider and the password login keep working (isolation);
 * - the `pkce: false` flow: the authorization URL carries NO
 *   `code_challenge`/`code_challenge_method` (and no `nonce` — the
 *   oauth2-userinfo branch never sends one, research.md §17), the token
 *   exchange goes out without `code_verifier` and still succeeds;
 * - auto-linking by verified email: trusted → login into the existing
 *   account, untrusted → `email_conflict` (data-model.md §7 rows 4–5).
 *
 * The IdP half of each flow runs on a dedicated loopback mount of [MockIdP]
 * routing BOTH endpoint families (the SsoFlowIT pattern): provider endpoints
 * must be absolute URIs known while `@DynamicPropertySource` binds
 * `sso.providers.*`, before the random application port becomes observable.
 * Test data follows the SessionIT conventions (accounts grown through the
 * real 002 registration API); this suite owns the 203.0.113.220+ block of
 * TEST-NET-3 (SsoFlowIT .1+, SsoLinkingIT .100+, SsoResilienceIT .180+) so
 * the shared static Redis of the full `gradlew check` run (the T052 order)
 * never mixes this suite's per-IP SSO buckets with the others.
 */
@Suppress("LargeClass") // tasks.md T058 mandates the whole US6 acceptance in this single IT file (SessionIT precedent)
class SsoOauth2FlowIT(
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
    fun `providers list carries both protocols in configuration order`() {
        val response = restTemplate.getForEntity(PROVIDERS_PATH, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val providers = objectMapper.readTree(response.body)["providers"]
        val ids = providers.map { it["id"].asText() }

        // the contract does not change with the protocol (FR-008): same
        // shape, same configuration order — oauth2 and OIDC side by side
        assertThat(ids).containsExactly(
            YAX_PROVIDER_ID,
            CLAIM_PROVIDER_ID,
            OIDC_PROVIDER_ID,
            BAD_SECRET_PROVIDER_ID,
            VK_PROVIDER_ID,
        )
        providers.forEach { provider ->
            assertThat(provider.has("clientId")).isFalse()
            assertThat(provider.has("clientSecret")).isFalse()
        }
    }

    @Test
    fun `authorize builds the pkce-less oauth2 url without nonce and challenge`() {
        val pkceLess = authorize(YAX_PROVIDER_ID)

        assertThat(pkceLess.statusCode).isEqualTo(HttpStatus.OK)
        val pkceLessUrl = objectMapper.readTree(pkceLess.body)["authorizationUrl"].asText()
        assertThat(URI.create(pkceLessUrl).path).isEqualTo(OAUTH2_AUTHORIZE_PATH)

        // research.md §18: pkce=false sends no challenge at all;
        // research.md §17: the oauth2-userinfo branch never sends a nonce
        val parameters = queryParametersOf(pkceLessUrl)
        assertThat(parameters.getValue(CLIENT_ID)).isEqualTo(YAX_CLIENT_ID)
        assertThat(parameters.getValue(REDIRECT_URI)).isEqualTo(BACKEND_CALLBACK_URL)
        assertThat(parameters.getValue(RESPONSE_TYPE)).isEqualTo("code")
        assertThat(parameters.getValue(STATE)).matches(TOKEN_43)
        assertThat(parameters).doesNotContainKey(CODE_CHALLENGE)
        assertThat(parameters).doesNotContainKey(CODE_CHALLENGE_METHOD)
        assertThat(parameters).doesNotContainKey(NONCE_CLAIM)

        // the flow context keeps its uniform shape (data-model.md §5)
        val flowKey = "$FLOW_KEY_PREFIX${parameters.getValue(STATE)}"
        val flowJson = redisTemplate.opsForValue().get(flowKey)
        assertThat(flowJson).contains("\"providerId\":\"$YAX_PROVIDER_ID\"").contains("\"purpose\":\"LOGIN\"")
        assertThat(redisTemplate.getExpire(flowKey, TimeUnit.SECONDS) ?: -1L)
            .isGreaterThan(0)
            .isLessThanOrEqualTo(FLOW_TTL_SECONDS)

        // the same protocol with pkce=true still carries the S256 challenge
        val pkceFull = authorize(CLAIM_PROVIDER_ID)
        assertThat(pkceFull.statusCode).isEqualTo(HttpStatus.OK)
        val pkceFullUrl = objectMapper.readTree(pkceFull.body)["authorizationUrl"].asText()
        assertThat(URI.create(pkceFullUrl).path).isEqualTo(OAUTH2_AUTHORIZE_PATH)
        val pkceParameters = queryParametersOf(pkceFullUrl)
        assertThat(pkceParameters.getValue(CODE_CHALLENGE)).matches(TOKEN_43)
        assertThat(pkceParameters.getValue(CODE_CHALLENGE_METHOD)).isEqualTo(S256_METHOD)
        // but no nonce in the oauth2 branch either — only OIDC binds one
        assertThat(pkceParameters).doesNotContainKey(NONCE_CLAIM)
    }

    @Test
    fun `full oauth2 login through userinfo lands in the unified sso session`() {
        val seeded = seedActiveUserWithIdentity(USERNAME_OTTO, EMAIL_OTTO, SUBJECT_OTTO)
        // the Yandex-shaped profile: psuid + default_email, NO email_verified
        // field — the provider guarantees the email (research.md §20)
        mockIdP.setUserinfoClaims(yandexProfile(SUBJECT_OTTO, EMAIL_OTTO))

        val sso = performSsoLogin(YAX_PROVIDER_ID)
        val passwordLogin = loginOk(seeded.username)

        // US6: the very same LoginResponse/TokenPair contract as US1
        assertThat(sso.body["tokenType"].asText()).isEqualTo("Bearer")
        assertThat(sso.body["expiresInSec"].asInt()).isEqualTo(ACCESS_TTL_SECONDS)
        assertThat(sso.body["refreshToken"].asText()).matches(TOKEN_43)
        assertThat(sso.body["user"]).isEqualTo(passwordLogin.body["user"])
        assertThat(sso.body["user"]["id"].asText()).isEqualTo(seeded.userId.toString())

        // the unified session (T016): auth_method/identity_id like any SSO login
        val session = sessionRow(sessionIdOf(sso.refreshToken)!!)!!
        assertThat(session["auth_method"]).isEqualTo("sso")
        assertThat(session["identity_id"]).isEqualTo(seeded.identityId)

        // data-model.md §7 row 1: the provider email refreshes on login
        val identity = identityRow(seeded.identityId)!!
        assertThat(identity["provider_email"]).isEqualTo(EMAIL_OTTO)
        assertThat(identity["provider_email_verified"]).isEqualTo(true)

        // FR-011: existing_identity resolution, no secrets in details
        val event = authEvent("sso_login_success", seeded.username)!!
        assertThat(event["user_id"]).isEqualTo(seeded.userId)
        assertThat(event["details"] as String)
            .contains("existing_identity")
            .contains(YAX_PROVIDER_ID)
            .doesNotContain(sso.accessToken)
            .doesNotContain(sso.refreshToken)
            .doesNotContain(sso.handshakeCode)
            .doesNotContain(YAX_CLIENT_SECRET)
    }

    @Test
    fun `provider-guaranteed email drives jit without any letter`() {
        val usersBefore = usersCount()
        mockIdP.setUserinfoClaims(yandexProfile(SUBJECT_PIKE, EMAIL_PIKE))

        val sso = performSsoLogin(YAX_PROVIDER_ID)

        // US6/FR-014: no id_token ever existed — the mapped userinfo claims
        // drive the very same JIT provisioning as the OIDC branch (US2-1)
        assertThat(sso.body["user"]["email"].asText()).isEqualTo(EMAIL_PIKE)
        val user = userRowByEmail(EMAIL_PIKE)!!
        assertThat(user["status"]).isEqualTo("active")
        assertThat(user["password_hash"]).isNull()
        assertThat(user["email_confirmed_at"]).isNotNull
        assertThat(usersCount()).isEqualTo(usersBefore + 1)

        val identity = identityRow(YAX_PROVIDER_ID, SUBJECT_PIKE)!!
        assertThat(identity["user_id"]).isEqualTo(user["id"])
        assertThat(identity["provider_email_verified"]).isEqualTo(true)

        val accountId = user["id"] as UUID
        assertThat(outboxCount(EMAIL_PIKE)).isZero()
        assertThat(authEventDetails(accountId, "sso_account_created")).hasSize(1)
        assertThat(authEventDetails(accountId, "sso_login_success").single()).contains("jit").contains(YAX_PROVIDER_ID)
    }

    @Test
    fun `claim mode gates the email on email_verified`() {
        val usersBefore = usersCount()
        val identitiesBefore = identitiesCount()

        // claim mode + email_verified:false → the FR-004 gate, no side effects
        mockIdP.setUserinfoClaims(claimProfile(SUBJECT_QUINN, EMAIL_QUINN, emailVerified = false))
        assertThat(rejectedSsoError(CLAIM_PROVIDER_ID)).isEqualTo(EMAIL_NOT_VERIFIED_CODE)
        assertThat(usersCount()).isEqualTo(usersBefore)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(handshakeKeyCount()).isZero()
        assertThat(authEventReasons("sso_login_failed", EMAIL_NOT_VERIFIED_CODE)).isNotEmpty

        // claim mode + email_verified:true → the very same flow succeeds
        mockIdP.setUserinfoClaims(claimProfile(SUBJECT_QUINN, EMAIL_QUINN, emailVerified = true))
        val sso = performSsoLogin(CLAIM_PROVIDER_ID)
        assertThat(sso.body["user"]["email"].asText()).isEqualTo(EMAIL_QUINN)
        assertThat(identityRow(CLAIM_PROVIDER_ID, SUBJECT_QUINN)!!["provider_email_verified"]).isEqualTo(true)
    }

    @Test
    fun `vk style nested profile with device binding lands in the unified session`() {
        val usersBefore = usersCount()
        // the VK shape: profile nested under `user` (dot-path claims), the
        // boolean email_verified inside the nest, and the authorize-issued
        // device_id required back at the token exchange (client-auth: post)
        mockIdP.issueDeviceId = true
        mockIdP.setUserinfoClaims(vkProfile(SUBJECT_VK, EMAIL_VK, emailVerified = true))

        val sso = performSsoLogin(VK_PROVIDER_ID)

        // the forwarded device_id satisfied the token exchange — without it
        // the IdP answers invalid_grant and the flow rejects (the next test)
        assertThat(sso.body["user"]["email"].asText()).isEqualTo(EMAIL_VK)
        assertThat(usersCount()).isEqualTo(usersBefore + 1)
        val identity = identityRow(VK_PROVIDER_ID, SUBJECT_VK)!!
        assertThat(identity["provider_email"]).isEqualTo(EMAIL_VK)
        assertThat(identity["provider_email_verified"]).isEqualTo(true)
        val session = sessionRow(sessionIdOf(sso.refreshToken)!!)!!
        assertThat(session["auth_method"]).isEqualTo("sso")
    }

    @Test
    fun `vk flow without the device binding is rejected as provider error`() {
        val usersBefore = usersCount()
        mockIdP.issueDeviceId = true
        mockIdP.setUserinfoClaims(vkProfile(SUBJECT_VK, EMAIL_VK, emailVerified = true))

        // a browser leg that drops the IdP-issued device_id: the token
        // endpoint answers invalid_grant → the single provider_error redirect
        val callbackResponse = callback(driveToCallbackUrl(VK_PROVIDER_ID, includeDeviceId = false))
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaParameters = queryParametersOf(callbackResponse.headers.location.toString())
        assertThat(spaParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR_CODE)
        assertThat(usersCount()).isEqualTo(usersBefore)
        assertThat(handshakeKeyCount()).isZero()
    }

    /**
     * US6 failure matrix (research.md §19/§21): every degraded oauth2 leg —
     * userinfo slower than the read cap, userinfo non-2xx, a profile without
     * the subject claim, a client secret the IdP rejects — ends the flow with
     * the single public `provider_error` inside the 5 s callback deadline
     * (SC-005), journaled as `sso_flow_error` with non-secret markers
     * (FR-011), with zero side effects and the parallel OIDC provider plus
     * the password login untouched (isolation).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("providerFailures")
    fun `a failing oauth2 provider answers provider_error within the deadline while oidc and password keep working`(
        scenario: ProviderFailureScenario,
    ) {
        scenario.arm()
        val tag = scenarioTag(scenario)
        val passwordEmail = "$tag@example.com"
        seedActivePasswordUser(tag, passwordEmail)
        val usersBefore = usersCount()
        val identitiesBefore = identitiesCount()

        val callbackUrl = driveToCallbackUrl(scenario.providerId)
        val (callbackResponse, elapsed) = timed { callback(callbackUrl) }

        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaParameters = queryParametersOf(callbackResponse.headers.location.toString())
        assertThat(spaParameters).doesNotContainKey(CODE)
        assertThat(spaParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR_CODE)

        // SC-005: the degraded leg never holds the user past the deadline
        assertThat(elapsed).isLessThan(CALLBACK_DEADLINE)

        // FR-011: non-secret markers only — the journal row of this provider
        val details = latestFlowErrorDetails(scenario.providerId)!!
        assertThat(details)
            .contains(scenario.providerId)
            .contains(PROVIDER_ERROR_CODE)
            .doesNotContain(scenario.clientSecret)

        // the failed flow leaves nothing behind
        assertThat(usersCount()).isEqualTo(usersBefore)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(handshakeKeyCount()).isZero()

        // isolation: the PARALLEL OIDC provider and the password login work on
        mockIdP.setClaims(oidcClaims("subject-$tag-oidc", "$tag-oidc@example.com"))
        val viaOidc = performSsoLogin(OIDC_PROVIDER_ID)
        assertThat(viaOidc.body["user"]["email"].asText()).isEqualTo("$tag-oidc@example.com")
        assertThat(passwordLogin(passwordEmail, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `the oidc provider works in parallel with the oauth2 providers`() {
        // both directions of the isolation: a healthy oauth2 login and a
        // healthy OIDC login on the SAME IdP instance, back to back
        mockIdP.setUserinfoClaims(yandexProfile(SUBJECT_RIVA, EMAIL_RIVA))
        val viaOauth2 = performSsoLogin(YAX_PROVIDER_ID)

        mockIdP.setClaims(oidcClaims(SUBJECT_SIMON, EMAIL_SIMON))
        val viaOidc = performSsoLogin(OIDC_PROVIDER_ID)

        assertThat(viaOauth2.body["user"]["email"].asText()).isEqualTo(EMAIL_RIVA)
        assertThat(identityRow(YAX_PROVIDER_ID, SUBJECT_RIVA)).isNotNull
        assertThat(viaOidc.body["user"]["email"].asText()).isEqualTo(EMAIL_SIMON)
        assertThat(identityRow(OIDC_PROVIDER_ID, SUBJECT_SIMON)).isNotNull

        // both sessions carry the same SSO origin — the protocol is invisible
        assertThat(sessionRow(sessionIdOf(viaOauth2.refreshToken)!!)!!["auth_method"]).isEqualTo("sso")
        assertThat(sessionRow(sessionIdOf(viaOidc.refreshToken)!!)!!["auth_method"]).isEqualTo("sso")
    }

    @Test
    fun `trusted oauth2 provider auto-links the matching account`() {
        val seededUserId = seedActivePasswordUser(USERNAME_TESS, EMAIL_TESS)
        mockIdP.setUserinfoClaims(yandexProfile(SUBJECT_TESS, EMAIL_TESS))

        val sso = performSsoLogin(YAX_PROVIDER_ID)

        // data-model.md §7 row 4: provider-guaranteed email + allowlist →
        // login INTO the existing account, binding added, password alive
        assertThat(sso.body["user"]["id"].asText()).isEqualTo(seededUserId.toString())
        assertThat(usersCountByEmail(EMAIL_TESS)).isEqualTo(1)
        assertThat(userRowByEmail(EMAIL_TESS)!!["password_hash"]).isNotNull
        assertThat(identityRow(YAX_PROVIDER_ID, SUBJECT_TESS)!!["user_id"]).isEqualTo(seededUserId)
        assertThat(authEventDetails(seededUserId, "sso_login_success").single()).contains("auto_linked")
        assertThat(passwordLogin(EMAIL_TESS, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `untrusted oauth2 provider with a taken verified email answers email_conflict`() {
        val seededUserId = seedActivePasswordUser(USERNAME_URSA, EMAIL_URSA)
        val identitiesBefore = identitiesCount()
        mockIdP.setUserinfoClaims(claimProfile(SUBJECT_URSA, EMAIL_URSA, emailVerified = true))

        // data-model.md §7 row 5: outside the allowlist → no auto-linking
        assertThat(rejectedSsoError(CLAIM_PROVIDER_ID)).isEqualTo(EMAIL_CONFLICT_CODE)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(identityRow(CLAIM_PROVIDER_ID, SUBJECT_URSA)).isNull()
        assertThat(passwordLogin(EMAIL_URSA, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(authEventReasons("sso_login_failed", EMAIL_CONFLICT_CODE)).isNotEmpty
        assertThat(userRowByEmail(EMAIL_URSA)!!["id"]).isEqualTo(seededUserId)
    }

    // --- SSO flow driving ---------------------------------------------------

    /** Runs authorize → IdP redirect and returns the ready-made backend callback URL. */
    private fun driveToCallbackUrl(
        providerId: String,
        includeDeviceId: Boolean = true,
    ): String {
        val authorizeResponse = authorize(providerId)
        assertThat(authorizeResponse.statusCode).isEqualTo(HttpStatus.OK)
        val authorizationUrl = objectMapper.readTree(authorizeResponse.body)["authorizationUrl"].asText()

        val idpRedirect = noRedirectClient.getForEntity(URI.create(authorizationUrl), String::class.java)
        assertThat(idpRedirect.statusCode).isEqualTo(HttpStatus.FOUND)
        val idpParameters = queryParametersOf(idpRedirect.headers.location.toString())
        assertThat(idpParameters).doesNotContainKey(ERROR_PARAMETER)

        val builder =
            UriComponentsBuilder
                .fromHttpUrl(rootUri() + CALLBACK_PATH)
                .queryParam(STATE, idpParameters.getValue(STATE))
                .queryParam(CODE, idpParameters.getValue(CODE))
        // a VK-style IdP appends device_id to its redirect — the honest
        // browser forwards it to the callback leg
        if (includeDeviceId) idpParameters[DEVICE_ID]?.let { builder.queryParam(DEVICE_ID, it) }
        return builder.build().toUriString()
    }

    /** The browser callback leg over the real port — never follows the SPA 302. */
    private fun callback(url: String): ResponseEntity<String> =
        noRedirectClient.exchange(
            URI.create(url),
            HttpMethod.GET,
            HttpEntity<String>(HttpHeaders().apply { set(X_FORWARDED_FOR_HEADER, nextClientIp()) }),
            String::class.java,
        )

    /** The whole choreography including the token exchange; the caller arms the IdP claims first. */
    private fun performSsoLogin(providerId: String): SsoLoginResult {
        val callbackResponse = callback(driveToCallbackUrl(providerId))
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaParameters = queryParametersOf(callbackResponse.headers.location.toString())
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
        val callbackResponse = callback(driveToCallbackUrl(providerId))
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaParameters = queryParametersOf(callbackResponse.headers.location.toString())
        assertThat(spaParameters).doesNotContainKey(CODE)
        return spaParameters.getValue(SSO_ERROR_PARAMETER)
    }

    private fun authorize(providerId: String): ResponseEntity<String> =
        postJson(AUTHORIZE_PATH, mapOf(PROVIDER_ID_FIELD to providerId), nextClientIp())

    private fun <T> timed(block: () -> T): Pair<T, Duration> {
        val start = System.nanoTime()
        val result = block()
        return result to Duration.ofNanos(System.nanoTime() - start)
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
        val clientIp = nextClientIp()
        register(username, email, clientIp)
        val confirmResponse = confirm(confirmTokenFromOutbox(email), clientIp)
        assertThat(confirmResponse.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirmResponse.body)["setupToken"].asText()
        assertThat(setPassword(setupToken, PASSWORD, clientIp).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        return userIdByUsername(username)!!
    }

    /** Grows an active password user and links the oauth2 identity to it (the US1 branch of the oauth2 flow). */
    private fun seedActiveUserWithIdentity(
        username: String,
        email: String,
        subject: String,
    ): SeededUser {
        val userId = seedActivePasswordUser(username, email)
        val identityId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO external_identities
                (id, user_id, provider_id, subject, provider_email, provider_email_verified, linked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            identityId,
            userId,
            YAX_PROVIDER_ID,
            subject,
            "stale-$email",
            false,
            OffsetDateTime.now(),
        )
        return SeededUser(username = username, email = email, userId = userId, identityId = identityId)
    }

    private fun loginOk(username: String): LoginResult {
        val response = postJson(LOGIN_PATH, mapOf("identifier" to username, "password" to PASSWORD), nextClientIp())
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return LoginResult(accessToken = body["accessToken"].asText(), body = body)
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
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payloads.firstOrNull().orEmpty())
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

    private fun usersCount(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Int::class.java)!!

    private fun identitiesCount(): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM external_identities",
            Int::class.java,
        )!!

    private fun usersCountByEmail(email: String): Int =
        jdbcTemplate.queryForObject(
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

    private fun identityRow(identityId: UUID): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT provider_email, provider_email_verified
                FROM external_identities WHERE id = ?
                """.trimIndent(),
                identityId,
            ).firstOrNull()

    private fun sessionRow(sid: UUID): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, user_id, status::text AS status, auth_method, identity_id
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

    private fun handshakeKeyCount(): Int = redisTemplate.keys("$HANDSHAKE_KEY_PREFIX*").orEmpty().size

    private fun outboxCount(recipientEmail: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM email_outbox WHERE lower(recipient_email) = ?",
            Int::class.java,
            recipientEmail.lowercase(),
        )!!

    private fun authEvent(
        eventType: String,
        username: String,
    ): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT user_id, details::text AS details
                FROM auth_events
                WHERE event_type = ?::auth_event_type
                  AND user_id = (SELECT id FROM users WHERE lower(username) = ?)
                """.trimIndent(),
                eventType,
                username.lowercase(),
            ).firstOrNull()

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

    /** `details` payloads of the events carrying the public reason marker, regardless of the user (FR-011). */
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

    /** Details of the last `sso_flow_error` of the provider (FR-011 inspection). */
    private fun latestFlowErrorDetails(providerId: String): String? =
        jdbcTemplate
            .queryForList(
                """
                SELECT details::text AS details FROM auth_events
                WHERE event_type = 'sso_flow_error' AND details->>'provider' = ?
                ORDER BY occurred_at DESC
                """.trimIndent(),
                String::class.java,
                providerId,
            ).firstOrNull()

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun nextClientIp(): String = "$CLIENT_IP_PREFIX${ipCounter.incrementAndGet()}"

    private fun scenarioTag(scenario: ProviderFailureScenario): String {
        // the "oa2" prefix keeps the seeded accounts clear of the equally
        // named scenario tags of SsoResilienceIT — one Postgres for all IT
        // classes of a full run, usernames are global
        val tag =
            scenario.name
                .filter(Char::isLetterOrDigit)
                .lowercase()
                .take(USERNAME_MAX_LENGTH)
        return "oa2$tag"
    }

    /** The Yandex-shaped userinfo profile: psuid + default_email, no verified field (research.md §20). */
    private fun yandexProfile(
        subject: String?,
        email: String,
    ): MockIdP.UserinfoClaims = MockIdP.UserinfoClaims(subject = subject, email = email, emailVerified = null)

    /** The claim-mode profile: default claim names, the verified fact from `email_verified`. */
    private fun claimProfile(
        subject: String,
        email: String,
        emailVerified: Boolean,
    ): MockIdP.UserinfoClaims =
        MockIdP.UserinfoClaims(
            subjectClaim = CLAIM_SUBJECT_CLAIM,
            emailClaim = CLAIM_EMAIL_CLAIM,
            subject = subject,
            email = email,
            emailVerified = emailVerified,
        )

    /** The VK-shaped profile: everything nested under `user`, VK field names. */
    private fun vkProfile(
        subject: String,
        email: String,
        emailVerified: Boolean,
    ): MockIdP.UserinfoClaims =
        MockIdP.UserinfoClaims(
            subjectClaim = VK_SUBJECT_CLAIM,
            emailClaim = VK_EMAIL_CLAIM,
            subject = subject,
            email = email,
            emailVerified = emailVerified,
            nestUnder = VK_PROFILE_NEST,
        )

    private fun oidcClaims(
        subject: String,
        email: String,
    ): MockIdP.ControlledClaims = MockIdP.ControlledClaims(subject = subject, email = email, emailVerified = true)

    private data class SeededUser(
        val username: String,
        val email: String,
        val userId: UUID,
        val identityId: UUID,
    )

    private data class LoginResult(
        val accessToken: String,
        val body: JsonNode,
    )

    private data class SsoLoginResult(
        val accessToken: String,
        val refreshToken: String,
        val handshakeCode: String,
        val body: JsonNode,
    )

    /** One US6 failure shape: the provider, its secret (for the no-leak assert) and the arming step. */
    data class ProviderFailureScenario(
        val name: String,
        val providerId: String,
        val clientSecret: String,
        val arm: () -> Unit,
    ) {
        override fun toString(): String = name
    }

    companion object {
        /** The MockIdP controller class of T018/T055, driven directly by this suite in BOTH modes. */
        private val mockIdP =
            MockIdP(ObjectMapper()).apply {
                registerClient(YAX_CLIENT_ID, YAX_CLIENT_SECRET)
                registerClient(CLAIM_CLIENT_ID, CLAIM_CLIENT_SECRET)
                registerClient(OIDC_CLIENT_ID, OIDC_CLIENT_SECRET)
                registerClient(VK_CLIENT_ID, VK_CLIENT_SECRET)
            }

        /**
         * Loopback mount of the MockIdP routing BOTH endpoint families (OIDC
         * and oauth2, T055) — its base URI is known BEFORE the Spring context
         * starts, the precondition for binding `sso.providers.*` to absolute
         * IdP endpoints (SsoFlowIT pattern, research.md §12/§21).
         */
        private val idpServer = SsoOauth2IdpLoopbackServer(mockIdP)

        @JvmStatic
        fun providerFailures(): List<ProviderFailureScenario> =
            listOf(
                ProviderFailureScenario(
                    name = "userinfo is slower than the read timeout",
                    providerId = YAX_PROVIDER_ID,
                    clientSecret = YAX_CLIENT_SECRET,
                    arm = { mockIdP.userinfoFailure = MockIdP.UserinfoFailure.UNAVAILABLE },
                ),
                ProviderFailureScenario(
                    name = "userinfo answers non-2xx",
                    providerId = YAX_PROVIDER_ID,
                    clientSecret = YAX_CLIENT_SECRET,
                    arm = { mockIdP.userinfoFailure = MockIdP.UserinfoFailure.NOT_2XX },
                ),
                ProviderFailureScenario(
                    name = "userinfo profile carries no subject claim",
                    providerId = YAX_PROVIDER_ID,
                    clientSecret = YAX_CLIENT_SECRET,
                    arm = { mockIdP.setUserinfoClaims(yandexFailureProfile()) },
                ),
                ProviderFailureScenario(
                    name = "client secret is wrong",
                    providerId = BAD_SECRET_PROVIDER_ID,
                    clientSecret = BAD_CLIENT_SECRET,
                    arm = {},
                ),
            )

        /**
         * A profile without the `psuid` field — «нет subject-клейма»
         * (research.md §21): the Yandex-shaped claim names with the subject
         * omitted from the JSON entirely.
         */
        private fun yandexFailureProfile(): MockIdP.UserinfoClaims =
            MockIdP.UserinfoClaims(
                subjectClaim = YAX_SUBJECT_CLAIM,
                emailClaim = YAX_EMAIL_CLAIM,
                subject = null,
                email = EMAIL_OTTO,
                emailVerified = null,
            )

        @DynamicPropertySource
        @JvmStatic
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            // US6 (research.md §20): the Yandex-shaped provider — no issuer,
            // no jwks, pkce off, provider-guaranteed email
            registry.add("sso.providers.$YAX_PROVIDER_ID.display-name") { "Yandex-ish" }
            registry.add("sso.providers.$YAX_PROVIDER_ID.trusted-for-email-linking") { "true" }
            registry.add("sso.providers.$YAX_PROVIDER_ID.protocol") { "oauth2-userinfo" }
            registry.add("sso.providers.$YAX_PROVIDER_ID.pkce") { "false" }
            registry.add("sso.providers.$YAX_PROVIDER_ID.subject-claim") { YAX_SUBJECT_CLAIM }
            registry.add("sso.providers.$YAX_PROVIDER_ID.email-claim") { YAX_EMAIL_CLAIM }
            registry.add("sso.providers.$YAX_PROVIDER_ID.email-verified-mode") { "provider-guaranteed" }
            registry.add("sso.providers.$YAX_PROVIDER_ID.client-id") { YAX_CLIENT_ID }
            registry.add("sso.providers.$YAX_PROVIDER_ID.client-secret") { YAX_CLIENT_SECRET }
            registerOauth2Endpoints(registry, YAX_PROVIDER_ID)

            // the same protocol with the defaults: pkce on, claim mode,
            // default claim names — outside the auto-linking allowlist
            registry.add("sso.providers.$CLAIM_PROVIDER_ID.display-name") { "Claim-ish" }
            registry.add("sso.providers.$CLAIM_PROVIDER_ID.protocol") { "oauth2-userinfo" }
            registry.add("sso.providers.$CLAIM_PROVIDER_ID.client-id") { CLAIM_CLIENT_ID }
            registry.add("sso.providers.$CLAIM_PROVIDER_ID.client-secret") { CLAIM_CLIENT_SECRET }
            registerOauth2Endpoints(registry, CLAIM_PROVIDER_ID)

            // the parallel OIDC provider on the same IdP — the isolation leg
            registry.add("sso.providers.$OIDC_PROVIDER_ID.display-name") { "OIDC Mate" }
            registry.add("sso.providers.$OIDC_PROVIDER_ID.client-id") { OIDC_CLIENT_ID }
            registry.add("sso.providers.$OIDC_PROVIDER_ID.client-secret") { OIDC_CLIENT_SECRET }
            registry.add("sso.providers.$OIDC_PROVIDER_ID.authorization-uri") {
                "${idpServer.baseUri}$OIDC_AUTHORIZE_PATH"
            }
            registry.add("sso.providers.$OIDC_PROVIDER_ID.token-uri") { "${idpServer.baseUri}$OIDC_TOKEN_PATH" }
            registry.add("sso.providers.$OIDC_PROVIDER_ID.jwks-uri") { "${idpServer.baseUri}$OIDC_JWKS_PATH" }

            // the secret the IdP rejects — invalid_client on the exchange
            registry.add("sso.providers.$BAD_SECRET_PROVIDER_ID.display-name") { "Bad Secret" }
            registry.add("sso.providers.$BAD_SECRET_PROVIDER_ID.protocol") { "oauth2-userinfo" }
            registry.add("sso.providers.$BAD_SECRET_PROVIDER_ID.pkce") { "false" }
            registry.add("sso.providers.$BAD_SECRET_PROVIDER_ID.email-verified-mode") { "provider-guaranteed" }
            registry.add("sso.providers.$BAD_SECRET_PROVIDER_ID.client-id") { YAX_CLIENT_ID }
            registry.add("sso.providers.$BAD_SECRET_PROVIDER_ID.client-secret") { BAD_CLIENT_SECRET }
            registerOauth2Endpoints(registry, BAD_SECRET_PROVIDER_ID)

            // the VK-shaped provider: nested dot-path claims, claim-mode
            // verified fact, client credentials in the token POST body,
            // authorize-issued device_id forwarded to the exchange; PKCE
            // stays on (VK supports RFC 7636, unlike Yandex)
            registry.add("sso.providers.$VK_PROVIDER_ID.display-name") { "VK-ish" }
            registry.add("sso.providers.$VK_PROVIDER_ID.trusted-for-email-linking") { "true" }
            registry.add("sso.providers.$VK_PROVIDER_ID.protocol") { "oauth2-userinfo" }
            registry.add("sso.providers.$VK_PROVIDER_ID.subject-claim") { "$VK_PROFILE_NEST.$VK_SUBJECT_CLAIM" }
            registry.add("sso.providers.$VK_PROVIDER_ID.email-claim") { "$VK_PROFILE_NEST.$VK_EMAIL_CLAIM" }
            registry.add("sso.providers.$VK_PROVIDER_ID.email-verified-mode") { "claim" }
            registry.add("sso.providers.$VK_PROVIDER_ID.email-verified-claim") { "$VK_PROFILE_NEST.email_verified" }
            registry.add("sso.providers.$VK_PROVIDER_ID.client-auth") { "post" }
            registry.add("sso.providers.$VK_PROVIDER_ID.token-device-id") { "true" }
            registry.add("sso.providers.$VK_PROVIDER_ID.client-id") { VK_CLIENT_ID }
            registry.add("sso.providers.$VK_PROVIDER_ID.client-secret") { VK_CLIENT_SECRET }
            registerOauth2Endpoints(registry, VK_PROVIDER_ID)
        }

        /** Explicit oauth2-mode MockIdP endpoints of one provider (research.md §21). */
        private fun registerOauth2Endpoints(
            registry: DynamicPropertyRegistry,
            providerId: String,
        ) {
            registry.add("sso.providers.$providerId.authorization-uri") { "${idpServer.baseUri}$OAUTH2_AUTHORIZE_PATH" }
            registry.add("sso.providers.$providerId.token-uri") { "${idpServer.baseUri}$OAUTH2_TOKEN_PATH" }
            registry.add("sso.providers.$providerId.userinfo-uri") { "${idpServer.baseUri}$OAUTH2_USERINFO_PATH" }
        }

        @AfterAll
        @JvmStatic
        fun stopIdpServer() {
            idpServer.stop()
        }

        private val ipCounter = AtomicInteger(CLIENT_IP_BASE)

        private const val YAX_PROVIDER_ID = "yandexish"

        private const val CLAIM_PROVIDER_ID = "claimish"

        private const val OIDC_PROVIDER_ID = "oidc-mate"

        private const val BAD_SECRET_PROVIDER_ID = "bad-secret"

        private const val VK_PROVIDER_ID = "vkish"

        private const val YAX_CLIENT_ID = "webchat-ya"

        private const val YAX_CLIENT_SECRET = "ya-oauth2-secret"

        private const val CLAIM_CLIENT_ID = "webchat-claim"

        private const val CLAIM_CLIENT_SECRET = "claim-oauth2-secret"

        private const val OIDC_CLIENT_ID = "webchat-oidc-mate"

        private const val OIDC_CLIENT_SECRET = "oidc-mate-secret"

        private const val BAD_CLIENT_SECRET = "rotated-away-oauth2-secret"

        private const val VK_CLIENT_ID = "webchat-vk"

        private const val VK_CLIENT_SECRET = "vk-oauth2-secret"

        /** The VK-shaped userinfo field names and profile nest. */
        private const val VK_SUBJECT_CLAIM = "user_id"

        private const val VK_EMAIL_CLAIM = "email"

        private const val VK_PROFILE_NEST = "user"

        private const val DEVICE_ID = "device_id"

        /** The Yandex-shaped userinfo claim names (research.md §20). */
        private const val YAX_SUBJECT_CLAIM = "psuid"

        private const val YAX_EMAIL_CLAIM = "default_email"

        /** The claim-mode defaults: plain `sub`/`email` field names. */
        private const val CLAIM_SUBJECT_CLAIM = "sub"

        private const val CLAIM_EMAIL_CLAIM = "email"

        private const val PASSWORD = "Str0ng-Oauth2-IT-Pass!"

        private const val ACCESS_TTL_SECONDS = 300

        private const val FLOW_TTL_SECONDS = 600L

        private const val FLOW_KEY_PREFIX = "sso:flow:"

        private const val HANDSHAKE_KEY_PREFIX = "sso:handshake:"

        /** research.md §19, SC-005: the overall callback budget of all provider calls. */
        private val CALLBACK_DEADLINE: Duration = Duration.ofSeconds(5)

        private const val CLIENT_IP_PREFIX = "203.0.113."

        /** First host of this suite's 203.0.113.0/24 block, after SsoResilienceIT (180+). */
        private const val CLIENT_IP_BASE = 220

        private const val USERNAME_MAX_LENGTH = 24

        private const val TOKEN_43 = "[A-Za-z0-9_-]{43}"

        private const val USERNAME_OTTO = "otto"

        private const val SUBJECT_OTTO = "psuid-otto-2.0000.7f3k"

        private const val EMAIL_OTTO = "otto.oauth2@example.com"

        private const val SUBJECT_PIKE = "psuid-pike-4.0000.9q1m"

        private const val EMAIL_PIKE = "pike.oauth2@example.com"

        private const val SUBJECT_QUINN = "subject-quinn-oauth2"

        private const val EMAIL_QUINN = "quinn.oauth2@example.com"

        private const val SUBJECT_RIVA = "psuid-riva-6.0000.4d8v"

        private const val EMAIL_RIVA = "riva.oauth2@example.com"

        private const val SUBJECT_SIMON = "subject-simon-oidc"

        private const val EMAIL_SIMON = "simon.oidc@example.com"

        /** The VK subject: the stable numeric `user_id` of id.vk.com. */
        private const val SUBJECT_VK = "4242424"

        private const val EMAIL_VK = "vera.vk@example.com"

        private const val USERNAME_TESS = "tesla"

        private const val EMAIL_TESS = "tess.oauth2@example.com"

        private const val SUBJECT_TESS = "psuid-tess-8.0000.2k6p"

        private const val USERNAME_URSA = "ursuline"

        private const val EMAIL_URSA = "ursa.oauth2@example.com"

        private const val SUBJECT_URSA = "subject-ursa-oauth2"

        // T042: local-dev default of sso.callback-url — the backend answers on
        // :8080 directly (no ingress)
        private const val BACKEND_CALLBACK_URL = "http://localhost:8080/api/v1/auth/sso/callback"

        private const val OAUTH2_AUTHORIZE_PATH = "/mock-idp/oauth2/authorize"

        private const val OAUTH2_TOKEN_PATH = "/mock-idp/oauth2/token"

        private const val OAUTH2_USERINFO_PATH = "/mock-idp/oauth2/userinfo"

        private const val OIDC_AUTHORIZE_PATH = "/mock-idp/authorize"

        private const val OIDC_TOKEN_PATH = "/mock-idp/token"

        private const val OIDC_JWKS_PATH = "/mock-idp/jwks"

        private const val PROVIDERS_PATH = "/api/v1/auth/sso/providers"

        private const val AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"

        private const val CALLBACK_PATH = "/api/v1/auth/sso/callback"

        private const val TOKEN_PATH = "/api/v1/auth/sso/token"

        private const val REGISTER_PATH = "/api/v1/auth/register"

        private const val CONFIRM_PATH = "/api/v1/auth/register/confirm"

        private const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"

        private const val LOGIN_PATH = "/api/v1/auth/login"

        private const val PROVIDER_ID_FIELD = "providerId"

        private const val CLIENT_ID = "client_id"

        private const val REDIRECT_URI = "redirect_uri"

        private const val RESPONSE_TYPE = "response_type"

        private const val STATE = "state"

        private const val NONCE_CLAIM = "nonce"

        private const val CODE_CHALLENGE = "code_challenge"

        private const val CODE_CHALLENGE_METHOD = "code_challenge_method"

        private const val S256_METHOD = "S256"

        private const val CODE = "code"

        private const val ERROR_PARAMETER = "error"

        private const val SSO_ERROR_PARAMETER = "sso_error"

        private const val PROVIDER_ERROR_CODE = "provider_error"

        private const val EMAIL_NOT_VERIFIED_CODE = "email_not_verified"

        private const val EMAIL_CONFLICT_CODE = "email_conflict"

        private const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
    }
}

/**
 * Loopback HTTP mount of the very same [MockIdP] controller class that also
 * runs as the context bean, routing BOTH of its endpoint families — the OIDC
 * authorize/token/jwks paths AND the oauth2-mode authorize/token/userinfo
 * paths (T055) — the SsoFlowIT pattern verbatim: the production [webchat.backend.sso.oidc.OidcClient]
 * needs the IdP at absolute URLs while `AbstractIntegrationTest` boots the
 * application on a RANDOM port that only becomes observable after the context
 * is fully built, too late for the `@DynamicPropertySource` binding of
 * `sso.providers.*`. Requests are translated into [MockHttpServletRequest]
 * and dispatched to the controller methods (headers included — the userinfo
 * leg authenticates by the Bearer header), responses are translated back —
 * byte-for-byte the same behavior as the in-context bean, without a second
 * Spring context. The pool keeps a slow userinfo endpoint (the UNAVAILABLE
 * scenario) from blocking parallel IdP calls.
 */
private class SsoOauth2IdpLoopbackServer(
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
        val method = exchange.requestMethod
        return when {
            method == GET_METHOD && path == OIDC_AUTHORIZE_PATH -> idp.authorize(toServletRequest(exchange))
            method == POST_METHOD && path == OIDC_TOKEN_PATH -> idp.token(toServletRequest(exchange))
            method == GET_METHOD && path == OIDC_JWKS_PATH -> jwksResponse()
            method == GET_METHOD && path == OAUTH2_AUTHORIZE_PATH -> idp.oauth2Authorize(toServletRequest(exchange))
            method == POST_METHOD && path == OAUTH2_TOKEN_PATH -> idp.oauth2Token(toServletRequest(exchange))
            method == GET_METHOD && path == OAUTH2_USERINFO_PATH -> userinfoResponse(exchange)
            else -> ResponseEntity.notFound().build()
        }
    }

    private fun jwksResponse(): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(idp.jwks())

    /** The userinfo endpoint answers by the raw Bearer header of the exchange (T055). */
    private fun userinfoResponse(exchange: HttpExchange): ResponseEntity<String> =
        idp.userinfo(exchange.requestHeaders.getFirst(HttpHeaders.AUTHORIZATION))

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

        const val OIDC_AUTHORIZE_PATH = "/mock-idp/authorize"

        const val OIDC_TOKEN_PATH = "/mock-idp/token"

        const val OIDC_JWKS_PATH = "/mock-idp/jwks"

        const val OAUTH2_AUTHORIZE_PATH = "/mock-idp/oauth2/authorize"

        const val OAUTH2_TOKEN_PATH = "/mock-idp/oauth2/token"

        const val OAUTH2_USERINFO_PATH = "/mock-idp/oauth2/userinfo"

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
