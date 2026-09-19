package webchat.backend.sso.oidc

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.config.SsoProperties
import webchat.backend.sso.SsoMetrics
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit checks of the `oauth2-userinfo` protocol branch (T057, research.md
 * §16–§19): the userinfo claim mapping — the pure leg, no HTTP — plus the
 * authorize-URL shape per provider flags and the backchannel legs against a
 * local JDK HTTP server (no Spring context, no containers — the full-flow
 * IT with MockIdP lands in T058).
 *
 * SC-004 acceptance of T057: every failure leaves as the typed
 * [OidcClientException] reason and the messages carry no tokens, codes or
 * profile values.
 */
class OidcClientTest {
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

    private val userinfoScenario = AtomicReference<UserinfoScenario>(UserinfoScenario.Ok)

    private val emailsScenario = AtomicReference<EmailsScenario>(EmailsScenario.Ok)

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    // userinfo claim mapping (research.md §16) — the pure unit leg

    @Test
    fun mapsCustomClaimsInProviderGuaranteedMode() {
        val claims =
            OidcClient.userinfoIdentityClaims(
                body = mapOf("psuid" to "ya-subject-1", "default_email" to "  user@ya.example  "),
                subjectClaim = "psuid",
                emailClaim = "default_email",
                emailVerifiedMode = SsoProperties.EmailVerifiedMode.PROVIDER_GUARANTEED,
            )

        assertThat(claims.subject).isEqualTo("ya-subject-1")
        assertThat(claims.email).isEqualTo("user@ya.example")
        assertThat(claims.emailVerified).isTrue()
    }

    @Test
    fun mapsDefaultClaimNamesInClaimMode() {
        val claims =
            OidcClient.userinfoIdentityClaims(
                body = mapOf("sub" to "subject-1", "email" to "user@example.com", "email_verified" to true),
                subjectClaim = "sub",
                emailClaim = "email",
                emailVerifiedMode = SsoProperties.EmailVerifiedMode.CLAIM,
            )

        assertThat(claims.subject).isEqualTo("subject-1")
        assertThat(claims.email).isEqualTo("user@example.com")
        assertThat(claims.emailVerified).isTrue()
    }

    @Test
    fun claimModeDefaultsEmailVerifiedToFalse() {
        listOf(null, "false", "unparseable", 42).forEach { raw ->
            val claims =
                OidcClient.userinfoIdentityClaims(
                    body = mapOf("sub" to "subject-1", "email" to "user@example.com", "email_verified" to raw),
                    subjectClaim = "sub",
                    emailClaim = "email",
                    emailVerifiedMode = SsoProperties.EmailVerifiedMode.CLAIM,
                )

            assertThat(claims.emailVerified).isFalse()
        }
    }

    @Test
    fun mapsAbsentEmailToNull() {
        val claims =
            OidcClient.userinfoIdentityClaims(
                body = mapOf("psuid" to "ya-subject-1"),
                subjectClaim = "psuid",
                emailClaim = "default_email",
                emailVerifiedMode = SsoProperties.EmailVerifiedMode.PROVIDER_GUARANTEED,
            )

        assertThat(claims.email).isNull()
    }

    @Test
    fun missingSubjectClaimIsTheIdTokenInvalidVerdict() {
        // research.md §16/§21: a profile without a subject claim is the
        // ID_TOKEN_INVALID-equivalent rejection → provider_error
        listOf<Map<String, Any?>?>(
            null,
            emptyMap(),
            mapOf("default_email" to "user@ya.example"),
            mapOf("psuid" to "   "),
        ).forEach { body ->
            assertThatThrownBy {
                OidcClient.userinfoIdentityClaims(
                    body = body,
                    subjectClaim = "psuid",
                    emailClaim = "default_email",
                    emailVerifiedMode = SsoProperties.EmailVerifiedMode.PROVIDER_GUARANTEED,
                )
            }.isInstanceOfSatisfying(OidcClientException::class.java) { e ->
                assertThat(e.reason).isEqualTo(OidcClientException.Reason.ID_TOKEN_INVALID)
                // SC-004: static markers only — no profile values in the message
                assertThat(e.message).doesNotContain("user@ya.example")
            }
        }
    }

