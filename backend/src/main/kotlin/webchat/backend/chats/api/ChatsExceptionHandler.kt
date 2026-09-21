package webchat.backend.chats.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.chats.domain.service.ChatNotFoundException
import webchat.backend.chats.domain.service.NotParticipantException
import webchat.backend.chats.domain.service.PeerNotFoundException
import webchat.backend.chats.domain.service.SelfForbiddenException

/**
 * RFC 9457 rendering for the chats API errors (api-contract.md §1, the
 * same conventions as the auth
 * [webchat.backend.auth.api.ApiExceptionHandler]): every failure leaves
 * the controller as a typed exception and reaches the client as
 * `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only, never
 * chat contents or participant details.
 */
@RestControllerAdvice
class ChatsExceptionHandler {
    /** 422 (api-contract.md №11): a dialog of the caller with themselves (FR-001). */
    @ExceptionHandler(SelfForbiddenException::class)
    fun onSelfForbidden(): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, SELF_FORBIDDEN_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(PEER_USER_ID_FIELD to listOf(SELF_FORBIDDEN_CODE))) }

    /** 404 (api-contract.md №11): the requested peer does not exist. */
    @ExceptionHandler(PeerNotFoundException::class)
    fun onPeerNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, PEER_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(PEER_FIELD to listOf(PEER_NOT_FOUND_CODE))) }

    /** 404 (api-contract.md №12–№17): an unknown chat id — uniform for any caller. */
    @ExceptionHandler(ChatNotFoundException::class)
    fun onChatNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, CHAT_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CHAT_FIELD to listOf(CHAT_NOT_FOUND_CODE))) }

    /** 403 (api-contract.md №12–№17): the chat exists but the caller is outside the pair (FR-002). */
    @ExceptionHandler(NotParticipantException::class)
    fun onNotParticipant(): ProblemDetail =
        problem(HttpStatus.FORBIDDEN, NOT_PARTICIPANT_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CHAT_FIELD to listOf(NOT_PARTICIPANT_CODE))) }

    /** 400 (api-contract.md №11): absent or malformed `peerUserId`. */
    @ExceptionHandler(InvalidPeerUserIdException::class)
    fun onInvalidPeerUserId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_PEER_USER_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(PEER_USER_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    private fun problem(
        status: HttpStatus,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            title = status.reasonPhrase
            this.detail = detail
        }

    private companion object {
        const val ERRORS_PROPERTY = "errors"
        const val CHAT_FIELD = "chat"
        const val PEER_FIELD = "peer"
        const val PEER_USER_ID_FIELD = "peerUserId"
        const val SELF_FORBIDDEN_CODE = "self_forbidden"
        const val PEER_NOT_FOUND_CODE = "peer_not_found"
        const val CHAT_NOT_FOUND_CODE = "chat_not_found"
        const val NOT_PARTICIPANT_CODE = "not_participant"
        const val INVALID_UUID_CODE = "invalid_uuid"
        const val SELF_FORBIDDEN_DETAIL = "A dialog requires two distinct users"
        const val PEER_NOT_FOUND_DETAIL = "The requested peer user does not exist"
        const val CHAT_NOT_FOUND_DETAIL = "The requested chat does not exist"
        const val NOT_PARTICIPANT_DETAIL = "The caller is not a participant of this chat"
        const val INVALID_PEER_USER_ID_DETAIL = "peerUserId must be a UUID"
    }
}
