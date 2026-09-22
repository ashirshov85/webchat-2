package webchat.backend.chats.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.chats.domain.port.ParticipantRepository
import java.time.Instant
import java.util.UUID

/**
 * Unit-level mirror of the T013 mandates (api-contract.md №11/№13): the
 * service layer refuses a self-dialog (`422 self_forbidden`, FR-001 edge)
 * and an unknown peer (`404 peer_not_found`) BEFORE any repository write,
 * and every `get` resolves through the membership gate of FR-002
 * (`404 chat_not_found` → `403 not_participant` → the chat). The
 * pair-canonicalization itself is owned by the repository (JdbcChatRepositoryIT
 * of T011); the HTTP problem+json rendering is owned by the api layer (T016,
 * ChatAccessIT of T008).
 */
class ChatServiceTest {
    @Test
    fun `ensure refuses a dialog of the caller with themselves`() {
        val exception =
            assertThrows<SelfForbiddenException> {
                service.ensure(ALICE, ALICE)
            }

        assertThat(exception.code).isEqualTo(CODE_SELF_FORBIDDEN)
        assertThat(repository.ensureCalls).isEmpty()
    }

    @Test
    fun `ensure refuses an unknown peer before any write`() {
        val exception =
            assertThrows<PeerNotFoundException> {
                service.ensure(ALICE, UNKNOWN_PEER)
            }

        assertThat(exception.code).isEqualTo(CODE_PEER_NOT_FOUND)
        assertThat(repository.ensureCalls).isEmpty()
    }

    @Test
    fun `ensure delegates an existing distinct pair to the repository`() {
        val result = service.ensure(ALICE, BOB)

        assertThat(result).isInstanceOf(ChatEnsureResult.Created::class.java)
        assertThat(result.chat).isEqualTo(PAIR_CHAT)
        assertThat(repository.ensureCalls).containsExactly(ALICE to BOB)
    }

    @Test
    fun `get resolves an unknown chat to chat_not_found`() {
        val exception =
            assertThrows<ChatNotFoundException> {
                service.get(UNKNOWN_CHAT, ALICE)
            }

        assertThat(exception.code).isEqualTo(CODE_CHAT_NOT_FOUND)
    }

    @Test
    fun `get refuses a stranger of the dialog`() {
        val exception =
            assertThrows<NotParticipantException> {
                service.get(PAIR_CHAT.id, CAROL)
            }

        assertThat(exception.code).isEqualTo(CODE_NOT_PARTICIPANT)
    }

    @Test
    fun `get resolves the chat for either participant`() {
        assertThat(service.get(PAIR_CHAT.id, ALICE)).isEqualTo(PAIR_CHAT)
        assertThat(service.get(PAIR_CHAT.id, BOB)).isEqualTo(PAIR_CHAT)
    }

    /**
     * T043: the №11/№13 read fields — the watermarks project PER SIDE from
     * the participant rows (the reader its own mark, the sender the peer's
     * mark), never as a chat-wide value; a side without a row reads as the
     * openapi default 0 («0 — ничего не прочитано»).
     */
    @Test
    fun `readWatermarks projects each side its own mark`() {
        participants.readMarks[ALICE] = ALICE_READ_SEQ
        participants.readMarks[BOB] = BOB_READ_SEQ

        assertThat(service.readWatermarks(PAIR_CHAT, ALICE))
            .isEqualTo(ReadWatermarks(myReadUpToSeq = ALICE_READ_SEQ, peerReadUpToSeq = BOB_READ_SEQ))
        assertThat(service.readWatermarks(PAIR_CHAT, BOB))
            .isEqualTo(ReadWatermarks(myReadUpToSeq = BOB_READ_SEQ, peerReadUpToSeq = ALICE_READ_SEQ))
    }

    @Test
    fun `readWatermarks answers the openapi default 0 when a side has no row`() {
        assertThat(service.readWatermarks(PAIR_CHAT, ALICE))
            .isEqualTo(ReadWatermarks(myReadUpToSeq = 0, peerReadUpToSeq = 0))
    }

    private val repository = RecordingChatRepository()

    private val participants = MapParticipantRepository()

    private val service =
        ChatService(
            userRepository = MapUserRepository(ALICE, BOB),
            chatRepository = repository,
            participantRepository = participants,
        )

    private companion object {
        const val CODE_SELF_FORBIDDEN = "self_forbidden"
        const val CODE_PEER_NOT_FOUND = "peer_not_found"
        const val CODE_CHAT_NOT_FOUND = "chat_not_found"
        const val CODE_NOT_PARTICIPANT = "not_participant"

        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val UNKNOWN_PEER = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        val UNKNOWN_CHAT = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
        val PAIR_CHAT = Chat.forPair(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), ALICE, BOB, CREATED_AT)

        /** T043 fixture marks: distinct per side, so a swap would not pass unnoticed. */
        const val ALICE_READ_SEQ = 3L
        const val BOB_READ_SEQ = 5L
    }

    /** Remembers the ensure arguments — the refusals MUST precede any repository write. */
    private class RecordingChatRepository : ChatRepository {
        val ensureCalls = mutableListOf<Pair<UUID, UUID>>()

        override fun findById(chatId: UUID): Chat? = if (chatId == PAIR_CHAT.id) PAIR_CHAT else null

        override fun ensure(
            callerId: UUID,
            peerId: UUID,
        ): ChatEnsureResult {
            ensureCalls += callerId to peerId
            return ChatEnsureResult.Created(PAIR_CHAT)
        }
    }

    /** T043: the per-user read marks of the pair dialog, mutable per test. */
    private class MapParticipantRepository : ParticipantRepository {
        val readMarks = mutableMapOf<UUID, Long>()

        override fun find(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = null

        override fun findForChat(chatId: UUID): List<ChatParticipant> =
            readMarks.map { (userId, seq) ->
                ChatParticipant(chatId = chatId, userId = userId, lastReadSeq = seq, createdAt = CREATED_AT)
            }

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
    }

    /** The auth port reused across features (sso does the same); existence only — the users table is the source. */
    private class MapUserRepository(
        vararg val existing: UUID,
    ) : UserRepository {
        override fun insert(user: User) = Unit

        override fun update(user: User) = Unit

        override fun findById(id: UUID): User? =
            existing
                .firstOrNull { it == id }
                ?.let { User.register(it, "user-$it", "user-$it@example.com", CREATED_AT) }

        override fun findByUsername(username: String): User? = null

        override fun findByEmail(email: String): User? = null
    }
}
