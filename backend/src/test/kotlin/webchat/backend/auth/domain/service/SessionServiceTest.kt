package webchat.backend.auth.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import webchat.backend.auth.domain.model.RefreshToken
import webchat.backend.auth.domain.model.RefreshTokenStatus
import webchat.backend.auth.domain.model.RevokedReason
import webchat.backend.auth.domain.model.Session
import webchat.backend.auth.domain.model.SessionStatus
import webchat.backend.auth.domain.port.Clock
import webchat.backend.auth.domain.port.RefreshTokenRepository
import webchat.backend.auth.domain.port.SessionRepository
import webchat.backend.auth.security.AuthEventRecorder
import webchat.backend.auth.security.AuthEventType
import webchat.backend.auth.security.JwtService
import webchat.backend.config.AuthTokenProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.mockito.Mockito.`when` as whenever

/**
 * Unit-level mirror of the T030 session lifecycle mandated for T026: login
 * issuance (hash-at-rest only), CAS rotation, reuse → compromised + chain
 * revocation + sid denylist + the FR-013 event set, logout, password-change
 * revocation. The end-to-end contract checks (uniform 401, parallel
 * sessions, Redis TTL bounds) live in SessionIT; its full GREEN lands with
 * T031–T033 ( JwtServiceTest precedent from T028).
 */
@Suppress("LargeClass") // tasks.md T030: the whole session lifecycle in one unit mirror
class SessionServiceTest {
    private val sessionRepository: SessionRepository = Mockito.mock(SessionRepository::class.java)

    private val refreshTokenRepository: RefreshTokenRepository = Mockito.mock(RefreshTokenRepository::class.java)

    private val jwtService: JwtService = Mockito.mock(JwtService::class.java)

