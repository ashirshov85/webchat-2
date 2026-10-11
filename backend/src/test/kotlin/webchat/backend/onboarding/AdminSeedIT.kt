package webchat.backend.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import webchat.backend.AbstractIntegrationTest
import java.sql.DriverManager
import java.util.UUID

/**
 * T026 [US4] (tasks.md 008b Phase 6, Test-First): the idempotent admin seed
 * of `AdminSeedRunner` (FR-011–FR-012 anchor, research 008b §1, data-model
 * 008b §1.3, quickstart §US4 scenario 1). Written BEFORE the implementation
 * (T028/T030) — until `AdminSeedProperties` and the runner land, every phase
 * fails (RED) by design: no `admin` row appears and no `admin_seed_total`
 * counters exist, while compilation stays green because the suite drives the
 * application purely through its boot-time behavior, the database, the
 * metrics registry and the 002 login endpoint (constitution VI).
 *
 * Covered acceptance:
 * - first start without an admin → EXACTLY ONE active `admin` with the
 *   configured email, `display_name='Administrator'` and
 *   `starter_contact_added = TRUE` (the account is never an onboarding
 *   target itself, data-model 008b §1.1);
 * - no `admin.seed.password` → the passwordless JIT form of 003: active with
 *   `password_hash IS NULL` and no `password_set_at`, while the confirmed
 *   email keeps `ck_users_active_implies_email` (V9) satisfied; password
 *   login against it stays the timing-safe uniform 401 "Invalid credentials"
 *   (research 008b §1);
 * - repeated application start → no duplicates, no overwrite: the row stays
 *   byte-identical and the boot is answered with the `skipped` outcome;
 * - FR-012 no-overwrite guarantee: an ops-side drift of the row (email,
 *   display name, hash set through the ordinary 002/008b flows) survives the
 *   next start untouched — the seed never executes an UPDATE;
 * - occupied username (a foreign legacy account already holds `admin`) →
 *   warn-level skip, the legacy row not touched by a single column, nothing
 *   created under the seed email;
 * - occupied seed email (username free, a foreign account holds the email) →
 *   the `ON CONFLICT DO NOTHING` insert is still swallowed (expression
 *   unique indexes of V1:23-24), no admin row appears, the holder untouched;
 * - observability (data-model 008b §1.3): `admin_seed_total{outcome=…}` —
 *   `created` exactly once on a seeding boot, `skipped` on every other.
 *
 * "Повторный старт приложения" is approximated the RateLimitIT US5-2 way:
 * `@DirtiesContext` rebuilds the Spring context (a fresh application over
 * the SAME static containers — the runner re-executes against the very same
 * database); a literal second backend instance is deliberately not started.
 * The ordered phases hand over state through companion snapshots and stage
 * their successor's database fixture at the tail (restart happens between
 * the methods, after the fixture is in place).
 *
 * Isolation: once T030 lands, EVERY context of the IT JVM runs the seed
 * runner (gate defaults to enabled), so whatever suite booted before this
 * class may have already seeded `admin` under the dev-default email. The
 * companion therefore purges every seed footprint over a raw JDBC link
 * BEFORE this class's context boots (exactly once per JVM; the schema probe
 * keeps the very first boot of the JVM — Flyway not yet applied — safe).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AdminSeedIT(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val meterRegistry: MeterRegistry,
) : AbstractIntegrationTest() {
    @Test
    @Order(1)
    @DirtiesContext // rebuild before Order(2) = the "repeated application start"
    fun `first start without an admin seeds exactly one active passwordless account`() {
        val admins = userRows(jdbcTemplate, ADMIN_USERNAME)
        assertThat(admins)
            .overridingErrorMessage(
                "application start must seed exactly one active 'admin' (research 008b §1), got: %s",
                admins,
            ).hasSize(1)
        val admin = admins.single()
        assertThat(admin["email"]).isEqualTo(PASSWORDLESS_SEED_EMAIL)
        assertThat(admin["status"]).isEqualTo("active")
        assertThat(admin["display_name"]).isEqualTo("Administrator")
        assertThat(admin["starter_contact_added"]).isEqualTo(true)
        // the passwordless JIT form of 003 (data-model 008b §1.3): active
        // with a NULL hash and no password_set_at, confirmed email keeps
        // ck_users_active_implies_email (V9) satisfied
        assertThat(admin["password_hash"]).isNull()
        assertThat(admin["password_set"]).isEqualTo(false)
        assertThat(admin["email_confirmed"]).isEqualTo(true)
        assertThat(userCountByEmail(jdbcTemplate, PASSWORDLESS_SEED_EMAIL)).isEqualTo(1)

        // research 008b §1: password login against the passwordless form is
        // the timing-safe uniform 401 — the password-less state is not disclosed
        val login = loginByPassword(restTemplate, ADMIN_USERNAME, LOGIN_PROBE_PASSWORD, SEED_LOGIN_IP)
        assertThat(login.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(objectMapper.readTree(login.body)["detail"].asText()).isEqualTo("Invalid credentials")

        // observability (data-model 008b §1.3): one created outcome on this context's registry
        assertThat(seedOutcomeCount(meterRegistry, "created")).isEqualTo(OUTCOME_ONCE)
        assertThat(seedOutcomeCount(meterRegistry, "skipped")).isEqualTo(OUTCOME_ABSENT)

        rowAfterFirstBoot = admin
    }

    @Test
    @Order(2)
    @DirtiesContext // rebuild before Order(3): the drifted row faces the next start
    fun `repeated start keeps the single admin row unchanged and skips`() {
        // FR-012 («без дублей и перезаписи»): the rebuilt application ran the
        // seed again over the SAME database — exactly one, byte-identical row
        assertThat(userRows(jdbcTemplate, ADMIN_USERNAME))
            .overridingErrorMessage(
                "a repeated application start must not duplicate or rewrite the admin row",
            ).isEqualTo(listOf(rowAfterFirstBoot))
        assertThat(seedOutcomeCount(meterRegistry, "created")).isEqualTo(OUTCOME_ABSENT)
        assertThat(seedOutcomeCount(meterRegistry, "skipped")).isEqualTo(OUTCOME_ONCE)

        // ops-side drift (email changed, password set through the ordinary
        // 002/008b flows) — the next start must NOT reset any of it
        jdbcTemplate.update(
            DRIFT_SQL,
            DRIFT_EMAIL,
            DRIFT_DISPLAY_NAME,
            DRIFT_HASH,
        )
        val drifted = userRows(jdbcTemplate, ADMIN_USERNAME).single()
        assertThat(drifted["email"]).isEqualTo(DRIFT_EMAIL)
        assertThat(drifted["password_hash"]).isEqualTo(DRIFT_HASH)
        rowAfterManualDrift = drifted
    }

    @Test
    @Order(3)
    @DirtiesContext // rebuild before Order(4): the username 'admin' gets occupied
    fun `manually drifted admin data is never corrected by the next start`() {
        // the seed never executes an UPDATE (research 008b §1): the drift survived
        assertThat(userRows(jdbcTemplate, ADMIN_USERNAME)).isEqualTo(listOf(rowAfterManualDrift))
        // the restart must not have "re-created" anything under the seed email either
        assertThat(userCountByEmail(jdbcTemplate, PASSWORDLESS_SEED_EMAIL)).isZero
        assertThat(seedOutcomeCount(meterRegistry, "created")).isEqualTo(OUTCOME_ABSENT)
        assertThat(seedOutcomeCount(meterRegistry, "skipped")).isEqualTo(OUTCOME_ONCE)

        // fixture: a foreign legacy account already holds the username
        deleteAdmin(jdbcTemplate)
        insertActiveUser(jdbcTemplate, UUID.randomUUID(), ADMIN_USERNAME, LEGACY_EMAIL, LEGACY_DISPLAY_NAME)
        legacyOccupierRow = userRows(jdbcTemplate, ADMIN_USERNAME).single()
    }

    @Test
    @Order(4)
    @DirtiesContext // rebuild before Order(5): the seed EMAIL gets occupied
    fun `occupied username skips seeding and leaves the legacy account untouched`() {
        // exactly the legacy account — not a single column rewritten, and the
        // warn-level skip is observable (data-model 008b §1.3: занято — warn)
        assertThat(userRows(jdbcTemplate, ADMIN_USERNAME)).isEqualTo(listOf(legacyOccupierRow))
        assertThat(userCountByEmail(jdbcTemplate, PASSWORDLESS_SEED_EMAIL)).isZero
        assertThat(seedOutcomeCount(meterRegistry, "created")).isEqualTo(OUTCOME_ABSENT)
        assertThat(seedOutcomeCount(meterRegistry, "skipped")).isEqualTo(OUTCOME_ONCE)

        // fixture: the username is free again, but a foreign account holds the seed email
        deleteAdmin(jdbcTemplate)
        insertActiveUser(jdbcTemplate, UUID.randomUUID(), HOLDER_USERNAME, PASSWORDLESS_SEED_EMAIL, HOLDER_DISPLAY_NAME)
        seedEmailHolderRow = userRows(jdbcTemplate, HOLDER_USERNAME).single()
    }

    @Test
    @Order(5)
    fun `occupied seed email also skips seeding and leaves the holder untouched`() {
        // username was FREE, yet the email conflict alone swallows the INSERT
        // (ON CONFLICT DO WITHOUT arbiter — the expression unique indexes of
        // V1:23-24 fire on lower(email) just the same): no admin row appears
        assertThat(userRows(jdbcTemplate, ADMIN_USERNAME)).isEmpty()
        assertThat(userRows(jdbcTemplate, HOLDER_USERNAME)).isEqualTo(listOf(seedEmailHolderRow))
        assertThat(seedOutcomeCount(meterRegistry, "created")).isEqualTo(OUTCOME_ABSENT)
        assertThat(seedOutcomeCount(meterRegistry, "skipped")).isEqualTo(OUTCOME_ONCE)
    }

    companion object {
        init {
            purgeSeedFootprint(listOf(PASSWORDLESS_SEED_EMAIL, PASSWORD_SEED_EMAIL))
        }

        @JvmStatic
        @DynamicPropertySource
        fun passwordlessSeedProperties(registry: DynamicPropertyRegistry) {
            registry.add("admin.seed.enabled") { "true" }
            registry.add("admin.seed.email") { PASSWORDLESS_SEED_EMAIL }
            // no admin.seed.password on purpose: the passwordless JIT form (research 008b §1)
        }

        private var rowAfterFirstBoot: Map<String, Any>? = null

        private var rowAfterManualDrift: Map<String, Any>? = null

        private var legacyOccupierRow: Map<String, Any>? = null

        private var seedEmailHolderRow: Map<String, Any>? = null

        private const val DRIFT_EMAIL = "admin-drifted@webchat-it.example"

        private const val DRIFT_DISPLAY_NAME = "Root Operator"

        private const val DRIFT_HASH = "{argon2}\$argon2id\$v=19\$manual-drift-placeholder-never-rewritten"

        private const val LEGACY_EMAIL = "legacy-admin@webchat-it.example"

        private const val LEGACY_DISPLAY_NAME = "Legacy Admin"

        private const val HOLDER_USERNAME = "seedmail.holder"

        private const val HOLDER_DISPLAY_NAME = "Seed Mail Holder"

        private const val SEED_LOGIN_IP = "198.18.29.10"

        private const val LOGIN_PROBE_PASSWORD = "Any-Admin-Seed-Guess-42!"
    }
}

/**
 * The `ADMIN_SEED_PASSWORD` leg of T026: a context started WITH the seed
 * password (the env-shaped property differs at boot time, hence a separate
 * context of the same file) must produce an Argon2id hash — the very encoder
 * of 002 — bound to the configured secret, with `password_set_at` set, and
 * the seeded password must actually open the 002 login (data-model 008b
 * §1.3, research 008b §1). RED until T028/T030 land, for the same reasons
 * as [AdminSeedIT].
 */
