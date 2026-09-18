package webchat.backend.sso.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.ratelimit.ClientIpResolver
import webchat.backend.sso.SsoMetrics
import webchat.backend.sso.api.dto.SsoAuthorizeResponse
import webchat.backend.sso.api.dto.SsoIdentitiesResponse
import webchat.backend.sso.api.dto.SsoIdentityResponse
import webchat.backend.sso.api.dto.SsoLinkAuthorizeRequest
import webchat.backend.sso.domain.port.ExternalIdentityRepository
import webchat.backend.sso.domain.port.SsoFlowContext
import webchat.backend.sso.domain.port.SsoFlowPurpose
import webchat.backend.sso.domain.port.SsoFlowStore
import webchat.backend.sso.domain.service.IdentityLinkService
import webchat.backend.sso.oidc.OidcClient
import webchat.backend.sso.oidc.SsoProviderRegistry
import java.util.UUID

/**
 * T035 [US3] — the authenticated identity-management surface of contract
 * 0.3.0 §5–§7 (contracts/sso-api.md): thin HTTP adapters over
 * [SsoProviderRegistry], [OidcClient], [SsoFlowStore],
 * [ExternalIdentityRepository] and [IdentityLinkService]; every business
 * rule and journal entry stays in the services (SsoController precedent).
 *
 * `POST /api/v1/auth/sso/link/authorize` — starts a purpose=link flow for
 * the Bearer-authenticated user: the flow context carries the `userId`
 * (data-model.md §5), never a session, and the browser is sent to the IdP;
 * the outcome lands in the link branch of the shared callback (§3) as
 * `302 /settings/security?linked=<providerId>` or `?sso_error=<code>`.
 * Unknown/disabled providers get the same single 404 as §2 — the provider
 * list is public anyway, no disclosure.
 *
 * `GET /api/v1/users/me/identities` — the user's bindings ordered by
 * `linkedAt`; `providerDisplayName` is resolved from the live configuration
 * and is `null` for a provider removed from it (the binding survives,
 * US4-3).
 *
 * `DELETE /api/v1/users/me/identities/{identityId}` — 204 on success via
 * [IdentityLinkService.unlinkIdentity]; the uniform 404 for a missing or
 * foreign identity; 409 `errors: {identity: ["last_login_method"]}` when
 * the unlink would remove the last login method of a password-less account
 * (US3-4 — rendered by [SsoExceptionHandler]). Sessions are NOT revoked
 * here: that rule belongs to the service (clarify 2026-09-16, US3-6).
 *
 * All three routes stay behind the default `authenticated()` rule of the
 * security chain — the uniform 401 of the boundary is produced entirely by
 * the chain, never here (UsersController precedent).
 */
@RestController
@Suppress("LongParameterList") // the endpoints' collaborators are the wiring itself (SsoController precedent)
class SsoIdentitiesController(
    private val registry: SsoProviderRegistry,
    private val oidcClient: OidcClient,
    private val flowStore: SsoFlowStore,
    private val identityLinkService: IdentityLinkService,
    private val externalIdentityRepository: ExternalIdentityRepository,
    private val clientIpResolver: ClientIpResolver,
    private val clock: Clock,
    private val ssoMetrics: SsoMetrics,
) {
    /** Contract §5: a purpose=link flow context bound to the current user, then the IdP URL. */
    @PostMapping("/api/v1/auth/sso/link/authorize")
    fun linkAuthorize(
        @AuthenticationPrincipal accessToken: Jwt,
        @RequestBody request: SsoLinkAuthorizeRequest,
    ): SsoAuthorizeResponse {
        val providerId = request.providerId ?: throw SsoProviderNotFoundException()
        registry.findEnabled(providerId) ?: throw SsoProviderNotFoundException()

        val authorization = oidcClient.startAuthorization(providerId)
        flowStore.saveFlow(
            authorization.state,
            SsoFlowContext(
                providerId = providerId,
                purpose = SsoFlowPurpose.LINK,
                userId = UUID.fromString(accessToken.subject),
                nonce = authorization.nonce,
                codeVerifier = authorization.codeVerifier,
                createdAt = clock.now(),
            ),
        )
        ssoMetrics.countFlow(providerId, SsoMetrics.FlowOutcome.STARTED)
        return SsoAuthorizeResponse(authorizationUrl = authorization.authorizationUrl)
    }

    /** Contract §6: the user's bindings by `linkedAt`; display name from the live config. */
    @GetMapping("/api/v1/users/me/identities")
    fun identities(
        @AuthenticationPrincipal accessToken: Jwt,
    ): SsoIdentitiesResponse =
        SsoIdentitiesResponse(
            externalIdentityRepository.findByUserId(UUID.fromString(accessToken.subject)).map { identity ->
                SsoIdentityResponse(
                    id = identity.id,
                    providerId = identity.providerId,
                    providerDisplayName = registry.find(identity.providerId)?.displayName,
                    email = identity.providerEmail,
                    linkedAt = identity.linkedAt,
                )
            },
        )

    /** Contract §7: 204/404/409 — the guards and journaling live in [IdentityLinkService]. */
    @DeleteMapping("/api/v1/users/me/identities/{identityId}")
    fun unlink(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable identityId: UUID,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        identityLinkService.unlinkIdentity(
            userId = UUID.fromString(accessToken.subject),
            identityId = identityId,
            clientIp = clientIpResolver.resolve(servletRequest),
            userAgent = servletRequest.getHeader(USER_AGENT_HEADER),
        )
        return ResponseEntity.noContent().build()
    }

    private companion object {
        const val USER_AGENT_HEADER = "User-Agent"
    }
}
