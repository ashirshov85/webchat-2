package webchat.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.port.ChatReadEvent
import webchat.backend.chats.domain.port.GroupMemberAddedEvent
import webchat.backend.chats.domain.port.GroupMemberUser
import webchat.backend.chats.domain.port.GroupRemovalReason
import webchat.backend.chats.domain.port.GroupRoleChangedEvent
import webchat.backend.chats.domain.port.GroupUpdatedEvent
import webchat.backend.chats.domain.port.GroupYouRemovedEvent
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.repository.JdbcChatRepository
import webchat.backend.chats.repository.JdbcParticipantRepository
import webchat.backend.groups.domain.model.GroupDescription
import webchat.backend.groups.domain.model.GroupTitle
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.model.MembershipState
import webchat.backend.groups.domain.port.GroupAdminAction
import webchat.backend.groups.domain.port.GroupAdminLogEntry
import webchat.backend.groups.domain.port.GroupAdminLogRepository
import webchat.backend.groups.domain.port.GroupRepository
import webchat.backend.realtime.RedisRealtimePublisher
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * T012a (tasks.md Phase 2, constitution VI Test-First) — the THIN ADAPTER
 * SLICE of the group persistence and realtime fan-out legs: real
 * Testcontainers PG 17 + Redis 7 through [AbstractIntegrationTest], no
 * mocks, every method staging its own users — the same discipline as the
 * T010 regression IT of 005. Written RED before the Phase 2 implementation
 * tasks land, this IT PINS the port surface they must provide:
 *
 *  * T007/T012 [GroupRepository]: `create` (the chats row + the whole
 *    roster in ONE transaction, watermarks initialized at `chats.last_seq`
 *    — FR-013), `find`/`findForUpdate` (the `SELECT … FOR UPDATE` row lock
 *    serializing concurrent additions, data-model §Сущность 2 №31),
 *    `updateMetadata`, `countActive`, `deleteHard` (rowcount);
 *  * T008/T011 [JdbcParticipantRepository] port extension: `findActive`,
 *    `activeMembers` (the ≤200 snapshot over `ix_chat_participants_chat_active`),
 *    `addMember`/`reactivate` (first add initializes BOTH watermarks at the
 *    chat head; re-adding a removed member PRESERVES them and resets
 *    `role='member'`, `hidden=false` — FR-002/FR-013), `removeMember`
 *    (ONE conditional UPDATE `state='removed', role='member'` resolved by
 *    rowcount — the kick×leave convergence), `maxOtherReadUpToSeq`
 *    (MAX of the OTHER ACTIVE readers — the ✓✓ rule of FR-012, «read by
 *    at least one»);
 *  * T007/T013 [GroupAdminLogRepository]: the append-only audit of the ten
 *    contract actions (FR-017; the chronology index `(group_id, created_at)`);
 *  * T009/T014 [RedisRealtimePublisher]: the fan-out port legs
 *    `fanoutGroupEvent`/`fanoutMessageCreated`/`fanoutChatRead` — every
 *    addressee's `rt:user:{userId}` channel receives the contract envelope
 *    `{"event":…,"data":…}` (realtime-group-events.md §2–§3; the strictly
 *    post-commit handover is the SERVICE's rule — T021+ — the slice pins
 *    the transport legs and the wire shape);
 *  * T005/T006 domain models the adapters project: `GroupChat`,
 *    `GroupTitle`/`GroupDescription`, `MemberRole`/`MembershipState` and
 *    `ChatParticipant` + `role`/`state`.
 *
 * NOTE (TDD, constitution VI): RED by design until T007–T009 and T011–T014
 * land — like the T005a/T010a unit slices, this file defines the surface,
 * it does not compile against it yet. The №27–№35 HTTP semantics
 * (membership gate, role hierarchy, problem codes) stay with the story ITs
 * (T018/T018a…); everything here is asserted at the adapter boundary.
 */
@Suppress("TooManyFunctions", "LargeClass") // T012a: one method per adapter rule of tasks.md
class GroupRepositoryIT : AbstractIntegrationTest() {
    @Autowired
    private lateinit var groupRepository: GroupRepository

    @Autowired
    private lateinit var adminLogRepository: GroupAdminLogRepository

    @Autowired
    private lateinit var participantRepository: JdbcParticipantRepository

    @Autowired
    private lateinit var chatRepository: JdbcChatRepository

    @Autowired
    private lateinit var realtimePublisher: RedisRealtimePublisher

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var redisConnectionFactory: RedisConnectionFactory

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    // --- create: one transaction, chats + the whole roster (T012) ---

