package webchat.backend.chats.domain.service

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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
 *  1. membership (FR-002, T014) → FR-003 text normalization → the
 *     exactly-once INSERT (`ON CONFLICT`, owned by
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
 * it: the flood bucket (T032) and the blocking-pair refusals (T054) —
 * both AFTER the dedup fast-path and between the membership gate and the
 * INSERT.
 */
@Service
class MessageService(
    private val chatService: ChatService,
    private val messageRepository: MessageRepository,
    private val realtimeEventPublisher: RealtimeEventPublisher,
    private val chatsProperties: ChatsProperties,
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
            ack.stop(ackTimer(OUTCOME_EXISTING))
            return MessageSendResult.Existing(recorded)
        }
        val chat = chatService.get(chatId, senderId)
        val text = MessageText.normalize(rawText, chatsProperties.message.maxLength)
        val outcome =
            messageRepository.insert(
                NewMessage(id = clientMessageId, chatId = chat.id, senderId = senderId, text = text),
            )
        return when (outcome) {
            is MessageInsertResult.Inserted -> {
                publishCreated(chat, outcome.message)
                ack.stop(ackTimer(OUTCOME_CREATED))
                MessageSendResult.Created(outcome.message)
            }
            is MessageInsertResult.Duplicate -> {
                val existing = outcome.existing
                if (existing.chatId != chat.id || existing.senderId != senderId) throw MessageIdConflictException()
                ack.stop(ackTimer(OUTCOME_EXISTING))
                MessageSendResult.Existing(existing)
            }
        }
    }

    /**
     * data-model 004 §3 step 0 (research.md 004 §3): the dedup fast-path —
     * the lookup by the client UUID runs BEFORE the membership gate, the
     * limits and the locks, so a retry of an already-recorded message
     * always converges to the same stored row (US2-1/2): the later flood
     * bucket (T032) and blocking-pair refusals (T054) can never interfere
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
    }
}
