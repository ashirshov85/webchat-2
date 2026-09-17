package webchat.backend.sso.oidc

import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.stereotype.Component
import webchat.backend.config.SsoProperties
import java.util.concurrent.ConcurrentHashMap

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
 * - explicit endpoints → deterministic build, no network;
 * - issuer-uri only → lazy OIDC discovery on first use, cached in memory
 *   (research.md §5: no IdP calls at startup, so boot never depends on external
 *   availability — US4-4 isolation). Discovery runs inside the cache mapping, so
 *   a concurrent first use may block on it; transport timeouts stay with
 *   OidcClient (T015).
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

    private fun registrationFor(id: String): ClientRegistration = buildRegistration(id, providers.getValue(id))

    private fun buildRegistration(
        id: String,
        provider: SsoProperties.Provider,
    ): ClientRegistration {
        val builder =
            if (provider.authorizationUri != null && provider.tokenUri != null) {
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
                    .fromOidcIssuerLocation(
                        checkNotNull(provider.issuerUri) {
                            "sso.providers[$id]: enabled provider must have issuer-uri or explicit endpoints (T007)"
                        },
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
     * (SC-004/FR-011).
     */
    class SsoProvider(
        val id: String,
        provider: SsoProperties.Provider,
    ) {
        val displayName: String = provider.displayName

        val trustedForEmailLinking: Boolean = provider.trustedForEmailLinking

        val enabled: Boolean = provider.enabled

        override fun toString(): String = "SsoProvider(id=$id, displayName=$displayName, enabled=$enabled)"
    }
}