    @Test
    fun `create persists the group row with the whole roster in one transaction`() {
        val owner = newUser()
        val first = newUser()
        val second = newUser()

        val group =
            groupRepository.create(
                title = GroupTitle.normalize("  Проект «Восход»  "),
                description = GroupDescription.normalize("Срез адаптеров T012a"),
                ownerId = owner,
                memberIds = listOf(first, second),
            )

        val chatRow =
            firstRow(CHAT_ROW_SQL, group.id)
                ?: error("create must leave the chats row of the group")
        assertThat(chatRow["kind"])
            .overridingErrorMessage("the group row must carry the kind discriminator (data-model §Сущность 1)")
            .isEqualTo("group")
        assertThat(chatRow["title"]).isEqualTo("Проект «Восход»")
        assertThat(chatRow["description"]).isEqualTo("Срез адаптеров T012a")
        assertThat(chatRow["user_low_id"])
            .overridingErrorMessage("a group row carries the NULL user pair (shape-CHECK of V14)")
            .isNull()
        assertThat(chatRow["user_high_id"]).isNull()

        assertThat(group.title.value).isEqualTo("Проект «Восход»")
        assertThat(group.description?.value).isEqualTo("Срез адаптеров T012a")
        assertThat(group.lastSeq).isZero

        val ownerRow = participantRepository.find(group.id, owner)!!
        assertThat(ownerRow.role).isEqualTo(MemberRole.OWNER)
        assertThat(ownerRow.state).isEqualTo(MembershipState.ACTIVE)
        assertThat(ownerRow.lastReadSeq)
            .overridingErrorMessage("the first add initializes the read watermark at the chat head (FR-013)")
            .isEqualTo(group.lastSeq)
        assertThat(ownerRow.deliveredUpToSeq)
            .overridingErrorMessage("the first add initializes the delivery position at the chat head")
            .isEqualTo(group.lastSeq)

        for (member in listOf(first, second)) {
            val memberRow = participantRepository.find(group.id, member)!!
            assertThat(memberRow.role)
                .overridingErrorMessage("№27 initial members join as plain members; only the creator owns")
                .isEqualTo(MemberRole.MEMBER)
            assertThat(memberRow.state).isEqualTo(MembershipState.ACTIVE)
            assertThat(memberRow.lastReadSeq).isEqualTo(group.lastSeq)
            assertThat(memberRow.deliveredUpToSeq).isEqualTo(group.lastSeq)
        }

        assertThat(participantRepository.activeMembers(group.id).map { it.userId to it.role })
            .overridingErrorMessage("the active roster read must project every membership with its role")
            .containsExactlyInAnyOrder(
                owner to MemberRole.OWNER,
                first to MemberRole.MEMBER,
                second to MemberRole.MEMBER,
            )
        assertThat(groupRepository.countActive(group.id)).isEqualTo(3)
    }

    @Test
    fun `create rolls everything back when the batch violates the roster primary key`() {
        val owner = newUser()
        val member = newUser()

        assertThatExceptionOfType(DuplicateKeyException::class.java).isThrownBy {
            groupRepository.create(
                title = GroupTitle.normalize("Откат"),
                description = null,
                ownerId = owner,
                memberIds = listOf(member, member),
            )
        }

        assertThat(countOf(PARTICIPANTS_OF_USER_SQL, owner))
            .overridingErrorMessage("the failed create must leave NO membership behind — one transaction")
            .isZero
        assertThat(countOf(PARTICIPANTS_OF_USER_SQL, member)).isZero
    }

    // --- find / findForUpdate / updateMetadata (T012) ---

    @Test
    fun `find resolves group rows only and never a direct dialog`() {
        val owner = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Проекция"), null, owner, emptyList())
        val alice = newUser()
        val bob = newUser()
        val direct = chatRepository.ensure(alice, bob).chat

        assertThat(groupRepository.find(group.id)!!.id).isEqualTo(group.id)
        assertThat(groupRepository.find(direct.id))
            .overridingErrorMessage("a direct dialog is not a group resource — №28 answers through the 404 gate")
            .isNull()
        assertThat(groupRepository.find(UUID.randomUUID())).isNull()
    }

