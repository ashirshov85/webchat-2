package webchat.backend.auth.onboarding

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import webchat.backend.config.AdminSeedProperties
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * T030 [US4] (research.md §1 / data-model.md §1.3, FR-011–FR-012): the
 * idempotent seed of the system `admin` account, run on EVERY application
 * start (a restart re-verifies the account and re-alerts its absence).
 *
 * The insert is `INSERT … ON CONFLICT DO NOTHING` without an arbiter — the
 * unique guarantees of `users` are expression indexes on `lower(username)` /
 * `lower(email)` (V1:23-24), for which no conflict target can be named — so
 * the seed NEVER executes an UPDATE (FR-012): a foreign holder of either the
 * username or the email survives untouched, and later ops-side drift of the
 * seeded row (rotated password, changed email) is never corrected back.
 *
 * Row shape: `status=active` with a confirmed email (the passwordless JIT
 * form of 003 passes `ck_users_active_implies_email`, V9) —
 * `password_hash` is the Argon2id encoding of `ADMIN_SEED_PASSWORD` at
 * runtime or `NULL` when the env is absent (the password is then set through
 * the ordinary 002 reset-by-email flow); `display_name='Administrator'`;
 * `starter_contact_added=TRUE` keeps the admin itself out of the onboarding
 * of T031. Concurrent starts of rolling-deploy replicas serialize on the
 * index entry and the loser lands in DO NOTHING — no advisory locks needed.
 *
 * Observability: `admin_seed_total{outcome=created|skipped|failed}`; a row
 * actually created under the dev-default email additionally logs a warning
 * and carries the `email=default` tag, making a prod misconfiguration (env
 * `ADMIN_SEED_EMAIL` unset, T036) visible without breaking the dev-defaults
 * convention. Any failure (including an unreachable database) is counted as
 * `failed` and logged — the application start itself never breaks.
 */
@Component
class AdminSeedRunner(
    private val properties: AdminSeedProperties,
    private val jdbcTemplate: JdbcTemplate,
    private val passwordEncoder: PasswordEncoder,
    private val meterRegistry: MeterRegistry,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdminSeedRunner::class.java)

    override fun run(args: ApplicationArguments) {
        if (!properties.enabled) return
        try {
            if (insertAdminRow()) {
                countCreated()
                return
            }
            reportSkip()
        } catch (
            @Suppress("TooGenericExceptionCaught") ex: Exception,
        ) {
            // the seed is best-effort by design: any failure (incl. an
            // unreachable database) must never break the application start
            log.error("admin seed failed — application start continues", ex)
            meterRegistry.counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_FAILED).increment()
        }
    }

    /**
     * Attempts the one-shot insert; true when THIS start created the row.
     * A blank `admin.seed.password` is treated as absent (an empty secret
     * is never encoded into a hash nothing could ever log in with).
     */
    private fun insertAdminRow(): Boolean {
        val now = Timestamp.from(Instant.now())
        val passwordHash = properties.password?.takeIf { it.isNotBlank() }?.let(passwordEncoder::encode)
        return jdbcTemplate.update(
            INSERT_SQL,
            UUID.randomUUID(),
            properties.email,
            passwordHash,
            now,
            passwordHash?.let { now },
        ) == INSERTED_ONE_ROW
    }

    /** The control select after a swallowed insert (research.md §1). */
    private fun reportSkip() {
        val existing =
            jdbcTemplate
                .query(FIND_ADMIN_SQL) { rs, _ ->
                    ExistingAdmin(
                        rs.getObject("id", UUID::class.java),
                        rs.getString("email").lowercase(),
                    )
                }.firstOrNull()
        when {
            existing == null ->
                log.warn(
                    "admin seed skipped: username 'admin' is free but email '{}' is occupied — no admin row exists",
                    properties.email,
                )
            existing.email != properties.email.lowercase() ->
                log.warn(
                    "admin seed skipped: username 'admin' is occupied by a foreign account (id={}) — " +
                        "existing data left untouched",
                    existing.id,
                )
            else -> log.info("admin seed skipped: system account 'admin' already exists (id={})", existing.id)
        }
        meterRegistry.counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_SKIPPED).increment()
    }

    private fun countCreated() {
        if (properties.email.equals(DEV_DEFAULT_EMAIL, ignoreCase = true)) {
            log.warn("admin seed created with default email — set ADMIN_SEED_EMAIL via env/secret for real deployments")
            meterRegistry
                .counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_CREATED, TAG_EMAIL, EMAIL_DEFAULT)
                .increment()
        } else {
            log.info("admin seed: system account 'admin' created")
            meterRegistry.counter(METRIC_TOTAL, TAG_OUTCOME, OUTCOME_CREATED).increment()
        }
    }

    /** The `lower(username)='admin'` row as found by the control select, if any. */
    private data class ExistingAdmin(
        val id: UUID,
        val email: String,
    )

    private companion object {
        // no conflict target: the unique guarantees are the expression
        // indexes ux_users_username_lower / ux_users_email_lower (V1:23-24)
        const val INSERT_SQL =
            """
            INSERT INTO users (id, username, email, password_hash, status, email_confirmed_at,
                               password_set_at, display_name, starter_contact_added)
            VALUES (?, 'admin', ?, ?, 'active'::user_status, ?, ?, 'Administrator', TRUE)
            ON CONFLICT DO NOTHING
            """

        const val FIND_ADMIN_SQL =
            """
            SELECT id, email FROM users WHERE lower(username) = 'admin'
            """

        const val METRIC_TOTAL = "admin_seed_total"

        const val TAG_OUTCOME = "outcome"

        const val TAG_EMAIL = "email"

        const val OUTCOME_CREATED = "created"

        const val OUTCOME_SKIPPED = "skipped"

        const val OUTCOME_FAILED = "failed"

        /** Marks a row created under the dev default (AdminSeedProperties.email fallback). */
        const val EMAIL_DEFAULT = "default"

        /** Mirrors the dev default of `admin.seed.email` (application.yml, ADMIN_SEED_EMAIL unset). */
        const val DEV_DEFAULT_EMAIL = "admin@webchat.local"

        const val INSERTED_ONE_ROW = 1
    }
}
