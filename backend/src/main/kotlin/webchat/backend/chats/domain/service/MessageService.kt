package webchat.backend.chats.domain.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import webchat.backend.backpressure.AdmissionDecision
import webchat.backend.backpressure.AdmissionLease
import webchat.backend.backpressure.SendAdmissionGate
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewMessage
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.config.ChatsProperties
import java.util.UUID

/**
 * 409 (api-contract.md №16): the client-supplied `clientMessageId` already
 * belongs to a DIFFERENT stored record (a foreign id or a cross-chat retry)
 * — the send is refused and nothing is written.
 */
class MessageIdConflictException : RuntimeException("the clientMessageId belongs to another stored message") {
    val code: String = "message_id_conflict"
}

/**
 * 429 (api-contract.md №16, FR-011): the per-user send flood limit is
 * exhausted — [retryAfterSeconds] is the integral ceiling of the wait for
 * the next available token, rendered by the api layer as the
 * `Retry-After` header. Nothing is written by a refused send.
 */
class FloodLimitException(
    val retryAfterSeconds: Long,
) : RuntimeException("the per-user send flood limit is exhausted") {
    val code: String = "flood_limit"
}

/**
 * 503 (api-contract.md 005 §3, FR-009/FR-013, T041): the per-instance
 * admission control SHED this send attempt — the path is loaded beyond
 * its adaptive limit. [retryAfterSeconds] is the integral drain estimate
 * of research.md 005 §6 (`ceil(in-flight × EWMA / max(limit, 1))`,
 * floored at 1), rendered by the api layer as the `Retry-After` header
 * of the `503 server_busy` problem+json. A shed writes NOTHING and burns
 * no flood token (FR-010) — the system signal stays ahead of the
 * personal limit, and the shed send retries idempotently by the same
 * `clientMessageId` (US4: the client parks it in the transparent queue).
 */
class ServerBusyException(
    val retryAfterSeconds: Long,
) : RuntimeException("the send path is overloaded beyond its admission limit") {
    val code: String = "server_busy"
}

/**
 * 403 (api-contract.md №16, FR-020): the SENDER blocks the recipient —
 * the refusal the blocker himself sees («вы заблокировали получателя»).
 * Rejected BEFORE the INSERT, so no record and no publication exist.
 */
class ChatBlockedByYouException : RuntimeException("the caller blocks the peer of this chat (FR-020)") {
    val code: String = "chat_blocked_by_you"
}

/**
 * 403 (api-contract.md №16, FR-020): the RECIPIENT blocks the sender —
 * the explicit notification of the blocked user, his ONLY way to learn
 * about the block (the API carries no inverse block field). Rejected
 * BEFORE the INSERT, so no record and no publication exist.
 */
class YouAreBlockedException : RuntimeException("the peer of this chat blocks the caller (FR-020)") {
    val code: String = "you_are_blocked"
}

/**
 * The outcome of a send for the api layer (T017): [Created] maps to
 * `201 Message` (a fresh durable record), [Existing] maps to `200 Message`
 * (the FR-004 idempotent retry — the same row, never a duplicate).
 */
sealed interface MessageSendResult {
    val message: Message

    data class Created(
        override val message: Message,
    ) : MessageSendResult

    data class Existing(
        override val message: Message,
    ) : MessageSendResult
}

/**
 * The send path of the exactly-once core (data-model 004 §3 «Запись»):
 *
 *  0. the dedup fast-path (T031) — a lookup by the client UUID BEFORE the
 *     membership gate, the limits and the locks: a retry of an
 *     already-recorded message resolves to the SAME stored row (`200`),
 *     so it can never be flood-penalized (T032) nor blocked (T054) — the
 *     retry always converges (US2-1/2);
 *  0b. the admission gate (T041; api-contract.md 005 §3, FR-009/FR-013) —
 *     STRICTLY after the dedup fast-path and BEFORE the membership,
 *     validation and flood legs: a retry of an already recorded id has
 *     already resolved through the dedup and never reaches the gate, so
 *     it can never be shed; a shed ([AdmissionDecision.Shed] →
 *     [ServerBusyException] → `503 server_busy` + `Retry-After`) writes
 *     nothing and burns no flood token (FR-010), and an admitted send
 *     holds its in-flight lease ([AdmissionDecision.Admitted.lease]) for
 *     the synchronous send leg — settled exactly once via `use`, with
 *     [webchat.backend.backpressure.AdmissionLease.acked] feeding the
 *     `201`/`200` hold into the limiter's latency signal only;
 *  1. membership (FR-002, T014) → FR-003 text normalization → the
 *     per-user flood bucket (FR-011, T032: `rl:user:msgsend:{userId}`,
 *     30 tokens/min in Redis — AFTER the dedup, BEFORE the INSERT, so a
 *     refused send consumes its token but writes nothing) → the FR-020
 *     blocking-pair gate (T054: BOTH directions checked before the
 *     INSERT) → the exactly-once INSERT (`ON CONFLICT`, owned by
 *     [MessageRepository.insert]) → `201` strictly after the PG commit →
 *     `message.created` published to `rt:user:{sender}` AND
 *     `rt:user:{recipient}` AFTER that commit (FR-004 basis, FR-007).
 *
 * The race leg of the dedup (an empty `RETURNING` of parallel retries)
 * stays owned by the repository: it SELECTs the winning row and reports
 * [MessageInsertResult.Duplicate], mapped here to the same `200`/`409`
 * rules as the fast-path.
 *
 * This service is deliberately NOT `@Transactional`:
 * [MessageRepository.insert] owns the single PG transaction (T012), and it
 * is already COMMITTED when it returns — so the publication below is
 * strictly post-commit (data-model 004 §3 step 5; a pre-commit publish
 * could announce a record that a rollback then erases). Wrapping `send`
 * in a transaction would break the FR-005/FR-007 ordering guarantee.
 *
 * Later stories insert their gates into this path without restructuring
 * it: the blocking-pair refusals (T054) — AFTER the dedup fast-path and
 * between the flood bucket and the INSERT — are in place: a retry of an
 * already recorded message resolves through the dedup FIRST and is never
 * blocked (FR-020 edge), while every fresh send into a blocked pair is
 * refused before any record with `403 chat_blocked_by_you` /
 * `403 you_are_blocked` and publishes nothing into either stream. The
 * admission gate (T041) joins the same way `SendPolicyGate` did in 004 —
 * injected from the outside (DIP, plan.md VIII), one port call on the
 * path. The delivery legs BEYOND the gate stay ungated («принятые
 * доставляются в приоритете», US4-4): the realtime fan-out below and the
 * №26 pull synchronization run without admission — overload may only
 * slow ACCEPTANCE, never the delivery of accepted messages.
 */
