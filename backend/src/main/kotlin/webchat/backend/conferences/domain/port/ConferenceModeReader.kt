package webchat.backend.conferences.domain.port

import webchat.backend.conferences.api.dto.ConferenceMode
import java.util.UUID

/**
 * The №44/№45 pre-join-mode read port of the conferences domain (T019):
 * the `ConferenceView.mode` source. The conferences row deliberately
 * stores NO mode (data-model 009 §Сущность 1 — it is a CLIENT start
 * default, not conference state), so the mode lives ONLY in the invite
 * widget payload `{"type":"conference_invite","conferenceId","mode"}`
 * posted into the chat history by the create transaction (T017,
 * data-model 009 §Сущность 4). This port reads it back — the single
 * source the API answers from, for BOTH the fresh `201` and the
 * idempotent `200 Existing` of №44 (a competing «double press» with a
 * DIFFERENT mode answers the mode of the conference that actually
 * runs) and for №45 (DIP: the adapter lives in
 * `webchat.backend.conferences.repository.JdbcConferenceModeReader`;
 * a conference service never touches the chats message store
 * directly — the same seam discipline as [ChatMembershipChecker]).
 */
interface ConferenceModeReader {
    /**
     * The pre-join mode of the invite widget of [conferenceId] within
     * [chatId], or `null` when the chat history holds no such widget.
     * `null` is a broken invariant for a stored conference (the widget
     * rides the SAME transaction as the row, T017) — the caller treats
     * it as such; the read never writes and never discloses anything
     * beyond the payload the chat members already see (constitution V).
     */
    fun modeOf(
        conferenceId: UUID,
        chatId: UUID,
    ): ConferenceMode?
}
