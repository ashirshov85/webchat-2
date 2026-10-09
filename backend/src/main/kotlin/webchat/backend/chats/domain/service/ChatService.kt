package webchat.backend.chats.domain.service

import org.springframework.stereotype.Service
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatKind
import webchat.backend.chats.domain.model.ChatListEntry
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatListRepository
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.contacts.domain.port.BlockRepository
import webchat.backend.contacts.domain.port.ContactRepository
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.service.GroupMembershipGate
import webchat.backend.users.domain.port.ProfileStore
import java.util.UUID

/**
 * 422 (api-contract.md №11): a dialog of the caller with themselves is
 * refused — a strict pair "one on one" has two DISTINCT sides (FR-001/edge).
 * The contract code is carried for the problem+json rendering of the api
 * layer (T016).
 */
class SelfForbiddenException : RuntimeException("a dialog requires two distinct users (FR-001)") {
    val code: String = "self_forbidden"
}

/**
 * 404 (api-contract.md №11): the requested peer does not exist — refused
 * BEFORE any repository write.
 */
class PeerNotFoundException : RuntimeException("the requested peer user does not exist") {
    val code: String = "peer_not_found"
}

/**
 * 404 (api-contract.md №12–№17): the chat id resolves to nothing — the
 * uniform answer to strangers and participants alike, so existence is
 * never disclosed beyond membership.
 */
class ChatNotFoundException : RuntimeException("the requested chat does not exist") {
    val code: String = "chat_not_found"
}

/**
 * 403 (api-contract.md, membership): the chat exists but the caller is not
 * one of its two participants (FR-002) — the refusal on EVERY chats
 * resource, resolved after the 404 above.
 */
class NotParticipantException : RuntimeException("the caller is not a participant of this chat") {
    val code: String = "not_participant"
}

/**
 * The read watermark pair of a dialog projected for ONE participant
 * (T043, openapi 0.4.0 №11/№13): [myReadUpToSeq] is the caller's own
 * mark, [peerReadUpToSeq] the peer's mark the sender's ✓✓ renders from
 * (research.md 004 §5) — per-user state, never a chat-wide value.
 */
data class ReadWatermarks(
    val myReadUpToSeq: Long,
    val peerReadUpToSeq: Long,
)

/**
 * The GROUP projection of `ChatView` (T024, 006 №13, api-contract.md §3):
 * the caller's [myRole] and own [myReadUpToSeq] from his ACTIVE
 * membership row, the FR-012 ✓✓ bound [othersReadUpToSeq]
 * (`MAX(last_read_seq)` of the other active members — ✓✓ once any one
 * of them has read) and the ACTIVE
 * [memberCount] — the group-side counterpart of [ReadWatermarks].
 */
data class GroupChatProjection(
    val myRole: MemberRole,
    val myReadUpToSeq: Long,
    val othersReadUpToSeq: Long,
    val memberCount: Long,
)

/**
 * The 008a name pair of a DIRECT dialog peer projected for the caller
 * (T015, api-contract.md §2): the peer's optional profile
 * [displayName] (FR-001 — the `PublicUser.displayName` slot of the
 * №11/№13 peer fragment, NULL = «not set» → the client renders
 * `username`) and the caller's PERSONAL [alias] toward the peer (FR-003
 * — the separate additive `peerAlias` slot, NULL = «not set or the peer
 * is not a contact of the caller»). Strictly caller-scoped material:
 * the alias is the caller's own `user_contacts` row and never reaches
 * the peer or any third party (the leakage ban of FR-003).
 */
data class PeerNames(
    val displayName: String?,
    val alias: String?,
)

