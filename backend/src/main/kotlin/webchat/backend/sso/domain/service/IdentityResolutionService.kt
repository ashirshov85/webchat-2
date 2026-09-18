package webchat.backend.sso.domain.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import webchat.backend.auth.domain.model.UserStatus
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.sso.domain.port.ExternalIdentityRepository
import webchat.backend.sso.oidc.OidcIdentityClaims
import java.util.UUID

/**
 * The public `sso_error` codes the login resolution can reject with
 * (contracts/sso-api.md §3, data-model.md §7 rows 2–6 and 8).
 *
 * The full vocabulary is fixed by the contract up front; T021 produces only
 * [REJECTED] (the existing-identity branch), T029 adds the first-login
 * producers ([EMAIL_NOT_VERIFIED], [EMAIL_CONFLICT],
 * [REGISTRATION_INCOMPLETE]). `identity_taken` is a link-flow outcome and
 * belongs to IdentityLinkService (T034), not to login resolution.
 */
enum class SsoLoginRejection(
    val errorCode: String,
) {
    /** Row 3: the provider did not confirm the email — no binding, no JIT (FR-004). */
    EMAIL_NOT_VERIFIED("email_not_verified"),

    /** Row 5: an active account owns the email but the provider is outside the allowlist. */
    EMAIL_CONFLICT("email_conflict"),

    /** Row 6: a pending/awaiting_password account owns the email — finish 002 registration first (US2-4). */
    REGISTRATION_INCOMPLETE("registration_incomplete"),

    /** Row 2 and the catch-all of §3: any other protective flow rejection. */
    REJECTED("rejected"),
}

/**
 * The decision of the login resolution table (data-model.md §7) — the callback
 * step (T022) turns it into either a single-use handshake (`LoginGranted`) or
 * the `?sso_error=<code>` redirect (`LoginRejected`); no session is opened
 * here, sessions live behind `POST /auth/sso/token` (data-model.md §5).
 */
sealed interface IdentityResolution {
    /**
     * Rows 1, 4 and 7: the login is granted — [userId] with [identityId] is
     * exactly the payload the caller persists as the handshake
     * `{userId, identityId, providerId}` (data-model.md §5).
     */
    data class LoginGranted(
        val userId: UUID,
        val identityId: UUID,
        val providerId: String,
    ) : IdentityResolution

    /** Row 8: the flow is rejected; accounts, bindings and sessions stay untouched. */
    data class LoginRejected(
        val rejection: SsoLoginRejection,
    ) : IdentityResolution
}

/**
 * Identity resolution — the core of the SSO login (data-model.md §7; T021).
 *
 * T021 implements the EXISTING-identity branch (rows 1–2): the verified ID-token
 * claims of `(providerId, subject)` resolve a bound identity, an `active` owner
 * is logged in with the provider email refreshed on every successful login,
 * and the journal gains `sso_login_success {resolution: existing_identity}`
 * (FR-011, research.md §10). A non-active owner is the protective row-2
 * rejection — `sso_login_failed` + `rejected`, no side effects (row 8).
 *
 * T029 extends the same method with the full first-login matrix (rows 3–8:
 * email gate, trusted auto-linking, conflicts, JIT); until then an unknown
 * `(providerId, subject)` takes the catch-all [SsoLoginRejection.REJECTED]
 * with no account, binding or session created.
 *
 * Journal contract (SC-004, FR-011): every event carries only the provider id
 * and a static resolution/reason marker — never tokens, codes or secrets
 * (research.md §10). The session itself is opened later by the token
 * exchange through [webchat.backend.auth.domain.service.SessionService]
 * with `auth_method='sso'` + `identity_id` (T016, research.md §11).
 */
@Service
class IdentityResolutionService(
    private val externalIdentityRepository: ExternalIdentityRepository,
    private val userRepository: UserRepository,
    private val authEventRecorder: AuthEventRecorder,
) {
    /**
     * Applies the data-model.md §7 table to the verified claims of the
     * callback's ID token. Success updates `provider_email*` and journals
     * `sso_login_success` atomically; every rejection journals
     * `sso_login_failed` and leaves the database untouched.
     *
     * ReturnCount suppressed: the exit legs ARE the contract — row 1 grant +
     * row 2 protective rejection + the T029 first-login extension point
     * (tasks.md T021/T029).
     */
    @Suppress("ReturnCount")
    @Transactional
    fun resolveLogin(
        providerId: String,
        claims: OidcIdentityClaims,
        clientIp: String,
        userAgent: String? = null,
    ): IdentityResolution {
        val identity =
            externalIdentityRepository.findByProviderAndSubject(providerId, claims.subject)
                // T029 extension point: the first-login matrix (rows 3–8).
                ?: return rejectLogin(providerId, userId = null, SsoLoginRejection.REJECTED, clientIp, userAgent)

        val user = userRepository.findById(identity.userId)
        if (user == null || user.status != UserStatus.ACTIVE) {
            // Row 2: a bound identity cannot belong to a non-active account
            // (V9 keeps registration states password-only) — protective rejection.
            return rejectLogin(providerId, identity.userId, SsoLoginRejection.REJECTED, clientIp, userAgent)
        }

        // Row 1: refresh the provider email on every successful login
        // (data-model.md §1) — last known state, including a cleared email.
        externalIdentityRepository.updateProviderEmail(identity.id, claims.email, claims.emailVerified)
        authEventRecorder.record(
            eventType = AuthEventType.SSO_LOGIN_SUCCESS,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = user.id,
            details =
                mapOf(
                    DETAIL_PROVIDER to providerId,
                    DETAIL_RESOLUTION to RESOLUTION_EXISTING_IDENTITY,
                ),
        )
        return IdentityResolution.LoginGranted(
            userId = user.id,
            identityId = identity.id,
            providerId = providerId,
        )
    }

    /**
     * Row 8: journal `sso_login_failed` with the non-secret reason marker and
     * return the rejection — the callback maps it to `?sso_error=<code>`.
     */
    private fun rejectLogin(
        providerId: String,
        userId: UUID?,
        rejection: SsoLoginRejection,
        clientIp: String,
        userAgent: String?,
    ): IdentityResolution.LoginRejected {
        // written inside the caller's transaction: the method returns normally,
        // so nothing rolls the journal back (unlike the throwing 401 legs of
        // LoginService)
        authEventRecorder.record(
            eventType = AuthEventType.SSO_LOGIN_FAILED,
            clientIp = clientIp,
            userAgent = userAgent,
            userId = userId,
            details =
                mapOf(
                    DETAIL_PROVIDER to providerId,
                    DETAIL_REASON to rejection.errorCode,
                ),
        )
        return IdentityResolution.LoginRejected(rejection)
    }

    private companion object {
        /** research.md §10 detail vocabulary — non-secret markers only. */
        const val DETAIL_PROVIDER = "provider"

        const val DETAIL_RESOLUTION = "resolution"

        const val DETAIL_REASON = "reason"

        /** data-model.md §7 row 1; `auto_linked`/`jit` arrive with T029. */
        const val RESOLUTION_EXISTING_IDENTITY = "existing_identity"
    }
}
