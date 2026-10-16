package webchat.backend.chats.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageKind
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.chats.domain.port.ChatReadEvent
import webchat.backend.chats.domain.port.ChatSoundUpdatedEvent
import webchat.backend.chats.domain.port.GroupEvent
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewMessage
import webchat.backend.chats.domain.port.NewSystemMessage
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.chats.domain.port.SystemMessagePort
import webchat.backend.chats.domain.port.TypingEvent
import webchat.backend.groups.domain.model.MemberRole
import webchat.backend.groups.domain.model.MembershipState
import java.time.Instant
import java.util.UUID

/**
 * T016 (009, research §8): the unit slice of the [SystemMessagePort]
 * implementation — the seam the №44 conference create (T017) uses to post
 * the invite card.
 *
 * Asserts the port contract of the append leg:
 *  * the widget payload guard — a JSON object serializing to at most
 *    [Message.MAX_PAYLOAD_BYTES] bytes (data-model §Сущность 4: the SIZE
 *    is service validation; the SHAPE is the V18 CHECK);
 *  * the addressee snapshot — the ACTIVE roster INCLUDING the operation
 *    actor, taken as the last statement BEFORE the INSERT (the 006
 *    discipline), with no sender to exclude and no FR-020 suppression
 *    (the server is the author);
 *  * the post-commit ordering — the fan-out fires strictly AFTER the
 *    INSERT returned and only when the ambient transaction COMMITTED
 *    (a rollback never announces a card, constitution III; without an
 *    ambient transaction the publication runs inline, the 006 fallback);
 *  * at-most-once isolation — a dead channel never fails the durable
 *    append (warn + converge via №15/№26, FR-009).
 *
 * SQL/persistence behaviour (the INSERT itself, the `last_seq` advance,
 * the FR-021 un-hide) is covered by JdbcMessageRepositoryIT; the №44
 * end-to-end scenarios (the card in history, `kind='system'`) — by the
 * conferences IT of T021.
 */
class SystemMessageServiceTest {
    private val objectMapper = ObjectMapper()
    private val participantRepository = ScriptedParticipantRepository()
    private val messageRepository = ScriptedMessageRepository()
    private val realtimePublisher = RecordingRealtimePublisher()
    private val service =
        SystemMessageService(
            messageRepository = messageRepository,
            participantRepository = participantRepository,
            realtimeEventPublisher = realtimePublisher,
        )

    @Test
    fun `append stores the widget row and fans it out to the active roster`() {
        participantRepository.members = listOf(participant(ALICE), participant(BOB), participant(CAROL, active = false))
        val payload = conferenceInvitePayload()

        val message = service.append(CHAT_ID, CAPTION, payload)

        assertThat(message.kind).isEqualTo(MessageKind.SYSTEM)
        assertThat(message.senderId).isNull()
        assertThat(message.payload).isEqualTo(payload)
        val inserted = messageRepository.systemInserts.single()
        assertThat(inserted.chatId).isEqualTo(CHAT_ID)
        assertThat(inserted.text.value).isEqualTo(CAPTION)

        val (addressees, event) = realtimePublisher.messageCreated.single()
        assertThat(addressees)
            .overridingErrorMessage(
                "the audience is the ACTIVE roster INCLUDING the actor — the card lands in the creator's own window",
            ).containsExactly(ALICE, BOB)
        assertThat(event.chatId).isEqualTo(CHAT_ID)
        assertThat(event.message).isEqualTo(message)
    }

    @Test
    fun `the fan-out is isolated from the durable append and ordered after the insert`() {
        realtimePublisher.fail = true

        val message = service.append(CHAT_ID, CAPTION, conferenceInvitePayload())

        assertThat(messageRepository.timeline).containsExactly("insert")
        assertThat(realtimePublisher.timeline).containsExactly("publish")
    }

    @Test
    fun `inside an ambient transaction the fan-out fires strictly after the commit`() {
        TransactionSynchronizationManager.initSynchronization()
        try {
            val message = service.append(CHAT_ID, CAPTION, conferenceInvitePayload())

            assertThat(messageRepository.systemInserts).hasSize(1)
            assertThat(realtimePublisher.messageCreated)
                .overridingErrorMessage("the registration alone publishes NOTHING before the commit")
                .isEmpty()

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit)
            assertThat(realtimePublisher.messageCreated).hasSize(1)
            val published = realtimePublisher.messageCreated.single()
            assertThat(published.second.message)
                .isEqualTo(message)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `a rolled-back ambient transaction never announces the card`() {
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.append(CHAT_ID, CAPTION, conferenceInvitePayload())

            assertThat(messageRepository.systemInserts).hasSize(1)
            assertThat(realtimePublisher.messageCreated).isEmpty()
        } finally {
            // the rollback path: the registered synchronizations die WITHOUT afterCommit
            TransactionSynchronizationManager.clearSynchronization()
        }

        assertThat(realtimePublisher.messageCreated)
            .overridingErrorMessage("a rollback never publishes message.created (constitution III)")
            .isEmpty()
    }

