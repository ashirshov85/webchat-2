package webchat.backend.sync.domain.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.api.dto.ChatPeerView
import webchat.backend.chats.api.dto.MessageView
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.UndeliveredChat
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.service.LimitOutOfRangeException
import webchat.backend.config.DeliveryProperties
import webchat.backend.contacts.domain.port.BlockRepository
import webchat.backend.sync.api.dto.SyncChatDelta
import webchat.backend.sync.api.dto.SyncResponse
import java.util.UUID

/**
 * One typed element of the №26 command (api-contract.md §1): the raw
 * nullable `SyncCursor` of the api layer is parsed into this by the
 * controller (T014 — the `400 invalid_uuid` problem lives there), so the
 * domain never sees a malformed chatId. Unlike the atomic №25 batch, a
 * cursor of a foreign or nonexistent chat is silently IGNORED, never a
 * refusal (a per-user operation answers only with the caller's own
 * dialogs). Duplicated chatIds fold to their max `upToSeq` — the same
 * GREATEST discipline the candidate read applies.
 */
data class ClientCursor(
    val chatId: UUID,
    val upToSeq: Long,
) {
    init {
        require(upToSeq >= 0) { "a client cursor must not be negative (contract int64 ≥ 0)" }
    }
}

/**
 * The catch-up synchronization of User Story 1 (T013; api-contract.md №26,
 * sync-protocol.md §3/§5, data-model 005 сущности 1–3, FR-003): the
 * aggregated per-cursor pull replaying the undelivered backlog of EVERY
 * dialog in one answer — completeness/order core of SC-001.
 *
 * The sweep is a PURE READ and never moves the delivery position (FR-001 —
 * pinned by SyncIT probes of `chat_participants.delivered_up_to_seq`:
 * only the ack №25 writes it); per chat:
 *
 *  1. the effective cursor — sync-protocol.md §1: `max(клиентский,
 *     серверный)`; the candidate read of [ParticipantRepository.loadForSync]
 *     already folds the client cursors into its candidacy bound (a chat
 *     caught up by its cursor leaves the page entirely, a foreign id
 *     rides along ignored), so pagination and `moreChats` stay
 *     cursor-aware in one read snapshot;
 *  2. the «курсор из будущего» repair — sync-protocol.md §5: a client
 *     cursor BEYOND the chat head is NOT a request error and NOT an
 *     adoption: the delta carries `desynced: true` + `serverUpToSeq`
 *     (the actual server position, which the server never rewinds) and
 *     resumes from it, while the other chats sync normally;
 *  3. the truncation — sync-protocol.md §5: the effective lower bound
 *     `GREATEST(delivered, deleted_up_to_seq)` (the 12-month history
 *     window leg waits for archival 009); a resume point below the floor
 *     surfaces `truncatedUpToSeq` with `startAfterSeq` pinned to the
 *     floor — messages below are inaccessible, not unread (US1-5), and
 *     the position itself moves only when the client acks the point;
 *  4. the delta page — [MessageRepository.findVisiblePageAfter]:
 *     strictly ascending `(startAfterSeq, …]`, at most `messageLimit`,
 *     visible AND including the caller's own outgoing messages (order/
 *     context), with `hasMore = lastSeq > seq(page last)` closing only
 *     at the chat head;
 *  5. the server counters — `peerReadUpToSeq` (the peer's read
 *     watermark — the ✓✓ catch-up of US3-8) and `unreadCount` through
 *     the ONE reusable calculator [ParticipantRepository.countUnread]
 *     (delivery-bounded: the current page does not count until the ack).
 *
 * All-or-refusal (the «сервер частично доступен» edge): every per-chat
 * read failure propagates and refuses the WHOLE request — a partial chat
 * list may never parade as the full one («частичный список как полный»
 * исключён); the refusal is temporary, the identical repeat answers fully.
 */
