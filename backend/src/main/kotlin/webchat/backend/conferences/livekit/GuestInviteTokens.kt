package webchat.backend.conferences.livekit

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.stereotype.Component
import webchat.backend.auth.security.Es256KeyRing
import webchat.backend.conferences.domain.model.GuestInvite
import webchat.backend.config.AuthJwtProperties
import java.time.Instant
import java.util.Date
import java.util.UUID

/** Validated nonce claims extracted by [GuestInviteTokens.verify]. */
data class GuestInviteNonce(
    val inviteId: UUID,
    val conferenceId: UUID,
    val expiresAt: Instant,
)

/**
 * Uniform verification failure carrying no distinguishing details, so the
 * public endpoints of T046 (№54/№55) can map every forgery mode to the same
 * opaque refusal (`{valid:false}` without a reason / `404 invite_invalid`)
 * — causes of forgery are never disclosed (009 api-contract §1 edge,
 * SC-003). Lifecycle states (expired/revoked/ended) are NOT forgery and stay
 * decidable by the caller from the status row.
 */
class InvalidGuestInviteTokenException(
    message: String,
) : RuntimeException(message)

/**
 * The stateless half of a guest invite link (009 research §6, T044): the
 * `{FRONTEND}/join/{nonce}` nonce is a JWT signed with the SAME ES256
 * kid-rotation infrastructure as the access tokens ([Es256KeyRing] over
 * `AUTH_JWT_KEYS`) — the `typ:"guest-invite"` claim keeps either token
 * class from being replayed as the other. Claims per research §6:
 * `sub = inviteId`, `cid = conferenceId`, `typ = "guest-invite"`,
 * `iat = createdAt`, `exp = expiresAt` (= `createdAt +
 * conferences.guest-invite-ttl`, 24 h default Q19=A — the mint takes the
 * [GuestInvite] row itself, so the JWT horizon and the status-row horizon
 * can never drift apart; data-model 009 §Сущность 3).
 *
 * Division of labour with the status layer (T046): [verify] proves
 * AUTHENTICITY only — signature, algorithm, kid, token type and claim
 * shape, every failure the one uniform [InvalidGuestInviteTokenException].
 * Expiry, revocation and conference state are disclosable lifecycle
 * decisions owned by №54/№55 against the `conference_guest_invites` row
 * (`reason:"expired"`, `409 invite_expired`, …), which is why [verify]
 * returns [GuestInviteNonce.expiresAt] instead of throwing on it: a
 * legitimately signed-but-expired nonce must still resolve to its row.
 */
@Component
class GuestInviteTokens(
    authJwtProperties: AuthJwtProperties,
) {
    private val keyRing = Es256KeyRing(authJwtProperties)

    /** Signs the nonce of [invite] — the cryptographic half of the №52 link (T045 appends it to the join URL). */
    fun mint(invite: GuestInvite): String {
        val jwt =
            SignedJWT(
                JWSHeader
                    .Builder(JWSAlgorithm.ES256)
                    .type(JOSEObjectType.JWT)
                    .keyID(keyRing.activeKid)
                    .build(),
                JWTClaimsSet
                    .Builder()
                    .subject(invite.id.toString())
                    .claim(CLAIM_CONFERENCE_ID, invite.conferenceId.toString())
                    .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_GUEST_INVITE)
                    .issueTime(Date.from(invite.createdAt))
                    .expirationTime(Date.from(invite.expiresAt))
                    .build(),
            )
        jwt.sign(ECDSASigner(keyRing.signingKey()))
        return jwt.serialize()
    }

    /**
     * Verifies the ES256 signature (kid lookup against the configured ring)
     * and the `typ:"guest-invite"` claim shape and returns the validated
     * claims. Expiry is deliberately NOT a verification failure — see the
     * class KDoc: the №54/№55 lifecycle reasons are decided by T046 from the
     * status row whose `expires_at` equals the JWT `exp` by construction.
     *
     * @throws InvalidGuestInviteTokenException for any unparseable,
     * foreign-key, tampered, mistyped or malformed-claim nonce — uniformly,
     * without details (edge «без раскрытия причин подделки»).
     */
    @Suppress("ThrowsCount") // straight-line validator: every failure throws the SAME uniform exception
    fun verify(token: String): GuestInviteNonce {
        val jwt =
            runCatching { SignedJWT.parse(token) }
                .getOrElse { throw InvalidGuestInviteTokenException("Unparseable guest invite nonce") }
        if (jwt.header.algorithm != JWSAlgorithm.ES256) {
            throw InvalidGuestInviteTokenException("Unexpected guest invite nonce algorithm")
        }
        val key =
            keyRing.verificationKey(jwt.header.keyID)
                ?: throw InvalidGuestInviteTokenException("Unknown guest invite nonce kid")
        val signatureValid =
            runCatching { jwt.verify(ECDSAVerifier(key.toPublicJWK())) }.getOrDefault(false)
        if (!signatureValid) {
            throw InvalidGuestInviteTokenException("Guest invite nonce signature verification failed")
        }
        val claims = jwt.getJWTClaimsSet()
        if (claims.getStringClaim(CLAIM_TOKEN_TYPE) != TOKEN_TYPE_GUEST_INVITE) {
            throw InvalidGuestInviteTokenException("Unexpected guest invite nonce type")
        }
        val expiresAt =
            claims.expirationTime?.toInstant()
                ?: throw InvalidGuestInviteTokenException("Missing exp claim")
        return GuestInviteNonce(
            inviteId = parseUuidClaim(claims.subject),
            conferenceId = parseUuidClaim(claims.getStringClaim(CLAIM_CONFERENCE_ID)),
            expiresAt = expiresAt,
        )
    }

    private fun parseUuidClaim(value: String?): UUID =
        value?.let { candidate -> runCatching { UUID.fromString(candidate) }.getOrNull() }
            ?: throw InvalidGuestInviteTokenException("Malformed UUID claim")

    private companion object {
        const val CLAIM_CONFERENCE_ID = "cid"
        const val CLAIM_TOKEN_TYPE = "typ"
        const val TOKEN_TYPE_GUEST_INVITE = "guest-invite"
    }
}
