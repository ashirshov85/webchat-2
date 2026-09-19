package webchat.backend.sso.oidc

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.core.oidc.StandardClaimNames
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestOperations
import org.springframework.web.client.RestTemplate
import webchat.backend.config.SsoProperties
import webchat.backend.sso.SsoMetrics
import webchat.backend.sso.oidc.OidcClientException.Reason
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side half of the SSO authorization code flow (T015, research.md §1)
 * with the two protocol branches of T057 (US6, research.md §16):
 * - `oidc` — PKCE S256 + state + nonce for the browser redirect, the code→token
 *   exchange and ID-token verification against the provider JWKS;
 * - `oauth2-userinfo` — the same code exchange (PKCE per provider flag,
 *   research.md §18), then a backchannel Bearer userinfo call whose claims are
 *   mapped into [OidcIdentityClaims] by the configured
 *   `subject-claim`/`email-claim`/`email-verified-mode` (research.md §16).
 * [SsoProviderRegistry] supplies the providers.
 *
 * The [OidcAuthorization.codeVerifier] and [OidcAuthorization.nonce] returned
 * by [startAuthorization] are flow secrets — the caller persists them in the
 * Redis flow context (`sso:flow:<state>`, data-model.md §5); the browser only
 * ever sees the challenge (server-side confidential client, research.md §3).
 * Both are minted for every protocol so the flow-context shape stays uniform
 * (data-model.md §5); the `oauth2-userinfo` branch never sends the nonce
 * (no ID token to bind it to, research.md §17) and a `pkce: false` provider
 * never uses the verifier (research.md §18).
 *
 * External access tokens are NOT returned or stored (FR-016) — the userinfo
 * call consumes one on the spot; the identity claims are all the domain needs
 * (spec Assumptions, "no external token storage"). Failures surface as
 * [OidcClientException] with non-secret markers only (SC-004, FR-011).
 */
data class OidcAuthorization(
    val authorizationUrl: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
)

/** Verified identity claims of an ID token (data-model.md §1). */
data class OidcIdentityClaims(
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
)

/**
 * A provider interaction failed: transport error or non-2xx token response
 * ([Reason.TOKEN_ENDPOINT]), no `id_token` in the response
 * ([Reason.ID_TOKEN_MISSING]), a token that failed signature/`iss`/`aud`/
 * `exp`/`nonce`/`sub` verification ([Reason.ID_TOKEN_INVALID]) — also the
 * verdict of an oauth2-userinfo profile without a usable subject claim,
 * which is the same "identity unusable" class (T057) — a failed userinfo
 * leg ([Reason.USERINFO_ENDPOINT]), or the callback deadline budget already
 * spent ([Reason.DEADLINE_EXCEEDED]).
 *
 * The caller maps every reason to the `provider_error` redirect and the
 * `sso_flow_error` audit event (contracts/sso-api.md §3, research.md §10) —
 * messages carry only the provider id and a static reason, never tokens,
 * codes or secrets (SC-004, FR-011).
 */
class OidcClientException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    enum class Reason {
        TOKEN_ENDPOINT,
        ID_TOKEN_MISSING,
        ID_TOKEN_INVALID,
        USERINFO_ENDPOINT,
        DEADLINE_EXCEEDED,
    }
}

/**
 * OIDC client built from spring-security-oauth2-client components WITHOUT the
 * servlet oauth2-login filters (T015, research.md §1): authorization requests
 * with PKCE S256 come from [OAuth2AuthorizationRequestCustomizers], the token
 * exchange from [RestClientAuthorizationCodeTokenResponseClient], and ID-token
 * verification from Spring's Nimbus integration — the pieces that are
 * stateless and fit the JSON-controller architecture (constitution II).
 *
 * Timeouts (research.md §1, §5, SC-005): every provider call is capped at
 * connect 1 s / read 2 s, and the callback carries an overall deadline
 * (5 s) that is re-checked before each call, so the sum of provider calls on
 * a callback fails fast with a clear error instead of hanging — a degraded
 * provider never blocks the user for long (US4-4).
 *
 * JWKS handling: the [NimbusJwtDecoder] per provider is cached lazily (first
 * successful login pays the fetch); Nimbus keeps the key set cached,
 * rate-limits re-fetches and refreshes on an unknown `kid` (research.md §5).
 * Issuer discovery itself is the registry's concern — `registrationOf` keeps
 * discovered metadata in memory (T014).
 */
