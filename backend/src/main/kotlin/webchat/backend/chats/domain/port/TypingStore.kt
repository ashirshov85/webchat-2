package webchat.backend.chats.domain.port

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One ephemeral typing-state record (data-model 008a §2.1): «the member
 * [userId] is typing in the chat [chatId] until [expiresAt]». The state
 * is born from a №41 `start` signal (score = now + TTL, 8 s — research
 * §A2), every further `start` of the same typer EXTENDS it (the 3 s
 * client repeat window keeps re-arming the horizon, so one lost repeat
 * does not expire the indicator, AC4), and it dies by exactly ONE of
 * the three extinguish legs — a №41 `stop`, the typer's own №16 message
 * INSERT (the server extinguishes at the send, data-model §2.1
 * transition 2) or the score lapse reaped by the poller (SC-003).
 * Nothing here is ever persisted to PG, read back by a connecting
 * client or replayed from history (FR-007/AC5 — no read surface
 * exists at all).
 */
data class TypingState(
    val chatId: UUID,
    val userId: UUID,
    val expiresAt: Instant,
)

/**
 * The ephemeral typing-state storage port (data-model 008a §2.1; DIP:
 * the Redis adapter lives outside the domain in
 * `webchat.backend.chats.repository.RedisTypingStore`, T028).
 *
 * The whole feature state is two ZSET families — `typing:active:{chatId}`
 * (member=userId, score=expiresAtMs) and the global poller trigger
 * `typing:watch` (member=`{chatId}:{userId}`, score=expiresAtMs) — plus
 * the key-TTL garbage insurance. Instances stay stateless (constitution
 * II): every leg is ONE atomic Redis operation (a Lua script over Redis
 * `TIME`, the 007 `RedisPresenceStore` discipline), so the SSE-driven
 * starts/stops of the same typer may ride different pods and the
 * pollers of all instances may compete safely. The port deliberately
 * exposes NO read surface beyond the due-expiry batch: there is nothing
 * to read back — a subscriber connecting mid-typing receives no replay
 * (AC5), the frames exist only for the live connections of the moment.
 */
interface TypingStore {
    /**
     * №41 `start` (T030 TypingService): atomically ZADDs the typer into
     * BOTH ZSETs with score = now + [ttl] — a fresh state creates it, a
     * repeat extends it (the renewal is unconditional: the publication
     * decision belongs to the caller, which publishes `typing.started`
     * on EVERY valid start, data-model §2.1 footnote — the anti-flap
     * debounce is the client's 3 s window + the 60/min flood bucket,
     * never a server-side suppression). Also re-arms the key-TTL
     * garbage insurance of both keys (data-model §2.1).
     */
    fun start(
        chatId: UUID,
        userId: UUID,
        ttl: Duration,
    )

    /**
     * The extinguish leg of a №41 `stop` signal AND of the typer's own
     * №16 message send (T030/T033): atomically ZREMs the pair from BOTH
     * ZSETs. Returns `true` ⟺ an actively-tracked state was actually
     * removed — ONLY then may the caller publish `typing.stopped`
     * (idempotence against the double extinguish: the client stop of
     * T036 racing the server-side extinguish at the message INSERT and
     * the poller's reap of a lapsed score all compete for the SAME
     * removal, and exactly one of them wins it — a repeat `stop`
     * without an active state publishes nothing).
     */
    fun stop(
        chatId: UUID,
        userId: UUID,
    ): Boolean

    /**
     * The SC-003 self-expiry leg of the poller (T032
     * TypingTransitionScheduler, `chats.typing.*`): atomically CLAIMS the
     * due states — the `{chatId}:{userId}` members of `typing:watch`
     * whose score has lapsed (ZRANGEBYSCORE −inf..now, oldest first,
     * at most [batch] — the entries beyond the cap stay due for the next
     * tick or a competing replica) and removes every claimed pair from
     * BOTH ZSETs inside the SAME script. The removal IS the claim: a
     * second replica (or a racing `stop`) finds nothing left, so the
     * `typing.stopped` publication per lapsed state is at-most-once
     * across the fleet (constitution II). A `start` renewal racing the
     * claim either lands before it (the score is already pushed up —
     * not due anymore) or after it (both ZSET entries re-created, the
     * state lives on); the script's atomicity leaves no in-between.
     */
    fun dueExpired(batch: Int): List<TypingState>
}
