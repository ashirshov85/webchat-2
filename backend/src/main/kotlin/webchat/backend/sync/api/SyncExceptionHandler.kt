package webchat.backend.sync.api

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import webchat.backend.sync.domain.service.AckBatchLimitException

/**
 * RFC 9457 rendering for the sync API errors (api-contract.md №25/№26,
 * the same conventions as the chats
 * [webchat.backend.chats.api.ChatsExceptionHandler]): every failure
 * leaves the controller as a typed exception and reaches the client as
 * `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only, never
 * message or dialog contents.
 *
 * The shared refusal families of №25/№26 — `404 chat_not_found`,
 * `403 not_participant`, `400 invalid_up_to_seq`, `400
 * limit_out_of_range` of №15 — already leave the reused chats services
 * as their typed exceptions and render through the global
 * [webchat.backend.chats.api.ChatsExceptionHandler]; this advice only
 * carries the problems born in the sync api layer itself (T014):
 * malformed `chatId` strings and the №26 page-limit knobs.
 */
@RestControllerAdvice
class SyncExceptionHandler {
    /**
     * 400 (api-contract.md №25/№26): the request `chatId` of an ack item
     * or a sync cursor is absent or not a UUID — rendered as
     * `errors: {chatId: [invalid_uuid]}`.
     */
    @ExceptionHandler(InvalidChatIdException::class)
    fun onInvalidChatId(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_CHAT_ID_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CHAT_ID_FIELD to listOf(INVALID_UUID_CODE))) }

    /**
     * 400 (api-contract.md №26): `chatLimit`/`messageLimit` outside the
     * contract bounds `1..50` — the precise offending knob carries the
     * code: `errors: {chatLimit: [limit_out_of_range]}` or
     * `errors: {messageLimit: [limit_out_of_range]}`.
     */
    @ExceptionHandler(InvalidSyncLimitException::class)
    fun onInvalidSyncLimit(failure: InvalidSyncLimitException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, INVALID_SYNC_LIMIT_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(failure.field to listOf(LIMIT_OUT_OF_RANGE_CODE))) }

    /**
     * 400 (api-contract.md №25): the ack batch outside the contract
     * bound `1..100` — refused by [AckBatchLimitException] before
     * anything is resolved or written, rendered as
     * `errors: {acks: [limit_out_of_range]}`.
     */
    @ExceptionHandler(AckBatchLimitException::class)
    fun onAckBatchLimit(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, ACK_BATCH_LIMIT_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(ACKS_FIELD to listOf(LIMIT_OUT_OF_RANGE_CODE))) }

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
        const val CHAT_ID_FIELD = "chatId"
        const val ACKS_FIELD = "acks"
        const val INVALID_UUID_CODE = "invalid_uuid"
        const val LIMIT_OUT_OF_RANGE_CODE = "limit_out_of_range"
        const val INVALID_CHAT_ID_DETAIL = "chatId must be a UUID"
        const val INVALID_SYNC_LIMIT_DETAIL = "chatLimit and messageLimit must be within 1..50"
        const val ACK_BATCH_LIMIT_DETAIL = "the ack batch must carry 1..100 items"
    }
}

/**
 * 400 (api-contract.md №25/№26): the request `chatId` of a
 * `DeliveryAckItem` or a `SyncCursor` is absent or not a UUID — thrown
 * by the controllers BEFORE any service is touched, so the domain never
 * sees a malformed identifier.
 */
class InvalidChatIdException(
    cause: IllegalArgumentException? = null,
) : RuntimeException("chatId must be a UUID", cause)

/**
 * 400 (api-contract.md №26): one of the №26 page-limit knobs
 * ([InvalidSyncLimitException.field] — `chatLimit` or `messageLimit`)
 * is outside the contract bounds `1..50`; the defaults themselves come
 * from `DeliveryProperties`, only the bounds are judged here.
 */
class InvalidSyncLimitException(
    val field: String,
) : RuntimeException("$field must be within 1..50")
