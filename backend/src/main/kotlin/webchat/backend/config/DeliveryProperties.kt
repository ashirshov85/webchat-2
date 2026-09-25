package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Delivery-resilience settings of 005-delivery-resilience, bound from
 * `delivery.*` (application.yml). Sync/ack values mirror the public
 * contract constants (api-contract.md №25/№26): the ack batch cap
 * ([Sync.ackBatchLimit]), the sync response page sizes — chats
 * ([Sync.chatPageSize]) and messages ([Sync.messagePageSize]) —
 * backing the `chatLimit`/`messageLimit` defaults and bounds of №26.
 * Backpressure bounds ([Backpressure]) come from research.md §6 /
 * quickstart §2: the adaptive per-instance admission limiter on the
 * send path (min/max concurrency limits, PG-commit latency baseline
 * of the gradient/AIMD controller). Consumed by DeliveryAckService/
 * SyncService/SyncController and GradientAdmissionLimiter; asserted
 * by IT Phase 3/6 (T007/T008/T038).
 */
@ConfigurationProperties(prefix = "delivery")
data class DeliveryProperties(
    val sync: Sync,
    val backpressure: Backpressure,
) {
    /** api-contract.md №25/№26: ack batch cap and sync page sizes. */
    data class Sync(
        val messagePageSize: Int,
        val chatPageSize: Int,
        val ackBatchLimit: Int,
    )

    /** research.md §6 / quickstart §2: gradient/AIMD admission bounds. */
    data class Backpressure(
        val enabled: Boolean,
        val minLimit: Int,
        val maxLimit: Int,
        val latencyBaseline: Duration,
    )
}
