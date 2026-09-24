package webchat.backend.sync.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import webchat.backend.chats.api.dto.ChatPeerView
import webchat.backend.chats.api.dto.MessageView
import java.util.UUID

/**
 * Contract №25 request item — `DeliveryAckItem` (api-contract.md §1,
 * openapi.yaml 0.5.0): `{chatId: uuid, upToSeq: int64 ≥ 1}`.
 *
 * Both values arrive as raw nullables on purpose: an absent or malformed
 * `chatId` is rejected in the controller as the contract 400 `errors:
 * {chatId: [invalid_uuid]}` and a missing `upToSeq` as `invalid_up_to_seq`
 * (problem+json), instead of dying in deserialization — the same
 * convention as `EnsureChatRequest`/`SendMessageRequest` (002/004).
 */
data class DeliveryAckItem(
    val chatId: String? = null,
    val upToSeq: Long? = null,
)

/**
 * Contract №25 request body — `DeliveryAckRequest` (api-contract.md §1,
 * openapi.yaml 0.5.0): `{acks: DeliveryAckItem[]}` with 1–100 elements.
 * The whole batch is atomic — one transaction, any failing element
 * rejects the entire batch with no partial effects (403/404/400 mapped
 * per api-contract.md; the size bound lives in `DeliveryProperties`).
 */
data class DeliveryAckRequest(
    val acks: List<DeliveryAckItem>? = null,
)

/**
 * Contract №26 request item — `SyncCursor` (api-contract.md §1,
 * openapi.yaml 0.5.0): `{chatId: uuid, upToSeq: int64 ≥ 0}` — the
 * client-side position of one chat (seq of the last confirmed message).
 *
 * Raw nullables for the same typed-problem reason as
 * [DeliveryAckItem]; unlike №25 the (per-user) sync silently IGNORES
 * foreign/nonexistent chatIds instead of failing the request.
 */
data class SyncCursor(
    val chatId: String? = null,
    val upToSeq: Long? = null,
)

/**
 * Contract №26 request body — `SyncRequest` (api-contract.md §1,
 * openapi.yaml 0.5.0): the per-chat cursor map plus optional page limits
 * `chatLimit` 1–50 (default 20) and `messageLimit` 1–50 (default 50);
 * out-of-range limits are rejected as the contract 400 `limit_out_of_range`
 * — hence the nullable raws, validated against `DeliveryProperties`.
 */
data class SyncRequest(
    val cursors: List<SyncCursor>? = null,
    val chatLimit: Int? = null,
    val messageLimit: Int? = null,
)

/**
 * Contract №26 success item — `SyncChatDelta` (api-contract.md §1,
 * openapi.yaml 0.5.0): the resumption cursor, one ascending message page
 * and the server counters of a chat, computed in a single read snapshot.
 *
 * `messages` reuse the `Message` schema ([MessageView], one schema for
 * №15/№16/SSE/sync — US6 of 004) and `peer` the `PublicUser` projection
 * ([ChatPeerView], as №12). The optionals `truncatedUpToSeq`,
 * `desynced`/`serverUpToSeq` are ABSENT when the phenomenon did not
 * occur (NON_NULL inclusion — never an explicit `null`): truncation and
 * the «cursor from the future» repair are per-chat, without a request
 * error (sync-protocol.md §5).
 */
data class SyncChatDelta(
    val chatId: UUID,
    val peer: ChatPeerView,
    val blockedByMe: Boolean,
    val startAfterSeq: Long,
    @JsonInclude(JsonInclude.Include.NON_NULL) val truncatedUpToSeq: Long? = null,
    val messages: List<MessageView>,
    val hasMore: Boolean,
    val peerReadUpToSeq: Long,
    val unreadCount: Long,
    val lastSeq: Long,
    @JsonInclude(JsonInclude.Include.NON_NULL) val desynced: Boolean? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val serverUpToSeq: Long? = null,
)

/**
 * Contract №26 success body — `SyncResponse` (api-contract.md §1,
 * openapi.yaml 0.5.0): deltas of ONLY the chats with undelivered
 * messages, ordered by the last activity DESC (the newest first), up to
 * `chatLimit`; an EMPTY list is a valid answer (everything delivered).
 * `moreChats` reports undelivered chats left beyond the limit — the
 * client repeats №26 with the refreshed cursors (cycle A,
 * sync-protocol.md §3.1).
 */
data class SyncResponse(
    val chats: List<SyncChatDelta>,
    val moreChats: Boolean,
)
