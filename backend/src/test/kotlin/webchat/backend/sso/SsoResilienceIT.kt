package webchat.backend.sso

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
import webchat.backend.sso.domain.port.SsoFlowContext
import webchat.backend.sso.domain.port.SsoFlowPurpose
import webchat.backend.sso.domain.port.SsoFlowStore
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * T040 [US4/US5] (tasks.md Phase 6, Test-First): the multi-provider
 * configuration and resilience acceptance (US4-1, US4-3, US4-4, SC-005) over
 * the real HTTP port of the `AbstractIntegrationTest` context through the
 * MockIdP (T018) — written BEFORE the implementation refinement (T041) and
 * RED until T044 brings it green (constitution VI); it also cross-checks the
 * US5-3 leg "provider unavailability" together with SsoSecurityIT.
 *
 * Providers under test (research.md §12: explicit endpoints, never discovery):
 * - `alpha` and `beta` — two ENABLED, healthy providers on one MockIdP:
 *   both are listed in configuration order and the login works through each
 *   (US4-1);
 * - `off` — a DISABLED provider: hidden from the public list, authorize
 *   answers the single 404 of unknown providers, and a callback of a flow
 *   started while it was still enabled ends with `sso_error=provider_disabled`
 *   (US4-3; the flow context is seeded straight into the store — the runtime
 *   "disabled between authorize and callback" gap of T041);
 * - `wrong-secret` — a configured secret the IdP rejects (`invalid_client`);
 * - `broken` — a provider whose token endpoint answers HTTP 500 or sleeps
 *   longer than the read timeout (MockIdP managed failures, research.md §12);
 * - `dark` — a provider whose token endpoint points at a CLOSED local port:
 *   the "unreachable endpoints" leg of US4-4.
 *
 * The failure scenarios (`broken`/`dark`) run on a SECOND MockIdP instance so
 * the degraded IdP never touches the healthy one — the isolation US4-4
 * demands: a failing provider answers `provider_error` within the 5 s
 * callback deadline (SC-005, FR-010) while the OTHER providers and the
 * password login keep working, with the failure journaled as `sso_flow_error`
 * with non-secret markers (FR-011) and zero side effects.
 *
 * The IdP half of each flow runs on dedicated loopback mounts of [MockIdP] —
 * the SsoFlowIT pattern: provider endpoints must be absolute URIs known while
 * `@DynamicPropertySource` binds `sso.providers.*`, before the random
 * application port becomes observable. Test data follows the SessionIT
 * conventions (accounts grown through the real 002 registration API); the
 * 198.51.100.x documentation IPs keep the 002 IP rate-limit buckets out of
 * the picture.
 */
