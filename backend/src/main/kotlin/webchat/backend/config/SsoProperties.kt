package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * SSO settings (003-sso-identity-providers, research.md §4, data-model.md §4):
 * external identity providers under `sso.providers.<id>` plus flow-wide TTLs
 * and the OIDC redirect URI. The providers map binds into a LinkedHashMap, so
 * provider iteration (and `GET /auth/sso/providers`) follows YAML declaration
 * order. Configuration is validated fail-fast on startup: provider id format
 * and, for enabled providers only, presence of client-id, client-secret and
 * endpoints (issuer-uri for lazy discovery or explicit authorization/token/
 * jwks URIs); disabled providers may keep empty secrets for opt-in activation
 * via env (T042). The common settings (callback-url, flow-ttl, handshake-ttl)
 * have no code-side defaults — they are required and bound from the `sso.*`
 * section of application.yml (T002); provider attribute defaults follow
 * data-model.md §4. Client secrets come exclusively from `${SSO_*}` env
 * placeholders (SC-004).
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

    data class Provider(
        val displayName: String,
        val enabled: Boolean = true,
        val trustedForEmailLinking: Boolean = false,
        val clientId: String? = null,
        val clientSecret: String? = null,
        val issuerUri: String? = null,
        val authorizationUri: String? = null,
        val tokenUri: String? = null,
        val userinfoUri: String? = null,
        val jwksUri: String? = null,
        val scopes: List<String> = listOf("openid", "email"),
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
