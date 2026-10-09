package webchat.backend.chats.scheduler

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import webchat.backend.chats.TypingMetrics
import webchat.backend.chats.domain.port.TypingStore
import webchat.backend.chats.domain.service.TypingService
import webchat.backend.config.ChatsProperties

/**
 * T032 (tasks.md 008a Phase 4, US2; FR-007/AC6, SC-003; research.md
 * §A4, data-model 008a §2.1): the `typing:watch` self-expiry poller —
 * the deferred-execution half of the ephemeral typing contract, the
 * exact `PresenceTransitionScheduler.drainDueOfflinePublishes` pattern
 * of 007 (research.md §A4) applied to the typing state machine.
 *
 * The №41 legs of TypingService only ever EXTEND the state (every
 * `start` re-arms `expiresAt = now + chats.typing.state-ttl`, 8 s);
 * THIS component is the one that executes the third extinguish leg —
 * the TTL lapse of an ABANDONED state (a hung tab, a `kill -9`'d pod,
 * a metro gap: no `stop` signal and no №16 message INSERT ever lands).
 * Without it the OBSERVERS' indicator would hang on the client's 10 s
 * safety timeout alone and the SC-003 budget («typing.stopped ≤ TTL +
 * poll-interval») would have no server leg at all.
 *
 * Every tick (`chats.typing.poll-interval`, fixed delay: 1 s prod,
 * 200 ms IT profile — T008) is a bounded, idempotent drain:
 *  * [TypingStore.dueExpired] is the ONE atomic Lua claim —
 *    ZRANGEBYSCORE `typing:watch` −inf..now (oldest first, at most
 *    `chats.typing.poll-batch`, 1000) AND the ZREM from BOTH ZSETs
 *    inside the same script: the removal IS the claim, so the pollers
 *    of every stateless replica (and a racing №41 `stop` / №16
 *    extinguish) may compete safely — exactly one of them wins each
 *    lapse, the losers find nothing left and publish nothing
 *    (at-most-once across the fleet, constitution II/III; the entries
 *    beyond the batch cap stay due for the next tick or a competing
 *    replica — an instance-crash storm back-pressures into the fleet
 *    instead of spiking one tick, research.md §E);
 *  * each claimed lapse counts
 *    `webchat_typing_state_expired_total` (FR-017 — the reap is
 *    counted even when the publication behind it is lawfully
 *    suppressed: a block pair, a vanished member);
 *  * [TypingService.onStateExpired] is the publication — the №16 gate,
 *    the DIRECT block-pair suppression and the self-excluding audience
 *    fold, all inside the service; it never throws for expected domain
 *    refusals, so one poisoned state cannot kill the tick's remaining
 *    batch. A claim is already durable when it runs: a failed or lost
 *    frame is never re-sent — the observer's 10 s safety timeout hides
 *    the indicator (FR-007/FR-008, at-most-once №18 discipline).
 *
 * Gated on `chats.typing.poller-enabled` (research.md §A4): production
 * always runs this bean (the conditional defaults to TRUE); the IT
 * profile disables it JVM-wide against the ONE shared Redis
 * (application-test.yml) and
 * [TypingTestSupport][webchat.backend.chats.TypingTestSupport]
 * re-enables it for the typing context — exactly ONE poller owner per
 * test JVM, the deterministic single-replica view the SC-003 budget of
 * TypingIT assumes (the T036 presence precedent).
 */
@ConditionalOnProperty(prefix = "chats.typing", name = ["poller-enabled"], havingValue = "true", matchIfMissing = true)
@Component
class TypingTransitionScheduler(
    private val typingStore: TypingStore,
    private val typingService: TypingService,
    private val typingMetrics: TypingMetrics,
    private val chatsProperties: ChatsProperties,
) {
    /**
     * One fixed-delay `typing:watch` drain: claim the due lapses in ONE
     * bounded batch, count every claim, publish `typing.stopped` per
     * claimed state. An empty trigger ZSET is the common idle case —
     * one cheap atomic read per tick and nothing else.
     */
    @Scheduled(fixedDelayString = "\${chats.typing.poll-interval}")
    fun reapExpiredTypingStates() {
        typingStore.dueExpired(chatsProperties.typing.pollBatch).forEach { state ->
            typingMetrics.countStateExpired()
            typingService.onStateExpired(state)
        }
    }
}
