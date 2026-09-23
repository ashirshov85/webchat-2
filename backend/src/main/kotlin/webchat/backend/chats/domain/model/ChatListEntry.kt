package webchat.backend.chats.domain.model

import java.time.Instant
import java.util.UUID

/**
 * The peer side of a [ChatListEntry] — a `PublicUser`-shaped snapshot
 * (id, username, email, lowercase `user_status`, createdAt) projected by
 * the ONE №12 list query (research.md 004 §8). A read-only value object:
 * the snapshot travels with the entry and is never resolved separately,
 * so the panel costs a single round trip.
 */
data class ChatPeerSnapshot(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
)

/**
 * One row of the №12 `GET /api/v1/chats` answer (T055, api-contract.md
 * №12, openapi.yaml 0.4.0 `ChatListItem`): the dialog id, the peer
 * snapshot, the LAST VISIBLE message (`seq > deleted_up_to_seq` — the
 * per-user deletion watermark of data-model 004 §2; `null` for a chat
 * without visible messages), the unread badge count (incoming visible
 * messages above the caller's read watermark) and the caller's OWN block
 * mark (FR-020 — the only block projection).
 *
 * The entry is a READ model: every field is derived in the single list
 * query of the repository adapter; nothing here mutates dialog state.
 */
data class ChatListEntry(
    val chatId: UUID,
    val peer: ChatPeerSnapshot,
    val lastMessage: Message?,
    val unreadCount: Long,
    val blockedByMe: Boolean,
)