    @Test
    fun mapsNestedVkStyleClaimsInClaimMode() {
        // the VK ID profile shape: everything nested under `user`, the
        // numeric `user_id` subject, the boolean verified fact inside the nest
        val claims =
            OidcClient.userinfoIdentityClaims(
                body =
                    mapOf(
                        "user" to
                            mapOf(
                                "user_id" to 4242424,
                                "email" to "user@vk.example",
                                "email_verified" to true,
                            ),
                    ),
                subjectClaim = "user.user_id",
                emailClaim = "user.email",
                emailVerifiedMode = SsoProperties.EmailVerifiedMode.CLAIM,
                emailVerifiedClaim = "user.email_verified",
            )

        assertThat(claims.subject).isEqualTo("4242424")
        assertThat(claims.email).isEqualTo("user@vk.example")
        assertThat(claims.emailVerified).isTrue()
    }

    @Test
    fun missingNestedSubjectClaimIsTheIdTokenInvalidVerdict() {
        listOf<Map<String, Any?>>(
            // the nest itself is absent
            mapOf("email" to "user@vk.example"),
            // the nest exists but carries no user_id leg
            mapOf("user" to mapOf("email" to "user@vk.example")),
        ).forEach { body ->
            assertThatThrownBy {
                OidcClient.userinfoIdentityClaims(
                    body = body,
                    subjectClaim = "user.user_id",
                    emailClaim = "user.email",
                    emailVerifiedMode = SsoProperties.EmailVerifiedMode.CLAIM,
                )
            }.isInstanceOfSatisfying(OidcClientException::class.java) { e ->
                assertThat(e.reason).isEqualTo(OidcClientException.Reason.ID_TOKEN_INVALID)
            }
        }
    }

    @Test
    fun nestedEmailVerifiedFalseStaysFalse() {
        val claims =
            OidcClient.userinfoIdentityClaims(
                body = mapOf("user" to mapOf("user_id" to 1, "email_verified" to false)),
                subjectClaim = "user.user_id",
                emailClaim = "user.email",
                emailVerifiedMode = SsoProperties.EmailVerifiedMode.CLAIM,
                emailVerifiedClaim = "user.email_verified",
            )

        assertThat(claims.emailVerified).isFalse()
    }

    // authorize-URL shape per protocol/pkce flags (research.md §16–§18)

    @Test
    fun oauth2UserinfoPkceFalseAuthorizeUrlOmitsCodeChallengeAndNonce() {
        val client = clientOf(oauth2Properties(pkce = false))

        val authorization = client.startAuthorization(PROVIDER_ID)

        val parameters = queryParametersOf(authorization.authorizationUrl)
        assertThat(parameters).containsKeys("client_id", "redirect_uri", "response_type", "state", "scope")
        // research.md §18: pkce=false sends no code_challenge leg…
        assertThat(parameters).doesNotContainKeys("code_challenge", "code_challenge_method")
        // …and research.md §17: the oauth2-userinfo branch binds no nonce
        assertThat(parameters).doesNotContainKey("nonce")
        // the flow-context shape stays uniform (data-model.md §5)
        assertThat(authorization.state).isNotBlank()
        assertThat(authorization.nonce).isNotBlank()
        assertThat(authorization.codeVerifier).isNotBlank()
    }

    @Test
    fun oauth2UserinfoPkceTrueKeepsCodeChallengeWithoutNonce() {
        val client = clientOf(oauth2Properties(pkce = true))

        val parameters = queryParametersOf(client.startAuthorization(PROVIDER_ID).authorizationUrl)

        assertThat(parameters).containsKeys("code_challenge", "code_challenge_method")
        assertThat(parameters["code_challenge_method"]).isEqualTo("S256")
        assertThat(parameters).doesNotContainKey("nonce")
    }

    @Test
    fun oidcProviderKeepsNonceAndPkce() {
        val client = clientOf(oidcProperties())

        val parameters = queryParametersOf(client.startAuthorization(PROVIDER_ID).authorizationUrl)

        assertThat(parameters).containsKeys("nonce", "code_challenge", "code_challenge_method")
        assertThat(parameters["code_challenge_method"]).isEqualTo("S256")
    }

    // backchannel legs against a local HTTP server (research.md §19)