@Service
@Suppress("LongParameterList") // the №16 path collaborators, one per leg (T041 adds the admission gate port)
class MessageService(
    private val chatService: ChatService,
    private val messageRepository: MessageRepository,
    private val realtimeEventPublisher: RealtimeEventPublisher,
    private val chatsProperties: ChatsProperties,
    private val sendPolicyGate: SendPolicyGate,
    private val sendAdmissionGate: SendAdmissionGate,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(MessageService::class.java)

    fun send(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        rawText: String,
    ): MessageSendResult {
        val ack = Timer.start(meterRegistry)
        resolveDedupFastPath(chatId, senderId, clientMessageId)?.let { recorded ->
            dedupTotal().increment()
            ack.stop(ackTimer(OUTCOME_EXISTING))
            return MessageSendResult.Existing(recorded)
        }
        // T041 (api-contract.md 005 §3): the admission gate joins the path
        // exactly here — AFTER the dedup fast-path (a retry never reaches
        // it, FR-009), BEFORE membership/validation/flood (a shed is the
        // system signal outranking every per-request refusal). The lease
        // settles exactly once via `use` on every outcome, and only the
        // durable `201`/`200` outcomes feed it a latency sample (acked).
        return when (val decision = sendAdmissionGate.admit(chatId, senderId)) {
            is AdmissionDecision.Shed -> throw shedRefusal(chatId, senderId, decision.retryAfterSeconds)
            is AdmissionDecision.Admitted ->
                decision.lease.use { lease ->
                    executeAdmittedSend(ack, lease, chatId, senderId, clientMessageId, rawText)
                }
        }
    }

    /**
     * T041 (SC-005): the warn carrier of a shed — ids only, never the
     * message text (constitution V, the PII minimization of 004); the
     * refusal itself leaves to the api layer as the typed
     * [ServerBusyException] rendered `503 server_busy` + `Retry-After`.
     */
    private fun shedRefusal(
        chatId: UUID,
        senderId: UUID,
        retryAfterSeconds: Long,
    ): ServerBusyException {
        log.warn(
            "send admission shed: chat <{}>, sender <{}>, retry after <{}> s " +
                "(503 server_busy, SC-005; no row written, no flood token burned)",
            chatId,
            senderId,
            retryAfterSeconds,
        )
        return ServerBusyException(retryAfterSeconds)
    }

    /**
     * The gated remainder of the №16 path (steps 1+ of the class doc):
     * membership → normalization → flood → blocking pair → INSERT → the
     * post-commit fan-out. The [webchat.backend.backpressure.AdmissionLease]
     * settles at the `use` of the caller on EVERY outcome; only the durable
     * `201 Created` / `200 Existing` settles mark [AdmissionLease.acked] —
     * a typed refusal thrown here releases its slot WITHOUT a latency
     * sample (a fast 4xx/429 never measured the PG-commit leg).
     */
    private fun executeAdmittedSend(
        ack: Timer.Sample,
        lease: AdmissionLease,
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
        rawText: String,
    ): MessageSendResult {
        val chat = chatService.get(chatId, senderId)
        val text = MessageText.normalize(rawText, chatsProperties.message.maxLength)
        sendPolicyGate.enforceFloodLimit(senderId)
        sendPolicyGate.enforceBlockPair(chat, senderId)
        val outcome =
            messageRepository.insert(
                NewMessage(id = clientMessageId, chatId = chat.id, senderId = senderId, text = text),
            )
        return when (outcome) {
            is MessageInsertResult.Inserted -> {
                publishCreated(chat, outcome.message)
                ack.stop(ackTimer(OUTCOME_CREATED))
                lease.acked()
                MessageSendResult.Created(outcome.message)
            }
            is MessageInsertResult.Duplicate -> {
                val existing = outcome.existing
                if (existing.chatId != chat.id || existing.senderId != senderId) {
                    throw MessageIdConflictException()
                }
                dedupTotal().increment()
                ack.stop(ackTimer(OUTCOME_EXISTING))
                lease.acked()
                MessageSendResult.Existing(existing)
            }
        }
    }

    /**
     * data-model 004 §3 step 0 (research.md 004 §3): the dedup fast-path —
     * the lookup by the client UUID runs BEFORE the membership gate, the
     * limits and the locks, so a retry of an already-recorded message
     * always converges to the same stored row (US2-1/2): the flood bucket
     * (T032) and the blocking-pair refusals (T054) can never interfere
     * with it.
     *
     * A found row is compared to the request by `chat_id`/`sender_id`
     * ONLY — the draft text is never compared nor rewritten (the id, not
     * the draft, is the deduplication key); a mismatch is the
     * `409 message_id_conflict` of №16 (a foreign id). A miss returns
     * `null` and the send continues down the gated path.
     */
    private fun resolveDedupFastPath(
        chatId: UUID,
        senderId: UUID,
        clientMessageId: UUID,
    ): Message? {
        val recorded = messageRepository.findById(clientMessageId) ?: return null
        if (recorded.chatId != chatId || recorded.senderId != senderId) throw MessageIdConflictException()
        return recorded
    }

    /**
     * FR-007 fan-out to BOTH participants (realtime-channel.md §3.1): the
     * recipient renders the message; the sender's other devices/sessions
     * render it already «доставлено» (US2-6). Only an ACTUAL new record
     * publishes — the idempotent `200` retry is silent (at-most-once
     * channel + exactly-once storage).
     *
     * Each target is isolated: the record is durable and the request
     * already succeeded, so a lost/failed realtime delivery must NOT fail
     * it — clients converge via the refetch on (re)connect (FR-009). Warn
     * logs carry ids only, never the message text (constitution V,
     * SC-008).
     */
    private fun publishCreated(
        chat: Chat,
        message: Message,
    ) {
        val event = MessageCreatedEvent(chatId = chat.id, message = message)
        val recipientId =
            checkNotNull(chat.peerOf(message.senderId)) {
                "the sender passed the membership gate, so the peer must resolve"
            }
        publishIsolated(message.senderId, event)
        publishIsolated(recipientId, event)
    }

    @Suppress("TooGenericExceptionCaught") // the transport adapter signals any delivery failure by throwing
    private fun publishIsolated(
        targetUserId: UUID,
        event: MessageCreatedEvent,
    ) {
        try {
            realtimeEventPublisher.publishMessageCreated(targetUserId, event)
        } catch (failure: Exception) {
            log.warn(
                "realtime fan-out of message <{}> in chat <{}> to user <{}> failed; " +
                    "the record is durable and clients converge on refetch (FR-009): {}",
                event.message.id,
                event.chatId,
                targetUserId,
                failure.message,
            )
        }
    }

    /**
     * T037 (research.md 004 §11, SC-008): `webchat_message_dedup_total`
     * counts every send that resolved through the dedup — BOTH legs: the
     * T031 fast-path lookup hit and the ON CONFLICT race leg of parallel
     * retries ([MessageInsertResult.Duplicate]) — i.e. every `200`
     * idempotent acknowledgement; a fresh `201` record is not a dedup
     * hit. The share of deduplications is this counter over all acked
     * sends of [ACK_SECONDS].
     */
    private fun dedupTotal(): Counter =
        Counter
            .builder(DEDUP_TOTAL)
            .description(
                "Send-path dedup hits: retries converged to the stored record (200), " +
                    "both the fast-path and the ON CONFLICT race leg (SC-008)",
            ).register(meterRegistry)

    /**
     * T028 (research.md 004 §11): `webchat_message_ack_seconds` times the
     * send path from the POST arrival (the entry of [send]) to the durable
     * `201`/`200` acknowledgement (SC-001) — only acked sends record a
     * sample; a refused send (400/403/404/409/429) abandons its sample and
     * stays unobserved here.
     */
    private fun ackTimer(outcome: String): Timer =
        Timer
            .builder(ACK_SECONDS)
            .description("Send-path acknowledgement latency: POST arrival to the durable 201/200 outcome (SC-001)")
            .tag(TAG_OUTCOME, outcome)
            .register(meterRegistry)

    private companion object {
        /** research.md 004 §11 observability contract name. */
        const val ACK_SECONDS = "webchat_message_ack_seconds"

        const val TAG_OUTCOME = "outcome"

        /** The two acked outcomes: the `201` fresh record and the `200` FR-004 retry. */
        const val OUTCOME_CREATED = "created"
        const val OUTCOME_EXISTING = "existing"

        /** SC-008 (T037, research.md 004 §11): dedup hits of the send path. */
        const val DEDUP_TOTAL = "webchat_message_dedup_total"
    }
}
