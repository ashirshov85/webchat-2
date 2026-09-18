package webchat.backend.sso.oidc

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2TokenValidator
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
import org.springframework.web.client.RestOperations
import org.springframework.web.client.RestTemplate
import webchat.backend.sso.oidc.OidcClientException.Reason
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side half of an OIDC authorization code flow (T015, research.md §1):
 * PKCE S256 + state + nonce for the browser redirect, the code→token exchange
 * and ID-token verification. [SsoProviderRegistry] supplies the providers.
 *
 * The [OidcAuthorization.codeVerifier] and [OidcAuthorization.nonce] returned
 * by [startAuthorization] are flow secrets — the caller persists them in the
 * Redis flow context (`sso:flow:<state>`, data-model.md §5); the browser only
 * ever sees the challenge (server-side confidential client, research.md §3).
 *
 * External access tokens are NOT returned or stored — only the ID token is
 * consumed on the spot; the identity claims are all the domain needs (spec
 * Assumptions, "no external token storage"). Failures surface as
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
 * `exp`/`nonce`/`sub` verification ([Reason.ID_TOKEN_INVALID]), or the
 * callback deadline budget already spent ([Reason.DEADLINE_EXCEEDED]).
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
class OidcClient(
    private val registry: SsoProviderRegistry,
) {
    private val decoders = ConcurrentHashMap<String, NimbusJwtDecoder>()

    // A JWKS fetch through this template is capped at connect 1 s / read 2 s
    // (remaining == READ_TIMEOUT yields exactly the per-call caps).
    private val jwksRestOperations: RestOperations = RestTemplate(requestFactory(READ_TIMEOUT))

    /**
     * Builds the provider authorization URL (authorize step, T022) and the
     * secrets to persist: PKCE S256 challenge goes into the URL, the verifier
     * and nonce stay server-side (research.md §3). state/nonce are 256-bit
     * base64url tokens.
     */
    fun startAuthorization(providerId: String): OidcAuthorization {
        val registration = registry.registrationOf(providerId)
        val state = randomToken()
        val nonce = randomToken()
        val builder =
            baseRequestBuilder(registration)
                .state(state)
                .additionalParameters(mapOf(IdTokenClaimNames.NONCE to nonce))
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder)
        val request = builder.build()
        val codeVerifier = request.getAttribute(PkceParameterNames.CODE_VERIFIER) as String
        return OidcAuthorization(request.authorizationRequestUri, state, nonce, codeVerifier)
    }

    /**
     * Exchanges the authorization code at the provider token endpoint
     * (callback step) and returns the raw ID token. [codeVerifier] comes from
     * the consumed flow context — the rebuilt authorization request carries
     * it so the standard converter adds `code_verifier` to the token request.
     * Every other token of the response is discarded (no external token
     * storage). A 400/5xx answer, a wrong client secret or an unreachable
     * endpoint — including a secret rotated between authorize and callback
     * (spec Edge Cases) — surfaces as [OidcClientException].
     */
    fun exchangeCodeForIdToken(
        providerId: String,
        codeVerifier: String,
        authorizationCode: String,
        deadline: Instant,
    ): String {
        val remaining = remainingUntil(deadline)
        val registration = registry.registrationOf(providerId)
        val authorizationRequest =
            baseRequestBuilder(registration)
                .attributes(mapOf(PkceParameterNames.CODE_VERIFIER to codeVerifier))
                .build()
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
            }
        val tokenResponse =
            try {
                tokenResponseClient.getTokenResponse(grantRequest)
            } catch (e: OAuth2AuthorizationException) {
                throw OidcClientException(
                    Reason.TOKEN_ENDPOINT,
                    "token endpoint of provider '$providerId' rejected the exchange",
                    e,
                )
            }
        val idToken =
            tokenResponse.additionalParameters[OidcParameterNames.ID_TOKEN] as? String
                ?: throw OidcClientException(
                    Reason.ID_TOKEN_MISSING,
                    "provider '$providerId' returned no id_token",
                )
        return idToken
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
    fun verifyIdToken(
        providerId: String,
        idToken: String,
        expectedNonce: String,
        deadline: Instant,
    ): OidcIdentityClaims {
        remainingUntil(deadline)
        val jwt =
            try {
                decoderFor(providerId).decode(idToken)
            } catch (e: JwtException) {
                throw OidcClientException(
                    Reason.ID_TOKEN_INVALID,
                    "ID token of provider '$providerId' failed verification",
                    e,
                )
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

    private fun baseRequestBuilder(registration: ClientRegistration): OAuth2AuthorizationRequest.Builder =
        OAuth2AuthorizationRequest
            .authorizationCode()
            .clientId(registration.clientId)
            .authorizationUri(registration.providerDetails.authorizationUri)
            .redirectUri(registration.redirectUri)
            .scopes(registration.scopes)

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

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(1)

        val READ_TIMEOUT: Duration = Duration.ofSeconds(2)

        val SECURE_RANDOM = SecureRandom()

        const val RANDOM_TOKEN_BYTES = 32

        const val MIN_TIMEOUT_MILLIS = 1
    }
}
