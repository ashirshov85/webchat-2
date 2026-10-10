package webchat.backend.chats.domain.port

import webchat.backend.chats.domain.model.Message
import webchat.backend.groups.domain.model.MemberRole
import java.time.Instant
import java.util.UUID

/**
 * `message.created` frame payload (realtime-channel.md §3.1;
 * `components.schemas.MessageCreatedEvent`): the full contract `Message` —
 * the server has committed the record BEFORE publishing (FR-005/FR-007),
 * so receiving devices render it already «доставлено». Clients deduplicate
 * by `message.id` (at-most-once channel + exactly-once storage, US2-6).
 */
data class MessageCreatedEvent(
    val chatId: UUID,
    val message: Message,
)

/**
 * `chat.read` frame payload (realtime-channel.md §3.2;
 * `components.schemas.ChatReadEvent`): the peer advanced their read
 * watermark — senders mark their outgoing `seq <= readUpToSeq` with ✓✓.
 * Published only on an actual advancement (the GREATEST-update rowcount
 * gate, US4-5); repeated/smaller values never publish.
 */
data class ChatReadEvent(
    val chatId: UUID,
    val readUpToSeq: Long,
    val byUserId: UUID,
)

/**
 * The added member inside a [GroupMemberAddedEvent] — the contract
 * `PublicUser` projection (realtime-group-events.md 006 §3.2): the
 * client renders the roster entry without a follow-up №28 round trip.
 * A pure passthrough of the users projection — the port never
 * interprets [status] on its own.
 */
data class GroupMemberUser(
    val id: UUID,
    val username: String,
    val email: String,
    val status: String,
    val createdAt: Instant,
)

/**
 * The `group.you_removed` reason (realtime-group-events.md 006 §3.6):
 * [KICKED] — excluded via №32 by the owner/an admin; [LEFT] — the
 * voluntary №33 exit. Serializes as the lower-case contract enum
 * `kicked`/`left`, never the Kotlin name.
 */
enum class GroupRemovalReason {
    KICKED,
    LEFT,
}

/**
 * Marker of the six №18 group frames (realtime-group-events.md 006 §3;
 * T009): a sealed hierarchy keeps the
 * [RealtimeEventPublisher.fanoutGroupEvent] leg exhaustive over the
 * contract set — a new frame lands as its payload type, and every
 * adapter must map it or fail to compile. `groupId` of every payload
 * IS the chatId of the `chats` row — the single key of №12–№17/№26,
 * №27–№35 and the dialog list (§1).
 */
sealed interface GroupEvent

/**
 * `group.updated` (realtime-group-events.md 006 §3.1, FR-007): group
 * metadata changed — the recipients retitle the dialog list and header
 * in place; [description] is nullable and rides the frame as `null`
 * when absent, never dropped.
 */
data class GroupUpdatedEvent(
    val groupId: UUID,
    val title: String,
    val description: String?,
    val actorId: UUID,
) : GroupEvent

/**
 * `group.member.added` (realtime-group-events.md 006 §3.2, FR-002):
 * the roster grew — addressed to every ACTIVE participant INCLUDING
 * the added one, so the group appears in their dialog list at once
 * (US1-2) with the watermark already anchored at the group head
 * (FR-013).
 */
data class GroupMemberAddedEvent(
    val groupId: UUID,
    val user: GroupMemberUser,
    val actorId: UUID,
) : GroupEvent

/**
 * `group.member.removed` (realtime-group-events.md 006 §3.3, №32
 * kick/№33 leave): the roster shrank — every REMAINING active
 * participant; the removed user gets the final [GroupYouRemovedEvent]
 * instead. [actorId] is `null` on a voluntary leave (№33).
 */
data class GroupMemberRemovedEvent(
    val groupId: UUID,
    val userId: UUID,
    val actorId: UUID?,
) : GroupEvent

/**
 * `group.role.changed` (realtime-group-events.md 006 §3.4, FR-003):
 * one role transition; the №35 ownership transfer publishes the frame
 * TWICE — the new owner and the former one demoted to admin (clients
 * fold both idempotently, FR-015).
 */
data class GroupRoleChangedEvent(
    val groupId: UUID,
    val userId: UUID,
    val role: MemberRole,
    val actorId: UUID,
) : GroupEvent

