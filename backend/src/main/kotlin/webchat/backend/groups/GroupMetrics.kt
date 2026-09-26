package webchat.backend.groups

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * T010 — the FR-017/SC-008 observability vocabulary of research.md §8,
 * on the shared Micrometer stack of 001 — no new dependencies:
 *
 *  * counter `webchat_group_authz_denials_total{operation}` — membership/
 *    role refusals per gated group operation (№28–№35; №27 is never gated —
 *    the group does not exist yet), incremented by [GroupMembershipGate]
 *    (T015) BEFORE the role distinction, so every privacy refusal is a
 *    counted 404 (SC-003);
 *  * counter `webchat_group_message_fanout_total` — fanout legs of group
 *    message delivery (T035), incremented by the recipient count;
 *  * timer `webchat_group_message_fanout_seconds` — post-commit fanout
 *    latency (SC-001), started/stopped around the publish loop;
 *  * distribution summary `webchat_group_size` — active membership size
 *    recorded on every add/remove (research.md §8).
 *
 * The names carry the Prometheus suffixes verbatim (`_total` is idempotent
 * for counters, `_seconds` is the timer base unit), so
 * `/actuator/prometheus` renders exactly the series quickstart §3.8 and
 * T068 assert.
 *
 * Constitution V: tag values carry operation ids only — never chat or
 * user identifiers.
 */
@Component
class GroupMetrics(
    private val meterRegistry: MeterRegistry,
) {
    /**
     * Counts one `webchat_group_authz_denials_total{operation}` refusal of
     * the membership gate (FR-008/FR-017, SC-003).
     */
    fun countAuthzDenial(operation: AuthzOperation) {
        meterRegistry
            .counter(AUTHZ_DENIALS_TOTAL, TAG_OPERATION, operation.value)
            .increment()
    }

    /**
     * Counts [legs] fanout legs of one group message — the recipients the
     * post-commit publish loop of T035 delivers to (FR-011).
     */
    fun countFanoutLegs(legs: Int) {
        fanoutTotal().increment(legs.toDouble())
    }

    /**
     * Starts a `webchat_group_message_fanout_seconds` sample; the caller
     * stops it with [FanoutSample.stop] after the post-commit publish loop
     * — the SC-001 «sent → delivered» latency signal.
     */
    fun startFanout(): FanoutSample = FanoutSample(Timer.start(meterRegistry))

    /**
     * Records [size] — the active membership size of a group after an
     * add/remove transition (research.md §8: the size distribution is
     * updated on every membership change).
     */
    fun recordGroupSize(size: Int) {
        groupSizeSummary().record(size.toDouble())
    }

    private fun fanoutTotal() =
        Counter
            .builder(FANOUT_TOTAL)
            .description("Fanout legs of group message delivery: one per recipient publish (FR-011)")
            .register(meterRegistry)

    private fun groupSizeSummary(): DistributionSummary =
        DistributionSummary
            .builder(GROUP_SIZE)
            .description("Active membership size of a group, recorded on every add/remove (FR-017)")
            .register(meterRegistry)

    private fun fanoutTimer(): Timer =
        Timer
            .builder(FANOUT_SECONDS)
            .description("Latency of the post-commit group message fanout (SC-001)")
            .register(meterRegistry)

    /** A running `webchat_group_message_fanout_seconds` measurement stopped exactly once. */
    inner class FanoutSample(
        private val sample: Timer.Sample,
    ) {
        fun stop() {
            sample.stop(fanoutTimer())
        }
    }

    /** The gated group operations №28–№35 of api-contract.md (№27 is never gated). */
    enum class AuthzOperation(
        val value: String,
    ) {
        GET("get"),
        UPDATE("update"),
        DELETE("delete"),
        ADD_MEMBERS("add_members"),
        REMOVE_MEMBER("remove_member"),
        LEAVE("leave"),
        SET_ROLE("set_role"),
        TRANSFER_OWNERSHIP("transfer_ownership"),
    }

    private companion object {
        // research.md §8 observability contract names (FR-017, SC-008)
        const val AUTHZ_DENIALS_TOTAL = "webchat_group_authz_denials_total"

        const val FANOUT_TOTAL = "webchat_group_message_fanout_total"

        const val FANOUT_SECONDS = "webchat_group_message_fanout_seconds"

        const val GROUP_SIZE = "webchat_group_size"

        const val TAG_OPERATION = "operation"
    }
}
