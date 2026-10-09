package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Messenger limits of 004-direct-messaging-core, bound from `chats.*`
 * (application.yml). Values mirror the public contract constants
 * (api-contract.md header, FR-003/FR-008/FR-011): message text cap and
 * history page size ([Message]), the per-user send and search flood
 * limits ([RateLimit], Bucket4j + Redis `rl:user:msgsend:*` T032 /
 * `rl:user:search:*` T053a) and the SSE
 * heartbeat cadence of the user event stream ([Realtime],
 * realtime-channel.md — the `:ka` comment frame). Consumed by
 * MessageService/HistoryService/RealtimeController; asserted by IT
 * T008a/T030/T038.
 *
 * 008a T008 extends the table with the typing-indicator windows
 * [Typing] (research.md A2/A4, data-model.md §2.1) and the №41/№42
 * flood capacities of [RateLimit] (research.md A5/D2) — consumed by
 * the TypingService/TypingTransitionScheduler/ChatSound routes of
 * US2/US4; the IT profile tightens the windows (application-test.yml).
 */
@ConfigurationProperties(prefix = "chats")
data class ChatsProperties(
    val message: Message,
    val rateLimit: RateLimit,
    val realtime: Realtime,
    val typing: Typing,
) {
    /** FR-003/FR-008: text validation bounds and history page size. */
    data class Message(
        val maxLength: Int,
        val pageSize: Int,
    )

    /**
     * FR-011: tokens per minute per user on the send path;
     * FR-016: searches per minute per user on №19 (T053a) — the
     * enumeration guard of the users route.
     *
     * 008a: [typingSignalsPerMinute] is the №41 per-user typing-signal
     * bucket `rl:user:typing:{userId}` — 60/min over the 60 s
     * [UserRateLimiter] window (an honest client sends ≤ 20 start/min
     * per chat at the 3 s repeat window, so the cap covers several
     * chats and stop/start chatter without indicator flapping at the
     * observers, research.md A5); [soundWritesPerMinute] is the №42
     * per-user sound-toggle bucket `rl:user:chat-sound:{userId}` —
     * the conservative 30/min №38 parity for a manual button
     * (research.md D2). Refusals answer 429 `flood_limit` +
     * Retry-After and publish nothing.
     */
    data class RateLimit(
        val messagesPerMinute: Int,
        val searchesPerMinute: Int,
        val typingSignalsPerMinute: Int,
        val soundWritesPerMinute: Int,
    )

    /** realtime-channel.md: `:ka` heartbeat interval of `GET /users/me/events`. */
    data class Realtime(
        val heartbeat: Duration,
    )

    /**
     * 008a research.md A2/A4 (FR-007, SC-003): the server-side typing
     * state machine windows. [stateTtl] is the horizon every №41 `start`
     * extends `expiresAt` to (8 s ≈ 2.5× the 3 s client repeat window —
     * one lost repeat does not expire the state); expiry is drained by
     * the TypingTransitionScheduler — gated on [pollerEnabled], ticking
     * every [pollInterval], batches capped at [pollBatch] — into
     * `typing.stopped` for the chat members (internal tick and batch,
     * not contract values; the offq-model 007 parity). The IT profile
     * tightens the windows and disables the poller for every cached
     * context; TypingTestSupport re-enables it as the ONE poller owner
     * of the test JVM (T027).
     */
    data class Typing(
        val stateTtl: Duration,
        val pollerEnabled: Boolean,
        val pollInterval: Duration,
        val pollBatch: Int,
    )

    init {
        require(rateLimit.typingSignalsPerMinute > 0) { "chats.rate-limit.typing-signals-per-minute must be positive" }
        require(rateLimit.soundWritesPerMinute > 0) { "chats.rate-limit.sound-writes-per-minute must be positive" }
        require(typing.stateTtl.isPositive) { "chats.typing.state-ttl must be positive" }
        require(typing.pollInterval.isPositive) { "chats.typing.poll-interval must be positive" }
        require(typing.pollBatch > 0) { "chats.typing.poll-batch must be positive" }
    }
}
