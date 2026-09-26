package webchat.backend.groups

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * T010a — unit-level acceptance of the FR-017/SC-008 observability
 * vocabulary that T010 (`GroupMetrics`) must expose (research.md §8):
 *
 *  * counter `webchat_group_authz_denials_total{operation}` — one tag per
 *    gated group operation (№28–№35; №27 is never gated — the group does
 *    not exist yet, tasks T015/T056);
 *  * counter `webchat_group_message_fanout_total` — fanout legs of group
 *    message delivery (T035), incremented by the recipient count;
 *  * timer `webchat_group_message_fanout_seconds` — post-commit fanout
 *    latency (SC-001), started/stopped around the publish loop;
 *  * distribution summary `webchat_group_size` — active membership size
 *    recorded on every add/remove (research.md §8).
 *
 * The names carry the Prometheus suffixes verbatim (`_total` is
 * idempotent for counters, `_seconds` is the timer base unit), so
 * `/actuator/prometheus` renders exactly the series quickstart §3.8 and
 * T068 assert — that end-to-end rendering is out of scope here, as are
 * the call sites (gate denials — T015a, fanout legs/latency — T030).
 *
 * Constitution V: tag values carry operation ids only — never chat or
 * user identifiers.
 */
class GroupMetricsTest {
    private val meterRegistry = SimpleMeterRegistry()

    private val metrics = GroupMetrics(meterRegistry)

    // --- webchat_group_authz_denials_total ---

    @Test
    fun `authz denial counter registers on first denial and grows monotonically`() {
        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counters()).isEmpty()

        metrics.countAuthzDenial(GroupMetrics.AuthzOperation.GET)

        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counters()).hasSize(1)
        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counter()).isNotNull
        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counter()!!.count()).isEqualTo(1.0)

        metrics.countAuthzDenial(GroupMetrics.AuthzOperation.GET)
        metrics.countAuthzDenial(GroupMetrics.AuthzOperation.GET)

        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counter()!!.count()).isEqualTo(3.0)
    }

    @Test
    fun `authz denial counter splits denials per operation tag`() {
        metrics.countAuthzDenial(GroupMetrics.AuthzOperation.GET)
        metrics.countAuthzDenial(GroupMetrics.AuthzOperation.GET)
        metrics.countAuthzDenial(GroupMetrics.AuthzOperation.REMOVE_MEMBER)

        assertThat(
            meterRegistry.find(AUTHZ_DENIALS_TOTAL).tag(TAG_OPERATION, "get").counter()!!.count(),
        ).isEqualTo(2.0)
        assertThat(
            meterRegistry.find(AUTHZ_DENIALS_TOTAL).tag(TAG_OPERATION, "remove_member").counter()!!.count(),
        ).isEqualTo(1.0)
        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counters().sumOf { it.count() })
            .isEqualTo(3.0)
    }

    @Test
    fun `authz operation vocabulary covers the gated group operations 28-35`() {
        assertThat(enumValues<GroupMetrics.AuthzOperation>().map { it.value })
            .contains(
                "get",
                "update",
                "delete",
                "add_members",
                "remove_member",
                "leave",
                "set_role",
                "transfer_ownership",
            )
    }

    // --- webchat_group_message_fanout_total ---

    @Test
    fun `fanout counter registers on first leg and grows by the leg count`() {
        assertThat(meterRegistry.find(FANOUT_TOTAL).counter()).isNull()

        metrics.countFanoutLegs(2)

        assertThat(meterRegistry.find(FANOUT_TOTAL).counter()!!.count()).isEqualTo(2.0)

        metrics.countFanoutLegs(3)

        assertThat(meterRegistry.find(FANOUT_TOTAL).counter()!!.count()).isEqualTo(5.0)
    }

    // --- webchat_group_message_fanout_seconds ---

    @Test
    fun `fanout timer registers on first stopped sample and its count grows`() {
        assertThat(meterRegistry.find(FANOUT_SECONDS).timer()).isNull()

        metrics.startFanout().stop()

        val timer = meterRegistry.find(FANOUT_SECONDS).timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1L)
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
            .isGreaterThanOrEqualTo(0.0)

        metrics.startFanout().stop()

        assertThat(meterRegistry.find(FANOUT_SECONDS).timer()!!.count()).isEqualTo(2L)
    }

    // --- webchat_group_size ---

    @Test
    fun `group size summary registers on first record and grows with membership sizes`() {
        assertThat(meterRegistry.find(GROUP_SIZE).summary()).isNull()

        metrics.recordGroupSize(3)

        val summary = meterRegistry.find(GROUP_SIZE).summary()
        assertThat(summary).isNotNull
        assertThat(summary!!.count()).isEqualTo(1L)
        assertThat(summary.max()).isEqualTo(3.0)

        metrics.recordGroupSize(200)
        metrics.recordGroupSize(1)

        assertThat(summary.count()).isEqualTo(3L)
        assertThat(summary.max()).isEqualTo(200.0)
        assertThat(summary.totalAmount()).isEqualTo(204.0)
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