    @ParameterizedTest
    @ValueSource(ints = [4097, 8192])
    fun `a widget payload beyond 4 KB is refused before any write`(oversize: Int) {
        val padding = "x".repeat(oversize)
        val payload =
            objectMapper
                .createObjectNode()
                .put("type", "conference_invite")
                .put("padding", padding)

        assertThrows<IllegalArgumentException> { service.append(CHAT_ID, CAPTION, payload) }

        assertThat(messageRepository.systemInserts).isEmpty()
        assertThat(realtimePublisher.messageCreated).isEmpty()
    }

    private fun conferenceInvitePayload() =
        objectMapper
            .createObjectNode()
            .put("type", "conference_invite")
            .put("conferenceId", CONFERENCE_ID.toString())
            .put("mode", "video")

    private fun participant(
        userId: UUID,
        active: Boolean = true,
    ): ChatParticipant =
        ChatParticipant(
            chatId = CHAT_ID,
            userId = userId,
            role = MemberRole.MEMBER,
            state = if (active) MembershipState.ACTIVE else MembershipState.REMOVED,
            createdAt = Instant.parse("2026-09-20T12:00:00Z"),
        )

    /**
     * The only leg the append reads: [ParticipantRepository.activeMembers] —
     * the audience snapshot. Emulates the production SQL filter
     * (`WHERE state = 'active'`): a REMOVED row never reaches the service.
     */
    private class ScriptedParticipantRepository : ParticipantRepository {
        var members: List<ChatParticipant> = emptyList()

        override fun activeMembers(chatId: UUID): List<ChatParticipant> =
            members.filter { it.state == MembershipState.ACTIVE }

        override fun find(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = null

        override fun findForChat(chatId: UUID): List<ChatParticipant> = emptyList()

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

        override fun findActive(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = null

        override fun addMember(
            chatId: UUID,
            userId: UUID,
            role: MemberRole,
        ): ChatParticipant = error("the system append never manages the roster")

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

        override fun updateSoundEnabled(
            chatId: UUID,
            userId: UUID,
            enabled: Boolean,
        ): ChatParticipant? = null
    }

    /** Remembers the append writes into the shared [ScriptedMessageRepository.timeline]. */
    private inner class ScriptedMessageRepository : MessageRepository {
        val systemInserts = mutableListOf<NewSystemMessage>()
        val timeline = mutableListOf<String>()

        override fun findById(id: UUID): Message? = null

        override fun insert(message: NewMessage): MessageInsertResult = error("the system append never sends №16 rows")

        override fun insertSystem(message: NewSystemMessage): Message {
            systemInserts += message
            timeline += "insert"
            return Message(
                id = message.id,
                chatId = message.chatId,
                senderId = null,
                text = message.text,
                seq = 42,
                createdAt = Instant.parse("2026-10-16T12:00:00Z"),
                kind = MessageKind.SYSTEM,
                payload = message.payload,
            )
        }

        override fun findVisiblePage(
            chatId: UUID,
            viewerId: UUID,
            before: Long?,
            limit: Int,
        ): List<Message> = emptyList()

        override fun findVisiblePageAfter(
            chatId: UUID,
            viewerId: UUID,
            after: Long,
            limit: Int,
        ): List<Message> = emptyList()
    }

    /** Records the fan-out in the shared timeline; [fail] simulates a dead channel. */
    private inner class RecordingRealtimePublisher : RealtimeEventPublisher {
        val messageCreated = mutableListOf<Pair<List<UUID>, MessageCreatedEvent>>()
        val timeline = mutableListOf<String>()
        var fail: Boolean = false

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
        ) {
            timeline += "publish"
            if (fail) error("channel down")
            messageCreated += toUserIds to event
        }

        override fun fanoutChatRead(
            toUserIds: List<UUID>,
            event: ChatReadEvent,
        ) = Unit

        override fun fanoutTypingEvent(
            toUserIds: List<UUID>,
            event: TypingEvent,
        ) = Unit

        override fun publishChatSoundUpdated(
            toUserId: UUID,
            event: ChatSoundUpdatedEvent,
        ) = Unit
    }

    private companion object {
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-00000000cc01")
        val ALICE = UUID.fromString("00000000-0000-0000-0000-00000000cc11")
        val BOB = UUID.fromString("00000000-0000-0000-0000-00000000cc12")
        val CAROL = UUID.fromString("00000000-0000-0000-0000-00000000cc13")
        val CONFERENCE_ID = UUID.fromString("00000000-0000-0000-0000-00000000c0a1")
        const val CAPTION = "Видеозвонок"
    }
}
