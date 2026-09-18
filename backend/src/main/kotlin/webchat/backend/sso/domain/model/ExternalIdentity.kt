package webchat.backend.sso.domain.model

import java.time.Instant
import java.util.UUID

/**
 * External identity: a binding of an external provider account to a WebChat user
 * (data-model.md §1; FR-006).
 *
 * The `(providerId, subject)` pair is globally unique (DB constraint
 * ux_external_identities_provider_subject), so an identity belongs to exactly one
 * user. `providerId` intentionally has no FK: bindings survive a provider being
 * disabled or removed from the configuration (US4-3).
 *
 * Referencing a provider code *known to the configuration* is enforced by the
 * domain services at creation time (they own the provider registry); this entity
 * validates the format only.
 */
data class ExternalIdentity(
    val id: UUID,
    val userId: UUID,
    val providerId: String,
    val subject: String,
    val providerEmail: String?,
    val providerEmailVerified: Boolean,
    val linkedAt: Instant,
) {
    init {
        require(PROVIDER_ID_PATTERN.matches(providerId)) {
            "providerId must match ${PROVIDER_ID_PATTERN.pattern}"
        }
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(subject.length <= SUBJECT_MAX_LENGTH) {
            "subject must be at most $SUBJECT_MAX_LENGTH characters"
        }
        require(providerEmail == null || providerEmail.length <= EMAIL_MAX_LENGTH) {
            "providerEmail must be at most $EMAIL_MAX_LENGTH characters"
        }
    }

    /**
     * Email from the provider refreshed on every successful login (US3: shown in
     * settings). Stored as-is (case preserved, the 002 convention); matching
     * against account emails is always done via `lower()` by the callers.
     */
    fun withProviderEmail(
        email: String?,
        verified: Boolean,
    ): ExternalIdentity =
        copy(
            providerEmail = email?.trim()?.takeIf { it.isNotEmpty() },
            providerEmailVerified = verified,
        )

    companion object {
        /** Provider code format (data-model.md §1, §4). */
        val PROVIDER_ID_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        /** OIDC `sub` is limited to 255 ASCII characters. */
        const val SUBJECT_MAX_LENGTH = 255

        const val EMAIL_MAX_LENGTH = 254

        /**
         * Binds an external account to a user. A provider without a unique `sub`
         * is considered misconfigured and never reaches this factory — the flow
         * is rejected earlier (edge spec).
         */
        @Suppress("LongParameterList") // domain factory mirroring the entity fields (data-model.md §1)
        fun link(
            id: UUID,
            userId: UUID,
            providerId: String,
            subject: String,
            providerEmail: String?,
            providerEmailVerified: Boolean,
            at: Instant,
        ): ExternalIdentity =
            ExternalIdentity(
                id = id,
                userId = userId,
                providerId = providerId,
                subject = subject,
                providerEmail = providerEmail?.trim()?.takeIf { it.isNotEmpty() },
                providerEmailVerified = providerEmailVerified,
                linkedAt = at,
            )
    }
}
