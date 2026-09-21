package webchat.backend.chats.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import webchat.backend.auth.domain.model.User
import webchat.backend.auth.domain.model.UserStatus
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.api.dto.EnsureChatRequest
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.chats.domain.service.ChatService
import java.time.Instant
import java.util.UUID

/**
 * Unit-level verification of the T016 mandates (api-contract.md №11/№13):
 * the HTTP adapter maps the idempotent pair resolve to `201 ChatView` /
 * `200 ChatView`, projects the peer as the reused `PublicUser` shape for
 * the CALLER's side of the dialog, and rejects an absent/malformed
 * `peerUserId` as the typed 400 carrier BEFORE the service is touched.
 *
 * The membership/problem+json legs render through [ChatsExceptionHandler]
 * and are asserted end-to-end by ChatAccessIT (T008, green at the T028
 * phase checkpoint together with the T019 publisher bean).
 */
class ChatControllerTest {
    @Test
    fun `ensure answers 201 with the peer projection when the dialog is created`() {
        val response = controller.ensure(EnsureChatRequest(BOB.toString()), tokenOf(ALICE))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        response.body!!.assertPairView()
    }

    @Test
    fun `ensure answers 200 for the existing dialog`() {
        controller.ensure(EnsureChatRequest(BOB.toString()), tokenOf(ALICE))

        val response = controller.ensure(EnsureChatRequest(BOB.toString()), tokenOf(ALICE))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        response.body!!.assertPairView()
    }

    @Test
    fun `ensure answers 200 symmetrically for the other participant`() {
        controller.ensure(EnsureChatRequest(BOB.toString()), tokenOf(ALICE))

        val response = controller.ensure(EnsureChatRequest(ALICE.toString()), tokenOf(BOB))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!.peer.id).isEqualTo(ALICE)
    }

    @Test
    fun `ensure rejects an absent peerUserId before the service is touched`() {
        assertThrows<InvalidPeerUserIdException> {
            controller.ensure(EnsureChatRequest(peerUserId = null), tokenOf(ALICE))
        }

        assertThat(repository.ensureCalls).isEmpty()
    }

    @Test
    fun `ensure rejects a malformed peerUserId before the service is touched`() {
        assertThrows<InvalidPeerUserIdException> {
            controller.ensure(EnsureChatRequest("not-a-uuid"), tokenOf(ALICE))
        }

        assertThat(repository.ensureCalls).isEmpty()
    }

    @Test
    fun `getChat answers the dialog of the caller with the peer projection`() {
        val view = controller.getChat(PAIR_CHAT.id, tokenOf(ALICE))

        view.assertPairView()
    }

    private fun webchat.backend.chats.api.dto.ChatView.assertPairView() {
        assertThat(chatId).isEqualTo(PAIR_CHAT.id)
        assertThat(peer.id).isEqualTo(BOB)
        assertThat(peer.username).isEqualTo("bob")
        assertThat(peer.email).isEqualTo("bob@example.com")
        assertThat(peer.status).isEqualTo("pending_email_confirmation")
        assertThat(peer.createdAt).isEqualTo(CREATED_AT)
    }

    private fun tokenOf(userId: UUID): Jwt =
        Jwt
            .withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .build()

    private val repository = ScriptedChatRepository()

    private val controller =
        ChatController(
            chatService = ChatService(userRepository = MapUserRepository(), chatRepository = repository),
            userRepository = MapUserRepository(),
        )

    private companion object {
        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
        val PAIR_CHAT = Chat.forPair(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), ALICE, BOB, CREATED_AT)
    }

    /** Answers Created on the FIRST ensure of a pair, Existing afterwards (№11 statuses, FR-018). */
    private class ScriptedChatRepository : ChatRepository {
        val ensureCalls = mutableListOf<Pair<UUID, UUID>>()
        private val ensuredPairs = mutableSetOf<Pair<UUID, UUID>>()

        override fun findById(chatId: UUID): Chat? = if (chatId == PAIR_CHAT.id) PAIR_CHAT else null

        override fun ensure(
            callerId: UUID,
            peerId: UUID,
        ): ChatEnsureResult {
            ensureCalls += callerId to peerId
            val pair = Chat.canonicalPair(callerId, peerId)
            return if (ensuredPairs.add(pair)) {
                ChatEnsureResult.Created(PAIR_CHAT)
            } else {
                ChatEnsureResult.Existing(PAIR_CHAT)
            }
        }
    }

    /** Serves both participants with stable PublicUser fields (the auth port reused across features). */
    private class MapUserRepository : UserRepository {
        override fun insert(user: User) = Unit

        override fun update(user: User) = Unit

        override fun findById(id: UUID): User? =
            when (id) {
                ALICE -> userOf(ALICE, "alice")
                BOB -> userOf(BOB, "bob")
                else -> null
            }

        override fun findByUsername(username: String): User? = null

        override fun findByEmail(email: String): User? = null

        private fun userOf(
            id: UUID,
            name: String,
        ): User =
            User(
                id = id,
                username = name,
                email = "$name@example.com",
                passwordHash = null,
                status = UserStatus.PENDING_EMAIL_CONFIRMATION,
                emailConfirmedAt = null,
                passwordSetAt = null,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            )
    }
}
