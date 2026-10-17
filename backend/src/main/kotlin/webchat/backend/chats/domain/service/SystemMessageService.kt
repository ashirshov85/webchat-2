package webchat.backend.chats.domain.service

import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewSystemMessage
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.chats.domain.port.SystemMessagePort
import java.util.UUID

/**
 * T016 (009, research §8): the `chats`-side implementation of the
 * [SystemMessagePort] — the write leg a cross-domain operation (the №44
 * conference create, T017) uses to post its widget card into the chat
 * history. Everything the 004/006 user send path carries BESIDES the
 * INSERT is deliberately ABSENT here:
 *  * no dedup fast-path, no admission gate, no flood bucket, no FR-020
 *    blocking pair — those gate a HUMAN sender; the system row is
 *    server-authored and its exactly-once discipline is the caller's own
 *    operation idempotency (`ux_conferences_chat_active` keeps a №44 retry
 *    from ever reaching a second append);
 *  * the write itself is the SAME single-transaction discipline:
 *    [MessageRepository.insertSystem] joins the AMBIENT operation
 *    transaction and folds the `seq` allocation, the `chats.last_seq`
 *    advance and the FR-021 `hidden` reset into it, so the card rides the
 *    per-chat order, the pagination cursors and the list revival exactly
 *    like a user record;
 *  * the `message.created` fan-out goes to the ACTIVE-roster snapshot
 *    (INCLUDING the operation actor — his own chat window must render the
 *    card; the №44 answer carries no message) read inside the same
 *    transaction, and fires strictly POST-COMMIT — a rollback never
 *    announces a card that was never stored (the 006 post-commit rule of
 *    GroupService, constitution III).
 *
 * The only NEW validation is the widget size cap of data-model §Сущность 4:
 * the serialized [ObjectNode] must stay within
 * [Message.MAX_PAYLOAD_BYTES] (4 KB) — a defense-in-depth guard (the
 * composed payloads are tiny by construction); the object SHAPE is the V18
 * `ck_messages_payload_object` CHECK.
 */
@Service
class SystemMessageService(
    private val messageRepository: MessageRepository,
    private val participantRepository: ParticipantRepository,
    private val realtimeEventPublisher: RealtimeEventPublisher,
) : SystemMessagePort {
    override fun append(
        chatId: UUID,
        caption: String,
        payload: ObjectNode,
    ): Message {
        val text = MessageText.normalize(caption)
        require(payload.toString().toByteArray().size <= Message.MAX_PAYLOAD_BYTES) {
            "a system-message payload must serialize to at most ${Message.MAX_PAYLOAD_BYTES} bytes " +
                "(data-model 009 §Сущность 4)"
        }
        // The FR-011-style addressee snapshot: the LAST statement before
        // the INSERT, inside the ambient operation transaction — the
        // roster under which the card is admitted (the 006 convergence
        // argument of MessageService applies verbatim: a roster race
        // outside the window converges client-side, FR-009).
        val addressees =
            participantRepository
                .activeMembers(chatId)
                .map { it.userId }
        val message =
            messageRepository.insertSystem(
                NewSystemMessage(
                    id = UUID.randomUUID(),
                    chatId = chatId,
                    text = text,
                    payload = payload,
                ),
            )
        fanoutMessageCreatedAfterCommit(addressees, message)
        return message
    }

    /**
     * The post-commit ordering rule (the port contract; the 006
     * `afterCommit` discipline): the fan-out runs in `afterCommit` of the
     * ambient operation transaction — a rolled-back create never
     * announces anything. Without an active synchronization (a direct
     * unit call) the publication runs inline. At-most-once isolation, the
     * same as every №18 leg: a lost or failed delivery must NOT fail the
     * already-durable operation (clients converge via №15/№26, FR-009);
     * warn logs carry ids only, never the payload (constitution V).
     */
    @Suppress("TooGenericExceptionCaught") // at-most-once isolation, the same defense-in-depth as the 004/006 legs
    private fun fanoutMessageCreatedAfterCommit(
        addressees: List<UUID>,
        message: Message,
    ) {
        val event = MessageCreatedEvent(chatId = message.chatId, message = message)
        afterCommit {
            try {
                realtimeEventPublisher.fanoutMessageCreated(addressees, event)
            } catch (failure: Exception) {
                log.warn(
                    "realtime fan-out of the system message <{}> in chat <{}> to <{}> addressees failed; " +
                        "the record is durable and clients converge on the №26 catch-up (FR-009): {}",
                    message.id,
                    message.chatId,
                    addressees.size,
                    failure.message,
                )
            }
        }
    }

    /** The 006 GroupService post-commit seam, verbatim (see its doc). */
    private fun afterCommit(publish: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = publish()
            },
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SystemMessageService::class.java)
    }
}
