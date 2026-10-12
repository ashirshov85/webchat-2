package webchat.backend.onboarding

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
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder
import webchat.backend.AbstractIntegrationTest
import webchat.backend.sso.MockIdP
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * T027 [US4] (tasks.md 008b Phase 6, Test-First): the starter-contact
 * onboarding of a fresh registration — FR-011/FR-013 anchor, US4 AC2–AC5,
 * SC-003, data-model 008b §1.1/§1.4/§3.1–3.2, research 008b §2–3,
 * quickstart §US4 scenarios 2–6. Written BEFORE the implementation
 * (T029–T035) — until the `RegistrationCompletedEvent` publication points,
 * the `StarterContactEventListener` and the `GET /contacts` self-healing
 * fallback land, the behavioral assertions fail (RED) by design, while
 * compilation stays green because the suite drives the application purely
 * through its observable surfaces — the real 002 registration API, the
 * 003 SSO login, contracts №20/№22 of the contacts API, the database and
 * bounded polling (the AdminSeedIT precedent, constitution VI).
 *
 * Covered acceptance:
 * - a COMPLETED 002 registration (email confirmation + password) onboards
 *   `admin` into the fresh user's contacts EXACTLY ONCE — the row appears
 *   without any client action and the flag `starter_contact_added`
 *   latches TRUE (US4 AC2, data-model 008b §3.1);
 * - an SSO JIT registration (feature 003, `provisionJitAccount`) receives
 *   the same starter contact exactly once — the single point of account
 *   creation (spec.md:107 edge, US4 AC2);
 * - repeated deliveries never duplicate: a redelivery racing past the
 *   flag short-circuit collapses into the idempotent `INSERT … ON
 *   CONFLICT DO NOTHING` + conditional UPDATE (SC-003, research 008b §3);
 * - a LOST registration event is self-healed by the FIRST `GET /contacts`
 *   which adds admin exactly once (US4 AC4, FR-011);
 * - a missing admin (seed not executed / failed) keeps the registration
 *   and the contacts listing healthy while the flag stays FALSE
 *   (best-effort — research 008b §3, spec.md:111 edge); once the admin
 *   row appears again (seed restored), the NEXT listing onboards exactly
 *   once;
 * - a DELETED starter contact is never re-added by later logins or
 *   listings — the flag never resets (US4 AC5, FR-013, data-model 008b
 *   §1.1 «обратный переход запрещён»);
 * - accounts that predated the feature (`starter_contact_added = TRUE`
 *   set by the V17 migration) receive NO backfill (spec.md:109 edge),
 *   verified against a fresh account onboarded in the same test so the
 *   method stays RED until the fallback exists;
 * - the admin account never onboards ITSELF (spec.md:112 edge,
 *   research 008b §1): with the admin's own flag forced FALSE (the seed
 *   normally latches it TRUE — the short-circuit must not mask the
 *   guard), the fallback adds no `(admin, admin)` row.
 *
 * US4 AC6 (a dialog with admin is the ordinary 004 pair) has NO dedicated
 * method on purpose — no special code branches exist (tasks.md T027);
 * it is covered by the 004 regression in T037.
 *
 * Simulation notes (Test-First constraints):
 * - the `RegistrationCompletedEvent` class does not exist yet (T029), so
 *   a "repeated delivery" cannot be published literally. Its observable
 *   equivalent — a second `ensureOnboarded` run over the state the first
 *   delivery left (contact row present, flag momentarily FALSE) — is
 *   shaped by SQL and re-triggered through the same production entry
 *   point the fallback serves: every `GET /contacts` (research 008b §3:
 *   all races collapse in the idempotent insert + conditional update);
 * - an event "loss" is shaped the same way: after the bounded settle
 *   wait, the delivery's footprint (contact row + flag) is wiped, and
 *   the first listing must restore exactly one row;
 * - the admin row is controlled per test method: the idempotent
 *   seed-shape upsert of [insertSeedShapeAdmin] runs BEFORE EACH test,
 *   so the suite is immune to whatever [AdminSeedIT]'s cross-suite
 *   purges left in the shared containers and works identically with and
 *   without the seed runner (T030) — resolution is by
 *   `lower(username) = 'admin'`, the email never matters here.
 *
 * Isolation follows the RateLimitIT/PasswordChangeIT conventions: every
 * method grows its own users through the real 002 API with unique source
 * IPs from the dedicated 198.18.30.x documentation block (register allows
 * only 5 per IP per hour on the shared static Redis). The single SSO
 * scenario drives the MockIdP through a loopback mount (SsoFlowIT/Password
 * Change IT precedent) — the provider endpoints must be absolute URIs
 * known before the context starts for the `sso.providers.*` binding.
 */
