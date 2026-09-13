package webchat.backend.auth.api.dto

/**
 * Contract №1 request body — `RegisterRequest` (api-contract.md §4).
 *
 * Fields are nullable so an absent field surfaces as a field-level
 * `Problem.errors` entry (contract §3) instead of a deserialization
 * failure: every error response stays problem+json. Presence is checked
 * by [webchat.backend.auth.api.RegisterController]; format rules are the
 * service's (T022b).
 */
data class RegisterRequest(
    val username: String? = null,
    val email: String? = null,
)

/**
 * Contract №2 request body — `ResendRequest` (api-contract.md §4):
 * nullable for the same problem+json reason as [RegisterRequest].
 */
data class ResendRequest(
    val email: String? = null,
)

/**
 * Contract №3 request body — `ConfirmRegistrationRequest`
 * (api-contract.md §4): the open email_verification token from the letter.
 */
data class ConfirmRegistrationRequest(
    val token: String? = null,
)

/**
 * Contract №4 request body — `SetPasswordRequest` (api-contract.md §4).
 */
data class SetPasswordRequest(
    val setupToken: String? = null,
    val password: String? = null,
    val confirmPassword: String? = null,
)

/**
 * Contract №3 success body: the open password_setup token handed to the
 * SPA for the /set-password step (api-contract.md №1, row 3). The open
 * value exists only here and in the response — persistence keeps the
 * SHA-256 hash (SC-005).
 */
data class ConfirmRegistrationResponse(
    val setupToken: String,
    val setupTokenType: String,
    val expiresInSec: Long,
)
