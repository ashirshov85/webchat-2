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
     * T044 (research 008a §B2): ONE round trip over the whole №36
     * candidate batch — `SELECT id, presence_hidden … WHERE id IN (…)`
     * with a placeholder per DISTINCT id (the snapshot's ≤ 200 cap keeps
     * the IN-list bounded). A row the table does not have is simply
     * absent from the answer: the caller consults this leg only for
     * targets the visibility policy already resolved as visible, so a
     * missing entry defaults to «not hidden» upstream.
     *
     * (The spread of the argument array is the dynamic IN-list's per-id
     * bind list — a tiny one-off copy of ≤ 200 ids, the №36 batch cap.)
     */
    @Suppress("SpreadOperator")
    override fun incognitoBatch(userIds: Collection<UUID>): Map<UUID, Boolean> {
        val distinct = userIds.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val sql = "$FIND_INCOGNITO_BATCH_SQL_PREFIX${distinct.joinToString(COMMA) { PLACEHOLDER }})"
        return jdbcTemplate
            .query(
                sql,
                { rs, _ -> UUID.fromString(rs.getString(ID_COLUMN)) to rs.getBoolean(PRESENCE_HIDDEN_COLUMN) },
                *distinct.toTypedArray(),
            ).toMap()
    }

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
        const val ID_COLUMN = "id"
        const val PRESENCE_HIDDEN_COLUMN = "presence_hidden"
        const val COMMA = ","
        const val PLACEHOLDER = "?"

        val FIND_INCOGNITO_SQL =
            """
            SELECT presence_hidden FROM users WHERE id = ?
            """.trimIndent()

        const val FIND_INCOGNITO_BATCH_SQL_PREFIX = "SELECT id, presence_hidden FROM users WHERE id IN ("

        val STORE_IF_CHANGED_SQL =
            """
            UPDATE users SET presence_hidden = ? WHERE id = ? AND presence_hidden <> ?
            """.trimIndent()
    }
}
