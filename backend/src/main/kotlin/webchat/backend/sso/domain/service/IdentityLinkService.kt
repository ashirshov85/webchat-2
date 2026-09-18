package webchat.backend.sso.domain.service

import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.sso.domain.model.ExternalIdentity
import webchat.backend.sso.domain.port.ExternalIdentityRepository
import webchat.backend.sso.oidc.OidcIdentityClaims
import java.sql.SQLException
import java.util.UUID

/**
 * The outcome of the link-branch callback (data-model.md §7 Link): the
 * caller (T035) maps [Linked]/[AlreadyLinked] to the
 * `302 /settings/security?linked=<providerId>` success redirect and
 * [IdentityTaken] to `?sso_error=identity_taken` (contracts/sso-api.md §3).
 */
sealed interface IdentityLinkOutcome {
    /** The identity was free and is now bound to the user → `sso_identity_linked`. */
    data class Linked(
        val providerId: String,
    ) : IdentityLinkOutcome

    /**
     * FR-012 no-op: the identity already belongs to the user — a silent
     * success without a duplicate binding and without a second journal
     * entry (research.md §13в).
     */
    data class AlreadyLinked(
        val providerId: String,
    ) : IdentityLinkOutcome

    /** US3-2 / FR-006: the identity belongs to another account — no owner details. */
    data class IdentityTaken(
        val providerId: String,
    ) : IdentityLinkOutcome
}

/**
 * 404 (contracts/sso-api.md §7): the identity is unknown or belongs to
 * another user — one uniform answer, no holder disclosure.
 */
class SsoIdentityNotFoundException : RuntimeException("Identity not found")

/**
 * 409 (contracts/sso-api.md §7, US3-4): the unlink would remove the last
 * login method of a password-less account — set a password first.
 */
class SsoLastLoginMethodException : RuntimeException("Set a password before removing the last login method")

/**
 * Manual identity linking/unlinking — the domain core of US3
 * (data-model.md §7 Link/Unlink; tasks.md T034).
 *
 * **Link** (the link-branch callback, purpose=link, Bearer-authenticated
 * user): a free `(providerId, subject)` is bound to the current user with the
 * provider email as-is — the account email is never rewritten (spec
 * Assumptions, US3-5); the user's OWN identity re-links as a no-op success
 * (FR-012); an identity owned by anyone else is refused with `identity_taken`
 * without owner details (FR-006). Two concurrent link flows racing for the
 * same free identity are settled by ux_external_identities_provider_subject —
 * the savepoint-free loser re-resolves ownership against the winning row
 * instead of surfacing an error (research.md §13в).
 *
 * **Unlink** (`DELETE /users/me/identities/{identityId}`): the binding must
 * belong to the current user (otherwise the uniform 404); a password-less
 * account must keep at least one binding (US3-4) — the app-level guard
 * answers fast, while the `keep_at_least_one_login_method` DB trigger of V9
 * closes the concurrent-unlink race app checks cannot (data-model.md §6/§8,
 * mapped here to [SsoLastLoginMethodException]). Sessions are NOT revoked:
 * `sessions.identity_id` is ON DELETE SET NULL, only new logins through the
 * unlinked provider are blocked (clarify 2026-09-16, US3-6).
 *
 * Journal contract (research.md §10, FR-011): `sso_identity_linked` /
 * `sso_identity_unlinked` carry only the provider marker; the `identity_taken`
 * refusal is journaled as `sso_flow_error {provider, reason}` — never tokens,
 * codes or secrets.
 */
