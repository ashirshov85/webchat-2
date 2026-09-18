package webchat.backend.sso

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
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.AbstractIntegrationTest
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * T046 [US5] (tasks.md Phase 7, Test-First): FR-009/SC-007 flood limits on
 * the five SSO routes of research.md §9 — the NORMATIVE source of the values,
 * mirrored by contracts/sso-api.md §1–5 and quickstart S6.2:
 * - `GET  /api/v1/auth/sso/providers`      30/1m per IP (§1);
 * - `POST /api/v1/auth/sso/authorize`      10/1m per IP (§2) — stricter: the
 *   route CREATES flow contexts in Redis;
 * - `GET  /api/v1/auth/sso/callback`       30/1m per IP (§3) — the GET leg the
 *   existing RateLimitFilter cannot even see yet (POST-only, T047);
 * - `POST /api/v1/auth/sso/token`          30/1m per IP (§4, token-consuming
 *   like confirm/refresh in 002);
 * - `POST /api/v1/auth/sso/link/authorize` 10/1m per IP, Bearer (§5).
 *
 * Every method floods ONE route from ONE source until the N+1-th request:
 * under the limit the route answers its normal uniform status (200 for the
 * provider list, 404 for an unknown provider on both authorize routes, the
 * 302 `?sso_error=invalid_state` SPA redirect for a never-issued state, 400
 * `invalid_code` for an unknown handshake code — "pure bucket" inputs with
 * zero side effects, the RateLimitIT convention), beyond it the uniform 429
 * problem+json with `Retry-After` ≥ 1 and ≤ 60 (every window is 1m). The
 * buckets are keyed per source IP (research §9): a FRESH source keeps
 * passing while the flooded one is throttled; the state lives in Redis under
 * the `rl:ip:sso:*` family of data-model.md §5, not in process memory
 * (FR-009, constitution II); a throttled authorize flood creates no flow
 * contexts (the chain never sees the request).
 *
 * The link/authorize Bearer token comes from the real 002 choreography
 * (register → confirm → set-password → login) driven from a dedicated setup
 * address, so no MockIdP mount is needed anywhere in this suite.
 *
 * NOTE (TDD, constitution VI): written BEFORE T047 — until the
 * RateLimitFilter/AuthRateLimitProperties table lands, every 429 assertion
 * fails (RED) by design while the under-limit statuses already pass. IPs are
 * unique per method inside 198.51.100.1–39 (TEST-NET-2 head — untouched by
 * the 198.18.0.0/15 of RateLimitIT, the 192.0.2.x of the other SSO suites
 * and the 198.51.100.40+/200+ tails of PasswordResetIT/
 * AuthenticationBoundaryIT), so the shared static Redis carries no state
 * between methods (windows are minutes).
 */
