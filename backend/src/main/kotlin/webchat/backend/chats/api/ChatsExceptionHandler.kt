package webchat.backend.chats.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.chats.domain.model.InvalidMessageTextException
import webchat.backend.chats.domain.model.MessageTextViolation
import webchat.backend.chats.domain.service.ChatNotFoundException
import webchat.backend.chats.domain.service.FloodLimitException
import webchat.backend.chats.domain.service.LimitOutOfRangeException
import webchat.backend.chats.domain.service.MessageIdConflictException
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
@Suppress("TooManyFunctions") // one @ExceptionHandler per contract failure code — the №11–№17 table is the size driver
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

    /**
     * 400 (api-contract.md №16): the FR-003 text refusal — `text_blank`
     * or `text_too_long` by the violated rule; the submitted text is
     * never echoed (constitution V).
     */
    @ExceptionHandler(InvalidMessageTextException::class)
    fun onInvalidMessageText(failure: InvalidMessageTextException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_MESSAGE_TEXT_DETAIL)
            .apply {
                setProperty(
                    ERRORS_PROPERTY,
                    mapOf(
                        TEXT_FIELD to
                            listOf(
                                when (failure.violation) {
                                    MessageTextViolation.BLANK -> TEXT_BLANK_CODE
                                    MessageTextViolation.TOO_LONG -> TEXT_TOO_LONG_CODE
                                },
                            ),
                    ),
                )
            }

    /** 400 (api-contract.md №16): absent or malformed `clientMessageId`. */
    @ExceptionHandler(InvalidClientMessageIdException::class)
    fun onInvalidClientMessageId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_CLIENT_MESSAGE_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CLIENT_MESSAGE_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    /**
     * 409 (api-contract.md №16): the `clientMessageId` belongs to another
     * stored record — refused, nothing written (FR-004 foreign-id edge).
     */
    @ExceptionHandler(MessageIdConflictException::class)
    fun onMessageIdConflict(): ProblemDetail =
        problem(HttpStatus.CONFLICT, MESSAGE_ID_CONFLICT_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CLIENT_MESSAGE_ID_FIELD to listOf(MESSAGE_ID_CONFLICT_CODE))) }

    /** 400 (api-contract.md №15): `limit` outside the contract bounds `1..50` (FR-008). */
    @ExceptionHandler(LimitOutOfRangeException::class)
    fun onLimitOutOfRange(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, LIMIT_OUT_OF_RANGE_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(LIMIT_FIELD to listOf(LIMIT_OUT_OF_RANGE_CODE))) }

    /**
     * 429 (api-contract.md №16): the FR-011 send flood limit — the
     * per-user allowance is exhausted, `Retry-After` carries the integral
     * seconds to the next available token and NOTHING was written. The
     * submitted text is never echoed (constitution V).
     */
    @ExceptionHandler(FloodLimitException::class)
    fun onFloodLimit(failure: FloodLimitException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, FLOOD_LIMIT_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(TEXT_FIELD to listOf(FLOOD_LIMIT_CODE))) },
            )

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
        const val TEXT_FIELD = "text"
        const val CLIENT_MESSAGE_ID_FIELD = "clientMessageId"
        const val LIMIT_FIELD = "limit"
        const val SELF_FORBIDDEN_CODE = "self_forbidden"
        const val PEER_NOT_FOUND_CODE = "peer_not_found"
        const val CHAT_NOT_FOUND_CODE = "chat_not_found"
        const val NOT_PARTICIPANT_CODE = "not_participant"
        const val INVALID_UUID_CODE = "invalid_uuid"
        const val TEXT_BLANK_CODE = "text_blank"
        const val TEXT_TOO_LONG_CODE = "text_too_long"
        const val MESSAGE_ID_CONFLICT_CODE = "message_id_conflict"
        const val LIMIT_OUT_OF_RANGE_CODE = "limit_out_of_range"
        const val FLOOD_LIMIT_CODE = "flood_limit"
        const val SELF_FORBIDDEN_DETAIL = "A dialog requires two distinct users"
        const val PEER_NOT_FOUND_DETAIL = "The requested peer user does not exist"
        const val CHAT_NOT_FOUND_DETAIL = "The requested chat does not exist"
        const val NOT_PARTICIPANT_DETAIL = "The caller is not a participant of this chat"
        const val INVALID_PEER_USER_ID_DETAIL = "peerUserId must be a UUID"
        const val INVALID_MESSAGE_TEXT_DETAIL = "message text violates FR-003 (blank or over 4096 after trim)"
        const val INVALID_CLIENT_MESSAGE_ID_DETAIL = "clientMessageId must be a UUID"
        const val MESSAGE_ID_CONFLICT_DETAIL = "the clientMessageId belongs to another stored message"
        const val LIMIT_OUT_OF_RANGE_DETAIL = "limit must be within 1..50"
        const val FLOOD_LIMIT_DETAIL = "The message rate limit is exceeded; retry after the indicated interval"
    }
}
