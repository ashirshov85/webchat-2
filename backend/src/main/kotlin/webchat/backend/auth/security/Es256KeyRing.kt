package webchat.backend.auth.security

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import webchat.backend.config.AuthJwtProperties
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The `AUTH_JWT_KEYS` kid→P-256 key ring behind every ES256 signer of the
 * backend: access tokens ([JwtService]) and the guest-invite nonces of
 * 009-video-conferences (T044 reuses the kid rotation already in the
 * project, research 009 §6; the `typ` claim keeps the token classes from
 * being replayed against each other).
 *
 * Every entry of [AuthJwtProperties.keys] must be a PKCS#8 `PRIVATE KEY`
 * (signing) or an X.509 `PUBLIC KEY` (verification-only, e.g. a kid kept
 * alive during rotation) PEM, raw or Base64-of-PEM for single-line env
 * values. [AuthJwtProperties.activeKid] picks the signing kid and MUST
 * reference a private entry: malformed material, a missing active kid or a
 * public-only active key refuse the application startup — nothing can ever
 * be signed with placeholder keys (constitution V).
 */
class Es256KeyRing(
    jwtProperties: AuthJwtProperties,
) {
    /** The kid every new signature is minted under (its key is private, checked at startup). */
    val activeKid: String

    private val keysByKid: Map<String, ECKey>
    private val keyFactory: KeyFactory = KeyFactory.getInstance("EC")

    init {
        val parsedKeys = parseKeys(jwtProperties.keys)
        check(parsedKeys.isNotEmpty()) { "auth.jwt.keys must contain at least one kid=PEM entry" }
        val activeKey =
            checkNotNull(parsedKeys[jwtProperties.activeKid]) {
                "auth.jwt.active-kid <${jwtProperties.activeKid}> is absent from auth.jwt.keys ${parsedKeys.keys}"
            }
        check(activeKey.isPrivate) {
            "Active JWT key <${jwtProperties.activeKid}> has no private part and cannot sign"
        }
        activeKid = jwtProperties.activeKid
        keysByKid = parsedKeys
    }

    /** The private key new tokens are signed with (the active kid, validated in [init]). */
    fun signingKey(): ECKey = keysByKid.getValue(activeKid)

    /** The verification key of [kid], or `null` for an unknown/absent kid. */
    fun verificationKey(kid: String?): ECKey? = kid?.let(keysByKid::get)

    private fun parseKeys(rawKeys: String): Map<String, ECKey> =
        rawKeys
            .split(ENTRY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val separatorIndex = entry.indexOf(KEY_VALUE_SEPARATOR)
                check(separatorIndex > 0) { "Malformed auth.jwt.keys entry <$entry>: expected kid=PEM" }
                val kid = entry.substring(0, separatorIndex).trim()
                kid to parseKey(kid, entry.substring(separatorIndex + KEY_VALUE_SEPARATOR.length))
            }.toMap()

    private fun parseKey(
        kid: String,
        encodedKey: String,
    ): ECKey {
        val pem = decodePem(encodedKey)
        val publicKey: ECPublicKey
        val privateKey: ECPrivateKey?
        when {
            pem.contains(PKCS8_BEGIN) -> {
                val key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(pemBody(pem, PKCS8_BEGIN, PKCS8_END)))
                check(key is ECPrivateKey) { "JWT key <$kid> is not an EC private key" }
                privateKey = key
                publicKey = derivePublicKey(kid, key)
            }
            pem.contains(PUBLIC_BEGIN) -> {
                val key = keyFactory.generatePublic(X509EncodedKeySpec(pemBody(pem, PUBLIC_BEGIN, PUBLIC_END)))
                check(key is ECPublicKey) { "JWT key <$kid> is not an EC public key" }
                checkIsP256(kid, key.params)
                privateKey = null
                publicKey = key
            }
            else ->
                error(
                    "JWT key <$kid> must be a PKCS#8 EC private key (BEGIN PRIVATE KEY) " +
                        "or an X.509 public key (BEGIN PUBLIC KEY) PEM",
                )
        }
        val builder = ECKey.Builder(Curve.P_256, publicKey).keyID(kid)
        privateKey?.let { builder.privateKey(it) }
        return builder.build()
    }

    /**
     * A PKCS#8 EC private key carries no public point, so the public key is
     * recovered as d*G over the (already P-256-verified) named curve.
     */
    private fun derivePublicKey(
        kid: String,
        privateKey: ECPrivateKey,
    ): ECPublicKey {
        checkIsP256(kid, privateKey.params)
        val publicPoint = P256_PARAMETERS.g.multiply(privateKey.s).normalize()
        val publicSpec =
            ECPublicKeySpec(
                java.security.spec.ECPoint(
                    publicPoint.affineXCoord.toBigInteger(),
                    publicPoint.affineYCoord.toBigInteger(),
                ),
                privateKey.params,
            )
        return keyFactory.generatePublic(publicSpec) as ECPublicKey
    }

    private fun checkIsP256(
        kid: String,
        params: ECParameterSpec,
    ) {
        check(EC5Util.convertCurve(params.curve) == P256_PARAMETERS.curve) {
            "JWT key <$kid> must be P-256 (ES256)"
        }
    }

    private fun decodePem(encodedKey: String): String {
        val value = encodedKey.trim().replace(ESCAPED_NEWLINE, NEWLINE)
        if (value.contains(PEM_BEGIN_MARKER)) {
            return value
        }
        return runCatching { String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8) }
            .getOrElse { e ->
                throw IllegalStateException("JWT key value is neither PEM nor Base64-encoded PEM", e)
            }
    }

    private fun pemBody(
        pem: String,
        beginMarker: String,
        endMarker: String,
    ): ByteArray {
        val base64 =
            pem
                .substringAfter(beginMarker)
                .substringBefore(endMarker)
                .replace(NEWLINE, "")
                .replace(CARRIAGE_RETURN, "")
                .trim()
        return runCatching { Base64.getMimeDecoder().decode(base64) }
            .getOrElse { throw IllegalStateException("JWT key PEM body is not valid Base64") }
    }

    private companion object {
        const val ENTRY_SEPARATOR = ","
        const val KEY_VALUE_SEPARATOR = "="
        const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
        const val PKCS8_END = "-----END PRIVATE KEY-----"
        const val PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----"
        const val PUBLIC_END = "-----END PUBLIC KEY-----"
        const val PEM_BEGIN_MARKER = "-----BEGIN"
        const val ESCAPED_NEWLINE = "\\n"
        const val NEWLINE = "\n"
        const val CARRIAGE_RETURN = "\r"

        val P256_PARAMETERS: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec("secp256r1")
    }
}