    @Test
    fun oauth2FlowExchangesWithoutVerifierAndMapsUserinfoClaims() {
        val server = startLocalIdp()
        val client = clientOf(oauth2Properties(pkce = false, serverPort = server.address.port))

        val claims =
            client.completeAuthorizationCodeFlow(
                providerId = PROVIDER_ID,
                codeVerifier = "flow-context-verifier-placeholder",
                authorizationCode = "issued-code",
                nonce = "flow-context-nonce-placeholder",
                deadline = Instant.now().plusSeconds(5),
            )

        assertThat(claims.subject).isEqualTo("ya-subject-1")
        assertThat(claims.email).isEqualTo("user@ya.example")
        assertThat(claims.emailVerified).isTrue()
        val tokenRequest = recordedRequests.single { it.path == "/token" }
        // research.md §18: pkce=false → no code_verifier in the exchange
        assertThat(tokenRequest.body).doesNotContain("code_verifier")
        val userinfoRequest = recordedRequests.single { it.path == "/userinfo" }
        // research.md §17: the profile comes from the token-issuing source
        assertThat(userinfoRequest.authorization).isEqualTo("Bearer local-access-token")
    }

    @Test
    fun userinfoNon2xxIsTheTypedUserinfoEndpointFailure() {
        val server = startLocalIdp()
        userinfoScenario.set(UserinfoScenario.Not2xx)
        val client = clientOf(oauth2Properties(pkce = false, serverPort = server.address.port))

        assertThatThrownBy {
            client.completeAuthorizationCodeFlow(
                providerId = PROVIDER_ID,
                codeVerifier = "flow-context-verifier-placeholder",
                authorizationCode = "issued-code",
                nonce = "flow-context-nonce-placeholder",
                deadline = Instant.now().plusSeconds(5),
            )
        }.isInstanceOfSatisfying(OidcClientException::class.java) { e ->
            assertThat(e.reason).isEqualTo(OidcClientException.Reason.USERINFO_ENDPOINT)
            // SC-004: the access token never appears in the failure message
            assertThat(e.message).doesNotContain("local-access-token")
        }
    }

    @Test
    fun userinfoWithoutSubjectClaimIsTheIdTokenInvalidVerdict() {
        val server = startLocalIdp()
        userinfoScenario.set(UserinfoScenario.NoSubjectClaim)
        val client = clientOf(oauth2Properties(pkce = false, serverPort = server.address.port))

        assertThatThrownBy {
            client.completeAuthorizationCodeFlow(
                providerId = PROVIDER_ID,
                codeVerifier = "flow-context-verifier-placeholder",
                authorizationCode = "issued-code",
                nonce = "flow-context-nonce-placeholder",
                deadline = Instant.now().plusSeconds(5),
            )
        }.isInstanceOfSatisfying(OidcClientException::class.java) { e ->
            assertThat(e.reason).isEqualTo(OidcClientException.Reason.ID_TOKEN_INVALID)
            // SC-004: no profile values in the message
            assertThat(e.message).doesNotContain("user@ya.example")
        }
    }

    @Test
    fun oauth2FlowRecordsTheUserinfoMetric() {
        val registry = SimpleMeterRegistry()
        val server = startLocalIdp()
        val client =
            OidcClient(
                SsoProviderRegistry(oauth2Properties(pkce = false, serverPort = server.address.port)),
                SsoMetrics(registry),
            )

        client.completeAuthorizationCodeFlow(
            providerId = PROVIDER_ID,
            codeVerifier = "flow-context-verifier-placeholder",
            authorizationCode = "issued-code",
            nonce = "flow-context-nonce-placeholder",
            deadline = Instant.now().plusSeconds(5),
        )

        // research.md §14/§19: kind=userinfo joins kind=token in the timer
        assertThat(
            registry
                .find("sso_idp_call_duration")
                .tag("provider", PROVIDER_ID)
                .tag("kind", "userinfo")
                .timer(),
        ).isNotNull
        assertThat(
            registry
                .find("sso_idp_call_duration")
                .tag("provider", PROVIDER_ID)
                .tag("kind", "token")
                .timer(),
        ).isNotNull
    }

    @Test
    fun vkStyleFlowSendsDeviceIdAndPostCredentialsToTheTokenEndpoint() {
        val server = startLocalIdp()
        userinfoScenario.set(UserinfoScenario.Vk)
        val client = clientOf(vkProperties(server.address.port))

        val claims =
            client.completeAuthorizationCodeFlow(
                providerId = PROVIDER_ID,
                codeVerifier = "flow-context-verifier-placeholder",
                authorizationCode = "issued-code",
                nonce = "flow-context-nonce-placeholder",
                deadline = Instant.now().plusSeconds(5),
                deviceId = "vk-device-42",
            )

        // the nested profile mapped through the dot-path claims
        assertThat(claims.subject).isEqualTo("4242424")
        assertThat(claims.email).isEqualTo("user@vk.example")
        assertThat(claims.emailVerified).isTrue()
        val tokenRequest = recordedRequests.single { it.path == "/token" }
        // VK contract: credentials in the body, not the Basic header…
        assertThat(tokenRequest.authorization).isNull()
        assertThat(tokenRequest.body)
            .contains("client_id=webchat-it")
            .contains("client_secret=mock-secret")
        // …and the authorize-issued device_id returns at the exchange
        assertThat(tokenRequest.body).contains("device_id=vk-device-42")
    }