    @Test
    fun `findForUpdate holds the chats row lock until the ambient transaction commits`() {
        val owner = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Блокировка"), null, owner, emptyList())
        val holderReady = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val lockedId = AtomicReference<UUID?>()
        val holderFailure = AtomicReference<Throwable?>()

        val holder =
            thread(name = "t012a-row-lock-holder") {
                try {
                    lockedId.set(
                        TransactionTemplate(transactionManager).execute {
                            val locked = groupRepository.findForUpdate(group.id)
                            holderReady.countDown()
                            releaseHolder.await(HOLDER_BUDGET.toMillis(), TimeUnit.MILLISECONDS)
                            locked?.id
                        },
                    )
                } catch (failure: Throwable) {
                    holderFailure.set(failure)
                    holderReady.countDown()
                }
            }

        try {
            assertThat(holderReady.await(READY_BUDGET.toMillis(), TimeUnit.MILLISECONDS))
                .overridingErrorMessage("the holder transaction did not reach the FOR UPDATE read in time")
                .isTrue

            val whileHeld = runCatching { selectForUpdateNowait(group.id) }
            assertThat(whileHeld.isSuccess)
                .overridingErrorMessage(
                    "findForUpdate must take the chats row lock inside the ambient transaction " +
                        "(the №31 serialization of concurrent additions, data-model §Сущность 2)",
                ).isFalse
        } finally {
            releaseHolder.countDown()
        }

        holder.join(HOLDER_BUDGET.toMillis())
        assertThat(holderFailure.get())
            .overridingErrorMessage("the holder transaction must commit cleanly, got: %s", holderFailure.get())
            .isNull()
        assertThat(lockedId.get()).isEqualTo(group.id)

        val afterCommit = runCatching { selectForUpdateNowait(group.id) }
        assertThat(afterCommit.isSuccess)
            .overridingErrorMessage("the row lock must be released at commit — the next addition proceeds")
            .isTrue
    }

    @Test
    fun `updateMetadata stores the last confirmed patch and leaves direct dialogs untouched`() {
        val owner = newUser()
        val group =
            groupRepository.create(
                GroupTitle.normalize("Старое название"),
                GroupDescription.normalize("было"),
                owner,
                emptyList(),
            )

        val updated = groupRepository.updateMetadata(group.id, GroupTitle.normalize("Новое название"), null)!!

        assertThat(updated.title.value).isEqualTo("Новое название")
        assertThat(updated.description).isNull()
        val chatRow = firstRow(CHAT_ROW_SQL, group.id)!!
        assertThat(chatRow["title"]).isEqualTo("Новое название")
        assertThat(chatRow["description"]).isNull()

        assertThat(groupRepository.updateMetadata(UUID.randomUUID(), GroupTitle.normalize("чужое"), null)).isNull()

        val alice = newUser()
        val bob = newUser()
        val direct = chatRepository.ensure(alice, bob).chat
        assertThat(groupRepository.updateMetadata(direct.id, GroupTitle.normalize("чужое"), null))
            .overridingErrorMessage("a direct dialog has no group metadata to patch")
            .isNull()
        val directRow = firstRow(CHAT_ROW_SQL, direct.id)!!
        assertThat(directRow["kind"]).isEqualTo("direct")
        assertThat(directRow["title"]).isNull()
    }

    // --- membership rows: add / reactivate / remove / MIN watermark (T011) ---

    @Test
    fun `addMember initializes both watermarks at the group head`() {
        val owner = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Водяные знаки"), null, owner, emptyList())
        val head = insertMessages(group.id, owner, HEAD_MESSAGES).last()
        stageHead(group.id, head)
        val carol = newUser()

        val added = participantRepository.addMember(group.id, carol, MemberRole.MEMBER)

        assertThat(added.lastReadSeq)
            .overridingErrorMessage("the first add anchors the badge at the chat head — 0 unread (FR-013)")
            .isEqualTo(head)
        assertThat(added.deliveredUpToSeq)
            .overridingErrorMessage("the history before the add is never re-delivered (005 FR-001 discipline)")
            .isEqualTo(head)
        assertThat(added.role).isEqualTo(MemberRole.MEMBER)
        assertThat(added.state).isEqualTo(MembershipState.ACTIVE)
        assertThat(groupRepository.countActive(group.id)).isEqualTo(2)
        assertThat(participantRepository.findActive(group.id, carol)!!.userId).isEqualTo(carol)
    }