class AdminSeedWithPasswordIT(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val meterRegistry: MeterRegistry,
    @Autowired private val passwordEncoder: PasswordEncoder,
) : AbstractIntegrationTest() {
    @Test
    fun `start with a seed password creates an argon2id admin that can log in`() {
        val admins = userRows(jdbcTemplate, ADMIN_USERNAME)
        assertThat(admins)
            .overridingErrorMessage(
                "application start must seed exactly one 'admin' (research 008b §1), got: %s",
                admins,
            ).hasSize(1)
        val admin = admins.single()
        assertThat(admin["email"]).isEqualTo(PASSWORD_SEED_EMAIL)
        assertThat(admin["status"]).isEqualTo("active")
        assertThat(admin["display_name"]).isEqualTo("Administrator")
        assertThat(admin["starter_contact_added"]).isEqualTo(true)
        assertThat(admin["email_confirmed"]).isEqualTo(true)
        assertThat(admin["password_set"]).isEqualTo(true)

        // the hash is a real Argon2id value of the configured secret — and
        // never the open password itself (SC-004: 0 open secrets in PG)
        val hash = admin["password_hash"] as String
        assertThat(hash).startsWith(ARGON2_PREFIX)
        assertThat(hash).doesNotContain(SEED_PASSWORD)
        assertThat(passwordEncoder.matches(SEED_PASSWORD, hash)).isTrue

        assertThat(seedOutcomeCount(meterRegistry, "created")).isEqualTo(OUTCOME_ONCE)
        assertThat(seedOutcomeCount(meterRegistry, "skipped")).isEqualTo(OUTCOME_ABSENT)

        // end-to-end: the seeded Argon2id hash really opens the 002 login
        val login = loginByPassword(restTemplate, ADMIN_USERNAME, SEED_PASSWORD, PASSWORD_SEED_LOGIN_IP)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(login.body)["accessToken"].asText()).isNotEmpty
    }

    companion object {
        init {
            purgeSeedFootprint(listOf(PASSWORDLESS_SEED_EMAIL, PASSWORD_SEED_EMAIL))
        }

        @JvmStatic
        @DynamicPropertySource
        fun passwordSeedProperties(registry: DynamicPropertyRegistry) {
            registry.add("admin.seed.enabled") { "true" }
            registry.add("admin.seed.email") { PASSWORD_SEED_EMAIL }
            registry.add("admin.seed.password") { SEED_PASSWORD }
        }

        private const val SEED_PASSWORD = "Adm1n-Seedit-P4ss!"

        private const val PASSWORD_SEED_LOGIN_IP = "198.18.29.20"
    }
}

