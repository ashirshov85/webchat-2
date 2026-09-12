package webchat.backend.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.savedrequest.NullRequestCache

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        val argon2 = Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS)
        return DelegatingPasswordEncoder(ENCODING_ID, mapOf(ENCODING_ID to argon2))
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            requestCache { requestCache = NullRequestCache() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/api/v1/auth/register", permitAll)
                authorize("/api/v1/auth/register/resend", permitAll)
                authorize("/api/v1/auth/register/confirm", permitAll)
                authorize("/api/v1/auth/register/password", permitAll)
                authorize("/api/v1/auth/login", permitAll)
                authorize("/api/v1/auth/refresh", permitAll)
                authorize("/api/v1/auth/password-reset", permitAll)
                authorize("/api/v1/auth/password-reset/confirm", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/prometheus", permitAll)
                authorize("/actuator/metrics/**", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = UnifiedAuthenticationEntryPoint()
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
