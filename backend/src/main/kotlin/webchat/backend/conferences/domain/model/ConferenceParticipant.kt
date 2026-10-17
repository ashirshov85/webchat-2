package webchat.backend.conferences.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Role of a conference principal (data-model 009 §Сущность 2, the V18
 * `ck_conference_participants_role` CHECK): [MODERATOR] — the creator's
 * first row plus members elevated by №50; moderators are equal and may
 * act on anyone, including each other and the creator (Q21=A — no
 * «владелец» role exists); [PARTICIPANT] — a plain member/guest. The
 * role persists until the end of the conference and survives
 * reconnects (Q9=A — re-computed from this row at every re-mint).
 */
enum class ParticipantRole {
    MODERATOR,
    PARTICIPANT,
}

/**
 * A conference principal — the carrier of the role and moderation
 * restrictions surviving reconnect (data-model 009 §Сущность 2,
 * research §5): a chat member is ONE row per `(conference_id, user_id)`
 * (partial UNIQUE of V18 — Q5/Q9 persistence), a guest is a row per
 * connection (new join = new row, Q7=A). [identity] is the stored
 * principal prefix ([ConferenceIdentity]); the `init` block mirrors the
 * V18 shape-CHECK `(user_id IS NULL) = (display_name IS NOT NULL)` — a
 * guest MUST carry the entered name (trimmed, 1..50 at the boundary —
 * Q7/Q19, `422 name_invalid` of T046), a member must NOT (the display
 * name comes from `users` at token mint, T018). All rows are deleted on
 * finalize (Q18=A — the roster of a finished conference is neither kept
 * nor shown, constitution V).
 */
data class ConferenceParticipant(
    val id: UUID,
    val conferenceId: UUID,
    val identity: ConferenceIdentity,
    val displayName: String? = null,
    val role: ParticipantRole = ParticipantRole.PARTICIPANT,
    val restrictions: ParticipantRestrictions = ParticipantRestrictions(),
    val firstSeenAt: Instant,
    val lastJoinedAt: Instant? = null,
    val lastLeftAt: Instant? = null,
) {
    init {
        when (identity) {
            is ConferenceIdentity.Member ->
                require(displayName == null) {
                    "a member principal carries no display name (V18 shape-CHECK; the name comes from users at mint)"
                }
            is ConferenceIdentity.Guest ->
                require(!displayName.isNullOrBlank()) {
                    "a guest principal must carry the entered display name (V18 shape-CHECK)"
                }
        }
    }

    /** `null` ⟺ guest (data-model 009 §Сущность 2: `user_id IS NULL ⟺ гость`). */
    val userId: UUID?
        get() = (identity as? ConferenceIdentity.Member)?.userId

    /** The guest marker of the LiveKit participant metadata `{"guest":true}` (T018/T046/T051). */
    val isGuest: Boolean
        get() = identity is ConferenceIdentity.Guest

    /**
     * The effective publish grants of this principal —
     * [ParticipantRestrictions.grants] of the persisted state: applied
     * at every mint (T018) and every moderation `UpdateParticipant`
     * (T036), which is what makes restrictions survive reconnect
     * (Q5=A, invariant 3).
     */
    fun grants(): PublishGrants = restrictions.grants()

    companion object {
        /**
         * The member principal row (T017/T019): identity `u:{userId}`,
         * no display name; the CREATOR row is created with
         * [ParticipantRole.MODERATOR] (FR-008 — moderator by default),
         * everyone else joins as [ParticipantRole.PARTICIPANT].
         */
        fun member(
            id: UUID,
            conferenceId: UUID,
            userId: UUID,
            firstSeenAt: Instant,
            role: ParticipantRole = ParticipantRole.PARTICIPANT,
        ): ConferenceParticipant =
            ConferenceParticipant(
                id = id,
                conferenceId = conferenceId,
                identity = ConferenceIdentity.member(userId),
                displayName = null,
                role = role,
                firstSeenAt = firstSeenAt,
            )

        /**
         * The guest principal row (T046): created at №55 join-ticket
         * issuance with identity `g:{connId}` and the entered (already
         * trimmed/length-validated) name — a new guest join is a new
         * row (Q7=A). Always [ParticipantRole.PARTICIPANT] (guests are
         * never moderators).
         */
        fun guest(
            id: UUID,
            conferenceId: UUID,
            connId: UUID,
            displayName: String,
            firstSeenAt: Instant,
        ): ConferenceParticipant =
            ConferenceParticipant(
                id = id,
                conferenceId = conferenceId,
                identity = ConferenceIdentity.guest(connId),
                displayName = displayName,
                role = ParticipantRole.PARTICIPANT,
                firstSeenAt = firstSeenAt,
            )
    }
}