// --- shared plumbing of the two seed contexts (file-private) ---------------

/** The seeded system username (research 008b §1; passes ck_users_username_format, V1:17-18). */
private const val ADMIN_USERNAME = "admin"

/** The admin.seed.email of the passwordless context. */
private const val PASSWORDLESS_SEED_EMAIL = "admin-seed@webchat-it.example"

/** The admin.seed.email of the with-password context. */
private const val PASSWORD_SEED_EMAIL = "admin-pwd-seed@webchat-it.example"

/** The DelegatingPasswordEncoder id prefix of the 002 Argon2id hashes. */
private const val ARGON2_PREFIX = "{argon2}"

/** `admin_seed_total{outcome=…}` — pinned by research 008b §1 / data-model 008b §1.3. */
private const val ADMIN_SEED_METRIC = "admin_seed_total"

private const val OUTCOME_ONCE = 1.0

private const val OUTCOME_ABSENT = 0.0

private const val LOGIN_PATH = "/api/v1/auth/login"

private const val USER_ROWS_SQL =
    """
    SELECT id::text AS id, username, lower(email) AS email, password_hash, status::text AS status,
           display_name, starter_contact_added,
           (email_confirmed_at IS NOT NULL) AS email_confirmed,
           (password_set_at IS NOT NULL) AS password_set,
           created_at::text AS created_at, updated_at::text AS updated_at
    FROM users
    WHERE lower(username) = ?
    ORDER BY created_at, id
    """

