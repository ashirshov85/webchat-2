package webchat.backend.groups.domain.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.GroupMemberAddedEvent
import webchat.backend.chats.domain.port.GroupMemberUser
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.config.GroupsProperties
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.model.UserProfile
import webchat.backend.contacts.domain.port.ContactRepository
import webchat.backend.contacts.domain.port.UserLookupPort
import webchat.backend.groups.GroupMetrics
import webchat.backend.groups.api.dto.GroupMember
import webchat.backend.groups.api.dto.GroupView
import webchat.backend.groups.api.dto.PublicUserView
import webchat.backend.groups.api.dto.memberRoleLabel
import webchat.backend.groups.domain.model.GroupChat
import webchat.backend.groups.domain.model.GroupDescription
import webchat.backend.groups.domain.model.GroupTitle
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.port.GroupAdminAction
import webchat.backend.groups.domain.port.GroupAdminLogEntry
import webchat.backend.groups.domain.port.GroupAdminLogRepository
import webchat.backend.groups.domain.port.GroupRepository
import java.util.UUID

/**
 * T021 — the №27/№31 roster scenarios of User Story 1 (FR-001/FR-002,
 * FR-004, FR-013; api-contract.md 006 §2): the create of a group with the
 * creator as the single `owner` and the initial roster sourced from HIS
 * contacts, and the owner/admin batch add with the idempotent re-add and
 * the 200-member capacity gate.
 *
 * Ordering discipline (pinned red-first by T018/T018a):
 *  * №27 — `400 self_forbidden` for the creator inside the roster is
 *    decided BEFORE the contact gate (the caller can never be his own
 *    contact, so the typed self refusal must win), then `422
 *    not_in_contacts` atomically (the whole request dies, no group row),
 *    then the capacity `409 group_full` (the lowered
 *    `groups.max-members` probe);
 *  * №31 — every gated scenario starts with [GroupMembershipGate]
 *    (FR-008: a stranger, a removed former member and an unknown chat id
 *    are the SAME counted `404 group_not_found`), then the group row
 *    resolves under `FOR UPDATE` (a DIRECT dialog of the caller answers
 *    the same uniform 404 — it is not a group resource at all), then the
 *    [GroupRolePolicy] role gate (FR-004), and only then the batch rules
 *    in the №27 order above.
 *
 * ONE transaction per scenario ([Transactional]): the `chats` row, the
 * roster rows, BOTH watermarks anchored at `chats.last_seq` (FR-013 —
 * both live in the repository statements of T012/T011) and the journal
 * facts of [GroupAdminLogRepository] commit or roll back TOGETHER — a
 * rejected batch leaves nothing behind (FR-002 atomicity). The
 * `group.member.added` frames (realtime-group-events.md 006 §3.2) are
 * addressed to the post-operation ACTIVE-roster snapshot taken INSIDE
 * that transaction (the added members included) and published strictly
 * AFTER the commit via a transaction synchronization — the same
 * post-commit ordering guarantee the 004 send/read paths hold (a
 * pre-commit frame could announce a group a rollback then erases).
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions") // the №27/№31 collaborators, one port per leg (DIP, plan.md VIII)
class GroupService(
    private val groupRepository: GroupRepository,
    private val participants: ParticipantRepository,
    private val contacts: ContactRepository,
    private val users: UserLookupPort,
    private val adminLog: GroupAdminLogRepository,
    private val membershipGate: GroupMembershipGate,
    private val rolePolicy: GroupRolePolicy,
    private val realtime: RealtimeEventPublisher,
    private val metrics: GroupMetrics,
    private val properties: GroupsProperties,
) {
    private val log = LoggerFactory.getLogger(GroupService::class.java)

    /**
     * №27 `POST /api/v1/groups` (api-contract.md 006 №27, FR-001/FR-002):
     * the group row, the creator row `role='owner'` and every initial
     * member `role='member'` land in ONE repository transaction with BOTH
     * watermarks of EVERY roster row anchored at `chats.last_seq` (a fresh
     * group starts at 0 — the badge-from-0 semantics of FR-013), the
     * journal records `group_created` + `member_added`×N in the SAME
     * transaction, and the answer is the caller's [GroupView] (the
     * creator an `owner`, the roster in the deterministic `joined_at,
     * user_id` order of the active-roster read).
     *
     * An empty description ([GroupDescription] of an empty value) stores
     * as «no description» — the optional field of FR-001 never persists a
     * meaningless empty string. Post-commit, every initial member rides a
     * `group.member.added` frame to ALL active participants INCLUDING the
     * creator (realtime-group-events.md 006 §3.2 — the group appears in
     * everyone's «Чаты» at once); a creator-only group publishes nothing
     * (nothing was added — the creator already holds the group from this
     * very answer, and the at-most-once channel never replays).
     */
    @Transactional
    fun create(
        callerId: UUID,
        title: GroupTitle,
        description: GroupDescription?,
        memberIds: List<UUID>,
    ): GroupView {
        requireNotSelf(callerId, memberIds, MEMBER_USER_IDS_FIELD)
        requireAllContactsOf(callerId, memberIds, MEMBER_USER_IDS_FIELD)
        requireWithinCapacity(activeCount = 0, joiningCount = 1 + memberIds.size)
        val group = groupRepository.create(title, description?.takeIf { it.value.isNotEmpty() }, callerId, memberIds)
        adminLog.append(
            GroupAdminLogEntry(group.id, callerId, targetUserId = null, action = GroupAdminAction.GROUP_CREATED),
        )
        memberIds.forEach { memberId ->
            adminLog.append(GroupAdminLogEntry(group.id, callerId, memberId, GroupAdminAction.MEMBER_ADDED))
        }
        val roster = participants.activeMembers(group.id)
        val profiles = profilesOf(roster)
        val addressees = roster.map(ChatParticipant::userId)
        memberIds.forEach { addedId ->
            publishMemberAddedAfterCommit(group.id, addedId, profiles, callerId, addressees)
        }
        metrics.recordGroupSize(roster.size)
        val myRole =
            requireNotNull(roster.single { it.userId == callerId }.role) {
                "the creator row of group <${group.id}> must carry the owner role"
            }
        return GroupView(
            chatId = group.id,
            title = group.title.value,
            description = group.description?.value,
            myRole = memberRoleLabel(myRole),
            members = roster.map { row -> memberView(row, profiles) },
        )
    }

    /**
     * №28 `GET /api/v1/groups/{chatId}` (api-contract.md 006 №28,
     * FR-008): the gated read of one group — the membership gate FIRST
     * (a stranger, a removed former member and an unknown chat id are
     * the SAME counted `404 group_not_found`, the authz metric riding
     * the gate), then the group row resolves through the
     * `kind='group'` predicate of [GroupRepository.find]: the chatId of
     * the caller's own DIRECT dialog passes the gate (an active
     * participant row exists) and STILL renders the same uniform 404 —
     * a direct dialog is not a group resource and its existence as one
     * is never disclosed (FR-008/FR-009).
     *
     * A proven member reads the [GroupView] with HIS `myRole` (the
     * gate's own row, not a roster re-lookup) and the ACTIVE roster
     * with roles (≤ 200, only `state='active'` rows). A pure read — no
     * journal fact, no frame, no metric beyond a refused gate.
     */
    fun get(
        callerId: UUID,
        chatId: UUID,
    ): GroupView {
        val membership = membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.GET)
        val group = resolveGroup(chatId)
        val roster = participants.activeMembers(group.id)
        val profiles = profilesOf(roster)
        val myRole =
            requireNotNull(membership.role) {
                "the gate row of an active group membership must carry a role (V14 ck_chat_participants_role)"
            }
        return GroupView(
            chatId = group.id,
            title = group.title.value,
            description = group.description?.value,
            myRole = memberRoleLabel(myRole),
            members = roster.map { row -> memberView(row, profiles) },
        )
    }

    /**
     * №31 `POST /api/v1/groups/{chatId}/members` (api-contract.md 006 №31,
     * FR-002/FR-004/FR-013): the membership gate → the `FOR UPDATE`
     * serialization of the group row → the add-members role gate → the
     * batch rules (self, contacts of the ADDER, capacity) → the writes.
     *
     * Per candidate, in the locked section: an ACTIVE row is SKIPPED (the
     * idempotent `200` of api-contract.md 006 §2 — no duplicate, no
     * journal fact, no frame), a REMOVED row is REACTIVATED preserving
     * both 004/005 watermarks with the role reset to `member`
     * (data-model 006 §Сущность 2), and a first-timer is INSERTED with
     * both watermarks anchored at the chat head (FR-013: the badge starts
     * at 0 over the pre-existing history). Every actual add journals
     * `member_added` in the same transaction and fans a
     * `group.member.added` frame post-commit to the post-operation ACTIVE
     * roster (the added members included — the group becomes visible in
     * their «Чаты» at once). The capacity check counts ONLY the genuinely
     * joining candidates (`active + incoming > max` — the conflict bound
     * of FR-002), so an idempotent re-add at a full roster still answers
     * `200`; the whole batch is atomic: any refusal rolls back
     * everything.
     *
     * @return the current ACTIVE roster after the operation — the
     * `{members: […]}` answer body of №31.
     */
    @Transactional
    fun addMembers(
        callerId: UUID,
        chatId: UUID,
        userIds: List<UUID>,
    ): List<GroupMember> {
        val membership =
            membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.ADD_MEMBERS)
        val group = resolveGroupForUpdate(chatId)
        rolePolicy.requireCanAddMembers(membership)
        requireNotSelf(callerId, userIds, USER_IDS_FIELD)
        requireAllContactsOf(callerId, userIds, USER_IDS_FIELD)
        val activeIds = participants.activeMembers(group.id).mapTo(mutableSetOf()) { it.userId }
        val incoming = userIds.filter { it !in activeIds }
        requireWithinCapacity(activeCount = activeIds.size, joiningCount = incoming.size)
        val addedIds = incoming.mapNotNull { userId -> addOrReactivate(group.id, userId, callerId) }
        val roster = participants.activeMembers(group.id)
        val profiles = profilesOf(roster)
        if (addedIds.isNotEmpty()) {
            val addressees = roster.map(ChatParticipant::userId)
            addedIds.forEach { addedId ->
                publishMemberAddedAfterCommit(group.id, addedId, profiles, callerId, addressees)
            }
            metrics.recordGroupSize(roster.size)
        }
        return roster.map { row -> memberView(row, profiles) }
    }

    /**
     * The №28 group resolve of the gated read: the lock-free
     * [GroupRepository.find] answers the same `kind='group'` predicate
     * as [resolveGroupForUpdate] — a `null` (an unknown id AND a direct
     * dialog) renders as the SAME uniform [GroupNotFoundException] the
     * gate throws one step earlier, so the read path stays
     * 404-indistinguishable end to end (api-contract.md 006 №28,
     * FR-008/FR-009).
     */
    private fun resolveGroup(chatId: UUID): GroupChat =
        groupRepository.find(chatId)
            ?: throw GroupNotFoundException()

    /**
     * The №31 group resolve INSIDE the ambient transaction: the
     * `SELECT … FOR UPDATE` of [GroupRepository.findForUpdate] serializes
     * concurrent additions on the row lock, and its `null` (an unknown id
     * AND a direct dialog — the `kind='group'` predicate) renders as the
     * SAME uniform [GroupNotFoundException] the gate throws one step
     * earlier: the chatId of his own direct dialog is not a group
     * resource, and its existence is never disclosed (api-contract.md 006
     * №28/№31, FR-008/FR-009).
     */
    private fun resolveGroupForUpdate(chatId: UUID): GroupChat =
        groupRepository.findForUpdate(chatId)
            ?: throw GroupNotFoundException()

    /**
     * №27/№31 (api-contract.md 006): the actor himself inside the batch —
     * `400 self_forbidden` on the operation's field.
     */
    private fun requireNotSelf(
        actorId: UUID,
        candidateIds: List<UUID>,
        field: String,
    ) {
        if (actorId in candidateIds) throw SelfForbiddenException(field)
    }

    /**
     * №27/№31 (FR-002, research.md 006 §5): every candidate must sit in
     * the ACTOR's contact list — one read of the 004 contact port, then
     * the membership fold; ANY outsider refuses the WHOLE batch with
     * `422 not_in_contacts` before a single row is written.
     */
    private fun requireAllContactsOf(
        actorId: UUID,
        candidateIds: List<UUID>,
        field: String,
    ) {
        if (candidateIds.isEmpty()) return
        val contactIds =
            contacts.listByOwner(actorId, ContactSort.LOGIN).mapTo(mutableSetOf()) { it.contact.contactUserId }
        if (candidateIds.any { it !in contactIds }) throw NotInContactsException(field)
    }

    /**
     * №27/№31 (FR-002, api-contract.md 006 §2): the capacity bound —
     * `active + joining > groups.max-members` refuses the whole batch
     * with `409 group_full`; [joiningCount] counts only the candidates
     * genuinely joining (the idempotent re-adds answer `200` even at a
     * full roster). №27 joins the creator himself with the initial
     * roster.
     */
    private fun requireWithinCapacity(
        activeCount: Int,
        joiningCount: Int,
    ) {
        if (activeCount + joiningCount > properties.maxMembers) throw GroupFullException()
    }

    /**
     * One №31 candidate inside the locked section (data-model 006
     * §Сущность 2 «№31 добавление»): no row — the FIRST add anchoring
     * both watermarks at the chat head (FR-013); a REMOVED row — the
     * reactivation preserving the watermarks and resetting the role to
     * `member` (FR-002). Every ACTUAL add journals `member_added` in the
     * ambient transaction; a zero-rowcount reactivation returns `null`
     * and is skipped idempotently (no journal fact, no frame).
     */
    private fun addOrReactivate(
        chatId: UUID,
        userId: UUID,
        actorId: UUID,
    ): UUID? {
        val joined =
            if (participants.find(chatId, userId) == null) {
                participants.addMember(chatId, userId, MemberRole.MEMBER)
            } else {
                participants.reactivate(chatId, userId) ?: return null
            }
        adminLog.append(GroupAdminLogEntry(chatId, actorId, userId, GroupAdminAction.MEMBER_ADDED))
        return joined.userId
    }

    /**
     * realtime-group-events.md 006 §3.2 (T021): the `group.member.added`
     * frame of one added member — the contract `PublicUser` payload rides
     * the frame so clients render the roster entry without a follow-up
     * №28 round trip. The frame is addressed to [addressees] — the
     * post-operation ACTIVE roster snapshot taken inside the transaction
     * — and is handed to the publisher strictly AFTER the commit.
     */
    @Suppress("TooGenericExceptionCaught") // at-most-once isolation, the same defense-in-depth as the 004 publish paths
    private fun publishMemberAddedAfterCommit(
        groupId: UUID,
        addedUserId: UUID,
        profiles: Map<UUID, UserProfile>,
        actorId: UUID,
        addressees: List<UUID>,
    ) {
        val profile = profiles.getValue(addedUserId)
        val event =
            GroupMemberAddedEvent(
                groupId = groupId,
                user =
                    GroupMemberUser(
                        id = profile.id,
                        username = profile.username,
                        email = profile.email,
                        status = profile.status,
                        createdAt = profile.createdAt,
                    ),
                actorId = actorId,
            )
        afterCommit {
            try {
                realtime.fanoutGroupEvent(addressees, event)
            } catch (failure: Exception) {
                log.warn(
                    "realtime fan-out of group.member.added (group <{}>, user <{}>) to <{}> addressees failed; " +
                        "the roster is durable and clients converge on refetch (FR-009): {}",
                    groupId,
                    addedUserId,
                    addressees.size,
                    failure.message,
                )
            }
        }
    }

    /**
     * The post-commit ordering rule of the group frames (the port
     * contract, data-model 006 §5): the publication runs in
     * `afterCommit` of the ambient scenario transaction — a rollback
     * never announces anything. Without an active synchronization (a
     * direct unit call — never the case through the @Transactional
     * scenarios) the publication runs inline.
     */
    private fun afterCommit(publish: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = publish()
            },
        )
    }

    /**
     * One [GroupMember] of the roster projection: the public profile, the
     * contract role label, the FIRST-add moment (FR-002).
     */
    private fun memberView(
        row: ChatParticipant,
        profiles: Map<UUID, UserProfile>,
    ): GroupMember {
        val role =
            requireNotNull(row.role) {
                "a group roster row always carries a role (V14 ck_chat_participants_role)"
            }
        val profile = profiles.getValue(row.userId)
        return GroupMember(
            user =
                PublicUserView(
                    id = profile.id,
                    username = profile.username,
                    email = profile.email,
                    status = profile.status,
                    createdAt = profile.createdAt,
                ),
            role = memberRoleLabel(role),
            joinedAt = row.createdAt,
        )
    }

    /**
     * The public profiles of the roster: the reused `PublicUser`
     * projection of the 004 lookup port, one read per member (≤ 200 by
     * FR-002). A roster member always resolves — the `chat_participants`
     * FK pins him in `users`; a miss is a broken invariant, never a
     * client answer.
     */
    private fun profilesOf(roster: List<ChatParticipant>): Map<UUID, UserProfile> =
        roster.associate { row ->
            val profile =
                users.findById(row.userId)
                    ?: error("group roster member <${row.userId}> does not resolve in users (the FK invariant)")
            row.userId to profile
        }

    private companion object {
        /** api-contract.md 006 №27: the `errors` key of the initial-roster refusals. */
        const val MEMBER_USER_IDS_FIELD = "memberUserIds"

        /** api-contract.md 006 №31: the `errors` key of the add-members refusals. */
        const val USER_IDS_FIELD = "userIds"
    }
}
