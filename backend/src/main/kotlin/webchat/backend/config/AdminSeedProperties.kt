package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * US4 (008b T028, research.md §1 / data-model.md §1.3, FR-011–FR-012): the
 * configuration of the idempotent system-account seed run by `AdminSeedRunner`
 * on every application start. [enabled] gates the runner (default true);
 * [email] is the seeded mailbox — env `ADMIN_SEED_EMAIL` with the dev default
 * `admin@webchat.local` (application.yml; a row actually created under the
 * default is warn-observable in the runner — prod MUST set the env, T036).
 * [password] is NEVER stored in the repo (constitution V): the
 * `ADMIN_SEED_PASSWORD` env var binds to `admin.seed.password` via Spring's
 * relaxed env binding with no yml entry at all — absent → null → the
 * passwordless JIT form of 003 (active row, `password_hash IS NULL`; the
 * password is later set through the ordinary 002 reset flow, which accepts
 * active passwordless accounts).
 */
@ConfigurationProperties(prefix = "admin.seed")
data class AdminSeedProperties(
    val enabled: Boolean = true,
    val email: String = "admin@webchat.local",
    val password: String? = null,
)
