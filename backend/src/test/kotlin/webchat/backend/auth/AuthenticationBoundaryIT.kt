package webchat.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * US3-1…US3-4 (T037): the authentication boundary of the whole chat API.
 *
 * Covers: every protected path without a token — including the contract's own
 * №7 logout and №10 /users/me, future chat resources and near-misses of public
 * paths (exact matching, no wildcards) — answers with the SINGLE uniform 401
 * `Not authenticated` problem+json and changes nothing (SC-002); every
 * invalid-token variant — foreign scheme, blank, unparseable, unknown `kid`,
 * tampered signature, wrong `typ` claim, expired beyond the clock skew, and
 * revoked through the logout denylist — yields the byte-identical same 401
 * with no distinguishing details (US3-1/2); a valid access token reaches the
 * protected resource as its owner (US3-3, PublicUser of contract №10 — this
 * test is the RED driver for T038 `UsersController`); the public surface is
 * EXACTLY the eight contract paths №1–6, №8–9 plus the technical actuator
 * endpoints of feature 001 (the health, prometheus and metrics subtrees —
 * outside the API contract, dev behaviour), and nothing else — /actuator/env
 * and any other path stay behind the boundary (US3-4, SC-002,
 * api-contract.md §1–§3).
 */
@Suppress("LargeClass") // tasks.md T037 pins the whole US3 boundary to this single IT file
class AuthenticationBoundaryIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
) : AbstractIntegrationTest() {
    @Test
    fun `chat api without a token answers the single uniform 401 and changes nothing`() {
        createActiveUser("brandt", "brandt@example.com")
        val tokens = loginOk("brandt")

        val withoutToken =
            listOf(
                "contract №10 GET /users/me" to get(USERS_ME_PATH),
                "contract №7 POST /auth/logout with the session's refresh in the body" to
                    postJson(LOGOUT_PATH, mapOf("refreshToken" to tokens.refreshToken)),
                "future chat resource GET /api/v1/messages" to get("/api/v1/messages"),
                "future chat resource GET /api/v1/rooms/general/messages" to
                    get("/api/v1/rooms/general/messages"),
                "near-miss of a public path POST /api/v1/auth/refreshx" to
                    postJson("/api/v1/auth/refreshx", mapOf("refreshToken" to tokens.refreshToken)),
            )

        withoutToken.forEach { (_, response) -> assertUniformNotAuthenticated(response) }

        // US3-1: the resource is not modified — the logout attempt carried a valid
        // refresh token in its body, but the boundary rejected it before the MVC layer
        assertThat(sessionStatusOf(tokens.refreshToken)).isEqualTo(SESSION_STATUS_ACTIVE)
    }

    @Test
    fun `every invalid token variant yields the identical uniform 401`() {
        createActiveUser("karmen", "karmen@example.com")
        val validAccessToken = loginOk("karmen").accessToken

        val invalidAuthorizations =
            listOf(
                "missing Authorization header" to null,
                "foreign scheme (Basic)" to BASIC_AUTHORIZATION,
                "empty bearer" to BEARER_SCHEME,
                "unparseable token" to BEARER_PREFIX + GARBAGE_TOKEN,
                "unknown kid (own key, kid unknown to the server)" to
                    BEARER_PREFIX + mintSignedToken(roguePrivateKey),
                "tampered signature" to BEARER_PREFIX + tamperSignature(validAccessToken),
                "wrong typ claim (refresh)" to
                    BEARER_PREFIX +
                    mintSignedToken(serverTestPrivateKey, tokenType = TOKEN_TYPE_REFRESH),
                "expired beyond the verify-side clock skew" to
                    BEARER_PREFIX +
                    mintSignedToken(serverTestPrivateKey, expiresAt = Instant.now().minus(EXPIRED_BACKDATE)),
            )

        invalidAuthorizations.forEach { (label, authorization) ->
            val response = get(USERS_ME_PATH, authorization)
            assertThat(response.statusCode).`as`(label).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(response.headers.contentType?.toString()).`as`(label).contains(PROBLEM_JSON_MEDIA_TYPE)
            assertThat(response.body).`as`(label).isEqualTo(UNIFORM_401_BODY)
        }
    }

    @Test
    fun `access token revoked by logout yields the same uniform 401`() {
        createActiveUser("odile", "odile@example.com")
        val tokens = loginOk("odile")

        val logout =
            postJson(
                LOGOUT_PATH,
                mapOf("refreshToken" to tokens.refreshToken),
                BEARER_PREFIX + tokens.accessToken,
            )
        assertThat(logout.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // US3-2: a revoked (denylisted sid) token is indistinguishable from no token at all
        assertUniformNotAuthenticated(get(USERS_ME_PATH, BEARER_PREFIX + tokens.accessToken))
    }

    @Test
    fun `valid access token reaches the protected resource as its owner`() {
        createActiveUser("pascal", "pascal@example.com")
        val tokens = loginOk("pascal")

        val response = get(USERS_ME_PATH, BEARER_PREFIX + tokens.accessToken)

        // US3-3 / contract №10: PublicUser of the token owner — RED until T038 lands
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        assertThat(body["id"].asText()).isEqualTo(userId("pascal")?.toString())
        assertThat(body["username"].asText()).isEqualTo("pascal")
        assertThat(body["email"].asText()).isEqualTo("pascal@example.com")
        assertThat(body["status"].asText()).isEqualTo(SESSION_STATUS_ACTIVE)
        assertThat(body.path("createdAt").asText()).isNotEmpty
    }

    @Test
    fun `public surface is exactly contract paths 1-6 and 8-9 plus technical actuator endpoints`() {
        // US3-4 (SC-002): the eight contract paths are reachable WITHOUT authentication —
        // each answer comes from the MVC layer, never the boundary's uniform 401
        val registerEmpty = postJson(REGISTER_PATH, emptyMap())
        assertThat(registerEmpty.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val resendEmpty = postJson(RESEND_PATH, emptyMap())
        assertThat(resendEmpty.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val confirmShortToken = postJson(CONFIRM_PATH, mapOf("token" to GARBAGE_TOKEN))
        assertThat(confirmShortToken.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val passwordEmpty = postJson(SET_PASSWORD_PATH, emptyMap())
        assertThat(passwordEmpty.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        // login/refresh answer with their BUSINESS 401s, never the boundary 401
        val loginEmpty = postJson(LOGIN_PATH, emptyMap())
        assertThat(loginEmpty.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(loginEmpty.body)["detail"].asText())
            .isEqualTo(INVALID_CREDENTIALS_DETAIL)

        val refreshShort = postJson(REFRESH_PATH, mapOf("refreshToken" to GARBAGE_TOKEN))
        assertThat(refreshShort.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(refreshShort.body)["detail"].asText())
            .isEqualTo(INVALID_REFRESH_TOKEN_DETAIL)

        // US4 endpoints (№8–9): permitAll in the chain; their controller lands with T042,
        // so today they surface as MVC 404s — in any case NOT the boundary 401
        assertThat(reachesMvcLayer(postJson(PASSWORD_RESET_PATH, emptyMap()))).isTrue
        assertThat(reachesMvcLayer(postJson(PASSWORD_RESET_CONFIRM_PATH, emptyMap()))).isTrue

        // Technical endpoints of feature 001 — outside the API contract, open in dev (T009a):
        // probes and the scrape/metrics targets for the T057b p95 verification
        val health = get("/actuator/health")
        assertThat(health.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(health.body)["status"].asText()).isEqualTo(HEALTH_UP)
        assertThat(reachesMvcLayer(get("/actuator/health/liveness"))).isTrue

        val prometheus = get("/actuator/prometheus")
        assertThat(prometheus.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(prometheus.body).contains(PROMETHEUS_JVM_METRIC_MARKER)

        assertThat(get("/actuator/metrics").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(get("/actuator/metrics/jvm.memory.used").statusCode).isEqualTo(HttpStatus.OK)

        // Nothing else is public: technical endpoints beyond the 001 list, and the
        // protected №7/№10, stay behind the boundary even for GET
        assertUniformNotAuthenticated(get("/actuator/env"))
        assertUniformNotAuthenticated(get(USERS_ME_PATH))
        assertUniformNotAuthenticated(postJson(LOGOUT_PATH, emptyMap()))
    }

    @Test
    fun `permitAll paths still authenticate an attached bearer token`() {
        createActiveUser("rogan", "rogan@example.com")
        val accessToken = loginOk("rogan").accessToken

        // permitAll disables only AUTHORIZATION, not AUTHENTICATION: the bearer
        // pipeline runs before the authorize rules, so an INVALID token attached to
        // a public path is the uniform boundary 401 — never the business answer and
        // never silently ignored (US3-2 holds on the public surface as well)
        assertUniformNotAuthenticated(
            postJson(
                LOGIN_PATH,
                mapOf("identifier" to "rogan", "password" to PASSWORD),
                BEARER_PREFIX + GARBAGE_TOKEN,
            ),
        )
        assertUniformNotAuthenticated(
            postJson(REGISTER_PATH, emptyMap(), BEARER_PREFIX + GARBAGE_TOKEN),
        )

        // a VALID bearer passes authentication on a permitAll path and the request
        // still reaches the MVC layer (login {} → the business 401, not the boundary one)
        val withValidToken = postJson(LOGIN_PATH, emptyMap(), BEARER_PREFIX + accessToken)
        assertThat(withValidToken.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(withValidToken.body)["detail"].asText())
            .isEqualTo(INVALID_CREDENTIALS_DETAIL)
        assertThat(
            reachesMvcLayer(postJson(CONFIRM_PATH, mapOf("token" to GARBAGE_TOKEN), BEARER_PREFIX + accessToken)),
        ).isTrue
    }

    private fun createActiveUser(
        username: String,
        email: String,
    ): Tokens {
        postJson(REGISTER_PATH, mapOf("username" to username, "email" to email))
        val confirmToken = extractVerificationToken(email)
        val confirm = postJson(CONFIRM_PATH, mapOf("token" to confirmToken))
        assertThat(confirm.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirm.body)["setupToken"].asText()

        val password =
            postJson(
                SET_PASSWORD_PATH,
                mapOf(
                    "setupToken" to setupToken,
                    "password" to PASSWORD,
                    "confirmPassword" to PASSWORD,
                ),
            )
        assertThat(password.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        return loginOk(username)
    }

    private fun loginOk(username: String): Tokens {
        val response =
            postJson(LOGIN_PATH, mapOf("identifier" to username, "password" to PASSWORD))
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return Tokens(body["accessToken"].asText(), body["refreshToken"].asText())
    }

    private fun get(
        path: String,
        authorization: String? = null,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity<String>(headers), String::class.java)
    }

    private fun postJson(
        path: String,
        payload: Map<String, String>,
        authorization: String? = null,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                authorization?.let { set(HttpHeaders.AUTHORIZATION, it) }
                // US5 buckets (T047) key on the source: one fresh documentation-range
                // address per call keeps every request on the shared static Redis in
                // its own bucket — no cross-test throttling, same assertions
                set(X_FORWARDED_FOR_HEADER, uniqueSourceIp())
            }
        return restTemplate.postForEntity(path, HttpEntity(payload, headers), String::class.java)
    }

    /** The boundary's one and only 401 (api-contract.md §3): shape, media type and bytes. */
    private fun assertUniformNotAuthenticated(response: ResponseEntity<String>) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType?.toString()).contains(PROBLEM_JSON_MEDIA_TYPE)
        assertThat(response.body).isEqualTo(UNIFORM_401_BODY)
    }

    /**
     * `true` when the request passed the security chain and was answered by the
     * MVC layer — any status except the boundary's exact uniform 401 (a business
     * 401 such as "Invalid credentials" also proves the path is permitAll).
     */
    private fun reachesMvcLayer(response: ResponseEntity<String>): Boolean {
        val body = response.body ?: return response.statusCode != HttpStatus.UNAUTHORIZED
        val detail =
            runCatching { objectMapper.readTree(body).path("detail").asText() }.getOrDefault("")
        return response.statusCode != HttpStatus.UNAUTHORIZED || detail != NOT_AUTHENTICATED_DETAIL
    }

    /**
     * Signs an ES256 access-token-shaped JWT with the GIVEN key — the same
     * P-256 test key the server trusts (kid `it-test`) for the expired and
     * wrong-`typ` variants, a rogue key with an unknown kid otherwise.
     */
    private fun mintSignedToken(
        signingKey: ECPrivateKey,
        tokenType: String = TOKEN_TYPE_ACCESS,
        expiresAt: Instant = Instant.now().plus(MINTED_TTL),
    ): String {
        val now = Instant.now()
        val jwt =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.ES256)
                    .type(JOSEObjectType.JWT)
                    .keyID(kidOf(signingKey))
                    .build(),
                JWTClaimsSet
                    .Builder()
                    .subject(UUID.randomUUID().toString())
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiresAt))
                    .claim(CLAIM_SESSION_ID, UUID.randomUUID().toString())
                    .claim(CLAIM_TOKEN_TYPE, tokenType)
                    .build(),
            )
        jwt.sign(ECDSASigner(signingKey))
        return jwt.serialize()
    }

    private fun kidOf(signingKey: ECPrivateKey): String =
        if (signingKey === serverTestPrivateKey) AbstractIntegrationTest.TEST_JWT_KID else ROGUE_KID

    /**
     * Corrupts the LAST signature character — the JWT stays parseable, the
     * signature check fails. The flip targets the TOP bit of the character's
     * 6-bit group: unlike a low-bit flip, it ALWAYS lands in the significant
     * bits of the final byte for every DER length (mod 3) — low-bit flips
     * only touch base64 padding bits in ~3 of 4 minted signatures and leave
     * the DER value bit-identical (the token would still verify).
     */
    private fun tamperSignature(token: String): String {
        val segments = token.split(".")
        val signature = segments.last()
        val flipped = flipTopBit(signature.last())
        return segments.dropLast(1).joinToString(SEGMENT_SEPARATOR) + SEGMENT_SEPARATOR +
            signature.dropLast(1) + flipped
    }

    private fun flipTopBit(character: Char): Char {
        val index = BASE64URL_ALPHABET.indexOf(character)
        assertThat(index)
            .overridingErrorMessage("a JWT signature segment must be unpadded base64url")
            .isNotNegative()
        return BASE64URL_ALPHABET[index xor TOP_BIT_MASK]
    }

    /** The P-256 private key backing the kid the IT context trusts (PKCS#8 PEM, Base64-wrapped). */
    private val serverTestPrivateKey: ECPrivateKey by lazy {
        val encodedPem =
            AbstractIntegrationTest.TEST_JWT_KEYS
                .removePrefix(AbstractIntegrationTest.TEST_JWT_KID + KEY_VALUE_SEPARATOR)
        val pem = String(Base64.getDecoder().decode(encodedPem), Charsets.UTF_8)
        val der =
            Base64
                .getMimeDecoder()
                .decode(
                    pem
                        .substringAfter(PKCS8_BEGIN)
                        .substringBefore(PKCS8_END)
                        .replace(NEWLINE, "")
                        .trim(),
                )
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        check(key is ECPrivateKey) { "The shared test JWT key must be an EC private key" }
        key
    }

    private val roguePrivateKey: ECPrivateKey by lazy {
        val keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec(CURVE_P256)) }
                .generateKeyPair()
        keyPair.private as ECPrivateKey
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
        val match = Regex(TOKEN_PATTERN).find(payloads.firstOrNull() ?: "")
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    private fun userId(username: String): UUID? =
        jdbcTemplate
            .queryForList(
                "SELECT id FROM users WHERE lower(username) = ?",
                UUID::class.java,
                username.lowercase(),
            ).firstOrNull()

    private fun sessionStatusOf(refreshToken: String): String? {
        val statuses =
            jdbcTemplate.queryForList(
                """
                SELECT s.status::text FROM sessions s
                JOIN refresh_tokens r ON r.session_id = s.id
                WHERE r.token_hash = ?
                """.trimIndent(),
                String::class.java,
                sha256Hex(refreshToken),
            )
        return statuses.firstOrNull()
    }

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
        // 198.51.100.0/24 (TEST-NET-2) tail: unique per call, plenty for this class
        const val SOURCE_IP_BASE = 200
        var sourceIpCounter = 0

        fun uniqueSourceIp(): String = "198.51.100.${SOURCE_IP_BASE + sourceIpCounter++}"

        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
        const val PASSWORD = "Str0ng-Boundary-IT-Pass!"
        const val BEARER_PREFIX = "Bearer "
        const val BEARER_SCHEME = "Bearer"
        const val BASIC_AUTHORIZATION = "Basic dXNlcjpwYXNz"
        const val GARBAGE_TOKEN = "this-is-not-a-jwt-token"
        const val UNIFORM_401_BODY =
            """{"title":"Unauthorized","status":401,"detail":"Not authenticated"}"""
        const val NOT_AUTHENTICATED_DETAIL = "Not authenticated"
        const val INVALID_CREDENTIALS_DETAIL = "Invalid credentials"
        const val INVALID_REFRESH_TOKEN_DETAIL = "Invalid refresh token"
        const val PROBLEM_JSON_MEDIA_TYPE = "application/problem+json"
        const val USERS_ME_PATH = "/api/v1/users/me"
        const val REGISTER_PATH = "/api/v1/auth/register"
        const val RESEND_PATH = "/api/v1/auth/register/resend"
        const val CONFIRM_PATH = "/api/v1/auth/register/confirm"
        const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"
        const val LOGIN_PATH = "/api/v1/auth/login"
        const val REFRESH_PATH = "/api/v1/auth/refresh"
        const val LOGOUT_PATH = "/api/v1/auth/logout"
        const val PASSWORD_RESET_PATH = "/api/v1/auth/password-reset"
        const val PASSWORD_RESET_CONFIRM_PATH = "/api/v1/auth/password-reset/confirm"
        const val ROGUE_KID = "rogue-boundary-kid"
        const val CLAIM_SESSION_ID = "sid"
        const val CLAIM_TOKEN_TYPE = "typ"
        const val TOKEN_TYPE_ACCESS = "access"
        const val TOKEN_TYPE_REFRESH = "refresh"
        const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
        const val PKCS8_END = "-----END PRIVATE KEY-----"
        const val KEY_VALUE_SEPARATOR = "="
        const val NEWLINE = "\n"
        const val SEGMENT_SEPARATOR = "."
        const val TOP_BIT_MASK = 32
        const val BASE64URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        const val TOKEN_PATTERN = """token=([A-Za-z0-9_-]{43})"""
        const val HEALTH_UP = "UP"
        const val PROMETHEUS_JVM_METRIC_MARKER = "jvm_"
        const val SESSION_STATUS_ACTIVE = "active"
        const val CURVE_P256 = "secp256r1"
        val MINTED_TTL: Duration = Duration.ofMinutes(5)
        val EXPIRED_BACKDATE: Duration = Duration.ofSeconds(61)
    }
}
