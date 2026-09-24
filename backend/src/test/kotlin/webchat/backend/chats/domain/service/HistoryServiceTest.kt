package webchat.backend.chats.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatListRepository
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.chats.domain.port.MessageInsertResult
import webchat.backend.chats.domain.port.MessageRepository
import webchat.backend.chats.domain.port.NewMessage
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.config.ChatsProperties
import webchat.backend.contacts.domain.model.UserBlock
import webchat.backend.contacts.domain.port.BlockRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the T015 mandates (api-contract.md №15,
 * research.md 004 §3): the history read resolves membership through the
 * same FR-002 gate as every chats resource (`404 chat_not_found` →
 * `403 not_participant`), bounds the page size to the contract `1..50`
 * (`400 limit_out_of_range`, the bound fixed by `chats.message.page-size`
 * and defaulted from it when the query omits `limit`), passes the
 * EXCLUSIVE `before` cursor straight to the visibility query (FR-008) and
 * derives `nextBefore` from the oldest row of a full page — a short or
 * empty page at the boundary is exhaustion, so no cursor and no error
 * (US3-4). The visible-page SQL itself is owned by the repository
 * (JdbcMessageRepositoryIT of T012); the HTTP problem+json rendering and
 * the query parsing are owned by the api layer (T017).
 */