    @Test
    fun `reactivate restores membership preserving the watermarks and resetting the role`() {
        val owner = newUser()
        val carol = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Возвращение"), null, owner, listOf(carol))
        participantRepository.advanceReadUpTo(group.id, carol, CAROL_READ)
        participantRepository.advanceDelivered(carol, mapOf(group.id to CAROL_DELIVERED))
        // Stage the away state directly: a removed row with a stale admin role
        // (removeMember resets it, but reactivate must hold the rule on its own)
        // and the hidden flag a №14 per-user deletion may have left behind.
        jdbcTemplate.update(STAGE_AWAY_SQL, group.id, carol)
        val newHead = insertMessages(group.id, owner, HEAD_MESSAGES).last()
        stageHead(group.id, newHead)

        val reactivated = participantRepository.reactivate(group.id, carol)!!

        assertThat(reactivated.state).isEqualTo(MembershipState.ACTIVE)
        assertThat(reactivated.role)
            .overridingErrorMessage("reactivation always rejoins as a plain member (data-model §Сущность 2 №31)")
            .isEqualTo(MemberRole.MEMBER)
        assertThat(reactivated.hidden)
            .overridingErrorMessage("reactivation unhides the dialog for the returning member")
            .isFalse
        assertThat(reactivated.lastReadSeq)
            .overridingErrorMessage("the read watermark SURVIVES the absence (FR-002) — no re-anchoring")
            .isEqualTo(CAROL_READ)
        assertThat(reactivated.deliveredUpToSeq)
            .overridingErrorMessage("the delivery position SURVIVES the absence (FR-002)")
            .isEqualTo(CAROL_DELIVERED)
        assertThat(participantRepository.findActive(group.id, carol)!!.lastReadSeq).isEqualTo(CAROL_READ)
        assertThat(participantRepository.reactivate(group.id, newUser()))
            .overridingErrorMessage("reactivating a user with no row is rowcount 0 — null, not a new membership")
            .isNull()
    }

    @Test
    fun `removeMember resolves the single conditional update by rowcount`() {
        val owner = newUser()
        val member = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Исключение"), null, owner, listOf(member))
        participantRepository.advanceReadUpTo(group.id, member, MEMBER_READ)

        assertThat(participantRepository.removeMember(group.id, member)).isTrue()

        val removedRow = participantRepository.find(group.id, member)!!
        assertThat(removedRow.state).isEqualTo(MembershipState.REMOVED)
        assertThat(removedRow.role)
            .overridingErrorMessage("the single UPDATE also normalizes the role to member")
            .isEqualTo(MemberRole.MEMBER)
        assertThat(removedRow.lastReadSeq)
            .overridingErrorMessage("the watermarks stay in the row for the re-add semantics (FR-002/FR-005)")
            .isEqualTo(MEMBER_READ)
        assertThat(participantRepository.findActive(group.id, member))
            .overridingErrorMessage("a removed row is NOT an active membership (FR-008)")
            .isNull()
        assertThat(groupRepository.countActive(group.id)).isEqualTo(1)

        assertThat(participantRepository.removeMember(group.id, member))
            .overridingErrorMessage(
                "a repeated removal is rowcount 0 — the kick×leave race has already resolved (edge, api-contract §2)",
            ).isFalse()
        assertThat(participantRepository.removeMember(group.id, newUser()))
            .overridingErrorMessage("removing a never-member is rowcount 0")
            .isFalse()
    }

    @Test
    fun `maxOtherReadUpToSeq folds the active members besides the reader`() {
        val owner = newUser()
        val first = newUser()
        val second = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Максимум"), null, owner, listOf(first, second))
        stageHead(group.id, insertMessages(group.id, owner, FOLD_MESSAGES).last())
        participantRepository.advanceReadUpTo(group.id, owner, OWNER_READ)
        participantRepository.advanceReadUpTo(group.id, first, FIRST_READ)
        participantRepository.advanceReadUpTo(group.id, second, SECOND_READ)

        assertThat(participantRepository.maxOtherReadUpToSeq(group.id, owner))
            .overridingErrorMessage(
                "the fold is the MAX of the OTHER readers (FR-012, «read by at least one»): " +
                    "MAX(FIRST_READ, SECOND_READ) = SECOND_READ — the earliest reader already sets ✓✓",
            ).isEqualTo(SECOND_READ)
        assertThat(participantRepository.maxOtherReadUpToSeq(group.id, first)).isEqualTo(OWNER_READ)
        assertThat(participantRepository.maxOtherReadUpToSeq(group.id, second)).isEqualTo(OWNER_READ)

        participantRepository.removeMember(group.id, second)
        assertThat(participantRepository.maxOtherReadUpToSeq(group.id, owner))
            .overridingErrorMessage(
                "the departure of the furthest reader lowers the MAX — new sessions pin to the " +
                    "lower bound; a live client holds its achieved maximum (FR-012)",
            ).isEqualTo(FIRST_READ)

        participantRepository.removeMember(group.id, first)
        assertThat(participantRepository.maxOtherReadUpToSeq(group.id, owner))
            .overridingErrorMessage("a group of one never reaches ✓✓ — 0 (data-model §Правила видимости)")
            .isZero
    }

    // --- append-only admin log (T013) ---