private const val USER_COUNT_BY_EMAIL_SQL =
    """
    SELECT COUNT(*) FROM users WHERE lower(email) = ?
    """

private const val DELETE_ADMIN_SQL =
    """
    DELETE FROM users WHERE lower(username) = 'admin'
    """

private const val INSERT_OCCUPIER_SQL =
    """
    INSERT INTO users (id, username, email, password_hash, status, email_confirmed_at, password_set_at,
                       display_name, starter_contact_added)
    VALUES (?, ?, ?, ?, 'active', now(), now(), ?, FALSE)
    """

private const val DRIFT_SQL =
    """
    UPDATE users SET email = ?, display_name = ?, password_hash = ? WHERE lower(username) = 'admin'
    """

private const val SCHEMA_PROBE_SQL =
    """
    SELECT to_regclass('public.users') IS NOT NULL AS present
    """

private const val PURGE_SQL =
    """
    DELETE FROM users WHERE lower(username) = 'admin' OR lower(email) = ANY(?)
    """

/**
 * The full observable row of a user by (case-insensitive) username — the
 * snapshot shape shared by every phase-to-phase comparison; timestamps are
 * compared as text so the equality survives connection churn of the rebuilt
 * contexts.
 */
private fun userRows(
    jdbcTemplate: JdbcTemplate,
    username: String,
): List<Map<String, Any>> = jdbcTemplate.queryForList(USER_ROWS_SQL, username.lowercase())

