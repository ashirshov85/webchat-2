package webchat.backend.sync.domain.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.service.ChatService
import webchat.backend.chats.domain.service.InvalidUpToSeqException
import webchat.backend.config.DeliveryProperties
import java.util.UUID

/**
 * 400 (api-contract.md №25): the ack batch outside the contract bound
 * `1..100` — an empty or an oversized batch is refused BEFORE anything
 * is resolved or written, so no partial effects are possible. The code
 * is carried for the problem+json rendering of the api layer (T014).
 */
class AckBatchLimitException : RuntimeException("the ack batch must carry 1..100 items") {
    val code: String = "limit_out_of_range"
}

/**
 * One typed element of the №25 command (api-contract.md §1): the raw
 * nullable `DeliveryAckItem` of the api layer is parsed into this by the
 * controller (T014 — `400 invalid_uuid` lives there), so the domain
 * never sees a malformed chatId.
 */
data class DeliveryAck(
    val chatId: UUID,
    val upToSeq: Long,
)

/**
 * The delivery-position writer of User Story 1 (T012; api-contract.md
 * №25, data-model сущность 1, FR-001): the batch monotone
 * GREATEST-advance of the CALLER's `delivered_up_to_seq` — the ONLY
 * operation that moves it (neither a №26 sync answer nor an SSE frame
 * writes the position, pinned by DeliveryAckIT).
 *
 * The refusal order is pinned by the contract text and the T007 IT:
 *  1. the batch bound itself: `acks` must carry 1..100 elements
 *     ([DeliveryProperties.Sync.ackBatchLimit]) — an empty or oversized
 *     batch is the 400 of [AckBatchLimitException], refused before any
 *     repository read;
 *  2. the membership precheck of EVERY item through the uniform
 *     `404 chat_not_found → 403 not_participant` gate of
 *     [ChatService.get] — the whole batch is refused by any foreign or
 *     unknown chat, and this precheck covers ALL items BEFORE any seq
 *     bound is judged (a foreign item that also carries an out-of-range
 *     `upToSeq` answers 403, not 400);
 *  3. the seq bound per item: `1 ≤ upToSeq ≤ chats.last_seq` — a
 *     violation is the `400 invalid_up_to_seq` of the reused
 *     [InvalidUpToSeqException] (as №17 in 004: an EMPTY dialog has
 *     head 0, so any ack there is refused — nothing is delivered yet);
 *  4. the advance itself: duplicates of one chatId fold to their max
 *     (exactly the GREATEST the port applies per PK) and the whole
 *     batch lands through [ParticipantRepository.advanceDelivered] —
 *     ONE transaction over per-PK conditional updates (T010), where a
 *     zero rowcount (a repeated or smaller `upToSeq`) is the
 *     idempotent no-op that still answers `204`.
 *
 * Like ReadService, this is deliberately NOT `@Transactional`: the
 * batch transaction is owned by the port adapter, so it is ALREADY
 * COMMITTED when this returns — and the acknowledgements publish no
 * realtime events at all (the position is private per-user state; the
 * peer converges via №12/№26 reads).
 */
@Service
class DeliveryAckService(
    private val chatService: ChatService,
    private val participantRepository: ParticipantRepository,
    private val deliveryProperties: DeliveryProperties,
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Contract №25: `204` in every non-refused case — an actual
     * advance, an idempotent repeat and a smaller `upToSeq` (the
     * GREATEST no-op) all succeed silently. Refusals leave as the typed
     * exceptions of the steps above: 400/403/404 with the batch
     * untouched (no partial effects — nothing has been written before
     * step 4, and step 4 is one transaction).
     */
    fun acknowledge(
        callerId: UUID,
        acks: List<DeliveryAck>,
    ) {
        val timing = Timer.start(meterRegistry)
        enforceBatchBound(acks)
        val chatHeads = resolveMembershipOfEveryItem(callerId, acks)
        enforceSeqBound(acks, chatHeads)
        participantRepository.advanceDelivered(callerId, foldToGreatest(acks))
        timing.stop(ackTimer())
    }

    /** Step 1 — the contract `minItems 1`/`maxItems 100` of №25, refused before any read. */
    private fun enforceBatchBound(acks: List<DeliveryAck>) {
        if (acks.isEmpty() || acks.size > deliveryProperties.sync.ackBatchLimit) throw AckBatchLimitException()
    }

    /**
     * Step 2 — the membership precheck of EVERY item: each chatId walks
     * the uniform `404 → 403` gate of [ChatService.get]; the resolved
     * `chats.last_seq` heads feed the seq bound. Strangers never learn
     * anything about a dialog beyond the refusal itself.
     */
    private fun resolveMembershipOfEveryItem(
        callerId: UUID,
        acks: List<DeliveryAck>,
    ): Map<UUID, Long> = acks.associate { (chatId, _) -> chatId to chatService.get(chatId, callerId).lastSeq }

    /** Step 3 — `1 ≤ upToSeq ≤ chats.last_seq` per item, judged only after step 2 covered the whole batch. */
    private fun enforceSeqBound(
        acks: List<DeliveryAck>,
        chatHeads: Map<UUID, Long>,
    ) {
        acks.forEach { ack ->
            if (ack.upToSeq < MIN_UP_TO_SEQ || ack.upToSeq > chatHeads.getValue(ack.chatId)) {
                throw InvalidUpToSeqException()
            }
        }
    }

    /**
     * Step 4 fold: duplicated chatIds of one batch collapse to their
     * max `upToSeq` — exactly the GREATEST the port applies per PK, so
     * the folded single-statement-per-chat batch is indistinguishable
     * from applying the duplicates in sequence.
     */
    private fun foldToGreatest(acks: List<DeliveryAck>): Map<UUID, Long> =
        acks
            .groupBy(keySelector = { it.chatId })
            .mapValues { (_, items) -> items.maxOf { it.upToSeq } }

    /**
     * T012 (tasks.md Phase 3, FR-014): `webchat_delivery_ack_seconds`
     * times the №25 path from the command arrival to the committed
     * batch advance — only committed acknowledgements record a sample;
     * a refused batch (400/403/404) abandons its sample and stays
     * unobserved here (the same convention as
     * `webchat_message_ack_seconds` of 004).
     */
    private fun ackTimer(): Timer =
        Timer
            .builder(ACK_SECONDS)
            .description("Delivery-ack path latency: №25 command arrival to the committed batch advance (FR-014)")
            .register(meterRegistry)

    private companion object {
        /** The №25 `minimum: 1` of `DeliveryAckItem.upToSeq` — the position starts at the first message. */
        const val MIN_UP_TO_SEQ = 1L

        /** research.md 005 §11 / tasks.md T012 observability contract name. */
        const val ACK_SECONDS = "webchat_delivery_ack_seconds"
    }
}
