package webchat.backend.chats.domain.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import webchat.backend.chats.domain.port.ChatReadEvent
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import java.util.UUID

/**
 * 400 (api-contract.md №17): `upToSeq` is outside the FR-010 bound
 * `1..seq of the last recorded message of the dialog` — refused BEFORE
 * the watermark is touched, so nothing moves.
 */
class InvalidUpToSeqException : RuntimeException("upToSeq is outside the recorded seq range of the dialog") {
    val code: String = "invalid_up_to_seq"
}

/**
 * The read path of User Story 4 (T042; api-contract.md №17, FR-010;
 * research.md 004 §5) — the per-user watermark advance and its
 * exactly-once realtime announcement:
 *
 *  1. the membership gate of every chats resource (FR-002):
 *     `404 chat_not_found` first, then `403 not_participant` — resolved
 *     by [ChatService.get] BEFORE the payload is judged, so a stranger
 *     never learns anything about the dialog;
 *  2. the FR-010 bound `1 ≤ upToSeq ≤ chats.last_seq` — a violation is
 *     the `400 invalid_up_to_seq` of №17 with the watermark unmoved;
 *  3. the monotone GREATEST-update owned by
 *     [ParticipantRepository.advanceReadUpTo]: `UPDATE … SET
 *     last_read_seq = GREATEST(last_read_seq, :upToSeq) WHERE … AND
 *     last_read_seq < :upToSeq` — a single conditional statement, so it
 *     is ALREADY COMMITTED when it returns. Rowcount 0 (a repeated or
 *     smaller `upToSeq`) is the idempotent no-op of US4-5: `204`,
 *     nothing changes, NO event;
 *  4. rowcount > 0 → the post-commit publish of `chat.read
 *     {chatId, readUpToSeq, byUserId}` to the PEER's channel only
 *     (realtime-channel.md §3.2 — the sender flips ✓→✓✓; the reader's
 *     own devices already hold their watermark locally).
 *
 * This service is deliberately NOT `@Transactional`: the conditional
 * UPDATE is one auto-committed statement, hence the publication below is
 * strictly post-commit — the same ordering discipline as
 * [MessageService.send] (a pre-commit publish could announce a watermark
 * that a rollback then erases).
 *
 * Later stories join without restructuring: the blocking-pair
 * suppression of FR-020 (T054) wraps the UPDATE/publish legs, and the
 * `webchat_read_advanced_total` counter (T046) hooks the advance below.
 */
@Service
class ReadService(
    private val chatService: ChatService,
    private val participantRepository: ParticipantRepository,
    private val realtimeEventPublisher: RealtimeEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ReadService::class.java)

    /**
     * Contract №17: `204` in every non-refused case — an actual advance
     * (event published), an idempotent repeat and a smaller `upToSeq`
     * (silent no-op, US4-5). Refusals leave as typed exceptions: 404/403
     * from the membership gate, `400 invalid_up_to_seq` from the bound.
     */
    fun markRead(
        chatId: UUID,
        callerId: UUID,
        upToSeq: Long,
    ) {
        val chat = chatService.get(chatId, callerId)
        if (upToSeq < MIN_UP_TO_SEQ || upToSeq > chat.lastSeq) throw InvalidUpToSeqException()

        val advanced = participantRepository.advanceReadUpTo(chatId, callerId, upToSeq) ?: return
        readAdvancedTotal().increment()
        val peerId =
            checkNotNull(chat.peerOf(callerId)) {
                "the caller passed the membership gate, so the peer must resolve"
            }
        publishIsolated(
            peerId,
            ChatReadEvent(chatId = chat.id, readUpToSeq = advanced.lastReadSeq, byUserId = callerId),
        )
    }

    /**
     * At-most-once isolation (the port contract, FR-009/constitution II):
     * the watermark advance is already durable when this runs, so a lost
     * or failed realtime delivery must NOT fail the answered `204` — the
     * peer converges via the refetch on (re)connect. The warn carries ids
     * only, never dialog contents (constitution V, SC-008).
     */
    @Suppress("TooGenericExceptionCaught") // the transport adapter signals any delivery failure by throwing
    private fun publishIsolated(
        targetUserId: UUID,
        event: ChatReadEvent,
    ) {
        try {
            realtimeEventPublisher.publishChatRead(targetUserId, event)
        } catch (failure: Exception) {
            log.warn(
                "realtime publish of chat.read (chat <{}>, seq {}) to user <{}> failed; " +
                    "the watermark is durable and the peer converges on refetch (FR-009): {}",
                event.chatId,
                event.readUpToSeq,
                targetUserId,
                failure.message,
            )
        }
    }

    /**
     * T046 (research.md 004 §11, SC-008): `webchat_read_advanced_total`
     * counts every ACTUAL watermark advance — the rowcount > 0 leg ONLY;
     * the idempotent no-ops of US4-5 (a repeated or smaller `upToSeq`)
     * and the `400/403/404` refusals never increment it, so the counter
     * is exactly the read-path signal the SC-007 budget is judged by.
     */
    private fun readAdvancedTotal(): Counter =
        Counter
            .builder(READ_ADVANCED_TOTAL)
            .description("Read-path watermark advances: the last_read_seq GREATEST-update actually moved (SC-007)")
            .register(meterRegistry)

    private companion object {
        /** FR-010 bound: the watermark starts at the first message — `upToSeq ≥ 1`. */
        const val MIN_UP_TO_SEQ = 1L

        /** research.md 004 §11 observability contract name. */
        const val READ_ADVANCED_TOTAL = "webchat_read_advanced_total"
    }
}
