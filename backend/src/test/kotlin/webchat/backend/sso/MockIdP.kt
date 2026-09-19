package webchat.backend.sso

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock identity provider for SSO integration tests (T018, research.md §12): a
 * test-scope [RestController] mounted in the `AbstractIntegrationTest` context,
 * so integration tests drive the real HTTP protocol loop — authorization
 * redirect, code→token exchange, JWKS signature verification — against a
 * deterministic, fully controllable IdP instead of WireMock or a Keycloak
 * container.
 *
 * Endpoints (providers in tests register explicit endpoints on them,
 * research.md §5):
 * - `GET /mock-idp/authorize` — validates the client, issues a single-use
 *   authorization code bound to client/redirect/PKCE/nonce and answers 302
 *   `redirect_uri?code=…&state=…`; with [consentDenied] armed it answers 302
 *   `error=access_denied` instead (user consent withdrawal, spec US1-5);
 * - `POST /mock-idp/token` — `authorization_code` grant with HTTP Basic (or
 *   form) client authentication, single-use code consumption, PKCE S256/plain
 *   verification; returns `access_token` and an RS256 `id_token` whose
 *   `sub`/`email`/`email_verified`/`nonce` claims are test-controlled via
 *   [ControlledClaims];
 * - `GET /mock-idp/jwks` — the public RSA key set verifying the ID tokens.
 *
 * Managed failure scenarios (research.md §12): [tokenEndpointFailure] (HTTP
 * 500 or a delay longer than any caller read timeout, SC-005), [consentDenied],
 * a wrong client secret (401 `invalid_client`) and a substituted or replayed
 * code (400 `invalid_grant`) — all reproduced by configuration and inputs.
 *
 * OAuth2 mode (T055, research.md §21): the `oauth2-userinfo` protocol has no
 * ID tokens, so a parallel set of endpoints model a pure-OAuth2 provider such
 * as Yandex — both modes stay mounted side by side, which lets T058 run an
 * OIDC provider and an oauth2 provider against the same context:
 * - `GET /mock-idp/oauth2/authorize` — same authorization-code contract as the
 *   OIDC authorize (a code bound to client/redirect; PKCE is simply absent in
 *   the `pkce: false` flow, which the shared logic tolerates);
 * - `POST /mock-idp/oauth2/token` — same validation legs (client auth,
 *   single-use code, redirect/PKCE match, [tokenEndpointFailure]) but the
 *   response omits `id_token` entirely; the issued `access_token` is recorded
 *   for the userinfo leg;
 * - `GET /mock-idp/oauth2/userinfo` — Bearer-authenticated profile JSON whose
 *   field NAMES are test-controlled ([UserinfoClaims], defaults model the
 *   Yandex shape `psuid`/`default_email`) so claim-mapping (`subject-claim` /
 *   `email-claim` / `email-verified-mode`) is exercised against arbitrary
 *   providers; managed failures: [userinfoFailure] unavailable (a delay longer
 *   than any caller read timeout) / non-2xx, and claim omission — a `null`
 *   subject or email leaves the field out of the JSON («нет subject-клейма» /
 *   «нет email-клейма»).
 *
 * Scenario state is mutable by design (test double): ITs share the cached
 * application context and drive this bean from the same JVM, resetting the
 * scenario state in @BeforeEach via [reset]. Registered clients survive the
 * reset — they are test setup, not scenario.
 */
