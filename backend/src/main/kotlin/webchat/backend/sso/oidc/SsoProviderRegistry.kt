package webchat.backend.sso.oidc

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import webchat.backend.config.SsoProperties
import java.util.concurrent.ConcurrentHashMap

/**
 * A provider's [ClientRegistration] could not be built (T041, US4-4): the
 * OIDC discovery endpoint is unreachable, slower than the per-call caps,
 * answers non-2xx, or serves a malformed/issuer-mismatched document — or the
 * entry is internally inconsistent. Per-provider isolation: only THIS
 * provider's flows fail; the authorize leg answers a typed 502 problem+json
 * (never a bare 500) and the callback maps it to the single `provider_error`
 * redirect (contracts/sso-api.md §2–§3). The message carries the provider id
 * and a static reason only — never endpoints or credentials (SC-004/FR-011).
 */
class SsoProviderRegistrationException(
    val providerId: String,
    reason: String,
    cause: Throwable? = null,
) : RuntimeException("registration of provider '$providerId' cannot be built: $reason", cause)

/**
 * Registry of the configured external identity providers (T014, research.md §4–§5).
 *
 * Builds Spring Security [ClientRegistration]s programmatically from
 * [SsoProperties]: the standard `spring.security.oauth2.client.*` binding cannot
 * carry displayName/enabled/trusted flags, and a second config section would
 * drift (research.md §4). Components of spring-security-oauth2-client serve as
 * the provider model only — no servlet filters, no session-based login
 * (research.md §1, constitution II).
 *
 * Lookup semantics (contracts/sso-api.md §2, US4-3):
 * - [findEnabled] returns `null` for unknown AND disabled providers alike — the
 *   single 404 `Provider not found` on authorize, with no disclosure of which
 *   one it was (the provider list is public anyway);
 * - [find] returns a known provider regardless of state — the callback path
 *   distinguishes a provider disabled between authorize and callback and
 *   reports `provider_disabled` (T040/T041).
 *
 * [registrationOf] resolves a provider to its [ClientRegistration]:
 * - `oauth2-userinfo` (T056, US6) → deterministic build from the explicit
 *   authorization/token/userinfo endpoints — no discovery, no JWKS;
 * - explicit endpoints → deterministic build, no network;
 * - issuer-uri only → lazy OIDC discovery on first use, cached in memory
 *   (research.md §5: no IdP calls at startup, so boot never depends on external
 *   availability — US4-4 isolation). Discovery is fetched manually with the
 *   shared per-call caps (connect 1 s / read 2 s, research.md §1) because
 *   [ClientRegistrations.fromOidcIssuerLocation] has no timeouts of its own —
 *   a hanging issuer must never stall a request (SC-005). A discovery failure
 *   is NOT cached: the next use retries, so a flapping issuer recovers without
 *   a restart. Discovery runs inside the cache mapping, so a concurrent first
 *   use may block on it.
 *
 * Any registration failure leaves as the typed
 * [SsoProviderRegistrationException] (T041): one broken provider never takes
 * the others or the shared endpoints down (US4-4 isolation) — its flows get
 * 502 on authorize and `provider_error` on callback, everything else keeps
 * working.
 *
 * Registrations are immutable snapshots: a client secret rotated after the
 * first use keeps failing token exchanges with the old value until restart —
 * the flow is still rejected (spec Edge Cases, "secret compromised/rotated").
 */
