package webchat.backend.groups.domain.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.GroupDeletedEvent
import webchat.backend.chats.domain.port.GroupEvent
import webchat.backend.chats.domain.port.GroupMemberAddedEvent
import webchat.backend.chats.domain.port.GroupMemberRemovedEvent
import webchat.backend.chats.domain.port.GroupMemberUser
import webchat.backend.chats.domain.port.GroupRemovalReason
import webchat.backend.chats.domain.port.GroupRoleChangedEvent
import webchat.backend.chats.domain.port.GroupUpdatedEvent
import webchat.backend.chats.domain.port.GroupYouRemovedEvent
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.config.GroupsProperties
import webchat.backend.contacts.domain.model.ContactSort
import webchat.backend.contacts.domain.model.UserProfile
import webchat.backend.contacts.domain.port.ContactRepository
import webchat.backend.contacts.domain.port.UserLookupPort
import webchat.backend.groups.GroupMetrics
import webchat.backend.groups.api.EmptyPatchException
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
 * T043 later grew the №32/№34/№35 role scenarios of User Story 3
 * (FR-003/FR-004/FR-010) on the same collaborators — [setRole],
 * [transferOwnership] and [kick] below.
 *
 * T051 grew the №29 metadata scenario of User Story 4 (FR-007) —
 * [update] below: the owner/admin patch of title/description with the
 * atomic last-confirmed UPDATE, the FR-017 journal pair and the
 * post-commit `group.updated` broadcast.
 *
 * T063 grew the №33 voluntary exit of User Story 6 (FR-005) — [leave]
 * below: the owner `403 owner_must_transfer` edge, the rowcount-race
 * convergence of the single removal UPDATE, the `member_left` journal
 * fact and the post-commit `group.you_removed {reason:'left'}` +
 * `group.member.removed {actorId:null}` pair.
 *
 * T064 grew the №30 hard delete of User Story 6 (FR-006) — [delete]
 * below: the owner-only gate, the pre-delete ACTIVE-roster snapshot,
 * the single CASCADE delete, the `group_deleted` journal fact WITHOUT
 * content and the post-commit `group.deleted` broadcast to every
 * former member.
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
     * №29 `PATCH /api/v1/groups/{chatId}` (api-contract.md 006 №29,
     * FR-007, T051): the metadata patch. The refusal order is the
     * contract's own, every leg BEFORE any write: the patch shape (`400
     * empty_patch` on `errors.body` — a body with NEITHER field has
     * nothing to apply; the same before-the-gate placement the №34
     * `invalid_role` leg holds), the membership gate (`404
     * group_not_found`, counted — a stranger, a removed former member,
     * an unknown id AND the chatId of a direct dialog are the SAME
     * answer), the `FOR UPDATE` group resolve, then the metadata role
     * gate — owner and admin manage the metadata, a plain member is
     * `403 forbidden_role` (FR-004).
     *
     * The FR-001 bounds are carried by the [GroupTitle]/
     * [GroupDescription] value objects themselves (the api layer builds
     * them per the T016 DTO, the same split as №27), so an invalid field
     * dies as its typed `400 invalid_title`/`invalid_description`
     * BEFORE the service — a MIXED patch (one valid, one invalid field)
     * is refused ATOMICALLY: nothing lands, nothing journals. The patch
     * MERGES with the locked row's metadata — an absent field keeps the
     * current value, a present one replaces it, and an EXPLICITLY EMPTY
     * description clears to «no description» (the №27 convention: an
     * empty string never persists). The single conditional
     * UPDATE…RETURNING of [GroupRepository.updateMetadata] then lands
     * the WHOLE merged pair atomically — «последняя подтверждённая
     * операция» (data-model 006 §Сущность 1): two admins patching
     * SIMULTANEOUSLY serialize on the row lock, BOTH confirm with 200
     * and the stored (title, description) pair is EXACTLY the last
     * confirmed whole patch, never a mix of the two (spec.md Edge
     * «одновременное переименование»).
     *
     * Durable effects in ONE transaction: one `title_changed` fact per
     * patched title and one `description_changed` fact per patched
     * description (`targetUserId = null` — the metadata facts of
     * FR-017). Post-commit, strictly after the commit, EVERY active
     * participant (the actor included) receives the `group.updated`
     * frame of realtime-group-events.md 006 §3.1 carrying the merged
     * payload verbatim.
     *
     * @param title the patched title, or `null` to keep the current one;
     * @param description the patched description, or `null` to keep the
     * current one (an empty value CLEARS it — FR-001's optional field
     * never persists as an empty string).
     * @return the caller's [GroupView] over the updated metadata — the
     * `200` answer body of №29.
     */
    @Suppress("ThrowsCount") // the throw legs ARE the №29 refusal ladder of api-contract.md 006 §2
    @Transactional
    fun update(
        callerId: UUID,
        chatId: UUID,
        title: GroupTitle?,
        description: GroupDescription?,
    ): GroupView {
        if (title == null && description == null) throw EmptyPatchException()
        val membership =
            membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.UPDATE)
        val current = resolveGroupForUpdate(chatId)
        rolePolicy.requireCanUpdateMetadata(membership)
        val updated =
            groupRepository.updateMetadata(
                chatId,
                title = title ?: current.title,
                description = mergedDescription(description, current.description),
            ) ?: throw GroupNotFoundException()
        if (title != null) {
            adminLog.append(
                GroupAdminLogEntry(chatId, callerId, targetUserId = null, action = GroupAdminAction.TITLE_CHANGED),
            )
        }
        if (description != null) {
            adminLog.append(
                GroupAdminLogEntry(
                    chatId,
                    callerId,
                    targetUserId = null,
                    action = GroupAdminAction.DESCRIPTION_CHANGED,
                ),
            )
        }
        val roster = participants.activeMembers(chatId)
        val profiles = profilesOf(roster)
        fanoutGroupEventAfterCommit(
            chatId,
            roster.map(ChatParticipant::userId),
            GroupUpdatedEvent(
                groupId = chatId,
                title = updated.title.value,
                description = updated.description?.value,
                actorId = callerId,
            ),
        )
        val myRole =
            requireNotNull(membership.role) {
                "the gate row of an active group membership must carry a role (V14 ck_chat_participants_role)"
            }
        return GroupView(
            chatId = updated.id,
            title = updated.title.value,
            description = updated.description?.value,
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
        val activeIds = participants.activeMembers(group.id).map { it.userId }.toSet()
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
     * №34 `PUT /api/v1/groups/{chatId}/members/{userId}/role`
     * (api-contract.md 006 №34, FR-003, T043): the owner ALONE grants
     * and revokes the admin flag. The refusal order is the contract's
     * own: the membership gate FIRST (a stranger, a removed former
     * member, an unknown id AND the chatId of a direct dialog are the
     * SAME counted `404 group_not_found` — the direct row passes the
     * gate and dies at the `kind='group'` resolve), then the self rule
     * (`400 self_forbidden` — the owner's own role moves only through
     * №35, decided BEFORE the role gate so it can never be masked by
     * `not_group_owner`), then [GroupRolePolicy.requireOwner] (`403
     * not_group_owner` for an admin/member caller), then the target
     * gate — an ACTIVE row is required (`409 target_not_member`) and
     * the conditional role UPDATE re-asserts it by rowcount (a kick
     * racing between the read and the write converges on the same 409).
     *
     * ONE transaction: the role UPDATE and the journal fact (`admin_granted`
     * / `admin_revoked`) commit or roll back together, and the
     * `group.role.changed` frame rides the POST-operation ACTIVE roster
     * snapshot — §3.4 broadcasts to ALL actives — strictly post-commit.
     * The `owner` label never reaches the UPDATE (the T016 DTO refuses
     * it; the guard here keeps the partial unique index inviolable from
     * any direct caller too).
     *
     * @return the updated [GroupMember] — the `200` answer body of №34.
     */
    @Suppress("ThrowsCount") // the throw legs ARE the №34 refusal ladder of api-contract.md 006 §2
    @Transactional
    fun setRole(
        callerId: UUID,
        chatId: UUID,
        targetUserId: UUID,
        role: MemberRole,
    ): GroupMember {
        if (role == MemberRole.OWNER) throw InvalidRoleException()
        val membership =
            membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.SET_ROLE)
        val group = resolveGroup(chatId)
        if (targetUserId == callerId) throw SelfForbiddenException(USER_ID_FIELD)
        rolePolicy.requireOwner(membership)
        participants.findActive(chatId, targetUserId) ?: throw TargetNotMemberException()
        val updated =
            participants.updateRole(group.id, targetUserId, role) ?: throw TargetNotMemberException()
        adminLog.append(
            GroupAdminLogEntry(
                group.id,
                callerId,
                targetUserId,
                if (role == MemberRole.ADMIN) GroupAdminAction.ADMIN_GRANTED else GroupAdminAction.ADMIN_REVOKED,
            ),
        )
        val roster = participants.activeMembers(group.id)
        val profiles = profilesOf(roster)
        fanoutGroupEventAfterCommit(
            group.id,
            roster.map(ChatParticipant::userId),
            GroupRoleChangedEvent(group.id, targetUserId, role, callerId),
        )
        return memberView(updated, profiles)
    }

    /**
     * №35 `POST /api/v1/groups/{chatId}/owner` (api-contract.md 006 №35,
     * FR-003, T043): the ownership hand-over — ONE transaction of demote
     * THEN promote, the exact order the partial unique index
     * `ux_chat_participants_owner` dictates: the former owner demotes to
     * `admin` BEFORE the target promotes to `owner`, so the index never
     * observes two active owners — nor zero — at any statement boundary,
     * and a mid-flight failure rolls the pair back together.
     *
     * The refusal order mirrors №34: the gate (`404 group_not_found`,
     * counted), the `FOR UPDATE` group resolve (concurrent transfers of
     * one owner serialize on the `chats` row lock — the loser re-reads
     * his row as a non-owner and answers `403 not_group_owner` instead
     * of racing the index), the self rule (`400 self_forbidden` — an
     * owner transfer to himself would be a no-op that still wears the
     * contract edge code), [GroupRolePolicy.requireOwner], then the
     * target gate: an ACTIVE participant is required (`409
     * target_not_member`) and the promote UPDATE re-asserts it by
     * rowcount (the kick×transfer race converges on the same 409 with
     * the demote rolled back).
     *
     * Durable effects: the journal `ownership_transferred` fact in the
     * SAME transaction, and the `group.role.changed` PAIR of frames —
     * the new owner promoted, the former demoted (§3.4: both to every
     * ACTIVE participant, clients fold them idempotently) — strictly
     * post-commit.
     *
     * @return the caller's [GroupView] — HIS `myRole` is now `admin`,
     * the roster carries the single new owner.
     */
    @Suppress("ThrowsCount") // the throw legs ARE the №35 refusal ladder of api-contract.md 006 §2
    @Transactional
    fun transferOwnership(
        callerId: UUID,
        chatId: UUID,
        targetUserId: UUID,
    ): GroupView {
        val membership =
            membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.TRANSFER_OWNERSHIP)
        val group = resolveGroupForUpdate(chatId)
        if (targetUserId == callerId) throw SelfForbiddenException(USER_ID_FIELD)
        rolePolicy.requireOwner(membership)
        participants.findActive(chatId, targetUserId) ?: throw TargetNotMemberException()
        val demoted =
            requireNotNull(participants.updateRole(group.id, callerId, MemberRole.ADMIN)) {
                "the transferring owner <$callerId> of group <${group.id}> must hold an active row (the gate proved it)"
            }
        participants.updateRole(group.id, targetUserId, MemberRole.OWNER) ?: throw TargetNotMemberException()
        adminLog.append(GroupAdminLogEntry(group.id, callerId, targetUserId, GroupAdminAction.OWNERSHIP_TRANSFERRED))
        val roster = participants.activeMembers(group.id)
        val profiles = profilesOf(roster)
        val addressees = roster.map(ChatParticipant::userId)
        fanoutGroupEventAfterCommit(
            group.id,
            addressees,
            GroupRoleChangedEvent(group.id, targetUserId, MemberRole.OWNER, callerId),
        )
        fanoutGroupEventAfterCommit(
            group.id,
            addressees,
            GroupRoleChangedEvent(group.id, callerId, MemberRole.ADMIN, callerId),
        )
        return GroupView(
            chatId = group.id,
            title = group.title.value,
            description = group.description?.value,
            myRole = memberRoleLabel(demoted.role!!),
            members = roster.map { row -> memberView(row, profiles) },
        )
    }

    /**
     * №32 `DELETE /api/v1/groups/{chatId}/members/{userId}`
     * (api-contract.md 006 №32, FR-004/FR-010, T043): the kick. The
     * refusal order is the contract's own, each step BEFORE any write:
     * the membership gate (`404 group_not_found`, counted — a direct
     * chatId dies at the `kind='group'` resolve the same way), the SELF
     * rule (`400 self_forbidden` decided BEFORE the hierarchy — even a
     * plain member kicking HIMSELF sees self_forbidden, never
     * forbidden_role; leaving is №33 only), the target gate (an ACTIVE
     * row required — a stranger or an already-removed target is `409
     * target_not_member`, the repeated-№32 convergence of
     * api-contract.md 006 §2), and [GroupRolePolicy.requireCanKick]
     * against the caller-resolved ACTIVE target row (a plain member
     * removes no one — `403 forbidden_role`; an admin removes ONLY
     * members — `403 role_hierarchy_violation`).
     *
     * The removal itself is the single conditional UPDATE of
     * [ParticipantRepository.removeMember] resolved by rowcount: a
     * concurrent №33 leave (or a winning twin №32) already resolved the
     * race, and the loser re-converges on `409 target_not_member` — no
     * duplicates, no partial effects (edge «исключение×выход»). The
     * watermarks survive in the row for the FR-002 re-add and the
     * kicked member's messages keep their attribution (`messages` rows
     * are never touched).
     *
     * Durable effects in ONE transaction: the journal `member_removed`
     * fact and the shrunken-roster size sample. Post-commit, strictly
     * after the commit: the FINAL `group.you_removed {reason:'kicked'}`
     * to the kicked user ALONE (§3.6 — after this frame no group event
     * reaches his channel again) and `group.member.removed` with the
     * actor to every REMAINING active participant (§3.3).
     */
    @Suppress("ThrowsCount") // the throw legs ARE the №32 refusal ladder of api-contract.md 006 §2
    @Transactional
    fun kick(
        callerId: UUID,
        chatId: UUID,
        targetUserId: UUID,
    ) {
        val membership =
            membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.REMOVE_MEMBER)
        val group = resolveGroup(chatId)
        if (targetUserId == callerId) throw SelfForbiddenException(USER_ID_FIELD)
        val target = participants.findActive(chatId, targetUserId) ?: throw TargetNotMemberException()
        rolePolicy.requireCanKick(membership, target)
        if (!participants.removeMember(group.id, targetUserId)) throw TargetNotMemberException()
        adminLog.append(GroupAdminLogEntry(group.id, callerId, targetUserId, GroupAdminAction.MEMBER_REMOVED))
        val survivors = participants.activeMembers(group.id)
        metrics.recordGroupSize(survivors.size)
        fanoutGroupEventAfterCommit(
            group.id,
            listOf(targetUserId),
            GroupYouRemovedEvent(group.id, GroupRemovalReason.KICKED),
        )
        fanoutGroupEventAfterCommit(
            group.id,
            survivors.map(ChatParticipant::userId),
            GroupMemberRemovedEvent(group.id, targetUserId, callerId),
        )
    }

    /**
     * №33 `DELETE /api/v1/groups/{chatId}/membership` (api-contract.md 006
     * №33, FR-005, T063): the VOLUNTARY exit — the mirror of [kick] with
     * the caller as his own target. The refusal order is the contract's
     * own, each step BEFORE any write: the membership gate (`404
     * group_not_found`, counted — a stranger, an already-removed former
     * member, an unknown chat id AND the chatId of a direct dialog are
     * the SAME answer; the repeated №33 of api-contract.md 006 §2 dies
     * HERE — the membership row is already `removed`), then the
     * lock-free `kind='group'` resolve, then the FR-005 owner edge — an
     * owner leaving would orphan the group, so he is refused `403
     * owner_must_transfer` UNTIL a №35 transfer (or the №30 delete):
     * after the hand-over the FORMER owner is a plain admin and leaves
     * штатно.
     *
     * The exit itself is the single conditional UPDATE of
     * [ParticipantRepository.removeMember] (`state='removed'`,
     * `role='member'`) resolved by rowcount — a concurrent №32 kick of
     * the same user (or a twin №33) already resolved the race, and the
     * loser re-converges on the SAME uniform 404 (api-contract.md 006 §2
     * edge «исключение×выход» — №32 sees `409 target_not_member`, №33
     * sees the 404 of a membership that is no more). The watermarks
     * survive in the row for the FR-002 re-add and the leaver's messages
     * keep their attribution (`messages` rows are never touched).
     *
     * Durable effects in ONE transaction: the journal `member_left` fact
     * (actor = target = the leaver — nobody removed him, data-model 006
     * §Сущность 4) and the shrunken-roster size sample. Post-commit,
     * strictly after the commit: the FINAL `group.you_removed
     * {reason:'left'}` to the leaver ALONE (§3.6 — after this frame no
     * group event reaches his channel again) and `group.member.removed`
     * with `actorId = null` — the voluntary exit carries no actor
     * (§3.3) — to every REMAINING active participant.
     */
    @Suppress("ThrowsCount") // the throw legs ARE the №33 refusal ladder of api-contract.md 006 §2
    @Transactional
    fun leave(
        callerId: UUID,
        chatId: UUID,
    ) {
        val membership =
            membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.LEAVE)
        val group = resolveGroup(chatId)
        if (membership.role == MemberRole.OWNER) throw OwnerMustTransferException()
        if (!participants.removeMember(group.id, callerId)) throw GroupNotFoundException()
        adminLog.append(GroupAdminLogEntry(group.id, callerId, callerId, GroupAdminAction.MEMBER_LEFT))
        val survivors = participants.activeMembers(group.id)
        metrics.recordGroupSize(survivors.size)
        fanoutGroupEventAfterCommit(
            group.id,
            listOf(callerId),
            GroupYouRemovedEvent(group.id, GroupRemovalReason.LEFT),
        )
        fanoutGroupEventAfterCommit(
            group.id,
            survivors.map(ChatParticipant::userId),
            GroupMemberRemovedEvent(group.id, callerId, actorId = null),
        )
    }

    /**
     * №30 `DELETE /api/v1/groups/{chatId}` (api-contract.md 006 №30,
     * FR-006, T064): the OWNER-ONLY hard delete — the only durable
     * removal of a group. The refusal order is the contract's own, each
     * step BEFORE any write: the membership gate (`404 group_not_found`,
     * counted — a stranger, a removed former member, an unknown chat id
     * AND the chatId of a direct dialog are the SAME answer; the
     * repeated №30 of api-contract.md 006 §2 dies HERE — the CASCADE of
     * the first delete took the caller's row away), then the `FOR
     * UPDATE` group resolve (the same `chats` row lock the №31/№35
     * scenarios serialize on — a racing add queues behind the delete
     * and re-reads a world without the group, so the snapshot below is
     * the EXACT set of rows the CASCADE erases), then
     * [GroupRolePolicy.requireOwner] — an admin or a plain member is
     * `403 not_group_owner` (FR-003).
     *
     * Inside ONE transaction, in this exact order: the ACTIVE-roster
     * snapshot is taken FIRST (the `group.deleted` addressees of
     * realtime-group-events.md 006 §3.5 — every former member INCLUDING
     * the deleting owner), then the single `DELETE FROM chats WHERE
     * kind='group'` lands and `chat_participants` and `messages`
     * CASCADE away on the V10 FKs (FR-006: the history, the
     * memberships, the watermarks and the counters are all erased
     * TOGETHER — a partial removal never survives), and the journal
     * fact is appended AFTER the delete in the SAME transaction —
     * `group_admin_log` deliberately carries NO FK on `chats` (V14,
     * data-model 006 §Сущность 4), so the append lands against the
     * already gone group id and SURVIVES as `group_deleted` WITHOUT
     * CONTENT (`targetUserId = null` — the FACT of the deletion only,
     * never the deleted roster or metadata, clarify 2026-09-25,
     * FR-017). A zero rowcount renders as the same uniform 404 (the
     * race with a twin №30 already resolved by the CASCADE).
     *
     * Post-commit, strictly after the commit: ONE `group.deleted` frame
     * with `actorId` = the deleting owner to EVERY member of the
     * pre-delete snapshot (§3.5 — recipients drop the group from
     * «Чаты», close its window and ignore every later frame of it).
     * No `webchat_group_size` sample rides this path: the histogram of
     * research.md 006 §8 samples the add/remove transitions of a
     * LIVING group's roster — the №30 delete ends the group itself,
     * its roster does not «shrink» to zero one member at a time.
     */
    @Suppress("ThrowsCount") // the throw legs ARE the №30 refusal ladder of api-contract.md 006 §2
    @Transactional
    fun delete(
        callerId: UUID,
        chatId: UUID,
    ) {
        val membership =
            membershipGate.requireActiveMembership(chatId, callerId, GroupMetrics.AuthzOperation.DELETE)
        val group = resolveGroupForUpdate(chatId)
        rolePolicy.requireOwner(membership)
        val formers = participants.activeMembers(group.id)
        if (!groupRepository.deleteHard(group.id)) throw GroupNotFoundException()
        adminLog.append(
            GroupAdminLogEntry(group.id, callerId, targetUserId = null, action = GroupAdminAction.GROUP_DELETED),
        )
        fanoutGroupEventAfterCommit(
            group.id,
            formers.map(ChatParticipant::userId),
            GroupDeletedEvent(group.id, callerId),
        )
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
            contacts.listByOwner(actorId, ContactSort.LOGIN).map { it.contact.contactUserId }.toSet()
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
     * The №29 description merge against the locked row: an ABSENT field
     * ([patch] == null) keeps the current value; a present one applies,
     * with the №27 empty-value convention — an EXPLICITLY EMPTY patch
     * description CLEARS the stored one to «no description» (the
     * optional FR-001 field never persists a meaningless empty string).
     */
    private fun mergedDescription(
        patch: GroupDescription?,
        current: GroupDescription?,
    ): GroupDescription? =
        when {
            patch == null -> current
            patch.value.isEmpty() -> null
            else -> patch
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
     * The T043 frame leg (realtime-group-events.md 006 §3.3/§3.4/§3.6):
     * one group frame to its EXACT addressee list — the ACTIVE-roster
     * snapshot taken inside the scenario transaction (§3.4 every
     * active; §3.3 the REMAINING roster; §3.6 the removed user ONLY) —
     * handed to the publisher strictly AFTER the commit, the same
     * defence-in-depth isolation as the №27/№31 leg above: a lost or
     * failed at-most-once delivery never fails the already-durable
     * operation, clients converge on refetch (FR-009, constitution II).
     */
    @Suppress("TooGenericExceptionCaught") // at-most-once isolation, the same defence-in-depth as the 004 publish paths
    private fun fanoutGroupEventAfterCommit(
        groupId: UUID,
        addressees: List<UUID>,
        event: GroupEvent,
    ) {
        afterCommit {
            try {
                realtime.fanoutGroupEvent(addressees, event)
            } catch (failure: Exception) {
                log.warn(
                    "realtime fan-out of a group frame (group <{}>, addressees <{}>) failed; " +
                        "the roster is durable and clients converge on refetch (FR-009): {}",
                    groupId,
                    addressees.size,
                    failure.message,
                )
            }
        }
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

        /** api-contract.md 006 №32/№34/№35: the `errors` key of the single-target refusals. */
        const val USER_ID_FIELD = "userId"
    }
}