    private val redisTemplate: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)

    @Suppress("UNCHECKED_CAST") // Mockito mocks the raw ValueOperations interface
    private val valueOperations: ValueOperations<String, String> =
        Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>

    private val authEventRecorder: AuthEventRecorder = Mockito.mock(AuthEventRecorder::class.java)

    private val clock: Clock = Clock { FIXED_NOW }

    private lateinit var service: SessionService

    @BeforeEach
    fun setUp() {
        service =
            SessionService(
                sessionRepository = sessionRepository,
                refreshTokenRepository = refreshTokenRepository,
                jwtService = jwtService,
                redisTemplate = redisTemplate,
                authEventRecorder = authEventRecorder,
                authTokenProperties = TOKEN_PROPERTIES,
                clock = clock,
            )
    }

    @Test
    fun `startSession opens an active session and persists only the hash of the refresh value`() {
        whenever(jwtService.createAccessToken(eqSafe(USER_ID), anyUuid())).thenReturn(ACCESS_TOKEN)

        service.startSession(USER_ID)

        val sessionCaptor = ArgumentCaptor.forClass(Session::class.java)
        verify(sessionRepository).insert(sessionCaptor.capture() ?: activeSession())
        val session = sessionCaptor.value
        assertThat(session.userId).isEqualTo(USER_ID)
        assertThat(session.status).isEqualTo(SessionStatus.ACTIVE)

        val generationCaptor = ArgumentCaptor.forClass(RefreshToken::class.java)
        verify(refreshTokenRepository).insert(generationCaptor.capture() ?: dummyGeneration())
        val generation = generationCaptor.value
        assertThat(generation.sessionId).isEqualTo(session.id)
        assertThat(generation.status).isEqualTo(RefreshTokenStatus.ACTIVE)
        assertThat(generation.expiresAt).isEqualTo(FIXED_NOW.plus(TOKEN_PROPERTIES.refreshTtl))
        assertThat(generation.tokenHash).hasSize(SHA_256_HEX_LENGTH).matches(HEX_PATTERN_STRING)

        verify(jwtService).createAccessToken(USER_ID, session.id)
    }

    @Test
    fun `refresh rotates the generation with CAS semantics and records refresh_rotated`() {
        val openValue = "first-generation-open-value"
        val session = activeSession()
        stubResolved(openValue, session)
        stubRotate(true)
        whenever(jwtService.createAccessToken(session.userId, session.id)).thenReturn(ACCESS_TOKEN)

        val issued = service.refresh(openValue, CLIENT_IP)

        verify(refreshTokenRepository).rotate(eqSafe(GENERATION_ID), eqSafe(sha256Hex(openValue)), anyUuid())
        val generationCaptor = ArgumentCaptor.forClass(RefreshToken::class.java)
        verify(refreshTokenRepository).insert(generationCaptor.capture() ?: dummyGeneration())
        val replacement = generationCaptor.value
        assertThat(replacement.sessionId).isEqualTo(session.id)
        assertThat(replacement.tokenHash).isNotEqualTo(sha256Hex(openValue))
        assertThat(issued.refreshToken).matches(BASE64_URL_PATTERN_STRING)
        assertThat(sha256Hex(issued.refreshToken)).isEqualTo(replacement.tokenHash)
        assertThat(issued.expiresInSec).isEqualTo(TOKEN_PROPERTIES.accessTtl.seconds)

        verify(sessionRepository).markRefreshed(session.id)
        verify(authEventRecorder).record(AuthEventType.REFRESH_ROTATED, CLIENT_IP, null, session.userId, emptyMap())
    }

    @Test
    fun `refresh with an unknown value is the uniform failure without any side effects`() {
        assertThatThrownBy { service.refresh("g".repeat(43), CLIENT_IP) }
            .isInstanceOf(InvalidRefreshTokenException::class.java)

        verifyNoInteractions(sessionRepository)
        verifyNoInteractions(authEventRecorder)
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `reuse of a consumed generation compromises the session, revokes the chain and denylists the sid`() {
        val openValue = "reused-generation-open-value"
        val session = activeSession()
        stubResolved(openValue, session)
        stubRotate(false)
        whenever(sessionRepository.markCompromised(session.id)).thenReturn(true)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOperations)

        assertThatThrownBy { service.refresh(openValue, CLIENT_IP, USER_AGENT) }
            .isInstanceOf(InvalidRefreshTokenException::class.java)

        verify(sessionRepository).markCompromised(session.id)
        verify(refreshTokenRepository).revokeActiveForSession(session.id)
        verify(valueOperations).set(DENYLIST_PREFIX + session.id, DENYLIST_MARKER, TOKEN_PROPERTIES.accessTtl)

        verify(authEventRecorder)
            .record(AuthEventType.REFRESH_REUSE_DETECTED, CLIENT_IP, USER_AGENT, session.userId, emptyMap())
        val detailsCaptor = detailsCaptor()
        verify(authEventRecorder).record(
            eqSafe(AuthEventType.SESSION_REVOKED),
            eqSafe(CLIENT_IP),
            eqSafe(USER_AGENT),
            eqSafe(session.userId),
            detailsCaptor.capture() ?: emptyMap(),
        )
        assertThat(detailsCaptor.value).containsEntry(DETAIL_REASON, "refresh_reuse_detected")
    }

    @Test
    fun `repeat presentation of a dead generation stays event-silent`() {
        val openValue = "dead-generation-open-value"
        val session = sessionWithStatus(SessionStatus.COMPROMISED)
        stubResolved(openValue, session)
        stubRotate(false)

        assertThatThrownBy { service.refresh(openValue, CLIENT_IP) }
            .isInstanceOf(InvalidRefreshTokenException::class.java)

        verifyNoInteractions(authEventRecorder)
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `logout revokes the session with its chain, denylists the sid and records both events`() {
        val openValue = "logout-generation-open-value"
        val session = activeSession()
        stubResolved(openValue, session)
        whenever(sessionRepository.revoke(session.id, RevokedReason.LOGOUT)).thenReturn(true)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOperations)

        service.logout(openValue, CLIENT_IP, USER_AGENT)

        verify(sessionRepository).revoke(session.id, RevokedReason.LOGOUT)
        verify(refreshTokenRepository).revokeActiveForSession(session.id)
        verify(valueOperations).set(DENYLIST_PREFIX + session.id, DENYLIST_MARKER, TOKEN_PROPERTIES.accessTtl)
        verify(authEventRecorder).record(AuthEventType.LOGOUT, CLIENT_IP, USER_AGENT, session.userId, emptyMap())
        val detailsCaptor = detailsCaptor()
        verify(authEventRecorder).record(
            eqSafe(AuthEventType.SESSION_REVOKED),
            eqSafe(CLIENT_IP),
            eqSafe(USER_AGENT),
            eqSafe(session.userId),
            detailsCaptor.capture() ?: emptyMap(),
        )
        assertThat(detailsCaptor.value).containsEntry(DETAIL_REASON, "logout")
    }

    @Test
    fun `logout of an already dead session is an idempotent no-op`() {
        val openValue = "dead-session-open-value"
        val session = sessionWithStatus(SessionStatus.REVOKED)
        stubResolved(openValue, session)

        service.logout(openValue, CLIENT_IP)

        verifyNoInteractions(authEventRecorder)
        verifyNoInteractions(redisTemplate)
    }

    @Test
    fun `revokeAllForUser kills every session, chain and sid with a password_change event each`() {
        val sidA = UUID.randomUUID()
        val sidB = UUID.randomUUID()
        whenever(sessionRepository.revokeAllForUser(USER_ID, RevokedReason.PASSWORD_CHANGE))
            .thenReturn(listOf(sidA, sidB))
        whenever(redisTemplate.opsForValue()).thenReturn(valueOperations)

        service.revokeAllForUser(USER_ID, CLIENT_IP)

        verify(refreshTokenRepository).revokeActiveForUser(USER_ID)
        verify(valueOperations).set(DENYLIST_PREFIX + sidA, DENYLIST_MARKER, TOKEN_PROPERTIES.accessTtl)
        verify(valueOperations).set(DENYLIST_PREFIX + sidB, DENYLIST_MARKER, TOKEN_PROPERTIES.accessTtl)
        val detailsCaptor = detailsCaptor()
        verify(authEventRecorder, Mockito.times(2)).record(
            eqSafe(AuthEventType.SESSION_REVOKED),
            eqSafe(CLIENT_IP),
            isNullUserAgent(),
            eqSafe(USER_ID),
            detailsCaptor.capture() ?: emptyMap(),
        )
        assertThat(detailsCaptor.allValues)
            .hasSize(2)
            .allSatisfy { details -> assertThat(details).containsEntry(DETAIL_REASON, "password_change") }
    }

    @Suppress("UNCHECKED_CAST") // Mockito captures the raw jsonb details map
    private fun detailsCaptor(): ArgumentCaptor<Map<String, Any>> =
        ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, Any>>

    /**
     * Mockito matchers made Kotlin-null-safe: the matcher is registered inside
     * [eq]/[any], the substituted value only satisfies the caller-side
     * non-null parameter checks of Kotlin mock calls.
     */
    private fun <T : Any> eqSafe(value: T): T = eq(value) ?: value

    private fun anyUuid(): UUID = any(UUID::class.java) ?: GENERATION_ID

    private fun isNullUserAgent(): String? = Mockito.isNull<String>()

    private fun stubResolved(
        openValue: String,
        session: Session,
    ) {
        val generation =
            RefreshToken(
                id = GENERATION_ID,
                sessionId = session.id,
                tokenHash = sha256Hex(openValue),
                status = RefreshTokenStatus.ACTIVE,
                expiresAt = FIXED_NOW.plus(TOKEN_PROPERTIES.refreshTtl),
                replacedBy = null,
                createdAt = FIXED_NOW,
            )
        whenever(refreshTokenRepository.findByTokenHash(sha256Hex(openValue))).thenReturn(generation)
        whenever(sessionRepository.findById(session.id)).thenReturn(session)
    }

    private fun stubRotate(result: Boolean) {
        whenever(refreshTokenRepository.rotate(anyUuid(), anyString() ?: "", anyUuid())).thenReturn(result)
    }

    private fun activeSession(): Session = sessionWithStatus(SessionStatus.ACTIVE)

    /** Non-null placeholder for `captor.capture()` inside non-null Kotlin parameters. */
    private fun dummyGeneration(): RefreshToken =
        RefreshToken(
            id = GENERATION_ID,
            sessionId = GENERATION_ID,
            tokenHash = "0".repeat(SHA_256_HEX_LENGTH),
            status = RefreshTokenStatus.ACTIVE,
            expiresAt = FIXED_NOW,
            replacedBy = null,
            createdAt = FIXED_NOW,
        )

    private fun sessionWithStatus(status: SessionStatus): Session =
        Session(
            id = UUID.randomUUID(),
            userId = USER_ID,
            status = status,
            createdAt = FIXED_NOW,
            lastRefreshedAt = FIXED_NOW,
            revokedAt = null,
            revokedReason = null,
        )

    private fun sha256Hex(value: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .toHexString()

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-09-14T10:00:00Z")
        val TOKEN_PROPERTIES =
            AuthTokenProperties(
                accessTtl = Duration.ofMinutes(5),
                refreshTtl = Duration.ofDays(3),
                emailVerificationTtl = Duration.ofHours(24),
                passwordSetupTtl = Duration.ofHours(1),
                passwordResetTtl = Duration.ofHours(1),
            )

        val USER_ID: UUID = UUID.randomUUID()
        val GENERATION_ID: UUID = UUID(0, 1)
        const val ACCESS_TOKEN = "stubbed.es256.access.token"
        const val CLIENT_IP = "192.0.2.10"
        const val USER_AGENT = "session-service-test/1.0"
        const val DENYLIST_PREFIX = "auth:denylist:sid:"
        const val DENYLIST_MARKER = "revoked"
        const val DETAIL_REASON = "reason"
        const val SHA_256 = "SHA-256"
        const val SHA_256_HEX_LENGTH = 64
        const val HEX_PATTERN_STRING = "^[0-9a-f]+$"
        const val BASE64_URL_PATTERN_STRING = "^[A-Za-z0-9_-]{43}$"
    }
}
