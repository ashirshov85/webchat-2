package webchat.backend.auth.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import webchat.backend.auth.api.dto.LoginRequest
import webchat.backend.auth.api.dto.LoginResponse
import webchat.backend.auth.api.dto.LogoutRequest
import webchat.backend.auth.api.dto.RefreshRequest
import webchat.backend.auth.api.dto.TokenPairResponse
import webchat.backend.auth.api.dto.UserRef
import webchat.backend.auth.domain.service.InvalidRefreshTokenException
import webchat.backend.auth.domain.service.IssuedTokenPair
import webchat.backend.auth.domain.service.LoginService
import webchat.backend.auth.domain.service.SessionService

/**
 * US2 session endpoints (api-contract.md №5–7), thin HTTP adapters over
 * [LoginService] and [SessionService]: client metadata is resolved here,
 * every business rule and journal entry stays in the services. All errors
 * leave as typed exceptions rendered problem+json by
 * [ApiExceptionHandler] — never inline bodies.
 *
 * `POST /api/v1/auth/login` — 200 `TokenPair` + `user{id, username, email}`;
 * the single uniform 401 «Invalid credentials» (US2-3); 403 for incomplete
 * registrations (US1-5).
 * `POST /api/v1/auth/refresh` — 200 a new pair (rotation, FR-006); the
 * uniform 401 without any distinction of expiry/revocation/reuse (US2-4).
 * `POST /api/v1/auth/logout` — Bearer-gated by the security chain before
 * this adapter (US2-5, FR-011); `{refreshToken}` → 204.
 */
@RestController
@RequestMapping("/api/v1/auth")
class SessionController(
    private val loginService: LoginService,
    private val sessionService: SessionService,
) {
    /** Contract №5 (FR-005): password login by username OR email with a fresh session pair. */
    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<LoginResponse> {
        val result =
            loginService.login(
                identifier = request.identifier.orEmpty(),
                password = request.password.orEmpty(),
                clientIp = clientIp(servletRequest),
                userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
            )
        return ResponseEntity.ok(
            LoginResponse(
                accessToken = result.tokenPair.accessToken,
                refreshToken = result.tokenPair.refreshToken,
                tokenType = TOKEN_TYPE_BEARER,
                expiresInSec = result.tokenPair.expiresInSec,
                user =
                    UserRef(
                        id = result.user.id,
                        username = result.user.username,
                        email = result.user.email,
                    ),
            ),
        )
    }

    /** Contract №6 (FR-006): single-use rotation; a replay is the uniform 401 and kills the chain. */
    @PostMapping("/refresh")
    fun refresh(
        @RequestBody request: RefreshRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<TokenPairResponse> {
        val pair =
            sessionService.refresh(
                refreshToken = request.refreshToken ?: throw InvalidRefreshTokenException(),
                clientIp = clientIp(servletRequest),
                userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
            )
        return ResponseEntity.ok(pair.toResponse())
    }

    /** Contract №7 (FR-011): revokes the session of the presented refresh generation. */
    @PostMapping("/logout")
    fun logout(
        @RequestBody request: LogoutRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        sessionService.logout(
            refreshToken = request.refreshToken ?: throw InvalidRefreshTokenException(),
            clientIp = clientIp(servletRequest),
            userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
        )
        return ResponseEntity.noContent().build()
    }

    private fun IssuedTokenPair.toResponse(): TokenPairResponse =
        TokenPairResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = TOKEN_TYPE_BEARER,
            expiresInSec = expiresInSec,
        )

    /**
     * Interim request-source extraction pending the shared US5 resolver
     * (T046): XFF leftmost with a remoteAddr fallback — compatible with
     * `server.forward-headers-strategy=framework`, where Spring rewrites
     * remoteAddr from XFF and strips the header. Only the SHA-256 peppered
     * hash of the value is ever persisted (FR-013).
     */
    private fun clientIp(request: HttpServletRequest): String {
        val forwardedFor = request.getHeader(X_FORWARDED_FOR_HEADER)
        if (forwardedFor != null) {
            val leftmostHop = forwardedFor.substringBefore(COMMA).trim()
            if (leftmostHop.isNotEmpty()) return leftmostHop
        }
        return request.remoteAddr
    }

    private companion object {
        const val TOKEN_TYPE_BEARER = "Bearer"
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
        const val USER_AGENT_HEADER = "User-Agent"
        const val COMMA = ","
    }
}