@Component
class SsoProviderRegistry(
    properties: SsoProperties,
) {
    private val callbackUrl: String = properties.callbackUrl
    private val providers: Map<String, SsoProperties.Provider> = properties.providers
    private val registrations = ConcurrentHashMap<String, ClientRegistration>()

    // The OIDC discovery fetch, capped at the shared per-call limits
    // (research.md §1, SC-005) — the same transport budget OidcClient applies
    // to its token/JWKS calls.
    private val discoveryClient: RestClient =
        RestClient
            .builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                    setReadTimeout(READ_TIMEOUT_MILLIS)
                },
            ).build()

    /** Enabled providers in YAML declaration order (LinkedHashMap binding, T007). */
    fun enabledProviders(): List<SsoProvider> =
        providers.entries
            .filter { it.value.enabled }
            .map { SsoProvider(it.key, it.value) }

    /** Authorize lookup: unknown or disabled → `null` → unified 404 (contracts/sso-api.md §2). */
    fun findEnabled(id: String): SsoProvider? = providers[id]?.takeIf { it.enabled }?.let { SsoProvider(id, it) }

    /** Any known provider regardless of `enabled` — callback distinguishes `provider_disabled` (US4-3). */
    fun find(id: String): SsoProvider? = providers[id]?.let { SsoProvider(id, it) }

    /**
     * Cached [ClientRegistration] for the provider — call only for enabled
     * providers resolved via [findEnabled]/[find]; disabled ones may lack
     * client-id/secret/endpoints (T007 validates enabled providers only).
     */
    fun registrationOf(id: String): ClientRegistration = registrations.computeIfAbsent(id, this::registrationFor)

    /** Every build failure leaves as the typed exception (T041) — never a raw one. */
    @Suppress("TooGenericExceptionCaught") // the registry boundary: ANY build failure — missing config,
    // unusable discovery metadata, mapping errors — becomes the one typed answer
    private fun registrationFor(id: String): ClientRegistration =
        try {
            buildRegistration(id, providers.getValue(id))
        } catch (e: SsoProviderRegistrationException) {
            throw e
        } catch (e: Exception) {
            throw SsoProviderRegistrationException(id, "provider configuration cannot be resolved", e)
        }

    /**
     * Manual discovery leg (T041, research.md §5): fetches
     * `{issuer}/.well-known/openid-configuration` under the per-call caps and
     * reuses Spring's [ClientRegistrations.fromOidcConfiguration] mapping. The
     * `issuer` claim must equal the configured issuer — the same metadata
     * substitution guard `fromOidcIssuerLocation` applies.
     */
    private fun discoveryConfiguration(
        id: String,
        issuerUri: String,
    ): Map<String, Any> {
        val normalizedIssuer = issuerUri.removeSuffix(SLASH)
        val configuration = fetchConfiguration(id, normalizedIssuer)
        val documentIssuer = configuration[ISSUER_KEY] as? String
        if (documentIssuer == null || documentIssuer.removeSuffix(SLASH) != normalizedIssuer) {
            throw SsoProviderRegistrationException(id, "OIDC discovery document issuer mismatch")
        }
        return configuration
    }

    private fun fetchConfiguration(
        id: String,
        normalizedIssuer: String,
    ): Map<String, Any> {
        val configuration =
            try {
                discoveryClient
                    .get()
                    .uri("$normalizedIssuer$WELL_KNOWN_PATH")
                    .retrieve()
                    .body(CONFIGURATION_TYPE)
            } catch (e: RestClientException) {
                throw SsoProviderRegistrationException(id, "OIDC discovery endpoint is unreachable", e)
            } ?: throw SsoProviderRegistrationException(id, "OIDC discovery document is empty")
        return configuration
    }

    private fun buildRegistration(
        id: String,
        provider: SsoProperties.Provider,
    ): ClientRegistration {
        val builder =
            if (provider.protocol == SsoProperties.Protocol.OAUTH2_USERINFO) {
                // T056 (US6, research.md §16): oauth2-userinfo is built from
                // explicit endpoints only — no discovery, no JWKS (the profile
                // leg is the userinfo call, T007 guarantees the URIs for
                // enabled providers); issuer-uri/jwks-uri are ignored.
                ClientRegistration
                    .withRegistrationId(id)
                    .authorizationUri(
                        checkNotNull(provider.authorizationUri) {
                            "sso.providers[$id]: enabled oauth2-userinfo provider must have authorization-uri (T007)"
                        },
                    ).tokenUri(
                        checkNotNull(provider.tokenUri) {
                            "sso.providers[$id]: enabled oauth2-userinfo provider must have token-uri (T007)"
                        },
                    ).userInfoUri(
                        checkNotNull(provider.userinfoUri) {
                            "sso.providers[$id]: enabled oauth2-userinfo provider must have userinfo-uri (T007)"
                        },
                    )
            } else if (provider.authorizationUri != null && provider.tokenUri != null) {
                // Explicit endpoints win when both shapes are configured — deterministic, no network.
                ClientRegistration
                    .withRegistrationId(id)
                    .authorizationUri(provider.authorizationUri)
                    .tokenUri(provider.tokenUri)
                    .also { b ->
                        provider.issuerUri?.let(b::issuerUri)
                        provider.jwksUri?.let(b::jwkSetUri)
                        provider.userinfoUri?.let(b::userInfoUri)
                    }
            } else {
                ClientRegistrations
                    .fromOidcConfiguration(
                        discoveryConfiguration(
                            id,
                            checkNotNull(provider.issuerUri) {
                                "sso.providers[$id]: enabled provider must have issuer-uri or explicit endpoints (T007)"
                            },
                        ),
                    ).registrationId(id)
            }
        return builder
            .clientId(
                checkNotNull(provider.clientId) {
                    "sso.providers[$id]: enabled provider must have client-id (T007)"
                },
            ).clientSecret(
                checkNotNull(provider.clientSecret) {
                    "sso.providers[$id]: enabled provider must have client-secret (T007)"
                },
            ).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(callbackUrl)
            .scope(provider.scopes)
            .build()
    }

    /**
     * Immutable non-secret snapshot of a provider entry — safe to expose to
     * controllers and services; client credentials never leave the registry
     * (SC-004/FR-011). T056 (US6): carries the protocol mode, claim mapping
     * and PKCE flag so the flow legs (OidcClient, T057) branch without
     * re-reading the raw configuration.
     */
    class SsoProvider(
        val id: String,
        provider: SsoProperties.Provider,
    ) {
        val displayName: String = provider.displayName

        val trustedForEmailLinking: Boolean = provider.trustedForEmailLinking

        val enabled: Boolean = provider.enabled

        val protocol: SsoProperties.Protocol = provider.protocol

        val subjectClaim: String = provider.subjectClaim

        val emailClaim: String = provider.emailClaim

        val emailVerifiedMode: SsoProperties.EmailVerifiedMode = provider.emailVerifiedMode

        val pkce: Boolean = provider.pkce

        override fun toString(): String = "SsoProvider(id=$id, displayName=$displayName, enabled=$enabled, protocol=$protocol)"
    }

    private companion object {
        /** research.md §1, SC-005: the per-call transport caps shared with OidcClient. */
        const val CONNECT_TIMEOUT_MILLIS = 1_000

        const val READ_TIMEOUT_MILLIS = 2_000

        /** OIDC Discovery, RFC 8414 §4.1: `{issuer}/.well-known/openid-configuration`. */
        const val WELL_KNOWN_PATH = "/.well-known/openid-configuration"

        const val ISSUER_KEY = "issuer"

        const val SLASH = "/"

        val CONFIGURATION_TYPE = object : ParameterizedTypeReference<Map<String, Any>>() {}
    }
}
