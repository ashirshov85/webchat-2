package webchat.backend.sso

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.AbstractIntegrationTest
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * T018 acceptance of the MockIdP itself (research.md §12): the test controller
 * rises in the `AbstractIntegrationTest` context and carries the full provider
 * half of the flow — authorize → 302 with code → token endpoint → ID-token
 * signature verification against the JWKS endpoint — with the controlled
 * `sub`/`email`/`email_verified`/`nonce` claims, plus every managed failure
 * scenario (HTTP 500, delay > timeout, wrong secret, substituted/replayed
 * code, withdrawn consent). The first END-TO-END run through the backend SSO
 * endpoints is the anchor T019/T026 (`SsoFlowIT`).
 *
 * T055 acceptance (research.md §21): the oauth2-mode endpoints rise in the
 * same Testcontainers context — token answer without `id_token`, userinfo
 * JSON with configurable field names (the Yandex `psuid`/`default_email`
 * shape), the managed userinfo failures (unavailable / non-2xx / omitted
 * subject or email claim) — while the OIDC mode stays untouched (the suite
 * above is its regression, plus an explicit no-leak test).
 */
class MockIdpIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val mockIdP: MockIdP,
    @Autowired private val objectMapper: ObjectMapper,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun resetIdp() {
        mockIdP.reset()
    }

    @Test
    fun `authorize issues a single-use code and redirects to the callback with state`() {
        val response = authorize()

        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        val parameters = queryParameters(response)
        assertThat(parameters[CODE]).isNotBlank
        assertThat(parameters[STATE]).isEqualTo(STATE_VALUE)
        assertThat(parameters).doesNotContainKey(ERROR)
    }

    @Test
    fun `full flow exchanges code for a jwks-verifiable id token with controlled claims`() {
        mockIdP.setClaims(
            MockIdP.ControlledClaims(subject = SUBJECT, email = EMAIL, emailVerified = true),
        )
        val code = issueCode(codeChallenge = s256(VERIFIER))

        val response = exchangeCode(code)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        assertThat(body.path(TOKEN_TYPE).asText()).isEqualTo(BEARER)
        assertThat(body.path(EXPIRES_IN).asInt()).isPositive()
        assertThat(body.path(ACCESS_TOKEN).asText()).isNotBlank()

        val jwt = jwksDecoder().decode(body.path(ID_TOKEN).asText())
        assertThat(jwt.subject).isEqualTo(SUBJECT)
        assertThat(jwt.getClaimAsString(EMAIL_CLAIM)).isEqualTo(EMAIL)
        assertThat(jwt.getClaimAsString(EMAIL_VERIFIED_CLAIM)).isEqualTo(TRUE)
        assertThat(jwt.getClaimAsString(NONCE_CLAIM)).isEqualTo(NONCE_VALUE)
        assertThat(jwt.audience).containsExactly(MockIdP.DEFAULT_CLIENT_ID)
        assertThat(jwt.issuer.toString()).isEqualTo(MockIdP.DEFAULT_ISSUER)
    }

    @Test
    fun `nonce override replaces the authorize-time nonce in the id token`() {
        mockIdP.setClaims(MockIdP.ControlledClaims(nonce = "attacker-nonce"))
        val code = issueCode()

        val jwt = jwksDecoder().decode(idTokenOf(exchangeCode(code)))

        assertThat(jwt.getClaimAsString(NONCE_CLAIM)).isEqualTo("attacker-nonce")
    }

    @Test
    fun `controlled claims can drop the subject and the email entirely`() {
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = null, email = null))
        val code = issueCode()

        val jwt = jwksDecoder().decode(idTokenOf(exchangeCode(code)))

        assertThat(jwt.subject).isNull()
        assertThat(jwt.getClaimAsString(EMAIL_CLAIM)).isNull()
        assertThat(jwt.getClaimAsString(EMAIL_VERIFIED_CLAIM)).isNull()
    }

    @Test
    fun `withdrawn consent redirects back with error access_denied and no code`() {
        mockIdP.consentDenied = true

        val response = authorize()

        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        val parameters = queryParameters(response)
        assertThat(parameters[ERROR]).isEqualTo("access_denied")
        assertThat(parameters[STATE]).isEqualTo(STATE_VALUE)
        assertThat(parameters).doesNotContainKey(CODE)
    }

    @Test
    fun `http 500 flag makes the token endpoint answer a server error`() {
        mockIdP.tokenEndpointFailure = MockIdP.TokenEndpointFailure.HTTP_500

        val response = exchangeCode("any-code")

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(objectMapper.readTree(response.body).path(ERROR).asText()).isEqualTo("server_error")
    }

    @Test
    fun `delay flag exceeds the caller read timeout`() {
        mockIdP.tokenEndpointFailure = MockIdP.TokenEndpointFailure.DELAY
        val impatientClient =
            RestTemplate(
                SimpleClientHttpRequestFactory().apply { setReadTimeout(FAST_READ_TIMEOUT_MILLIS) },
            )

        assertThatThrownBy {
            impatientClient.postForEntity(
                URI.create(rootUri() + "/mock-idp/token"),
                HttpEntity(tokenForm("any-code", VERIFIER), tokenHeaders()),
                String::class.java,
            )
        }.isInstanceOf(ResourceAccessException::class.java)
    }

    @Test
    fun `wrong client secret is rejected with 401 invalid_client`() {
        val code = issueCode()

        val response = exchangeCode(code, clientSecret = "wrong-secret")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(response.body).path(ERROR).asText()).isEqualTo("invalid_client")
    }

    @Test
    fun `substituted code is rejected with 400 invalid_grant`() {
        val response = exchangeCode("forged-code")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(response.body).path(ERROR).asText()).isEqualTo("invalid_grant")
    }

    @Test
    fun `code is single-use - a replay is rejected with 400 invalid_grant`() {
        val code = issueCode(codeChallenge = s256(VERIFIER))
        assertThat(exchangeCode(code).statusCode).isEqualTo(HttpStatus.OK)

        val replay = exchangeCode(code)

        assertThat(replay.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(replay.body).path(ERROR).asText()).isEqualTo("invalid_grant")
    }

    @Test
    fun `pkce verifier mismatch is rejected with 400 invalid_grant`() {
        val code = issueCode(codeChallenge = s256(VERIFIER))

        val response = exchangeCode(code, codeVerifier = "wrong-verifier")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(objectMapper.readTree(response.body).path(ERROR).asText()).isEqualTo("invalid_grant")
    }

    // T055 (US6, research.md §21): the oauth2-mode half of the MockIdP

    @Test
    fun `oauth2 token answer omits id_token and its access token opens userinfo`() {
        // the pkce:false shape — neither a code_challenge nor a code_verifier
        val code = issueOauth2Code()

        val response = exchangeOauth2Code(code)
        val body = objectMapper.readTree(response.body)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(body.path(ACCESS_TOKEN).asText()).isNotBlank
        assertThat(body.path(TOKEN_TYPE).asText()).isEqualTo(BEARER)
        assertThat(body.has(ID_TOKEN)).isFalse

        val userinfo = userinfo(body.path(ACCESS_TOKEN).asText())
        assertThat(userinfo.statusCode).isEqualTo(HttpStatus.OK)
        val profile = objectMapper.readTree(userinfo.body)
        assertThat(profile.path(PSUID_CLAIM).asText()).isEqualTo(MockIdP.DEFAULT_SUBJECT)
        assertThat(profile.path(DEFAULT_EMAIL_CLAIM).asText()).isEqualTo("mock-user@example.com")
        // the default shape is provider-guaranteed: no email_verified field
        assertThat(profile.has(EMAIL_VERIFIED_CLAIM)).isFalse
    }

    @Test
    fun `userinfo field names and values are configurable for claim mapping`() {
        mockIdP.setUserinfoClaims(
            MockIdP.UserinfoClaims(
                subjectClaim = "uid",
                emailClaim = "mail",
                subject = "mapping-subject-7",
                email = "mapped@example.com",
                emailVerified = true,
            ),
        )
        val accessToken = oauth2AccessToken()

        val profile = objectMapper.readTree(userinfo(accessToken).body)

        assertThat(profile.path("uid").asText()).isEqualTo("mapping-subject-7")
        assertThat(profile.path("mail").asText()).isEqualTo("mapped@example.com")
        assertThat(profile.path(EMAIL_VERIFIED_CLAIM).asBoolean()).isTrue
        assertThat(profile.has(PSUID_CLAIM)).isFalse
        assertThat(profile.has(DEFAULT_EMAIL_CLAIM)).isFalse
    }

    @Test
    fun `userinfo rejects a missing or foreign bearer token with 401`() {
        assertThat(userinfo(accessToken = null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val response = userinfo("forged-access-token")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(response.body).path(ERROR).asText()).isEqualTo("invalid_token")
    }

    @Test
    fun `userinfo unavailable flag delays beyond the caller read timeout`() {
        mockIdP.userinfoFailure = MockIdP.UserinfoFailure.UNAVAILABLE
        val impatientClient =
            RestTemplate(
                SimpleClientHttpRequestFactory().apply { setReadTimeout(FAST_READ_TIMEOUT_MILLIS) },
            )

        assertThatThrownBy {
            impatientClient.exchange(
                URI.create(rootUri() + OAUTH2_USERINFO_PATH),
                HttpMethod.GET,
                HttpEntity<String>(HttpHeaders()),
                String::class.java,
            )
        }.isInstanceOf(ResourceAccessException::class.java)
    }

    @Test
    fun `userinfo non-2xx flag answers a server error`() {
        mockIdP.userinfoFailure = MockIdP.UserinfoFailure.NOT_2XX

        val response = userinfo(oauth2AccessToken())

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(objectMapper.readTree(response.body).path(ERROR).asText()).isEqualTo("server_error")
    }

    @Test
    fun `omitted subject or email claims are absent from the userinfo json`() {
        mockIdP.setUserinfoClaims(MockIdP.UserinfoClaims(subject = null, email = null))

        val profile = objectMapper.readTree(userinfo(oauth2AccessToken()).body)

        assertThat(profile.has(PSUID_CLAIM)).isFalse
        assertThat(profile.has(DEFAULT_EMAIL_CLAIM)).isFalse
        assertThat(profile.has(EMAIL_VERIFIED_CLAIM)).isFalse
    }

    @Test
    fun `oauth2 scenario state does not leak into the oidc token endpoint`() {
        mockIdP.userinfoFailure = MockIdP.UserinfoFailure.NOT_2XX
        mockIdP.setUserinfoClaims(
            MockIdP.UserinfoClaims(subjectClaim = "uid", subject = "other-subject", email = null),
        )
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = SUBJECT, email = EMAIL, emailVerified = true))
        val code = issueCode(codeChallenge = s256(VERIFIER))

        val jwt = jwksDecoder().decode(idTokenOf(exchangeCode(code)))

        assertThat(jwt.subject).isEqualTo(SUBJECT)
        assertThat(jwt.getClaimAsString(EMAIL_CLAIM)).isEqualTo(EMAIL)
    }

    private fun issueCode(codeChallenge: String? = null): String {
        val response = authorize(path = AUTHORIZE_PATH, codeChallenge = codeChallenge)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        return queryParameters(response).getValue(CODE)
    }

    private fun issueOauth2Code(): String {
        val response = authorize(path = OAUTH2_AUTHORIZE_PATH)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FOUND)
        return queryParameters(response).getValue(CODE)
    }

    /** Full oauth2 leg: code → access token, for tests that only need the profile call. */
    private fun oauth2AccessToken(): String {
        val body = objectMapper.readTree(exchangeOauth2Code(issueOauth2Code()).body)
        return body.path(ACCESS_TOKEN).asText()
    }

    /**
     * TestRestTemplate's JDK client follows redirects, which would chase the
     * 302 Location straight into the (unreachable) redirect_uri; the authorize
     * assertions need the raw FOUND response with its Location header.
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

    private fun authorize(
        path: String = AUTHORIZE_PATH,
        codeChallenge: String? = null,
    ): ResponseEntity<String> {
        val builder =
            UriComponentsBuilder
                .fromPath(path)
                .queryParam(CLIENT_ID, MockIdP.DEFAULT_CLIENT_ID)
                .queryParam(REDIRECT_URI, CALLBACK_URI)
                .queryParam(RESPONSE_TYPE, "code")
                .queryParam(SCOPE, "openid email")
                .queryParam(STATE, STATE_VALUE)
                .queryParam(NONCE_CLAIM, NONCE_VALUE)
        codeChallenge?.let {
            builder.queryParam(CODE_CHALLENGE, it)
            builder.queryParam(CODE_CHALLENGE_METHOD, "S256")
        }
        return noRedirectClient.getForEntity(rootUri() + builder.build().toUriString(), String::class.java)
    }

    private fun exchangeCode(
        code: String,
        codeVerifier: String = VERIFIER,
        clientSecret: String = MockIdP.DEFAULT_CLIENT_SECRET,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            AUTHORIZE_TOKEN_PATH,
            HttpEntity(tokenForm(code, codeVerifier), tokenHeaders(clientSecret)),
            String::class.java,
        )

    /** OAuth2-mode exchange: no code_verifier leg (the `pkce: false` shape, research.md §18). */
    private fun exchangeOauth2Code(
        code: String,
        clientSecret: String = MockIdP.DEFAULT_CLIENT_SECRET,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            OAUTH2_TOKEN_PATH,
            HttpEntity(tokenForm(code, codeVerifier = null), tokenHeaders(clientSecret)),
            String::class.java,
        )

    private fun userinfo(accessToken: String?): ResponseEntity<String> {
        val headers = HttpHeaders()
        accessToken?.let(headers::setBearerAuth)
        return restTemplate.exchange(
            OAUTH2_USERINFO_PATH,
            HttpMethod.GET,
            HttpEntity<String>(headers),
            String::class.java,
        )
    }

    private fun tokenHeaders(clientSecret: String = MockIdP.DEFAULT_CLIENT_SECRET): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            setBasicAuth(MockIdP.DEFAULT_CLIENT_ID, clientSecret)
        }

    private fun tokenForm(
        code: String,
        codeVerifier: String?,
    ): MultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply {
            add(GRANT_TYPE, "authorization_code")
            add(CODE, code)
            add(REDIRECT_URI, CALLBACK_URI)
            codeVerifier?.let { add(CODE_VERIFIER, it) }
        }

    /** Verification machinery mirrors the production OidcClient: Nimbus against the JWKS endpoint. */
    private fun jwksDecoder(): JwtDecoder =
        NimbusJwtDecoder
            .withJwkSetUri(rootUri() + "/mock-idp/jwks")
            .build()

    private fun idTokenOf(response: ResponseEntity<String>): String {
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body).path(ID_TOKEN).asText()
    }

    private fun queryParameters(response: ResponseEntity<String>): Map<String, String> {
        val location = response.headers.location
        assertThat(location).isNotNull
        return UriComponentsBuilder
            .fromUriString(location.toString())
            .build()
            .queryParams
            .map { (name, values) -> name to values.first() }
            .toMap()
    }

    private fun rootUri(): String = restTemplate.rootUri.removeSuffix("/")

    private fun s256(verifier: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance(SHA_256).digest(verifier.toByteArray()))

    private companion object {
        const val CALLBACK_URI = "http://localhost:5173/api/v1/auth/sso/callback"

        const val AUTHORIZE_PATH = "/mock-idp/authorize"

        const val AUTHORIZE_TOKEN_PATH = "/mock-idp/token"

        const val OAUTH2_AUTHORIZE_PATH = "/mock-idp/oauth2/authorize"

        const val OAUTH2_TOKEN_PATH = "/mock-idp/oauth2/token"

        const val OAUTH2_USERINFO_PATH = "/mock-idp/oauth2/userinfo"

        /** The Yandex-shaped userinfo field names (research.md §20). */
        const val PSUID_CLAIM = "psuid"

        const val DEFAULT_EMAIL_CLAIM = "default_email"

        const val STATE_VALUE = "it-state-1a2b3c4d5e6f"

        const val NONCE_VALUE = "it-nonce-0f9e8d7c6b5a"

        const val VERIFIER = "it-code-verifier-0123456789abcdefghijklmnopqrstuv"

        const val SUBJECT = "subject-42"

        const val EMAIL = "mock-it-user@example.com"

        const val FAST_READ_TIMEOUT_MILLIS = 500

        const val CLIENT_ID = "client_id"

        const val REDIRECT_URI = "redirect_uri"

        const val RESPONSE_TYPE = "response_type"

        const val GRANT_TYPE = "grant_type"

        const val CODE_VERIFIER = "code_verifier"

        const val CODE_CHALLENGE = "code_challenge"

        const val CODE_CHALLENGE_METHOD = "code_challenge_method"

        const val STATE = "state"

        const val NONCE_CLAIM = "nonce"

        const val EMAIL_CLAIM = "email"

        const val EMAIL_VERIFIED_CLAIM = "email_verified"

        const val ACCESS_TOKEN = "access_token"

        const val TOKEN_TYPE = "token_type"

        const val BEARER = "Bearer"

        const val EXPIRES_IN = "expires_in"

        const val ID_TOKEN = "id_token"

        const val SCOPE = "scope"

        const val CODE = "code"

        const val ERROR = "error"

        const val TRUE = "true"

        const val SHA_256 = "SHA-256"
    }
}
