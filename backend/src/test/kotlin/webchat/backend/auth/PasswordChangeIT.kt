package webchat.backend.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.AbstractIntegrationTest
import webchat.backend.sso.MockIdP
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T010 [US2] (tasks.md 008b Phase 4, Test-First): POST /api/v1/auth/password-change
 * — contract 0.10.0 §1 (contracts/auth-ux-polish-api.md), data-model 008b §3.3,
 * quickstart §US2. Written BEFORE the implementation (T013–T019) — until the
 * endpoint, the V17 audit enums consumer and both rate-limit tiers land, the
 * assertions fail (RED) by design (constitution VI).
 *
 * Covered acceptance:
 * - success: the answer is a bare `TokenPair` (parity with POST /auth/refresh:
 *   `tokenType`/`expiresInSec`/fresh 43-char refresh, NO `user` fragment), the
 *   new pair is immediately usable, the old password answers the uniform 401
 *   "Invalid credentials" while the new one logs in, the stored hash changes
 *   and stays Argon2id, and `password_change_completed` is journaled with
 *   `details={reason:"profile"}` — no journal row of the user carries an open
 *   password or a token value (FR-010, SC-004);
 * - wrong current password: the single 400 `currentPassword: ["invalid"]`
 *   problem+json, the hash untouched, `password_change_failed` with
 *   `details={reason:"invalid_current_password"}`, the session untouched;
 * - JIT account (SSO 003, `password_hash IS NULL`): the SAME unified 400 —
 *   the fictitious-hash verification must not leak the password-less state
 *   (FR-004) — plus the same `password_change_failed` reason marker;
 * - policy violations (002 codes): too_short/too_long/blank/too_common/
 *   same_as_username/same_as_email answer 400 `newPassword` field codes while
 *   the hash and the sessions stay untouched, and NO journal row appears
 *   (data-model 008b §1.2: policy failures are plain 400 validation);
 * - per-user flood limit 5/1m (research 008b §6): the 6th change attempt of a
 *   user within a minute — every attempt from a FRESH source IP, so only the
 *   shared per-user bucket `rl:user:pwdchange:<userId>` can trip — answers
 *   429 + `Retry-After`, is not journaled, and changes nothing (FR-007);
 * - per-IP flood limit 10/1m (the RateLimitIT «beyond ip limit» pattern): two
 *   users share one source IP until the bucket drains (5 attempts each —
 *   every per-user verdict stays within its own 5/1m allowance), then the
 *   11th request of that minute — made by a THIRD user whose per-user bucket
 *   is untouched — is throttled with 429 + `Retry-After` and the external
 *   `rl:ip:password-change:<ip>` key exists; the same third user from a
 *   different IP is NOT throttled (the block is IP-scoped);
 * - multi-device (FR-008): changing on device A revokes every OTHER session
 *   with `sessions.status=revoked / revoked_reason=password_change`, denylists
 *   each revoked sid (TTL bounded by the access lifetime), kills device B's
 *   refresh AND access (401), while the CURRENT session stays active with its
 *   refresh ROTATED — the old refresh answers 401, the response pair works,
 *   and exactly ONE active refresh generation of the user remains
 *   (data-model 008b §5.3); `session_revoked`×1 with
 *   `details={reason:"password_change"}` and `password_change_completed` are
 *   journaled without secrets;
 * - SSO session of the second device (feature 003, `auth_method='sso'`): the
 *   revocation is identical to the password-session case — FR-008 uniformity;
 * - a new password EQUAL to the old one still walks the full path: other
 *   sessions revoked, completion journaled (clarify spec.md:17).
 *
 * Test-method isolation follows the RateLimitIT conventions: every method
 * grows its own users through the real 002 API and uses its own documentation
 * source IPs (198.18.20–28.x), so the shared static Redis/PG carry no state
 * between methods. The two SSO scenarios drive the MockIdP through a loopback
 * mount (SsoFlowIT precedent) — the provider endpoints must be absolute URIs
 * known before the context starts for the `sso.providers.*` binding.
 */