class HistoryServiceTest {
    @Test
    fun `history resolves an unknown chat to chat_not_found`() {
        val exception =
            assertThrows<ChatNotFoundException> {
                service.history(UNKNOWN_CHAT, ALICE, before = null, limit = null)
            }

        assertThat(exception.code).isEqualTo(CODE_CHAT_NOT_FOUND)
        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `history refuses a stranger of the dialog with not_participant`() {
        val exception =
            assertThrows<NotParticipantException> {
                service.history(CHAT_ID, CAROL, before = null, limit = null)
            }

        assertThat(exception.code).isEqualTo(CODE_NOT_PARTICIPANT)
        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `history rejects a limit outside the contract bounds before any read`() {
        for (bad in intArrayOf(0, -1, PAGE_SIZE + 1)) {
            val exception =
                assertThrows<LimitOutOfRangeException> {
                    service.history(CHAT_ID, ALICE, before = null, limit = bad)
                }

            assertThat(exception.code).isEqualTo(CODE_LIMIT_OUT_OF_RANGE)
        }

        assertThat(repository.calls).isEmpty()
    }

    @Test
    fun `history defaults the absent limit to the contract page size`() {
        val result = service.history(CHAT_ID, ALICE, before = null, limit = null)

        assertThat(result.nextBefore).isNull()
        assertThat(repository.calls).containsExactly(PageCall(CHAT_ID, ALICE, before = null, limit = PAGE_SIZE))
    }

    @Test
    fun `history passes the exclusive before cursor to the visibility query`() {
        repository.page = listOf(message(seq = 42), message(seq = 40))

        service.history(CHAT_ID, ALICE, before = 43, limit = null)

        assertThat(repository.calls).containsExactly(PageCall(CHAT_ID, ALICE, before = 43, limit = PAGE_SIZE))
    }

    @Test
    fun `history derives nextBefore from the oldest row of a full page`() {
        repository.page = (PAGE_SIZE.toLong() downTo 1L).map(::message)

        val result = service.history(CHAT_ID, ALICE, before = (PAGE_SIZE + 1).toLong(), limit = null)

        assertThat(result.messages).hasSize(PAGE_SIZE)
        assertThat(result.nextBefore).isEqualTo(1)
    }

    @Test
    fun `history omits nextBefore when the page is short`() {
        repository.page = listOf(message(seq = 9), message(seq = 8), message(seq = 7))

        val result = service.history(CHAT_ID, ALICE, before = 10, limit = null)

        assertThat(result.messages).hasSize(3)
        assertThat(result.nextBefore).isNull()
    }

    @Test
    fun `history omits nextBefore on the empty boundary page without failing`() {
        repository.page = emptyList()

        val result = service.history(CHAT_ID, ALICE, before = 1, limit = null)

        assertThat(result.messages).isEmpty()
        assertThat(result.nextBefore).isNull()
    }

    private val repository = ScriptedPageRepository()

    private val service =
        HistoryService(
            chatService =
                ChatService(
                    NoopUserRepository,
                    GateChatRepository,
                    ChatListRepository { emptyList() },
                    NoopParticipantRepository,
                    NoopBlockRepository,
                ),
            messageRepository = repository,
            chatsProperties = TEST_PROPERTIES,
        )

    private fun message(seq: Long): Message =
        Message(
            id = UUID.nameUUIDFromBytes("msg-$seq".toByteArray()),
            chatId = CHAT_ID,
            senderId = ALICE,
            text = MessageText.normalize("текст $seq", TEST_CAP),
            seq = seq,
            createdAt = CREATED_AT,
        )

    private data class PageCall(
        val chatId: UUID,
        val viewerId: UUID,
        val before: Long?,
        val limit: Int,
    )

    /** Answers every page request from [page], remembering the exact call for cursor/default assertions. */
    private class ScriptedPageRepository(
        var page: List<Message> = emptyList(),
    ) : MessageRepository {
        val calls = mutableListOf<PageCall>()

        override fun findById(id: UUID): Message? = null

        override fun insert(message: NewMessage): MessageInsertResult = error("history never writes")

        override fun findVisiblePage(
            chatId: UUID,
            viewerId: UUID,
            before: Long?,
            limit: Int,
        ): List<Message> {
            calls += PageCall(chatId, viewerId, before, limit)
            return page
        }
    }

    /** The FR-002 gate fixture: only the ensured pair chat resolves, `ensure` is never reached by a history read. */
    private object GateChatRepository : ChatRepository {
        override fun findById(chatId: UUID): Chat? = if (chatId == PAIR_CHAT.id) PAIR_CHAT else null

        override fun ensure(
            callerId: UUID,
            peerId: UUID,
        ): ChatEnsureResult = error("a history read never ensures a chat")
    }

    /** The watermark port stands unused here — a history read never touches the read marks (T043 leg). */
    private object NoopParticipantRepository : ParticipantRepository {
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

        /** 005 legs are outside the №15 surface — empty by contract default. */
        override fun advanceDelivered(
            userId: UUID,
            acks: Map<UUID, Long>,
        ) = Unit

        override fun loadForSync(
            userId: UUID,
            chatLimit: Int,
        ): UndeliveredChatPage = UndeliveredChatPage(emptyList(), moreChats = false)
    }

    /** The auth port stands unused here — the history path reads membership, not user rows. */
    private object NoopUserRepository : UserRepository {
        override fun insert(user: User) = Unit

        override fun update(user: User) = Unit

        override fun findById(id: UUID): User? = null

        override fun findByUsername(username: String): User? = null

        override fun findByEmail(email: String): User? = null
    }

    /** The FR-020 pair is unblocked here — history stays readable under a block (BlockingIT, T048). */
    private object NoopBlockRepository : BlockRepository {
        override fun block(
            blockerId: UUID,
            blockedId: UUID,
        ): UserBlock = error("the history path never establishes a block")

        override fun unblock(
            blockerId: UUID,
            blockedId: UUID,
        ): Unit = error("the history path never lifts a block")

        override fun exists(
            blockerId: UUID,
            blockedId: UUID,
        ): Boolean = false
    }

    private companion object {
        const val CODE_CHAT_NOT_FOUND = "chat_not_found"
        const val CODE_NOT_PARTICIPANT = "not_participant"
        const val CODE_LIMIT_OUT_OF_RANGE = "limit_out_of_range"

        const val PAGE_SIZE = 50
        const val TEST_CAP = 8

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val CHAT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val UNKNOWN_CHAT = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
        val PAIR_CHAT =
            Chat.forPair(CHAT_ID, ALICE, UUID.fromString("00000000-0000-0000-0000-000000000002"), CREATED_AT)
        val TEST_PROPERTIES =
            ChatsProperties(
                message = ChatsProperties.Message(maxLength = TEST_CAP, pageSize = PAGE_SIZE),
                rateLimit = ChatsProperties.RateLimit(messagesPerMinute = 30, searchesPerMinute = 30),
                realtime = ChatsProperties.Realtime(heartbeat = Duration.ofSeconds(15)),
            )
    }
}
