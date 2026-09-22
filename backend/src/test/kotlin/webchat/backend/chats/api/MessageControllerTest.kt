package webchat.backend.chats.api

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
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.api.dto.ReadRequest
import webchat.backend.chats.api.dto.SendMessageRequest
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatParticipant
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
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.port.RealtimeEventPublisher
import webchat.backend.chats.domain.service.ChatService
import webchat.backend.chats.domain.service.HistoryService
import webchat.backend.chats.domain.service.InvalidUpToSeqException
import webchat.backend.chats.domain.service.MessageIdConflictException
import webchat.backend.chats.domain.service.MessageService
import webchat.backend.chats.domain.service.ReadService
import webchat.backend.config.ChatsProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier

/**
 * Unit-level verification of the T017 mandates (api-contract.md №15/№16)
 * and the T042 №17 mapping: the HTTP adapter maps the exactly-once send
 * to `201 MessageView` on a fresh record and `200 MessageView` on the
 * idempotent retry (FR-004), projects the domain record verbatim as the
 * contract `Message` schema (№16 answer and №15 pages share ONE shape,
 * US6), rejects an absent/malformed `clientMessageId` (`errors:
 * {clientMessageId: [invalid_uuid]}`) and an absent `text`
 * (`text_blank`) as typed 400 carriers BEFORE the service is touched,
 * derives the №15 `MessagePage` — `messages` by `seq DESC` plus
 * `nextBefore`, absent at exhaustion (FR-008) — and maps №17 to the bare
 * `204` with the parsed `upToSeq` (an absent value rejected BEFORE the
 * service is touched, FR-010).
 *
 * The membership/FR-003/problem+json legs render through
 * [ChatsExceptionHandler] and are asserted end-to-end by ChatAccessIT
 * (T008) and MessageValidationIT (T008a); the №17 watermark semantics —
 * by ReadReceiptsIT (T041).
 */
class MessageControllerTest {
    @Test
    fun `send answers 201 with the contract Message view on a fresh record`() {
        val request = SendMessageRequest(CLIENT_MESSAGE_ID.toString(), "  $VALID_TEXT  ")

        val response = controller.send(CHAT_ID, request, tokenOf(ALICE))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        response.body!!.assertStoredView()
    }

    @Test
    fun `send answers 200 with the same record view on the idempotent retry`() {
        repository.outcome = MessageInsertResult.Duplicate(STORED)
        val request = SendMessageRequest(CLIENT_MESSAGE_ID.toString(), VALID_TEXT)

        val response = controller.send(CHAT_ID, request, tokenOf(ALICE))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        response.body!!.assertStoredView()
        assertThat(repository.inserts).hasSize(1)
    }

    @Test
    fun `send rejects an absent clientMessageId before the service is touched`() {
        assertThrows<InvalidClientMessageIdException> {
            controller.send(CHAT_ID, SendMessageRequest(clientMessageId = null, text = VALID_TEXT), tokenOf(ALICE))
        }

        assertThat(repository.inserts).isEmpty()
    }

    @Test
    fun `send rejects a malformed clientMessageId before the service is touched`() {
        assertThrows<InvalidClientMessageIdException> {
            controller.send(CHAT_ID, SendMessageRequest("not-a-uuid", VALID_TEXT), tokenOf(ALICE))
        }

        assertThat(repository.inserts).isEmpty()
    }

    @Test
    fun `send rejects an absent text as blank before the service is touched`() {
        val request = SendMessageRequest(CLIENT_MESSAGE_ID.toString(), text = null)

        val exception =
            assertThrows<InvalidMessageTextException> {
                controller.send(CHAT_ID, request, tokenOf(ALICE))
            }

        assertThat(exception.violation).isEqualTo(MessageTextViolation.BLANK)
        assertThat(repository.inserts).isEmpty()
    }

    @Test
    fun `send propagates the 409 carrier of a foreign clientMessageId untouched`() {
        repository.outcome = MessageInsertResult.Duplicate(STORED.copy(chatId = UNKNOWN_CHAT))

        assertThrows<MessageIdConflictException> {
            controller.send(CHAT_ID, SendMessageRequest(CLIENT_MESSAGE_ID.toString(), VALID_TEXT), tokenOf(ALICE))
        }
    }