@Suppress("LargeClass", "TooManyFunctions")
class SsoRateLimitIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : AbstractIntegrationTest() {
    @Test
    fun `providers route is ip limited to 30 per minute`() {
        val sourceIp = PROVIDERS_FLOOD_IP

        repeat(PROVIDERS_LIMIT) {
            val response = get(PROVIDERS_PATH, sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(objectMapper.readTree(response.body)["providers"].isArray).isTrue
        }

        val flooded = get(PROVIDERS_PATH, sourceIp)

        assertTooManyRequests(flooded)
        assertSsoBucketExists(sourceIp)

        // per-IP key (research §9): a fresh source sails through the untouched bucket
        assertThat(get(PROVIDERS_PATH, FRESH_CHECK_IP).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `authorize route is ip limited to 10 per minute`() {
        val sourceIp = AUTHORIZE_FLOOD_IP
        val flowKeysBefore = flowKeyCount()

        // unknown provider → the uniform 404 of contracts/sso-api.md §2 with
        // ZERO side effects — only the bucket is consumed (pure bucket input)
        repeat(AUTHORIZE_LIMIT) {
            val response = postJson(AUTHORIZE_PATH, unknownProviderRequest(), sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

        val flooded = postJson(AUTHORIZE_PATH, unknownProviderRequest(), sourceIp)

        assertTooManyRequests(flooded)
        assertSsoBucketExists(sourceIp)
        // the throttled flood never reached the chain: no flow contexts appeared
        assertThat(flowKeyCount()).isEqualTo(flowKeysBefore)
    }

    @Test
    fun `callback route is ip limited to 30 per minute`() {
        val sourceIp = CALLBACK_FLOOD_IP

        // a state authorize never issued → the 302 `?sso_error=invalid_state`
        // SPA redirect (contracts/sso-api.md §3) on every under-limit hit
        repeat(CALLBACK_LIMIT) {
            val response = callback(sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
            assertThat(queryParametersOf(response.headers.location.toString()).getValue(SSO_ERROR_PARAMETER))
                .isEqualTo(INVALID_STATE)
        }

        val flooded = callback(sourceIp)

        assertTooManyRequests(flooded)
        assertSsoBucketExists(sourceIp)
    }

    @Test
    fun `token route is ip limited to 30 per minute`() {
        val sourceIp = TOKEN_FLOOD_IP

        repeat(TOKEN_LIMIT) {
            val response = postJson(TOKEN_PATH, mapOf(CODE to garbageToken("rate-limit-token")), sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
        // the uniform 400 body of the under-limit leg (contracts/sso-api.md §4)
        val underLimit = postJson(TOKEN_PATH, mapOf(CODE to garbageToken("rate-limit-token")), FRESH_CHECK_IP)
        assertThat(underLimit.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(underLimit.body)["errors"]["code"][0].asText()).isEqualTo(INVALID_CODE)

        val flooded = postJson(TOKEN_PATH, mapOf(CODE to garbageToken("rate-limit-token")), sourceIp)

        assertTooManyRequests(flooded)
        assertSsoBucketExists(sourceIp)
    }

    @Test
    fun `link authorize route is ip limited to 10 per minute for authenticated callers`() {
        val accessToken = passwordLoginAccessToken()
        val sourceIp = LINK_FLOOD_IP

        repeat(LINK_AUTHORIZE_LIMIT) {
            val response = postJsonBearer(LINK_AUTHORIZE_PATH, unknownProviderRequest(), accessToken, sourceIp)
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

        val flooded = postJsonBearer(LINK_AUTHORIZE_PATH, unknownProviderRequest(), accessToken, sourceIp)

        assertTooManyRequests(flooded)
        assertSsoBucketExists(sourceIp)

        // per-IP key again: the SAME token from a fresh source still passes
        val freshSource = postJsonBearer(LINK_AUTHORIZE_PATH, unknownProviderRequest(), accessToken, FRESH_CHECK_IP)
        assertThat(freshSource.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- request helpers (RateLimitIT/SsoSecurityIT conventions) ------------

    private fun get(
        path: String,
        xForwardedFor: String,
    ): ResponseEntity<String> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<String>(headersOf(xForwardedFor)), String::class.java)

    private fun postJson(
        path: String,
        payload: Map<String, String>,
        xForwardedFor: String,
    ): ResponseEntity<String> {
        val headers = headersOf(xForwardedFor)
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    private fun postJsonBearer(
        path: String,
        payload: Map<String, String>,
        accessToken: String,
        xForwardedFor: String,
    ): ResponseEntity<String> {
        val headers =
            headersOf(xForwardedFor).apply {
                contentType = MediaType.APPLICATION_JSON
                set(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    private fun headersOf(xForwardedFor: String): HttpHeaders =
        HttpHeaders()
            .apply { set(X_FORWARDED_FOR_HEADER, xForwardedFor) }

    /** The browser callback leg with a never-issued state — never follows the SPA 302. */
    private fun callback(xForwardedFor: String): ResponseEntity<String> =
        noRedirectClient.exchange(
            URI.create(
                UriComponentsBuilder
                    .fromHttpUrl(rootUri() + CALLBACK_PATH)
                    .queryParam(STATE, garbageToken("rate-limit-state"))
                    .queryParam(CODE, garbageToken("rate-limit-code"))
                    .build()
                    .toUriString(),
            ),
            HttpMethod.GET,
            HttpEntity<String>(headersOf(xForwardedFor)),
            String::class.java,
        )

    /**
     * TestRestTemplate's JDK client follows redirects, which would chase the
     * callback 302 Location straight into the SPA; the FOUND assertions need
     * the raw response (SsoSecurityIT/SsoFlowIT precedent).
     */
    private val noRedirectClient =
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

    // --- the 002 choreography financing the Bearer leg -----------------------

    /** register → confirm → set-password → login over the real 002 endpoints from the setup address. */
    private fun passwordLoginAccessToken(): String {
        val register =
            postJson(REGISTER_PATH, mapOf(USERNAME to LINK_USER, EMAIL to LINK_USER_EMAIL), SETUP_IP)
        assertThat(register.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val confirm =
            postJson(CONFIRM_PATH, mapOf(TOKEN to extractVerificationToken(LINK_USER_EMAIL)), SETUP_IP)
        assertThat(confirm.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirm.body)["setupToken"].asText()

        val setPassword =
            postJson(
                SET_PASSWORD_PATH,
                mapOf(
                    SETUP_TOKEN to setupToken,
                    PASSWORD to LINK_USER_PASSWORD,
                    CONFIRM_PASSWORD to LINK_USER_PASSWORD,
                ),
                SETUP_IP,
            )
        assertThat(setPassword.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val login =
            postJson(
                LOGIN_PATH,
                mapOf(IDENTIFIER to LINK_USER, PASSWORD to LINK_USER_PASSWORD),
                SETUP_IP,
            )
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(login.body)["accessToken"].asText()
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
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payloads.firstOrNull() ?: "")
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    // --- assertions ----------------------------------------------------------

    /** The uniform 429 of research.md §9: problem+json + `Retry-After` within [1, 60] (1m windows). */
    private fun assertTooManyRequests(response: ResponseEntity<String>) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        val retryAfter = response.headers.getFirst(HttpHeaders.RETRY_AFTER)
        assertThat(retryAfter).isNotNull
        assertThat(retryAfter!!.toInt()).isBetween(MIN_RETRY_AFTER_SECONDS, MINUTE_SECONDS)
        assertThat(response.headers.contentType).isNotNull
        assertThat(response.headers.contentType!!.toString()).contains("application/problem+json")
        val problem = objectMapper.readTree(response.body)
        assertThat(problem["status"].asInt()).isEqualTo(TOO_MANY_REQUESTS_STATUS)
        assertThat(problem["title"].asText()).isEqualTo(TOO_MANY_REQUESTS_TITLE)
    }

    /** FR-009: the bucket state is external — a `rl:ip:sso:*` key ends with the flooded source (data-model.md §5). */
    private fun assertSsoBucketExists(sourceIp: String) {
        val keys = redisTemplate.keys(SSO_BUCKET_KEY_GLOB + sourceIp).orEmpty()
        assertThat(keys).isNotEmpty
    }

    private fun flowKeyCount(): Int = redisTemplate.keys(FLOW_KEY_PREFIX + "*").orEmpty().size

    private fun queryParametersOf(url: String): Map<String, String> =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .map { (name, values) -> name to URLDecoder.decode(values.first(), StandardCharsets.UTF_8) }
            .toMap()

    private fun unknownProviderRequest(): Map<String, String> = mapOf(PROVIDER_ID_FIELD to UNKNOWN_PROVIDER_ID)

    /** 43-char base64url-shaped value for state/code stand-ins (SsoSecurityIT convention). */
    private fun garbageToken(seed: String): String = seed.padEnd(TOKEN_43_LENGTH, '-').take(TOKEN_43_LENGTH)

    private companion object {
        // research.md §9 (normative): the five SSO route limits, per source IP
        const val PROVIDERS_LIMIT = 30
        const val AUTHORIZE_LIMIT = 10
        const val CALLBACK_LIMIT = 30
        const val TOKEN_LIMIT = 30
        const val LINK_AUTHORIZE_LIMIT = 10

        const val MIN_RETRY_AFTER_SECONDS = 1
        const val MINUTE_SECONDS = 60
        const val TOO_MANY_REQUESTS_STATUS = 429
        const val TOO_MANY_REQUESTS_TITLE = "Too Many Requests"

        // 198.51.100.1–39 (TEST-NET-2 head): untouched by every other IT suite
        const val PROVIDERS_FLOOD_IP = "198.51.100.10"
        const val AUTHORIZE_FLOOD_IP = "198.51.100.11"
        const val CALLBACK_FLOOD_IP = "198.51.100.12"
        const val TOKEN_FLOOD_IP = "198.51.100.13"
        const val LINK_FLOOD_IP = "198.51.100.14"
        const val SETUP_IP = "198.51.100.15"
        const val FRESH_CHECK_IP = "198.51.100.16"

        // data-model.md §5: the SSO route table rides the `rl:ip:sso:*` family
        const val SSO_BUCKET_KEY_GLOB = "rl:ip:sso*:"
        const val FLOW_KEY_PREFIX = "sso:flow:"

        const val UNKNOWN_PROVIDER_ID = "no-such-provider"

        const val LINK_USER = "rl-sso-link-user"
        const val LINK_USER_EMAIL = "rl-sso-link-user@example.com"
        const val LINK_USER_PASSWORD = "Str0ng-SsoRateLimit-IT-Pass!"

        const val TOKEN_43_LENGTH = 43

        const val PROVIDERS_PATH = "/api/v1/auth/sso/providers"
        const val AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"
        const val CALLBACK_PATH = "/api/v1/auth/sso/callback"
        const val TOKEN_PATH = "/api/v1/auth/sso/token"
        const val LINK_AUTHORIZE_PATH = "/api/v1/auth/sso/link/authorize"

        const val REGISTER_PATH = "/api/v1/auth/register"
        const val CONFIRM_PATH = "/api/v1/auth/register/confirm"
        const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"
        const val LOGIN_PATH = "/api/v1/auth/login"

        const val PROVIDER_ID_FIELD = "providerId"
        const val USERNAME = "username"
        const val EMAIL = "email"
        const val IDENTIFIER = "identifier"
        const val PASSWORD = "password"
        const val CONFIRM_PASSWORD = "confirmPassword"
        const val SETUP_TOKEN = "setupToken"
        const val TOKEN = "token"
        const val STATE = "state"
        const val CODE = "code"

        const val SSO_ERROR_PARAMETER = "sso_error"
        const val INVALID_STATE = "invalid_state"
        const val INVALID_CODE = "invalid_code"

        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
        const val BEARER_PREFIX = "Bearer "
    }
}
