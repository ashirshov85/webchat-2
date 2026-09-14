package webchat.backend.auth.api.dto

import java.util.UUID

/**
 * Contract №5 request body — `LoginRequest` (api-contract.md §4).
 *
 * Fields are nullable so an absent field stays on the uniform 401 leg —
 * the endpoint declares no 400 (api-contract.md §1, row 5) and an empty
 * identifier can only fail — instead of breaking deserialization: every
 * error response stays problem+json. The request carries NO password
 * policy: any mismatch is the uniform 401 (US2-3).
 */
data class LoginRequest(
    val identifier: String? = null,
    val password: String? = null,
)

/**
 * Contract №6 request body — `RefreshRequest` (api-contract.md §4):
 * nullable for the same problem+json reason as [LoginRequest].
 */
data class RefreshRequest(
    val refreshToken: String? = null,
)

/**
 * Contract №7 request body — `LogoutRequest` (api-contract.md §4):
 * sent together with the Bearer access token.
 */
data class LogoutRequest(
    val refreshToken: String? = null,
)

/**
 * The `user` fragment of the login success body (api-contract.md №5):
 * exactly `{id, username, email}` — status and timestamps are not part
 * of the login reply.
 */
data class UserRef(
    val id: UUID,
    val username: String,
    val email: String,
)

/**
 * Contract №6 success body — `TokenPair` (api-contract.md §4). The open
 * refresh value exists only in this response — persistence keeps the
 * SHA-256 hash (SC-005).
 */
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresInSec: Long,
)

/**
 * Contract №5 success body — `TokenPair` plus the required `user` fragment
 * (openapi.yaml: allOf TokenPair + `{user: {id, username, email}}`).
 */
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresInSec: Long,
    val user: UserRef,
)