    @Test
    fun `list answers the page with the exclusive nextBefore cursor`() {
        repository.page = listOf(STORED.copy(seq = 42), STORED.copy(seq = 40))

        val view = controller.list(CHAT_ID, before = 43, limit = 2, accessToken = tokenOf(ALICE))

        assertThat(view.messages).hasSize(2)
        assertThat(view.messages[0].seq).isEqualTo(42)
        assertThat(view.messages[1].seq).isEqualTo(40)
        assertThat(view.nextBefore).isEqualTo(40)
        assertThat(repository.pageCalls).containsExactly(PageCall(CHAT_ID, ALICE, before = 43, limit = 2))
    }

    @Test
    fun `list omits nextBefore at exhaustion`() {
        repository.page = listOf(STORED.copy(seq = 2), STORED.copy(seq = 1))

        val view = controller.list(CHAT_ID, before = null, limit = null, accessToken = tokenOf(ALICE))

        assertThat(view.nextBefore).isNull()
        assertThat(repository.pageCalls).containsExactly(PageCall(CHAT_ID, ALICE, before = null, limit = PAGE_SIZE))
    }

    @Test
    fun `list projects every row with the same Message shape as the send answer`() {
        repository.page = listOf(STORED)

        val view = controller.list(CHAT_ID, before = null, limit = null, accessToken = tokenOf(ALICE))

        view.messages.single().assertStoredView()
    }

    @Test
    fun `read answers the bare 204 and forwards the parsed upToSeq`() {
        val response = controller.read(CHAT_ID, ReadRequest(upToSeq = LAST_SEQ), tokenOf(ALICE))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(response.body).isNull()
        assertThat(participants.advances).containsExactly(AdvanceCall(CHAT_ID, ALICE, LAST_SEQ))
    }

    @Test
    fun `read rejects an absent upToSeq before the service is touched`() {
        assertThrows<InvalidUpToSeqException> {
            controller.read(CHAT_ID, ReadRequest(upToSeq = null), tokenOf(ALICE))
        }

        assertThat(participants.advances).isEmpty()
    }

    private fun webchat.backend.chats.api.dto.MessageView.assertStoredView() {
        assertThat(id).isEqualTo(CLIENT_MESSAGE_ID)
        assertThat(chatId).isEqualTo(CHAT_ID)
        assertThat(senderId).isEqualTo(ALICE)
        assertThat(text).isEqualTo(VALID_TEXT)
        assertThat(seq).isEqualTo(1)
        assertThat(createdAt).isEqualTo(CREATED_AT)
    }

    private fun tokenOf(userId: UUID): Jwt =
        Jwt
            .withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .build()

    private val repository = ScriptedMessageRepository()

    /** The №17 leg fixture: remembers every advance and always reports it applied. */
    private val participants = ScriptedParticipantRepository()

    /**
     * T032: the flood gate always admits in this unit scope — the token
     * bucket itself (Redis key family, drip, 429 rendering) is covered
     * by FloodLimitIT (T030) and MessageServiceTest.
     */
    @Suppress("UNCHECKED_CAST") // the raw Mockito mock is the ProxyManager<ByteArray> seam
    private val floodControl = Mockito.mock(ProxyManager::class.java) as ProxyManager<ByteArray>

    init {
        val admittingBucket = Mockito.mock(BucketProxy::class.java)
        Mockito
            .`when`(admittingBucket.tryConsumeAndReturnRemaining(ArgumentMatchers.anyLong()))
            .thenReturn(ConsumptionProbe.consumed(Long.MAX_VALUE, 0L))
        Mockito
            .`when`(
                floodControl.getProxy(
                    ArgumentMatchers.any(ByteArray::class.java),
                    ArgumentMatchers.any<Supplier<BucketConfiguration>>(),
                ),
            ).thenReturn(admittingBucket)
    }

    private val controller =
        MessageController(
            messageService =
                MessageService(
                    chatService = ChatService(NoopUserRepository, GateChatRepository, participants),
                    messageRepository = repository,
                    realtimeEventPublisher = NoopRealtimePublisher,
                    chatsProperties = TEST_PROPERTIES,
                    rateLimitProxyManager = floodControl,
                    meterRegistry = SimpleMeterRegistry(),
                ),
            historyService =
                HistoryService(
                    chatService = ChatService(NoopUserRepository, GateChatRepository, participants),
                    messageRepository = repository,
                    chatsProperties = TEST_PROPERTIES,
                ),
            readService =
                ReadService(
                    chatService = ChatService(NoopUserRepository, GateChatRepository, participants),
                    participantRepository = participants,
                    realtimeEventPublisher = NoopRealtimePublisher,
                    meterRegistry = SimpleMeterRegistry(),
                ),
        )

