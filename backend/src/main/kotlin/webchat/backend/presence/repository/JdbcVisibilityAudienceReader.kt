package webchat.backend.presence.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import webchat.backend.presence.domain.port.VisibilityAudienceReader
import java.util.UUID

/**
 * The JDBC adapter of the [VisibilityAudienceReader] port (T009; DIP: the
 * SQL lives outside the domain). The audience is COMPUTED live over the
 * CURRENT PG state on every call — no cache, no side-tables (research.md
 * §C1 rejected both): a contact added or a group kick converges by the
 * next transition/snapshot without any invalidation (edges, ≤ 5 s as in
 * 006 FR-010).
 *
 * Membership formula (data-model 007 §1.4), one statement per port leg:
 *
 * ```text
 * audience(X) = { ACTIVE co-participants of X's ACTIVE chats (direct +
 *                 group — chat_participants.state='active')      -- V14
 *               ∪ { owners having X in their contacts }          -- V11 PK
 *             } − { block-pairs of X in BOTH directions }        -- V11 PK
 *               − { X }
 * ```
 *
 * The subject-side chat lookup rides `ix_chat_participants_user` (V12),
 * the roster side — `ix_chat_participants_chat_active` (V14, INCLUDE
 * user_id, partial on state='active'); contacts and blocks are point
 * lookups on their composite PKs (V11). Direct-dialog rows stay
 * `state='active'` forever (V14), so requiring BOTH sides active
 * excludes exactly the removed group members.
 *
 * The adapter knows nothing about «невидимка» (`users.presence_hidden`
 * never appears in the SQL — the freeze and the indistinguishable
 * `offline` answer are the PresenceService business, FR-007/T033) and
 * nothing about Redis: membership only, both presence legs (T016/T017)
 * resolve through the same formula so an event and a snapshot can never
 * disagree about who may see whom (SC-004).
 */
@Repository
class JdbcVisibilityAudienceReader(
    private val jdbcTemplate: JdbcTemplate,
) : VisibilityAudienceReader {
    /**
     * The fan-out addressee set of a subject transition (T017): ONE
     * statement — the UNION of both legs dedupes (an observer sharing a
     * direct AND a group chat with the subject is emitted once → one
     * `rt:user:{observerId}` publication per observer), then the
     * block-pair anti-join (both directions) and the self-exclusion.
     * An unknown subject simply yields an empty set.
     */
    override fun audienceOf(subjectId: UUID): Set<UUID> =
        jdbcTemplate
            .query(
                AUDIENCE_SQL,
                MEMBER_ROW_MAPPER,
                subjectId,
                subjectId,
                subjectId,
                subjectId,
                subjectId,
            ).toSet()

    /**
     * The №36 per-pair policy batch (T016): the same formula resolved
     * pairwise (`observer ∈ audience(target)`) for the WHOLE batch in one
     * statement — a `(VALUES …)` join (the roster pattern of
     * JdbcGroupRepository), never N+1. [targetIds] arrives deduped and
     * capped at 200 (№36 validation, contracts/presence-api.md §1); the
     * local re-dedup keeps set semantics total — a duplicate or a
     * self-entry is harmless, a nonexistent userId never matches the
     * EXISTS legs and reads `unknown` (FR-007: «no access» is
     * indistinguishable from «does not exist»).
     *
     * (The spread of the argument array is the dynamic VALUES join's
     * per-target list — a tiny one-off copy of ≤ 201 elements.)
     */
    @Suppress("SpreadOperator")
    override fun visibleTargets(
        observerId: UUID,
        targetIds: Collection<UUID>,
    ): Set<UUID> {
        val targets = targetIds.toSet()
        if (targets.isEmpty()) return emptySet()
        val args =
            buildList(capacity = targets.size + OBSERVER_ARG_COUNT) {
                targets.forEach { add(it) }
                repeat(OBSERVER_ARG_COUNT) { add(observerId) }
            }
        return jdbcTemplate
            .query(visibleTargetsSql(targets.size), MEMBER_ROW_MAPPER, *args.toTypedArray())
            .toSet()
    }

    private companion object {
        /** The bind order follows the TEXTUAL placeholder order: the VALUES rows come first. */
        private const val OBSERVER_ARG_COUNT = 5

        private const val TARGET_ROW = "(?::uuid)"

        private val MEMBER_ROW_MAPPER = RowMapper { rs, _ -> rs.getObject(1, UUID::class.java) }

        private fun visibleTargetsSql(targetCount: Int): String {
            val rows = (1..targetCount).joinToString(", ") { TARGET_ROW }
            return """
                SELECT v.target_id
                FROM (VALUES $rows) AS v(target_id)
                WHERE v.target_id <> ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM user_blocks b
                      WHERE (b.blocker_id = v.target_id AND b.blocked_id = ?)
                         OR (b.blocker_id = ? AND b.blocked_id = v.target_id)
                  )
                  AND (
                      EXISTS (
                          SELECT 1
                          FROM chat_participants po
                          JOIN chat_participants pt ON pt.chat_id = po.chat_id
                          WHERE po.user_id = ? AND po.state = 'active'
                            AND pt.user_id = v.target_id AND pt.state = 'active'
                      )
                      OR EXISTS (
                          SELECT 1
                          FROM user_contacts c
                          WHERE c.owner_id = ? AND c.contact_user_id = v.target_id
                      )
                  )
                """.trimIndent()
        }

        private val AUDIENCE_SQL =
            """
            SELECT member_id
            FROM (
                SELECT cp_o.user_id AS member_id
                FROM chat_participants cp_s
                JOIN chat_participants cp_o ON cp_o.chat_id = cp_s.chat_id
                WHERE cp_s.user_id = ? AND cp_s.state = 'active'
                  AND cp_o.state = 'active'
                UNION
                SELECT uc.owner_id AS member_id
                FROM user_contacts uc
                WHERE uc.contact_user_id = ?
            ) members
            WHERE members.member_id <> ?
              AND NOT EXISTS (
                  SELECT 1
                  FROM user_blocks b
                  WHERE (b.blocker_id = members.member_id AND b.blocked_id = ?)
                     OR (b.blocker_id = ? AND b.blocked_id = members.member_id)
              )
            """.trimIndent()
    }
}
