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
 */
@ConfigurationProperties(prefix = "chats")
data class ChatsProperties(
    val message: Message,
    val rateLimit: RateLimit,
    val realtime: Realtime,
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
     */
    data class RateLimit(
        val messagesPerMinute: Int,
        val searchesPerMinute: Int,
    )

    /** realtime-channel.md: `:ka` heartbeat interval of `GET /users/me/events`. */
    data class Realtime(
        val heartbeat: Duration,
    )

}
