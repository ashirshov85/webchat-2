package webchat.backend.sso.domain.port

import webchat.backend.sso.domain.model.ExternalIdentity
import java.util.UUID

/**
 * Persistence port for [ExternalIdentity] (data-model.md §1; FR-006, FR-012).
 *
 * `(providerId, subject)` is globally unique (DB constraint
 * ux_external_identities_provider_subject): a duplicate [insert] surfaces as a
 * unique-violation error the caller maps to `identity_taken` (data-model.md §7
 * Link); the DB is the final arbiter under concurrency.
 *
 * Bindings survive a provider being disabled or removed from the configuration
 * (US4-3): reads and deletes work for provider codes no longer in the config.
 */
interface ExternalIdentityRepository {
    /** Binds an external account to a user; unique violation → identity is already taken. */
    fun insert(identity: ExternalIdentity)

    /** Login resolution lookup (data-model.md §7 rows 1–2). */
    fun findByProviderAndSubject(
        providerId: String,
        subject: String,
    ): ExternalIdentity?

    /** All bindings of the user, ordered by `linked_at` (US3: settings list, last-login-method guard). */
    fun findByUserId(userId: UUID): List<ExternalIdentity>

    /**
     * Removes a binding; returns false when it does not exist (→ 404).
     * Deleting the last login method of a passwordless account is rejected by the
     * `keep_at_least_one_login_method` DB trigger (data-model.md §6) — the caller
     * maps that error to 409 `last_login_method` (US3-4). Sessions are not
     * revoked (`sessions.identity_id` is ON DELETE SET NULL).
     */
    fun delete(id: UUID): Boolean

    /** Refreshes the provider email on every successful login (data-model.md §1, §7 row 1). */
    fun updateProviderEmail(
        id: UUID,
        providerEmail: String?,
        providerEmailVerified: Boolean,
    )
}
