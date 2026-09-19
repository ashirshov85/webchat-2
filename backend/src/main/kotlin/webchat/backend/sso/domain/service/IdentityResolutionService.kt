package webchat.backend.sso.domain.service

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.model.UserStatus
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.sso.domain.model.ExternalIdentity
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
 * The rows 3–7 decision input of the first-login matrix once the email gate
 * has passed: the canonical (trimmed, provider-verified) email plus the
 * callback attribution shared by every journal entry.
 */
private data class FirstLogin(
    val providerId: String,
    val claims: OidcIdentityClaims,
    val email: String,
    val trustedForEmailLinking: Boolean,
    val clientIp: String,
    val userAgent: String?,
)

/**
 * Identity resolution — the core of the SSO login (data-model.md §7).
 *
 * T021 implemented the EXISTING-identity branch (rows 1–2): the verified ID-token
 * claims of `(providerId, subject)` resolve a bound identity, an `active` owner
 * is logged in with the provider email refreshed on every successful login,
 * and the journal gains `sso_login_success {resolution: existing_identity}`
 * (FR-011, research.md §10). A non-active owner is the protective row-2
 * rejection — `sso_login_failed` + `rejected`, no side effects (row 8).
 *
 * T029 adds the full FIRST-LOGIN matrix for an unknown `(providerId, subject)`
 * (rows 3–7, research.md §8): the email gate (`email_not_verified` unless the
 * provider returned a verified email — the allowlist is not consulted, JIT
 * included, FR-004/US2-7), trusted auto-linking into an existing active
 * account with the same `lower(email)` (row 4), `email_conflict` for a
 * non-trusted provider (row 5), `registration_incomplete` for the 002
 * registration states (row 6 — never an activation bypass), and JIT
 * provisioning of an active password-less account with a generated username
 * (row 7, data-model.md §2 — `email_confirmed_at = now()`, no letter).
 *
 * The email owner lookup race of two concurrent JIT logins is settled by the
 * `lower(email)` unique index (FR-012, research.md §13г): the savepoint-safe
 * loser of the insert re-resolves against the winning account instead of
 * surfacing an error.
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
    private val usernameGenerator: UsernameGenerator,
    private val authEventRecorder: AuthEventRecorder,
    private val clock: Clock,
) {
    /**
     * Applies the data-model.md §7 table to the verified claims of the
     * callback's ID token; [trustedForEmailLinking] is the provider snapshot
     * flag of the caller (the allowlist for auto-linking, research.md §8).
     * Success binds/refreshes identities and journals `sso_login_success`
     * atomically; every rejection journals `sso_login_failed` and leaves the
     * database untouched.
     *
     * ReturnCount suppressed: the exit legs ARE the contract — the row 1/2
     * existing-identity branches plus the rows 3–7 first-login ladder
     * (tasks.md T021/T029).
     */
    @Suppress("ReturnCount")
    @Transactional
    fun resolveLogin(
        providerId: String,
        claims: OidcIdentityClaims,
        trustedForEmailLinking: Boolean,
        clientIp: String,
        userAgent: String? = null,
    ): IdentityResolution {
        val identity =
            externalIdentityRepository.findByProviderAndSubject(providerId, claims.subject)
                ?: return resolveFirstLogin(providerId, claims, trustedForEmailLinking, clientIp, userAgent)

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
     * Rows 3–7 of the first-login matrix (research.md §8): an unknown
     * `(providerId, subject)` never enters by email alone — the provider must
     * have confirmed the email first; then the `lower(email)` owner decides
     * between auto-linking, conflict, incomplete registration and JIT.
     */
    private fun resolveFirstLogin(
        providerId: String,
        claims: OidcIdentityClaims,
        trustedForEmailLinking: Boolean,
        clientIp: String,
        userAgent: String?,
    ): IdentityResolution {
        // Row 3: no verified provider email — no binding and no JIT, whatever
        // the allowlist says (FR-004; the gate applies to JIT too, US2-7).
        val email = claims.email?.trim()?.takeIf { it.isNotEmpty() }
        if (email == null || !claims.emailVerified) {
            return rejectLogin(providerId, userId = null, SsoLoginRejection.EMAIL_NOT_VERIFIED, clientIp, userAgent)
        }

        // `lower(email)` canonicalization of the account matching (data-model.md §1)
        val firstLogin =
            FirstLogin(
                providerId = providerId,
                claims = claims,
                email = email,
                trustedForEmailLinking = trustedForEmailLinking,
                clientIp = clientIp,
                userAgent = userAgent,
            )
        val owner = userRepository.findByEmail(email)
        return if (owner == null) provisionJitAccount(firstLogin) else resolveEmailOwner(firstLogin, owner)
    }

    /**
     * Rows 4–6 for an existing `lower(email)` owner: the registration states
     * of 002 are never bypassed (row 6), a non-trusted provider never enters
     * an active account by email alone (row 5), a trusted one is auto-linked
     * into it without a password prompt (row 4, clarify 2026-09-16).
     */
    private fun resolveEmailOwner(
        firstLogin: FirstLogin,
        owner: User,
    ): IdentityResolution =
        when {
            owner.status != UserStatus.ACTIVE ->
                rejectLogin(
                    firstLogin.providerId,
                    owner.id,
                    SsoLoginRejection.REGISTRATION_INCOMPLETE,
                    firstLogin.clientIp,
                    firstLogin.userAgent,
                )

            !firstLogin.trustedForEmailLinking ->
                rejectLogin(
                    firstLogin.providerId,
                    owner.id,
                    SsoLoginRejection.EMAIL_CONFLICT,
                    firstLogin.clientIp,
                    firstLogin.userAgent,
                )

            else -> autoLinkOwner(firstLogin, owner)
        }

    /**
     * Row 4: bind the identity to the existing active account and log in —
     * the password stays a second login method, the account email/username
     * are untouched (data-model.md §7).
     */
    private fun autoLinkOwner(
        firstLogin: FirstLogin,
        owner: User,
    ): IdentityResolution.LoginGranted {
        val identityId = bindIdentity(firstLogin.providerId, firstLogin.claims, firstLogin.email, owner.id)
        authEventRecorder.record(
            eventType = AuthEventType.SSO_LOGIN_SUCCESS,
            clientIp = firstLogin.clientIp,
            userAgent = firstLogin.userAgent,
            userId = owner.id,
            details =
                mapOf(
                    DETAIL_PROVIDER to firstLogin.providerId,
                    DETAIL_RESOLUTION to RESOLUTION_AUTO_LINKED,
                ),
        )
        return IdentityResolution.LoginGranted(
            userId = owner.id,
            identityId = identityId,
            providerId = firstLogin.providerId,
        )
    }

    /**
     * Row 7: JIT provisioning (FR-003, data-model.md §2) — an `active`
     * password-less account straight away (`email_confirmed_at = now()`, the
     * provider confirmed it; no letter is ever queued, spec Assumptions) with
     * the collision-free username of [UsernameGenerator], plus the binding
     * and the `sso_account_created` journal entry.
     *
     * FR-012 / research.md §13г: a unique violation the username ladder
     * cannot fix means a concurrent flow won the same `lower(email)` — the
     * loser re-resolves against the winning account (auto-link/conflict/
     * incomplete exactly as a sequential second login would) instead of
     * surfacing an error.
     */
    private fun provisionJitAccount(firstLogin: FirstLogin): IdentityResolution {
        val now = clock.now()
        val userId = UUID.randomUUID()

        try {
            usernameGenerator.insertWithUniqueUsername(firstLogin.email) { username ->
                userRepository.insert(
                    User(
                        id = userId,
                        username = username,
                        email = firstLogin.email,
                        passwordHash = null,
                        status = UserStatus.ACTIVE,
                        emailConfirmedAt = now,
                        passwordSetAt = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        } catch (violation: DataIntegrityViolationException) {
            val winner = userRepository.findByEmail(firstLogin.email) ?: throw violation
            return resolveEmailOwner(firstLogin, winner)
        }

        val identityId = bindIdentity(firstLogin.providerId, firstLogin.claims, firstLogin.email, userId)
        authEventRecorder.record(
            eventType = AuthEventType.SSO_ACCOUNT_CREATED,
            clientIp = firstLogin.clientIp,
            userAgent = firstLogin.userAgent,
            userId = userId,
            details = mapOf(DETAIL_PROVIDER to firstLogin.providerId),
        )
        authEventRecorder.record(
            eventType = AuthEventType.SSO_LOGIN_SUCCESS,
            clientIp = firstLogin.clientIp,
            userAgent = firstLogin.userAgent,
            userId = userId,
            details =
                mapOf(
                    DETAIL_PROVIDER to firstLogin.providerId,
                    DETAIL_RESOLUTION to RESOLUTION_JIT,
                ),
        )
        return IdentityResolution.LoginGranted(
            userId = userId,
            identityId = identityId,
            providerId = firstLogin.providerId,
        )
    }

    /** The binding shared by auto-linking and JIT — `(providerId, subject)` uniqueness is the DB's word (FR-006). */
    private fun bindIdentity(
        providerId: String,
        claims: OidcIdentityClaims,
        email: String,
        userId: UUID,
    ): UUID {
        val identityId = UUID.randomUUID()
        externalIdentityRepository.insert(
            ExternalIdentity.link(
                id = identityId,
                userId = userId,
                providerId = providerId,
                subject = claims.subject,
                providerEmail = email,
                providerEmailVerified = claims.emailVerified,
                at = clock.now(),
            ),
        )
        return identityId
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

        /** data-model.md §7 resolution markers (rows 1, 4 and 7). */
        const val RESOLUTION_EXISTING_IDENTITY = "existing_identity"

        const val RESOLUTION_AUTO_LINKED = "auto_linked"

        const val RESOLUTION_JIT = "jit"
    }
}