@Service
class SyncService(
    private val participantRepository: ParticipantRepository,
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val blockRepository: BlockRepository,
    private val deliveryProperties: DeliveryProperties,
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Contract №26: `200 SyncResponse` — the deltas of ONLY the chats
     * with undelivered content beyond the effective cursor, latest
     * activity first (the candidate read's order), up to `chatLimit`;
     * an EMPTY chat list is the correct full answer when everything is
     * delivered. Limits default from [DeliveryProperties.Sync] and are
     * bound by the contract `1..50` — a violation is the reused
     * `400 limit_out_of_range` of [LimitOutOfRangeException].
     */
    fun sync(
        callerId: UUID,
        cursors: List<ClientCursor>,
        chatLimit: Int?,
        messageLimit: Int?,
    ): SyncResponse {
        val timing = Timer.start(meterRegistry)
        val effectiveChatLimit = chatLimit ?: deliveryProperties.sync.chatPageSize
        val effectiveMessageLimit = messageLimit ?: deliveryProperties.sync.messagePageSize
        if (effectiveChatLimit !in 1..MAX_PAGE_LIMIT || effectiveMessageLimit !in 1..MAX_PAGE_LIMIT) {
            throw LimitOutOfRangeException()
        }
        val folded = foldToGreatest(cursors)
        val page = participantRepository.loadForSync(callerId, folded, effectiveChatLimit)
        val deltas =
            page.chats.map { candidate ->
                deltaOf(callerId, candidate, folded[candidate.chatId] ?: NO_CURSOR, effectiveMessageLimit)
            }
        val response = SyncResponse(chats = deltas, moreChats = page.moreChats)
        timing.stop(requestTimer(outcomeOf(response)))
        recordDeliveryCounters(deltas)
        return response
    }

    /**
     * The per-chat delta (data-model сущность 2): every field derives in
     * this one sweep — the repair flags [SyncChatDelta.desynced]/
     * [SyncChatDelta.serverUpToSeq] and [SyncChatDelta.truncatedUpToSeq]
     * stay ABSENT (NON_NULL) when the phenomenon did not occur. The
     * candidate always carries a visible undelivered head (the candidacy
     * bound), so the page is never empty here.
     */
    private fun deltaOf(
        callerId: UUID,
        candidate: UndeliveredChat,
        clientCursor: Long,
        messageLimit: Int,
    ): SyncChatDelta {
        val desynced = clientCursor > candidate.chatLastSeq
        val effective =
            if (desynced) candidate.deliveredUpToSeq else maxOf(clientCursor, candidate.deliveredUpToSeq)
        val startAfter = maxOf(effective, candidate.deletedUpToSeq)
        val truncatedUpToSeq = (startAfter > effective).takeIf { it }?.let { startAfter }

        val messages = messageRepository.findVisiblePageAfter(candidate.chatId, callerId, startAfter, messageLimit)
        val pageHead = messages.lastOrNull()?.seq ?: startAfter
        val peerParticipant =
            participantRepository.findForChat(candidate.chatId).firstOrNull { it.userId != callerId }
                ?: error("chat ${candidate.chatId} must carry the peer participant row of the dialog")
        val peer =
            userRepository.findById(peerParticipant.userId)
                ?: error("chat ${candidate.chatId} peer ${peerParticipant.userId} does not resolve")

        return SyncChatDelta(
            chatId = candidate.chatId,
            peer = peerView(peer),
            blockedByMe = blockRepository.exists(callerId, peer.id),
            startAfterSeq = startAfter,
            truncatedUpToSeq = truncatedUpToSeq,
            messages = messages.map(::messageView),
            hasMore = candidate.chatLastSeq > pageHead,
            peerReadUpToSeq = peerParticipant.lastReadSeq,
            unreadCount = participantRepository.countUnread(callerId, candidate.chatId),
            lastSeq = candidate.chatLastSeq,
            desynced = desynced.takeIf { it },
            serverUpToSeq = candidate.deliveredUpToSeq.takeIf { desynced },
        )
    }

    /**
     * Duplicated chatIds collapse to their max `upToSeq` — exactly the
     * GREATEST the candidate read folds into its bound, so a duplicated
     * cursor is indistinguishable from applying the duplicates in
     * sequence (the same fold as [DeliveryAckService.acknowledge]).
     */
    private fun foldToGreatest(cursors: List<ClientCursor>): Map<UUID, Long> =
        cursors
            .groupBy(keySelector = { it.chatId })
            .mapValues { (_, items) -> items.maxOf { it.upToSeq } }

    /** The `PublicUser` projection of the delta peer — the same shape as №12/№13 (ChatController). */
    private fun peerView(peer: User): ChatPeerView =
        ChatPeerView(
            id = peer.id,
            username = peer.username,
            email = peer.email,
            status = peer.status.name.lowercase(),
            createdAt = peer.createdAt,
        )

    /** The reused `Message` schema (one schema for №15/№16/SSE/sync — US6 of 004). */
    private fun messageView(message: Message): MessageView =
        MessageView(
            id = message.id,
            chatId = message.chatId,
            senderId = message.senderId,
            text = message.text.value,
            seq = message.seq,
            createdAt = message.createdAt,
        )

    /**
     * T013 (tasks.md Phase 3, FR-014): the №26 observability — the replay
     * volume and the two repair counters record only on a COMPLETED
     * sweep (a refused request abandons its samples, the same convention
     * as `webchat_delivery_ack_seconds` of T012):
     * `webchat_sync_messages_delivered_total` — the messages handed out
     * by the deltas (the catch-up volume), `webchat_sync_truncated_total`
     * — the truncation repairs, `webchat_sync_desynced_total` — the
     * «cursor from the future» repairs.
     */
    private fun recordDeliveryCounters(deltas: List<SyncChatDelta>) {
        val messages = deltas.sumOf { it.messages.size }
        if (messages > 0) {
            counter(MESSAGES_DELIVERED_TOTAL, "Messages replayed by №26 sync deltas (catch-up volume, FR-014)")
                .increment(messages.toDouble())
        }
        val truncations = deltas.count { it.truncatedUpToSeq != null }
        if (truncations > 0) {
            counter(
                TRUNCATED_TOTAL,
                "№26 truncation repairs — the resume point pinned to the visibility floor (FR-014)",
            ).increment(truncations.toDouble())
        }
        val repairs = deltas.count { it.desynced == true }
        if (repairs > 0) {
            counter(DESYNCED_TOTAL, "№26 future-cursor repairs — desynced chats answered with serverUpToSeq (FR-014)")
                .increment(repairs.toDouble())
        }
    }

    private fun counter(
        name: String,
        description: String,
    ): Counter = Counter.builder(name).description(description).register(meterRegistry)

    /**
     * T013 (research.md 005 §8, FR-014): `webchat_sync_request_seconds`
     * times the №26 sweep from the command arrival to the completed
     * answer; the `outcome` tag splits the full catch-up sweeps (nothing
     * left to fetch) from the continuation ones (`hasMore`/`moreChats`
     * pages — the client repeats №26 with the refreshed cursors).
     */
    private fun requestTimer(outcome: String): Timer =
        Timer
            .builder(REQUEST_SECONDS)
            .description("Catch-up sync path latency: №26 command arrival to the completed sweep (FR-014)")
            .tag(TAG_OUTCOME, outcome)
            .register(meterRegistry)

    private fun outcomeOf(response: SyncResponse): String =
        if (response.moreChats || response.chats.any { it.hasMore }) OUTCOME_CONTINUATION else OUTCOME_FULL

    private companion object {
        /** api-contract.md №26: `chatLimit`/`messageLimit` are bound `1..50` (defaults from DeliveryProperties). */
        const val MAX_PAGE_LIMIT = 50

        /** sync-protocol.md §1: a chat without a client cursor resumes from the server position. */
        const val NO_CURSOR = 0L

        /** research.md 005 §8 / tasks.md T013 observability contract names. */
        const val REQUEST_SECONDS = "webchat_sync_request_seconds"
        const val MESSAGES_DELIVERED_TOTAL = "webchat_sync_messages_delivered_total"
        const val TRUNCATED_TOTAL = "webchat_sync_truncated_total"
        const val DESYNCED_TOTAL = "webchat_sync_desynced_total"

        const val TAG_OUTCOME = "outcome"
        const val OUTCOME_FULL = "full"
        const val OUTCOME_CONTINUATION = "continuation"
    }
}
