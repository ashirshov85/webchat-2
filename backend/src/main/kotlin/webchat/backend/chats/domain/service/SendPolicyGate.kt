package webchat.backend.chats.domain.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import webchat.backend.chats.domain.model.Chat
import webchat.backend.config.ChatsProperties
import webchat.backend.config.UserRateLimiter
import webchat.backend.contacts.domain.port.BlockRepository
import java.util.UUID

/**
 * The refusal gates of the №16 send path (T032/T054), grouped as one
 * collaborator of [MessageService]: the per-user flood bucket of FR-011
 * and the blocking-pair gate of FR-020. Both run strictly AFTER the
 * FR-004 dedup fast-path and BEFORE the INSERT — only a send that will
 * be recorded consumes a token, a refused send writes nothing, and a
 * retry of an already recorded id can never be flood-penalized nor
 * blocked (the dedup resolves first).
 *
 * The side effects of a refusal are owned here: the SC-008 warn logs
 * (ids ONLY — never the message text, constitution V) and the
 * `webchat_send_rejected_total{reason=…}` counter (T032 `flood`, T054
 * `blocked_by_you` / `you_are_blocked`); the typed exceptions stay in
 * [MessageService.kt] so the api layer keeps rendering them as the
 * contract codes of №16.
 */
@Component
class SendPolicyGate(
    private val chatsProperties: ChatsProperties,
    private val rateLimiter: UserRateLimiter,
    private val blockRepository: BlockRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(SendPolicyGate::class.java)

    /**
     * T032 (FR-011, research.md 004 §7): the per-user send flood bucket
     * `rl:user:msgsend:{userId}` — `chats.rate-limit.messages-per-minute`
     * tokens with the continuous 30/60s drip (greedy refill), state in
     * Redis via the shared [UserRateLimiter] (Bucket4j+Lettuce over the
     * 002 [webchat.backend.config.RateLimitConfig] infra) so every replica
     * and every device of the user draws from ONE bucket (constitution
     * II).
     *
     * The refusal is the `429 flood_limit` problem+json + `Retry-After`
     * of №16 (ceil of the refill wait, ≥1s); the warn log carries the
     * user id and the wait ONLY — never the message text (constitution
     * V, SC-008) — and the `webchat_send_rejected_total{reason=flood}`
     * counter grows. Redis unavailability fails OPEN (the 002 §7
     * invariant): losing the ephemeral store may only reset the limit,
     * never 5xx the send path.
     */
    fun enforceFloodLimit(senderId: UUID) {
        val perMinute = chatsProperties.rateLimit.messagesPerMinute.toLong()
        val verdict = rateLimiter.tryAcquire(MSGSEND_KEY_FAMILY, senderId, perMinute)
        if (verdict !is UserRateLimiter.Verdict.Rejected) return

        log.warn(
            "send refused by the flood limit (FR-011): user <{}> exhausted {} messages/minute, retry after {}s",
            senderId,
            perMinute,
            verdict.retryAfterSeconds,
        )
        rejectedTotal(REASON_FLOOD).increment()
        throw FloodLimitException(verdict.retryAfterSeconds)
    }

    /**
     * T054 (FR-020, research.md 004 §6): the blocking-pair gate — the
     * block acts in BOTH directions, checked as two point lookups on the
     * `(blocker_id, blocked_id)` PK pair STRICTLY BEFORE the INSERT (after
     * the dedup fast-path and the flood bucket, per the path contract of
     * [MessageService.send]):
     *
     *  * `exists(sender, recipient)` → the sender is the blocker —
     *    `403 chat_blocked_by_you`;
     *  * `exists(recipient, sender)` → the sender is the blocked one —
     *    `403 you_are_blocked`, his ONLY notification of the block (the
     *    API carries no inverse field).
     *
     * A refused send writes nothing, publishes nothing (neither stream
     * may learn about the attempt — the Edge Case of BlockingIT) and is
     * never flood-exempt: only the FR-004 dedup of an ALREADY recorded id
     * passes a blocked pair, because it runs earlier. The warn logs carry
     * ids ONLY — never the message text (constitution V, SC-008) — and
     * the `webchat_send_rejected_total{reason=blocked_by_you|
     * you_are_blocked}` counters grow.
     */
    fun enforceBlockPair(
        chat: Chat,
        senderId: UUID,
    ) {
        val recipientId =
            checkNotNull(chat.peerOf(senderId)) {
                "the sender passed the membership gate, so the peer must resolve"
            }
        if (blockRepository.exists(senderId, recipientId)) {
            log.warn(
                "send refused: user <{}> blocks the peer of chat <{}> (FR-020 chat_blocked_by_you)",
                senderId,
                chat.id,
            )
            rejectedTotal(REASON_BLOCKED_BY_YOU).increment()
            throw ChatBlockedByYouException()
        }
        if (blockRepository.exists(recipientId, senderId)) {
            log.warn(
                "send refused: user <{}> is blocked in chat <{}> (FR-020 you_are_blocked)",
                senderId,
                chat.id,
            )
            rejectedTotal(REASON_YOU_ARE_BLOCKED).increment()
            throw YouAreBlockedException()
        }
    }

    /**
     * SC-008 (T032+T054): send-path refusals by reason — `flood` (FR-011)
     * and the blocking-pair codes of FR-020.
     */
    private fun rejectedTotal(reason: String): Counter =
        Counter
            .builder(SEND_REJECTED_TOTAL)
            .description("Send-path refusals by reason: flood limit (FR-011), blocking pair (FR-020, T054)")
            .tag(TAG_REASON, reason)
            .register(meterRegistry)

    private companion object {
        /** research.md 004 §7: the Redis key family of the FR-011 send bucket. */
        const val MSGSEND_KEY_FAMILY = "rl:user:msgsend:"

        /** SC-008 (T032): send refusals by reason. */
        const val SEND_REJECTED_TOTAL = "webchat_send_rejected_total"
        const val TAG_REASON = "reason"
        const val REASON_FLOOD = "flood"

        /** SC-008 (T054, FR-020): the blocking-pair refusal reasons. */
        const val REASON_BLOCKED_BY_YOU = "blocked_by_you"
        const val REASON_YOU_ARE_BLOCKED = "you_are_blocked"
    }
}
