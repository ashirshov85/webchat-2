package webchat.backend.users.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.users.api.dto.PublicUserResponse
import java.util.UUID

/**
 * Contract №10 (T038): `GET /api/v1/users/me` — the minimal protected
 * resource fixing the authentication boundary of the chat API (US3,
 * SC-002); every later chat endpoint stays behind the same Bearer gate.
 *
 * The security chain has ALREADY authenticated the request before this
 * adapter runs: [webchat.backend.auth.security.AuthJwtDecoder] verified the
 * ES256 access token (signature, expiry, `typ`, denylisted `sid`) and the
 * authorize rules require `authenticated()` — so the uniform 401 of
 * api-contract.md §3 is produced entirely by the chain, never here. This
 * adapter only resolves the token owner (`sub` claim, minted by JwtService
 * as the user id) into the `PublicUser` contract body.
 */
@RestController
@RequestMapping("/api/v1/users")
class UsersController(
    private val userRepository: UserRepository,
) {
    /** Contract №10: the token owner as `PublicUser {id, username, email, status, createdAt}`. */
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal accessToken: Jwt,
    ): PublicUserResponse {
        val owner =
            userRepository.findById(UUID.fromString(accessToken.subject))
                ?: throw AuthenticatedUserNotFoundException()
        return PublicUserResponse(
            id = owner.id,
            username = owner.username,
            email = owner.email,
            status = owner.status.name.lowercase(),
            createdAt = owner.createdAt,
        )
    }

    /**
     * Defensive-only 401 (contract №10 declares just 200/401): a
     * cryptographically valid token whose owner no longer exists holds no
     * authenticated identity, so the reply is the SAME uniform boundary
     * problem — `Not authenticated` problem+json — never a 500.
     */
    @ExceptionHandler(AuthenticatedUserNotFoundException::class)
    fun onAuthenticatedUserNotFound(): ProblemDetail = problem(HttpStatus.UNAUTHORIZED, NOT_AUTHENTICATED_DETAIL)

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private class AuthenticatedUserNotFoundException : RuntimeException(NOT_AUTHENTICATED_DETAIL)

    private companion object {
        const val NOT_AUTHENTICATED_DETAIL = "Not authenticated"
    }
}