@Service
class IdentityLinkService(
    private val externalIdentityRepository: ExternalIdentityRepository,
    private val userRepository: UserRepository,
    private val authEventRecorder: AuthEventRecorder,
    private val clock: Clock,
) {
    /**
     * Applies the Link row of data-model.md §7 to the verified claims of the
     * link-flow callback; [userId] comes from the purpose=link flow context
     * (data-model.md §5), never from the IdP. The account email is never
     * touched — the provider email lands on the binding alone.
     */
    @Transactional
    fun linkIdentity(
        userId: UUID,
        providerId: String,
        claims: OidcIdentityClaims,
        clientIp: String,
        userAgent: String? = null,
    ): IdentityLinkOutcome {
        val existing = externalIdentityRepository.findByProviderAndSubject(providerId, claims.subject)
        if (existing != null) return resolveExistingLink(userId, providerId, existing.userId, clientIp, userAgent)

        val identityId = UUID.randomUUID()
        return try {
            externalIdentityRepository.insert(
                ExternalIdentity.link(
                    id = identityId,
                    userId = userId,
                    providerId = providerId,
                    subject = claims.subject,
                    providerEmail = claims.email,
                    providerEmailVerified = claims.emailVerified,
                    at = clock.now(),
                ),
            )
            authEventRecorder.record(
                eventType = AuthEventType.SSO_IDENTITY_LINKED,
                clientIp = clientIp,
                userAgent = userAgent,
                userId = userId,
                details = mapOf(DETAIL_PROVIDER to providerId),
            )
            IdentityLinkOutcome.Linked(providerId)
        } catch (violation: DataIntegrityViolationException) {
            // research.md §13в: a concurrent flow won the insert — the DB is
            // the final arbiter, the loser re-resolves ownership
            val winnerUserId =
                externalIdentityRepository.findByProviderAndSubject(providerId, claims.subject)?.userId
                    ?: throw violation
            resolveExistingLink(userId, providerId, winnerUserId, clientIp, userAgent)
        }
    }

    /**
     * Removes the user's binding (contracts/sso-api.md §7): 204 via [delete],
     * the uniform [SsoIdentityNotFoundException] for a missing or foreign
     * identity, [SsoLastLoginMethodException] when the app guard or the V9
     * trigger refuses the last login method of a password-less account.
     * Sessions stay alive (US3-6); `sso_identity_unlinked` is journaled
     * atomically with the delete.
     *
     * ThrowsCount suppressed: the three throw legs ARE the contract — the
     * uniform 404 (foreign or missing identity, vanished account) plus the
     * two last-login-method guards (app check and DB trigger, US3-4).
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun unlinkIdentity(
        userId: UUID,
        identityId: UUID,
        clientIp: String,
        userAgent: String? = null,
    ) {
        val identities = externalIdentityRepository.findByUserId(userId)
        val identity = identities.find { it.id == identityId } ?: throw SsoIdentityNotFoundException()
        val user = userRepository.findById(userId) ?: throw SsoIdentityNotFoundException()

        // US3-4 app check: password-less + this being the only binding — a
        // fast 409 without touching the DB (the trigger remains the authority)
        val keepsAnotherBinding = identities.any { it.id != identityId }
        if (user.passwordHash == null && !keepsAnotherBinding) throw SsoLastLoginMethodException()

        try {
            externalIdentityRepository.delete(identity.id)
        } catch (guard: DataAccessException) {
            // the V9 trigger refuses the concurrent double unlink of a
            // password-less account — exactly one racer may win (data-model.md §6)
            if (!isLastLoginMethodGuard(guard)) throw guard
            throw SsoLastLoginMethodException()
        }
        authEventRecorder.record(
            eventType = AuthEventType.SSO_IDENTITY_UNLINKED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = userId,
            details = mapOf(DETAIL_PROVIDER to identity.providerId),
        )
    }

    /**
     * The known-identity leg of linking (data-model.md §7 Link): the owner
     * gets the FR-012 no-op success, anyone else the owner-free
     * `identity_taken` refusal — journaled as `sso_flow_error` with the
     * non-secret reason marker only (research.md §10).
     */
    private fun resolveExistingLink(
        userId: UUID,
        providerId: String,
        ownerUserId: UUID,
        clientIp: String,
        userAgent: String?,
    ): IdentityLinkOutcome =
        if (ownerUserId == userId) {
            IdentityLinkOutcome.AlreadyLinked(providerId)
        } else {
            authEventRecorder.record(
                eventType = AuthEventType.SSO_FLOW_ERROR,
                clientIp = clientIp,
                userAgent = userAgent,
                userId = userId,
                details =
                    mapOf(
                        DETAIL_PROVIDER to providerId,
                        DETAIL_REASON to REASON_IDENTITY_TAKEN,
                    ),
            )
            IdentityLinkOutcome.IdentityTaken(providerId)
        }

    /**
     * The V9 trigger surfaces as `RAISE EXCEPTION 'last login method cannot
     * be removed'` (SQLState P0001) — matched by the marker message we own,
     * through the whole translator cause chain.
     */
    private fun isLastLoginMethodGuard(ex: DataAccessException): Boolean =
        generateSequence(ex as Throwable?) { it.cause }
            .filterIsInstance<SQLException>()
            .any { cause -> cause.message?.contains(TRIGGER_MARKER, ignoreCase = true) == true }

    private companion object {
        /** research.md §10 detail vocabulary — non-secret markers only. */
        const val DETAIL_PROVIDER = "provider"

        const val DETAIL_REASON = "reason"

        /** The public refusal code of the Link row (contracts/sso-api.md §3). */
        const val REASON_IDENTITY_TAKEN = "identity_taken"

        /** The RAISE EXCEPTION marker of keep_at_least_one_login_method (V9). */
        const val TRIGGER_MARKER = "last login method"
    }
}