    // the GitHub emails-list selection — the pure unit leg (GET /user/emails)

    @Test
    fun primaryVerifiedEmailOfPicksThePrimaryVerifiedEntry() {
        val emails =
            listOf(
                mapOf("email" to "secondary@gh.example", "primary" to false, "verified" to true),
                mapOf("email" to "public@gh.example", "primary" to true, "verified" to false),
                mapOf("email" to "main@gh.example", "primary" to true, "verified" to true),
            )

        assertThat(OidcClient.primaryVerifiedEmailOf(emails)).isEqualTo("main@gh.example")
    }

    @Test
    fun primaryVerifiedEmailOfAnswersNullWithoutAVerifiedEntry() {
        listOf(
            null,
            emptyList<Any?>(),
            // primary but unverified — never trusted
            listOf(mapOf("email" to "public@gh.example", "primary" to true, "verified" to false)),
            // verified but not primary — not THE address
            listOf(mapOf("email" to "secondary@gh.example", "primary" to false, "verified" to true)),
            // malformed entries are skipped, not guessed around
            listOf("not-an-object", mapOf("email" to 42, "primary" to true, "verified" to true)),
        ).forEach { emails ->
            assertThat(OidcClient.primaryVerifiedEmailOf(emails)).isNull()
        }
    }

    // the GitHub shape: profile without an email fact + the /user/emails leg

    @Test
    fun githubStyleFlowOverridesTheProfileEmailFromTheEmailsEndpoint() {
        val server = startLocalIdp()
        userinfoScenario.set(UserinfoScenario.Github)
        emailsScenario.set(EmailsScenario.Ok)
        val client = clientOf(githubProperties(server.address.port))

        val claims =
            client.completeAuthorizationCodeFlow(
                providerId = PROVIDER_ID,
                codeVerifier = "flow-context-verifier-placeholder",
                authorizationCode = "issued-code",
                nonce = "flow-context-nonce-placeholder",
                deadline = Instant.now().plusSeconds(5),
            )

        // numeric `id` subject + the primary+verified address from the list
        assertThat(claims.subject).isEqualTo("9876543")
        assertThat(claims.email).isEqualTo("main@gh.example")
        assertThat(claims.emailVerified).isTrue()
        val tokenRequest = recordedRequests.single { it.path == "/token" }
        // GitHub contract: credentials in the body, no code_verifier (pkce off)
        assertThat(tokenRequest.authorization).isNull()
        assertThat(tokenRequest.body)
            .contains("client_id=webchat-it")
            .contains("client_secret=mock-secret")
            .doesNotContain("code_verifier")
        // the emails leg rode the SAME Bearer credential (FR-016 — on the spot)
        val emailsRequest = recordedRequests.single { it.path == "/emails" }
        assertThat(emailsRequest.authorization).isEqualTo("Bearer local-access-token")
    }

    @Test
    fun githubStyleEmailsEndpointFailureIsTheTypedUserinfoEndpointFailure() {
        val server = startLocalIdp()
        userinfoScenario.set(UserinfoScenario.Github)
        emailsScenario.set(EmailsScenario.Not2xx)
        val client = clientOf(githubProperties(server.address.port))

        assertThatThrownBy {
            client.completeAuthorizationCodeFlow(
                providerId = PROVIDER_ID,
                codeVerifier = "flow-context-verifier-placeholder",
                authorizationCode = "issued-code",
                nonce = "flow-context-nonce-placeholder",
                deadline = Instant.now().plusSeconds(5),
            )
        }.isInstanceOfSatisfying(OidcClientException::class.java) { e ->
            assertThat(e.reason).isEqualTo(OidcClientException.Reason.USERINFO_ENDPOINT)
            // SC-004: the access token never appears in the failure message
            assertThat(e.message).doesNotContain("local-access-token")
        }
    }