    private data class PageCall(
        val chatId: UUID,
        val viewerId: UUID,
        val before: Long?,
        val limit: Int,
    )

    private data class AdvanceCall(
        val chatId: UUID,
        val userId: UUID,
        val upToSeq: Long,
    )

    /** Serves both the №16 write ([outcome]) and the №15 page ([page]), remembering every call. */
    private class ScriptedMessageRepository(
        var outcome: MessageInsertResult = MessageInsertResult.Inserted(STORED),
        var page: List<Message> = emptyList(),
    ) : MessageRepository {
        val inserts = mutableListOf<NewMessage>()
        val pageCalls = mutableListOf<PageCall>()

        override fun findById(id: UUID): Message? = null

        override fun insert(message: NewMessage): MessageInsertResult {
            inserts += message
            return outcome
        }

        override fun findVisiblePage(
            chatId: UUID,
            viewerId: UUID,
            before: Long?,
            limit: Int,
        ): List<Message> {
            pageCalls += PageCall(chatId, viewerId, before, limit)
            return page
        }
    }

    /** The №17 watermark sink (T042): an always-applied advance, remembering every call. */
    private class ScriptedParticipantRepository : ParticipantRepository {
        val advances = mutableListOf<AdvanceCall>()

        override fun find(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = null

        override fun findForChat(chatId: UUID): List<ChatParticipant> = emptyList()

        override fun advanceReadUpTo(
            chatId: UUID,
            userId: UUID,
            upToSeq: Long,
        ): ChatParticipant? {
            advances += AdvanceCall(chatId, userId, upToSeq)
            return ChatParticipant(
                chatId = chatId,
                userId = userId,
                lastReadSeq = upToSeq,
                createdAt = CREATED_AT,
            )
        }

        override fun deleteUpTo(
            chatId: UUID,
            userId: UUID,
            chatLastSeq: Long,
        ): ChatParticipant? = null
    }

    /** The realtime leg is irrelevant to the HTTP mapping — a silent sink keeps the unit surface narrow. */
    private object NoopRealtimePublisher : RealtimeEventPublisher {
        override fun publishMessageCreated(
            toUserId: UUID,
            event: MessageCreatedEvent,
        ) = Unit

        override fun publishChatRead(
            toUserId: UUID,
            event: ChatReadEvent,
        ) = Unit
    }

    /** The FR-002 gate fixture: only the ensured pair chat resolves, `ensure` is never reached here. */
    private object GateChatRepository : ChatRepository {
        override fun findById(chatId: UUID): Chat? = if (chatId == PAIR_CHAT.id) PAIR_CHAT else null

        override fun ensure(
            callerId: UUID,
            peerId: UUID,
        ): ChatEnsureResult = error("messages endpoints never ensure a chat")
    }

    /** The auth port stands unused here — these paths read membership, not user rows. */
    private object NoopUserRepository : UserRepository {
        override fun insert(user: User) = Unit

        override fun update(user: User) = Unit

        override fun findById(id: UUID): User? = null

        override fun findByUsername(username: String): User? = null

        override fun findByEmail(email: String): User? = null
    }

    private companion object {
        const val VALID_TEXT = "привет"
        const val PAGE_SIZE = 50
        const val TEST_CAP = 8

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val UNKNOWN_CHAT = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
        val CLIENT_MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")

        /** The №17 bound top: the gated pair chat has two recorded messages. */
        const val LAST_SEQ = 2L

        val PAIR_CHAT = Chat.forPair(CHAT_ID, ALICE, BOB, CREATED_AT).advanceLastSeq(LAST_SEQ)
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
                message = ChatsProperties.Message(maxLength = TEST_CAP, pageSize = PAGE_SIZE),
                rateLimit = ChatsProperties.RateLimit(messagesPerMinute = 30),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofSeconds(15)),
            )
    }
}
