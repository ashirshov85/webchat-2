package webchat.backend.chats.domain.service

import org.springframework.stereotype.Service
import webchat.backend.auth.domain.port.UserRepository
import webchat.backend.chats.domain.model.Chat
import webchat.backend.chats.domain.port.ChatEnsureResult
import webchat.backend.chats.domain.port.ChatRepository
import java.util.UUID

/**
 * 422 (api-contract.md №11): a dialog of the caller with themselves is
 * refused — a strict pair "one on one" has two DISTINCT sides (FR-001/edge).
 * The contract code is carried for the problem+json rendering of the api
 * layer (T016).
 */
class SelfForbiddenException : RuntimeException("a dialog requires two distinct users (FR-001)") {
    val code: String = "self_forbidden"
}

/**
 * 404 (api-contract.md №11): the requested peer does not exist — refused
 * BEFORE any repository write.
 */
class PeerNotFoundException : RuntimeException("the requested peer user does not exist") {
    val code: String = "peer_not_found"
}

/**
 * 404 (api-contract.md №12–№17): the chat id resolves to nothing — the
 * uniform answer to strangers and participants alike, so existence is
 * never disclosed beyond membership.
 */
class ChatNotFoundException : RuntimeException("the requested chat does not exist") {
    val code: String = "chat_not_found"
}

/**
 * 403 (api-contract.md, membership): the chat exists but the caller is not
 * one of its two participants (FR-002) — the refusal on EVERY chats
 * resource, resolved after the 404 above.
 */
class NotParticipantException : RuntimeException("the caller is not a participant of this chat") {
    val code: String = "not_participant"
}

/**
 * Dialog lifecycle of User Story 1 (T013): the idempotent pair resolve and
 * the membership-gated read.
 *
 * Ensure (api-contract.md №11, FR-001/FR-018): `422 self_forbidden` for a
 * dialog with oneself and `404 peer_not_found` for an unknown peer are
 * decided HERE, before the repository is touched — the pair resolve itself
 * (`ON CONFLICT` + lazy participants + caller `hidden` reset, FR-021) is
 * one PG transaction owned by [ChatRepository.ensure].
 *
 * Get (api-contract.md №13, FR-002): every chats resource resolves through
 * the same gate — `404 chat_not_found` first, then `403 not_participant`
 * for a stranger (Carol), so the two are never distinguishable beyond
 * membership. List (№12) and the per-user delete (№14) join here in T055/T056.
 */
@Service
class ChatService(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
) {
    fun ensure(
        callerId: UUID,
        peerId: UUID,
    ): ChatEnsureResult {
        if (callerId == peerId) throw SelfForbiddenException()
        if (userRepository.findById(peerId) == null) throw PeerNotFoundException()
        return chatRepository.ensure(callerId, peerId)
    }

    fun get(
        chatId: UUID,
        callerId: UUID,
    ): Chat {
        val chat = chatRepository.findById(chatId) ?: throw ChatNotFoundException()
        if (!chat.involves(callerId)) throw NotParticipantException()
        return chat
    }
}
