package webchat.backend.chats.domain.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.NoopContactRepository
import webchat.backend.chats.NoopProfileStore
import webchat.backend.chats.TypingMetrics
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatKind
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatListRepository
import webchat.backend.chats.domain.port.ChatReadEvent
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.chats.domain.port.GroupEvent
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.chats.domain.port.TypingEvent
import webchat.backend.chats.domain.port.TypingStartedEvent
import webchat.backend.chats.domain.port.TypingState
import webchat.backend.chats.domain.port.TypingStoppedEvent
import webchat.backend.chats.domain.port.TypingStore
import webchat.backend.config.ChatsProperties
import webchat.backend.contacts.domain.model.UserBlock
import webchat.backend.contacts.domain.port.BlockRepository
import webchat.backend.groups.GroupMetrics
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.model.MembershipState
import webchat.backend.groups.domain.service.GroupMembershipGate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the T030 mandates (api-contract.md 008a №41,
 * data-model.md §2.1, realtime-events.md §1): the service resolves the
 * №16 membership refusals through the shared gate (`404 chat_not_found`
 * → `403 not_participant` — a stranger learns nothing), a `start` of a
 * clean dialog stores the TTL state and fans `typing.started` out to
 * the ACTIVE participants MINUS the sender on EVERY valid signal, the
 * DIRECT block pair suppresses BOTH directions WITHOUT refusing (no
 * state, no frame — so the poller can never later reap what nobody saw)
 * while a GROUP never consults the block marks (FR-008), a `stop`
 * publishes `typing.stopped` ONLY over the store's atomic claim (a
 * repeated stop stays silent — the idempotence against the T036 client
 * stop × T033 INSERT extinguish × T032 poller reap), and a failed
 * fan-out never fails the already-durable signal (at-most-once
 * isolation). The `webchat_typing_events_total{event}` sample rides
 * every publication. The Redis ZSET legs themselves belong to
 * RedisTypingStore (T028) and the full №41/№18 wiring to TypingIT
 * (T027/T034).
 */
class TypingServiceTest {
    @Test
    fun `start resolves an unknown chat to chat_not_found`() {
        val exception =
            assertThrows<ChatNotFoundException> {
                service.start(UNKNOWN_CHAT, ALICE)
            }

        assertThat(exception.code).isEqualTo(CODE_CHAT_NOT_FOUND)
        assertThat(store.starts).isEmpty()
        assertThat(publisher.typingFanouts).isEmpty()
        assertThat(meterRegistry.find(METRIC_EVENTS).counters()).isEmpty()
    }

    @Test
    fun `start refuses a stranger of the dialog with not_participant`() {
        val exception =
            assertThrows<NotParticipantException> {
                service.start(CHAT_ID, CAROL)
            }

        assertThat(exception.code).isEqualTo(CODE_NOT_PARTICIPANT)
        assertThat(store.starts).isEmpty()
        assertThat(publisher.typingFanouts).isEmpty()
        assertThat(meterRegistry.find(METRIC_EVENTS).counters()).isEmpty()
    }

    @Test
    fun `start stores the ttl state and fans typing started out to the peer only`() {
        service.start(CHAT_ID, ALICE)

        assertThat(store.starts).containsExactly(Triple(CHAT_ID, ALICE, STATE_TTL))
        assertThat(publisher.typingFanouts)
            .containsExactly(listOf(BOB) to TypingStartedEvent(chatId = CHAT_ID, userId = ALICE))
        assertThat(eventCount(EVENT_STARTED)).isEqualTo(1.0)
        assertThat(eventCount(EVENT_STOPPED)).isNull()
    }

    @Test
    fun `start is suppressed without state or frame when the sender blocks the peer`() {
        blocks.blockedPairs = setOf(ALICE to BOB)

        service.start(CHAT_ID, ALICE)

        assertThat(store.starts).isEmpty()
        assertThat(publisher.typingFanouts).isEmpty()
        assertThat(meterRegistry.find(METRIC_EVENTS).counters()).isEmpty()
    }

