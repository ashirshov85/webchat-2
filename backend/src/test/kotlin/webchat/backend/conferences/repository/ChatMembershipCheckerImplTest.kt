package webchat.backend.conferences.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.model.MembershipState
import java.time.Instant
import java.util.UUID

/**
 * T013 unit regression (tasks.md Foundational): the adapter is a pure
 * projection of the 006 membership port — pinned here against the
 * port's documented contract (research.md §2, the
 * `GroupMembershipGate`/`ParticipantRepository` mechanism):
 *
 *  * **member** — an ACTIVE row (a group member with a role AND a
 *    direct-dialog peer — the same read answers for both chat kinds)
 *    projects `true`;
 *  * **non-member** — a stranger and an unknown chatId answer `null`
 *    ALIKE at the port, projecting the uniform `false`;
 *  * **left member** — a №32-kicked/№33-left row is REMOVED, the
 *    ACTIVE projection folds it into the same `null` → `false`,
 *    indistinguishable from a stranger (FR-003 edge: the T020
 *    listener has already disconnected him, and his next №47 renders
 *    the same `403 conference_forbidden` — the re-check this gate
 *    answers);
 *  * one `findActive` read per check, NO mutation leg of the 006 port
 *    is ever touched (the watermarks/role survive in the row — a
 *    later №31 re-add is none of this adapter's business);
 *  * `activeMemberIds` hands back the roster ids in the port's
 *    deterministic `created_at, user_id` order — the SSE addressee
 *    snapshot shape of T054/T055.
 *
 * The SQL truth of `state='active'` itself is pinned by the 006
 * repository IT set; the join-gate end-to-end (403 before any
 * participant row) — by the IT T021.
 */
class ChatMembershipCheckerImplTest {
    private val participants = Mockito.mock(ParticipantRepository::class.java)

    private val checker = ChatMembershipCheckerImpl(participants)

    @Test
    fun `active group membership projects true`() {
        val chatId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        Mockito
            .`when`(participants.findActive(chatId, userId))
            .thenReturn(
                ChatParticipant(
                    chatId = chatId,
                    userId = userId,
                    role = MemberRole.MEMBER,
                    state = MembershipState.ACTIVE,
                    createdAt = T1,
                ),
            )

        assertThat(checker.isMember(chatId, userId))
            .overridingErrorMessage("an ACTIVE membership row is a member, whatever the role")
            .isTrue
    }

    @Test
    fun `active direct-dialog membership projects true`() {
        val chatId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        Mockito
            .`when`(participants.findActive(chatId, userId))
            .thenReturn(
                ChatParticipant(
                    chatId = chatId,
                    userId = userId,
                    // a direct dialog carries no group projection — still a member
                    role = null,
                    state = MembershipState.ACTIVE,
                    createdAt = T1,
                ),
            )

        assertThat(checker.isMember(chatId, userId))
            .overridingErrorMessage("the same ACTIVE-row read answers for BOTH chat kinds")
            .isTrue
    }

    @Test
    fun `stranger projects false`() {
        val chatId = UUID.randomUUID()
        Mockito
            .`when`(participants.findActive(chatId, STRANGER_ID))
            .thenReturn(null)

        assertThat(checker.isMember(chatId, STRANGER_ID))
            .overridingErrorMessage("a stranger never mints (SC-002)")
            .isFalse
    }

    @Test
    fun `unknown chat projects false - no existence disclosure`() {
        val chatId = UUID.randomUUID()
        Mockito
            .`when`(participants.findActive(chatId, STRANGER_ID))
            .thenReturn(null)

        assertThat(checker.isMember(chatId, STRANGER_ID))
            .overridingErrorMessage("an unknown chatId answers exactly like a stranger's chat")
            .isFalse
    }

    @Test
    fun `left member projects false - indistinguishable from a stranger`() {
        val chatId = UUID.randomUUID()
        val leftId = UUID.randomUUID()
        // the №33-leave/№32-kick aftermath: the row survives with its
        // watermarks, but the port's ACTIVE projection answers `null`
        // for a REMOVED membership — the adapter renders the uniform
        // `false` without ever looking at the row's leftovers
        Mockito
            .`when`(participants.findActive(chatId, leftId))
            .thenReturn(null)

        assertThat(checker.isMember(chatId, leftId))
            .overridingErrorMessage("a REMOVED member re-joining after №32/№33 is the same false as a stranger")
            .isFalse
    }

    @Test
    fun `isMember is one active-row read - no mutation of the 006 state`() {
        val chatId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        Mockito
            .`when`(participants.findActive(chatId, userId))
            .thenReturn(
                ChatParticipant(
                    chatId = chatId,
                    userId = userId,
                    role = MemberRole.ADMIN,
                    state = MembershipState.ACTIVE,
                    createdAt = T1,
                ),
            )

        checker.isMember(chatId, userId)

        Mockito.verify(participants).findActive(chatId, userId)
        Mockito.verifyNoMoreInteractions(participants)
    }

    @Test
    fun `activeMemberIds projects the roster ids in the port order`() {
        val chatId = UUID.randomUUID()
        val first = memberOf(chatId, UUID.randomUUID(), createdAt = T1)
        val second = memberOf(chatId, UUID.randomUUID(), createdAt = T1.plusSeconds(1))
        val third = memberOf(chatId, UUID.randomUUID(), createdAt = T1.plusSeconds(2))
        Mockito
            .`when`(participants.activeMembers(chatId))
            .thenReturn(listOf(first, second, third))

        val ids = checker.activeMemberIds(chatId)

        assertThat(ids)
            .overridingErrorMessage("the deterministic created_at, user_id roster order of 006 survives the projection")
            .containsExactly(first.userId, second.userId, third.userId)
    }

    @Test
    fun `activeMemberIds of a roster without members is empty`() {
        val chatId = UUID.randomUUID()
        Mockito
            .`when`(participants.activeMembers(chatId))
            .thenReturn(emptyList())

        assertThat(checker.activeMemberIds(chatId)).isEmpty()
    }

    private companion object {
        val T1: Instant = Instant.parse("2026-10-15T12:00:00Z")

        val STRANGER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c0de")

        fun memberOf(
            chatId: UUID,
            userId: UUID,
            createdAt: Instant,
        ): ChatParticipant =
            ChatParticipant(
                chatId = chatId,
                userId = userId,
                role = MemberRole.MEMBER,
                state = MembershipState.ACTIVE,
                createdAt = createdAt,
            )
    }
}