@Suppress("LargeClass") // tasks.md T040 mandates the whole US4 acceptance in this single IT file (SessionIT precedent)
@AutoConfigureObservability // deterministic in-test tracing so T044 can drive the callback traceId via traceparent
@ExtendWith(OutputCaptureExtension::class)
class SsoResilienceIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
    @Autowired private val flowStore: SsoFlowStore,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun resetIdps() {
        healthyIdP.reset()
        degradedIdP.reset()
    }

    @Test
    fun `providers lists the enabled providers in configuration order and hides the disabled one`() {
        val response = restTemplate.getForEntity(PROVIDERS_PATH, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val providers = objectMapper.readTree(response.body)["providers"]
        val ids = providers.map { it["id"].asText() }

        // US4-1: every enabled provider is offered; US4-3: the disabled one is hidden
        assertThat(ids).contains(ALPHA_PROVIDER_ID, BETA_PROVIDER_ID)
        assertThat(ids).doesNotContain(OFF_PROVIDER_ID)

        // contract §1: configuration (YAML) declaration order, alpha declared first
        assertThat(ids.indexOf(ALPHA_PROVIDER_ID)).isLessThan(ids.indexOf(BETA_PROVIDER_ID))
        assertThat(providers[ids.indexOf(ALPHA_PROVIDER_ID)]["displayName"].asText()).isEqualTo(ALPHA_DISPLAY_NAME)

        // SC-004: the public list never carries client credentials
        providers.forEach { provider ->
            assertThat(provider.has("clientId")).isFalse()
            assertThat(provider.has("clientSecret")).isFalse()
        }
    }

    @Test
    fun `login works through both enabled providers simultaneously`() {
        val usersBefore = usersCount()
        healthyIdP.setClaims(idpClaims(SUBJECT_URSA, EMAIL_URSA))

        val viaAlpha = performSsoLogin(ALPHA_PROVIDER_ID)

        healthyIdP.setClaims(idpClaims(SUBJECT_VICTOR, EMAIL_VICTOR))
        val viaBeta = performSsoLogin(BETA_PROVIDER_ID)

        // US4-1: both providers hand out a working session of their own
        assertThat(viaAlpha.body["user"]["email"].asText()).isEqualTo(EMAIL_URSA)
        assertThat(viaBeta.body["user"]["email"].asText()).isEqualTo(EMAIL_VICTOR)
        assertThat(usersCount()).isEqualTo(usersBefore + 2)
        assertThat(identityRow(ALPHA_PROVIDER_ID, SUBJECT_URSA)!!["provider_id"]).isEqualTo(ALPHA_PROVIDER_ID)
        assertThat(identityRow(BETA_PROVIDER_ID, SUBJECT_VICTOR)!!["provider_id"]).isEqualTo(BETA_PROVIDER_ID)
        assertThat(sessionRow(sessionIdOf(viaAlpha.refreshToken)!!)!!["auth_method"]).isEqualTo("sso")
        assertThat(sessionRow(sessionIdOf(viaBeta.refreshToken)!!)!!["auth_method"]).isEqualTo("sso")
    }

    @Test
    fun `disabled provider is hidden, rejected on authorize and answers provider_disabled on callback`() {
        seedActivePasswordUser(USERNAME_XANDER, EMAIL_XANDER)
        val usersBefore = usersCount()
        val identitiesBefore = identitiesCount()
        val handshakesBefore = handshakeKeyCount()

        // US4-3: the direct flow start is rejected — the same single 404 as for unknown providers
        val disabled = authorize(OFF_PROVIDER_ID)
        assertThat(disabled.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(objectMapper.readTree(disabled.body)["detail"].asText()).isEqualTo("Provider not found")
        val unknown = authorize("no-such-provider")
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(objectMapper.readTree(unknown.body)["detail"].asText()).isEqualTo("Provider not found")

        // the provider was disabled BETWEEN authorize and callback: the flow
        // context exists, the provider is off now — the callback must say so
        // (T041). The context is seeded straight into the store because the
        // 404 above makes a live authorize impossible for `off`.
        val state = flowToken("resilience-off-state")
        flowStore.saveFlow(
            state,
            SsoFlowContext(
                providerId = OFF_PROVIDER_ID,
                purpose = SsoFlowPurpose.LOGIN,
                nonce = flowToken("resilience-off-nonce"),
                codeVerifier = flowToken("resilience-off-verifier"),
                createdAt = Instant.now(),
            ),
        )
        val callbackUrl =
            UriComponentsBuilder
                .fromUriString(rootUri() + CALLBACK_PATH)
                .queryParam(STATE, state)
                .queryParam(CODE, flowToken("resilience-off-code"))
                .build()
                .toUriString()

        val trace = nextTraceContext()
        val callbackResponse = callback(callbackUrl, trace.traceparent)

        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaParameters = queryParametersOf(callbackResponse.headers.location.toString())
        assertThat(spaParameters).doesNotContainKey(CODE)
        assertThat(spaParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_DISABLED_CODE)

        // US4-3: no side effects — the binding-less rejection creates nothing
        assertThat(usersCount()).isEqualTo(usersBefore)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(handshakeKeyCount()).isEqualTo(handshakesBefore)

        // FR-011 + T044: the outcome is journaled with non-secret markers and
        // tied to the request traceId (the WARN log assert runs in the
        // provider-failure tests below)
        assertFlowErrorJournaled(trace, OFF_PROVIDER_ID, PROVIDER_DISABLED_CODE)

        // US4-3: the remaining login methods are untouched — the password
        // account still logs in, and so does a healthy provider
        assertThat(passwordLogin(EMAIL_XANDER, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
        healthyIdP.setClaims(idpClaims(SUBJECT_WREN, EMAIL_WREN))
        assertThat(performSsoLogin(ALPHA_PROVIDER_ID).body["user"]["email"].asText()).isEqualTo(EMAIL_WREN)
        assertThat(identityRow(ALPHA_PROVIDER_ID, SUBJECT_WREN)).isNotNull
    }

    /**
     * T041, US4-4: an issuer-based (discovery) provider whose metadata
     * endpoint is unreachable. Authorize answers the typed 502 problem+json —
     * never a bare 500 — and writes no flow context; a flow started before the
     * outage (context seeded, the T040 `off`-provider pattern) ends on the
     * callback with the single `provider_error` inside the 5 s deadline, with
     * no side effects and healthy providers plus password login untouched.
     */
    @Test
    fun `unreachable issuer discovery is isolated to its provider`(capturedOutput: CapturedOutput) {
        val usersBefore = usersCount()
        val identitiesBefore = identitiesCount()
        val handshakesBefore = handshakeKeyCount()
        val flowsBefore = flowKeyCount()

        // authorize cannot even start: the metadata endpoint is down — the
        // typed 502 of T041, and no flow context survives the failure
        val authorizeResponse = authorize(LOST_DISCOVERY_PROVIDER_ID)
        assertThat(authorizeResponse.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        assertThat(objectMapper.readTree(authorizeResponse.body)["detail"].asText())
            .isEqualTo(PROVIDER_UNAVAILABLE_DETAIL)
        assertThat(flowKeyCount()).isEqualTo(flowsBefore)

        // the callback of a flow started before the outage: the registration
        // build fails inside the flow — the single provider_error (T041)
        val state = flowToken("resilience-lost-state")
        flowStore.saveFlow(
            state,
            SsoFlowContext(
                providerId = LOST_DISCOVERY_PROVIDER_ID,
                purpose = SsoFlowPurpose.LOGIN,
                nonce = flowToken("resilience-lost-nonce"),
                codeVerifier = flowToken("resilience-lost-verifier"),
                createdAt = Instant.now(),
            ),
        )
        val callbackUrl =
            UriComponentsBuilder
                .fromUriString(rootUri() + CALLBACK_PATH)
                .queryParam(STATE, state)
                .queryParam(CODE, flowToken("resilience-lost-code"))
                .build()
                .toUriString()

        val trace = nextTraceContext()
        val (callbackResponse, elapsed) = timed { callback(callbackUrl, trace.traceparent) }

        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaParameters = queryParametersOf(callbackResponse.headers.location.toString())
        assertThat(spaParameters).doesNotContainKey(CODE)
        assertThat(spaParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR_CODE)
        assertThat(elapsed).isLessThan(CALLBACK_DEADLINE)

        // FR-011 + T044: journaled with non-secret markers and fixed in the
        // logs under the request traceId; no side effects anywhere
        assertFlowErrorObservability(trace, LOST_DISCOVERY_PROVIDER_ID, PROVIDER_ERROR_CODE, capturedOutput)
        assertThat(usersCount()).isEqualTo(usersBefore)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(handshakeKeyCount()).isEqualTo(handshakesBefore)

        // US4-4 isolation: the healthy providers and the password login work on
        healthyIdP.setClaims(idpClaims(SUBJECT_LOST, EMAIL_LOST))
        assertThat(performSsoLogin(ALPHA_PROVIDER_ID).body["user"]["email"].asText()).isEqualTo(EMAIL_LOST)
    }

    /**
     * US4-4 / SC-005 / FR-010: one provider fails — its own flow ends with
     * `provider_error` inside the 5 s callback deadline, while EVERY other
     * login method (the second healthy provider and the password login)
     * keeps working; the failure is journaled with non-secret markers.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("providerFailures")
    fun `a failing provider answers provider_error while the rest keep working`(
        scenario: ProviderFailureScenario,
        capturedOutput: CapturedOutput,
    ) {
        scenario.arm()
        val tag = scenarioTag(scenario)
        val passwordEmail = "$tag@example.com"
        seedActivePasswordUser(tag, passwordEmail)
        val usersBefore = usersCount()
        val identitiesBefore = identitiesCount()
        val handshakesBefore = handshakeKeyCount()

        val callbackUrl = driveToCallbackUrl(scenario.providerId)
        val trace = nextTraceContext()
        val (callbackResponse, elapsed) = timed { callback(callbackUrl, trace.traceparent) }

        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val spaParameters = queryParametersOf(callbackResponse.headers.location.toString())
        assertThat(spaParameters).doesNotContainKey(CODE)
        assertThat(spaParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR_CODE)

        // SC-005: a degraded provider never holds the user longer than the 5 s callback deadline
        assertThat(elapsed).isLessThan(CALLBACK_DEADLINE)

        // FR-010/FR-011 + T044: the failure is fixed in observability with
        // non-secret markers — journal row and WARN log share the traceId
        assertFlowErrorObservability(trace, scenario.providerId, PROVIDER_ERROR_CODE, capturedOutput)
        assertThat(latestFlowError(scenario.providerId, PROVIDER_ERROR_CODE)!!["details"].toString())
            .doesNotContain(scenario.clientSecret)

        // the failed flow leaves nothing behind — no JIT account, no binding, no handshake
        assertThat(usersCount()).isEqualTo(usersBefore)
        assertThat(identitiesCount()).isEqualTo(identitiesBefore)
        assertThat(handshakeKeyCount()).isEqualTo(handshakesBefore)

        // US4-4 isolation: both healthy providers and the password login keep working
        healthyIdP.setClaims(idpClaims("subject-$tag-alpha", "$tag-alpha@example.com"))
        val viaAlpha = performSsoLogin(ALPHA_PROVIDER_ID)
        assertThat(viaAlpha.body["user"]["email"].asText()).isEqualTo("$tag-alpha@example.com")
        healthyIdP.setClaims(idpClaims("subject-$tag-beta", "$tag-beta@example.com"))
        val viaBeta = performSsoLogin(BETA_PROVIDER_ID)
        assertThat(viaBeta.body["user"]["email"].asText()).isEqualTo("$tag-beta@example.com")
        assertThat(passwordLogin(passwordEmail, PASSWORD).statusCode).isEqualTo(HttpStatus.OK)
    }

    // --- SSO flow driving ---------------------------------------------------

    /** Runs authorize → IdP redirect and returns the ready-made backend callback URL. */
    private fun driveToCallbackUrl(providerId: String): String {
        val authorizeResponse = authorize(providerId)
        assertThat(authorizeResponse.statusCode).isEqualTo(HttpStatus.OK)
        val authorizationUrl = objectMapper.readTree(authorizeResponse.body)["authorizationUrl"].asText()

        val idpRedirect = noRedirectClient.getForEntity(URI.create(authorizationUrl), String::class.java)
        assertThat(idpRedirect.statusCode).isEqualTo(HttpStatus.FOUND)
        val idpParameters = queryParametersOf(idpRedirect.headers.location.toString())
        assertThat(idpParameters).doesNotContainKey(ERROR_PARAMETER)

        return UriComponentsBuilder
            .fromUriString(rootUri() + CALLBACK_PATH)
            .queryParam(STATE, idpParameters.getValue(STATE))
            .queryParam(CODE, idpParameters.getValue(CODE))
            .build()
            .toUriString()
    }

    /** The browser callback leg over the real port — never follows the SPA 302. */
    private fun callback(
        url: String,
        traceparent: String? = null,
    ): ResponseEntity<String> =
        noRedirectClient.exchange(
            URI.create(url),
            HttpMethod.GET,
            HttpEntity<String>(
                HttpHeaders().apply {
                    set(X_FORWARDED_FOR_HEADER, nextClientIp())
                    traceparent?.let { set(TRACEPARENT_HEADER, it) }
                },
            ),
            String::class.java,
        )

    /** The whole US1 choreography including the token exchange; the caller arms the IdP claims first. */
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
        register(username, email, nextClientIp())
        val confirmResponse = confirm(confirmTokenFromOutbox(email), nextClientIp())
        assertThat(confirmResponse.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirmResponse.body)["setupToken"].asText()
        assertThat(setPassword(setupToken, PASSWORD, nextClientIp()).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
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

    private fun identitiesCount(): Int {
        val sql = "SELECT COUNT(*) FROM external_identities"
        return jdbcTemplate.queryForObject(sql, Int::class.java)!!
    }

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

    private fun userIdByUsername(username: String): UUID? =
        jdbcTemplate
            .queryForList(
                "SELECT id FROM users WHERE lower(username) = ?",
                UUID::class.java,
                username.lowercase(),
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

    private fun flowKeyCount(): Int = redisTemplate.keys("$FLOW_KEY_PREFIX*").orEmpty().size

    /** The last `sso_flow_error` of the provider: `details` + `trace_id` (FR-011 + research.md §14). */
    private fun latestFlowError(
        providerId: String,
        reason: String,
    ): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT details::text AS details, trace_id FROM auth_events
                WHERE event_type = 'sso_flow_error'
                  AND details->>'provider' = ?
                  AND details->>'reason' = ?
                ORDER BY occurred_at DESC
                """.trimIndent(),
                providerId,
                reason,
            ).firstOrNull()

    /**
     * T044: the provider failure is fixed in observability — the
     * `sso_flow_error` journal row AND one structured WARN log record both
     * carry the callback request's traceId, so the log stream and
     * `auth_events` are correlated (research.md §14; the driven traceparent
     * makes the traceId deterministic). Non-secret markers only (FR-011).
     */
    private fun assertFlowErrorObservability(
        trace: TraceContext,
        providerId: String,
        reason: String,
        capturedOutput: CapturedOutput,
    ) {
        assertFlowErrorJournaled(trace, providerId, reason)

        val record =
            capturedOutput.all
                .lineSequence()
                .firstOrNull { it.contains("\"traceId\":\"${trace.traceId}\"") && it.contains("sso_flow_error") }
        assertThat(record).isNotNull
        assertThat(record).contains("\"level\":\"WARN\"")
        assertThat(record).contains(providerId)
        assertThat(record).contains(reason)
    }

    /** The journal half of [assertFlowErrorObservability]: `details` markers + the request traceId in `trace_id`. */
    private fun assertFlowErrorJournaled(
        trace: TraceContext,
        providerId: String,
        reason: String,
    ) {
        val row = latestFlowError(providerId, reason)
        assertThat(row).isNotNull
        assertThat(row!!["trace_id"]).isEqualTo(trace.traceId)
        assertThat(row["details"].toString()).contains(providerId).contains(reason)
    }

    /** A deterministic W3C trace context for one callback request (RequestLoggingTests pattern). */
    private data class TraceContext(
        val traceId: String,
        val traceparent: String,
    )

    private fun nextTraceContext(): TraceContext {
        val traceId = TRACE_ID_HEX_FORMAT.format(traceCounter.incrementAndGet())
        val spanId = SPAN_ID_HEX_FORMAT.format(traceCounter.incrementAndGet())
        return TraceContext(traceId, "00-$traceId-$spanId-01")
    }

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun idpClaims(
        subject: String,
        email: String,
    ): MockIdP.ControlledClaims = MockIdP.ControlledClaims(subject = subject, email = email, emailVerified = true)

    /** 43-char base64url-shaped value for state/nonce/verifier/code stand-ins. */
    private fun flowToken(seed: String): String = seed.padEnd(TOKEN_43_LENGTH, '-').take(TOKEN_43_LENGTH)

    private fun scenarioTag(scenario: ProviderFailureScenario): String =
        scenario.name
            .filter(Char::isLetterOrDigit)
            .lowercase()
            .take(USERNAME_MAX_LENGTH)

    private fun nextClientIp(): String = "$CLIENT_IP_PREFIX${ipCounter.incrementAndGet()}"

    private data class SsoLoginResult(
        val accessToken: String,
        val refreshToken: String,
        val handshakeCode: String,
        val body: JsonNode,
    )

    /** One US4-4 failure shape: the provider, its secret (for the no-leak assert) and the arming step. */
    data class ProviderFailureScenario(
        val name: String,
        val providerId: String,
        val clientSecret: String,
        val arm: () -> Unit,
    ) {
        override fun toString(): String = name
    }

    companion object {
        /** The healthy IdP of the two ENABLED providers (US4-1). */
        private val healthyIdP =
            MockIdP(ObjectMapper()).apply {
                registerClient(ALPHA_CLIENT_ID, ALPHA_CLIENT_SECRET)
                registerClient(BETA_CLIENT_ID, BETA_CLIENT_SECRET)
            }

        /**
         * A SECOND MockIdP instance for the degraded providers (`broken`,
         * `dark`): its failures never touch the healthy instance — exactly
         * the per-provider isolation US4-4 demands (SsoFlowIT loopback
         * pattern, research.md §12).
         */
        private val degradedIdP =
            MockIdP(ObjectMapper()).apply {
                registerClient(BROKEN_CLIENT_ID, BROKEN_CLIENT_SECRET)
                registerClient(DARK_CLIENT_ID, DARK_CLIENT_SECRET)
            }

        private val healthyServer = SsoResilienceIdpLoopbackServer(healthyIdP)

        private val degradedServer = SsoResilienceIdpLoopbackServer(degradedIdP)

        /**
         * A local port that is (almost surely) CLOSED: bound once to learn a
         * free number, released immediately and never listened on again —
         * the "token-uri points at an unreachable endpoint" provider of
         * US4-4.
         */
        private val closedPort: Int = ServerSocket(0).use { it.localPort }

        @JvmStatic
        fun providerFailures(): List<ProviderFailureScenario> =
            listOf(
                ProviderFailureScenario(
                    name = "token endpoint answers http 500",
                    providerId = BROKEN_PROVIDER_ID,
                    clientSecret = BROKEN_CLIENT_SECRET,
                    arm = { degradedIdP.tokenEndpointFailure = MockIdP.TokenEndpointFailure.HTTP_500 },
                ),
                ProviderFailureScenario(
                    name = "token endpoint is slower than the read timeout",
                    providerId = BROKEN_PROVIDER_ID,
                    clientSecret = BROKEN_CLIENT_SECRET,
                    arm = { degradedIdP.tokenEndpointFailure = MockIdP.TokenEndpointFailure.DELAY },
                ),
                ProviderFailureScenario(
                    name = "client secret is wrong",
                    providerId = WRONG_SECRET_PROVIDER_ID,
                    clientSecret = WRONG_CLIENT_SECRET,
                    arm = {},
                ),
                ProviderFailureScenario(
                    name = "token endpoint is unreachable",
                    providerId = DARK_PROVIDER_ID,
                    clientSecret = DARK_CLIENT_SECRET,
                    arm = {},
                ),
            )

        @DynamicPropertySource
        @JvmStatic
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            // US4-1: two enabled, healthy providers — declared in this order
            registry.add("sso.providers.$ALPHA_PROVIDER_ID.display-name") { ALPHA_DISPLAY_NAME }
            registry.add("sso.providers.$ALPHA_PROVIDER_ID.client-id") { ALPHA_CLIENT_ID }
            registry.add("sso.providers.$ALPHA_PROVIDER_ID.client-secret") { ALPHA_CLIENT_SECRET }
            registerHealthyEndpoints(registry, ALPHA_PROVIDER_ID)
            registry.add("sso.providers.$BETA_PROVIDER_ID.display-name") { "Beta IdP" }
            registry.add("sso.providers.$BETA_PROVIDER_ID.client-id") { BETA_CLIENT_ID }
            registry.add("sso.providers.$BETA_PROVIDER_ID.client-secret") { BETA_CLIENT_SECRET }
            registerHealthyEndpoints(registry, BETA_PROVIDER_ID)

            // US4-3: configured but disabled — hidden, 404 on authorize
            registry.add("sso.providers.$OFF_PROVIDER_ID.display-name") { "Off IdP" }
            registry.add("sso.providers.$OFF_PROVIDER_ID.enabled") { "false" }

            // US4-4: the IdP rejects the exchange — invalid_client
            registry.add("sso.providers.$WRONG_SECRET_PROVIDER_ID.display-name") { "Wrong Secret IdP" }
            registry.add("sso.providers.$WRONG_SECRET_PROVIDER_ID.client-id") { BETA_CLIENT_ID }
            registry.add("sso.providers.$WRONG_SECRET_PROVIDER_ID.client-secret") { WRONG_CLIENT_SECRET }
            registerHealthyEndpoints(registry, WRONG_SECRET_PROVIDER_ID)

            // US4-4: token endpoint failures of the degraded IdP
            registry.add("sso.providers.$BROKEN_PROVIDER_ID.display-name") { "Broken IdP" }
            registry.add("sso.providers.$BROKEN_PROVIDER_ID.client-id") { BROKEN_CLIENT_ID }
            registry.add("sso.providers.$BROKEN_PROVIDER_ID.client-secret") { BROKEN_CLIENT_SECRET }
            registerDegradedEndpoints(registry, BROKEN_PROVIDER_ID)

            // US4-4 "unreachable endpoints": the token exchange dials a dead port
            registry.add("sso.providers.$DARK_PROVIDER_ID.display-name") { "Dark IdP" }
            registry.add("sso.providers.$DARK_PROVIDER_ID.client-id") { DARK_CLIENT_ID }
            registry.add("sso.providers.$DARK_PROVIDER_ID.client-secret") { DARK_CLIENT_SECRET }
            registry.add("sso.providers.$DARK_PROVIDER_ID.authorization-uri") {
                "${degradedServer.baseUri}/mock-idp/authorize"
            }
            registry.add("sso.providers.$DARK_PROVIDER_ID.token-uri") {
                "http://$LOOPBACK_HOST:$closedPort/mock-idp/token"
            }
            registry.add("sso.providers.$DARK_PROVIDER_ID.jwks-uri") { "${degradedServer.baseUri}/mock-idp/jwks" }

            // T041: issuer-uri only (discovery branch) pointing at the dead
            // port — the registration can never be built
            registry.add("sso.providers.$LOST_DISCOVERY_PROVIDER_ID.display-name") { "Lost Discovery IdP" }
            registry.add("sso.providers.$LOST_DISCOVERY_PROVIDER_ID.client-id") { LOST_CLIENT_ID }
            registry.add("sso.providers.$LOST_DISCOVERY_PROVIDER_ID.client-secret") { LOST_CLIENT_SECRET }
            registry.add("sso.providers.$LOST_DISCOVERY_PROVIDER_ID.issuer-uri") {
                "http://$LOOPBACK_HOST:$closedPort"
            }
        }

        /** Explicit MockIdP endpoints of the healthy instance (research.md §12: tests never use discovery). */
        private fun registerHealthyEndpoints(
            registry: DynamicPropertyRegistry,
            providerId: String,
        ) = registerIdpEndpoints(registry, providerId, healthyServer)

        /** Explicit MockIdP endpoints of the degraded instance. */
        private fun registerDegradedEndpoints(
            registry: DynamicPropertyRegistry,
            providerId: String,
        ) = registerIdpEndpoints(registry, providerId, degradedServer)

        private fun registerIdpEndpoints(
            registry: DynamicPropertyRegistry,
            providerId: String,
            server: SsoResilienceIdpLoopbackServer,
        ) {
            registry.add("sso.providers.$providerId.authorization-uri") { "${server.baseUri}/mock-idp/authorize" }
            registry.add("sso.providers.$providerId.token-uri") { "${server.baseUri}/mock-idp/token" }
            registry.add("sso.providers.$providerId.jwks-uri") { "${server.baseUri}/mock-idp/jwks" }
        }

        @AfterAll
        @JvmStatic
        fun stopIdpServers() {
            healthyServer.stop()
            degradedServer.stop()
        }

        private val ipCounter = AtomicInteger()

        /** Unique sequential trace/span numbers for [nextTraceContext] (T044). */
        private val traceCounter = AtomicInteger()

        private const val ALPHA_PROVIDER_ID = "alpha"

        private const val BETA_PROVIDER_ID = "beta"

        private const val OFF_PROVIDER_ID = "off"

        private const val WRONG_SECRET_PROVIDER_ID = "wrong-secret"

        private const val BROKEN_PROVIDER_ID = "broken"

        private const val DARK_PROVIDER_ID = "dark"

        /** T041: the discovery-based provider whose issuer is unreachable. */
        private const val LOST_DISCOVERY_PROVIDER_ID = "lost-discovery"

        private const val ALPHA_DISPLAY_NAME = "Alpha IdP"

        private const val ALPHA_CLIENT_ID = "webchat-alpha"

        private const val ALPHA_CLIENT_SECRET = "alpha-resilience-secret"

        private const val BETA_CLIENT_ID = "webchat-beta"

        private const val BETA_CLIENT_SECRET = "beta-resilience-secret"

        private const val WRONG_CLIENT_SECRET = "rotated-away-secret"

        private const val BROKEN_CLIENT_ID = "webchat-broken"

        private const val BROKEN_CLIENT_SECRET = "broken-resilience-secret"

        private const val DARK_CLIENT_ID = "webchat-dark"

        private const val DARK_CLIENT_SECRET = "dark-resilience-secret"

        private const val LOST_CLIENT_ID = "webchat-lost"

        private const val LOST_CLIENT_SECRET = "lost-resilience-secret"

        private const val PASSWORD = "Str0ng-Resilience-IT-Pass!"

        private const val USERNAME_XANDER = "xander"

        private const val EMAIL_XANDER = "xander@example.com"

        private const val SUBJECT_URSA = "subject-ursa-7r2"

        private const val EMAIL_URSA = "ursa.resil@example.com"

        private const val SUBJECT_VICTOR = "subject-victor-9k5"

        private const val EMAIL_VICTOR = "victor.resil@example.com"

        private const val SUBJECT_WREN = "subject-wren-3m8"

        private const val EMAIL_WREN = "wren.resil@example.com"

        private const val SUBJECT_LOST = "subject-lost-4q6"

        private const val EMAIL_LOST = "lost.resil@example.com"

        /** T041: the static detail of the typed 502 authorize answer. */
        private const val PROVIDER_UNAVAILABLE_DETAIL = "Identity provider is temporarily unavailable"

        /** research.md §1/§5, SC-005: the overall callback budget of all provider calls. */
        private val CALLBACK_DEADLINE: Duration = Duration.ofSeconds(5)

        private const val CLIENT_IP_PREFIX = "198.51.100."

        private const val USERNAME_MAX_LENGTH = 24

        private const val TOKEN_43_LENGTH = 43

        private const val TOKEN_43 = "[A-Za-z0-9_-]{43}"

        private const val HANDSHAKE_KEY_PREFIX = "sso:handshake:"

        private const val FLOW_KEY_PREFIX = "sso:flow:"

        private const val LOOPBACK_HOST = "127.0.0.1"

        private const val PROVIDERS_PATH = "/api/v1/auth/sso/providers"

        private const val AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"

        private const val CALLBACK_PATH = "/api/v1/auth/sso/callback"

        private const val TOKEN_PATH = "/api/v1/auth/sso/token"

        private const val REGISTER_PATH = "/api/v1/auth/register"

        private const val CONFIRM_PATH = "/api/v1/auth/register/confirm"

        private const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"

        private const val LOGIN_PATH = "/api/v1/auth/login"

        private const val PROVIDER_ID_FIELD = "providerId"

        private const val STATE = "state"

        private const val CODE = "code"

        private const val ERROR_PARAMETER = "error"

        private const val SSO_ERROR_PARAMETER = "sso_error"

        private const val PROVIDER_DISABLED_CODE = "provider_disabled"

        private const val PROVIDER_ERROR_CODE = "provider_error"

        private const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"

        /** T044: W3C header driving the callback traceId so journal and log rows are correlatable. */
        private const val TRACEPARENT_HEADER = "traceparent"

        /** W3C ids: 32 hex chars traceId / 16 hex chars spanId. */
        private const val TRACE_ID_HEX_FORMAT = "%032x"

        private const val SPAN_ID_HEX_FORMAT = "%016x"
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
 * Spring context. The pool keeps a slow token endpoint (the DELAY scenario)
 * from blocking parallel IdP calls.
 */
private class SsoResilienceIdpLoopbackServer(
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
