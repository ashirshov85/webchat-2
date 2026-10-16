package webchat.backend.conferences.domain.port

import java.util.UUID

/**
 * The №44 chat projection of the conferences domain (T017): the bare
 * existence + the V18 `chats.e2ee` boundary flag of a chat — the two
 * attributes of the chat row the create path needs BEFORE anything is
 * written. [e2ee] answers the FR-001/010 v1 boundary («E2EE-чаты не
 * порождают конференций»); the id round-trips only to keep the
 * projection self-describing. No kind, no title, no roster — the
 * minimum the caller reads (constitution V: nothing else crosses the
 * seam).
 */
data class ChatInfo(
    val id: UUID,
    val e2ee: Boolean,
)

/**
 * The chat-membership read port of the conferences domain (DIP: the
 * adapter lives in
 * `webchat.backend.conferences.repository.ChatMembershipCheckerImpl`,
 * T013 — a thin projection over the 006 membership mechanism:
 * `GroupMembershipGate`/`chats.ParticipantRepository.findActive`/
 * `activeMembers`; a conference service NEVER touches the chats ports
 * directly).
 *
 * «Member» here is the 006 ACTIVE-membership row of `chat_participants`
 * — for BOTH chat kinds (direct and group): a stranger, a REMOVED
 * former member (№32 kick/№33 leave) and an unknown chatId answer
 * identically as a non-member, so the join gate renders them all as
 * the same `403 conference_forbidden` without disclosing the chat's
 * existence (FR-003, SC-002; privacy discipline of 006 FR-008).
 */
interface ChatMembershipChecker {
    /**
     * The №44 create resolution of the chat row (T017): `null` → the
     * contract `404 chat_not_found` — an unknown id is answered BEFORE
     * the membership gate and BEFORE any media-plane probe or write.
     * A found row carries the [ChatInfo.e2ee] flag of the v1 boundary
     * with 010 (FR-001: `409 conference_e2ee_unsupported`), read for
     * BOTH chat kinds. One row read, no mutation.
     */
    fun findChatInfo(chatId: UUID): ChatInfo?

    /**
     * The EVERY-JOIN authorization gate (T017 create + T019 №47,
     * FR-003/SC-002): is [userId] an ACTIVE member of the chat RIGHT
     * NOW? Checked BEFORE any participant row and BEFORE the token
     * mint — a non-member never mints (the IT invariant 2 of
     * data-model 009 §примеры), and a member removed mid-call is
     * re-checked here on his next №47 (the T020 listener has already
     * disconnected the live session, FR-003 edge). One row read, no
     * mutation.
     */
    fun isMember(
        chatId: UUID,
        userId: UUID,
    ): Boolean

    /**
     * The SSE addressee snapshot of the lifecycle events (T054
     * `conference.started`, T055 `conference.ended`): the ACTIVE chat
     * members at the moment of the read — the caller takes it INSIDE
     * the transition transaction so the event reaches exactly the
     * roster the conference belonged to («carol, не входя в звонок»,
     * US5-1/SC-006). Deterministic roster order of 006
     * (`created_at, user_id`); a per-user `rt:user:{userId}` publish
     * per entry rides the existing realtime fan-out.
     */
    fun activeMemberIds(chatId: UUID): List<UUID>
}
