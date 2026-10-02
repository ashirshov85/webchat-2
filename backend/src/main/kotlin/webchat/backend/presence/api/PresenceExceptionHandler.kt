package webchat.backend.presence.api

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * RFC 9457 rendering for the presence API errors (contracts/presence-api.md
 * §4; the same conventions as the chats/contacts advices): every failure
 * leaves the controller as a typed exception and reaches the client as
 * `application/problem+json` with the contract code inside
 * `errors: map<string, string[]>` — codes and field names only, never a
 * connectionId or any payload (constitution V).
 *
 * The advice claims exactly its own carrier types, so the global advices
 * never collide; the №36/№38 legs (T016/T032) extend it with
 * `presence_ids_too_many` and the settings codes.
 */
@RestControllerAdvice
class PresenceExceptionHandler {
    /** 400 (openapi №37): the body is absent, connectionId is not set or is not a UUID. */
    @ExceptionHandler(HeartbeatMalformedException::class)
    fun onHeartbeatMalformed(failure: HeartbeatMalformedException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, failure.message ?: MALFORMED_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONNECTION_ID_FIELD to listOf(MALFORMED_REQUEST_CODE))) }

    /** 404 (openapi №37): unknown/expired/foreign connectionId — the client reconnects №18. */
    @ExceptionHandler(HeartbeatConnectionNotFoundException::class)
    fun onHeartbeatConnectionNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, CONNECTION_NOT_FOUND_DETAIL)
            .apply { setProperty(ERRORS_PROPERTY, mapOf(CONNECTION_ID_FIELD to listOf(CONNECTION_NOT_FOUND_CODE))) }

    /**
     * 429 (openapi №37, presence-api.md §2): the per-user heartbeat
     * bucket is exhausted — `Retry-After` carries the integral seconds
     * to the next token; the client keeps the №18 stream and retries in
     * the next interval (the TTL headroom makes the wait safe). The
     * connectionId itself is never echoed (constitution V).
     */
    @ExceptionHandler(PresenceHeartbeatFloodException::class)
    fun onHeartbeatFlood(failure: PresenceHeartbeatFloodException): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
            .body(
                problem(HttpStatus.TOO_MANY_REQUESTS, FLOOD_LIMIT_DETAIL)
                    .apply { setProperty(ERRORS_PROPERTY, mapOf(CONNECTION_ID_FIELD to listOf(FLOOD_LIMIT_CODE))) },
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
        const val CONNECTION_ID_FIELD = "connectionId"
        const val MALFORMED_REQUEST_CODE = "malformed_request"
        const val CONNECTION_NOT_FOUND_CODE = "presence_connection_not_found"
        const val FLOOD_LIMIT_CODE = "flood_limit"
        const val MALFORMED_DETAIL = "connectionId must be present and a UUID"
        const val CONNECTION_NOT_FOUND_DETAIL =
            "The connectionId is not a live registration of the caller (unknown or expired)"
        const val FLOOD_LIMIT_DETAIL =
            "The per-user heartbeat flood limit is exhausted; keep the stream and retry in the next interval"
    }
}
