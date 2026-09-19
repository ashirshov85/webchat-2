package webchat.backend.sso.api

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.auth.api.dto.LoginResponse
import webchat.backend.auth.api.dto.UserRef
import webchat.backend.auth.domain.model.SessionAuthMethod
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.auth.domain.service.SessionService
import webchat.backend.auth.ratelimit.ClientIpResolver
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.sso.SsoMetrics
import webchat.backend.sso.api.dto.SsoAuthorizeRequest
import webchat.backend.sso.api.dto.SsoAuthorizeResponse
import webchat.backend.sso.api.dto.SsoProviderResponse
import webchat.backend.sso.api.dto.SsoProvidersResponse
import webchat.backend.sso.api.dto.SsoTokenRequest
import webchat.backend.sso.domain.port.SsoFlowContext
import webchat.backend.sso.domain.port.SsoFlowPurpose
import webchat.backend.sso.domain.port.SsoFlowStore
import webchat.backend.sso.domain.port.SsoHandshake
import webchat.backend.sso.domain.service.IdentityLinkOutcome
import webchat.backend.sso.domain.service.IdentityLinkService
import webchat.backend.sso.domain.service.IdentityResolution
import webchat.backend.sso.domain.service.IdentityResolutionService
import webchat.backend.sso.oidc.OidcClient
import webchat.backend.sso.oidc.OidcClientException
import webchat.backend.sso.oidc.OidcIdentityClaims
import webchat.backend.sso.oidc.SsoProviderRegistrationException
import webchat.backend.sso.oidc.SsoProviderRegistry
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** 404 (sso-api.md §2): unknown and disabled providers are one uniform answer — no disclosure. */
class SsoProviderNotFoundException : RuntimeException("Provider not found")

/** 400 (sso-api.md §2): `returnTo` is not a relative in-SPA path or exceeds 512 characters. */
class SsoReturnToInvalidException : RuntimeException("returnTo must be a relative path of at most 512 characters")

/** 400 (sso-api.md §4): the single uniform answer for unknown, used and expired handshake codes. */
class SsoHandshakeCodeInvalidException : RuntimeException("Token is invalid or expired; request a new login")

/**
 * T022 [US1] — the four public login-flow endpoints (contracts/sso-api.md
 * §1–§4): thin HTTP adapters over [SsoProviderRegistry], [OidcClient],
 * [SsoFlowStore] and [IdentityResolutionService]; every business rule and
 * journal entry stays in the services (SessionController precedent).
 *
 * `GET /api/v1/auth/sso/providers` — enabled providers in configuration
 * order; an empty list is valid (SSO not configured).
 *
 * `POST /api/v1/auth/sso/authorize` — starts a login flow: state/nonce/PKCE
 * are minted by [OidcClient], the flow context lands in Redis (data-model.md
 * §5, `returnTo` included for audit), and the browser is sent to the IdP
 * authorization URL; unknown/disabled providers answer the single 404, a
 * non-relative or over-long `returnTo` answers 400 `invalid_format` BEFORE
 * any flow context is written (US1-6).
 *
 * `GET /api/v1/auth/sso/callback` — the browser leg (never JSON): the flow
 * state is consumed atomically (`GETDEL` — a replay is rejected before any
 * side effect, SC-006), the code is exchanged and the identity verified
 * within the overall 5 s callback deadline (SC-005) — ID-token
 * verification for `oidc` providers, the Bearer userinfo call for
 * `oauth2-userinfo` (T057) — then either identity
 * resolution (login flows) or [IdentityLinkService] (link flows started by
 * `POST /auth/sso/link/authorize`, T035) runs, and the outcome leaves as a
 * 302: a single-use handshake code on login success, `?linked=<providerId>`
 * on link success, `?sso_error=<code>` otherwise. Login outcomes land on
 * `/sso/callback`, link outcomes on `/settings/security` (sso-api.md §3).
 * The session itself is NOT opened here — tokens exist only in the
 * `POST /token` response (research.md §2).
 *
 * `POST /api/v1/auth/sso/token` — the SPA exchanges the handshake code
 * (`GETDEL`) for a `LoginResponse` through [SessionService.startSession]
 * with `auth_method='sso'` + `identity_id` (T016); any code miss is the
 * uniform 400 `invalid_code` without a duplicate session (FR-012).
 */
