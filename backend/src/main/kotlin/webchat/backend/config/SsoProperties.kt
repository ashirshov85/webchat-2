package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * SSO settings (003-sso-identity-providers, research.md §4, §16–§18,
 * data-model.md §4): external identity providers under `sso.providers.<id>`
 * plus flow-wide TTLs and the OIDC redirect URI. The providers map binds into
 * a LinkedHashMap, so provider iteration (and `GET /auth/sso/providers`)
 * follows YAML declaration order. Configuration is validated fail-fast on
 * startup: provider id format and, for enabled providers only, presence of
 * client-id, client-secret and endpoints — protocol-dependent (T056, US6):
 * `oidc` needs issuer-uri for lazy discovery or explicit authorization/token/
 * jwks URIs; `oauth2-userinfo` needs explicit authorization/token/userinfo
 * URIs (issuer/jwks are OIDC-only and ignored); `pkce: false` still requires
 * client-secret (FR-015 confidential code exchange). Disabled providers may
 * keep empty secrets for opt-in activation via env (T042). The common
 * settings (callback-url, flow-ttl, handshake-ttl) have no code-side
 * defaults — they are required and bound from the `sso.*` section of
 * application.yml (T002); provider attribute defaults follow data-model.md
 * §4 and research.md §16/§18: `protocol: oidc`, `subject-claim: sub`,
 * `email-claim: email`, `email-verified-mode: claim`, `pkce: true`.
 * Client secrets come exclusively from `${SSO_*}` env placeholders (SC-004).
 */
@ConfigurationProperties(prefix = "sso")
data class SsoProperties(
    val callbackUrl: String,
    val flowTtl: Duration,
    val handshakeTtl: Duration,
    val providers: Map<String, Provider> = LinkedHashMap(),
) {
    init {
        require(callbackUrl.isNotBlank()) { "sso.callback-url must not be blank" }
        require(flowTtl.isPositive) { "sso.flow-ttl must be positive" }
        require(handshakeTtl.isPositive) { "sso.handshake-ttl must be positive" }
        providers.forEach { (id, provider) -> provider.validate(id) }
    }

    /**
     * Protocol branch of the flow (research.md §16, US6): `oidc` —
     * authorization code + signed ID-token verification (default, backward
     * compatible: dex/google keep their behavior); `oauth2-userinfo` —
     * authorization code + backchannel userinfo call instead of ID-token
     * verification (Yandex-style OAuth2-only providers).
     */
    enum class Protocol {
        OIDC,
        OAUTH2_USERINFO,
    }

    /**
     * Client authentication of the token endpoint (RFC 6749 §2.3.1):
     * `basic` — HTTP Basic `Authorization` header (default); `post` —
     * `client_id`/`client_secret` in the request body (VK-style providers
     * that reject the Basic header).
     */
    enum class ClientAuth {
        BASIC,
        POST,
    }

    /**
     * Source of the "email verified" fact (research.md §16): `claim` — read
     * the boolean `email_verified` claim; `provider-guaranteed` — the
     * provider guarantees the configured email claim is verified by
     * definition (Yandex `default_email`).
     */
    enum class EmailVerifiedMode {
        CLAIM,
        PROVIDER_GUARANTEED,
    }

    data class Provider(
        val displayName: String,
        val enabled: Boolean = true,
        val trustedForEmailLinking: Boolean = false,
        val protocol: Protocol = Protocol.OIDC,
        val subjectClaim: String = "sub",
        val emailClaim: String = "email",
        val emailVerifiedMode: EmailVerifiedMode = EmailVerifiedMode.CLAIM,
        val pkce: Boolean = true,
        val clientId: String? = null,
        val clientSecret: String? = null,
        val issuerUri: String? = null,
        val authorizationUri: String? = null,
        val tokenUri: String? = null,
        val userinfoUri: String? = null,
        val jwksUri: String? = null,
        val scopes: List<String> = listOf("openid", "email"),
        // VK ID (RFC 6749 §2.3.1): token-endpoint client authentication shape
        val clientAuth: ClientAuth = ClientAuth.BASIC,
        // claim name (dot-path allowed) of the boolean verified fact; null → standard `email_verified`
        val emailVerifiedClaim: String? = null,
        // VK ID: forward the callback `device_id` parameter into the token exchange body
        val tokenDeviceId: Boolean = false,
    ) {
        fun validate(id: String) {
            require(providerIdRegex.matches(id)) {
                "sso.providers[$id]: provider id must match $PROVIDER_ID_PATTERN"
            }
            require(displayName.isNotBlank()) { "sso.providers[$id]: display-name must not be blank" }
            requireHttpUri(id, "issuer-uri", issuerUri)
            requireHttpUri(id, "authorization-uri", authorizationUri)
            requireHttpUri(id, "token-uri", tokenUri)
            requireHttpUri(id, "userinfo-uri", userinfoUri)
            requireHttpUri(id, "jwks-uri", jwksUri)
            if (!enabled) return
            require(!clientId.isNullOrBlank()) { "sso.providers[$id]: enabled provider must have client-id" }
            require(!clientSecret.isNullOrBlank()) { "sso.providers[$id]: enabled provider must have client-secret" }
            if (!pkce) {
                // FR-015: pkce=false only drops the code_challenge leg — the
                // confidential code exchange still requires client-secret
                require(!clientSecret.isNullOrBlank()) {
                    "sso.providers[$id]: enabled provider with pkce=false must have client-secret (FR-015)"
                }
            }
            when (protocol) {
                Protocol.OAUTH2_USERINFO -> {
                    // research.md §16: explicit endpoints are mandatory;
                    // issuer-uri/jwks-uri are OIDC-only and ignored here
                    listOf(
                        "authorization-uri" to authorizationUri,
                        "token-uri" to tokenUri,
                        "userinfo-uri" to userinfoUri,
                    ).forEach { (name, uri) ->
                        require(!uri.isNullOrBlank()) {
                            "sso.providers[$id]: enabled oauth2-userinfo provider must have $name"
                        }
                    }
                }
                Protocol.OIDC ->
                    if (issuerUri.isNullOrBlank()) {
                        listOf(
                            "authorization-uri" to authorizationUri,
                            "token-uri" to tokenUri,
                            "jwks-uri" to jwksUri,
                        ).forEach { (name, uri) ->
                            require(!uri.isNullOrBlank()) {
                                "sso.providers[$id]: enabled provider must have $name when issuer-uri is absent"
                            }
                        }
                    }
            }
        }

        private fun requireHttpUri(
            id: String,
            name: String,
            value: String?,
        ) {
            if (value.isNullOrBlank()) return
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri?.scheme == "http" || uri?.scheme == "https") {
                "sso.providers[$id]: $name must be an absolute http(s) URI"
            }
        }

        companion object {
            private const val PROVIDER_ID_PATTERN = "^[a-z0-9][a-z0-9-]{0,63}$"
            private val providerIdRegex = Regex(PROVIDER_ID_PATTERN)
        }
    }
}
