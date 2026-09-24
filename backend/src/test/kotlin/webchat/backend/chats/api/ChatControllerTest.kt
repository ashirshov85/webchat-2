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
import webchat.backend.chats.domain.model.ChatListEntry
import webchat.backend.chats.domain.model.ChatParticipant
import webchat.backend.chats.domain.model.ChatPeerSnapshot
import webchat.backend.chats.domain.model.Message
import webchat.backend.chats.domain.model.MessageText
import webchat.backend.chats.domain.model.UndeliveredChatPage
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatListRepository
import webchat.backend.chats.domain.port.ChatRepository
import webchat.backend.chats.domain.port.ParticipantRepository
import webchat.backend.chats.domain.service.ChatService
import webchat.backend.contacts.domain.model.UserBlock
import webchat.backend.contacts.domain.port.BlockRepository
import java.time.Instant
import java.util.UUID

/**
 * Unit-level verification of the T016 mandates (api-contract.md №11/№13):
 * the HTTP adapter maps the idempotent pair resolve to `201 ChatView` /
 * `200 ChatView`, projects the peer as the reused `PublicUser` shape for
 * the CALLER's side of the dialog, and rejects an absent/malformed
 * `peerUserId` as the typed 400 carrier BEFORE the service is touched.
 *
 * The T043 leg: `ChatView` carries BOTH read watermarks — `myReadUpToSeq`
 * of the caller and `peerReadUpToSeq` of the peer — projected per side
 * from the participant rows (a side with NO row reads as the openapi
 * default 0, «0 — ничего не прочитано»).
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
        assertThat(response.body!!.myReadUpToSeq)
            .overridingErrorMessage("the reader must see its OWN mark as myReadUpToSeq")
            .isEqualTo(BOB_READ_SEQ)
        assertThat(response.body!!.peerReadUpToSeq)
            .overridingErrorMessage("the reader must see the peer's mark as peerReadUpToSeq")
            .isZero()
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

    @Test
    fun `getChat projects blockedByMe for the blocker only`() {
        // T054 (FR-020): the ONLY block projection of ChatView — the
        // caller's own mark; the dialog stays visible either way.
        blocks.blockedPairs = setOf(ALICE to BOB)

        val blockerView = controller.getChat(PAIR_CHAT.id, tokenOf(ALICE))
        val blockedView = controller.getChat(PAIR_CHAT.id, tokenOf(BOB))

        assertThat(blockerView.blockedByMe)
            .overridingErrorMessage("the blocker must see blockedByMe=true")
            .isTrue
        assertThat(blockedView.blockedByMe)
            .overridingErrorMessage(
                "the blocked user must see NO block mark — the inverse projection may not exist (FR-020)",
            ).isFalse
    }

    /**
     * T055 (№12): the list re-shapes the aggregate rows into the contract
     * `ChatListItem`s — the peer snapshot, the FULL-text last visible
     * message and the badge travel verbatim; the caller is resolved from
     * the token `sub` for the read.
     */
    @Test
    fun `listChats maps the aggregate rows into contract items`() {
        listRepository.entries = listOf(pairEntry(), emptyEntry())

        val response = controller.listChats(tokenOf(ALICE))

        assertThat(response.chats).hasSize(2)
        val pairItem = response.chats[0]
        assertThat(pairItem.chatId).isEqualTo(PAIR_CHAT.id)
        assertThat(pairItem.peer.id).isEqualTo(BOB)
        assertThat(pairItem.peer.username).isEqualTo("bob")
        assertThat(pairItem.peer.email).isEqualTo("bob@example.com")
        assertThat(pairItem.peer.status).isEqualTo("pending_email_confirmation")
        assertThat(pairItem.peer.createdAt).isEqualTo(CREATED_AT)
        assertThat(pairItem.lastMessage!!.id).isEqualTo(LAST_MESSAGE.id)
        assertThat(pairItem.lastMessage.chatId).isEqualTo(PAIR_CHAT.id)
        assertThat(pairItem.lastMessage.senderId).isEqualTo(BOB)
        assertThat(pairItem.lastMessage.text).isEqualTo(LAST_MESSAGE.text.value)
        assertThat(pairItem.lastMessage.seq).isEqualTo(LAST_MESSAGE.seq)
        assertThat(pairItem.lastMessage.createdAt).isEqualTo(CREATED_AT)
        assertThat(pairItem.unreadCount).isEqualTo(UNREAD_COUNT)
        assertThat(pairItem.blockedByMe).isFalse
        assertThat(listRepository.listCalls).containsExactly(ALICE)
    }

    /** №12 empty leg: a caller without dialogs gets an EMPTY array, not an absent field. */
    @Test
    fun `listChats answers an empty list for a caller without dialogs`() {
        val response = controller.listChats(tokenOf(ALICE))

        assertThat(response.chats).isEmpty()
    }

    /** №12 null leg: a chat without visible messages carries `lastMessage = null`. */
    @Test
    fun `listChats renders lastMessage null for a chat without visible messages`() {
        listRepository.entries = listOf(emptyEntry())

        val response = controller.listChats(tokenOf(ALICE))

        assertThat(response.chats.single().lastMessage).isNull()
    }

    /**
     * T055/FR-020: `blockedByMe` of the list item is the caller's OWN mark
     * carried from the aggregate row — the same single direction as
     * ChatView (T054).
     */
    @Test
    fun `listChats carries the caller's own block mark only`() {
        listRepository.entries = listOf(pairEntry().copy(blockedByMe = true))

        val response = controller.listChats(tokenOf(ALICE))

        assertThat(response.chats.single().blockedByMe).isTrue
    }

    private fun webchat.backend.chats.api.dto.ChatView.assertPairView() {
        assertThat(chatId).isEqualTo(PAIR_CHAT.id)
        assertThat(peer.id).isEqualTo(BOB)
        assertThat(peer.username).isEqualTo("bob")
        assertThat(peer.email).isEqualTo("bob@example.com")
        assertThat(peer.status).isEqualTo("pending_email_confirmation")
        assertThat(peer.createdAt).isEqualTo(CREATED_AT)
        assertThat(blockedByMe)
            .overridingErrorMessage("the unblocked fixture pair carries no block mark")
            .isFalse
        assertThat(myReadUpToSeq)
            .overridingErrorMessage("ALICE has no participant row — her watermark reads as the openapi default 0")
            .isZero()
        assertThat(peerReadUpToSeq)
            .overridingErrorMessage("the sender's ✓✓ source is the peer's (BOB's) watermark")
            .isEqualTo(BOB_READ_SEQ)
    }

    private fun tokenOf(userId: UUID): Jwt =
        Jwt
            .withTokenValue("test-token")
            .header("alg", "none")
            .subject(userId.toString())
            .build()

    private val repository = ScriptedChatRepository()

    /** The T055 seam: the №12 aggregate rows served to the controller. */
    private val listRepository = ScriptedChatListRepository()

    /** The T054 seam: the (blocker, blocked) pairs the `blockedByMe` projection answers `true` for. */
    private val blocks = ScriptedBlockRepository()

    private val controller =
        ChatController(
            chatService =
                ChatService(
                    userRepository = MapUserRepository(),
                    chatRepository = repository,
                    chatListRepository = listRepository,
                    participantRepository = MapParticipantRepository(),
                    blockRepository = blocks,
                ),
            userRepository = MapUserRepository(),
        )

    private fun pairEntry(): ChatListEntry =
        ChatListEntry(
            chatId = PAIR_CHAT.id,
            peer =
                ChatPeerSnapshot(
                    id = BOB,
                    username = "bob",
                    email = "bob@example.com",
                    status = "pending_email_confirmation",
                    createdAt = CREATED_AT,
                ),
            lastMessage = LAST_MESSAGE,
            unreadCount = UNREAD_COUNT,
            blockedByMe = false,
        )

    private fun emptyEntry(): ChatListEntry =
        ChatListEntry(
            chatId = EMPTY_CHAT.id,
            peer =
                ChatPeerSnapshot(
                    id = CAROL,
                    username = "carol",
                    email = "carol@example.com",
                    status = "active",
                    createdAt = CREATED_AT,
                ),
            lastMessage = null,
            unreadCount = 0,
            blockedByMe = false,
        )

    private companion object {
        val ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val BOB = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val CAROL = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val CREATED_AT = Instant.parse("2026-01-01T00:00:00Z")
        val PAIR_CHAT = Chat.forPair(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), ALICE, BOB, CREATED_AT)
        val EMPTY_CHAT = Chat.forPair(UUID.fromString("00000000-0000-0000-0000-0000000000cc"), ALICE, CAROL, CREATED_AT)

        /** T043 fixture: BOB (the reader) has advanced his watermark; ALICE has no row yet. */
        const val BOB_READ_SEQ = 7L

        /** T055 fixtures: the last visible message and the badge of the pair dialog. */
        val LAST_MESSAGE =
            Message(
                id = UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                chatId = PAIR_CHAT.id,
                senderId = BOB,
                text = MessageText.normalize("the last visible message — full text"),
                seq = 9,
                createdAt = CREATED_AT,
            )
        const val UNREAD_COUNT = 2L
    }

    /**
     * Answers Created on the FIRST ensure of a pair, Existing afterwards (№11 statuses, FR-018).
     */
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

    /** The T055 seam: serves the scripted №12 rows and remembers the callers. */
    private class ScriptedChatListRepository : ChatListRepository {
        var entries: List<ChatListEntry> = emptyList()
        val listCalls = mutableListOf<UUID>()

        override fun listForUser(callerId: UUID): List<ChatListEntry> {
            listCalls += callerId
            return entries
        }
    }

    /**
     * T043 fixture: serves the per-user read marks of the pair dialog —
     * BOB at [BOB_READ_SEQ], ALICE with no row at all (the openapi
     * default-0 leg of the watermark projection).
     */
    private class MapParticipantRepository : ParticipantRepository {
        override fun find(
            chatId: UUID,
            userId: UUID,
        ): ChatParticipant? = null

        override fun findForChat(chatId: UUID): List<ChatParticipant> =
            if (chatId != PAIR_CHAT.id) {
                emptyList()
            } else {
                listOf(
                    ChatParticipant(
                        chatId = chatId,
                        userId = BOB,
                        lastReadSeq = BOB_READ_SEQ,
                        createdAt = CREATED_AT,
                    ),
                )
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

        /** 005 legs are outside the №11–№13 surface — empty by contract default. */
        override fun advanceDelivered(
            userId: UUID,
            acks: Map<UUID, Long>,
        ) = Unit

        override fun loadForSync(
            userId: UUID,
            chatLimit: Int,
        ): UndeliveredChatPage = UndeliveredChatPage(emptyList(), moreChats = false)
    }

    /** The T054 fixture: point lookups against the scripted [blockedPairs] (empty — no blocks). */
    private class ScriptedBlockRepository : BlockRepository {
        var blockedPairs: Set<Pair<UUID, UUID>> = emptySet()

        override fun block(
            blockerId: UUID,
            blockedId: UUID,
        ): UserBlock = error("the chat views never establish a block")

        override fun unblock(
            blockerId: UUID,
            blockedId: UUID,
        ): Unit = error("the chat views never lift a block")

        override fun exists(
            blockerId: UUID,
            blockedId: UUID,
        ): Boolean = blockerId to blockedId in blockedPairs
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