    @Test
    fun githubStyleProfileWithoutAVerifiedEmailStaysEmailLess() {
        val server = startLocalIdp()
        userinfoScenario.set(UserinfoScenario.Github)
        emailsScenario.set(EmailsScenario.NoVerifiedEntry)
        val client = clientOf(githubProperties(server.address.port))

        val claims =
            client.completeAuthorizationCodeFlow(
                providerId = PROVIDER_ID,
                codeVerifier = "flow-context-verifier-placeholder",
                authorizationCode = "issued-code",
                nonce = "flow-context-nonce-placeholder",
                deadline = Instant.now().plusSeconds(5),
            )

        // no verified list entry → the profile keeps its own (absent) email:
        // unverified addresses are never trusted (FR-004 posture)
        assertThat(claims.subject).isEqualTo("9876543")
        assertThat(claims.email).isNull()
        assertThat(claims.emailVerified).isFalse()
    }

    private fun clientOf(properties: SsoProperties): OidcClient =
        OidcClient(SsoProviderRegistry(properties), SsoMetrics(SimpleMeterRegistry()))

    private fun oauth2Properties(
        pkce: Boolean,
        serverPort: Int? = null,
    ): SsoProperties {
        val base = "http://127.0.0.1:${serverPort ?: UNUSED_PORT}"
        return propertiesOf(
            provider =
                SsoProperties.Provider(
                    displayName = "Yandex",
                    protocol = SsoProperties.Protocol.OAUTH2_USERINFO,
                    pkce = pkce,
                    subjectClaim = "psuid",
                    emailClaim = "default_email",
                    emailVerifiedMode = SsoProperties.EmailVerifiedMode.PROVIDER_GUARANTEED,
                    clientId = "webchat-it",
                    clientSecret = "mock-secret",
                    authorizationUri = "$base/authorize",
                    tokenUri = "$base/token",
                    userinfoUri = "$base/userinfo",
                ),
        )
    }

    private fun oidcProperties(): SsoProperties =
        propertiesOf(
            provider =
                SsoProperties.Provider(
                    displayName = "Dex",
                    clientId = "webchat-it",
                    clientSecret = "mock-secret",
                    authorizationUri = "http://127.0.0.1:$UNUSED_PORT/authorize",
                    tokenUri = "http://127.0.0.1:$UNUSED_PORT/token",
                    jwksUri = "http://127.0.0.1:$UNUSED_PORT/jwks",
                ),
        )

    /** The VK shape: dot-path claims, client-auth post, device_id forwarding, pkce off. */
    private fun vkProperties(serverPort: Int): SsoProperties {
        val base = "http://127.0.0.1:$serverPort"
        return propertiesOf(
            provider =
                SsoProperties.Provider(
                    displayName = "VK ID",
                    protocol = SsoProperties.Protocol.OAUTH2_USERINFO,
                    pkce = false,
                    subjectClaim = "user.user_id",
                    emailClaim = "user.email",
                    emailVerifiedMode = SsoProperties.EmailVerifiedMode.CLAIM,
                    emailVerifiedClaim = "user.email_verified",
                    clientAuth = SsoProperties.ClientAuth.POST,
                    tokenDeviceId = true,
                    clientId = "webchat-it",
                    clientSecret = "mock-secret",
                    authorizationUri = "$base/authorize",
                    tokenUri = "$base/token",
                    userinfoUri = "$base/userinfo",
                ),
        )
    }

    /** The GitHub shape: numeric `id` subject, claim mode, the /user/emails leg, pkce off. */
    private fun githubProperties(serverPort: Int): SsoProperties {
        val base = "http://127.0.0.1:$serverPort"
        return propertiesOf(
            provider =
                SsoProperties.Provider(
                    displayName = "GitHub",
                    protocol = SsoProperties.Protocol.OAUTH2_USERINFO,
                    pkce = false,
                    subjectClaim = "id",
                    emailClaim = "email",
                    emailVerifiedMode = SsoProperties.EmailVerifiedMode.CLAIM,
                    clientAuth = SsoProperties.ClientAuth.POST,
                    emailEndpoint = "$base/emails",
                    scopes = listOf("read:user", "user:email"),
                    clientId = "webchat-it",
                    clientSecret = "mock-secret",
                    authorizationUri = "$base/authorize",
                    tokenUri = "$base/token",
                    userinfoUri = "$base/userinfo",
                ),
        )
    }

