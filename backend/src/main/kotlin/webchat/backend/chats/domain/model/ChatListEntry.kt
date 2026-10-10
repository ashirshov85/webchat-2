package webchat.backend.chats.domain.model

import webchat.backend.groups.domain.model.MemberRole
import java.time.Instant
import java.util.UUID

/**
 * The peer side of a [ChatListEntry] — a `PublicUser`-shaped snapshot
 * (id, username, email, lowercase `user_status`, createdAt) projected by
 * the ONE №12 list query (research.md 004 §8). A read-only value object:
 * the snapshot travels with the entry and is never resolved separately,
 * so the panel costs a single round trip. Since 006 it is carried only
 * by DIRECT rows — a group has no peer, its element answers `peer: null`
 * (api-contract.md 006 §3).
 *
 * 008a (data-model §3, T015): the optional [displayName] of the peer's
 * profile (V16 `users.display_name`, NULL = «not set») joins the SAME
 * aggregate query — the panel keeps its single-round-trip property; the
 * caller's personal `peerAlias` is NOT part of the snapshot (it lives on
 * `user_contacts` and is batch-joined by the service layer through
 * `ContactRepository.aliasesOf`).
 */
data class ChatPeerSnapshot(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
    val displayName: String? = null,
)

/**
 * One row of the №12 `GET /api/v1/chats` answer (T055, api-contract.md
 * №12, openapi.yaml `ChatListItem`): the unified «Чаты» panel of 006 —
 * a DIRECT dialog or a GROUP (FR-014) discriminated by [kind] (T023).
 * Direct rows carry the dialog id, the peer snapshot, the LAST VISIBLE
 * message (`seq > deleted_up_to_seq` — the per-user deletion watermark
 * of data-model 004 §2; `null` for a chat without visible messages), the
 * unread badge count and the caller's OWN block mark (FR-020 — the only
 * block projection). Group rows carry the group projection of 006
 * (api-contract.md §3): `title`, the ACTIVE roster size
 * ([memberCount], including the owner — the capacity/limit counter of
 * `ix_chat_participants_chat_active`) and the caller's [myRole]; their
 * peer/block fields are `null` — blocks never apply to groups
 * (Assumptions 006).
 *
 * The badge (005 T031, data-model сущность 3, FR-007) is
 * server-authoritative and delivery-bounded: incoming messages with
 * `GREATEST(last_read_seq, deleted_up_to_seq) < seq ≤
 * LEAST(chats.last_seq, delivered_up_to_seq)` — it grows ONLY by the
 * delivery ack №25 (a realtime frame and a №26 sync page do not count
 * until acked), and below the truncation point messages are
 * inaccessible, not unread (US1-5). The SAME formula serves both kinds
 * — a group is a `chats` row with shared history, so the 004/005
 * mechanics carry over unchanged (plan.md 006).
 *
 * The entry is a READ model: every field is derived in the single list
 * query of the repository adapter; nothing here mutates dialog state.
 *
 * 008a (data-model §1.3, T052): [soundEnabled] — the caller's PERSONAL
 * per-chat sound switch (`chat_participants.sound_enabled`, FR-012)
 * rides the SAME aggregate query for BOTH kinds (the server sets the
 * №12 slot always; `true` for a fresh participation by the V16 column
 * default). Strictly the row owner's own projection: the value and the
 * very fact of the setting never reach another participant's panel.
 */
data class ChatListEntry(
    val chatId: UUID,
    val kind: ChatKind = ChatKind.DIRECT,
    val title: String? = null,
    val memberCount: Long? = null,
    val myRole: MemberRole? = null,
    val peer: ChatPeerSnapshot? = null,
    val lastMessage: Message?,
    val unreadCount: Long,
    val blockedByMe: Boolean? = null,
    // 008a (V16, data-model §1.3): the personal sound switch of the
    // CALLER's own row — default TRUE mirrors the column DEFAULT of a
    // fresh participation.
    val soundEnabled: Boolean = true,
) {
    init {
        // The kind discrimination of the unified №12 row (api-contract.md
        // 006 §3): exactly one side of the projection is populated — a
        // direct dialog answers the pair fields, a group the roster
        // fields with nulled peer/block marks.
        when (kind) {
            ChatKind.DIRECT -> {
                requireNotNull(peer) { "a direct №12 row requires the peer snapshot" }
                requireNotNull(blockedByMe) { "a direct №12 row requires the caller's block mark" }
                require(title == null && memberCount == null && myRole == null) {
                    "group fields never appear on a direct №12 row"
                }
            }
            ChatKind.GROUP -> {
                require(peer == null && blockedByMe == null) {
                    "a group №12 row carries no peer/block projection (blocks never apply, Assumptions 006)"
                }
                requireNotNull(title) { "a group №12 row requires the title" }
                requireNotNull(memberCount) { "a group №12 row requires the active member count" }
                require(memberCount >= 1) { "an active group has at least its owner" }
                requireNotNull(myRole) { "a group №12 row requires the caller's role" }
            }
        }
    }
}