    @Test
    fun `admin log keeps the append-only chronology of all ten actions`() {
        val owner = newUser()
        val target = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Журнал"), null, owner, listOf(target))

        adminLogRepository.append(GroupAdminLogEntry(group.id, owner, null, GroupAdminAction.GROUP_CREATED))
        TimeUnit.MILLISECONDS.sleep(CHRONOLOGY_GAP_MILLIS)
        adminLogRepository.append(GroupAdminLogEntry(group.id, owner, target, GroupAdminAction.MEMBER_ADDED))
        TimeUnit.MILLISECONDS.sleep(CHRONOLOGY_GAP_MILLIS)
        adminLogRepository.append(GroupAdminLogEntry(group.id, owner, null, GroupAdminAction.TITLE_CHANGED))

        val chronology = adminLogRepository.chronology(group.id)
        assertThat(chronology.map { it.action })
            .overridingErrorMessage("the journal reads back in the (group_id, created_at) chronology")
            .containsExactly(
                GroupAdminAction.GROUP_CREATED,
                GroupAdminAction.MEMBER_ADDED,
                GroupAdminAction.TITLE_CHANGED,
            )
        assertThat(chronology[1].targetUserId).isEqualTo(target)
        assertThat(chronology.map { it.createdAt })
            .overridingErrorMessage("every persisted entry carries its server timestamp")
            .allSatisfy { createdAt -> assertThat(createdAt).isAfter(Instant.EPOCH) }

        for (action in enumValues<GroupAdminAction>()) {
            adminLogRepository.append(GroupAdminLogEntry(group.id, owner, target, action))
        }
        val loggedActions = adminLogRepository.chronology(group.id).map { it.action }.toSet()
        assertThat(loggedActions)
            .overridingErrorMessage("the CHECK enum of V14 admits exactly the ten contract actions (FR-017)")
            .containsExactlyInAnyOrderElementsOf(enumValues<GroupAdminAction>().toList())

        val stranger = newUser()
        val otherGroup = groupRepository.create(GroupTitle.normalize("Чужой журнал"), null, stranger, emptyList())
        assertThat(adminLogRepository.chronology(otherGroup.id))
            .overridingErrorMessage("the chronology never leaks entries of another group")
            .isEmpty()
    }

    // --- hard-delete: the CASCADE and the surviving journal (T012/T013) ---

    @Test
    fun `deleteHard wipes the chat cascade and the journal survives it`() {
        val owner = newUser()
        val member = newUser()
        val group = groupRepository.create(GroupTitle.normalize("Удаление"), null, owner, listOf(member))
        insertMessages(group.id, member, HEAD_MESSAGES)
        adminLogRepository.append(GroupAdminLogEntry(group.id, owner, null, GroupAdminAction.GROUP_CREATED))
        adminLogRepository.append(GroupAdminLogEntry(group.id, owner, member, GroupAdminAction.MEMBER_ADDED))

        assertThat(groupRepository.deleteHard(group.id)).isTrue()

        assertThat(firstRow(CHAT_ROW_SQL, group.id))
            .overridingErrorMessage("№30 is a hard delete — the chats row is gone")
            .isNull()
        assertThat(countOf(PARTICIPANTS_OF_CHAT_SQL, group.id))
            .overridingErrorMessage("the membership rows CASCADE away — watermarks and counters are wiped (FR-006)")
            .isZero
        assertThat(countOf(MESSAGES_OF_CHAT_SQL, group.id))
            .overridingErrorMessage("the history CASCADEs away with the chat row")
            .isZero

        assertThat(adminLogRepository.chronology(group.id).map { it.action })
            .overridingErrorMessage("group_admin_log has NO FK on chats — the audit trail survives the delete (FR-017)")
            .containsExactly(GroupAdminAction.GROUP_CREATED, GroupAdminAction.MEMBER_ADDED)
        adminLogRepository.append(GroupAdminLogEntry(group.id, owner, null, GroupAdminAction.GROUP_DELETED))
        assertThat(adminLogRepository.chronology(group.id))
            .overridingErrorMessage("the №30 fact lands in the journal of the already-deleted group")
            .hasSize(3)

        assertThat(groupRepository.deleteHard(group.id))
            .overridingErrorMessage("a repeated №30 is rowcount 0 — the 404 convergence of api-contract.md §2")
            .isFalse()

        val alice = newUser()
        val bob = newUser()
        val direct = chatRepository.ensure(alice, bob).chat
        assertThat(groupRepository.deleteHard(direct.id))
            .overridingErrorMessage("№30 deletes groups only — a direct dialog is not a group resource")
            .isFalse()
        assertThat(firstRow(CHAT_ROW_SQL, direct.id)!!["kind"]).isEqualTo("direct")
    }

