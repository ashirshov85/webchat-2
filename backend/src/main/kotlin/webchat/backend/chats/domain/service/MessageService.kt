package webchat.backend.chats.domain.service

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.micrometer.core.instrument.Counter
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
import java.nio.charset.StandardCharsets
import java.time.Duration
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
 *     per-user flood bucket (FR-011, T032: `rl:user:msgsend:{userId}`,
 *     30 tokens/min in Redis — AFTER the dedup, BEFORE the INSERT, so a
 *     refused send consumes its token but writes nothing) → the
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
 * it: the blocking-pair refusals (T054) — AFTER the dedup fast-path and
 * between the flood bucket and the INSERT.
 */
@Service
class MessageService(
    private val chatService: ChatService,
    private val messageRepository: MessageRepository,
    private val realtimeEventPublisher: RealtimeEventPublisher,
    private val chatsProperties: ChatsProperties,
    private val rateLimitProxyManager: ProxyManager<ByteArray>,
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
        val chat = chatService.get(chatId, senderId)
        val text = MessageText.normalize(rawText, chatsProperties.message.maxLength)
        enforceFloodLimit(senderId)
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
                dedupTotal().increment()
                ack.stop(ackTimer(OUTCOME_EXISTING))
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
     * T032 (FR-011, research.md 004 §7): the per-user send flood bucket
     * `rl:user:msgsend:{userId}` — `chats.rate-limit.messages-per-minute`
     * tokens with the continuous 30/60s drip (greedy refill), state in
     * Redis via the shared 002 Bucket4j+Lettuce [ProxyManager] so every
     * replica and every device of the user draws from ONE bucket
     * (constitution II). Runs strictly AFTER the dedup fast-path and the
     * FR-003 text gate, strictly BEFORE the INSERT: only a send that will
     * be recorded consumes a token, and a refused send writes nothing.
     *
     * The refusal is the `429 flood_limit` problem+json + `Retry-After`
     * of №16 (ceil of the refill wait, ≥1s); the warn log carries the
     * user id and the wait ONLY — never the message text (constitution
     * V, SC-008) — and the `webchat_send_rejected_total{reason=flood}`
     * counter grows. Redis unavailability fails OPEN (the 002 §7
     * invariant): losing the ephemeral store may only reset the limit,
     * never 5xx the send path.
     */
    @Suppress("TooGenericExceptionCaught") // the driver signals any outage by throwing
    private fun enforceFloodLimit(senderId: UUID) {
        val perMinute = chatsProperties.rateLimit.messagesPerMinute.toLong()
        val key = "$MSGSEND_KEY_FAMILY$senderId"
        val probe =
            runCatching {
                rateLimitProxyManager
                    .getProxy(key.toByteArray(StandardCharsets.UTF_8)) { floodBucketConfiguration(perMinute) }
                    .tryConsumeAndReturnRemaining(1)
            }.onFailure { outage ->
                log.warn("flood-limit bucket <{}> is unavailable, failing open: {}", key, outage.toString())
            }.getOrNull() ?: return

        if (probe.isConsumed) return

        val retryAfterSeconds =
            ((probe.nanosToWaitForRefill + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
                .coerceAtLeast(RETRY_AFTER_FLOOR_SECONDS)
        log.warn(
            "send refused by the flood limit (FR-011): user <{}> exhausted {} messages/minute, retry after {}s",
            senderId,
            perMinute,
            retryAfterSeconds,
        )
        rejectedTotal(REASON_FLOOD).increment()
        throw FloodLimitException(retryAfterSeconds)
    }

    /** research.md 004 §7: capacity N, greedy N/60s — the uniform drip, burst ≤ N then 1 per 2s. */
    private fun floodBucketConfiguration(perMinute: Long): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(perMinute)
                    .refillGreedy(perMinute, Duration.ofSeconds(REFILL_WINDOW_SECONDS))
                    .build(),
            ).build()

    /**
     * SC-008 (T032, later T054): send-path refusals by reason — `flood`
     * here; the blocking-pair codes join the same counter in T054.
     */
    private fun rejectedTotal(reason: String): Counter =
        Counter
            .builder(SEND_REJECTED_TOTAL)
            .description("Send-path refusals by reason: flood limit (FR-011), blocking pair (FR-020, T054)")
            .tag(TAG_REASON, reason)
            .register(meterRegistry)

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

        /** research.md 004 §7: the Redis key family of the FR-011 send bucket. */
        const val MSGSEND_KEY_FAMILY = "rl:user:msgsend:"

        /** research.md 004 §7: one window of the N-tokens-per-minute drip. */
        const val REFILL_WINDOW_SECONDS = 60L

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** openapi №16: `Retry-After` minimum 1. */
        const val RETRY_AFTER_FLOOR_SECONDS = 1L

        /** SC-008 (T032): send refusals by reason. */
        const val SEND_REJECTED_TOTAL = "webchat_send_rejected_total"
        const val TAG_REASON = "reason"
        const val REASON_FLOOD = "flood"

        /** SC-008 (T037, research.md 004 §11): dedup hits of the send path. */
        const val DEDUP_TOTAL = "webchat_message_dedup_total"
    }
}
