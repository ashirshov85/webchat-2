package webchat.backend.chats.domain.port

import webchat.backend.chats.domain.model.Message
import java.util.UUID

/**
 * `message.created` frame payload (realtime-channel.md §3.1;
 * `components.schemas.MessageCreatedEvent`): the full contract `Message` —
 * the server has committed the record BEFORE publishing (FR-005/FR-007),
 * so receiving devices render it already «доставлено». Clients deduplicate
 * by `message.id` (at-most-once channel + exactly-once storage, US2-6).
 */
data class MessageCreatedEvent(
    val chatId: UUID,
    val message: Message,
)

/**
 * `chat.read` frame payload (realtime-channel.md §3.2;
 * `components.schemas.ChatReadEvent`): the peer advanced their read
 * watermark — senders mark their outgoing `seq <= readUpToSeq` with ✓✓.
 * Published only on an actual advancement (the GREATEST-update rowcount
 * gate, US4-5); repeated/smaller values never publish.
 */
data class ChatReadEvent(
    val chatId: UUID,
    val readUpToSeq: Long,
    val byUserId: UUID,
)

/**
 * Outbound realtime fan-out port (research.md 004 §2; DIP: the Redis
 * adapter lives outside the domain in
 * `webchat.backend.realtime.RedisRealtimePublisher`, T019).
 *
 * Publish-only: channel subscriptions and SSE emitters are transport
 * concerns of the realtime adapters (T018/T019), never of the domain.
 * Implementations publish a compact JSON envelope to the per-user Redis
 * Pub/Sub channel `rt:user:{userId}` strictly AFTER the PG commit
 * (data-model 004 §3 step 5) — the channel is at-most-once, and a lost or
 * failed delivery must NOT fail the already-durable request: the client
 * converges via the refetch on (re)connect (FR-009, constitution II).
 */
interface RealtimeEventPublisher {
    /**
     * FR-007: fanned out to BOTH participants' channels by the caller
     * (T014) — the recipient renders the message; the sender's OTHER
     * devices/sessions render it as already delivered (US2-6).
     */
    fun publishMessageCreated(
        toUserId: UUID,
        event: MessageCreatedEvent,
    )

    /**
     * FR-010 (T042): sent to the PEER's channel only; the blocking-pair
     * suppression of FR-020 is decided by the caller — this port never
     * reveals blocking state on its own.
     */
    fun publishChatRead(
        toUserId: UUID,
        event: ChatReadEvent,
    )
}
