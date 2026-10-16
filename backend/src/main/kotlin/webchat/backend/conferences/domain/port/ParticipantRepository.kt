package webchat.backend.conferences.domain.port

import webchat.backend.conferences.domain.model.ConferenceParticipant
import webchat.backend.conferences.domain.model.ParticipantRestrictions
import webchat.backend.conferences.domain.model.ParticipantRole
import java.time.Instant
import java.util.UUID

/**
 * Persistence port for the conference principal
 * [ConferenceParticipant] (data-model 009 §Сущность 2; DIP: the JDBC
 * adapter lives outside the domain in
 * `webchat.backend.conferences.repository.JdbcParticipantRepository`,
 * T010 — JdbcTemplate, the domain convention without JPA).
 *
 * The principal row is the ONLY carrier of the role and moderation
 * restrictions that must survive reconnect (Q5/Q9/Q22=A): grants of
 * every mint and every `UpdateParticipant` re-compute from it. Rows
 * are born at the create transaction (the creator, T017), at the №47
 * join-ticket mint (a first-time member) and at the №55 guest mint
 * (T046); all rows are deleted by the finalize transaction of
 * [ConferenceRepository.finalize] (Q18=A — this port has no delete
 * leg of its own). A kick (№51) NEVER writes here (кик ≠ бан).
 *
 * The key of every moderation/webhook lookup is the PRINCIPAL identity
 * — the stored `conference_participants.identity` prefix `u:{userId}`
 * / `g:{connId}` ([webchat.backend.conferences.domain.model.ConferenceIdentity.value]);
 * callers fold a full member CONNECTION identity `u:{userId}:{connId}`
 * (API paths, webhook bodies) through
 * `ConferenceIdentity.parsePrincipal` before calling.
 */
interface ParticipantRepository {
    /**
     * The unconditional INSERT of a FRESH principal row: the creator's
     * `role='moderator'` row of the create transaction (T017 — the
     * conference row was just inserted, no conflict is possible) and
     * the №55 guest row `g:{connId}` with the validated display name
     * (T046 — a new guest join is a new row by construction, Q7=A).
     * Returns the stored row; a key violation can only mean a caller
     * invariant breach and rolls back the ambient transaction.
     */
    fun insert(participant: ConferenceParticipant): ConferenceParticipant

    /**
     * The №47 join leg (T018/T019): ensures the member principal row
     * exists — `INSERT … ON CONFLICT (conference_id, user_id) WHERE
     * user_id IS NOT NULL DO NOTHING` + the `SELECT` over the partial
     * unique index `ux_conference_participants_member`. The FIRST join
     * creates the row (`role='participant'`, no restrictions,
     * `first_seen_at = at`); every re-join — reconnect, second tab (the
     * edge Q10=A: two mints, two LiveKit slots, ONE principal) —
     * returns the EXISTING row VERBATIM with its role and restrictions
     * (Q5/Q9=A — the mint re-computes grants from it, invariant 3).
     * The caller has ALREADY passed the membership gate and the
     * `conference_ended` check; idempotent by construction.
     */
    fun ensureMember(
        conferenceId: UUID,
        userId: UUID,
        at: Instant,
    ): ConferenceParticipant

    /**
     * Principal resolution by identity within a conference — the
     * moderation targets №49/№50/№51 (the `{identity}` path segment
     * folded to its principal value, T036/T037/T039) and the roster
     * reads. `null` → `404 participant_not_found`: no such principal,
     * never joined, or the conference is already finalized (all rows
     * deleted, Q18=A — indistinguishable by design).
     */
    fun findByIdentity(
        conferenceId: UUID,
        identity: String,
    ): ConferenceParticipant?

    /**
     * №46 `GET …/me` resolution (T019/T033): the CALLER's member
     * principal row — the source of `MeView` role/restrictions for the
     * pre-join toggle gating (FR-007). `null` when the member has not
     * joined yet: the service answers the DEFAULT `MeView`
     * (`participant`, nothing restricted) — a read never creates rows.
     */
    fun findMember(
        conferenceId: UUID,
        userId: UUID,
    ): ConferenceParticipant?

    /**
     * The `participant_joined` webhook leg (T054,
     * livekit-integration.md §5): `last_joined_at = at` on the
     * principal row resolved by the PARSED identity prefix. `false` on
     * rowcount 0 — no principal row (a connection the backend never
     * minted for, or the finalize race already deleted the roster):
     * no error, the webhook still answers 200.
     */
    fun markJoined(
        conferenceId: UUID,
        identity: String,
        at: Instant,
    ): Boolean

    /**
     * The `participant_left` webhook leg (T054): the `last_left_at`
     * twin of [markJoined] — same keying, same rowcount semantics, no
     * side effects on `false`.
     */
    fun markLeft(
        conferenceId: UUID,
        identity: String,
        at: Instant,
    ): Boolean

    /**
     * №49 `PUT …/participants/{identity}/restrictions` (T036): the
     * full SET-state write of the four moderation flags —
     * `UPDATE … SET mic_restricted=:mic, camera_restricted=:camera,
     * screen_restricted=:screen, speech_restricted=:speech WHERE
     * conference_id = :conferenceId AND identity = :identity
     * RETURNING …`. SET-semantics: repeating the same body rewrites the
     * same values (idempotent PUT); persistence until the end of the
     * conference is the row itself (Q5/Q22=A). Returns the updated row
     * (the `ParticipantView` answer and the grants source of the
     * immediate `UpdateParticipant`/`MutePublishedTrack` application),
     * or `null` → `404 participant_not_found` (no principal / already
     * finalized).
     */
    fun updateRestrictions(
        conferenceId: UUID,
        identity: String,
        restrictions: ParticipantRestrictions,
    ): ConferenceParticipant?

    /**
     * №50 `PUT …/participants/{identity}/role` (T037): the single
     * conditional role write — `UPDATE … SET role = :role WHERE
     * conference_id = :conferenceId AND identity = :identity
     * RETURNING …` (moderators are equal and may act on anyone,
     * including each other and the creator — Q21=A; the role gate
     * itself stays with the service). The role persists until the end
     * of the conference and reproduces in every re-mint (Q9=A).
     * Returns the updated row (the `ParticipantView` answer plus the
     * new `metadata` broadcast of the live connection), or `null` →
     * `404 participant_not_found`.
     */
    fun updateRole(
        conferenceId: UUID,
        identity: String,
        role: ParticipantRole,
    ): ConferenceParticipant?
}