@RestController
@RequestMapping("/mock-idp")
class MockIdP(
    private val objectMapper: ObjectMapper,
) {
    /**
     * ID-token claims minted into every token: `sub`, `email`/`email_verified`
     * (email claims are omitted entirely for a `null` email — research.md §8,
     * "email absent") and an optional `nonce` override (a `null` override
     * echoes the authorize-time `nonce` — the honest IdP behavior — while a
     * non-null value simulates a provider that misbinds the flow, T045).
     */
    data class ControlledClaims(
        val subject: String? = DEFAULT_SUBJECT,
        val email: String? = DEFAULT_EMAIL,
        val emailVerified: Boolean = DEFAULT_EMAIL_VERIFIED,
        val nonce: String? = null,
    )

    /** Token-endpoint failure scenario (research.md §12): HTTP 500 or a slow provider. */
    enum class TokenEndpointFailure {
        NONE,
        HTTP_500,
        DELAY,
    }

    /**
     * Userinfo-endpoint failure scenario (research.md §21): an unavailable
     * endpoint (a delay longer than any caller read timeout — the transport
     * budget leg of SC-005) or a non-2xx answer.
     */
    enum class UserinfoFailure {
        NONE,
        UNAVAILABLE,
        NOT_2XX,
    }

    /**
     * Profile JSON minted by the oauth2-mode userinfo endpoint (research.md
     * §21): the field NAMES are part of the controlled state so claim-mapping
     * tests can point `subject-claim`/`email-claim` at arbitrary names — the
     * defaults model the Yandex shape (`psuid`/`default_email`, research.md
     * §20). A `null` subject/email omits the field from the JSON entirely
     * («нет subject-клейма»/«нет email-клейма»); `emailVerified == null`
     * omits `email_verified` (providers that guarantee the email by
     * definition send no such field — `provider-guaranteed` mode).
     * `nestUnder` models the VK ID shape: the whole profile (subject, email,
     * `email_verified` included) is nested under one object key (`user`) —
     * the dot-path claim mapping of the client walks into it.
     */
    data class UserinfoClaims(
        val subjectClaim: String = DEFAULT_USERINFO_SUBJECT_CLAIM,
        val emailClaim: String = DEFAULT_USERINFO_EMAIL_CLAIM,
        val subject: String? = DEFAULT_SUBJECT,
        val email: String? = DEFAULT_EMAIL,
        val emailVerified: Boolean? = null,
        val nestUnder: String? = null,
    )

    /** Consent-denied scenario: authorize 302-redirects back with `error=access_denied`. */
    @Volatile
    var consentDenied = false

    /** Managed token-endpoint failure scenario. */
    @Volatile
    var tokenEndpointFailure = TokenEndpointFailure.NONE

    /** Managed userinfo-endpoint failure scenario (oauth2 mode). */
    @Volatile
    var userinfoFailure = UserinfoFailure.NONE

    /**
     * VK-style device binding (oauth2 mode): when armed, the authorize
     * redirect carries a fresh `device_id` and the token endpoint requires
     * exactly that value back in the exchange body.
     */
    @Volatile
    var issueDeviceId = false

    /** `iss` claim of minted ID tokens — align with the provider configuration under test. */
    @Volatile
    var issuer = DEFAULT_ISSUER

    @Volatile
    private var claims = ControlledClaims()

    @Volatile
    private var userinfoClaims = UserinfoClaims()

    private val clients = ConcurrentHashMap<String, String>()

    private val issuedCodes = ConcurrentHashMap<String, IssuedAuthorization>()

    /** Access tokens issued by the oauth2-mode token endpoint — the userinfo Bearer gate. */
    private val issuedAccessTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        registerClient(DEFAULT_CLIENT_ID, DEFAULT_CLIENT_SECRET)
    }

    /** Registers (or replaces) a client known to this IdP — call from test setup. */
    fun registerClient(
        clientId: String,
        clientSecret: String,
    ) {
        clients[clientId] = clientSecret
    }

    /** Arms the claims minted into the following ID tokens (T019/T026 scenarios). */
    fun setClaims(controlledClaims: ControlledClaims) {
        claims = controlledClaims
    }

    /** Arms the profile JSON of the following userinfo answers (T055/T058 scenarios). */
    fun setUserinfoClaims(controlledUserinfoClaims: UserinfoClaims) {
        userinfoClaims = controlledUserinfoClaims
    }

    /** Restores the default scenario state: claims, failure flags, issuer, issued codes. */
    fun reset() {
        claims = ControlledClaims()
        userinfoClaims = UserinfoClaims()
        consentDenied = false
        tokenEndpointFailure = TokenEndpointFailure.NONE
        userinfoFailure = UserinfoFailure.NONE
        issueDeviceId = false
        issuer = DEFAULT_ISSUER
        issuedCodes.clear()
        issuedAccessTokens.clear()
    }

    @GetMapping("/authorize")
    fun authorize(request: HttpServletRequest): ResponseEntity<String> = issueAuthorizationCode(request)

    /**
     * OAuth2-mode authorize (research.md §21): the same authorization-code
     * contract — a `pkce: false` provider simply arrives without
     * `code_challenge`, which the shared logic tolerates.
     */
    @GetMapping("/oauth2/authorize")
    fun oauth2Authorize(request: HttpServletRequest): ResponseEntity<String> = issueAuthorizationCode(request)

    @Suppress("ReturnCount") // each return is a protocol answer of an IdP authorize endpoint (RFC 6749 §4.1.2)
    private fun issueAuthorizationCode(request: HttpServletRequest): ResponseEntity<String> {
        val clientId = request.getParameter(CLIENT_ID)
        if (clientId == null || !clients.containsKey(clientId)) {
            return oauthError(HttpStatus.BAD_REQUEST, INVALID_CLIENT)
        }
        val redirectUri = request.getParameter(REDIRECT_URI)
        if (redirectUri.isNullOrBlank() || request.getParameter(RESPONSE_TYPE) != RESPONSE_TYPE_CODE) {
            return oauthError(HttpStatus.BAD_REQUEST, INVALID_REQUEST)
        }
        if (consentDenied) {
            return redirectTo(redirectUri, ERROR to ACCESS_DENIED, state = request.getParameter(STATE))
        }
        val code = randomToken()
        val deviceId = if (issueDeviceId) randomToken() else null
        issuedCodes[code] =
            IssuedAuthorization(
                clientId = clientId,
                redirectUri = redirectUri,
                nonce = request.getParameter(NONCE_CLAIM),
                codeChallenge = request.getParameter(CODE_CHALLENGE),
                codeChallengeMethod = request.getParameter(CODE_CHALLENGE_METHOD),
                deviceId = deviceId,
            )
        val redirectParameters =
            buildList {
                add(CODE to code)
                deviceId?.let { add(DEVICE_ID to it) }
            }
        return redirectTo(redirectUri, *redirectParameters.toTypedArray(), state = request.getParameter(STATE))
    }

    @PostMapping("/token")
    fun token(request: HttpServletRequest): ResponseEntity<String> = exchangeCode(request, includeIdToken = true)

    /**
     * OAuth2-mode token endpoint (research.md §21): identical validation legs,
     * but the response carries no `id_token` — the profile comes from the
     * userinfo endpoint. The minted access token is recorded so userinfo can
     * authenticate the Bearer caller.
     */
    @PostMapping("/oauth2/token")
    fun oauth2Token(request: HttpServletRequest): ResponseEntity<String> = exchangeCode(request, includeIdToken = false)

    // each return is a distinct OAuth error code of the token endpoint (RFC 6749 §5.2)
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun exchangeCode(
        request: HttpServletRequest,
        includeIdToken: Boolean,
    ): ResponseEntity<String> {
        when (tokenEndpointFailure) {
            TokenEndpointFailure.DELAY -> Thread.sleep(DELAY_MILLIS)
            TokenEndpointFailure.HTTP_500 -> return oauthError(HttpStatus.INTERNAL_SERVER_ERROR, SERVER_ERROR)
            TokenEndpointFailure.NONE -> Unit
        }
        val credentials = clientCredentialsOf(request)
        val expectedSecret = credentials?.let { clients[it.first] }
        if (credentials == null || expectedSecret == null || credentials.second != expectedSecret) {
            return oauthError(HttpStatus.UNAUTHORIZED, INVALID_CLIENT)
        }
        if (request.getParameter(GRANT_TYPE) != GRANT_TYPE_AUTHORIZATION_CODE) {
            return oauthError(HttpStatus.BAD_REQUEST, UNSUPPORTED_GRANT_TYPE)
        }
        val issued =
            request.getParameter(CODE)?.let(issuedCodes::remove)
                ?: return oauthError(HttpStatus.BAD_REQUEST, INVALID_GRANT)
        if (issued.clientId != credentials.first || request.getParameter(REDIRECT_URI) != issued.redirectUri) {
            return oauthError(HttpStatus.BAD_REQUEST, INVALID_GRANT)
        }
        if (!pkceMatches(request, issued)) {
            return oauthError(HttpStatus.BAD_REQUEST, INVALID_GRANT)
        }
        // VK-style device binding: a device_id issued at authorize must come
        // back in the exchange — a missing or foreign value is invalid_grant
        if (issued.deviceId != null && request.getParameter(DEVICE_ID) != issued.deviceId) {
            return oauthError(HttpStatus.BAD_REQUEST, INVALID_GRANT)
        }
        val accessToken = randomToken()
        val body =
            linkedMapOf<String, Any>(
                ACCESS_TOKEN to accessToken,
                TOKEN_TYPE to BEARER,
                EXPIRES_IN to ACCESS_TOKEN_TTL_SECONDS,
                SCOPE to (request.getParameter(SCOPE) ?: DEFAULT_SCOPES_PARAMETER),
            )
        if (includeIdToken) {
            body[ID_TOKEN] = mintIdToken(issued, credentials.first)
        } else {
            issuedAccessTokens.add(accessToken)
        }
        return ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
    }

    /**
     * OAuth2-mode userinfo (research.md §21): Bearer-authenticated profile
     * JSON with test-controlled field names. Missing/foreign Bearer tokens
     * answer 401 `invalid_token` — a real provider rejects unknown callers.
     */
    @GetMapping("/oauth2/userinfo")
    @Suppress("ReturnCount") // each return is a protocol answer of the userinfo endpoint
    fun userinfo(
        @RequestHeader(AUTHORIZATION_HEADER) authorization: String?,
    ): ResponseEntity<String> {
        when (userinfoFailure) {
            UserinfoFailure.UNAVAILABLE -> Thread.sleep(DELAY_MILLIS)
            UserinfoFailure.NOT_2XX -> return oauthError(HttpStatus.INTERNAL_SERVER_ERROR, SERVER_ERROR)
            UserinfoFailure.NONE -> Unit
        }
        val accessToken = authorization?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)
        if (accessToken.isNullOrEmpty() || accessToken !in issuedAccessTokens) {
            return oauthError(HttpStatus.UNAUTHORIZED, INVALID_TOKEN)
        }
        val controlled = userinfoClaims
        val profile = LinkedHashMap<String, Any>()
        controlled.subject?.takeIf(String::isNotEmpty)?.let { profile[controlled.subjectClaim] = it }
        controlled.email?.let {
            profile[controlled.emailClaim] = it
            controlled.emailVerified?.let { verified -> profile[EMAIL_VERIFIED_CLAIM] = verified }
        }
        val body =
            controlled.nestUnder?.let { nest -> LinkedHashMap<String, Any>(mapOf(nest to profile)) } ?: profile
        return ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
    }

    @GetMapping("/jwks", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): String = JWKS_JSON

    @Suppress("ReturnCount") // guard legs: malformed Basic header, missing form credentials
    private fun clientCredentialsOf(request: HttpServletRequest): Pair<String, String>? {
        val header = request.getHeader(AUTHORIZATION_HEADER)
        if (header != null && header.startsWith(BASIC_PREFIX)) {
            val decoded =
                String(
                    Base64.getDecoder().decode(header.removePrefix(BASIC_PREFIX)),
                    StandardCharsets.UTF_8,
                )
            val separator = decoded.indexOf(CREDENTIALS_SEPARATOR)
            if (separator <= 0) return null
            return decoded.substring(0, separator) to decoded.substring(separator + 1)
        }
        val clientId = request.getParameter(CLIENT_ID)
        val clientSecret = request.getParameter(CLIENT_SECRET)
        if (clientId.isNullOrEmpty() || clientSecret == null) return null
        return clientId to clientSecret
    }

    @Suppress("ReturnCount") // the PKCE verdict is a flat match of RFC 7636 §4.6
    private fun pkceMatches(
        request: HttpServletRequest,
        issued: IssuedAuthorization,
    ): Boolean {
        val challenge = issued.codeChallenge ?: return true
        val verifier = request.getParameter(CODE_VERIFIER) ?: return false
        return when (issued.codeChallengeMethod) {
            S256_METHOD -> sha256Base64Url(verifier) == challenge
            PLAIN_METHOD -> verifier == challenge
            else -> false
        }
    }

    private fun mintIdToken(
        issued: IssuedAuthorization,
        clientId: String,
    ): String {
        val controlled = claims
        val now = Instant.now()
        val claimsBuilder =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(listOf(clientId))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ID_TOKEN_TTL_SECONDS)))
        controlled.subject?.takeIf(String::isNotEmpty)?.let(claimsBuilder::subject)
        controlled.email?.let {
            claimsBuilder.claim(EMAIL_CLAIM, it)
            claimsBuilder.claim(EMAIL_VERIFIED_CLAIM, controlled.emailVerified)
        }
        (controlled.nonce ?: issued.nonce)?.let { claimsBuilder.claim(NONCE_CLAIM, it) }
        val idToken =
            SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
                claimsBuilder.build(),
            )
        idToken.sign(RSASSASigner(KEY_PAIR.private))
        return idToken.serialize()
    }

    private fun redirectTo(
        redirectUri: String,
        vararg parameters: Pair<String, String>,
        state: String?,
    ): ResponseEntity<String> {
        val builder = UriComponentsBuilder.fromUriString(redirectUri)
        parameters.forEach { (name, value) -> builder.queryParam(name, value) }
        if (!state.isNullOrEmpty()) builder.queryParam(STATE, state)
        return ResponseEntity
            .status(HttpStatus.FOUND)
            .location(URI(builder.build().toUriString()))
            .build()
    }

    private fun oauthError(
        status: HttpStatus,
        error: String,
    ): ResponseEntity<String> =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(mapOf(ERROR to error)))

    private fun sha256Base64Url(value: String): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance(SHA_256).digest(value.toByteArray(StandardCharsets.UTF_8)),
            )

    /** 256-bit base64url token — same shape as the state/nonce values of the SSO flow. */
    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private data class IssuedAuthorization(
        val clientId: String,
        val redirectUri: String,
        val nonce: String?,
        val codeChallenge: String?,
        val codeChallengeMethod: String?,
        val deviceId: String? = null,
    )

    companion object {
        const val DEFAULT_CLIENT_ID = "webchat-it"

        const val DEFAULT_CLIENT_SECRET = "mock-secret"

        const val DEFAULT_ISSUER = "https://mock-idp.test"

        /** Controlled-claims defaults — public for IT assertions on scenario resets. */
        const val DEFAULT_SUBJECT = "mock-subject"

        const val DEFAULT_EMAIL = "mock-user@example.com"

        private const val DEFAULT_EMAIL_VERIFIED = true

        /** OAuth2-mode defaults: the Yandex userinfo shape (research.md §20). */
        private const val DEFAULT_USERINFO_SUBJECT_CLAIM = "psuid"

        private const val DEFAULT_USERINFO_EMAIL_CLAIM = "default_email"

        private const val ACCESS_TOKEN_TTL_SECONDS = 3_600

        private const val ID_TOKEN_TTL_SECONDS = 3_600L

        /** Longer than the 2 s read cap of OidcClient (SC-005) — the delay scenario must exceed it. */
        private const val DELAY_MILLIS = 3_000L

        private const val RSA_KEY_BITS = 2048

        private const val TOKEN_BYTES = 32

        private const val KEY_ID = "mock-idp-rs256"

        private const val RESPONSE_TYPE = "response_type"

        private const val RESPONSE_TYPE_CODE = "code"

        private const val GRANT_TYPE = "grant_type"

        private const val GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code"

        private const val UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type"

        private const val INVALID_REQUEST = "invalid_request"

        private const val INVALID_GRANT = "invalid_grant"

        private const val INVALID_CLIENT = "invalid_client"

        private const val SERVER_ERROR = "server_error"

        private const val ACCESS_DENIED = "access_denied"

        private const val CLIENT_SECRET = "client_secret"

        private const val REDIRECT_URI = "redirect_uri"

        private const val CODE_VERIFIER = "code_verifier"

        private const val CODE_CHALLENGE = "code_challenge"

        private const val CODE_CHALLENGE_METHOD = "code_challenge_method"

        private const val S256_METHOD = "S256"

        private const val PLAIN_METHOD = "plain"

        private const val DEFAULT_SCOPES_PARAMETER = "openid email"

        private const val AUTHORIZATION_HEADER = "Authorization"

        private const val BASIC_PREFIX = "Basic "

        private const val CREDENTIALS_SEPARATOR = ':'

        private const val CLIENT_ID = "client_id"

        private const val CODE = "code"

        const val DEVICE_ID = "device_id"

        private const val STATE = "state"

        private const val NONCE_CLAIM = "nonce"

        private const val EMAIL_CLAIM = "email"

        private const val EMAIL_VERIFIED_CLAIM = "email_verified"

        private const val ACCESS_TOKEN = "access_token"

        private const val TOKEN_TYPE = "token_type"

        private const val BEARER = "Bearer"

        private const val BEARER_PREFIX = "Bearer "

        private const val INVALID_TOKEN = "invalid_token"

        private const val EXPIRES_IN = "expires_in"

        private const val SCOPE = "scope"

        private const val ID_TOKEN = "id_token"

        private const val ERROR = "error"

        private const val SHA_256 = "SHA-256"

        private const val RSA_ALGORITHM = "RSA"

        private val SECURE_RANDOM = SecureRandom()

        private val KEY_PAIR by lazy {
            KeyPairGenerator
                .getInstance(RSA_ALGORITHM)
                .apply { initialize(RSA_KEY_BITS) }
                .generateKeyPair()
        }

        /**
         * Public-only JWKS JSON: the set is built from the public key alone, so
         * no private parameters can ever leak through the endpoint (the signer
         * keeps the private key separately).
         */
        private val JWKS_JSON by lazy {
            val jwk =
                RSAKey
                    .Builder(KEY_PAIR.public as RSAPublicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(KEY_ID)
                    .build()
            JWKSet(jwk).toString()
        }
    }
}

/**
 * PermitAll security chain for the MockIdP endpoints: they are called by the
 * backend itself (OidcClient token/JWKS exchange, T015) and by tests over the
 * real port — the main chain of `SecurityConfig` would answer the uniform
 * 401. The explicit [Order] puts this chain before the catch-all main chain
 * (which has no order), and `securityMatcher` keeps its scope strictly on the
 * MockIdP paths: everything else — boundary 401s, rate limiting — is
 * untouched.
 */
@Configuration
class MockIdPSecurityConfig {
    @Bean
    @Order(MOCK_IDP_CHAIN_ORDER)
    fun mockIdpSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            securityMatcher("/mock-idp/**")
            csrf { disable() }
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
        }
        return http.build()
    }

    private companion object {
        const val MOCK_IDP_CHAIN_ORDER = 0
    }
}
