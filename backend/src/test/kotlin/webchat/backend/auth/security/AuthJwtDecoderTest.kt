package webchat.backend.auth.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant
import java.util.UUID
import org.mockito.Mockito.`when` as whenever

/**
 * Unit-level mirror of the T032 decoder gate: kid/signature/expiry/skew
 * validation delegated to JwtService (covered by JwtServiceTest), the Redis
 * sid denylist and the uniform no-details failure (US3-1/US3-2). The
 * end-to-end 401 boundary (problem+json body, public-path audit) lives in
 * SessionIT/AuthenticationBoundaryIT; their full GREEN lands with T033/T038
 * ( JwtServiceTest precedent from T028).
 */
class AuthJwtDecoderTest {
    private val jwtService: JwtService = Mockito.mock(JwtService::class.java)

    private val redisTemplate: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)

    private lateinit var decoder: AuthJwtDecoder

    @BeforeEach
    fun setUp() {
        decoder = AuthJwtDecoder(jwtService, redisTemplate)
    }

    @Test
    fun `decode returns the Spring Jwt view of the verified claims`() {
        stubVerification()
        stubDenylisted(false)

        val jwt = decoder.decode(ACCESS_TOKEN)

        assertThat(jwt.tokenValue).isEqualTo(ACCESS_TOKEN)
        assertThat(jwt.headers.get(ALGORITHM_HEADER)).isEqualTo(ES256)
        assertThat(jwt.subject).isEqualTo(USER_ID.toString())
        assertThat(jwt.claims[SESSION_ID_CLAIM]).isEqualTo(SESSION_ID.toString())
        assertThat(jwt.claims[JTI_CLAIM]).isEqualTo(JTI)
        assertThat(jwt.claims[TOKEN_TYPE_CLAIM]).isEqualTo(TOKEN_TYPE_ACCESS)
        assertThat(jwt.expiresAt).isEqualTo(EXPIRES_AT)
        verify(redisTemplate).hasKey(DENYLIST_KEY_PREFIX + SESSION_ID)
    }

    @Test
    fun `verification failures reject with the same uniform JwtException without details`() {
        stubDenylisted(false)
        whenever(jwtService.verifyAccessToken(anyString()))
            .thenThrow(
                InvalidAccessTokenException("Unknown access token kid"),
                InvalidAccessTokenException("Access token expired"),
                InvalidAccessTokenException("Access token signature verification failed"),
            )

        val failureMessages =
            listOf("unknown-kid-leg", "expired-leg", "signature-leg").map { token ->
                val exception = runCatching { decoder.decode(token) }.exceptionOrNull()
                val jwtException = exception as? JwtException ?: error("every rejection must be a JwtException")
                assertThat(jwtException.message)
                    .doesNotContain("kid")
                    .doesNotContain("expired")
                    .doesNotContain("signature")
                jwtException.message
            }
        assertThat(failureMessages.toSet()).hasSize(1)
        assertThat(failureMessages.first()).isEqualTo(UNIFORM_MESSAGE)
    }

    @Test
    fun `blank and null tokens reject with the uniform JwtException without touching JwtService`() {
        listOf(null, "", BLANK_TOKEN).forEach { token ->
            assertThatThrownBy { decoder.decode(token) }
                .isInstanceOf(JwtException::class.java)
                .hasMessage(UNIFORM_MESSAGE)
        }
        verifyNoInteractions(jwtService)
    }

    @Test
    fun `denylisted sid rejects with the same uniform JwtException`() {
        stubVerification()
        stubDenylisted(true)

        assertThatThrownBy { decoder.decode(ACCESS_TOKEN) }
            .isInstanceOf(JwtException::class.java)
            .hasMessage(UNIFORM_MESSAGE)
    }

    @Test
    fun `missing denylist marker decodes as null-safe false`() {
        stubVerification()
        whenever(redisTemplate.hasKey(anyString())).thenReturn(null)

        assertThat(decoder.decode(ACCESS_TOKEN).subject).isEqualTo(USER_ID.toString())
    }

    @Test
    fun `redis outage fails open instead of rejecting all authenticated traffic`() {
        stubVerification()
        whenever(redisTemplate.hasKey(anyString())).thenThrow(QueryTimeoutException("redis is down"))

        val jwt = decoder.decode(ACCESS_TOKEN)

        assertThat(jwt.subject).isEqualTo(USER_ID.toString())
    }

    private fun stubVerification() {
        whenever(jwtService.verifyAccessToken(anyString())).thenReturn(VERIFIED)
    }

    private fun stubDenylisted(denylisted: Boolean) {
        whenever(redisTemplate.hasKey(anyString())).thenReturn(denylisted)
    }

    private companion object {
        val USER_ID: UUID = UUID.randomUUID()
        val SESSION_ID: UUID = UUID.randomUUID()
        val EXPIRES_AT: Instant = Instant.parse("2026-09-14T12:05:00Z")
        const val JTI = "a7b8c9d0-1111-2222-3333-444455556666"

        val VERIFIED =
            VerifiedAccessToken(
                userId = USER_ID,
                sessionId = SESSION_ID,
                jti = JTI,
                expiresAt = EXPIRES_AT,
            )

        const val UNIFORM_MESSAGE = "Not authenticated"
        const val ACCESS_TOKEN = "header.payload.signature"
        const val BLANK_TOKEN = "   "
        const val DENYLIST_KEY_PREFIX = "auth:denylist:sid:"
        const val ALGORITHM_HEADER = "alg"
        const val ES256 = "ES256"
        const val SESSION_ID_CLAIM = "sid"
        const val JTI_CLAIM = "jti"
        const val TOKEN_TYPE_CLAIM = "typ"
        const val TOKEN_TYPE_ACCESS = "access"
    }
}
