package webchat.backend.chats.domain.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.InvalidMessageTextException
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.model.MessageTextViolation
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatReadEvent
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.chats.domain.port.MessageCreatedEvent
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewMessage
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.config.ChatsProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the T014 mandates (api-contract.md №16,
 * data-model 004 §3): the send path resolves membership through the same
 * FR-002 gate as every chats resource (`404 chat_not_found` →
 * `403 not_participant`), normalizes the text by the single FR-003 rule
 * (trim, non-empty, ≤ `chats.message.max-length`) BEFORE any write, maps
 * the exactly-once INSERT to `Created` (201) / `Existing` (200, FR-004
 * retry) / `409 message_id_conflict` (a foreign clientMessageId), and —
 * only for an actual new record — fans `message.created` out to BOTH
 * participants strictly AFTER the durable insert (FR-007). The PG
 * transaction itself is owned by the repository (JdbcMessageRepositoryIT
 * of T012); the HTTP problem+json rendering is owned by the api layer
 * (T017, MessageValidationIT/ChatAccessIT of T008/T008a).
 */
class MessageServiceTest {
    @Test
    fun `send resolves an unknown chat to chat_not_found`() {
        val exception =
            assertThrows<ChatNotFoundException> {
                service.send(UNKNOWN_CHAT, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
            }

        assertThat(exception.code).isEqualTo(CODE_CHAT_NOT_FOUND)
        assertThat(repository.inserts).isEmpty()
        assertThat(publisher.messageCreated).isEmpty()
    }

    @Test
    fun `send refuses a stranger of the dialog with not_participant`() {
        val exception =
            assertThrows<NotParticipantException> {
                service.send(CHAT_ID, CAROL, CLIENT_MESSAGE_ID, VALID_TEXT)
            }

        assertThat(exception.code).isEqualTo(CODE_NOT_PARTICIPANT)
        assertThat(repository.inserts).isEmpty()
        assertThat(publisher.messageCreated).isEmpty()
    }

    @Test
    fun `send refuses whitespace-only text before any write`() {
        val exception =
            assertThrows<InvalidMessageTextException> {
                service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, BLANKISH_TEXT)
            }

        assertThat(exception.violation).isEqualTo(MessageTextViolation.BLANK)
        assertThat(repository.inserts).isEmpty()
        assertThat(publisher.messageCreated).isEmpty()
    }

    @Test
    fun `send refuses text beyond the configured cap before any write`() {
        val exception =
            assertThrows<InvalidMessageTextException> {
                service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, "c".repeat(TEST_CAP + 1))
            }

