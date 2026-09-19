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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * T045 [US5] (tasks.md Phase 7, Test-First): the flow-integrity acceptance of
 * US5-2/SC-006/FR-012 over the real HTTP port of the `AbstractIntegrationTest`
 * context through the MockIdP (T018) — replayed and forged provider answers
 * are rejected BEFORE any effect: no session, no binding, no account.
 *
 * Covered attacks:
 * - replayed state (US5-2): the callback URL of an ALREADY COMPLETED flow is
 *   replayed verbatim — the `GETDEL` consumption of `sso:flow:<state>`
 *   (T013) leaves nothing to consume a second time, so the replay ends with
 *   `sso_error=invalid_state` and zero new effects (SC-006); a FAILED
 *   callback is no different: its state was consumed on the first attempt,
 *   the replay of the very same URL is `invalid_state` again;
 * - forged/missing state: a state value never issued by authorize answers
 *   the single `invalid_state` (contracts/sso-api.md §3);
 * - substituted nonce: the IdP mints an ID token whose `nonce` does not match
 *   the flow (the [MockIdP.ControlledClaims.nonce] override) — verification
 *   rejects it (T015 "mismatched nonce") with `provider_error` and a
 *   `sso_flow_error` journal entry, nothing is created;
 * - another tab's code (spec Edge Cases "несколько вкладок"): the code minted
 *   for flow B cannot finish flow A — the server-side PKCE verifier of A
 *   mismatches the challenge of B, the IdP exchange fails, the flow ends with
 *   `provider_error` and no effects; every OTHER flow stays independent and
 *   completes (US5-2 "каждый флоу проверяется независимо");
 * - single-use handshake (FR-012): a replayed `POST /auth/sso/token` with the
 *   SAME handshake code answers the uniform 400 `invalid_code` and opens no
 *   second session (the `GETDEL` of `sso:handshake:<sha256(code)>`, T013);
 * - ID token without `sub` (spec Edge Cases): an absent or empty subject
 *   claim — the MockIdP omits `sub` entirely for both — rejects the flow
 *   (T015 "empty subject") with `provider_error` + `sso_flow_error`;
 * - client secret rotation between authorize and callback (spec Edge Cases
 *   "секрет скомпрометирован/ротирован"): the IdP-side client record is
 *   re-registered with a NEW secret after the flow started, so the backend's
 *   exchange presents the stale one — `invalid_client`, the callback ends
 *   with `provider_error`, no session is created, and no secret value ever
 *   reaches the journal (FR-011/SC-004).
 *
 * The IdP half of each flow runs on a dedicated loopback mount of [MockIdP]
 * — the SsoFlowIT pattern: provider endpoints must be absolute URIs known
 * while `@DynamicPropertySource` binds `sso.providers.*`, before the random
 * application port becomes observable. Logins ride the JIT branch (US2-1,
 * verified email, no pre-existing account), so every test grows its own
 * account through the real flow; the 192.0.2.x documentation IPs keep the
 * 002 IP rate-limit buckets out of the picture.
 *
 * tasks.md T045 mandates the whole US5-2 acceptance in this single IT
 * file (SessionIT precedent).
 */
