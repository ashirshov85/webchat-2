package webchat.backend.users.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import webchat.backend.AbstractIntegrationTest
import webchat.backend.users.domain.model.DisplayName
import webchat.backend.users.domain.port.ProfileStore
import java.util.UUID

/**
 * T011 (tasks.md Phase 3, constitution VI Test-First) — the THIN ADAPTER
 * SLICE of the profile store over real Testcontainers PG 17 (V16 applied
 * by Flyway): what `ProfileStore.updateDisplayName` must do for the №39
 * legs ProfileDisplayNameIT (T009) drives over HTTP once T012 lands —
 *
 *  * the stored value is the TRIMMED form (the normalization happens in
 *    the domain BEFORE the write, so the `ck_users_display_name` CHECK of
 *    V16 re-holds on the raw column);
 *  * `null` resets the column to NULL = «not set» (data-model 008a §1.1);
 *  * a second set is a plain last-write-wins overwrite — idempotent, no
 *    side conditions;
 *  * the returned [webchat.backend.users.domain.model.Profile] carries
 *    the public projection of the SAME row (username/email/status/
 *    createdAt) with the fresh value — the future `200 PublicUser` body;
 *  * the write touches ONLY the profile column: the 002 aggregate
 *    columns of the row (password/status material) survive verbatim;
 *  * the 64-character bound itself persists (the CHECK boundary);
 *  * an unknown user id fails fast — existence is the API layer's gate
 *    (№39 is `PUT /users/me/profile`, an authenticated caller).
 */
class ProfileStoreIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var profileStore: ProfileStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `set stores the trimmed value and returns the updated public projection`() {
        val user = newUser()

        val updated = profileStore.updateDisplayName(user, DisplayName.normalize("  $PROFILE_NAME  "))

        assertThat(updated.id).isEqualTo(user)
        val answeredName = updated.displayName!!.value
        assertThat(answeredName)
            .overridingErrorMessage("the store must answer the TRIMMED stored value, got <%s>", answeredName)
            .isEqualTo(PROFILE_NAME)
        assertThat(updated.username).isEqualTo(loginOf(user))
        assertThat(updated.email).isEqualTo(emailOf(user))
        assertThat(updated.status).isEqualTo(STAGED_STATUS)
        assertThat(updated.createdAt).isNotNull
        assertThat(storedDisplayName(user))
            .overridingErrorMessage("users.display_name must carry the trimmed form (data-model 008a §1.1)")
            .isEqualTo(PROFILE_NAME)
    }

    @Test
    fun `null resets the column to not set`() {
        val user = newUser()
        profileStore.updateDisplayName(user, DisplayName.normalize(PROFILE_NAME))

        val cleared = profileStore.updateDisplayName(user, null)

        assertThat(cleared.displayName)
            .overridingErrorMessage("the reset leg must answer displayName = null (NULL = «не задано»)")
            .isNull()
        assertThat(storedDisplayName(user)).isNull()
    }

    @Test
    fun `a repeated set is a plain last-write-wins overwrite`() {
        val user = newUser()
        profileStore.updateDisplayName(user, DisplayName.normalize(PROFILE_NAME))

        val overwritten = profileStore.updateDisplayName(user, DisplayName.normalize(SECOND_NAME))

        assertThat(overwritten.displayName!!.value).isEqualTo(SECOND_NAME)
        assertThat(storedDisplayName(user)).isEqualTo(SECOND_NAME)
    }

    @Test
    fun `the write never touches the 002 aggregate columns of the row`() {
        val user = newUser()

        profileStore.updateDisplayName(user, DisplayName.normalize(PROFILE_NAME))

        val row = userRow(user)!!
        assertThat(row["password_hash"])
            .overridingErrorMessage("a profile write must not disturb password material")
            .isNull()
        assertThat(row["status"])
            .overridingErrorMessage("a profile write must not disturb the account status")
            .isEqualTo(STAGED_STATUS)
        assertThat(row["email"]).isEqualTo(emailOf(user))
    }

    @Test
    fun `the 64-character bound itself persists`() {
        val user = newUser()
        val boundary = "м".repeat(DisplayName.MAX_LENGTH)

        val updated = profileStore.updateDisplayName(user, DisplayName.normalize(boundary))

        assertThat(updated.displayName!!.value).isEqualTo(boundary)
        assertThat(storedDisplayName(user)).isEqualTo(boundary)
    }

    @Test
    fun `an unknown user id fails fast instead of pretending success`() {
        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { profileStore.updateDisplayName(UUID.randomUUID(), DisplayName.normalize(PROFILE_NAME)) }
    }

    // --- staging helpers (the T012a discipline: raw SQL only for state the ports cannot write) ---

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(INSERT_USER_SQL, id, loginOf(id), emailOf(id))
        return id
    }

    private fun loginOf(id: UUID): String = "t011-${id.toString().substring(0, 8)}"

    private fun emailOf(id: UUID): String = "${loginOf(id)}@example.com"

    private fun storedDisplayName(userId: UUID): String? = userRow(userId)?.get("display_name") as String?

    private fun userRow(userId: UUID): Map<String, Any>? = jdbcTemplate.queryForList(ROW_SQL, userId).firstOrNull()

    private companion object {
        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()

        val ROW_SQL =
            """
            SELECT username, email, password_hash, status, display_name
            FROM users
            WHERE id = ?
            """.trimIndent()

        const val STAGED_STATUS = "pending_email_confirmation"
        const val PROFILE_NAME = "Мария"
        const val SECOND_NAME = "Маша"
    }
}
