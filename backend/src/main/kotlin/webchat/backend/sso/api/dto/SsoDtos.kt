package webchat.backend.sso.api.dto

import java.time.Instant
import java.util.UUID

/**
 * `SsoProvider` item of `SsoProvidersResponse` (sso-api.md §1): exactly
 * `{id, displayName}` (`additionalProperties: false`). `id` is the stable
 * provider code from the `sso.providers` configuration (`^[a-z0-9][a-z0-9-]{0,63}$`,
 * SsoProperties validation); `displayName` is the button label.
 */
data class SsoProviderResponse(
    val id: String,
    val displayName: String,
)

/**
 * Contract §1 success body — `SsoProvidersResponse` (sso-api.md §1):
 * enabled providers in configuration order. Disabled and unknown providers
 * are never listed; an empty list is valid (SSO not configured, FR-008).
 */
data class SsoProvidersResponse(
    val providers: List<SsoProviderResponse>,
)

/**
 * Contract §2 request body — `SsoAuthorizeRequest` (sso-api.md §2).
 *
 * Fields are nullable so an absent field stays on the endpoint's declared
 * error legs (404 problem for an unknown provider, field-level `Problem.errors`
 * for `returnTo`) instead of breaking deserialization: every error response
 * stays problem+json. `returnTo` is an optional in-SPA relative path
 * (`maxLength: 512`); the server validates only the relative format —
 * non-relative or over-long values yield `errors: {returnTo: ["invalid_format"]}`.
 */
data class SsoAuthorizeRequest(
    val providerId: String? = null,
    val returnTo: String? = null,
)

/**
 * Contract §2 success body — `SsoAuthorizeResponse` (sso-api.md §2): the
 * IdP authorization URL containing `state`, `nonce` and the PKCE S256
 * `code_challenge`. The verifier stays server-side (confidential client);
 * the SPA must perform a browser redirect (`window.location.assign`).
 */
data class SsoAuthorizeResponse(
    val authorizationUrl: String,
)

/**
 * Contract §4 request body — `SsoTokenRequest` (sso-api.md §4): nullable
 * for the same problem+json reason as [SsoAuthorizeRequest]; an absent or
 * malformed code maps to the uniform 400 `errors: {code: ["invalid_code"]}`
 * (unknown/used/expired handshake code — indistinguishable).
 */
data class SsoTokenRequest(
    val code: String? = null,
)

/**
 * Contract §5 request body — `SsoLinkAuthorizeRequest` (sso-api.md §5):
 * `{providerId}` only. Nullable so an absent field stays on the endpoint's
 * declared 404 problem leg (unknown or disabled provider — one answer)
 * instead of breaking deserialization.
 */
data class SsoLinkAuthorizeRequest(
    val providerId: String? = null,
)

/**
 * Contract §6 item — `Identity` (sso-api.md §6): one binding of the current
 * user. [providerDisplayName] comes from the live configuration and is
 * `null` when the provider has been removed from it — the binding itself
 * survives (US4-3); [email] is the last provider-known email (nullable when
 * the provider returned none, data-model.md §1).
 */
data class SsoIdentityResponse(
    val id: UUID,
    val providerId: String,
    val providerDisplayName: String?,
    val email: String?,
    val linkedAt: Instant,
)

/** Contract §6 success body — `IdentitiesResponse` (sso-api.md §6), ordered by `linkedAt`. */
data class SsoIdentitiesResponse(
    val identities: List<SsoIdentityResponse>,
)