    // --- realtime fan-out legs over rt:user:{userId} (T014) ---

    @Test
    fun `fanout delivers a group event to every addressee channel`() {
        val owner = newUser()
        val member = newUser()
        val groupId = UUID.randomUUID()

        ChannelRecorder().use { recorder ->
            recorder.record(channelOf(owner))
            recorder.record(channelOf(member))
            recorder.awaitReady()

            realtimePublisher.fanoutGroupEvent(
                listOf(owner, member),
                GroupMemberAddedEvent(
                    groupId = groupId,
                    user = GroupMemberUser(member, "carol", "carol@example.com", "active", T1),
                    actorId = owner,
                ),
            )

            for (addressee in listOf(owner, member)) {
                val data = recorder.awaitFrame(channelOf(addressee), "group.member.added")
                assertThat(UUID.fromString(data["groupId"].asText())).isEqualTo(groupId)
                assertThat(UUID.fromString(data["actorId"].asText())).isEqualTo(owner)
                assertThat(fieldNames(data["user"]))
                    .overridingErrorMessage(
                        "the added member rides the contract PublicUser shape (realtime-group-events.md §3.2)",
                    ).containsExactlyInAnyOrder("id", "username", "email", "status", "createdAt")
                assertThat(data["user"]["id"].asText()).isEqualTo(member.toString())
                assertThat(data["user"]["username"].asText()).isEqualTo("carol")
            }

            realtimePublisher.fanoutGroupEvent(
                listOf(owner),
                GroupUpdatedEvent(groupId = groupId, title = "Новое название", description = null, actorId = owner),
            )

            val updated = recorder.awaitFrame(channelOf(owner), "group.updated")
            assertThat(updated["title"].asText()).isEqualTo("Новое название")
            assertThat(updated.has("description") && updated["description"].isNull)
                .overridingErrorMessage("a null description rides the frame as null, never dropped (§3.1)")
                .isTrue
        }
    }

    @Test
    fun `the final you_removed event rides only the affected channel with contract enums`() {
        val alice = newUser()
        val bob = newUser()
        val groupId = UUID.randomUUID()

        ChannelRecorder().use { recorder ->
            recorder.record(channelOf(alice))
            recorder.record(channelOf(bob))
            recorder.awaitReady()

            realtimePublisher.fanoutGroupEvent(listOf(alice), GroupYouRemovedEvent(groupId, GroupRemovalReason.KICKED))
            realtimePublisher.fanoutGroupEvent(
                listOf(alice, bob),
                GroupRoleChangedEvent(groupId = groupId, userId = bob, role = MemberRole.ADMIN, actorId = alice),
            )

            val youRemoved = recorder.awaitFrame(channelOf(alice), "group.you_removed")
            assertThat(UUID.fromString(youRemoved["groupId"].asText())).isEqualTo(groupId)
            assertThat(youRemoved["reason"].asText())
                .overridingErrorMessage("the wire enum is the lower-case contract value, not the Kotlin name")
                .isEqualTo("kicked")

            assertThat(recorder.awaitFrame(channelOf(alice), "group.role.changed")["role"].asText()).isEqualTo("admin")
            assertThat(recorder.awaitFrame(channelOf(bob), "group.role.changed")["role"].asText()).isEqualTo("admin")

            recorder.assertNoFrame(channelOf(bob), "group.you_removed")
        }
    }

    @Test
    fun `message and read fanouts ride the same channels with contract payloads`() {
        val sender = newUser()
        val first = newUser()
        val second = newUser()
        val groupId = UUID.randomUUID()
        val message =
            Message(
                id = UUID.randomUUID(),
                chatId = groupId,
                senderId = sender,
                text = MessageText.normalize("  привет, группа  "),
                seq = GROUP_MESSAGE_SEQ,
                createdAt = T1,
            )

        ChannelRecorder().use { recorder ->
            recorder.record(channelOf(sender))
            recorder.record(channelOf(first))
            recorder.record(channelOf(second))
            recorder.awaitReady()

            realtimePublisher.fanoutMessageCreated(
                listOf(first, second),
                MessageCreatedEvent(chatId = groupId, message = message),
            )

            for (addressee in listOf(first, second)) {
                val data = recorder.awaitFrame(channelOf(addressee), "message.created")
                assertThat(UUID.fromString(data["chatId"].asText())).isEqualTo(groupId)
                assertThat(fieldNames(data["message"]))
                    .overridingErrorMessage("the fanned-out message keeps the contract Message shape (§3.7)")
                    .containsExactlyInAnyOrder("id", "chatId", "senderId", "text", "seq", "createdAt")
                assertThat(data["message"]["text"].asText()).isEqualTo("привет, группа")
                assertThat(data["message"]["seq"].asLong()).isEqualTo(GROUP_MESSAGE_SEQ)
            }
            recorder.assertNoFrame(channelOf(sender), "message.created")

            realtimePublisher.fanoutChatRead(
                listOf(sender, first),
                ChatReadEvent(chatId = groupId, readUpToSeq = GROUP_MESSAGE_SEQ, byUserId = second),
            )

            val read = recorder.awaitFrame(channelOf(sender), "chat.read")
            assertThat(UUID.fromString(read["chatId"].asText())).isEqualTo(groupId)
            assertThat(read["readUpToSeq"].asLong()).isEqualTo(GROUP_MESSAGE_SEQ)
            assertThat(UUID.fromString(read["byUserId"].asText())).isEqualTo(second)
        }
    }