@Suppress("LargeClass")
class SsoSecurityIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun resetIdp() {
        mockIdP.reset()
        // registerClient survives MockIdP.reset() by design — the
        // secret-rotation test re-registers the client, so the correct
        // credential is restored BEFORE every test regardless of order
        mockIdP.registerClient(CLIENT_ID, CLIENT_SECRET)
    }

    @Test
    fun `replayed callback of a completed flow is rejected before any effects`() {
        mockIdP.setClaims(idpClaims(SUBJECT_REPLAY, EMAIL_REPLAY))
        val flow = startFlow()
        val url = callbackUrl(flow)

        val first = callback(url)
        assertThat(first.statusCode).isEqualTo(HttpStatus.FOUND)
        val successParameters = queryParametersOf(first.headers.location.toString())
        assertThat(successParameters).doesNotContainKey(SSO_ERROR_PARAMETER)
        assertThat(successParameters.getValue(CODE)).matches(TOKEN_43)

        // the completed flow leaves exactly its own effects (JIT account +
        // handshake); the replay must add NOTHING on top of them
        val effectsAfterSuccess = effectsNow()

        val replay = callback(url)

        // US5-2/SC-006: the state was consumed by GETDEL — the replay is the single invalid_state
        assertThat(replay.statusCode).isEqualTo(HttpStatus.FOUND)
        val replayParameters = queryParametersOf(replay.headers.location.toString())
        assertThat(replayParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(INVALID_STATE)
        assertThat(replayParameters).doesNotContainKey(CODE)

        // no session, no binding, no account, no handshake beyond the first success
        assertThat(effectsNow()).isEqualTo(effectsAfterSuccess)

        // the rejection is journaled with the non-secret reason marker (FR-011)
        assertThat(latestFlowErrorDetails(INVALID_STATE)).contains(INVALID_STATE)
    }

    @Test
    fun `forged or missing state is rejected as invalid_state without effects`() {
        mockIdP.setClaims(idpClaims(SUBJECT_FORGED, EMAIL_FORGED))
        val effectsBefore = effectsNow()

        // a state value authorize never issued (attacker-crafted, same 43-char shape)
        val forged = callbackUrl(StartedFlow(state = flowToken("forged-state"), code = flowToken("forged-code")))
        val forgedResponse = callback(forged)
        assertThat(forgedResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val forgedParameters = queryParametersOf(forgedResponse.headers.location.toString())
        assertThat(forgedParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(INVALID_STATE)
        assertThat(forgedParameters).doesNotContainKey(CODE)

        // no state parameter at all — the same single answer
        val bare =
            UriComponentsBuilder
                .fromHttpUrl(rootUri() + CALLBACK_PATH)
                .queryParam(CODE, flowToken("orphan-code"))
                .build()
                .toUriString()
        val bareResponse = callback(bare)
        assertThat(bareResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        assertThat(queryParametersOf(bareResponse.headers.location.toString()).getValue(SSO_ERROR_PARAMETER))
            .isEqualTo(INVALID_STATE)

        assertThat(effectsNow()).isEqualTo(effectsBefore)
    }

    @Test
    fun `substituted nonce in the id token rejects the flow and its replay`() {
        mockIdP.setClaims(
            MockIdP.ControlledClaims(
                subject = SUBJECT_NONCE,
                email = EMAIL_NONCE,
                emailVerified = true,
                // a provider that misbinds the flow: the minted token carries a foreign nonce
                nonce = flowToken("forged-nonce"),
            ),
        )
        val effectsBefore = effectsNow()
        val url = callbackUrl(startFlow())

        val first = callback(url)

        // T015: nonce mismatch fails verification — the single provider_error, never a session
        assertThat(first.statusCode).isEqualTo(HttpStatus.FOUND)
        val firstParameters = queryParametersOf(first.headers.location.toString())
        assertThat(firstParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR)
        assertThat(firstParameters).doesNotContainKey(CODE)

        // the failed attempt still consumed the state: the replay of the SAME url is invalid_state
        val replay = callback(url)
        val replayParameters = queryParametersOf(replay.headers.location.toString())
        assertThat(replayParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(INVALID_STATE)

        // SC-006: neither attempt created anything
        assertThat(effectsNow()).isEqualTo(effectsBefore)

        // FR-011: journaled as sso_flow_error with non-secret markers only
        val details = latestFlowErrorDetails(PROVIDER_ERROR)
        assertThat(details).contains(PROVIDER_ID).contains(PROVIDER_ERROR)
        assertThat(details).doesNotContain(CLIENT_SECRET)
    }

    @Test
    fun `a code minted for another tab cannot finish this flow`() {
        mockIdP.setClaims(idpClaims(SUBJECT_CROSS_TAB, EMAIL_CROSS_TAB))
        val effectsBefore = effectsNow()

        // two independent flows — two tabs of the same user
        val tabA = startFlow()
        val tabB = startFlow()

        // tab A presents ITS state with tab B's code: the PKCE verifier of A
        // mismatches the challenge the code was bound to — the exchange fails
        val mixed = callback(callbackUrl(tabA.state, tabB.code))
        assertThat(mixed.statusCode).isEqualTo(HttpStatus.FOUND)
        val mixedParameters = queryParametersOf(mixed.headers.location.toString())
        assertThat(mixedParameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR)
        assertThat(mixedParameters).doesNotContainKey(CODE)

        // US5-2 "каждый флоу проверяется независимо": no account/binding/session from the mixed-up finish
        assertThat(effectsNow()).isEqualTo(effectsBefore)

        // and the user is not locked out: a FRESH flow still completes end to end
        val fresh = performSsoLogin()
        assertThat(fresh.body["user"]["email"].asText()).isEqualTo(EMAIL_CROSS_TAB)
        assertThat(usersCount()).isEqualTo(effectsBefore.users + 1)
    }

    @Test
    fun `handshake code is single-use - the replayed exchange answers 400 invalid_code without a second session`() {
        mockIdP.setClaims(idpClaims(SUBJECT_HANDSHAKE, EMAIL_HANDSHAKE))
        val effectsBefore = effectsNow()
        val login = performSsoLogin()

        // exactly one session for exactly one JIT account — the baseline of FR-012;
        // the exchanged handshake was consumed (GETDEL), so its key count is unchanged
        val effectsAfterLogin = effectsNow()
        assertThat(effectsAfterLogin.users).isEqualTo(effectsBefore.users + 1)
        assertThat(effectsAfterLogin.identities).isEqualTo(effectsBefore.identities + 1)
        assertThat(effectsAfterLogin.sessions).isEqualTo(effectsBefore.sessions + 1)
        assertThat(effectsAfterLogin.handshakes).isEqualTo(effectsBefore.handshakes)
        assertThat(sessionsOfUser(login.body["user"]["id"].asText())).hasSize(1)

        val replay = postJson(TOKEN_PATH, mapOf(CODE to login.handshakeCode), nextClientIp())

        // contracts/sso-api.md §4: one uniform answer for a used code — no cause disclosure
        assertThat(replay.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(replay.headers.contentType.toString()).contains("application/problem+json")
        val problem = objectMapper.readTree(replay.body)
        assertThat(problem["errors"]["code"][0].asText()).isEqualTo(INVALID_CODE)

        // FR-012: the replay opened NO second session and created nothing else
        assertThat(effectsNow()).isEqualTo(effectsAfterLogin)
        assertThat(sessionsOfUser(login.body["user"]["id"].asText())).hasSize(1)
    }

    @ParameterizedTest(name = "sub={0}")
    @MethodSource("emptySubjects")
    fun `id token without sub claim is rejected and journaled as sso_flow_error`(emptySubject: String?) {
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = emptySubject, email = EMAIL_NO_SUB, emailVerified = true))
        val effectsBefore = effectsNow()

        val response = callback(callbackUrl(startFlow()))

        // T015: a provider without a unique subject is misconfigured — the flow is rejected
        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        val parameters = queryParametersOf(response.headers.location.toString())
        assertThat(parameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR)
        assertThat(parameters).doesNotContainKey(CODE)

        // spec Edge Cases: rejection + sso_flow_error, and nothing is created
        assertThat(effectsNow()).isEqualTo(effectsBefore)
        assertThat(latestFlowErrorDetails(PROVIDER_ERROR)).contains(PROVIDER_ID).contains(PROVIDER_ERROR)
    }

    @Test
    fun `provider client secret rotated between authorize and callback rejects the flow`() {
        mockIdP.setClaims(idpClaims(SUBJECT_ROTATED, EMAIL_ROTATED))
        val flow = startFlow()
        val effectsBefore = effectsNow()

        // the IdP rotates the client credential after the flow started: the
        // backend still presents the stale secret (spec Edge Cases "секрет
        // скомпрометирован/ротирован") — the exchange is invalid_client
        mockIdP.registerClient(CLIENT_ID, ROTATED_CLIENT_SECRET)

        val response = callback(callbackUrl(flow))

        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        val parameters = queryParametersOf(response.headers.location.toString())
        assertThat(parameters.getValue(SSO_ERROR_PARAMETER)).isEqualTo(PROVIDER_ERROR)
        assertThat(parameters).doesNotContainKey(CODE)

        // no session for the rotated-away flow, no account, no binding, no handshake
        assertThat(effectsNow()).isEqualTo(effectsBefore)

        // FR-011/SC-004: the failure is journaled with markers only — neither secret ever appears
        val details = latestFlowErrorDetails(PROVIDER_ERROR)
        assertThat(details).contains(PROVIDER_ID).contains(PROVIDER_ERROR)
        assertThat(details)
            .doesNotContain(CLIENT_SECRET)
            .doesNotContain(ROTATED_CLIENT_SECRET)
    }

    // --- SSO flow driving ---------------------------------------------------

    /** One tab's flow legs: the state of the backend context and the IdP-issued code. */
    private data class StartedFlow(
        val state: String,
        val code: String,
    )

    /** authorize → IdP redirect: returns the single-use state/code pair of this tab. */
    private fun startFlow(): StartedFlow {
        val authorizeResponse = postJson(AUTHORIZE_PATH, mapOf(PROVIDER_ID_FIELD to PROVIDER_ID), nextClientIp())
        assertThat(authorizeResponse.statusCode).isEqualTo(HttpStatus.OK)
        val authorizationUrl = objectMapper.readTree(authorizeResponse.body)["authorizationUrl"].asText()

        val idpRedirect = noRedirectClient.getForEntity(URI.create(authorizationUrl), String::class.java)
        assertThat(idpRedirect.statusCode).isEqualTo(HttpStatus.FOUND)
        val parameters = queryParametersOf(idpRedirect.headers.location.toString())
        assertThat(parameters).doesNotContainKey(ERROR_PARAMETER)

        return StartedFlow(state = parameters.getValue(STATE), code = parameters.getValue(CODE))
    }

    private fun callbackUrl(flow: StartedFlow): String = callbackUrl(flow.state, flow.code)

    private fun callbackUrl(
        state: String,
        code: String,
    ): String =
        UriComponentsBuilder
            .fromHttpUrl(rootUri() + CALLBACK_PATH)
            .queryParam(STATE, state)
            .queryParam(CODE, code)
            .build()
            .toUriString()

    /** The browser callback leg over the real port — never follows the SPA 302. */
    private fun callback(url: String): ResponseEntity<String> =
        noRedirectClient.exchange(
            URI.create(url),
            HttpMethod.GET,
            HttpEntity<String>(HttpHeaders().apply { set(X_FORWARDED_FOR_HEADER, nextClientIp()) }),
            String::class.java,
        )

    /** The whole US1 choreography including the token exchange; the caller arms the IdP claims first. */
    private fun performSsoLogin(): SsoLoginResult {
        val callbackResponse = callback(callbackUrl(startFlow()))
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

    /**
     * TestRestTemplate's JDK client follows redirects, which would chase the
     * 302 Locations straight into the SPA; the authorize and callback
     * assertions need the raw FOUND responses (SsoFlowIT precedent).
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

    /** The full side-effect footprint the SC-006 "before any effects" asserts compare. */
    private data class EffectsSnapshot(
        val users: Int,
        val identities: Int,
        val sessions: Int,
        val handshakes: Int,
    )

    private fun effectsNow(): EffectsSnapshot =
        EffectsSnapshot(
            users = usersCount(),
            identities = identitiesCount(),
            sessions = sessionsCount(),
            handshakes = handshakeKeyCount(),
        )

    private fun queryParametersOf(url: String): Map<String, String> =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .map { (name, values) -> name to URLDecoder.decode(values.first(), StandardCharsets.UTF_8) }
            .toMap()

    private fun usersCount(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Int::class.java)!!

    private fun identitiesCount(): Int =
        jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM external_identities", Int::class.java)!!

    private fun sessionsCount(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sessions", Int::class.java)!!

    private fun sessionsOfUser(userId: String): List<Map<String, Any>> =
        jdbcTemplate.queryForList(
            """
            SELECT id, status::text AS status, auth_method FROM sessions
            WHERE user_id = ?::uuid
            """.trimIndent(),
            userId,
        )

    private fun handshakeKeyCount(): Int = redisTemplate.keys("$HANDSHAKE_KEY_PREFIX*").orEmpty().size

    /** Details of the last `sso_flow_error` carrying the given public reason marker (FR-011). */
    private fun latestFlowErrorDetails(reason: String): String? =
        jdbcTemplate
            .queryForList(
                """
                SELECT details::text AS details FROM auth_events
                WHERE event_type = 'sso_flow_error' AND details->>'reason' = ?
                ORDER BY occurred_at DESC
                """.trimIndent(),
                String::class.java,
                reason,
            ).firstOrNull()

    private fun idpClaims(
        subject: String,
        email: String,
    ): MockIdP.ControlledClaims = MockIdP.ControlledClaims(subject = subject, email = email, emailVerified = true)

    /** 43-char base64url-shaped value for state/nonce/code stand-ins. */
    private fun flowToken(seed: String): String = seed.padEnd(TOKEN_43_LENGTH, '-').take(TOKEN_43_LENGTH)

    private fun nextClientIp(): String = "$CLIENT_IP_PREFIX${ipCounter.incrementAndGet()}"

    private data class SsoLoginResult(
        val accessToken: String,
        val refreshToken: String,
        val handshakeCode: String,
        val body: JsonNode,
    )

    companion object {
        /** The same MockIdP controller class as the context bean (T018), driven directly by this suite. */
        private val mockIdP =
            MockIdP(ObjectMapper()).apply {
                registerClient(CLIENT_ID, CLIENT_SECRET)
            }

        private val idpServer = SsoSecurityIdpLoopbackServer(mockIdP)

        /** The `sub` shapes that must be rejected: claim absent (`null`) and claim empty (T015 "empty subject"). */
        @JvmStatic
        fun emptySubjects(): List<String?> = listOf(null, "")

        @DynamicPropertySource
        @JvmStatic
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("sso.providers.$PROVIDER_ID.display-name") { PROVIDER_DISPLAY_NAME }
            registry.add("sso.providers.$PROVIDER_ID.client-id") { CLIENT_ID }
            registry.add("sso.providers.$PROVIDER_ID.client-secret") { CLIENT_SECRET }
            registry.add("sso.providers.$PROVIDER_ID.authorization-uri") { "${idpServer.baseUri}/mock-idp/authorize" }
            registry.add("sso.providers.$PROVIDER_ID.token-uri") { "${idpServer.baseUri}/mock-idp/token" }
            registry.add("sso.providers.$PROVIDER_ID.jwks-uri") { "${idpServer.baseUri}/mock-idp/jwks" }
        }

        @AfterAll
        @JvmStatic
        fun stopIdpServer() {
            idpServer.stop()
        }

        private val ipCounter = AtomicInteger()

        private const val PROVIDER_ID = "mock-idp"

        private const val PROVIDER_DISPLAY_NAME = "Mock IdP"

        private const val CLIENT_ID = "webchat-security-it"

        private const val CLIENT_SECRET = "security-it-secret"

        /** The IdP-side value the rotation test re-registers the client with. */
        private const val ROTATED_CLIENT_SECRET = "rotated-away-security-secret"

        private const val SUBJECT_REPLAY = "subject-replay-6t2"

        private const val EMAIL_REPLAY = "replay.sec@example.com"

        private const val SUBJECT_FORGED = "subject-forged-4n8"

        private const val EMAIL_FORGED = "forged.sec@example.com"

        private const val SUBJECT_NONCE = "subject-nonce-8k4"

        private const val EMAIL_NONCE = "nonce.sec@example.com"

        private const val SUBJECT_CROSS_TAB = "subject-crosstab-3v9"

        private const val EMAIL_CROSS_TAB = "crosstab.sec@example.com"

        private const val SUBJECT_HANDSHAKE = "subject-handshake-7j1"

        private const val EMAIL_HANDSHAKE = "handshake.sec@example.com"

        private const val EMAIL_NO_SUB = "nosub.sec@example.com"

        private const val SUBJECT_ROTATED = "subject-rotated-9m3"

        private const val EMAIL_ROTATED = "rotated.sec@example.com"

        private const val TOKEN_43 = "[A-Za-z0-9_-]{43}"

        private const val TOKEN_43_LENGTH = 43

        private const val HANDSHAKE_KEY_PREFIX = "sso:handshake:"

        private const val CLIENT_IP_PREFIX = "192.0.2."

        private const val AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"

        private const val CALLBACK_PATH = "/api/v1/auth/sso/callback"

        private const val TOKEN_PATH = "/api/v1/auth/sso/token"

        private const val PROVIDER_ID_FIELD = "providerId"

        private const val STATE = "state"

        private const val CODE = "code"

        private const val ERROR_PARAMETER = "error"

        private const val SSO_ERROR_PARAMETER = "sso_error"

        private const val INVALID_STATE = "invalid_state"

        private const val PROVIDER_ERROR = "provider_error"

        private const val INVALID_CODE = "invalid_code"

        private const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
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
private class SsoSecurityIdpLoopbackServer(
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