        assertThat(exception.violation).isEqualTo(MessageTextViolation.TOO_LONG)
        assertThat(repository.inserts).isEmpty()
        assertThat(publisher.messageCreated).isEmpty()
    }

    @Test
    fun `send stores the trimmed text and maps a fresh record to Created`() {
        val result = service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, "  $VALID_TEXT  ")

        assertThat(result).isEqualTo(MessageSendResult.Created(STORED))
        val candidate = repository.inserts.single()
        assertThat(candidate.id).isEqualTo(CLIENT_MESSAGE_ID)
        assertThat(candidate.chatId).isEqualTo(CHAT_ID)
        assertThat(candidate.senderId).isEqualTo(ALICE)
        assertThat(candidate.text.value).isEqualTo(VALID_TEXT)
    }

    @Test
    fun `send fans message created out to both participants after the durable insert`() {
        service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)

        assertThat(timeline).containsExactly("insert", "publish:$ALICE", "publish:$BOB")
        val expectedEvent = MessageCreatedEvent(chatId = CHAT_ID, message = STORED)
        assertThat(publisher.messageCreated)
            .containsExactly(ALICE to expectedEvent, BOB to expectedEvent)
    }

    @Test
    fun `send maps a retry of the same record to Existing without publishing`() {
        repository.outcome = MessageInsertResult.Duplicate(STORED)

        val result = service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)

        assertThat(result).isEqualTo(MessageSendResult.Existing(STORED))
        assertThat(publisher.messageCreated).isEmpty()
    }

    @Test
    fun `send refuses a foreign clientMessageId with message_id_conflict`() {
        repository.outcome = MessageInsertResult.Duplicate(STORED.copy(chatId = UNKNOWN_CHAT))
        val foreignChat =
            assertThrows<MessageIdConflictException> {
                service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
            }
        repository.outcome = MessageInsertResult.Duplicate(STORED.copy(senderId = BOB))
        val foreignSender =
            assertThrows<MessageIdConflictException> {
                service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
            }

        assertThat(foreignChat.code).isEqualTo(CODE_MESSAGE_ID_CONFLICT)
        assertThat(foreignSender.code).isEqualTo(CODE_MESSAGE_ID_CONFLICT)
        assertThat(publisher.messageCreated).isEmpty()
    }

    @Test
    fun `send records the ack latency for both the fresh and the retried record`() {
        service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
        repository.outcome = MessageInsertResult.Duplicate(STORED)
        service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)

        val ackTimers = meterRegistry.find(METRIC_ACK_SECONDS).timers()
        assertThat(ackTimers.map { it.id.getTag("outcome") to it.count() })
            .containsExactlyInAnyOrder(
                OUTCOME_CREATED to 1L,
                OUTCOME_EXISTING to 1L,
            )
        assertThat(ackTimers.all { it.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) >= 0 }).isTrue()
    }

    @Test
    fun `a realtime fan-out failure does not fail the durable send`() {
        publisher.failFor = setOf(ALICE, BOB)

        val result = service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)

        assertThat(result).isEqualTo(MessageSendResult.Created(STORED))
        assertThat(repository.inserts).hasSize(1)
    }

    private val timeline = mutableListOf<String>()

    private val repository = ScriptedMessageRepository(MessageInsertResult.Inserted(STORED), timeline)

    private val publisher = RecordingRealtimePublisher(timeline = timeline)

    private val meterRegistry = SimpleMeterRegistry()

    private val service =
        MessageService(
            chatService = ChatService(NoopUserRepository, GateChatRepository),
            messageRepository = repository,
            realtimeEventPublisher = publisher,
            chatsProperties = TEST_PROPERTIES,
            meterRegistry = meterRegistry,
        )

    private companion object {
        const val CODE_CHAT_NOT_FOUND = "chat_not_found"
        const val CODE_NOT_PARTICIPANT = "not_participant"
        const val CODE_MESSAGE_ID_CONFLICT = "message_id_conflict"

        /** T028: the SC-001 ack timer and its outcome tag values. */
        const val METRIC_ACK_SECONDS = "webchat_message_ack_seconds"
        const val OUTCOME_CREATED = "created"
        const val OUTCOME_EXISTING = "existing"

        const val VALID_TEXT = "привет"
        const val BLANKISH_TEXT = "  \t\n \n\t "
        const val TEST_CAP = 8

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val UNKNOWN_CHAT = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
        val CLIENT_MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
        val PAIR_CHAT = Chat.forPair(CHAT_ID, ALICE, BOB, CREATED_AT)
        val STORED =
            Message(
                id = CLIENT_MESSAGE_ID,
                chatId = CHAT_ID,
                senderId = ALICE,
                text = MessageText.normalize(VALID_TEXT, TEST_CAP),
                seq = 1,
                createdAt = CREATED_AT,
            )
        val TEST_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = TEST_CAP, pageSize = 50),
                rateLimit = ChatsProperties.RateLimit(messagesPerMinute = 30),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofSeconds(15)),
            )
    }

    /** Remembers every insert into the shared [timeline] — the publish MUST come after it (post-commit). */
    private class ScriptedMessageRepository(
        var outcome: MessageInsertResult,
        private val timeline: MutableList<String>,
    ) : MessageRepository {
        val inserts = mutableListOf<NewMessage>()

        override fun findById(id: UUID): Message? = null

        override fun insert(message: NewMessage): MessageInsertResult {
            inserts += message
            timeline += "insert"
            return outcome
        }

        override fun findVisiblePage(
            chatId: UUID,
            viewerId: UUID,
            before: Long?,
            limit: Int,
        ): List<Message> = emptyList()
    }

    /** Records the fan-out targets in the shared [timeline]; [failFor] simulates a dead channel. */
    private class RecordingRealtimePublisher(
        var failFor: Set<UUID> = emptySet(),
        private val timeline: MutableList<String>,
    ) : RealtimeEventPublisher {
        val messageCreated = mutableListOf<Pair<UUID, MessageCreatedEvent>>()

        override fun publishMessageCreated(
            toUserId: UUID,
            event: MessageCreatedEvent,
        ) {
            if (toUserId in failFor) error("channel down")
            messageCreated += toUserId to event
            timeline += "publish:$toUserId"
        }

        override fun publishChatRead(
            toUserId: UUID,
            event: ChatReadEvent,
        ) = Unit
    }

    /** The FR-002 gate fixture: only the ensured pair chat resolves, `ensure` is never reached by a send. */
    private object GateChatRepository : ChatRepository {
        override fun findById(chatId: UUID): Chat? = if (chatId == PAIR_CHAT.id) PAIR_CHAT else null

        override fun ensure(
            callerId: UUID,
            peerId: UUID,
        ): ChatEnsureResult = error("a send never ensures a chat")
    }

    /** The auth port stands unused here — the send path reads membership, not user rows. */
    private object NoopUserRepository : UserRepository {
        override fun insert(user: User) = Unit

        override fun update(user: User) = Unit

        override fun findById(id: UUID): User? = null

        override fun findByUsername(username: String): User? = null

        override fun findByEmail(email: String): User? = null
    }
}
