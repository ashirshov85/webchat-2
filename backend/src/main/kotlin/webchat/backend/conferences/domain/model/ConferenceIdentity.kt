package webchat.backend.conferences.domain.model

import java.util.UUID

/**
 * The principal identity of a conference participant — the value stored
 * in `conference_participants.identity` and the PREFIX of the
 * per-connection LiveKit identity (data-model 009 §Сущность 2,
 * research §4):
 * - [Member] — `u:{userId}`: the principal of a chat member, one row
 *   per `(conference_id, user_id)` that survives reconnects (Q5/Q9);
 *   each connection/tab mints the full identity `u:{userId}:{connId}`
 *   with a fresh server UUID (`connId`) — LiveKit de-duplicates by
 *   identity, so two tabs of one user occupy two slots (edge Q10).
 * - [Guest] — `g:{connId}`: a guest's row AND connection identity in
 *   one — a new join is a new row/identity (Q7=A: guest restrictions do
 *   not survive reconnect).
 * No PII beyond opaque UUIDs (research §4 — LiveKit logs/traces).
 *
 * Webhooks arrive with the full connection identity; [parsePrincipal]
 * folds it back to the principal for the exact row lookup within the
 * conference (research §4, livekit-integration.md §5).
 */
sealed interface ConferenceIdentity {
    /** The stored `conference_participants.identity` prefix: `u:{userId}` / `g:{connId}`. */
    val value: String

    /** The chat-member principal `u:{userId}`. */
    data class Member(
        val userId: UUID,
    ) : ConferenceIdentity {
        override val value: String
            get() = "u:$userId"

        /**
         * The full per-connection identity minted into a member token
         * (`u:{userId}:{connId}`, research §4): one fresh `connId` per
         * №47 mint so reconnects and second tabs keep distinct slots.
         */
        fun connectionIdentity(connId: UUID): String = "u:$userId:$connId"
    }

    /** The guest principal/connection identity `g:{connId}` (Q7=A). */
    data class Guest(
        val connId: UUID,
    ) : ConferenceIdentity {
        override val value: String
            get() = "g:$connId"
    }

    companion object {
        /** The V18 `conference_participants.identity varchar(80)` cap; the longest form `u:{uuid}:{uuid}` is 75. */
        const val MAX_LENGTH: Int = 80

        private const val MEMBER_PREFIX = "u"
        private const val GUEST_PREFIX = "g"

        fun member(userId: UUID): Member = Member(userId)

        fun guest(connId: UUID): Guest = Guest(connId)

        /** Segment count of a stored principal prefix: `{prefix}:{uuid}`. */
        private const val PRINCIPAL_SEGMENTS = 2

        /** Segment count of a member connection identity: `u:{userId}:{connId}`. */
        private const val CONNECTION_SEGMENTS = 3

        /**
         * Parses an external identity string — a stored principal
         * prefix (`u:{userId}` / `g:{connId}`) or a full member
         * connection identity (`u:{userId}:{connId}`, folded to the
         * member principal) — into the principal for the webhook row
         * lookup (research §13 «identity-парсинг»). Malformed input
         * (unknown prefix, non-UUID segments, wrong arity) yields
         * `null`, never throws: webhook bodies and LiveKit listings
         * are external input.
         */
        fun parsePrincipal(raw: String): ConferenceIdentity? {
            val parts = raw.split(':')
            return when {
                parts.size == PRINCIPAL_SEGMENTS -> ofPrefix(parts[0], parts[1])
                // a member connection identity folds to its principal
                // u:{userId}; both segments must validate — a malformed
                // connId means the identity was not minted by us
                parts.size == CONNECTION_SEGMENTS && parts[0] == MEMBER_PREFIX ->
                    if (uuidOrNull(parts[2]) != null) uuidOrNull(parts[1])?.let(::Member) else null
                else -> null
            }
        }

        /**
         * FR-003 edge predicate of the chat-removal listener (T020):
         * whether [raw] is a member CONNECTION identity of [userId]
         * (prefix `u:{userId}:`) — every LiveKit participant it matches
         * gets `RemoveParticipant` after the member left/was removed
         * from the chat.
         */
        fun isMemberConnectionOf(
            raw: String,
            userId: UUID,
        ): Boolean = raw.startsWith("u:$userId:")

        private fun ofPrefix(
            prefix: String,
            uuidPart: String,
        ): ConferenceIdentity? =
            when (prefix) {
                MEMBER_PREFIX -> uuidOrNull(uuidPart)?.let(::Member)
                GUEST_PREFIX -> uuidOrNull(uuidPart)?.let(::Guest)
                else -> null
            }

        private fun uuidOrNull(raw: String): UUID? =
            try {
                UUID.fromString(raw)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
