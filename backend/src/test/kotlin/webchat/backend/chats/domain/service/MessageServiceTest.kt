package webchat.backend.chats.domain.service

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.ConsumptionProbe
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
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
import java.util.function.Supplier

/**
 * Unit-level mirror of the T014/T031 mandates (api-contract.md №16,
 * data-model 004 §3): the send path resolves the dedup fast-path FIRST
 * (T031 — a lookup by the client UUID before the membership gate, the
 * limits and the locks: a recorded id converges to its stored row, a
 * foreign one is refused `409 message_id_conflict`), then resolves
 * membership through the same FR-002 gate as every chats resource
 * (`404 chat_not_found` → `403 not_participant`), normalizes the text by
 * the single FR-003 rule (trim, non-empty, ≤ `chats.message.max-length`)
 * BEFORE any write, maps the exactly-once INSERT to `Created` (201) /
 * `Existing` (200, FR-004 race leg — the empty `RETURNING` of parallel
 * retries) / `409 message_id_conflict`, and — only for an actual new
 * record — fans `message.created` out to BOTH participants strictly
 * AFTER the durable insert (FR-007). The T032 flood bucket joins as the
 * last gate before the INSERT: a drained bucket refuses the send with
 * the ceil-of-refill-wait `Retry-After` and the
 * `webchat_send_rejected_total{reason=flood}` counter, while the dedup
 * fast-path stays ahead of it. The PG transaction itself is owned
 * by the repository (JdbcMessageRepositoryIT of T012); the Redis bucket
 * wiring — key family, drip, fail-open — by FloodLimitIT (T030); the
 * HTTP problem+json rendering is owned by the api layer (T017,
 * MessageValidationIT/ChatAccessIT of T008/T008a).
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
    fun `dedup fast-path returns the recorded row before the membership gate is reached`() {
        // The recorded row lives in a chat the FR-002 fixture CANNOT
        // resolve — only the step-0 lookup (data-model 004 §3, T031) can
        // answer this send; a membership-first order would throw
        // ChatNotFoundException instead of converging the retry.
        val recorded = STORED.copy(chatId = UNKNOWN_CHAT, seq = 7)
        repository.existingById = recorded

        val result = service.send(UNKNOWN_CHAT, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)

        assertThat(result).isEqualTo(MessageSendResult.Existing(recorded))
        assertThat(repository.inserts).isEmpty()
        assertThat(publisher.messageCreated).isEmpty()
        assertThat(meterRegistry.find(METRIC_ACK_SECONDS).timers().map { it.id.getTag("outcome") to it.count() })
            .overridingErrorMessage("the fast-path 200 retry must record the ack latency with outcome=existing")
            .containsExactly(OUTCOME_EXISTING to 1L)
    }

    @Test
    fun `dedup fast-path refuses a foreign recorded id with message_id_conflict`() {
        repository.existingById = STORED.copy(senderId = BOB)

        val exception =
            assertThrows<MessageIdConflictException> {
                service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
            }

        assertThat(exception.code).isEqualTo(CODE_MESSAGE_ID_CONFLICT)
        assertThat(repository.inserts).isEmpty()
        assertThat(publisher.messageCreated).isEmpty()
    }

    @Test
    fun `a retry whose edited draft became invalid still converges to the recorded row`() {
        // research.md 004 §3 ordering: the fast-path lookup precedes the
        // FR-003 validation — the id, not the draft, owns the outcome, so
        // even a blank re-send answers with the stored `200` row.
        repository.existingById = STORED

        val result = service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, BLANKISH_TEXT)

        assertThat(result).isEqualTo(MessageSendResult.Existing(STORED))
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
    fun `send records the ack latency for the fresh record and both legs of the 200 retry`() {
        service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
        repository.outcome = MessageInsertResult.Duplicate(STORED)
        service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
        repository.existingById = STORED
        service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)

        val ackTimers = meterRegistry.find(METRIC_ACK_SECONDS).timers()
        assertThat(ackTimers.map { it.id.getTag("outcome") to it.count() })
            .containsExactlyInAnyOrder(
                OUTCOME_CREATED to 1L,
                OUTCOME_EXISTING to 2L,
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

    @Test
    fun `a drained flood bucket refuses the send before the insert and counts the rejection`() {
        floodAnswer = ConsumptionProbe.rejected(0, NANOS_TO_WAIT, NANOS_TO_WAIT)

        val exception =
            assertThrows<FloodLimitException> {
                service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)
            }

        assertThat(exception.code).isEqualTo(CODE_FLOOD_LIMIT)
        assertThat(exception.retryAfterSeconds)
            .overridingErrorMessage(
                "Retry-After must be the ceil of the refill wait (openapi №16), got <%s>",
                exception.retryAfterSeconds,
            ).isEqualTo(EXPECTED_RETRY_AFTER_SECONDS)
        assertThat(repository.inserts).isEmpty()
        assertThat(publisher.messageCreated).isEmpty()
        assertThat(meterRegistry.find(METRIC_SEND_REJECTED).counters().map { it.id.getTag("reason") to it.count() })
            .overridingErrorMessage("the refusal must grow webchat_send_rejected_total{reason=flood}")
            .containsExactly(REASON_FLOOD to 1.0)
    }

    @Test
    fun `a drained flood bucket never penalizes the dedup fast-path retry`() {
        // research.md 004 §7 ordering «дедуп → флуд»: with the bucket
        // drained the recorded-id retry still converges to its row —
        // the probe is never even consulted, no rejection sample lands.
        repository.existingById = STORED
        floodAnswer = ConsumptionProbe.rejected(0, NANOS_TO_WAIT, NANOS_TO_WAIT)

        val result = service.send(CHAT_ID, ALICE, CLIENT_MESSAGE_ID, VALID_TEXT)

        assertThat(result).isEqualTo(MessageSendResult.Existing(STORED))
        assertThat(repository.inserts).isEmpty()
        assertThat(meterRegistry.find(METRIC_SEND_REJECTED).counters()).isEmpty()
    }

    private val timeline = mutableListOf<String>()

    private val repository = ScriptedMessageRepository(MessageInsertResult.Inserted(STORED), timeline)

    private val publisher = RecordingRealtimePublisher(timeline = timeline)

    private val meterRegistry = SimpleMeterRegistry()

    /**
     * T032: the flood-bucket seam of the unit scope — every token probe
     * answers with the current [floodAnswer] (`consumed` by default; the
     * flood tests flip it to `rejected` to drain the bucket). The real
     * Redis bucket is covered by FloodLimitIT (T030).
     */
    private var floodAnswer: ConsumptionProbe = ConsumptionProbe.consumed(Long.MAX_VALUE, 0L)

    @Suppress("UNCHECKED_CAST") // the raw Mockito mock is the ProxyManager<ByteArray> seam
    private val floodControl = Mockito.mock(ProxyManager::class.java) as ProxyManager<ByteArray>

    init {
        val admittingBucket = Mockito.mock(BucketProxy::class.java)
        Mockito
            .`when`(admittingBucket.tryConsumeAndReturnRemaining(ArgumentMatchers.anyLong()))
            .thenAnswer { floodAnswer }
        Mockito
            .`when`(
                floodControl.getProxy(
                    ArgumentMatchers.any(ByteArray::class.java),
                    ArgumentMatchers.any<Supplier<BucketConfiguration>>(),
                ),
            ).thenReturn(admittingBucket)
    }

    private val service =
        MessageService(
            chatService = ChatService(NoopUserRepository, GateChatRepository),
            messageRepository = repository,
            realtimeEventPublisher = publisher,
            chatsProperties = TEST_PROPERTIES,
            rateLimitProxyManager = floodControl,
            meterRegistry = meterRegistry,
        )

    private companion object {
        const val CODE_CHAT_NOT_FOUND = "chat_not_found"
        const val CODE_NOT_PARTICIPANT = "not_participant"
        const val CODE_MESSAGE_ID_CONFLICT = "message_id_conflict"
        const val CODE_FLOOD_LIMIT = "flood_limit"

        /** T028: the SC-001 ack timer and its outcome tag values. */
        const val METRIC_ACK_SECONDS = "webchat_message_ack_seconds"
        const val OUTCOME_CREATED = "created"
        const val OUTCOME_EXISTING = "existing"

        /** T032: the SC-008 rejection counter and its reason tag value. */
        const val METRIC_SEND_REJECTED = "webchat_send_rejected_total"
        const val REASON_FLOOD = "flood"

        /** 1.5s of refill wait — the №16 Retry-After must ceil it to 2s. */
        const val NANOS_TO_WAIT = 1_500_000_000L
        const val EXPECTED_RETRY_AFTER_SECONDS = 2L

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

        /** The T031 fast-path script: the row a `findById` lookup resolves (`null` — a miss). */
        var existingById: Message? = null

        override fun findById(id: UUID): Message? = existingById?.takeIf { it.id == id }

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
