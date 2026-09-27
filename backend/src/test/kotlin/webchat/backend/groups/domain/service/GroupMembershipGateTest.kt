package webchat.backend.groups.domain.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.groups.GroupMetrics
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.model.MembershipState
import java.time.Instant
import java.util.UUID

/**
 * T015a — unit-level acceptance of the FR-008 membership gate that T015
 * (`GroupMembershipGate`) must provide, written red-first per the
 * constitution VI discipline of Phase 2.
 *
 * The gate is THE per-request authorization primitive of every group
 * operation №28–№35 (T021/T044/T052/T056/T065 call it before anything
 * else — "проверка до различения ролей"): it resolves the caller's
 * ACTIVE membership row through the T008 port extension
 * [ParticipantRepository.findActive] and refuses everything else with
 * the SINGLE typed 404 carrier `GroupNotFoundException`
 * (`group_not_found`, api-contract.md §1):
 *
 *  * a stranger who never was a member,
 *  * a removed former member (`state='removed'` — membership ⟺ active
 *    row, data-model.md §Сущность 2),
 *  * an unknown/non-group chat id
 *
 * all render IDENTICALLY — the group's existence is never disclosed
 * (FR-008/FR-009), and the refusal message carries no chat or user
 * identifiers (constitution V).
 *
 * Every refusal increments the FR-017/SC-008 counter
 * `webchat_group_authz_denials_total{operation}` (research.md §8, the
 * vocabulary pinned by GroupMetricsTest) with the tag of the gated
 * operation; a pass never touches it. The HTTP rendering of the carrier
 * as `404 errors: {group: [group_not_found]}` is owned by T017
 * (GroupsExceptionHandlerTest); the end-to-end matrix — by GroupPrivacyIT
 * (T054, SC-003).
 */
class GroupMembershipGateTest {
    private val meterRegistry = SimpleMeterRegistry()

    private val participants =
        ScriptedParticipantRepository(
            (GROUP to OWNER) to membershipRow(OWNER, MemberRole.OWNER),
            (GROUP to ADMIN_MEMBER) to membershipRow(ADMIN_MEMBER, MemberRole.ADMIN),
            (GROUP to PLAIN_MEMBER) to membershipRow(PLAIN_MEMBER, MemberRole.MEMBER),
            (GROUP to REMOVED_MEMBER) to membershipRow(REMOVED_MEMBER, MemberRole.MEMBER, MembershipState.REMOVED),
        )

    private val gate = GroupMembershipGate(participants, GroupMetrics(meterRegistry))

    // --- the pass: the active row with its role ---

