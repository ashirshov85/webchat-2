package webchat.backend.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.savedrequest.NullRequestCache
import webchat.backend.auth.ratelimit.RateLimitFilter
import webchat.backend.auth.security.AuthJwtDecoder
import webchat.backend.auth.security.JwtService

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        val argon2 = Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS)
        return DelegatingPasswordEncoder(ENCODING_ID, mapOf(ENCODING_ID to argon2))
    }

    /**
     * The resource-server decoder for access tokens (T032, research.md §4–§5):
     * [AuthJwtDecoder] — `kid`-based key selection, signature/expiry/clock-skew
     * validation via JwtService plus the Redis sid denylist; every failure mode
     * becomes the uniform 401 of the security chain.
     */
    @Bean
    fun jwtDecoder(
        jwtService: JwtService,
        redisTemplate: StringRedisTemplate,
    ): JwtDecoder = AuthJwtDecoder(jwtService, redisTemplate)

    /**
     * The US5 rate-limit guard bean is wired by [RateLimitFilterConfig].
     *
     * The filter lives ONLY in the security chain (added before
     * [UsernamePasswordAuthenticationFilter]) — this registration disables
     * Boot's automatic servlet-container registration so it cannot run twice.
     */
    @Bean
    fun rateLimitFilterRegistration(rateLimitFilter: RateLimitFilter): FilterRegistrationBean<RateLimitFilter> =
        FilterRegistrationBean(rateLimitFilter).apply { isEnabled = false }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authJwtDecoder: JwtDecoder,
        rateLimitFilter: RateLimitFilter,
    ): SecurityFilterChain {
        // T037: the SAME uniform 401 entry point guards both rejection points —
        // the authorize rules (ExceptionTranslationFilter) AND the resource-server
        // bearer pipeline (resolver failures like a malformed `Bearer` header and
        // decoder failures via BadJwtException), so no path can leak a bodiless
        // default 401 or a 500 error dispatch (api-contract.md §3, US3-1/2)
        val unifiedEntryPoint = UnifiedAuthenticationEntryPoint()
        http {
            // T047: bucket limits on the public auth routes run first in the chain
            addFilterBefore<UsernamePasswordAuthenticationFilter>(rateLimitFilter)
            csrf { disable() }
            requestCache { requestCache = NullRequestCache() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            oauth2ResourceServer {
                authenticationEntryPoint = unifiedEntryPoint
                jwt { jwtDecoder = authJwtDecoder }
            }
            authorizeHttpRequests {
                authorize("/api/v1/auth/register", permitAll)
                authorize("/api/v1/auth/register/resend", permitAll)
                authorize("/api/v1/auth/register/confirm", permitAll)
                authorize("/api/v1/auth/register/password", permitAll)
                authorize("/api/v1/auth/login", permitAll)
                authorize("/api/v1/auth/refresh", permitAll)
                authorize("/api/v1/auth/password-reset", permitAll)
                authorize("/api/v1/auth/password-reset/confirm", permitAll)
                // T017: the four public SSO routes of contract 0.3.0 (exact paths,
                // no wildcards) — like the №8–9 precedent above, their controllers
                // land with T022, so until then they surface as MVC 404s, never
                // the boundary 401
                authorize("/api/v1/auth/sso/providers", permitAll)
                authorize("/api/v1/auth/sso/authorize", permitAll)
                authorize("/api/v1/auth/sso/callback", permitAll)
                authorize("/api/v1/auth/sso/token", permitAll)
                // T047 (009, US4, FR-009): the ONLY public `conferences`
                // surface — the guest pair №54/№55 (exact paths, no
                // wildcards, the T017 discipline). They stand permitAll
                // WITHOUT any bearer expectation; their flood guard is the
                // per-IP bucket pair `rl:ip:conferences-invite-info` 30/1m
                // and `rl:ip:conferences-join-by-invite` 10/1m in the
                // RateLimitFilter above. The webhook route stays CLOSED
                // here until its controller opens it in T053 — a public
                // path must never precede its handler
                authorize("/api/v1/conferences/invite-info", permitAll)
                authorize("/api/v1/conferences/join-by-invite", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/prometheus", permitAll)
                authorize("/actuator/metrics/**", permitAll)
                // T037: Boot error dispatch — an MVC-layer 404 (e.g. a permitAll
                // path whose controller lands with a later story) is FORWARDED to
                // /error; without this rule the chain would hijack that forward and
                // overwrite the 404 with the boundary 401
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = unifiedEntryPoint
            }
        }
        return http.build()
    }

    private class UnifiedAuthenticationEntryPoint : AuthenticationEntryPoint {
        override fun commence(
            request: HttpServletRequest,
            response: HttpServletResponse,
            authException: AuthenticationException,
        ) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.characterEncoding = Charsets.UTF_8.name()
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.writer.write(UNAUTHORIZED_BODY)
        }

        private companion object {
            const val UNAUTHORIZED_BODY =
                """{"title":"Unauthorized","status":401,"detail":"Not authenticated"}"""
        }
    }

    private companion object {
        const val ENCODING_ID = "argon2"
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val PARALLELISM = 1
        const val MEMORY_KIB = 19456
        const val ITERATIONS = 2
    }
}