@Suppress("LargeClass", "TooManyFunctions", "LongMethod")
class PasswordChangeIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val redisTemplate: StringRedisTemplate,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun resetIdp() {
        mockIdP.reset()
    }

    @Test
    fun `successful change answers a token pair and switches the login password`() {
        createActiveUser("pcsuccess", "pcsuccess@example.com", registerIp = "198.18.20.10")
        val hashBefore = userRow("pcsuccess")!!["password_hash"] as String
        assertThat(hashBefore).startsWith(ARGON2_PREFIX)

        val deviceA = loginOkPair("pcsuccess", xForwardedFor = "198.18.20.11")

        val change = passwordChange(deviceA.accessToken, PASSWORD, NEW_PASSWORD, xForwardedFor = "198.18.20.12")
        assertThat(change.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(change.headers.contentType!!.toString()).contains("application/json")
        val pair = objectMapper.readTree(change.body)
        assertThat(pair["tokenType"].asText()).isEqualTo("Bearer")
        assertThat(pair["expiresInSec"].asInt()).isEqualTo(ACCESS_TTL_SECONDS)
        assertThat(pair["accessToken"].asText()).isNotEmpty
        assertThat(pair["refreshToken"].asText()).matches(TOKEN_43)
        // contract 0.10.0 §1: parity with POST /auth/refresh — a BARE TokenPair, no user fragment
        assertThat(pair.has("user")).isFalse

        // the response pair is live at once: access opens the profile, refresh rotates
        val profile = usersMe(pair["accessToken"].asText())
        assertThat(profile.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(profile.body)["username"].asText()).isEqualTo("pcsuccess")
        assertThat(refresh(pair["refreshToken"].asText(), xForwardedFor = "198.18.20.15").statusCode)
            .isEqualTo(HttpStatus.OK)

        // data-model 008b §5.3: exactly ONE active refresh generation — the
        // rotated current one (asserted BEFORE the US2 AC2 re-login below: a
        // successful 002 login always opens its own session+generation, so
        // counting after it would legitimately see the second device)
        assertThat(activeRefreshGenerationsFor("pcsuccess")).isEqualTo(1)

        // US2 AC2: the old password is dead with the uniform message, the new one logs in
        val oldPasswordLogin = login("pcsuccess", PASSWORD, xForwardedFor = "198.18.20.13")
        assertThat(oldPasswordLogin.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(oldPasswordLogin.body)["detail"].asText()).isEqualTo("Invalid credentials")
        assertThat(login("pcsuccess", NEW_PASSWORD, xForwardedFor = "198.18.20.14").statusCode).isEqualTo(HttpStatus.OK)

        // FR-009: the hash changed and remains an Argon2id value with its own salt
        val hashAfter = userRow("pcsuccess")!!["password_hash"] as String
        assertThat(hashAfter).isNotEqualTo(hashBefore)
        assertThat(hashAfter).startsWith(ARGON2_PREFIX)

        // FR-010: completion journaled with details={reason:"profile"}, no secrets anywhere
        assertThat(authEventCount("password_change_completed", "pcsuccess")).isEqualTo(1)
        assertThat(latestEventReason("password_change_completed", "pcsuccess")).isEqualTo("profile")
        assertThat(authEventCount("password_change_failed", "pcsuccess")).isZero
        assertNoJournalSecrets(
            "pcsuccess",
            PASSWORD,
            NEW_PASSWORD,
            deviceA.accessToken,
            deviceA.refreshToken,
            pair["refreshToken"].asText(),
        )
    }

    @Test
    fun `wrong current password answers 400 currentPassword invalid and changes nothing`() {
        createActiveUser("pcwrongcur", "pcwrongcur@example.com", registerIp = "198.18.21.10")
        val hashBefore = userRow("pcwrongcur")!!["password_hash"]
        val deviceA = loginOkPair("pcwrongcur", xForwardedFor = "198.18.21.11")
        val sidA = sessionIdOf(deviceA.refreshToken)!!

        val response =
            passwordChange(
                deviceA.accessToken,
                "Wr0ng-Current-Pass!",
                NEW_PASSWORD,
                xForwardedFor = "198.18.21.12",
            )

        // contract 0.10.0 §1: the single field code, rendered problem+json
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType!!.toString()).contains("application/problem+json")
        assertThat(errorCode(response.body, "currentPassword")).isEqualTo("invalid")
        assertThat(errorCode(response.body, "newPassword")).isNull()

        // US2 AC3: nothing changed — hash intact, session alive, login unaffected
        assertThat(userRow("pcwrongcur")!!["password_hash"]).isEqualTo(hashBefore)
        assertThat(sessionRow(sidA)!!["status"]).isEqualTo("active")
        assertThat(login("pcwrongcur", PASSWORD, xForwardedFor = "198.18.21.14").statusCode).isEqualTo(HttpStatus.OK)

        // FR-010: the failed attempt is journaled with the unified reason marker, no secrets
        assertThat(authEventCount("password_change_failed", "pcwrongcur")).isEqualTo(1)
        assertThat(authEventCount("password_change_completed", "pcwrongcur")).isZero
        assertThat(latestEventReason("password_change_failed", "pcwrongcur")).isEqualTo("invalid_current_password")
        assertThat(authEventCount("session_revoked", "pcwrongcur")).isZero
        assertNoJournalSecrets("pcwrongcur", PASSWORD, "Wr0ng-Current-Pass!", NEW_PASSWORD)
    }

    @Test
    fun `jit account without password gets the same uniform 400 currentPassword invalid`() {
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = SUBJECT_JIT, email = EMAIL_JIT, emailVerified = true))

        val sso = performSsoLogin()
        val jitUsername = sso.body["user"]["username"].asText()

        // sanity: the 003 JIT shape — active account, password_hash IS NULL (FR-004)
        val jitRow = userRow(jitUsername)!!
        assertThat(jitRow["status"]).isEqualTo("active")
        assertThat(jitRow["password_hash"]).isNull()

        val response = passwordChange(sso.accessToken, "Any-Current-Pass!", NEW_PASSWORD, xForwardedFor = nextSsoIp())

        // the unified 400 — the account's password-less state is never disclosed
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body, "currentPassword")).isEqualTo("invalid")
        assertThat(userRow(jitUsername)!!["password_hash"]).isNull()

        // data-model 008b §3.3 step 2: the JIT leg journals the same failure reason
        assertThat(authEventCount("password_change_failed", jitUsername)).isEqualTo(1)
        assertThat(latestEventReason("password_change_failed", jitUsername)).isEqualTo("invalid_current_password")
        assertThat(authEventCount("password_change_completed", jitUsername)).isZero
        // the SSO session itself is untouched — only a success revokes
        assertThat(sessionRow(sessionIdOf(sso.refreshToken)!!)!!["status"]).isEqualTo("active")
        assertNoJournalSecrets(jitUsername, "Any-Current-Pass!", NEW_PASSWORD)
    }

    @Test
    fun `policy violations answer 400 field codes and keep the password unchanged`() {
        createActiveUser("pcpolicy1", "pcpolicy1@example.com", registerIp = "198.18.22.10")
        createActiveUser("pcpolicy2", "pcpol2@example.com", registerIp = "198.18.22.20")
        val first = loginOkPair("pcpolicy1", xForwardedFor = "198.18.22.11")
        val second = loginOkPair("pcpolicy2", xForwardedFor = "198.18.22.21")
        val hashFirst = userRow("pcpolicy1")!!["password_hash"]
        val hashSecond = userRow("pcpolicy2")!!["password_hash"]

        // the FR-005 policy of 002, one user per ≤5 attempts (their own 5/1m buckets)
        val firstViolations =
            listOf(
                "Sh0rt!1" to "too_short",
                "U".repeat(129) to "too_long",
                "" to "blank",
                "        " to "blank",
            )
        firstViolations.forEachIndexed { index, (candidate, code) ->
            val response =
                passwordChange(
                    first.accessToken,
                    PASSWORD,
                    candidate,
                    xForwardedFor = "198.18.22.1${2 + index}",
                )
            assertThat(response.statusCode)
                .overridingErrorMessage("password <%s> must be rejected with 400", candidate)
                .isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(errorCode(response.body, "newPassword"))
                .overridingErrorMessage("password <%s> must answer its policy code", candidate)
                .isEqualTo(code)
        }

        // the same_as_* legs need username/email-length candidates: normalize()
        // compares trim().toLowerCase(), so mixed case still matches
        val secondViolations =
            listOf(
                "password123" to "too_common",
                "PcPolicy2" to "same_as_username",
                "PcPol2@Example.com" to "same_as_email",
            )
        secondViolations.forEachIndexed { index, (candidate, code) ->
            val response =
                passwordChange(
                    second.accessToken,
                    PASSWORD,
                    candidate,
                    xForwardedFor = "198.18.22.2${2 + index}",
                )
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(errorCode(response.body, "newPassword")).isEqualTo(code)
        }

        // nothing changed and nothing journaled — policy failures are plain 400 validation
        assertThat(userRow("pcpolicy1")!!["password_hash"]).isEqualTo(hashFirst)
        assertThat(userRow("pcpolicy2")!!["password_hash"]).isEqualTo(hashSecond)
        listOf("pcpolicy1", "pcpolicy2").forEach { username ->
            assertThat(authEventCount("password_change_failed", username)).isZero
            assertThat(authEventCount("password_change_completed", username)).isZero
            assertThat(authEventCount("session_revoked", username)).isZero
        }
        assertThat(login("pcpolicy1", PASSWORD, xForwardedFor = "198.18.22.18").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `sixth change within a minute by one user is throttled with retry-after`() {
        createActiveUser("pcrluser", "pcrluser@example.com", registerIp = "198.18.23.10")
        val device = loginOkPair("pcrluser", xForwardedFor = "198.18.23.11")
        val uid = userId("pcrluser")!!
        val hashBefore = userRow("pcrluser")!!["password_hash"]

        // per-user 5/1m (research 008b §6): five wrong-current attempts, EVERY one
        // from a FRESH source IP — the per-IP buckets see one request each, so
        // only the shared per-user bucket `rl:user:pwdchange:<userId>` accumulates
        repeat(USER_PER_MINUTE) { index ->
            val response =
                passwordChange(
                    device.accessToken,
                    "Wr0ng-Current-$index!",
                    NEW_PASSWORD,
                    xForwardedFor = "198.18.23.2$index",
                )
            assertThat(response.statusCode)
                .overridingErrorMessage(
                    "attempt %d within the per-user limit must be answered, not throttled",
                    index + 1,
                ).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        // the 6th change of the minute → 429 + Retry-After (US2 AC5, FR-007)
        val flooded =
            passwordChange(device.accessToken, "Wr0ng-Current-Final!", NEW_PASSWORD, xForwardedFor = "198.18.23.30")
        assertTooManyRequests(flooded)

        // the bucket state is external (data-model 008b §2), the throttle changed
        // nothing and was not journaled as an attempt (limiter runs first)
        assertThat(redisTemplate.hasKey("$USER_BUCKET_KEY_PREFIX$uid") ?: false).isTrue
        assertThat(userRow("pcrluser")!!["password_hash"]).isEqualTo(hashBefore)
        assertThat(authEventCount("password_change_failed", "pcrluser")).isEqualTo(USER_PER_MINUTE)
        assertThat(authEventCount("password_change_completed", "pcrluser")).isZero
    }

    @Test
    fun `eleventh request from one ip is throttled across users with retry-after`() {
        createActiveUser("pcip1", "pcip1@example.com", registerIp = "198.18.24.10")
        createActiveUser("pcip2", "pcip2@example.com", registerIp = "198.18.24.11")
        createActiveUser("pcip3", "pcip3@example.com", registerIp = "198.18.24.12")
        val first = loginOkPair("pcip1", xForwardedFor = "198.18.24.13")
        val second = loginOkPair("pcip2", xForwardedFor = "198.18.24.14")
        val third = loginOkPair("pcip3", xForwardedFor = "198.18.24.15")
        val sharedIp = "198.18.24.50"

        // two users drain the per-IP bucket of the shared source: 5 attempts each,
        // every one within its own per-user 5/1m allowance — no user-level throttle
        repeat(IP_PER_MINUTE / 2) { index ->
            val firstAttempt =
                passwordChange(
                    first.accessToken,
                    "Wr0ng-First-$index!",
                    NEW_PASSWORD,
                    xForwardedFor = sharedIp,
                )
            assertThat(firstAttempt.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            val secondAttempt =
                passwordChange(
                    second.accessToken,
                    "Wr0ng-Second-$index!",
                    NEW_PASSWORD,
                    xForwardedFor = sharedIp,
                )
            assertThat(secondAttempt.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        // the 11th request of the minute from that IP: a THIRD user whose per-user
        // bucket is untouched — only the shared `rl:ip:password-change:<ip>` tier
        // can reject it (the RateLimitIT «beyond ip limit» pattern)
        val flooded = passwordChange(third.accessToken, PASSWORD, NEW_PASSWORD, xForwardedFor = sharedIp)
        assertTooManyRequests(flooded)
        assertThat(redisTemplate.hasKey("$IP_BUCKET_KEY_PREFIX$sharedIp") ?: false).isTrue

        // the block is IP-scoped: the same third user from a different IP passes
        // the filter and reaches the endpoint (their per-user bucket holds 1 of 5)
        val elsewhere = passwordChange(third.accessToken, PASSWORD, NEW_PASSWORD, xForwardedFor = "198.18.24.99")
        assertThat(elsewhere.statusCode).isEqualTo(HttpStatus.OK)
        // the throttled attempt was never processed: exactly one completion exists
        assertThat(authEventCount("password_change_completed", "pcip3")).isEqualTo(1)
    }

    @Test
    fun `change revokes other devices denylists their sids and rotates the current refresh`() {
        createActiveUser("pcmultidev", "pcmultidev@example.com", registerIp = "198.18.25.10")
        val deviceA = loginOkPair("pcmultidev", xForwardedFor = "198.18.25.11")
        val deviceB = loginOkPair("pcmultidev", xForwardedFor = "198.18.25.12")
        val sidA = sessionIdOf(deviceA.refreshToken)!!
        val sidB = sessionIdOf(deviceB.refreshToken)!!
        assertThat(sidA).isNotEqualTo(sidB)

        val change = passwordChange(deviceA.accessToken, PASSWORD, NEW_PASSWORD, xForwardedFor = "198.18.25.13")
        assertThat(change.statusCode).isEqualTo(HttpStatus.OK)
        val pair = objectMapper.readTree(change.body)
        assertThat(pair["tokenType"].asText()).isEqualTo("Bearer")
        assertThat(pair["refreshToken"].asText()).matches(TOKEN_43)

        // FR-008: device B is fully revoked — row, denylist marker, both tokens dead
        assertThat(sessionRow(sidB)!!["status"]).isEqualTo("revoked")
        assertThat(sessionRow(sidB)!!["revoked_reason"]).isEqualTo("password_change")
        val denylistTtl = redisTemplate.getExpire("$DENYLIST_KEY_PREFIX$sidB", TimeUnit.SECONDS)
        assertThat(denylistTtl ?: -1L).isGreaterThan(0).isLessThanOrEqualTo(ACCESS_TTL_SECONDS.toLong())
        assertThat(usersMe(deviceB.accessToken).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(refresh(deviceB.refreshToken, xForwardedFor = "198.18.25.16").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)

        // the CURRENT session stays logged in: active row, rotated pair works,
        // the OLD refresh of the current session is dead (data-model 008b §3.3 step 6)
        assertThat(sessionRow(sidA)!!["status"]).isEqualTo("active")
        assertThat(refresh(deviceA.refreshToken, xForwardedFor = "198.18.25.17").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(usersMe(pair["accessToken"].asText()).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(refresh(pair["refreshToken"].asText(), xForwardedFor = "198.18.25.18").statusCode)
            .isEqualTo(HttpStatus.OK)

        // data-model 008b §5.3 invariant: exactly one active generation of the user
        assertThat(activeRefreshGenerationsFor("pcmultidev")).isEqualTo(1)

        // audit: one session_revoked{password_change} per other session + completion
        assertThat(authEventCount("session_revoked", "pcmultidev")).isEqualTo(1)
        assertThat(latestEventReason("session_revoked", "pcmultidev")).isEqualTo("password_change")
        assertThat(authEventCount("password_change_completed", "pcmultidev")).isEqualTo(1)
        assertThat(latestEventReason("password_change_completed", "pcmultidev")).isEqualTo("profile")
        assertNoJournalSecrets(
            "pcmultidev",
            PASSWORD,
            NEW_PASSWORD,
            deviceA.refreshToken,
            deviceB.refreshToken,
            pair["refreshToken"].asText(),
        )
    }

    @Test
    fun `sso session of a second device is revoked on par with a password session`() {
        seedPasswordUserWithIdentity("pcsso", "pcsso@example.com", SUBJECT_SSO, registerIp = "198.18.25.30")
        val deviceA = loginOkPair("pcsso", xForwardedFor = "198.18.25.31")
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = SUBJECT_SSO, email = EMAIL_SSO, emailVerified = true))
        val deviceB = performSsoLogin()
        val sidA = sessionIdOf(deviceA.refreshToken)!!
        val sidB = sessionIdOf(deviceB.refreshToken)!!

        // the 003 anchor: device B really is an SSO session of the SAME user
        val ssoSession = sessionRow(sidB)!!
        assertThat(ssoSession["auth_method"]).isEqualTo("sso")
        assertThat(ssoSession["user_id"]).isEqualTo(userId("pcsso"))
        assertThat(sidA).isNotEqualTo(sidB)

        val change = passwordChange(deviceA.accessToken, PASSWORD, NEW_PASSWORD, xForwardedFor = "198.18.25.32")
        assertThat(change.statusCode).isEqualTo(HttpStatus.OK)

        // FR-008 uniformity: the SSO session is revoked exactly like a password one
        assertThat(sessionRow(sidB)!!["status"]).isEqualTo("revoked")
        assertThat(sessionRow(sidB)!!["revoked_reason"]).isEqualTo("password_change")
        val denylistTtl = redisTemplate.getExpire("$DENYLIST_KEY_PREFIX$sidB", TimeUnit.SECONDS)
        assertThat(denylistTtl ?: -1L).isGreaterThan(0).isLessThanOrEqualTo(ACCESS_TTL_SECONDS.toLong())
        assertThat(usersMe(deviceB.accessToken).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(refresh(deviceB.refreshToken, xForwardedFor = "198.18.25.33").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(sessionRow(sidA)!!["status"]).isEqualTo("active")
        assertThat(authEventCount("session_revoked", "pcsso")).isEqualTo(1)
        assertThat(latestEventReason("session_revoked", "pcsso")).isEqualTo("password_change")
    }

    @Test
    fun `changing to the same password still revokes the other sessions`() {
        createActiveUser("pcsamepass", "pcsamepass@example.com", registerIp = "198.18.26.10")
        val deviceA = loginOkPair("pcsamepass", xForwardedFor = "198.18.26.11")
        val deviceB = loginOkPair("pcsamepass", xForwardedFor = "198.18.26.12")
        val sidB = sessionIdOf(deviceB.refreshToken)!!

        // clarify spec.md:17 — the new password EQUALS the old one; the full
        // FR-008 path still runs, no branch short-circuits the revocation
        val change = passwordChange(deviceA.accessToken, PASSWORD, PASSWORD, xForwardedFor = "198.18.26.13")
        assertThat(change.statusCode).isEqualTo(HttpStatus.OK)

        assertThat(sessionRow(sidB)!!["status"]).isEqualTo("revoked")
        assertThat(sessionRow(sidB)!!["revoked_reason"]).isEqualTo("password_change")
        assertThat(refresh(deviceB.refreshToken, xForwardedFor = "198.18.26.14").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(authEventCount("session_revoked", "pcsamepass")).isEqualTo(1)
        assertThat(authEventCount("password_change_completed", "pcsamepass")).isEqualTo(1)
        assertThat(activeRefreshGenerationsFor("pcsamepass")).isEqualTo(1)

        // the (unchanged) password keeps logging in — nothing was broken
        assertThat(login("pcsamepass", PASSWORD, xForwardedFor = "198.18.26.15").statusCode).isEqualTo(HttpStatus.OK)
    }

    // --- 002 password-side helpers (PasswordResetIT conventions) ------------

    private fun register(
        username: String,
        email: String,
        xForwardedFor: String,
    ): ResponseEntity<String> =
        postJson(
            REGISTER_PATH,
            mapOf("username" to username, "email" to email),
            xForwardedFor = xForwardedFor,
        )

    private fun confirm(
        token: String,
        xForwardedFor: String,
    ): ResponseEntity<String> = postJson(CONFIRM_PATH, mapOf("token" to token), xForwardedFor = xForwardedFor)

    private fun setPassword(
        setupToken: String,
        password: String,
        xForwardedFor: String,
    ): ResponseEntity<String> =
        postJson(
            SET_PASSWORD_PATH,
            mapOf("password" to password, "confirmPassword" to password, "setupToken" to setupToken),
            xForwardedFor = xForwardedFor,
        )

    /** Grows an active password user through the real 002 API (register → confirm → set password). */
    private fun createActiveUser(
        username: String,
        email: String,
        registerIp: String,
    ) {
        register(username, email, registerIp)
        val confirmResponse = confirm(extractVerificationToken(email), registerIp)
        assertThat(confirmResponse.statusCode).isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirmResponse.body)["setupToken"].asText()
        assertThat(setPassword(setupToken, PASSWORD, registerIp).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    /** The 002 user of the SsoFlowIT conventions: password login + pre-linked external identity. */
    private fun seedPasswordUserWithIdentity(
        username: String,
        email: String,
        subject: String,
        registerIp: String,
    ) {
        createActiveUser(username, email, registerIp)
        jdbcTemplate.update(
            """
            INSERT INTO external_identities
                (id, user_id, provider_id, subject, provider_email, provider_email_verified, linked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            userId(username),
            PROVIDER_ID,
            subject,
            "stale-$email",
            false,
            OffsetDateTime.now(),
        )
    }

    private fun login(
        identifier: String,
        password: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> =
        postJson(
            LOGIN_PATH,
            mapOf("identifier" to identifier, "password" to password),
            xForwardedFor = xForwardedFor,
        )

    /** Successful login pair; [password] defaults to the seeded [PASSWORD]. */
    private fun loginOkPair(
        identifier: String,
        password: String = PASSWORD,
        xForwardedFor: String? = null,
    ): Tokens {
        val response = login(identifier, password, xForwardedFor)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(response.body)
        return Tokens(body["accessToken"].asText(), body["refreshToken"].asText())
    }

    /** The endpoint under test: Bearer + {currentPassword, newPassword} (contract 0.10.0 §1). */
    private fun passwordChange(
        accessToken: String,
        currentPassword: String,
        newPassword: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> =
        postJson(
            PASSWORD_CHANGE_PATH,
            mapOf("currentPassword" to currentPassword, "newPassword" to newPassword),
            accessToken = accessToken,
            xForwardedFor = xForwardedFor,
        )

    private fun refresh(
        refreshToken: String,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> =
        postJson(
            REFRESH_PATH,
            mapOf("refreshToken" to refreshToken),
            xForwardedFor = xForwardedFor,
        )

    private fun usersMe(accessToken: String): ResponseEntity<String> =
        restTemplate.exchange(
            USERS_ME_PATH,
            HttpMethod.GET,
            HttpEntity(null, authHeaders(accessToken = accessToken)),
            String::class.java,
        )

    private fun postJson(
        path: String,
        payload: Map<String, String>,
        accessToken: String? = null,
        xForwardedFor: String? = null,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            path,
            HttpEntity(payload, authHeaders(accessToken = accessToken, xForwardedFor = xForwardedFor)),
            String::class.java,
        )

    private fun authHeaders(
        accessToken: String? = null,
        xForwardedFor: String? = null,
    ): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accessToken?.let { set(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            xForwardedFor?.let { set(X_FORWARDED_FOR, it) }
        }

    private fun extractVerificationToken(email: String): String {
        val payloads =
            jdbcTemplate.queryForList(
                """
                SELECT payload::text FROM email_outbox
                WHERE lower(recipient_email) = ? AND email_type = 'email_verification'
                ORDER BY created_at DESC
                """.trimIndent(),
                String::class.java,
                email.lowercase(),
            )
        val match = Regex("""token=([A-Za-z0-9_-]{43})""").find(payloads.firstOrNull().orEmpty())
        assertThat(match)
            .overridingErrorMessage(
                "verification email to <%s> must contain a /confirm-registration?token=... link",
                email,
            ).isNotNull
        return match!!.groupValues[1]
    }

    // --- SSO helpers (SsoFlowIT conventions) ---------------------------------

    private fun performSsoLogin(): SsoLoginResult {
        val authorizeResponse =
            postJson(
                SSO_AUTHORIZE_PATH,
                mapOf("providerId" to PROVIDER_ID),
                xForwardedFor = nextSsoIp(),
            )
        assertThat(authorizeResponse.statusCode).isEqualTo(HttpStatus.OK)
        val authorizationUrl = objectMapper.readTree(authorizeResponse.body)["authorizationUrl"].asText()

        val idpRedirect = noRedirectClient.getForEntity(URI.create(authorizationUrl), String::class.java)
        assertThat(idpRedirect.statusCode).isEqualTo(HttpStatus.FOUND)
        val idpParameters = queryParametersOf(idpRedirect.headers.location.toString())

        val callbackUrl =
            UriComponentsBuilder
                .fromHttpUrl(rootUri() + SSO_CALLBACK_PATH)
                .queryParam(STATE_PARAMETER, idpParameters.getValue(STATE_PARAMETER))
                .queryParam(CODE_PARAMETER, idpParameters.getValue(CODE_PARAMETER))
                .build()
                .toUriString()
        val callbackResponse = noRedirectClient.getForEntity(URI.create(callbackUrl), String::class.java)
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val handshakeCode = queryParametersOf(callbackResponse.headers.location.toString()).getValue(CODE_PARAMETER)

        val tokenResponse =
            postJson(
                SSO_TOKEN_PATH,
                mapOf(CODE_PARAMETER to handshakeCode),
                xForwardedFor = nextSsoIp(),
            )
        assertThat(tokenResponse.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(tokenResponse.body)
        return SsoLoginResult(
            accessToken = body["accessToken"].asText(),
            refreshToken = body["refreshToken"].asText(),
            body = body,
        )
    }

    /**
     * TestRestTemplate's JDK client follows redirects, which would chase the
     * 302 Locations straight into unreachable redirect targets; the IdP and
     * callback assertions need the raw FOUND responses (SsoFlowIT precedent).
     */
    private val noRedirectClient: RestTemplate =
        RestTemplate(
            object : SimpleClientHttpRequestFactory() {
                override fun prepareConnection(
                    connection: HttpURLConnection,
                    httpMethod: String,
                ) {
                    super.prepareConnection(connection, httpMethod)
                    connection.instanceFollowRedirects = false
                }
            },
        )

    private fun rootUri(): String = restTemplate.rootUri.removeSuffix("/")

    private fun queryParametersOf(url: String): Map<String, String> =
        UriComponentsBuilder
            .fromUriString(url)
            .build()
            .queryParams
            .map { (name, values) -> name to values.first() }
            .toMap()

    // --- inspection helpers (PasswordResetIT conventions) -------------------

    private fun userRow(username: String): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, username, email, password_hash, status::text AS status
                FROM users WHERE lower(username) = ?
                """.trimIndent(),
                username.lowercase(),
            ).firstOrNull()

    private fun userId(username: String): UUID? =
        jdbcTemplate
            .queryForList(
                "SELECT id FROM users WHERE lower(username) = ?",
                UUID::class.java,
                username.lowercase(),
            ).firstOrNull()

    private fun sessionIdOf(refreshToken: String): UUID? =
        jdbcTemplate
            .queryForList(
                "SELECT session_id FROM refresh_tokens WHERE token_hash = ?",
                UUID::class.java,
                sha256Hex(refreshToken),
            ).firstOrNull()

    private fun sessionRow(sid: UUID): Map<String, Any>? =
        jdbcTemplate
            .queryForList(
                """
                SELECT id, user_id, status::text AS status, revoked_reason::text AS revoked_reason, revoked_at,
                       auth_method, identity_id
                FROM sessions WHERE id = ?
                """.trimIndent(),
                sid,
            ).firstOrNull()

    /** data-model 008b §5.3: the active refresh generations across ALL sessions of the user. */
    private fun activeRefreshGenerationsFor(username: String): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM refresh_tokens rt
            JOIN sessions s ON s.id = rt.session_id
            WHERE s.user_id = (SELECT id FROM users WHERE lower(username) = ?)
              AND rt.status = 'active'
            """.trimIndent(),
            Int::class.java,
            username.lowercase(),
        )

    private fun authEventCount(
        eventType: String,
        username: String,
    ): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM auth_events
            WHERE event_type = ?::auth_event_type
              AND user_id = (SELECT id FROM users WHERE lower(username) = ?)
            """.trimIndent(),
            Int::class.java,
            eventType,
            username.lowercase(),
        )

    /** The `details.reason` marker of the latest event of the kind (data-model 008b §1.2). */
    private fun latestEventReason(
        eventType: String,
        username: String,
    ): String? =
        jdbcTemplate
            .queryForList(
                """
                SELECT details::text AS details FROM auth_events
                WHERE event_type = ?::auth_event_type
                  AND user_id = (SELECT id FROM users WHERE lower(username) = ?)
                ORDER BY occurred_at DESC
                """.trimIndent(),
                String::class.java,
                eventType,
                username.lowercase(),
            ).firstOrNull()
            ?.let { details -> objectMapper.readTree(details)["reason"]?.asText() }

    /** FR-010/SC-004: no journal row of the user may carry any submitted secret or token value. */
    private fun assertNoJournalSecrets(
        username: String,
        vararg secrets: String,
    ) {
        secrets.filter { it.isNotEmpty() }.forEach { secret ->
            val leaks =
                jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM auth_events
                    WHERE user_id = (SELECT id FROM users WHERE lower(username) = ?)
                      AND (details::text LIKE '%' || ? || '%' OR user_agent LIKE '%' || ? || '%')
                    """.trimIndent(),
                    Int::class.java,
                    username.lowercase(),
                    secret,
                    secret,
                )
            assertThat(leaks)
                .overridingErrorMessage("journal of <%s> leaks the secret <%s>", username, secret)
                .isZero
        }
    }

    /** The uniform 429 shape of the auth rate limiting (RateLimitIT conventions, FR-007). */
    private fun assertTooManyRequests(response: ResponseEntity<String>) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        val retryAfter = response.headers.getFirst(HttpHeaders.RETRY_AFTER)
        assertThat(retryAfter).isNotNull
        assertThat(retryAfter!!.toInt()).isBetween(1, MINUTE_SECONDS)
        assertThat(response.headers.contentType).isNotNull
        assertThat(response.headers.contentType!!.toString()).contains("application/problem+json")
        val problem = objectMapper.readTree(response.body)
        assertThat(problem["status"].asInt()).isEqualTo(429)
        assertThat(problem["title"].asText()).isEqualTo("Too Many Requests")
    }

    private fun errorCode(
        body: String?,
        field: String,
    ): String? =
        body
            ?.let(objectMapper::readTree)
            ?.get("errors")
            ?.get(field)
            ?.firstOrNull()
            ?.asText()

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun nextSsoIp(): String = "$SSO_IP_PREFIX${ssoIpCounter.incrementAndGet()}"

    private data class Tokens(
        val accessToken: String,
        val refreshToken: String,
    )

    private data class SsoLoginResult(
        val accessToken: String,
        val refreshToken: String,
        val body: JsonNode,
    )

    companion object {
        /** The same MockIdP controller class as the SSO ITs, driven directly by this suite. */
        val mockIdP = MockIdP(ObjectMapper())

        /**
         * Loopback mount of the MockIdP whose base URI is known BEFORE the
         * Spring context starts — the precondition for binding
         * `sso.providers.*` to absolute IdP endpoints (SsoFlowIT precedent).
         */
        private val idpServer = IdpLoopbackMount(mockIdP)

        @JvmStatic
        @DynamicPropertySource
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("sso.providers.$PROVIDER_ID.display-name") { "Password Change IT IdP" }
            registry.add("sso.providers.$PROVIDER_ID.client-id") { MockIdP.DEFAULT_CLIENT_ID }
            registry.add("sso.providers.$PROVIDER_ID.client-secret") { MockIdP.DEFAULT_CLIENT_SECRET }
            registry.add("sso.providers.$PROVIDER_ID.authorization-uri") { "${idpServer.baseUri}/mock-idp/authorize" }
            registry.add("sso.providers.$PROVIDER_ID.token-uri") { "${idpServer.baseUri}/mock-idp/token" }
            registry.add("sso.providers.$PROVIDER_ID.jwks-uri") { "${idpServer.baseUri}/mock-idp/jwks" }
        }

        @JvmStatic
        @AfterAll
        fun stopIdpServer() {
            idpServer.stop()
        }

        private val ssoIpCounter = AtomicInteger()

        private const val PROVIDER_ID = "mock-idp"

        private const val PASSWORD = "Str0ng-Pwd-Change-IT-Pass!"

        private const val NEW_PASSWORD = "Pwd-Change-N3w-IT-Pass!"

        private const val ARGON2_PREFIX = "{argon2}"

        private const val ACCESS_TTL_SECONDS = 300

        private const val MINUTE_SECONDS = 60

        // research 008b §6 defaults, bound to auth.ratelimit.password-change.*
        private const val USER_PER_MINUTE = 5

        private const val IP_PER_MINUTE = 10

        private const val TOKEN_43 = "[A-Za-z0-9_-]{43}"

        private const val X_FORWARDED_FOR = "X-Forwarded-For"

        private const val DENYLIST_KEY_PREFIX = "auth:denylist:sid:"

        private const val USER_BUCKET_KEY_PREFIX = "rl:user:pwdchange:"

        private const val IP_BUCKET_KEY_PREFIX = "rl:ip:password-change:"

        private const val SUBJECT_JIT = "subject-pcjit-7k2n"

        private const val EMAIL_JIT = "pcjit.idp@example.com"

        private const val SUBJECT_SSO = "subject-pcsso-9m4t"

        private const val EMAIL_SSO = "pcsso.idp@example.com"

        private const val SSO_IP_PREFIX = "198.18.28."

        private const val STATE_PARAMETER = "state"

        private const val CODE_PARAMETER = "code"

        private const val REGISTER_PATH = "/api/v1/auth/register"

        private const val CONFIRM_PATH = "/api/v1/auth/register/confirm"

        private const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"

        private const val LOGIN_PATH = "/api/v1/auth/login"

        private const val REFRESH_PATH = "/api/v1/auth/refresh"

        private const val PASSWORD_CHANGE_PATH = "/api/v1/auth/password-change"

        private const val SSO_AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"

        private const val SSO_CALLBACK_PATH = "/api/v1/auth/sso/callback"

        private const val SSO_TOKEN_PATH = "/api/v1/auth/sso/token"

        private const val USERS_ME_PATH = "/api/v1/users/me"
    }
}

/**
 * Loopback HTTP mount of the very same [MockIdP] controller class the SSO ITs
 * use (SsoFlowIT's MockIdPLoopbackServer precedent, reduced to the three OIDC
 * endpoints this suite drives): the provider endpoints must be absolute URIs
 * known while `@DynamicPropertySource` binds `sso.providers.*`, long before
 * the random application port becomes observable. Requests are translated
 * into [MockHttpServletRequest] and dispatched to the controller methods,
 * responses are translated back — byte-for-byte the same behavior as the
 * in-context bean, without a second Spring context.
 */
private class IdpLoopbackMount(
    private val idp: MockIdP,
) {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }

    private val server: HttpServer = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, 0), BACKLOG)

    /** Absolute base URI (e.g. `http://127.0.0.1:39291`) known before the Spring context starts. */
    val baseUri: String = "http://$LOOPBACK_HOST:${server.address.port}"

    init {
        server.createContext(ROOT_PATH) { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    fun stop() {
        server.stop(STOP_DELAY_SECONDS)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val response =
            try {
                route(exchange)
            } catch (cause: Exception) {
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(cause.toString())
            }
        respond(exchange, response)
        exchange.close()
    }

    @Suppress("ReturnCount") // each branch is one routed IdP endpoint
    private fun route(exchange: HttpExchange): ResponseEntity<String> {
        val path = exchange.requestURI.path
        return when {
            exchange.requestMethod == GET_METHOD && path == AUTHORIZE_PATH -> idp.authorize(toServletRequest(exchange))
            exchange.requestMethod == POST_METHOD && path == TOKEN_PATH -> idp.token(toServletRequest(exchange))
            exchange.requestMethod == GET_METHOD && path == JWKS_PATH ->
                ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(idp.jwks())
            else -> ResponseEntity.notFound().build()
        }
    }

    private fun toServletRequest(exchange: HttpExchange): MockHttpServletRequest {
        val request = MockHttpServletRequest(exchange.requestMethod, exchange.requestURI.toString())
        exchange.requestHeaders.forEach { (name, values) -> values.forEach { request.addHeader(name, it) } }
        addParametersFrom(request, exchange.requestURI.rawQuery)
        addParametersFrom(request, exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
        return request
    }

    /** Splits a raw query/form string into decoded parameters — the IdP reads every input via getParameter. */
    private fun addParametersFrom(
        request: MockHttpServletRequest,
        raw: String?,
    ) {
        if (raw.isNullOrEmpty()) return
        raw.split(AMPERSAND).forEach { pair ->
            val separator = pair.indexOf(EQUALS_SIGN)
            if (separator <= 0) return@forEach
            val name = pair.substring(0, separator)
            val value = pair.substring(separator + 1)
            request.addParameter(
                java.net.URLDecoder.decode(name, StandardCharsets.UTF_8),
                java.net.URLDecoder.decode(value, StandardCharsets.UTF_8),
            )
        }
    }

    private fun respond(
        exchange: HttpExchange,
        response: ResponseEntity<String>,
    ) {
        response.headers.location?.let { exchange.responseHeaders.set(HttpHeaders.LOCATION, it.toString()) }
        val body = response.body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        exchange.responseHeaders.set(
            HttpHeaders.CONTENT_TYPE,
            response.headers.contentType?.toString() ?: MediaType.APPLICATION_JSON_VALUE,
        )
        exchange.sendResponseHeaders(
            response.statusCode.value(),
            if (body.isEmpty()) NO_BODY else body.size.toLong(),
        )
        if (body.isNotEmpty()) exchange.responseBody.use { stream -> stream.write(body) }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        const val ROOT_PATH = "/"

        const val AUTHORIZE_PATH = "/mock-idp/authorize"

        const val TOKEN_PATH = "/mock-idp/token"

        const val JWKS_PATH = "/mock-idp/jwks"

        const val THREAD_NAME = "pc-it-idp-loopback"

        const val BACKLOG = 0

        const val STOP_DELAY_SECONDS = 0

        const val NO_BODY = -1L

        const val GET_METHOD = "GET"

        const val POST_METHOD = "POST"

        const val AMPERSAND = "&"

        const val EQUALS_SIGN = '='
    }
}
