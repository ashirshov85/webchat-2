package webchat.backend.conferences.domain.port

import webchat.backend.conferences.domain.model.Conference
import webchat.backend.conferences.domain.model.ConferenceEndReason
import webchat.backend.conferences.domain.model.ConferenceStatus
import java.time.Instant
import java.util.UUID

/**
 * The outcome of №44 `POST /chats/{chatId}/conferences` (api-contract.md
 * §2): the caller (T017) maps [Created] to `201 ConferenceView` plus the
 * system invite message and [Existing] to `200 ConferenceView` of the
 * already running conference WITHOUT a second message (FR-001 edge
 * «двойное нажатие») — both carry the resolved row.
 */
sealed interface ConferenceCreateResult {
    val conference: Conference

    /**
     * The chat had no non-ended conference: the row (status `created`)
     * was inserted; the creator's moderator row and the system message
     * ride the SAME transaction (T017), the eager `CreateRoom` is
     * post-commit (livekit-integration.md §2, research §7).
     */
    data class Created(
        override val conference: Conference,
    ) : ConferenceCreateResult

    /**
     * The partial unique index `ux_conferences_chat_active` rejected the
     * INSERT — a non-ended conference already exists for the chat and is
     * returned verbatim; NO creator row and NO system message were
     * written (idempotency, invariant 1 of data-model 009 §примеры).
     */
    data class Existing(
        override val conference: Conference,
    ) : ConferenceCreateResult
}

/**
 * Persistence port for the [Conference] aggregate (data-model 009
 * §Сущность 1; DIP: the JDBC adapter lives outside the domain in
 * `webchat.backend.conferences.repository.JdbcConferenceRepository`,
 * T010 — JdbcTemplate, the domain convention without JPA).
 *
 * The status column moves ONLY forward and every transition is a
 * CONDITIONAL single-statement UPDATE resolved by rowcount — the
 * idempotency discipline shared with the auth/chats ports: a zero
 * rowcount means the transition does not apply and NO side effects
 * occur (duplicate webhooks, №48 racing `room_finished`, sweeper racing
 * the webhook — research §7, invariant 5).
 *
 * Media state is NOT persisted here (research §4/§7): rooms and
 * connections live in LiveKit behind [LiveKitAdminClient]; this port
 * keeps only what must survive reconnect and outlive the call.
 */
interface ConferenceRepository {
    /**
     * №44 (T017), ONE PG transaction against the partial unique index
     * `ux_conferences_chat_active`: `INSERT … ON CONFLICT (chat_id)
     * WHERE status <> 'ended' DO NOTHING` + the `SELECT` of the
     * resolved row — [ConferenceCreateResult.Created] for the winner,
     * [ConferenceCreateResult.Existing] for the competing «double
     * press». The service has ALREADY run the media-plane pre-flight
     * (`ListRooms` probe → `503 conference_media_unavailable` BEFORE
     * any write) and the membership/E2EE gates (`403
     * conference_forbidden` / `409 conference_e2ee_unsupported`);
     * the system invite message of the chats domain joins the SAME
     * transaction (FR-002), the eager `CreateRoom` follows post-commit.
     */
    fun create(conference: Conference): ConferenceCreateResult

    /**
     * №45/№47/№48–№53 resolution: the row whatever its status — an
     * ENDED conference legitimately resolves (`200 ConferenceView` with
     * `status='ended'` for the «конференция завершена» answer of an old
     * link, №45; `409 conference_ended` for №47/№48/№52/№53). `null` →
     * `404 conference_not_found` (unknown id; after finalize the ROW
     * survives — only participants/invites are deleted, Q18=A).
     */
    fun findById(id: UUID): Conference?

    /**
     * The chat-deletion listener leg (T062, FR-014): every non-ENDED
     * conference of the chat — at most ONE row by
     * `ux_conferences_chat_active`; the list shape only feeds the
     * defensive `finalize(end_reason='chat_deleted')` loop that runs in
     * the deletion transaction BEFORE the `ON DELETE CASCADE`. Also the
     * T017 E2EE/membership-independent existence probe of a chat's
     * running call.
     */
    fun findUnendedByChatId(chatId: UUID): List<Conference>

    /**
     * The sweeper/max-duration scans (T056, research §7) over
     * `ix_conferences_status`: [ConferenceStatus.CREATED] — rooms to
     * re-create after a crash and «никто не вошёл» candidates for the
     * empty-timeout `room_lost` finalize; [ConferenceStatus.ACTIVE] —
     * the max-duration horizon (`started_at + max-duration`) and the
     * `room_lost` grace window against `ListRooms`. ENDED rows have no
     * scan consumer (Q18=A) and are not enumerated.
     */
    fun findByStatus(status: ConferenceStatus): List<Conference>

    /**
     * `created → active` on the FIRST `participant_joined` webhook
     * (T054, data-model 009 §Сущность 1): the conditional
     * `UPDATE … SET status='active', started_at=:at WHERE id = :id AND
     * status='created' RETURNING …` — the storage twin of
     * [Conference.activate]. Returns the advanced row exactly once (the
     * caller's post-commit SSE `conference.started` anchor, US5-1), or
     * `null` for the idempotent no-op: a duplicate/retried webhook, an
     * out-of-order `participant_joined` after `room_finished`, or a
     * conference already active/ended — nothing changes, no event.
     */
    fun activate(
        id: UUID,
        at: Instant,
    ): Conference?

    /**
     * The ONE idempotent finalize of every end path (T055, research
     * §7): webhook `room_finished` (reason `last_left`, or `room_lost`
     * for a conference still `created`), №48 moderator (T038
     * pre-records `moderator` BEFORE `DeleteRoom` — the later webhook
     * re-enters here as a no-op), the max-duration scheduler
     * (`max_duration`), the sweeper (`room_lost`) and the chat-deletion
     * listener (`chat_deleted`, T062). ONE transaction:
     * the conditional `UPDATE … SET status='ended', ended_at=:at,
     * end_reason=:reason WHERE id = :id AND status <> 'ended'
     * RETURNING …` PLUS the Q18=A deletions of ALL
     * `conference_participants` and `conference_guest_invites` rows of
     * the conference. Returns the finalized row (the SSE
     * `conference.ended` payload source), or `null` when already ended
     * — a duplicate `room_finished` leaves `ended_at`/`end_reason`
     * untouched (invariant 5). The caller snapshots the chat membership
     * for the post-commit SSE in the SAME ambient transaction (T055/
     * T062) and issues `DeleteRoom` post-commit only when the room may
     * still be alive.
     */
    fun finalize(
        id: UUID,
        reason: ConferenceEndReason,
        at: Instant,
    ): Conference?
}