private fun userCountByEmail(
    jdbcTemplate: JdbcTemplate,
    email: String,
): Int = jdbcTemplate.queryForObject(USER_COUNT_BY_EMAIL_SQL, Int::class.java, email.lowercase())

/** Cascades sessions/refresh tokens/identities/contacts (V3/V4/V9/V11); auth_events detach (SET NULL, V6). */
private fun deleteAdmin(jdbcTemplate: JdbcTemplate) {
    jdbcTemplate.update(DELETE_ADMIN_SQL)
}

/** A foreign ACTIVE account holding a username and/or email the seed would want (the "занято" fixtures). */
private fun insertActiveUser(
    jdbcTemplate: JdbcTemplate,
    id: UUID,
    username: String,
    email: String,
    displayName: String,
) {
    jdbcTemplate.update(
        INSERT_OCCUPIER_SQL,
        id,
        username,
        email,
        OCCUPIER_HASH,
        displayName,
    )
}

private fun loginByPassword(
    restTemplate: TestRestTemplate,
    identifier: String,
    password: String,
    xForwardedFor: String,
): ResponseEntity<String> {
    val headers =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Forwarded-For", xForwardedFor)
        }
    return restTemplate.postForEntity(
        LOGIN_PATH,
        HttpEntity(mapOf("identifier" to identifier, "password" to password), headers),
        String::class.java,
    )
}

/** The outcome count of the seed metric on THIS context's registry (0 when the counter does not exist yet). */
private fun seedOutcomeCount(
    meterRegistry: MeterRegistry,
    outcome: String,
): Double =
    meterRegistry
        .find(ADMIN_SEED_METRIC)
        .tag("outcome", outcome)
        .counter()
        ?.count()
        ?: OUTCOME_ABSENT

/**
 * Removes every seed footprint over a raw JDBC link BEFORE this file's
 * contexts boot (companion init = exactly once per class per JVM): once the
 * runner exists, ANY earlier-booted context of the shared-container JVM has
 * already run it under the dev-default email, and the sibling seed class of
 * this file leaves its own admin behind — both would silently flip these
 * suites' first boots to `skipped`. The schema probe keeps the very first
 * boot of the JVM (Flyway not applied yet, no `users` table) safe.
 */
private fun purgeSeedFootprint(emails: Iterable<String>) {
    DriverManager
        .getConnection(
            AbstractIntegrationTest.postgres.jdbcUrl,
            AbstractIntegrationTest.APP_DB_ROLE,
            AbstractIntegrationTest.APP_DB_ROLE,
        ).use { connection ->
            val schemaReady =
                connection.createStatement().use { statement ->
                    statement.executeQuery(SCHEMA_PROBE_SQL).use { rows -> rows.next() && rows.getBoolean("present") }
                }
            if (!schemaReady) return
            connection
                .prepareStatement(PURGE_SQL)
                .use { statement ->
                    val emailArray =
                        connection.createArrayOf("varchar", emails.map(String::lowercase).toTypedArray())
                    statement.setArray(1, emailArray)
                    statement.executeUpdate()
                }
        }
}

/** The placeholder hash of the foreign occupying accounts (opaque, never compared against a real encoder). */
private const val OCCUPIER_HASH = "{argon2}\$argon2id\$v=19\$pre-existing-account-placeholder"