    private fun propertiesOf(provider: SsoProperties.Provider): SsoProperties =
        SsoProperties(
            callbackUrl = CALLBACK_URL,
            flowTtl = Duration.ofMinutes(10),
            handshakeTtl = Duration.ofMinutes(2),
            providers = linkedMapOf(PROVIDER_ID to provider),
        )

    /** A minimal local IdP: the token and userinfo endpoints of the oauth2 branch. */
    private fun startLocalIdp(): HttpServer {
        val httpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/token") { exchange ->
                    val body = exchange.requestBody.readBytes().decodeToString()
                    val authorization = exchange.requestHeaders.getFirst("Authorization")
                    recordedRequests += RecordedRequest("/token", body, authorization)
                    respond(exchange, TOKEN_RESPONSE_JSON)
                }
                createContext("/userinfo") { exchange ->
                    val authorization = exchange.requestHeaders.getFirst("Authorization")
                    recordedRequests += RecordedRequest("/userinfo", "", authorization)
                    when (userinfoScenario.get()) {
                        UserinfoScenario.Ok -> respond(exchange, USERINFO_RESPONSE_JSON)
                        UserinfoScenario.Vk -> respond(exchange, VK_USERINFO_RESPONSE_JSON)
                        UserinfoScenario.Github -> respond(exchange, GITHUB_USERINFO_RESPONSE_JSON)
                        UserinfoScenario.Not2xx -> respond(exchange, ERROR_RESPONSE_JSON, 500)
                        UserinfoScenario.NoSubjectClaim -> respond(exchange, NO_SUBJECT_RESPONSE_JSON)
                    }
                }
                createContext("/emails") { exchange ->
                    val authorization = exchange.requestHeaders.getFirst("Authorization")
                    recordedRequests += RecordedRequest("/emails", "", authorization)
                    when (emailsScenario.get()) {
                        EmailsScenario.Ok -> respond(exchange, EMAILS_RESPONSE_JSON)
                        EmailsScenario.Not2xx -> respond(exchange, ERROR_RESPONSE_JSON, 500)
                        EmailsScenario.NoVerifiedEntry -> respond(exchange, NO_VERIFIED_EMAILS_RESPONSE_JSON)
                    }
                }
                start()
            }
        server = httpServer
        return httpServer
    }

    private fun respond(
        exchange: com.sun.net.httpserver.HttpExchange,
        body: String,
        status: Int = 200,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun queryParametersOf(url: String): Map<String, String> =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .toSingleValueMap()

    private data class RecordedRequest(
        val path: String,
        val body: String,
        val authorization: String?,
    )

    private enum class UserinfoScenario {
        Ok,
        Vk,
        Github,
        Not2xx,
        NoSubjectClaim,
    }

    private enum class EmailsScenario {
        Ok,
        Not2xx,
        NoVerifiedEntry,
    }

    private companion object {
        const val PROVIDER_ID = "yandex"

        const val CALLBACK_URL = "http://localhost:8080/api/v1/auth/sso/callback"

        /** No server is started for the offline URL-shape tests — the port stays unused. */
        const val UNUSED_PORT = 65535

        const val TOKEN_RESPONSE_JSON =
            """{"access_token":"local-access-token","token_type":"Bearer","expires_in":3600}"""

        const val USERINFO_RESPONSE_JSON =
            """{"psuid":"ya-subject-1","default_email":"user@ya.example"}"""

        /** The VK ID profile shape: nested under `user`, numeric subject. */
        const val VK_USERINFO_RESPONSE_JSON =
            """{"user":{"user_id":4242424,"email":"user@vk.example","email_verified":true}}"""

        /** The GitHub /user shape: numeric `id`, login — no email fact. */
        const val GITHUB_USERINFO_RESPONSE_JSON =
            """{"id":9876543,"login":"gh-user","email":null}"""

        /** The GitHub /user/emails shape: the primary+verified entry wins. */
        const val EMAILS_RESPONSE_JSON =
            """[{"email":"secondary@gh.example","primary":false,"verified":true,"visibility":"private"},""" +
                """{"email":"main@gh.example","primary":true,"verified":true,"visibility":null}]"""

        /** No verified entry — an unverified public address is never trusted. */
        const val NO_VERIFIED_EMAILS_RESPONSE_JSON =
            """[{"email":"public@gh.example","primary":true,"verified":false}]"""

        const val NO_SUBJECT_RESPONSE_JSON = """{"default_email":"user@ya.example"}"""

        const val ERROR_RESPONSE_JSON = """{"error":"server_error"}"""
    }
}