    // --- staging helpers (the T010 discipline: raw SQL only for state the ports cannot write) ---

    private fun newUser(): UUID {
        val id = UUID.randomUUID()
        val login = "t012a-${id.toString().substring(0, 8)}"
        jdbcTemplate.update(INSERT_USER_SQL, id, login, "$login@example.com")
        return id
    }

    private fun insertMessages(
        chatId: UUID,
        senderId: UUID,
        count: Int,
    ): List<Long> =
        (0 until count).map { index ->
            jdbcTemplate.queryForObject(
                INSERT_MESSAGE_SQL,
                Long::class.java,
                UUID.randomUUID(),
                chatId,
                senderId,
                "t012a message $index",
                Timestamp.from(T1),
            ) ?: error("INSERT … RETURNING seq must answer")
        }

    private fun stageHead(
        chatId: UUID,
        head: Long,
    ) {
        jdbcTemplate.update(STAGE_HEAD_SQL, head, chatId)
    }

    private fun firstRow(
        sql: String,
        vararg args: Any,
    ): Map<String, Any>? = jdbcTemplate.queryForList(sql, *args).firstOrNull()

    private fun countOf(
        sql: String,
        vararg args: Any,
    ): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args) ?: 0

    /** A second session's `FOR UPDATE NOWAIT` probe — fails fast while the row is locked. */
    private fun selectForUpdateNowait(chatId: UUID) {
        val dataSource = jdbcTemplate.dataSource ?: error("the JdbcTemplate must carry its DataSource")
        dataSource.connection.use { connection ->
            connection.prepareStatement(NOWAIT_PROBE_SQL).use { statement ->
                statement.setObject(1, chatId)
                statement.executeQuery().use { resultSet -> resultSet.next() }
            }
        }
    }

    private fun channelOf(userId: UUID): String = "$CHANNEL_PREFIX$userId"

    private fun fieldNames(node: JsonNode): List<String> = node.fieldNames().asSequence().toList()

    /**
     * The T012a subscription seam: a PRIVATE listener container on the
     * shared Testcontainers connection factory. The app's own container
     * stays subscribed to nothing in this IT (no SSE session is open), so
     * every frame the recorder observes is exactly the Redis PUBLISH of
     * the port under test on `rt:user:{userId}`.
     */
    private inner class ChannelRecorder : AutoCloseable {
        private val container =
            RedisMessageListenerContainer().apply {
                setConnectionFactory(this@GroupRepositoryIT.redisConnectionFactory)
                afterPropertiesSet()
                start()
            }

        private val frames: ConcurrentLinkedQueue<Pair<String, String>> = ConcurrentLinkedQueue()

        private val channels: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val objectMapper = ObjectMapper()

        fun record(channel: String) {
            channels += channel
            container.addMessageListener(
                MessageListener { message, _ ->
                    frames +=
                        String(message.channel, Charsets.UTF_8) to String(message.body, Charsets.UTF_8)
                },
                ChannelTopic(channel),
            )
        }

        /**
         * Redis Pub/Sub drops frames of channels the subscription has not
         * reached yet, so readiness is proven by a warm-up round trip per
         * channel: probe frames are re-sent until every channel echoes one
         * back, and only then the queue is cleared for the real asserts.
         */
        fun awaitReady() {
            val deadline = System.nanoTime() + READY_BUDGET.toNanos()
            val observed = mutableSetOf<String>()
            while (observed != channels) {
                if (System.nanoTime() > deadline) {
                    error("the recorder did not observe warm-up frames of $channels within $READY_BUDGET")
                }
                channels.forEach { redisTemplate.convertAndSend(it, WARMUP_ENVELOPE) }
                TimeUnit.MILLISECONDS.sleep(POLL_MILLIS)
                frames.forEach { (channel, body) -> if (body == WARMUP_ENVELOPE) observed += channel }
            }
            frames.clear()
        }

        fun awaitFrame(
            channel: String,
            eventName: String,
        ): JsonNode {
            val deadline = System.nanoTime() + FRAME_BUDGET.toNanos()
            while (true) {
                frames.filter { it.first == channel }.forEach { (_, body) ->
                    val envelope = objectMapper.readTree(body)
                    if (envelope["event"]?.asText() == eventName) return envelope["data"]!!
                }
                if (System.nanoTime() > deadline) {
                    throw AssertionError(
                        "no <$eventName> frame reached <$channel> within $FRAME_BUDGET; frames: $frames",
                    )
                }
                TimeUnit.MILLISECONDS.sleep(POLL_MILLIS)
            }
        }

        fun assertNoFrame(
            channel: String,
            eventName: String,
        ) {
            TimeUnit.MILLISECONDS.sleep(NO_FRAME_WINDOW.toMillis())
            frames.filter { it.first == channel }.forEach { (_, body) ->
                val envelope = objectMapper.readTree(body)
                assertThat(envelope["event"]?.asText())
                    .overridingErrorMessage("an unexpected <$eventName> frame reached the channel: $body")
                    .isNotEqualTo(eventName)
            }
        }

        override fun close() {
            runCatching { container.destroy() }
        }
    }

    private companion object {
        /** data-model 006 §6: the per-user fan-out channel family of the realtime transport. */
        const val CHANNEL_PREFIX = "rt:user:"

        val INSERT_USER_SQL =
            """
            INSERT INTO users (id, username, email, status)
            VALUES (?, ?, ?, 'pending_email_confirmation')
            """.trimIndent()

        val INSERT_MESSAGE_SQL =
            """
            INSERT INTO messages (id, chat_id, sender_id, text, created_at)
            VALUES (?, ?, ?, ?, ?)
            RETURNING seq
            """.trimIndent()

        const val STAGE_HEAD_SQL = "UPDATE chats SET last_seq = ? WHERE id = ?"

        /** Away staging for the reactivate slice: removed + stale admin role + hidden (data-model §Сущность 2). */
        val STAGE_AWAY_SQL =
            """
            UPDATE chat_participants
            SET state = 'removed', role = 'admin', hidden = true
            WHERE chat_id = ? AND user_id = ?
            """.trimIndent()

        val CHAT_ROW_SQL =
            """
            SELECT kind, title, description, user_low_id, user_high_id
            FROM chats
            WHERE id = ?
            """.trimIndent()

        val PARTICIPANTS_OF_USER_SQL =
            """
            SELECT count(*) FROM chat_participants WHERE user_id = ?
            """.trimIndent()

        val PARTICIPANTS_OF_CHAT_SQL =
            """
            SELECT count(*) FROM chat_participants WHERE chat_id = ?
            """.trimIndent()

        val MESSAGES_OF_CHAT_SQL =
            """
            SELECT count(*) FROM messages WHERE chat_id = ?
            """.trimIndent()

        val NOWAIT_PROBE_SQL =
            """
            SELECT id FROM chats WHERE id = ? FOR UPDATE NOWAIT
            """.trimIndent()

        /** A warm-up frame the recorder itself sends until Redis routes the channel to it. */
        const val WARMUP_ENVELOPE = """{"event":"__t012a_ready","data":{}}"""

        /** Deterministic instants for payloads and staged rows. */
        val T1 = Instant.parse("2026-01-01T00:00:00Z")

        const val HEAD_MESSAGES = 5
        const val FOLD_MESSAGES = 10
        const val GROUP_MESSAGE_SEQ = 7L
        const val CAROL_READ = 2L
        const val CAROL_DELIVERED = 1L
        const val MEMBER_READ = 3L
        const val OWNER_READ = 10L
        const val FIRST_READ = 4L
        const val SECOND_READ = 7L

        /**
         * PG `now()` is the TRANSACTION start time — a small gap between
         * the ordered appends keeps the chronology strictly monotone.
         */
        const val CHRONOLOGY_GAP_MILLIS = 10L

        const val POLL_MILLIS = 20L

        /** CI-tolerant budgets of the Redis round trips (SC-001 latency is the k6 profile's business). */
        val READY_BUDGET: Duration = Duration.ofSeconds(5)
        val FRAME_BUDGET: Duration = Duration.ofSeconds(5)
        val NO_FRAME_WINDOW: Duration = Duration.ofMillis(1_500)

        /** The row-lock holder waits/reports within these bounds. */
        val HOLDER_BUDGET: Duration = Duration.ofSeconds(15)
    }
}