@RestController
@RequestMapping("/api/v1/auth/sso")
// the endpoints' collaborators are the wiring itself; the shared callback legs
// of BOTH purposes (login T022 + link T035) live here by design (sso-api.md §3)
@Suppress("LongParameterList", "TooManyFunctions")
class SsoController(
    private val registry: SsoProviderRegistry,
    private val oidcClient: OidcClient,
    private val flowStore: SsoFlowStore,
    private val resolutionService: IdentityResolutionService,
    private val linkService: IdentityLinkService,
    private val sessionService: SessionService,
    private val userRepository: UserRepository,
    private val authEventRecorder: AuthEventRecorder,
    private val clientIpResolver: ClientIpResolver,
    private val clock: Clock,
    private val ssoMetrics: SsoMetrics,
    @param:Value("\${app.public-base-url}") private val publicBaseUrl: String,
) {
    private val log = LoggerFactory.getLogger(SsoController::class.java)

    /** Contract §1: enabled providers only, YAML declaration order (LinkedHashMap binding, T007). */
    @GetMapping("/providers")
    fun providers(): SsoProvidersResponse =
        SsoProvidersResponse(
            registry.enabledProviders().map { SsoProviderResponse(id = it.id, displayName = it.displayName) },
        )

    /** Contract §2: creates the single-use flow context and answers the IdP authorization URL. */
    @PostMapping("/authorize")
    fun authorize(
        @RequestBody request: SsoAuthorizeRequest,
    ): SsoAuthorizeResponse {
        val returnTo = request.returnTo?.let(::validatedReturnTo)
        val providerId = request.providerId ?: throw SsoProviderNotFoundException()
        registry.findEnabled(providerId) ?: throw SsoProviderNotFoundException()

        val authorization = oidcClient.startAuthorization(providerId)
        flowStore.saveFlow(
            authorization.state,
            SsoFlowContext(
                providerId = providerId,
                purpose = SsoFlowPurpose.LOGIN,
                returnTo = returnTo,
                nonce = authorization.nonce,
                codeVerifier = authorization.codeVerifier,
                createdAt = clock.now(),
            ),
        )
        ssoMetrics.countFlow(providerId, SsoMetrics.FlowOutcome.STARTED)
        return SsoAuthorizeResponse(authorizationUrl = authorization.authorizationUrl)
    }

    /**
     * Contract §3: consumes the flow state (`GETDEL`), exchanges the code and
     * verifies the ID token within the 5 s deadline, then applies the flow's
     * purpose — [loginOutcome] for a login, [linkOutcome] for a link flow —
     * and answers 302: handshake code on login success,
     * `?linked=<providerId>` on link success, `?sso_error=<code>` otherwise.
     * Errors never render JSON: the browser follows the redirect; all
     * outcomes are journaled (FR-011).
     */
    @Suppress("ReturnCount") // each return is one terminal outcome of the browser-leg state machine (sso-api.md §3)
    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "device_id", required = false) deviceId: String?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        val clientIp = clientIpResolver.resolve(servletRequest)
        val userAgent = servletRequest.getHeader(USER_AGENT_HEADER)

        if (state.isNullOrBlank()) {
            return flowRejected(ERROR_INVALID_STATE, providerId = null, clientIp, userAgent)
        }
        val flow =
            flowStore.consumeFlow(state)
                ?: return flowRejected(ERROR_INVALID_STATE, providerId = null, clientIp, userAgent)

        // every later outcome of this flow lands on ITS SPA screen: a login
        // on /sso/callback, a link flow on the settings screen (sso-api.md §3)
        val spaPath =
            if (flow.purpose == SsoFlowPurpose.LINK) SPA_SETTINGS_PATH else SPA_CALLBACK_PATH

        val provider = registry.find(flow.providerId)
        if (provider == null || !provider.enabled) {
            return flowRejected(ERROR_PROVIDER_DISABLED, flow.providerId, clientIp, userAgent, spaPath)
        }

        // the IdP redirected back with an OAuth error (e.g. access_denied,
        // US1-5) — mapped to the single provider_error code (sso-api.md §3)
        if (!error.isNullOrBlank()) {
            return flowRejected(ERROR_PROVIDER_ERROR, flow.providerId, clientIp, userAgent, spaPath)
        }
        if (code.isNullOrBlank()) {
            return flowRejected(ERROR_REJECTED, flow.providerId, clientIp, userAgent, spaPath)
        }

        // both provider calls share the overall 5 s callback deadline (SC-005);
        // any failure — transport, non-2xx, bad signature/iss/aud/exp/nonce,
        // or an unbuildable registration (discovery down, T041) — is the single
        // provider_error outcome (research.md §5)
        val claims =
            exchangeAndVerify(flow, code, deviceId)
                ?: return flowRejected(ERROR_PROVIDER_ERROR, flow.providerId, clientIp, userAgent, spaPath)

        return if (flow.purpose == SsoFlowPurpose.LINK) {
            linkOutcome(flow, claims, clientIp, userAgent, spaPath)
        } else {
            loginOutcome(flow, provider, claims, state, clientIp, userAgent, spaPath)
        }
    }

    /**
     * The provider backchannel legs shared by both purposes (sso-api.md §3):
     * the code→token exchange plus the protocol-dependent profile leg — ID
     * token verification for `oidc`, the Bearer userinfo call for
     * `oauth2-userinfo` (T057) — both inside the overall callback deadline;
     * `null` maps to the single `provider_error`. A registration that cannot
     * be built (discovery outage of an issuer-based provider, T041) maps
     * there too — the browser leg answers with the 302 redirect, never a 500
     * (US4-4 isolation).
     */
    private fun exchangeAndVerify(
        flow: SsoFlowContext,
        code: String,
        deviceId: String?,
    ): OidcIdentityClaims? {
        val deadline = Instant.now().plus(CALLBACK_DEADLINE)
        return try {
            oidcClient.completeAuthorizationCodeFlow(
                flow.providerId,
                flow.codeVerifier,
                code,
                flow.nonce,
                deadline,
                deviceId,
            )
        } catch (_: OidcClientException) {
            null
        } catch (_: SsoProviderRegistrationException) {
            null
        }
    }

    /**
     * The link branch of §3 (T035): userId comes from the flow context,
     * never from the IdP; the service owns the outcomes and their journal
     * entries (`identity_taken` is already journaled there — no second
     * entry here). Both success shapes redirect to the settings screen with
     * `?linked=<providerId>` (FR-012 no-op included).
     */
    private fun linkOutcome(
        flow: SsoFlowContext,
        claims: OidcIdentityClaims,
        clientIp: String,
        userAgent: String?,
        spaPath: String,
    ): ResponseEntity<Unit> =
        when (linkService.linkIdentity(flow.userId!!, flow.providerId, claims, clientIp, userAgent)) {
            is IdentityLinkOutcome.Linked,
            is IdentityLinkOutcome.AlreadyLinked,
            -> {
                ssoMetrics.countFlow(flow.providerId, SsoMetrics.FlowOutcome.COMPLETED)
                redirectToSpa(spaPath, LINKED_PARAMETER to flow.providerId)
            }
            is IdentityLinkOutcome.IdentityTaken -> {
                ssoMetrics.countFlow(flow.providerId, SsoMetrics.FlowOutcome.FLOW_REJECTED)
                redirectToSpa(spaPath, SSO_ERROR_PARAMETER to ERROR_IDENTITY_TAKEN)
            }
        }

    /**
     * The login branch of §3 (T022): identity resolution decides between the
     * single-use handshake code (its session opens only in `POST /token`)
     * and the public `sso_error` redirect — every rejection code of
     * data-model.md §7 rows 3–6, 8.
     */
    private fun loginOutcome(
        flow: SsoFlowContext,
        provider: SsoProviderRegistry.SsoProvider,
        claims: OidcIdentityClaims,
        state: String,
        clientIp: String,
        userAgent: String?,
        spaPath: String,
    ): ResponseEntity<Unit> {
        val resolution =
            resolutionService.resolveLogin(
                flow.providerId,
                claims,
                provider.trustedForEmailLinking,
                clientIp,
                userAgent,
            )
        return when (resolution) {
            is IdentityResolution.LoginGranted -> {
                val handshakeCode = randomHandshakeCode()
                flowStore.saveHandshake(
                    handshakeCode,
                    SsoHandshake(
                        userId = resolution.userId,
                        identityId = resolution.identityId,
                        providerId = resolution.providerId,
                    ),
                )
                ssoMetrics.countFlow(flow.providerId, SsoMetrics.FlowOutcome.COMPLETED)
                redirectToSpa(spaPath, HANDSHAKE_CODE_PARAMETER to handshakeCode, STATE_PARAMETER to state)
            }
            is IdentityResolution.LoginRejected -> {
                ssoMetrics.countFlow(flow.providerId, SsoMetrics.FlowOutcome.LOGIN_FAILED)
                redirectToSpa(spaPath, SSO_ERROR_PARAMETER to resolution.rejection.errorCode)
            }
        }
    }

    /** Contract §4: single-use handshake exchange — the only place an SSO session is opened. */
    @PostMapping("/token")
    fun token(
        @RequestBody request: SsoTokenRequest,
    ): ResponseEntity<LoginResponse> {
        val (handshake, user) =
            consumeHandshakeWithUser(request.code) ?: throw SsoHandshakeCodeInvalidException()

        val pair = sessionService.startSession(handshake.userId, SessionAuthMethod.SSO, handshake.identityId)
        return ResponseEntity.ok(
            LoginResponse(
                accessToken = pair.accessToken,
                refreshToken = pair.refreshToken,
                tokenType = TOKEN_TYPE_BEARER,
                expiresInSec = pair.expiresInSec,
                user =
                    UserRef(
                        id = user.id,
                        username = user.username,
                        email = user.email,
                    ),
            ),
        )
    }

    /**
     * The uniform 400 leg of contract §4: an absent or malformed code, an
     * unknown, already exchanged or expired handshake, and a vanished owner
     * account are indistinguishable — one `null`.
     */
    private fun consumeHandshakeWithUser(code: String?): Pair<SsoHandshake, User>? =
        code
            ?.takeIf { it.isNotBlank() && handshakeCodePattern.matches(it) }
            ?.let(flowStore::consumeHandshake)
            ?.let { handshake -> userRepository.findById(handshake.userId)?.let { handshake to it } }

    /**
     * US1-6: `returnTo` must be an in-SPA relative path — leading `/` but not
     * a protocol-relative `//`, no backslash — of at most 512 characters
     * (contracts/sso-api.md §2); anything else is 400 `invalid_format`.
     */
    private fun validatedReturnTo(returnTo: String): String {
        val relativePath =
            returnTo.length <= RETURN_TO_MAX_LENGTH &&
                returnTo.startsWith(PATH_PREFIX) &&
                !returnTo.startsWith(PROTOCOL_RELATIVE_PREFIX) &&
                !returnTo.contains(BACKSLASH)
        if (!relativePath) throw SsoReturnToInvalidException()
        return returnTo
    }

    /**
     * Row 8 of the resolution table for flow-level failures (data-model.md
     * §7): journals `sso_flow_error` with non-secret markers and answers the
     * 302 `?sso_error=<code>` redirect on the flow's SPA screen — no
     * accounts, bindings or sessions are created (research.md §10, FR-011).
     */
    private fun flowRejected(
        errorCode: String,
        providerId: String?,
        clientIp: String,
        userAgent: String?,
        spaPath: String = SPA_CALLBACK_PATH,
    ): ResponseEntity<Unit> {
        // research.md §14: the provider failure is also fixed in the
        // structured logs — one WARN record carrying the request traceId from
        // MDC, tying the log stream to the `sso_flow_error` journal row below
        // (T044). Non-secret markers only, mirroring the `details` payload.
        log.warn("sso_flow_error: provider={} reason={}", providerId ?: UNKNOWN_PROVIDER_MARKER, errorCode)
        ssoMetrics.countFlow(
            providerId,
            if (errorCode == ERROR_PROVIDER_ERROR) {
                SsoMetrics.FlowOutcome.PROVIDER_ERROR
            } else {
                SsoMetrics.FlowOutcome.FLOW_REJECTED
            },
        )
        authEventRecorder.record(
            eventType = AuthEventType.SSO_FLOW_ERROR,
            clientIp = clientIp,
            userAgent = userAgent,
            details =
                buildMap {
                    providerId?.let { put(DETAIL_PROVIDER, it) }
                    put(DETAIL_REASON, errorCode)
                },
        )
        return redirectToSpa(spaPath, SSO_ERROR_PARAMETER to errorCode)
    }

    private fun redirectToSpa(
        spaPath: String,
        vararg parameters: Pair<String, String>,
    ): ResponseEntity<Unit> =
        ResponseEntity
            .status(HttpStatus.FOUND)
            .location(
                UriComponentsBuilder
                    .fromUriString(publicBaseUrl.removeSuffix(SLASH) + spaPath)
                    .apply { parameters.forEach { (name, value) -> queryParam(name, value) } }
                    .encode()
                    .build()
                    .toUri(),
            ).build()

    /** 256-bit base64url value — the 43-char handshake shape of contracts/sso-api.md §3. */
    private fun randomHandshakeCode(): String {
        val bytes = ByteArray(HANDSHAKE_CODE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        /** research.md §1/§5, SC-005: the overall callback budget for all provider calls. */
        val CALLBACK_DEADLINE: Duration = Duration.ofSeconds(5)

        val handshakeCodePattern = Regex(HANDSHAKE_CODE_PATTERN)

        val secureRandom = SecureRandom()

        const val HANDSHAKE_CODE_PATTERN = "^[A-Za-z0-9_-]{43}$"

        const val HANDSHAKE_CODE_BYTES = 32

        const val RETURN_TO_MAX_LENGTH = 512

        const val TOKEN_TYPE_BEARER = "Bearer"

        const val USER_AGENT_HEADER = "User-Agent"

        const val SPA_CALLBACK_PATH = "/sso/callback"

        /** The link-flow outcome screen of contracts/sso-api.md §3/§5 (T037 renders it). */
        const val SPA_SETTINGS_PATH = "/settings/security"

        const val SSO_ERROR_PARAMETER = "sso_error"

        /** The `?linked=<providerId>` success marker of the link branch (sso-api.md §3). */
        const val LINKED_PARAMETER = "linked"

        const val HANDSHAKE_CODE_PARAMETER = "code"

        const val STATE_PARAMETER = "state"

        /** sso_error vocabulary of contracts/sso-api.md §3. */
        const val ERROR_INVALID_STATE = "invalid_state"

        /** Log marker of flows rejected before their provider is known (journal `details` omits the provider too). */
        const val UNKNOWN_PROVIDER_MARKER = "unknown"

        const val ERROR_PROVIDER_DISABLED = "provider_disabled"

        const val ERROR_PROVIDER_ERROR = "provider_error"

        const val ERROR_REJECTED = "rejected"

        /** US3-2 / FR-006: the identity belongs to another account — no owner details. */
        const val ERROR_IDENTITY_TAKEN = "identity_taken"

        /** research.md §10 detail vocabulary — non-secret markers only. */
        const val DETAIL_PROVIDER = "provider"

        const val DETAIL_REASON = "reason"

        const val PATH_PREFIX = "/"

        const val PROTOCOL_RELATIVE_PREFIX = "//"

        const val BACKSLASH = "\\"

        const val SLASH = "/"
    }
}