/**
 * Dialog lifecycle of User Story 1 (T013): the idempotent pair resolve and
 * the membership-gated read.
 *
 * Ensure (api-contract.md №11, FR-001/FR-018): `422 self_forbidden` for a
 * dialog with oneself and `404 peer_not_found` for an unknown peer are
 * decided HERE, before the repository is touched — the pair resolve itself
 * (`ON CONFLICT` + lazy participants + caller `hidden` reset, FR-021) is
 * one PG transaction owned by [ChatRepository.ensure].
 *
 * Get (api-contract.md №13, FR-002): every chats resource resolves through
 * the same gate — `404 chat_not_found` first, then `403 not_participant`
 * for a stranger (Carol), so the two are never distinguishable beyond
 * membership. List (№12) and the per-user delete (№14) join here in T055/T056.
 * T034 (006, api-contract.md §3) embeds the GROUP half of the gate here
 * as well: the №15–№17 messaging paths and EVERY №25 batch element
 * resolve a `kind='group'` row through the ACTIVE-membership resolution
 * of [GroupMembershipGate], keeping this 004 refusal order verbatim.
 *
 * List (№12, T055): [listChats] is a pure read — the WHOLE panel
 * (participants + peer + block mark + aggregates + sorting) is ONE
 * aggregate query owned by [ChatListRepository] (research.md 004 §8);
 * the service holds no list-side business rules to enforce, so it only
 * delegates — the method exists to keep the HTTP adapter thin like the
 * rest of the controller surface.
 *
 * The read fields of `ChatView` (T043): [readWatermarks] projects the two
 * per-user marks of the resolved dialog for №11/№13 — the FR-010 watermark
 * is advanced by `ReadService` (№17) and only READ here.
 *
 * The FR-020 block projection of `ChatView`/`ChatListItem` (T054):
 * [blockedByMe] is a point lookup `exists(me, peer)` on the block pair —
 * the ONLY direction ever exposed; the inverse «who blocked me» is
 * deliberately not derivable from this service (research.md 004 §6).
 *
 * The 008a name pair of the DIRECT peer (T015, api-contract.md §2):
 * [peerNames] joins the peer's optional profile displayName (the shared
 * `PublicUser` read of [ProfileStore]) with the caller's PERSONAL alias
 * ([ContactRepository.aliasesOf] — one SELECT) for the
 * `peer.displayName`/`peerAlias` slots of №11/№13, while [peerAliases]
 * serves the whole №12 panel in ONE batched read; the №12 displayName
 * itself rides the single aggregate query of [ChatListRepository]
 * (`ChatPeerSnapshot.displayName`).
 */