/**
 * `group.you_removed` (realtime-group-events.md 006 §3.6,
 * FR-010/FR-015): the FINAL frame of the group for the affected user
 * only — all their devices/sessions drop the group from the dialog
 * list and ignore every later frame of it (the post-commit ordering
 * race, §1); after this publication the roster snapshot no longer
 * contains the user, so no group frame reaches the channel again.
 */
data class GroupYouRemovedEvent(
    val groupId: UUID,
    val reason: GroupRemovalReason,
) : GroupEvent

/**
 * `group.deleted` (realtime-group-events.md 006 §3.5, FR-006): the
 * owner hard-deleted the group — addressed to the snapshot of the
 * former active roster taken BEFORE the delete; recipients remove the
 * group, close its window and ignore every later frame of it.
 */
data class GroupDeletedEvent(
    val groupId: UUID,
    val actorId: UUID,
) : GroupEvent

/**
 * Marker of the two ephemeral №18 typing frames of 008a
 * (realtime-events.md §1; T029): a sealed hierarchy with the shared
 * `{chatId, userId}` payload keeps
 * [RealtimeEventPublisher.fanoutTypingEvent] exhaustive over the
 * contract set — a future typing frame lands as its payload type, and
 * every adapter must map it or fail to compile (the [GroupEvent]
 * discipline of 006 T009). [userId] is always the TYPING user (the №41
 * signal sender), never the observing addressee.
 */
sealed interface TypingEvent {
    val chatId: UUID

    val userId: UUID
}

/**
 * `typing.started` (realtime-events.md 008a §1.1; FR-006): the member
 * [userId] began/continued typing in the chat [chatId] — published on
 * EVERY valid №41 `start` including the ~3 s renewals («the indicator
 * does not sag», AC4); the anti-flap debounce is the client window +
 * the 60/min flood bucket, never a server-side suppression.
 */
data class TypingStartedEvent(
    override val chatId: UUID,
    override val userId: UUID,
) : TypingEvent

/**
 * `typing.stopped` (realtime-events.md 008a §1.2; FR-007): the member
 * [userId] stopped typing in the chat [chatId] — one of the three
 * extinguish legs (a №41 `stop` at an ACTIVE state, the typer's own №16
 * message INSERT, the poller's reap of the lapsed 8 s state); the
 * schema is identical to [TypingStartedEvent].
 */
data class TypingStoppedEvent(
    override val chatId: UUID,
    override val userId: UUID,
) : TypingEvent

/**
 * `chat.sound.updated` (realtime-events.md 008a §1.3; FR-012/FR-015):
 * the toggler's OWN per-chat sound switch genuinely CHANGED through №42
 * (a repeat of the stored value is the idempotent no-op that publishes
 * NOTHING) — the frame names the toggled dialog [chatId] and the NEW
 * [soundEnabled]. It is the ONLY 008a frame with an audience of exactly
 * one: the toggler himself (the multi-device sync of SC-006), never
 * another participant — the value and the very fact of the setting are
 * the row owner's alone (the privacy ban of FR-012).
 */
data class ChatSoundUpdatedEvent(
    val chatId: UUID,
    val soundEnabled: Boolean,
)

/**
 * Outbound realtime fan-out port (research.md 004 §2; DIP: the Redis
 * adapter lives outside the domain in
 * `webchat.backend.realtime.RedisRealtimePublisher`, T019).
 *
 * Publish-only: channel subscriptions and SSE emitters are transport
 * concerns of the realtime adapters (T018/T019), never of the domain.
 * Implementations publish a compact JSON envelope to the per-user Redis
 * Pub/Sub channel `rt:user:{userId}` strictly AFTER the PG commit
 * (data-model 004 §3 step 5) — the channel is at-most-once, and a lost or
 * failed delivery must NOT fail the already-durable request: the client
 * converges via the refetch on (re)connect (FR-009, constitution II).
 *
 * Since 006 (T009) the SAME port carries the group frames
 * (realtime-group-events.md §3) and the list-addressee fan-out legs:
 * group events and the 004 `message.created`/`chat.read` frames ride
 * the SAME per-user channels of a roster snapshot (≤ 200 by FR-002) —
 * a separate group realtime port is deliberately NOT introduced (YAGNI,
 * constitution VII; research.md 006 §4: no new broker topology).
 *
 * Since 008a (T029) it also carries the EPHEMERAL typing frames
 * (realtime-events.md 008a §1): same per-user channel family, same
 * at-most-once discipline — the state behind them lives in the
 * [TypingStore] and never touches PG, so a lost frame is compensated
 * by the observer's 10 s safety timeout, never by re-sending.
 *
 * Since 008a (T053) it also carries the №42 own-channel sound frame
 * `chat.sound.updated` (realtime-events.md 008a §1.3): the durable
 * `chat_participants.sound_enabled` change is already committed when
 * the caller hands the frame over, and a lost frame is compensated by
 * the №12/№13 refetch of a (re)connecting device — never by re-sending.
 */
