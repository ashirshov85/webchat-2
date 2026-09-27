package webchat.backend.chats.domain.model

import webchat.backend.groups.domain.model.GroupDescription
import webchat.backend.groups.domain.model.GroupTitle
import java.time.Instant
import java.util.UUID

/**
 * Discriminator of a `chats` row (data-model 006 §Сущность 1, the V14
 * `ck_chats_kind` CHECK): [DIRECT] — the immutable two-user dialog of
 * 004 carrying the canonical user pair; [GROUP] — the shared group chat
 * of 006 with a NULL pair and group metadata (`title`, optional
 * `description`). Mirrors the stored values `direct`/`group`.
 */
enum class ChatKind {
    DIRECT,
    GROUP,
}

/**
 * A single conversation row of the unified `chats` table: the personal
 * dialog of exactly two users (004, FR-001) or the group projection of
 * 006 — discriminated by [kind] (data-model 006 §Сущность 1). The
 * dialog pair is stored in canonical order (`user_low_id = least(a, b)`,
 * `user_high_id = greatest(a, b)`, `low < high` — the UNIQUE pair of
 * V10) and is NULL for groups (the V14 shape-CHECK), so exactly one
 * dialog exists per pair and ensure is idempotent (FR-018). Dialogs are
 * immutable after creation; group metadata mutates through the groups
 * feature (№29) — this projection only carries the current row state.
 * Per-user visibility lives in [ChatParticipant], messages in [Message].
 *
 * [lastSeq] caches `MAX(messages.seq)` of the chat, maintained in the
 * message INSERT transaction (T012): list sorting (FR-014), the deletion
 * watermark (FR-021) and visibility reads avoid a full MAX scan — in
 * groups it also seeds the membership watermarks on the first add
 * (FR-013).
 */
data class Chat(
    val id: UUID,
    val kind: ChatKind = ChatKind.DIRECT,
    val userLowId: UUID? = null,
    val userHighId: UUID? = null,
    val title: String? = null,
    val description: String? = null,
    val createdAt: Instant,
    val lastSeq: Long = 0,
) {
    init {
        // The shape invariant mirrors the V14 `ck_chats_shape` CHECK:
        // a direct chat carries the canonical pair, a group carries none.
        when (kind) {
            ChatKind.DIRECT -> {
                val low = requireNotNull(userLowId) { "a direct chat requires the canonical user pair" }
                val high = requireNotNull(userHighId) { "a direct chat requires the canonical user pair" }
                require(canonicalPair(low, high).first == low) {
                    "chat pair must be in canonical order user_low_id < user_high_id"
                }
            }
            ChatKind.GROUP -> {
                require(userLowId == null && userHighId == null) {
                    "a group chat carries no dialog pair (V14 shape-CHECK)"
                }
                // Mirrors the V14 `ck_chats_title` CHECK (FR-001; the
                // normalized value object `GroupTitle` is the validation
                // source at the groups-feature boundary — T005).
                val normalizedTitle = title?.trim()
                require(!normalizedTitle.isNullOrEmpty() && normalizedTitle.length <= GroupTitle.MAX_LENGTH) {
                    "a group chat requires a title of 1..${GroupTitle.MAX_LENGTH} characters after trim"
                }
                require(description == null || description.length <= GroupDescription.MAX_LENGTH) {
                    "a group description must not exceed ${GroupDescription.MAX_LENGTH} characters"
                }
            }
        }
    }

    /**
     * FR-002 (004): membership check used by every DIRECT-dialog
     * resource. A group has no pair — its membership is the ACTIVE
     * participant row checked on every request (FR-008 of 006), so this
     * predicate is always `false` for groups (the 004 paths then answer
     * through their own `403 not_participant`/`404 chat_not_found`
     * semantics, api-contract.md 006 §3).
     */
    fun involves(userId: UUID): Boolean = kind == ChatKind.DIRECT && (userId == userLowId || userId == userHighId)

    /**
     * The other side of the dialog for a participant, `null` for
     * strangers — and `null` for a group (no peer exists; the roster is
     * the `chat_participants` rows of 006).
     */
    fun peerOf(userId: UUID): UUID? =
        when {
            kind != ChatKind.DIRECT -> null
            userId == userLowId -> userHighId
            userId == userHighId -> userLowId
            else -> null
        }

    /**
     * Monotone `last_seq` cache advance inside the message INSERT
     * transaction (data-model 004 §3 step 4): never moves backwards.
     */
    fun advanceLastSeq(seq: Long): Chat {
        require(seq >= lastSeq) { "chat last_seq must not move backwards" }
        return copy(lastSeq = seq)
    }

    companion object {
        /**
         * PG orders uuid byte-wise UNSIGNED (the V10 CHECK
         * `user_low_id < user_high_id` and the UNIQUE pair key use
         * least/greatest), while Java's [UUID.compareTo] is SIGNED on the
         * two 64-bit halves — the orders disagree whenever the most
         * significant byte crosses 0x80 (regression: JdbcChatRepositoryIT).
         * The hex form compares exactly in PG byte order. Single ordering
         * source for the model invariant and the JDBC adapters.
         */
        fun canonicalPair(
            a: UUID,
            b: UUID,
        ): Pair<UUID, UUID> = if (a.toString() < b.toString()) a to b else b to a

        /**
         * Opens (or re-resolves) the single dialog of a pair: canonical
         * least/greatest order regardless of the argument order; a dialog
         * with oneself is refused here in addition to the `422 self_forbidden`
         * of the service layer (FR-001/edge). The result is a [ChatKind.DIRECT]
         * row — groups are created exclusively through the groups feature (№27).
         */
        fun forPair(
            id: UUID,
            a: UUID,
            b: UUID,
            at: Instant,
        ): Chat {
            require(a != b) { "a dialog requires two distinct users (FR-001)" }
            val (low, high) = canonicalPair(a, b)
            return Chat(id = id, userLowId = low, userHighId = high, createdAt = at)
        }
    }
}
