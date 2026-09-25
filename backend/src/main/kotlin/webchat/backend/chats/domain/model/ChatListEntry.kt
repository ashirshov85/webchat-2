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
 * №12, openapi.yaml `ChatListItem`): the dialog id, the peer
 * snapshot, the LAST VISIBLE message (`seq > deleted_up_to_seq` — the
 * per-user deletion watermark of data-model 004 §2; `null` for a chat
 * without visible messages), the unread badge count and the caller's OWN
 * block mark (FR-020 — the only block projection).
 *
 * The badge (005 T031, data-model сущность 3, FR-007) is
 * server-authoritative and delivery-bounded: incoming messages with
 * `GREATEST(last_read_seq, deleted_up_to_seq) < seq ≤
 * LEAST(chats.last_seq, delivered_up_to_seq)` — it grows ONLY by the
 * delivery ack №25 (a realtime frame and a №26 sync page do not count
 * until acked), and below the truncation point messages are
 * inaccessible, not unread (US1-5).
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