interface RealtimeEventPublisher {
    /**
     * FR-007: fanned out to BOTH participants' channels by the caller
     * (T014) — the recipient renders the message; the sender's OTHER
     * devices/sessions render it as already delivered (US2-6).
     */
    fun publishMessageCreated(
        toUserId: UUID,
        event: MessageCreatedEvent,
    )

    /**
     * FR-010 (T042): sent to the PEER's channel only; the blocking-pair
     * suppression of FR-020 is decided by the caller — this port never
     * reveals blocking state on its own.
     */
    fun publishChatRead(
        toUserId: UUID,
        event: ChatReadEvent,
    )

    /**
     * T009 (realtime-group-events.md 006 §3): the six №18 group frames
     * — ONE envelope per [toUserIds] addressee, each on their own
     * `rt:user:{userId}` channel. The caller (T021+) takes the
     * ACTIVE-roster snapshot INSIDE the operation transaction and hands
     * it over strictly post-commit; the list is already the exact
     * addressee set of the specific frame (§3.2 including the added
     * member, §3.3 the REMAINING roster, §3.5 the pre-delete snapshot,
     * §3.6 the removed user ONLY) — the port fans it out without
     * second-guessing the membership (FR-008 is the gate's business).
     */
    fun fanoutGroupEvent(
        toUserIds: List<UUID>,
        event: GroupEvent,
    )

    /**
     * FR-011 (realtime-group-events.md 006 §3.7): the group leg of the
     * 004 message frame — the addressees are the ACTIVE-roster snapshot
     * minus the SENDER (≤ 199 publications by the FR-002 cap), taken in
     * the send transaction and published strictly post-commit (T035);
     * the payload stays the exact 004 `MessageCreatedEvent` shape.
     */
    fun fanoutMessageCreated(
        toUserIds: List<UUID>,
        event: MessageCreatedEvent,
    )

    /**
     * FR-012 (realtime-group-events.md 006 §3.7): the group leg of the
     * 004 read frame — everyone ACTIVE except the reader (T036); the
     * payload stays the exact 004 `ChatReadEvent` shape, and every
     * sender folds it monotonically (max) into their ✓✓ projection.
     */
    fun fanoutChatRead(
        toUserIds: List<UUID>,
        event: ChatReadEvent,
    )

    /**
     * T029 (realtime-events.md 008a §1.1/§1.2): the ephemeral №18 typing
     * frames — ONE envelope per [toUserIds] addressee, each on their own
     * `rt:user:{userId}` channel. The list is the caller's (T030
     * TypingService) EXACT audience: the ACTIVE chat participants MINUS
     * the signal sender (own devices never render their own typing —
     * US2 AC9 solved by addressing, never by payload flags), with the
     * DIRECT block-pair suppression already decided upstream (FR-008).
     * The frames are fire-and-forget over the at-most-once channel:
     * nothing is persisted, replayed or re-sent (FR-007) — a lost frame
     * dies with the observer's 10 s safety timeout.
     */
    fun fanoutTypingEvent(
        toUserIds: List<UUID>,
        event: TypingEvent,
    )

    /**
     * T053 (realtime-events.md 008a §1.3, FR-012/FR-015, SC-006): the
     * №42 multi-device sync frame — ONE envelope to the toggler's OWN
     * `rt:user:{userId}` channel and NOBODY else: the caller (T053
     * [ChatService.updateSound]) has already resolved the membership
     * gate, committed the durable change and judged the idempotency
     * rule (only a genuine CHANGE reaches this leg — a repeat of the
     * stored value never does), and the frame is the toggler's own
     * business exclusively: another participant's channel must never
     * carry it (privacy FR-012). At-most-once like every №18 frame: a
     * lost frame is compensated by the №12/№13 `soundEnabled` refetch
     * of a (re)connecting device, never by a re-send.
     */
    fun publishChatSoundUpdated(
        toUserId: UUID,
        event: ChatSoundUpdatedEvent,
    )
}