    @Test
    fun `start is suppressed without state or frame when the peer blocks the sender`() {
        blocks.blockedPairs = setOf(BOB to ALICE)

        service.start(CHAT_ID, ALICE)

        assertThat(store.starts).isEmpty()
        assertThat(publisher.typingFanouts).isEmpty()
        assertThat(meterRegistry.find(METRIC_EVENTS).counters()).isEmpty()
    }

    @Test
    fun `a group start fans out to the active roster minus the sender regardless of block marks`() {
        // FR-008: the same blocked pair inside a shared group still
        // trades typing frames — block marks filter the DIRECT pair
        // only (the 006 chat.read/message.created parity).
        blocks.blockedPairs = setOf(ALICE to BOB)

        service.start(GROUP_CHAT_ID, ALICE)

        assertThat(publisher.typingFanouts)
            .containsExactly(
                listOf(BOB, CAROL) to TypingStartedEvent(chatId = GROUP_CHAT_ID, userId = ALICE),
            )
        assertThat(eventCount(EVENT_STARTED)).isEqualTo(1.0)
    }

    @Test
    fun `stop publishes typing stopped over the claimed state`() {
        store.stopAnswer = true

        service.stop(CHAT_ID, ALICE)

        assertThat(store.stops).containsExactly(CHAT_ID to ALICE)
        assertThat(publisher.typingFanouts)
            .containsExactly(listOf(BOB) to TypingStoppedEvent(chatId = CHAT_ID, userId = ALICE))
        assertThat(eventCount(EVENT_STOPPED)).isEqualTo(1.0)
    }

    @Test
    fun `a repeated stop without an active state stays silent`() {
        store.stopAnswer = false

        service.stop(CHAT_ID, ALICE)

        assertThat(store.stops).containsExactly(CHAT_ID to ALICE)
        assertThat(publisher.typingFanouts).isEmpty()
        assertThat(meterRegistry.find(METRIC_EVENTS).counters()).isEmpty()
    }

    @Test
    fun `stop claims the state but suppresses the frame of a blocked direct pair`() {
        // The state may have been created BEFORE the block landed — the
        // claim still cleans it (the poller never reaps it later), only
        // the frame dies: the observers never see the block chatter.
        store.stopAnswer = true
        blocks.blockedPairs = setOf(ALICE to BOB)

        service.stop(CHAT_ID, ALICE)

        assertThat(store.stops).containsExactly(CHAT_ID to ALICE)
        assertThat(publisher.typingFanouts).isEmpty()
        assertThat(meterRegistry.find(METRIC_EVENTS).counters()).isEmpty()
    }

    @Test
    fun `a failed fan-out never fails the accepted signal`() {
        publisher.failFanout = true

        assertThatCode { service.start(CHAT_ID, ALICE) }.doesNotThrowAnyException()

        assertThat(store.starts).containsExactly(Triple(CHAT_ID, ALICE, STATE_TTL))
        assertThat(eventCount(EVENT_STARTED)).isEqualTo(1.0)
    }

    /** The published-frame sample of `webchat_typing_events_total{event}` — `null` when the leg never ran. */
    private fun eventCount(event: String): Double? =
        meterRegistry
            .find(METRIC_EVENTS)
            .tag(TAG_EVENT, event)
            .counter()
            ?.count()

    private val meterRegistry = SimpleMeterRegistry()

    private val store =
        ScriptedTypingStore().apply {
            // The DIRECT dialog and the three-seat group of the fixtures.
            activeRows[CHAT_ID] = listOf(directRow(ALICE), directRow(BOB))
            activeRows[GROUP_CHAT_ID] = listOf(groupRow(ALICE, MemberRole.OWNER), groupRow(BOB), groupRow(CAROL))
        }

    private val publisher = RecordingTypingPublisher()

    private val blocks = ScriptedBlockRepository()

