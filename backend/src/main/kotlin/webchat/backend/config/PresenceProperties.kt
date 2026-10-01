package webchat.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Presence settings of 007-user-presence, bound from `presence.*`
 * (application.yml; research.md §G — the contract-parameter fixation):
 * the registration TTL and the anti-flap hysteresis window drive the
 * Redis state machine of `RedisPresenceStore` (T008, data-model.md §3),
 * the heartbeat interval is the contract RECOMMENDATION of №37 (the
 * client-side cadence — not a server timer), the poller interval is the
 * internal tick of the offq/watch pollers (T025/T028), and
 * [snapshotBatchLimit] is the №36 batch cap after dedup
 * (contracts/presence-api.md §1, parity with the 006 group limit).
 *
 * The test profile tightens every window (T003) so the SC-003 silence
 * budget does not flake on poller slack and registrations survive
 * scenarios longer than the TTL (read by T011/T023/T026).
 */
@ConfigurationProperties(prefix = "presence")
data class PresenceProperties(
    /** FR-002: registration horizon — every №37 heartbeat pushes it to `now + ttl` (90 s = 3× the heartbeat). */
    val ttl: Duration,
    /** FR-004: the anti-flap window a pending `offline` publish waits in `presence:offq` before the CAS (45 s). */
    val hysteresis: Duration,
    /** contracts/presence-api.md §2: the recommended client heartbeat cadence of №37 (30 s). */
    val heartbeatInterval: Duration,
    /** research.md §G: the fixed-delay tick of the offq/watch pollers (internal, not a contract value). */
    val pollerInterval: Duration,
    /** contracts/presence-api.md §1: the №36 batch snapshot cap after dedup (≤ 200). */
    val snapshotBatchLimit: Int,
) {
    init {
        require(ttl.isPositive) { "presence.ttl must be positive" }
        require(hysteresis.isPositive) { "presence.hysteresis must be positive" }
        require(heartbeatInterval.isPositive) { "presence.heartbeat-interval must be positive" }
        require(pollerInterval.isPositive) { "presence.poller-interval must be positive" }
        require(snapshotBatchLimit > 0) { "presence.snapshot-batch-limit must be positive" }
    }
}