@Suppress("LargeClass", "TooManyFunctions", "LongMethod")
class StarterContactIT(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val passwordEncoder: PasswordEncoder,
) : AbstractIntegrationTest() {
    @BeforeEach
    fun seedShapeAdmin() {
        mockIdP.reset()
        insertSeedShapeAdmin()
    }

    /** US4 AC2/FR-011: a completed 002 registration onboards admin exactly once. */
    @Test
    fun `completed password registration onboards the admin starter contact exactly once`() {
        val user = registerPasswordUser("sc002a")

        assertThat(awaitStarterContact(user.id))
            .overridingErrorMessage(
                "completing the 002 registration must asynchronously add the admin starter contact " +
                    "(FR-011, US4 AC2) — no row appeared within %ds",
                ONBOARDING_WAIT_SECONDS,
            ).isTrue

        // data-model 008b §1.1: exactly one latch, the flag monotone TRUE
        assertThat(starterContactCountOf(user.id))
            .overridingErrorMessage("the starter contact must exist exactly once (SC-003)")
            .isEqualTo(1)
        assertThat(onboardingFlagOf(user.id)).isTrue

        val admin = requireNotNull(adminRowId()) { "the seed-shape admin fixture must exist" }
        val first = listContacts(user.accessToken)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(first))
            .overridingErrorMessage(
                "the first contacts listing must show admin exactly once (US4 AC2), got <%s> in %s",
                adminEntries(first).map { it["user"]["id"].asText() },
                first.body,
            ).hasSize(1)
        assertThat(adminEntries(first).single()["user"]["id"].asText()).isEqualTo(admin.toString())

        // a repeated listing re-runs the short-circuited path — still exactly one
        assertThat(adminEntries(listContacts(user.accessToken))).hasSize(1)
        assertThat(starterContactCountOf(user.id)).isEqualTo(1)
    }

    /** spec.md:107 edge / US4 AC2: an SSO JIT registration gets the same starter contact exactly once. */
    @Test
    fun `jit registration over sso onboards the same starter contact exactly once`() {
        mockIdP.setClaims(MockIdP.ControlledClaims(subject = SUBJECT_JIT, email = EMAIL_JIT, emailVerified = true))

        val sso = performSsoLogin()
        val userId = UUID.fromString(sso.body["user"]["id"].asText())

        assertThat(awaitStarterContact(userId))
            .overridingErrorMessage(
                "an SSO JIT registration must asynchronously add the same admin starter contact " +
                    "(spec.md:107 edge, FR-011) — no row appeared within %ds",
                ONBOARDING_WAIT_SECONDS,
            ).isTrue
        assertThat(starterContactCountOf(userId)).isEqualTo(1)
        assertThat(onboardingFlagOf(userId)).isTrue

        val listing = listContacts(sso.accessToken)
        assertThat(listing.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(listing)).hasSize(1)
    }

    /** SC-003 / US4 AC3: redeliveries and repeated listings never duplicate the starter contact. */
    @Test
    fun `repeated deliveries and repeated listings never duplicate the starter contact`() {
        val user = registerPasswordUser("sc003a")
        assertThat(awaitStarterContact(user.id)).isTrue

        // (a) repeated listings — every GET re-enters ensureOnboarded; the
        // latched flag short-circuits and the row count never moves
        repeat(REPEAT_LISTINGS) {
            val listing = listContacts(user.accessToken)
            assertThat(listing.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(adminEntries(listing)).hasSize(1)
        }
        assertThat(starterContactCountOf(user.id)).isEqualTo(1)

        // (b) a redelivery racing past the flag: the contact row the first
        // delivery inserted is present while the flag reads FALSE (the state
        // a second listener run / a fallback would see) — the idempotent
        // insert + conditional update must collapse it to the same single row
        jdbcTemplate.update(RESET_FLAG_SQL, user.id)

        val afterRedelivery = listContacts(user.accessToken)
        assertThat(afterRedelivery.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(afterRedelivery))
            .overridingErrorMessage(
                "a repeated delivery must not duplicate the starter contact (SC-003), got <%s>",
                adminEntries(afterRedelivery).map { it["user"]["id"].asText() },
            ).hasSize(1)
        assertThat(starterContactCountOf(user.id)).isEqualTo(1)
        assertThat(onboardingFlagOf(user.id)).isTrue
    }

    /** US4 AC4/FR-011: a lost registration event is self-healed exactly once by the first listing. */
    @Test
    fun `a lost registration event is self-healed exactly once by the first contacts listing`() {
        val user = registerPasswordUser("sc004a")
        awaitOnboardingSettled(user.id) // fixture-only: tolerate both worlds, no assertion

        // the loss: whatever the event delivered never landed — flag FALSE, no rows
        jdbcTemplate.update(DELETE_USER_CONTACTS_SQL, user.id)
        jdbcTemplate.update(RESET_FLAG_SQL, user.id)
        assertThat(onboardingFlagOf(user.id)).isFalse
        assertThat(starterContactCountOf(user.id)).isZero

        val first = listContacts(user.accessToken)
        assertThat(first.statusCode)
            .overridingErrorMessage(
                "the self-healing fallback must never break the contacts listing (FR-011, best-effort), got <%s>: %s",
                first.statusCode,
                first.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(first))
            .overridingErrorMessage(
                "the FIRST listing after a lost event must self-heal admin exactly once (US4 AC4), got <%s> in %s",
                adminEntries(first).map { it["user"]["id"].asText() },
                first.body,
            ).hasSize(1)
        assertThat(starterContactCountOf(user.id)).isEqualTo(1)
        assertThat(onboardingFlagOf(user.id)).isTrue

        // exactly ONCE: the second listing adds nothing
        assertThat(adminEntries(listContacts(user.accessToken))).hasSize(1)
        assertThat(starterContactCountOf(user.id)).isEqualTo(1)
    }

    /**
     * spec.md:111 edge (research 008b §1/§3): with the seed missing, the
     * registration and the listing stay healthy and the flag stays FALSE
     * (best-effort, observable skip); once the admin row exists again
     * (the restored seed), the NEXT listing onboards exactly once.
     */
    @Test
    fun `a missing admin keeps listings healthy and the restored seed onboards on the next listing`() {
        deleteAdminRows()

        // the registration itself must not break while the admin is absent
        val user = registerPasswordUser("sc005a")
        awaitOnboardingSettled(user.id) // fixture-only: the event (if any) warned and skipped

        assertThat(onboardingFlagOf(user.id))
            .overridingErrorMessage(
                "a missing admin must leave the onboarding flag FALSE for a retry (research 008b §3, spec.md:111)",
            ).isFalse
        val broken = listContacts(user.accessToken)
        assertThat(broken.statusCode)
            .overridingErrorMessage(
                "the fallback must be best-effort: a listing without the admin stays a healthy 200, got <%s>: %s",
                broken.statusCode,
                broken.body,
            ).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(broken)).isEmpty()
        assertThat(onboardingFlagOf(user.id)).isFalse
        assertThat(starterContactCountOf(user.id)).isZero

        // the seed is restored (the observable outcome of the runner's next
        // success — its boot-time mechanics are AdminSeedIT's scope)
        insertSeedShapeAdmin()

        val healed = listContacts(user.accessToken)
        assertThat(healed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(healed))
            .overridingErrorMessage(
                "after the admin reappears the next listing must onboard exactly once (spec.md:111), got <%s> in %s",
                adminEntries(healed).map { it["user"]["id"].asText() },
                healed.body,
            ).hasSize(1)
        assertThat(starterContactCountOf(user.id)).isEqualTo(1)
        assertThat(onboardingFlagOf(user.id)).isTrue
        assertThat(adminEntries(listContacts(user.accessToken))).hasSize(1)
    }

    /** US4 AC5/FR-013: a deleted starter contact is never re-added; the flag never resets. */
    @Test
    fun `a deleted starter contact is never re-added by later logins or listings`() {
        val user = registerPasswordUser("sc006a")
        assertThat(awaitStarterContact(user.id)).isTrue
        val admin = requireNotNull(adminRowId()) { "the seed-shape admin fixture must exist" }

        assertThat(deleteContact(user.accessToken, admin).statusCode)
            .overridingErrorMessage("the starter contact is an ordinary 004 contact: deletion must answer 204")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(starterContactCountOf(user.id)).isZero

        // repeated logins and listings never resurrect it (US4 AC5)
        val reloggedToken = loginAccessToken(user.username)
        repeat(REPEAT_LISTINGS) {
            val listing = listContacts(reloggedToken)
            assertThat(listing.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(adminEntries(listing)).isEmpty()
        }
        assertThat(starterContactCountOf(user.id)).isZero
        assertThat(onboardingFlagOf(user.id))
            .overridingErrorMessage("deleting the contact must never reset the onboarding flag (data-model 008b §1.1)")
            .isTrue
    }

    /**
     * spec.md:109 edge: no backfill for accounts that predated the feature
     * (`starter_contact_added = TRUE` set by the V17 migration, never a
     * contact row) — pinned against a fresh account onboarded in the same
     * method so the suite stays RED until the fallback exists.
     */
    @Test
    fun `accounts predating the feature get no backfill while fresh accounts are onboarded`() {
        // the "predates V17" shape: the flag latched TRUE without any contact
        val migrated = registerPasswordUser("sc007a")
        awaitOnboardingSettled(migrated.id)
        jdbcTemplate.update(DELETE_USER_CONTACTS_SQL, migrated.id)
        jdbcTemplate.update(RAISE_FLAG_SQL, migrated.id)

        // the fresh account in the event-loss shape (the fallback's own case)
        val fresh = registerPasswordUser("sc007b")
        awaitOnboardingSettled(fresh.id)
        jdbcTemplate.update(DELETE_USER_CONTACTS_SQL, fresh.id)
        jdbcTemplate.update(RESET_FLAG_SQL, fresh.id)

        val migratedListing = listContacts(migrated.accessToken)
        assertThat(migratedListing.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(migratedListing))
            .overridingErrorMessage(
                "accounts that predated the feature must receive NO starter contact (spec.md:109, no backfill)",
            ).isEmpty()
        assertThat(starterContactCountOf(migrated.id)).isZero
        assertThat(onboardingFlagOf(migrated.id)).isTrue

        val freshListing = listContacts(fresh.accessToken)
        assertThat(adminEntries(freshListing))
            .overridingErrorMessage(
                "the fresh account must be onboarded by the fallback (FR-011), got <%s> in %s",
                adminEntries(freshListing).map { it["user"]["id"].asText() },
                freshListing.body,
            ).hasSize(1)

        // a repeated listing leaves the migrated account untouched
        assertThat(adminEntries(listContacts(migrated.accessToken))).isEmpty()
        assertThat(starterContactCountOf(migrated.id)).isZero
    }

    /**
     * spec.md:112 edge: the fallback never onboards the admin himself. The
     * admin's flag is forced FALSE first — the seed normally latches it TRUE
     * (data-model 008b §1.1) and the short-circuit would mask the guard.
     * The positive control (an ordinary user onboarded by the same fallback)
     * keeps the method RED until the implementation lands.
     */
    @Test
    fun `the fallback onboards ordinary users but never the admin himself`() {
        // positive control: the ordinary user's loss-shaped listing onboards
        val ordinary = registerPasswordUser("sc008a")
        awaitOnboardingSettled(ordinary.id)
        jdbcTemplate.update(DELETE_USER_CONTACTS_SQL, ordinary.id)
        jdbcTemplate.update(RESET_FLAG_SQL, ordinary.id)
        assertThat(adminEntries(listContacts(ordinary.accessToken)))
            .overridingErrorMessage("the ordinary user must be onboarded by the fallback (FR-011)")
            .hasSize(1)

        // the admin himself, with the guard exposed (flag FALSE)
        val admin = requireNotNull(adminRowId()) { "the seed-shape admin fixture must exist" }
        jdbcTemplate.update(RESET_FLAG_SQL, admin)
        grantAdminPassword()

        val adminToken = loginAccessToken(ADMIN_USERNAME, ADMIN_PASSWORD)
        val selfListing = listContacts(adminToken)
        assertThat(selfListing.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(adminEntries(selfListing))
            .overridingErrorMessage("the admin account must never add itself as a contact (spec.md:112)")
            .isEmpty()
        assertThat(selfRowCount()).isZero
    }

    // --- 002 fixtures (PasswordChangeIT conventions) ------------------------

    /** A fully registered, logged-in fixture user grown through the real 002 API. */
    private data class FixtureUser(
        val id: UUID,
        val username: String,
        val email: String,
        val accessToken: String,
    )

    /** register → email confirmation from the outbox → set password → login (US4 AC2 path). */
    private fun registerPasswordUser(label: String): FixtureUser {
        val username = "$label-${UUID.randomUUID().toString().substring(0, UUID_SUFFIX_LENGTH)}"
        val email = "$username@example.com"
        val ip = nextIp()

        val registration = postJson(REGISTER_PATH, mapOf("username" to username, "email" to email), ip = ip)
        assertThat(registration.statusCode)
            .overridingErrorMessage("the fixture registration of <%s> must be accepted", username)
            .isEqualTo(HttpStatus.ACCEPTED)
        val confirmation = postJson(CONFIRM_PATH, mapOf("token" to extractVerificationToken(email)), ip = ip)
        assertThat(confirmation.statusCode)
            .overridingErrorMessage("email confirmation for fixture user <%s> must succeed", username)
            .isEqualTo(HttpStatus.OK)
        val setupToken = objectMapper.readTree(confirmation.body)["setupToken"].asText()
        val passwordSetup =
            postJson(
                SET_PASSWORD_PATH,
                mapOf("password" to USER_PASSWORD, "confirmPassword" to USER_PASSWORD, "setupToken" to setupToken),
                ip = ip,
            )
        assertThat(passwordSetup.statusCode)
            .overridingErrorMessage("password setup for fixture user <%s> must succeed", username)
            .isEqualTo(HttpStatus.NO_CONTENT)

        val rowId =
            jdbcTemplate
                .queryForList(
                    "SELECT id FROM users WHERE lower(username) = ?",
                    UUID::class.java,
                    username.lowercase(),
                ).firstOrNull()
        return FixtureUser(
            id = requireNotNull(rowId) { "the fixture user must exist after registration" },
            username = username,
            email = email,
            accessToken = loginAccessToken(username),
        )
    }

    /** Successful 002 login → the fresh access token (a later "repeated login"). */
    private fun loginAccessToken(
        identifier: String,
        password: String = USER_PASSWORD,
    ): String {
        val response = postJson(LOGIN_PATH, mapOf("identifier" to identifier, "password" to password), ip = nextIp())
        assertThat(response.statusCode)
            .overridingErrorMessage(
                "fixture login of <%s> must succeed, got <%s>: %s",
                identifier,
                response.statusCode,
                response.body,
            ).isEqualTo(HttpStatus.OK)
        return objectMapper.readTree(response.body)["accessToken"].asText()
    }

    private fun postJson(
        path: String,
        payload: Map<String, String>,
        accessToken: String? = null,
        ip: String? = null,
    ): ResponseEntity<String> =
        restTemplate.postForEntity(
            path,
            HttpEntity(payload, authHeaders(accessToken, ip)),
            String::class.java,
        )

    private fun authHeaders(
        accessToken: String? = null,
        ip: String? = null,
    ): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accessToken?.let { set(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            ip?.let { set(X_FORWARDED_FOR, it) }
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

    // --- contracts №20/№22 of the contacts API ------------------------------

    private fun listContacts(accessToken: String): ResponseEntity<String> =
        restTemplate.exchange(
            CONTACTS_PATH,
            HttpMethod.GET,
            HttpEntity<Unit>(null, authHeaders(accessToken = accessToken)),
            String::class.java,
        )

    private fun deleteContact(
        accessToken: String,
        userId: UUID,
    ): ResponseEntity<String> =
        restTemplate.exchange(
            "$CONTACTS_PATH/$userId",
            HttpMethod.DELETE,
            HttpEntity<Unit>(null, authHeaders(accessToken = accessToken)),
            String::class.java,
        )

    /** The listing entries whose contact user is the system admin. */
    private fun adminEntries(response: ResponseEntity<String>): List<JsonNode> =
        objectMapper
            .readTree(response.body)["contacts"]
            .filter { it["user"]["username"].asText() == ADMIN_USERNAME }

    // --- SSO helpers (the SsoFlowIT/PasswordChangeIT loopback-MockIdP way) --

    private data class SsoLoginResult(
        val accessToken: String,
        val body: JsonNode,
    )

    private fun performSsoLogin(): SsoLoginResult {
        val authorizeResponse =
            postJson(SSO_AUTHORIZE_PATH, mapOf("providerId" to PROVIDER_ID), ip = nextIp())
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

        val tokenResponse = postJson(SSO_TOKEN_PATH, mapOf(CODE_PARAMETER to handshakeCode), ip = nextIp())
        assertThat(tokenResponse.statusCode).isEqualTo(HttpStatus.OK)
        val body = objectMapper.readTree(tokenResponse.body)
        return SsoLoginResult(body["accessToken"].asText(), body)
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

    // --- database fixtures and inspection -----------------------------------

    /**
     * The idempotent seed-shape row of data-model 008b §1.3 — the very
     * statement family of the runner (`ON CONFLICT DO NOTHING`, no arbiter:
     * the expression unique indexes of V1:23-24). Passwordless JIT form; the
     * password for the self-guard login is granted separately, the 002/008b
     * drift way (research 008b §1).
     */
    private fun insertSeedShapeAdmin() {
        jdbcTemplate.update(
            INSERT_SEED_ADMIN_SQL,
            UUID.randomUUID(),
            SEED_ADMIN_EMAIL,
        )
    }

    /** Cascades sessions/refresh tokens/identities/contacts (V3/V4/V9/V11); auth_events detach (SET NULL, V6). */
    private fun deleteAdminRows() {
        jdbcTemplate.update(DELETE_ADMIN_SQL)
    }

    private fun grantAdminPassword() {
        jdbcTemplate.update(
            GRANT_ADMIN_PASSWORD_SQL,
            passwordEncoder.encode(ADMIN_PASSWORD),
        )
    }

    private fun adminRowId(): UUID? =
        jdbcTemplate
            .queryForList("SELECT id FROM users WHERE lower(username) = ?", UUID::class.java, ADMIN_USERNAME)
            .firstOrNull()

    /** The rows `(owner, admin)` — the invariant of SC-003 is 0 or 1, never more. */
    private fun starterContactCountOf(userId: UUID): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM user_contacts uc
            JOIN users a ON a.id = uc.contact_user_id
            WHERE uc.owner_id = ? AND lower(a.username) = ?
            """.trimIndent(),
            Int::class.java,
            userId,
            ADMIN_USERNAME,
        )

    private fun onboardingFlagOf(userId: UUID): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT starter_contact_added FROM users WHERE id = ?",
            Boolean::class.java,
            userId,
        )

    /** The forbidden self-row `(admin, admin)` (also backstopped by ck_user_contacts_not_self, V11). */
    private fun selfRowCount(): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM user_contacts
            WHERE owner_id = (SELECT id FROM users WHERE lower(username) = ?)
              AND contact_user_id = (SELECT id FROM users WHERE lower(username) = ?)
            """.trimIndent(),
            Int::class.java,
            ADMIN_USERNAME,
            ADMIN_USERNAME,
        )

    /**
     * ASSERTION-side wait: polls until the starter contact row is visible —
     * the async AFTER_COMMIT listener of research 008b §2 must land within
     * the bound; a timeout returns FALSE and the caller's assertion turns it
     * into the RED message (constitution VI).
     */
    private fun awaitStarterContact(userId: UUID): Boolean {
        val deadline = System.nanoTime() + ONBOARDING_WAIT_SECONDS * NANOS_PER_SECOND
        while (System.nanoTime() < deadline) {
            if (starterContactCountOf(userId) > 0) return true
            Thread.sleep(POLL_MILLIS)
        }
        return starterContactCountOf(userId) > 0
    }

    /**
     * FIXTURE-side wait: bounds the async delivery so a SQL reset cannot race
     * a still-pending listener; NEVER asserts — pre-implementation (or with
     * the admin absent) nothing ever settles and the fixture proceeds in the
     * shaped state either way.
     */
    private fun awaitOnboardingSettled(userId: UUID): Boolean {
        val deadline = System.nanoTime() + SETTLE_WAIT_SECONDS * NANOS_PER_SECOND
        while (System.nanoTime() < deadline) {
            if (onboardingFlagOf(userId)) return true
            Thread.sleep(POLL_MILLIS)
        }
        return onboardingFlagOf(userId)
    }

    private fun nextIp(): String = "$IP_PREFIX${ipCounter.incrementAndGet()}"

    companion object {
        /** The same MockIdP controller class as the SSO ITs, driven directly by this suite. */
        val mockIdP = MockIdP(ObjectMapper())

        /**
         * Loopback mount of the MockIdP whose base URI is known BEFORE the
         * Spring context starts — the precondition for binding
         * `sso.providers.*` to absolute IdP endpoints (SsoFlowIT precedent).
         */
        private val idpServer = StarterIdpLoopbackMount(mockIdP)

        @JvmStatic
        @DynamicPropertySource
        fun ssoProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("sso.providers.$PROVIDER_ID.display-name") { "Starter Contact IT IdP" }
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

        private val ipCounter = AtomicInteger()

        /** The system username resolved by the onboarding (research 008b §1). */
        private const val ADMIN_USERNAME = "admin"

        /** This suite's own seed email — resolution is by username, the email never matters here. */
        private const val SEED_ADMIN_EMAIL = "starter-admin@webchat-it.example"

        private const val ADMIN_PASSWORD = "Adm-Starter-IT-Seed-Pass!"

        private const val USER_PASSWORD = "Sc-Starter-IT-User-Pass!"

        // research 008b §6-adjacent isolation: the dedicated 198.18.30.x block
        // (register allows 5/IP/hour on the shared static Redis — unique per call)
        private const val IP_PREFIX = "198.18.30."

        private const val UUID_SUFFIX_LENGTH = 8

        private const val ONBOARDING_WAIT_SECONDS = 10L

        private const val SETTLE_WAIT_SECONDS = 3L

        private const val POLL_MILLIS = 100L

        private const val NANOS_PER_SECOND = 1_000_000_000L

        private const val REPEAT_LISTINGS = 3

        private const val PROVIDER_ID = "starter-idp"

        private const val SUBJECT_JIT = "subject-scjit-4r8t"

        private const val EMAIL_JIT = "scjit.idp@example.com"

        private const val X_FORWARDED_FOR = "X-Forwarded-For"

        private const val STATE_PARAMETER = "state"

        private const val CODE_PARAMETER = "code"

        private const val REGISTER_PATH = "/api/v1/auth/register"

        private const val CONFIRM_PATH = "/api/v1/auth/register/confirm"

        private const val SET_PASSWORD_PATH = "/api/v1/auth/register/password"

        private const val LOGIN_PATH = "/api/v1/auth/login"

        private const val SSO_AUTHORIZE_PATH = "/api/v1/auth/sso/authorize"

        private const val SSO_CALLBACK_PATH = "/api/v1/auth/sso/callback"

        private const val SSO_TOKEN_PATH = "/api/v1/auth/sso/token"

        private const val CONTACTS_PATH = "/api/v1/contacts"

        private const val INSERT_SEED_ADMIN_SQL =
            """
            INSERT INTO users (id, username, email, password_hash, status, email_confirmed_at, password_set_at,
                               display_name, starter_contact_added)
            VALUES (?, 'admin', ?, NULL, 'active', now(), NULL, 'Administrator', TRUE)
            ON CONFLICT DO NOTHING
            """

        private const val DELETE_ADMIN_SQL =
            """
            DELETE FROM users WHERE lower(username) = 'admin'
            """

        private const val GRANT_ADMIN_PASSWORD_SQL =
            """
            UPDATE users SET password_hash = ?, password_set_at = now() WHERE lower(username) = 'admin'
            """

        private const val DELETE_USER_CONTACTS_SQL =
            """
            DELETE FROM user_contacts WHERE owner_id = ?
            """

        private const val RESET_FLAG_SQL =
            """
            UPDATE users SET starter_contact_added = FALSE WHERE id = ?
            """

        private const val RAISE_FLAG_SQL =
            """
            UPDATE users SET starter_contact_added = TRUE WHERE id = ?
            """
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
private class StarterIdpLoopbackMount(
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
                URLDecoder.decode(name, StandardCharsets.UTF_8),
                URLDecoder.decode(value, StandardCharsets.UTF_8),
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

        const val THREAD_NAME = "sc-it-idp-loopback"

        const val BACKLOG = 0

        const val STOP_DELAY_SECONDS = 0

        const val NO_BODY = -1L

        const val GET_METHOD = "GET"

        const val POST_METHOD = "POST"

        const val AMPERSAND = "&"

        const val EQUALS_SIGN = '='
    }
}