    private val service =
        TypingService(
            chatService =
                ChatService(
                    NoopUserRepository,
                    GateChatRepository,
                    ChatListRepository { emptyList() },
                    store,
                    blocks,
                    GroupMembershipGate(store, GroupMetrics(SimpleMeterRegistry())),
                    NoopProfileStore,
                    NoopContactRepository,
                ),
            participantRepository = store,
            blockRepository = blocks,
            typingStore = store,
            realtimeEventPublisher = publisher,
            chatsProperties = TEST_PROPERTIES,
            typingMetrics = TypingMetrics(meterRegistry),
        )

    private companion object {
        const val CODE_CHAT_NOT_FOUND = "chat_not_found"
        const val CODE_NOT_PARTICIPANT = "not_participant"

        /** T030: the published-frame counter and its tag (realtime-events.md 008a §4). */
        const val METRIC_EVENTS = "webchat_typing_events_total"
        const val TAG_EVENT = "event"
        const val EVENT_STARTED = "started"
        const val EVENT_STOPPED = "stopped"

        const val TEST_CAP = 8
        val STATE_TTL: Duration = Duration.ofSeconds(8)

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val GROUP_CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd")
        val UNKNOWN_CHAT = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
        val PAIR_CHAT = Chat.forPair(CHAT_ID, ALICE, BOB, CREATED_AT)
        val GROUP_CHAT =
            Chat(
                id = GROUP_CHAT_ID,
                kind = ChatKind.GROUP,
                title = "typing unit group",
                createdAt = CREATED_AT,
            )
        val TEST_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = TEST_CAP, pageSize = 50),
                rateLimit =
                    ChatsProperties.RateLimit(
                        messagesPerMinute = 30,
                        searchesPerMinute = 30,
                        typingSignalsPerMinute = 60,
                        soundWritesPerMinute = 30,
                    ),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofSeconds(15)),
                typing =
                    ChatsProperties.Typing(
                        stateTtl = STATE_TTL,
                        pollerEnabled = false,
                        pollInterval = Duration.ofSeconds(1),
                        pollBatch = 1000,
                    ),
            )

        /** A DIRECT participant row — `state='active'` by the V14 default, no role. */
        fun directRow(userId: UUID) = ChatParticipant(chatId = CHAT_ID, userId = userId, createdAt = CREATED_AT)

        /** An ACTIVE group membership row of [GROUP_CHAT_ID]. */
        fun groupRow(
            userId: UUID,
            role: MemberRole = MemberRole.MEMBER,
        ) = ChatParticipant(chatId = GROUP_CHAT_ID, userId = userId, role = role, createdAt = CREATED_AT)
    }

    /**
     * The T028 seam: remembers every start/stop, answers [stopAnswer]
     * as the atomic claim (`true` — an active state was removed), and
     * doubles as the roster port — [activeRows] serves BOTH the
     * `activeMembers` audience read and the `findActive` membership
     * lookups of the shared gate.
     */
    private class ScriptedTypingStore :
        TypingStore,
        ParticipantRepository {
        val starts = mutableListOf<Triple<UUID, UUID, Duration>>()
        val stops = mutableListOf<Pair<UUID, UUID>>()

        /** The scripted claim outcome of `stop` — the idempotence switch of the tests. */
        var stopAnswer: Boolean = true

        /** chatId → ACTIVE rows; the audience/gate fixture, mutable per test. */
        val activeRows = mutableMapOf<UUID, List<ChatParticipant>>()

        override fun start(
            chatId: UUID,
            userId: UUID,
            ttl: Duration,
        ) {
            starts += Triple(chatId, userId, ttl)
        }

        override fun stop(
            chatId: UUID,
            userId: UUID,
        ): Boolean {
            stops += chatId to userId
            return stopAnswer
        }

        override fun dueExpired(batch: Int): List<TypingState> = emptyList()

        override fun find(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = activeRows[chatId]?.firstOrNull { it.userId == userId }

        override fun findForChat(chatId: UUID): List<ChatParticipant> = activeRows[chatId].orEmpty()

        override fun findActive(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = find(chatId, userId)?.takeIf { it.state == MembershipState.ACTIVE }

        override fun activeMembers(chatId: UUID): List<ChatParticipant> =
            activeRows[chatId].orEmpty().filter { it.state == MembershipState.ACTIVE }

        override fun advanceReadUpTo(
            chatId: UUID,
            userId: UUID,
            upToSeq: Long,
        ): ChatParticipant? = null

        override fun deleteUpTo(
            chatId: UUID,
            userId: UUID,
            chatLastSeq: Long,
        ): ChatParticipant? = null

        override fun advanceDelivered(
            userId: UUID,
            acks: Map<UUID, Long>,
        ) = Unit

        override fun loadForSync(
            userId: UUID,
            clientCursors: Map<UUID, Long>,
            chatLimit: Int,
        ): UndeliveredChatPage = UndeliveredChatPage(emptyList(), moreChats = false)

        override fun countUnread(
            userId: UUID,
            chatId: UUID,
        ): Long = 0L

        override fun addMember(
            chatId: UUID,
            userId: UUID,
            role: MemberRole,
        ): ChatParticipant = error("a typing signal never manages the roster")

        override fun reactivate(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = null

        override fun removeMember(
            chatId: UUID,
            userId: UUID,
        ): Boolean = false

        override fun updateRole(
            chatId: UUID,
            userId: UUID,
            role: MemberRole,
        ): ChatParticipant? = null

        override fun maxOtherReadUpToSeq(
            chatId: UUID,
            userId: UUID,
        ): Long = 0L
    }

    /** Records the typing fan-out legs; [failFanout] simulates a dead channel. */
    private class RecordingTypingPublisher : RealtimeEventPublisher {
        val typingFanouts = mutableListOf<Pair<List<UUID>, TypingEvent>>()

        var failFanout: Boolean = false

        override fun publishMessageCreated(
            toUserId: UUID,
            event: MessageCreatedEvent,
        ) = Unit

        override fun publishChatRead(
            toUserId: UUID,
            event: ChatReadEvent,
        ) = Unit

        override fun fanoutGroupEvent(
            toUserIds: List<UUID>,
            event: GroupEvent,
        ) = Unit

        override fun fanoutMessageCreated(
            toUserIds: List<UUID>,
            event: MessageCreatedEvent,
        ) = Unit

        override fun fanoutChatRead(
            toUserIds: List<UUID>,
            event: ChatReadEvent,
        ) = Unit

        override fun fanoutTypingEvent(
            toUserIds: List<UUID>,
            event: TypingEvent,
        ) {
            if (failFanout) error("channel down")
            typingFanouts += toUserIds to event
        }
    }

    /** The FR-002 gate fixture: only the pair chat and the group resolve, `ensure` is never reached. */
    private object GateChatRepository : ChatRepository {
        override fun findById(chatId: UUID): Chat? =
            when (chatId) {
                PAIR_CHAT.id -> PAIR_CHAT
                GROUP_CHAT.id -> GROUP_CHAT
                else -> null
            }

        override fun ensure(
            callerId: UUID,
            peerId: UUID,
        ): ChatEnsureResult = error("a typing signal never ensures a chat")
    }

    /** The auth port stands unused here — the typing path reads membership, not user rows. */
    private object NoopUserRepository : UserRepository {
        override fun insert(user: User) = Unit

        override fun update(user: User) = Unit

        override fun findById(id: UUID): User? = null

        override fun findByUsername(username: String): User? = null

        override fun findByEmail(email: String): User? = null
    }

    /** The block seam: point lookups against the scripted [blockedPairs] (the FR-020/FR-008 fixture). */
    private class ScriptedBlockRepository : BlockRepository {
        var blockedPairs: Set<Pair<UUID, UUID>> = emptySet()

        override fun block(
            blockerId: UUID,
            blockedId: UUID,
        ): UserBlock = error("a typing signal never establishes a block")

        override fun unblock(
            blockerId: UUID,
            blockedId: UUID,
        ): Unit = error("a typing signal never lifts a block")

        override fun exists(
            blockerId: UUID,
            blockedId: UUID,
        ): Boolean = blockerId to blockedId in blockedPairs

        override fun blockedTargetsOf(blockerId: UUID): Set<UUID> =
            blockedPairs.filter { it.first == blockerId }.map { it.second }.toSet()
    }
}