    @Test
    fun `gate resolves the active membership row of every role`() {
        val activeRoster =
            listOf(
                OWNER to MemberRole.OWNER,
                ADMIN_MEMBER to MemberRole.ADMIN,
                PLAIN_MEMBER to MemberRole.MEMBER,
            )

        for ((user, role) in activeRoster) {
            val membership = gate.requireActiveMembership(GROUP, user, GroupMetrics.AuthzOperation.GET)

            assertThat(membership.userId).isEqualTo(user)
            assertThat(membership.state).isEqualTo(MembershipState.ACTIVE)
            assertThat(membership.role)
                .overridingErrorMessage("the gate returns the row with its role for the FR-003/FR-004 checks")
                .isEqualTo(role)
        }

        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counters())
            .overridingErrorMessage("a passed gate must never grow the denial counter")
            .isEmpty()
    }

    // --- the refusals: one uniform 404 carrier ---

    @Test
    fun `gate refuses a stranger of the group with the uniform 404 carrier`() {
        val exception =
            assertThrows<GroupNotFoundException> {
                gate.requireActiveMembership(GROUP, STRANGER, GroupMetrics.AuthzOperation.GET)
            }

        assertThat(exception.code).isEqualTo(CODE_GROUP_NOT_FOUND)
        assertThat(denials(TAG_GET)).isEqualTo(1.0)
    }

    @Test
    fun `gate refuses a removed former member identically to a stranger`() {
        val strangerRefusal = refusalOf(GROUP, STRANGER)
        val removedRefusal = refusalOf(GROUP, REMOVED_MEMBER)

        assertThat(removedRefusal.code).isEqualTo(CODE_GROUP_NOT_FOUND)
        assertThat(removedRefusal.message)
            .overridingErrorMessage("a removed former member must be indistinguishable from a stranger (FR-008/FR-009)")
            .isEqualTo(strangerRefusal.message)
        assertThat(denials(TAG_GET)).isEqualTo(2.0)
    }

    @Test
    fun `gate answers an unknown group identically to a stranger of an existing one`() {
        val strangerRefusal = refusalOf(GROUP, STRANGER, GroupMetrics.AuthzOperation.UPDATE)
        val unknownGroupRefusal = refusalOf(UNKNOWN_GROUP, OWNER, GroupMetrics.AuthzOperation.UPDATE)

        assertThat(unknownGroupRefusal.code).isEqualTo(CODE_GROUP_NOT_FOUND)
        assertThat(unknownGroupRefusal.message)
            .overridingErrorMessage("an unknown group id must answer exactly like membership denied (FR-008)")
            .isEqualTo(strangerRefusal.message)
        assertThat(denials(TAG_UPDATE)).isEqualTo(2.0)
    }

    @Test
    fun `refusal never echoes chat or user identifiers`() {
        val exception =
            assertThrows<GroupNotFoundException> {
                gate.requireActiveMembership(UNKNOWN_GROUP, STRANGER, GroupMetrics.AuthzOperation.LEAVE)
            }

        assertThat(exception.message).doesNotContain(UNKNOWN_GROUP.toString()).doesNotContain(STRANGER.toString())
    }

    // --- the metric: one increment per refusal, split by operation tag ---

    @Test
    fun `denial counter splits per gated operation tag`() {
        refusalOf(GROUP, STRANGER, GroupMetrics.AuthzOperation.GET)
        refusalOf(GROUP, REMOVED_MEMBER, GroupMetrics.AuthzOperation.GET)
        refusalOf(GROUP, STRANGER, GroupMetrics.AuthzOperation.ADD_MEMBERS)

        assertThat(denials(TAG_GET)).isEqualTo(2.0)
        assertThat(denials(TAG_ADD_MEMBERS)).isEqualTo(1.0)
        assertThat(meterRegistry.find(AUTHZ_DENIALS_TOTAL).counters().sumOf { it.count() }).isEqualTo(3.0)
    }

    // --- the port discipline ---

    @Test
    fun `gate consults the active-membership port exactly once per check`() {
        gate.requireActiveMembership(GROUP, OWNER, GroupMetrics.AuthzOperation.GET)
        refusalOf(GROUP, STRANGER, GroupMetrics.AuthzOperation.GET)

        assertThat(participants.findActiveCalls).containsExactly(GROUP to OWNER, GROUP to STRANGER)
    }

    private fun refusalOf(
        chatId: UUID,
        userId: UUID,
        operation: GroupMetrics.AuthzOperation = GroupMetrics.AuthzOperation.GET,
    ): GroupNotFoundException =
        assertThrows<GroupNotFoundException> {
            gate.requireActiveMembership(chatId, userId, operation)
        }

    private fun denials(operation: String): Double =
        meterRegistry
            .find(AUTHZ_DENIALS_TOTAL)
            .tag(TAG_OPERATION, operation)
            .counter()
            ?.count()
            ?: 0.0

    private fun membershipRow(
        userId: UUID,
        role: MemberRole,
        state: MembershipState = MembershipState.ACTIVE,
    ): ChatParticipant =
        ChatParticipant(
            chatId = GROUP,
            userId = userId,
            role = role,
            state = state,
            createdAt = CREATED_AT,
        )

    /**
     * The T008 projection seam: `findActive` mirrors the adapter's
     * `WHERE state='active'` point lookup and remembers every call.
     * Every other port member fails loudly — the gate is a READ-ONLY
     * authorization primitive and must never mutate membership state.
     */
    private class ScriptedParticipantRepository(
        vararg rows: Pair<Pair<UUID, UUID>, ChatParticipant>,
    ) : ParticipantRepository {
        val findActiveCalls = mutableListOf<Pair<UUID, UUID>>()

        private val rowsByPk: Map<Pair<UUID, UUID>, ChatParticipant> = rows.toMap()

        override fun findActive(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? {
            findActiveCalls += chatId to userId
            return rowsByPk[chatId to userId]?.takeIf { it.state == MembershipState.ACTIVE }
        }

        override fun find(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = error("the gate reads the ACTIVE-membership projection only (findActive)")

        override fun findForChat(chatId: UUID): List<ChatParticipant> = error("one caller row, not the roster")

        override fun activeMembers(chatId: UUID): List<ChatParticipant> = error("one caller row, not the roster")

        override fun advanceReadUpTo(
            chatId: UUID,
            userId: UUID,
            upToSeq: Long,
        ): ChatParticipant? = error("the gate never moves the read watermark")

        override fun deleteUpTo(
            chatId: UUID,
            userId: UUID,
            chatLastSeq: Long,
        ): ChatParticipant? = error("the gate never moves the deletion watermark")

        override fun advanceDelivered(
            userId: UUID,
            acks: Map<UUID, Long>,
        ): Unit = error("the gate never acks delivery")

        override fun loadForSync(
            userId: UUID,
            clientCursors: Map<UUID, Long>,
            chatLimit: Int,
        ): UndeliveredChatPage = error("the gate never syncs")

        override fun countUnread(
            userId: UUID,
            chatId: UUID,
        ): Long = error("the gate never counts unread")

        override fun addMember(
            chatId: UUID,
            userId: UUID,
            role: MemberRole,
        ): ChatParticipant = error("the gate never adds members")

        override fun reactivate(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = error("the gate never reactivates members")

        override fun removeMember(
            chatId: UUID,
            userId: UUID,
        ): Boolean = error("the gate never removes members")

        override fun minOtherReadUpToSeq(
            chatId: UUID,
            userId: UUID,
        ): Long = error("the gate never projects the MIN watermark")
    }

    private companion object {
        /** research.md §8 / GroupMetricsTest: the FR-017 authz-denial series. */
        const val AUTHZ_DENIALS_TOTAL = "webchat_group_authz_denials_total"

        const val TAG_OPERATION = "operation"

        /** GroupMetrics.AuthzOperation wire values of the operations exercised here. */
        const val TAG_GET = "get"

        const val TAG_UPDATE = "update"

        const val TAG_ADD_MEMBERS = "add_members"

        const val CODE_GROUP_NOT_FOUND = "group_not_found"

        val GROUP = UUID.fromString("00000000-0000-0000-0000-00000000a0aa")
        val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ADMIN_MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val PLAIN_MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val REMOVED_MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val UNKNOWN_GROUP = UUID.fromString("00000000-0000-0000-0000-0000000000ee")

        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
    }
}
