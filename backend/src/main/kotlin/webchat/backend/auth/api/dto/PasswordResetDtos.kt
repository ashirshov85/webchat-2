package webchat.backend.auth.api.dto

/**
 * Contract №8 request body — `PasswordResetRequest` (api-contract.md §4).
 *
 * Fields are nullable so an absent field surfaces as a field-level
 * `Problem.errors` entry (contract §3) instead of a deserialization
 * failure: every error response stays problem+json. Presence is checked
 * by [webchat.backend.auth.api.PasswordResetController]; format rules are
 * the service's (T041).
 */
data class PasswordResetRequest(
    val email: String? = null,
)

/**
 * Contract №9 request body — `PasswordResetConfirmRequest`
 * (api-contract.md §4): the open `password_reset` token from the letter
 * plus the new password and its confirmation. An absent token is the
 * uniform invalid-link 400 (FR-012), never a deserialization failure.
 */
data class PasswordResetConfirmRequest(
    val token: String? = null,
    val password: String? = null,
    val confirmPassword: String? = null,
)