@Component
@Suppress("TooManyFunctions") // T057: one function per flow leg — the two protocol branches share the class
class OidcClient(
    private val registry: SsoProviderRegistry,
    private val ssoMetrics: SsoMetrics,
) {
    private val decoders = ConcurrentHashMap<String, NimbusJwtDecoder>()

    // A JWKS fetch through this template is capped at connect 1 s / read 2 s
    // (remaining == READ_TIMEOUT yields exactly the per-call caps).
    private val jwksRestOperations: RestOperations = RestTemplate(requestFactory(READ_TIMEOUT))

    /**
     * Builds the provider authorization URL (authorize step, T022) and the
     * secrets to persist. Protocol/PKCE branches (T057, research.md §16/§18):
     * - `oidc` — PKCE S256 challenge in the URL (unless `pkce: false`) plus a
     *   `nonce` parameter bound to the later ID-token check;
     * - `oauth2-userinfo` — no `nonce` (no ID token to bind, research.md §17);
     *   PKCE applies per the provider flag alone.
     *
     * state/nonce/verifier are 256-bit base64url tokens; all three are minted
     * regardless of branch so the Redis flow context keeps its uniform shape
     * (data-model.md §5) — the unused legs are simply never sent anywhere.
     */
    fun startAuthorization(providerId: String): OidcAuthorization {
        val registration = registry.registrationOf(providerId)
        val provider = providerOf(providerId)
        val state = randomToken()
        val nonce = randomToken()
        val builder =
            baseRequestBuilder(registration)
                .state(state)
        if (provider.protocol == SsoProperties.Protocol.OIDC) {
            builder.additionalParameters(mapOf(IdTokenClaimNames.NONCE to nonce))
        }
        if (provider.pkce) {
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder)
        }
        val request = builder.build()
        // pkce=false: no challenge was sent, so the minted verifier is an
        // inert placeholder of the uniform flow context — never transmitted
        val codeVerifier = request.getAttribute(PkceParameterNames.CODE_VERIFIER) as? String ?: randomToken()
        return OidcAuthorization(request.authorizationRequestUri, state, nonce, codeVerifier)
    }

    /**
     * The callback backchannel (T057): the code→token exchange plus the
     * protocol-dependent profile leg, both inside the overall callback
     * deadline (SC-005) —
     * - `oidc`: [exchangeCode] then [verifyIdToken] (T015 behavior unchanged);
     * - `oauth2-userinfo`: [exchangeCode] (without `code_verifier` for a
     *   `pkce: false` provider, research.md §18), then the Bearer userinfo
     *   call whose claims are mapped by the provider's
     *   `subject-claim`/`email-claim`/`email-verified-mode` (research.md §16).
     *
     * The external access token of the oauth2 branch exists only inside this
     * call — it backs the single userinfo request and is never returned,
     * stored or logged (FR-016). A missing or blank subject claim is the
     * `ID_TOKEN_INVALID`-equivalent verdict: a provider without a unique
     * subject is treated as misconfigured and the flow is rejected.
     */
    @Suppress("LongParameterList") // one flat parameter per flow leg (data-model.md §5) + the VK device binding
    fun completeAuthorizationCodeFlow(
        providerId: String,
        codeVerifier: String,
        authorizationCode: String,
        nonce: String,
        deadline: Instant,
        deviceId: String? = null,
    ): OidcIdentityClaims {
        val provider = providerOf(providerId)
        val tokenResponse =
            exchangeCode(provider, codeVerifier, authorizationCode, deadline, deviceId)
        return when (provider.protocol) {
            SsoProperties.Protocol.OIDC -> {
                val idToken =
                    tokenResponse.additionalParameters[OidcParameterNames.ID_TOKEN] as? String
                        ?: throw OidcClientException(
                            Reason.ID_TOKEN_MISSING,
                            "provider '$providerId' returned no id_token",
                        )
                verifyIdToken(providerId, idToken, nonce, deadline)
            }
            SsoProperties.Protocol.OAUTH2_USERINFO ->
                fetchUserinfoClaims(
                    providerId,
                    provider,
                    tokenResponse.accessToken.tokenValue,
                    deadline,
                )
        }
    }

    /**
     * Exchanges the authorization code at the provider token endpoint
     * (callback step) and returns the full token response. [codeVerifier]
     * comes from the consumed flow context — for a `pkce: true` provider the
     * rebuilt authorization request carries it so the standard converter adds
     * `code_verifier` to the token request; a `pkce: false` provider never
     * sees the parameter (research.md §18). Every other token of the response
     * is discarded (no external token storage). A 400/5xx answer, a wrong
     * client secret or an unreachable endpoint — including a secret rotated
     * between authorize and callback (spec Edge Cases) — surfaces as
     * [OidcClientException].
     */
    private fun exchangeCode(
        provider: SsoProviderRegistry.SsoProvider,
        codeVerifier: String,
        authorizationCode: String,
        deadline: Instant,
        deviceId: String? = null,
    ): OAuth2AccessTokenResponse {
        val providerId = provider.id
        val remaining = remainingUntil(deadline)
        val registration = registry.registrationOf(providerId)
        val requestBuilder = baseRequestBuilder(registration)
        if (provider.pkce) {
            requestBuilder.attributes(mapOf(PkceParameterNames.CODE_VERIFIER to codeVerifier))
        }
        val authorizationRequest = requestBuilder.build()
        val authorizationResponse =
            OAuth2AuthorizationResponse
                .success(authorizationCode)
                .redirectUri(registration.redirectUri)
                .build()
        val grantRequest =
            OAuth2AuthorizationCodeGrantRequest(
                registration,
                OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse),
            )
        val tokenResponseClient =
            RestClientAuthorizationCodeTokenResponseClient().apply {
                // Same converter/error-handler wiring as the framework's own
                // builder (AbstractRestClientOAuth2AccessTokenResponseClient):
                // a bare RestClient would let Jackson guess-deserialize
                // OAuth2AccessTokenResponse (additionalParameters == null) and
                // would surface provider errors as generic RestClientExceptions
                // instead of OAuth2AuthorizationException.
                setRestClient(
                    RestClient
                        .builder()
                        .requestFactory(requestFactory(remaining))
                        .messageConverters { converters ->
                            converters.clear()
                            converters.add(FormHttpMessageConverter())
                            converters.add(OAuth2AccessTokenResponseHttpMessageConverter())
                        }.defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
                        .build(),
                )
                // VK ID contract: the authorize leg issued `device_id` — its
                // token endpoint requires the very value back in the body.
                // Gated by the provider flag, so no other provider ever
                // sends the parameter; the client instance is per-call, so
                // the closure carries this flow's value only.
                if (provider.tokenDeviceId && !deviceId.isNullOrBlank()) {
                    setParametersCustomizer { parameters -> parameters.add(DEVICE_ID_PARAMETER, deviceId) }
                }
            }
        // research.md §14: the token leg is timed as
        // `sso_idp_call_duration{provider, kind=token}` on success AND
        // failure — the slow/errored provider calls are the signal (SC-005)
        val tokenSample = ssoMetrics.startIdpCall(providerId, SsoMetrics.IdpCallKind.TOKEN)
        val tokenResponse =
            try {
                tokenResponseClient.getTokenResponse(grantRequest)
            } catch (e: OAuth2AuthorizationException) {
                throw OidcClientException(
                    Reason.TOKEN_ENDPOINT,
                    "token endpoint of provider '$providerId' rejected the exchange",
                    e,
                )
            } finally {
                tokenSample.stop()
            }
        return tokenResponse
    }

    /**
     * Verifies the ID token (spec Edge Cases, data-model.md §1): signature
     * against the provider JWKS plus `iss` (when the provider declares an
     * issuer), `aud`, `exp` via the standard validators, then `nonce` against
     * the flow and a non-blank `sub` — a provider without a unique subject is
     * treated as misconfigured and the flow is rejected. Returns the identity
     * claims; email is trimmed (data-model.md §1), `email_verified` defaults
     * to `false` when absent or unparseable.
     */
    private fun verifyIdToken(
        providerId: String,
        idToken: String,
        expectedNonce: String,
        deadline: Instant,
    ): OidcIdentityClaims {
        remainingUntil(deadline)
        // research.md §14: the verification leg (incl. the lazily fetched
        // key set) is timed as `sso_idp_call_duration{provider, kind=jwks}`
        // on success AND failure (SC-005)
        val jwksSample = ssoMetrics.startIdpCall(providerId, SsoMetrics.IdpCallKind.JWKS)
        val jwt =
            try {
                decoderFor(providerId).decode(idToken)
            } catch (e: JwtException) {
                throw OidcClientException(
                    Reason.ID_TOKEN_INVALID,
                    "ID token of provider '$providerId' failed verification",
                    e,
                )
            } finally {
                jwksSample.stop()
            }
        val invalidation =
            when {
                jwt.subject.isNullOrBlank() -> "empty subject"
                expectedNonce != jwt.getClaimAsString(IdTokenClaimNames.NONCE) -> "mismatched nonce"
                else -> null
            }
        if (invalidation != null) {
            throw OidcClientException(
                Reason.ID_TOKEN_INVALID,
                "ID token of provider '$providerId' was rejected: $invalidation",
            )
        }
        return OidcIdentityClaims(
            subject = jwt.subject,
            email = jwt.getClaimAsString(StandardClaimNames.EMAIL)?.trim()?.takeIf(String::isNotEmpty),
            emailVerified = emailVerifiedOf(jwt),
        )
    }

    /**
     * The profile leg of the `oauth2-userinfo` branch (T057, research.md
     * §16/§19): `GET <userinfo-uri>` with the exchanged access token as the
     * Bearer credential — the compensating trust measure of the mode: the
     * claims come from the very source that issued the token to THIS
     * client_id over the server-to-server TLS backchannel (research.md §17).
     *
     * The access token lives only in this stack frame (FR-016). The call
     * carries the per-call caps (connect 1 s / read 2 s, SC-005) shrunk to
     * the remaining callback budget and is timed as
     * `sso_idp_call_duration{provider, kind=userinfo}` on success AND failure
     * (research.md §14). A transport error, a non-2xx answer or an unreadable
     * body is the typed [Reason.USERINFO_ENDPOINT] failure; a profile without
     * a usable subject claim is the [Reason.ID_TOKEN_INVALID]-equivalent
     * verdict of [userinfoIdentityClaims]. Messages never contain the access
     * token or any claim values (SC-004).
     *
     * GitHub-style providers (spec.md "Расширение на GitHub") declare an
     * `email-endpoint`: the userinfo profile of GitHub carries no verified
     * email fact, so a second Bearer GET fetches the emails list and its
     * `primary && verified` entry overrides the `email`/`email_verified`
     * facts of the profile (timed as `kind=emails`, same caps and deadline
     * budget). A list without a verified entry leaves the profile untouched —
     * the flow then proceeds email-less (no JIT, no auto-linking), never
     * trusting an unverified address.
     */
    private fun fetchUserinfoClaims(
        providerId: String,
        provider: SsoProviderRegistry.SsoProvider,
        accessToken: String,
        deadline: Instant,
    ): OidcIdentityClaims {
        val remaining = remainingUntil(deadline)
        val registration = registry.registrationOf(providerId)
        val userinfoUri =
            registration.providerDetails.userInfoEndpoint.uri
                ?: throw OidcClientException(
                    Reason.USERINFO_ENDPOINT,
                    "provider '$providerId' has no userinfo endpoint configured",
                )
        val body = userinfoBodyOf(providerId, userinfoUri, accessToken, remaining)
        val effectiveBody =
            provider.emailEndpoint?.let { endpoint ->
                val emails = emailsBodyOf(providerId, endpoint, accessToken, remainingUntil(deadline))
                primaryVerifiedEmailOf(emails)?.let { email ->
                    (body ?: LinkedHashMap()) +
                        mapOf(
                            StandardClaimNames.EMAIL to email,
                            StandardClaimNames.EMAIL_VERIFIED to true,
                        )
                }
            } ?: body
        return userinfoIdentityClaims(
            effectiveBody,
            provider.subjectClaim,
            provider.emailClaim,
            provider.emailVerifiedMode,
            provider.emailVerifiedClaim,
        )
    }

    /** The timed `GET userinfo` itself — every transport/decoding failure is the one typed answer. */
    private fun userinfoBodyOf(
        providerId: String,
        userinfoUri: String,
        accessToken: String,
        remaining: Duration,
    ): Map<String, Any>? {
        val userinfoClient =
            RestClient
                .builder()
                .requestFactory(requestFactory(remaining))
                .build()
        val userinfoSample = ssoMetrics.startIdpCall(providerId, SsoMetrics.IdpCallKind.USERINFO)
        return try {
            userinfoClient
                .get()
                .uri(userinfoUri)
                .header(HttpHeaders.AUTHORIZATION, "$BEARER_PREFIX$accessToken")
                .retrieve()
                .body(USERINFO_TYPE)
        } catch (e: RestClientException) {
            throw OidcClientException(
                Reason.USERINFO_ENDPOINT,
                "userinfo endpoint of provider '$providerId' failed",
                e,
            )
        } catch (e: HttpMessageConversionException) {
            throw OidcClientException(
                Reason.USERINFO_ENDPOINT,
                "userinfo response of provider '$providerId' is unreadable",
                e,
            )
        } finally {
            userinfoSample.stop()
        }
    }

    /**
     * The timed `GET <email-endpoint>` of the GitHub shape: the same Bearer
     * credential and transport budget as the userinfo leg, timed as
     * `sso_idp_call_duration{provider, kind=emails}` (research.md §14); every
     * transport/decoding failure is the same [Reason.USERINFO_ENDPOINT]
     * answer — it IS the profile leg of such providers. The token never
     * leaves this frame (FR-016), the message never carries values (SC-004).
     */
    private fun emailsBodyOf(
        providerId: String,
        emailEndpoint: String,
        accessToken: String,
        remaining: Duration,
    ): List<Any?>? {
        val emailsClient =
            RestClient
                .builder()
                .requestFactory(requestFactory(remaining))
                .build()
        val emailsSample = ssoMetrics.startIdpCall(providerId, SsoMetrics.IdpCallKind.EMAILS)
        return try {
            emailsClient
                .get()
                .uri(emailEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "$BEARER_PREFIX$accessToken")
                .retrieve()
                .body(EMAILS_TYPE)
        } catch (e: RestClientException) {
            throw OidcClientException(
                Reason.USERINFO_ENDPOINT,
                "email endpoint of provider '$providerId' failed",
                e,
            )
        } catch (e: HttpMessageConversionException) {
            throw OidcClientException(
                Reason.USERINFO_ENDPOINT,
                "email response of provider '$providerId' is unreadable",
                e,
            )
        } finally {
            emailsSample.stop()
        }
    }

    private fun baseRequestBuilder(registration: ClientRegistration): OAuth2AuthorizationRequest.Builder =
        OAuth2AuthorizationRequest
            .authorizationCode()
            .clientId(registration.clientId)
            .authorizationUri(registration.providerDetails.authorizationUri)
            .redirectUri(registration.redirectUri)
            .scopes(registration.scopes)

    private fun providerOf(providerId: String): SsoProviderRegistry.SsoProvider =
        registry.find(providerId)
            ?: throw SsoProviderRegistrationException(providerId, "provider is unknown")

    private fun decoderFor(providerId: String): NimbusJwtDecoder =
        decoders.computeIfAbsent(providerId) { id -> buildDecoder(id, registry.registrationOf(id)) }

    private fun buildDecoder(
        providerId: String,
        registration: ClientRegistration,
    ): NimbusJwtDecoder {
        val jwkSetUri =
            registration.providerDetails.jwkSetUri
                ?: error("provider '$providerId' has no jwks-uri configured")
        val validators =
            buildList<OAuth2TokenValidator<Jwt>> {
                registration.providerDetails.issuerUri?.let { add(JwtValidators.createDefaultWithIssuer(it)) }
                add(OidcIdTokenValidator(registration))
            }
        return NimbusJwtDecoder
            .withJwkSetUri(jwkSetUri)
            .restOperations(jwksRestOperations)
            .build()
            .apply { setJwtValidator(DelegatingOAuth2TokenValidator(validators)) }
    }

    /**
     * Per-call transport caps shrunk to the remaining deadline budget
     * (SC-005): connect ≤ 1 s, read ≤ 2 s and never more time than the
     * callback has left.
     */
    private fun requestFactory(remaining: Duration): SimpleClientHttpRequestFactory =
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(minOf(CONNECT_TIMEOUT, remaining).toMillis().toInt().coerceAtLeast(MIN_TIMEOUT_MILLIS))
            setReadTimeout(minOf(READ_TIMEOUT, remaining).toMillis().toInt().coerceAtLeast(MIN_TIMEOUT_MILLIS))
        }

    private fun remainingUntil(deadline: Instant): Duration {
        val remaining = Duration.between(Instant.now(), deadline)
        if (remaining.isNegative || remaining.isZero) {
            throw OidcClientException(
                Reason.DEADLINE_EXCEEDED,
                "callback deadline is exhausted before calling the identity provider",
            )
        }
        return remaining
    }

    private fun emailVerifiedOf(jwt: Jwt): Boolean =
        when (val raw = jwt.claims[StandardClaimNames.EMAIL_VERIFIED]) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull() ?: false
            else -> false
        }

    /** 256-bit base64url token (research.md §3) — 43 characters. */
    private fun randomToken(): String {
        val bytes = ByteArray(RANDOM_TOKEN_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    internal companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(1)

        val READ_TIMEOUT: Duration = Duration.ofSeconds(2)

        val USERINFO_TYPE = object : ParameterizedTypeReference<Map<String, Any>>() {}

        /** The GitHub `/user/emails` list: `[{email, primary, verified, ...}]`. */
        val EMAILS_TYPE = object : ParameterizedTypeReference<List<Any?>>() {}

        val SECURE_RANDOM = SecureRandom()

        const val RANDOM_TOKEN_BYTES = 32

        const val MIN_TIMEOUT_MILLIS = 1

        const val BEARER_PREFIX = "Bearer "

        /** VK ID token-exchange body parameter carrying the authorize-issued device binding. */
        const val DEVICE_ID_PARAMETER = "device_id"

        /** Field names of the GitHub `/user/emails` entries (GET /user/emails contract). */
        const val EMAILS_EMAIL_FIELD = "email"

        const val EMAILS_PRIMARY_FIELD = "primary"

        const val EMAILS_VERIFIED_FIELD = "verified"

        const val DOT = "."

        /**
         * Maps the userinfo profile JSON into [OidcIdentityClaims] (T057,
         * research.md §16): [subjectClaim] (default `sub`, Yandex `psuid`) —
         * a missing, blank or non-scalar value is the
         * [Reason.ID_TOKEN_INVALID]-equivalent verdict: a provider without a
         * unique subject is unusable; [emailClaim] (default `email`, Yandex
         * `default_email`) — trimmed, empty/absent → `null`; the verified
         * fact per [SsoProperties.EmailVerifiedMode] — `CLAIM` reads the
         * boolean `email_verified` (string tolerated, absent/unparseable →
         * `false`), `PROVIDER_GUARANTEED` is `true` by definition (the
         * email-owning resolution rows still gate on the email itself,
         * data-model.md §7).
         */
        @Throws(OidcClientException::class)
        fun userinfoIdentityClaims(
            body: Map<String, Any?>?,
            subjectClaim: String,
            emailClaim: String,
            emailVerifiedMode: SsoProperties.EmailVerifiedMode,
            emailVerifiedClaim: String? = null,
        ): OidcIdentityClaims {
            val subject =
                when (val raw = claimAt(body, subjectClaim)) {
                    is String -> raw.trim()
                    is Number -> raw.toString()
                    else -> null
                }?.takeIf(String::isNotEmpty)
                    ?: throw OidcClientException(
                        Reason.ID_TOKEN_INVALID,
                        "userinfo profile carries no usable subject claim '$subjectClaim'",
                    )
            return OidcIdentityClaims(
                subject = subject,
                email = (claimAt(body, emailClaim) as? String)?.trim()?.takeIf(String::isNotEmpty),
                emailVerified =
                    when (emailVerifiedMode) {
                        SsoProperties.EmailVerifiedMode.PROVIDER_GUARANTEED -> true
                        SsoProperties.EmailVerifiedMode.CLAIM ->
                            emailVerifiedOf(
                                claimAt(body, emailVerifiedClaim ?: StandardClaimNames.EMAIL_VERIFIED),
                            )
                    },
            )
        }

        /**
         * Resolves a claim path through nested JSON objects: a plain name
         * (`psuid`) reads the top level, a dot-path (`user.user_id` — the VK
         * ID profile shape) walks the nesting; a missing leg answers `null`.
         */
        private fun claimAt(
            body: Map<String, Any?>?,
            path: String,
        ): Any? {
            var current: Any? = body
            for (segment in path.split(DOT)) {
                current = (current as? Map<*, *>)?.get(segment) ?: return null
            }
            return current
        }

        /**
         * The GitHub emails-list verdict (spec.md «Расширение на GitHub»):
         * the one `primary && verified` entry is THE usable address — GitHub
         * itself verifies every listed mailbox; anything else (no list, a
         * non-array body, no verified entry) answers `null` so the flow
         * never trusts an unverified address. Only scalar shape is read —
         * a malformed entry is skipped, not guessed around.
         */
        fun primaryVerifiedEmailOf(emails: List<Any?>?): String? =
            emails
                ?.asSequence()
                ?.filterIsInstance<Map<*, *>>()
                ?.firstOrNull { entry ->
                    entry[EMAILS_PRIMARY_FIELD] == true && entry[EMAILS_VERIFIED_FIELD] == true
                }?.get(EMAILS_EMAIL_FIELD) as? String

        private fun emailVerifiedOf(raw: Any?): Boolean =
            when (raw) {
                is Boolean -> raw
                is String -> raw.toBooleanStrictOrNull() ?: false
                else -> false
            }
    }
}