@Service
class ChatService(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val chatListRepository: ChatListRepository,
    private val participantRepository: ParticipantRepository,
    private val blockRepository: BlockRepository,
    private val groupMembershipGate: GroupMembershipGate,
    private val profileStore: ProfileStore,
    private val contactRepository: ContactRepository,
) {
    fun ensure(
        callerId: UUID,
        peerId: UUID,
    ): ChatEnsureResult {
        if (callerId == peerId) throw SelfForbiddenException()
        if (userRepository.findById(peerId) == null) throw PeerNotFoundException()
        return chatRepository.ensure(callerId, peerId)
    }

    fun get(
        chatId: UUID,
        callerId: UUID,
    ): Chat {
        val chat = chatRepository.findById(chatId) ?: throw ChatNotFoundException()
        val member =
            when (chat.kind) {
                // FR-002 (004): the pair predicate of a DIRECT dialog.
                ChatKind.DIRECT -> chat.involves(callerId)
                // FR-008 (006, T034): a group resolves through the ACTIVE
                // membership row LENT by the T015 gate — a stranger, a
                // REMOVED former member and (with the 404 above) an
                // unknown chat id stay inside the SAME 004 semantics of
                // №15–№17/№25 (api-contract.md 006 §3: «не-участнику 404
                // chat_not_found/403 not_participant — семантика 004»),
                // unlike the counted uniform group 404 of №28+; the №25
                // batch walks EVERY element through this same `get`
                // (DeliveryAckService, all-or-refusal).
                ChatKind.GROUP -> groupMembershipGate.findActiveMembership(chatId, callerId) != null
            }
        if (!member) throw NotParticipantException()
        return chat
    }

    /**
     * №14 (T056, FR-021): the PER-USER deletion — resolves through the same
     * `404 chat_not_found → 403 not_participant` gate as every chats
     * resource, then delegates to the ATOMIC single-row UPDATE of
     * [ParticipantRepository.deleteUpTo]: `deleted_up_to_seq = chats.last_seq`
     * and `hidden = true` for the CALLER only, in one statement — the peer's
     * row is never touched. Idempotent by construction: a repeated DELETE
     * re-applies the same watermark (the `last_seq` snapshot has not moved)
     * and maps to the same `204`.
     */
    fun delete(
        chatId: UUID,
        callerId: UUID,
    ) {
        val chat = chatRepository.findById(chatId) ?: throw ChatNotFoundException()
        if (!chat.involves(callerId)) throw NotParticipantException()
        participantRepository.deleteUpTo(chat.id, callerId, chat.lastSeq)
    }

    /**
     * №12 (T055; unified by 006 T023, FR-014): the caller's UNIFIED chat
     * list — direct dialogs AND groups in one panel — the one aggregate
     * read of [ChatListRepository]; the repository owns the sorting (last
     * visible message `createdAt` DESC NULLS LAST, `chat_id` tie-break —
     * one order over both kinds), the §2 exclusion of fully deleted
     * dialogs, the 006 active-membership filter (a group stays in the
     * list ONLY while the caller holds an ACTIVE `chat_participants`
     * row, FR-008) and the aggregates (the group `memberCount` included),
     * so this is a straight delegation with nothing to veto.
     *
     * T031 (005, FR-007): the `unreadCount` aggregate is the
     * server-authoritative delivery-bounded formula of data-model 005
     * сущность 3 — `GREATEST(last_read, deleted) < seq ≤
     * LEAST(chats.last_seq, delivered_up_to_seq)` of incoming messages —
     * the SAME formula [ParticipantRepository.countUnread] embodies for
     * the №26 `SyncChatDelta.unreadCount` (one calculator, no drift): the
     * badge grows ONLY by the delivery ack №25, on both the realtime and
     * the catch-up paths alike; below the truncation point messages are
     * inaccessible, not unread (US1-5). The formula serves groups
     * unchanged — a group is a `chats` row with shared history (plan.md
     * 006), and the first-add watermark initialization (FR-013) starts a
     * new member's badge at 0.
     */
    fun listChats(callerId: UUID): List<ChatListEntry> = chatListRepository.listForUser(callerId)

    /**
     * T043: `myReadUpToSeq`/`peerReadUpToSeq` of `ChatView` (№11/№13) —
     * both [webchat.backend.chats.domain.model.ChatParticipant] rows in
     * one read; a missing row reads as the contract default 0 («0 — ничего
     * не прочитано»), matching the `minimum: 0` of openapi 0.4.0. Monotone
     * by construction: the values mirror the GREATEST-watermark rows and
     * are never derived.
     */
    fun readWatermarks(
        chat: Chat,
        callerId: UUID,
    ): ReadWatermarks {
        val peerId =
            chat.peerOf(callerId)
                ?: error("chat ${chat.id} does not involve the authenticated caller")
        val marks = participantRepository.findForChat(chat.id).associateBy { it.userId }
        return ReadWatermarks(
            myReadUpToSeq = marks[callerId]?.lastReadSeq ?: DEFAULT_READ_UP_TO_SEQ,
            peerReadUpToSeq = marks[peerId]?.lastReadSeq ?: DEFAULT_READ_UP_TO_SEQ,
        )
    }

    /**
     * T054 (FR-020, research.md 004 §6): the `blockedByMe` projection of
     * `ChatView` (№11/№13) and later `ChatListItem` (№12, T055) — ONE
     * point lookup `exists(caller, peer)` on the `(blocker_id, blocked_id)`
     * PK pair. This is the ONLY block direction the API ever answers: the
     * dialog stays fully visible to the blocked user without any mark —
     * he learns about the block ONLY from the `403 you_are_blocked` of his
     * own send (the leakage ban of FR-020).
     */
    fun blockedByMe(
        chat: Chat,
        callerId: UUID,
    ): Boolean {
        val peerId =
            chat.peerOf(callerId)
                ?: error("chat ${chat.id} does not involve the authenticated caller")
        return blockRepository.exists(callerId, peerId)
    }

    /**
     * T015 (008a, api-contract.md §2, FR-001/FR-003): the name pair of
     * the DIRECT dialog peer — the `peer.displayName`/`peerAlias` slots
     * of the №11/№13 bodies. The displayName rides the SHARED
     * `PublicUser` point read [ProfileStore.findByUserId] (the same
     * projector that serves №10 — one source of the name, no drift); the
     * alias is ONE [ContactRepository.aliasesOf] lookup over the caller's
     * own `(owner, peer)` row, holding only non-null values (an absent
     * key = «no alias» — the neutral fallback of the display chain
     * `alias → displayName → username`). Strictly caller-scoped: the
     * answer never leaves the caller's own surfaces (FR-003).
     */
    fun peerNames(
        chat: Chat,
        callerId: UUID,
    ): PeerNames {
        require(chat.kind == ChatKind.DIRECT) { "chat ${chat.id} is not a direct dialog" }
        val peerId =
            chat.peerOf(callerId)
                ?: error("chat ${chat.id} does not involve the authenticated caller")
        val displayName = profileStore.findByUserId(peerId)?.displayName?.value
        val alias = contactRepository.aliasesOf(callerId, listOf(peerId))[peerId]
        return PeerNames(displayName = displayName, alias = alias)
    }

    /**
     * T015 (008a, FR-003): the caller's personal aliases toward [userIds]
     * — the batched `peerAlias` join of the №12 panel: ONE
     * [ContactRepository.aliasesOf] SELECT over the caller's
     * `user_contacts` rows serves every DIRECT row of the list (an empty
     * collection costs no database round-trip; the map holds ONLY
     * non-null aliases — a missing key renders the slot ABSENT). The
     * same strictly-personal scope as [peerNames]: never projected into
     * anyone else's surfaces.
     */
    fun peerAliases(
        callerId: UUID,
        userIds: Collection<UUID>,
    ): Map<UUID, String> = contactRepository.aliasesOf(callerId, userIds)

    /**
     * T024 (006, api-contract.md §3 №13): the GROUP projection of
     * `ChatView` — the caller's own role and read watermark from his
     * ACTIVE membership row (the one [get] has just proven), the ✓✓ rule
     * of FR-012 as [GroupChatProjection.othersReadUpToSeq] (the
     * `maxOtherReadUpToSeq` MAX-fold over the other active members — 0 in
     * a group of one) and [GroupChatProjection.memberCount] as the ACTIVE
     * roster size (1–200, the `ix_chat_participants_chat_active` scan of
     * [ParticipantRepository.activeMembers] — no `FOR UPDATE` lock of a
     * plain read). A pure read: nothing here mutates dialog state.
     */
    fun groupProjection(
        chat: Chat,
        callerId: UUID,
    ): GroupChatProjection {
        require(chat.kind == ChatKind.GROUP) { "chat ${chat.id} is not a group" }
        val membership =
            participantRepository.findActive(chat.id, callerId)
                ?: error("chat ${chat.id} does not carry an active membership of the authenticated caller")
        val myRole =
            requireNotNull(membership.role) {
                "an active group membership row must carry a role (V14 ck_chat_participants_role)"
            }
        return GroupChatProjection(
            myRole = myRole,
            myReadUpToSeq = membership.lastReadSeq,
            othersReadUpToSeq = participantRepository.maxOtherReadUpToSeq(chat.id, callerId),
            memberCount = participantRepository.activeMembers(chat.id).size.toLong(),
        )
    }

    private companion object {
        /** Openapi 0.4.0 `peerReadUpToSeq`/`myReadUpToSeq`: 0 — nothing read yet. */
        const val DEFAULT_READ_UP_TO_SEQ = 0L
    }
}
