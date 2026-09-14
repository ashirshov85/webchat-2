package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * ES256 signing keys for access tokens (`auth.jwt.*`, T028, research.md §4).
 *
 * [keys] maps kids to P-256 keys as a comma-separated `kid=PEM` list; each PEM
 * may be a raw PKCS#8 private/public document (literal newlines or `\n`
 * escapes) or its Base64-encoded form for single-line env values. [activeKid]
 * picks the signing key; the remaining kids stay verifiable so a new kid can
 * be added while previously issued tokens still pass verification (key
 * rotation, constitution V). Both values are mandatory — a missing property
 * fails the application startup (fail-fast).
 */
@ConfigurationProperties(prefix = "auth.jwt")
data class AuthJwtProperties(
    val keys: String,
    val activeKid: String,
)
