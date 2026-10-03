package webchat.backend.presence.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import webchat.backend.presence.domain.port.PresenceSettingsStore
import java.util.UUID

/**
 * The JDBC adapter of the [PresenceSettingsStore] port (T032): the V15
 * `users.presence_hidden` column (data-model 007 §1.5/§2) — the single
 * durable leg of the 007 feature. Point lookups on the `users` PK, one
 * statement per port call, nothing else: the adapter knows NEITHER the
 * №38 HTTP contract (shape/flood gates live in the controller) NOR the
 * «невидимка» state machine (the freeze/reveal transitions of T033 own
 * PresenceService) — it only makes the mode durable and answers the
 * atomic change verdict the idempotent PUT relies on.
 */
@Repository
class JdbcPresenceSettingsStore(
    private val jdbcTemplate: JdbcTemplate,
) : PresenceSettingsStore {
    /** №38 GET: the persisted mode; the NOT NULL V15 default keeps the answer definite. */
    override fun incognitoOf(userId: UUID): Boolean =
        jdbcTemplate
            .query(
                FIND_INCOGNITO_SQL,
                { rs, _ -> rs.getBoolean(PRESENCE_HIDDEN_COLUMN) },
                userId,
            ).firstOrNull() ?: false

    /**
     * №38 PUT: the conditional write whose affected-row count IS the
     * change verdict — `0` resolves the IDEMPOTENT repeat of the very
     * same value as a no-op (presence-api.md §3: «повтор той же
     * величины — no-op без событий») without any read-before-write
     * race; `1` is the actual flip the caller may react to. The column
     * is NOT NULL, so the plain `<>` predicate is total.
     */
    override fun storeIfChanged(
        userId: UUID,
        incognito: Boolean,
    ): Boolean =
        jdbcTemplate
            .update(
                STORE_IF_CHANGED_SQL,
                incognito,
                userId,
                incognito,
            ) > 0

    private companion object {
        const val PRESENCE_HIDDEN_COLUMN = "presence_hidden"

        val FIND_INCOGNITO_SQL =
            """
            SELECT presence_hidden FROM users WHERE id = ?
            """.trimIndent()

        val STORE_IF_CHANGED_SQL =
            """
            UPDATE users SET presence_hidden = ? WHERE id = ? AND presence_hidden <> ?
            """.trimIndent()
    }
}
